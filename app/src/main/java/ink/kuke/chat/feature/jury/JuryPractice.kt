package ink.kuke.chat.feature.jury

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private val Ballots = listOf("none", "l1", "l2", "l3")

/** 四个判断按钮：练习和考试共用 */
@Composable
private fun BallotRow(selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = KcTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ballots.forEach { vote ->
            val on = selected == vote
            val tint = VoteColors.of(vote)
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) tint.copy(alpha = 0.16f) else colors.panelMuted)
                    .border(1.5.dp, if (on) tint else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { onPick(vote) },
                contentAlignment = Alignment.Center,
            ) { Text(voteLabel(vote, short = true), style = KcTheme.type.secondary, color = if (on) tint else colors.text, fontWeight = if (on) FontWeight.W700 else FontWeight.W500) }
        }
    }
}

/* ───────────────────────── 练习场 ───────────────────────── */

/**
 * 练习场：用已有结论的真实案例练手，不计一致率、不给风纪值。
 * 选完判断立刻揭晓标准结论；每 24 小时滚动有额度。
 */
@Composable
internal fun JuryPracticePage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var next by remember { mutableStateOf<PracticeNext?>(null) }
    var result by remember { mutableStateOf<PracticeAnswer?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var limitText by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        loading = true
        result = null
        picked = null
        runCatching { ctx.api.practiceNext() }
            .onSuccess { next = it; error = null; limitText = null }
            .onFailure {
                if (it.apiCode() == "practice_daily_limit") limitText = slotResetText(it.apiField("resets_at")) ?: "最早练的那一道满 24 小时后恢复名额"
                else error = it.userMessage()
            }
        loading = false
    }

    KcPage("练习场", onBack = { ctx.back() }) {
        val data = next
        val question = data?.question
        when {
            loading -> CenterText("正在出题…")
            limitText != null -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("今天的练习额度用完了", style = type.title, color = colors.text)
                Text("$limitText。练习额度按最近 24 小时滚动计算。", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
            }
            error != null && data == null -> ErrorBlock(error!!, { tick++ })
            data != null && question == null -> CenterText("题库里暂时没有可练的题")
            data != null && question != null -> Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 考试入口
                    if (ctx.config.has("exam") && ctx.status?.exam != null) {
                        val exam = ctx.status.exam
                        Card(Modifier.clickable { ctx.go(JuryPage.Exam) }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("入门考试", style = type.rowTitle, color = colors.text)
                                    Text(
                                        if (exam.passed) "已通过" else "${exam.total} 题，答对 ${exam.passScore} 题通过 · 已考 ${exam.attemptsUsed}/${exam.attemptsMax} 次",
                                        style = type.caption, color = colors.muted,
                                    )
                                }
                                Text(if (exam.passed) "查看" else "去考试", style = type.secondary, color = colors.accent)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(kindLabel(question.kind), colors.accent)
                        if (!question.verified) Tag("未核对", colors.warn)
                        if (question.answeredBefore) Tag("做过", colors.subtle)
                        Text(
                            "今日还能练 ${data.leftToday}/${data.dailyLimit}" + (data.stats?.let { " · 累计答对 ${it.correct}/${it.answered}" } ?: ""),
                            style = type.caption, color = colors.muted,
                        )
                    }
                    Text("举报理由：${reasonsText(question.reasons)}", style = type.secondary, color = colors.muted)
                    CaseEvidence(question.kind, question.messages, question.post, ctx.config.rules.contextNear)

                    result?.let { answer ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background((if (answer.correct) colors.ok else colors.danger).copy(alpha = 0.10f)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(if (answer.correct) "答对了" else "答错了", style = type.rowTitle, color = if (answer.correct) colors.ok else colors.danger)
                            val standard = answer.answer
                            Text(
                                "标准结论：${outcomeLabel(standard.outcome)}${standard.level?.let { " · ${levelText(it)}" } ?: ""}${standard.categoryLabel?.let { " · $it" } ?: ""}",
                                style = type.secondary, color = colors.text,
                            )
                            standard.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.secondary, color = colors.muted) }
                        }
                    }
                    error?.let { Text(it, style = type.secondary, color = colors.danger) }
                }
                Column(Modifier.fillMaxWidth().background(colors.panel).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result == null) {
                        Text("你的判断（选完立刻揭晓）", style = type.caption, color = colors.muted)
                        BallotRow(picked, enabled = !submitting) { vote ->
                            picked = vote
                            submitting = true
                            scope.launch {
                                runCatching { ctx.api.practiceAnswer(question.questionId, vote) }
                                    .onSuccess { result = it; error = null; Haptics.confirm(view) }
                                    .onFailure {
                                        Haptics.reject(view)
                                        if (it.apiCode() == "practice_daily_limit") limitText = slotResetText(it.apiField("resets_at")) ?: "最早练的那一道满 24 小时后恢复名额"
                                        else error = it.userMessage()
                                    }
                                submitting = false
                            }
                        }
                    } else {
                        PrimaryButton("下一题", { tick++ }, Modifier.fillMaxWidth())
                    }
                }
            }
            else -> ErrorBlock(error ?: "出题失败", { tick++ })
        }
    }
}

