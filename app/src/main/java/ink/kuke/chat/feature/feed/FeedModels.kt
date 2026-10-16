package ink.kuke.chat.feature.feed

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.ui.parseInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 动态、评论、话题、CCW 创作者的数据模型（KukeChat-Server/app/schemas/post.py、user.py）。
 * 只声明界面用得到的字段，其余忽略；这里的类型同时给「主页」「组队」两个目录用。
 */

@Serializable
data class CcwCreation(
    val oid: String = "",
    val url: String = "",
    val title: String = "",
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("author_name") val authorName: String = "",
    @SerialName("author_avatar_url") val authorAvatarUrl: String? = null,
    val version: String? = null,
    @SerialName("view_count") val viewCount: Int? = null,
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("comment_count") val commentCount: Int? = null,
)

@Serializable
data class PostTopic(
    val id: Long = 0,
    val name: String = "",
    @SerialName("post_count") val postCount: Int = 0,
    @SerialName("moderation_status") val moderationStatus: String = "approved",
)

@Serializable
data class PostComment(
    val id: Long,
    @SerialName("post_id") val postId: Long = 0,
    @SerialName("author_id") val authorId: Long = 0,
    val author: User? = null,
    @SerialName("parent_id") val parentId: Long? = null,
    @SerialName("parent_author") val parentAuthor: User? = null,
    val content: String = "",
    @SerialName("moderation_status") val moderationStatus: String = "approved",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
)

@Serializable
data class PostRef(
    val id: Long,
    @SerialName("author_id") val authorId: Long = 0,
    val author: User? = null,
    val content: String = "",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class Post(
    val id: Long,
    @SerialName("author_id") val authorId: Long = 0,
    val author: User? = null,
    val content: String = "",
    @SerialName("ccw_creations") val ccwCreations: List<CcwCreation> = emptyList(),
    val visibility: String = "public",
    @SerialName("moderation_status") val moderationStatus: String = "approved",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("repost_of_id") val repostOfId: Long? = null,
    @SerialName("repost_of") val repostOf: PostRef? = null,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    val topics: List<PostTopic> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
    @SerialName("recent_likes") val recentLikes: List<User> = emptyList(),
) {
    val authorName: String get() = author?.displayName ?: "用户 $authorId"
}

/** 点赞记录（GET /posts/{id}/likes） */
@Serializable
data class PostLike(
    val id: Long = 0,
    @SerialName("user_id") val userId: Long = 0,
    val user: User? = null,
    @SerialName("created_at") val createdAt: String = "",
)

/** 大佬入住里的创作者：已绑定 CCW 的用户 + CCW 侧的统计 */
@Serializable
data class CcwCreator(
    val id: Long,
    val username: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("profile_title") val profileTitle: String? = null,
    @SerialName("profile_tagline") val profileTagline: String? = null,
    @SerialName("profile_cover_url") val profileCoverUrl: String? = null,
    @SerialName("ccw_name") val ccwName: String? = null,
    @SerialName("ccw_bio") val ccwBio: String? = null,
    @SerialName("ccw_follower_count") val followerCount: Int = 0,
    @SerialName("ccw_like_count") val likeCount: Int? = null,
    @SerialName("ccw_creation_count") val creationCount: Int? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: ccwName?.takeIf { it.isNotBlank() } ?: username.ifBlank { "用户 $id" }
    val intro: String get() = profileTagline?.takeIf { it.isNotBlank() } ?: profileTitle?.takeIf { it.isNotBlank() } ?: bio?.takeIf { it.isNotBlank() } ?: ccwBio.orEmpty()
}

const val POST_PAGE_SIZE = 20
const val POST_MAX_LENGTH = 2000
const val COMMENT_MAX_LENGTH = 500
const val POST_MAX_IMAGES = 9

// ------------------------------------------------------------------ 文案与格式

/** 和电脑版 formatTime 一致：刚刚 / N分钟前 / N小时前 / 更早显示 MM-dd HH:mm（北京时间） */
fun postTime(iso: String?): String {
    val instant = parseInstant(iso) ?: return iso.orEmpty()
    val seconds = Instant.now().epochSecond - instant.epochSecond
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60}分钟前"
        seconds < 86400 -> "${seconds / 3600}小时前"
        else -> instant.atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }
}

fun visibilityLabel(visibility: String): String = when (visibility) {
    "friends" -> "好友可见"
    "private" -> "仅自己"
    else -> "公开"
}

