package ink.kuke.chat.feature.space

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.appearance.AppearanceStore
import ink.kuke.chat.feature.appearance.DarkMode
import ink.kuke.chat.feature.chat.Meta
import ink.kuke.chat.feature.level.LevelIcons
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
import ink.kuke.chat.ui.shell.AnnouncementsRoute
import ink.kuke.chat.ui.shell.BackFx
import ink.kuke.chat.ui.shell.FavoritesRoute
import ink.kuke.chat.ui.shell.JuryRoute
import ink.kuke.chat.ui.shell.KeepAliveRoute
import ink.kuke.chat.ui.shell.LevelRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.SEEK_SCALE
import ink.kuke.chat.ui.shell.SettingsPageRoute
import ink.kuke.chat.ui.shell.SettingsRoute
import ink.kuke.chat.ui.shell.RedPacketRecordsRoute
import ink.kuke.chat.ui.shell.ShopRoute
import ink.kuke.chat.ui.shell.TasksRoute
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/** 抽屉最后是开着还是关着（进程内）：从抽屉进二级页面再返回时恢复 */
private var drawerLeftOpen = false

/** 开合用的弹簧：临界阻尼不回弹，约 300ms 落定；松手时带上手指速度 */
private val Settle = spring<Float>(dampingRatio = 1f, stiffness = 380f)

/** 松手速度超过「每秒 1 个抽屉宽」就按方向开 / 关，否则看停在哪 */
private const val FLING = 1f

/**
 * 「我的」侧边抽屉的开合状态：progress 0 = 收起，1 = 全开。拖动时跟手，松手按速度和位置决定开还是关。
 * 用 [rememberProfileDrawerState] 拿；聊天 tab 的头像 / 右滑调 [open]，抽屉里跳页面时调 [close]。
 */
@Stable
class ProfileDrawerState internal constructor(private val scope: CoroutineScope) {
    // 推入二级页面时底栏外壳会离开组合；回来时按上次的开合状态直接摆好（不重播动画）
    internal val progress = Animatable(if (drawerLeftOpen) 1f else 0f).apply { updateBounds(0f, 1f) }
    internal var widthPx by mutableFloatStateOf(1f)
    private var dragTarget = 0f

    /** 目标状态是打开（拖动途中不算） */
    var isOpen by mutableStateOf(drawerLeftOpen)
        private set

    /** 会话列表有展开的左滑行、弹出的菜单时置 true：这时右滑交给列表自己 */
    var swipeBlocked by mutableStateOf(false)

    internal val visible: Boolean get() = isOpen || progress.value > 0f

    fun open(velocity: Float = 0f) {
        isOpen = true
        drawerLeftOpen = true
        scope.launch { progress.animateTo(1f, Settle, velocity) }
    }

    fun close(velocity: Float = 0f) {
        isOpen = false
        drawerLeftOpen = false
        scope.launch { progress.animateTo(0f, Settle, velocity) }
    }

    internal fun snapClosed() {
        isOpen = false
        drawerLeftOpen = false
        scope.launch { progress.snapTo(0f) }
    }

    internal fun dragStart() { dragTarget = progress.value }

    /** 自己记目标值再 snap：连续几次事件之间 progress 可能还没更新，直接读会丢位移 */
    internal fun dragBy(dx: Float) {
        dragTarget = (dragTarget + dx / widthPx).coerceIn(0f, 1f)
        // 同一帧里的多次移动只发一次 snap（每次都开协程排队会让画面落后、偶尔跳一下）
        if (snapJob?.isActive == true) return
        snapJob = scope.launch {
            var applied = Float.NaN
            while (applied != dragTarget) {
                applied = dragTarget
                progress.snapTo(applied)
            }
        }
    }
    private var snapJob: kotlinx.coroutines.Job? = null

    /** 松手：[velocity] 是 px/s */
    internal fun settle(velocity: Float) {
        val v = velocity / widthPx
        val toOpen = when {
            v > FLING -> true
            v < -FLING -> false
            else -> dragTarget > 0.5f
        }
        if (toOpen) open(v) else close(v)
    }

    /** 预测性返回：手势进度 0..1 → 抽屉跟着往回收一段 */
    internal fun backPreview(raw: Float) {
        val target = 1f - 0.5f * raw.coerceIn(0f, 1f)
        dragTarget = target
        scope.launch { progress.snapTo(target) }
    }
}

@Composable
fun rememberProfileDrawerState(): ProfileDrawerState {
    val scope = rememberCoroutineScope()
    return remember { ProfileDrawerState(scope) }
}

/**
 * 包住整个底栏外壳（含底栏）。抽屉从左侧滑入、宽 84%（最宽 380dp），后面的页面随之右移、微缩、圆角，压一层暗色。
 * [swipeEnabled] 时在页面任意位置向右横滑就拉出抽屉（竖向滚动、左滑会话行不受影响）；
 * 打开后：横向拖动跟手关闭、点暗处关闭、系统返回关闭（侧滑返回时抽屉按手势进度往回收）。
 */
