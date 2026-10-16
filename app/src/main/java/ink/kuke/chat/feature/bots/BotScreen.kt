package ink.kuke.chat.feature.bots

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ChoiceDialog
import ink.kuke.chat.feature.tasks.ConfirmDialog
import ink.kuke.chat.feature.tasks.DialogCard
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SectionLabel
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import kotlinx.serialization.json.put
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DetailTab(val label: String) { About("介绍"), Commands("指令"), Reviews("评价"), Info("信息") }

private sealed interface BotPageState {
    data object Detail : BotPageState
    data object Dashboard : BotPageState
    data object Edit : BotPageState
}

/**
 * 机器人详情（设计稿 P22）：头部、介绍 / 指令 / 评价 / 信息四个选项卡、评分分布、添加到群聊、私信。
 * 创建者额外有 仪表盘 / 编辑资料 / 重置 Key / 删除（确认）。
 */
@Composable
fun BotScreen(graph: AppGraph, botId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val api = remember { BotApi(graph.api) }
    val me = graph.session.session.value?.user

    // 上次看到的机器人详情和评价（内存）：先摆出来，再静默刷新
    val cache = remember(graph, botId) { graph.screenCache.slot<Pair<Bot, List<BotReview>>>("bot.$botId") }
    val cached = remember(cache) { cache.get() }
    var bot by remember { mutableStateOf(cached?.first) }
    var reviews by remember { mutableStateOf(cached?.second ?: emptyList()) }
    var loading by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(DetailTab.About) }
    var state by remember { mutableStateOf<BotPageState>(BotPageState.Detail) }
    var showInstall by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(botId, tick) {
        runCatching {
            bot = api.bot(botId)
            reviews = runCatching { api.reviews(botId) }.getOrDefault(reviews)
            error = null
            bot?.let { cache.put(it to reviews) }
        }.onFailure { error = it.userMessage() }
        loading = false
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2400)
            toast = null
        }
    }

    val current = bot
    if (current != null && state == BotPageState.Edit) {
        BotEditorPage(graph, current, onBack = { state = BotPageState.Detail }, onSaved = { state = BotPageState.Detail; tick++ })
        return
    }
    if (current != null && state == BotPageState.Dashboard) {
        BotDashboardPage(graph, current, onBack = { state = BotPageState.Detail })
        return
    }

    KcPage(
        title = current?.name ?: "机器人", onBack = { nav.back() },
        actions = {
            if (current != null && current.ownerId == me?.id) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { showMore = true }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.more, tint = colors.text, size = 22.dp, contentDescription = "管理")
                }
            }
        },
    ) {
        when {
            current == null && loading -> CenterText("正在加载…")
            current == null -> ErrorBlock(error ?: "打不开这个机器人", { loading = true; tick++ })
            else -> {
                val isOwner = current.ownerId == me?.id
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // 头部
                    Column(Modifier.fillMaxWidth().background(colors.panel).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BotAvatar(current, 72.dp)
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(current.name, style = type.title.copy(fontSize = type.title.fontSize * 1.1f), color = colors.text)
                            if (current.disabled) Tag("已停用", colors.danger) else if (current.online) Tag("在线", colors.ok) else Tag("离线", colors.subtle)
                            if (!current.isPublic) Tag("私有", colors.warn)
                        }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val rating = current.ratingAverage
                            if (rating != null && rating > 0) {
                                Stars(rating)
                                Text(ratingText(rating), style = type.secondary, color = colors.text, fontWeight = FontWeight.W600)
                            } else {
                                Text("暂无评分", style = type.secondary, color = colors.muted)
                            }
                            Text("· ${countText(current.reviewCount)} 条评价 · ${countText(current.installCount)} 个群在使用", style = type.caption, color = colors.muted)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SoftButton(if (busy) "…" else "私信", {
                                if (busy) return@SoftButton
                                busy = true
                                scope.launch {
                                    runCatching { api.directChat(current.accountId) }
                                        .onSuccess { nav.go(ChatRoute(it)) }
                                        .onFailure { toast = it.userMessage() }
                                    busy = false
                                }
                            }, Modifier.weight(1f), enabled = !current.disabled)
                            if (isOwner) SoftButton("仪表盘", { state = BotPageState.Dashboard }, Modifier.weight(1f))
                            PrimaryButton("添加到群聊", { showInstall = true }, Modifier.weight(if (isOwner) 1.3f else 1.6f), enabled = !current.disabled)
                        }
                        if (current.disabled) {
                            Text("这个机器人已被停用，暂时不能私信，也不能添加到群聊。", style = type.caption, color = colors.warn, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

                    // 选项卡
                    Row(Modifier.fillMaxWidth().background(colors.panel)) {
                        DetailTab.entries.forEach { item ->
                            val on = tab == item
                            Column(Modifier.weight(1f).clickable { tab = item }.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val label = when (item) {
                                    DetailTab.Commands -> parseBotCommands(current.commands).size.takeIf { it > 0 }?.let { "指令 $it" } ?: "指令"
                                    DetailTab.Reviews -> if (current.reviewCount > 0) "评价 ${current.reviewCount}" else "评价"
                                    else -> item.label
                                }
                                Text(label, style = type.rowTitle, color = if (on) colors.accent else colors.muted, fontWeight = if (on) FontWeight.W600 else FontWeight.W400)
                                Box(Modifier.padding(top = 10.dp).width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) colors.accent else androidx.compose.ui.graphics.Color.Transparent))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            DetailTab.About -> AboutTab(current)
                            DetailTab.Commands -> CommandsTab(current)
                            DetailTab.Reviews -> ReviewsTab(
                                bot = current, reviews = reviews, myId = me?.id, isOwner = isOwner, api = api,
                                onChanged = { tick++ }, onToast = { toast = it },
                            )
                            DetailTab.Info -> InfoTab(current, onOwner = { nav.go(UserRoute(current.ownerId)) })
                        }
                    }
                }
                toast?.let { message ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Text(
                            message, style = type.secondary, color = colors.panel,
                            modifier = Modifier.padding(24.dp).clip(RoundedCornerShape(22.dp)).background(colors.text.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    if (current != null) {
        if (showInstall) InstallToGroupDialog(graph, current, onDone = { showInstall = false; toast = it; tick++ }, onDismiss = { showInstall = false })
        if (showMore) {
            val options = buildList {
                add("edit" to "编辑资料")
                add("toggle" to if (current.disabled) "启用机器人" else "停用机器人")
                add("rotate" to "重置 Key")
                add("delete" to "删除机器人")
            }
            ChoiceDialog("管理", options, null, onPick = { key ->
                showMore = false
                when (key) {
                    "edit" -> state = BotPageState.Edit
                    "toggle" -> scope.launch {
                        runCatching {
                            api.update(current.id, kotlinx.serialization.json.buildJsonObject {
                                put("status", if (current.disabled) "active" else "disabled")
                            })
                        }.onSuccess { tick++ }.onFailure { toast = it.userMessage() }
                    }
                    "rotate" -> confirmRotate = true
                    "delete" -> confirmDelete = true
                }
            }, onDismiss = { showMore = false })
        }
        if (confirmRotate) {
            ConfirmDialog("重置 Key", "旧 Key 会立刻失效，正在用它的程序要换成新 Key 才能继续工作。确定重置吗？", "重置", onConfirm = {
                confirmRotate = false
                scope.launch {
                    runCatching { api.rotateKey(current.id) }.onSuccess { newKey = it }.onFailure { toast = it.userMessage() }
                }
            }, onDismiss = { confirmRotate = false })
        }
        if (confirmDelete) {
            ConfirmDialog("删除机器人", "删除「${current.name}」后，它会从所有群里移除，且无法恢复。", "删除", onConfirm = {
                confirmDelete = false
                scope.launch {
                    runCatching { api.delete(current.id) }
                        .onSuccess { Haptics.confirm(view); nav.back() }
                        .onFailure { toast = it.userMessage(); Haptics.reject(view) }
                }
            }, onDismiss = { confirmDelete = false })
        }
    }
    newKey?.let { KeyDialog(it, "新的 Key", onDismiss = { newKey = null }) }
}

@Composable
private fun AboutTab(bot: Bot) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                bot.description?.takeIf { it.isNotBlank() } ?: "这个机器人还没有写介绍",
                style = type.body, color = if (bot.description.isNullOrBlank()) colors.subtle else colors.text,
            )
            bot.functions?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp, end = 8.dp).size(5.dp).clip(CircleShape).background(colors.accent))
                    Text(line, style = type.body, color = colors.text)
                }
            }
        }
    }
}

