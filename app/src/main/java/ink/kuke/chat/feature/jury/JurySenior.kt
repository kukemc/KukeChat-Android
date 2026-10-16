package ink.kuke.chat.feature.jury

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/**
 * 资深工作台：合议 · 申诉复审 · 复审记录。一次一件，投完才发下一件。
 * 内容全部是举报时固化的匿名快照；原审观点只读。
 */
@Composable
internal fun JurySeniorPage(ctx: JuryCtx) {
    var tab by rememberSaveable { mutableStateOf("panel") }
    val queue = ctx.status?.senior
    val seats = (ctx.config.panel ?: SeniorRules()).seats
    val subtitle = when (tab) {
        "panel" -> "普通评审定不下来的案件，由 $seats 位资深委员合议"
        "appeals" -> "对处罚的申诉、举报人的复议，先由资深委员复审"
        else -> "你参与过的申诉复审和举报复议"
    }
    KcPage("资深工作台", subtitle = subtitle, onBack = { ctx.back() }) {
        Column(Modifier.fillMaxSize()) {
            FilterChips(
                listOf(
                    "panel" to ("合议" + (queue?.panel?.takeIf { it > 0 }?.let { " · $it" } ?: "")),
                    "appeals" to ("申诉复审" + (queue?.appeals?.takeIf { it > 0 }?.let { " · $it" } ?: "")),
                    "history" to "复审记录",
                ),
                tab,
            ) { tab = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    tab == "panel" -> if (ctx.config.has("panel")) PanelDesk(ctx) else SeniorNotice("合议还没有开放", "服务端开放资深合议后，这里会出现待合议的案件。")
                    !ctx.config.has("appeal_review") -> SeniorNotice("申诉复审还没有开放", "现在所有申诉都直接由管理员处理。")
                    tab == "appeals" -> AppealDesk(ctx, onHistory = { tab = "history" })
                    else -> AppealHistory(ctx)
                }
            }
        }
    }
}

/** 领不到时的原因：不是资深 / 受处罚 / 没有待办 / 失败 */
private data class SeniorIdle(val title: String, val text: String, val retry: Boolean = false)

private fun seniorBlocked(status: JuryStatus?): SeniorIdle? = when {
    status == null -> null
    !status.isSenior -> SeniorIdle("只有在任的资深委员能处理", "正式委员满足晋升条件后会成为资深委员。")
    status.restriction != null -> SeniorIdle("受处罚期间不能处理", status.restriction.message.ifBlank { "受处罚期间不能参与评审" })
    else -> null
}

private fun seniorFailure(error: Throwable): SeniorIdle = when (error.apiCode()) {
    "not_senior" -> SeniorIdle("只有在任的资深委员能处理", "正式委员满足晋升条件后会成为资深委员。")
    "juror_restricted" -> SeniorIdle("受处罚期间不能处理", error.message ?: "受处罚期间不能参与评审")
    else -> SeniorIdle("领取失败", error.userMessage(), retry = true)
}

@Composable
private fun SeniorNotice(title: String, text: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(KcTheme.colors.panelMuted), contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.shieldCheck, tint = KcTheme.colors.muted, size = 26.dp)
        }
        Text(title, style = KcTheme.type.title, color = KcTheme.colors.text, modifier = Modifier.padding(top = 14.dp), textAlign = TextAlign.Center)
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
        if (onRetry != null) SoftButton("刷新", onRetry, Modifier.padding(top = 16.dp))
    }
}

/** 一行提示条：绿色是我自己的操作结果，灰色是「手里那件已经不在了」 */
@Composable
private fun Flash(text: String, ok: Boolean) {
    val colors = KcTheme.colors
    val tone = if (ok) colors.ok else colors.muted
    Text(text, style = KcTheme.type.secondary, color = if (ok) tone else colors.text, modifier = Modifier.fillMaxWidth().background(tone.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 8.dp))
}