@Composable
fun ProfileDrawerHost(graph: AppGraph, state: ProfileDrawerState, swipeEnabled: Boolean, content: @Composable () -> Unit) {
    val colors = KcTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.rail)) {
        val drawerWidth = minOf(maxWidth * 0.84f, 380.dp)
        val widthPx = with(LocalDensity.current) { drawerWidth.toPx() }
        SideEffect { state.widthPx = widthPx }
        val canSwipe by rememberUpdatedState(swipeEnabled)

        Box(
            Modifier
                .fillMaxSize()
                // 手势挂在变换层外面：页面跟着位移缩放时，手指坐标不受影响
                .drawerOpenSwipe(state) { canSwipe && !state.swipeBlocked && !state.isOpen }
                .graphicsLayer {
                    val p = state.progress.value
                    translationX = widthPx * 0.3f * p
                    scaleX = 1f - 0.06f * p
                    scaleY = scaleX
                    shape = RoundedCornerShape(28.dp * p)
                    clip = p > 0.001f
                },
        ) { content() }

        // 抽屉常驻组合、收起时只是移出屏幕：每次打开不用重新排版、重新加载封面，开头那一帧不会闪
        DrawerOverlay(graph, state, drawerWidth, widthPx)

        PredictiveBackHandler(enabled = state.isOpen) { events ->
            // 抽屉自己就是返回预览，不再画外壳的返回箭头
            BackFx.gesture.value = false
            try {
                // 外壳转发给页面栈的进度乘过 SEEK_SCALE，这里还原成手势原始进度
                events.collect { state.backPreview(it.progress / SEEK_SCALE) }
                state.close()
            } catch (e: CancellationException) {
                state.open()
                throw e
            }
        }
    }
}

/** 向右横滑拉出抽屉：在 Initial 阶段先看，确认是右滑就吃掉事件（列表的竖向滚动、会话行的左滑随之取消）；竖滑、左滑一律放行 */
private fun Modifier.drawerOpenSwipe(state: ProfileDrawerState, enabled: () -> Boolean): Modifier = pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var dragging = false
        var settled = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (dragging) {
                    change.consume()
                    state.settle(tracker.calculateVelocity().x)
                    settled = true
                }
                break
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()
            if (!dragging) {
                total += delta
                when {
                    abs(total.y) > slop && abs(total.y) >= abs(total.x) -> break
                    total.x < -slop -> break
                    total.x > slop && total.x > abs(total.y) * 1.4f -> {
                        dragging = true
                        state.dragStart()
                        state.dragBy(total.x - slop)
                    }
                }
            } else {
                state.dragBy(delta.x)
            }
            if (dragging) change.consume()
        }
        if (dragging && !settled) state.settle(0f)
    }
}

@Composable
private fun DrawerOverlay(graph: AppGraph, state: ProfileDrawerState, drawerWidth: androidx.compose.ui.unit.Dp, widthPx: Float) {
    val colors = KcTheme.colors
    val scrim = if (colors.isDark) 0.6f else 0.42f
    // 收起时整层不接触摸（不然会挡住下面的页面）
    val active = state.visible
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (active) Modifier.draggable(
                    state = rememberDraggableState { state.dragBy(it) },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { state.dragStart() },
                    onDragStopped = { state.settle(it) },
                ) else Modifier,
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.progress.value }
                .background(Color.Black.copy(alpha = scrim))
                .then(if (active) Modifier.clickable(interactionSource = rememberInteraction(), indication = null) { state.close() } else Modifier),
        )
        Box(
            Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    val p = state.progress.value
                    // 多移出 24dp，收起时投影也完全在屏幕外
                    translationX = -(1f - p) * (widthPx + 24.dp.toPx())
                    shadowElevation = 18.dp.toPx() * p
                    shape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
                    clip = true
                }
                .background(colors.list)
                // 挡住面板空白处的点击，免得穿到下面的暗层把抽屉关掉
                .then(if (active) Modifier.clickable(interactionSource = rememberInteraction(), indication = null) {} else Modifier),
        ) {
            DrawerPanel(graph, state)
        }
    }
}