@Composable
private fun CommandsTab(bot: Bot) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val commands = remember(bot.commands) { parseBotCommands(bot.commands) }
    if (commands.isEmpty()) {
        // 一条都解析不出来的旧自由文本原样显示，不当成「暂无指令」
        Text(bot.commands?.takeIf { it.isNotBlank() } ?: "暂无指令", style = type.body, color = if (bot.commands.isNullOrBlank()) colors.subtle else colors.text)
        return
    }
    Card {
        commands.forEachIndexed { index, line ->
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    (listOf(line.command) + line.params).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (line.description.isNotBlank()) Text(line.description, style = type.secondary, color = colors.muted)
            }
            if (index < commands.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(colors.border))
        }
    }
    if (commands.any { it.params.isNotEmpty() }) {
        Text("尖括号里的是必填参数，方括号里的可以不填。在群里输入 / 就能唤出指令面板。", style = type.caption, color = colors.muted)
    }
}

@Composable
private fun InfoTab(bot: Bot, onOwner: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val owner = bot.owner
    Card {
        InfoRow("创建者", onClick = onOwner) {
            if (owner != null) Avatar(owner.avatarUrl, owner.displayName, owner.id, size = 24.dp)
            Text(owner?.displayName ?: "用户 ${bot.ownerId}", style = type.body, color = colors.text, modifier = Modifier.padding(start = 8.dp))
        }
        InfoRow("可见范围") { Text(if (bot.isPublic) "公开到广场" else "私有", style = type.body, color = colors.text) }
        InfoRow("运行状态") { Text(if (bot.disabled) "已停用" else if (bot.online) "在线" else "离线", style = type.body, color = colors.text) }
        InfoRow("最近更新") { Text(dateText(bot.updatedAt).ifBlank { "未知" }, style = type.body, color = colors.text) }
        InfoRow("创建时间") { Text(dateText(bot.createdAt).ifBlank { "未知" }, style = type.body, color = colors.text) }
    }
}

