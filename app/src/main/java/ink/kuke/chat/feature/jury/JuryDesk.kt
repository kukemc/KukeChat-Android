package ink.kuke.chat.feature.jury

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.userMessage
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 评审台为什么现在领不到案件 */
private sealed interface DeskIdle {
    data object NotJuror : DeskIdle
    data class Restricted(val message: String) : DeskIdle
    data class Cooldown(val until: String?) : DeskIdle
    data class Limit(val resetsAt: String?) : DeskIdle
    data object Empty : DeskIdle
    data class Failed(val message: String) : DeskIdle
}

private data class Voted(val caseId: Long, val vote: String, val settled: Boolean, val detail: JuryCase?, val reward: Reward?)

private const val COUNTDOWN_SECONDS = 45

/**
 * 评审台（设计稿 P24）：上方证据滚动，下方吸底表决面板。一次一件，投完才发下一件。
 * 提交要点两次（第二次变「确认提交」）；打开「连续评审」后投完自动发下一件（质控题、回避除外：质控题的反馈要给你看）。
 */
@Composable
internal fun JuryDeskPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val status = ctx.status

    var case by remember { mutableStateOf<JuryCase?>(null) }
    var idle by remember { mutableStateOf<DeskIdle?>(null) }
    var loading by remember { mutableStateOf(true) }
    var voted by remember { mutableStateOf<Voted?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var voteError by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var continuous by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    // 先看身份：不是在任委员、受处罚、被暂停发案、额度用完，都不去领案件，直接说明原因
    val blockedBy: DeskIdle? = when {
        status == null -> null
        !status.isJuror -> DeskIdle.NotJuror
        status.restriction != null -> DeskIdle.Restricted(status.restriction.message.ifBlank { "受处罚期间不能参与评审" })
        remainingText(status.cooldownUntil) != null -> DeskIdle.Cooldown(status.cooldownUntil)
        status.dailyLimit > 0 && status.votedToday >= status.dailyLimit -> DeskIdle.Limit(status.dailyResetsAt)
        else -> null
    }

    LaunchedEffect(reload, status?.status, blockedBy == null) {
        if (status == null) return@LaunchedEffect
        if (blockedBy != null) {
            idle = blockedBy
            case = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        idle = null
        runCatching { ctx.api.nextCase() }
            .onSuccess { case = it; if (it == null) idle = DeskIdle.Empty }
            .onFailure { error ->
                case = null
                idle = when (error.apiCode()) {
                    "not_juror" -> DeskIdle.NotJuror
                    "juror_restricted" -> DeskIdle.Restricted(error.message ?: "受处罚期间不能参与评审")
                    "jury_cooldown" -> DeskIdle.Cooldown(error.apiField("until"))
                    "jury_daily_limit" -> DeskIdle.Limit(error.apiField("resets_at"))
                    else -> DeskIdle.Failed(error.userMessage())
                }
            }
        loading = false
    }

    fun submit(current: JuryCase, vote: String, note: String, category: String?) {
        if (submitting) return
        submitting = true
        voteError = null
        scope.launch {
            runCatching { ctx.api.vote(current.caseId, vote, note, category) }
                .onSuccess { result ->
                    Haptics.confirm(view)
                    ctx.refresh()
                    val outcome = Voted(current.caseId, vote, result.settled, result.detail, result.reward)
                    val showFeedback = vote != "recuse" && (!continuous || result.detail?.isQualityCheck == true)
                    if (showFeedback) {
                        voted = outcome
                    } else {
                        flash = if (vote == "recuse") "已回避${reviewNo(current.caseId)}，案件会改派给别的委员，不计入你的评审数。"
                        else "${reviewNo(current.caseId)} 已提交：${voteLabel(vote)}。" + if (result.settled) "这一票让案件有了结论。" else ""
                        reload++
                    }
                }
                .onFailure { error ->
                    Haptics.reject(view)
                    if (error.apiCode() in setOf("recuse_limit", "juror_restricted", "jury_cooldown", "jury_daily_limit", "invalid_category")) ctx.refresh()
                    val reset = if (error.apiCode() in setOf("jury_daily_limit", "recuse_limit")) slotResetText(error.apiField("resets_at")) else null
                    voteError = error.userMessage() + (reset?.let { "（$it）" } ?: "")
                }
            submitting = false
        }
    }

    // 投完之后：投后视图（质控题揭晓、进度、观点区），点「下一件」继续
    voted?.let { done ->
        KcPage(
            title = "${reviewNo(done.caseId)} · 已提交", onBack = { ctx.back() },
            actions = {
                PrimaryButton("下一件", { voted = null; reload++ }, Modifier.padding(end = 8.dp), color = colors.fill)
            },
        ) {
            CaseDetailBody(ctx, done.caseId, done.detail, reward = done.reward)
        }
        return
    }

    KcPage(title = case?.let { reviewNo(it.caseId) } ?: "评审台", onBack = { ctx.back() }) {
        val current = case
        when {
            loading && current == null -> CenterText("正在领取案件…")
            idle != null -> IdleView(idle!!, onRetry = { reload++ }, onPractice = { ctx.go(JuryPage.Practice) }, canPractice = ctx.config.has("practice"))
            current != null -> Column(Modifier.fillMaxSize()) {
                // 顶部信息条
                Row(Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (status != null) {
                            Text("24 小时内 ${status.votedToday} / ${status.dailyLimit} · 一次一件", style = type.caption, color = colors.muted)
                        }
                    }
                    Text("连续评审", style = type.caption, color = colors.muted, modifier = Modifier.padding(end = 6.dp))
                    Switch(checked = continuous, onCheckedChange = { continuous = it })
                }
                flash?.let {
                    Text(it, style = type.secondary, color = colors.ok, modifier = Modifier.fillMaxWidth().background(colors.ok.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CaseHeader(current)
                    Text(
                        "案件里的发言人都用代称，看不到是谁、也看不到是哪个群。请只根据内容本身判断，不要搜索、猜测或联系当事人。",
                        style = type.caption, color = colors.muted,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(10.dp),
                    )
                    CaseEvidence(current.kind, current.messages, current.post, ctx.config.rules.contextNear)
                }
                VotePanel(ctx, current, submitting, voteError) { vote, note, category -> submit(current, vote, note, category) }
            }
            else -> ErrorBlock("没有拿到案件", { reload++ })
        }
    }
}

@Composable
private fun CaseHeader(case: JuryCase) {
    val colors = KcTheme.colors
    var seconds by remember(case.caseId) { mutableIntStateOf(COUNTDOWN_SECONDS) }
    LaunchedEffect(case.caseId) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tag(kindLabel(case.kind), colors.accent)
        if (case.caseOrigin == "machine") Tag("机器送审", colors.warn)
        Text(
            if (seconds > 0) "请在 ${seconds}s 内完成" else "时间到了，尽快提交",
            style = KcTheme.type.caption, color = if (seconds in 1..10 || seconds == 0) colors.danger else colors.muted,
        )
        remainingText(case.expiresAt)?.let {
            Text("· 超过 $it 会让给别人", style = KcTheme.type.caption, color = colors.subtle)
        }
    }
}

@Composable
private fun IdleView(idle: DeskIdle, onRetry: () -> Unit, onPractice: () -> Unit, canPractice: Boolean) {
    val colors = KcTheme.colors
    val (title, text) = when (idle) {
        DeskIdle.NotJuror -> "只有在任的风纪委员能评审" to "你可以先去练习场熟悉流程，或者回到风纪首页申请成为委员。"
        is DeskIdle.Restricted -> "受处罚期间不能评审" to idle.message
        is DeskIdle.Cooldown -> "暂时领不到新案件" to ("近期弃权偏多，系统暂停发案" + (remainingText(idle.until)?.let { "，约 $it 后恢复" } ?: "") + "。认真看完再判断，比选「拿不准」更有帮助。")
        is DeskIdle.Limit -> "24 小时内的评审额度用完了" to (slotResetText(idle.resetsAt) ?: "最早投的那一票满 24 小时后会恢复名额。") + "评审上限按最近 24 小时滚动计算，不在零点清零。"
        DeskIdle.Empty -> "现在没有等你评审的案件" to "有新案件时会在这里出现，也可以去练习场练练手。"
        is DeskIdle.Failed -> "案件领取失败" to idle.message
    }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = KcTheme.type.title, color = colors.text)
        Text(text, style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (idle is DeskIdle.Failed || idle is DeskIdle.Empty) SoftButton("刷新", onRetry)
            if (canPractice) SoftButton("去练习场", onPractice)
        }
    }
}

