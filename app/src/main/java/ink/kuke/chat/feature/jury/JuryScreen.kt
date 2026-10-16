package ink.kuke.chat.feature.jury

import ink.kuke.chat.core.data.slot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.DialogCard
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SectionLabel
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 风纪里的页面。用状态切换，不另开路由；系统返回键先回首页再离开 */
internal sealed interface JuryPage {
    data object Home : JuryPage
    data object Desk : JuryPage
    data object Practice : JuryPage
    data object Exam : JuryPage
    data object Mine : JuryPage
    data class MineCase(val caseId: Long) : JuryPage
    data object Senior : JuryPage
    data object Bulletin : JuryPage
    data class BulletinCase(val caseId: Long) : JuryPage
    data object Handbook : JuryPage
    data object Ranking : JuryPage
    data object Hall : JuryPage
    data class HallPost(val postId: Long) : JuryPage
    data object Growth : JuryPage
    data object Precedents : JuryPage
    data class Precedent(val precedentId: Long) : JuryPage
}

/** 各子页面共用的上下文：配置、我的身份，以及「重拉身份」 */
internal class JuryCtx(
    val graph: AppGraph,
    val api: JuryApi,
    val config: JuryConfig,
    val status: JuryStatus?,
    val refresh: () -> Unit,
    val go: (JuryPage) -> Unit,
    val back: () -> Unit,
)

/**
 * 风纪首页（设计稿 P23）与各板块的入口。
 * 首页 = 我的身份卡 + 板块宫格 + 社区概况；板块页面在同目录的 Jury*.kt 里。
 * 隐私：整个风纪只能看到服务端给的匿名案件快照，没有任何别的消息浏览入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JuryScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val api = remember { JuryApi(graph.api) }
    var page by remember { mutableStateOf<JuryPage>(JuryPage.Home) }
    // 上次的开关配置和我的身份（内存）：先摆出来，再静默刷新
    val cache = remember(graph) { graph.screenCache.slot<Pair<JuryConfig, JuryStatus>>("jury.me") }
    val cached = remember(cache) { cache.get() }
    var config by remember { mutableStateOf(cached?.first) }
    var status by remember { mutableStateOf(cached?.second) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        runCatching {
            val freshConfig = api.config()
            val freshStatus = api.me()
            config = freshConfig
            status = freshStatus
            error = null
            cache.put(freshConfig to freshStatus)
        }.onFailure { error = it.userMessage() }
    }
    // 资格、层级变化、新案件进队列：重拉身份（角标和状态跟着变）
    LaunchedEffect(Unit) {
        graph.realtime.events.collect { event ->
            if (event.type == "jury.status.changed" || event.type == "jury.queue.changed" || event.type == "jury.points.changed") tick++
        }
    }

    val parent: (JuryPage) -> JuryPage = {
        when (it) {
            is JuryPage.MineCase -> JuryPage.Mine
            is JuryPage.BulletinCase -> JuryPage.Bulletin
            is JuryPage.HallPost -> JuryPage.Hall
            is JuryPage.Precedent -> JuryPage.Precedents
            JuryPage.Exam -> JuryPage.Practice
            else -> JuryPage.Home
        }
    }
    BackHandler(enabled = page != JuryPage.Home) { page = parent(page) }

    val loadedConfig = config
    if (loadedConfig == null) {
        KcPage("风纪", onBack = { nav.back() }) {
            if (error != null) ErrorBlock(error!!, { tick++ }) else CenterText("正在加载…")
        }
        return
    }
    val ctx = JuryCtx(graph, api, loadedConfig, status, refresh = { tick++ }, go = { page = it }, back = { page = parent(page) })

    when (val current = page) {
        JuryPage.Home -> JuryHome(ctx, error, onExit = { nav.back() })
        JuryPage.Desk -> JuryDeskPage(ctx)
        JuryPage.Practice -> JuryPracticePage(ctx)
        JuryPage.Exam -> JuryExamPage(ctx)
        JuryPage.Mine -> JuryMinePage(ctx)
        is JuryPage.MineCase -> JuryCaseDetailPage(ctx, current.caseId)
        JuryPage.Senior -> JurySeniorPage(ctx)
        JuryPage.Bulletin -> JuryBulletinPage(ctx)
        is JuryPage.BulletinCase -> JuryPublicCasePage(ctx, current.caseId)
        JuryPage.Handbook -> JuryHandbookPage(ctx)
        JuryPage.Ranking -> JuryRankingPage(ctx)
        JuryPage.Hall -> JuryHallPage(ctx)
        is JuryPage.HallPost -> JuryHallPostPage(ctx, current.postId)
        JuryPage.Growth -> JuryGrowthPage(ctx)
        JuryPage.Precedents -> JuryPrecedentsPage(ctx)
        is JuryPage.Precedent -> JuryPrecedentPage(ctx, current.precedentId)
    }
}

/* ───────────────────────── 首页 ───────────────────────── */

