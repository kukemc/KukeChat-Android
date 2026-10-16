package ink.kuke.chat.feature.user

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.contacts.ErrorRetry
import ink.kuke.chat.feature.contacts.FilterPill
import ink.kuke.chat.feature.contacts.Load
import ink.kuke.chat.feature.contacts.LoadingBox
import ink.kuke.chat.feature.contacts.ToastHost
import ink.kuke.chat.feature.contacts.asList
import ink.kuke.chat.feature.contacts.decode
import ink.kuke.chat.feature.contacts.rememberToast
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val HISTORY_DAYS = 14

/**
 * 「我们的友谊」页：等级进度、今天的来源、最近 14 天柱图、记录、徽章墙（含隐藏彩蛋）、选择挂哪一枚。
 * 契约 KukeChat-Server/docs/intimacy.md 第 3 节。打开时把徽章标成已看（「新」标记本次仍保留，下次进来才消失）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntimacyScreen(graph: AppGraph, friendId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val me = graph.session.session.value?.user

    // 上次看到的亲密度详情（内存）：先摆出来，再静默刷新
    val cache = remember(graph, friendId) { graph.screenCache.slot<IntimacySnapshot>("intimacy.$friendId") }
    val cached = remember(cache) { cache.get() }
    var detail by remember { mutableStateOf<Load<FriendIntimacyDetail>>(cached?.detail?.let { Load.Ready(it) } ?: Load.Loading) }
    var history by remember { mutableStateOf(cached?.history) }
    var rules by remember { mutableStateOf(cached?.rules) }
    var refreshing by remember { mutableStateOf(false) }
    var openSeries by remember { mutableStateOf<SeriesView?>(null) }
    var seenSent by remember { mutableStateOf(false) }

    suspend fun load(silent: Boolean) {
        if (!silent) detail = Load.Loading
        coroutineScope {
            val d = async { runCatching { graph.api.get("/intimacy/friends/$friendId").decode(FriendIntimacyDetail.serializer()) } }
            val h = async { runCatching { graph.api.get("/intimacy/friends/$friendId/history?days=$HISTORY_DAYS").decode(IntimacyHistory.serializer()).items } }
            val r = async { runCatching { graph.api.get("/intimacy/rules").decode(IntimacyRules.serializer()) } }
            d.await().onSuccess { detail = Load.Ready(it) }.onFailure { if (!silent || detail !is Load.Ready) detail = Load.Failed(it.userMessage()) }
            h.await().onSuccess { history = it }
            r.await().onSuccess { rules = it }
        }
        (detail as? Load.Ready)?.value?.let { fresh ->
            // 「新」徽章只在第一次看到时亮：存进缓存的那份去掉标记，下次进来不再闪一下
            val seen = fresh.copy(series = fresh.series.map { s -> s.copy(levels = s.levels.map { it.copy(isNew = false) }) })
            cache.put(IntimacySnapshot(seen, history, rules))
        }
    }

    LaunchedEffect(friendId) {
        IntimacyCache.start(graph)
        // 有缓存时静默刷新；没有缓存时初始状态本来就是「加载中」
        load(silent = true)
    }
    LaunchedEffect(friendId) {
        graph.realtime.events
            .filter { it.type == "intimacy.updated" && (it.data["friend_id"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull == friendId }
            .collect { load(true) }
    }
    // 打开徽章墙就把「新」标成已看；这一次的「新」标记仍然留在界面上
    LaunchedEffect(detail) {
        val ready = (detail as? Load.Ready)?.value ?: return@LaunchedEffect
        if (!seenSent && ready.series.any { s -> s.levels.any { it.isNew } }) {
            seenSent = true
            runCatching { graph.api.post("/intimacy/friends/$friendId/seen") }.onSuccess { IntimacyCache.refresh(graph) }
        }
    }

    fun setDisplay(value: String) {
        scope.launch {
            runCatching { graph.api.put("/intimacy/friends/$friendId/badge", putBadgeBody(value)) }
                .onSuccess {
                    toast.show(when (value) { "auto" -> "已设为自动挂最好的徽章"; "none" -> "名字旁不再挂徽章"; else -> "已挂在 TA 的名字旁" })
                    load(true)
                    IntimacyCache.refresh(graph)
                }
                .onFailure { toast.show(it.userMessage()) }
        }
    }

    KcPage(title = "我们的友谊", onBack = { nav.back() }) {
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; load(true); refreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val state = detail) {
                    Load.Loading -> LoadingBox("正在加载…")
                    is Load.Failed -> ErrorRetry(state.message, onRetry = { scope.launch { load(false) } })
                    is Load.Ready -> {
                        val d = state.value
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(key = "hero") { Hero(d, me?.avatarUrl, me?.displayName ?: "我", me?.id ?: 0L) }
                            item(key = "today") { TodayCard(d.today, d.stats.messagesMine, rules) }
                            item(key = "history") { HistoryCard(history) }
                            item(key = "stats") { StatsCard(d.stats) }
                            item(key = "wall-head") { BadgeWallHeader(d, onPick = ::setDisplay) }
                            val rows = d.series.chunked(2)
                            items(rows.size, key = { "series-$it" }) { index ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    rows[index].forEach { series ->
                                        SeriesCard(series, Modifier.weight(1f)) { openSeries = series }
                                    }
                                    if (rows[index].size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
            ToastHost(toast)
        }
    }

    openSeries?.let { series ->
        val d = (detail as? Load.Ready)?.value
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { openSeries = null }, sheetState = sheet, containerColor = colors.panel) {
            SeriesDetail(
                series = series,
                pinned = d?.display == series.key,
                onPin = { setDisplay(series.key); openSeries = null },
            )
        }
    }
}

// ---------------------------------------------------------------- 各块

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(KcTheme.colors.panel).padding(16.dp)) { content() }
}

@Composable
private fun Hero(d: FriendIntimacyDetail, myAvatar: String?, myName: String, myId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Card {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(myAvatar, myName, myId, 56.dp)
                Spacer(Modifier.width(10.dp))
                KcIcon(KcIcons.heart.duotone(), tint = colors.unread, size = 24.dp)
                Spacer(Modifier.width(10.dp))
                Avatar(d.friend.avatarUrl, d.friend.displayName, d.friend.id, 56.dp)
            }
            Spacer(Modifier.height(10.dp))
            Text("你和 ${d.friend.displayName}", style = type.secondary, color = colors.muted)
            Spacer(Modifier.height(4.dp))
            Text("Lv.${d.level.level} ${d.level.name}", style = type.title.copy(fontSize = 20.sp), color = colors.text)
            Spacer(Modifier.height(2.dp))
            Text("${d.intimacy}", style = type.number.copy(fontSize = 32.sp), color = colors.accent)
            Text("亲密度", style = type.caption, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            val next = d.level.nextThreshold
            val progress = if (next == null) 1f else {
                val span = (next - d.level.threshold).coerceAtLeast(1)
                ((d.intimacy - d.level.threshold).toFloat() / span).coerceIn(0f, 1f)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(colors.panelMuted)) {
                Box(Modifier.fillMaxWidth(progress).height(8.dp).clip(CircleShape).background(colors.fill))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (next == null) "已经是最高等级了" else "还差 ${(next - d.intimacy).coerceAtLeast(0)} 点升级为「${d.level.nextName ?: "下一级"}」",
                style = type.caption, color = colors.muted,
            )
            Spacer(Modifier.height(8.dp))
            val facts = buildList {
                if (d.friendDays > 0) add("成为好友 ${d.friendDays} 天")
                d.rank?.let { add("亲密榜第 $it / ${d.friendCount}") }
            }
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = type.caption, color = colors.subtle)
        }
    }
}

@Composable
private fun TodayCard(today: IntimacyToday, myMessages: Int, rules: IntimacyRules?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    fun label(key: String, fallback: String) = rules?.sources?.firstOrNull { it.key == key }?.label ?: fallback
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今天", style = type.title, color = colors.text, modifier = Modifier.weight(1f))
                Text("+${today.gained}", style = type.number.copy(fontSize = 20.sp), color = colors.accent)
            }
            Spacer(Modifier.height(4.dp))
            Text("每天最多涨 ${today.cap} 点（生日礼物不占上限）", style = type.caption, color = colors.muted)
            Spacer(Modifier.height(10.dp))
            val counted = if (today.cap > 0) (today.counted.toFloat() / today.cap).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.panelMuted)) {
                Box(Modifier.fillMaxWidth(counted).height(6.dp).clip(CircleShape).background(colors.fill))
            }
            Spacer(Modifier.height(12.dp))
            SourceRow(label("chat", "互相聊天"), today.chat, today.chatCap.takeIf { it > 0 })
            SourceRow(label("gift", "送礼物"), today.gift, today.giftCap.takeIf { it > 0 })
            SourceRow(label("streak", "连续互动"), today.streak, null)
            SourceRow(label("birthday", "生日礼物"), today.birthday, null)
            Spacer(Modifier.height(8.dp))
            Text("今天你发了 ${today.messagesMine} 条，TA 发了 ${today.messagesTheirs} 条", style = type.caption, color = colors.subtle)
        }
    }
}

@Composable
private fun SourceRow(label: String, value: Int, cap: Int?) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = KcTheme.type.secondary, color = colors.text, modifier = Modifier.weight(1f))
        Text(if (cap != null) "$value / $cap" else "+$value", style = KcTheme.type.secondary, color = if (value > 0) colors.accent else colors.muted)
    }
}

@Composable
private fun HistoryCard(history: List<IntimacyHistoryDay>?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Card {
        Column {
            Text("最近 $HISTORY_DAYS 天", style = type.title, color = colors.text)
            Spacer(Modifier.height(12.dp))
            if (history == null) {
                Text("正在加载…", style = type.caption, color = colors.muted)
            } else if (history.isEmpty() || history.all { it.gained == 0 }) {
                Text("这段时间还没有涨过亲密度，多聊几句吧", style = type.caption, color = colors.muted)
            } else {
                val bar = colors.fill
                val stub = colors.borderStrong
                val dot = colors.ok
                val max = history.maxOf { it.gained }.coerceAtLeast(1)
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val count = history.size
                    val slot = size.width / count
                    val barWidth = slot * 0.56f
                    val chartHeight = size.height - 14.dp.toPx()
                    history.forEachIndexed { i, day ->
                        val h = if (day.gained == 0) 3.dp.toPx() else (day.gained.toFloat() / max * chartHeight).coerceAtLeast(6.dp.toPx())
                        val x = slot * i + (slot - barWidth) / 2
                        drawRoundRect(
                            color = if (day.gained == 0) stub else bar,
                            topLeft = Offset(x, chartHeight - h),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        )
                        if (day.mutual) drawCircle(dot, 2.5.dp.toPx(), Offset(slot * i + slot / 2, size.height - 4.dp.toPx()))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(shortDate(history.first().date), style = type.caption, color = colors.subtle, modifier = Modifier.weight(1f))
                    Text("绿点：双方都聊了天", style = type.caption, color = colors.subtle)
                    Text(shortDate(history.last().date), style = type.caption, color = colors.subtle, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun shortDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MM/dd"))
}.getOrDefault(iso)

@Composable
private fun StatsCard(s: IntimacyStats) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val cells = listOf(
        "互发消息" to s.messagesTotal, "一起聊天的天数" to s.mutualDays,
        "当前连续" to s.streakDays, "最长连续" to s.bestStreak,
        "深夜互聊天数" to s.nightDays, "清晨互聊天数" to s.dawnDays,
        "周末互动天数" to s.weekendDays, "单日最多消息" to s.bestDayMessages,
        "互送礼物" to s.giftCount, "生日礼物" to s.birthdayGifts,
    )
    Card {
        Column {
            Text("记录", style = type.title, color = colors.text)
            Spacer(Modifier.height(8.dp))
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(value.toString(), style = type.number.copy(fontSize = 18.sp), color = colors.text)
                            Text(label, style = type.caption, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeWallHeader(d: FriendIntimacyDetail, onPick: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Card {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("徽章墙", style = type.title, color = colors.text, modifier = Modifier.weight(1f))
                Text("${d.badgeCount} / ${d.totalBadges}", style = type.secondary, color = colors.muted)
            }
            Spacer(Modifier.height(4.dp))
            Text("选择挂在 TA 名字旁的那一枚，只有你自己看得到", style = type.caption, color = colors.muted)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("自动", selected = d.display == "auto") { onPick("auto") }
                FilterPill("不挂", selected = d.display == "none") { onPick("none") }
                d.series.filter { it.level > 0 && it.revealed }.forEach { series ->
                    FilterPill(series.name ?: series.key, selected = d.display == series.key) { onPick(series.key) }
                }
            }
            d.badge?.let { badge ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BadgeGlyph(badge.icon, badge.tier, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("现在挂的是「${badge.name}」", style = type.secondary, color = colors.text)
                }
            }
        }
    }
}

/** 一个系列当前该画的外框档位：已解锁的最高一级；一级都没解锁就画第一级的外框 */
private fun SeriesView.currentTier(): Int {
    val unlocked = levels.filter { it.unlockedAt != null }
    return (unlocked.maxByOrNull { it.level } ?: levels.firstOrNull())?.tier ?: 1
}

