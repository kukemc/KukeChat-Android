package ink.kuke.chat.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import kotlin.math.sqrt
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import ink.kuke.chat.feature.shop.bubbleSkin
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.feature.shop.toBrush
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 消息行上的回调：长按（弹快捷回应 + 操作宫格）、右滑引用、点图片、点回应、点头像 */
class MessageCallbacks(
    val onLongPress: (Message) -> Unit,
    val onQuote: (Message) -> Unit,
    val onOpenImage: (String) -> Unit,
    val onReaction: (Message, String) -> Unit,
    val onAvatar: (Message) -> Unit,
    val onJumpToQuote: (Long) -> Unit,
    /** 群聊双击头像 = 戳一戳 */
    val onAvatarDoubleTap: (Message) -> Unit = {},
    /** 群聊长按头像 = 在输入框里 @ 他 */
    val onAvatarLongPress: (Message) -> Unit = {},
    val onVote: (Message, PollData, List<Long>) -> Unit = { _, _, _ -> },
    val onClosePoll: (Message, PollData) -> Unit = { _, _ -> },
    val onPollVoters: (Message, PollData) -> Unit = { _, _ -> },
    val canClosePoll: (Message) -> Boolean = { false },
    val onOpenRedPacket: (Message, RedPacketMeta) -> Unit = { _, _ -> },
    val redPacketState: (Long) -> RedPacketState? = { null },
    val loadRedPacket: (Long) -> Unit = {},
    /** 当前登录用户：专属红包要知道是不是给我的 */
    val myId: Long? = null,
    val onOpenBundle: (Message) -> Unit = {},
    val onOpenShare: (Message) -> Unit = {},
    /** 群文件消息卡片（metadata.file）：点开下载面板 */
    val onOpenFile: (Message) -> Unit = {},
    val onButton: (Message, BotButton) -> Unit = { _, _ -> },
    /** 多选模式下点一下 = 选中 / 取消 */
    val onToggleSelect: (Message) -> Unit = {},
    /** 长按多图消息里的某一张：菜单里的「添加到表情」加的是这一张。默认同长按整条消息 */
    val onImageLongPress: (Message, String) -> Unit = { message, _ -> onLongPress(message) },
)

private const val QUOTE_THRESHOLD_DP = 64

/**
 * 一条消息（设计稿 P03）：他人在左带头像，自己在右；群聊里昵称旁是群头衔、等级标签。
 * 气泡圆角 14dp、朝头像一角 4dp；他人白底柔影，自己强调色实底。贴图、单张图片不套气泡。
 * 手势：长按 350ms 轻震动 + 抬起（交给外层弹出操作面板）；向右轻滑过 64dp 轻震动，松手 = 引用回复。
 */
