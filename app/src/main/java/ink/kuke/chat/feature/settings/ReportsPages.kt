package ink.kuke.chat.feature.settings

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ------------------------------------------------------------------ 共用：写一段话的弹窗

/** 申诉、复议这类「写一段理由再提交」的对话框 */
@Composable
fun TextPromptDialog(
    title: String,
    hint: String,
    min: Int,
    max: Int,
    confirmText: String,
    note: String? = null,
    busy: Boolean = false,
    error: String? = null,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KcTheme.colors
    var text by remember { mutableStateOf("") }
    val length = text.trim().length
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = colors.panel,
        title = { Text(title, style = KcTheme.type.title, color = colors.text) },
        text = {
            Column {
                KcTextField(text, { text = it }, hint = hint, singleLine = false, minLines = 4, maxLength = max)
                Text("${length}/$max，至少 $min 个字" + (note?.let { "。$it" } ?: ""), style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                if (!error.isNullOrBlank()) Text(error, style = KcTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = length >= min && !busy, onClick = { onSubmit(text.trim()) }) {
                Text(if (busy) "提交中…" else confirmText, color = if (length >= min && !busy) colors.accent else colors.subtle, fontWeight = FontWeight.W600)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消", color = colors.muted) } },
    )
}

// ------------------------------------------------------------------ 举报记录

@Serializable
private data class ReportItem(
    val id: Long,
    @SerialName("target_type") val targetType: String = "",
    val reason: String = "",
    val description: String? = null,
    val details: String? = null,
    val status: String? = null,
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("result_title") val resultTitle: String? = null,
    @SerialName("result_text") val resultText: String? = null,
    @SerialName("can_reconsider") val canReconsider: Boolean = false,
    @SerialName("reconsider_status") val reconsiderStatus: String? = null,
)

private val TargetLabel = mapOf("message" to "消息", "user" to "用户", "conversation" to "会话", "post" to "动态")
private const val ReconsiderMin = 10
private const val ReconsiderMax = 500

/** 设置 › 举报记录：我提交过的举报和处理结果（GET /reports/me） */
@Composable
fun ReportsPage(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("reports.me")) {
        val element = graph.api.get("/reports/me")
        val array = (element as? JsonArray) ?: ((element as? JsonObject)?.get("items") as? JsonArray) ?: JsonArray(emptyList())
        KcJson.decodeFromJsonElement(ListSerializer(ReportItem.serializer()), array)
    }
    var reconsidering by remember { mutableStateOf<ReportItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(colors.chat)) {
        LoadBox(loader) { reports ->
            if (reports.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(120.dp))
                    KcIcon(KcIcons.flag, tint = colors.subtle, size = 32.dp)
                    Text("还没有举报记录", style = type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp))
                    Text("在消息或用户上长按即可举报，处理进度会显示在这里。", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(reports, key = { it.id }) { report ->
                        val status = when (report.status) {
                            "pending" -> "处理中" to colors.warn
                            "reviewed" -> "已处理" to colors.ok
                            "rejected" -> "未采纳" to colors.muted
                            else -> (report.status ?: "未知") to colors.muted
                        }
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(status.first, status.second)
                                Text(
                                    "  #${report.id} · 举报${TargetLabel[report.targetType] ?: report.targetType} · ${chinaTime(report.createdAt)}",
                                    style = type.caption, color = colors.muted, maxLines = 1,
                                )
                            }
                            Text(report.reason, style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600, modifier = Modifier.padding(top = 8.dp))
                            (report.description ?: report.details)?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                            }
                            val result = report.resultTitle?.let { t -> if (report.resultText.isNullOrBlank()) t else "$t。${report.resultText}" }
                                ?: report.reviewNote?.takeIf { it.isNotBlank() }
                                ?: if (report.status == "pending") "还在处理中，有结果会通知你。" else "没有更多说明。"
                            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(10.dp)) {
                                Text("处理结果：$result", style = type.secondary, color = colors.muted)
                            }
                            val reconsiderText = when (report.reconsiderStatus) {
                                "pending" -> "已申请复议，正在复审。有结果会通知你。"
                                "upheld" -> "复议结果：维持原结论，仍然认定不违规。一个案件只能复议一次。"
                                "overturned" -> "复议结果：改判为违规，已经按规则处理。"
                                else -> null
                            }
                            if (reconsiderText != null) {
                                Text(reconsiderText, style = type.caption, color = colors.accent, modifier = Modifier.padding(top = 8.dp))
                            } else if (report.canReconsider) {
                                Text("对「不违规」的结论有异议？可以申请一次复议，由另外几位资深风纪委员重新看。", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                                SecondaryButton("申请复议", onClick = { error = null; reconsidering = report }, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
        ToastHost(toast)
    }

    reconsidering?.let { report ->
        TextPromptDialog(
            title = "申请复议",
            hint = "说明你认为结论有误的理由：原来的评审漏看了什么、哪一点判断得不对。",
            min = ReconsiderMin, max = ReconsiderMax, confirmText = "提交",
            note = "一个案件只能复议一次，提交后不能修改",
            busy = busy, error = error,
            onDismiss = { reconsidering = null },
            onSubmit = { text ->
                scope.launch {
                    busy = true
                    runCatching { graph.api.post("/reports/${report.id}/reconsider", buildJsonObject { put("text", text) }) }
                        .onSuccess { reconsidering = null; toast.show("已提交复议申请", scope); loader.load() }
                        .onFailure { error = it.userMessage() }
                    busy = false
                }
            },
        )
    }
}

// ------------------------------------------------------------------ 处罚与申诉

@Serializable
private data class Violation(
    val id: Long,
    @SerialName("category_label") val categoryLabel: String = "",
    val level: Int = 0,
    val points: Int = 0,
    val source: String = "",
    @SerialName("target_type") val targetType: String? = null,
    val summary: String? = null,
    val status: String = "active",
    @SerialName("penalty_kind") val penaltyKind: String = "none",
    @SerialName("penalty_ends_at") val penaltyEndsAt: String? = null,
    @SerialName("penalty_permanent") val penaltyPermanent: Boolean = false,
    @SerialName("under_review") val underReview: Boolean = false,
    @SerialName("appeal_status") val appealStatus: String = "none",
    @SerialName("appeal_note") val appealNote: String? = null,
    @SerialName("appeal_result") val appealResult: String? = null,
    @SerialName("can_appeal") val canAppeal: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
private data class Standing(
    val points: Int = 0,
    @SerialName("point_window_days") val windowDays: Int = 30,
    @SerialName("muted_until") val mutedUntil: String? = null,
    @SerialName("mute_reason") val muteReason: String? = null,
    @SerialName("next_threshold") val nextThreshold: Int? = null,
    @SerialName("next_penalty") val nextPenalty: String? = null,
    val ban: BanInfo? = null,
    val violations: List<Violation> = emptyList(),
)

@Serializable
private data class BanInfo(val reason: String? = null, @SerialName("banned_until") val bannedUntil: String? = null, val permanent: Boolean = false)

private val SourceLabel = mapOf("auto" to "系统识别", "report" to "用户举报", "jury" to "风纪委员评审", "admin" to "管理员认定")
private val ViolationTarget = mapOf(
    "message" to "消息", "post" to "动态", "post_comment" to "动态评论", "post_topic" to "话题",
    "teamup_profile" to "组队名片", "teamup_comment" to "组队留言", "profile_cover" to "主页封面",
)
private val AppealLabel = mapOf("pending" to "申诉处理中", "accepted" to "申诉已通过", "rejected" to "申诉未通过")
private const val AppealMin = 5
private const val AppealMax = 2000

/** 处置编码（mute:24、ban:permanent）翻成人话，同客户端 describePenaltyCode */
private fun describePenalty(code: String?): String {
    if (code.isNullOrBlank() || code == "none") return "无"
    if (code == "notice") return "提醒"
    if (code == "warning") return "警告"
    val parts = code.split(':')
    val label = when (parts[0]) { "mute" -> "禁言"; "ban" -> "封禁"; else -> code }
    val amount = parts.getOrNull(1) ?: return label
    if (amount == "permanent") return "永久$label"
    val hours = amount.toIntOrNull() ?: return label
    if (hours <= 0) return label
    return if (hours % 24 == 0) "$label ${hours / 24} 天" else "$label $hours 小时"
}

private fun penaltyText(v: Violation): String {
    val period = when {
        v.penaltyPermanent -> "永久"
        v.penaltyEndsAt != null -> "至 ${chinaTime(v.penaltyEndsAt)}"
        else -> ""
    }
    return when (v.penaltyKind) {
        "notice" -> "提醒"
        "warning" -> "警告"
        "mute" -> "禁言" + if (period.isNotEmpty()) "，$period" else ""
        "ban" -> "封禁" + if (period.isNotEmpty()) "，$period" else ""
        else -> "仅处理内容，不记分"
    }
}

/** 设置 › 处罚与申诉：累计记分、当前禁言 / 封禁、处罚记录和申诉入口（GET /moderation/me） */
@Composable
fun PenaltiesPage(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("moderation.me")) { KcJson.decodeFromJsonElement(Standing.serializer(), graph.api.get("/moderation/me")) }
    var appealing by remember { mutableStateOf<Violation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(colors.chat)) {
        LoadBox(loader) { standing ->
            val mutedUntil = ink.kuke.chat.core.ui.parseInstant(standing.mutedUntil)?.isAfter(java.time.Instant.now()) == true
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "summary") {
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${standing.points}", style = type.display, color = colors.text)
                            Text(" 分", style = type.secondary, color = colors.muted, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Text(
                            "近 ${standing.windowDays} 天累计记分，满 ${standing.windowDays} 天自动失效。" +
                                (standing.nextThreshold?.let { "累计到 $it 分会触发：${describePenalty(standing.nextPenalty)}。" } ?: "已达到最高一档处置。"),
                            style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 6.dp),
                        )
                        standing.ban?.let { ban ->
                            Text(
                                "账号封禁中，" + (if (ban.permanent || ban.bannedUntil == null) "永久封禁" else "至 ${chinaTime(ban.bannedUntil)}") + "。" + (ban.reason?.let { "原因：$it" } ?: ""),
                                style = type.secondary, color = colors.danger, modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        if (mutedUntil) {
                            Text(
                                "禁言中，${chinaTime(standing.mutedUntil)} 解除。期间可以正常登录和查看，但不能发布内容。" + (standing.muteReason?.let { "原因：$it" } ?: ""),
                                style = type.secondary, color = colors.warn, modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
                if (standing.violations.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            KcIcon(KcIcons.shieldCheck, tint = colors.ok, size = 32.dp)
                            Text("没有处罚记录", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                items(standing.violations, key = { it.id }) { v ->
                    val revoked = v.status == "revoked"
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.categoryLabel, style = type.rowTitle, color = if (revoked) colors.muted else colors.text, fontWeight = FontWeight.W600)
                            Spacer(Modifier.weight(1f))
                            if (revoked) Pill("已撤销", colors.muted) else Pill(if (v.level > 0) "L${v.level} · 记 ${v.points} 分" else "不记分", colors.danger)
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (v.underReview) Pill("管理员复核中", colors.warn)
                            AppealLabel[v.appealStatus]?.let { Pill(it, colors.accent) }
                        }
                        v.summary?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                        }
                        Text(
                            "处理结果：${penaltyText(v)} · 来源：${SourceLabel[v.source] ?: v.source}" +
                                (v.targetType?.let { "（${ViolationTarget[it] ?: it}）" } ?: "") +
                                (if (!revoked && v.points > 0 && v.expiresAt != null) " · 记分 ${chinaTime(v.expiresAt)} 失效" else ""),
                            style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(chinaTime(v.createdAt), style = type.caption, color = colors.subtle, modifier = Modifier.padding(top = 2.dp))
                        v.appealNote?.takeIf { it.isNotBlank() }?.let {
                            Text("处理说明：$it", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
                        }
                        if (v.canAppeal) {
                            SecondaryButton("申诉", onClick = { error = null; appealing = v }, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
        ToastHost(toast)
    }

    appealing?.let { violation ->
        TextPromptDialog(
            title = "申诉",
            hint = "说明你认为处理有误的理由。每条处罚只能申诉一次，请写清楚。",
            min = AppealMin, max = AppealMax, confirmText = "提交申诉",
            busy = busy, error = error,
            onDismiss = { appealing = null },
            onSubmit = { text ->
                scope.launch {
                    busy = true
                    runCatching { graph.api.post("/moderation/violations/${violation.id}/appeal", buildJsonObject { put("text", text) }) }
                        .onSuccess { appealing = null; toast.show("申诉已提交", scope); loader.load() }
                        .onFailure { error = it.userMessage() }
                    busy = false
                }
            },
        )
    }
}