/** 席位：几个座位亮了几个，只表示票数，不透露别人投了什么 */
@Composable
internal fun SeatDots(cast: Int, total: Int) {
    val colors = KcTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(maxOf(total, cast)) { index ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (index < cast) colors.accent else colors.panelMuted).border(1.dp, if (index < cast) colors.accent else colors.borderStrong, CircleShape))
        }
    }
}

/** 带标题的白卡片 */
@Composable
private fun TitledCard(title: String, aside: String? = null, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = KcTheme.type.rowTitle, color = KcTheme.colors.text, modifier = Modifier.weight(1f))
                if (aside != null) Text(aside, style = KcTheme.type.caption, color = KcTheme.colors.subtle)
            }
            content()
        }
    }
}

/* ───────────────────────── 资深合议 ───────────────────────── */

private data class PanelDone(val caseId: Long, val vote: String, val settled: Boolean, val detail: JuryCase?, val reward: Reward?)

@Composable
private fun PanelDesk(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val rules = ctx.config.panel ?: SeniorRules()
    var case by remember { mutableStateOf<PanelCase?>(null) }
    var idle by remember { mutableStateOf<SeniorIdle?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var voteError by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var done by remember { mutableStateOf<PanelDone?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val blocked = seniorBlocked(ctx.status)

    LaunchedEffect(reload, blocked) {
        if (blocked != null) { idle = blocked; case = null; loading = false; return@LaunchedEffect }
        loading = true
        runCatching { ctx.api.panelNext() }
            .onSuccess { case = it; idle = if (it == null) SeniorIdle("现在没有等你合议的案件", "有新的合议案件时会在这里出现，首页的角标也会提醒你。", retry = true) else null }
            .onFailure { case = null; idle = seniorFailure(it) }
        loading = false
    }

    fun submit(current: PanelCase, vote: String, note: String, category: String?) {
        if (submitting) return
        submitting = true
        voteError = null
        scope.launch {
            runCatching { ctx.api.panelVote(current.caseId, vote, note, category) }
                .onSuccess { result ->
                    Haptics.confirm(view)
                    ctx.refresh()
                    if (vote == "recuse" || result.detail == null) {
                        flash = "已回避合议 #${current.caseId}。有别的资深委员可以顶上时会改派；凑不齐 ${rules.seats} 位时转给管理员。" to true
                        reload++
                    } else {
                        flash = null
                        done = PanelDone(current.caseId, vote, result.settled, result.detail, result.reward)
                    }
                }
                .onFailure { error ->
                    Haptics.reject(view)
                    when (error.apiCode()) {
                        "already_voted", "already_recused" -> {
                            flash = "合议 #${current.caseId}：" + (if (error.apiCode() == "already_voted") "你已经投过合议票了，这次没有重复记票。" else "你已经回避过这个案件。") to false
                            ctx.refresh()
                            reload++
                        }
                        else -> voteError = error.userMessage()
                    }
                }
            submitting = false
        }
    }

    done?.let { result ->
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(colors.ok.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("合议 #${result.caseId} · 已提交：${voteLabel(result.vote)}", style = type.rowTitle, color = colors.ok)
                Text(
                    (if (result.settled) "已有 ${rules.consensusVotes} 票一致，案件定案。" else "还差其他合议委员的票，${rules.consensusVotes} 票一致即定案。") +
                        "下面的观点区对原审委员和合议委员开放。",
                    style = type.caption, color = colors.muted,
                )
                PrimaryButton("下一件", { done = null; reload++ }, Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f)) { CaseDetailBody(ctx, result.caseId, result.detail, reward = result.reward) }
        }
        return
    }

    val current = case
    Column(Modifier.fillMaxSize()) {
        flash?.let { Flash(it.first, it.second) }
        when {
            loading && current == null -> CenterText("正在领取合议案件…")
            idle != null -> SeniorNotice(idle!!.title, idle!!.text, if (idle!!.retry) ({ reload++ }) else null)
            current != null -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("合议 #${current.caseId}", style = type.title, color = colors.text)
                        Tag(kindLabel(current.kind), colors.accent)
                        if (current.caseOrigin == "machine") Tag("机器筛查", colors.warn)
                    }
                    remainingText(current.panel.expiresAt)?.let {
                        Text("合议还剩 $it，到期没有定案会转给管理员", style = type.caption, color = colors.muted)
                    }
                    WhyHere(ctx, current, rules)
                    TitledCard("案件内容", if (current.caseOrigin == "machine") "机器筛查 · 没有举报人" else "${reasonsText(current.reasons)} · ${current.reportCount} 人举报") {
                        AnonNote()
                        CaseEvidence(current.kind, current.messages, current.post, ctx.config.rules.contextNear)
                    }
                    TitledCard("原审观点 ${current.origin.opinions.sumOf { 1 + it.replies.size }}", "只读 · 匿名代号") {
                        OriginOpinions(current.origin.opinions)
                    }
                }
                VotePanel(ctx, current.asCase(), submitting, voteError, panel = true) { vote, note, category -> submit(current, vote, note, category) }
            }
            else -> ErrorBlock("没有拿到案件", { reload++ })
        }
    }
}

