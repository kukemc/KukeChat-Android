package ink.kuke.chat.feature.bots

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ConfirmDialog
import ink.kuke.chat.feature.tasks.ChoiceDialog
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.BotRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class BotsTab { Square, Mine }

/** 本页里的几个子页面：列表、创建 / 编辑表单、接入文档。用状态切换，不另开路由 */
private sealed interface BotsPage {
    data object List : BotsPage
    data class Editor(val bot: Bot?) : BotsPage
    data object Docs : BotsPage
}

/**
 * 机器人广场（设计稿 P21）：概览统计条、搜索、广场 / 我的、列表行、接入文档入口。
 * 长按一行：查看详情 · 添加到群聊；自己的机器人还有 编辑资料 · 重置 Key · 删除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BotsScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val api = remember { BotApi(graph.api) }
    val me = graph.session.session.value?.user

    var page by remember { mutableStateOf<BotsPage>(BotsPage.List) }
    var tab by remember { mutableStateOf(BotsTab.Square) }
    var query by remember { mutableStateOf("") }
    // 上次的广场第一页和「我的」（内存）：先摆出来，再静默刷新；搜索结果不缓存
    val cache = remember(graph) { graph.screenCache.slot<Pair<BotSquarePage, List<Bot>>>("bots") }
    val cached = remember(cache) { cache.get() }
    var square by remember { mutableStateOf(cached?.first) }
    var squareItems by remember { mutableStateOf(cached?.first?.items ?: emptyList()) }
    var mine by remember { mutableStateOf(cached?.second ?: emptyList()) }
    var loading by remember { mutableStateOf(cached == null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var menuBot by remember { mutableStateOf<Bot?>(null) }
    var installBot by remember { mutableStateOf<Bot?>(null) }
    var deleteBot by remember { mutableStateOf<Bot?>(null) }
    var rotateBot by remember { mutableStateOf<Bot?>(null) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, tick) {
        // 搜索防抖：停手 300ms 才请求
        if (query.isNotEmpty()) delay(300)
        error = null
        runCatching {
            val first = api.square(query)
            square = first
            squareItems = first.items
            mine = runCatching { api.mine() }.getOrDefault(mine)
            if (query.isEmpty()) cache.put(first to mine)
        }.onFailure { error = it.userMessage() }
        loading = false
        refreshing = false
    }

    when (val current = page) {
        is BotsPage.Editor -> {
            BotEditorPage(
                graph = graph, bot = current.bot, onBack = { page = BotsPage.List },
                onSaved = { page = BotsPage.List; tick++ },
            )
            return
        }
        BotsPage.Docs -> {
            BotDocsPage(onBack = { page = BotsPage.List })
            return
        }
        BotsPage.List -> Unit
    }

    KcPage(
        title = "机器人", subtitle = "把别人做好的机器人加进你管理的群", onBack = { nav.back() },
        actions = {
            val press = rememberInteraction()
            Box(
                Modifier.size(44.dp).pressScale(press, 0.9f).clip(CircleShape).clickable(interactionSource = press, indication = null) { page = BotsPage.Editor(null) },
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.plus, tint = colors.text, size = 22.dp, contentDescription = "创建机器人") }
        },
    ) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.fillMaxSize()) {
            val rows = if (tab == BotsTab.Square) squareItems else mine.filter { query.isBlank() || it.name.contains(query.trim(), true) }
            when {
                loading -> CenterText("正在加载机器人…")
                error != null && squareItems.isEmpty() && mine.isEmpty() -> ErrorBlock(error!!, { loading = true; tick++ })
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item("search") {
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { PlainField(query, { query = it }, "搜索名称、功能或指令") }
                        }
                    }
                    item("tabs") {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.active).padding(3.dp),
                        ) {
                            listOf(BotsTab.Square to "广场", BotsTab.Mine to "我的 ${mine.size}").forEach { (item, label) ->
                                val on = tab == item
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) colors.panel else Color.Transparent)
                                        .clickable { tab = item }.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(label, style = type.secondary, color = if (on) colors.text else colors.muted, fontWeight = if (on) FontWeight.W700 else FontWeight.W500) }
                            }
                        }
                    }
                    item("stats") {
                        val stats = square?.stats ?: BotSquareStats()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(vertical = 12.dp)) {
                            listOf("广场机器人" to stats.totalBots, "正在在线" to stats.onlineBots, "已接入群聊" to stats.installedGroups, "累计安装" to stats.totalInstalls).forEach { (label, value) ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(countText(value), style = type.title, color = if (label == "正在在线") colors.ok else colors.text)
                                    Text(label, style = type.caption, color = colors.muted, maxLines = 1)
                                }
                            }
                        }
                    }
                    item("section") {
                        Text(if (tab == BotsTab.Square) "公开的机器人" else "我创建的", style = type.group, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp))
                    }
                    if (rows.isEmpty()) {
                        item("empty") {
                            Text(
                                if (tab == BotsTab.Mine) "你还没有创建机器人，点右上角「+」创建一个" else if (query.isNotBlank()) "没有找到「${query.trim()}」相关的机器人" else "广场里还没有机器人",
                                style = type.secondary, color = colors.muted, modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    itemsIndexed(rows, key = { _, it -> it.id }) { index, bot ->
                        val top = if (index == 0) 14.dp else 0.dp
                        val bottom = if (index == rows.lastIndex) 14.dp else 0.dp
                        Box(
                            Modifier.animateItem().padding(horizontal = 12.dp)
                                .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)).background(colors.panel),
                        ) {
                            BotRow(
                                bot, isMine = bot.ownerId == me?.id,
                                onClick = { nav.go(BotRoute(bot.id)) },
                                onLongClick = { Haptics.tick(view); menuBot = bot },
                            )
                        }
                    }
                    if (tab == BotsTab.Square && square?.hasMore == true) {
                        item("more") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                SoftButton(if (loadingMore) "正在加载…" else "加载更多", {
                                    if (loadingMore) return@SoftButton
                                    loadingMore = true
                                    scope.launch {
                                        runCatching { api.square(query, offset = squareItems.size) }
                                            .onSuccess { square = it; squareItems = squareItems + it.items }
                                            .onFailure { toast = it.userMessage() }
                                        loadingMore = false
                                    }
                                })
                            }
                        }
                    }
                    item("docs") {
                        Box(Modifier.padding(12.dp)) {
                            Card(Modifier.clickable { page = BotsPage.Docs }) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                                        KcIcon(KcIcons.code, tint = colors.accent, size = 22.dp)
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text("接入文档", style = type.rowTitle, color = colors.text)
                                        Text("Scratch 扩展 · Bot API（REST + WebSocket）", style = type.caption, color = colors.muted)
                                    }
                                    KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
                                }
                            }
                        }
                    }
                }
            }
            toast?.let { message ->
                LaunchedEffect(message) { delay(2400); toast = null }
                Text(
                    message, style = type.secondary, color = colors.panel,
                    modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).clip(RoundedCornerShape(22.dp)).background(colors.text.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }

    menuBot?.let { bot ->
        val owner = bot.ownerId == me?.id
        val options = buildList {
            add("view" to "查看详情")
            if (!bot.disabled) add("install" to "添加到群聊")
            if (owner) {
                add("edit" to "编辑资料")
                add("rotate" to "重置 Key")
                add("delete" to "删除")
            }
        }
        ChoiceDialog(bot.name, options, null, onPick = { key ->
            menuBot = null
            when (key) {
                "view" -> nav.go(BotRoute(bot.id))
                "install" -> installBot = bot
                "edit" -> page = BotsPage.Editor(bot)
                "rotate" -> rotateBot = bot
                "delete" -> deleteBot = bot
            }
        }, onDismiss = { menuBot = null })
    }
    installBot?.let { bot ->
        InstallToGroupDialog(graph, bot, onDone = { installBot = null; toast = it; tick++ }, onDismiss = { installBot = null })
    }
    rotateBot?.let { bot ->
        ConfirmDialog("重置 Key", "旧 Key 会立刻失效，正在用它的程序要换成新 Key 才能继续工作。确定重置「${bot.name}」的 Key 吗？", "重置", onConfirm = {
            rotateBot = null
            scope.launch {
                runCatching { api.rotateKey(bot.id) }.onSuccess { newKey = it }.onFailure { toast = it.userMessage() }
            }
        }, onDismiss = { rotateBot = null })
    }
    deleteBot?.let { bot ->
        ConfirmDialog("删除机器人", "删除「${bot.name}」后，它会从所有群里移除，且无法恢复。", "删除", onConfirm = {
            deleteBot = null
            scope.launch {
                runCatching { api.delete(bot.id) }
                    .onSuccess { Haptics.confirm(view); tick++ }
                    .onFailure { toast = it.userMessage(); Haptics.reject(view) }
            }
        }, onDismiss = { deleteBot = null })
    }
    newKey?.let { key -> KeyDialog(key, "新的 Key", onDismiss = { newKey = null }) }
}

/** 一行：头像 + 在线点 · 名称 + 分类 / 私有 / 已停用 · 简介 · 星级 + 安装群数 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BotRow(bot: Bot, isMine: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val press = rememberInteraction()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).pressScale(press, 0.98f)
            .combinedClickable(interactionSource = press, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BotAvatar(bot, 48.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(bot.name, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!bot.isPublic) Tag("私有", colors.warn)
                if (bot.disabled) Tag("已停用", colors.danger)
                if (isMine) Tag("我的", colors.ok)
            }
            Text(
                bot.description?.takeIf { it.isNotBlank() } ?: "这个机器人还没有写介绍",
                style = type.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val rating = bot.ratingAverage
                if (rating != null && rating > 0) {
                    Stars(rating, 12.dp)
                    Text(ratingText(rating), style = type.caption, color = colors.text)
                } else {
                    Text("暂无评分", style = type.caption, color = colors.subtle)
                }
                Text("· ${bot.installCount} 群", style = type.caption, color = colors.muted)
            }
        }
    }
}
