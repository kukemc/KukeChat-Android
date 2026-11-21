package ink.kuke.chat.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.UnreadBadge
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.BotRoute
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LevelRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.NotificationCategoryRoute
import ink.kuke.chat.ui.shell.PostRoute
import ink.kuke.chat.ui.shell.TeamupProfileRoute
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * 通知中心（仿 QQ「联系人 › 新朋友 / 群通知」的结构）：
 *  一级页 = 五个分类入口行（好友 · 群聊 · 动态 · 互动 · 系统，带最新一条摘要和角标）+「待处理」（行内接受 / 拒绝）+「最近」；
 *  点入口进二级页 NotificationCategoryRoute 看这一类的完整列表。
 * 数据放在进程级的 NotificationStore 里：再次进入先显示上次的列表，再静默刷新，不会闪白。
 */

/** 一类通知的入口信息 */
private data class NotifyGroup(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    /** 入口行里没有通知时的说明 */
    val hint: String,
    /** 二级页空列表文案 */
    val empty: String,
)

private val Groups = listOf(
    NotifyGroup("friend", "好友通知", KcIcons.userPlus, Color(0xFF1F6BFF), "好友申请会出现在这里", "还没有好友申请"),
    NotifyGroup("group", "群通知", KcIcons.users, Color(0xFF12A679), "入群申请和审核结果", "还没有群通知"),
    NotifyGroup("post", "动态通知", KcIcons.heart, Color(0xFFF0506E), "赞、评论、转发和 @我", "还没有人和你的动态互动"),
    NotifyGroup("interact", "互动通知", KcIcons.messagesSquare, Color(0xFF7950F2), "组队名片、机器人收到的评论", "暂时没有互动"),
    NotifyGroup("system", "系统通知", KcIcons.bell, Color(0xFFF59F00), "等级、审核结果和群成员变动", "暂时没有系统通知"),
)

private fun groupOf(key: String) = Groups.firstOrNull { it.key == key }

/** 实时事件里哪些会让通知中心要重拉（同电脑版 invalidateQueries(['notifications'])） */
private fun refreshesNotifications(type: String) =
    type.startsWith("friend") || type.startsWith("group.join_request") || type.startsWith("notification") ||
        type.startsWith("post.") || type.startsWith("moderation") || type.startsWith("account.level") ||
        type.startsWith("jury") || type.startsWith("teamup") || type.startsWith("bot")

/** 进程级缓存：最近一次拉到的通知列表 + 本次运行里处理过的申请。按登录账号隔离。 */
private object NotificationStore {
    var list by mutableStateOf<NotificationList?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 处理过的申请：通知 id -> 结果文案 */
    val outcomes = mutableStateMapOf<String, String>()
    /** 正在处理的申请 */
    val busy = mutableStateMapOf<String, Boolean>()
    /**
     * 入群申请的拒绝理由：申请 id -> 理由。统一通知里不带这个字段，
     * 从 GET /conversations/join-requests/outgoing、incoming 里补；自己刚拒绝时直接记下。
     */
    val decisionNotes = mutableStateMapOf<Long, String>()

    private var ownerId: Long? = null
    private val mutex = Mutex()

    private fun bind(graph: AppGraph) {
        val uid = graph.session.session.value?.user?.id
        if (uid != ownerId) {
            ownerId = uid
            list = null
            error = null
            outcomes.clear()
            busy.clear()
            decisionNotes.clear()
        }
    }

    /** 有缓存时失败不打扰（继续显示旧列表）；没有缓存时把错误交给界面 */
    suspend fun refresh(graph: AppGraph) {
        bind(graph)
        mutex.withLock {
            runCatching { SocialApi(graph.api).notifications(100) }
                .onSuccess { list = it; error = null; PendingRequests.apply(it) }
                .onFailure { if (list == null) error = it.userMessage() }
        }
        loadDecisionNotes(graph)
    }

    /** 只有列表里有已拒绝的入群申请时才去拉；拉不到不影响通知本身 */
    private suspend fun loadDecisionNotes(graph: AppGraph) {
        val rejected = list?.items.orEmpty().filter { it.groupJoinRequestId != null && joinStatus(it) == "rejected" }
        if (rejected.isEmpty()) return
        val social = SocialApi(graph.api)
        val requests = buildList {
            if (rejected.any { it.type == "group.join_request.outgoing" }) runCatching { social.outgoingJoinRequests() }.onSuccess { addAll(it) }
            if (rejected.any { it.type == "group.join_request.incoming" }) runCatching { social.incomingJoinRequests() }.onSuccess { addAll(it) }
        }
        requests.forEach { r -> r.decisionNote?.takeIf { it.isNotBlank() }?.let { decisionNotes[r.id] = it } }
    }

