package ink.kuke.chat.feature.chat

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import ink.kuke.chat.ui.components.softShadow
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.composed
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import ink.kuke.chat.ui.shell.TasksRoute
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.messagePreview
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.service.Notifier
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.gifts.GiftStageArea
import ink.kuke.chat.feature.gifts.GiftStageFullscreen
import ink.kuke.chat.feature.groupplus.FeaturedMessagesPage
import ink.kuke.chat.feature.groupplus.GroupPlusApi
import ink.kuke.chat.feature.groupplus.GroupTodoBar
import ink.kuke.chat.feature.groupplus.PlusTierTag
import ink.kuke.chat.feature.groupplus.apiCode
import ink.kuke.chat.feature.groupplus.effectivePlusTier
import ink.kuke.chat.ui.shell.ChatSearchRoute
import ink.kuke.chat.ui.shell.GroupFilesRoute
import ink.kuke.chat.ui.shell.GroupInfoRoute
import ink.kuke.chat.ui.shell.GroupPlusRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.feature.feed.asItems
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 聊天页上层叠的面板 / 页面。同一时间只有一个 */
private sealed interface Overlay {
    data object More : Overlay
    data object Search : Overlay
    data object Featured : Overlay
    data object Bookmarks : Overlay
    data class Forward(val ids: List<Long>) : Overlay
    data class Bundle(val message: Message) : Overlay
    data class Voters(val poll: PollData) : Overlay
    data class RedPacket(val meta: RedPacketMeta) : Overlay
    data object RedPacketSend : Overlay
    data object GiftSend : Overlay
    data object PollCreate : Overlay
    data object PokePick : Overlay
    data object ConfirmClear : Overlay
    data class Report(val target: ReportTarget) : Overlay
}

/**
 * 聊天页（设计稿 P03–P06）：顶栏 · 消息画布 · 浮在画布上的输入卡。
 * 往上翻到顶自动加载更早的消息；不在最底部时有新消息，右下出现「回到最新」胶囊。
 * 长按消息 → 快捷回应 + 操作宫格；右滑消息 → 引用回复；点图片 → 全屏看图；群聊双击头像 → 戳一戳。
 * 顶栏「更多」：搜索聊天记录、群精华、本会话收藏、群资料、清空聊天记录、举报。
 */
