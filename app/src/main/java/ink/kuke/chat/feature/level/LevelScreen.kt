package ink.kuke.chat.feature.level

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.settings.GroupCard
import ink.kuke.chat.feature.settings.LoadBox
import ink.kuke.chat.feature.settings.Loader
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.feature.settings.SecondaryButton
import ink.kuke.chat.feature.settings.rememberLoader
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 账号等级（设计稿 P26）：「我的等级」Hero + 今天的来源 + 近 30 天柱图 + 规则 / 里程碑；另一页签是排行榜（总榜 / 月榜）。
 * 契约：KukeChat-Server/docs/account-level.md。规则里的数值全部来自 GET /levels/rules，这里不写死阈值。
 */
@Composable
fun LevelScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    var tab by remember { mutableIntStateOf(0) }
    KcPage(title = "账号等级", onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Segmented(listOf("我的等级", "排行榜"), tab, { tab = it })
            }
            if (tab == 0) MyLevelTab(graph) else LevelBoardTab(graph)
        }
    }
}

// ------------------------------------------------------------------ 我的等级

private class MyLevelData(val rules: LevelRules, val me: LevelMe, val history: List<HistoryDay>)

@Composable
private fun MyLevelTab(graph: AppGraph) {
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("level.me")) { loadMyLevel(graph) }
    // 页面开着的时候每分钟重拉一次：今天的在线时长、加成是实时累计的
    LaunchedEffect(loader) {
        while (true) {
            delay(60_000)
            loader.load(silent = true)
        }
    }
    LoadBox(loader) { data ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroCard(data.me, data.rules)
            data.me.today?.let { TodayCard(it, data.rules) }
            HistoryCard(data.history)
            RulesCard(data.rules, data.me)
        }
    }
}

private suspend fun loadMyLevel(graph: AppGraph): MyLevelData {
    val rules = KcJson.decodeFromJsonElement(LevelRules.serializer(), graph.api.get("/levels/rules"))
    val me = KcJson.decodeFromJsonElement(LevelMe.serializer(), graph.api.get("/levels/me"))
    val history = runCatching {
        val element = graph.api.get("/levels/history?days=30")
        val array = (element as? JsonArray) ?: ((element as? JsonObject)?.get("items") as? JsonArray) ?: JsonArray(emptyList())
        KcJson.decodeFromJsonElement(ListSerializer(HistoryDay.serializer()), array)
    }.getOrDefault(emptyList())
    return MyLevelData(rules, me, history)
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(KcTheme.colors.panel).padding(16.dp)) { content() }
}

@Composable
private fun CardTitle(text: String, aside: String? = null) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = KcTheme.type.title, color = KcTheme.colors.text, modifier = Modifier.weight(1f))
        if (aside != null) Text(aside, style = KcTheme.type.caption, color = KcTheme.colors.muted)
    }
}

@Composable
private fun Meter(value: Double, max: Double, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val fraction = if (max > 0) (value / max).coerceIn(0.0, 1.0).toFloat() else 0f
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(colors.panelMuted)) {
        Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(CircleShape).background(colors.fill))
    }
}