    suspend fun decide(graph: AppGraph, n: UnifiedNotification, accept: Boolean, note: String? = null): Result<Unit> {
        val social = SocialApi(graph.api)
        busy[n.id] = true
        val result = runCatching {
            when {
                n.friendRequestId != null -> if (accept) social.acceptFriendRequest(n.friendRequestId) else social.rejectFriendRequest(n.friendRequestId)
                n.groupJoinRequestId != null -> if (accept) social.acceptJoinRequest(n.groupJoinRequestId) else social.rejectJoinRequest(n.groupJoinRequestId, note)
                else -> error("这条通知已经处理过了")
            }
        }
        busy.remove(n.id)
        result.onSuccess {
            outcomes[n.id] = if (accept) (if (n.friendRequestId != null) "已接受" else "已通过") else "已拒绝"
            if (!accept && n.groupJoinRequestId != null && !note.isNullOrBlank()) decisionNotes[n.groupJoinRequestId] = note
            PendingRequests.remove(n.id)
        }
        return result
    }

    /** 忽略一条好友申请：从自己的列表里拿掉，对方那边仍是「等待验证」 */
    suspend fun ignore(graph: AppGraph, n: UnifiedNotification): Result<Unit> {
        val id = n.friendRequestId ?: return Result.failure(IllegalStateException("只有好友申请可以忽略"))
        busy[n.id] = true
        val result = runCatching { SocialApi(graph.api).ignoreFriendRequest(id) }
        busy.remove(n.id)
        result.onSuccess { hide(setOf(n.id)) }
        return result
    }

    /** 全部忽略：所有还没处理的好友申请 */
    suspend fun ignoreAll(graph: AppGraph): Result<Int> {
        val ids = list?.items.orEmpty().filter { actionable(it) && it.friendRequestId != null }.map { it.id }.toSet()
        return runCatching { SocialApi(graph.api).ignoreAllFriendRequests() }.map {
            hide(ids)
            ids.size
        }
    }

    private fun hide(ids: Set<String>) {
        val current = list ?: return
        list = current.copy(items = current.items.filterNot { it.id in ids })
        ids.forEach(PendingRequests::remove)
    }

    /** 「全部已读」只作用于动态通知（接口 /posts/notifications/read）；先在本地把它们标掉，再静默刷新 */
    fun markPostsRead() {
        val current = list ?: return
        val items = current.items.map { if (it.category == "post" && it.unread) it.copy(unread = false) else it }
        list = current.copy(items = items, unreadCount = items.count { it.unread })
    }
}

/** 入群申请通知的状态：payload.status（pending / accepted / rejected），本次运行里自己处理过的以结果为准 */
private fun joinStatus(n: UnifiedNotification): String? =
    if (NotificationStore.outcomes[n.id] == "已拒绝") "rejected"
    else runCatching { n.payload?.get("status")?.jsonPrimitive?.contentOrNull }.getOrNull()

/** 被拒绝的入群申请的理由：优先通知自带的（新服务端可能放进 payload），其次从申请列表补到的 */
private fun decisionNoteOf(n: UnifiedNotification): String? {
    val id = n.groupJoinRequestId ?: return null
    if (joinStatus(n) != "rejected") return null
    val inline = runCatching { n.payload?.get("decision_note")?.jsonPrimitive?.contentOrNull }.getOrNull()
    return (inline ?: NotificationStore.decisionNotes[id])?.trim()?.takeIf { it.isNotBlank() }
}

/** 一条是否还需要我处理（接受 / 拒绝） */
private fun actionable(n: UnifiedNotification) =
    n.pending && NotificationStore.outcomes[n.id] == null && (n.friendRequestId != null || n.groupJoinRequestId != null)

/** 入口角标：待处理 + 未读 */
private fun needsAttention(n: UnifiedNotification) = actionable(n) || (n.unread && NotificationStore.outcomes[n.id] == null)