@Composable
fun ChatScreen(graph: AppGraph, conversationId: Long, onBack: () -> Unit, focusMessageId: Long? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val view = LocalView.current
    val nav = LocalAppNav.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    val controller = remember(conversationId) { ChatController(graph, conversationId, scope) }
    val listState = rememberLazyListState()
    var acting by remember { mutableStateOf<Message?>(null) }
    /** 长按的是消息里的哪张图（多图消息）；长按整条消息时为空 */
    var actingImage by remember { mutableStateOf<String?>(null) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var cardUserId by remember { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // 群文件消息卡片、「去升级」提示
    var openFile by remember { mutableStateOf<Message?>(null) }
    var upgradeHint by remember { mutableStateOf<String?>(null) }
    val staff = conversation?.myRole == "owner" || conversation?.myRole == "admin"
    val isGroup = conversation?.isGroup == true
    val messages = controller.messages
    var composerDismiss by remember { mutableIntStateOf(0) }
    val selecting = controller.selecting.value
    // 礼物动效：新到的礼物消息（实时推来的、自己刚送出的）排进来，二档画在消息区、三档全屏
    val giftStage = ink.kuke.chat.feature.gifts.rememberGiftStage()
    ink.kuke.chat.feature.gifts.GiftArrivals(giftStage, messages)

    // 被移出群 / 退群 / 解散之后，会话从列表里消失：自动退出聊天页
    var seen by remember { mutableStateOf(false) }
    if (conversation != null) seen = true
    LaunchedEffect(conversation == null) { if (seen && conversation == null) onBack() }

    DisposableEffect(conversationId) {
        Drafts.init(context)
        graph.conversations.visibleConversationId = conversationId
        Notifier.cancel(context, conversationId)
        controller.start()
        onDispose {
            controller.stop()
            if (graph.conversations.visibleConversationId == conversationId) graph.conversations.visibleConversationId = null
        }
    }

    // ---------------------------------------------------------------- 发图：多选（最多 9 张）→ 确认面板 → 乐观发送
    val imageDraft = remember { androidx.compose.runtime.mutableStateListOf<android.net.Uri>() }
    var imageCaption by remember { mutableStateOf("") }
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(ChatController.MAX_IMAGES),
    ) { uris ->
        val fresh = uris.filter { it !in imageDraft }
        val room = ChatController.MAX_IMAGES - imageDraft.size
        imageDraft.addAll(fresh.take(room))
        if (fresh.size > room) toast(context, "一次最多发 ${ChatController.MAX_IMAGES} 张")
    }
    fun pickMoreImages() = pickImages.launch(
        androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly),
    )
    // 长按发送中 / 失败的本地消息：只有「重发」「删除」
    var actingPending by remember { mutableStateOf<PendingMessage?>(null) }
    val pendingItems = controller.outbox.items
    // 上下文模式下列表尾巴不是最新，本地消息不接在后面（发送时会先回到最新）
    val pendingShown = if (controller.inContext) emptyList() else pendingItems.toList()

    // 一句话提示
    controller.notice.value?.let { text ->
        LaunchedEffect(text) {
            toast(context, text)
            controller.notice.value = null
        }
    }
    // 被戳：我是被戳的人就来一下中等震动（头像抖动在消息行里）
    val poked = controller.poked.value
    LaunchedEffect(poked) { if (poked != null && poked.userId == me?.id) Haptics.confirm(view) }

    BackHandler(enabled = selecting) { controller.stopSelecting() }

    // ---------------------------------------------------------------- 跳转到某条消息（同电脑版 jumpToMessage）
    // 目标已加载：滚到屏幕中间并闪一下；没加载：拉它前后各 25 条换掉列表、进入上下文模式，再居中 + 闪一下
    var jumping by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var flashSeq by remember { mutableIntStateOf(0) }
    var jumpJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun jumpTo(messageId: Long) {
        jumpJob?.cancel()
        jumpJob = scope.launch {
            jumping = true
            try {
                val problem = controller.jumpTo(messageId)
                if (problem != null) {
                    if (problem.isNotBlank()) toast(context, problem)
                    return@launch
                }
                if (listState.centerOn(messages, messageId)) {
                    val token = ++flashSeq
                    flash = messageId to token
                    // 闪完就清掉：不然这条滑出去再滑回来（重新进组合）会再闪一次
                    scope.launch {
                        kotlinx.coroutines.delay(1700)
                        if (flash?.second == token) flash = null
                    }
                    // 目标附近的图片陆续加载会把它挤走：稍后再对一次中（用户已经在滑就不管了）
                    kotlinx.coroutines.delay(450)
                    if (!listState.isScrollInProgress) listState.centerOn(messages, messageId)
                }
            } finally {
                jumping = false
            }
        }
    }
    /** 「回到最新」：上下文模式下换回最新一页，否则滚到底 */
    fun goLatest() {
        jumpJob?.cancel()
        scope.launch {
            if (controller.inContext) {
                controller.resetToLatest()
                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }
    // 带着目标消息进来（全局搜索、收藏）：等第一页拉完再跳
    var focusConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(focusMessageId, controller.initialLoaded.value) {
        if (focusMessageId != null && !focusConsumed && controller.initialLoaded.value) {
            focusConsumed = true
            kotlinx.coroutines.delay(80)
            jumpTo(focusMessageId)
        }
    }
    // 已经开着这个会话时，从上层页面（全局搜索）发来的跳转
    LaunchedEffect(controller) {
        snapshotFlow { ChatJumps.pending.value to controller.initialLoaded.value }.collect { (request, loaded) ->
            if (request != null && request.first == conversationId && loaded) {
                ChatJumps.pending.value = null
                jumpTo(request.second)
            }
        }
    }

    // 列表是倒着排的（最新在 index 0）：翻到最后几项就加载更早的；上下文模式下滑到底附近就往后补
    LaunchedEffect(listState) {
        snapshotFlow { ListProbe(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, listState.firstVisibleItemIndex, jumping, controller.contextEdgeId.value, controller.loadingNewer.value) }.collect { probe ->
            if (probe.jumping) return@collect
            if (messages.isNotEmpty() && probe.last >= messages.size - 5) controller.loadOlder()
            if (probe.edge != null && !probe.loadingNewer && probe.first <= 3) controller.loadNewerContext()
        }
    }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    var lastSeenNewest by remember { mutableStateOf(0L) }
    val newest = messages.lastOrNull()?.id ?: 0L
    LaunchedEffect(newest) {
        // 跳转换列表、上下文模式往后补页时不跟（底部不是真正的最新，跟下去会一路连锁加载）
        if (jumping || controller.inContext) return@LaunchedEffect
        // 在底部（或者是自己发的）时自动跟到最新；不在底部就只亮「回到最新」
        if (atBottom || messages.lastOrNull()?.senderId == me?.id) listState.animateScrollToItem(0)
        if (atBottom) lastSeenNewest = newest
    }
    LaunchedEffect(atBottom) { if (atBottom) lastSeenNewest = newest }
    // 刚点了发送：本地那条接在最末尾，贴底
    val newestPending = pendingShown.lastOrNull()?.nonce
    LaunchedEffect(newestPending) { if (newestPending != null) listState.animateScrollToItem(0) }
    // 长按菜单开着时那条已经发出去了：关掉（它已经是普通消息了）
    if (actingPending != null && pendingItems.none { it === actingPending }) actingPending = null

    // 「+1」复读：群聊里最后两条内容相同时挂在最新那条旁边
    val repeat by remember(controller, isGroup) { derivedStateOf { if (isGroup && !controller.inContext) repeatTarget(controller.messages) else null } }
    // 聊天里点到邀请链接：进应用内的邀请页
    val inviteUriHandler = remember(uriHandler, nav) { InviteAwareUriHandler(uriHandler) { token -> nav.go(ink.kuke.chat.ui.shell.InviteRoute(token)) } }

    // 群成员的角色（群主 / 管理员）和名字：身份标签、机器人消息里的 <at id/> 要用。
    // 消息不带发送者角色，只能从成员列表来：先用上次缓存的画（MemberDirectory，内存 + 磁盘），进聊天再拉一次覆盖；拉不到就留着缓存
    val members by androidx.compose.runtime.produceState(
        remember(conversationId) { MemberDirectory.cached(graph, conversationId).orEmpty() }, conversationId, isGroup,
    ) {
        if (!isGroup) return@produceState
        runCatching { MemberDirectory.refresh(graph, conversationId) }.onSuccess { value = it }
    }
    val memberRoles = remember(members) { members.associate { it.id to it.role } }
    val memberNames = remember(members) { members.associate { it.id to it.name } }
    val groupIdentity = remember(memberRoles, conversation?.levelTitlesEnabled, conversation?.levelTitles, me?.id, conversation?.myRole) {
        // 自己的角色以会话上的 my_role 为准（成员列表还没拉到时也对）
        val roles = if (me != null && conversation != null) memberRoles + (me.id to conversation.myRole) else memberRoles
        GroupIdentity(roles, if (conversation?.levelTitlesEnabled == true) conversation.levelTitles.orEmpty() else emptyList())
    }

    // 机器人的头像 / 名字：不弹用户名片，直接进机器人详情
    val openBot = ink.kuke.chat.feature.bots.rememberOpenBotForUser(graph)
    val callbacks = remember(controller, staff, me?.id, isGroup, openBot) {
        MessageCallbacks(
            onLongPress = {
                // 输入法还开着时，操作菜单会被键盘盖住：先收起键盘、取消输入框焦点
                focusManager.clearFocus()
                keyboardController?.hide()
                actingImage = null
                acting = it
            },
            onImageLongPress = { message, url ->
                focusManager.clearFocus()
                keyboardController?.hide()
                actingImage = url
                acting = message
            },
            onQuote = controller::quoteMessage,
            onAvatarLongPress = { message ->
                val id = message.senderId
                if (isGroup && id != null && id != me?.id && message.sender?.isBot != true) controller.mentionRequest.value = id to message.senderName
            },
            onOpenImage = { viewing = it },
            onReaction = controller::toggleReaction,
            // 点头像：用户名片由 feature/user 提供，这里先进用户主页
            // 设计稿 P30：点头像弹底部用户名片（名片里有「个人主页」入口），不再整页跳走
            onAvatar = { message ->
                val id = message.senderId
                if (id != null && id != me?.id && message.sender?.isBot == true) openBot(id) else id?.let { cardUserId = it }
            },
            onJumpToQuote = { id -> jumpTo(id) },
            onAvatarDoubleTap = { message -> message.senderId?.takeIf { it != me?.id }?.let(controller::poke) },
            onVote = { message, poll, ids -> controller.vote(message, poll.id, ids) },
            onClosePoll = { message, poll -> controller.closePoll(message, poll.id) },
            onPollVoters = { _, poll -> overlay = Overlay.Voters(poll) },
            canClosePoll = { message -> message.senderId == me?.id || (isGroup && staff) },
            onOpenRedPacket = { _, meta -> overlay = Overlay.RedPacket(meta) },
            redPacketState = { id -> controller.redPackets[id] },
            loadRedPacket = controller::loadRedPacket,
            myId = me?.id,
            onOpenBundle = { overlay = Overlay.Bundle(it) },
            onOpenShare = { message -> shareRouteOf(message)?.let(nav::go) },
            onOpenFile = { openFile = it },
            onButton = { message, button ->
                if (button.action == "open") button.href?.let { runCatching { inviteUriHandler.openUri(it) } }
                else controller.interact(message, button)
            },
            onToggleSelect = controller::toggleSelected,
        )
    }

    // 本地消息（发送中 / 失败）：不能引用、回应、转发；长按只给「重发」「删除」，点图照常看大图
    val pendingCallbacks = remember(controller) {
        MessageCallbacks(
            onLongPress = { message ->
                focusManager.clearFocus()
                keyboardController?.hide()
                actingPending = controller.outbox.items.firstOrNull { it.preview.id == message.id }
            },
            onQuote = {},
            onOpenImage = { viewing = it },
            onReaction = { _, _ -> },
            onAvatar = { message -> message.senderId?.let { cardUserId = it } },
            onJumpToQuote = { id -> jumpTo(id) },
        )
    }
    // 发出去以后：上下文模式下先回到最新，否则贴底
    val afterSend: () -> Unit = { if (controller.inContext) goLatest() else scope.launch { listState.animateScrollToItem(0) } }

    androidx.compose.runtime.CompositionLocalProvider(LocalUriHandler provides inviteUriHandler, LocalMemberNames provides memberNames) {
    Box(Modifier.fillMaxSize()) {
        // 长按菜单打开时下层模糊（设计稿 P04：backdrop-filter blur 6）
        Column(Modifier.fillMaxSize().blur(if (acting != null) 6.dp else 0.dp).background(colors.chat).imePadding()) {
            if (selecting) {
                SelectionTopBar(controller.selected.size, onCancel = controller::stopSelecting)
            } else {
                ChatTopBar(
                    conversation, onBack, onMore = { overlay = Overlay.More }, onTasks = if (isGroup) ({ nav.go(ink.kuke.chat.ui.shell.GroupTasksRoute(conversationId)) }) else null, onSearch = if (!isGroup) ({ overlay = Overlay.Search }) else null,
                    onFriendship = { id -> nav.go(ink.kuke.chat.ui.shell.IntimacyRoute(id)) },
                )
            }
            // 群待办条：每群最多一条，点击跳转原消息 / 查看全文（快照）
            if (isGroup && !selecting) {
                GroupTodoBar(
                    graph = graph,
                    conversationId = conversationId,
                    initial = conversation?.groupTodo,
                    staff = staff,
                    onJump = callbacks.onJumpToQuote,
                )
            }
            // 临时会话：加好友 / 关闭 / 屏蔽。会话消失时上面的自动退出会接手；还在列表里（没刷掉）就手动退
            if (conversation != null && conversation.isTemporary && !isGroup && !selecting) {
                TempConversationBanner(graph, conversation, onLeft = {
                    if (graph.conversations.items.value.any { it.id == conversationId }) onBack()
                })
            }
            // 点一下（或开始拖动）消息区：收起键盘和表情 / 工具面板（只旁听手势，不吃掉，消息上的点击照常）
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (event.changes.any { it.changedToDown() }) composerDismiss++
                        }
                    }
                }.swipeBackGesture(onSwipeLeft = {
                    if (isGroup) nav.go(GroupInfoRoute(conversationId)) else conversation?.directUser?.let { if (it.isBot) openBot(it.id) else nav.go(UserRoute(it.id)) }
                }),
            ) {
                // 倒排（最新在前）：本地消息（发送中 / 失败）排在所有服务端消息之后。
                // key：本地那条是 "n:nonce"，换成服务端消息后沿用同一个 key（outbox.keyOf），原地替换不闪不跳
                val reversed = pendingShown.asReversed().map { ChatEntry(it.preview, it) } + messages.asReversed().map { ChatEntry(it, null) }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
                    // 设计稿 .msgs：消息之间 14dp
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(reversed, key = { _, entry -> entry.pending?.let(controller.outbox::keyOf) ?: controller.outbox.keyOf(entry.message) }) { index, entry ->
                        val message = entry.message
                        val pending = entry.pending
                        val older = reversed.getOrNull(index + 1)?.message
                        Column {
                            if (older == null || gapMinutes(older, message) >= 5) TimeDivider(message.createdAt)
                            if (message.id == controller.newDividerId.value) NewMessagesDivider()
                            val repeatHere = repeat?.takeIf { it.first == message.id }?.second
                            MessageRow(
                                message = message,
                                mine = message.senderId == me?.id,
                                isGroup = isGroup,
                                showName = isGroup,
                                callbacks = if (pending != null) pendingCallbacks else callbacks,
                                me = me,
                                identity = groupIdentity,
                                selecting = selecting && pending == null,
                                selected = message.id in controller.selected,
                                shakeToken = poked?.takeIf { it.userId == message.senderId }?.token ?: 0,
                                flashToken = flash?.takeIf { it.first == message.id }?.second ?: 0,
                                aside = repeatHere?.let { content ->
                                    {
                                        RepeatPill(enabled = true) {
                                            Haptics.tick(view)
                                            if (controller.repeat(content)) afterSend()
                                        }
                                    }
                                },
                                pending = pending,
                                onRetry = { pending?.let(controller::retryPending) },
                            )
                            if (isGameMessage(message)) GameSourceNote(message.senderId == me?.id)
                        }
                    }
                    if (controller.loadingOlder.value) item { Text("正在加载更早的消息…", style = type.caption, color = colors.subtle, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
                }
                // 上下文模式往后补页时，底边一行小字（倒排列表里加在最前面会挤动锚点，所以浮在底边上）
                if (controller.loadingNewer.value) Text(
                    "正在加载更新的消息…", style = type.caption, color = colors.subtle,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                )
                // 群公告条：设计稿里是浮在画布顶部的圆角卡（上 8 / 左右 10、高 40、圆角 12、柔影），不是通栏
                conversation?.announcement?.takeIf { it.isNotBlank() && conversation.isGroup }?.let { ann ->
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 10.dp, end = 10.dp).fillMaxWidth().height(40.dp)
                            .shadow(3.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(colors.panel)
                            .clickable { nav.go(ink.kuke.chat.ui.shell.GroupAnnouncementsRoute(conversationId)) }.padding(start = 12.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KcIcon(KcIcons.announcement, tint = colors.accent, size = 16.dp)
                        Text(
                            buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append("群公告") }; append(" · $ann") },
                            style = type.secondary.copy(fontSize = type.secondary.fontSize * (13f / 13.5f)), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
                    }
                }
                GiftStageArea(giftStage)
                // 「N 个红包未领」：右上角（有群公告卡时在它下面），点一下跳到那条红包，多个就从最早的开始逐个轮
                if (!selecting) {
                    val hasAnnouncement = conversation?.isGroup == true && !conversation.announcement.isNullOrBlank()
                    PendingRedPacketPill(
                        controller = controller,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = if (hasAnnouncement) 56.dp else 10.dp, end = 12.dp),
                        onJump = { jumpTo(it) },
                    )
                }
                // 回到最新（右下胶囊）：上下文模式下一直在，点了直接换回最新一页；平时有新消息才出现
                val showLatest = controller.inContext || (!atBottom && newest > lastSeenNewest)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showLatest && !selecting,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(200)) { it / 3 },
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) + androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(160)) { it / 3 },
                ) {
                    InkCapsule(onClick = { goLatest() }) {
                        KcIcon(KcIcons.arrowDown, tint = colors.panel, size = 14.dp)
                        Text(
                            if (controller.inContext) "回到最新" else "有新消息",
                            style = type.caption.copy(fontWeight = FontWeight.W600),
                            color = colors.panel,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
            controller.error.value?.let {
                Text(it, style = type.caption, color = colors.danger, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { controller.error.value = null })
            }
            if (controller.typing.isNotEmpty() && !selecting) {
                val names = controller.typing.values.toList()
                Text(
                    (if (names.size > 2) "${names.take(2).joinToString("、")} 等 ${names.size} 人" else names.joinToString("、")) + " 正在输入…",
                    style = type.caption,
                    color = colors.muted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            val locked = isGroup && conversation?.allMuted == true && !staff
            when {
                selecting -> SelectionBar(
                    onCopy = {
                        val text = controller.selectedMessages().joinToString("\n") { "${it.senderName}：${messagePreview(it)}" }
                        copyText(context, text)
                        toast(context, "已复制")
                        controller.stopSelecting()
                    },
                    onForward = {
                        val ids = controller.selectedMessages().map { it.id }
                        if (ids.isNotEmpty()) overlay = Overlay.Forward(ids)
                    },
                    onCancel = controller::stopSelecting,
                )
                locked -> Text(
                    "全员禁言中，管理员可以发言",
                    style = type.secondary,
                    color = colors.muted,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(14.dp),
                )
                else -> Composer(
                    controller = controller,
                    placeholder = if (isGroup) "发消息，@ 可以提醒群成员" else "发消息",
                    onSent = afterSend,
                    isGroup = isGroup,
                    canMentionAll = staff,
                    dismissToken = composerDismiss,
                    onTool = { tool ->
                        if (tool == "image") {
                            pickMoreImages()
                            return@Composer
                        }
                        overlay = when (tool) {
                            "poll" -> Overlay.PollCreate
                            "redPacket" -> Overlay.RedPacketSend
                            "gift" -> Overlay.GiftSend
                            "poke" -> Overlay.PokePick
                            else -> null
                        }
                    },
                )
            }
        }

        cardUserId?.let { id ->
            ink.kuke.chat.feature.user.UserCardSheet(graph, id, conversationId) { cardUserId = null }
        }
        // 选好的图：先确认（可去掉、继续添加、加一行说明），点发送才合成一条消息乐观发出
        if (imageDraft.isNotEmpty()) {
            val closeDraft = {
                imageDraft.clear()
                imageCaption = ""
            }
            ImageSendSheet(
                images = imageDraft.toList(),
                caption = imageCaption,
                onCaption = { imageCaption = it },
                onRemove = { imageDraft.remove(it) },
                onAdd = if (imageDraft.size < ChatController.MAX_IMAGES) ({ pickMoreImages() }) else null,
                onCancel = closeDraft,
                onSend = {
                    if (controller.sendImages(imageDraft.toList(), imageCaption)) {
                        Haptics.confirm(view)
                        afterSend()
                    }
                    closeDraft()
                },
            )
        }
        actingPending?.let { pending ->
            MessageActionsLayer(
                message = pending.preview,
                isGroup = isGroup,
                mine = true,
                staff = false,
                onReaction = {},
                onAction = { key ->
                    when (key) {
                        "retry" -> controller.retryPending(pending)
                        "delete" -> controller.discardPending(pending)
                    }
                    actingPending = null
                },
                onDismiss = { actingPending = null },
                pendingActions = buildList {
                    if (pending.failed) add(MessageAction("retry", "重发", KcIcons.refresh))
                    add(MessageAction("delete", "删除", KcIcons.trash, danger = true))
                },
            )
        }
        acting?.let { message ->
            val mine = message.senderId == me?.id
            MessageActionsLayer(
                message = message,
                isGroup = isGroup,
                mine = mine,
                staff = staff,
                imageUrl = actingImage,
                onReaction = { emoji ->
                    controller.toggleReaction(message, emoji)
                    acting = null
                },
                onAction = { key ->
                    when (key) {
                        "copy" -> copyText(context, message.content).also { toast(context, "已复制") }
                        "quote" -> controller.quoteMessage(message)
                        "bookmark" -> controller.bookmark(message)
                        "feature" -> scope.launch {
                            runCatching { graph.api.post("/conversations/$conversationId/messages/${message.id}/feature") }
                                .onFailure { error ->
                                    // 超出精华上限：服务端 409 featured_limit，提示并引导升级
                                    if (error.apiCode == "featured_limit") upgradeHint = error.userMessage()
                                    else controller.error.value = error.userMessage()
                                }
                        }
                        "todo" -> scope.launch {
                            runCatching { GroupPlusApi(graph).setTodo(conversationId, message.id) }
                                .onSuccess { toast(context, "已设为群待办，已提醒全体成员") }
                                .onFailure { controller.error.value = it.userMessage() }
                        }
                        "recall" -> controller.recall(message)
                        "delete" -> controller.deleteForMe(message)
                        "forward" -> overlay = Overlay.Forward(listOf(message.id))
                        "select" -> controller.startSelecting(message)
                        "sticker" -> (actingImage ?: Meta.imageUrl(message.content))?.let(controller::addToStickers)
                        "report" -> overlay = Overlay.Report(
                            ReportTarget(
                                type = "message",
                                targetId = message.id,
                                label = "消息：${messagePreview(message).take(48).ifBlank { "空消息" }}",
                                conversationId = conversationId,
                                messageId = message.id,
                                reportedUserId = message.senderId,
                            ),
                        )
                    }
                    acting = null
                },
                onDismiss = { acting = null },
            )
        }

        overlay?.let { current ->
            val close = { overlay = null }
            when (current) {
                Overlay.More -> MoreMenu(
                    conversation = conversation,
                    onDismiss = close,
                    onPick = { key ->
                        overlay = null
                        when (key) {
                            "search" -> nav.go(ChatSearchRoute(conversationId))
                            "files" -> nav.go(GroupFilesRoute(conversationId))
                            "plus" -> nav.go(GroupPlusRoute(conversationId))
                            "featured" -> overlay = Overlay.Featured
                            "bookmarks" -> overlay = Overlay.Bookmarks
                            "info" -> if (isGroup) nav.go(GroupInfoRoute(conversationId)) else conversation?.directUser?.let { if (it.isBot) openBot(it.id) else nav.go(UserRoute(it.id)) }
                            "intimacy" -> conversation?.directUser?.let { nav.go(ink.kuke.chat.ui.shell.IntimacyRoute(it.id)) }
                            "clear" -> overlay = Overlay.ConfirmClear
                            "report" -> overlay = Overlay.Report(reportTargetFor(conversation, conversationId, controller, me?.id))
                        }
                    },
                )
                Overlay.Search -> ChatSearchPage(controller, onBack = close, onJump = { overlay = null; jumpTo(it.id) })
                Overlay.Featured -> FeaturedMessagesPage(
                    graph = graph,
                    conversationId = conversationId,
                    staff = staff,
                    onBack = close,
                    onJump = { overlay = null; jumpTo(it) },
                    onUpgrade = {
                        overlay = null
                        nav.go(GroupPlusRoute(conversationId, reason = "featured"))
                    },
                )
                Overlay.Bookmarks -> MessageBrowsePage(
                    title = "本会话收藏",
                    emptyText = "还没有收藏的消息。长按消息可以收藏。",
                    graph = graph,
                    onBack = close,
                    onJump = { overlay = null; jumpTo(it.id) },
                    load = { parseMessagesLoose(graph.api.get("/conversations/$conversationId/bookmarks")) },
                )
                is Overlay.Bundle -> BundleDetailPage(current.message, onBack = close, onOpenImage = { viewing = it })
                is Overlay.Forward -> ForwardTargetSheet(controller, current.ids.size, onDismiss = close) { target, note ->
                    scope.launch {
                        if (controller.forward(target, current.ids, note)) {
                            controller.stopSelecting()
                            overlay = null
                        }
                    }
                }
                is Overlay.Voters -> PollVotersSheet(controller, current.poll, onDismiss = close)
                is Overlay.RedPacket -> RedPacketOpenSheet(controller, current.meta, onDismiss = close)
                Overlay.RedPacketSend -> RedPacketSendSheet(
                    controller,
                    isGroup,
                    // 全员禁言（群主 / 管理员不受限）时表单只读；个人禁言由服务端的 muted 错误兜住
                    mutedReason = if (isGroup && conversation?.allMuted == true && !staff) "群里开了全员禁言，暂时不能发红包" else null,
                    onDismiss = close,
                )
                Overlay.GiftSend -> GiftSheet(controller, conversation, onDismiss = close)
                Overlay.PollCreate -> PollCreateSheet(controller, onDismiss = close)
                Overlay.PokePick -> MemberPickSheet(controller, title = "戳一戳谁", excludeMe = true, onDismiss = close) {
                    controller.poke(it.userId)
                    overlay = null
                }
                Overlay.ConfirmClear -> KcConfirm(
                    title = "清空聊天记录",
                    text = "只清空你这边的聊天记录，对方不受影响。",
                    confirmLabel = "清空",
                    danger = true,
                    onConfirm = {
                        controller.clearHistory {}
                        overlay = null
                    },
                    onDismiss = close,
                )
                is Overlay.Report -> ReportSheet(controller, current.target, onDismiss = close)
            }
        }
        // 群公告：进群弹窗（逐条；需确认的点「确认收到」）
        ink.kuke.chat.feature.groupannouncement.AnnouncementPopupHost(graph, conversationId, enabled = isGroup)
        GiftStageFullscreen(giftStage)
        viewing?.let { ImageViewer(it) { viewing = null } }
        // 文件卡片的保留状态以群文件列表为准（转永久后快照里的 expires_at 会让卡片误报「已过期」）
        val hasFileMessages = messages.any { it.metadata?.o("file") != null }
        var fileTick by remember(conversationId) { mutableStateOf(0) }
        LaunchedEffect(conversationId) {
            graph.realtime.events.collect { event ->
                if (event.type == "group.file.updated") fileTick++
            }
        }
        LaunchedEffect(conversationId, hasFileMessages, fileTick) {
            if (hasFileMessages) {
                runCatching { ink.kuke.chat.feature.groupplus.GroupPlusApi(graph).filesForCards(conversationId) }
                    .onSuccess { ink.kuke.chat.feature.chat.LiveFiles.apply(it) }
            }
        }
        openFile?.let { message ->
            val meta = fileMetaOf(message)
            if (meta != null) {
                ink.kuke.chat.feature.groupfiles.FileMessageSheet(graph, conversationId, meta.id, meta.name, meta.size, meta.isExpired(), meta.contentType, onDismiss = { openFile = null })
            } else {
                openFile = null
            }
        }
        upgradeHint?.let { text ->
            KcConfirm(
                title = "精华已达上限",
                text = text,
                confirmLabel = if (staff) "去升级" else "知道了",
                onConfirm = {
                    upgradeHint = null
                    if (staff) nav.go(GroupPlusRoute(conversationId, reason = "featured"))
                },
                onDismiss = { upgradeHint = null },
            )
        }
    }
    }
}

/** 举报入口的目标：群聊举报群，私聊举报对方（并把对方在本会话里发过的消息列成可勾选的证据） */
private fun reportTargetFor(conversation: Conversation?, conversationId: Long, controller: ChatController, myId: Long?): ReportTarget {
    if (conversation?.isGroup == true) {
        return ReportTarget("conversation", conversationId, "群聊：${conversation.name()}", conversationId)
    }
    val other = conversation?.directUser
    val otherId = other?.id ?: 0L
    return ReportTarget(
        type = "user",
        targetId = otherId,
        label = "用户：${other?.displayName ?: "对方"}",
        conversationId = conversationId,
        reportedUserId = otherId,
        evidence = controller.messages.filter { it.senderId == otherId && it.senderId != myId && !it.isRecalled && it.type != "system" }.takeLast(20).reversed(),
    )
}

@Composable
private fun ChatTopBar(conversation: Conversation?, onBack: () -> Unit, onMore: () -> Unit, onTasks: (() -> Unit)? = null, onSearch: (() -> Unit)? = null, onFriendship: (Long) -> Unit = {}) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = rememberInteraction()
            Box(Modifier.size(44.dp).pressScale(back, 0.9f).clip(CircleShape).clickable(interactionSource = back, indication = null, onClick = onBack), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.chevronLeft, tint = colors.text, size = 24.dp, contentDescription = "返回")
            }
            // 设计稿 P32：私聊顶栏在名字前带 36dp 头像
            conversation?.takeIf { !it.isGroup }?.directUser?.let { u ->
                Box(Modifier.padding(horizontal = 2.dp)) { ink.kuke.chat.ui.components.Avatar(u.avatarUrl, u.displayName, u.id, 36.dp) }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(conversation?.name() ?: "", style = type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // 群升级档位后缀（Pro 蓝 / Pro+ 紫 / MAX 金）
                    if (conversation != null && conversation.isGroup) PlusTierTag(conversation.effectivePlusTier, Modifier.padding(start = 6.dp))
                    // 单聊：名字后的好友徽章，点它进「我们的友谊」（不是好友 / 未开放时不画、也不占点击区）
                    val peer = conversation?.takeIf { !it.isGroup }?.directUser?.id
                    if (peer != null && ink.kuke.chat.feature.user.IntimacyCache.bonds.collectAsState().value[peer]?.badge != null) {
                        Box(Modifier.padding(start = 2.dp).size(28.dp).clip(CircleShape).clickable { onFriendship(peer) }, contentAlignment = Alignment.Center) {
                            ink.kuke.chat.feature.user.FriendBadgeMark(peer, size = 18.dp)
                        }
                    }
                }
                val sub = when {
                    conversation == null -> ""
                    conversation.isGroup -> buildString {
                        append("${conversation.memberCount} 名成员")
                        if (conversation.allMuted) append(" · 全员禁言")
                        if (conversation.rateLimit > 0) append(" · 每分钟 ${conversation.rateLimit} 条")
                        if (conversation.quiet) append(" · 免打扰")
                    }
                    conversation.isTemporary -> "临时会话"
                    else -> conversation.directUser?.presenceText ?: conversation.directUser?.profileStatus ?: ""
                }
                if (sub.isNotBlank()) Text(sub, style = type.caption, color = colors.muted, maxLines = 1)
            }
            // 设计稿 P03：群聊顶栏在「更多」前多一个「群任务」入口
            if (onSearch != null) {
                val search = rememberInteraction()
                Box(
                    Modifier.size(44.dp).pressScale(search, 0.9f).clip(CircleShape).clickable(interactionSource = search, indication = null, onClick = onSearch),
                    contentAlignment = Alignment.Center,
                ) { KcIcon(KcIcons.search, tint = colors.text, size = 22.dp, contentDescription = "搜索聊天记录") }
            }
            if (onTasks != null) {
                val tasks = rememberInteraction()
                Box(
                    Modifier.size(44.dp).pressScale(tasks, 0.9f).clip(CircleShape).clickable(interactionSource = tasks, indication = null, onClick = onTasks),
                    contentAlignment = Alignment.Center,
                ) { KcIcon(KcIcons.checkSquare, tint = colors.text, size = 22.dp, contentDescription = "群任务") }
            }
            val more = rememberInteraction()
            Box(
                Modifier.size(44.dp).pressScale(more, 0.9f).clip(CircleShape).clickable(interactionSource = more, indication = null, onClick = onMore),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.more, tint = colors.text, size = 22.dp, contentDescription = "更多") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** 多选模式的顶栏：「已选择 N 条」+ 取消 */
