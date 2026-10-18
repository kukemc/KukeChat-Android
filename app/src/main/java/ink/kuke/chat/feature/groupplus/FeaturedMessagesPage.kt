package ink.kuke.chat.feature.groupplus

import ink.kuke.chat.core.data.slot

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.ReadOnlyMessageHost
import ink.kuke.chat.feature.chat.SnapshotMessageRow
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.fullTime
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private const val PAGE = 30

/**
 * 群精华：精华是快照，原消息被修剪 / 撤回也还在。
 * 顶部显示「已用 / 上限」；用量达到上限提示去升级；权益过期后可看、可取消，不能新增。
 * 点一条：原消息还在 → 跳到它在聊天里的位置；已被修剪 → 提示这是快照。分页靠响应头 X-Total-Count / X-Has-More。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturedMessagesPage(
    graph: AppGraph,
    conversationId: Long,
    staff: Boolean,
    onBack: () -> Unit,
    onJump: (Long) -> Unit,
    onUpgrade: () -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { GroupPlusApi(graph) }
    // 上次看到的第一页（内存）：先摆出来，再静默刷新；翻页照旧
    val cache = remember(graph, conversationId) { graph.screenCache.slot<FeaturedSnapshot>("featured.$conversationId") }
    val cached = remember(cache) { cache.get() }
    var items by remember { mutableStateOf(cached?.items ?: emptyList()) }
    var total by remember { mutableStateOf(cached?.total ?: 0) }
    var hasMore by remember { mutableStateOf(cached?.hasMore ?: false) }
    var info by remember { mutableStateOf(cached?.info) }
    var loading by remember { mutableStateOf(cached == null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<FeaturedItem?>(null) }
    var tick by remember { mutableStateOf(0) }
    BackHandler(onBack = onBack)

    /** silent：已有内容时不显示下拉转圈（进页面、实时事件触发的刷新） */
    suspend fun load(reset: Boolean, silent: Boolean = false) {
        if (reset) {
            if (items.isEmpty()) loading = true else if (!silent) refreshing = true
        } else {
            loadingMore = true
        }
        runCatching { api.featured(conversationId, PAGE, if (reset) 0 else items.size) }
            .onSuccess { page ->
                items = if (reset) page.items else items + page.items.filter { n -> items.none { it.id == n.id } }
                total = page.total
                hasMore = page.hasMore
                error = null
            }
            .onFailure { if (items.isEmpty()) error = it.userMessage() else toast(context, it.userMessage()) }
        if (reset) {
            runCatching { api.info(conversationId) }.onSuccess { info = it }
            if (error == null) cache.put(FeaturedSnapshot(items.take(PAGE), total, hasMore || items.size > PAGE, info))
        }
        loading = false
        refreshing = false
        loadingMore = false
    }

    LaunchedEffect(conversationId, tick) { load(true, silent = true) }
    LaunchedEffect(conversationId) {
        var pending: Job? = null
        graph.realtime.events.collect { event ->
            if (event.type == "message.featured.updated" || event.type == "group.plus.updated") {
                val id = (event.data["conversation_id"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (id == null || id == conversationId) {
                    pending?.cancel()
                    pending = scope.launch {
                        delay(300)
                        load(true, silent = true)
                    }
                }
            }
        }
    }

    ReadOnlyMessageHost(graph) { actions ->
        KcPage(title = "群精华", onBack = onBack, subtitle = if (total > 0) "共 $total 条" else null) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { load(true) } }, modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, items.size, hasMore) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                        if (!loading && !loadingMore && hasMore && last >= items.size - 2) load(false)
                    }
                }
                when {
                    loading && items.isEmpty() -> StateText("正在加载…", Modifier.fillMaxWidth().padding(top = 80.dp))
                    error != null && items.isEmpty() -> StateText(error!!, Modifier.fillMaxWidth().padding(top = 80.dp), error = true, onRetry = { tick++ })
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "quota") { FeaturedQuota(info, staff, onUpgrade) }
                        if (items.isEmpty()) {
                            item(key = "empty") {
                                Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    KcIcon(KcIcons.star, tint = colors.subtle, size = 34.dp)
                                    Text("还没有精华消息", style = type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp))
                                    Text(if (staff) "长按消息，选择「设为精华」。" else "群主和管理员可以把重要的消息设为精华。", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        } else {
                            items(items, key = { it.id }) { item ->
                                FeaturedRow(
                                    item = item,
                                    actions = actions,
                                    staff = staff,
                                    onClick = {
                                        val target = item.messageId
                                        if (item.messageExists && target != null) onJump(target)
                                        else toast(context, "原消息已不在聊天记录里，这里保存的是精华快照")
                                    },
                                    onRemove = { confirmRemove = item },
                                )
                                Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(colors.border))
                            }
                            if (loadingMore) item(key = "more") { Text("加载中…", style = type.caption, color = colors.subtle, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { target ->
        KcConfirm(
            title = "取消精华",
            text = "取消后这条消息不再出现在群精华里。",
            confirmLabel = "取消精华",
            danger = true,
            onConfirm = {
                confirmRemove = null
                scope.launch {
                    runCatching { api.removeFeatured(conversationId, target.id) }
                        .onSuccess {
                            items = items.filter { it.id != target.id }
                            total = (total - 1).coerceAtLeast(0)
                            load(true)
                        }
                        .onFailure { toast(context, it.userMessage()) }
                }
            },
            onDismiss = { confirmRemove = null },
        )
    }
}

/** 顶部：已用 / 上限 + 状态提示（达上限引导升级；权益过期后的只读说明） */
@Composable
private fun FeaturedQuota(info: PlusInfo?, staff: Boolean, onUpgrade: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    info ?: return
    val used = info.usage.featuredCount
    val limit = info.limits.featuredLimit
    val over = used > limit
    val full = used >= limit
    Column(
        Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("精华额度", style = type.rowTitle, color = colors.text)
            PlusTierTag(PlusTiers.effective(info.tier, info.expiresAt), Modifier.padding(start = 8.dp))
            Box(Modifier.weight(1f))
            Text("$used / $limit", style = type.rowTitle, color = if (full) colors.warn else colors.text)
        }
        val ratio = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.panelMuted)) {
            Box(Modifier.fillMaxWidth(ratio).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (full) colors.warn else colors.fill))
        }
        when {
            over -> Text("群升级已到期，已有的 $used 条精华都会保留，但超出当前上限 $limit 条后不能再添加新的精华。", style = type.caption, color = colors.warn)
            full -> Text("精华已达上限，再添加需要先取消旧的或升级群。", style = type.caption, color = colors.warn)
            else -> Text("精华消息永久保存，原消息被清理后依然可以查看。", style = type.caption, color = colors.muted)
        }
        if (full && staff) Text("去升级提高上限", style = type.secondary, color = colors.accent, modifier = Modifier.clickable(onClick = onUpgrade).padding(vertical = 2.dp))
    }
}

