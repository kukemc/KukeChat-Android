package ink.kuke.chat.feature.teamup

import androidx.compose.ui.graphics.vector.ImageVector
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.feed.CcwCreation
import ink.kuke.chat.ui.icons.KcIcons
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.ln
import kotlin.math.max

/** 组队相关的数据模型（KukeChat-Server/app/schemas/teamup.py），字段对照 KukeChat-Client/src/types/api.ts */

@Serializable
data class TeamupAuthor(
    val id: Long = 0,
    val username: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("account_level") val accountLevel: Int = 0,
    @SerialName("ccw_student_oid") val ccwStudentOid: String? = null,
    @SerialName("ccw_name") val ccwName: String? = null,
    @SerialName("ccw_bio") val ccwBio: String? = null,
    @SerialName("ccw_following_count") val ccwFollowing: Int? = null,
    @SerialName("ccw_follower_count") val ccwFollowers: Int? = null,
    @SerialName("ccw_like_count") val ccwLikes: Int? = null,
    @SerialName("ccw_creation_count") val ccwCreations: Int? = null,
    @SerialName("ccw_view_count") val ccwViews: Int? = null,
    @SerialName("ccw_favorite_count") val ccwFavorites: Int? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username.ifBlank { "用户 $id" }
}

@Serializable
data class SkillItem(val skill: String, val level: String = "skilled")

@Serializable
data class TeamupComment(
    val id: Long,
    @SerialName("profile_id") val profileId: Long = 0,
    @SerialName("author_id") val authorId: Long = 0,
    val author: TeamupAuthor? = null,
    @SerialName("parent_id") val parentId: Long? = null,
    @SerialName("parent_author") val parentAuthor: TeamupAuthor? = null,
    val content: String = "",
    @SerialName("moderation_status") val moderationStatus: String = "approved",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
)

@Serializable
data class TeamupProfile(
    val id: Long,
    @SerialName("user_id") val userId: Long = 0,
    val author: TeamupAuthor? = null,
    val headline: String = "",
    val intro: String = "",
    val skills: List<SkillItem> = emptyList(),
    @SerialName("looking_for") val lookingFor: List<String> = emptyList(),
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("background_url") val backgroundUrl: String? = null,
    @SerialName("ccw_creations") val ccwCreations: List<CcwCreation> = emptyList(),
    @SerialName("contact_note") val contactNote: String? = null,
    val status: String = "recruiting",
    @SerialName("moderation_status") val moderationStatus: String = "approved",
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("is_friend") val isFriend: Boolean = false,
    @SerialName("is_self") val isSelf: Boolean = false,
) {
    val name: String get() = author?.displayName ?: "用户 $userId"
    val recruiting: Boolean get() = status == "recruiting"
}

@Serializable
data class TeamupEvent(
    val id: Long = 0,
    val title: String = "",
    val theme: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("seconds_remaining") val secondsRemaining: Long? = null,
    @SerialName("profile_count") val profileCount: Int = 0,
    @SerialName("recruiting_count") val recruitingCount: Int = 0,
)

@Serializable
data class SkillStat(val skill: String, val count: Int = 0)

@Serializable
data class TeamupStats(
    @SerialName("profile_count") val profileCount: Int = 0,
    @SerialName("recruiting_count") val recruitingCount: Int = 0,
    @SerialName("total_profile_count") val totalProfileCount: Int = 0,
    @SerialName("total_recruiting_count") val totalRecruitingCount: Int = 0,
)

// ------------------------------------------------------------------ 技能

/** 技能方向 7 个（设计稿 P17）：程序 code · 美术 image · 策划 sparkles · 音乐音效 volume · 文案剧情 quote · 测试 shieldCheck · 其他 star */
data class SkillMeta(val key: String, val label: String, val icon: ImageVector)

