package ink.kuke.chat.feature.jury

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ChoiceDialog
import ink.kuke.chat.feature.tasks.DialogCard
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 我投过票的案件（回看）。评审编号 = 路由里的 caseId，不是公示栏的公开编号 */
@Composable
internal fun JuryCaseDetailPage(ctx: JuryCtx, caseId: Long) {
    KcPage(title = reviewNo(caseId), onBack = { ctx.back() }) {
        CaseDetailBody(ctx, caseId, null)
    }
}

/**
 * 案件投后视图：我的票、进度与结论、证据、观点区。
 * 只有投过票的人读得到；内容过了保留期服务端会清掉，这里就只剩结论。
 */
@Composable
internal fun CaseDetailBody(ctx: JuryCtx, caseId: Long, initial: JuryCase?, reward: Reward? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var detail by remember(caseId) { mutableStateOf(initial?.takeIf { it.myVote != null }) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var markPrecedent by remember { mutableStateOf(false) }

    LaunchedEffect(caseId, tick) {
        runCatching { ctx.api.caseDetail(caseId) }
            .onSuccess { detail = it; error = null }
            .onFailure { if (detail == null) error = it.userMessage() }
    }
    // 案件定案 / 被改判时刷新
    LaunchedEffect(caseId) {
        ctx.graph.realtime.events.collect { event ->
            if (event.type == "jury.case.settled" || event.type == "jury.opinion.created" || event.type == "jury.opinion.changed") tick++
        }
    }

    val current = detail
    if (current == null) {
        if (error != null) ErrorBlock(error!!, { tick++ }) else CenterText("正在加载案件…")
        return
    }
    val mine = current.myVote
    if (markPrecedent) {
        PrecedentEditorDialog(ctx, caseId = caseId, editing = null, onDismiss = { markPrecedent = false }) { markPrecedent = false; tick++ }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (reward != null && (reward.points != 0 || reward.items.isNotEmpty())) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.ok.copy(alpha = 0.12f)).padding(12.dp)) {
                Text("${if (reward.points >= 0) "+" else ""}${reward.points} 风纪值（现在共 ${reward.total}）", style = type.rowTitle, color = colors.ok)
                reward.items.forEach { Text("${it.label}  ${if (it.points >= 0) "+" else ""}${it.points}", style = type.caption, color = colors.muted) }
            }
        }

        // 质控题：投完当场揭晓
        if (current.isQualityCheck) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.warn.copy(alpha = 0.12f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("这是一道质控题", style = type.rowTitle, color = colors.warn)
                val verdict = current.verdict
                if (verdict != null) {
                    Text("标准结论：${outcomeLabel(verdict.outcome)}${verdict.level?.let { " · ${levelText(it)}" } ?: ""}${verdict.categoryLabel?.let { " · $it" } ?: ""}", style = type.secondary, color = colors.text)
                    verdict.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.secondary, color = colors.muted) }
                }
                mine?.agreed?.let { Text(if (it) "你的判断和标准结论一致" else "你的判断和标准结论不一致", style = type.secondary, color = if (it) colors.ok else colors.danger) }
            }
        }

        // 我的票
        if (mine != null) {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("我的判断", style = type.group, color = colors.muted)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(voteLabel(mine.vote), VoteColors.of(mine.vote), filled = true)
                        mine.alias?.let { Text(it, style = type.caption, color = colors.muted) }
                        Text(dateTimeText(mine.votedAt), style = type.caption, color = colors.subtle)
                    }
                    mine.category?.let { key -> ctx.config.categories.firstOrNull { it.key == key }?.let { Text("类别：${it.label}", style = type.secondary, color = colors.muted) } }
                    mine.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.body, color = colors.text) }
                    when (mine.agreed) {
                        true -> Text("与结论一致", style = type.secondary, color = colors.ok)
                        false -> Text("与结论不一致", style = type.secondary, color = colors.danger)
                        null -> Unit
                    }
                    mine.points?.takeIf { it != 0 }?.let { Text("这个案件给了你 ${if (it > 0) "+" else ""}$it 风纪值", style = type.caption, color = colors.muted) }
                }
            }
        }

        // 进度 / 结论
        if (!current.isQualityCheck) {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("案件进度", style = type.group, color = colors.muted)
                    val verdict = current.verdict
                    if (verdict == null) {
                        Text("${stageLabel(current.stage).ifBlank { "评审中" }} · 已有 ${current.votesCast} 张有效票", style = type.body, color = colors.text)
                        Text("定案后才会显示票数分布和结论（${consensusRuleText(ctx.config.rules, ctx.config.has("panel"))}）", style = type.caption, color = colors.muted)
                    } else {
                        Text(
                            "${outcomeLabel(verdict.outcome)}${verdict.level?.let { " · ${levelText(it)}" } ?: ""}${verdict.categoryLabel?.let { " · $it" } ?: ""}",
                            style = type.rowTitle, color = if (verdict.outcome == "violation") colors.danger else colors.ok,
                        )
                        Text("${sourceLabel(verdict.source)} · 用时 ${hoursText(verdict.hours)} · ${dateTimeText(verdict.closedAt)}", style = type.caption, color = colors.muted)
                        if (verdict.overturned) {
                            Text("已改判${verdict.overturnNote?.let { "：$it" } ?: ""}", style = type.secondary, color = colors.warn)
                        }
                        current.tally?.let { TallyBars(it) }
                    }
                    if (current.publicCaseId != null) {
                        SoftButton("在公示栏查看", { ctx.go(JuryPage.BulletinCase(current.publicCaseId)) }, Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        // 判例：已是判例就指过去；还不是、而我（资深）有资格标，就给「标为判例」
        if (ctx.config.has("precedents") && !current.isQualityCheck) {
            val precedent = current.precedent
            if (precedent != null) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("判例", style = type.group, color = colors.muted)
                        Text("这个案件被标为判例，附有一段裁判要旨。", style = type.secondary, color = colors.muted)
                        SoftButton(precedent.title.ifBlank { "查看裁判要旨" }, { ctx.go(JuryPage.Precedent(precedent.id)) })
                    }
                }
            } else if (current.canMarkPrecedent) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("判例", style = type.group, color = colors.muted)
                        Text("如果这个案件的结论能帮大家对齐同类情况的尺度，可以把它标为判例，并写一段裁判要旨。", style = type.secondary, color = colors.muted)
                        SoftButton("标为判例", { markPrecedent = true })
                    }
                }
            }
        }

        // 证据
        Text("案件内容（已匿名）", style = type.group, color = colors.muted)
        if (!current.contentAvailable) {
            Text("定案超过 ${ctx.config.rules.contentRetentionDays} 天，案件内容已按规定清除，只保留结论。", style = type.secondary, color = colors.muted)
        } else {
            CaseEvidence(current.kind, current.messages, current.post, ctx.config.rules.contextNear)
        }

        if (current.opinionsEnabled && !current.isQualityCheck) {
            OpinionsSection(ctx, caseId, current.opinionCount, onChanged = { tick++ })
        }
    }
}

