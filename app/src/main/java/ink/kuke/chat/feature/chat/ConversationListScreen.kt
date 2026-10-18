package ink.kuke.chat.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.realtime.ConnectionState
import ink.kuke.chat.core.ui.listTime
import ink.kuke.chat.core.ui.messagePreview
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.groupplus.PlusTierTag
import ink.kuke.chat.feature.groupplus.effectivePlusTier
import ink.kuke.chat.feature.space.ProfileDrawerState
import ink.kuke.chat.feature.space.presenceColor
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.SideEffect
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.UnreadBadge
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.AddFriendRoute
import ink.kuke.chat.ui.shell.CreateGroupRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.OnlineUsersRoute
import ink.kuke.chat.ui.shell.SearchRoute
import ink.kuke.chat.feature.user.FriendBadgeMark
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * 会话列表（设计稿 P02 / P31）：大标题「聊天」+ 在线胶囊 + 「+」，搜索框，72dp 的会话行。
 * 选中 / 按下是缩进 8dp 的圆角底，置顶行压一层 4.5% 深底；有未读时标题加粗、时间变强调色；免打扰用灰色角标。
 * 左滑露出 置顶 · 免打扰 · 删除（滑过 60% 直接触发删除，需确认）；长按弹完整菜单；输入了没发的会话行显示「[草稿] …」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(graph: AppGraph, onOpen: (Long) -> Unit, drawer: ProfileDrawerState? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val nav = LocalAppNav.current
    // 冷启动：先摆出上次存下的会话列表，再照常刷新
    remember(graph) { graph.conversations.restoreCached() }
    val items by graph.conversations.items.collectAsStateWithLifecycle()
    val loaded by graph.conversations.loaded.collectAsStateWithLifecycle()
    val connection by graph.realtime.state.collectAsStateWithLifecycle()
    val drafts by Drafts.all.collectAsStateWithLifecycle()
    val hidden by HiddenConversations.all.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var onlineCount by remember { mutableStateOf<Int?>(null) }
    var plusOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var openedSwipe by remember { mutableStateOf<Long?>(null) }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    // 有展开的左滑行 / 弹出的菜单时，右滑不拉抽屉（先让它们自己收起）
    val blockDrawer = openedSwipe != null || plusOpen || menuFor != null || deleting != null
    if (drawer != null) SideEffect { drawer.swipeBlocked = blockDrawer }

    LaunchedEffect(Unit) {
        Drafts.init(context)
        HiddenConversations.init(context)
        if (!loaded) graph.conversations.refresh().onFailure { error = it.userMessage() }
    }

    // 在线人数：进页面取一次，之后一分钟一次；presence.updated 事件带 online_count 时直接用
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { graph.api.get("/users/online-count").asObj()?.i("online_count") }.onSuccess { if (it != null) onlineCount = it }
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) {
        graph.realtime.events.collect { event ->
            if (event.type == "presence.updated") event.data.i("online_count")?.let { onlineCount = it }
        }
    }

    val visible = items.filter { conversation ->
        val stamp = hidden[conversation.id] ?: return@filter true
        // 隐藏之后又有新消息（updated_at 变新）就重新出现
        val hiddenAt = parseInstant(stamp)
        val updated = parseInstant(conversation.updatedAt)
        hiddenAt == null || updated == null || updated.isAfter(hiddenAt)
    }

    fun patchSettings(conversation: Conversation, body: kotlinx.serialization.json.JsonObject) {
        scope.launch {
            runCatching { graph.api.patch("/conversations/${conversation.id}/members/me/settings", body) }
                .onSuccess { graph.conversations.refresh() }
                .onFailure { toast(context, it.userMessage()) }
        }
    }

    fun setMessageSetting(conversation: Conversation, setting: String) = patchSettings(conversation, buildJsonObject {
        put("message_setting", setting)
        put("do_not_disturb", setting != "notify")
    })

    var showPresence by remember { mutableStateOf(false) }
    if (showPresence && me != null) {
        ink.kuke.chat.feature.space.PresenceSheet(
            current = me.presenceStatus ?: "online",
            currentText = me.presenceText.orEmpty(),
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
                        runCatching { graph.session.updateUser(ink.kuke.chat.core.net.KcJson.decodeFromJsonElement(ink.kuke.chat.core.model.User.serializer(), it)) }
                    }.onFailure { toast(context, it.userMessage()) }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(colors.list).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (drawer != null) 12.dp else 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                // 左上角（同 QQ）：我的头像 + 名字 + 在线状态。点头像 / 名字打开「我的」抽屉，点状态胶囊直接改状态
                if (me != null) {
                    // 左边这块占满剩下的宽度（名字长了才省略）；右边的「在线 N」和「+」靠它推到最右
                    Row(
                        Modifier.weight(1f)
                            .graphicsLayer { alpha = 1f - (drawer?.progress?.value ?: 0f) }
                            .clip(RoundedCornerShape(22.dp))
                            .then(if (drawer != null) Modifier.clickable { drawer.open() } else Modifier)
                            .padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Avatar(me.avatarUrl, me.displayName, tone = me.id, size = 40.dp)
                            Box(
                                Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp).size(14.dp)
                                    .background(colors.list, CircleShape).padding(2.5.dp)
                                    .background(if (connection == ConnectionState.Connected) presenceColor(me.presenceStatus) else colors.subtle, CircleShape),
                            )
                        }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(
                                me.displayName,
                                style = type.rowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.W700),
                                color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            // 状态胶囊：没连上时显示连接状态
                            Row(
                                Modifier.padding(top = 2.dp).clip(RoundedCornerShape(99.dp))
                                    .clickable(enabled = connection == ConnectionState.Connected) { showPresence = true }
                                    .padding(end = 4.dp, top = 1.dp, bottom = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val label = when (connection) {
                                    ConnectionState.Connected -> ink.kuke.chat.feature.space.presenceLabel(me.presenceStatus, me.presenceText)
                                    ConnectionState.Idle -> "未连接"
                                    else -> "连接中…"
                                }
                                Text(label, style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                                if (connection == ConnectionState.Connected) KcIcon(KcIcons.chevronDown, tint = colors.subtle, size = 12.dp, modifier = Modifier.padding(start = 2.dp))
                            }
                        }
                    }
                } else {
                    Text("聊天", style = type.display, color = colors.text, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                onlineCount?.let { count ->
                    Row(
                        Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.panelMuted)
                            .clickable { nav.go(OnlineUsersRoute) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(7.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(colors.ok))
                        Text("在线 $count", style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Box(
                    Modifier.padding(start = 6.dp).width(40.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (plusOpen) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent).clickable { plusOpen = true },
                    contentAlignment = Alignment.Center,
                ) { KcIcon(KcIcons.plus, tint = if (plusOpen) colors.accent else colors.text, size = 22.dp, contentDescription = "新建") }
            }
            // 搜索框：点进全局搜索页
            Row(
                Modifier
                    // 设计稿 .field：高 44、15sp，下方留 6dp
                    .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.panelMuted)
                    .clickable { nav.go(SearchRoute) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcIcon(KcIcons.search, tint = colors.subtle, size = 18.dp)
                Text("搜索", style = type.body.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified), color = colors.subtle, modifier = Modifier.padding(start = 8.dp))
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        graph.conversations.refresh().onFailure { error = it.userMessage() }.onSuccess { error = null }
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !loaded && items.isEmpty() && error == null -> Center("正在加载会话…")
                    visible.isEmpty() && error != null -> Center(error!!)
                    visible.isEmpty() -> Center("还没有会话，去联系人里找好友聊聊吧")
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ink.kuke.chat.ui.shell.LocalBottomBarSpace.current + 8.dp)) {
                        items(visible, key = { it.id }) { conversation ->
                            androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                            SwipeConversationRow(
                                conversation = conversation,
                                draft = drafts[conversation.id],
                                opened = openedSwipe == conversation.id,
                                onOpenedChange = { open -> openedSwipe = if (open) conversation.id else if (openedSwipe == conversation.id) null else openedSwipe },
                                onClick = { onOpen(conversation.id) },
                                onLongClick = { menuFor = conversation },
                                onPin = { patchSettings(conversation, buildJsonObject { put("pinned", !conversation.pinned) }) },
                                onMute = { setMessageSetting(conversation, if (conversation.quiet) "notify" else "silent") },
                                onDelete = { deleting = conversation },
                            )
                            }
                        }
                    }
                }
            }
        }

        // 「+」菜单：弹簧缩放，从右上角展开
        if (plusOpen) {
            PlusMenu(
                onDismiss = { plusOpen = false },
                onPick = { route ->
                    plusOpen = false
                    nav.go(route)
                },
            )
        }

        menuFor?.let { conversation ->
            ConversationMenu(
                conversation = conversation,
                onDismiss = { menuFor = null },
                onPin = {
                    menuFor = null
                    patchSettings(conversation, buildJsonObject { put("pinned", !conversation.pinned) })
                },
                onSetting = {
                    menuFor = null
                    setMessageSetting(conversation, it)
                },
                onCopyInvite = {
                    menuFor = null
                    scope.launch {
                        runCatching { graph.api.post("/invites/group/${conversation.id}").asObj()?.s("url") }
                            .onSuccess { url ->
                                if (url.isNullOrBlank()) toast(context, "邀请链接生成失败") else {
                                    copyText(context, url)
                                    toast(context, "邀请链接已复制")
                                }
                            }
                            .onFailure { toast(context, it.userMessage()) }
                    }
                },
                onDelete = {
                    menuFor = null
                    deleting = conversation
                },
            )
        }

        deleting?.let { conversation ->
            val group = conversation.isGroup
            KcConfirm(
                title = if (group) "退出群聊" else "删除会话",
                text = if (group) "退出「${conversation.name()}」后不会再收到这个群的消息。" else "会清空你这边与「${conversation.name()}」的聊天记录，并从列表移除。对方不受影响，有新消息时会重新出现。",
                confirmLabel = if (group) "退出" else "删除",
                danger = true,
                onConfirm = {
                    deleting = null
                    openedSwipe = null
                    scope.launch {
                        runCatching {
                            when {
                                group -> graph.api.delete("/conversations/${conversation.id}/members/me")
                                conversation.isTemporary -> graph.api.post("/conversations/${conversation.id}/temporary/close")
                                else -> graph.api.post("/conversations/${conversation.id}/clear")
                            }
                        }.onSuccess {
                            if (!group) HiddenConversations.hide(conversation.id, conversation.updatedAt)
                            Drafts.set(conversation.id, "")
                            graph.conversations.refresh()
                        }.onFailure { toast(context, it.userMessage()) }
                    }
                },
                onDismiss = { deleting = null },
            )
        }
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted)
    }
}