/** 抽屉内容：封面 + 资料卡 + 功能列表 + 底部（设置 / 深浅色 / 外观）。数据沿用空间页的 loadSpace 和进程缓存 */
@Composable
private fun DrawerPanel(graph: AppGraph, state: ProfileDrawerState) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val sessionUser = graph.session.session.collectAsStateWithLifecycle().value?.user
    val conversationList by graph.conversations.items.collectAsStateWithLifecycle()
    val appearance = remember { AppearanceStore.shared(context) }

    var data by remember { mutableStateOf(spaceCache?.takeIf { it.me == null || it.me.id == sessionUser?.id }) }
    fun reload() {
        scope.launch {
            runCatching { loadSpace(graph) }.getOrNull()?.let { data = it; spaceCache = it }
        }
    }
    // 抽屉一出现（开始拉出）就静默刷新，先显示上次的缓存
    LaunchedEffect(Unit) { reload() }
    var keepTick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { keepTick++ }
    val keepReady = remember(keepTick) { KeepAlive.ready(context) }
    val (_, updateDot) = rememberUpdateBadge(graph)
    var showPresence by remember { mutableStateOf(false) }
    var showLikes by remember { mutableStateOf(false) }

    val me = data?.me
    val name = me?.displayName ?: sessionUser?.displayName.orEmpty()
    val avatar = me?.avatarUrl ?: sessionUser?.avatarUrl
    val userId = me?.id ?: sessionUser?.id ?: 0L
    val username = me?.username ?: sessionUser?.username.orEmpty()
    val level = me?.accountLevel ?: sessionUser?.accountLevel ?: 0
    val presenceStatus = me?.presenceStatus ?: sessionUser?.presenceStatus
    val presenceText = me?.presenceText ?: sessionUser?.presenceText
    val bio = me?.bio?.takeIf { it.isNotBlank() } ?: sessionUser?.bio?.takeIf { it.isNotBlank() }
    val groupCount = conversationList.count { it.isGroup }

    fun go(route: Any) {
        // 同 QQ：从抽屉打开的页面返回后，抽屉还开着，直到手动关
        nav.go(route)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                DrawerCover(data?.cover, onEdit = { go(SettingsPageRoute("profile")) })
                Column(Modifier.padding(top = 128.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // ---- 资料卡
                    val cardShape = RoundedCornerShape(22.dp)
                    Column(
                        Modifier
                            .padding(horizontal = 14.dp)
                            .fillMaxWidth()
                            .graphicsLayer {
                                shadowElevation = 6.dp.toPx()
                                shape = cardShape
                                clip = true
                            }
                            .background(colors.panel)
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                Box(Modifier.background(colors.panel, CircleShape).padding(2.dp)) {
                                    Avatar(avatar, name, tone = userId, size = 60.dp, frame = ink.kuke.chat.feature.shop.rememberAvatarFrame(userId, sessionUser?.cosmetics))
                                }
                                Box(
                                    Modifier.align(Alignment.BottomEnd).offset(x = (-1).dp, y = (-1).dp).size(16.dp)
                                        .background(colors.panel, CircleShape).padding(3.dp).background(presenceColor(presenceStatus), CircleShape),
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.W800, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (level > 0) LevelIcons(level, Modifier, size = 13.dp, compact = true)
                                }
                                Text(
                                    "@$username · ID $userId",
                                    style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp))
                                        .clickable { context.copyToClipboard("用户 ID", userId.toString()); toast.show("已复制用户 ID", scope) },
                                )
                            }
                            // 谁赞了我
                            Column(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(colors.danger.copy(alpha = 0.08f)).clickable { showLikes = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                KcIcon(KcIcons.heart, tint = colors.danger, size = 16.dp)
                                Text(me?.profileLikeCount?.let { compactCount(it) } ?: "–", style = type.caption, color = colors.danger, fontWeight = FontWeight.W700)
                            }
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 在线状态：点开切换
                            Row(
                                Modifier.height(24.dp).clip(RoundedCornerShape(99.dp)).background(colors.text.copy(alpha = 0.06f))
                                    .clickable { showPresence = true }.padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(7.dp).background(presenceColor(presenceStatus), CircleShape))
                                Text(presenceLabel(presenceStatus, presenceText), style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 5.dp), maxLines = 1)
                                KcIcon(KcIcons.chevronDown, tint = colors.subtle, size = 12.dp)
                            }
                            me?.juryBadge?.let { badge -> JuryLabels[badge]?.let { Pill(it, colors.accent) } }
                            if (!me?.ccwStudentOid.isNullOrBlank()) Pill("CCW", colors.ok)
                        }
                        Text(
                            bio ?: "还没有签名，去资料里写一句吧",
                            style = type.secondary,
                            color = if (bio == null) colors.subtle else colors.text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        // 数据：动态 / 获赞 / 好友 / 群聊
                        Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panelMuted).padding(vertical = 10.dp)) {
                            listOf(
                                Triple("动态", data?.posts) { go(UserRoute(userId)) },
                                Triple("获赞", data?.likes) { go(UserRoute(userId)) },
                                Triple("好友", data?.friends, null),
                                Triple("群聊", groupCount, null),
                            ).forEach { (label, value, click) ->
                                Column(
                                    Modifier.weight(1f).then(if (click != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = click) else Modifier),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(value?.let { compactCount(it) } ?: "–", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.W700)
                                    Text(label, style = type.caption, color = colors.muted)
                                }
                            }
                        }
                    }

                    // ---- 功能
                    MenuCard {
                        MenuRow("我的主页", KcIcons.home, Color(0xFF0B97E8)) { go(UserRoute(userId)) }
                        MenuRow("收藏", KcIcons.bookmark, featureTint("favorites")) { go(FavoritesRoute) }
                        MenuRow("商店", KcIcons.store, featureTint("shop")) { go(ShopRoute) }
                        MenuRow("红包记录", KcIcons.redPacket, Color(0xFFE5484D)) { go(RedPacketRecordsRoute) }
                        MenuRow("礼物记录", KcIcons.gift, Color(0xFFD9468F)) { go(ink.kuke.chat.ui.shell.GiftRecordsRoute) }
                        MenuRow("账号等级", KcIcons.award, featureTint("level"), value = if (level > 0) "Lv.$level" else null) { go(LevelRoute) }
                    }
                    MenuCard {
                        MenuRow("任务", KcIcons.listChecks, featureTint("tasks"), badge = data?.tasksDue ?: 0) { go(TasksRoute) }
                        val juryPending = data?.juryPending ?: 0
                        if (me?.juryBadge != null || juryPending > 0) MenuRow("风纪", KcIcons.scale, featureTint("jury"), badge = juryPending) { go(JuryRoute) }
                        MenuRow("公告", KcIcons.announcement, featureTint("announcements")) { go(AnnouncementsRoute) }
                    }
                    MenuCard {
                        MenuRow(
                            "后台接收消息", KcIcons.bellRing, colors.accent,
                            value = if (keepReady) "已设置" else "需要设置",
                            valueColor = if (keepReady) colors.ok else colors.unread,
                        ) { go(KeepAliveRoute) }
                    }
                }
            }
            // ---- 底部：设置 · 深浅色 · 外观
            val dark = colors.isDark
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.list)
                    .navigationBarsPadding()
                    .height(68.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomAction("设置", KcIcons.settings, dot = updateDot) { go(SettingsRoute) }
                BottomAction(if (dark) "日间" else "夜间", if (dark) KcIcons.sun else KcIcons.moon) {
                    appearance.update { it.copy(mode = if (dark) DarkMode.Light else DarkMode.Dark) }
                }
                BottomAction("外观", KcIcons.palette) { go(SettingsPageRoute("appearance")) }
                Spacer(Modifier.weight(1f))
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
        LikesSheet(graph, onDismiss = { showLikes = false }, onOpenUser = { showLikes = false; go(UserRoute(it)) })
    }
}

