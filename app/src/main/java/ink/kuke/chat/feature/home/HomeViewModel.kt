package ink.kuke.chat.feature.home

import ink.kuke.chat.core.data.slot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.feed.CcwCreator
import ink.kuke.chat.feature.feed.decodeItems
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** GET /home 的数据（KukeChat-Server/app/schemas/home.py） */
@Serializable
data class HomeStats(
    @SerialName("registered_users") val registeredUsers: Long = 0,
    val messages: Long = 0,
    @SerialName("messages_last_7_days") val messagesLast7Days: Long = 0,
    val groups: Long = 0,
    @SerialName("public_groups") val publicGroups: Long = 0,
    @SerialName("public_posts") val publicPosts: Long = 0,
    val friendships: Long = 0,
    @SerialName("post_likes") val postLikes: Long = 0,
)

@Serializable
data class HomeGroup(
    val id: Long,
    val title: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val description: String? = null,
    val announcement: String? = null,
    @SerialName("join_mode") val joinMode: String = "open",
    @SerialName("auto_approve") val autoApprove: Boolean = false,
    @SerialName("join_question") val joinQuestion: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    val joined: Boolean = false,
    @SerialName("join_request_status") val joinRequestStatus: String? = null,
    @SerialName("recent_message_count") val recentMessageCount: Int = 0,
    @SerialName("heat_score") val heatScore: Int = 0,
) {
    val name: String get() = displayTitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: "群聊 $id"

    /** 要不要先提交申请（审核制，或者要回答问题；群设了自动通过则直接加） */
    val needsRequest: Boolean get() = (joinMode == "approval" || joinMode == "question") && !autoApprove

    val actionLabel: String
        get() = when {
            joined -> "进入群聊"
            joinRequestStatus == "pending" -> "等待审核"
            needsRequest -> "申请加入"
            else -> "加入群聊"
        }
}

/** 一个状态里存下主页上所有要显示的东西，换一批只换 seed */
class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    var stats by mutableStateOf<HomeStats?>(null)
        private set
    val groups = mutableStateListOf<HomeGroup>()
    val creators = mutableStateListOf<CcwCreator>()
    var creatorTotal by mutableStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 正在提交的群 id；失败信息按群记，显示在那一行下面 */
    val busy = mutableStateMapOf<Long, Boolean>()
    val groupErrors = mutableStateMapOf<Long, String>()

    private var seed = randomSeed()
    private fun randomSeed(): Int = (Math.random() * 2_147_483_647).toInt()

    /** 上次的 /home 原样存在内存：再进主页先摆出来，再静默刷新 */
    private val cache = graph.screenCache.slot<JsonObject>("home")

    init {
        cache.get()?.let { applyHome(it); loading = false }
        load()
    }

    fun load(pull: Boolean = false) {
        if (pull) refreshing = true else loading = stats == null
        error = null
        viewModelScope.launch {
            runCatching { graph.api.get("/home?seed=$seed").jsonObject }
                .onSuccess { applyHome(it); cache.put(it) }
                .onFailure { error = it.userMessage() }
            loading = false
            refreshing = false
        }
    }

    private fun applyHome(obj: JsonObject) {
        stats = obj["stats"]?.let { runCatching { KcJson.decodeFromJsonElement<HomeStats>(it) }.getOrNull() }
        groups.clear()
        obj["groups"]?.decodeItems<HomeGroup>()?.let { groups.addAll(it) }
        creators.clear()
        obj["creators"]?.decodeItems<CcwCreator>()?.let { creators.addAll(it) }
        creatorTotal = obj["creator_total"]?.jsonPrimitive?.longOrNull?.toInt() ?: creators.size
    }

    /** 「换一批」：换一个随机种子再取 */
    fun reshuffle() {
        seed = randomSeed()
        load(pull = true)
    }

    /** 直接加入（开放群 / 自动通过）；成功后返回会话 id 让界面跳进去 */
    fun join(group: HomeGroup, onJoined: (Long) -> Unit) {
        if (busy[group.id] == true) return
        busy[group.id] = true
        groupErrors.remove(group.id)
        viewModelScope.launch {
            runCatching { graph.api.post("/conversations/${group.id}/join") }
                .onSuccess {
                    // 会话列表里要立刻出现这个群
                    runCatching { graph.conversations.refresh() }
                    markJoined(group.id)
                    onJoined(group.id)
                }
                .onFailure { groupErrors[group.id] = it.userMessage() }
            busy.remove(group.id)
        }
    }

    /** 提交加群申请；设了入群问题的群 answer 是答案，否则是申请说明 */
    fun request(group: HomeGroup, answer: String, onDone: () -> Unit) {
        if (busy[group.id] == true) return
        busy[group.id] = true
        groupErrors.remove(group.id)
        viewModelScope.launch {
            val body = buildJsonObject {
                if (group.joinMode == "question") put("answer", answer) else if (answer.isNotBlank()) put("message", answer)
            }
            runCatching { graph.api.post("/conversations/${group.id}/join-requests", body) }
                .onSuccess {
                    val index = groups.indexOfFirst { it.id == group.id }
                    if (index >= 0) groups[index] = groups[index].copy(joinRequestStatus = "pending")
                    onDone()
                }
                .onFailure { groupErrors[group.id] = it.userMessage() }
            busy.remove(group.id)
        }
    }

    private fun markJoined(groupId: Long) {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index >= 0) groups[index] = groups[index].copy(joined = true)
    }
}
