package ink.kuke.chat.feature.user

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** 好友此刻在共创世界上玩 / 创作的作品（后端 docs/ccw-social.md）。上报由油猴脚本 KukeChat for CCW 负责，App 只读和显示 */
@Serializable
data class CcwActivity(
    val kind: String = "play",
    @SerialName("ccw_oid") val ccwOid: String? = null,
    val title: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** 过了这个时间还没收到新的推送，就当它已经结束 */
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
private data class UserCcwActivity(
    @SerialName("user_id") val userId: Long,
    val activity: CcwActivity? = null,
)

/** 「正在玩《X》」「正在创作《X》」（同电脑版） */
fun CcwActivity.label(): String = "${when (kind) { "create" -> "正在创作"; "read" -> "正在看"; else -> "正在玩" }}《$title》"

/** 「12 分钟」「1 小时 5 分钟」；不到一分钟「刚刚开始」 */
fun CcwActivity.elapsed(now: Long = System.currentTimeMillis()): String {
    val start = parseInstant(startedAt)?.toEpochMilli() ?: return "刚刚开始"
    val minutes = (now - start) / 60_000
    if (minutes < 1) return "刚刚开始"
    if (minutes < 60) return "$minutes 分钟"
    val hours = minutes / 60
    return if (minutes % 60 != 0L) "$hours 小时 ${minutes % 60} 分钟" else "$hours 小时"
}

/** 点一下去共创世界看同一个作品；「正在看」的文章没有作品页 */
fun CcwActivity.url(): String? = ccwOid?.takeIf { it.isNotBlank() && kind != "read" }?.let { "https://www.ccw.site/detail/$it" }

/**
 * 好友的「正在玩」缓存：登录后拉一次 GET /ccw/activity/friends，之后靠 ccw.activity.updated 推送更新。
 * 服务端不发「结束」：每条带 expires_at，到点还没收到新推送就自己删掉。老后端没有这个接口时什么也不显示。
 */
object CcwActivityCache {
    private val _byUser = MutableStateFlow<Map<Long, CcwActivity>>(emptyMap())
    val byUser: StateFlow<Map<Long, CcwActivity>> = _byUser.asStateFlow()

    private var owner: AppGraph? = null
    private val expiry = ConcurrentHashMap<Long, Job>()

    /** 进主界面时调（每次登录都会进）：第一次挂上实时事件，之后每次都清空重拉，免得看到上一个账号的好友 */
    fun start(graph: AppGraph) {
        if (owner !== graph) {
            owner = graph
            graph.scope.launch {
                graph.realtime.events.collect { event ->
                    if (event.type == "ccw.activity.updated") apply(graph, event.data)
                }
            }
            graph.scope.launch { graph.realtime.reconnected.collect { refresh(graph) } }
        }
        clear()
        graph.scope.launch { refresh(graph) }
    }

    fun clear() {
        expiry.values.forEach { it.cancel() }
        expiry.clear()
        _byUser.value = emptyMap()
    }

    private suspend fun refresh(graph: AppGraph) {
        val list = runCatching { KcJson.decodeFromJsonElement(ListSerializer(UserCcwActivity.serializer()), graph.api.get("/ccw/activity/friends")) }
            .getOrNull() ?: return
        list.forEach { set(graph, it.userId, it.activity) }
    }

    private fun apply(graph: AppGraph, data: JsonObject) {
        val userId = (data["user_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
        val activity = (data["activity"] as? JsonObject)?.let { runCatching { KcJson.decodeFromJsonElement(CcwActivity.serializer(), it) }.getOrNull() }
        set(graph, userId, activity)
    }

    private fun set(graph: AppGraph, userId: Long, activity: CcwActivity?) {
        expiry.remove(userId)?.cancel()
        val expiresAt = parseInstant(activity?.expiresAt)?.toEpochMilli()
        val left = expiresAt?.minus(System.currentTimeMillis())
        if (activity == null || activity.title.isBlank() || left == null || left <= 0) {
            _byUser.update { it - userId }
            return
        }
        _byUser.update { it + (userId to activity) }
        expiry[userId] = graph.scope.launch {
            delay(left)
            expiry.remove(userId)
            _byUser.update { it - userId }
        }
    }
}

/** 同电脑版 .kccw-mark */
val CcwActivityGreen = Color(0xFF22A861)

@Composable
fun rememberCcwActivity(userId: Long?): CcwActivity? {
    val map by CcwActivityCache.byUser.collectAsState()
    return userId?.let { map[it] }
}

/** 名字旁的小手柄：这个人此刻正在共创世界上玩 / 创作。没在玩时什么也不画，昵称旁边哪儿都能放 */
@Composable
fun CcwActivityMark(userId: Long?, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    val activity = rememberCcwActivity(userId) ?: return
    KcIcon(KcIcons.gamepad2, tint = CcwActivityGreen, size = size, modifier = modifier, contentDescription = activity.label())
}
