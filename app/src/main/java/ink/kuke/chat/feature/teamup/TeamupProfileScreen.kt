package ink.kuke.chat.feature.teamup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.feature.feed.CcwCreationCard
import ink.kuke.chat.feature.feed.ErrorBox
import ink.kuke.chat.feature.feed.ImageViewer
import ink.kuke.chat.feature.feed.LoadingBox
import ink.kuke.chat.feature.feed.ShareSheet
import ink.kuke.chat.feature.feed.SmallTag
import ink.kuke.chat.feature.feed.compactCount
import ink.kuke.chat.feature.feed.graphViewModel
import ink.kuke.chat.feature.feed.postTime
import ink.kuke.chat.feature.feed.shareCard
import ink.kuke.chat.feature.feed.tap
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive

private const val COMMENT_MAX = 500

/**
 * 组队名片详情（设计稿 P18）：头部 · 擅长 / 想找队友 · 个人介绍 · 作品配图 · 共创代表作 · 联系备注 · CCW · 留言板，
 * 吸底操作「主页 · 留言 · 组队」。自己的名片多一个「编辑」，编辑页在本页内部全屏切换。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TeamupProfileScreen(graph: AppGraph, profileId: Long) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val vm = graphViewModel("teamup-profile-$profileId") { TeamupProfileViewModel(graph, profileId) }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    var editing by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var celebration by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.notice) {
        if (vm.notice != null) { delay(2600); vm.notice = null }
    }
    LaunchedEffect(vm.celebrate) {
        if (vm.celebrate > 0) { celebration = true; delay(2100); celebration = false }
    }

    if (editing) {
        BackHandler { editing = false }
        TeamupEditorScreen(graph, vm.profile, onClose = { editing = false }, onSaved = { vm.onProfileSaved(it); editing = false })
        return
    }

    val profile = vm.profile
    KcPage(
        title = "组队名片",
        onBack = { nav.back() },
        actions = {
            if (profile != null) {
                Box(Modifier.size(44.dp).tap(scale = 0.9f) { sharing = true }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.share, tint = colors.text, size = 22.dp, contentDescription = "分享")
                }
                if (profile.isSelf || profile.userId == me?.id) {
                    Box(Modifier.size(44.dp).tap(scale = 0.9f) { editing = true }, contentAlignment = Alignment.Center) {
                        KcIcon(KcIcons.edit, tint = colors.text, size = 22.dp, contentDescription = "编辑")
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                vm.loading && profile == null -> LoadingBox()
                profile == null -> ErrorBox(vm.error ?: "名片不存在或已被删除", vm::load)
                else -> Column(Modifier.fillMaxSize().imePadding()) {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { refreshing = true; vm.load(); scope.launch { delay(600); refreshing = false } },
                        modifier = Modifier.weight(1f),
                    ) {
                        ProfileBody(
                            vm = vm,
                            profile = profile,
                            meId = me?.id,
                            onUser = { nav.go(UserRoute(it)) },
                            onImage = { urls, index -> viewer = urls to index },
                        )
                    }
                    BottomActions(
                        vm = vm,
                        profile = profile,
                        composing = composing,
                        onCompose = { composing = it },
                        onProfile = { nav.go(UserRoute(profile.userId)) },
                    )
                }
            }
            vm.notice?.let { message ->
                Text(
                    message, style = KcTheme.type.secondary, color = colors.panel,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(22.dp)).background(colors.text.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (celebration && profile != null) {
                Celebration(
                    meName = me?.displayName ?: "我", meAvatar = me?.avatarUrl, meId = me?.id ?: 0L,
                    otherName = profile.name, otherAvatar = profile.author?.avatarUrl, otherId = profile.userId,
                )
            }
        }
    }

    viewer?.let { (urls, index) -> ImageViewer(urls, index) { viewer = null } }
    if (sharing && profile != null) {
        val card = shareCard(
            "type" to "teamup",
            "profile_id" to profile.id,
            "user_id" to profile.userId,
            "name" to profile.name,
            "avatar_url" to profile.author?.avatarUrl,
            "headline" to profile.headline,
            "status" to profile.status,
            "skills" to buildJsonArray { profile.skills.forEach { add(JsonPrimitive(it.skill)) } },
            "looking_for" to buildJsonArray { profile.lookingFor.forEach { add(JsonPrimitive(it)) } },
            "creation_count" to profile.ccwCreations.size,
            "cover_url" to profile.backgroundUrl,
        )
        ShareSheet(graph, "分享组队名片", "[组队名片] ${profile.name}：${profile.headline}".take(500), card) { sharing = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileBody(vm: TeamupProfileViewModel, profile: TeamupProfile, meId: Long?, onUser: (Long) -> Unit, onImage: (List<String>, Int) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val intro = remember(profile.intro) { stripCcwLinks(profile.intro) }
    val threads = vm.threads
    val author = profile.author

    LazyColumn(Modifier.fillMaxSize()) {
        // 头部
        item("header") {
            Column(Modifier.fillMaxWidth().background(colors.panel)) {
                Box(Modifier.fillMaxWidth().height(120.dp)) {
                    if (profile.backgroundUrl != null) {
                        AsyncImage(Endpoints.absolute(profile.backgroundUrl), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    } else {
                        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.55f), colors.accentSoft))))
                    }
                    // 头像一半压在封面上：画在封面 Box 里、往下偏移，下面留出同样高的空位
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(start = 16.dp).offset(y = 32.dp).tap(scale = 0.94f) { onUser(profile.userId) }
                            .clip(CircleShape).background(colors.panel).padding(3.dp),
                    ) { Avatar(author?.avatarUrl, profile.name, profile.userId, 64.dp) }
                }
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 40.dp, bottom = 14.dp)) {
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(profile.name, style = type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        StatusBadge(profile)
                        if (profile.isFriend && !profile.isSelf) SmallTag("已是好友", colors.accent, colors.accentSoft)
                    }
                    Text(profile.headline, style = type.body, color = colors.text, fontWeight = FontWeight.W600, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("${compactCount(profile.viewCount)} 浏览", style = type.caption, color = colors.muted)
                        Text("${profile.commentCount} 留言", style = type.caption, color = colors.muted)
                        Text("${profile.ccwCreations.size} 代表作", style = type.caption, color = colors.muted)
                    }
                }
            }
        }

        // 擅长 / 想找队友
        if (profile.skills.isNotEmpty() || profile.lookingFor.isNotEmpty()) {
            item("skills") {
                SectionCard("擅长", Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth()) {
                    if (profile.skills.isEmpty()) Text("未填写", style = type.caption, color = colors.subtle)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profile.skills.forEach { item ->
                            val meta = skillMeta(item.skill)
                            Row(
                                Modifier.clip(RoundedCornerShape(16.dp)).background(colors.accentSoft).padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KcIcon(meta.icon, tint = colors.accent, size = 16.dp)
                                Text("${meta.label} · ${levelLabel(item.level)}", style = type.secondary, color = colors.accent, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    if (profile.lookingFor.isNotEmpty()) {
                        Text("想找队友", style = type.group, color = colors.subtle, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            profile.lookingFor.forEach { skill ->
                                val meta = skillMeta(skill)
                                Row(
                                    Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, colors.borderStrong, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    KcIcon(meta.icon, tint = colors.text, size = 16.dp)
                                    Text(meta.label, style = type.secondary, color = colors.text, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (intro.isNotBlank()) {
            item("intro") {
                SectionCard("个人介绍", Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) { MarkdownLite(intro) }
            }
        }

        if (profile.imageUrls.isNotEmpty()) {
            item("images") {
                SectionCard("作品配图", Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
                    ink.kuke.chat.feature.feed.ImageGrid(profile.imageUrls, onClick = { onImage(profile.imageUrls, it) })
                }
            }
        }

        if (profile.ccwCreations.isNotEmpty()) {
            item("works") {
                SectionCard("共创代表作 · ${profile.ccwCreations.size} 个", Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profile.ccwCreations.forEach { CcwCreationCard(it) }
                    }
                }
            }
        }

        if (!profile.contactNote.isNullOrBlank()) {
            item("contact") {
                SectionCard("联系备注", Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
                    Text(profile.contactNote.orEmpty(), style = type.secondary, color = colors.text)
                }
            }
        }

        if (author?.ccwStudentOid != null) {
            item("ccw") {
                val uri = LocalUriHandler.current
                SectionCard("CCW 已绑定", Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
                    Text(author.ccwName ?: author.ccwStudentOid.orEmpty(), style = type.rowTitle, color = colors.text)
                    author.ccwBio?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("关注" to author.ccwFollowing, "粉丝" to author.ccwFollowers, "获赞" to author.ccwLikes, "作品" to author.ccwCreations, "浏览" to author.ccwViews)
                            .forEach { (label, value) -> Text("$label ${value?.let(::compactCount) ?: "-"}", style = type.caption, color = colors.muted) }
                    }
                    Text(
                        "打开 CCW 主页", style = type.secondary, color = colors.accent,
                        modifier = Modifier.padding(top = 8.dp).tap(scale = 0.95f) { runCatching { uri.openUri("https://www.ccw.site/student/${author.ccwStudentOid}") } },
                    )
                }
            }
        }

        // 留言板
        item("board-head") {
            Text(
                "留言板 ${profile.commentCount} 条", style = type.title, color = colors.text,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(colors.panel).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        when {
            vm.commentsError != null && vm.comments.isEmpty() -> item("board-error") {
                ErrorBox(vm.commentsError.orEmpty(), vm::load, Modifier.fillMaxWidth().height(160.dp))
            }
            threads.isEmpty() -> item("board-empty") {
                Text("还没有留言，来抢沙发", style = type.secondary, color = colors.muted, modifier = Modifier.fillMaxWidth().background(colors.panel).padding(28.dp))
            }
            else -> threads.forEach { thread ->
                item("t-${thread.root.id}") {
                    Column(Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 16.dp, vertical = 4.dp)) {
                        BoardComment(thread.root, vm, meId, profile, 34, onUser)
                        if (thread.replies.isNotEmpty()) {
                            Column(Modifier.padding(start = 44.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(horizontal = 10.dp, vertical = 2.dp)) {
                                thread.replies.forEach { BoardComment(it, vm, meId, profile, 24, onUser) }
                            }
                        }
                    }
                }
            }
        }
        item("tail") { Box(Modifier.fillMaxWidth().height(20.dp).background(colors.panel)) }
    }
}

@Composable
private fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = KcTheme.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(14.dp)) {
        Text(title, style = KcTheme.type.group, color = colors.subtle, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun BoardComment(comment: TeamupComment, vm: TeamupProfileViewModel, meId: Long?, profile: TeamupProfile, avatar: Int, onUser: (Long) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var confirm by remember { mutableStateOf(false) }
    val name = comment.author?.displayName ?: "用户 ${comment.authorId}"
    // 自己的名片上，名片主人可以删任何留言；其他人只能删自己的
    val canDelete = comment.authorId == meId || profile.isSelf
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.tap(scale = 0.94f) { onUser(comment.authorId) }) { Avatar(comment.author?.avatarUrl, name, comment.authorId, avatar.dp) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, style = type.secondary, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (comment.authorId == profile.userId) SmallTag("作者", colors.accent, colors.accentSoft)
                if (comment.authorId == meId && comment.moderationStatus == "pending") SmallTag("审核中", colors.warn, colors.warn.copy(alpha = 0.14f))
            }
            val target = comment.parentAuthor?.displayName?.takeIf { comment.parentId != null }
            Text(
                buildAnnotatedString {
                    if (target != null) withStyle(SpanStyle(color = colors.accent)) { append("回复 @$target：") }
                    append(comment.content)
                },
                style = type.body, color = colors.text, modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(postTime(comment.createdAt), style = type.caption, color = colors.subtle)
                Text("回复", style = type.caption, color = colors.muted, modifier = Modifier.tap(scale = 0.94f) { vm.replyTo = comment })
                if (canDelete) Text("删除", style = type.caption, color = colors.muted, modifier = Modifier.tap(scale = 0.94f) { confirm = true })
            }
        }
        Column(Modifier.tap(scale = 0.9f) { vm.toggleLike(comment) }.padding(start = 6.dp, top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            KcIcon(if (comment.likedByMe) KcIcons.heart.duotone() else KcIcons.heart, tint = if (comment.likedByMe) colors.danger else colors.subtle, size = 18.dp)
            if (comment.likeCount > 0) Text("${comment.likeCount}", style = type.caption, color = if (comment.likedByMe) colors.danger else colors.subtle)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("删除这条留言？") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.delete(comment) }) { Text("删除", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

/** 吸底操作：主页 · 留言 · 组队（关系状态：已是好友 / 接受组队 / 组队 / 申请已发送）；点「留言」切成输入条 */
@Composable
private fun BottomActions(vm: TeamupProfileViewModel, profile: TeamupProfile, composing: Boolean, onCompose: (Boolean) -> Unit, onProfile: () -> Unit) {
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(colors.panel).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        vm.replyTo?.let { target ->
            Row(Modifier.fillMaxWidth().background(colors.panelMuted).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("回复 @${target.author?.displayName ?: "用户 ${target.authorId}"}", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(28.dp).tap(scale = 0.9f) { vm.replyTo = null }, contentAlignment = Alignment.Center) { KcIcon(KcIcons.close, tint = colors.muted, size = 16.dp) }
            }
        }
        if (composing || vm.replyTo != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(colors.panelMuted).padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text(if (vm.replyTo != null) "写下你的回复…" else "留下你的留言…", style = KcTheme.type.body, color = colors.subtle)
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= COMMENT_MAX) text = it },
                        textStyle = KcTheme.type.body.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val can = text.isNotBlank() && !vm.sending
                Box(
                    Modifier.padding(start = 10.dp).size(40.dp).tap(enabled = can, scale = 0.9f) { scope.launch { if (vm.send(text)) { text = ""; onCompose(false) } } }
                        .clip(CircleShape).background(if (can) colors.fill else colors.panelMuted),
                    contentAlignment = Alignment.Center,
                ) { KcIcon(KcIcons.send, tint = if (can) colors.onFill else colors.subtle, size = 20.dp) }
                Box(Modifier.padding(start = 4.dp).size(40.dp).tap(scale = 0.9f) { vm.replyTo = null; onCompose(false) }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.close, tint = colors.muted, size = 20.dp)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill("主页", KcIcons.profile, filled = false, enabled = true, modifier = Modifier.weight(1f), onClick = onProfile)
                ActionPill("留言", KcIcons.message, filled = false, enabled = true, modifier = Modifier.weight(1f)) { onCompose(true) }
                when {
                    profile.isSelf -> ActionPill("我的名片", KcIcons.checkCircle, filled = false, enabled = false, modifier = Modifier.weight(1.4f)) {}
                    profile.isFriend -> ActionPill("已是好友", KcIcons.check, filled = false, enabled = false, modifier = Modifier.weight(1.4f)) {}
                    vm.incomingRequestId != null -> ActionPill(if (vm.relationBusy) "处理中…" else "接受组队", KcIcons.userPlus, filled = true, enabled = !vm.relationBusy, modifier = Modifier.weight(1.4f)) { vm.acceptFriend() }
                    vm.requestSent -> ActionPill("申请已发送", KcIcons.clock, filled = false, enabled = false, modifier = Modifier.weight(1.4f)) {}
                    else -> ActionPill(if (vm.relationBusy) "发送中…" else "组队", KcIcons.userPlus, filled = true, enabled = !vm.relationBusy, modifier = Modifier.weight(1.4f)) { vm.requestFriend() }
                }
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, filled: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val fg = if (filled) colors.onFill else if (enabled) colors.text else colors.muted
    Row(
        modifier.height(44.dp).tap(enabled = enabled, scale = 0.96f, onClick = onClick).clip(RoundedCornerShape(12.dp))
            .background(if (filled) colors.fill else colors.panelMuted),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(icon, tint = fg, size = 18.dp)
        Text(label, style = KcTheme.type.rowTitle, color = fg, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 「组队申请已发送」：两个头像之间连线画出来，再迸出一点光（设计稿 TeamupCelebration 的手机版） */
@Composable
private fun Celebration(meName: String, meAvatar: String?, meId: Long, otherName: String, otherAvatar: String?, otherId: Long) {
    val colors = KcTheme.colors
    val progress = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(700))
        pop.animateTo(1f, tween(500))
    }
    Box(Modifier.fillMaxSize().background(colors.scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(colors.panel).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(width = 240.dp, height = 72.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    val y = size.height / 2
                    val startX = 36.dp.toPx()
                    val endX = size.width - 36.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(colors.accent, Color(0xFF7950F2))),
                        start = Offset(startX, y),
                        end = Offset(startX + (endX - startX) * progress.value, y),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    if (pop.value > 0f) {
                        val radius = 10.dp.toPx() + 14.dp.toPx() * pop.value
                        drawCircle(colors.accent.copy(alpha = 0.35f * (1f - pop.value)), radius = radius, center = Offset((startX + endX) / 2, y), style = Stroke(2.dp.toPx()))
                    }
                }
                Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Avatar(meAvatar, meName, meId, 56.dp)
                    Avatar(otherAvatar, otherName, otherId, 56.dp, Modifier.alpha(0.4f + 0.6f * progress.value))
                }
            }
            Text("组队申请已发送", style = KcTheme.type.title, color = colors.text, modifier = Modifier.padding(top = 14.dp))
            Text("等 $otherName 通过后就可以开聊了", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 介绍里的 Markdown 子集：## 标题、- 列表、> 引用、**加粗**、*斜体*、`代码`、[文字](链接)。够看，不求完整 */
@Composable
internal fun MarkdownLite(source: String) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        source.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Box(Modifier.height(4.dp))
                line.startsWith("#") -> Text(inline(line.trimStart('#').trim(), colors.accent), style = type.title, color = colors.text)
                line.startsWith("> ") -> Row {
                    Box(Modifier.width(3.dp).heightIn(min = 18.dp).clip(RoundedCornerShape(2.dp)).background(colors.borderStrong))
                    Text(inline(line.removePrefix("> "), colors.accent), style = type.body, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
                }
                line.startsWith("- ") || line.startsWith("* ") -> Row {
                    Text("•", style = type.body, color = colors.muted, modifier = Modifier.padding(end = 8.dp))
                    Text(inline(line.drop(2), colors.accent), style = type.body, color = colors.text)
                }
                else -> Text(inline(line, colors.accent), style = type.body, color = colors.text)
            }
        }
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[([^\]]+)]\([^)]*\)""")

private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(last, match.range.first))
        val g = match.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = accent.copy(alpha = 0.1f))) { append(g[3]) }
            else -> withStyle(SpanStyle(color = accent)) { append(g[4]) }
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}