/* ───────────────────────── 入门考试（简版） ───────────────────────── */

/**
 * 入门考试：逐题作答，可以不答完就交（没答的按答错）。倒计时用服务器给的到期时间，
 * 到点自动提交已有的答案。通过后回首页申请成为委员。
 */
@Composable
internal fun JuryExamPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var state by remember { mutableStateOf<ExamState?>(null) }
    var attempt by remember { mutableStateOf<ExamAttempt?>(null) }
    var result by remember { mutableStateOf<ExamResult?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var answers by remember { mutableStateOf(mapOf<Int, String>()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var leftSeconds by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        runCatching { ctx.api.exam() }
            .onSuccess { state = it; attempt = it.active ?: attempt; error = null }
            .onFailure { error = it.userMessage() }
        loading = false
    }

    fun submit() {
        val current = attempt ?: return
        if (busy) return
        busy = true
        scope.launch {
            runCatching { ctx.api.examSubmit(current.attemptId, answers) }
                .onSuccess { result = it; attempt = null; ctx.refresh(); Haptics.confirm(view) }
                .onFailure { error = it.userMessage(); Haptics.reject(view); if (it.apiCode() == "exam_closed") { attempt = null; tick++ } }
            busy = false
        }
    }

    // 倒计时：到点自动交已有的答案
    LaunchedEffect(attempt?.attemptId) {
        val expires = attempt?.expiresAt?.let { ink.kuke.chat.core.ui.parseInstant(it) } ?: return@LaunchedEffect
        while (attempt != null) {
            leftSeconds = Duration.between(Instant.now(), expires).seconds.toInt().coerceAtLeast(0)
            if (leftSeconds <= 0) {
                submit()
                break
            }
            delay(1000)
        }
    }

    KcPage("入门考试", onBack = { ctx.back() }) {
        val finished = result
        val running = attempt
        val info = state
        when {
            loading -> CenterText("正在加载…")
            finished != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${finished.score} / ${finished.total}", style = type.display, color = if (finished.passed) colors.ok else colors.danger)
                        Text(if (finished.passed) "通过了！回到风纪首页就可以申请成为委员。" else "没有通过，答对 ${finished.passScore} 题才算通过。${if (finished.attemptsLeft > 0) "还可以再考 ${finished.attemptsLeft} 次。" else ""}", style = type.secondary, color = colors.muted)
                        finished.nextAttemptAt?.let { Text("下次可考：${dateTimeText(it)}", style = type.caption, color = colors.muted) }
                    }
                }
                finished.results.forEach { item ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("第 ${item.question} 题", style = type.rowTitle, color = colors.text)
                            Tag(if (item.correct) "答对" else "答错", if (item.correct) colors.ok else colors.danger)
                            Text("你的判断：${item.yourVote?.let { voteLabel(it, short = true) } ?: "未作答"}", style = type.caption, color = colors.muted)
                        }
                        val answer = item.answer
                        Text("标准结论：${outcomeLabel(answer.outcome)}${answer.level?.let { " · ${levelText(it)}" } ?: ""}${answer.categoryLabel?.let { " · $it" } ?: ""}", style = type.secondary, color = colors.text)
                        answer.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.caption, color = colors.muted) }
                    }
                }
                SoftButton("返回", { ctx.back() }, Modifier.fillMaxWidth())
            }
            running != null -> {
                val total = running.questions.size
                val question = running.questions.getOrNull(index.coerceIn(0, (total - 1).coerceAtLeast(0)))
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${index + 1} / $total 题 · 已答 ${answers.size}", style = type.secondary, color = colors.text, modifier = Modifier.weight(1f))
                        Text("剩余 %02d:%02d".format(leftSeconds / 60, leftSeconds % 60), style = type.secondary, color = if (leftSeconds < 120) colors.danger else colors.muted)
                    }
                    if (question != null) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("举报理由：${reasonsText(question.reasons)}", style = type.secondary, color = colors.muted)
                            CaseEvidence(question.kind, question.messages, question.post, ctx.config.rules.contextNear)
                        }
                        Column(Modifier.fillMaxWidth().background(colors.panel).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BallotRow(answers[question.question], enabled = !busy) { vote -> answers = answers + (question.question to vote) }
                            error?.let { Text(it, style = type.caption, color = colors.danger) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SoftButton("上一题", { index = (index - 1).coerceAtLeast(0) }, Modifier.weight(1f), enabled = index > 0)
                                if (index < total - 1) PrimaryButton("下一题", { index++ }, Modifier.weight(1f))
                                else PrimaryButton(if (busy) "交卷中…" else "交卷", { submit() }, Modifier.weight(1f), enabled = !busy)
                            }
                        }
                    }
                }
            }
            info != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (info.passed) "你已通过入门考试" else "入门考试", style = type.title, color = colors.text)
                        Text("${info.total} 题，答对 ${info.passScore} 题通过，限时 ${info.timeLimitMinutes} 分钟；可以不答完就交，没答的按答错。", style = type.secondary, color = colors.muted)
                        Text("已考 ${info.attemptsUsed}/${info.attemptsMax} 次" + (info.lastResult?.let { " · 上次 ${it.score} 分" } ?: ""), style = type.caption, color = colors.muted)
                        info.nextAttemptAt?.let { Text("下次可考：${dateTimeText(it)}", style = type.caption, color = colors.warn) }
                    }
                }
                when {
                    info.passed -> Text("通过后 90 天内有效，回风纪首页就可以申请成为委员。", style = type.secondary, color = colors.muted)
                    !info.eligible -> Text("还没满足申请条件（账号时长、近期发言等），暂时不能考试。", style = type.secondary, color = colors.warn)
                    !info.available -> Text("题库里已核对的题还不够，暂时不能考试。", style = type.secondary, color = colors.warn)
                    else -> PrimaryButton(if (busy) "准备中…" else "开始考试", {
                        busy = true
                        scope.launch {
                            runCatching { ctx.api.examStart() }
                                .onSuccess { attempt = it; index = 0; answers = emptyMap(); error = null }
                                .onFailure { error = it.userMessage(); Haptics.reject(view) }
                            busy = false
                        }
                    }, Modifier.fillMaxWidth(), enabled = !busy && info.nextAttemptAt == null)
                }
                error?.let { Text(it, style = type.secondary, color = colors.danger) }
            }
            else -> ErrorBlock(error ?: "考试信息加载失败", { loading = true; tick++ })
        }
    }
}
