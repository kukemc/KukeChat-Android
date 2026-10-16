package ink.kuke.chat.feature.group

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcButton
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.KcSwitch
import ink.kuke.chat.feature.chat.KcTextField
import ink.kuke.chat.feature.chat.SheetForm
import ink.kuke.chat.feature.chat.SheetRow
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.a
import ink.kuke.chat.feature.chat.asArr
import ink.kuke.chat.feature.chat.asObj
import ink.kuke.chat.feature.chat.b
import ink.kuke.chat.feature.chat.copyText
import ink.kuke.chat.feature.chat.i
import ink.kuke.chat.feature.chat.l
import ink.kuke.chat.feature.chat.o
import ink.kuke.chat.feature.chat.parseMemberPage
import ink.kuke.chat.feature.chat.s
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.contacts.SocialApi
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.GroupPlusRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 群资料页上新加的面板 / 子页（叠在页面上层，同一时间一个） */
internal sealed interface GroupExtra {
    data class Leaderboard(val type: String) : GroupExtra
    data object Checkin : GroupExtra
    data object Invite : GroupExtra
    data object Share : GroupExtra
    data object ShareForward : GroupExtra
    data object Emojis : GroupExtra
    data object Category : GroupExtra
    data object GameOid : GroupExtra
    data class RemoveBot(val bot: GroupBot) : GroupExtra
}

/** 本群的一个机器人（BotInstallationRead 的一部分） */
internal data class GroupBot(val botId: Long, val name: String, val description: String?, val avatarUrl: String?, val enabled: Boolean)

/** 游戏模式设置（GameModeRead） */
internal data class GameMode(val enabled: Boolean, val creationOid: String?)

/** 群分类（同电脑版 groupCategoryOptions） */
internal val GroupCategories = listOf("同学", "同事", "亲友", "家校", "游戏", "二次元", "更多兴趣")

private val CreationOidPattern = Regex("^[0-9a-f]{24}$")

/** 支持直接粘贴作品链接，抽出其中的 24 位十六进制作品 ID（同电脑版 normalizeCreationOid） */
internal fun normalizeCreationOid(value: String): String {
    val trimmed = value.trim()
    return (Regex("[0-9a-fA-F]{24}").find(trimmed)?.value ?: trimmed).lowercase()
}

/**
 * 群资料页新增区块的状态：机器人、游戏模式、当前打开的面板。
 * 和 [GroupModel] 分开，群资料页只需要多挂一个它。
 */
internal class GroupExtras(val graph: AppGraph, val conversationId: Long, private val scope: kotlinx.coroutines.CoroutineScope) {
    var sheet by mutableStateOf<GroupExtra?>(null)
    var bots by mutableStateOf<List<GroupBot>?>(null)
    var botBusy by mutableStateOf(false)
    var game by mutableStateOf<GameMode?>(null)
    var gameBusy by mutableStateOf(false)
    var gameError by mutableStateOf<String?>(null)
    /** 没开启时作品 ID 只留在本地，打开开关时一起提交 */
    var gameDraft by mutableStateOf("")

    fun loadBots() {
        scope.launch {
            runCatching { graph.api.get("/bots/conversations/$conversationId/bots").asArr().orEmpty() }
                .onSuccess { list ->
                    bots = list.mapNotNull { item ->
                        val obj = item.asObj() ?: return@mapNotNull null
                        val bot = obj.o("bot")
                        val botId = obj.l("bot_id") ?: return@mapNotNull null
                        GroupBot(
                            botId = botId,
                            name = bot?.s("name") ?: "机器人 $botId",
                            description = bot?.s("description")?.takeIf { it.isNotBlank() },
                            avatarUrl = bot?.s("avatar_url") ?: bot?.o("user")?.s("avatar_url"),
                            enabled = obj.b("enabled") != false,
                        )
                    }
                }
                .onFailure { if (bots == null) bots = emptyList() }
        }
    }

