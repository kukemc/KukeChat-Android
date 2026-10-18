package ink.kuke.chat.feature.groupannouncement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.groupplus.plusInk
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme

private const val PAGE_SIZE = 50

/**
 * 确认统计（仅群主 / 管理员）：已确认 / 未确认两个页签，顶部是人数，下面是名单（50 人一页，滑到底自动加载）。
 * 只统计现任成员；已确认按确认时间从新到旧，未确认按入群时间（服务端排好）。有人确认时人数实时跟着变。
 */
@Composable
fun AnnouncementConfirmsScreen(graph: AppGraph, conversationId: Long, announcementId: Long) {
    val nav = LocalAppNav.current
    val k = plusInk()
    val api = remember(graph) { GroupAnnouncementApi(graph) }
    var tab by remember { mutableStateOf("confirmed") }
    val items = remember { mutableStateListOf<ConfirmationItem>() }
    val cached = AnnouncementHub.cached(announcementId)
    var confirmed by remember { mutableIntStateOf(cached?.confirmedCount ?: 0) }
    var members by remember { mutableIntStateOf(cached?.memberCount ?: 0) }
    var counted by remember { mutableStateOf(cached != null) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    suspend fun load(reset: Boolean) {
        loading = true
        error = null
        val requested = tab
        val offset = if (reset) 0 else items.size
        runCatching { api.confirmations(conversationId, announcementId, requested, offset, PAGE_SIZE) }
            .onSuccess { page ->
                if (requested != tab) return@onSuccess
                if (reset) items.clear()
                items.addAll(page.items.filter { incoming -> incoming.user == null || items.none { it.user?.id == incoming.user.id } })
                hasMore = page.hasMore
                confirmed = page.confirmedCount
                members = page.memberCount
                counted = true
            }
            .onFailure { error = it.userMessage() }
        loading = false
    }

    LaunchedEffect(tab, attempt) {
        listState.scrollToItem(0)
        load(reset = true)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (items.isNotEmpty() && last >= items.size - 5 && hasMore && !loading) load(reset = false)
        }
    }
    // 有人确认：人数跟着变（名单不重排，免得正在看的位置跳走；切页签会重拉）
    LaunchedEffect(announcementId) {
        AnnouncementHub.changes.collect { (cid, aid) ->
            if (cid != conversationId || aid != announcementId) return@collect
            if (AnnouncementHub.isDeleted(announcementId)) {
                nav.back()
                return@collect
            }
            AnnouncementHub.cached(announcementId)?.let {
                confirmed = it.confirmedCount
                members = it.memberCount
            }
        }
    }

    val unconfirmed = (members - confirmed).coerceAtLeast(0)
    KcPage(title = "确认情况", onBack = nav::back) {
        Column(Modifier.fillMaxSize().background(KcTheme.colors.panel)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                CountTab("已确认", if (counted) confirmed.toString() else "–", tab == "confirmed", Modifier.weight(1f)) { if (tab != "confirmed") { items.clear(); tab = "confirmed" } }
                CountTab("未确认", if (counted) unconfirmed.toString() else "–", tab == "unconfirmed", Modifier.weight(1f)) { if (tab != "unconfirmed") { items.clear(); tab = "unconfirmed" } }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null && items.isEmpty() -> StateText(error!!, error = true, onRetry = { attempt++ })
                    loading && items.isEmpty() -> StateText("加载中…")
                    items.isEmpty() -> StateText(if (tab == "confirmed") "还没有人确认收到" else "所有成员都已确认")
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(items, key = { it.user?.id ?: it.hashCode().toLong() }) { item ->
                            ConfirmRow(item, showTime = tab == "confirmed") { item.user?.id?.let { nav.go(UserRoute(it)) } }
                        }
                        if (loading) item { Text("加载中…", color = k.faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                        item { Box(Modifier.navigationBarsPadding()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountTab(label: String, count: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val k = plusInk()
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(count, color = if (selected) k.ink else k.faint, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.W600)
        Text(label, color = if (selected) k.ink else k.sub, fontSize = 13.sp, fontWeight = if (selected) FontWeight.W600 else FontWeight.W400, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(top = 8.dp).width(20.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) k.ink else androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable
private fun ConfirmRow(item: ConfirmationItem, showTime: Boolean, onClick: () -> Unit) {
    val k = plusInk()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(item.user?.avatarUrl, item.name, tone = item.user?.id ?: 0, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, color = k.ink, fontSize = 15.sp, fontWeight = FontWeight.W500, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when (item.role) {
                    "owner" -> AnnTag("群主", Modifier.padding(start = 6.dp), strong = true)
                    "admin" -> AnnTag("管理员", Modifier.padding(start = 6.dp))
                }
            }
            val sub = item.user?.username?.takeIf { it.isNotBlank() && it != item.name }
            if (sub != null) Text("@$sub", color = k.faint, fontSize = 12.sp, maxLines = 1)
        }
        if (showTime && item.confirmedAt != null) Text(announcementTime(item.confirmedAt), color = k.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
