package ink.kuke.chat.feature.user

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 好友亲密度与好友成就的数据（契约 KukeChat-Server/docs/intimacy.md，客户端参考 src/api/intimacy.ts）。
 * 数值缺了按 0；KcJson 开了 coerceInputValues，服务端给 null 的非空字段会落到默认值。
 */

@Serializable
data class BadgeRef(
    val series: String = "",
    @SerialName("series_name") val seriesName: String = "",
    val level: Int = 1,
    val name: String = "",
    val tier: Int = 1,
    val icon: String = "",
    @SerialName("unlocked_at") val unlockedAt: String? = null,
)

@Serializable
data class FriendBond(
    val rank: Int = 0,
    val friend: User,
    val intimacy: Int = 0,
    val level: Int = 0,
    @SerialName("level_name") val levelName: String = "",
    @SerialName("today_gained") val todayGained: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0,
    val badge: BadgeRef? = null,
    @SerialName("badge_count") val badgeCount: Int = 0,
    @SerialName("new_badges") val newBadges: Int = 0,
    @SerialName("friends_since") val friendsSince: String? = null,
)

@Serializable
data class FriendBondList(
    val enabled: Boolean = false,
    @SerialName("daily_cap") val dailyCap: Int = 40,
    val items: List<FriendBond> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class IntimacyLevelView(
    val level: Int = 0,
    val name: String = "",
    val threshold: Int = 0,
    @SerialName("next_level") val nextLevel: Int? = null,
    @SerialName("next_name") val nextName: String? = null,
    @SerialName("next_threshold") val nextThreshold: Int? = null,
)

@Serializable
data class IntimacyToday(
    val date: String = "",
    val gained: Int = 0,
    val counted: Int = 0,
    val cap: Int = 40,
    val chat: Int = 0,
    val gift: Int = 0,
    val streak: Int = 0,
    val birthday: Int = 0,
    @SerialName("messages_mine") val messagesMine: Int = 0,
    @SerialName("messages_theirs") val messagesTheirs: Int = 0,
    @SerialName("chat_cap") val chatCap: Int = 0,
    @SerialName("gift_cap") val giftCap: Int = 0,
)

@Serializable
data class IntimacyStats(
    @SerialName("messages_total") val messagesTotal: Int = 0,
    @SerialName("messages_mine") val messagesMine: Int = 0,
    @SerialName("messages_theirs") val messagesTheirs: Int = 0,
    @SerialName("mutual_days") val mutualDays: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0,
    @SerialName("best_streak") val bestStreak: Int = 0,
    @SerialName("night_days") val nightDays: Int = 0,
    @SerialName("dawn_days") val dawnDays: Int = 0,
    @SerialName("weekend_days") val weekendDays: Int = 0,
    @SerialName("best_day_messages") val bestDayMessages: Int = 0,
    @SerialName("gift_count") val giftCount: Int = 0,
    @SerialName("birthday_gifts") val birthdayGifts: Int = 0,
)

@Serializable
data class SeriesLevelView(
    val level: Int = 0,
    val name: String? = null,
    val tier: Int = 1,
    val threshold: Int? = null,
    @SerialName("unlocked_at") val unlockedAt: String? = null,
    @SerialName("is_new") val isNew: Boolean = false,
)

@Serializable
data class SeriesView(
    val key: String = "",
    val hidden: Boolean = false,
    val revealed: Boolean = true,
    val name: String? = null,
    val condition: String? = null,
    val icon: String = "",
    val current: Int? = null,
    val level: Int = 0,
    @SerialName("max_level") val maxLevel: Int = 0,
    @SerialName("next_threshold") val nextThreshold: Int? = null,
    val levels: List<SeriesLevelView> = emptyList(),
)

@Serializable
data class FriendIntimacyDetail(
    val friend: User,
    @SerialName("friends_since") val friendsSince: String? = null,
    @SerialName("friend_days") val friendDays: Int = 0,
    val intimacy: Int = 0,
    val level: IntimacyLevelView = IntimacyLevelView(),
    val rank: Int? = null,
    @SerialName("friend_count") val friendCount: Int = 0,
    val display: String = "auto",
    val badge: BadgeRef? = null,
    val today: IntimacyToday = IntimacyToday(),
    val stats: IntimacyStats = IntimacyStats(),
    @SerialName("badge_count") val badgeCount: Int = 0,
    @SerialName("total_badges") val totalBadges: Int = 0,
    val series: List<SeriesView> = emptyList(),
)

@Serializable
data class IntimacyHistoryDay(
    val date: String = "",
    val gained: Int = 0,
    val chat: Int = 0,
    val gift: Int = 0,
    val streak: Int = 0,
    val birthday: Int = 0,
    val mutual: Boolean = false,
)

@Serializable
data class IntimacyHistory(val items: List<IntimacyHistoryDay> = emptyList())

@Serializable
data class IntimacySource(val key: String = "", val label: String = "", val cap: Int? = null, val detail: String = "")

@Serializable
data class IntimacyRules(
    val enabled: Boolean = false,
    @SerialName("daily_cap") val dailyCap: Int = 40,
    val sources: List<IntimacySource> = emptyList(),
)

/**
 * 好友亲密榜的进程内缓存：名字旁的徽章（FriendBadgeMark）、联系人页、用户页都从这里读，不各自请求。
 * start() 幂等；实时事件 intimacy.updated 直接改对应那一行，好友增删后重拉。
 * 服务端没有这个功能（404）时 available = false，所有徽章位置什么也不画。
 */
object IntimacyCache {
    private val _bonds = MutableStateFlow<Map<Long, FriendBond>>(emptyMap())
    val bonds: StateFlow<Map<Long, FriendBond>> = _bonds.asStateFlow()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var owner: AppGraph? = null

    /** 登录后调一次（联系人页、用户页进入时也会调，重复调没关系） */
    fun start(graph: AppGraph) {
        if (owner === graph) return
        owner = graph
        graph.scope.launch {
            refresh(graph)
            graph.realtime.events.collect { event ->
                when {
                    event.type == "intimacy.updated" -> applyUpdate(event.data)
                    event.type == "intimacy.badge.unlocked" -> applyBadges(event.data)
                    // 后端事件名是 friend_request.accepted；旧写法也认，免得哪天改名漏掉
                    event.type == "intimacy.level.up" || event.type == "friend_request.accepted" ||
                        event.type == "friend.request.accepted" || event.type == "friendship.deleted" -> refresh(graph)
                }
            }
        }
        graph.scope.launch { graph.realtime.reconnected.collect { refresh(graph) } }
    }

    fun clear() {
        owner = null
        _bonds.value = emptyMap()
        _available.value = false
    }

    fun bond(userId: Long?): FriendBond? = if (userId == null) null else _bonds.value[userId]

    suspend fun refresh(graph: AppGraph) {
        try {
            val list = KcJson.decodeFromJsonElement(FriendBondList.serializer(), graph.api.get("/intimacy/friends"))
            _bonds.value = list.items.associateBy { it.friend.id }
            _available.value = true
        } catch (error: ApiException) {
            // 旧后端没有这个功能：当作「不可用」，名字旁什么也不画
            if (error.status == 404) {
                _available.value = false
                _bonds.value = emptyMap()
            }
        } catch (_: Exception) {
            // 网络问题：保留已有缓存
        }
    }

    /** 新解锁成就：只给这一行的成就数 / 未读数加上，详情页进来会再拉 */
    private fun applyBadges(data: JsonObject) {
        val id = (data["friend_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
        val added = (data["badges"] as? kotlinx.serialization.json.JsonArray)?.size ?: return
        _bonds.update { map ->
            val old = map[id] ?: return@update map
            map + (id to old.copy(badgeCount = old.badgeCount + added, newBadges = old.newBadges + added))
        }
    }

    private fun applyUpdate(data: JsonObject) {
        val id = (data["friend_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
        val old = _bonds.value[id] ?: return
        val badge = data["badge"]?.let { runCatching { KcJson.decodeFromJsonElement(BadgeRef.serializer(), it) }.getOrNull() }
        _bonds.update { map ->
            map + (id to old.copy(
                intimacy = (data["intimacy"] as? JsonPrimitive)?.content?.toIntOrNull() ?: old.intimacy,
                level = (data["level"] as? JsonPrimitive)?.content?.toIntOrNull() ?: old.level,
                levelName = (data["level_name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: old.levelName,
                todayGained = (data["today_gained"] as? JsonPrimitive)?.content?.toIntOrNull() ?: old.todayGained,
                badge = badge,
            ))
        }
    }
}

/**
 * 好友名字旁的小徽章。只给用户 id：不是好友、没解锁、选了不挂、服务端没有这个功能，都什么也不画，
 * 所以昵称旁边哪儿都能放。传 graph 时顺便保证缓存已启动。
 */
@Composable
fun FriendBadgeMark(userId: Long?, size: Dp = 16.dp, modifier: Modifier = Modifier, graph: AppGraph? = null) {
    if (graph != null) LaunchedEffect(graph) { IntimacyCache.start(graph) }
    val bonds by IntimacyCache.bonds.collectAsState()
    val badge = userId?.let { bonds[it]?.badge } ?: return
    BadgeGlyph(icon = badge.icon, tier = badge.tier, size = size, modifier = modifier)
}

internal fun putBadgeBody(display: String): JsonObject = buildJsonObject { put("badge", display) }