@Composable
private fun SelectionTopBar(count: Int, onCancel: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选择 $count 条", style = KcTheme.type.title, color = colors.text, modifier = Modifier.weight(1f))
            Text("取消", style = KcTheme.type.rowTitle, color = colors.accent, modifier = Modifier.clickable(onClick = onCancel).padding(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** 多选模式的底栏：复制 · 转发 · 取消 */
@Composable
private fun SelectionBar(onCopy: () -> Unit, onForward: () -> Unit, onCancel: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.panel).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            listOf(Triple("复制", KcIcons.copy, onCopy), Triple("转发", KcIcons.forward, onForward), Triple("取消", KcIcons.close, onCancel)).forEach { (label, icon, action) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = action).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    KcIcon(icon, tint = colors.text, size = 22.dp)
                    Text(label, style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/**
 * 「更多」菜单：从右上角「…」处弹出的浮层卡片（弹簧缩放 + 淡入，收起时缩回去）。
 * 上面一排是群功能宫格（聊天记录 · 群精华 · 群文件 · 群升级），中间是常用项，最下面一组是清空 / 举报。
 */
@Composable
private fun MoreMenu(conversation: Conversation?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val isGroup = conversation?.isGroup == true
    val bonds by ink.kuke.chat.feature.user.IntimacyCache.bonds.collectAsState()
    val friendBond = !isGroup && conversation?.directUser?.id?.let { bonds[it] } != null
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by androidx.compose.animation.core.animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 520f) else androidx.compose.animation.core.tween(130),
        label = "moreMenu",
    )
    fun close(then: () -> Unit) {
        if (!shown) return
        shown = false
        scope.launch {
            kotlinx.coroutines.delay(130)
            then()
        }
    }
    BackHandler { close(onDismiss) }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.14f * progress.coerceIn(0f, 1f))) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close(onDismiss) },
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 50.dp, end = 10.dp)
                .width(248.dp)
                .graphicsLayer {
                    val p = progress
                    scaleX = 0.82f + 0.18f * p
                    scaleY = 0.78f + 0.22f * p
                    alpha = p.coerceIn(0f, 1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.92f, 0f)
                }
                .softShadow(blur = 18.dp, offsetY = 6.dp, color = Color.Black.copy(alpha = 0.16f), corner = 18.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.raised)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(vertical = 8.dp),
        ) {
            // 功能宫格
            val tiles = buildList {
                add(Triple("聊天记录", KcIcons.search, "search") to Color(0xFF1F6BFF))
                if (isGroup) {
                    add(Triple("群精华", KcIcons.star, "featured") to Color(0xFFF2A41C))
                    add(Triple("群文件", KcIcons.folder, "files") to Color(0xFF12A679))
                    add(Triple("群升级", KcIcons.rocket, "plus") to Color(0xFF8B5CF6))
                } else {
                    add(Triple("收藏", KcIcons.bookmark, "bookmarks") to Color(0xFFF2A41C))
                    add(Triple("资料", KcIcons.info, "info") to Color(0xFF12A679))
                    // 好友且服务端有亲密度功能时多一格「我们的友谊」
                    if (friendBond) add(Triple("友谊", KcIcons.heartHandshake, "intimacy") to Color(0xFFF0507A))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                tiles.forEachIndexed { i, (item, tint) ->
                    val (label, icon, key) = item
                    val press = rememberInteraction()
                    // 宫格依次弹出一点点（错开 40ms 的感觉用进度偏移来做）
                    val local = ((progress - i * 0.06f) / (1f - i * 0.06f)).coerceIn(0f, 1.2f)
                    Column(
                        Modifier
                            .weight(1f)
                            .graphicsLayer { translationY = (1f - local.coerceAtMost(1f)) * 10.dp.toPx() }
                            .pressScale(press)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(interactionSource = press, indication = null) { close { onPick(key) } }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            KcIcon(icon, tint = tint, size = 21.dp)
                        }
                        Text(label, style = type.caption.copy(fontWeight = FontWeight.W500), color = colors.text, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    }
                }
            }
            MoreDivider()
            if (isGroup) {
                MoreItem("本会话收藏", KcIcons.bookmark) { close { onPick("bookmarks") } }
                MoreItem("群资料", KcIcons.info) { close { onPick("info") } }
                MoreDivider()
            }
            MoreItem("清空聊天记录", KcIcons.trash, danger = true) { close { onPick("clear") } }
            MoreItem(if (isGroup) "举报群聊" else "举报", KcIcons.flag, danger = true) { close { onPick("report") } }
        }
    }
}