/** 两个页面共用：首次进入 / 实时事件时静默刷新，处理申请、全部已读 */
private class NotificationController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val view: android.view.View,
    private val toast: ToastState,
) {
    /** 正在填拒绝理由的入群申请（弹框由 rememberNotificationController 画） */
    var rejecting by mutableStateOf<UnifiedNotification?>(null)
    /** 正在看详情的申请（好友申请 / 入群申请）：附言、回答、拒绝理由完整显示，可在里面处理 */
    var detail by mutableStateOf<UnifiedNotification?>(null)

    fun decide(n: UnifiedNotification, accept: Boolean, note: String? = null) {
        if (NotificationStore.busy[n.id] == true) return
        // 拒绝入群申请先问理由（选填）；好友申请照旧直接拒绝
        if (!accept && n.groupJoinRequestId != null && rejecting == null && note == null) {
            rejecting = n
            return
        }
        scope.launch {
            NotificationStore.decide(graph, n, accept, note)
                .onSuccess {
                    Haptics.confirm(view)
                    if (accept && n.friendRequestId != null) graph.conversations.refreshSoon()
                }
                .onFailure {
                    Haptics.reject(view)
                    toast.show(it.userMessage())
                }
        }
    }

    fun ignore(n: UnifiedNotification) {
        if (NotificationStore.busy[n.id] == true) return
        scope.launch {
            NotificationStore.ignore(graph, n)
                .onSuccess { toast.show("已忽略，对方不会收到通知") }
                .onFailure { toast.show(if ((it as? ink.kuke.chat.core.net.ApiException)?.status == 404) "服务器暂未支持忽略，请先更新服务端" else it.userMessage()) }
        }
    }

    fun ignoreAll() {
        scope.launch {
            NotificationStore.ignoreAll(graph)
                .onSuccess { toast.show(if (it > 0) "已忽略 $it 条好友申请" else "没有待处理的好友申请") }
                .onFailure { toast.show(if ((it as? ink.kuke.chat.core.net.ApiException)?.status == 404) "服务器暂未支持忽略，请先更新服务端" else it.userMessage()) }
        }
    }

    fun readAll() {
        scope.launch {
            runCatching { graph.api.post("/posts/notifications/read") }
                .onSuccess {
                    NotificationStore.markPostsRead()
                    toast.show("已全部标为已读")
                    NotificationStore.refresh(graph)
                }
                .onFailure { toast.show(it.userMessage()) }
        }
    }
}

@Composable
private fun rememberNotificationController(graph: AppGraph, toast: ToastState): NotificationController {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val controller = remember(graph) { NotificationController(graph, scope, view, toast) }
    // 看过通知中心：角标只提醒一次，之后只算新来的
    LaunchedEffect(graph) { NotificationStore.refresh(graph); PendingRequests.markSeen(graph) }
    LaunchedEffect(graph) {
        graph.realtime.events.filter { refreshesNotifications(it.type) }.collectLatest {
            delay(300)
            NotificationStore.refresh(graph)
        }
    }
    controller.detail?.let { n ->
        val nav = LocalAppNav.current
        RequestDetailSheet(
            n = n,
            onDismiss = { controller.detail = null },
            onAccept = { controller.detail = null; controller.decide(n, true) },
            onReject = { controller.detail = null; controller.decide(n, false) },
            onIgnore = { controller.detail = null; controller.ignore(n) },
            onOpen = { route -> controller.detail = null; nav.go(route) },
        )
    }
    controller.rejecting?.let { n ->
        ink.kuke.chat.feature.chat.RejectJoinRequestDialog(
            name = n.actor?.displayName,
            onConfirm = { note -> controller.rejecting = null; controller.decide(n, false, note ?: "") },
            onDismiss = { controller.rejecting = null },
        )
    }
    return controller
}