@Composable
private fun AnonNote() {
    Text(
        "发言人都用代称，看不到是谁、也看不到是哪个群。请只根据内容本身判断。",
        style = KcTheme.type.caption, color = KcTheme.colors.muted,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KcTheme.colors.panelMuted).padding(10.dp),
    )
}

/** 为什么来到合议：原因、原审分布、合议规则 */
@Composable
private fun WhyHere(ctx: JuryCtx, case: PanelCase, rules: SeniorRules) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val jr = ctx.config.rules
    TitledCard(panelReasonLabel(case.origin.reason)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatDots(case.panel.votesCast, case.panel.seats)
            Text("合议 ${case.panel.seats} 席 · 已投 ${case.panel.votesCast}", style = type.caption, color = colors.muted)
        }
        Text(
            when (case.origin.reason) {
                "need_context" -> "原审里有多位委员认为现有上下文不足以判断。你看到的是同一份内容，快照之外的消息已经取不到了，请在这个前提下给出判断。"
                "timeout" -> "这个案件在委员队列里超过 ${jr.timeoutHours} 小时没有凑够票。原审留下的票不多，主要靠合议来定。"
                else -> "原审没有形成共识（${consensusRuleText(jr, true)}）分歧在哪，看下面的票数分布和每位委员写的理由。"
            } + if (case.caseOrigin == "machine") " 这个案件来自机器筛查，没有举报人。" else "",
            style = type.secondary, color = colors.text,
        )
        case.origin.tally?.let {
            Text("原审票数分布", style = type.caption, color = colors.muted)
            TallyBars(it)
        }
        Text(
            "合议 ${rules.seats} 席，${rules.consensusVotes} 票一致即定案，不能弃权；${rules.timeoutHours} 小时内没有定案会转给管理员。请写明理由，它会留在本案观点区。",
            style = type.caption, color = colors.subtle,
        )
    }
}

/** 原审观点：按票分组，只读（不能赞、回、举报） */
@Composable
internal fun OriginOpinions(opinions: List<Opinion>) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    if (opinions.isEmpty()) {
        Text("原审委员没有留下理由，只能根据票数分布和内容本身来判断。", style = type.secondary, color = colors.subtle)
        return
    }
    val order = listOf("none", "l1", "l2", "l3", "need_context", "abstain")
    val groups = order.map { vote -> vote to opinions.filter { it.vote == vote } } + ("" to opinions.filter { it.vote == null || it.vote !in order })
    groups.filter { it.second.isNotEmpty() }.forEach { (vote, items) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (vote.isEmpty()) Tag("没有标明", colors.muted) else Tag(voteLabel(vote, short = true), VoteColors.of(vote), filled = true)
            Text("${items.size} 条观点", style = type.caption, color = colors.muted)
        }
        items.forEach { root ->
            ReadOnlyOpinion(root, reply = false)
            root.replies.forEach { ReadOnlyOpinion(it, reply = true) }
        }
    }
}

