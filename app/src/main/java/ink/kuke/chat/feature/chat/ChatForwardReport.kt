package ink.kuke.chat.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.messagePreview
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 选一个会话转发（POST /conversations/{target}/forward-bundle，合并成一条聊天记录发过去） */
@Composable
internal fun ForwardTargetSheet(controller: ChatController, count: Int, onDismiss: () -> Unit, onPick: (targetId: Long, note: String) -> Unit) {
    val colors = KcTheme.colors
    val conversations by controller.graph.conversations.items.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val list = conversations.filter { query.isBlank() || it.name().contains(query.trim(), ignoreCase = true) }
    KcSheet(onDismiss = onDismiss, title = "转发 $count 条消息给", maxHeight = 600.dp) {
        Box(Modifier.padding(horizontal = 20.dp)) { KcTextField(query, { query = it }, "搜索会话", Modifier.fillMaxWidth()) }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { KcTextField(note, { if (it.length <= 100) note = it }, "给聊天记录加一句话（可选）", Modifier.fillMaxWidth()) }
        if (list.isEmpty()) {
            StateText("没有可转发的会话")
        } else {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.id }) { conversation ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(conversation.id, note) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (conversation.isSelf) ink.kuke.chat.feature.selfchat.DeviceAvatar(40.dp) else Avatar(
                            if (conversation.isGroup) conversation.avatarUrl else conversation.directUser?.avatarUrl ?: conversation.avatarUrl,
                            conversation.name(),
                            tone = conversation.directUser?.id ?: conversation.id,
                            size = 40.dp,
                        )
                        Text(conversation.name(), style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        if (conversation.isGroup) Text("群聊", style = KcTheme.type.caption, color = colors.subtle)
                    }
                }
            }
        }
    }
}

/** 举报入口要的信息 */
internal data class ReportTarget(
    val type: String,
    val targetId: Long,
    val label: String,
    val conversationId: Long?,
    val messageId: Long? = null,
    val reportedUserId: Long? = null,
    /** 举报用户时可勾选的证据：被举报人在本会话里发过的消息，新的在前 */
    val evidence: List<Message> = emptyList(),
)

private val FallbackReasons = listOf("垃圾广告" to false, "辱骂骚扰" to false, "色情低俗" to false, "违法违规" to false, "诈骗或钓鱼" to false, "其他" to true)

