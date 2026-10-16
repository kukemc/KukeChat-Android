package ink.kuke.chat.feature.chat

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.messagePreview
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val resultTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun fullTime(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(resultTime).orEmpty()

private fun decodeMessage(element: JsonElement): Message? = runCatching { KcJson.decodeFromJsonElement(Message.serializer(), element) }.getOrNull()

/** 一条消息的结果行：头像 · 发送者 · 时间 · 摘要（keyword 命中的部分用强调色） */
@Composable
internal fun MessageResultRow(message: Message, keyword: String = "", onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Avatar(message.sender?.avatarUrl, message.senderName, tone = message.senderId ?: 0, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(message.senderName, style = type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(fullTime(message.createdAt), style = type.caption, color = colors.subtle)
            }
            val text = messagePreview(message)
            val hit = keyword.isNotBlank() && text.contains(keyword, ignoreCase = true)
            if (hit) {
                Text(
                    buildAnnotatedString {
                        var cursor = 0
                        while (cursor < text.length) {
                            val at = text.indexOf(keyword, cursor, ignoreCase = true)
                            if (at < 0) {
                                append(text.substring(cursor))
                                break
                            }
                            append(text.substring(cursor, at))
                            withStyle(SpanStyle(color = colors.accent)) { append(text.substring(at, at + keyword.length)) }
                            cursor = at + keyword.length
                        }
                    },
                    style = type.body,
                    color = colors.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(text, style = type.body, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val categories = listOf("all" to "全部", "text" to "文字", "image" to "图片", "sticker" to "表情", "voice" to "语音", "link" to "链接", "forward_bundle" to "聊天记录")

/**
 * 搜索聊天记录：GET /conversations/{id}/messages/search?q=&category=&before_id=&limit=。
 * 只搜这一个会话（隐私边界：不提供跨会话 / 按用户的消息浏览）。结果点开 → 跳到上下文。
 */
@Composable
internal fun ChatSearchPage(controller: ChatController, onBack: () -> Unit, onJump: (Message) -> Unit) {
    val colors = KcTheme.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("all") }
    var results by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var nextBefore by remember { mutableStateOf<Long?>(null) }
    var searched by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    suspend fun load(reset: Boolean) {
        val q = query.trim()
        if (q.isEmpty() && category == "all") {
            results = emptyList()
            searched = false
            return
        }
        loading = true
        error = null
        runCatching {
            val params = buildString {
                append("limit=20&category=$category")
                if (q.isNotEmpty()) append("&q=${URLEncoder.encode(q, "UTF-8")}")
                if (!reset) nextBefore?.let { append("&before_id=$it") }
            }
            controller.graph.api.get("/conversations/${controller.conversationId}/messages/search?$params")
        }.onSuccess { element ->
            val obj = element.asObj()
            val array = obj?.a("items") ?: element.asArr()
            val page = array?.mapNotNull { item ->
                val wrapped = item.asObj()
                wrapped?.get("message")?.let(::decodeMessage) ?: item.let(::decodeMessage)
            }.orEmpty()
            results = if (reset) page else results + page
            hasMore = obj?.b("has_more") ?: (page.size >= 20)
            nextBefore = obj?.l("next_before_id") ?: page.lastOrNull()?.id
            searched = true
        }.onFailure { error = it.userMessage() }
        loading = false
    }

    // 输入停 400ms 再搜
    LaunchedEffect(query, category) {
        delay(400)
        load(reset = true)
    }

    KcPage(title = "搜索聊天记录", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                KcTextField(query, { query = it }, "搜索关键词", Modifier.fillMaxWidth())
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { (key, label) -> Chip(label, category == key) { category = key } }
            }
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(colors.border))
            when {
                error != null -> StateText(error!!, error = true, onRetry = { scope.launch { load(true) } })
                loading && results.isEmpty() -> StateText("正在搜索…")
                searched && results.isEmpty() -> StateText("没有找到相关的聊天记录")
                !searched -> StateText("输入关键词，或者选一个类型来找")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { message -> MessageResultRow(message, query.trim()) { onJump(message) } }
                    if (hasMore) item {
                        Text(
                            if (loading) "加载中…" else "加载更多",
                            style = KcTheme.type.secondary,
                            color = colors.accent,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !loading) { scope.launch { load(false) } }.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 精华消息 / 本会话收藏：同一种「消息列表」，点一条跳到上下文 */
@Composable
internal fun MessageBrowsePage(title: String, emptyText: String, onBack: () -> Unit, onJump: (Message) -> Unit, load: suspend () -> List<Message>, graph: ink.kuke.chat.core.AppGraph? = null) {
    var items by remember { mutableStateOf<List<Message>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    BackHandler(onBack = onBack)
    LaunchedEffect(attempt) {
        error = null
        runCatching { load() }.onSuccess { items = it }.onFailure { error = it.userMessage() }
    }
    ReadOnlyMessageHost(graph) { actions ->
    KcPage(title = title, onBack = onBack) {
        val list = items
        when {
            error != null -> StateText(error!!, error = true, onRetry = { attempt++ })
            list == null -> StateText("加载中…")
            list.isEmpty() -> StateText(emptyText)
            // 和聊天页同一个渲染器：图片 / 表情 / 语音 / 投票 / 红包 / 名片 / 嵌套合并转发都能正常显示
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { message -> SnapshotMessageRow(message, actions, onClick = { onJump(message) }) }
            }
        }
    }
    }
}

/** 精华接口的一项可能是 {message, …} 也可能直接是消息 */
internal fun parseMessagesLoose(element: JsonElement): List<Message> =
    element.asArr().orEmpty().mapNotNull { item -> item.asObj()?.get("message")?.let(::decodeMessage) ?: decodeMessage(item) }