@Composable
private fun HeroCard(me: LevelMe, rules: LevelRules) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(colors.accentSoft).padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (me.level > 0) LevelIcons(me.level, size = 26.dp) else LevelGlyph(LevelIconKind.Star, 26.dp)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Lv.", style = type.secondary, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
                    Text(me.level.toString(), color = colors.text, fontSize = (32 * 1f).sp, fontWeight = FontWeight.W800)
                }
                Text("累计活跃 ${formatDays(me.activeDays)} 天", style = type.secondary, color = colors.muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        val maxed = me.nextLevel == null || me.nextLevelDays == null
        if (maxed) {
            Text("已经是最高等级（Lv.${rules.maxLevel}）", style = type.secondary, color = colors.text)
        } else {
            val span = (me.nextLevelDays ?: 0.0) - me.levelDays
            val done = (me.activeDays - me.levelDays).coerceAtLeast(0.0)
            Row {
                Text(
                    me.daysToNext?.let { "还差 ${formatDays(it)} 天升到 Lv.${me.nextLevel}" } ?: "下一级 Lv.${me.nextLevel}",
                    style = type.secondary, color = colors.text, modifier = Modifier.weight(1f),
                )
                Text("${formatDays(me.activeDays)} / ${formatDays(me.nextLevelDays ?: 0.0)} 天", style = type.caption, color = colors.muted)
            }
            Meter(done, span, Modifier.padding(top = 8.dp))
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("连续活跃", "${me.streak} 天", Modifier.weight(1f))
            Fact("全站名次", formatRank(me.rank), Modifier.weight(1f))
            Fact("注册", "${"%,d".format(me.registeredDays)} 天", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier) {
    val colors = KcTheme.colors
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = KcTheme.type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
        Text(label, style = KcTheme.type.caption, color = colors.muted)
    }
}

@Composable
private fun TodayCard(today: LevelToday, rules: LevelRules) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val cap = if (today.cap > 0) today.cap else rules.dailyCap
    val tiers = rules.online.sortedBy { it.minutes }
    val nextTier = tiers.firstOrNull { it.minutes > today.onlineMinutes }
    val baseEarned = today.base > 0
    val states = today.bonuses.associateBy { it.key }
    Card {
        CardTitle("今天", "已得 ${formatDayPart(today.earned)} / 上限 ${formatDayPart(cap)}")
        Meter(today.earned, cap)
        Row(Modifier.padding(top = 12.dp)) {
            Text("在线 ${formatMinutes(today.onlineMinutes)}", style = type.secondary, color = colors.text, modifier = Modifier.weight(1f))
            Text("基础 +${formatDayPart(today.base)}", style = type.secondary, color = colors.text)
        }
        val quiet = (if (nextTier != null) "再在线 ${formatMinutes(nextTier.minutes - today.onlineMinutes)}，基础活跃天变成 ${formatDayPart(nextTier.days)}。"
        else if (tiers.isNotEmpty()) "今天的基础活跃天已经拿满。" else "") + if (baseEarned) "" else "加成要先拿到基础活跃天才生效。"
        if (quiet.isNotBlank()) Text(quiet, style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
        if (rules.bonuses.isNotEmpty()) {
            Text("加成", style = type.group, color = colors.muted, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            rules.bonuses.forEach { rule ->
                val state = states[rule.key]
                val earned = state?.earned == true
                val target = state?.target ?: rule.target
                val current = state?.current
                val counted = target != null && target > 0 && current != null
                val reached = counted && current!! >= target!!
                val note = when {
                    earned -> "已拿到"
                    reached -> if (baseEarned) "已达成，今天按更高的一档计入" else "已达成，拿到基础活跃天后生效"
                    counted -> "${formatDays(current!!)} / ${formatDays(target!!)} · 还差 ${formatDays(target - current)}"
                    else -> if (baseEarned) "还没达成" else "拿到基础活跃天后才计入"
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(if (earned) colors.ok else colors.panelMuted),
                        contentAlignment = Alignment.Center,
                    ) { if (earned) KcIcon(KcIcons.check, tint = Color.White, size = 12.dp) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(rule.label, style = type.secondary, color = colors.text)
                        Text(note, style = type.caption, color = colors.muted)
                        if (counted && !reached) Meter(current!!, target!!, Modifier.padding(top = 5.dp))
                    }
                    Text("+${formatDayPart(state?.days ?: rule.days)}", style = type.secondary, color = if (earned) colors.ok else colors.muted)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(items: List<HistoryDay>) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val total = items.sumOf { it.earned }
    val activeDays = items.count { it.earned > 0 }
    Card {
        CardTitle("近 30 天", if (items.isNotEmpty() && total > 0) "共 ${formatDays(total)} 天 · $activeDays 天有活跃" else null)
        if (items.isEmpty() || total <= 0) {
            Text("近 30 天还没有拿到活跃天。在线满一会儿，这里就会长出第一根柱子。", style = type.caption, color = colors.muted)
        } else {
            val peak = items.maxOf { it.earned }.coerceAtLeast(0.5)
            val base = colors.fill
            val bonus = colors.fill.copy(alpha = 0.35f)
            Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                val n = items.size
                val gap = 3.dp.toPx()
                val barW = (size.width - gap * (n - 1)) / n
                items.forEachIndexed { i, day ->
                    val x = i * (barW + gap)
                    val h = (day.earned / peak).toFloat() * size.height
                    val baseH = (minOf(day.base, day.earned) / peak).toFloat() * size.height
                    if (h > 0f) {
                        drawRoundRect(bonus, Offset(x, size.height - h), Size(barW, h), CornerRadius(2.dp.toPx()))
                        if (baseH > 0f) drawRoundRect(base, Offset(x, size.height - baseH), Size(barW, baseH), CornerRadius(2.dp.toPx()))
                    } else {
                        drawRoundRect(colors.panelMuted, Offset(x, size.height - 2.dp.toPx()), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(formatDayLabel(items.first().date), style = type.caption, color = colors.subtle, modifier = Modifier.weight(1f))
                Text("${formatDayLabel(items.last().date)}（今天）", style = type.caption, color = colors.subtle)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot(base, "基础")
                LegendDot(bonus, "加成")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = KcTheme.type.caption, color = KcTheme.colors.muted, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun RulesCard(rules: LevelRules, me: LevelMe) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val tiers = rules.online.sortedBy { it.minutes }
    val icons = rules.icons.ifEmpty { LevelIconKind.entries.map { IconRule(it.name.lowercase(), it.label, it.levels) } }
        .mapNotNull { rule -> LevelIconKind.entries.firstOrNull { it.name.equals(rule.key, ignoreCase = true) }?.let { it to rule } }
        .sortedBy { it.second.levels }
    Card {
        CardTitle("规则")
        Text("等级怎么算", style = type.group, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            buildString {
                append("等级看的是累计活跃天数，升得很慢：")
                if (rules.formula.isNotBlank()) append("升到 N 级需要 ${rules.formula} 天，")
                append("最高 ${rules.maxLevel} 级。")
                if (rules.dailyCap > 0) append("每天最多攒 ${formatDayPart(rules.dailyCap)} 个活跃天：")
                if (tiers.isNotEmpty()) append("当天在线" + tiers.joinToString("、") { "满 ${formatMinutes(it.minutes.toDouble())}记 ${formatDayPart(it.days)}" } + "（基础）")
                append(if (rules.bonuses.isNotEmpty()) "，再加上「今天」里列的各项加成。挂着不动只有基础，真的在聊天、签到才拿得满。" else "。")
            },
            style = type.secondary, color = colors.text,
        )
        Text("图标怎么换", style = type.group, color = colors.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        icons.firstOrNull()?.let { (kind, rule) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text("${rule.levels} 级", style = type.secondary, color = colors.text)
                Text("  =  ", style = type.secondary, color = colors.muted)
                LevelGlyph(kind, 16.dp)
                Text("  1 个${rule.label}", style = type.secondary, color = colors.text)
            }
        }
        for (i in 1 until icons.size) {
            val (fromKind, from) = icons[i - 1]
            val (toKind, to) = icons[i]
            val count = to.levels / from.levels
            if (count > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    LevelGlyph(fromKind, 16.dp)
                    Text(" $count 个${from.label}", style = type.secondary, color = colors.text)
                    Text("  =  ", style = type.secondary, color = colors.muted)
                    LevelGlyph(toKind, 16.dp)
                    Text(" 1 个${to.label}", style = type.secondary, color = colors.text)
                }
            }
        }
        if (rules.milestones.isNotEmpty()) {
            Text("里程碑", style = type.group, color = colors.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            rules.milestones.sortedBy { it.level }.forEach { m ->
                val reached = me.activeDays >= m.days
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(96.dp)) { LevelIcons(m.level, size = 14.dp) }
                    Text("Lv.${m.level}", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600, modifier = Modifier.width(64.dp))
                    Text("${formatDays(m.days)} 天", style = type.secondary, color = colors.muted, modifier = Modifier.weight(1f))
                    Text(if (reached) "已达到" else "还差 ${formatDays(m.days - me.activeDays)} 天", style = type.caption, color = if (reached) colors.ok else colors.muted)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 排行榜

private const val BoardPage = 50

/** 活跃榜（总榜 / 月榜）。LevelScreen 的页签和 RankingScreen 的「活跃榜」页签共用 */
@Composable
fun LevelBoardTab(graph: AppGraph) {
    var period by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Segmented(listOf("总榜", "月榜"), period, { period = it })
        }
        // 切换总榜 / 月榜换一个 Loader（key = period）
        val key = if (period == 0) "all" else "month"
        BoardList(graph, key)
    }
}

@Composable
private fun BoardList(graph: AppGraph, period: String) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    var extra by remember(period) { mutableStateOf<List<BoardItem>>(emptyList()) }
    var loadingMore by remember(period) { mutableStateOf(false) }
    var moreError by remember(period) { mutableStateOf<String?>(null) }
    val loader = rememberLoader(period, cache = graph.screenCache.slot("level.board.$period")) {
        extra = emptyList()
        KcJson.decodeFromJsonElement(LevelBoard.serializer(), graph.api.get("/levels/leaderboard?period=$period&limit=$BoardPage&offset=0"))
    }

    LoadBox(loader) { board ->
        val all = board.items + extra
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (board.me != null) 88.dp else 24.dp)) {
                if (all.isEmpty()) {
                    item {
                        Text("榜上还没有人", style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 64.dp))
                    }
                }
                items(all, key = { it.rank.toString() + "-" + (it.user?.id ?: 0) }) { item ->
                    BoardRow(item, period == "month", onClick = { item.user?.let { nav.go(UserRoute(it.id)) } })
                }
                if (all.size < board.total) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            SecondaryButton(if (loadingMore) "加载中…" else moreError ?: "加载更多", enabled = !loadingMore, onClick = {
                                scope.launch {
                                    loadingMore = true
                                    moreError = null
                                    runCatching {
                                        KcJson.decodeFromJsonElement(LevelBoard.serializer(), graph.api.get("/levels/leaderboard?period=$period&limit=$BoardPage&offset=${all.size}"))
                                    }.onSuccess { extra = extra + it.items }.onFailure { moreError = it.userMessage() }
                                    loadingMore = false
                                }
                            })
                        }
                    }
                }
            }
            // 「你的位置」钉在底部
            board.me?.let { mine ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.accentSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("你的位置", style = type.caption, color = colors.accent, modifier = Modifier.padding(end = 10.dp))
                        Text(formatRank(mine.rank), style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700, modifier = Modifier.weight(1f))
                        LevelIcons(mine.level, size = 14.dp, compact = true)
                        Text(
                            if (period == "month") "本月 +${formatDays(mine.gained)}" else "${formatDays(mine.activeDays)} 天",
                            style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

private val MedalGold = Color(0xFFF5B301)
private val MedalSilver = Color(0xFFA8B0BC)
private val MedalBronze = Color(0xFFC98A5B)

@Composable
private fun BoardRow(item: BoardItem, monthly: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val user = item.user
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val medal = when (item.rank) { 1 -> MedalGold; 2 -> MedalSilver; 3 -> MedalBronze; else -> null }
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            if (medal != null) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(medal), contentAlignment = Alignment.Center) {
                    Text(item.rank.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W800)
                }
            } else {
                Text(item.rank.toString(), style = type.secondary, color = colors.muted, fontWeight = FontWeight.W600)
            }
        }
        Avatar(user?.avatarUrl, user?.displayName ?: "?", tone = user?.id ?: 0, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(user?.displayName ?: "已注销用户", style = type.rowTitle, color = colors.text, maxLines = 1)
            LevelIcons(item.level, size = 13.dp, compact = true)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (monthly) "+${formatDays(item.gained)}" else formatDays(item.activeDays), style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
            Text(if (monthly) "本月活跃天" else "活跃天", style = type.caption, color = colors.muted)
        }
    }
}