/* ───────────────────────── 表决面板 ───────────────────────── */

private val KnownKinds = setOf("clean", "violation", "abstain", "recuse")

/** 表决面板。panel = true 时是资深合议：只有四个判断和回避，不能弃权 */
@Composable
internal fun VotePanel(ctx: JuryCtx, case: JuryCase, pending: Boolean, error: String?, panel: Boolean = false, onSubmit: (String, String, String?) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val config = ctx.config
    val status = ctx.status
    val options = remember(config.votes, panel) {
        if (!panel) config.votes.filter { it.kind in KnownKinds }
        else config.votes.filter { it.kind == "clean" || it.kind == "violation" } +
            (config.votes.firstOrNull { it.kind == "recuse" } ?: VoteOption("recuse", "recuse", "主动回避"))
    }
    val categories = if (config.has("votes_v2")) config.categories else emptyList()
    val recuseLeft = status?.recuseLeftToday

    var choice by remember(case.caseId) { mutableStateOf<String?>(null) }
    var category by remember(case.caseId) { mutableStateOf(case.suggestedCategory?.takeIf { key -> categories.any { it.key == key } }) }
    var note by remember(case.caseId) { mutableStateOf("") }
    var armed by remember(case.caseId) { mutableStateOf(false) }
    var expanded by remember(case.caseId) { mutableStateOf(false) }

    val selected = options.firstOrNull { it.value == choice }
    val recusing = selected?.kind == "recuse"
    val pickingCategory = selected?.kind == "violation" && categories.isNotEmpty()
    val maxCount = (case.reasons.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    fun blocked(option: VoteOption) = option.kind == "recuse" && recuseLeft != null && recuseLeft <= 0

    Column(
        Modifier.fillMaxWidth().background(colors.panel).border(1.dp, colors.border).imePadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 把手：上拉看完整选项，下拉只露提交按钮
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f))
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.borderStrong))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text(if (expanded) "收起" else "更多选项", style = type.caption, color = colors.accent)
            }
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 举报理由
                if (case.caseOrigin != "machine" && case.reasons.isNotEmpty()) {
                    Text("举报理由 · ${kindLabel(case.kind)} · ${case.reportCount} 人举报", style = type.group, color = colors.muted)
                    case.reasons.forEach { reason ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(reason.reason, style = type.secondary, color = colors.text, modifier = Modifier.weight(1.2f))
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.panelMuted)) {
                                Box(Modifier.fillMaxWidth(reason.count.toFloat() / maxCount).height(6.dp).background(colors.warn))
                            }
                            Text("${reason.count}", style = type.caption, color = colors.text, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else if (case.caseOrigin == "machine") {
                    Text("这条内容是机器审核拿不准后送来的，没有举报人。", style = type.secondary, color = colors.muted)
                }

                // 无法判断 / 回避
                val special = options.filter { it.kind == "abstain" || it.kind == "recuse" }
                if (special.isNotEmpty()) {
                    Text(if (panel) "需要回避" else "无法判断 · 需要回避", style = type.group, color = colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        special.forEach { option ->
                            val on = choice == option.value
                            val off = blocked(option)
                            Box(
                                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (on) VoteColors.Neutral.copy(alpha = 0.16f) else colors.panelMuted)
                                    .border(1.dp, if (on) VoteColors.Neutral else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable(enabled = !pending && !off) { choice = option.value; armed = false },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    option.label.ifBlank { voteLabel(option.value) }, style = type.secondary,
                                    color = if (off) colors.subtle else if (on) colors.text else colors.muted, fontWeight = if (on) FontWeight.W600 else FontWeight.W400,
                                )
                            }
                        }
                    }
                    when {
                        choice == "recuse" -> Text(
                            (if (panel) "认识当事人或有利害关系时选这项。案件会改派给别的资深委员，没人顶上时转给管理员。" else "认识当事人或有利害关系时选这项。不占席位，不影响一致率；回避后不能再看这个案件。") +
                                (recuseLeft?.let { "24 小时内还可回避 $it 次。" } ?: ""),
                            style = type.caption, color = colors.muted,
                        )
                        choice == "need_context" -> Text(
                            "先把折叠的上下文看完，仍然判断不了再选。不计入表决；同一案件累计几张这样的票后会转出处理。",
                            style = type.caption, color = colors.muted,
                        )
                        choice == "abstain" -> Text("这一票不算数，案件交给别的委员。弃权过多会被暂停发案。", style = type.caption, color = colors.muted)
                    }
                }

                // 违规类别
                if (pickingCategory) {
                    Text("违规类别" + if (case.suggestedCategory != null) " · 已按举报理由预选" else "", style = type.group, color = colors.muted)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.chunked(3).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowItems.forEach { item ->
                                    val on = category == item.key
                                    Box(
                                        Modifier.weight(1f).heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp))
                                            .background(if (on) colors.accentSoft else colors.panelMuted)
                                            .border(1.dp, if (on) colors.accent else Color.Transparent, RoundedCornerShape(10.dp))
                                            .clickable(enabled = !pending) { category = item.key; armed = false },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(item.label, style = type.caption, color = if (on) colors.accent else colors.text) }
                                }
                                repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Text("最终类别取违规票里的多数，只影响记录和公示的归类；记分和处置由上面选的等级决定。", style = type.caption, color = colors.subtle)
                }

                if (!recusing) {
                    Text("判断理由（选填）· ${note.length}/${config.opinions.maxLength}", style = type.group, color = colors.muted)
                    PlainField(
                        note, { note = it.take(config.opinions.maxLength) },
                        when {
                            panel -> "请写明理由：它会留在本案观点区，原审委员和其他合议委员都看得到。"
                            config.has("opinions") -> "写下你的理由。提交后它会成为你在本案观点区的第一条观点，只有同样投过票的委员能看到。"
                            else -> "补充说明，仅管理员可见。"
                        },
                        singleLine = false, minHeight = 64.dp, enabled = !pending,
                    )
                }
            }
        }

        // 四个判断：不违规 · 轻微 · 一般 · 严重
        val verdicts = options.filter { it.kind == "clean" || it.kind == "violation" }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            verdicts.forEachIndexed { index, option ->
                val on = choice == option.value
                val tint = VoteColors.of(option.value)
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) tint.copy(alpha = 0.16f) else colors.panelMuted)
                        .border(1.5.dp, if (on) tint else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable(enabled = !pending) { choice = option.value; armed = false }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("${index + 1}", style = type.caption, color = colors.subtle)
                    Text(
                        voteLabel(option.value, short = true).ifBlank { option.label }, style = type.secondary,
                        color = if (on) tint else colors.text, fontWeight = if (on) FontWeight.W700 else FontWeight.W500,
                    )
                }
            }
        }

        error?.let { Text(it, style = type.secondary, color = colors.danger) }

        // 提交：先选 → 点一次变「确认提交」→ 再点真正提交
        val tint = if (selected != null) VoteColors.of(selected.value) else colors.fill
        val label = when {
            selected == null -> "请先选择一个选项"
            pending -> "提交中…"
            armed && recusing -> "确认回避"
            armed -> "确认提交"
            recusing -> "回避这个案件"
            else -> "提交：${selected.label.ifBlank { voteLabel(selected.value) }}"
        }
        if (armed && selected != null && !pending) {
            Text(
                if (recusing) "确认回避？回避后不能再查看这个案件。" else "提交后不能修改，并且会真实生效。" +
                    (if (pickingCategory && category != null) "类别：${categories.firstOrNull { it.key == category }?.label.orEmpty()}。" else ""),
                style = type.caption, color = colors.muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (armed && !pending) SoftButton("返回修改", { armed = false }, Modifier.weight(0.6f))
            PrimaryButton(label, {
                val option = selected ?: return@PrimaryButton
                if (!armed) {
                    armed = true
                } else {
                    onSubmit(option.value, if (recusing) "" else note.trim(), if (pickingCategory) category else null)
                }
            }, Modifier.weight(1f), enabled = selected != null && !pending, color = tint)
        }
    }
}
