package ink.kuke.chat.feature.gifts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.contacts.relativeTime
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

private const val PageSize = 20
private const val WallRecordsPage = 8

/**
 * 我的礼物记录（GET /gifts/mine）：收到的 / 送出的两个页签，顶上是这个方向的合计，往下翻自动加载更多。点一行去那个人的主页。
 * 入口：送礼面板右上角、侧边栏「礼物记录」。
 */
@Composable
fun GiftRecordsScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { GiftsApi(graph.api) }
    val catalog by Gifts.catalog.collectAsStateWithLifecycle()
    val tick by Gifts.tick.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val direction = if (tab == 0) "received" else "sent"
    var items by remember(direction) { mutableStateOf<List<GiftRecord>?>(null) }
    var total by remember(direction) { mutableIntStateOf(0) }
    var sum by remember(direction) { mutableIntStateOf(0) }
    var error by remember(direction) { mutableStateOf<String?>(null) }
    var loadingMore by remember(direction) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val charmLabel = catalog?.charmLabel ?: DefaultCharmLabel

    suspend fun loadFirst() {
        error = null
        runCatching { api.mine(direction, PageSize, 0) }
            .onSuccess { items = it.items; total = it.total; sum = it.sum }
            .onFailure { if (items == null) error = it.userMessage() }
    }

    fun loadMore() {
        val current = items ?: return
        if (loadingMore || current.size >= total) return
        loadingMore = true
        scope.launch {
            runCatching { api.mine(direction, PageSize, current.size) }
                .onSuccess { page -> items = (current + page.items).distinctBy { it.id }; total = page.total }
            loadingMore = false
        }
    }

    LaunchedEffect(Unit) { Gifts.ensureCatalog(graph) }
    LaunchedEffect(direction, tick) { loadFirst() }
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 4 } == true } }
    LaunchedEffect(nearEnd, items?.size) { if (nearEnd) loadMore() }

    KcPage(title = "礼物记录", onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(colors.panel).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Segmented(listOf("收到的", "送出的"), tab, { tab = it })
            }
            val list = items
            when {
                error != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("礼物记录没有加载出来：$error", style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    Text("重试", style = type.secondary, color = GiftColors.rose, fontWeight = FontWeight.W600, modifier = Modifier.padding(12.dp).clickable { scope.launch { loadFirst() } }.padding(8.dp))
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在加载…", style = type.secondary, color = colors.muted) }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp)) {
                    item(key = "sum") { SumCard(received = tab == 0, sum = sum, count = total, charmLabel = charmLabel) }
                    if (list.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                if (tab == 0) "还没有收到过礼物" else "还没有送过礼物",
                                if (tab == 0) "朋友送你的礼物会记在这里。" else "在聊天的工具栏、对方的个人主页上都可以送。",
                            )
                        }
                    } else {
                        item(key = "top") { Spacer(Modifier.height(10.dp)) }
                        items(list, key = { it.id }) { record ->
                            val received = tab == 0
                            val who = if (received) record.fromUser else record.toUser
                            GiftRecordRow(
                                record, received,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(colors.panel),
                                onOpenUser = who?.let { { nav.go(UserRoute(it.id)) } },
                                side = {
                                    Text(
                                        (if (received) "+" else "−") + coins(record.amount),
                                        style = type.number.copy(fontSize = 15.sp), fontWeight = FontWeight.W700,
                                        color = if (received) GiftColors.rose else colors.muted,
                                    )
                                },
                            )
                        }
                        if (loadingMore) item(key = "more") { Text("正在加载更多…", style = type.caption, color = colors.subtle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                        else if (list.size >= total && total > PageSize) item(key = "end") { Text("没有更多了", style = type.caption, color = colors.subtle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SumCard(received: Boolean, sum: Int, count: Int, charmLabel: String) {
    val colors = KcTheme.colors
    val rose = GiftColors.rose
    val gold = GiftColors.gold
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.panel)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(rose.copy(alpha = 0.16f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = size.width * 0.8f))
                drawRect(Brush.radialGradient(listOf(gold.copy(alpha = 0.16f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width, size.height), radius = size.width * 0.6f))
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (received) "共收到" else "共送出", style = KcTheme.type.caption, color = colors.muted)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                Text(coins(sum), style = KcTheme.type.number.copy(fontSize = 30.sp), fontWeight = FontWeight.W800, color = colors.text)
                Text(if (received) charmLabel else "币", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp))
            }
            Text("$count 次礼物", style = KcTheme.type.caption, color = colors.subtle)
        }
        GiftGlyph(if (received) "heart" else "box", 56.dp)
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    val colors = KcTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(GiftColors.roseSoft), contentAlignment = Alignment.Center) { KcIcon(KcIcons.gift, tint = GiftColors.rose, size = 28.dp) }
        Text(title, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 14.dp))
        Text(text, style = KcTheme.type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
    }
}

