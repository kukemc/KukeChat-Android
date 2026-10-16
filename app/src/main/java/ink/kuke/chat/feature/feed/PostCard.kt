package ink.kuke.chat.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.theme.KcTheme

/** 一张帖子卡上所有可点的去处，列表页和详情页各自传自己的 */
class PostCardActions(
    val onOpen: (() -> Unit)? = null,
    val onUser: (Long) -> Unit,
    val onLike: () -> Unit,
    val onComment: () -> Unit,
    val onShare: () -> Unit,
    val onTopic: (String) -> Unit,
    val onImage: (List<String>, Int) -> Unit,
    /** 只有自己的动态才有：置顶 / 取消置顶、删除 */
    val onPin: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    /** 自己的动态：编辑 */
    val onEdit: (() -> Unit)? = null,
    /** 转发到动态；为 null 时不出现转发按钮 */
    val onRepost: (() -> Unit)? = null,
    /** 点赞摘要点开「谁赞了」 */
    val onLikes: (() -> Unit)? = null,
)

/**
 * 帖子卡（设计稿 P11）：头部（头像、名字、等级、时间 · 可见性、置顶 / 审核标签）· 正文（链接 / @ / #）·
 * CCW 作品卡 · 图片宫格 · 点赞摘要 · 赞 / 评论 / 分享。detail = true 时是详情页里的整页版式：正文不截断、不套卡片。
 */
