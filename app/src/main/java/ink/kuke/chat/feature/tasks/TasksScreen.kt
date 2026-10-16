package ink.kuke.chat.feature.tasks

import ink.kuke.chat.core.data.slot

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.TaskRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

private enum class Nav(val label: String, val scope: String) {
    Assigned("我负责的", "assigned"),
    Watching("我关注的", "watching"),
    Activity("动态", "all"),
    All("全部任务", "all"),
    Created("我创建的", "created"),
    Completed("已完成", "all"),
}

private enum class ViewMode { List, Board, Dashboard }

private enum class StatusFilter(val label: String) { Open("未完成"), Completed("已完成"), All("全部") }

/** 导航切换时状态筛选跟着走：「已完成」只看完成的，「全部任务」不筛，其余默认看未完成 */
private fun filterFor(nav: Nav) = when (nav) {
    Nav.Completed -> StatusFilter.Completed
    Nav.All -> StatusFilter.All
    else -> StatusFilter.Open
}

/**
 * 任务中心（设计稿 P19）：标题下拉切换范围，视图 列表 / 看板 / 仪表盘，列表按截止时间分桶并可折叠。
 * 数据照电脑版：GET /tasks?scope=…，子任务从一份全量里按 parent_id 归并，实时事件 task.changed 触发刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val api = remember { TaskApi(graph.api) }
    val conversations by graph.conversations.items.collectAsState()
    val groupConversations = remember(conversations) { conversations.filter { it.isGroup } }

    var section by remember { mutableStateOf(Nav.Assigned) }
    var groupConversationId by remember { mutableStateOf<Long?>(null) }
    var mode by remember { mutableStateOf(ViewMode.List) }
    var filter by remember { mutableStateOf(StatusFilter.Open) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }

    // 上次看到的默认视图（我负责的 · 未完成 · 全部群）、动态、看板（内存）：先摆出来，再静默刷新
    val cache = remember(graph) { graph.screenCache.slot<TasksSnapshot>("tasks") }
    val cached = remember(cache) { cache.get() }
    var tasks by remember { mutableStateOf(cached?.tasks ?: emptyList()) }
    var pool by remember { mutableStateOf(cached?.pool ?: emptyList()) }
    var activities by remember { mutableStateOf(cached?.activities ?: emptyList()) }
    var dashboard by remember { mutableStateOf(cached?.dashboard) }
    var taskGroups by remember { mutableStateOf(cached?.groups ?: emptyList()) }
    var loading by remember { mutableStateOf(cached?.tasks == null) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    // 看板分组：新建（id 为 null）/ 改名 / 删除
    var groupEdit by remember { mutableStateOf<TaskGroupDef?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var groupDelete by remember { mutableStateOf<TaskGroupDef?>(null) }
    // 勾选后先在界面上立刻变，等接口返回重拉之后再以服务端为准
    val optimistic = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(section, groupConversationId, filter, tick) {
        error = null
        runCatching {
            if (section == Nav.Activity) {
                activities = api.activities(80)
            } else {
                val includeDone = filter != StatusFilter.Open
                tasks = api.tasks(section.scope, includeDone, groupConversationId)
                pool = api.tasks("all", true, groupConversationId)
                taskGroups = runCatching { api.groups() }.getOrDefault(taskGroups)
            }
            if (mode == ViewMode.Dashboard) dashboard = api.dashboard()
            val previous = cache.get()
            val isDefault = section == Nav.Assigned && groupConversationId == null && filter == StatusFilter.Open
            cache.put(TasksSnapshot(
                tasks = if (isDefault) tasks else previous?.tasks,
                pool = if (isDefault) pool else previous?.pool,
                groups = taskGroups,
                activities = if (section == Nav.Activity) activities else previous?.activities,
                dashboard = dashboard,
            ))
        }.onFailure { error = it.userMessage() }
        optimistic.clear()
        loading = false
        refreshing = false
    }
    LaunchedEffect(mode) {
        if (mode == ViewMode.Dashboard) runCatching { api.dashboard() }.onSuccess { dashboard = it }
    }
    // 别处改了任务（评论、完成、被指派）时跟着刷新
    LaunchedEffect(Unit) {
        graph.realtime.events.collect { if (it.type == "task.changed") tick++ }
    }

    val top = remember(tasks, filter) {
        val rows = tasks.filter { it.parentId == null }
        when (filter) {
            StatusFilter.Open -> rows.filter { !it.closed }
            StatusFilter.Completed -> rows.filter { it.completed }
            StatusFilter.All -> rows
        }
    }
    val subtaskCount = remember(pool) { pool.filter { it.parentId != null }.groupBy { it.parentId }.mapValues { (_, list) -> list.count { it.completed } to list.size } }
    val titleText = if (groupConversationId != null) groupConversations.firstOrNull { it.id == groupConversationId }?.name() ?: "群聊任务" else section.label

    fun toggle(task: TaskItem) {
        val next = !(optimistic[task.id] ?: task.completed)
        optimistic[task.id] = next
        if (next) Haptics.confirm(view) else Haptics.tick(view)
        scope.launch {
            runCatching { api.complete(task.id, next) }
                .onFailure { optimistic.remove(task.id); error = it.userMessage(); Haptics.reject(view) }
                .onSuccess { tick++ }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.chat)) {
        // 顶栏：返回 · 标题下拉 · 视图切换
        Row(Modifier.fillMaxWidth().background(colors.panel).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = rememberInteraction()
            Box(
                Modifier.size(44.dp).pressScale(back, 0.9f).clip(CircleShape).clickable(interactionSource = back, indication = null) { nav.back() },
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.chevronLeft, tint = colors.text, size = 24.dp, contentDescription = "返回") }
            Row(
                Modifier.weight(1f, fill = false).clip(RoundedCornerShape(10.dp)).clickable { showSwitcher = true }.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(titleText, style = type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!loading && section != Nav.Activity) {
                        val open = top.count { !it.closed }
                        Text("任务 · $open 项未完成", style = type.caption, color = colors.muted, maxLines = 1)
                    }
                }
                KcIcon(KcIcons.chevronDown, tint = colors.muted, size = 18.dp, modifier = Modifier.padding(start = 2.dp))
            }
            Box(Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        // 仪表盘视图里的三个关键数字常驻顶部
        if (section != Nav.Activity) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ViewSwitch(mode) { mode = it }
                Box(Modifier.weight(1f))
                if (mode == ViewMode.List) StatusFilter.entries.forEach { item ->
                    val on = filter == item
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.text else colors.panelMuted)
                            .clickable { filter = item }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text(item.label, style = type.secondary, color = if (on) colors.panel else colors.muted, fontWeight = if (on) FontWeight.W700 else FontWeight.W500) }
                }
            }
        }

        if (section != Nav.Activity && mode != ViewMode.Board && dashboard != null) DashboardStrip(dashboard)

        Box(Modifier.weight(1f)) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.fillMaxSize()) {
                when {
                    loading -> CenterText("正在加载任务…")
                    error != null && (if (section == Nav.Activity) activities.isEmpty() else tasks.isEmpty()) -> ErrorBlock(error!!, { loading = true; tick++ })
                    section == Nav.Activity -> ActivityList(activities) { id -> nav.go(TaskRoute(taskLabel(id))) }
                    else -> when (mode) {
                        ViewMode.List -> TaskBuckets(
                            tasks = top, collapsed = collapsed, subtaskCount = subtaskCount, optimistic = optimistic,
                            onToggleCollapse = { key -> collapsed = if (key in collapsed) collapsed - key else collapsed + key },
                            onToggle = ::toggle, onOpen = { nav.go(TaskRoute(it.label)) },
                        )
                        ViewMode.Board -> TaskBoard(
                            top, taskGroups, subtaskCount, optimistic, ::toggle,
                            onCreateGroup = { creatingGroup = true },
                            onRenameGroup = { groupEdit = it },
                            onDeleteGroup = { groupDelete = it },
                        ) { nav.go(TaskRoute(it.label)) }
                        ViewMode.Dashboard -> DashboardBody(dashboard, top, subtaskCount, optimistic, ::toggle) { nav.go(TaskRoute(it.label)) }
                    }
                }
            }
            if (error != null && !loading && (if (section == Nav.Activity) activities.isNotEmpty() else tasks.isNotEmpty())) {
                Text(
                    error!!, style = type.caption, color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(colors.danger).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (section != Nav.Activity) {
                val press = rememberInteraction()
                Row(
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).pressScale(press)
                        .clip(RoundedCornerShape(24.dp)).background(colors.fill)
                        .clickable(interactionSource = press, indication = null) { showEditor = true }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.plus, tint = colors.onFill, size = 20.dp)
                    Text("新建任务", color = colors.onFill, style = type.rowTitle, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (showSwitcher) {
        val options = buildList {
            Nav.entries.forEach { add(it.name to it.label) }
            groupConversations.forEach { add("g${it.id}" to "群聊 · ${it.name()}") }
        }
        val selected = if (groupConversationId != null) "g$groupConversationId" else section.name
        ChoiceDialog("任务范围", options, selected, onPick = { key ->
            if (key.startsWith("g")) {
                groupConversationId = key.drop(1).toLongOrNull()
                section = Nav.All
                filter = StatusFilter.Open
            } else {
                groupConversationId = null
                section = Nav.valueOf(key)
                filter = filterFor(section)
            }
            loading = true
            showSwitcher = false
        }, onDismiss = { showSwitcher = false })
    }
    if (creatingGroup || groupEdit != null) {
        val editing = groupEdit
        GroupNameDialog(
            title = if (editing == null) "新建分组" else "重命名分组",
            initial = editing?.name.orEmpty(),
            onDismiss = { creatingGroup = false; groupEdit = null },
        ) { name ->
            runCatching { if (editing == null) api.createGroup(name) else api.renameGroup(editing.id, name) }
                .onSuccess { saved ->
                    taskGroups = if (editing == null) taskGroups + saved else taskGroups.map { if (it.id == saved.id) saved else it }
                    creatingGroup = false
                    groupEdit = null
                    Haptics.confirm(view)
                    tick++
                }
                .exceptionOrNull()?.userMessage()
        }
    }
    groupDelete?.let { group ->
        ConfirmDialog(
            "删除分组「${group.name}」？", "组里的任务会回到「默认分组」，任务本身不会被删除。", "删除",
            onConfirm = {
                groupDelete = null
                scope.launch {
                    runCatching { api.deleteGroup(group.id) }
                        .onSuccess { taskGroups = taskGroups.filter { it.id != group.id }; tick++ }
                        .onFailure { error = it.userMessage(); Haptics.reject(view) }
                }
            },
            onDismiss = { groupDelete = null },
        )
    }
    if (showEditor) {
        TaskEditorSheet(
            graph = graph, fixedConversationId = groupConversationId, parentId = null, initialGroupId = null,
            onDismiss = { showEditor = false },
            onCreated = { showEditor = false; tick++ },
        )
    }
}

@Composable
private fun ViewSwitch(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(2.dp)) {
        listOf(ViewMode.List to KcIcons.list, ViewMode.Board to KcIcons.kanban, ViewMode.Dashboard to KcIcons.poll).forEach { (item, icon) ->
            val on = mode == item
            Box(
                Modifier.size(width = 38.dp, height = 32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) colors.panel else Color.Transparent).clickable { onChange(item) },
                contentAlignment = Alignment.Center,
            ) { KcIcon(icon, tint = if (on) colors.accent else colors.muted, size = 18.dp) }
        }
    }
}

/** 已逾期 · 今日到期 · 即将到期 三个数字 */
@Composable
private fun DashboardStrip(dashboard: TaskDashboard?) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().background(colors.panel).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("已逾期", dashboard?.overdue, Color(0xFFEF4444)),
            Triple("今日到期", dashboard?.dueToday, Color(0xFFF97316)),
            Triple("即将到期", dashboard?.dueSoon, Color(0xFF0A84FF)),
        ).forEach { (label, value, color) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(value?.toString() ?: "—", style = KcTheme.type.number, color = color)
                Text(label, style = KcTheme.type.caption, color = colors.muted)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun DashboardBody(
    dashboard: TaskDashboard?,
    tasks: List<TaskItem>,
    subtaskCount: Map<Long?, Pair<Int, Int>>,
    optimistic: Map<Long, Boolean>,
    onToggle: (TaskItem) -> Unit,
    onOpen: (TaskItem) -> Unit,
) {
    val colors = KcTheme.colors
    val urgent = tasks.filter { isOverdue(it) || bucketOf(it).key == "today" }.filter { !it.closed }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("stats") {
            Card {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    listOf("全部" to dashboard?.total, "待处理" to dashboard?.pending, "进行中" to dashboard?.inProgress, "已完成" to dashboard?.completed).forEach { (label, value) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value?.toString() ?: "—", style = KcTheme.type.number, color = colors.text)
                            Text(label, style = KcTheme.type.caption, color = colors.muted)
                        }
                    }
                }
            }
        }
        item("urgent-title") { SectionLabel("需要尽快处理", trailing = "${urgent.size}") }
        if (urgent.isEmpty()) {
            item("urgent-empty") { Text("没有逾期或今天到期的任务", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(8.dp)) }
        } else {
            items(urgent, key = { it.id }) { task -> TaskCard(task, subtaskCount[task.id], optimistic[task.id], onToggle, onOpen) }
        }
    }
}