/**
 * 记录里的一行（礼物记录和礼物墙展开的那一段共用）：送的东西、谁、什么时候、生日标记、留言。
 * fromSide = true 时写送礼人（收到的、礼物墙），否则写收礼人（送出的）。留言只有送的人和收的人看得到，别人拿到的是 null。
 */
@Composable
fun GiftRecordRow(record: GiftRecord, fromSide: Boolean, modifier: Modifier = Modifier, onOpenUser: (() -> Unit)? = null, side: (@Composable () -> Unit)? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val user = if (fromSide) record.fromUser else record.toUser
    val name = user?.name ?: "有人"
    val press = rememberInteraction()
    Row(
        modifier.pressScale(press, 0.98f).clickable(interactionSource = press, indication = null, enabled = onOpenUser != null) { onOpenUser?.invoke() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(GiftColors.roseSoft.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            GiftThing(record.gift, record.item, 36.dp, wearer = record.toUser?.let { Wearer(it.id, it.name, it.avatarUrl) })
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (user != null) Avatar(user.avatarUrl, user.name, user.id, 18.dp, Modifier.padding(end = 6.dp))
                Text(
                    (if (fromSide) "$name 送的 " else "送给 $name ") + giftThingLabel(record.kind, record.gift, record.item, record.count),
                    style = type.secondary, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (record.onBirthday) {
                    Row(Modifier.padding(start = 6.dp).clip(CircleShape).background(GiftColors.gold.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        KcIcon(KcIcons.cake, tint = GiftColors.gold, size = 11.dp)
                        Text("生日礼物", fontSize = 10.5.sp, color = GiftColors.gold, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 2.dp))
                    }
                }
            }
            Text(
                relativeTime(record.createdAt) + if (record.conversationId == null) " · 从个人主页送的" else "",
                style = type.caption, color = colors.subtle, modifier = Modifier.padding(top = 2.dp),
            )
            record.message?.let {
                Text(
                    "「$it」", style = type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(colors.text.copy(alpha = 0.04f)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (side != null) Box(Modifier.padding(start = 10.dp)) { side() }
    }
}

// ------------------------------------------------------------------ 礼物墙

/**
 * 个人主页上的礼物墙（GET /users/{id}/gifts，公开）：一个数字（收到几件）+ 几种，下面是四列格子（图形一样大，名字、数量）。
 * 心意值放在小标题旁边。点某一种，格子下面展开它的送礼记录（谁送的、什么时候）。看别人的墙时右上角有「送礼物」。
 * 礼物功能关着（或服务端没有）、是机器人、接口出错时整块不出现。
 */
@Composable
fun GiftWallSection(graph: AppGraph, userId: Long, name: String, avatarUrl: String?, isSelf: Boolean, isBot: Boolean, charm: Int?, onSend: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val catalog by Gifts.catalog.collectAsStateWithLifecycle()
    val tick by Gifts.tick.collectAsStateWithLifecycle()
    val api = remember(graph) { GiftsApi(graph.api) }
    var wall by remember(userId) { mutableStateOf<GiftWall?>(null) }
    var failed by remember(userId) { mutableStateOf(false) }
    var opened by remember(userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) { Gifts.ensureCatalog(graph) }
    val active = catalog?.enabled == true && !isBot
    LaunchedEffect(userId, tick, active) {
        if (!active) return@LaunchedEffect
        runCatching { api.wall(userId) }.onSuccess { wall = it; failed = false }.onFailure { if (wall == null) failed = true }
    }
    if (!active || failed) return
    val charmLabel = catalog?.charmLabel ?: DefaultCharmLabel
    val charmValue = maxOf(charm ?: 0, wall?.charm ?: 0)

    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(colors.panel).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("礼物墙", style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
            Row(Modifier.padding(start = 8.dp).clip(CircleShape).background(GiftColors.roseSoft).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                KcIcon(KcIcons.heart, tint = GiftColors.rose, size = 11.dp)
                Text("$charmLabel ", fontSize = 11.sp, color = GiftColors.rose, modifier = Modifier.padding(start = 3.dp))
                Text(coins(charmValue), fontSize = 11.5.sp, color = GiftColors.rose, fontWeight = FontWeight.W700)
            }
            Spacer(Modifier.weight(1f))
            if (!isSelf) {
                val press = rememberInteraction()
                Row(
                    Modifier.pressScale(press).clip(CircleShape).border(1.dp, GiftColors.rose.copy(alpha = 0.4f), CircleShape)
                        .clickable(interactionSource = press, indication = null, onClick = onSend).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.gift, tint = GiftColors.rose, size = 14.dp)
                    Text("送礼物", style = type.caption, color = GiftColors.rose, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        val w = wall
        when {
            w == null -> Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { Box(Modifier.weight(1f).height(86.dp).clip(RoundedCornerShape(14.dp)).background(colors.panelMuted)) }
            }
            w.summary.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(GiftColors.roseSoft), contentAlignment = Alignment.Center) { KcIcon(KcIcons.gift, tint = GiftColors.rose, size = 22.dp) }
                Text(if (isSelf) "还没有收到礼物" else "$name 还没有收到礼物", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600, modifier = Modifier.padding(top = 10.dp))
                Text(if (isSelf) "朋友送你的礼物会摆在这里。" else "送出第一份礼物，它会一直摆在这里。", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
            else -> {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(coins(w.totalCount), style = type.number.copy(fontSize = 20.sp), color = colors.text, fontWeight = FontWeight.W800)
                    Text(" 件礼物 · ${w.summary.size} 种", style = type.caption, color = colors.muted, modifier = Modifier.padding(bottom = 3.dp))
                }
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    w.summary.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { entry ->
                                WallTile(entry, entry.key == opened, Wearer(userId, name, avatarUrl), Modifier.weight(1f)) { opened = if (opened == entry.key) null else entry.key }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                val openedEntry = w.summary.firstOrNull { it.key == opened }
                AnimatedVisibility(openedEntry != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    var last by remember { mutableStateOf(openedEntry) }
                    if (openedEntry != null) last = openedEntry
                    last?.let { entry -> androidx.compose.runtime.key(entry.key) { WallRecords(api, userId, entry) } }
                }
            }
        }
    }
}

@Composable
private fun WallTile(entry: GiftWallEntry, active: Boolean, wearer: Wearer, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shape = RoundedCornerShape(14.dp)
    val lift by animateFloatAsState(if (active) 1.1f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "wallTile")
    Column(
        modifier.pressScale(press).clip(shape)
            .background(if (active) GiftColors.roseSoft else colors.panelMuted)
            .border(1.5.dp, if (active) GiftColors.rose else Color.Transparent, shape)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(40.dp).graphicsLayer { scaleX = lift; scaleY = lift }, contentAlignment = Alignment.Center) {
            GiftThing(entry.gift, entry.item, 38.dp, wearer = wearer, sampleText = "你好")
        }
        Text(entry.label, style = KcTheme.type.caption, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text("×${coins(entry.count)}", fontSize = 11.sp, color = GiftColors.rose, fontWeight = FontWeight.W700)
    }
}

@Composable
private fun WallRecords(api: GiftsApi, userId: Long, entry: GiftWallEntry) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    var limit by remember { mutableIntStateOf(WallRecordsPage) }
    var page by remember { mutableStateOf<GiftRecordPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(limit, attempt) {
        loading = true
        error = null
        runCatching { api.wallRecords(userId, entry, limit, 0) }.onSuccess { page = it }.onFailure { error = it.userMessage() }
        loading = false
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(colors.panelMuted).padding(vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("「${entry.label}」的送礼记录", style = KcTheme.type.secondary, color = colors.text, fontWeight = FontWeight.W600, modifier = Modifier.weight(1f))
            page?.let { Text("共 ${it.total} 次", style = KcTheme.type.caption, color = colors.muted) }
        }
        val p = page
        when {
            p == null && error != null -> Text("记录没有加载出来：$error  点这里重试", style = KcTheme.type.caption, color = colors.danger, modifier = Modifier.padding(12.dp).clickable { attempt++ })
            p == null -> Text("加载中…", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(12.dp))
            p.items.isEmpty() -> Text("还没有记录。", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(12.dp))
            else -> {
                p.items.forEach { record ->
                    GiftRecordRow(record, fromSide = true, modifier = Modifier.fillMaxWidth(), onOpenUser = record.fromUser?.let { u -> { nav.go(UserRoute(u.id)) } })
                }
                if (p.total > p.items.size) {
                    Text(
                        if (loading) "加载中…" else "再看一些（${p.items.size} / ${p.total}）",
                        style = KcTheme.type.caption, color = GiftColors.rose, fontWeight = FontWeight.W600, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading) { limit += WallRecordsPage * 2 }.padding(10.dp),
                    )
                }
            }
        }
    }
}