private val ActionWidth = 64.dp
private val SwipePin = Color(0xFFF59F00)
private val SwipeMute = Color(0xFF7B8394)

/**
 * 可左滑的会话行：背后三个操作 置顶 · 免打扰 · 删除；左滑过半露出，滑过行宽 60% 直接触发删除（外层弹确认）。
 * 同一时间只开一行（opened 由列表管理）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeConversationRow(
    conversation: Conversation,
    draft: String?,
    opened: Boolean,
    onOpenedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = KcTheme.colors
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val actionsPx = with(density) { (ActionWidth * 3).toPx() }
    val offset = remember { Animatable(0f) }
    var rowWidth by remember { mutableStateOf(1f) }
    var crossed by remember { mutableStateOf(false) }

    // 别的行打开时，这一行自己合上
    LaunchedEffect(opened) { if (!opened && offset.value != 0f) offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .onSizeChangedWidth { rowWidth = it.toFloat() },
    ) {
        // 背后的操作按钮：删除随着继续左滑变宽
        val shown = -offset.value
        if (shown > 0f) {
            Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(with(density) { shown.toDp() })) {
                ActionCell("置顶".takeIf { !conversation.pinned } ?: "取消置顶", SwipePin, Modifier.width(ActionWidth)) {
                    scope.launch { offset.animateTo(0f) }
                    onOpenedChange(false)
                    onPin()
                }
                ActionCell(if (conversation.quiet) "取消免打扰" else "免打扰", SwipeMute, Modifier.width(ActionWidth)) {
                    scope.launch { offset.animateTo(0f) }
                    onOpenedChange(false)
                    onMute()
                }
                ActionCell("删除", colors.danger, Modifier.weight(1f)) {
                    scope.launch { offset.animateTo(0f) }
                    onOpenedChange(false)
                    onDelete()
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .offset { androidx.compose.ui.unit.IntOffset(offset.value.roundToInt(), 0) }
                .background(colors.list)
                .pointerInput(conversation.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { crossed = false },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    -offset.value > rowWidth * 0.6f -> {
                                        offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                                        onOpenedChange(false)
                                        onDelete()
                                    }
                                    -offset.value > actionsPx * 0.45f -> {
                                        offset.animateTo(-actionsPx, spring(dampingRatio = 0.75f, stiffness = 600f))
                                        onOpenedChange(true)
                                    }
                                    else -> {
                                        offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                                        onOpenedChange(false)
                                    }
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, amount ->
                        change.consume()
                        val next = (offset.value + amount).coerceIn(-rowWidth, 0f)
                        scope.launch { offset.snapTo(next) }
                        val over = -next > rowWidth * 0.6f
                        if (over != crossed) {
                            crossed = over
                            if (over) Haptics.tick(view)
                        }
                    }
                },
        ) {
            ConversationRow(
                conversation = conversation,
                draft = draft,
                onClick = {
                    if (opened || offset.value != 0f) {
                        scope.launch { offset.animateTo(0f) }
                        onOpenedChange(false)
                    } else onClick()
                },
                onLongClick = {
                    Haptics.tick(view)
                    onLongClick()
                },
            )
        }
    }
}

/** 只关心宽度的 onSizeChanged，省得到处写 IntSize */
private fun Modifier.onSizeChangedWidth(block: (Int) -> Unit): Modifier = this.onSizeChanged { block(it.width) }