    fun toggleBot(bot: GroupBot, enabled: Boolean, say: (String) -> Unit) {
        if (botBusy) return
        botBusy = true
        bots = bots?.map { if (it.botId == bot.botId) it.copy(enabled = enabled) else it }
        scope.launch {
            runCatching { graph.api.patch("/bots/${bot.botId}/installations/$conversationId", buildJsonObject { put("enabled", enabled) }) }
                .onFailure { e ->
                    bots = bots?.map { if (it.botId == bot.botId) it.copy(enabled = bot.enabled) else it }
                    say(e.userMessage())
                }
            botBusy = false
        }
    }

    fun removeBot(bot: GroupBot, say: (String) -> Unit) {
        botBusy = true
        scope.launch {
            runCatching { graph.api.delete("/bots/${bot.botId}/installations/$conversationId") }
                .onSuccess {
                    bots = bots?.filter { it.botId != bot.botId }
                    graph.conversations.refreshSoon(0)
                    say("已将「${bot.name}」移出本群")
                }
                .onFailure { say(it.userMessage()) }
            botBusy = false
        }
    }

    fun loadGame() {
        scope.launch {
            runCatching { graph.api.get("/game/conversations/$conversationId").asObj() }
                .onSuccess { obj ->
                    val mode = GameMode(obj?.b("enabled") == true, obj?.s("creation_oid"))
                    game = mode
                    gameDraft = mode.creationOid.orEmpty()
                }
        }
    }

    /** 开 / 关游戏模式。后端会说明拒绝原因（非公开群、缺作品 ID 等），原样展示 */
    fun saveGame(enabled: Boolean, oidInput: String) {
        val oid = normalizeCreationOid(oidInput)
        if (enabled && !CreationOidPattern.matches(oid)) {
            gameError = "请先填写要绑定的作品 ID（24 位，可直接粘贴作品链接）"
            return
        }
        gameBusy = true
        scope.launch {
            runCatching {
                graph.api.patch("/game/conversations/$conversationId", buildJsonObject {
                    put("enabled", enabled)
                    put("creation_oid", if (enabled) JsonPrimitive(oid) else JsonNull)
                }).asObj()
            }.onSuccess { obj ->
                gameError = null
                val mode = GameMode(obj?.b("enabled") == true, obj?.s("creation_oid"))
                game = mode
                if (mode.creationOid != null) gameDraft = mode.creationOid
            }.onFailure { gameError = it.userMessage() }
            gameBusy = false
        }
    }
}

@Composable
internal fun rememberGroupExtras(graph: AppGraph, conversationId: Long): GroupExtras {
    val scope = rememberCoroutineScope()
    return remember(conversationId) { GroupExtras(graph, conversationId, scope) }
}

// ------------------------------------------------------------------ 成长：等级、签到、排行榜入口

/**
 * 群成长卡（同电脑版 GrowthSection）：等级 + 本级经验进度 + 签到按钮；下面三格通往排行榜。
 * 签到按钮打开签到面板（可附带一句留言）。
 */
@Composable
internal fun GroupGrowthCard(model: GroupModel, extras: GroupExtras, levelTitle: String?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val checkin = model.checkin ?: return
    // 接口给的是「距下一级还差多少」；本级总共要 40 + 12×(等级-1)（服务端 docs/group-levels.md）
    val remaining = checkin.nextExp
    val target = if (remaining > 0) (1f - remaining / (40f + 12f * (checkin.level - 1))).coerceIn(0f, 1f) else 1f
    val progress by animateFloatAsState(target, tween(600), label = "groupExp")
    Card {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("LV${checkin.level}", style = type.title.copy(fontWeight = FontWeight.W800), color = colors.accent)
                    if (levelTitle != null) Text(levelTitle, style = type.caption, color = colors.warn, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.panelMuted)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(3.dp)).background(colors.fill))
                }
                Text(
                    if (remaining > 0) "经验 ${checkin.exp} · 距下一级还差 $remaining" else "经验 ${checkin.exp} · 已满级",
                    style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box(Modifier.width(14.dp))
            KcButton(
                when {
                    model.checkinBusy -> "签到中…"
                    checkin.checkedInToday -> "已签到"
                    else -> "签到"
                },
                { extras.sheet = GroupExtra.Checkin },
                enabled = !checkin.checkedInToday && !model.checkinBusy,
                modifier = Modifier.width(88.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrowthStat("${checkin.streak}", "连续签到", Modifier.weight(1f)) { extras.sheet = GroupExtra.Leaderboard("checkin_streak") }
            GrowthStat("${checkin.total}", "累计签到", Modifier.weight(1f)) { extras.sheet = GroupExtra.Leaderboard("checkin_total") }
            GrowthStat(null, "群活跃榜", Modifier.weight(1f)) { extras.sheet = GroupExtra.Leaderboard("activity") }
        }
    }
}

