package ink.kuke.chat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 接口数据（KukeChat-Server/app/schemas）。只声明界面用得到的字段，其余忽略（KcJson.ignoreUnknownKeys）。
 * 字段名和接口返回一致，用 @SerialName 对上蛇形命名。
 */

@Serializable
data class User(
    val id: Long,
    val username: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("account_level") val accountLevel: Int = 0,
    @SerialName("presence_status") val presenceStatus: String? = null,
    @SerialName("presence_text") val presenceText: String? = null,
    @SerialName("profile_status") val profileStatus: String? = null,
    @SerialName("jury_badge") val juryBadge: String? = null,
    @SerialName("is_birthday_today") val isBirthdayToday: Boolean = false,
    /** 装备着的装扮 { 槽: { sku, style } }（docs/shop.md 2.3）；旧后端没有。画之前过 feature/shop/Cosmetics */
    val cosmetics: JsonObject? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username.ifBlank { "用户 $id" }
}

@Serializable
data class Reaction(
    val emoji: String,
    val count: Int = 0,
    @SerialName("reacted_by_me") val reactedByMe: Boolean = false,
    /** 回应了这个表情的人（长按表情回应时列出来） */
    val users: List<User> = emptyList(),
)

@Serializable
data class Message(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("sender_id") val senderId: Long? = null,
    val sender: User? = null,
    @SerialName("sender_display_name") val senderDisplayName: String? = null,
    @SerialName("sender_title") val senderTitle: String? = null,
    @SerialName("sender_level") val senderLevel: Int? = null,
    val type: String = "text",
    val content: String = "",
    val metadata: JsonObject? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("recalled_at") val recalledAt: String? = null,
    val reactions: List<Reaction> = emptyList(),
    val featured: Boolean = false,
    /** 发送时带的随机串，POST 返回和 message.created 原样回显（不落库，历史接口里为 null）：乐观发送靠它认领本地那条 */
    @SerialName("client_nonce") val clientNonce: String? = null,
    /** 语音转出来的文字（有人转过就带上，没转过 / 旧后端为 null）：长按「转文字」直接显示，不再请求 */
    @SerialName("voice_transcript") val voiceTranscript: String? = null,
) {
    val isRecalled: Boolean get() = recalledAt != null
    val senderName: String get() = senderDisplayName?.takeIf { it.isNotBlank() } ?: sender?.displayName ?: "用户 ${senderId ?: 0}"
}

@Serializable
data class LevelTitle(
    @SerialName("min_level") val minLevel: Int = 0,
    val title: String = "",
)

@Serializable
data class Conversation(
    val id: Long,
    val type: String = "direct",
    val title: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val announcement: String? = null,
    @SerialName("all_muted") val allMuted: Boolean = false,
    @SerialName("message_rate_limit_per_minute") val rateLimit: Int = 0,
    @SerialName("is_temporary") val isTemporary: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_message") val lastMessage: Message? = null,
    @SerialName("direct_user") val directUser: User? = null,
    @SerialName("my_role") val myRole: String = "member",
    @SerialName("my_remark") val myRemark: String? = null,
    @SerialName("my_pinned") val pinned: Boolean = false,
    @SerialName("my_do_not_disturb") val doNotDisturb: Boolean = false,
    @SerialName("my_message_setting") val messageSetting: String = "notify",
    @SerialName("pending_join_request_count") val pendingJoinRequests: Int = 0,
    @SerialName("red_packet_pending") val redPacketPending: Boolean = false,
    @SerialName("mention_pending") val mentionPending: String? = null,
    /** 群升级档位（有效档位：free / pro / pro_plus / max），私聊恒为 free（docs/group-plus.md 第 2 节） */
    @SerialName("plus_tier") val plusTier: String = "free",
    @SerialName("plus_expires_at") val plusExpiresAt: String? = null,
    @SerialName("file_default_ttl_hours") val fileDefaultTtlHours: Int = 24,
    /** 等级头衔：开着时，没有专属头衔、也不是群主 / 管理员的成员显示所在等级段的头衔（按 min_level 升序） */
    @SerialName("level_titles_enabled") val levelTitlesEnabled: Boolean? = null,
    @SerialName("level_titles") val levelTitles: List<LevelTitle>? = null,
    /** 群待办（GroupTodo），没有为 null；解析见 feature/groupplus 的 GroupTodo.parse */
    @SerialName("group_todo") val groupTodo: JsonObject? = null,
    /** 好友火花（docs/sparks.md），好友私聊才有；没有可画的为 null */
    val spark: Spark? = null,
) {
    val isGroup: Boolean get() = type == "group"

    /** 「我的设备」会话（docs/self-chat.md）：只有自己，各端自己起名（手机上叫「我的电脑」），没有对方资料 */
    val isSelf: Boolean get() = type == SELF_TYPE

    /** 会话列表上显示的名字：「我的设备」按本端起名；备注 > 服务端算好的显示名 > 群名 > 私聊对方 */
    fun name(): String = if (isSelf) SELF_CHAT_NAME else myRemark?.takeIf { it.isNotBlank() }
        ?: displayTitle?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: directUser?.displayName
        ?: "会话 $id"

    /** 免打扰：设置了免打扰，或者消息设置不是「接收并提醒」 */
    val quiet: Boolean get() = doNotDisturb || messageSetting != "notify"
}

/** 「我的设备」会话的类型名 */
const val SELF_TYPE = "self"

/** 「我的设备」会话在手机上的名字（桌面端叫「我的手机」，见 docs/self-chat.md 1.3） */
const val SELF_CHAT_NAME = "我的电脑"

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val user: User? = null,
)

@Serializable
data class FriendEntry(
    val user: User,
    @SerialName("created_at") val createdAt: String? = null,
)