@Composable
private fun ActionCell(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = KcTheme.type.caption, color = Color.White, maxLines = 1, softWrap = false)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(conversation: Conversation, draft: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val unread = conversation.unreadCount
    val name = conversation.name()
    val avatarUrl = if (conversation.isGroup) conversation.avatarUrl else conversation.directUser?.avatarUrl ?: conversation.avatarUrl
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (conversation.pinned) colors.text.copy(alpha = 0.045f) else colors.list)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val frame = if (conversation.isGroup) null else conversation.directUser?.let { ink.kuke.chat.feature.shop.rememberAvatarFrame(it.id, it.cosmetics) }
        Avatar(avatarUrl, name, tone = conversation.directUser?.id ?: conversation.id, size = 48.dp, plusTier = conversation.effectivePlusTier, frame = frame)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 名字 + 群升级标签占左边剩下的全部宽度，时间始终贴右（之前两个 weight 平分，名字短时时间会跑到中间）
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = type.rowTitle.copy(fontWeight = if (unread > 0) FontWeight.W700 else FontWeight.W500),
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 单聊：好友徽章挂在名字后（不是好友 / 未开放时什么也不画）
                    if (!conversation.isGroup) FriendBadgeMark(conversation.directUser?.id, size = 16.dp, modifier = Modifier.padding(start = 4.dp))
                    // 单聊：好友火花（图标 + 天数，docs/sparks.md）
                    if (!conversation.isGroup) ink.kuke.chat.feature.spark.SparkMark(conversation.spark, modifier = Modifier.padding(start = 4.dp))
                    PlusTierTag(conversation.effectivePlusTier, Modifier.padding(start = 6.dp), compact = true)
                }
                Text(listTime(conversation.lastMessage?.createdAt ?: conversation.updatedAt), modifier = Modifier.padding(start = 8.dp), style = type.caption, color = if (unread > 0 && !conversation.quiet) colors.accent else colors.subtle)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preview(conversation, draft), style = type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (conversation.quiet) KcIcon(KcIcons.bellOff, tint = colors.subtle, size = 14.dp, modifier = Modifier.padding(start = 6.dp))
                if (unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    UnreadBadge(unread, quiet = conversation.quiet)
                }
            }
        }
    }
}