@Composable
private fun GrowthStat(value: String?, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Column(
        modifier.pressScale(press).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted)
            .clickable(interactionSource = press, indication = null, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (value != null) Text(value, style = KcTheme.type.rowTitle.copy(fontWeight = FontWeight.W700), color = colors.text)
        else KcIcon(KcIcons.trophy, tint = colors.warn, size = 20.dp)
        Text(label, style = KcTheme.type.caption, color = colors.muted)
    }
}

// ------------------------------------------------------------------ 群机器人

/** 本群已添加的机器人（同电脑版 BotsSection）。群主 / 管理员可以停用或移出 */
@Composable
internal fun GroupBotsSection(extras: GroupExtras, manager: Boolean) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    LaunchedEffect(extras.conversationId) { extras.loadBots() }
    val bots = extras.bots ?: return
    // 没有机器人时只给管理员看提示，普通成员不必多一张空卡
    if (bots.isEmpty() && !manager) return
    Card {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("群机器人", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            if (bots.isNotEmpty()) Text("${bots.size} 个", style = KcTheme.type.caption, color = colors.muted)
        }
        if (bots.isEmpty()) {
            Text("暂无机器人，可以到机器人中心添加公开机器人", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
        }
        bots.forEach { bot ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(bot.avatarUrl, bot.name, tone = bot.botId, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(bot.name, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (bot.enabled) bot.description ?: "KukeChat Bot API" else "已停用，不会接收或发送消息",
                        style = KcTheme.type.caption, color = if (bot.enabled) colors.muted else colors.warn, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (manager) {
                    KcSwitch(bot.enabled, { on -> extras.toggleBot(bot, on) { toast(context, it) } }, enabled = !extras.botBusy)
                    Box(
                        Modifier.padding(start = 6.dp).size(34.dp).clip(CircleShape).clickable(enabled = !extras.botBusy) { extras.sheet = GroupExtra.RemoveBot(bot) },
                        contentAlignment = Alignment.Center,
                    ) { KcIcon(KcIcons.trash, tint = colors.muted, size = 18.dp, contentDescription = "移出本群") }
                }
            }
        }
        Box(Modifier.height(6.dp))
    }
}

// ------------------------------------------------------------------ 群表情入口

@Composable
internal fun GroupEmojiEntry(graph: AppGraph, extras: GroupExtras, manager: Boolean) {
    val emojis = rememberGroupEmojis(graph, extras.conversationId)
    Card {
        GroupRow(KcIcons.sticker, "群表情", (if (manager) "管理 · " else "") + (emojis?.let { "${it.size} / ${GroupEmojis.MAX}" } ?: "…")) {
            extras.sheet = GroupExtra.Emojis
        }
    }
}

// ------------------------------------------------------------------ 任务设置

/** 群任务设置（同电脑版 TasksSection）：功能开关，以及谁可以创建任务 */
@Composable
internal fun GroupTaskSettings(detail: GroupDetail, model: GroupModel) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    var enabled by remember(detail.tasksEnabled) { mutableStateOf(detail.tasksEnabled) }
    Card {
        SwitchRow(KcIcons.checkSquare, "开启任务功能", enabled, hint = "成员可以在群里创建、指派和跟进任务") { on ->
            enabled = on
            model.patch("/settings", buildJsonObject { put("tasks_enabled", on) }) { msg -> if (msg != null) { enabled = detail.tasksEnabled; toast(context, msg) } }
        }
        AnimatedVisibility(enabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("谁可以创建任务", style = KcTheme.type.secondary, color = colors.text, modifier = Modifier.weight(1f))
                Segmented(listOf("members" to "全部成员", "admins" to "仅管理员"), detail.taskCreationPermission) { key ->
                    model.patch("/settings", buildJsonObject { put("task_creation_permission", key) }) { msg -> if (msg != null) toast(context, msg) }
                }
            }
        }
    }
}

