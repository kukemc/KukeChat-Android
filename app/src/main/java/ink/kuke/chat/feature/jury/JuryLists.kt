package ink.kuke.chat.feature.jury

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.SectionLabel
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 筛选条：一排胶囊，选中用强调色浅底 */
@Composable
internal fun FilterChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.accentSoft else colors.panelMuted)
                    .clickable { onSelect(key) }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(label, style = KcTheme.type.secondary, color = if (on) colors.accent else colors.muted, fontWeight = if (on) FontWeight.W600 else FontWeight.W400) }
        }
    }
}

/* ───────────────────────── 我的评审 ───────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JuryMinePage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    // 「全部」的第一页和统计（内存）：先摆出来，再静默刷新；翻页、其它筛选照旧
    val cache = remember(ctx.graph) { ctx.graph.screenCache.slot<Triple<List<VoteItem>, Int, MyStats?>>("jury.mine") }
    val cached = remember(cache) { cache.get() }
    var rows by remember { mutableStateOf(cached?.first ?: emptyList()) }
    var total by remember { mutableIntStateOf(cached?.second ?: 0) }
    var stats by remember { mutableStateOf(cached?.third) }
    var loading by remember { mutableStateOf(cached == null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(filter, tick) {
        runCatching {
            val page = ctx.api.votes(0, state = filter.takeIf { it != "all" })
            rows = page.items
            total = page.total
            stats = runCatching { ctx.api.myStats() }.getOrNull() ?: stats
            error = null
            if (filter == "all") cache.put(Triple(page.items, page.total, stats))
        }.onFailure { error = it.userMessage() }
        loading = false
        refreshing = false
    }

    KcPage("我的评审", onBack = { ctx.back() }) {
        Column(Modifier.fillMaxSize()) {
            FilterChips(listOf("all" to "全部", "open" to "未定案", "closed" to "已定案"), filter) { filter = it; loading = true }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.weight(1f)) {
                when {
                    loading -> CenterText("正在加载…")
                    error != null && rows.isEmpty() -> ErrorBlock(error!!, { loading = true; tick++ })
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stats?.let { s ->
                            item("stats") {
                                Card {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                        listOf(
                                            "已投" to s.votesTotal.toString(),
                                            "一致率" to if (s.votesSettled > 0) rateText(s.agreementRate) else "—",
                                            "质控正确" to if (s.goldenTotal > 0) rateText(s.goldenRate) else "—",
                                            "获赞" to s.likesReceived.toString(),
                                        ).forEach { (label, value) ->
                                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(value, style = type.title, color = colors.text)
                                                Text(label, style = type.caption, color = colors.muted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (rows.isEmpty()) item("empty") { Text("还没有评审记录", style = type.secondary, color = colors.muted, modifier = Modifier.padding(24.dp)) }
                        items(rows, key = { it.caseId }) { row ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel)
                                    .clickable(enabled = row.canView) { ctx.go(JuryPage.MineCase(row.caseId)) }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Tag(voteLabel(row.vote, short = true), VoteColors.of(row.vote), filled = true)
                                        Text(reviewNo(row.caseId), style = type.secondary, color = colors.text)
                                        if (row.isQualityCheck) Tag("质控", colors.warn)
                                    }
                                    Text(
                                        listOfNotNull(kindLabel(row.kind), row.primaryReason).joinToString(" · ") + " · " + dateTimeText(row.votedAt),
                                        style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    val state = when {
                                        row.vote == "recuse" -> "已回避"
                                        row.stage == "closed" -> "已定案：" + outcomeLabel(row.outcome) + (row.verdictLevel?.let { " · ${levelText(it)}" } ?: "") + if (row.overturned) "（已改判）" else ""
                                        else -> stageLabel(row.stage).ifBlank { "评审中" }
                                    }
                                    Text(state, style = type.caption, color = colors.subtle)
                                }
                                when (row.agreed) {
                                    true -> Text("一致", style = type.caption, color = colors.ok)
                                    false -> Text("不一致", style = type.caption, color = colors.danger)
                                    null -> Unit
                                }
                            }
                        }
                        if (rows.size < total) {
                            item("more") {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    SoftButton(if (loadingMore) "正在加载…" else "加载更多", {
                                        if (loadingMore) return@SoftButton
                                        loadingMore = true
                                        scope.launch {
                                            runCatching { ctx.api.votes(rows.size, state = filter.takeIf { it != "all" }) }.onSuccess { rows = rows + it.items; total = it.total }
                                            loadingMore = false
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ───────────────────────── 公示栏 ───────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JuryBulletinPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    // 「全部」的第一页（内存）：先摆出来，再静默刷新；翻页、其它筛选照旧
    val cache = remember(ctx.graph) { ctx.graph.screenCache.slot<Pair<List<PublicCase>, Int>>("jury.bulletin") }
    val cached = remember(cache) { cache.get() }
    var rows by remember { mutableStateOf(cached?.first ?: emptyList()) }
    var total by remember { mutableIntStateOf(cached?.second ?: 0) }
    var loading by remember { mutableStateOf(cached == null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(filter, tick) {
        runCatching {
            val page = ctx.api.publicCases(0, outcome = filter.takeIf { it != "all" })
            rows = page.items
            total = page.total
            error = null
            if (filter == "all") cache.put(page.items to page.total)
        }.onFailure { error = it.userMessage() }
        loading = false
        refreshing = false
    }

    KcPage("公示栏", onBack = { ctx.back() }) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "定案满 ${ctx.config.rules.publishDelayHours} 小时、且没有待处理申诉的案件才会公示。认定不违规的案件只公示结论和票数，不带内容。",
                style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FilterChips(listOf("all" to "全部", "violation" to "违规", "clean" to "不违规"), filter) { filter = it; loading = true }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.weight(1f)) {
                when {
                    loading -> CenterText("正在加载…")
                    error != null && rows.isEmpty() -> ErrorBlock(error!!, { loading = true; tick++ })
                    rows.isEmpty() -> CenterText("还没有公示的案件")
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { it.caseId }) { row ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel)
                                    .clickable { ctx.go(JuryPage.BulletinCase(row.caseId)) }.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Tag(outcomeLabel(row.outcome) + (row.level?.let { " · ${levelName(it)}" } ?: ""), if (row.outcome == "violation") colors.danger else colors.ok, filled = true)
                                    Text(publicNo(row.caseId), style = type.secondary, color = colors.text)
                                    if (row.overturned) Tag("已改判", colors.warn)
                                    if (row.precedentId != null && ctx.config.has("precedents")) Tag("判例", colors.accent)
                                }
                                Text(
                                    "${kindLabel(row.kind)} · ${reasonsText(row.reasons)}", style = type.caption, color = colors.muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${sourceLabel(row.source)} · ${row.votes} 票 · ${dateTimeText(row.closedAt)}", style = type.caption, color = colors.subtle,
                                )
                            }
                        }
                        if (rows.size < total) {
                            item("more") {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    SoftButton(if (loadingMore) "正在加载…" else "加载更多", {
                                        if (loadingMore) return@SoftButton
                                        loadingMore = true
                                        scope.launch {
                                            runCatching { ctx.api.publicCases(rows.size, outcome = filter.takeIf { it != "all" }) }.onSuccess { rows = rows + it.items; total = it.total }
                                            loadingMore = false
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val HiddenReasons = mapOf(
    "group_message" to ("群聊消息不公示内容" to "群聊里的话是说给群成员听的。为了保护群聊隐私，这类案件只公示举报理由、结论、等级、票数和用时，不展示消息原文。"),
    "clean" to ("认定不违规，不公示内容" to "委员认定这条内容没有违规。把原文挂出来等于公开「此人被举报过」，所以这里只公示结论和票数。"),
    "sensitive" to ("涉及隐私，不公示内容" to "这类案件的内容本身可能包含他人的个人信息，公示会造成二次伤害。"),
    "overturned" to ("结论已被改判，内容已撤下" to "这个案件定案后经申诉或复核被改判，原先公示的内容已撤下。"),
)

@Composable
internal fun JuryPublicCasePage(ctx: JuryCtx, caseId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val cache = remember(ctx.graph, caseId) { ctx.graph.screenCache.slot<PublicCase>("jury.public.$caseId") }
    var case by remember(caseId) { mutableStateOf(cache.get()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(caseId, tick) {
        runCatching { ctx.api.publicCase(caseId) }.onSuccess { case = it; error = null; cache.put(it) }.onFailure { error = it.userMessage() }
    }
    KcPage(publicNo(caseId), onBack = { ctx.back() }) {
        val current = case
        when {
            current == null && error != null -> ErrorBlock(error!!, { tick++ })
            current == null -> CenterText("正在加载…")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tag(outcomeLabel(current.outcome) + (current.level?.let { " · ${levelText(it)}" } ?: ""), if (current.outcome == "violation") colors.danger else colors.ok, filled = true)
                            current.categoryLabel?.let { Tag(it, colors.muted) }
                            if (current.overturned) Tag("已改判", colors.warn)
                        }
                        Text("${kindLabel(current.kind)} · 举报理由：${reasonsText(current.reasons)}（${current.reportCount} 人举报）", style = type.secondary, color = colors.text)
                        Text("${sourceLabel(current.source)} · ${current.votes} 张有效票 · 用时 ${hoursText(current.hours)} · ${dateTimeText(current.closedAt)}", style = type.caption, color = colors.muted)
                        if (current.overturned && !current.overturnNote.isNullOrBlank()) Text("改判说明：${current.overturnNote}", style = type.secondary, color = colors.warn)
                        current.tally?.let { TallyBars(it) }
                        if (current.precedentId != null && ctx.config.has("precedents")) {
                            SoftButton("本案是判例 · 查看裁判要旨", { ctx.go(JuryPage.Precedent(current.precedentId)) }, Modifier.padding(top = 6.dp))
                        }
                    }
                }
                val content = current.content
                if (content != null) {
                    Text("被举报的内容", style = type.group, color = colors.muted)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(content.text ?: "（没有文字）", style = type.body, color = colors.text)
                        if (content.imageCount > 0) Text("含 ${content.imageCount} 张图片（不展示）", style = type.caption, color = colors.muted)
                    }
                } else {
                    val reason = HiddenReasons[current.contentHiddenReason]
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(reason?.first ?: "这个案件不公示内容", style = type.rowTitle, color = colors.text)
                        reason?.second?.let { Text(it, style = type.secondary, color = colors.muted) }
                    }
                }
            }
        }
    }
}

/* ───────────────────────── 排行榜 ───────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JuryRankingPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var period by remember { mutableStateOf("week") }
    // 每个周期的榜单（内存）：先摆出来，再静默刷新
    var board by remember { mutableStateOf(ctx.graph.screenCache.get<Leaderboard>("jury.board.week")) }
    var loading by remember { mutableStateOf(board == null) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(period, tick) {
        runCatching { ctx.api.leaderboard(period) }.onSuccess { board = it; error = null; ctx.graph.screenCache.put("jury.board.$period", it) }.onFailure { error = it.userMessage() }
        loading = false
        refreshing = false
    }

    KcPage("排行榜", onBack = { ctx.back() }) {
        Column(Modifier.fillMaxSize()) {
            FilterChips(listOf("week" to "本周", "month" to "本月", "all" to "累计"), period) { next ->
                period = next
                val cachedBoard = ctx.graph.screenCache.get<Leaderboard>("jury.board.$next")
                if (cachedBoard != null) board = cachedBoard else loading = true
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.weight(1f)) {
                val data = board
                when {
                    loading -> CenterText("正在加载…")
                    data == null && error != null -> ErrorBlock(error!!, { loading = true; tick++ })
                    data == null || data.items.isEmpty() -> CenterText("本期还没有人上榜")
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.me?.let { me ->
                            item("me") {
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.accentSoft).padding(12.dp)) {
                                    Text("我的名次：第 ${me.rank} 名 · ${me.points} 风纪值" + (me.votes?.let { " · 投了 $it 票" } ?: ""), style = type.rowTitle, color = colors.accent)
                                }
                            }
                        }
                        items(data.items, key = { it.rank }) { item ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (item.me) colors.accentSoft else colors.panel).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${item.rank}", style = type.title,
                                    color = when (item.rank) { 1 -> colors.star; 2 -> colors.muted; 3 -> colors.warn; else -> colors.subtle },
                                    modifier = Modifier.width(36.dp),
                                )
                                val user = item.user
                                if (user != null) Avatar(user.avatarUrl, user.displayName, user.id, size = 36.dp)
                                else Box(Modifier.size(36.dp).clip(CircleShape).background(colors.panelMuted), contentAlignment = Alignment.Center) { Text("匿", style = type.caption, color = colors.muted) }
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(if (item.me) "我" else user?.displayName ?: "匿名委员", style = type.rowTitle, color = colors.text, maxLines = 1)
                                    Text(listOfNotNull(tierLabel(item.tier).takeIf { it.isNotBlank() }?.let { "${it}委员" }, item.votes?.let { "$it 票" }).joinToString(" · "), style = type.caption, color = colors.muted)
                                }
                                Text("${item.points}", style = type.title, color = colors.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ───────────────────────── 规则手册 ───────────────────────── */