@Composable
private fun MoreDivider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(KcTheme.colors.border))
}

@Composable
private fun MoreItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(44.dp).clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(color = colors.text), onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(icon, tint = if (danger) colors.danger else colors.muted, size = 19.dp)
        Text(label, style = KcTheme.type.secondary.copy(fontSize = KcTheme.type.rowTitle.fontSize * 0.94f), color = if (danger) colors.danger else colors.text, modifier = Modifier.padding(start = 12.dp))
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun TimeDivider(iso: String) {
    val instant = parseInstant(iso) ?: return
    val time = instant.atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val label = when (time.toLocalDate()) {
        today -> "今天 ${time.format(clockFormat)}"
        today.minusDays(1) -> "昨天 ${time.format(clockFormat)}"
        else -> time.format(dayFormat)
    }
    Text(label, style = KcTheme.type.caption, color = KcTheme.colors.subtle, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = TextAlign.Center)
}

/** 「以下是新消息」：进入会话时的第一条未读上方 */
@Composable
private fun NewMessagesDivider() {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderStrong))
        Text("以下是新消息", style = KcTheme.type.caption, color = colors.accent, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderStrong))
    }
}

private fun gapMinutes(older: Message, newer: Message): Long {
    val a = parseInstant(older.createdAt) ?: return 0
    val b = parseInstant(newer.createdAt) ?: return 0
    return java.time.Duration.between(a, b).toMinutes()
}

