package ink.kuke.chat.feature.search

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.chat.Chip
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.MemberLite
import ink.kuke.chat.feature.chat.Meta
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.parseMemberPage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.ChatSearchRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Categories = listOf("all" to "全部", "media" to "图片视频", "sticker" to "表情", "file" to "文件", "link" to "链接")
private val ShanghaiZone = ZoneId.of("Asia/Shanghai")

private sealed interface Row2 {
    data class Header(val label: String) : Row2
    data class Hit(val hit: SearchHit) : Row2
}

/**
 * 聊天记录搜索（QQ 式）：搜索框（防抖 250ms，过期请求自动取消）· 分类页签 · 筛选（发送日期 / 发送人）·
 * 结果按日期分组 · 命中处高亮 · 分页 · 点结果跳到该消息在会话里的位置（ChatRoute.focusMessageId）。
 * conversationId = 0 时不限定会话：先按会话聚合命中数，点进去再看详情。只搜自己的会话。
 */
@Composable
fun ChatSearchScreen(graph: AppGraph, conversationId: Long, initialQuery: String = "") {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val api = remember(graph) { ChatSearchApi(graph) }
    val focus = remember { FocusRequester() }
    val conversation = graph.conversations.find(conversationId)
    val me = graph.session.session.value?.user

    var input by remember { mutableStateOf(initialQuery) }
    var category by remember { mutableStateOf("all") }
    var senderId by remember { mutableStateOf<Long?>(null) }
    var senderName by remember { mutableStateOf<String?>(null) }
    var dateFrom by remember { mutableStateOf<String?>(null) }
    var dateTo by remember { mutableStateOf<String?>(null) }
    var datePreset by remember { mutableStateOf("any") }
    var showFilter by remember { mutableStateOf(false) }
    var pickSender by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var capped by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var nextBefore by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<SearchSummary?>(null) }

    val global = conversationId == 0L
    val filter = SearchFilter(input, category, senderId, dateFrom, dateTo)

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // 条件变了：防抖 250ms 再查；LaunchedEffect 重启会取消上一次还在飞的请求（过期结果不会落进来）
    LaunchedEffect(input, category, senderId, dateFrom, dateTo, tick) {
        if (global && input.isBlank()) {
            summary = null
            hits = emptyList()
            searched = false
            error = null
            return@LaunchedEffect
        }
        if (!global && filter.isEmpty) {
            hits = emptyList()
            searched = false
            error = null
            return@LaunchedEffect
        }
        delay(250)
        loading = true
        error = null
        if (global) {
            runCatching { api.summary(input) }
                .onSuccess { summary = it; searched = true }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.userMessage() }
        } else {
            runCatching { api.search(filter, conversationId) }
                .onSuccess { page ->
                    hits = page.hits
                    total = page.total
                    capped = page.totalCapped
                    hasMore = page.hasMore
                    nextBefore = page.nextBeforeId
                    searched = true
                }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.userMessage() }
        }
        loading = false
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore || global) return
        val before = nextBefore ?: return
        val snapshot = filter
        loadingMore = true
        scope.launch {
            runCatching { api.search(snapshot, conversationId, beforeId = before) }
                .onSuccess { page ->
                    // 加载期间条件变了：丢掉
                    if (snapshot == SearchFilter(input, category, senderId, dateFrom, dateTo)) {
                        hits = hits + page.hits.filter { n -> hits.none { it.message.id == n.message.id } }
                        hasMore = page.hasMore
                        nextBefore = page.nextBeforeId
                    }
                }
                .onFailure { error = it.userMessage() }
            loadingMore = false
        }
    }

    Column(Modifier.fillMaxSize().background(colors.chat)) {
        // 顶栏：返回 · 搜索框 · 筛选
        Column(Modifier.fillMaxWidth().background(colors.panel).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { nav.back() }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.chevronLeft, tint = colors.text, size = 24.dp, contentDescription = "返回")
                }
                Row(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(colors.panelMuted).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.search, tint = colors.subtle, size = 18.dp)
                    Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) Text(if (global) "搜索聊天记录" else "搜索 ${conversation?.name() ?: "聊天"} 的聊天记录", style = type.body, color = colors.subtle, maxLines = 1)
                        BasicTextField(
                            value = input,
                            onValueChange = { if (it.length <= 100) input = it },
                            singleLine = true,
                            textStyle = type.body.copy(color = colors.text),
                            cursorBrush = SolidColor(colors.fill),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (input.isNotEmpty()) {
                        Box(Modifier.size(28.dp).clip(CircleShape).clickable { input = "" }, contentAlignment = Alignment.Center) {
                            KcIcon(KcIcons.close, tint = colors.subtle, size = 16.dp, contentDescription = "清空")
                        }
                    }
                }
                if (!global) {
                    val active = senderId != null || dateFrom != null
                    Row(
                        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(16.dp)).clickable { showFilter = !showFilter }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KcIcon(KcIcons.filter, tint = if (active || showFilter) colors.accent else colors.text, size = 18.dp)
                        Text("筛选", style = type.secondary, color = if (active || showFilter) colors.accent else colors.text, modifier = Modifier.padding(start = 3.dp))
                    }
                }
            }
            if (!global) {
                // 分类页签
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Categories.forEach { (key, label) -> Chip(label, category == key) { category = key } }
                }
            }
            if (showFilter && !global) {
                FilterPanel(
                    senderName = senderName,
                    datePreset = datePreset,
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    onPreset = { preset ->
                        datePreset = preset
                        val today = LocalDate.now(ShanghaiZone)
                        when (preset) {
                            "today" -> { dateFrom = today.toString(); dateTo = today.toString() }
                            "week" -> { dateFrom = today.minusDays(6).toString(); dateTo = today.toString() }
                            "month" -> { dateFrom = today.minusDays(29).toString(); dateTo = today.toString() }
                            "any" -> { dateFrom = null; dateTo = null }
                        }
                    },
                    onPickDate = { isFrom ->
                        val base = (if (isFrom) dateFrom else dateTo)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(ShanghaiZone)
                        DatePickerDialog(context, { _, y, m, d ->
                            val picked = LocalDate.of(y, m + 1, d).format(DateTimeFormatter.ISO_LOCAL_DATE)
                            datePreset = "custom"
                            if (isFrom) dateFrom = picked else dateTo = picked
                        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                    },
                    onPickSender = { pickSender = true },
                    onClearSender = { senderId = null; senderName = null },
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> StateText(error!!, Modifier.padding(top = 80.dp), error = true, onRetry = { tick++ })
                global -> GlobalSummary(summary, loading, searched, input) { cid -> nav.go(ChatSearchRoute(cid, input.trim())) }
                !searched && loading -> StateText("正在搜索…", Modifier.padding(top = 80.dp))
                !searched -> Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    KcIcon(KcIcons.search, tint = colors.subtle, size = 32.dp)
                    Text("搜索这个会话里的聊天记录", style = type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp))
                    Text("输入关键词，或用「图片视频 / 文件 / 链接」和筛选来找", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                }
                hits.isEmpty() -> StateText("没有找到相关的聊天记录", Modifier.padding(top = 80.dp))
                else -> {
                    val rows = remember(hits) { groupByDate(hits) }
                    val listState = rememberLazyListState()
                    LaunchedEffect(listState, rows.size, hasMore) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                            if (last >= rows.size - 4) loadMore()
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        item(key = "total") {
                            Text(
                                "共 ${if (capped) "${total}+" else total.toString()} 条结果",
                                style = type.caption, color = colors.subtle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(rows, key = { row -> if (row is Row2.Header) "h-${row.label}" else "m-${(row as Row2.Hit).hit.message.id}" }) { row ->
                            when (row) {
                                is Row2.Header -> Text(row.label, style = type.group, color = colors.muted, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                                is Row2.Hit -> HitRow(row.hit, onClick = {
                                    keyboard?.hide()
                                    nav.go(ChatRoute(row.hit.message.conversationId.takeIf { it > 0 } ?: conversationId, row.hit.message.id))
                                })
                            }
                        }
                        item(key = "more") {
                            Text(
                                when {
                                    loadingMore -> "加载中…"
                                    hasMore -> "上滑加载更多"
                                    else -> "没有更多了"
                                },
                                style = type.caption, color = colors.subtle, modifier = Modifier.fillMaxWidth().padding(16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    if (pickSender) {
        SenderPickSheet(
            graph = graph,
            conversationId = conversationId,
            isGroup = conversation?.isGroup == true,
            myId = me?.id,
            myName = me?.displayName,
            otherId = conversation?.directUser?.id,
            otherName = conversation?.directUser?.displayName,
            onDismiss = { pickSender = false },
            onPick = { id, name ->
                senderId = id
                senderName = name
                pickSender = false
            },
        )
    }
}

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun groupByDate(hits: List<SearchHit>): List<Row2> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val rows = mutableListOf<Row2>()
    var current: LocalDate? = null
    hits.forEach { hit ->
        val date = parseInstant(hit.message.createdAt)?.atZone(zone)?.toLocalDate()
        if (date != null && date != current) {
            current = date
            rows += Row2.Header(
                when {
                    date == today -> "今天"
                    date == today.minusDays(1) -> "昨天"
                    date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日 ${weekdays[date.dayOfWeek.value - 1]}"
                    else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
                },
            )
        }
        rows += Row2.Hit(hit)
    }
    return rows
}

// ------------------------------------------------------------------ 结果行

@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val message = hit.message
    val time = parseInstant(message.createdAt)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
    Row(
        Modifier.fillMaxWidth().background(colors.panel).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(message.sender?.avatarUrl, message.senderName, tone = message.senderId ?: 0, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.senderName, style = type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(time, style = type.caption, color = colors.subtle)
            }
            if (message.type == "image" && (hit.category == "media" || hit.category == "sticker")) {
                AsyncImage(
                    model = Meta.imageUrl(message.content),
                    contentDescription = if (hit.category == "sticker") "表情" else "图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(highlighted(hit.snippet, hit.snippetHighlights), style = type.body, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(1.dp).background(colors.border))
}

@Composable
private fun highlighted(text: String, ranges: List<IntRange>) = buildAnnotatedString {
    val color = KcTheme.colors.accent
    var cursor = 0
    ranges.sortedBy { it.first }.forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (start < cursor) return@forEach
        append(text.substring(cursor, start))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.W700)) { append(text.substring(start, end)) }
        cursor = end
    }
    append(text.substring(cursor))
}

// ------------------------------------------------------------------ 筛选

@Composable
private fun FilterPanel(
    senderName: String?,
    datePreset: String,
    dateFrom: String?,
    dateTo: String?,
    onPreset: (String) -> Unit,
    onPickDate: (isFrom: Boolean) -> Unit,
    onPickSender: () -> Unit,
    onClearSender: () -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("发送日期", style = type.caption, color = colors.muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("any" to "不限", "today" to "今天", "week" to "近 7 天", "month" to "近 30 天").forEach { (key, label) -> Chip(label, datePreset == key) { onPreset(key) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(dateFrom?.let { "从 $it" } ?: "开始日期", datePreset == "custom" && dateFrom != null) { onPickDate(true) }
            Chip(dateTo?.let { "到 $it" } ?: "结束日期", datePreset == "custom" && dateTo != null) { onPickDate(false) }
        }
        Text("发送人", style = type.caption, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(senderName ?: "选择发送人", senderName != null) { onPickSender() }
            if (senderName != null) Text("清除", style = type.secondary, color = colors.accent, modifier = Modifier.clickable(onClick = onClearSender).padding(6.dp))
        }
    }
}

/** 发送人选择：群聊拉成员（可搜），私聊只有「我」和「对方」 */
@Composable
private fun SenderPickSheet(
    graph: AppGraph,
    conversationId: Long,
    isGroup: Boolean,
    myId: Long?,
    myName: String?,
    otherId: Long?,
    otherName: String?,
    onDismiss: () -> Unit,
    onPick: (Long, String) -> Unit,
) {
    val colors = KcTheme.colors
    var query by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<MemberLite>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(query, attempt, isGroup) {
        if (!isGroup) return@LaunchedEffect
        delay(if (query.isEmpty()) 0 else 300)
        error = null
        runCatching {
            if (query.isBlank()) parseMemberPage(graph.api.get("/conversations/$conversationId/members?limit=50&offset=0")).items
            else parseMemberPage(graph.api.get("/conversations/$conversationId/members/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=30")).items
        }.onSuccess { members = it }.onFailure { error = it.userMessage() }
    }
    KcSheet(onDismiss = onDismiss, title = "选择发送人", maxHeight = 560.dp) {
        if (!isGroup) {
            listOfNotNull(myId?.let { it to "我（${myName.orEmpty()}）" }, otherId?.let { it to (otherName ?: "对方") }).forEach { (id, name) ->
                Text(name, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.fillMaxWidth().clickable { onPick(id, name.removePrefix("我（").removeSuffix("）").ifBlank { name }) }.padding(horizontal = 20.dp, vertical = 14.dp))
            }
            Box(Modifier.height(12.dp))
        } else {
            Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                ink.kuke.chat.feature.chat.KcTextField(query, { query = it }, "搜索成员", Modifier.fillMaxWidth())
            }
            val list = members
            when {
                error != null -> StateText(error!!, error = true, onRetry = { attempt++ })
                list == null -> StateText("加载中…")
                list.isEmpty() -> StateText("没有找到成员")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.userId }) { member ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(member.userId, member.name) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(member.avatarUrl, member.name, tone = member.userId, size = 38.dp)
                            Text(member.name, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 全局：按会话聚合

@Composable
private fun GlobalSummary(summary: SearchSummary?, loading: Boolean, searched: Boolean, query: String, onOpen: (Long) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    when {
        query.isBlank() -> StateText("输入关键词，在你所有的会话里找聊天记录", Modifier.padding(top = 80.dp))
        loading && summary == null -> StateText("正在搜索…", Modifier.padding(top = 80.dp))
        summary == null || summary.items.isEmpty() -> if (searched) StateText("没有找到相关的聊天记录", Modifier.padding(top = 80.dp))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item(key = "total") { Text("共 ${summary.total} 条结果，来自 ${summary.items.size} 个会话", style = type.caption, color = colors.subtle, modifier = Modifier.padding(16.dp)) }
            items(summary.items, key = { it.conversation.id }) { entry ->
                ConversationHitRow(entry, query) { onOpen(entry.conversation.id) }
            }
        }
    }
}

/** 一个会话的命中汇总行：头像（带群档位角标）· 名字 + 档位标签 · 「N 条相关聊天记录」 */
@Composable
fun ConversationHitRow(entry: ConversationHits, query: String, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val conversation = entry.conversation
    Row(
        Modifier.fillMaxWidth().background(colors.panel).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conversation.avatarUrl, conversation.displayTitle, tone = conversation.directUser?.id ?: conversation.id, size = 44.dp, plusTier = if (conversation.isGroup) conversation.plusTier else null)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.displayTitle, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (conversation.isGroup) ink.kuke.chat.feature.groupplus.PlusTierTag(conversation.plusTier, Modifier.padding(start = 6.dp))
            }
            Text("${entry.count} 条相关聊天记录", style = type.caption, color = colors.muted)
        }
        KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
    }
    Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(1.dp).background(colors.border))
}
