package ink.kuke.chat.feature.groupplus

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.ImageViewer
import ink.kuke.chat.feature.chat.KcButton
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.ReadOnlyActions
import ink.kuke.chat.feature.chat.ReadOnlyMessageBody
import ink.kuke.chat.feature.chat.copyText
import ink.kuke.chat.feature.chat.fullTime
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 聊天页顶部的群待办条（每群最多一条，可收起 / 展开）：
 * 点条 = 原消息还在就跳过去，已被清理 / 撤回就打开「查看全文」（快照）；展开后有 查看全文 / 跳转 / 取消（群主管理员）。
 * 实时：group.todo.updated 带完整的 todo（或 null = 已取消），直接换。
 */
@Composable
fun GroupTodoBar(
    graph: AppGraph,
    conversationId: Long,
    initial: JsonObject?,
    staff: Boolean,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { GroupPlusApi(graph) }
    var todo by remember(initial) { mutableStateOf(GroupTodo.parse(initial)) }
    var expanded by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        graph.realtime.events.collect { event ->
            if (event.type != "group.todo.updated") return@collect
            if ((event.data["conversation_id"] as? JsonPrimitive)?.content?.toLongOrNull() != conversationId) return@collect
            todo = GroupTodo.parse(event.data["todo"] as? JsonObject)
        }
    }

    val current = todo ?: return
    val jumpable = current.messageExists && !current.recalled

    Column(modifier.fillMaxWidth().background(colors.panel).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable { if (jumpable) onJump(current.messageId) else showFull = true }
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcIcon(KcIcons.checkSquare, tint = colors.accent, size = 18.dp)
            Text("群待办", style = type.secondary, color = colors.accent, fontWeight = FontWeight.W700, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
            Text(
                if (current.recalled) "[消息已撤回]" else current.summary.ifBlank { "[消息]" },
                style = type.secondary,
                color = if (current.recalled) colors.subtle else colors.text,
                maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                KcIcon(KcIcons.chevronDown, tint = colors.subtle, size = 18.dp, modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f }, contentDescription = if (expanded) "收起" else "展开")
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(current.senderName)
                        current.setByName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it).append(" 设置") }
                        if (!current.messageExists && !current.recalled) append(" · 原消息已清理")
                    },
                    style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                BarAction("查看全文") { showFull = true }
                if (jumpable) BarAction("跳转") { onJump(current.messageId) }
                if (staff) BarAction("取消待办", danger = true) { confirmClear = true }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }

    if (showFull) TodoFullTextSheet(current, conversationId, onDismiss = { showFull = false })

    if (confirmClear) {
        KcConfirm(
            title = "取消群待办",
            text = "取消后群里不再置顶这条待办，不会再发提醒。",
            confirmLabel = "取消待办",
            danger = true,
            onConfirm = {
                confirmClear = false
                if (busy) return@KcConfirm
                busy = true
                scope.launch {
                    runCatching { api.clearTodo(conversationId) }
                        .onSuccess { todo = null }
                        .onFailure {
                            // 已经没有待办了（别人先取消）：条直接收掉
                            if ((it as? ink.kuke.chat.core.net.ApiException)?.code == "todo_not_found") todo = null else toast(context, it.userMessage())
                        }
                    busy = false
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun BarAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Text(
        label,
        style = KcTheme.type.caption,
        fontWeight = FontWeight.W600,
        color = if (danger) colors.danger else colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** 查看全文：快照里的完整内容（文字 / 图片 / 合并转发 / 投票 / 文件都用聊天页同一套渲染） */
@Composable
private fun TodoFullTextSheet(todo: GroupTodo, conversationId: Long, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<String?>(null) }
    val message = remember(todo) {
        Message(
            id = todo.messageId,
            conversationId = conversationId,
            senderId = todo.senderId,
            sender = User(id = todo.senderId ?: 0, nickname = todo.senderName, avatarUrl = todo.senderAvatar),
            senderDisplayName = todo.senderName,
            type = todo.snapshotType,
            content = todo.snapshotContent,
            metadata = todo.snapshotMetadata,
            createdAt = todo.snapshotAt.orEmpty(),
        )
    }
    val actions = remember { ReadOnlyActions(openImage = { viewing = it }, openBundle = {}, openFile = {}, openShare = {}) }
    KcSheet(onDismiss = onDismiss, title = "群待办", maxHeight = 600.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(todo.senderAvatar, todo.senderName, tone = todo.senderId ?: 0, size = 36.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(todo.senderName + (todo.senderTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = type.rowTitle, color = colors.text)
                    if (!todo.snapshotAt.isNullOrBlank()) Text(fullTime(todo.snapshotAt), style = type.caption, color = colors.subtle)
                }
            }
            when {
                todo.recalled -> Text("这条消息已被撤回，内容不再显示。", style = type.secondary, color = colors.muted)
                todo.snapshotType == "text" -> Text(todo.snapshotContent, style = type.body, color = colors.text)
                else -> ReadOnlyMessageBody(message, actions)
            }
            if (!todo.messageExists && !todo.recalled) Text("原消息已不在聊天记录里，这里是设置待办时保存的快照。", style = type.caption, color = colors.subtle)
            if (!todo.recalled && todo.snapshotContent.isNotBlank()) {
                KcButton("复制全文", {
                    copyText(context, todo.snapshotContent)
                    toast(context, "已复制")
                }, Modifier.fillMaxWidth(), primary = false)
            }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}