@Composable
private fun ReadOnlyOpinion(opinion: Opinion, reply: Boolean) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(
        Modifier.padding(start = if (reply) 20.dp else 0.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(opinion.alias.ifBlank { "委员" }, style = type.caption, color = colors.text, fontWeight = FontWeight.W600)
            opinion.tier?.let { Tag(tierLabel(it), colors.muted) }
            if (opinion.isNote) Tag("投票备注", colors.subtle)
            if (opinion.likeCount > 0) Text("赞 ${opinion.likeCount}", style = type.caption, color = colors.subtle)
        }
        when (opinion.state) {
            "deleted" -> Text("这条观点已删除", style = type.secondary, color = colors.subtle)
            "hidden" -> Text("这条观点已被折叠", style = type.secondary, color = colors.subtle)
            else -> Text((opinion.replyToAlias?.let { "回复 $it：" } ?: "") + opinion.content.orEmpty(), style = type.secondary, color = colors.text)
        }
    }
}

/* ───────────────────────── 申诉复审 ───────────────────────── */

private data class ReviewOption(val value: String, val label: String, val effect: String, val hint: String)

/** 三个选项各自的后果；举报复议只有两个 */
private fun reviewOptions(review: AppealReview): List<ReviewOption> {
    if (review.reconsider) return listOf(
        ReviewOption("uphold", "维持原结论", "举报仍不成立", "原审认定不违规没有问题。内容保留；一个案件只能复议一次，这就是最后的结论。"),
        ReviewOption("overturn", "改判为违规", "案件改判", "原结论有误。案件按改判处理：内容撤回，并按你选的等级记分和处置。"),
    )
    val restricting = review.penalty?.kind == "mute" || review.penalty?.kind == "ban"
    val what = if (review.penalty?.kind == "ban") "封禁" else "禁言"
    return listOf(
        ReviewOption("uphold", "维持", "申诉驳回", "违规成立，处罚也合适。违规记录、记分和处罚都保持不变。"),
        ReviewOption(
            "reduce", "减轻", if (restricting) "解除$what，保留记录" else "保留记录",
            if (restricting) "违规成立，但处罚偏重。保留违规记录和记分，解除这条处罚带来的$what。" else "违规成立，但处置偏重。这条处罚没有禁言或封禁，「减轻」不会带来实际变化，只表明你的态度。",
        ),
        ReviewOption(
            "revoke", "撤销", "撤销处罚和记分",
            if (review.source == "machine") "不该处罚。撤销这条处罚和记分，能恢复的内容会恢复。" else "不该处罚。撤销处罚和记分；原案件按改判处理，原审委员的一致率也会跟着变。",
        ),
    )
}

private data class AppealDone(val review: AppealReview, val vote: String, val result: AppealVoteResult)

