package ink.kuke.chat.feature.space

import androidx.compose.foundation.layout.requiredSize
import kotlinx.serialization.json.contentOrNull
import ink.kuke.chat.ui.shell.SettingsPageRoute
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.level.LevelIcons
import ink.kuke.chat.feature.settings.KcTextField
import ink.kuke.chat.feature.settings.PrimaryButton
import ink.kuke.chat.feature.settings.NavFeature
import ink.kuke.chat.feature.settings.NavFeatures
import ink.kuke.chat.feature.settings.NavPrefsStore
import ink.kuke.chat.feature.settings.Pill
import ink.kuke.chat.feature.settings.ToastHost
import ink.kuke.chat.feature.settings.copyToClipboard
import ink.kuke.chat.feature.settings.rememberToast
import ink.kuke.chat.feature.settings.rememberUpdateBadge
import ink.kuke.chat.service.KeepAlive
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.UnreadBadge
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.KeepAliveRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.OnlineUsersRoute
import ink.kuke.chat.ui.shell.SettingsRoute
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 空间页要读的我的资料（核心 User 模型里没有资料点赞数） */
@Serializable
internal data class SpaceMe(
    val id: Long,
    val username: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("presence_status") val presenceStatus: String? = null,
    @SerialName("presence_text") val presenceText: String? = null,
    @SerialName("account_level") val accountLevel: Int = 0,
    @SerialName("jury_badge") val juryBadge: String? = null,
    @SerialName("profile_like_count") val profileLikeCount: Int? = null,
    @SerialName("ccw_student_oid") val ccwStudentOid: String? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
}

@Serializable
private data class LikeItem(val user: User, @SerialName("created_at") val createdAt: String = "", val today: Boolean = false)

internal class SpaceData(
    val me: SpaceMe?, val online: Int?, val juryPending: Int, val tasksDue: Int,
    val cover: String? = null, val posts: Int? = null, val likes: Int? = null, val friends: Int? = null,
)

/** 进程里留一份上次的空间数据：切回「我的」时直接显示，后台静默刷新，不会先闪成 0 */
internal var spaceCache: SpaceData? = null

private data class PresenceOption(val key: String, val label: String, val detail: String)

private val PresenceOptions = listOf(
    PresenceOption("online", "在线", "正常聊天和接收消息"),
    PresenceOption("creating", "正在创作", "适合做作品时展示"),
    PresenceOption("busy", "忙碌", "暂时不方便回复"),
    PresenceOption("dnd", "请勿打扰", "告诉别人先别打扰"),
    PresenceOption("away", "离开", "人不在电脑前"),
    PresenceOption("custom", "自定义", "展示自己的状态文案"),
)

internal fun presenceLabel(status: String?, text: String?): String =
    text?.takeIf { it.isNotBlank() } ?: PresenceOptions.firstOrNull { it.key == status }?.label ?: "在线"

internal val JuryLabels = mapOf("trainee" to "见习风纪委员", "regular" to "风纪委员", "senior" to "资深风纪委员")

