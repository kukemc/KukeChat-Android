package ink.kuke.chat.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcEmpty
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 动态详情（设计稿 P12）：完整的帖子、评论按最热 / 最新、楼中楼默认露 2 条、
 * 吸底评论框（@ 联想好友、# 联想话题；审核中的动态禁用评论）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScreen(graph: AppGraph, postId: Long) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val vm = graphViewModel("post-$postId") { PostViewModel(graph, postId) }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var reposting by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.notice) {
        if (vm.notice != null) { delay(2600); vm.notice = null }
    }

    KcPage(title = "动态详情", onBack = { nav.back() }, actions = {
        if (vm.post != null) Box(Modifier.size(44.dp).tap(scale = 0.9f) { sharing = true }, contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.share, tint = colors.text, size = 22.dp, contentDescription = "分享")
        }
    }) {
        val post = vm.post
        when {
            vm.gone -> KcEmpty("这条动态已被删除，或者你没有权限查看")
            vm.loading && post == null -> LoadingBox()
            post == null -> ErrorBox(vm.error ?: "加载失败", vm::load)
            else -> Column(Modifier.fillMaxSize().imePadding()) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { refreshing = true; vm.load(); scope.launch { delay(600); refreshing = false } },
                    modifier = Modifier.weight(1f),
                ) {
                    val own = post.authorId == me?.id
                    val threads = vm.threads
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        item("post") {
                            PostCard(
                                post = post,
                                detail = true,
                                actions = PostCardActions(
                                    onUser = { nav.go(UserRoute(it)) },
                                    onLike = vm::toggleLike,
                                    onComment = { runCatching { focus.requestFocus() } },
                                    onShare = { sharing = true },
                                    onTopic = { nav.go(ink.kuke.chat.ui.shell.TopicRoute(it)) },
                                    onImage = { urls, index -> viewer = urls to index },
                                    onPin = if (own) ({ vm.togglePin() }) else null,
                                    onDelete = if (own) ({ confirmDelete = true }) else null,
                                    onEdit = if (own) ({ editing = true }) else null,
                                    onRepost = if (post.moderationStatus == "approved") ({ reposting = true }) else null,
                                    onLikes = { nav.go(ink.kuke.chat.ui.shell.PostLikesRoute(post.id)) },
                                ),
                                headerTrailing = {
                                    if (!own && !vm.isFriend(post.authorId)) {
                                        val sent = vm.friendRequested
                                        Text(
                                            if (sent) "已申请" else "+ 好友",
                                            style = KcTheme.type.caption,
                                            color = if (sent) colors.muted else colors.accent,
                                            modifier = Modifier.padding(end = 4.dp).tap(enabled = !sent, scale = 0.94f) { vm.sendFriendRequest() }
                                                .clip(RoundedCornerShape(14.dp)).background(if (sent) colors.panelMuted else colors.accentSoft)
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                },
                            )
                        }
                        item("comments-head") { CommentsHeader(post.commentCount, vm.hotFirst) { vm.hotFirst = it } }
                        when {
                            vm.commentsError != null && vm.comments.isEmpty() -> item("comments-error") {
                                ErrorBox(vm.commentsError.orEmpty(), vm::load, Modifier.fillMaxWidth().height(180.dp))
                            }
                            threads.isEmpty() -> item("comments-empty") {
                                Text("还没有评论，来抢个沙发吧", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.fillMaxWidth().background(colors.panel).padding(32.dp))
                            }
                            else -> items(threads, key = { "c-${it.root.id}" }) { thread ->
                                ThreadView(thread, vm, post, me?.id, onUser = { nav.go(UserRoute(it)) }, onReply = { vm.replyTo = it; runCatching { focus.requestFocus() } })
                            }
                        }
                        item("tail") { Box(Modifier.height(24.dp).fillMaxWidth().background(colors.panel)) }
                    }
                }
                CommentBar(vm, post, me?.avatarUrl, me?.displayName ?: "我", me?.id ?: 0L, focus)
            }
        }
        vm.notice?.let { message ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    message, style = KcTheme.type.secondary, color = colors.panel,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(22.dp)).background(colors.text.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    viewer?.let { (urls, index) -> ImageViewer(urls, index) { viewer = null } }
    if (sharing) vm.post?.let { post -> ShareSheet(graph, "分享动态", postShareText(post), postShareCard(post)) { sharing = false } }
    if (editing) vm.post?.let { post ->
        EditPostSheet(graph, post, onDismiss = { editing = false }) { saved -> editing = false; vm.replacePost(saved); vm.notice = "已保存" }
    }
    if (reposting) vm.post?.let { post ->
        RepostSheet(graph, post, onDismiss = { reposting = false }) { reposting = false; vm.notice = "已转发到你的动态" }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条动态？") },
            text = { Text("删除后评论和点赞也会一起消失，无法恢复。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePost { nav.back() } }) { Text("删除", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CommentsHeader(count: Int, hotFirst: Boolean, onSort: (Boolean) -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(colors.panel).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("评论 $count", style = KcTheme.type.title, color = colors.text, modifier = Modifier.weight(1f))
        listOf(true to "最热", false to "最新").forEach { (hot, label) ->
            val on = hotFirst == hot
            Text(
                label,
                style = KcTheme.type.secondary,
                color = if (on) colors.accent else colors.subtle,
                modifier = Modifier.tap(scale = 0.94f) { onSort(hot) }.padding(start = 6.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) colors.accentSoft else colors.panel).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ThreadView(thread: CommentThread, vm: PostViewModel, post: Post, meId: Long?, onUser: (Long) -> Unit, onReply: (PostComment) -> Unit) {
    val colors = KcTheme.colors
    val expanded = vm.isExpanded(thread.root.id)
    val shown = if (expanded) thread.replies else thread.replies.take(2)
    Column(Modifier.fillMaxWidth().background(colors.panel).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)) {
        CommentRow(thread.root, post, vm, meId, avatar = 36, onUser, onReply)
        if (thread.replies.isNotEmpty()) {
            Column(
                Modifier.padding(start = 46.dp, top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                shown.forEach { reply -> CommentRow(reply, post, vm, meId, avatar = 26, onUser, onReply) }
                if (thread.replies.size > 2 || expanded) {
                    Text(
                        if (expanded) "收起回复" else "共 ${thread.replies.size} 条回复，点击查看",
                        style = KcTheme.type.caption,
                        color = colors.accent,
                        modifier = Modifier.tap(scale = 0.97f) { vm.toggleExpanded(thread.root.id) }.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: PostComment,
    post: Post,
    vm: PostViewModel,
    meId: Long?,
    avatar: Int,
    onUser: (Long) -> Unit,
    onReply: (PostComment) -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var confirm by remember { mutableStateOf(false) }
    val name = comment.author?.displayName ?: "用户 ${comment.authorId}"
    val isReply = avatar < 30
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.tap(scale = 0.94f) { onUser(comment.authorId) }) {
            Avatar(comment.author?.avatarUrl, name, comment.authorId, avatar.dp)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, style = type.secondary, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (comment.authorId == post.authorId) SmallTag("作者", colors.accent, colors.accentSoft)
                if (comment.authorId != post.authorId && vm.isFriend(comment.authorId)) SmallTag("好友", colors.ok, colors.ok.copy(alpha = 0.14f))
                if (comment.moderationStatus == "pending") SmallTag("审核中", colors.warn, colors.warn.copy(alpha = 0.14f))
            }
            val replyTarget = if (isReply && comment.parentId != null) comment.parentAuthor?.displayName else null
            val body = remember(comment.content, replyTarget, colors.accent) {
                val prefix = replyTarget?.let { "回复 @$it：" }.orEmpty()
                val annotated = postAnnotated(prefix + comment.content, colors.accent, onTopic = {}, onUrl = { link -> runCatching { uri.openUri(link) } })
                annotated
            }
            Text(body, style = type.body, color = colors.text, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(postTime(comment.createdAt), style = type.caption, color = colors.subtle)
                Text("回复", style = type.caption, color = colors.muted, modifier = Modifier.tap(scale = 0.94f) { onReply(comment) })
                if (comment.authorId == meId) {
                    Text("删除", style = type.caption, color = colors.muted, modifier = Modifier.tap(scale = 0.94f) { confirm = true })
                }
            }
        }
        Column(
            Modifier.tap(scale = 0.9f) { vm.toggleCommentLike(comment) }.padding(start = 6.dp, top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KcIcon(if (comment.likedByMe) KcIcons.heart.duotone() else KcIcons.heart, tint = if (comment.likedByMe) colors.danger else colors.subtle, size = 18.dp)
            if (comment.likeCount > 0) Text(compactCount(comment.likeCount), style = type.caption, color = if (comment.likedByMe) colors.danger else colors.subtle)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("删除这条评论？") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.deleteComment(comment) }) { Text("删除", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

/** 吸底评论框：回复提示条、@ / # 联想、输入框 + 发送；审核中的动态整条禁用 */
@Composable
private fun CommentBar(vm: PostViewModel, post: Post, myAvatar: String?, myName: String, myId: Long, focus: FocusRequester) {
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val blocked = when (post.moderationStatus) {
        "pending" -> "动态审核通过后才能评论"
        "rejected" -> "未通过审核的动态不能评论"
        else -> null
    }
    val trigger = if (blocked == null) inputTrigger(field.text, field.selection.end) else null
    val topics = rememberTopicSuggestions(vm.api, trigger)

    Column(Modifier.fillMaxWidth().background(colors.panel).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        if (trigger != null) {
            SuggestionPopup(
                trigger = trigger,
                friends = vm.friends,
                topics = topics,
                onPickUser = { user ->
                    val (text, cursor) = replaceTrigger(field.text, field.selection.end, "@${user.displayName} ")
                    field = TextFieldValue(text, TextRange(cursor))
                },
                onPickTopic = { name ->
                    val (text, cursor) = replaceTrigger(field.text, field.selection.end, "#$name ")
                    field = TextFieldValue(text, TextRange(cursor))
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        vm.replyTo?.let { target ->
            Row(Modifier.fillMaxWidth().background(colors.panelMuted).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "回复 @${target.author?.displayName ?: "用户 ${target.authorId}"}：${target.content}",
                    style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(28.dp).tap(scale = 0.9f) { vm.replyTo = null }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.close, tint = colors.muted, size = 16.dp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(myAvatar, myName, myId, 32.dp)
            Box(
                Modifier.weight(1f).padding(horizontal = 10.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(colors.panelMuted).padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (field.text.isEmpty()) {
                    Text(
                        blocked ?: if (vm.replyTo != null) "写下你的回复…" else "写下你的评论…",
                        style = KcTheme.type.body, color = colors.subtle,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = { if (it.text.length <= COMMENT_MAX_LENGTH) field = it },
                    enabled = blocked == null,
                    textStyle = KcTheme.type.body.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            val canSend = blocked == null && field.text.isNotBlank() && !vm.sending
            Box(
                Modifier.size(40.dp).tap(enabled = canSend, scale = 0.9f) {
                    scope.launch { if (vm.send(field.text)) field = TextFieldValue("") }
                }.clip(CircleShape).background(if (canSend) colors.fill else colors.panelMuted),
                contentAlignment = Alignment.Center,
            ) {
                KcIcon(KcIcons.send, tint = if (canSend) colors.onFill else colors.subtle, size = 20.dp)
            }
        }
    }
}