/** 票数分布：不违规 · 轻微 · 一般 · 严重 · 弃权 */
@Composable
internal fun TallyBars(tally: kotlinx.serialization.json.JsonObject) {
    val colors = KcTheme.colors
    val rows = listOf("none", "l1", "l2", "l3", "abstain").map { it to tally.count(it) } +
        (tally.count("need_context").takeIf { it > 0 }?.let { listOf("need_context" to it) } ?: emptyList())
    val max = rows.maxOf { it.second }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        rows.forEach { (vote, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(voteLabel(vote, short = true), style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.width(72.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.panelMuted)) {
                    Box(Modifier.fillMaxWidth(count.toFloat() / max).height(8.dp).clip(RoundedCornerShape(4.dp)).background(VoteColors.of(vote)))
                }
                Text("$count", style = KcTheme.type.caption, color = colors.text, modifier = Modifier.width(32.dp).padding(start = 8.dp))
            }
        }
    }
}

/** 观点区：只有投过票的委员能看到；发表、回复、点赞、举报受处罚期间会被服务端拒绝 */
@Composable
private fun OpinionsSection(ctx: JuryCtx, caseId: Long, count: Int, onChanged: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var items by remember(caseId) { mutableStateOf<List<Opinion>>(emptyList()) }
    var loading by remember(caseId) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Opinion?>(null) }
    var reportTarget by remember { mutableStateOf<Opinion?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(caseId, reload, count) {
        runCatching { ctx.api.opinions(caseId) }
            .onSuccess { items = it; error = null }
            .onFailure { error = it.userMessage() }
        loading = false
    }

    Text("观点区", style = type.group, color = colors.muted)
    when {
        loading -> Text("正在加载观点…", style = type.secondary, color = colors.muted)
        error != null -> Text(error!!, style = type.secondary, color = colors.muted)
        items.isEmpty() -> Text("还没有观点。投过票的委员可以在这里匿名讨论。", style = type.secondary, color = colors.subtle)
        else -> items.forEach { opinion ->
            OpinionRow(opinion, onReply = { replyTo = it }, onLike = {
                scope.launch { runCatching { ctx.api.likeOpinion(it.id, !it.liked) }.onSuccess { reload++ }.onFailure { error = it.userMessage() } }
            }, onDelete = {
                scope.launch { runCatching { ctx.api.deleteOpinion(it.id) }.onSuccess { reload++; onChanged() }.onFailure { error = it.userMessage() } }
            }, onReport = { reportTarget = it })
            opinion.replies.forEach { reply ->
                Box(Modifier.padding(start = 24.dp)) {
                    OpinionRow(reply, onReply = { replyTo = opinion }, onLike = {
                        scope.launch { runCatching { ctx.api.likeOpinion(it.id, !it.liked) }.onSuccess { reload++ } }
                    }, onDelete = {
                        scope.launch { runCatching { ctx.api.deleteOpinion(it.id) }.onSuccess { reload++; onChanged() } }
                    }, onReport = { reportTarget = it })
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        replyTo?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("回复 ${it.alias}", style = type.caption, color = colors.accent, modifier = Modifier.weight(1f))
                Text("取消", style = type.caption, color = colors.muted, modifier = Modifier.clickable { replyTo = null })
            }
        }
        PlainField(draft, { draft = it.take(ctx.config.opinions.maxLength) }, "说说你的看法（匿名，最多 ${ctx.config.opinions.maxLength} 字）", singleLine = false, minHeight = 56.dp)
        PrimaryButton(if (sending) "发送中…" else "发表", {
            sending = true
            scope.launch {
                runCatching { ctx.api.postOpinion(caseId, draft.trim(), replyTo?.id) }
                    .onSuccess { Haptics.confirm(view); draft = ""; replyTo = null; reload++; onChanged() }
                    .onFailure { Haptics.reject(view); error = it.userMessage() }
                sending = false
            }
        }, Modifier.fillMaxWidth(), enabled = draft.isNotBlank() && !sending)
    }

    reportTarget?.let { target ->
        val reasons = ctx.config.opinions.reportReasons.ifEmpty { listOf("人身攻击", "泄露身份或隐私", "照抄违规原文", "与案件无关", "其他") }
        ChoiceDialog("举报这条观点", reasons.map { it to it }, null, onPick = { reason ->
            reportTarget = null
            scope.launch {
                runCatching { ctx.api.reportOpinion(target.id, reason, if (reason == "其他") "其他原因（来自手机端举报）" else null) }
                    .onSuccess { error = "已举报，感谢你维护讨论环境"; reload++ }
                    .onFailure { error = it.userMessage() }
            }
        }, onDismiss = { reportTarget = null })
    }
}

@Composable
private fun OpinionRow(opinion: Opinion, onReply: (Opinion) -> Unit, onLike: (Opinion) -> Unit, onDelete: (Opinion) -> Unit, onReport: (Opinion) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(opinion.alias + if (opinion.mine) "（我）" else "", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600)
            if (opinion.tier != null) Tag(tierLabel(opinion.tier), colors.muted)
            opinion.vote?.let { Tag(voteLabel(it, short = true), VoteColors.of(it)) }
            if (opinion.isNote) Tag("投票备注", colors.subtle)
        }
        when (opinion.state) {
            "deleted" -> Text("这条观点已删除", style = type.secondary, color = colors.subtle)
            "hidden" -> Text("这条观点被多位委员举报后折叠，等待管理员处理", style = type.secondary, color = colors.subtle)
            else -> {
                opinion.replyToAlias?.let { Text("回复 $it", style = type.caption, color = colors.accent) }
                Text(opinion.content.orEmpty(), style = type.body, color = colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        (if (opinion.liked) "已赞 " else "赞 ") + opinion.likeCount, style = type.caption,
                        color = if (opinion.liked) colors.accent else colors.muted,
                        modifier = Modifier.clickable(enabled = !opinion.mine) { onLike(opinion) },
                    )
                    Text("回复", style = type.caption, color = colors.muted, modifier = Modifier.clickable { onReply(opinion) })
                    if (opinion.mine) Text("删除", style = type.caption, color = colors.danger, modifier = Modifier.clickable { confirmDelete = true })
                    else Text("举报", style = type.caption, color = colors.muted, modifier = Modifier.clickable { onReport(opinion) })
                }
            }
        }
    }
    if (confirmDelete) {
        DialogCard({ confirmDelete = false }, "删除这条观点") {
            Text("删除后别人就看不到了。", style = type.secondary, color = colors.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("取消", { confirmDelete = false }, Modifier.weight(1f))
                PrimaryButton("删除", { confirmDelete = false; onDelete(opinion) }, Modifier.weight(1f), color = colors.danger)
            }
        }
    }
}
