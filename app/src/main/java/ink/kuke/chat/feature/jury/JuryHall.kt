package ink.kuke.chat.feature.jury

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ConfirmDialog
import ink.kuke.chat.feature.tasks.DialogCard
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/* ───────────────────────── 议事厅 ───────────────────────── */

private fun hallKind(kind: String) = when (kind) {
    "announcement" -> "公告"
    "proposal" -> "提案"
    else -> "讨论"
}

private fun hallStatus(status: String) = when (status) {
    "open" -> "进行中"
    "passed" -> "已通过，待采纳"
    "rejected" -> "未通过"
    "adopted" -> "已采纳"
    "declined" -> "未采纳"
    "closed" -> "已关闭"
    else -> status
}

private val DefaultHallReasons = listOf("人身攻击", "泄露身份或隐私", "讨论具体案件", "广告或无关内容", "其他")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JuryHallPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    // 第一页（内存）：先摆出来，再静默刷新；翻页照旧
    val cache = remember(ctx.graph) { ctx.graph.screenCache.slot<List<HallPost>>("jury.hall") }
    val cached = remember(cache) { cache.get() }
    var rows by remember { mutableStateOf(cached ?: emptyList()) }
    var loading by remember { mutableStateOf(cached == null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf((cached?.size ?: 0) >= 20) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var composing by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        runCatching { ctx.api.hallPosts(0) }
            .onSuccess { rows = it; hasMore = it.size >= 20; error = null; cache.put(it) }
            .onFailure { error = it.userMessage() }
        loading = false
        refreshing = false
    }

    KcPage(
        "议事厅", subtitle = "委员们讨论规则、提出改进的地方", onBack = { ctx.back() },
        actions = {
            if (ctx.status?.isJuror == true) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { composing = true }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.squarePen, tint = colors.text, size = 22.dp, contentDescription = "发帖")
                }
            }
        },
    ) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.fillMaxSize()) {
            when {
                loading -> CenterText("正在加载…")
                error != null && rows.isEmpty() -> ErrorBlock(error!!, { loading = true; tick++ })
                rows.isEmpty() -> CenterText(if (ctx.status?.isJuror == true) "议事厅里还没有帖子\n点右上角发第一个提案或讨论" else "议事厅里还没有帖子")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.id }) { post ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel)
                                .clickable { ctx.go(JuryPage.HallPost(post.id)) }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (post.pinned) Tag("置顶", colors.warn)
                                Tag(hallKind(post.kind), if (post.kind == "proposal") colors.accent else colors.muted)
                                if (post.mine) Tag("我的", colors.ok)
                                Text(post.title, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            when {
                                post.state == "hidden" -> Text("多人举报后已折叠，等待管理员处理", style = type.secondary, color = colors.subtle)
                                !post.content.isNullOrBlank() -> Text(post.content, style = type.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            val vote = post.vote
                            Text(
                                listOfNotNull(
                                    post.author?.displayName,
                                    hallStatus(post.status),
                                    vote?.let { "赞成 ${it.yes} · 反对 ${it.no}" },
                                    "${post.commentCount} 条评论",
                                ).joinToString(" · "),
                                style = type.caption, color = colors.subtle,
                            )
                        }
                    }
                    if (hasMore) {
                        item("more") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                SoftButton(if (loadingMore) "正在加载…" else "加载更多", {
                                    if (loadingMore) return@SoftButton
                                    loadingMore = true
                                    scope.launch {
                                        runCatching { ctx.api.hallPosts(rows.size) }.onSuccess { rows = rows + it; hasMore = it.size >= 20 }
                                        loadingMore = false
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    if (composing) {
        HallEditorDialog(ctx, editing = null, onDismiss = { composing = false }) { created ->
            composing = false
            tick++
            ctx.go(JuryPage.HallPost(created.id))
        }
    }
}

/** 发帖 / 改帖：新帖可选提案或讨论（公告只能在后台发）；改帖只改标题和正文 */
@Composable
private fun HallEditorDialog(ctx: JuryCtx, editing: HallPost?, onDismiss: () -> Unit, onSaved: (HallPost) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val rules = ctx.config.hall ?: HallRules()
    var kind by remember { mutableStateOf(editing?.kind ?: "discussion") }
    var title by remember { mutableStateOf(editing?.title.orEmpty()) }
    var content by remember { mutableStateOf(editing?.content.orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(false) }

    DialogCard(onDismiss, if (editing == null) "发帖" else "修改帖子") {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editing == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("discussion" to "讨论", "proposal" to "提案").forEach { (key, label) ->
                        val on = kind == key
                        Box(
                            Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (on) colors.accentSoft else colors.panelMuted).clickable { kind = key },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = type.secondary, color = if (on) colors.accent else colors.muted, fontWeight = if (on) FontWeight.W600 else FontWeight.W400) }
                    }
                }
                Text(
                    if (kind == "proposal") "提案会开放 ${rules.voteDays} 天表决，在任委员投赞成或反对，达到人数和比例才算通过，之后由管理员决定是否采纳。"
                    else "讨论没有表决，大家可以自由评论。",
                    style = type.caption, color = colors.muted,
                )
            } else if (editing.kind == "proposal") {
                Text("提案有人表决之后就不能再改了。", style = type.caption, color = colors.warn)
            }
            PlainField(title, { title = it.take(rules.titleMax) }, "标题（最多 ${rules.titleMax} 字）")
            PlainField(content, { content = it.take(rules.contentMax) }, "正文：说清楚现状、问题和你的想法", singleLine = false, minHeight = 140.dp)
            Text("${content.length}/${rules.contentMax}", style = type.caption, color = colors.subtle)
            if (rules.guidelines.isNotBlank()) {
                Text(if (showGuide) "收起议事守则" else "查看议事守则", style = type.caption, color = colors.accent, modifier = Modifier.clickable { showGuide = !showGuide })
                if (showGuide) Text(rules.guidelines, style = type.caption, color = colors.muted)
            }
            error?.let { Text(it, style = type.secondary, color = colors.danger) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("取消", onDismiss, Modifier.weight(1f))
            PrimaryButton(if (sending) "提交中…" else if (editing == null) "发布" else "保存", {
                sending = true
                error = null
                scope.launch {
                    runCatching {
                        if (editing == null) ctx.api.hallCreate(kind, title.trim(), content.trim())
                        else ctx.api.hallUpdate(editing.id, title.trim(), content.trim())
                    }.onSuccess { Haptics.confirm(view); onSaved(it) }.onFailure { Haptics.reject(view); error = it.userMessage() }
                    sending = false
                }
            }, Modifier.weight(1f), enabled = !sending && title.isNotBlank() && content.isNotBlank())
        }
    }
}

/** 举报议事厅里的帖子或评论：选理由，「其他」要写说明；交给管理员处理 */
@Composable
private fun HallReportDialog(ctx: JuryCtx, targetType: String, targetId: Long, quote: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val reasons = ctx.config.hall?.reportReasons?.takeIf { it.isNotEmpty() } ?: DefaultHallReasons
    var reason by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val needDetails = reason == "其他"
    val valid = reason != null && (!needDetails || details.trim().length >= 5)

    DialogCard(onDismiss, if (targetType == "post") "举报这个帖子" else "举报这条评论") {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(quote, style = type.secondary, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(10.dp))
            reasons.forEach { item ->
                val on = reason == item
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp)).clickable { reason = item }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(if (on) colors.accent else colors.panelMuted), contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(colors.panel))
                    }
                    Text(item, style = type.body, color = colors.text, modifier = Modifier.padding(start = 10.dp))
                }
            }
            PlainField(details, { details = it.take(500) }, if (needDetails) "请说明具体情况（至少 5 个字）" else "补充说明（选填）", singleLine = false, minHeight = 64.dp)
            Text("举报交给管理员处理，被举报的人不会知道是谁举报的。", style = type.caption, color = colors.subtle)
            error?.let { Text(it, style = type.secondary, color = colors.danger) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("取消", onDismiss, Modifier.weight(1f))
            PrimaryButton(if (sending) "提交中…" else "提交举报", {
                val picked = reason ?: return@PrimaryButton
                sending = true
                scope.launch {
                    runCatching { ctx.api.hallReport(targetType, targetId, picked, details.trim()) }
                        .onSuccess { onDone("已举报，管理员会尽快处理") }
                        .onFailure { error = it.userMessage() }
                    sending = false
                }
            }, Modifier.weight(1f), enabled = valid && !sending, color = colors.danger)
        }
    }
}