@Composable
private fun preview(conversation: Conversation, draft: String?) = buildAnnotatedString {
    val colors = KcTheme.colors
    // 草稿优先：有人 @ 我的提示也让位，回到会话自然会看到
    if (!draft.isNullOrBlank()) {
        withStyle(SpanStyle(color = colors.danger)) { append("[草稿] ") }
        append(draft.replace('\n', ' '))
        return@buildAnnotatedString
    }
    when {
        conversation.mentionPending == "me" -> withStyle(SpanStyle(color = colors.unread)) { append("[有人@我] ") }
        conversation.mentionPending == "all" -> withStyle(SpanStyle(color = colors.unread)) { append("[@全体成员] ") }
        conversation.redPacketPending -> withStyle(SpanStyle(color = colors.unread)) { append("[有红包] ") }
    }
    val last = conversation.lastMessage
    if (last == null) {
        append(if (conversation.isGroup) "${conversation.memberCount} 名成员" else "开始聊天吧")
        return@buildAnnotatedString
    }
    if (conversation.isGroup && last.type != "system") append("${last.senderName}：")
    append(messagePreview(last))
}

/** 「+」菜单：创建群聊 · 加好友 / 加群（扫一扫需要相机能力，先不做） */
@Composable
private fun PlusMenu(onDismiss: () -> Unit, onPick: (Any) -> Unit) {
    val colors = KcTheme.colors
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f)) }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 52.dp, end = 12.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 0f)
                    scaleX = 0.6f + 0.4f * appear.value
                    scaleY = scaleX
                    alpha = appear.value.coerceIn(0f, 1f)
                }
                .width(196.dp)
                .shadow(10.dp, RoundedCornerShape(14.dp))
                .background(colors.raised, RoundedCornerShape(14.dp))
                .padding(5.dp),
        ) {
            PlusItem("创建群聊", KcIcons.users) { onPick(CreateGroupRoute) }
            PlusItem("加好友 / 加群", KcIcons.userPlus) { onPick(AddFriendRoute) }
        }
    }
}