@Composable
private fun FeaturedRow(item: FeaturedItem, actions: ink.kuke.chat.feature.chat.ReadOnlyActions, staff: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val footer = buildString {
        append("设为精华 ").append(fullTime(item.createdAt))
        item.setByName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (!item.messageExists) append(" · 原消息已清理")
    }
    // 撤回的精华：内容已被服务端清掉，按「已撤回」画；快照缺失的只留空壳
    // 原消息已被修剪的快照 id 是 0：换成精华 id 的负数，几条语音不会共用一个播放键（点一条、别的也显示在放）
    val message = when {
        item.recalled -> item.message.copy(recalledAt = item.message.recalledAt ?: "recalled")
        else -> item.message
    }.let { if (it.id <= 0L) it.copy(id = -item.id) else it }
    if (item.snapshotMissing && message.content.isBlank() && message.metadata == null) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("这条精华的原消息已被清理，内容无法恢复", style = type.secondary, color = colors.muted)
                Text(footer, style = type.caption, color = colors.subtle)
            }
            if (staff) RemoveButton(onRemove)
        }
    } else {
        SnapshotMessageRow(message, actions, footer = footer, onClick = onClick, trailing = if (staff) ({ RemoveButton(onRemove) }) else null)
    }
}

@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) { KcIcon(KcIcons.close, tint = KcTheme.colors.subtle, size = 18.dp, contentDescription = "取消精华") }
}

/** 精华消息页第一页的快照（只在内存，退出登录清掉） */
private class FeaturedSnapshot(val items: List<FeaturedItem>, val total: Int, val hasMore: Boolean, val info: PlusInfo?)
