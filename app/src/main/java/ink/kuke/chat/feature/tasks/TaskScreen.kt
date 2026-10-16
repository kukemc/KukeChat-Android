package ink.kuke.chat.feature.tasks

import ink.kuke.chat.core.data.slot

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 详情时间线的一项：评论或里程碑动态，按时间先后排 */
private sealed interface TimelineItem {
    val time: String
    data class Comment(val value: TaskComment) : TimelineItem { override val time get() = value.createdAt }
    data class Activity(val value: TaskActivity) : TimelineItem { override val time get() = value.createdAt }
}

/**
 * 任务详情（设计稿 P20）：属性行、子任务、评论与动态、完成 / 删除。
 * taskId 是路由里的字符串（t000123），请求用数字。
 */
@Composable
fun TaskScreen(graph: AppGraph, taskId: String) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val api = remember { TaskApi(graph.api) }
    val numericId = remember(taskId) { parseTaskId(taskId) }

    // 上次看到的这条任务（内存）：先摆出来，再静默刷新
    val cache = remember(graph, numericId) { graph.screenCache.slot<TaskSnapshot>("task.$numericId") }
    val cached = remember(cache) { if (numericId != null) cache.get() else null }
    var task by remember { mutableStateOf(cached?.task) }
    var comments by remember { mutableStateOf(cached?.comments ?: emptyList()) }
    var activities by remember { mutableStateOf(cached?.activities ?: emptyList()) }
    var subtasks by remember { mutableStateOf(cached?.subtasks ?: emptyList()) }
    var members by remember { mutableStateOf(cached?.members ?: emptyList()) }
    var taskGroups by remember { mutableStateOf(cached?.groups ?: emptyList()) }
    var loading by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var showSubtaskEditor by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    // 完成圈的乐观状态：点了立刻变绿划线，不等接口
    var optimisticDone by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(numericId, tick) {
        val id = numericId
        if (id == null) {
            error = "任务编号不对"
            loading = false
            return@LaunchedEffect
        }
        runCatching {
            val loaded = api.task(id)
            task = loaded
            comments = runCatching { api.comments(id) }.getOrDefault(comments)
            activities = runCatching { api.taskActivities(id) }.getOrDefault(activities)
            subtasks = runCatching { api.tasks("all", true, loaded.conversationId).filter { it.parentId == id } }.getOrDefault(subtasks)
            if (members.isEmpty()) members = runCatching { api.members(loaded.conversationId) }.getOrDefault(members)
            if (taskGroups.isEmpty()) taskGroups = runCatching { api.groups() }.getOrDefault(taskGroups)
            error = null
            cache.put(TaskSnapshot(loaded, comments, activities, subtasks, members, taskGroups))
        }.onFailure { error = it.userMessage() }
        optimisticDone = null
        loading = false
    }
    LaunchedEffect(Unit) {
        graph.realtime.events.collect { if (it.type == "task.changed") tick++ }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { actionError = null; tick++ }
                .onFailure { actionError = it.userMessage(); optimisticDone = null; Haptics.reject(view) }
        }
    }

    fun patch(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val id = numericId ?: return
        run { api.update(id, buildJsonObject(build)) }
    }

    val current = task
    Column(Modifier.fillMaxSize().background(colors.chat)) {
        // 顶栏：返回 · 完成任务 · 编号 · 更多
        Row(Modifier.fillMaxWidth().background(colors.panel).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = rememberInteraction()
            Box(
                Modifier.size(44.dp).pressScale(back, 0.9f).clip(CircleShape).clickable(interactionSource = back, indication = null) { nav.back() },
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.chevronLeft, tint = colors.text, size = 24.dp, contentDescription = "返回") }
            if (current != null) {
                val done = optimisticDone ?: current.completed
                val pill by animateColorAsState(if (done) DoneGreen else Color.Transparent, label = "pill")
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(pill)
                        .border(1.dp, if (done) DoneGreen else colors.borderStrong, RoundedCornerShape(16.dp))
                        .clickable {
                            val next = !done
                            optimisticDone = next
                            if (next) Haptics.confirm(view) else Haptics.tick(view)
                            run { api.complete(current.id, next) }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.check, tint = if (done) Color.White else colors.text, size = 16.dp)
                    Text(if (done) "已完成" else "完成任务", color = if (done) Color.White else colors.text, style = type.secondary, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 4.dp))
                }
                Text(current.label, style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 10.dp).weight(1f))
                if (current.canEdit) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { showMore = true }, contentAlignment = Alignment.Center) {
                        KcIcon(KcIcons.more, tint = colors.text, size = 22.dp, contentDescription = "更多")
                    }
                }
            } else {
                Box(Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        when {
            current == null && loading -> CenterText("正在加载任务…")
            current == null -> ErrorBlock(error ?: "任务不存在或已删除", { loading = true; tick++ })
            else -> {
                val done = optimisticDone ?: current.completed
                val timeline = remember(comments, activities) {
                    (activities.filter { it.type in MilestoneTypes }.map { TimelineItem.Activity(it) } + comments.map { TimelineItem.Comment(it) })
                        .sortedBy { localDateTimeOf(it.time) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        current.title, style = type.title.copy(fontSize = type.title.fontSize * 1.15f), color = if (done) colors.subtle else colors.text,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        modifier = Modifier.fillMaxWidth().then(if (current.canEdit) Modifier.clickable { editText = current.title; dialog = "title" } else Modifier),
                    )
                    current.conversationTitle?.takeIf { it.isNotBlank() }?.let {
                        Text("来自会话 $it", style = type.secondary, color = colors.muted)
                    }
                    actionError?.let { Text(it, style = type.secondary, color = colors.danger) }

                    Card {
                        val editable = current.canEdit
                        PropertyRow("负责人", editable, onClick = { dialog = "assignees" }) {
                            AvatarsValue(current.assignees)
                        }
                        Divider()
                        PropertyRow("截止时间", editable, onClick = { dialog = "due" }) {
                            val label = if (current.dueAt == null) "无" else dueLabel(current.dueAt) + remindOffsetOf(current)?.let { " · ${remindLabel(it)}" }.orEmpty()
                            Text(label, style = type.body, color = if (isOverdue(current)) colors.danger else if (current.dueAt == null) colors.subtle else colors.text)
                        }
                        Divider()
                        PropertyRow("优先级", editable, onClick = { dialog = "priority" }) {
                            val meta = priorityOf(current.priority)
                            Tag("${meta.code} ${meta.label}", meta.color)
                        }
                        Divider()
                        PropertyRow("分组", editable, onClick = { dialog = "group" }) {
                            Text(current.groupName?.takeIf { it.isNotBlank() } ?: "默认分组", style = type.body, color = colors.text)
                        }
                        Divider()
                        PropertyRow("关注人", editable, onClick = { dialog = "watchers" }) {
                            AvatarsValue(current.watchers)
                        }
                    }

                    // 描述
                    Card(Modifier.then(if (current.canEdit) Modifier.clickable { editText = current.description; dialog = "description" } else Modifier)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("描述", style = type.group, color = colors.muted)
                            Text(
                                current.description.ifBlank { if (current.canEdit) "点这里添加描述…" else "无" },
                                style = type.body, color = if (current.description.isBlank()) colors.subtle else colors.text,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }

                    // 子任务
                    val subDone = subtasks.count { it.completed }
                    Card {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("子任务", style = type.group, color = colors.muted)
                            if (subtasks.isNotEmpty()) Text("  $subDone / ${subtasks.size}", style = type.caption, color = colors.subtle)
                            Box(Modifier.weight(1f))
                            if (current.canEdit && current.parentId == null) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { showSubtaskEditor = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    KcIcon(KcIcons.plus, tint = colors.accent, size = 16.dp)
                                    Text("新建子任务", style = type.secondary, color = colors.accent, modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                        if (subtasks.isEmpty()) {
                            Text("暂无子任务", style = type.secondary, color = colors.subtle, modifier = Modifier.padding(start = 14.dp, bottom = 14.dp))
                        }
                        subtasks.forEach { sub ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                CompleteCircle(
                                    done = sub.completed, color = priorityOf(sub.priority).color, size = 20.dp,
                                    onClick = {
                                        if (!sub.completed) Haptics.confirm(view) else Haptics.tick(view)
                                        run { api.complete(sub.id, !sub.completed) }
                                    },
                                )
                                Text(
                                    sub.title, style = type.body, color = if (sub.completed) colors.subtle else colors.text,
                                    textDecoration = if (sub.completed) TextDecoration.LineThrough else null,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).clickable { nav.go(TaskRoute(sub.label)) }.padding(end = 14.dp),
                                )
                            }
                        }
                    }

                    // 评论与动态
                    SectionLabel("评论与动态", trailing = if (comments.isNotEmpty()) "${comments.size} 条评论" else null)
                    if (timeline.isEmpty()) {
                        Text("还没有评论，说点什么吧", style = type.secondary, color = colors.subtle, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    timeline.forEach { entry ->
                        when (entry) {
                            is TimelineItem.Activity -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(colors.panelMuted), contentAlignment = Alignment.Center) {
                                    KcIcon(KcIcons.clock, tint = colors.muted, size = 12.dp)
                                }
                                Text(
                                    activityText(entry.value.type, entry.value.actor?.displayName.orEmpty(), entry.value.payload) + "  " + fullDateLabel(entry.time),
                                    style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            is TimelineItem.Comment -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                                val author = entry.value.author
                                Avatar(author?.avatarUrl, author?.displayName ?: "?", author?.id ?: 0, size = 32.dp)
                                Column(Modifier.padding(start = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(author?.displayName ?: "用户", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600)
                                        Text(fullDateLabel(entry.time), style = type.caption, color = colors.subtle, modifier = Modifier.padding(start = 8.dp))
                                    }
                                    Text(entry.value.content, style = type.body, color = colors.text, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                    }
                }

                // 评论框吸底
                Row(
                    Modifier.fillMaxWidth().background(colors.panel).imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlainField(comment, { comment = it.take(2000) }, "写评论…", modifier = Modifier.weight(1f), singleLine = false, minHeight = 40.dp)
                    PrimaryButton("发送", {
                        val text = comment.trim()
                        val id = numericId ?: return@PrimaryButton
                        sending = true
                        scope.launch {
                            runCatching { api.addComment(id, text) }
                                .onSuccess { comment = ""; tick++ }
                                .onFailure { actionError = it.userMessage(); Haptics.reject(view) }
                            sending = false
                        }
                    }, enabled = comment.isNotBlank() && !sending)
                }
            }
        }
    }

    val shown = task
    if (shown != null) {
        when (dialog) {
            "title" -> DialogCard({ dialog = null }, "任务标题") {
                PlainField(editText, { editText = it.take(200) }, "任务标题")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("取消", { dialog = null }, Modifier.weight(1f))
                    PrimaryButton("保存", {
                        val next = editText.trim()
                        dialog = null
                        if (next.isNotEmpty() && next != shown.title) patch { put("title", next) }
                    }, Modifier.weight(1f), enabled = editText.isNotBlank())
                }
            }
            "description" -> DialogCard({ dialog = null }, "任务描述") {
                PlainField(editText, { editText = it.take(4000) }, "添加描述…", singleLine = false, minHeight = 120.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("取消", { dialog = null }, Modifier.weight(1f))
                    PrimaryButton("保存", { dialog = null; patch { put("description", editText.trim()) } }, Modifier.weight(1f))
                }
            }
            "assignees" -> MemberPickDialog("负责人", membersWith(members, shown.assignees), shown.assignees.map { it.id }.toSet(), { ids ->
                dialog = null
                patch { put("assignee_ids", JsonArray(ids.map { JsonPrimitive(it) })) }
            }, { dialog = null })
            "watchers" -> MemberPickDialog("关注人", membersWith(members, shown.watchers), shown.watchers.map { it.id }.toSet(), { ids ->
                dialog = null
                patch { put("watcher_ids", JsonArray(ids.map { JsonPrimitive(it) })) }
            }, { dialog = null })
            "priority" -> ChoiceDialog("优先级", Priorities.map { it.value to "${it.code}  ${it.label}" }, shown.priority, { value ->
                dialog = null
                patch { put("priority", value) }
            }, { dialog = null })
            "group" -> ChoiceDialog(
                "分组", listOf<Pair<Long?, String>>(null to "默认分组") + taskGroups.map { it.id to it.name }, shown.groupId,
                { value -> dialog = null; run { api.moveToGroup(shown.id, value) } }, { dialog = null },
            )
            "due" -> DuePickerDialog(localDateTimeOf(shown.dueAt), remindOffsetOf(shown), onConfirm = { result ->
                dialog = null
                val due = result.due
                if (due == null) {
                    patch { put("clear_due_at", true); put("clear_remind_at", true) }
                } else {
                    patch {
                        put("due_at", toApiTime(due))
                        if (result.remindMinutes != null) put("remind_at", toApiTime(due.minusMinutes(result.remindMinutes.toLong())))
                        else put("clear_remind_at", true)
                    }
                }
            }, onDismiss = { dialog = null })
            "delete" -> ConfirmDialog("删除任务", "删除后无法恢复。子任务和评论也会一起删除。", "删除", onConfirm = {
                dialog = null
                scope.launch {
                    runCatching { api.delete(shown.id) }
                        .onSuccess { Haptics.confirm(view); nav.back() }
                        .onFailure { actionError = it.userMessage(); Haptics.reject(view) }
                }
            }, onDismiss = { dialog = null })
        }
        if (showMore) {
            ChoiceDialog("更多", listOf("delete" to "删除任务"), null, { showMore = false; dialog = "delete" }, { showMore = false })
        }
        if (showSubtaskEditor) {
            TaskEditorSheet(
                graph = graph, fixedConversationId = shown.conversationId, parentId = shown.id, initialGroupId = null,
                onDismiss = { showSubtaskEditor = false },
                onCreated = { showSubtaskEditor = false; tick++ },
            )
        }
    }
}

/** 候选成员里没有的人（已退群的负责人）也要保留在列表里，否则保存时会被悄悄去掉 */
private fun membersWith(members: List<User>, extra: List<User>): List<User> =
    members + extra.filter { e -> members.none { it.id == e.id } }

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(KcTheme.colors.border))
}

@Composable
private fun PropertyRow(label: String, editable: Boolean, onClick: () -> Unit, value: @Composable () -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = editable, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KcTheme.type.body, color = colors.muted, modifier = Modifier.padding(end = 16.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { value() }
        if (editable) KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun AvatarsValue(users: List<User>) {
    val colors = KcTheme.colors
    if (users.isEmpty()) {
        Text("无", style = KcTheme.type.body, color = colors.subtle)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        users.take(2).forEach { Avatar(it.avatarUrl, it.displayName, it.id, size = 24.dp) }
        Text(
            if (users.size <= 2) users.joinToString("、") { it.displayName } else "${users.take(2).joinToString("、") { it.displayName }} 等 ${users.size} 人",
            style = KcTheme.type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 任务详情的快照（只在内存，退出登录清掉） */
private class TaskSnapshot(
    val task: TaskItem,
    val comments: List<TaskComment>,
    val activities: List<TaskActivity>,
    val subtasks: List<TaskItem>,
    val members: List<User>,
    val groups: List<TaskGroupDef>,
)
