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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.SectionLabel
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/**
 * 成长：层级与晋升进度、风纪值、成就、风纪值明细、评审足迹、公开身份开关。
 * 每一块各看各的能力（tiers / points / achievements / badge），没开放的不出现也不请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JuryGrowthPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val config = ctx.config
    val status = ctx.status
    var refreshing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    KcPage("我的成长", onBack = { ctx.back() }) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; ctx.refresh(); tick++ }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (status != null) {
                    TierCard(status, showProgress = config.has("tiers"))
                    status.restriction?.let {
                        GrowthNote(it.message.ifBlank { "受处罚期间不能评审、发表观点和点赞" } + (it.until?.let { until -> "，至 ${dateTimeText(until)}" } ?: "") + "。", colors.danger)
                    }
                    remainingText(status.cooldownUntil)?.let { GrowthNote("最近的票里弃权太多，暂时不给你发新案件，约 $it 后恢复。", colors.warn) }
                    status.warnings.forEach { GrowthNote(it.message, colors.warn) }
                }
                if (config.has("achievements")) AchievementsBlock(ctx, tick)
                if (config.has("points")) PointsLedger(ctx, tick)
                HistoryBlock(ctx, tick)
                if (config.has("badge") && status != null) BadgeSetting(ctx, status)
            }
        }
        LaunchedEffect(tick) { refreshing = false }
    }
}

@Composable
private fun GrowthNote(text: String, tone: androidx.compose.ui.graphics.Color) {
    Text(text, style = KcTheme.type.secondary, color = tone, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.10f)).padding(12.dp))
}

/** 层级卡：当前层级、风纪值、一致率，以及到下一层还差什么 */
@Composable
private fun TierCard(status: JuryStatus, showProgress: Boolean) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val tier = status.tier
    val tint = when (tier) { "senior" -> colors.warn; "regular" -> colors.accent; else -> colors.ok }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    KcIcon(if (tier == "senior") KcIcons.crown else KcIcons.medal, tint = tint, size = 26.dp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(if (tier != null) "${tierLabel(tier)}委员" else membershipLabel(status.status), style = type.title, color = colors.text)
                    Text(if (status.isJuror) "在任" + (remainingText(status.termEndsAt)?.let { " · 任期还剩 $it" } ?: "") else membershipLabel(status.status), style = type.caption, color = colors.muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${status.points}", style = type.number, color = colors.text)
                    Text("风纪值", style = type.caption, color = colors.muted)
                }
            }
            status.stats?.let { stats ->
                Row(Modifier.fillMaxWidth()) {
                    listOf(
                        "已投" to stats.votesTotal.toString(),
                        "一致率" to if (stats.votesSettled > 0) rateText(stats.agreementRate) else "—",
                        "质控" to if (stats.goldenTotal > 0) "${stats.goldenCorrect}/${stats.goldenTotal}" else "—",
                    ).forEach { (label, value) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, style = type.rowTitle, color = colors.text)
                            Text(label, style = type.caption, color = colors.muted)
                        }
                    }
                }
            }
            val progress = status.tierProgress
            if (showProgress && progress?.nextTier != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("距离${tierLabel(progress.nextTier)}委员", style = type.group, color = colors.muted)
                    progress.requirements.forEach { req ->
                        val isRate = req.key.endsWith("rate")
                        val current = req.current
                        val fraction = if (current == null || req.target <= 0.0) 0f else (current / req.target).toFloat().coerceIn(0f, 1f)
                        val currentText = if (current == null) req.note ?: "—" else if (isRate) rateText(current) else current.toInt().toString()
                        val targetText = if (isRate) rateText(req.target) else req.target.toInt().toString()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(req.label, style = type.caption, color = colors.text, modifier = Modifier.weight(1f))
                                Text("$currentText / $targetText", style = type.caption, color = if (req.ok) colors.ok else colors.muted)
                            }
                            Bar(if (req.ok) 1f else fraction, if (req.ok) colors.ok else colors.accent)
                        }
                    }
                }
            } else if (showProgress && tier == "senior") {
                Text("你已经是最高层级。保持一致率和活跃度，避免被降级。", style = type.caption, color = colors.muted)
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, tint: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(KcTheme.colors.panelMuted)) {
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(tint))
    }
}

