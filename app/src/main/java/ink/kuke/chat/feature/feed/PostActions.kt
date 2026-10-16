package ink.kuke.chat.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcEmpty
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/* 动态的二级操作：编辑我的动态、转发到动态（可附一句话）、看谁赞了。 */

/** 可见范围三选一（同发表页） */
@Composable
private fun VisibilityPicker(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = KcTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("public", "friends", "private").forEach { item ->
            val on = value == item
            Text(
                visibilityLabel(item), style = KcTheme.type.secondary,
                fontWeight = if (on) FontWeight.W700 else FontWeight.W600,
                color = if (on) colors.accent else colors.muted,
                modifier = Modifier.tap(enabled = enabled, scale = 0.96f) { onChange(item) }
                    .clip(RoundedCornerShape(16.dp)).background(if (on) colors.accentSoft else colors.active)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** 多行输入区：无边框，占位字浅色 */
@Composable
private fun SheetTextArea(value: String, onChange: (String) -> Unit, placeholder: String, enabled: Boolean, minHeight: Int) {
    val colors = KcTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = KcTheme.type.body, color = colors.subtle)
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled,
            textStyle = KcTheme.type.body.copy(color = colors.text), cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 底部抽屉的标题行：标题 + 右侧主按钮 */
@Composable
private fun SheetHeader(title: String, action: String, enabled: Boolean, onAction: () -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KcTheme.type.title, color = colors.text, modifier = Modifier.weight(1f))
        Text(
            action, style = KcTheme.type.rowTitle, color = if (enabled) colors.onFill else colors.subtle,
            modifier = Modifier.tap(enabled = enabled, scale = 0.94f, onClick = onAction)
                .clip(RoundedCornerShape(16.dp)).background(if (enabled) colors.fill else colors.panelMuted)
                .padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

/** @ 联想用的好友：只在打开抽屉时拉一次 */
@Composable
private fun rememberFriends(api: PostsApi): List<User> {
    val friends = remember { mutableStateListOf<User>() }
    LaunchedEffect(Unit) { runCatching { api.friends() }.onSuccess { friends.clear(); friends.addAll(it) } }
    return friends
}

/**
 * 编辑我的动态：正文、可见范围，可以去掉已有的图片（不新增）。转发的动态只改附言。
 * 保存走 PATCH /posts/{id}，审核状态由服务端重新判定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPostSheet(graph: AppGraph, post: Post, onDismiss: () -> Unit, onSaved: (Post) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val api = remember { PostsApi(graph.api) }
    val friends = rememberFriends(api)
    var text by remember { mutableStateOf(post.content) }
    var visibility by remember { mutableStateOf(post.visibility) }
    val images = remember { mutableStateListOf<String>().apply { addAll(post.imageUrls) } }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val repost = post.repostOfId != null
    val changed = text.trim() != post.content.trim() || visibility != post.visibility || images.toList() != post.imageUrls
    val canSave = !saving && changed && (text.isNotBlank() || images.isNotEmpty() || repost)

    fun save() {
        if (!canSave) return
        saving = true
        error = null
        scope.launch {
            val content = text.trim()
            runCatching { api.update(post.id, content, visibility, images.toList(), extractMentionIds(content, friends), extractTopicNames(content)) }
                .onSuccess { onSaved(it) }
                .onFailure { error = it.userMessage() }
            saving = false
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, containerColor = colors.panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetHeader("编辑动态", if (saving) "保存中" else "保存", canSave, ::save)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTextArea(text, { if (it.length <= POST_MAX_LENGTH) text = it }, if (repost) "说点什么（选填）" else "分享点什么…", !saving, 140)
                if (images.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        images.toList().forEach { url ->
                            Box(Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted)) {
                                AsyncImage(Endpoints.absolute(url), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).tap(enabled = !saving, scale = 0.9f) { images.remove(url) }
                                        .clip(CircleShape).background(colors.scrim),
                                    contentAlignment = Alignment.Center,
                                ) { KcIcon(KcIcons.close, tint = Color.White, size = 13.dp) }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VisibilityPicker(visibility, !saving) { visibility = it }
                    Spacer(Modifier.weight(1f))
                    Text("${text.length} / $POST_MAX_LENGTH", style = type.caption, color = colors.subtle)
                }
                Text("改动后会重新进入审核；输入 # 添加话题、@ 提醒好友。", style = type.caption, color = colors.subtle)
                error?.let { Text(it, style = type.secondary, color = colors.danger) }
            }
        }
    }
}

/**
 * 转发到动态：预览原帖（转发一条转发时带的是最初那条），可以附一句话、选可见范围。
 * 想发到聊天的走「分享」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepostSheet(graph: AppGraph, post: Post, onDismiss: () -> Unit, onDone: (Post) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val api = remember { PostsApi(graph.api) }
    val friends = rememberFriends(api)
    var text by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("public") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = post.repostOf
    val previewAuthor = preview?.author?.displayName ?: preview?.let { "用户 ${it.authorId}" } ?: post.authorName
    val previewText = preview?.content ?: post.content
    val previewImages = preview?.imageUrls ?: post.imageUrls

    fun send() {
        if (sending) return
        sending = true
        error = null
        scope.launch {
            val content = text.trim()
            runCatching { api.repost(post.id, content, visibility, extractMentionIds(content, friends), extractTopicNames(content)) }
                .onSuccess { onDone(it) }
                .onFailure { error = it.userMessage() }
            sending = false
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!sending) onDismiss() }, containerColor = colors.panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetHeader("转发到动态", if (sending) "转发中" else "转发", !sending, ::send)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTextArea(text, { if (it.length <= POST_MAX_LENGTH) text = it }, "说点什么吧（选填）", !sending, 88)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    previewImages.firstOrNull()?.let { url ->
                        AsyncImage(
                            Endpoints.absolute(url), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(end = 10.dp).size(52.dp).clip(RoundedCornerShape(8.dp)).background(colors.panel),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("@$previewAuthor", style = type.caption, color = colors.accent, fontWeight = FontWeight.W600, maxLines = 1)
                        Text(previewText.ifBlank { "（图片动态）" }, style = type.secondary, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                VisibilityPicker(visibility, !sending) { visibility = it }
                error?.let { Text(it, style = type.secondary, color = colors.danger) }
            }
        }
    }
}

/** 谁赞了这条动态：最新的在前，点头像进主页 */
@Composable
fun PostLikesScreen(graph: AppGraph, postId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val api = remember { PostsApi(graph.api) }
    var likes by remember { mutableStateOf<List<PostLike>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        runCatching { api.likes(postId) }.onSuccess { likes = it; error = null }.onFailure { error = it.userMessage() }
    }
    val list = likes
    KcPage(title = "赞过的人", subtitle = list?.let { "${it.size} 人" }, onBack = { nav.back() }) {
        when {
            list == null && error != null -> ErrorBox(error.orEmpty(), { tick++ })
            list == null -> LoadingBox()
            list.isEmpty() -> KcEmpty("还没有人赞过")
            else -> LazyColumn(Modifier.fillMaxSize().background(colors.panel), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(list, key = { it.id.takeIf { id -> id != 0L } ?: it.userId }) { like ->
                    val name = like.user?.displayName ?: "用户 ${like.userId}"
                    Row(
                        Modifier.fillMaxWidth().tap(scale = 0.99f) { nav.go(UserRoute(like.userId)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(like.user?.avatarUrl, name, like.userId, 42.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(name, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(postTime(like.createdAt) + " 赞了", style = type.caption, color = colors.subtle)
                        }
                        KcIcon(KcIcons.heart, tint = colors.danger.copy(alpha = 0.7f), size = 16.dp)
                    }
                }
            }
        }
    }
}
