package ink.kuke.chat.feature.contacts

import ink.kuke.chat.core.data.CacheSlot
import ink.kuke.chat.core.data.ScreenCache
import ink.kuke.chat.core.data.slot
import kotlinx.serialization.builtins.ListSerializer

import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.ui.parseInstant
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 联系人、通知、搜索共用的数据模型和请求。
 * 接口形状来自 KukeChat-Client/src/api/{friends,notifications,conversations,users}.ts，只声明用得到的字段。
 */

/** 一个页面的三种数据状态：加载中 / 失败（带可直接展示的文案）/ 就绪 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

@Serializable
data class Friendship(
    val id: Long? = null,
    val user: User? = null,
    val friend: User? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /** 同电脑版 friendUser()：优先 friend，其次 user */
    val other: User? get() = friend ?: user
}

@Serializable
data class FriendRequest(
    val id: Long,
    @SerialName("requester_id") val requesterId: Long = 0,
    @SerialName("receiver_id") val receiverId: Long = 0,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null,
    val requester: User? = null,
    val receiver: User? = null,
)

@Serializable
data class GroupJoinRequest(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long = 0,
    val status: String = "pending",
)

@Serializable
data class TempBlock(
    val id: Long,
    @SerialName("blocked_user_id") val blockedUserId: Long = 0,
    @SerialName("blocked_user") val blockedUser: User? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class OnlineUsers(
    @SerialName("online_count") val onlineCount: Int = 0,
    val users: List<User> = emptyList(),
)

@Serializable
data class GroupInfo(
    val id: Long,
    val title: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("join_mode") val joinMode: String? = null,
    @SerialName("auto_approve") val autoApprove: Boolean = false,
    @SerialName("join_question") val joinQuestion: String? = null,
    @SerialName("join_request_status") val joinRequestStatus: String? = null,
    val joined: Boolean = false,
    val description: String? = null,
    @SerialName("plus_tier") val plusTier: String = "free",
    @SerialName("plus_expires_at") val plusExpiresAt: String? = null,
) {
    val name: String get() = displayTitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: "群聊 $id"
    val needsApproval: Boolean get() = (joinMode == "approval" || joinMode == "question") && !autoApprove
}

@Serializable
data class UnifiedNotification(
    val id: String,
    val category: String = "system",
    val type: String = "",
    val title: String = "",
    val body: String? = null,
    val time: String = "",
    val unread: Boolean = false,
    val pending: Boolean = false,
    val actor: User? = null,
    @SerialName("conversation_id") val conversationId: Long? = null,
    @SerialName("conversation_title") val conversationTitle: String? = null,
    @SerialName("conversation_avatar_url") val conversationAvatarUrl: String? = null,
    @SerialName("post_id") val postId: Long? = null,
    @SerialName("friend_request_id") val friendRequestId: Long? = null,
    @SerialName("group_join_request_id") val groupJoinRequestId: Long? = null,
    val action: String = "none",
    val payload: JsonObject? = null,
)

@Serializable
data class NotificationList(
    val items: List<UnifiedNotification> = emptyList(),
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("pending_count") val pendingCount: Int = 0,
    /** 上次看通知的时间之后新来的条数；旧服务端没有这个字段（null） */
    @SerialName("new_count") val newCount: Int? = null,
    @SerialName("seen_at") val seenAt: String? = null,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class SearchHit(val message: Message, val snippet: String? = null)

@Serializable
data class SearchPage(val items: List<SearchHit> = emptyList())

/** 把「数组」或「{items: []}」统一解成列表（服务端各接口的包装不太一致，同电脑版 asList） */
fun <T> JsonElement.asList(serializer: KSerializer<T>): List<T> {
    val array: JsonArray = when (this) {
        is JsonArray -> this
        is JsonObject -> (this["items"] ?: this["data"]) as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }
    return array.mapNotNull { runCatching { KcJson.decodeFromJsonElement(serializer, it) }.getOrNull() }
}

fun <T> JsonElement.decode(serializer: KSerializer<T>): T = KcJson.decodeFromJsonElement(serializer, this)

fun JsonElement.listOfUsers(): List<User> = asList(User.serializer())

/** 好友列表在页面缓存里的那一格（落盘）：联系人、建群、找人、搜索共用 */
fun ScreenCache.friendsSlot(): CacheSlot<List<Friendship>> = slot("contacts.friends", ListSerializer(Friendship.serializer()))

/** 常用请求。都是挂起函数，在界面的协程里调；失败抛 ApiException / IOException，用 userMessage() 展示。 */
class SocialApi(private val api: Api) {
    suspend fun friends(): List<Friendship> = api.get("/friends").asList(Friendship.serializer())
    suspend fun incomingFriendRequests(): List<FriendRequest> = api.get("/friends/requests/incoming").asList(FriendRequest.serializer())
    suspend fun outgoingFriendRequests(): List<FriendRequest> = api.get("/friends/requests/outgoing").asList(FriendRequest.serializer())
    suspend fun sendFriendRequest(userId: Long, message: String? = null) {
        api.post("/friends/requests", buildJsonObject {
            put("receiver_id", userId)
            if (!message.isNullOrBlank()) put("message", message.trim())
        })
    }
    suspend fun acceptFriendRequest(id: Long) { api.post("/friends/requests/$id/accept") }
    suspend fun rejectFriendRequest(id: Long) { api.post("/friends/requests/$id/reject") }
    /** 忽略：只对自己隐藏，对方那边仍是「等待验证」，不知道被拒 */
    suspend fun ignoreFriendRequest(id: Long) { api.post("/friends/requests/$id/ignore") }
    suspend fun ignoreAllFriendRequests() { api.post("/friends/requests/ignore-all") }
    /** 看过通知了：之后角标只算这之后新来的（其他设备同步） */
    suspend fun markNotificationsSeen() { api.post("/notifications/seen") }
    suspend fun deleteFriend(userId: Long) { api.delete("/friends/$userId") }
    suspend fun tempBlocks(): List<TempBlock> = api.get("/friends/temporary-blocks").asList(TempBlock.serializer())
    suspend fun deleteTempBlock(id: Long) { api.delete("/friends/temporary-blocks/$id") }

    suspend fun notifications(limit: Int = 100): NotificationList = api.get("/notifications?limit=$limit").decode(NotificationList.serializer())

    suspend fun acceptJoinRequest(id: Long) { api.post("/conversations/join-requests/$id/accept", JsonObject(emptyMap())) }
    suspend fun rejectJoinRequest(id: Long) { api.post("/conversations/join-requests/$id/reject", JsonObject(emptyMap())) }
    suspend fun outgoingJoinRequests(): List<GroupJoinRequest> = api.get("/conversations/join-requests/outgoing").asList(GroupJoinRequest.serializer())
    suspend fun joinGroup(id: Long) { api.post("/conversations/$id/join") }
    suspend fun requestJoinGroup(id: Long, message: String? = null, answer: String? = null) {
        api.post("/conversations/$id/join-requests", buildJsonObject {
            if (!message.isNullOrBlank()) put("message", message.trim())
            if (!answer.isNullOrBlank()) put("answer", answer.trim())
        })
    }

    suspend fun searchUsers(query: String): List<User> = api.get("/users/search?q=${enc(query)}").listOfUsers()
    suspend fun recommendedUsers(): List<User> = api.get("/users/recommended").listOfUsers()
    suspend fun searchGroups(query: String): List<GroupInfo> = api.get("/conversations/groups/search?q=${enc(query)}").asList(GroupInfo.serializer())
    suspend fun recommendedGroups(): List<GroupInfo> = api.get("/conversations/groups/recommended").asList(GroupInfo.serializer())
    suspend fun onlineUsers(): OnlineUsers = api.get("/users/online").decode(OnlineUsers.serializer())

    /** 单个会话内搜索（没有全站浏览：全局聊天记录搜索是在「我自己的会话」里逐个搜，同客户端 globalSearch.ts） */
    suspend fun searchConversation(conversationId: Long, query: String, limit: Int = 20): List<SearchHit> =
        api.get("/conversations/$conversationId/messages/search?q=${enc(query)}&category=all&limit=$limit").decode(SearchPage.serializer()).items

    /** 取（必要时创建）和某人的私聊会话，返回会话 id */
    suspend fun openDirect(userId: Long): Long {
        val result = api.post("/conversations/direct", buildJsonObject { put("user_id", userId) })
        return (result as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull ?: error("会话创建失败")
    }

    suspend fun createGroup(title: String, memberIds: List<Long>): Long {
        val body = buildJsonObject {
            put("title", title.trim())
            put("member_ids", JsonArray(memberIds.map { JsonPrimitive(it) }))
        }
        val result = api.post("/conversations/groups", body)
        return (result as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull ?: error("群聊创建失败")
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value.trim(), "UTF-8")
}

/** 在线状态文案（utils/presence.ts）：没有「离线」信息时按在线算，和电脑版一致 */
fun presenceLabel(user: User?): String {
    val custom = user?.presenceText?.trim().orEmpty()
    if (custom.isNotEmpty()) return custom
    return when (user?.presenceStatus) {
        "creating" -> "正在创作"
        "busy" -> "忙碌"
        "dnd" -> "请勿打扰"
        "away" -> "离开"
        "gaming" -> "游戏中"
        "custom" -> "自定义"
        else -> "在线"
    }
}

/** 通知的相对时间：刚刚 / N分钟前 / N小时前 / MM/DD（服务端时间不带时区，按 UTC） */
fun relativeTime(iso: String?): String {
    val instant = parseInstant(iso) ?: return ""
    val minutes = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 60 * 24 -> "${minutes / 60}小时前"
        else -> DateTimeFormatter.ofPattern("MM/dd").format(instant.atZone(ZoneId.systemDefault()))
    }
}

val JsonElement?.stringOrNull: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
val JsonElement?.intValue: Int? get() = (this as? JsonPrimitive)?.intOrNull