/**
 * 举报（POST /reports）：选项和次数限制来自 GET /reports/options、/reports/quota。
 * 证据快照由服务端在提交时固化，客户端只传消息 id（见 KukeChat-Client reportForm.ts）。
 * 举报用户 / 群聊没有固定的那一条内容，必须给出依据：勾选证据消息、传截图，或写够字数的说明。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReportSheet(controller: ChatController, target: ReportTarget, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reasons by remember { mutableStateOf(FallbackReasons) }
    var minLength by remember { mutableStateOf(10) }
    var maxEvidence by remember { mutableStateOf(10) }
    var maxAttachments by remember { mutableStateOf(6) }
    var remaining by remember { mutableStateOf<Int?>(null) }
    var reason by remember { mutableStateOf(FallbackReasons.first().first) }
    var description by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<String>() }
    val evidenceIds = remember { mutableStateListOf<Long>() }
    var uploading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { controller.graph.api.get("/reports/options").asObj() }.onSuccess { obj ->
            val parsed = obj?.a("reasons")?.mapNotNull { element ->
                val item = element.asObj() ?: return@mapNotNull null
                (item.s("reason") ?: return@mapNotNull null) to (item.b("details_required") == true)
            }.orEmpty()
            if (parsed.isNotEmpty()) {
                reasons = parsed
                reason = parsed.first().first
            }
            obj?.i("details_min_length")?.let { minLength = it }
            obj?.i("max_evidence_messages")?.let { maxEvidence = it }
            obj?.i("max_attachments")?.let { maxAttachments = it }
        }
        runCatching { controller.graph.api.get("/reports/quota").asObj() }.onSuccess { remaining = it?.i("remaining_today") }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            runCatching { controller.uploadImage(context, uri) }.onSuccess { attachments.add(it) }.onFailure { error = it.userMessage() }
            uploading = false
        }
    }

    val clean = description.trim()
    val detailsRequired = reasons.firstOrNull { it.first == reason }?.second == true
    val needsEvidence = target.type == "user" || target.type == "conversation"
    val blocker = when {
        remaining != null && remaining!! <= 0 -> "今天的举报次数已经用完，明天再来吧。"
        detailsRequired && clean.length < minLength -> "选择「$reason」时请写明具体情况，至少 $minLength 个字。"
        needsEvidence && evidenceIds.isEmpty() && attachments.isEmpty() && clean.length < minLength -> "请${if (target.evidence.isNotEmpty()) "勾选证据消息、" else ""}上传截图，或写至少 $minLength 个字的说明。"
        else -> ""
    }

    KcSheet(onDismiss = onDismiss, title = "举报", maxHeight = 680.dp) {
        SheetForm {
            Text(target.label, style = KcTheme.type.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reasons.forEach { (name, _) -> Chip(name, reason == name) { reason = name } }
            }
            KcTextField(description, { if (it.length <= 300) description = it }, "补充说明（最多 300 字）", singleLine = false, minHeight = 90.dp)
            if (target.evidence.isNotEmpty()) {
                Text("勾选证据消息（最多 $maxEvidence 条）", style = KcTheme.type.secondary, color = colors.muted)
                target.evidence.take(20).forEach { message ->
                    val on = message.id in evidenceIds
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) colors.accentSoft else colors.panelMuted)
                            .clickable { if (on) evidenceIds.remove(message.id) else if (evidenceIds.size < maxEvidence) evidenceIds.add(message.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KcIcon(if (on) KcIcons.checkCircle else KcIcons.circle, tint = if (on) colors.accent else colors.subtle, size = 20.dp)
                        Text(messagePreview(message), style = KcTheme.type.secondary, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (uploading) "上传中…" else "添加截图（${attachments.size}/$maxAttachments）",
                    style = KcTheme.type.secondary,
                    color = if (attachments.size < maxAttachments && !uploading) colors.accent else colors.subtle,
                    modifier = Modifier.clickable(enabled = attachments.size < maxAttachments && !uploading) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                if (attachments.isNotEmpty()) Text("清除", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.clickable { attachments.clear() })
            }
            val message = error ?: blocker.ifEmpty { null }
            message?.let { Text(it, style = KcTheme.type.caption, color = colors.danger) }
            KcButton(if (busy) "提交中…" else "提交举报", {
                busy = true
                error = null
                scope.launch {
                    runCatching {
                        controller.graph.api.post("/reports", buildJsonObject {
                            put("target_type", target.type)
                            put("target_id", target.targetId)
                            put("reason", reason)
                            if (clean.isNotEmpty()) {
                                put("description", clean)
                                put("details", clean)
                            }
                            put("attachments", buildJsonArray { attachments.forEach { add(JsonPrimitive(it)) } })
                            if (evidenceIds.isNotEmpty()) put("evidence_message_ids", buildJsonArray { evidenceIds.forEach { add(JsonPrimitive(it)) } })
                            target.conversationId?.let { put("conversation_id", it) }
                            target.messageId?.let { put("message_id", it) }
                            target.reportedUserId?.let { put("reported_user_id", it) }
                        })
                    }.onSuccess {
                        toast(context, "举报已提交，管理员会保留当前证据进行审核")
                        onDismiss()
                    }.onFailure {
                        error = if (it is ApiException && it.code == "already_reviewed") "这条内容近期已经评审过，认定不违规。" else it.userMessage()
                    }
                    busy = false
                }
            }, enabled = blocker.isEmpty() && !busy && !uploading, modifier = Modifier.fillMaxWidth())
        }
    }
}