/** 两三段的小分段选择器 */
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(3.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                style = KcTheme.type.caption.copy(fontWeight = if (on) FontWeight.W700 else FontWeight.W500),
                color = if (on) colors.text else colors.muted,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) colors.panel else Color.Transparent)
                    .clickable { if (!on) onPick(key) }.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 游戏模式

/** 游戏模式（同电脑版 GameModeSection）：把本群绑定到一个作品。只有群主能配置 */
@Composable
internal fun GameModeSection(extras: GroupExtras) {
    val colors = KcTheme.colors
    LaunchedEffect(extras.conversationId) { extras.loadGame() }
    val mode = extras.game
    Card {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("游戏模式", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            if (mode?.enabled == true) Text("已开启", style = KcTheme.type.caption.copy(fontWeight = FontWeight.W600), color = colors.ok)
        }
        GroupRow(KcIcons.gamepad2, "绑定作品 ID", extras.gameDraft.ifBlank { "未绑定" }) { extras.sheet = GroupExtra.GameOid }
        SwitchRow(KcIcons.zap, "开启游戏模式", mode?.enabled == true) { on ->
            if (!extras.gameBusy && mode != null) extras.saveGame(on, extras.gameDraft)
        }
        extras.gameError?.let {
            Text(it, style = KcTheme.type.caption, color = colors.danger, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp))
        }
        Text(
            "开启后，绑定作品里的玩家可以直接收发本群消息。本群近期聊天记录会对所有游玩该作品的人可见（包括未登录访客），因此仅公开群可以开启。玩家发言时会自动入群，原有的禁言与成员管理照常生效。",
            style = KcTheme.type.caption,
            color = colors.warn,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(10.dp)).background(colors.warn.copy(alpha = 0.1f)).padding(10.dp),
        )
    }
}

// ------------------------------------------------------------------ 面板宿主

/**
 * 群资料页新增面板的宿主：放在页面层（不在滚动列里），按 [GroupExtras.sheet] 打开一个。
 */