val SkillList: List<SkillMeta> by lazy {
    listOf(
        SkillMeta("program", "程序", KcIcons.code),
        SkillMeta("art", "美术", KcIcons.image),
        SkillMeta("design", "策划", KcIcons.sparkles),
        SkillMeta("music", "音乐音效", KcIcons.volume),
        SkillMeta("writing", "文案剧情", KcIcons.quote),
        SkillMeta("test", "测试", KcIcons.shieldCheck),
        SkillMeta("other", "其他", KcIcons.star),
    )
}

fun skillMeta(key: String): SkillMeta = SkillList.firstOrNull { it.key == key } ?: SkillMeta(key, key, KcIcons.star)

fun levelLabel(level: String): String = when (level) {
    "beginner" -> "入门"
    "expert" -> "精通"
    else -> "熟练"
}

val SkillLevels = listOf("beginner", "skilled", "expert")

// ------------------------------------------------------------------ 推荐打分（同电脑版 teamupModel.ts）

private fun ageHours(profile: TeamupProfile, now: Instant): Double {
    val at = parseInstant(profile.updatedAt.ifBlank { profile.createdAt }) ?: now
    return max(0.0, (now.epochSecond - at.epochSecond) / 3600.0)
}

fun activityScore(profile: TeamupProfile, now: Instant): Double {
    val freshness = 90.0 / (1 + ageHours(profile, now) / 18)
    val activity = ln(profile.viewCount + 2.0) / ln(2.0) * 12 + ln(profile.commentCount * 3 + 2.0) / ln(2.0) * 16
    return freshness + activity + if (profile.recruiting) 24 else 0
}

/** 有自己的名片时，把「对方会我想找的」「对方想找我会的」加进活跃度里 */
fun matchScore(profile: TeamupProfile, mine: TeamupProfile?, now: Instant): Double {
    if (mine == null || profile.isSelf) return activityScore(profile, now)
    val mySkills = mine.skills.map { it.skill }.toSet()
    val wanted = mine.lookingFor.toSet()
    val complementary = profile.skills.count { it.skill in wanted }
    val needMine = profile.lookingFor.count { it in mySkills }
    return activityScore(profile, now) + complementary * 55 + needMine * 44
}

/** 招募倒计时：「9 天 06:21」 */
fun countdown(seconds: Long): String {
    val s = max(0L, seconds)
    val days = s / 86400
    val hours = s % 86400 / 3600
    val minutes = s % 3600 / 60
    return if (days > 0) "$days 天 ${"%02d".format(hours)}:${"%02d".format(minutes)}" else "${"%02d".format(hours)}:${"%02d".format(minutes)}"
}

private val CCW_LINK = Regex("""https?://(?:www\.)?ccw\.site/detail/([0-9a-fA-F]{24})(?:[/?#][^\s]*)?""")

data class CcwRef(val oid: String, val accessKey: String?)

/** 从介绍文字里找出 CCW 作品链接（最多 limit 个，去重），代表作就存在介绍末尾的链接里 */
fun extractCcwRefs(text: String, limit: Int): List<CcwRef> {
    val seen = LinkedHashMap<String, CcwRef>()
    for (match in CCW_LINK.findAll(text)) {
        val oid = match.groupValues[1].lowercase()
        val key = Regex("""[?&]accessKey=([^&\s#]+)""").find(match.value)?.groupValues?.get(1)
        seen.getOrPut("$oid:${key.orEmpty()}") { CcwRef(oid, key) }
        if (seen.size >= limit) break
    }
    return seen.values.toList()
}

/** 介绍里的作品链接另有卡片展示，正文里去掉 */
fun stripCcwLinks(text: String): String =
    text.replace(CCW_LINK, " ").replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()

fun ccwWorkUrl(ref: CcwRef): String =
    "https://www.ccw.site/detail/${ref.oid}" + (ref.accessKey?.let { "?accessKey=$it" } ?: "")

/** 名片卡上的两行摘要：去掉作品链接和 Markdown 记号，只留文字 */
fun introPlain(intro: String): String = stripCcwLinks(intro)
    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), " ")
    .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""[#>*_`~|-]"""), " ")
    .replace(Regex("""\s{2,}"""), " ")
    .trim()