/** 列表视图：按截止时间分桶，桶标题可折叠 */
@Composable
private fun TaskBuckets(
    tasks: List<TaskItem>,
    collapsed: Set<String>,
    subtaskCount: Map<Long?, Pair<Int, Int>>,
    optimistic: Map<Long, Boolean>,
    onToggleCollapse: (String) -> Unit,
    onToggle: (TaskItem) -> Unit,
    onOpen: (TaskItem) -> Unit,
) {
    val colors = KcTheme.colors
    if (tasks.isEmpty()) {
        CenterText("这里还没有任务，点右下角「新建任务」开始")
        return
    }
    val buckets = remember(tasks) { tasks.groupBy { bucketOf(it) }.toList().sortedBy { it.first.order } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        buckets.forEach { (bucket, rows) ->
            val closed = bucket.key in collapsed
            item("h-${bucket.key}") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onToggleCollapse(bucket.key) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val rotate by animateFloatAsState(if (closed) -90f else 0f, label = "rotate")
                    KcIcon(KcIcons.chevronDown, tint = colors.muted, size = 16.dp, modifier = Modifier.graphicsLayer { rotationZ = rotate })
                    Text(
                        "${bucket.name} · ${rows.size}", style = KcTheme.type.group,
                        color = if (bucket.key == "overdue") colors.danger else colors.muted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (!closed) {
                items(rows, key = { it.id }) { task ->
                    Box(Modifier.animateItem().padding(horizontal = 12.dp, vertical = 4.dp)) { TaskCard(task, subtaskCount[task.id], optimistic[task.id], onToggle, onOpen) }
                }
            }
        }
    }
}

/** 看板：按自定义分组分列，横向滑动 */
@Composable
private fun TaskBoard(
    tasks: List<TaskItem>,
    groups: List<TaskGroupDef>,
    subtaskCount: Map<Long?, Pair<Int, Int>>,
    optimistic: Map<Long, Boolean>,
    onToggle: (TaskItem) -> Unit,
    onCreateGroup: () -> Unit,
    onRenameGroup: (TaskGroupDef) -> Unit,
    onDeleteGroup: (TaskGroupDef) -> Unit,
    onOpen: (TaskItem) -> Unit,
) {
    val colors = KcTheme.colors
    val columns = remember(tasks, groups) {
        val known = groups.map { it.id }.toSet()
        val result = mutableListOf<Triple<Long?, String, List<TaskItem>>>()
        result += Triple(null, "默认分组", tasks.filter { it.groupId == null || it.groupId !in known })
        groups.forEach { group -> result += Triple(group.id, group.name, tasks.filter { it.groupId == group.id }) }
        result
    }
    LazyRow(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(columns, key = { it.first ?: -1L }) { (groupId, name, rows) ->
            Column(Modifier.width(280.dp).fillMaxSize().clip(RoundedCornerShape(14.dp)).background(colors.list).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$name · ${rows.size}", style = KcTheme.type.group, color = colors.muted, modifier = Modifier.weight(1f).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val group = groups.firstOrNull { it.id == groupId }
                    if (group != null) GroupMenu(onRename = { onRenameGroup(group) }, onDelete = { onDeleteGroup(group) })
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.id }) { task -> TaskCard(task, subtaskCount[task.id], optimistic[task.id], onToggle, onOpen) }
                    if (rows.isEmpty()) item("empty") { Text("没有任务", style = KcTheme.type.caption, color = colors.subtle, modifier = Modifier.padding(8.dp)) }
                }
            }
        }
        item("new-group") {
            val press = rememberInteraction()
            Column(
                Modifier.width(160.dp).heightIn(min = 120.dp).pressScale(press).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(14.dp))
                    .clickable(interactionSource = press, indication = null, onClick = onCreateGroup).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                KcIcon(KcIcons.plus, tint = colors.muted, size = 22.dp)
                Text("新建分组", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** 一条任务：完成圈 · 标题（完成后划线）· 优先级 / 截止 / 子任务进度 */
@Composable
private fun TaskCard(task: TaskItem, subtasks: Pair<Int, Int>?, optimisticDone: Boolean?, onToggle: (TaskItem) -> Unit, onOpen: (TaskItem) -> Unit) {
    val colors = KcTheme.colors
    val done = optimisticDone ?: task.completed
    val priority = priorityOf(task.priority)
    val press = rememberInteraction()
    Row(
        Modifier.fillMaxWidth().pressScale(press).clip(RoundedCornerShape(12.dp)).background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = null) { onOpen(task) }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompleteCircle(done = done, color = priority.color, onClick = { onToggle(task) })
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                task.title, style = KcTheme.type.rowTitle, color = if (done) colors.subtle else colors.text,
                textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("${priority.code}", priority.color)
                if (task.dueAt != null) {
                    Text(
                        dueLabel(task.dueAt), style = KcTheme.type.caption,
                        color = if (isOverdue(task) && !done) colors.danger else colors.muted,
                    )
                }
                val (finished, total) = subtasks ?: (task.subtaskDone to task.subtaskTotal)
                if (total > 0) Text("子任务 $finished/$total", style = KcTheme.type.caption, color = colors.muted)
                task.conversationTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = KcTheme.type.caption, color = colors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        if (task.assignees.isNotEmpty()) {
            val first = task.assignees.first()
            Avatar(first.avatarUrl, first.displayName, first.id, size = 24.dp)
        }
    }
}

/** 完成圈：点下去弹簧填绿，调用方负责划线和震动 */
@Composable
internal fun CompleteCircle(done: Boolean, color: Color, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val fill by animateColorAsState(if (done) DoneGreen else Color.Transparent, label = "fill")
    val ring by animateColorAsState(if (done) DoneGreen else color, label = "ring")
    val scale by animateFloatAsState(if (done) 1f else 0.92f, spring(dampingRatio = 0.4f, stiffness = 600f), label = "scale")
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(fill).border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (done) KcIcon(KcIcons.check, tint = Color.White, size = size * 0.62f) }
    }
}

