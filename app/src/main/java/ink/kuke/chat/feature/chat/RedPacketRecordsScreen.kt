package ink.kuke.chat.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private const val PAGE_SIZE = 20

/** 记录里的一行：收到的是我领到的一笔（amount = 我领到的），发出的是整个红包（amount = 总额） */
private data class RedPacketRecord(val key: String, val packet: RedPacketState, val amount: Int, val createdAt: String?)

private data class RecordPage(val items: List<RedPacketRecord>, val total: Int, val sum: Int)

private fun parseRecordPage(obj: JsonObject?, sent: Boolean, offset: Int): RecordPage {
    val items = obj?.a("items").orEmpty().mapIndexedNotNull { index, element ->
        val item = element.asObj() ?: return@mapIndexedNotNull null
        val raw = if (sent) item else item.o("red_packet") ?: return@mapIndexedNotNull null
        val packet = parseRedPacket(raw) ?: return@mapIndexedNotNull null
        RedPacketRecord(
            key = "${if (sent) "s" else "r"}-${packet.id}-${offset + index}",
            packet = packet,
            amount = if (sent) packet.total ?: 0 else item.i("amount") ?: 0,
            createdAt = if (sent) packet.createdAt else item.s("created_at"),
        )
    }
    return RecordPage(items, obj?.i("total") ?: items.size, obj?.i("sum") ?: 0)
}

/**
 * 红包记录（GET /red-packets/mine）：收到的 / 发出的两个页签，顶上是这个方向的合计，往下滑自动加载更多。
 * 点一行直接打开那个红包的领取详情（同聊天里的弹层，initialDetail）。
 */
@Composable
fun RedPacketRecordsScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    var tab by remember { mutableIntStateOf(0) }
    val sent = tab == 1
    val items = remember { mutableStateListOf<RedPacketRecord>() }
    var total by remember { mutableIntStateOf(0) }
    var sum by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<RedPacketRecord?>(null) }
    val cache = remember { mutableStateMapOf<Long, RedPacketState>() }
    val source = remember { RedPacketSource(graph, cache) }
    val listState = rememberLazyListState()

    suspend fun fetch(offset: Int) {
        val forTab = tab
        val direction = if (sent) "sent" else "received"
        val page = parseRecordPage(graph.api.get("/red-packets/mine?direction=$direction&limit=$PAGE_SIZE&offset=$offset").asObj(), sent, offset)
        // 等待期间切了页签：这一页作废
        if (forTab != tab) return
        if (offset == 0) items.clear()
        items.addAll(page.items)
        total = page.total
        sum = page.sum
    }

    LaunchedEffect(tab, attempt) {
        loading = true
        error = null
        sum = null
        items.clear()
        listState.scrollToItem(0)
        runCatching { fetch(0) }.onFailure { if (it is CancellationException) throw it; error = it.userMessage() }
        loading = false
    }
    // 滑到底自动翻页
    LaunchedEffect(listState, tab) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!loading && error == null && items.isNotEmpty() && items.size < total && last >= items.size - 3) {
                loading = true
                scope.launch {
                    runCatching { fetch(items.size) }.onFailure { if (it is CancellationException) throw it; error = it.userMessage() }
                    loading = false
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        KcPage(title = "红包记录", onBack = { nav.back() }) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "head") {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Segmented(listOf("收到的", "发出的"), tab, { tab = it })
                        SummaryCard(sent, sum, total)
                    }
                }
                when {
                    items.isEmpty() && loading -> item(key = "state") { StateText("加载中…") }
                    items.isEmpty() && error != null -> item(key = "state") { StateText("红包记录没有加载出来：$error", error = true, onRetry = { attempt++ }) }
                    items.isEmpty() -> item(key = "state") {
                        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            KcIcon(KcIcons.redPacket, tint = colors.subtle, size = 40.dp)
                            Text(if (sent) "还没有发过红包" else "还没有收到过红包", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    else -> {
                        item(key = "list-top") { Box(Modifier.height(4.dp)) }
                        items(items, key = { it.key }) { record ->
                            RecordRow(record, sent) {
                                if (!cache.containsKey(record.packet.id)) cache[record.packet.id] = record.packet
                                opened = record
                            }
                        }
                        item(key = "foot") {
                            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                when {
                                    loading -> Text("加载中…", style = KcTheme.type.caption, color = colors.subtle)
                                    error != null -> Text("加载失败，点这里重试", style = KcTheme.type.caption, color = colors.danger, modifier = Modifier.clickable {
                                        error = null
                                        scope.launch {
                                            loading = true
                                            runCatching { fetch(items.size) }.onFailure { if (it is CancellationException) throw it; error = it.userMessage() }
                                            loading = false
                                        }
                                    })
                                    items.size >= total -> Text("共 $total 条", style = KcTheme.type.caption, color = colors.subtle)
                                }
                            }
                        }
                    }
                }
            }
        }
        opened?.let { record ->
            val packet = record.packet
            // 会话在手头就看它是不是群；不在（已退群之类）按群算，只影响「私聊里自己发的」那一条
            val isGroup = graph.conversations.find(packet.conversationId ?: 0)?.isGroup ?: true
            RedPacketDialog(
                source = source,
                meta = RedPacketMeta(packet.id, packet.kind, packet.greeting, packet.count),
                isGroup = isGroup,
                myId = me?.id,
                initialDetail = true,
                onDismiss = { opened = null },
            )
        }
    }
}