// ───────────────────────────── 一级页 ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val controller = rememberNotificationController(graph, toast)
    var refreshing by remember { mutableStateOf(false) }

    val list = NotificationStore.list
    val pendingItems = list?.items?.filter { it.pending && (it.friendRequestId != null || it.groupJoinRequestId != null) }.orEmpty()
    val pendingCount = pendingItems.count { actionable(it) }
    val unreadPosts = list?.items?.count { it.category == "post" && it.unread } ?: 0

    KcPage(
        title = "通知中心",
        subtitle = if (pendingCount > 0) "$pendingCount 条待处理" else null,
        onBack = { nav.back() },
        actions = { if (unreadPosts > 0) ReadAllAction { controller.readAll() } },
    ) {
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; NotificationStore.refresh(graph); refreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                val error = NotificationStore.error
                when {
                    list == null && error != null -> ErrorRetry(error, onRetry = { scope.launch { NotificationStore.refresh(graph) } })
                    list == null -> LoadingBox("正在加载通知…")
                    else -> {
                        val pendingIds = pendingItems.mapTo(HashSet()) { it.id }
                        val recent = aggregate(list.items.filter { it.id !in pendingIds })
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                            itemsIndexed(Groups, key = { _, g -> "entry:${g.key}" }) { index, g ->
                                val items = list.items.filter { it.category == g.key }
                                EntryRow(
                                    group = g,
                                    latest = items.firstOrNull(),
                                    badge = items.count { needsAttention(it) },
                                    divider = index < Groups.lastIndex,
                                ) { nav.go(NotificationCategoryRoute(g.key)) }
                            }
                            if (pendingItems.isNotEmpty()) {
                                item(key = "label:pending") { SectionLabel("待处理") }
                                notificationRows(pendingItems.map { Entry.Single(it) }, controller, prefix = "p:", onOpen = { nav.go(it) })
                            }
                            item(key = "label:recent") { SectionLabel("最近") }
                            if (recent.isEmpty()) {
                                item(key = "recent:empty") { EmptyBlock("暂时没有新的通知") }
                            } else {
                                notificationRows(recent, controller, prefix = "r:", onOpen = { nav.go(it) })
                            }
                        }
                    }
                }
            }
            ToastHost(toast)
        }
    }
}

// ───────────────────────────── 二级页：某一类的完整列表 ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCategoryScreen(graph: AppGraph, category: String) {
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val controller = rememberNotificationController(graph, toast)
    var refreshing by remember { mutableStateOf(false) }
    val group = groupOf(category)
    var confirmIgnoreAll by remember { mutableStateOf(false) }
    if (confirmIgnoreAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmIgnoreAll = false },
            title = { Text("忽略全部好友申请？") },
            text = { Text("这些申请会从你的列表里消失，对方那边仍显示「等待验证」，不会知道被忽略。") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmIgnoreAll = false; controller.ignoreAll() }) { Text("全部忽略") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmIgnoreAll = false }) { Text("取消") } },
        )
    }

    val list = NotificationStore.list
    // 待我处理的排在最前，其余保持时间倒序
    val items = list?.items?.filter { it.category == category }.orEmpty().sortedByDescending { actionable(it) }
    val unread = if (category == "post") items.count { it.unread } else 0

    KcPage(
        title = group?.label ?: "通知",
        subtitle = if (items.isNotEmpty()) "${items.size} 条" else null,
        onBack = { nav.back() },
        actions = {
            if (unread > 0) ReadAllAction { controller.readAll() }
            if (category == "friend" && items.any { actionable(it) }) {
                Text(
                    "全部忽略",
                    style = KcTheme.type.secondary.copy(fontWeight = FontWeight.W600),
                    color = KcTheme.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { confirmIgnoreAll = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; NotificationStore.refresh(graph); refreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                val error = NotificationStore.error
                when {
                    list == null && error != null -> ErrorRetry(error, onRetry = { scope.launch { NotificationStore.refresh(graph) } })
                    list == null -> LoadingBox("正在加载通知…")
                    items.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        // 放进可滚动列表里，空页面也能下拉刷新
                        item { EmptyBlock(group?.empty ?: "暂时没有通知", icon = group?.icon, tint = group?.tint) }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                        notificationRows(aggregate(items), controller, prefix = "", onOpen = { nav.go(it) })
                    }
                }
            }
            ToastHost(toast)
        }
    }
}

// ───────────────────────────── 列表数据：连续点赞合并 ─────────────────────────────

private sealed interface Entry {
    val key: String
    val lead: UnifiedNotification

    data class Single(val n: UnifiedNotification) : Entry {
        override val key get() = n.id
        override val lead get() = n
    }

    /** 同一条动态上连续的几次点赞，合成一行 */
    data class Likes(val items: List<UnifiedNotification>) : Entry {
        override val key get() = items.first().id
        override val lead get() = items.first()
    }
}