@Composable
private fun PlusItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        KcIcon(icon, tint = colors.muted, size = 19.dp)
        Text(label, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(start = 12.dp))
    }
}

/** 长按会话行的完整菜单：置顶、复制邀请链接（群）、接收并提醒 / 接收不提醒 / 屏蔽消息、删除 */
@Composable
private fun ConversationMenu(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onSetting: (String) -> Unit,
    onCopyInvite: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = KcTheme.colors
    KcSheet(onDismiss = onDismiss, title = conversation.name(), maxHeight = 520.dp) {
        SheetRow(if (conversation.pinned) "取消置顶" else "置顶会话", onPin, icon = KcIcons.pin)
        if (conversation.isGroup) SheetRow("复制邀请链接", onCopyInvite, icon = KcIcons.link)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        SheetRow("接收并提醒", { onSetting("notify") }, icon = KcIcons.bell, selected = conversation.messageSetting == "notify")
        SheetRow("接收不提醒", { onSetting("silent") }, icon = KcIcons.bellOff, selected = conversation.messageSetting == "silent")
        SheetRow("屏蔽消息", { onSetting("ignore") }, icon = KcIcons.muted, selected = conversation.messageSetting == "ignore")
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        SheetRow(if (conversation.isGroup) "退出群聊" else "删除会话", onDelete, icon = KcIcons.trash, danger = true)
        Box(Modifier.height(8.dp))
    }
}
