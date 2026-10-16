package ink.kuke.chat.feature.user

import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.contacts.asList
import ink.kuke.chat.feature.contacts.decode
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 用户资料页和名片用的数据。core 的 User 只有聊天够用的字段，资料页要封面、CCW 统计、被赞数，所以单独声明一份。
 * 契约：GET /users/{id}、/users/{id}/likes（docs/chat-features.md 第 6 节）、/posts/users/{id}。
 */

@Serializable
data class Profile(
    val id: Long,
    val username: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("account_level") val accountLevel: Int = 0,
    @SerialName("presence_status") val presenceStatus: String? = null,
    @SerialName("presence_text") val presenceText: String? = null,
    @SerialName("profile_title") val profileTitle: String? = null,
    @SerialName("profile_tagline") val profileTagline: String? = null,
    @SerialName("profile_status") val profileStatus: String? = null,
    @SerialName("profile_location") val profileLocation: String? = null,
    @SerialName("profile_interests") val profileInterests: String? = null,
    @SerialName("profile_cover_url") val profileCoverUrl: String? = null,
    @SerialName("ccw_student_oid") val ccwStudentOid: String? = null,
    @SerialName("ccw_following_count") val ccwFollowing: Int? = null,
    @SerialName("ccw_follower_count") val ccwFollowers: Int? = null,
    @SerialName("ccw_like_count") val ccwLikes: Int? = null,
    @SerialName("profile_like_count") val profileLikeCount: Int? = null,
    @SerialName("jury_badge") val juryBadge: String? = null,
    @SerialName("is_birthday_today") val isBirthdayToday: Boolean = false,
    /** 心意值（收到的礼物花了送礼人多少币）；旧后端没有这个字段 */
    val charm: Int? = null,
    /** 装备着的商店装饰：{ 槽位: { sku, style } } */
    val cosmetics: JsonObject? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username.ifBlank { "用户 $id" }
    val ccwBound: Boolean get() = !ccwStudentOid.isNullOrBlank()
}

@Serializable
data class ProfilePost(
    val id: Long,
    val content: String = "",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    val visibility: String = "public",
    @SerialName("moderation_status") val moderationStatus: String = "approved",
    @SerialName("pinned_at") val pinnedAt: String? = null,
)

@Serializable
data class PostStats(
    @SerialName("post_count") val postCount: Int = 0,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
)

@Serializable
data class LikeState(
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("today_count") val todayCount: Int = 0,
    @SerialName("liked_today") val likedToday: Boolean = false,
    @SerialName("can_like") val canLike: Boolean = false,
    val reason: String? = null,
    @SerialName("left_today") val leftToday: Int? = null,
)

@Serializable
data class GroupMember(
    @SerialName("user_id") val userId: Long = 0,
    val role: String = "member",
    val muted: Boolean = false,
    @SerialName("muted_until") val mutedUntil: String? = null,
    val nickname: String? = null,
    val title: String? = null,
) {
    /** 同电脑版 isMuted：muted 且（没有截止时间或截止时间还没到） */
    val isMuted: Boolean
        get() {
            if (!muted) return false
            val until = parseInstant(mutedUntil) ?: return true
            return until.isAfter(Instant.now())
        }
}

@Serializable
data class ReportReason(val reason: String = "", @SerialName("details_required") val detailsRequired: Boolean = false)

@Serializable
data class ReportOptions(
    val reasons: List<ReportReason> = emptyList(),
    @SerialName("details_min_length") val detailsMinLength: Int = 10,
)

val FallbackReportOptions = ReportOptions(
    reasons = listOf("垃圾广告", "辱骂骚扰", "色情低俗", "违法违规", "诈骗或钓鱼").map { ReportReason(it) } + ReportReason("其他", true),
    detailsMinLength = 10,
)

/** 资料页和名片的请求。失败抛 ApiException / IOException，界面用 userMessage() 展示。 */
class UserApi(private val api: Api) {
    suspend fun profile(id: Long): Profile = api.get("/users/$id").decode(Profile.serializer())
    suspend fun postStats(id: Long): PostStats = api.get("/posts/users/$id/stats").decode(PostStats.serializer())
    suspend fun posts(id: Long, limit: Int = 20): List<ProfilePost> = api.get("/posts/users/$id?limit=$limit").asList(ProfilePost.serializer())

    /** 老后端没有资料点赞：404 或形状不对都当「没有这个功能」，返回 null */
    suspend fun likeState(id: Long): LikeState? = runCatching { parseLike(api.get("/users/$id/likes")) }.getOrNull()
    suspend fun like(id: Long): LikeState = parseLike(api.post("/users/$id/likes")) ?: error("点赞失败")

    private fun parseLike(element: JsonElement): LikeState? {
        val obj = element as? JsonObject ?: return null
        if (obj["like_count"] == null || obj["like_count"] is JsonNull) return null
        return KcJson.decodeFromJsonElement(LikeState.serializer(), obj)
    }

    suspend fun poke(conversationId: Long, userId: Long) {
        api.post("/conversations/$conversationId/members/$userId/poke")
    }

    suspend fun reportOptions(): ReportOptions =
        runCatching { api.get("/reports/options").decode(ReportOptions.serializer()) }.getOrNull()?.takeIf { it.reasons.isNotEmpty() } ?: FallbackReportOptions

    suspend fun reportUser(userId: Long, reason: String, details: String?, conversationId: Long?) {
        api.post("/reports", buildJsonObject {
            put("target_type", "user")
            put("target_id", userId)
            put("reported_user_id", userId)
            put("reason", reason)
            if (!details.isNullOrBlank()) put("details", details.trim())
            if (conversationId != null) put("conversation_id", conversationId)
        })
    }

    suspend fun member(conversationId: Long, userId: Long): GroupMember =
        api.get("/conversations/$conversationId/members/$userId").decode(GroupMember.serializer())

    suspend fun setMute(conversationId: Long, userId: Long, minutes: Long?): GroupMember {
        val body = buildJsonObject {
            put("muted", minutes != null)
            if (minutes != null) put("muted_until", Instant.now().plusSeconds(minutes * 60).toString()) else put("muted_until", JsonNull)
        }
        return api.patch("/conversations/$conversationId/members/$userId/mute", body).decode(GroupMember.serializer())
    }

    suspend fun setRole(conversationId: Long, userId: Long, role: String): GroupMember =
        api.patch("/conversations/$conversationId/members/$userId/role", buildJsonObject { put("role", role) }).decode(GroupMember.serializer())

    suspend fun setTitle(conversationId: Long, userId: Long, title: String?): GroupMember =
        api.patch(
            "/conversations/$conversationId/members/$userId/title",
            buildJsonObject { if (title.isNullOrBlank()) put("title", JsonNull) else put("title", JsonPrimitive(title.trim())) },
        ).decode(GroupMember.serializer())

    suspend fun removeMember(conversationId: Long, userId: Long) {
        api.delete("/conversations/$conversationId/members/$userId")
    }
}

/** 资料点赞不可点时的原因（契约 6.3 reason） */
fun likeReasonText(reason: String?): String = when (reason) {
    "self" -> "不能给自己点赞"
    "bot" -> "不能给机器人点赞"
    "muted" -> "被禁言期间不能点赞"
    "liked_today" -> "今天已经赞过了，明天再赞"
    "limit" -> "今天点赞的人数到上限了"
    else -> "暂时不能点赞"
}

fun roleLabel(role: String): String = when (role) {
    "owner" -> "群主"
    "admin" -> "管理员"
    else -> "成员"
}
