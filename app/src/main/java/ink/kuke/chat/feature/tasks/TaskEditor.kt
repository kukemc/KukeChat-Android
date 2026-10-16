package ink.kuke.chat.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime

/**
 * 新建任务（也用来新建子任务：传 parentId，会话固定）。
 * 任务都挂在群里：会话不固定时让用户选一个群；创建后由服务端决定是否往群里发卡片（send_to_conversation）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskEditorSheet(
    graph: AppGraph,
    fixedConversationId: Long?,
    parentId: Long?,
    initialGroupId: Long?,
    onDismiss: () -> Unit,
    onCreated: (TaskItem) -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val api = remember { TaskApi(graph.api) }
    val me = graph.session.session.collectAsState().value?.user
    val conversations by graph.conversations.items.collectAsState()
    val groupConversations = remember(conversations) { conversations.filter { it.isGroup } }

    var conversationId by remember { mutableStateOf(fixedConversationId ?: groupConversations.firstOrNull()?.id) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var due by remember { mutableStateOf<LocalDateTime?>(null) }
    var remind by remember { mutableStateOf<Int?>(null) }
    var assigneeIds by remember { mutableStateOf(setOfNotNull(me?.id)) }
    var watcherIds by remember { mutableStateOf(emptySet<Long>()) }
    var groupId by remember { mutableStateOf(initialGroupId) }
    var sendToConversation by remember { mutableStateOf(parentId == null) }
    var members by remember { mutableStateOf<List<User>>(emptyList()) }
    var taskGroups by remember { mutableStateOf<List<TaskGroupDef>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    var dialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { runCatching { api.groups() }.onSuccess { taskGroups = it } }
    LaunchedEffect(conversationId) {
        val id = conversationId ?: return@LaunchedEffect
        runCatching { api.members(id) }.onSuccess { members = it }
    }

    val canSubmit = title.isNotBlank() && conversationId != null && !submitting
    fun submit() {
        val id = conversationId ?: return
        submitting = true
        error = null
        scope.launch {
            runCatching {
                val body = buildJsonObject {
                    put("conversation_id", id)
                    put("title", title.trim())
                    put("description", description.trim())
                    put("priority", priority)
                    if (groupId != null) put("group_id", groupId!!)
                    if (parentId != null) put("parent_id", parentId)
                    due?.let {
                        put("due_at", toApiTime(it))
                        remind?.let { minutes -> put("remind_at", toApiTime(it.minusMinutes(minutes.toLong()))) }
                    }
                    put("assignee_ids", JsonArray(assigneeIds.map { JsonPrimitive(it) }))
                    put("watcher_ids", JsonArray(watcherIds.map { JsonPrimitive(it) }))
                    put("send_to_conversation", sendToConversation)
                }
                api.create(body)
            }.onSuccess {
                Haptics.confirm(view)
                onCreated(it)
            }.onFailure {
                Haptics.reject(view)
                error = it.userMessage()
                submitting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.panel,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (parentId != null) "新建子任务" else "新建任务", style = type.title, color = colors.text)
            Column(Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlainField(title, { title = it.take(200) }, "任务标题")
                PlainField(description, { description = it.take(4000) }, "添加描述（选填）", singleLine = false, minHeight = 76.dp)

                SheetRow("所在群聊", groupConversations.firstOrNull { it.id == conversationId }?.name() ?: if (conversationId != null) "会话 $conversationId" else "还没有群聊", enabled = fixedConversationId == null) { dialog = "conversation" }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priorities.forEach { meta ->
                        val on = priority == meta.value
                        Box(
                            Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (on) meta.color.copy(alpha = 0.16f) else colors.panelMuted)
                                .border(1.dp, if (on) meta.color else colors.panelMuted, RoundedCornerShape(10.dp))
                                .clickable { priority = meta.value },
                            contentAlignment = Alignment.Center,
                        ) { Text("${meta.code} ${meta.label}", color = if (on) meta.color else colors.muted, style = type.secondary, fontWeight = FontWeight.W600) }
                    }
                }
                SheetRow("截止时间", due?.let { dueLabel(toApiTime(it)) + (if (remind != null) " · ${remindLabel(remind)}" else "") } ?: "无") { dialog = "due" }
                SheetRow("负责人", memberSummary(members, assigneeIds)) { dialog = "assignees" }
                SheetRow("关注人", memberSummary(members, watcherIds)) { dialog = "watchers" }
                if (parentId == null) {
                    SheetRow("分组", taskGroups.firstOrNull { it.id == groupId }?.name ?: "默认分组") { dialog = "group" }
                }
                if (parentId == null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { sendToConversation = !sendToConversation },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("在群里发一张任务卡片", style = type.body, color = colors.text, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = sendToConversation, onCheckedChange = { sendToConversation = it })
                    }
                }
            }
            error?.let { Text(it, style = type.secondary, color = colors.danger) }
            PrimaryButton(if (submitting) "创建中…" else "创建", ::submit, Modifier.fillMaxWidth(), enabled = canSubmit)
        }
    }

    when (dialog) {
        "conversation" -> ChoiceDialog(
            "选择群聊", groupConversations.map { it.id to it.name() }, conversationId,
            onPick = { conversationId = it; assigneeIds = setOfNotNull(me?.id); watcherIds = emptySet(); dialog = null },
            onDismiss = { dialog = null },
        )
        "due" -> DuePickerDialog(due, remind, onConfirm = { due = it.due; remind = it.remindMinutes; dialog = null }, onDismiss = { dialog = null })
        "assignees" -> MemberPickDialog("负责人", members, assigneeIds, { assigneeIds = it; dialog = null }, { dialog = null })
        "watchers" -> MemberPickDialog("关注人", members, watcherIds, { watcherIds = it; dialog = null }, { dialog = null })
        "group" -> ChoiceDialog(
            "分组", listOf<Pair<Long?, String>>(null to "默认分组") + taskGroups.map { it.id to it.name }, groupId,
            onPick = { groupId = it; dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

internal fun memberSummary(members: List<User>, ids: Set<Long>): String {
    if (ids.isEmpty()) return "无"
    val names = ids.map { id -> members.firstOrNull { it.id == id }?.displayName ?: "用户 $id" }
    return if (names.size <= 2) names.joinToString("、") else "${names.take(2).joinToString("、")} 等 ${names.size} 人"
}

@Composable
internal fun SheetRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KcTheme.type.body, color = colors.muted)
        Text(
            value, style = KcTheme.type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