internal suspend fun loadSpace(graph: AppGraph): SpaceData = coroutineScope {
    val me = async { runCatching { KcJson.decodeFromJsonElement(SpaceMe.serializer(), graph.api.get("/auth/me")) }.getOrNull() }
    val online = async { runCatching { (graph.api.get("/users/online-count") as JsonObject)["online_count"]?.jsonPrimitive?.intOrNull }.getOrNull() }
    // 风纪角标：在任委员才有，数字是现在能分给我的案件 + 合议 / 复审件数（同导航轨）
    val jury = async {
        runCatching {
            val obj = graph.api.get("/jury/me") as JsonObject
            if (obj["status"]?.jsonPrimitive?.content != "active") 0
            else {
                val cases = obj["available_cases"]?.jsonPrimitive?.intOrNull ?: 0
                val senior = obj["senior"] as? JsonObject
                val panel = senior?.get("panel")?.jsonPrimitive?.intOrNull ?: 0
                val appeals = senior?.get("appeals")?.jsonPrimitive?.intOrNull ?: 0
                maxOf(0, cases) + maxOf(0, panel) + maxOf(0, appeals)
            }
        }.getOrDefault(0)
    }
    val tasks = async {
        runCatching {
            val obj = graph.api.get("/tasks/dashboard") as JsonObject
            (obj["overdue"]?.jsonPrimitive?.intOrNull ?: 0) + (obj["due_today"]?.jsonPrimitive?.intOrNull ?: 0)
        }.getOrDefault(0)
    }
    val mine = me.await()
    // 封面和数据卡：封面在 /users/{id} 的 profile_cover_url，动态 / 获赞在 /posts/users/{id}/stats，好友数是 /friends 的条数
    val cover = async { mine?.let { m -> runCatching { (graph.api.get("/users/${m.id}") as JsonObject)["profile_cover_url"]?.jsonPrimitive?.contentOrNull }.getOrNull() } }
    val stats = async { mine?.let { m -> runCatching { graph.api.get("/posts/users/${m.id}/stats") as JsonObject }.getOrNull() } }
    val friends = async { runCatching { (graph.api.get("/friends") as? kotlinx.serialization.json.JsonArray)?.size }.getOrNull() }
    val st = stats.await()
    SpaceData(
        mine, online.await(), jury.await(), tasks.await(),
        cover = cover.await(), posts = st?.get("post_count")?.jsonPrimitive?.intOrNull, likes = st?.get("like_count")?.jsonPrimitive?.intOrNull, friends = friends.await(),
    )
}