@Composable
fun PostCard(post: Post, actions: PostCardActions, modifier: Modifier = Modifier, detail: Boolean = false, headerTrailing: (@Composable () -> Unit)? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val shape = RoundedCornerShape(if (detail) 0.dp else 14.dp)
    val body = Modifier.fillMaxWidth().let { m ->
        if (!detail && actions.onOpen != null) m.clip(shape).background(colors.panel).tap(scale = 0.99f, onClick = actions.onOpen) else m.clip(shape).background(colors.panel)
    }
    Column(modifier.then(body).padding(horizontal = 14.dp, vertical = 12.dp)) {
        PostHeader(post, actions, headerTrailing)

        if (post.content.isNotBlank()) {
            val uri = LocalUriHandler.current
            val text = remember(post.content, colors.accent) {
                postAnnotated(post.content, colors.accent, onTopic = actions.onTopic, onUrl = { runCatching { uri.openUri(it) } })
            }
            Text(
                text,
                style = type.body,
                color = colors.text,
                maxLines = if (detail) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        post.repostOf?.let { ref ->
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(10.dp),
            ) {
                Text(ref.author?.displayName ?: "用户 ${ref.authorId}", style = type.caption, color = colors.muted, fontWeight = FontWeight.W600)
                Text(ref.content.ifBlank { "（图片动态）" }, style = type.secondary, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }

        post.ccwCreations.forEach { CcwCreationCard(it, Modifier.padding(top = 8.dp)) }

        if (post.imageUrls.isNotEmpty()) {
            ImageGrid(post.imageUrls, onClick = { actions.onImage(post.imageUrls, it) }, modifier = Modifier.padding(top = 10.dp))
        }

        if (post.likeCount > 0) {
            Row(
                Modifier.padding(top = 10.dp).let { m -> actions.onLikes?.let { m.clip(RoundedCornerShape(12.dp)).tap(scale = 0.98f, onClick = it) } ?: m },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val likers = post.recentLikes.take(3)
                Box(Modifier.width((22 + (likers.size.coerceAtLeast(1) - 1) * 14).dp).height(22.dp)) {
                    likers.forEachIndexed { index, user ->
                        Avatar(user.avatarUrl, user.displayName, user.id, 22.dp, Modifier.offset(x = (index * 14).dp).clip(CircleShape))
                    }
                }
                Text(likeSummary(post), style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
                if (actions.onLikes != null) KcIcon(KcIcons.chevron, tint = colors.subtle, size = 14.dp, modifier = Modifier.padding(start = 2.dp))
            }
        }

        // 赞 / 评论 / 分享
        Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton(
                icon = if (post.likedByMe) KcIcons.heart.duotone() else KcIcons.heart,
                label = if (post.likeCount > 0) compactCount(post.likeCount) else "赞",
                tint = if (post.likedByMe) colors.danger else colors.muted,
                onClick = actions.onLike,
            )
            ActionButton(KcIcons.message, if (post.commentCount > 0) compactCount(post.commentCount) else "评论", colors.muted, actions.onComment)
            actions.onRepost?.let { ActionButton(KcIcons.forward, "转发", colors.muted, it) }
            ActionButton(KcIcons.share, "分享", colors.muted, actions.onShare)
        }
    }
}

@Composable
private fun PostHeader(post: Post, actions: PostCardActions, trailing: (@Composable () -> Unit)?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var menu by remember { mutableStateOf(false) }
    val author = post.author
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.tap(scale = 0.94f) { actions.onUser(post.authorId) }) {
            Avatar(author?.avatarUrl, post.authorName, post.authorId, 42.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(post.authorName, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                LevelTag(author?.accountLevel ?: 0)
                if (post.pinnedAt != null) {
                    // 置顶：一枚小图钉 + 字，不要整块底色
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KcIcon(KcIcons.pin, tint = colors.warn, size = 12.dp)
                        Text("置顶", color = colors.warn, fontSize = 11.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 2.dp))
                    }
                }
                if (post.moderationStatus == "pending") SmallTag("审核中", colors.warn, colors.warn.copy(alpha = 0.14f))
                if (post.moderationStatus == "rejected") SmallTag("未通过", colors.danger, colors.danger.copy(alpha = 0.14f))
            }
            Text("${postTime(post.createdAt)} · ${visibilityLabel(post.visibility)}", style = type.caption, color = colors.subtle)
        }
        trailing?.invoke()
        if (actions.onDelete != null || actions.onPin != null || actions.onEdit != null) {
            Box {
                CircleIconButton(KcIcons.more, colors.muted, onClick = { menu = true }, size = 36)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    actions.onEdit?.let {
                        DropdownMenuItem(text = { Text("编辑动态") }, onClick = { menu = false; it() })
                    }
                    actions.onPin?.let {
                        DropdownMenuItem(text = { Text(if (post.pinnedAt != null) "取消置顶" else "置顶到我的空间") }, onClick = { menu = false; it() })
                    }
                    actions.onDelete?.let {
                        DropdownMenuItem(text = { Text("删除动态", color = colors.danger) }, onClick = { menu = false; it() })
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).tap(scale = 0.92f, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(icon, tint = tint, size = 20.dp)
        Text(label, style = KcTheme.type.secondary, color = tint, modifier = Modifier.padding(start = 6.dp))
    }
}

/** CCW 作品卡：封面 + 标题 + 作者 + 点赞 / 浏览。点开用浏览器打开作品页 */
@Composable
fun CcwCreationCard(creation: CcwCreation, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val uri = LocalUriHandler.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted)
            .tap(scale = 0.98f) { if (creation.url.isNotBlank()) runCatching { uri.openUri(creation.url) } }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 88.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(colors.fill.copy(alpha = 0.12f))) {
            AsyncImage(
                model = Endpoints.absolute(creation.coverUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(creation.title.ifBlank { "CCW 作品" }, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(creation.authorName.takeIf { it.isNotBlank() }, creation.likeCount?.let { "${compactCount(it)} 赞" }, creation.viewCount?.let { "${compactCount(it)} 浏览" }).joinToString(" · "),
                style = KcTheme.type.caption,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KcIcon(KcIcons.external, tint = colors.subtle, size = 18.dp)
    }
}

/** 图片宫格：1 张给 4:3 的大图，2 / 4 张两列，其余三列；点哪张就从哪张开始看 */
@Composable
fun ImageGrid(urls: List<String>, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val columns = when {
        urls.size == 1 -> 1
        urls.size == 2 || urls.size == 4 -> 2
        else -> 3
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        urls.withIndex().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (index, url) ->
                    Box(
                        Modifier.weight(1f).aspectRatio(if (columns == 1) 4f / 3f else 1f).clip(shape).background(colors.panelMuted)
                            .tap(scale = 0.98f) { onClick(index) },
                    ) {
                        AsyncImage(Endpoints.absolute(url), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                }
                // 末行不满时补空位，保持每格同宽
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