@Composable
internal fun GroupExtrasHost(extras: GroupExtras, model: GroupModel, onMemberAdded: () -> Unit) {
    val graph = extras.graph
    val context = LocalContext.current
    val close = { extras.sheet = null }
    val detail = model.detail
    when (val sheet = extras.sheet) {
        null -> Unit
        is GroupExtra.Leaderboard -> LeaderboardSheet(graph, extras.conversationId, sheet.type, onDismiss = close)
        GroupExtra.Checkin -> CheckinSheet(onDismiss = close) { message ->
            close()
            model.doCheckin(message) { toast(context, it) }
        }
        GroupExtra.Invite -> InviteFriendsSheet(graph, extras.conversationId, manager = detail?.manager == true, onDismiss = close, onAdded = onMemberAdded)
        GroupExtra.Share -> KcSheet(onDismiss = close, title = "分享群聊") {
            SheetRow("转发群聊名片", { extras.sheet = GroupExtra.ShareForward }, icon = KcIcons.send, detail = "发到一个会话，对方点卡片就能加入")
            SheetRow("复制邀请链接", {
                close()
                copyInviteLink(graph, extras.conversationId, context)
            }, icon = KcIcons.copy)
            Box(Modifier.height(8.dp))
        }
        GroupExtra.ShareForward -> if (detail != null) ShareCardSheet(graph, detail, onBack = { extras.sheet = GroupExtra.Share }, onDone = close)
        GroupExtra.Emojis -> GroupEmojiPage(graph, extras.conversationId, canManage = detail?.manager == true, onBack = close)
        GroupExtra.Category -> KcSheet(onDismiss = close, title = "群分类") {
            (listOf<String?>(null) + GroupCategories).forEach { option ->
                SheetRow(option ?: "未设置", {
                    close()
                    model.patch("/profile", buildJsonObject { put("category", option?.let { JsonPrimitive(it) } ?: JsonNull) }) { msg -> if (msg != null) toast(context, msg) }
                }, selected = (detail?.category?.takeIf { it.isNotBlank() }) == option)
            }
            Box(Modifier.height(8.dp))
        }
        GroupExtra.GameOid -> {
            var text by remember { mutableStateOf(extras.gameDraft) }
            KcSheet(onDismiss = close, title = "绑定作品 ID") {
                SheetForm {
                    KcTextField(text, { text = it.take(200) }, "粘贴作品链接或 24 位作品 ID")
                    Text("没开启游戏模式时只保存在本地，打开开关时一起提交。", style = KcTheme.type.caption, color = KcTheme.colors.muted)
                    KcButton("保存", {
                        val oid = normalizeCreationOid(text)
                        if (oid.isNotEmpty() && !CreationOidPattern.matches(oid)) {
                            toast(context, "作品 ID 应为 24 位十六进制，可直接粘贴作品链接")
                        } else {
                            extras.gameDraft = oid
                            close()
                            if (extras.game?.enabled == true && oid != extras.game?.creationOid) extras.saveGame(true, oid)
                        }
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is GroupExtra.RemoveBot -> KcConfirm("移出机器人", "确定将机器人「${sheet.bot.name}」移出本群吗？", "移出", danger = true, onConfirm = {
            close()
            extras.removeBot(sheet.bot) { toast(context, it) }
        }, onDismiss = close)
    }
}

private fun copyInviteLink(graph: AppGraph, conversationId: Long, context: android.content.Context) {
    graph.scope.launch {
        runCatching { graph.api.post("/invites/group/$conversationId").asObj()?.s("url") }
            .onSuccess { url ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (url.isNullOrBlank()) toast(context, "邀请链接生成失败") else {
                        copyText(context, url)
                        toast(context, "邀请链接已复制")
                    }
                }
            }
            .onFailure { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { toast(context, it.userMessage()) } }
    }
}

// ------------------------------------------------------------------ 签到

/** 签到面板：可以附带一句留言（≤80 字），会出现在群里的签到消息里 */
@Composable
private fun CheckinSheet(onDismiss: () -> Unit, onSubmit: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    KcSheet(onDismiss = onDismiss, title = "今日签到") {
        SheetForm {
            KcTextField(text, { text = it.take(80) }, "说点什么（可选，最多 80 字）", singleLine = false, minHeight = 72.dp)
            Text("${text.length}/80", style = KcTheme.type.caption, color = KcTheme.colors.subtle, modifier = Modifier.align(Alignment.End))
            KcButton("签到", { onSubmit(text.trim().takeIf { it.isNotEmpty() }) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ 排行榜

private val BoardTypes = listOf("activity" to "活跃榜", "level" to "等级榜", "checkin_streak" to "连签榜", "checkin_total" to "累签榜", "message" to "发言榜")
private val BoardPeriods = listOf("today" to "今日", "week" to "近7天", "month" to "近30天", "all" to "全部")

private data class BoardRow(
    val rank: Int,
    val userId: Long,
    val name: String,
    val avatarUrl: String?,
    val level: Int,
    val levelExp: Int,
    val activity: Int,
    val streak: Int,
    val total: Int,
    val messages: Int,
)

private fun parseBoardRow(obj: JsonObject): BoardRow? {
    val user = obj.o("user")
    val member = obj.o("member")
    val userId = obj.l("user_id") ?: return null
    return BoardRow(
        rank = obj.i("rank") ?: 0,
        userId = userId,
        name = member?.s("nickname")?.takeIf { it.isNotBlank() } ?: user?.s("nickname")?.takeIf { it.isNotBlank() } ?: user?.s("username") ?: "用户 $userId",
        avatarUrl = user?.s("avatar_url"),
        level = obj.i("level") ?: 1,
        levelExp = obj.i("level_exp") ?: 0,
        activity = obj.i("activity_score") ?: 0,
        streak = obj.i("current_checkin_streak") ?: 0,
        total = obj.i("total_checkins") ?: 0,
        messages = obj.i("message_count") ?: 0,
    )
}

private fun BoardRow.score(type: String): String = when (type) {
    "message" -> "$messages 条"
    "checkin_total" -> "$total 天"
    "checkin_streak" -> "$streak 天"
    "level" -> "$levelExp 经验"
    else -> "$activity"
}

/** 群排行榜（GET /conversations/{id}/leaderboard）：活跃、等级、签到、发言，按周期切换；底部钉着我的名次 */
@Composable
private fun LeaderboardSheet(graph: AppGraph, conversationId: Long, initialType: String, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    var type by remember { mutableStateOf(initialType) }
    var period by remember { mutableStateOf("all") }
    var rows by remember { mutableStateOf<List<BoardRow>?>(null) }
    var mine by remember { mutableStateOf<BoardRow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(type, period) {
        error = null
        runCatching { graph.api.get("/conversations/$conversationId/leaderboard?type=$type&period=$period&limit=50").asObj() }
            .onSuccess { obj ->
                rows = obj?.a("items")?.mapNotNull { it.asObj()?.let(::parseBoardRow) }.orEmpty()
                mine = obj?.o("my_rank")?.let(::parseBoardRow)
            }
            .onFailure { error = it.userMessage() }
    }
    KcSheet(onDismiss = onDismiss, title = "群排行榜", maxHeight = 640.dp) {
        ChipRow(BoardTypes, type) { type = it }
        ChipRow(BoardPeriods, period) { period = it }
        val list = rows
        when {
            error != null && list == null -> StateText(error!!, error = true)
            list == null -> StateText("正在加载榜单…")
            list.isEmpty() -> StateText("暂无榜单数据")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                items(list, key = { "${it.rank}-${it.userId}" }) { row -> BoardLine(row, type) }
            }
        }
        mine?.let { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Box(Modifier.background(colors.accentSoft)) { BoardLine(row, type, me = true) }
        }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                style = KcTheme.type.secondary.copy(fontWeight = if (on) FontWeight.W700 else FontWeight.W500),
                color = if (on) colors.onFill else colors.muted,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.fill else colors.panelMuted).clickable { onPick(key) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BoardLine(row: BoardRow, type: String, me: Boolean = false) {
    val colors = KcTheme.colors
    val medal = when (row.rank) {
        1 -> Color(0xFFF2B01E)
        2 -> Color(0xFFA0A8B8)
        3 -> Color(0xFFCD7F4A)
        else -> null
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(medal ?: Color.Transparent), contentAlignment = Alignment.Center) {
            Text("${row.rank}", style = KcTheme.type.caption.copy(fontWeight = FontWeight.W800), color = if (medal != null) Color.White else colors.muted)
        }
        Box(Modifier.padding(start = 10.dp)) { Avatar(row.avatarUrl, row.name, tone = row.userId, size = 36.dp) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(if (me) "${row.name}（我）" else row.name, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("LV${row.level} · 活跃 ${row.activity} · 连签 ${row.streak} 天", style = KcTheme.type.caption, color = colors.muted, maxLines = 1)
        }
        Text(row.score(type), style = KcTheme.type.secondary.copy(fontWeight = FontWeight.W700), color = colors.accent, modifier = Modifier.padding(start = 8.dp))
    }
}

// ------------------------------------------------------------------ 邀请好友

/**
 * 邀请好友入群（同电脑版 InviteDialog）：列出好友，已在群里的置灰，多选后 POST /conversations/{id}/members。
 * 409 group_full：群满员，提示原因，群主 / 管理员多一个「去升级」。顶上还有「复制邀请链接」。
 */
@Composable
private fun InviteFriendsSheet(graph: AppGraph, conversationId: Long, manager: Boolean, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<ink.kuke.chat.core.model.User>?>(null) }
    var memberIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fullText by remember { mutableStateOf<String?>(null) }
    val myId = graph.session.session.collectAsState().value?.user?.id
    LaunchedEffect(Unit) {
        runCatching { SocialApi(graph.api).friends().mapNotNull { it.other }.filter { it.id != myId }.distinctBy { it.id } }
            .onSuccess { friends = it }
            .onFailure { friends = emptyList(); toast(context, it.userMessage()) }
        // 已在群里的成员：分页取一遍（每页 100，最多 10 页），只用来置灰；服务端本来也会跳过已在群里的人
        val ids = mutableSetOf<Long>()
        var offset = 0
        repeat(10) {
            val page = runCatching { parseMemberPage(graph.api.get("/conversations/$conversationId/members?limit=100&offset=$offset"), offset) }.getOrNull() ?: return@repeat
            page.items.forEach { ids += it.userId }
            memberIds = ids.toSet()
            if (!page.hasMore || page.items.isEmpty()) return@LaunchedEffect
            offset += page.items.size
        }
    }
    KcSheet(onDismiss = onDismiss, title = "邀请好友", maxHeight = 640.dp) {
        SheetRow("复制邀请链接", { copyInviteLink(graph, conversationId, context) }, icon = KcIcons.link, detail = "发到任意地方")
        if (!manager) {
            Text("只有群主和管理员可以直接拉好友进群，你可以把邀请链接发给他们。", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Box(Modifier.height(8.dp))
            return@KcSheet
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { KcTextField(query, { query = it }, "搜索好友", Modifier.fillMaxWidth()) }
        fullText?.let { text ->
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.warn.copy(alpha = 0.12f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = KcTheme.type.caption, color = colors.text, modifier = Modifier.weight(1f))
                Row(
                    Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(colors.fill).clickable {
                        onDismiss()
                        nav.go(GroupPlusRoute(conversationId))
                    }.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.sparkles, tint = colors.onFill, size = 14.dp)
                    Text("去升级", style = KcTheme.type.caption.copy(fontWeight = FontWeight.W700), color = colors.onFill, modifier = Modifier.padding(start = 3.dp))
                }
            }
        }
        val list = friends
        val shown = list.orEmpty().filter { query.isBlank() || it.displayName.contains(query.trim(), ignoreCase = true) || it.username.contains(query.trim(), ignoreCase = true) }
        when {
            list == null -> StateText("加载好友…")
            shown.isEmpty() -> StateText(if (list.isEmpty()) "还没有好友" else "没有找到")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(shown, key = { it.id }) { user ->
                    val inGroup = user.id in memberIds
                    val on = user.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !inGroup) { selected = if (on) selected - user.id else selected + user.id }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(user.avatarUrl, user.displayName, tone = user.id, size = 40.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(user.displayName, style = KcTheme.type.rowTitle, color = if (inGroup) colors.subtle else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (inGroup) "已在群里" else "@${user.username}", style = KcTheme.type.caption, color = colors.subtle, maxLines = 1)
                        }
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(if (on) colors.fill else Color.Transparent)
                                .border(1.5.dp, if (on) colors.fill else colors.borderStrong.copy(alpha = if (inGroup) 0.4f else 1f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) KcIcon(KcIcons.check, tint = colors.onFill, size = 14.dp) }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            KcButton(
                when {
                    busy -> "邀请中…"
                    selected.isEmpty() -> "邀请"
                    else -> "邀请 ${selected.size} 人"
                },
                {
                    busy = true
                    fullText = null
                    scope.launch {
                        runCatching {
                            graph.api.post("/conversations/$conversationId/members", buildJsonObject {
                                put("user_ids", buildJsonArray { selected.forEach { add(JsonPrimitive(it)) } })
                            })
                        }.onSuccess {
                            toast(context, "已邀请 ${selected.size} 位好友")
                            onAdded()
                            onDismiss()
                        }.onFailure { e ->
                            if ((e as? ApiException)?.code == "group_full") fullText = e.userMessage().ifBlank { "群人数已经达到上限，不能再邀请新成员了" }
                            else toast(context, e.userMessage())
                        }
                        busy = false
                    }
                },
                enabled = selected.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ 分享群名片

/**
 * 转发群聊名片（同电脑版 ShareForwardDialog）：选一个会话，发一条带 share_card 的文字消息（正文是邀请链接，旧客户端也能点）。
 * 对方点卡片进邀请页，直接加入。
 */
@Composable
private fun ShareCardSheet(graph: AppGraph, detail: GroupDetail, onBack: () -> Unit, onDone: () -> Unit) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by graph.conversations.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    val list = conversations.filter { it.id != detail.id && (query.isBlank() || it.name().contains(query.trim(), ignoreCase = true)) }
    KcSheet(onDismiss = onDone, title = "转发群聊名片", maxHeight = 640.dp) {
        Box(Modifier.padding(horizontal = 20.dp)) { KcTextField(query, { query = it }, "搜索会话", Modifier.fillMaxWidth()) }
        if (list.isEmpty()) StateText("没有可发送的会话") else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(top = 6.dp)) {
            items(list, key = { it.id }) { conversation ->
                val on = conversation.id == target
                Row(
                    Modifier.fillMaxWidth().background(if (on) colors.accentSoft else Color.Transparent).clickable { target = conversation.id }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        if (conversation.isGroup) conversation.avatarUrl else conversation.directUser?.avatarUrl ?: conversation.avatarUrl,
                        conversation.name(),
                        tone = conversation.directUser?.id ?: conversation.id,
                        size = 40.dp,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(conversation.name(), style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (conversation.isGroup) "群聊 · ${conversation.memberCount} 人" else "联系人", style = KcTheme.type.caption, color = colors.subtle)
                    }
                    if (on) KcIcon(KcIcons.check, tint = colors.accent, size = 20.dp)
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { KcTextField(note, { note = it.take(120) }, "可选，添加一句附言", Modifier.fillMaxWidth()) }
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KcButton("返回", onBack, modifier = Modifier.weight(1f), primary = false)
            KcButton(if (busy) "发送中…" else "发送", {
                val targetId = target ?: return@KcButton
                busy = true
                scope.launch {
                    runCatching {
                        val link = graph.api.post("/invites/group/${detail.id}").asObj() ?: error("邀请链接生成失败")
                        val url = link.s("url") ?: error("邀请链接生成失败")
                        val card = buildJsonObject {
                            put("type", "group")
                            put("conversation_id", detail.id)
                            put("title", detail.title)
                            detail.avatarUrl?.let { put("avatar_url", it) }
                            if (detail.description.isNotBlank()) put("description", detail.description)
                            detail.category?.let { put("category", it) }
                            put("member_count", detail.memberCount)
                            put("join_mode", detail.joinMode)
                            put("auto_approve", detail.autoApprove)
                            link.s("token")?.let { put("invite_token", it) }
                            put("invite_url", url)
                        }
                        graph.api.post("/conversations/$targetId/messages", buildJsonObject {
                            put("content", listOf(note.trim(), url).filter { it.isNotEmpty() }.joinToString("\n"))
                            put("type", "text")
                            put("metadata", buildJsonObject { put("share_card", card) })
                        })
                    }.onSuccess {
                        graph.conversations.refreshSoon(0)
                        toast(context, "群聊名片已发送")
                        onDone()
                    }.onFailure { toast(context, it.userMessage()) }
                    busy = false
                }
            }, enabled = target != null && !busy, modifier = Modifier.weight(1f))
        }
    }
}