private fun aggregate(items: List<UnifiedNotification>): List<Entry> {
    val out = ArrayList<Entry>(items.size)
    var run = ArrayList<UnifiedNotification>()
    fun flush() {
        when (run.size) {
            0 -> Unit
            1 -> out += Entry.Single(run[0])
            else -> out += Entry.Likes(run)
        }
        run = ArrayList()
    }
    for (n in items) {
        val isLike = n.type == "post.like" && n.postId != null
        if (isLike && run.isNotEmpty() && run[0].postId == n.postId) {
            run += n
        } else {
            flush()
            if (isLike) run += n else out += Entry.Single(n)
        }
    }
    flush()
    return out
}

private fun LazyListScope.notificationRows(
    entries: List<Entry>,
    controller: NotificationController,
    prefix: String,
    onOpen: (Any) -> Unit,
) {
    itemsIndexed(entries, key = { _, e -> prefix + e.key }) { index, e ->
        val divider = index < entries.lastIndex
        when (e) {
            is Entry.Likes -> LikesRow(e, divider) { e.lead.postId?.let { onOpen(PostRoute(it)) } }
            is Entry.Single -> NotificationRow(
                n = e.n,
                divider = divider,
                onAccept = { controller.decide(e.n, true) },
                onReject = { controller.decide(e.n, false) },
                onIgnore = { controller.ignore(e.n) },
                onOpen = {
                    val route = routeOf(e.n)
                    // 申请类先看详情（附言、回答在列表里放不下），要进群 / 看资料在详情里点
                    if (e.n.friendRequestId != null || e.n.groupJoinRequestId != null) {
                        controller.detail = e.n
                    } else if (route != null) {
                        onOpen(route)
                    } else {
                        e.n.actor?.let { onOpen(UserRoute(it.id)) }
                    }
                },
            )
        }
    }
}

/** 点一条通知去哪（同电脑版 notificationTarget）；去不了的返回 null */
private fun routeOf(n: UnifiedNotification): Any? {
    if (n.type == "account_level") return LevelRoute
    if (n.action == "open_post" && n.postId != null) return PostRoute(n.postId)
    val profileId = n.payload?.get("profile_id")?.jsonPrimitive?.longOrNull
    if (n.action == "open_teamup" && profileId != null) return TeamupProfileRoute(profileId)
    val botId = n.payload?.get("bot_id")?.jsonPrimitive?.longOrNull
    if (n.action == "open_bot" && botId != null) return BotRoute(botId)
    if (n.action == "open_conversation" && n.conversationId != null) return ChatRoute(n.conversationId)
    return null
}

// ───────────────────────────── 每行显示什么 ─────────────────────────────

private class RowText(val headline: AnnotatedString, val snippet: String?, val status: String?)

private val NameStyle = SpanStyle(fontWeight = FontWeight.W600)

private fun bolded(name: String, rest: String) = buildAnnotatedString {
    withStyle(NameStyle) { append(name) }
    append(rest)
}

/**
 * 申请类：标题只放人名（右侧要留给按钮），动作和附言放第二行；
 * 服务端 body 第一段是状态（等待处理 / 已通过 / 已拒绝），其余是附言、回答。
 * 其他：标题里打头的人名加粗，第二行是正文摘要。
 */
private fun rowText(n: UnifiedNotification): RowText {
    val actorName = n.actor?.displayName
    val parts = n.body?.split(" · ").orEmpty()
    val state = parts.firstOrNull()?.takeIf { it.isNotBlank() }
    val extras = parts.drop(1).joinToString(" · ").ifBlank { null }
    val group = n.conversationTitle?.takeIf { it.isNotBlank() } ?: "群聊"
    fun join(vararg s: String?) = s.filterNot { it.isNullOrBlank() }.joinToString(" · ").ifBlank { null }
    // 服务端以后若把理由写进 body，就不再重复拼一遍
    val reason = decisionNoteOf(n)?.takeIf { extras?.contains("拒绝理由") != true }?.let { "拒绝理由：$it" }
    return when (n.type) {
        "friend.request.incoming" -> RowText(bolded(actorName ?: n.title, ""), "请求添加你为好友", if (n.pending) null else state)
        "friend.request.outgoing" -> RowText(bolded(actorName ?: n.title, ""), "你发出的好友申请", state)
        "group.join_request.incoming" -> RowText(bolded(actorName ?: n.title, ""), join("申请加入 $group", extras, reason), if (n.pending) null else state)
        "group.join_request.outgoing" -> RowText(bolded(group, ""), join(reason, "你申请加入这个群", extras), state)
        else -> {
            val headline = if (actorName != null && n.title.startsWith(actorName)) {
                // 服务端标题是「名字点赞了你的动态」，名字和动作之间补一个空格更好读
                val rest = n.title.removePrefix(actorName)
                bolded(actorName, if (rest.startsWith(" ")) rest else " $rest")
            } else AnnotatedString(n.title)
            RowText(headline, n.body?.replace('\n', ' ')?.trim()?.ifBlank { null }, null)
        }
    }
}