@Composable
fun MessageRow(
    message: Message,
    mine: Boolean,
    isGroup: Boolean,
    showName: Boolean,
    callbacks: MessageCallbacks,
    selecting: Boolean = false,
    selected: Boolean = false,
    /** 头像被戳时自增，驱动一次抖动 */
    shakeToken: Int = 0,
    /** 跳转到这条时自增：一层浅浅的墨色底淡入、停一下、淡出（共约 1.6 秒，同电脑版 kcd-flash） */
    flashToken: Int = 0,
    /** 贴在气泡外侧的小部件（「+1」复读）：别人的消息在右手边，自己的在左手边 */
    aside: (@Composable () -> Unit)? = null,
    /** 当前登录用户：自己的消息用它画右侧头像（发送中的消息还没有 sender）；不传就用消息里的 sender */
    me: ink.kuke.chat.core.model.User? = null,
    /** 群里的成员角色和等级头衔（名字前的身份标签用）；不传就只显示等级和专属头衔 */
    identity: GroupIdentity? = null,
    /** 发送中 / 发送失败的本地消息（乐观发送）：不能引用、回应；旁边画转圈或红色感叹号 */
    pending: PendingMessage? = null,
    onRetry: () -> Unit = {},
    /** 本地消息的「删除」（文件卡片上的 × / 删除：顺带取消上传） */
    onDiscard: () -> Unit = {},
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val threshold = with(density) { QUOTE_THRESHOLD_DP.dp.toPx() }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeToken) {
        if (shakeToken == 0) return@LaunchedEffect
        // 戳一戳：头像左右抖几下
        for (target in listOf(-7f, 7f, -5f, 5f, -2f, 0f)) shake.animateTo(target, tween(55))
    }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashToken) {
        if (flashToken == 0) return@LaunchedEffect
        flash.snapTo(0f)
        flash.animateTo(1f, tween(160))
        kotlinx.coroutines.delay(560)
        flash.animateTo(0f, tween(880))
    }
    val flashColor = colors.text.copy(alpha = if (colors.isDark) 0.10f else 0.065f)
    val flashModifier = Modifier.drawBehind {
        val a = flash.value
        if (a > 0f) {
            val dx = 8.dp.toPx()
            val dy = 4.dp.toPx()
            drawRoundRect(flashColor.copy(alpha = flashColor.alpha * a), topLeft = Offset(-dx, -dy), size = Size(size.width + dx * 2, size.height + dy * 2), cornerRadius = CornerRadius(12.dp.toPx()))
        }
    }

    // 群公告卡片（system 消息，metadata.system_event = group.announcement.card）：画成卡片、点开公告详情；和别的系统消息一样没有长按菜单，不能转发、不进多选
    if (message.type == "system" && !message.isRecalled) {
        ink.kuke.chat.feature.groupannouncement.announcementCardOf(message)?.let { card ->
            ink.kuke.chat.feature.groupannouncement.AnnouncementCard(message, card, flashModifier)
            return
        }
    }
    if (message.type == "system" || message.isRecalled) {
        Box(Modifier.fillMaxWidth().then(flashModifier).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
            Text(
                if (message.isRecalled) "${if (mine) "你" else message.senderName} 撤回了一条消息" else message.content,
                style = type.caption,
                color = colors.muted,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.text.copy(alpha = 0.05f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        return
    }

    Box(
        Modifier
            .fillMaxWidth()
            .then(flashModifier)
            .then(if (selecting) Modifier.clickable { callbacks.onToggleSelect(message) } else Modifier),
    ) {
        // 引用手势的方向跟着消息走：我的消息（右边）向右划，别人的消息（左边）向左划；图标在露出的那一侧
        val dir = if (mine) 1 else -1
        val progress = (swipe.value / threshold).coerceIn(0f, 1f)
        if (progress > 0f) {
            Box(
                Modifier.align(if (mine) Alignment.CenterStart else Alignment.CenterEnd).padding(start = if (mine) 8.dp else 0.dp, end = if (mine) 0.dp else 8.dp).size(30.dp).graphicsLayer { alpha = progress; scaleX = 0.6f + 0.4f * progress; scaleY = scaleX }
                    .clip(RoundedCornerShape(15.dp)).background(if (progress >= 1f) colors.fill else colors.panelMuted),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.reply, tint = if (progress >= 1f) colors.onFill else colors.muted, size = 16.dp) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset((swipe.value * dir).roundToInt(), 0) },
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            if (selecting) {
                Box(
                    Modifier.padding(end = 8.dp).align(Alignment.CenterVertically).size(22.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (selected) colors.fill else colors.panel)
                        .border(1.5.dp, if (selected) colors.fill else colors.borderStrong, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (selected) KcIcon(KcIcons.check, tint = colors.onFill, size = 14.dp) }
            }
            if (!mine) {
                Box(
                    Modifier
                        .offset { IntOffset(shake.value.roundToInt() * 2, 0) }
                        .pointerInput(message.id, selecting) {
                            if (selecting) return@pointerInput
                            detectTapGestures(
                                onTap = { callbacks.onAvatar(message) },
                                onDoubleTap = { if (isGroup) callbacks.onAvatarDoubleTap(message) else callbacks.onAvatar(message) },
                                onLongPress = {
                                    if (isGroup) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        callbacks.onAvatarLongPress(message)
                                    }
                                },
                            )
                        },
                ) {
                    // 不裁切这一层：头像框画在头像外面
                    val frame = ink.kuke.chat.feature.shop.rememberAvatarFrame(message.senderId ?: message.sender?.id, message.sender?.cosmetics)
                    Avatar(message.sender?.avatarUrl, message.senderName, tone = message.senderId ?: 0, size = 38.dp, frame = frame)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (mine && aside != null && !selecting) Box(Modifier.align(Alignment.CenterVertically).padding(end = 6.dp)) { aside() }
            // 发送状态：失败 = 红色感叹号（点一下重发）；文字等超过 1 秒还没确认才出现小转圈（图片的转圈叠在图上）
            // 文件卡片自己画进度 / 失败，不再挂转圈和感叹号
            val pendingFile = pending?.file
            if (pending != null && pendingFile == null) Box(Modifier.align(Alignment.CenterVertically).padding(end = 6.dp)) { PendingStatus(pending, onRetry) }
            // 引用手势只认按在消息上（名字、气泡、回应）起手的横划：整行的空白处留给外层的左右划（返回 / 打开资料）
            Column(
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 288.dp)
                    .pointerInput(message.id, selecting, mine, pending != null) {
                        if (selecting || pending != null) return@pointerInput
                        // 只认引用方向的横划：起手方向反了就不接手（不吃掉事件），交给外层的「右滑返回」
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var crossed = false
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                if (over * dir > 0f) change.consume()
                            } ?: return@awaitEachGesture
                            val finished = horizontalDrag(drag.id) { change ->
                                val amount = change.positionChange().x
                                val next = (swipe.value + amount * dir * 0.6f).coerceIn(0f, threshold * 1.4f)
                                change.consume()
                                scope.launch { swipe.snapTo(next) }
                                if (!crossed && next >= threshold) {
                                    crossed = true
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            if (finished && swipe.value >= threshold) callbacks.onQuote(message)
                            scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                        }
                    },
            ) {
                // 群聊里自己的消息也显示身份标签和名字（同电脑版 / QQ，跟着气泡靠右）；私聊两边都不显示（showName = 是否群聊）
                // 机器人的名字点了和点头像一样（聊天页把机器人转到机器人详情）
                if (showName) NameLine(message, isGroup, identity, me, onBotName = if (!mine && !selecting && message.sender?.isBot == true) ({ callbacks.onAvatar(message) }) else null)
                Box(
                    Modifier.pointerInput(message.id, selecting) {
                        if (selecting) return@pointerInput
                        detectTapGestures(onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            callbacks.onLongPress(message)
                        })
                    },
                ) {
                    if (pending != null && pendingFile != null) {
                        PendingFileCard(pendingFile, pending.failed, pending.error, onCancel = onDiscard, onRetry = onRetry)
                    } else {
                        MessageBody(
                            message, mine, callbacks, skin = rememberSenderSkin(message),
                            imageOverlay = pending?.takeIf { it.hasLocalImages && !it.failed }?.let { p -> { index -> UploadBadge(p, index) } },
                        )
                    }
                }
                if (message.reactions.isNotEmpty()) Reactions(message, mine, callbacks)
                if (pending?.failed == true && pendingFile == null) {
                    Text(
                        "发送失败，点击重发",
                        style = type.caption,
                        color = colors.danger,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onRetry).padding(horizontal = 2.dp, vertical = 1.dp),
                    )
                }
            }
            if (!mine && aside != null && !selecting) Box(Modifier.align(Alignment.CenterVertically).padding(start = 6.dp)) { aside() }
            // 自己的消息：头像在右边（同 QQ / 电脑版），点它看自己的资料
            if (mine) {
                Spacer(Modifier.width(8.dp))
                val self = me ?: message.sender
                val selfId = self?.id ?: message.senderId
                val frame = ink.kuke.chat.feature.shop.rememberAvatarFrame(selfId, self?.cosmetics)
                Box(
                    Modifier.pointerInput(message.id, selecting) {
                        if (selecting) return@pointerInput
                        detectTapGestures(onTap = { callbacks.onAvatar(message) })
                    },
                ) {
                    Avatar(self?.avatarUrl, self?.displayName ?: message.senderName, tone = selfId ?: 0, size = 38.dp, frame = frame)
                }
            }
        }
    }
}