/** 成就：已获得的亮，未获得的显示进度 */
@Composable
private fun AchievementsBlock(ctx: JuryCtx, tick: Int) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var items by remember { mutableStateOf(ctx.graph.screenCache.get<List<Achievement>>("jury.achievements")) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        runCatching { ctx.api.achievements() }.onSuccess { items = it; error = null; ctx.graph.screenCache.put("jury.achievements", it) }.onFailure { error = it.userMessage() }
    }
    val list = items
    SectionLabel("成就", trailing = list?.let { "${it.count { a -> a.earnedAt != null }} / ${it.size}" })
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                list == null -> Text(error ?: "正在加载…", style = type.secondary, color = colors.muted)
                list.isEmpty() -> Text("还没有成就", style = type.secondary, color = colors.muted)
                else -> list.forEach { item ->
                    val earned = item.earnedAt != null
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (earned) colors.star.copy(alpha = 0.18f) else colors.panelMuted),
                            contentAlignment = Alignment.Center,
                        ) { KcIcon(KcIcons.award, tint = if (earned) colors.star else colors.subtle, size = 20.dp) }
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.label, style = type.rowTitle, color = if (earned) colors.text else colors.muted)
                            Text(item.description, style = type.caption, color = colors.muted)
                            if (!earned && item.target > 1) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { Bar((item.current.toFloat() / item.target).coerceIn(0f, 1f), colors.accent) }
                                    Text("${item.current}/${item.target}", style = type.caption, color = colors.subtle, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        if (earned) Text(dateTimeText(item.earnedAt).substringBefore(' '), style = type.caption, color = colors.subtle, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** 风纪值明细，分页往下加载 */
@Composable
private fun PointsLedger(ctx: JuryCtx, tick: Int) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<PointItem>>(emptyList()) }
    var count by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        runCatching { ctx.api.points(0) }.onSuccess { rows = it.items; count = it.count; error = null }.onFailure { error = it.userMessage() }
        loaded = true
    }
    SectionLabel("风纪值明细", trailing = if (count > 0) "共 $count 条" else null)
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            when {
                !loaded -> Text("正在加载…", style = type.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
                error != null && rows.isEmpty() -> Text(error!!, style = type.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
                rows.isEmpty() -> Text("还没有风纪值变动。投票、观点被认可、通过考试都会加分。", style = type.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
                else -> rows.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = item.caseId != null) { item.caseId?.let { ctx.go(JuryPage.MineCase(it)) } }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pointKindLabel(item.kind, item.label), style = type.secondary, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(dateTimeText(item.createdAt), item.caseId?.let { reviewNo(it) }).joinToString(" · "), style = type.caption, color = colors.subtle)
                        }
                        Text(signedPoints(item.points), style = type.rowTitle, color = if (item.points > 0) colors.ok else if (item.points < 0) colors.danger else colors.muted, fontWeight = FontWeight.W700)
                    }
                }
            }
            if (rows.isNotEmpty() && rows.size < count) {
                SoftButton(if (loadingMore) "正在加载…" else "加载更多", {
                    if (loadingMore) return@SoftButton
                    loadingMore = true
                    scope.launch {
                        runCatching { ctx.api.points(rows.size) }.onSuccess { rows = rows + it.items; count = it.count }
                        loadingMore = false
                    }
                }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
    }
}

/** 评审足迹：最近投过的案件和结论，点开回看 */
@Composable
private fun HistoryBlock(ctx: JuryCtx, tick: Int) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var items by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { runCatching { ctx.api.history() }.onSuccess { items = it } }
    val list = items ?: return
    if (list.isEmpty()) return
    SectionLabel("评审足迹", trailing = "${list.size} 件")
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            (if (expanded) list else list.take(8)).forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ctx.go(JuryPage.MineCase(item.caseId)) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(item.vote?.let { VoteColors.of(it) } ?: colors.subtle))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("${reviewNo(item.caseId)} · ${kindLabel(item.kind)}", style = type.secondary, color = colors.text)
                        Text(
                            listOfNotNull(item.vote?.let { "我投：${voteLabel(it)}" }, item.outcome?.let { "结论：${outcomeLabel(it)}" } ?: "未定案", dateTimeText(item.votedAt)).joinToString(" · "),
                            style = type.caption, color = colors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when (item.agreed) {
                        true -> Text("一致", style = type.caption, color = colors.ok)
                        false -> Text("不一致", style = type.caption, color = colors.danger)
                        null -> Unit
                    }
                }
            }
            if (list.size > 8) {
                Text(
                    if (expanded) "收起" else "展开全部 ${list.size} 件", style = type.caption, color = colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** 公开展示委员身份：默认不展示；案件里的匿名不受影响 */
@Composable
private fun BadgeSetting(ctx: JuryCtx, status: JuryStatus) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var visible by remember(status.badgeVisible) { mutableStateOf(status.badgeVisible) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionLabel("公开展示委员身份", trailing = if (visible) "已公开" else "未公开（默认）")
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("让别人看到我是风纪委员", style = type.rowTitle, color = colors.text)
                    Text(
                        if (status.isJuror) "随时可以关掉。关掉后不再对外展示。" else "只有在任期间徽章才会展示；现在打开，下次任职时生效。",
                        style = type.caption, color = colors.muted,
                    )
                }
                Switch(checked = visible, enabled = !pending, onCheckedChange = { next ->
                    pending = true
                    error = null
                    visible = next
                    scope.launch {
                        runCatching { ctx.api.setBadge(next) }
                            .onSuccess { Haptics.confirm(view); ctx.refresh() }
                            .onFailure {
                                visible = !next
                                error = if (it.apiCode() == "not_juror") "这一项只有当过风纪委员的人才能设置。" else it.userMessage()
                            }
                        pending = false
                    }
                })
            }
            error?.let { Text(it, style = type.secondary, color = colors.danger) }
            Text("打开后：聊天里你的昵称旁和资料卡上会多一个层级徽章；排行榜上显示你的昵称和头像（不公开时显示为「匿名委员」）。", style = type.caption, color = colors.muted)
            Text("永远不会公开：你评审过哪些案件、投了什么票、在观点区说过什么。案件里用的始终是匿名代号。", style = type.caption, color = colors.muted)
            Text("公开之前想一想：可能有人来打听案件或求情。遇到这种情况不要回应；和你有关系的案件请用「主动回避」。", style = type.caption, color = colors.subtle)
        }
    }
}