// ───────────────────────────── 行组件 ─────────────────────────────

private val RowPadH = 16.dp
private val AvatarSize = 40.dp
private val DividerInset = RowPadH + AvatarSize + 12.dp

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = KcTheme.type.group,
        color = KcTheme.colors.muted,
        modifier = Modifier.fillMaxWidth().padding(start = RowPadH, end = RowPadH, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = DividerInset).height(0.5.dp).background(KcTheme.colors.borderStrong))
}

@Composable
private fun ReadAllAction(onClick: () -> Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text("全部已读", style = KcTheme.type.secondary, color = KcTheme.colors.accent, fontWeight = FontWeight.W600)
    }
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp = AvatarSize) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = if (KcTheme.colors.isDark) 0.22f else 0.14f)), contentAlignment = Alignment.Center) {
        KcIcon(icon, tint = tint, size = size * 0.52f)
    }
}

@Composable
private fun EmptyBlock(text: String, icon: ImageVector? = null, tint: Color? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = if (icon != null) 96.dp else 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null && tint != null) {
            IconTile(icon, tint, 56.dp)
            Spacer(Modifier.height(12.dp))
        }
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted)
    }
}

/** 分类入口行（QQ「新朋友」样式）：图标块 · 标题 + 时间 · 最新一条摘要 · 角标 · 箭头 */
@Composable
private fun EntryRow(group: NotifyGroup, latest: UnifiedNotification?, badge: Int, divider: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = RowPadH, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(group.icon, group.tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.label, style = type.rowTitle, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
                    if (latest != null) Text(relativeTime(latest.time), style = type.caption, color = colors.subtle, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        latest?.title ?: group.hint,
                        style = type.secondary,
                        color = if (latest != null) colors.muted else colors.subtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (badge > 0) {
                        Spacer(Modifier.width(8.dp))
                        UnreadBadge(badge)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
        }
        if (divider) RowDivider()
    }
}

/** 一条通知：头像 · 标题（人名加粗）+ 时间 · 一行摘要 + 状态；待处理的右侧是 忽略（好友申请）/ 拒绝 · 接受 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotificationRow(
    n: UnifiedNotification,
    divider: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onIgnore: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val text = rowText(n)
    val outcome = NotificationStore.outcomes[n.id]
    val canAct = actionable(n)
    val busy = NotificationStore.busy[n.id] == true
    val unread = n.unread && outcome == null
    val status = outcome ?: text.status
    val time = relativeTime(n.time)
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("忽略（对方看不出来）") }, onClick = { menu = false; onIgnore() })
        }
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { if (canAct && n.friendRequestId != null) menu = true })
                .padding(horizontal = RowPadH, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NotificationIcon(n)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text.headline, style = type.rowTitle.copy(fontSize = 15.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!canAct) TimeAndDot(time, unread)
                }
                val snippet = if (canAct) listOfNotNull(text.snippet, time.ifBlank { null }).joinToString(" · ") else text.snippet
                if (!snippet.isNullOrBlank() || status != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 入群申请带附言 / 回答时给两行，其余一行；完整内容点开详情看
                        val lines = if (n.groupJoinRequestId != null && requestFields(n).isNotEmpty()) 2 else 1
                        Text(snippet.orEmpty(), style = type.secondary, color = colors.muted, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (status != null && !canAct) {
                            Spacer(Modifier.width(8.dp))
                            StatusText(status)
                        }
                    }
                }
            }
            if (canAct) {
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 拒绝 · 接受 / 通过；好友申请的「忽略」（对方看不出来）在长按菜单和详情里
                    RowButton("拒绝", primary = false, enabled = !busy, onClick = onReject)
                    RowButton(if (n.friendRequestId != null) "接受" else "通过", primary = true, enabled = !busy, onClick = onAccept)
                }
            }
        }
        if (divider) RowDivider()
    }
}

/** 同一条动态的多次点赞：叠两张头像，「A、B 等 3 人赞了你的动态」 */
@Composable
private fun LikesRow(e: Entry.Likes, divider: Boolean, onOpen: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val actors = e.items.mapNotNull { it.actor }.distinctBy { it.id }
    val names = actors.take(2).map { it.displayName }
    val count = if (actors.isNotEmpty()) actors.size else e.items.size
    val headline = buildAnnotatedString {
        if (names.isEmpty()) append("${e.items.size} 人") else withStyle(NameStyle) { append(names.joinToString("、")) }
        append(if (count > names.size && names.isNotEmpty()) " 等 $count 人赞了你的动态" else " 赞了你的动态")
    }
    val unread = e.items.any { it.unread }
    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = RowPadH, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(AvatarSize)) {
                val first = actors.getOrNull(0)
                if (first != null) Avatar(first.avatarUrl, first.displayName, first.id, AvatarSize) else IconTile(KcIcons.heart, Color(0xFFF0506E))
                val second = actors.getOrNull(1)
                if (second != null) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(22.dp).clip(CircleShape).background(colors.panel).padding(1.5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Avatar(second.avatarUrl, second.displayName, second.id, 19.dp) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(headline, style = type.rowTitle.copy(fontSize = 15.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TimeAndDot(relativeTime(e.lead.time), unread)
                }
                val snippet = e.lead.body?.replace('\n', ' ')?.trim()
                if (!snippet.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(snippet, style = type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (divider) RowDivider()
    }
}

@Composable
private fun TimeAndDot(time: String, unread: Boolean) {
    val colors = KcTheme.colors
    if (unread) {
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(7.dp).clip(CircleShape).background(colors.accent))
    }
    if (time.isNotBlank()) {
        Spacer(Modifier.width(if (unread) 6.dp else 8.dp))
        Text(time, style = KcTheme.type.caption, color = if (unread) colors.accent else colors.subtle, maxLines = 1)
    }
}

@Composable
private fun StatusText(status: String) {
    val colors = KcTheme.colors
    val color = when {
        status.startsWith("已拒") -> colors.subtle
        status.startsWith("已") -> colors.ok
        else -> colors.warn
    }
    Text(status, style = KcTheme.type.caption, color = color, fontWeight = FontWeight.W600, maxLines = 1)
}

/** 行内紧凑按钮（高 30）：主按钮强调色实底，次按钮淡底 */
@Composable
private fun RowButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shape = RoundedCornerShape(99.dp)
    val bg = when {
        !enabled -> colors.panelMuted
        primary -> colors.fill
        else -> colors.text.copy(alpha = 0.07f)
    }
    val fg = when {
        !enabled -> colors.subtle
        primary -> colors.onFill
        else -> colors.text
    }
    Box(
        Modifier
            .pressScale(press)
            .height(30.dp)
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

/** 左侧图标：好友 / 互动用触发人的头像，群聊用群头像，系统类用图标块 */
@Composable
private fun NotificationIcon(n: UnifiedNotification) {
    val actor = n.actor
    when {
        n.category == "system" || (actor == null && n.conversationAvatarUrl == null) -> {
            val (icon, tint) = systemIcon(n)
            IconTile(icon, tint)
        }
        n.type == "group.join_request.outgoing" || actor == null ->
            Avatar(n.conversationAvatarUrl, n.conversationTitle ?: "群", (n.conversationId ?: 0L), AvatarSize)
        else -> Avatar(actor.avatarUrl, actor.displayName, actor.id, AvatarSize)
    }
}

private fun systemIcon(n: UnifiedNotification): Pair<ImageVector, Color> = when {
    n.type == "account_level" -> KcIcons.trendingUp to Color(0xFFF59F00)
    n.type == "moderation_penalty" -> KcIcons.gavel to Color(0xFFE0383E)
    n.type == "report_result" -> KcIcons.flag to Color(0xFF0C8599)
    n.type == "jury" -> KcIcons.scale to Color(0xFF7950F2)
    n.category == "friend" -> KcIcons.userPlus to Color(0xFF1F6BFF)
    n.category == "group" -> KcIcons.users to Color(0xFF12A679)
    else -> KcIcons.bell to Color(0xFF1F6BFF)
}

// ───────────────────────────── 申请详情 ─────────────────────────────

private val RequestFieldLabels = listOf("附言", "回答", "拒绝理由")

/**
 * 入群申请 body 里的附言 / 回答 / 拒绝理由（服务端拼成「状态 · 附言：… · 回答：… · 拒绝理由：…」）。
 * 附言里自己带「 · 」时，接回上一段而不是另起一个字段。拒绝理由服务端没写进 body 时从 decisionNoteOf 补。
 */
private fun requestFields(n: UnifiedNotification): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    n.body?.split(" · ").orEmpty().drop(1).forEach { part ->
        val label = RequestFieldLabels.firstOrNull { part.startsWith("$it：") }
        when {
            label != null -> out += label to part.removePrefix("$label：")
            out.isNotEmpty() -> out[out.lastIndex] = out.last().first to (out.last().second + " · " + part)
        }
    }
    if (out.none { it.first == "拒绝理由" }) decisionNoteOf(n)?.let { out += "拒绝理由" to it }
    return out.filter { it.second.isNotBlank() }
}

/** 点一条好友申请 / 入群申请：谁、申请什么、附言回答全文，以及能做的操作 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestDetailSheet(
    n: UnifiedNotification,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onIgnore: () -> Unit,
    onOpen: (Any) -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val text = rowText(n)
    val canAct = actionable(n)
    val busy = NotificationStore.busy[n.id] == true
    val status = NotificationStore.outcomes[n.id] ?: text.status ?: n.body?.split(" · ")?.firstOrNull()
    val fields = requestFields(n)
    val isFriend = n.friendRequestId != null
    val outgoingGroup = n.type == "group.join_request.outgoing"
    val group = n.conversationTitle?.takeIf { it.isNotBlank() } ?: "群聊"

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.panel,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val actor = n.actor
                if (outgoingGroup || actor == null) Avatar(n.conversationAvatarUrl, group, n.conversationId ?: 0L, 48.dp)
                else Avatar(actor.avatarUrl, actor.displayName, actor.id, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (outgoingGroup) group else (n.actor?.displayName ?: n.title),
                        style = type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (n.type) {
                            "friend.request.incoming" -> "请求添加你为好友"
                            "friend.request.outgoing" -> "你发出的好友申请"
                            "group.join_request.incoming" -> "申请加入 $group"
                            else -> "你申请加入这个群"
                        },
                        style = type.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status != null && !canAct) {
                    Spacer(Modifier.width(8.dp))
                    StatusText(status)
                }
            }
            val time = relativeTime(n.time)
            if (time.isNotBlank()) Text(time, style = type.caption, color = colors.subtle, modifier = Modifier.padding(top = 10.dp))

            if (fields.isNotEmpty()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        fields.forEach { (label, value) ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(label, style = type.caption, color = colors.muted, fontWeight = FontWeight.W600)
                                Spacer(Modifier.height(4.dp))
                                Text(value, style = type.body, color = if (label == "拒绝理由") colors.danger else colors.text)
                            }
                        }
                    }
                }
            } else if (n.groupJoinRequestId != null) {
                Text("没有填写附言", style = type.secondary, color = colors.subtle, modifier = Modifier.padding(top = 14.dp))
            }

            // 看资料 / 进群
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!outgoingGroup) n.actor?.let { actor -> RowButton("查看资料", primary = false, enabled = true) { onOpen(UserRoute(actor.id)) } }
                val conversationId = n.conversationId
                if (n.groupJoinRequestId != null && n.action == "open_conversation" && conversationId != null) {
                    RowButton("进入群聊", primary = false, enabled = true) { onOpen(ChatRoute(conversationId)) }
                }
            }

            if (canAct) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isFriend) ink.kuke.chat.feature.chat.KcButton("忽略", onIgnore, modifier = Modifier.weight(1f), enabled = !busy, primary = false)
                    ink.kuke.chat.feature.chat.KcButton("拒绝", onReject, modifier = Modifier.weight(1f), enabled = !busy, primary = false)
                    ink.kuke.chat.feature.chat.KcButton(if (isFriend) "接受" else "通过", onAccept, modifier = Modifier.weight(1f), enabled = !busy)
                }
                if (isFriend) {
                    Text("忽略：从你的列表里拿掉，对方仍显示「等待验证」；拒绝：对方会看到已拒绝。", style = type.caption, color = colors.subtle, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