/**
 * 群里的身份信息：成员角色（群主 / 管理员，进聊天时拉一次成员列表）和群的等级头衔分档。
 * 由聊天页传进来；精华、收藏这些只读场景没有，就只显示等级和专属头衔。
 */
class GroupIdentity(val roles: Map<Long, String>, val levelTitles: List<ink.kuke.chat.core.model.LevelTitle>)

/** 等级所在分档的头衔（分档按 min_level 升序） */
private fun levelTitleOf(level: Int?, tiers: List<ink.kuke.chat.core.model.LevelTitle>): String? {
    if (level == null || level <= 0) return null
    return tiers.lastOrNull { it.minLevel <= level }?.title?.trim()?.takeIf { it.isNotEmpty() }
}

@Composable
private fun NameLine(message: Message, isGroup: Boolean, identity: GroupIdentity?, me: ink.kuke.chat.core.model.User? = null, onBotName: (() -> Unit)? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
        // 同电脑版 / QQ：身份标签在名字前面。机器人只挂一个带图标的「机器人」；
        // 真人把等级和头衔合成一个标签（「LV100 管理员」「LV8 萌新」），头衔的优先级：成员专属头衔 > 群主 / 管理员 > 等级头衔。
        // 颜色同电脑版：群主橙、管理员蓝、其他人灰
        if (message.sender?.isBot == true) {
            Tag("机器人", colors.accent, KcIcons.bot)
        } else if (isGroup) {
            val role = message.senderId?.let { identity?.roles?.get(it) }
            val custom = message.senderTitle?.trim()?.takeIf { it.isNotEmpty() && it != "机器人" }
            val title = custom ?: when (role) {
                "owner" -> "群主"
                "admin" -> "管理员"
                else -> levelTitleOf(message.senderLevel, identity?.levelTitles.orEmpty())
            }
            val text = listOfNotNull(message.senderLevel?.takeIf { it > 0 }?.let { "LV$it" }, title).joinToString(" ")
            val tone = when (role) {
                "owner" -> colors.warn
                "admin" -> colors.accent
                else -> colors.muted
            }
            if (text.isNotEmpty()) Tag(text, tone)
        }
        // 自己刚发出、还没回来的消息没有 sender：名字用当前登录用户的
        val name = if (message.sender == null && message.senderDisplayName.isNullOrBlank() && me != null) me.displayName else message.senderName
        Text(
            name, style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp).widthIn(max = 160.dp)
                .then(if (onBotName != null) Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onBotName) else Modifier),
        )
        // 群聊里我的好友：名字后挂好友徽章（同电脑版；不是好友 / 未开放时什么也不画）
        if (isGroup && message.sender?.isBot != true && (me == null || message.senderId != me.id)) {
            ink.kuke.chat.feature.user.FriendBadgeMark(message.senderId, size = 14.dp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun Tag(text: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) KcIcon(icon, tint = color, size = 11.dp, modifier = Modifier.padding(end = 3.dp))
        Text(text, style = KcTheme.type.caption.copy(fontSize = KcTheme.type.caption.fontSize * 0.88f, fontWeight = androidx.compose.ui.text.font.FontWeight.W600), color = color, maxLines = 1)
    }
}