@Composable
internal fun JuryHandbookPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var tab by remember { mutableStateOf("rules") }
    var rules by remember { mutableStateOf(ctx.graph.screenCache.get<ModerationRules>("jury.rules")) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        runCatching { ctx.api.rules() }.onSuccess { rules = it; error = null; ctx.graph.screenCache.put("jury.rules", it) }.onFailure { error = it.userMessage() }
    }

    KcPage("规则手册", onBack = { ctx.back() }) {
        Column(Modifier.fillMaxSize()) {
            FilterChips(listOf("rules" to "处罚规则", "guide" to "委员须知"), tab) { tab = it }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == "rules") {
                    val data = rules
                    when {
                        data == null && error != null -> ErrorBlock(error!!, { tick++ }, Modifier.padding(top = 40.dp))
                        data == null -> Text("正在加载处罚规则…", style = type.secondary, color = colors.muted)
                        else -> {
                            SectionLabel("违规类别", trailing = "每个类别有默认等级，评审时可按情节调整")
                            Card {
                                data.categories.forEachIndexed { index, category ->
                                    val level = data.levels.firstOrNull { it.level == category.level }
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(category.label, style = type.rowTitle, color = colors.text)
                                            Text("默认 ${levelText(category.level)}" + (level?.let { " · 记 ${it.points} 分 · ${penaltyText(it.penalty)}" } ?: ""), style = type.caption, color = colors.muted)
                                        }
                                    }
                                    if (index < data.categories.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 12.dp).height(1.dp).background(colors.border))
                                }
                            }
                            SectionLabel("违规等级")
                            Card {
                                data.levels.forEach { level ->
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("L${level.level} ${levelName(level.level)}", style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                                        Text("记 ${level.points} 分 · ${penaltyText(level.penalty)}", style = type.secondary, color = colors.muted)
                                    }
                                }
                            }
                            SectionLabel("累计记分的阶梯处置", trailing = "近 ${data.pointWindowDays} 天")
                            Card {
                                data.ladder.forEach { step ->
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${step.threshold} 分", style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                                        Text(penaltyText(step.penalty), style = type.secondary, color = colors.muted)
                                    }
                                }
                            }
                            val maxBan = ctx.config.rules.maxBanHours?.let { if (it % 24 == 0) "${it / 24} 天" else "$it 小时" } ?: "7 天"
                            Bullets(
                                listOf(
                                    "记分在 ${data.pointWindowDays} 天的滚动窗口内累计，满 ${data.pointWindowDays} 天的记分自动失效。",
                                    "累计分数跨过一个门槛时才触发那一档的处置；没有跨过新门槛，就只执行这一次违规本身的基础处置。",
                                    "机器和风纪委员给出的封禁最长 $maxBan，更重的等管理员确认。",
                                    "同一条内容只罚一次。机器拿不准的内容不会自动处理，也不记分，只进人工队列。",
                                    "被处罚的人可以对每条处罚申诉一次。申诉通过后记分清零，由它触发的禁言、封禁同步解除。",
                                ),
                            )
                        }
                    }
                } else {
                    val rulesCfg = ctx.config.rules
                    Text("委员须知", style = type.title, color = colors.text)
                    Bullets(
                        listOf(
                            "案件内容经过匿名处理，请只根据内容本身判断，不要搜索、猜测或联系当事人。",
                            consensusRuleText(rulesCfg, ctx.config.has("panel")),
                            "分到手的案件 ${rulesCfg.assignmentTtlHours} 小时内不投，会让给别的委员；案件 ${rulesCfg.timeoutHours} 小时没有结论会转出。",
                            "最近 24 小时最多评审 ${rulesCfg.dailyVotes} 件，按滚动 24 小时计算，不在零点清零。",
                            "拿不准就选「拿不准」或「上下文不足」，比随手投一票更负责；但弃权过多会被暂停发案。",
                            "认识当事人、或者和这件事有利害关系，请「主动回避」。",
                            "讨论违规内容免不了转述，观点区可以举报，被多人举报会自动折叠等待处理。",
                            "任期 ${rulesCfg.termDays} 天，条件仍满足时自动续任；一致率或质控正确率过低会被降级甚至暂停。",
                            "定案 ${rulesCfg.contentRetentionDays} 天后，案件内容会被清除，只保留结论。",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bullets(lines: List<String>) {
    val colors = KcTheme.colors
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp, end = 8.dp).size(5.dp).clip(CircleShape).background(colors.accent))
                    Text(line, style = KcTheme.type.secondary, color = colors.text)
                }
            }
        }
    }
}
