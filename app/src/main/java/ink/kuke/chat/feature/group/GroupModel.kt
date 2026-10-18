package ink.kuke.chat.feature.group

import ink.kuke.chat.core.data.slot

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.MemberPage
import ink.kuke.chat.feature.chat.a
import ink.kuke.chat.feature.chat.asArr
import ink.kuke.chat.feature.chat.asObj
import ink.kuke.chat.feature.chat.b
import ink.kuke.chat.feature.chat.i
import ink.kuke.chat.feature.chat.l
import ink.kuke.chat.feature.chat.parseMemberPage
import ink.kuke.chat.feature.chat.s
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 群资料页要用的群字段（ConversationRead 里的一部分；核心模型 Conversation 没有描述、加群方式、等级头衔） */
data class GroupDetail(
    val id: Long,
    val title: String,
    val avatarUrl: String?,
    val description: String,
    val category: String?,
    val allMuted: Boolean,
    val rateLimit: Int,
    val joinMode: String,
    val autoApprove: Boolean,
    val joinQuestion: String,
    val levelTitlesEnabled: Boolean,
    val levelTitles: List<Pair<Int, String>>,
    val memberCount: Int,
    val myRole: String,
    val myNickname: String,
    val myRemark: String,
    val pinned: Boolean,
    val doNotDisturb: Boolean,
    val messageSetting: String,
    val pendingRequests: Int,
    /** 群任务：功能开关、谁可以创建（members / admins） */
    val tasksEnabled: Boolean = false,
    val taskCreationPermission: String = "members",
    /** 「正在输入」：auto 只在 200 人以下的群显示 / on 大群也显示 / off 不显示（服务端决定转不转发）。旧服务端没有这个字段 → auto */
    val typingIndicator: String = "auto",
) {
    val manager: Boolean get() = myRole == "owner" || myRole == "admin"
    val owner: Boolean get() = myRole == "owner"
}

fun parseGroupDetail(obj: JsonObject): GroupDetail = GroupDetail(
    id = obj.l("id") ?: 0,
    title = obj.s("display_title")?.takeIf { it.isNotBlank() } ?: obj.s("title").orEmpty().ifBlank { "群聊" },
    avatarUrl = obj.s("avatar_url"),
    description = obj.s("description").orEmpty(),
    category = obj.s("category"),
    allMuted = obj.b("all_muted") == true,
    rateLimit = obj.i("message_rate_limit_per_minute") ?: 0,
    joinMode = obj.s("join_mode") ?: "approval",
    autoApprove = obj.b("auto_approve") == true,
    joinQuestion = obj.s("join_question").orEmpty(),
    levelTitlesEnabled = obj.b("level_titles_enabled") == true,
    levelTitles = obj.a("level_titles")?.mapNotNull {
        val tier = it.asObj() ?: return@mapNotNull null
        (tier.i("min_level") ?: return@mapNotNull null) to (tier.s("title") ?: return@mapNotNull null)
    }.orEmpty(),
    memberCount = obj.i("member_count") ?: 0,
    myRole = obj.s("my_role") ?: "member",
    myNickname = obj.s("my_nickname").orEmpty(),
    myRemark = obj.s("my_remark").orEmpty(),
    pinned = obj.b("my_pinned") == true,
    doNotDisturb = obj.b("my_do_not_disturb") == true,
    messageSetting = obj.s("my_message_setting") ?: "notify",
    pendingRequests = obj.i("pending_join_request_count") ?: 0,
    tasksEnabled = obj.b("tasks_enabled") == true,
    taskCreationPermission = obj.s("task_creation_permission") ?: "members",
    typingIndicator = obj.s("typing_indicator")?.takeIf { it in setOf("auto", "on", "off") } ?: "auto",
)

data class CheckinStatus(val checkedInToday: Boolean, val total: Int, val streak: Int, val best: Int, val level: Int, val exp: Int, val nextExp: Int)

fun parseCheckin(obj: JsonObject) = CheckinStatus(
    checkedInToday = obj.b("checked_in_today") == true,
    total = obj.i("total_checkins") ?: 0,
    streak = obj.i("current_streak") ?: 0,
    best = obj.i("best_streak") ?: 0,
    level = obj.i("level") ?: 1,
    exp = obj.i("level_exp") ?: 0,
    nextExp = obj.i("next_level_exp") ?: 0,
)

/** 签到的结果：ok = 这次签上了；text 是给人看的一句话 */
class CheckinOutcome(val ok: Boolean, val text: String)

/**
 * 群签到（POST /conversations/{id}/checkin，可附带一句 ≤80 字的留言）。群资料页的签到面板和聊天页右上角「签到」共用。
 * 今天已经签过：服务端 409，换成「今天已经签到过了」；其余失败用服务端的话。
 */