@Composable
internal fun JuryHallPostPage(ctx: JuryCtx, postId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var post by remember(postId) { mutableStateOf<HallPost?>(null) }
    var comments by remember(postId) { mutableStateOf<List<HallComment>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<HallComment?>(null) }
    var sending by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteComment by remember { mutableStateOf<HallComment?>(null) }
    var report by remember { mutableStateOf<Triple<String, Long, String>?>(null) }
    val commentMax = ctx.config.hall?.commentMax ?: 1000

    LaunchedEffect(postId, tick) {
        runCatching {
            post = ctx.api.hallPost(postId)
            comments = runCatching { ctx.api.hallComments(postId) }.getOrDefault(comments)
            error = null
        }.onFailure { error = it.userMessage() }
    }

    KcPage(
        "议事厅", onBack = { ctx.back() },
        actions = {
            val current = post
            if (current != null && (current.canEdit || current.canDelete || (!current.mine && !current.reported))) {
                Box {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                        KcIcon(KcIcons.more, tint = colors.text, size = 22.dp, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (current.canEdit) DropdownMenuItem(text = { Text("修改") }, onClick = { menu = false; editing = true })
                        if (current.canDelete) DropdownMenuItem(text = { Text(if (current.kind == "proposal" && current.status == "open") "撤回提案" else "删除帖子", color = colors.danger) }, onClick = { menu = false; confirmDelete = true })
                        if (!current.mine && !current.reported) DropdownMenuItem(text = { Text("举报") }, onClick = { menu = false; report = Triple("post", current.id, current.title) })
                    }
                }
            }
        },
    ) {
        val current = post
        when {
            current == null && error != null -> ErrorBlock(error!!, { tick++ })
            current == null -> CenterText("正在加载…")
            else -> Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.pinned) Tag("置顶", colors.warn)
                    Tag(hallKind(current.kind), colors.accent)
                    Tag(hallStatus(current.status), colors.muted)
                    if (current.reported) Tag("已举报", colors.subtle)
                }
                Text(current.title, style = type.title, color = colors.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    current.author?.let { Avatar(it.avatarUrl, it.displayName, it.id, size = 24.dp) }
                    Text(
                        (current.author?.displayName ?: "管理员") + (current.authorTier?.let { " · ${tierLabel(it)}委员" } ?: "") + " · " + dateTimeText(current.createdAt),
                        style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(current.content ?: "（内容已折叠，等待管理员处理）", style = type.body, color = if (current.content == null) colors.subtle else colors.text)
                current.adminNote?.takeIf { it.isNotBlank() }?.let { Text("管理员说明：$it", style = type.secondary, color = colors.warn) }

                current.vote?.let { vote ->
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("表决 · 赞成 ${vote.yes} · 反对 ${vote.no}", style = type.rowTitle, color = colors.text)
                            Text("至少 ${vote.quorum} 人参与才算数" + (remainingText(vote.endsAt)?.let { " · 还剩 $it" } ?: ""), style = type.caption, color = colors.muted)
                            if (vote.myVote != null) Text("你投了：${if (vote.myVote == "yes") "赞成" else "反对"}（结束前可以改票）", style = type.secondary, color = colors.accent)
                            if (vote.canVote) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SoftButton("反对", {
                                        scope.launch {
                                            runCatching { ctx.api.hallVote(postId, "no") }.onSuccess { post = it; Haptics.confirm(view) }.onFailure { notice = it.userMessage(); Haptics.reject(view) }
                                        }
                                    }, Modifier.weight(1f))
                                    PrimaryButton("赞成", {
                                        scope.launch {
                                            runCatching { ctx.api.hallVote(postId, "yes") }.onSuccess { post = it; Haptics.confirm(view) }.onFailure { notice = it.userMessage(); Haptics.reject(view) }
                                        }
                                    }, Modifier.weight(1f))
                                }
                            } else if (current.status == "open") {
                                Text("只有在任委员可以参与表决", style = type.caption, color = colors.subtle)
                            }
                        }
                    }
                }

                Text("评论 ${comments.count { it.state == "visible" }}", style = type.group, color = colors.muted)
                if (comments.isEmpty()) Text("还没有评论", style = type.secondary, color = colors.subtle)
                // 回复只有一层：根评论 + 挂在它下面的回复
                val roots = comments.filter { it.parentId == null }
                val orphans = comments.filter { c -> c.parentId != null && roots.none { it.id == c.parentId } }
                (roots + orphans).forEach { root ->
                    HallCommentRow(root, reply = false, canReply = current.canComment, onReply = { replyTo = it }, onDelete = { deleteComment = it }, onReport = { report = Triple("comment", it.id, it.content.orEmpty()) })
                    comments.filter { it.parentId == root.id }.forEach { reply ->
                        HallCommentRow(reply, reply = true, canReply = current.canComment, onReply = { replyTo = root }, onDelete = { deleteComment = it }, onReport = { report = Triple("comment", it.id, it.content.orEmpty()) })
                    }
                }
                if (current.canComment) {
                    replyTo?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("回复 ${it.author?.displayName ?: "委员"}", style = type.caption, color = colors.accent, modifier = Modifier.weight(1f))
                            Text("取消", style = type.caption, color = colors.muted, modifier = Modifier.clickable { replyTo = null })
                        }
                    }
                    PlainField(draft, { draft = it.take(commentMax) }, if (replyTo != null) "写回复…" else "写评论…", singleLine = false, minHeight = 56.dp)
                    PrimaryButton(if (sending) "发送中…" else if (replyTo != null) "回复" else "发表评论", {
                        sending = true
                        scope.launch {
                            runCatching { ctx.api.hallReply(postId, draft.trim(), replyTo?.id) }
                                .onSuccess { draft = ""; replyTo = null; tick++; Haptics.confirm(view) }
                                .onFailure { notice = it.userMessage(); Haptics.reject(view) }
                            sending = false
                        }
                    }, Modifier.fillMaxWidth(), enabled = draft.isNotBlank() && !sending)
                } else {
                    Text("在任的风纪委员才能评论和表决", style = type.caption, color = colors.subtle)
                }
                notice?.let { Text(it, style = type.secondary, color = colors.muted) }
            }
        }
    }

    if (editing) post?.let { current ->
        HallEditorDialog(ctx, editing = current, onDismiss = { editing = false }) { saved -> editing = false; post = saved; tick++ }
    }
    if (confirmDelete) post?.let { current ->
        ConfirmDialog(
            if (current.kind == "proposal" && current.status == "open") "撤回这个提案？" else "删除这个帖子？",
            "删除后别人就看不到了，评论也会一起消失。", "删除",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    runCatching { ctx.api.hallDelete(current.id) }
                        .onSuccess { Haptics.confirm(view); ctx.graph.screenCache.remove("jury.hall"); ctx.back() }
                        .onFailure { notice = it.userMessage(); Haptics.reject(view) }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    deleteComment?.let { target ->
        ConfirmDialog("删除这条评论？", "删除后别人就看不到了。", "删除", onConfirm = {
            deleteComment = null
            scope.launch {
                runCatching { ctx.api.hallDeleteComment(target.id) }.onSuccess { tick++ }.onFailure { notice = it.userMessage() }
            }
        }, onDismiss = { deleteComment = null })
    }
    report?.let { (targetType, targetId, quote) ->
        HallReportDialog(ctx, targetType, targetId, quote, onDismiss = { report = null }) { message ->
            report = null
            notice = message
            tick++
        }
    }
}

@Composable
private fun HallCommentRow(comment: HallComment, reply: Boolean, canReply: Boolean, onReply: (HallComment) -> Unit, onDelete: (HallComment) -> Unit, onReport: (HallComment) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(
        Modifier.padding(start = if (reply) 24.dp else 0.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (reply) colors.panelMuted else colors.panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            (comment.author?.displayName ?: "委员") + (if (comment.mine) "（我）" else "") + (comment.authorTier?.let { " · ${tierLabel(it)}" } ?: "") + " · " + dateTimeText(comment.createdAt),
            style = type.caption, color = colors.muted,
        )
        when (comment.state) {
            "deleted" -> Text("这条评论已删除", style = type.secondary, color = colors.subtle)
            "hidden" -> Text("这条评论被多人举报后折叠，等待管理员处理", style = type.secondary, color = colors.subtle)
            else -> {
                Text((comment.replyTo?.let { "回复 @${it.displayName}：" } ?: "") + comment.content.orEmpty(), style = type.body, color = colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (canReply) Text("回复", style = type.caption, color = colors.muted, modifier = Modifier.clickable { onReply(comment) })
                    if (comment.mine) Text("删除", style = type.caption, color = colors.danger, modifier = Modifier.clickable { onDelete(comment) })
                    else if (comment.reported) Text("已举报", style = type.caption, color = colors.subtle)
                    else Text("举报", style = type.caption, color = colors.muted, modifier = Modifier.clickable { onReport(comment) })
                }
            }
        }
    }
}