/**
 * 空间（设计稿 P27）：手机版的总入口。资料卡（SelfCard）、在线人数卡、功能宫格、「后台接收消息」。
 * 宫格的顺序来自「设置 › 底栏与功能排序」，排在前两位的已经在底栏上，不再出现在宫格里；设置固定在最后。
 *
 * [onKeepAlive] 只是为了兼容旧的调用方式；不传时用 LocalAppNav 跳 KeepAliveRoute。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(graph: AppGraph, onKeepAlive: (() -> Unit)? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val sessionUser = graph.session.session.collectAsStateWithLifecycle().value?.user
    val order by NavPrefsStore.shared(context).order.collectAsStateWithLifecycle()

    var data by remember { mutableStateOf(spaceCache?.takeIf { it.me == null || it.me.id == sessionUser?.id }) }
    var refreshing by remember { mutableStateOf(false) }
    var keepTick by remember { mutableIntStateOf(0) }
    // silent：进入页面时的刷新不显示下拉转圈，数据回来直接替换
    fun reload(silent: Boolean = false) {
        scope.launch {
            if (!silent) refreshing = true
            val fresh = runCatching { loadSpace(graph) }.getOrNull()
            if (fresh != null) { data = fresh; spaceCache = fresh }
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { reload(silent = true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { keepTick++ }
    val keepReady = remember(keepTick) { KeepAlive.ready(context) }
    val (_, updateDot) = rememberUpdateBadge(graph)

    val me = data?.me
    val name = me?.displayName ?: sessionUser?.displayName.orEmpty()
    val avatar = me?.avatarUrl ?: sessionUser?.avatarUrl
    val userId = me?.id ?: sessionUser?.id ?: 0L
    val username = me?.username ?: sessionUser?.username.orEmpty()
    val level = me?.accountLevel ?: sessionUser?.accountLevel ?: 0
    val presenceStatus = me?.presenceStatus ?: sessionUser?.presenceStatus
    val presenceText = me?.presenceText ?: sessionUser?.presenceText

    val conversationList by graph.conversations.items.collectAsStateWithLifecycle()
    val groupCount = conversationList.count { it.isGroup }
    var showPresence by remember { mutableStateOf(false) }
    var showLikes by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(colors.list)) {
        // 封面：取自「主页自定义」；没有设置时用设计稿的紫蓝渐变，高 210dp，铺在状态栏下面
        Box(Modifier.fillMaxWidth().height(210.dp)) {
            if (!data?.cover.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = ink.kuke.chat.feature.chat.Meta.imageUrl(data?.cover),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF7C6CFF), Color(0xFF4F8BFF)))))
            }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { reload() }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp + ink.kuke.chat.ui.shell.LocalBottomBarSpace.current)) {
                // 右上：设置（有更新时红点），半透明深色圆底
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.16f)).clickable { nav.go(SettingsRoute) },
                        contentAlignment = Alignment.Center,
                    ) {
                        KcIcon(KcIcons.settings, tint = Color.White, size = 22.dp, contentDescription = "设置")
                        if (updateDot) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).background(colors.unread, CircleShape))
                    }
                }
                Spacer(Modifier.height(58.dp))
                // ---- 资料卡（电脑版 SelfCard）：头像压在卡片上缘
                val cardShape = RoundedCornerShape(20.dp)
                Column(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().shadow(3.dp, cardShape, clip = false).background(colors.panel, cardShape)
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.requiredSize(86.dp).offset(y = (-36).dp)) {
                            Box(Modifier.shadow(4.dp, CircleShape).background(colors.panel, CircleShape).padding(3.dp)) {
                                Avatar(avatar, name, tone = userId, size = 80.dp, frame = ink.kuke.chat.feature.shop.rememberAvatarFrame(userId, sessionUser?.cosmetics))
                            }
                            Box(
                                Modifier.align(Alignment.BottomEnd).offset(x = (-3).dp, y = (-3).dp).size(18.dp).background(colors.panel, CircleShape).padding(3.dp)
                                    .background(presenceColor(presenceStatus), CircleShape),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // 谁赞了我：红色淡底的小按钮
                        Row(
                            Modifier.align(Alignment.Bottom).height(32.dp).clip(RoundedCornerShape(10.dp)).background(colors.danger.copy(alpha = 0.08f)).clickable { showLikes = true }.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KcIcon(KcIcons.heart, tint = colors.danger, size = 16.dp)
                            Text(
                                me?.profileLikeCount?.toString() ?: "–",
                                style = type.secondary, color = colors.danger, fontWeight = FontWeight.W700, modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.W800, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (level > 0) LevelIcons(level, Modifier, size = 14.dp, compact = true)
                        me?.juryBadge?.let { badge -> JuryLabels[badge]?.let { Pill(it, colors.accent) } }
                        if (!me?.ccwStudentOid.isNullOrBlank()) Pill("CCW", colors.ok)
                    }
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "@$username · ID $userId",
                            style = type.caption, color = colors.muted, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { context.copyToClipboard("用户 ID", userId.toString()); toast.show("已复制用户 ID", scope) },
                        )
                        KcIcon(KcIcons.copy, tint = colors.subtle, size = 14.dp)
                        // 在线状态切换：22dp 高的小胶囊
                        Row(
                            Modifier.height(22.dp).clip(RoundedCornerShape(99.dp)).background(colors.text.copy(alpha = 0.06f)).clickable { showPresence = true }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.dp).background(presenceColor(presenceStatus), CircleShape))
                            Text(presenceLabel(presenceStatus, presenceText), style = type.caption.copy(fontSize = type.caption.fontSize * (11.5f / 12f)), color = colors.muted, modifier = Modifier.padding(horizontal = 5.dp), maxLines = 1)
                            KcIcon(KcIcons.chevronDown, tint = colors.subtle, size = 12.dp)
                        }
                    }
                    val bio = me?.bio?.takeIf { it.isNotBlank() } ?: sessionUser?.bio?.takeIf { it.isNotBlank() }
                    Text(
                        bio ?: "还没有签名，去设置里写一句吧",
                        style = type.body.copy(fontSize = type.body.fontSize * (14f / 15f), lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                        color = if (bio == null) colors.subtle else colors.text,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // 数据：动态 / 获赞 / 好友 / 群聊
                    Row(Modifier.padding(top = 12.dp).fillMaxWidth().drawBehind { drawRect(colors.border, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }.padding(top = 12.dp)) {
                        listOf(
                            "动态" to data?.posts, "获赞" to data?.likes, "好友" to data?.friends, "群聊" to groupCount,
                        ).forEach { (label, value) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value?.let { compactCount(it) } ?: "–", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.W700)
                                Text(label, style = type.caption.copy(fontSize = type.caption.fontSize * (11.5f / 12f)), color = colors.muted)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("编辑资料", KcIcons.edit) { nav.go(SettingsPageRoute("profile")) }, Triple("我的主页", KcIcons.home) { nav.go(UserRoute(userId)) }).forEach { (label, icon, click) ->
                            Row(
                                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.panel)
                                    .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp)).clickable(onClick = click),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KcIcon(icon, tint = colors.text, size = 16.dp)
                                Text(label, style = type.secondary, color = colors.text, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }

                Column(Modifier.padding(horizontal = 12.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ---- 在线人数
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel)
                        .clickable { nav.go(OnlineUsersRoute) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(colors.ok, CircleShape))
                    Text(
                        data?.online?.let { "当前在线 $it 人" } ?: "当前在线",
                        style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
                }

                // ---- 功能宫格
                val features = order.drop(2).mapNotNull { NavFeatures.byId(it) }.filter { it.route != null }
                val cells = features.map { feature ->
                    GridCell(
                        label = feature.label,
                        icon = feature.icon(),
                        badge = when (feature.id) { "jury" -> data?.juryPending ?: 0; "tasks" -> data?.tasksDue ?: 0; else -> 0 },
                        dot = false,
                        tint = featureTint(feature.id),
                        onClick = { feature.route?.let { nav.go(it) } },
                    )
                } + GridCell("设置", KcIcons.settings, 0, updateDot, tint = Color(0xFF6B7385)) { nav.go(SettingsRoute) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.panel).padding(vertical = 8.dp, horizontal = 4.dp)) {
                    cells.chunked(4).forEach { rowCells ->
                        Row(Modifier.fillMaxWidth()) {
                            rowCells.forEach { cell -> Box(Modifier.weight(1f)) { GridItem(cell) } }
                            repeat(4 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                // ---- 后台接收消息
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel)
                        .clickable { if (onKeepAlive != null) onKeepAlive() else nav.go(KeepAliveRoute) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.bellRing, tint = colors.accent, size = 20.dp)
                    Text("后台接收消息", style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Text(if (keepReady) "已设置" else "需要设置", style = type.caption, color = if (keepReady) colors.ok else colors.unread)
                    KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp, modifier = Modifier.padding(start = 4.dp))
                }
                }
            }
        }
        ToastHost(toast)
    }

    if (showPresence) {
        PresenceSheet(
            current = presenceStatus ?: "online",
            currentText = presenceText.orEmpty(),
            onDismiss = { showPresence = false },
            onPick = { status, text ->
                showPresence = false
                scope.launch {
                    runCatching {
                        graph.api.patch("/users/me/presence", buildJsonObject {
                            put("presence_status", status)
                            if (text.isNotBlank()) put("presence_text", text.trim())
                        })
                    }.onSuccess {
                        runCatching { graph.session.updateUser(KcJson.decodeFromJsonElement(User.serializer(), it)) }
                        reload()
                    }.onFailure { toast.show(it.userMessage(), scope) }
                }
            },
        )
    }
    if (showLikes) {
        LikesSheet(graph, onDismiss = { showLikes = false }, onOpenUser = { showLikes = false; nav.go(UserRoute(it)) })
    }
}

@Composable
internal fun presenceColor(status: String?): Color {
    val c = KcTheme.colors
    return when (status) {
        "busy" -> c.warn
        "dnd" -> c.danger
        "away" -> c.subtle
        "creating" -> c.accent
        else -> c.ok
    }
}

private class GridCell(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val badge: Int, val dot: Boolean, val tint: Color? = null, val onClick: () -> Unit)

/** 功能宫格的图标色（设计稿 .c-*）：每个功能一个色相，没列到的用主题强调色 */
internal fun featureTint(id: String): Color? = when (id) {
    "teamup" -> Color(0xFF1F6BFF)
    "tasks" -> Color(0xFF12A679)
    "bots" -> Color(0xFF7A55D6)
    "jury" -> Color(0xFFF0700A)
    "shop" -> Color(0xFFE64F8A)
    "home" -> Color(0xFF0B97E8)
    "favorites", "level" -> Color(0xFFE39B00)
    "announcements" -> Color(0xFFE5484D)
    "ranking" -> Color(0xFF4A5EE6)
    else -> null
}