/**
 * 发送者装备的气泡皮肤（docs/shop.md 2.2）。自己的消息读自己的登录用户（发送中的消息没有 sender）。
 * 机器人的富文本 / 组件消息自带底色，不穿皮肤。
 */
@Composable
private fun rememberSenderSkin(message: Message): ink.kuke.chat.feature.shop.BubbleSkin? {
    if (message.sender?.isBot == true) return null
    return ink.kuke.chat.feature.shop.rememberBubbleSkin(message.senderId ?: message.sender?.id, message.sender?.cosmetics)
}

/** 消息正文。聊天页、精华、收藏、合并转发详情共用（只读场景见 SharedMessageView）。[skin] 只染文字气泡 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageBody(
    message: Message,
    mine: Boolean,
    callbacks: MessageCallbacks,
    skin: ink.kuke.chat.feature.shop.BubbleSkin? = null,
    /** 叠在第 index 张图上的东西（发送中的上传转圈）。图片外面总是套一层 Box，有没有叠加结构都一样，换成服务端消息时图不重建 */
    imageOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.(Int) -> Unit)? = null,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val baseRadius = ink.kuke.chat.feature.appearance.LocalAppearance.current.bubbleRadius
    // 语音不穿皮肤（播放键、波形配色跟主题走）
    val skinPaint = if (message.type == "voice") null else skin?.paint(colors.isDark)
    val radius = (if (skinPaint != null) skin?.radius ?: baseRadius else baseRadius).dp
    val bubbleShape = if (mine) RoundedCornerShape(radius, 4.dp, radius, radius) else RoundedCornerShape(4.dp, radius, radius, radius)
    val content = skinPaint?.text ?: if (mine) colors.onBubbleOut else colors.text
    val haptics = LocalHapticFeedback.current

    // 不套气泡的：贴图、单张图片
    if (message.type == "image") {
        val url = Meta.imageUrl(message.content)
        val sticker = Meta.isSticker(message)
        // 表情按自身比例放进 120dp 的框里：原图常常只有几十像素，按像素原样画在高密度屏上就只剩一点点
        var stickerAspect by remember(url) { mutableStateOf(1f) }
        Box {
        AsyncImage(
            // 自己刚发的图：用本地那张（和发送中显示的是同一个缓存键）
            model = LocalImages.model(message.content),
            contentDescription = if (sticker) "表情" else "图片",
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val drawable = state.result.drawable
                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    stickerAspect = (drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight).coerceIn(0.5f, 2f)
                }
            },
            modifier = Modifier
                .then(
                    if (sticker) {
                        if (stickerAspect >= 1f) Modifier.size(STICKER_SIZE, STICKER_SIZE / stickerAspect)
                        else Modifier.size(STICKER_SIZE * stickerAspect, STICKER_SIZE)
                    } else Modifier.heightIn(max = 260.dp).widthIn(max = 220.dp),
                )
                .clip(RoundedCornerShape(if (sticker) 6.dp else 12.dp))
                .combinedClickable(
                    onClick = { url?.let(callbacks.onOpenImage) },
                    // 长按出菜单（添加到表情、转发…）：外层气泡的长按收不到这里被点击接管的按下，得在这儿接
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (url != null) callbacks.onImageLongPress(message, url) else callbacks.onLongPress(message)
                    },
                ),
        )
        imageOverlay?.invoke(this, 0)
        }
        return
    }

    // 有交互的卡片：投票、红包、礼物、合并转发
    when (message.type) {
        "poll" -> pollOf(message)?.let {
            PollCard(message, it, callbacks)
            return
        }
        "red_packet" -> redPacketMetaOf(message)?.let {
            RedPacketCard(message, it, callbacks)
            return
        }
        "gift" -> giftMetaOf(message)?.let {
            GiftCard(it)
            return
        }
        "forward_bundle" -> if (forwardOf(message) != null) {
            ForwardCard(message, callbacks)
            return
        }
    }
    // 群文件消息：按 metadata.file 画文件卡片（旧客户端看到的是「[文件] 名字（大小）」那行字）
    fileMetaOf(message)?.let { meta ->
        MessageFileCard(meta) { callbacks.onOpenFile(message) }
        return
    }
    val card = Meta.card(message)
    if (card != null) {
        MessageCard(card, onClick = if (shareRouteOf(message) != null) ({ callbacks.onOpenShare(message) }) else null)
        return
    }
    if (isBotRichMessage(message)) {
        BotContent(message, callbacks.onButton)
        return
    }

    // 语音：没引用时整条是胶囊（设计稿 .voice 圆角 22、高 44），有引用时沿用普通气泡把引用和语音包在一起；整条都能点播放
    val isVoice = message.type == "voice"
    val quote = Meta.quote(message)
    val pill = isVoice && quote == null
    val shape = if (pill) RoundedCornerShape(22.dp) else bubbleShape
    val voicePress = rememberInteraction()
    // 气泡下面挂外站链接卡片（CCW 作品、B 站视频）
    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
    Column(
        Modifier
            .then(if (isVoice) Modifier.pressScale(voicePress, 0.97f) else Modifier)
            .then(if (skinPaint != null && skin != null) Modifier.bubbleSkin(skin, skinPaint, shape, message.id) else Modifier.shadow(if (mine) 0.dp else 1.dp, shape).background(if (mine) colors.bubbleOut else colors.bubbleIn, shape))
            .then(if (isVoice) Modifier.clip(shape).clickable(interactionSource = voicePress, indication = null) { VoicePlayer.toggle(message) } else Modifier)
            .padding(if (pill) PaddingValues(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp) else PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
    ) {
        quote?.let { quote ->
            Row(
                Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(content.copy(alpha = 0.08f))
                    .clickable { quote.messageId?.let(callbacks.onJumpToQuote) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Box(Modifier.width(2.dp).height(30.dp).background(if (mine || skinPaint != null) content.copy(alpha = 0.5f) else colors.accent))
                Column(Modifier.padding(start = 6.dp)) {
                    Text(quote.senderName, style = type.caption, color = content.copy(alpha = 0.75f), maxLines = 1)
                    Text(quote.preview, style = type.caption, color = content.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // 接龙红包领取时自动发出的那句：前面挂一个「红包接龙」小标记，点它打开那个红包
        redPacketChainOf(message)?.let { packetId ->
            Row(
                Modifier
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { callbacks.onOpenRedPacket(message, RedPacketMeta(packetId, "normal", "恭喜发财，大吉大利", 1)) }
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcIcon(KcIcons.redPacket, tint = content.copy(alpha = 0.62f), size = 12.dp)
                Text("红包接龙", style = type.caption, color = content.copy(alpha = 0.62f), modifier = Modifier.padding(start = 4.dp))
            }
        }
        when (message.type) {
            "voice" -> VoiceContent(message, mine, inset = if (pill) 20.dp else 24.dp)
            else -> {
                if (message.content.isNotBlank()) {
                    val rich = if (skinPaint != null) remember(message.content, skinPaint) { richText(message, true, skinPaint.link, skinPaint.link) } else richText(message, mine, colors.accent, colors.onBubbleOut)
                    Text(rich, style = type.body, color = content)
                }
                val images = Meta.images(message)
                if (images.isNotEmpty()) {
                    ImageGrid(message, images, callbacks, topGap = message.content.isNotBlank() || quote != null, overlay = imageOverlay)
                }
            }
        }
    }
    if (!isVoice && message.content.isNotBlank()) LinkPreviewCards(message)
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageGrid(
    message: Message,
    images: List<String>,
    callbacks: MessageCallbacks,
    topGap: Boolean = true,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.(Int) -> Unit)? = null,
) {
    // 多图：一行 3 张（84dp × 3 + 间距正好放进 264dp 的气泡内宽），4 张排成 2 × 2
    FlowRow(
        Modifier.padding(top = if (topGap) 6.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = if (images.size == 4) 2 else 3,
    ) {
        val haptics = LocalHapticFeedback.current
        images.forEachIndexed { index, raw ->
            val url = Meta.imageUrl(raw)
            Box {
                AsyncImage(
                    model = LocalImages.model(raw),
                    contentDescription = "图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (images.size == 1) 200.dp else 84.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(
                        onClick = { url?.let(callbacks.onOpenImage) },
                        // 长按某一张：菜单照常（转发、引用…），「添加到表情」加的是按住的这张（动图服务端原样保存）
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (url != null) callbacks.onImageLongPress(message, url) else callbacks.onLongPress(message)
                        },
                    ),
                )
                overlay?.invoke(this, index)
            }
        }
    }
}

/** 发送中的图片右下角：小圆底 + 细线转圈；拿得到上传进度时是一圈慢慢画满的环，传完了等确认时转圈 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.UploadBadge(pending: PendingMessage, index: Int) {
    val image = pending.images.getOrNull(index) ?: return
    val progress = if (image.url == null && image.progress > 0f) image.progress else null
    Box(
        Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.34f)),
        contentAlignment = Alignment.Center,
    ) { SendSpinner(14.dp, Color.White.copy(alpha = 0.86f), progress) }
}

/** 气泡旁的发送状态：失败 = 红色感叹号；没有本地图的消息超过 1 秒还没确认才出现灰色小转圈 */
@Composable
private fun PendingStatus(pending: PendingMessage, onRetry: () -> Unit) {
    val colors = KcTheme.colors
    if (pending.failed) {
        Box(
            Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onRetry),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(17.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.danger), contentAlignment = Alignment.Center) {
                Text("!", style = KcTheme.type.caption.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W800), color = Color.White)
            }
        }
        return
    }
    if (pending.hasLocalImages) return
    var slow by remember(pending, pending.attempt) { mutableStateOf(false) }
    LaunchedEffect(pending, pending.attempt) {
        val waited = android.os.SystemClock.uptimeMillis() - pending.startedAt
        kotlinx.coroutines.delay((1000 - waited).coerceAtLeast(0))
        slow = true
    }
    if (slow) SendSpinner(14.dp, colors.subtle, null)
}

/** 细线转圈（[progress] 不为空时画成进度环） */
@Composable
internal fun SendSpinner(size: Dp, color: Color, progress: Float?) {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "sendSpin")
    val angle by spin.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(850, easing = androidx.compose.animation.core.LinearEasing)),
        label = "sendSpinAngle",
    )
    Canvas(Modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(color.copy(alpha = color.alpha * 0.22f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (progress != null) {
            drawArc(color, -90f, 360f * progress.coerceIn(0.04f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        } else {
            drawArc(color, angle - 90f, 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

/** 语音气泡宽度：1 秒 120dp、60 秒 240dp，前段长得快（同微信 / QQ） */
private fun voiceBubbleWidth(seconds: Long): Dp = (120f + 120f * sqrt((seconds - 1).coerceIn(0, 59) / 59f)).dp

/**
 * 语音消息（设计稿 P32 .voice）：左边强调色实心圆播放键，中间细圆角竖条波形，右端时长 `7″`。
 * 播放时波形按进度从左到右点亮（已放 = 强调色 / 白，未放 = 淡色）；自己的消息反色：白键、强调色图标、白条。
 * [inset] 是外层气泡左右内边距之和，内容宽度 = 气泡宽度 − inset。
 */
@Composable
private fun VoiceContent(message: Message, mine: Boolean, inset: Dp) {
    val colors = KcTheme.colors
    val voice = Meta.voice(message)
    val playing = VoicePlayer.playingId == message.id
    val seconds = (voice.durationMs / 1000).coerceAtLeast(1)
    val button = if (mine) Color.White else colors.fill
    val glyph = if (mine) colors.bubbleOut else colors.onFill
    val lit = if (mine) colors.onBubbleOut else colors.fill
    val dim = if (mine) colors.onBubbleOut.copy(alpha = 0.42f) else colors.text.copy(alpha = 0.24f)
    val playT by animateFloatAsState(if (playing) 1f else 0f, tween(220, easing = EaseSmooth), label = "voicePlay")
    val levels = remember(message.id, voice.waveform) { voiceLevels(voice.waveform, message.id) }
    // 正在拖的位置（0–1）：拖动时进度跟着手指，松手才真的跳过去，免得边拖边卡顿
    var scrub by remember(message.id) { mutableStateOf<Float?>(null) }
    Row(Modifier.width(voiceBubbleWidth(seconds) - inset).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        // 播放 ⇄ 暂停：一个缩小淡出、一个放大淡入
        Canvas(Modifier.size(32.dp)) {
            drawCircle(button)
            val u = size.minDimension / 32f
            if (playT < 1f) scale(0.6f + 0.4f * (1f - playT)) {
                val tri = Path().apply {
                    moveTo(12.6f * u, 10.6f * u)
                    lineTo(21.6f * u, 16f * u)
                    lineTo(12.6f * u, 21.4f * u)
                    close()
                }
                drawPath(tri, glyph, alpha = 1f - playT)
                drawPath(tri, glyph, alpha = 1f - playT, style = Stroke(width = 2f * u, join = StrokeJoin.Round))
            }
            if (playT > 0f) scale(0.6f + 0.4f * playT) {
                val bar = Size(3.4f * u, 11f * u)
                drawRoundRect(glyph, Offset(11.6f * u, 10.5f * u), bar, CornerRadius(1.4f * u), alpha = playT)
                drawRoundRect(glyph, Offset(17f * u, 10.5f * u), bar, CornerRadius(1.4f * u), alpha = playT)
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 8.dp)
                .height(32.dp)
                // 波形上点一下 = 跳到那里；横着拖 = 拖进度（吃掉横划，不会触发引用 / 返回）
                .pointerInput(message.id) {
                    detectTapGestures { offset -> VoicePlayer.seek(message, offset.x / size.width.coerceAtLeast(1)) }
                }
                .pointerInput(message.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> scrub = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        onDragEnd = { scrub?.let { VoicePlayer.seek(message, it) }; scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ ->
                        change.consume()
                        scrub = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                },
        ) {
            val bw = 3.dp.toPx()
            val gap = 2.5.dp.toPx()
            val n = ((size.width + gap) / (bw + gap)).toInt().coerceAtLeast(1)
            val bars = resampleLevels(levels, n)
            val minH = 4.dp.toPx()
            fun drawBars(color: Color) = bars.forEachIndexed { i, v ->
                val h = minH + (size.height - minH) * v
                drawRoundRect(color, Offset(i * (bw + gap), (size.height - h) / 2f), Size(bw, h), CornerRadius(bw / 2f))
            }
            drawBars(dim)
            // 只在播放中读进度：进度每帧变，只重画这块画布
            val progress = scrub ?: if (playing) VoicePlayer.progress else 0f
            if (progress > 0f) clipRect(right = progress * (n * (bw + gap) - gap)) { drawBars(lit) }
        }
        Text(
            "$seconds″",
            style = KcTheme.type.caption.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
            color = if (mine) colors.onBubbleOut.copy(alpha = 0.85f) else colors.muted,
        )
    }
}

/** 服务端存的 0–100 振幅按本条最大值归一到 0–1（旧消息是线性刻度、普遍偏低）；没有波形就按消息 id 生成一串固定的起伏 */
private fun voiceLevels(waveform: List<Int>, seed: Long): List<Float> {
    if (waveform.isNotEmpty()) {
        val max = waveform.max().coerceAtLeast(1).toFloat()
        return waveform.map { (it / max).coerceIn(0.12f, 1f) }
    }
    val random = java.util.Random(seed)
    var last = 0.5f
    return List(32) {
        last = (last * 0.4f + (0.2f + random.nextFloat() * 0.8f) * 0.6f).coerceIn(0.15f, 1f)
        last
    }
}

/** 把波形重采样成 [n] 根：多了取每段最大值，少了线性插值 */
internal fun resampleLevels(src: List<Float>, n: Int): List<Float> {
    if (src.isEmpty() || n <= 0) return List(n.coerceAtLeast(0)) { 0f }
    if (src.size >= n) return List(n) { i ->
        val from = i * src.size / n
        val to = ((i + 1) * src.size / n).coerceAtLeast(from + 1)
        (from until to).maxOf { src[it] }
    }
    if (src.size == 1) return List(n) { src[0] }
    return List(n) { i ->
        val pos = i * (src.size - 1) / (n - 1).coerceAtLeast(1).toFloat()
        val lo = pos.toInt().coerceAtMost(src.size - 2)
        val t = pos - lo
        src[lo] * (1 - t) + src[lo + 1] * t
    }
}

@Composable
private fun MessageCard(card: Meta.Card, onClick: (() -> Unit)? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val (icon, accent) = when (card.kind) {
        "gift" -> KcIcons.gift to Color(0xFFD9468F)
        "redPacket" -> KcIcons.redPacket to Color(0xFFE2463A)
        "poll" -> KcIcons.poll to colors.accent
        "forward" -> KcIcons.messagesSquare to colors.accent
        "group" -> KcIcons.users to colors.accent
        "bot" -> KcIcons.bot to colors.accent
        "task" -> KcIcons.listChecks to colors.ok
        else -> KcIcons.profile to colors.accent
    }
    Row(
        Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .background(colors.panel, RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            KcIcon(icon, tint = accent, size = 22.dp)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(card.title, style = type.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            card.subtitle?.let { Text(it, style = type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Reactions(message: Message, mine: Boolean, callbacks: MessageCallbacks) {
    val colors = KcTheme.colors
    val haptics = LocalHapticFeedback.current
    // 长按某个表情回应：列出都有谁回应了
    var usersOf by remember(message.id) { mutableStateOf<String?>(null) }
    usersOf?.let { emoji -> ReactionUsersSheet(message.reactions, emoji, onDismiss = { usersOf = null }) }
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, if (mine) Alignment.End else Alignment.Start), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        message.reactions.forEach { reaction ->
            Text(
                "${reaction.emoji} ${reaction.count}",
                style = KcTheme.type.caption,
                color = if (reaction.reactedByMe) colors.accent else colors.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (reaction.reactedByMe) colors.accentSoft else colors.panel)
                    .border(1.dp, if (reaction.reactedByMe) colors.fill.copy(alpha = 0.4f) else colors.border, RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { callbacks.onReaction(message, reaction.emoji) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            usersOf = reaction.emoji
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** 聊天里表情的显示边长（长边） */
private val STICKER_SIZE = 120.dp