/** 动态：按天归组，一行一件事 */
@Composable
private fun ActivityList(activities: List<TaskActivity>, onOpen: (Long) -> Unit) {
    val colors = KcTheme.colors
    if (activities.isEmpty()) {
        CenterText("还没有任务动态")
        return
    }
    val days = remember(activities) { activities.groupBy { localDateTimeOf(it.createdAt)?.toLocalDate() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
        days.forEach { (day, rows) ->
            item("d-$day") {
                val label = day?.let {
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(it, java.time.LocalDate.now()).toInt()
                    when (diff) { 0 -> "今天"; 1 -> "昨天"; else -> "${it.monthValue}月${it.dayOfMonth}日" }
                } ?: "更早"
                Text(label, style = KcTheme.type.group, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            items(rows, key = { "a${it.id}" }) { activity ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(activity.taskId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val actor = activity.actor
                    Avatar(actor?.avatarUrl, actor?.displayName ?: "?", actor?.id ?: 0, size = 32.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(activityText(activity.type, actor?.displayName.orEmpty(), activity.payload), style = KcTheme.type.body, color = colors.text)
                        Text(activity.taskTitle.orEmpty(), style = KcTheme.type.secondary, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(clockOf(activity.createdAt), style = KcTheme.type.caption, color = colors.subtle)
                }
            }
        }
    }
}

/** 任务页的快照（只在内存，退出登录清掉）；tasks 为 null 表示默认视图还没拉到过 */
private class TasksSnapshot(
    val tasks: List<TaskItem>?,
    val pool: List<TaskItem>?,
    val groups: List<TaskGroupDef>,
    val activities: List<TaskActivity>?,
    val dashboard: TaskDashboard?,
)