@Composable
private fun AppealDesk(ctx: JuryCtx, onHistory: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val rules = ctx.config.appeals ?: SeniorRules()
    val appealsOn = ctx.config.switches["appeals"] == true
    val enabled = appealsOn || ctx.config.has("reconsider")
    var review by remember { mutableStateOf<AppealReview?>(null) }
    var idle by remember { mutableStateOf<SeniorIdle?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var voteError by remember { mutableStateOf<String?>(null) }
    var skipped by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<AppealDone?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val blocked = seniorBlocked(ctx.status)

    // 改判为违规时要看各等级的记分和处置
    var moderation by remember { mutableStateOf(ctx.graph.screenCache.get<ModerationRules>("jury.rules")) }
    LaunchedEffect(Unit) {
        if (moderation == null) runCatching { ctx.api.rules() }.onSuccess { moderation = it; ctx.graph.screenCache.put("jury.rules", it) }
    }

    LaunchedEffect(reload, blocked, enabled) {
        if (!enabled) { loading = false; return@LaunchedEffect }
        if (blocked != null) { idle = blocked; review = null; loading = false; return@LaunchedEffect }
        loading = true
        runCatching { ctx.api.appealNext() }
            .onSuccess { review = it; idle = if (it == null) SeniorIdle("现在没有等你复审的申诉", "有新的申诉或复议分给你时会在这里出现。", retry = true) else null }
            .onFailure { review = null; idle = seniorFailure(it) }
        loading = false
    }

    fun submit(target: AppealReview, vote: String, note: String, level: Int?, category: String?) {
        if (submitting) return
        submitting = true
        voteError = null
        scope.launch {
            runCatching { ctx.api.appealVote(target.reviewId, vote, note, level, category) }
                .onSuccess { result ->
                    Haptics.confirm(view)
                    ctx.refresh()
                    skipped = null
                    done = AppealDone(target, vote, result)
                }
                .onFailure { error ->
                    Haptics.reject(view)
                    val code = error.apiCode()
                    val status = (error as? ApiException)?.status
                    if (status == 404 || status == 409 || code == "invalid_vote") {
                        val why = when (code) {
                            "review_closed" -> "已经结束，或者已经转给管理员处理"
                            "already_voted" -> "你已经投过了，这次没有重复记票"
                            "invalid_vote" -> "手里这份已经过时，已为你重新领取"
                            else -> "已经不在你手上了（可能已经改派）"
                        }
                        skipped = "复审 #${target.reviewId} $why。"
                        ctx.refresh()
                        reload++
                    } else {
                        voteError = error.userMessage()
                    }
                }
            submitting = false
        }
    }

    if (!enabled) {
        SeniorNotice("申诉复审还没有启用", "这项功能由管理员决定什么时候打开。现在所有申诉都直接由管理员处理；打开之后，符合条件的申诉会先交给 ${rules.seats} 位资深委员复审。")
        return
    }

    done?.let { result ->
        val reconsider = result.review.reconsider
        val who = if (reconsider) "举报人" else "申诉人"
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(colors.ok.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.check, tint = colors.ok, size = 28.dp)
            }
            Text("复审 #${result.review.reviewId} · 你的意见已提交", style = type.title, color = colors.text, modifier = Modifier.padding(top = 14.dp), textAlign = TextAlign.Center)
            Text(
                "你投的是「${appealVoteLabel(result.vote, reconsider)}」。" + if (result.result.settled && result.result.outcome != null)
                    "已有 ${rules.consensusVotes} 票相同，复审结果：${appealVoteLabel(result.result.outcome, reconsider)}。结果已通知$who。"
                else "还差其他复审委员的票，${rules.consensusVotes} 票相同即出结果，到时会通知$who。",
                style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
            result.result.reward?.takeIf { it.points > 0 }?.let { Text("风纪值 +${it.points}", style = type.caption, color = colors.ok, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("复审记录", onHistory)
                PrimaryButton("下一件", { done = null; reload++ })
            }
        }
        return
    }

    val current = review
    Column(Modifier.fillMaxSize()) {
        if (!appealsOn && idle == null && current != null) Flash("申诉复审的开关还没有打开，现在这个队列里只有举报人申请的复议。", false)
        skipped?.let { Flash(it, false) }
        when {
            loading && current == null -> CenterText("正在领取复审…")
            idle != null -> SeniorNotice(idle!!.title, idle!!.text, if (idle!!.retry) ({ reload++ }) else null)
            current != null -> AppealReviewBody(ctx, current, rules, moderation, submitting, voteError) { vote, note, level, category -> submit(current, vote, note, level, category) }
            else -> ErrorBlock("没有拿到复审", { reload++ })
        }
    }
}

@Composable
private fun AppealReviewBody(
    ctx: JuryCtx,
    review: AppealReview,
    rules: SeniorRules,
    moderation: ModerationRules?,
    pending: Boolean,
    error: String?,
    onSubmit: (String, String, Int?, String?) -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val options = remember(review.reviewId) { reviewOptions(review) }
    var choice by remember(review.reviewId) { mutableStateOf<String?>(null) }
    var level by remember(review.reviewId) { mutableStateOf<Int?>(null) }
    var category by remember(review.reviewId) { mutableStateOf<String?>(null) }
    var note by remember(review.reviewId) { mutableStateOf("") }
    var armed by remember(review.reviewId) { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == choice }
    val overturning = choice == "overturn"
    val ready = selected != null && (!overturning || level != null)
    val noteMax = ctx.config.opinions.maxLength.coerceAtMost(500)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("复审 #${review.reviewId}", style = type.title, color = colors.text)
                Tag(if (review.reconsider) "举报复议" else "申诉复审", colors.accent)
                Tag(kindLabel(review.kind), colors.muted)
            }
            remainingText(review.expiresAt)?.let { Text("复审还剩 $it，到期没有结果会转给管理员", style = type.caption, color = colors.muted) }

            // 当事人的话
            val statement = if (review.reconsider) review.reconsiderText else review.appealText
            TitledCard(if (review.reconsider) "举报人的复议理由" else "申诉理由", dateTimeText(if (review.reconsider) review.requestedAt else review.appealedAt) + " 提交") {
                Text(
                    statement?.takeIf { it.isNotBlank() } ?: "没有写理由", style = type.body,
                    color = if (statement.isNullOrBlank()) colors.subtle else colors.text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(10.dp),
                )
                Text(
                    if (review.reconsider) "这是举报人的一面之词，他看不到原审的讨论。请对照内容和原审的理由来判断。"
                    else "这是被处罚人的一面之词。请对照内容本身判断它成不成立，不要去猜他是谁。",
                    style = type.caption, color = colors.subtle,
                )
            }

            // 原处罚 / 原结论
            if (review.reconsider) {
                TitledCard("被质疑的结论") {
                    Fact("原结论", "认定不违规，举报不成立")
                    Fact("结论来源", sourceLabel(review.verdict?.source).ifBlank { "—" })
                    Fact("定案时间", dateTimeText(review.verdict?.closedAt))
                }
            } else {
                TitledCard("原处罚", (review.categoryLabel ?: "违规") + (review.level?.let { " · ${levelText(it)}" } ?: "")) {
                    Fact("违规类别", review.categoryLabel ?: "—")
                    Fact("等级", levelText(review.level).ifBlank { "—" })
                    Fact("处置", penaltyLabel(review.penalty))
                    Fact("谁给的", penaltySourceLabel(review.source).ifBlank { "—" })
                }
            }

            // 匿名内容
            val hasCase = review.messages.isNotEmpty() || review.post != null
            TitledCard(if (review.reconsider) "被举报的内容" else "被处罚的内容", "已匿名") {
                when {
                    hasCase -> {
                        AnonNote()
                        CaseEvidence(review.kind, review.messages, review.post, ctx.config.rules.contextNear)
                    }
                    review.content != null -> {
                        val content = review.content
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.danger.copy(alpha = 0.06f))
                                .border(1.5.dp, colors.danger.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("被处罚的那一条", style = type.caption, color = colors.danger, fontWeight = FontWeight.W600)
                            Text(content.text ?: "没有文字", style = type.body, color = if (content.text == null) colors.subtle else colors.text)
                            if (content.imageCount > 0) Text("原文带 ${content.imageCount} 张图片，这里不展示。", style = type.caption, color = colors.muted)
                        }
                        Text("这是机器自动给出的处罚，没有经过委员评审，所以只有这一条内容，没有上下文。", style = type.caption, color = colors.subtle)
                    }
                    else -> Text("没有可以展示的内容", style = type.secondary, color = colors.subtle)
                }
            }

            // 原审
            val origin = review.origin
            if (origin == null) {
                TitledCard("原审") {
                    Text("这条处罚是机器审核直接给出的，没有票数分布，也没有观点。你们是第一批看它的人。", style = type.secondary, color = colors.muted)
                }
            } else {
                TitledCard("原审", "只读 · 匿名代号") {
                    origin.tally?.let {
                        Text("原审票数分布", style = type.caption, color = colors.muted)
                        TallyBars(it)
                    }
                    OriginOpinions(origin.opinions)
                }
            }

            // 表决
            TitledCard("复审席位", "${review.seats} 席 · 已投 ${review.votesCast}") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatDots(review.votesCast, review.seats)
                    Text("${rules.consensusVotes} 票相同即出结果" + if (review.reconsider) "" else "，三票各不相同按「减轻」处理", style = type.caption, color = colors.muted)
                }
                Text("出结果之前看不到别人投了什么。${rules.timeoutHours} 小时没有结果会转给管理员；管理员也可以改掉复审的结论。", style = type.caption, color = colors.subtle)
            }
            Text("你的意见", style = type.group, color = colors.muted)
            options.forEach { option ->
                val on = choice == option.value
                val tint = when (option.value) { "revoke" -> colors.ok; "reduce" -> colors.warn; "overturn" -> colors.danger; else -> colors.accent }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (on) tint.copy(alpha = 0.10f) else colors.panel)
                        .border(1.5.dp, if (on) tint else colors.border, RoundedCornerShape(12.dp))
                        .clickable(enabled = !pending) { choice = option.value; armed = false }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, style = type.rowTitle, color = if (on) tint else colors.text, modifier = Modifier.weight(1f))
                        Text(option.effect, style = type.caption, color = if (on) tint else colors.muted)
                    }
                    Text(option.hint, style = type.caption, color = colors.muted)
                }
            }
            if (overturning) {
                Text("改判的等级 · 必选", style = type.group, color = colors.muted)
                val levels = moderation?.levels?.filter { it.level in 1..3 }?.takeIf { it.isNotEmpty() } ?: (1..3).map { RuleLevel(it, 0, "") }
                levels.forEach { item ->
                    val on = level == item.level
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) VoteColors.of("l${item.level}").copy(alpha = 0.14f) else colors.panelMuted)
                            .border(1.dp, if (on) VoteColors.of("l${item.level}") else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = !pending) { level = item.level; armed = false }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(levelText(item.level), style = type.secondary, color = colors.text, fontWeight = if (on) FontWeight.W600 else FontWeight.W400, modifier = Modifier.weight(1f))
                        if (item.penalty.isNotBlank()) Text("记 ${item.points} 分 · ${penaltyText(item.penalty)}", style = type.caption, color = colors.muted)
                    }
                }
                val categories = ctx.config.categories
                if (categories.isNotEmpty()) {
                    Text("违规类别（选填）", style = type.group, color = colors.muted)
                    categories.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { item ->
                                val on = category == item.key
                                Box(
                                    Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp))
                                        .background(if (on) colors.accentSoft else colors.panelMuted)
                                        .border(1.dp, if (on) colors.accent else Color.Transparent, RoundedCornerShape(10.dp))
                                        .clickable(enabled = !pending) { category = if (on) null else item.key; armed = false },
                                    contentAlignment = Alignment.Center,
                                ) { Text(item.label, style = type.caption, color = if (on) colors.accent else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
                Text("最终等级取改判票里偏低的中位数。", style = type.caption, color = colors.subtle)
            }
            Text("理由（选填）· ${note.length}/$noteMax", style = type.group, color = colors.muted)
            PlainField(
                note, { note = it.take(noteMax) },
                if (review.reconsider) "为什么维持或改判。理由留给管理员和其他复审委员参考。" else "申诉理由里哪一点成立、哪一点不成立。理由留给管理员和其他复审委员参考。",
                singleLine = false, minHeight = 72.dp, enabled = !pending,
            )
        }

        // 吸底提交：先选 → 点一次变「确认提交」→ 再点真正提交
        Column(
            Modifier.fillMaxWidth().background(colors.panel).border(1.dp, colors.border).imePadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { Text(it, style = type.secondary, color = colors.danger) }
            if (armed && selected != null && !pending) {
                Text(
                    "确认提交「${selected.label}」？" + (if (overturning && level != null) "等级：${levelText(level)}。" else "") +
                        "提交后不能修改，结果会真实生效并通知${if (review.reconsider) "举报人" else "申诉人"}。",
                    style = type.caption, color = colors.muted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (armed && !pending) SoftButton("返回修改", { armed = false }, Modifier.weight(0.6f))
                PrimaryButton(
                    when {
                        selected == null -> "请先选择一项"
                        !ready -> "请选择改判的等级"
                        pending -> "提交中…"
                        armed -> "确认提交"
                        else -> "提交：${selected.label}"
                    },
                    {
                        val option = selected ?: return@PrimaryButton
                        if (!armed) armed = true
                        else onSubmit(option.value, note.trim(), if (overturning) level else null, if (overturning) category else null)
                    },
                    Modifier.weight(1f), enabled = ready && !pending,
                )
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = KcTheme.type.secondary, color = KcTheme.colors.muted, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = KcTheme.type.secondary, color = KcTheme.colors.text, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/* ───────────────────────── 复审记录 ───────────────────────── */

@Composable
private fun AppealHistory(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AppealMineItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        runCatching { ctx.api.appealsMine(0) }
            .onSuccess { rows = it.items; total = it.total; error = null }
            .onFailure { error = it.userMessage() }
        loading = false
    }

    when {
        loading -> CenterText("正在加载…")
        error != null && rows.isEmpty() -> ErrorBlock(error!!, { loading = true; tick++ })
        rows.isEmpty() -> SeniorNotice("还没有参与过复审", "投出第一张复审票后，记录会出现在这里。")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("note") { Text("结果可能被管理员改掉，以申诉人收到的通知为准。", style = type.caption, color = colors.subtle, modifier = Modifier.padding(horizontal = 4.dp)) }
            items(rows, key = { it.reviewId }) { item ->
                val reconsider = item.type == "reconsider"
                val agreed = item.outcome?.let { it == item.myVote }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("复审 #${item.reviewId}", style = type.rowTitle, color = colors.text)
                        Tag(if (reconsider) "举报复议" else "申诉复审", colors.accent)
                        Box(Modifier.weight(1f))
                        when (agreed) {
                            true -> Text("一致", style = type.caption, color = colors.ok)
                            false -> Text("不一致", style = type.caption, color = colors.danger)
                            null -> Unit
                        }
                    }
                    Text(
                        if (reconsider) "对「不违规」的结论申请复议"
                        else listOfNotNull(item.categoryLabel ?: "处罚", item.level?.let { levelText(it) }, item.penalty?.let { penaltyLabel(it) }).joinToString(" · "),
                        style = type.secondary, color = colors.text,
                    )
                    Text("${kindLabel(item.kind)} · ${penaltySourceLabel(item.source)}", style = type.caption, color = colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("我的意见", style = type.caption, color = colors.subtle)
                        Tag(appealVoteLabel(item.myVote, reconsider), colors.accent)
                        Text("结果", style = type.caption, color = colors.subtle)
                        if (item.outcome != null) Tag(appealVoteLabel(item.outcome, reconsider), colors.text) else Tag("等待其他委员", colors.warn)
                    }
                    Text(dateTimeText(item.votedAt) + (item.settledAt?.let { " · ${dateTimeText(it)} 出结果" } ?: ""), style = type.caption, color = colors.subtle)
                }
            }
            if (rows.size < total) item("more") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    SoftButton(if (loadingMore) "正在加载…" else "加载更多", {
                        if (loadingMore) return@SoftButton
                        loadingMore = true
                        scope.launch {
                            runCatching { ctx.api.appealsMine(rows.size) }.onSuccess { rows = rows + it.items; total = it.total }
                            loadingMore = false
                        }
                    })
                }
            }
        }
    }
}