/** 1200 → 1.2k，和设计稿的统计数字一致 */
internal fun compactCount(n: Int): String = if (n >= 1000) String.format("%.1fk", n / 1000f).replace(".0k", "k") else n.toString()

@Composable
private fun GridItem(cell: GridCell) {
    val colors = KcTheme.colors
    val interaction = rememberInteraction()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = cell.onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            val tint = cell.tint ?: colors.accent
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                KcIcon(cell.icon, tint = tint, size = 22.dp)
            }
            if (cell.badge > 0) UnreadBadge(cell.badge, modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
            else if (cell.dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(10.dp).background(colors.unread, CircleShape).border(2.dp, colors.panel, CircleShape))
        }
        Text(cell.label, style = KcTheme.type.caption, color = colors.text, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresenceSheet(current: String, currentText: String, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var customText by remember { mutableStateOf(currentText) }
    var custom by remember { mutableStateOf(current == "custom") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panel) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("在线状态", style = type.title, color = colors.text, modifier = Modifier.padding(bottom = 8.dp))
            PresenceOptions.forEach { option ->
                val selected = if (custom) option.key == "custom" else option.key == current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.accentSoft else Color.Transparent)
                        .clickable { if (option.key == "custom") custom = true else onPick(option.key, "") }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(presenceColor(option.key), CircleShape))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(option.label, style = type.rowTitle, color = colors.text)
                        Text(option.detail, style = type.caption, color = colors.muted)
                    }
                    if (selected) KcIcon(KcIcons.check, tint = colors.accent, size = 18.dp)
                }
            }
            if (custom) {
                Spacer(Modifier.height(8.dp))
                KcTextField(customText, { customText = it }, hint = "例如：赶作业中", maxLength = 20)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("设为当前状态", enabled = customText.isNotBlank(), onClick = { onPick("custom", customText) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LikesSheet(graph: AppGraph, onDismiss: () -> Unit, onOpenUser: (Long) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var items by remember { mutableStateOf<List<LikeItem>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { graph.api.get("/users/me/likes/received?limit=50&offset=0") as JsonObject }
            .onSuccess { obj ->
                val list = (obj["items"] as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())
                items = list.mapNotNull { runCatching { KcJson.decodeFromJsonElement(LikeItem.serializer(), it) }.getOrNull() }
                total = obj["total"]?.jsonPrimitive?.intOrNull ?: items.orEmpty().size
            }
            .onFailure { error = it.userMessage() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panel) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp).heightIn(min = 200.dp, max = 520.dp)) {
            Text("谁赞了我" + if (total > 0) " · $total" else "", style = type.title, color = colors.text, modifier = Modifier.padding(bottom = 8.dp))
            val list = items
            when {
                error != null -> Text(error!!, style = type.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 24.dp))
                list == null -> Text("正在加载…", style = type.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 24.dp))
                list.isEmpty() -> Text("还没有人赞过你的资料", style = type.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 24.dp))
                else -> LazyColumn {
                    items(list, key = { it.user.id.toString() + it.createdAt }) { item ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpenUser(item.user.id) }, verticalAlignment = Alignment.CenterVertically) {
                            Avatar(item.user.avatarUrl, item.user.displayName, tone = item.user.id, size = 40.dp)
                            Text(item.user.displayName, style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
                            if (item.today) Pill("今天", colors.accent)
                        }
                    }
                }
            }
        }
    }
}