@Composable
private fun InfoRow(label: String, onClick: (() -> Unit)? = null, value: @Composable () -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = KcTheme.type.body, color = colors.muted, modifier = Modifier.weight(1f))
        value()
        if (onClick != null) KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 评价：评分分布 · 我的评价 · 评价列表（点赞、回复） */
@Composable
private fun ReviewsTab(bot: Bot, reviews: List<BotReview>, myId: Long?, isOwner: Boolean, api: BotApi, onChanged: () -> Unit, onToast: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var myRating by remember(bot.myReview?.id) { mutableIntStateOf(bot.myReview?.rating?.toInt() ?: 0) }
    var myText by remember(bot.myReview?.id) { mutableStateOf(bot.myReview?.content.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<Pair<BotReview, Long?>?>(null) }
    var replyText by remember { mutableStateOf("") }

    // 评分分布（5 → 1 星）：来自评价列表自己统计
    val counts = remember(reviews) { (5 downTo 1).map { star -> star to reviews.count { it.rating.toInt() == star } } }
    val maxCount = (counts.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    if (reviews.isNotEmpty()) {
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("评分分布", style = type.group, color = colors.muted)
                counts.forEach { (star, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$star ★", style = type.caption, color = colors.muted, modifier = Modifier.width(34.dp))
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.panelMuted)) {
                            Box(Modifier.fillMaxWidth(count.toFloat() / maxCount).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.star))
                        }
                        Text("$count", style = type.caption, color = colors.text, modifier = Modifier.width(36.dp).padding(start = 8.dp))
                    }
                }
            }
        }
    }

    if (!isOwner) {
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (bot.myReview != null) "修改我的评价" else "给它打个分", style = type.group, color = colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (star in 1..5) {
                        KcIcon(
                            if (star <= myRating) KcIcons.star.duotone() else KcIcons.star,
                            tint = if (star <= myRating) colors.star else colors.subtle, size = 32.dp,
                            modifier = Modifier.clip(CircleShape).clickable { myRating = star },
                        )
                    }
                }
                PlainField(myText, { myText = it.take(500) }, "说说使用感受（选填）", singleLine = false, minHeight = 64.dp)
                PrimaryButton(if (saving) "提交中…" else "提交评价", {
                    saving = true
                    scope.launch {
                        runCatching { api.saveReview(bot.id, myRating, myText) }
                            .onSuccess { Haptics.confirm(view); onToast("评价已提交"); onChanged() }
                            .onFailure { Haptics.reject(view); onToast(it.userMessage()) }
                        saving = false
                    }
                }, enabled = myRating > 0 && !saving)
            }
        }
    }

    SectionLabel("全部评价", trailing = "${reviews.size} 条")
    if (reviews.isEmpty()) Text("还没有评价", style = type.secondary, color = colors.subtle)
    reviews.forEach { review ->
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val user = review.user
                    Avatar(user?.avatarUrl, user?.displayName ?: "?", user?.id ?: review.userId, size = 30.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(user?.displayName ?: "用户 ${review.userId}", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Stars(review.rating, 12.dp)
                            Text(dateText(review.createdAt), style = type.caption, color = colors.subtle)
                        }
                    }
                }
                if (!review.content.isNullOrBlank()) Text(review.content, style = type.body, color = colors.text)
                review.replies.forEach { reply ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.panelMuted).padding(10.dp)) {
                        Text(
                            (reply.user?.displayName ?: "用户 ${reply.userId}") + (reply.parentAuthor?.let { " 回复 ${it.displayName}" } ?: ""),
                            style = type.caption, color = colors.accent,
                        )
                        Text(reply.content, style = type.secondary, color = colors.text)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(
                                (if (reply.likedByMe) "已赞 " else "赞 ") + reply.likeCount, style = type.caption, color = if (reply.likedByMe) colors.accent else colors.muted,
                                modifier = Modifier.clickable { scope.launch { runCatching { api.likeReview(bot.id, review.id, reply.id) }.onSuccess { onChanged() } } },
                            )
                            Text("回复", style = type.caption, color = colors.muted, modifier = Modifier.clickable { replyTarget = review to reply.id; replyText = "" })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        (if (review.likedByMe) "已赞 " else "赞 ") + review.likeCount, style = type.caption, color = if (review.likedByMe) colors.accent else colors.muted,
                        modifier = Modifier.clickable { scope.launch { runCatching { api.likeReview(bot.id, review.id) }.onSuccess { onChanged() } } },
                    )
                    Text("回复", style = type.caption, color = colors.muted, modifier = Modifier.clickable { replyTarget = review to null; replyText = "" })
                }
            }
        }
    }

    replyTarget?.let { (review, parentId) ->
        DialogCard({ replyTarget = null }, "回复评价") {
            PlainField(replyText, { replyText = it.take(500) }, "写下你的回复", singleLine = false, minHeight = 80.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("取消", { replyTarget = null }, Modifier.weight(1f))
                PrimaryButton("发送", {
                    val text = replyText
                    replyTarget = null
                    scope.launch {
                        runCatching { api.replyReview(bot.id, review.id, text, parentId) }
                            .onSuccess { onChanged() }
                            .onFailure { onToast(it.userMessage()) }
                    }
                }, Modifier.weight(1f), enabled = replyText.isNotBlank())
            }
        }
    }
}