/** 顶部合计：红底金字，右上一枚装饰金币 */
@Composable
private fun SummaryCard(sent: Boolean, sum: Int?, count: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(RpColors.FlapTop, RpColors.RedDeep), start = Offset.Zero, end = Offset(900f, 600f))),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.height * 0.9f, center = Offset(size.width * 0.95f, -size.height * 0.15f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.height * 0.55f, center = Offset(size.width * 0.05f, size.height * 1.1f))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(if (sent) "共发出" else "共收到", style = KcTheme.type.secondary, color = Color.White.copy(alpha = 0.85f))
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CoinGlyph(24.dp)
                Text(sum?.let { coins(it) } ?: "—", fontSize = 34.sp, fontWeight = FontWeight.W700, color = RpColors.GoldLight, modifier = Modifier.padding(start = 8.dp))
                Text("币", style = KcTheme.type.secondary, color = RpColors.Gold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            Text(if (sum != null) "$count 个红包" else " ", style = KcTheme.type.caption, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 一条记录：红包图标 · 祝福语 / 来源与时间（发出的带退回）· 右侧金额与状态 */
@Composable
private fun RecordRow(record: RedPacketRecord, sent: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val packet = record.packet
    val press = rememberInteraction()
    val kind = if (packet.lucky) "拼手气" else "普通"
    val note = if (sent) "${kind}红包 · 已领 ${packet.claimedCount.coerceAtMost(packet.count)}/${packet.count}" else "${packet.senderName} 的${kind}红包"
    val time = rpTime(record.createdAt)
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(press, 0.98f)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(RpColors.FlapTop, RpColors.RedDeep))),
            contentAlignment = Alignment.Center,
        ) { KcIcon(KcIcons.redPacket, tint = RpColors.Gold, size = 22.dp) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(packet.greeting, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note + if (time.isNotEmpty()) " · $time" else "", style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val refunded = packet.refundedAmount ?: 0
            if (sent && refunded > 0) Text("没领完，退回 ${coins(refunded)} 币", style = type.caption, color = colors.ok)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (sent) "−" else "+") + coins(record.amount),
                style = type.rowTitle,
                fontWeight = FontWeight.W600,
                color = if (sent) colors.text else RpColors.amount(),
            )
            if (sent) {
                val status = packet.endedStatus ?: "active"
                val live = status == "active"
                Text(
                    redPacketStatusText(status),
                    fontSize = 11.sp,
                    color = if (live) RpColors.Red else colors.muted,
                    modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(6.dp)).background((if (live) RpColors.Red else colors.muted).copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        KcIcon(KcIcons.chevron, tint = colors.subtle, size = 14.dp, modifier = Modifier.padding(start = 6.dp))
    }
}