/** 封面：「主页自定义」的封面图，没有就用紫蓝渐变；顶部压一层暗色托住状态栏，底部渐隐到抽屉底色 */
@Composable
private fun DrawerCover(cover: String?, onEdit: () -> Unit) {
    val colors = KcTheme.colors
    Box(Modifier.fillMaxWidth().height(200.dp)) {
        if (!cover.isNullOrBlank()) {
            AsyncImage(model = Meta.imageUrl(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF7C6CFF), Color(0xFF4F8BFF), Color(0xFF38C6E8)))))
        }
        Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color.Transparent, colors.list))))
        // 右上：编辑资料（半透明玻璃胶囊）
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp)
                .height(30.dp).clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = 0.22f)).clickable(onClick = onEdit)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcIcon(KcIcons.edit, tint = Color.White, size = 14.dp)
            Text("编辑资料", style = KcTheme.type.caption, color = Color.White, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(KcTheme.colors.panel).padding(vertical = 4.dp),
    ) { content() }
}

/** 功能行：彩色图标方块 + 名称 + 右侧数字角标 / 状态文字 + 箭头 */
@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    tint: Color?,
    badge: Int = 0,
    value: String? = null,
    valueColor: Color? = null,
    onClick: () -> Unit,
) {
    val colors = KcTheme.colors
    val c = tint ?: colors.accent
    val interaction = rememberInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp)
            .pressScale(interaction, pressed = 0.98f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            KcIcon(icon, tint = c, size = 18.dp)
        }
        Text(label, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
        if (badge > 0) UnreadBadge(badge, modifier = Modifier.padding(end = 4.dp))
        value?.let { Text(it, style = KcTheme.type.caption, color = valueColor ?: colors.muted, modifier = Modifier.padding(end = 4.dp)) }
        KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
    }
}

@Composable
private fun BottomAction(label: String, icon: ImageVector, dot: Boolean = false, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val interaction = rememberInteraction()
    Column(
        Modifier
            .width(64.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            KcIcon(icon, tint = colors.text, size = 22.dp, contentDescription = label)
            if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(8.dp).background(colors.unread, CircleShape))
        }
        Text(label, style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}