/** 设计稿的计数写法：1.2k、1.6w，小于一千原样 */
fun compactCount(value: Int): String = when {
    value >= 10000 -> "${"%.1f".format(value / 10000f).removeSuffix(".0")}w"
    value >= 1000 -> "${"%.1f".format(value / 1000f).removeSuffix(".0")}k"
    else -> value.toString()
}

/** 中文计数：不满一万用千分位（9,812），否则 1.2万 / 3.4亿 */
fun wanCount(value: Int): String = when {
    value >= 100_000_000 -> "${"%.1f".format(value / 100_000_000f).removeSuffix(".0")}亿"
    value >= 10000 -> "${"%.1f".format(value / 10000f).removeSuffix(".0")}万"
    else -> "%,d".format(value)
}

/** 「阿夸、白猫 等 57 人赞了」 */
fun likeSummary(post: Post): String {
    val count = post.likeCount
    if (count <= 0) return ""
    val names = post.recentLikes.map { it.displayName }.filter { it.isNotBlank() }.take(5)
    if (names.isEmpty()) return "$count 人赞了"
    return if (count > names.size) "${names.joinToString("、")} 等 $count 人赞了" else "${names.joinToString("、")} $count 人赞了"
}

// ------------------------------------------------------------------ 正文里的链接 / @ / #

private val TOKEN = Regex("""https?://[^\s，。！？）)]+|@[^\s@#，。！？,.!?:：;；]{1,40}|#[\w一-鿿][\w一-鿿-]{0,39}""")
private val TOPIC_IN_TEXT = Regex("""(?:^|\s)#([\w一-鿿][\w一-鿿-]{0,39})""")

/** 把正文拆成文字、链接、@ 好友、# 话题；话题和链接可点（和电脑版 tokenizePostText 同一套规则） */
fun postAnnotated(content: String, linkColor: Color, onTopic: (String) -> Unit, onUrl: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = linkColor))
    var last = 0
    for (match in TOKEN.findAll(content)) {
        val start = match.range.first
        val token = match.value
        // @ 和 # 必须在行首或空白后面，免得把邮箱、颜色码当成提及
        val boundary = token.startsWith("http") || start == 0 || content[start - 1].isWhitespace()
        if (!boundary || start < last) continue
        append(content.substring(last, start))
        when {
            token.startsWith("http") -> withLink(LinkAnnotation.Clickable("url", style) { onUrl(token) }) { append(token) }
            token.startsWith("#") -> withLink(LinkAnnotation.Clickable("topic", style) { onTopic(token.drop(1)) }) { append(token) }
            else -> withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.W500)) { append(token) }
        }
        last = match.range.last + 1
    }
    append(content.substring(last))
}

fun extractTopicNames(content: String): List<String> =
    TOPIC_IN_TEXT.findAll(content).map { it.groupValues[1] }.distinctBy { it.lowercase() }.take(10).toList()

/** 正文里 @ 到的好友 id（按昵称匹配，最多 20 个；和电脑版 extractMentionUserIds 一致） */
fun extractMentionIds(content: String, users: List<User>): List<Long> {
    val ids = LinkedHashSet<Long>()
    for (user in users.distinctBy { it.id }) {
        val name = user.displayName.trim()
        if (name.isEmpty()) continue
        val pattern = Regex("""(^|\s)@${Regex.escape(name)}(?=\s|$|[，。！？,.!?])""")
        if (pattern.containsMatchIn(content)) ids += user.id
        if (ids.size >= 20) break
    }
    return ids.toList()
}

/** 光标前正在输入的 @xxx / #xxx：返回类型与关键字 */
data class InputTrigger(val mention: Boolean, val query: String)

private val TRIGGER = Regex("""(?:^|\s)([@#])([\w一-鿿-]{0,40})$""")

fun inputTrigger(text: String, cursor: Int): InputTrigger? {
    val before = text.substring(0, cursor.coerceIn(0, text.length))
    val match = TRIGGER.find(before) ?: return null
    return InputTrigger(mention = match.groupValues[1] == "@", query = match.groupValues[2])
}

/** 把光标前正在输入的触发词换成选中的那一项，返回新文本和新光标位置 */
fun replaceTrigger(text: String, cursor: Int, token: String): Pair<String, Int> {
    val at = cursor.coerceIn(0, text.length)
    val before = text.substring(0, at)
    val after = text.substring(at)
    val match = TRIGGER.find(before)
    if (match == null) {
        val glue = if (before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) " " else ""
        val head = before + glue + token
        return head + after to head.length
    }
    val group = match.groups[1]!!
    val head = before.substring(0, group.range.first) + token
    return head + after to head.length
}