suspend fun groupCheckin(graph: AppGraph, conversationId: Long, note: String? = null): CheckinOutcome = try {
    val message = note?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
    val result = graph.api.post(
        "/conversations/$conversationId/checkin",
        JsonObject(mapOf("message" to (message?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull))),
    ).asObj()
    CheckinOutcome(true, "签到成功，连续 ${result?.i("streak_days") ?: 1} 天" + (result?.i("exp_awarded")?.let { exp -> "，经验 +$exp" } ?: ""))
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    CheckinOutcome(false, if ((e as? ink.kuke.chat.core.net.ApiException)?.status == 409) "今天已经签到过了" else e.userMessage())
}

enum class GroupLoad { Loading, Ready, NotMember, Failed }

/**
 * 群资料页状态。服务端没有「取单个会话」接口，群字段取自 GET /conversations（会话列表）里的那一项；
 * 列表里没有 = 我不是这个群的成员（比如从名片点进来）。
 * 上次看到的资料、成员、签到存在内存里：再进来先摆出来，[reload] 照常静默刷新。
 */
class GroupModel(val graph: AppGraph, val id: Long, private val scope: CoroutineScope) {
    var load by mutableStateOf(GroupLoad.Loading)
    var error by mutableStateOf<String?>(null)
    var detail by mutableStateOf<GroupDetail?>(null)
    var members by mutableStateOf<MemberPage?>(null)
    var checkin by mutableStateOf<CheckinStatus?>(null)
    var checkinBusy by mutableStateOf(false)

    private class Snapshot(val detail: GroupDetail, val members: MemberPage?, val checkin: CheckinStatus?)
    private val cache = graph.screenCache.slot<Snapshot>("group.$id")

    init {
        cache.get()?.let { cached ->
            detail = cached.detail
            members = cached.members
            checkin = cached.checkin
            load = GroupLoad.Ready
        }
    }

    private fun saveSnapshot() {
        detail?.let { cache.put(Snapshot(it, members, checkin)) }
    }

    /** 已经有资料（缓存或上次拉的）时不再切回「加载中」，数据回来直接替换 */
    fun reload(quiet: Boolean = false) {
        if (!quiet && detail == null) load = GroupLoad.Loading
        scope.launch {
            runCatching {
                val list = graph.api.get("/conversations").asArr().orEmpty()
                list.firstNotNullOfOrNull { it.asObj()?.takeIf { obj -> obj.l("id") == id } }
            }.onSuccess { obj ->
                if (obj == null) {
                    load = GroupLoad.NotMember
                    graph.screenCache.remove(cache.key)
                } else {
                    detail = parseGroupDetail(obj)
                    load = GroupLoad.Ready
                    saveSnapshot()
                    loadMembers()
                    loadCheckin()
                }
            }.onFailure {
                if (detail == null) {
                    error = it.userMessage()
                    load = GroupLoad.Failed
                }
            }
        }
    }

    private fun loadMembers() {
        scope.launch {
            runCatching { parseMemberPage(graph.api.get("/conversations/$id/members?limit=15&offset=0")) }.onSuccess { members = it; saveSnapshot() }
        }
    }

    private fun loadCheckin() {
        scope.launch {
            runCatching { graph.api.get("/conversations/$id/checkin/me").asObj()?.let(::parseCheckin) }.onSuccess { checkin = it ?: checkin; saveSnapshot() }
        }
    }

    /** 签到：可附带一句留言（≤80 字，服务端 GroupCheckinCreate）。成功后返回经验增量的提示文案；失败返回服务端的话 */
    fun doCheckin(message: String? = null, done: (String) -> Unit) {
        if (checkinBusy) return
        checkinBusy = true
        scope.launch {
            val outcome = groupCheckin(graph, id, message)
            done(outcome.text)
            // 签上了，或者其实今天已经签过（状态过期）：都重读一次
            loadCheckin()
            checkinBusy = false
        }
    }

    /** 提交一个设置 PATCH 请求，成功后重读群资料。失败把服务端的话交给 done */
    fun patch(path: String, body: JsonObject, done: (String?) -> Unit = {}) {
        scope.launch {
            runCatching { graph.api.patch("/conversations/$id$path", body) }
                .onSuccess {
                    graph.conversations.refreshSoon(0)
                    reload(quiet = true)
                    done(null)
                }
                .onFailure { done(it.userMessage()) }
        }
    }

    /** 上传并设置群头像 */
    fun changeAvatar(context: Context, uri: Uri, done: (String?) -> Unit) {
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: error("读不到这张图片")
                val type = context.contentResolver.getType(uri) ?: "image/jpeg"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "avatar.jpg", bytes.toRequestBody(type.toMediaType()))
                    .build()
                val url = graph.api.upload("/uploads/avatar", body).asObj()?.s("url") ?: error("上传没有返回地址")
                graph.api.patch("/conversations/$id/profile", JsonObject(mapOf("avatar_url" to kotlinx.serialization.json.JsonPrimitive(url))))
            }.onSuccess {
                graph.conversations.refreshSoon(0)
                reload(quiet = true)
                done(null)
            }.onFailure { done(it.userMessage()) }
        }
    }
}

internal fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