private fun SeriesView.conditionText(): String? {
    val template = condition ?: return null
    val n = nextThreshold ?: levels.lastOrNull()?.threshold
    return if (n != null) template.replace("{n}", n.toString()) else template.replace("{n}", "N")
}

@Composable
private fun SeriesCard(series: SeriesView, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val locked = series.level == 0
    val mystery = series.hidden && !series.revealed
    val hasNew = series.levels.any { it.isNew }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(colors.panel).clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            BadgeGlyph(series.icon, series.currentTier(), 56.dp, locked = locked, mystery = mystery)
            if (hasNew) {
                Box(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(6.dp)).background(colors.unread).padding(horizontal = 4.dp)) {
                    Text("新", color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.W700)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (mystery) "？？？" else series.name ?: series.key, style = type.rowTitle.copy(fontSize = 14.sp), color = if (locked) colors.muted else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                mystery -> "隐藏成就，达成时揭晓"
                series.level >= series.maxLevel && series.maxLevel > 0 -> "已集齐 ${series.level}/${series.maxLevel}"
                else -> "${series.level}/${series.maxLevel} · ${series.current ?: 0}" + (series.nextThreshold?.let { " / $it" } ?: "")
            },
            style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SeriesDetail(series: SeriesView, pinned: Boolean, onPin: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val mystery = series.hidden && !series.revealed
    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BadgeGlyph(series.icon, series.currentTier(), 64.dp, locked = series.level == 0, mystery = mystery)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (mystery) "隐藏成就" else series.name ?: series.key, style = type.title, color = colors.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (mystery) "在一起经历某件事之后才会揭晓" else series.conditionText() ?: "",
                    style = type.secondary, color = colors.muted,
                )
            }
        }
        if (!mystery && series.current != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "当前 ${series.current}" + (series.nextThreshold?.let { "，下一级需要 $it" } ?: "，已经全部解锁"),
                style = type.caption, color = colors.subtle,
            )
        }
        Spacer(Modifier.height(14.dp))
        series.levels.forEach { level ->
            val unlocked = level.unlockedAt != null
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BadgeGlyph(series.icon, level.tier, 36.dp, locked = !unlocked, mystery = mystery)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (mystery || level.name == null) "？？？" else level.name, style = type.rowTitle, color = if (unlocked) colors.text else colors.muted)
                    Text(
                        when {
                            unlocked -> "解锁于 ${unlockDate(level.unlockedAt)}"
                            level.threshold != null -> "需要 ${level.threshold}"
                            else -> "尚未解锁"
                        },
                        style = type.caption, color = colors.subtle,
                    )
                }
                if (level.isNew) Text("新", style = type.caption, color = colors.unread, fontWeight = FontWeight.W700)
            }
        }
        if (series.level > 0 && !mystery) {
            Spacer(Modifier.height(14.dp))
            if (pinned) {
                Text("已挂在 TA 的名字旁", style = type.secondary, color = colors.ok, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                ink.kuke.chat.feature.contacts.SmallButton("挂在 TA 的名字旁", modifier = Modifier.fillMaxWidth(), primary = true, onClick = onPin)
            }
        }
    }
}

private fun unlockDate(iso: String?): String {
    val instant = parseInstant(iso) ?: return ""
    return DateTimeFormatter.ofPattern("yyyy/MM/dd").format(instant.atZone(ZoneId.systemDefault()))
}

/** 好友亲密度页的快照（只在内存，退出登录清掉） */
private class IntimacySnapshot(val detail: FriendIntimacyDetail, val history: List<IntimacyHistoryDay>?, val rules: IntimacyRules?)