private data class Section(
    val page: JuryPage,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    /** 不可用时的说明；null 表示可用 */
    val lockedReason: String?,
    val badge: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JuryHome(ctx: JuryCtx, error: String?, onExit: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val status = ctx.status
    val config = ctx.config
    val scope = rememberCoroutineScope()
    val overviewCache = remember(ctx.graph) { ctx.graph.screenCache.slot<JuryOverview>("jury.overview") }
    var overview by remember { mutableStateOf(overviewCache.get()) }
    var refreshing by remember { mutableStateOf(false) }
    var showPenalties by remember { mutableStateOf(false) }
    var lockedInfo by remember { mutableStateOf<String?>(null) }
    var showApply by remember { mutableStateOf(false) }

    LaunchedEffect(status?.status) { runCatching { ctx.api.overview() }.onSuccess { overview = it; overviewCache.put(it) } }

    val sections = remember(status, config) {
        val juror = status?.isJuror == true
        val canPractice = status?.everJuror == true || status?.eligible == true
        fun capability(name: String, reason: String?): String? = if (!config.has(name)) "这个板块还没有开放" else reason
        listOf(
            Section(JuryPage.Desk, "评审台", KcIcons.scale, Color(0xFF0A84FF), if (juror) null else "成为在任的风纪委员后才能评审", status?.availableCases ?: 0),
            Section(JuryPage.Practice, "练习场", KcIcons.target, Color(0xFF7950F2), capability("practice", if (canPractice) null else "满足申请条件的用户都可以练习")),
            Section(JuryPage.Mine, "我的评审", KcIcons.history, Color(0xFF12B886), if (status?.everJuror == true) null else "当过委员之后，这里会记录你投过的每一票"),
            Section(JuryPage.Senior, "资深工作台", KcIcons.shieldCheck, Color(0xFFF76707), capability("panel", if (status?.isSenior == true) null else "资深委员才有的工作台：资深合议与申诉复审"), ((status?.senior?.panel ?: 0) + (status?.senior?.appeals ?: 0))),
            Section(JuryPage.Bulletin, "公示栏", KcIcons.announcement, Color(0xFF0C8599), capability("public_cases", null)),
            Section(JuryPage.Handbook, "规则手册", KcIcons.bookOpen, Color(0xFFF59F00), null),
            Section(JuryPage.Ranking, "排行榜", KcIcons.trophy, Color(0xFFE0383E), capability("leaderboard", null)),
            Section(JuryPage.Hall, "议事厅", KcIcons.messagesSquare, Color(0xFF2B8A3E), capability("hall", null)),
            Section(JuryPage.Growth, "我的成长", KcIcons.trendingUp, Color(0xFF1C7ED6), if (status?.everJuror == true) null else "当过委员之后，这里会记录你的层级、风纪值和成就"),
            Section(JuryPage.Precedents, "判例库", KcIcons.bookmark, Color(0xFF5F3DC4), capability("precedents", null)),
        )
    }

    KcPage(
        title = "风纪", subtitle = "社区自己管理自己", onBack = onExit,
        actions = {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { showPenalties = true }, contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.shield, tint = colors.text, size = 22.dp, contentDescription = "处罚与申诉")
            }
        },
    ) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
            refreshing = true
            ctx.refresh()
            scope.launch {
                runCatching { ctx.api.overview() }.onSuccess { overview = it }
                refreshing = false
            }
        }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (status == null) {
                    Card { Text(error ?: "正在加载你的身份…", style = type.secondary, color = colors.muted, modifier = Modifier.padding(16.dp)) }
                } else {
                    IdentityCard(ctx, status, onApply = { showApply = true })
                }

                SectionLabel("板块")
                Card {
                    Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp)) {
                        sections.chunked(4).forEach { rowItems ->
                            Row(Modifier.fillMaxWidth()) {
                                rowItems.forEach { section ->
                                    SectionTile(section, Modifier.weight(1f)) {
                                        val reason = section.lockedReason
                                        if (reason == null) ctx.go(section.page) else lockedInfo = "${section.label}：$reason"
                                    }
                                }
                                repeat(4 - rowItems.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                overview?.let { ov ->
                    SectionLabel("社区概况")
                    Card {
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                            listOf("在任委员" to ov.jurors.total, "等待评审" to (ov.backlog.jury + ov.backlog.panel), "近 7 天定案" to ov.settled.d7).forEach { (label, value) ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(value.toString(), style = type.number, color = colors.text)
                                    Text(label, style = type.caption, color = colors.muted)
                                }
                            }
                        }
                        if (ov.sampleEnough) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("共识率 ${rateText(ov.consensusRate)}", style = type.caption, color = colors.muted)
                                Text("违规占比 ${rateText(ov.violationRate)}", style = type.caption, color = colors.muted)
                                Text("中位用时 ${hoursText(ov.medianHours)}", style = type.caption, color = colors.muted)
                            }
                        }
                    }
                }

                SectionLabel("这套制度怎么运转")
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val steps = listOf(
                            "举报" to "任何人都可以举报群聊消息或公开动态。针对同一条内容的举报会并成一个案件，只审一次。",
                            "匿名评审" to "案件分给几位风纪委员。内容经过匿名处理：看不到是谁说的、在哪个群；和当事人有关系的委员会被自动回避。",
                            "表决" to "${consensusRuleText(config.rules, config.has("panel"))}${config.rules.timeoutHours} 小时没有结论的案件同样转出。",
                            "处置与申诉" to "认定违规的按规则记分和处置，委员给出的封禁最长 ${config.rules.maxBanHours?.let { if (it % 24 == 0) "${it / 24} 天" else "$it 小时" } ?: "7 天"}。被处罚的人可以申诉，管理员保留终审权。",
                        )
                        steps.forEachIndexed { index, (title, text) ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(24.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", style = type.caption, color = colors.accent, fontWeight = FontWeight.W700)
                                }
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(title, style = type.rowTitle, color = colors.text)
                                    Text(text, style = type.secondary, color = colors.muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    lockedInfo?.let { message ->
        DialogCard({ lockedInfo = null }, "暂时不能用") {
            Text(message, style = type.secondary, color = colors.muted)
            PrimaryButton("知道了", { lockedInfo = null }, Modifier.fillMaxWidth())
        }
    }
    if (showPenalties) {
        DialogCard({ showPenalties = false }, "处罚与申诉") {
            Text(
                "你收到的处罚和申诉记录在「设置」里查看。被处罚的人可以对每条处罚申诉一次，申诉通过后记分清零，由它触发的禁言、封禁同步解除。",
                style = type.secondary, color = colors.muted,
            )
            PrimaryButton("知道了", { showPenalties = false }, Modifier.fillMaxWidth())
        }
    }
    if (showApply && status != null) {
        ApplyDialog(ctx, status, onDismiss = { showApply = false })
    }
}

@Composable
private fun SectionTile(section: Section, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val locked = section.lockedReason != null
    val press = rememberInteraction()
    Column(
        modifier.pressScale(press).clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = null, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (locked) colors.panelMuted else section.tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { KcIcon(section.icon, tint = if (locked) colors.subtle else section.tint, size = 26.dp) }
            if (section.badge > 0 && !locked) {
                ink.kuke.chat.ui.components.UnreadBadge(section.badge, modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
            }
        }
        Text(
            section.label, style = KcTheme.type.caption, color = if (locked) colors.subtle else colors.text,
            modifier = Modifier.padding(top = 6.dp), maxLines = 1,
        )
    }
}

/** 我的身份：一致率环 + 状态文案 + 任期 / 24 小时额度 / 风纪值 */
@Composable
private fun IdentityCard(ctx: JuryCtx, status: JuryStatus, onApply: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val stats = status.stats
    val rate = if (stats != null && stats.votesSettled > 0) stats.agreementRate else null

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(headline(status), style = type.title, color = colors.text)
                    }
                    Tag(
                        if (status.isJuror && status.tier != null) "在任 · ${tierLabel(status.tier)}" else membershipLabel(status.status),
                        if (status.isJuror) colors.ok else if (status.status == "applied") colors.warn else if (status.status in setOf("suspended", "rejected")) colors.danger else colors.muted,
                        Modifier.padding(top = 6.dp),
                    )
                }
                AgreementRing(rate)
            }
            Text(statusText(status, ctx.config.rules.termDays), style = type.secondary, color = colors.muted)
            if (!status.reviewNote.isNullOrBlank()) {
                Text("说明：${status.reviewNote}", style = type.secondary, color = colors.warn)
            }
            status.restriction?.let {
                Text(it.message.ifBlank { "受处罚期间不能参与评审" } + (it.until?.let { until -> "，至 ${dateTimeText(until)}" } ?: ""), style = type.secondary, color = colors.danger)
            }
            remainingText(status.cooldownUntil)?.let {
                Text("弃权偏多，暂时领不到新案件，约 $it 后恢复。", style = type.secondary, color = colors.warn)
            }
            status.warnings.forEach { Text(it.message, style = type.secondary, color = colors.warn) }

            if (status.isJuror) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fact("任期", remainingText(status.termEndsAt)?.let { "还剩 $it" } ?: dateTimeText(status.termEndsAt), Modifier.weight(1f))
                    Fact("24 小时已评", "${status.votedToday} / ${status.dailyLimit}", Modifier.weight(1f))
                    Fact("风纪值", "${status.points}", Modifier.weight(1f))
                }
                status.tierProgress?.takeIf { it.nextTier != null }?.let { progress ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("距离${tierLabel(progress.nextTier)}委员", style = type.group, color = colors.muted)
                        progress.requirements.forEach { req ->
                            val current = req.current
                            val isRate = req.key.endsWith("rate")
                            val currentText = if (current == null) req.note ?: "—" else if (isRate) rateText(current) else current.toInt().toString()
                            val targetText = if (isRate) rateText(req.target) else req.target.toInt().toString()
                            Text(
                                "${if (req.ok) "✓" else "·"} ${req.label}  $currentText / $targetText",
                                style = type.caption, color = if (req.ok) colors.ok else colors.muted,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        "进入评审台" + (status.availableCases?.takeIf { it > 0 }?.let { " · $it" } ?: ""),
                        { ctx.go(JuryPage.Desk) }, Modifier.weight(1f),
                    )
                    if (ctx.config.has("practice")) SoftButton("练习场", { ctx.go(JuryPage.Practice) }, Modifier.weight(0.6f))
                }
            } else if (status.canApply) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(if (status.status == "expired") "续任" else "申请成为委员", onApply, Modifier.weight(1f))
                    if (ctx.config.has("practice") && (status.everJuror || status.eligible)) SoftButton("去练习场", { ctx.go(JuryPage.Practice) }, Modifier.weight(0.7f))
                }
            } else if (ctx.config.has("practice") && (status.everJuror || status.eligible)) {
                SoftButton("去练习场", { ctx.go(JuryPage.Practice) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(value, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1)
        Text(label, style = KcTheme.type.caption, color = colors.muted, maxLines = 1)
    }
}

/** 一致率环：没有已定案的票时只画灰环和一道横线 */
@Composable
private fun AgreementRing(rate: Double?) {
    val colors = KcTheme.colors
    val track = colors.panelMuted
    val progress = colors.ok
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (rate != null) drawArc(progress, -90f, (360f * rate.toFloat()).coerceIn(0f, 360f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(rateText(rate), style = KcTheme.type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
    }
}

/** 申请成为委员（含续任）：条件清单 + 陈述 */
@Composable
private fun ApplyDialog(ctx: JuryCtx, status: JuryStatus, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    var statement by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val needExam = status.examRequired && status.exam?.passed != true
    DialogCard(onDismiss, if (status.status == "expired") "续任" else "申请成为风纪委员") {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            status.requirements.forEach { req ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(if (req.ok) "✓" else "✗", color = if (req.ok) colors.ok else colors.danger, style = KcTheme.type.body, modifier = Modifier.padding(end = 8.dp))
                    Column {
                        Text(req.text, style = KcTheme.type.body, color = colors.text)
                        req.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = KcTheme.type.caption, color = colors.muted) }
                    }
                }
            }
            if (needExam) {
                Text("申请前需要先通过入门考试，可以在练习场里进入考试。", style = KcTheme.type.secondary, color = colors.warn)
            }
            PlainField(statement, { statement = it.take(300) }, "说说你为什么想当风纪委员（选填，最多 300 字）", singleLine = false, minHeight = 80.dp)
            Text("提交后由管理员审批，通过后任期 ${ctx.config.rules.termDays} 天。", style = KcTheme.type.caption, color = colors.muted)
            error?.let { Text(it, style = KcTheme.type.secondary, color = colors.danger) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("取消", onDismiss, Modifier.weight(1f))
            PrimaryButton(if (sending) "提交中…" else "提交申请", {
                sending = true
                error = null
                scope.launch {
                    runCatching { ctx.api.apply(statement.trim()) }
                        .onSuccess { Haptics.confirm(view); ctx.refresh(); onDismiss() }
                        .onFailure { Haptics.reject(view); error = it.userMessage() }
                    sending = false
                }
            }, Modifier.weight(1f), enabled = !sending && status.eligible && !needExam)
        }
    }
}
