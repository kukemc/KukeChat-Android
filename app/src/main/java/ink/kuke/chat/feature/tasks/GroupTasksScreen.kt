package ink.kuke.chat.feature.tasks

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.TaskRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

private enum class GroupTaskFilter(val label: String) { All("全部"), Open("进行中"), Completed("已完成"), Overdue("逾期") }

/**
 * 群任务（同电脑版 GroupTasksPanel）：本群全部任务的完成进度、进行中 / 已完成 / 逾期统计，按状态筛选；
 * 点任务进详情，勾圈完成（乐观更新）；右下「新建」固定在本群。任务有变动（task.changed）自动刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupTasksScreen(graph: AppGraph, conversationId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val api = remember { TaskApi(graph.api) }
    val conversations by graph.conversations.items.collectAsState()
    val groupName = conversations.firstOrNull { it.id == conversationId }?.name() ?: "群聊"
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(GroupTaskFilter.All) }
    var tick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    val optimistic = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(tick) {
        runCatching { api.tasks("all", includeCompleted = true, conversationId = conversationId) }
            .onSuccess { tasks = it; error = null }
            .onFailure { error = it.userMessage() }
        optimistic.clear()
        refreshing = false
    }
    LaunchedEffect(Unit) { graph.realtime.events.collect { if (it.type == "task.changed") tick++ } }

    val top = tasks.orEmpty().filter { it.parentId == null }.map { task -> optimistic[task.id]?.let { done -> task.copy(status = if (done) "completed" else "pending") } ?: task }
    val done = top.count { it.completed }
    val open = top.count { !it.closed }
    val overdue = top.count { !it.closed && isOverdue(it) }
    val progress = if (top.isEmpty()) 0f else done / top.size.toFloat()
    val visible = when (filter) {
        GroupTaskFilter.All -> top
        GroupTaskFilter.Open -> top.filter { !it.closed }
        GroupTaskFilter.Completed -> top.filter { it.completed }
        GroupTaskFilter.Overdue -> top.filter { !it.closed && isOverdue(it) }
    }

    fun toggle(task: TaskItem) {
        val next = !task.completed
        optimistic[task.id] = next
        if (next) Haptics.confirm(view) else Haptics.tick(view)
        scope.launch {
            runCatching { api.complete(task.id, next) }
                .onFailure { optimistic.remove(task.id); error = it.userMessage(); Haptics.reject(view) }
                .onSuccess { tick++ }
        }
    }

    KcPage(title = "群任务", subtitle = groupName, onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize()) {
            // 进度 + 三个数字
            Column(Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("完成进度", style = type.caption, color = colors.muted, modifier = Modifier.weight(1f))
                    Text("$done/${top.size} · ${(progress * 100).toInt()}%", style = type.caption.copy(fontWeight = FontWeight.W700), color = colors.text)
                }
                val shown by animateFloatAsState(progress, tween(420), label = "taskProgress")
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.panelMuted)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(3.dp)).background(DoneGreen))
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("$open", "进行中", Modifier.weight(1f))
                    StatBox("$done", "已完成", Modifier.weight(1f))
                    StatBox("$overdue", "逾期", Modifier.weight(1f), danger = overdue > 0)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupTaskFilter.entries.forEach { item ->
                    val on = filter == item
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.fill else colors.panelMuted)
                            .clickable { filter = item }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text(item.label, style = type.secondary, color = if (on) colors.onFill else colors.muted, fontWeight = if (on) FontWeight.W700 else FontWeight.W500) }
                }
            }
            Box(Modifier.weight(1f)) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.fillMaxSize()) {
                    when {
                        tasks == null && error != null -> ErrorBlock(error!!, { tick++ })
                        tasks == null -> CenterText("正在加载任务…")
                        visible.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            KcIcon(KcIcons.checkSquare, tint = colors.subtle, size = 36.dp)
                            Text(if (filter == GroupTaskFilter.All) "本群还没有任务" else "没有符合条件的任务", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                            if (filter == GroupTaskFilter.All) Box(Modifier.padding(top = 14.dp)) { PrimaryButton("创建第一个任务", { showEditor = true }) }
                        }
                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visible, key = { it.id }) { task ->
                                GroupTaskRow(task, Modifier.animateItem(), onToggle = { toggle(task) }) { nav.go(TaskRoute(task.label)) }
                            }
                        }
                    }
                }
                if (error != null && tasks != null) {
                    Text(
                        error!!, style = type.caption, color = colors.onFill,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(colors.danger).clickable { error = null }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
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

    if (showEditor) {
        TaskEditorSheet(
            graph = graph, fixedConversationId = conversationId, parentId = null, initialGroupId = null,
            onDismiss = { showEditor = false },
            onCreated = { showEditor = false; tick++ },
        )
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier, danger: Boolean = false) {
    val colors = KcTheme.colors
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = KcTheme.type.rowTitle.copy(fontWeight = FontWeight.W700), color = if (danger) colors.danger else colors.text)
        Text(label, style = KcTheme.type.caption, color = colors.muted)
    }
}

/** 一条群任务：完成圈 · 标题（完成后划线）· 编号 / 优先级 / 子任务 / 截止 · 负责人头像 */
@Composable
private fun GroupTaskRow(task: TaskItem, modifier: Modifier, onToggle: () -> Unit, onOpen: () -> Unit) {
    val colors = KcTheme.colors
    val priority = priorityOf(task.priority)
    val press = rememberInteraction()
    Row(
        modifier.fillMaxWidth().animateContentSize().pressScale(press).clip(RoundedCornerShape(12.dp)).background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = null, onClick = onOpen)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompleteCircle(done = task.completed, color = priority.color, onClick = onToggle)
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                task.title, style = KcTheme.type.rowTitle, color = if (task.completed) colors.subtle else colors.text,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(task.label, style = KcTheme.type.caption, color = colors.subtle)
                if (task.priority != "normal") Tag(priority.code, priority.color)
                if (task.subtaskTotal > 0) Text("子任务 ${task.subtaskDone}/${task.subtaskTotal}", style = KcTheme.type.caption, color = colors.muted)
                if (task.dueAt != null) Text(dueLabel(task.dueAt), style = KcTheme.type.caption, color = if (isOverdue(task) && !task.closed) colors.danger else colors.muted)
            }
        }
        task.assignees.take(3).forEachIndexed { index, user ->
            Box(Modifier.padding(start = if (index == 0) 4.dp else 0.dp)) { Avatar(user.avatarUrl, user.displayName, user.id, size = 24.dp) }
        }
    }
}