/**
 * 聊天区向右划 = 返回（同 QQ，不必从屏幕边缘起手）；向左划 = 打开资料（[onSwipeLeft]，群资料 / 对方主页）。
 * 向右划：不自己画动画：把手势当成一次系统侧滑返回发给返回分发器，
 * 走和边缘侧滑完全一样的预览（页面缩小、左侧箭头、过阈值震动、松手滑出 / 弹回）。
 * 向左划：划过屏幕宽度的 22%（或快速甩一下）松手就打开，过阈值时轻震一下；起手时就决定方向，中途不换。
 * 按在消息上起手的横划归消息（引用），不会走到这里。
 */
private fun Modifier.swipeBackGesture(onSwipeLeft: () -> Unit): Modifier = composed {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val openInfo by androidx.compose.runtime.rememberUpdatedState(onSwipeLeft)
    val context = androidx.compose.ui.platform.LocalContext.current
    val dispatcher = remember(context) {
        var c = context
        while (c is android.content.ContextWrapper && c !is androidx.activity.ComponentActivity) c = c.baseContext
        (c as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher
    }
    if (dispatcher == null) return@composed this
    pointerInput(dispatcher) {
        val width = size.width.toFloat().coerceAtLeast(1f)
        var dx = 0f
        var y = 0f
        var started = false
        // 向左划（打开资料）：left 是这一下是不是向左起手的，leftArmed 是过没过阈值
        var left = false
        var leftDx = 0f
        var leftArmed = false
        var lastTime = 0L
        var velocity = 0f
        fun event(progress: Float) = androidx.activity.BackEventCompat(dx, y, progress, androidx.activity.BackEventCompat.EDGE_LEFT)
        detectHorizontalDragGestures(
            onDragStart = { offset -> dx = 0f; y = offset.y; started = false; left = false; leftDx = 0f; leftArmed = false; velocity = 0f; lastTime = System.nanoTime() },
            onDragEnd = {
                if (left) {
                    if (leftArmed || (velocity < -1.2f && -leftDx > width * 0.08f)) openInfo()
                    left = false
                }
                if (started) {
                    val progress = (dx / width).coerceIn(0f, 1f)
                    if (progress > 0.35f || (velocity > 1.2f && progress > 0.08f)) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
                }
                started = false
            },
            onDragCancel = { if (started) dispatcher.dispatchOnBackCancelled(); started = false; left = false },
        ) { change, amount ->
            val now0 = System.nanoTime()
            if (left || (!started && amount < 0f && dx == 0f)) {
                left = true
                change.consume()
                val dt = ((now0 - lastTime) / 1_000_000f).coerceAtLeast(1f)
                velocity = 0.7f * velocity + 0.3f * (amount / dt)
                lastTime = now0
                leftDx = (leftDx + amount).coerceAtMost(0f)
                val armed = -leftDx >= width * 0.22f
                if (armed && !leftArmed) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                leftArmed = armed
                return@detectHorizontalDragGestures
            }
            if (!started && amount <= 0f) return@detectHorizontalDragGestures
            if (!started) {
                if (!dispatcher.hasEnabledCallbacks()) return@detectHorizontalDragGestures
                started = true
                dispatcher.dispatchOnBackStarted(event(0f))
            }
            change.consume()
            val now = System.nanoTime()
            val dt = ((now - lastTime) / 1_000_000f).coerceAtLeast(1f)
            velocity = 0.7f * velocity + 0.3f * (amount / dt)  // px/ms
            lastTime = now
            dx = (dx + amount).coerceAtLeast(0f)
            dispatcher.dispatchOnBackProgressed(event((dx / width).coerceIn(0f, 1f)))
        }
    }
}
