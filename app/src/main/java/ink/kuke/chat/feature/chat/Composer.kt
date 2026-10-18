package ink.kuke.chat.feature.chat

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.border
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import ink.kuke.chat.core.net.userMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private enum class Panel { None, Emoji, Tools }

/** 设计稿 P05 的常用表情（快捷回应也用前 6 个） */
val QuickReactions = listOf("😂", "😘", "❔", "🤔", "😡", "😭")
private val Emojis = (
    "😂👍🥺😆🎉❤️🤔😭😘🙏🐱✨🔥😎👀🍉😡🥳💪😴🤝🌙⭐💯" +
        "😀😃😄😁😅🤣😊😇🙂😉😍🥰😋😜🤪🤗🤩😏😒😞😔😟😕🙁😣😖😫😩😢😤😠🤯😳🥵🥶😱😨😰😥😓🤫🤭🙄😬🤥😶😐😑😯😦😧😮😲🥱🤤😪😵🤐🥴🤢🤮🤧😷🤒🤕" +
        "👋🤚✋🖖👌🤌🤏✌️🤞🤟🤘🤙👈👉👆👇☝️👎✊👊🤛🤜👏🙌👐🤲💅🤳💪🧠👶🧒👦👧🧑👨👩" +
        "💖💗💓💞💕💔❣️💘💝💟🌟💫⚡☀️🌈☁️❄️🌸🌹🌻🍀🍎🍓🍒🍰🎂🍭🍬🍫🍿☕🧋🎮🎨🎵🎶🎁🏆🥇🚀🗼📡💡📌✅❌⭕❓❗"
    ).codePointsToGraphemes()

private fun String.codePointsToGraphemes(): List<String> {
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(this)
    val out = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != java.text.BreakIterator.DONE) {
        out += substring(start, end)
        start = end
        end = iterator.next()
    }
    return out
}

/**
 * 输入卡（设计稿 P03 / P05 / P06 / P32）：语音 · 输入框 · 表情 · 加号；有内容时加号弹簧缩小、发送键放大顶替。
 * 表情面板和工具面板顶替键盘出现，高度取上一次键盘的高度（没弹过键盘就 280dp），切换不跳动。
 * 左侧麦克风切到「按住说话」：按住录音，上滑或左滑到 ✕ 取消，右滑到锁定继续录，松手发送（最短 1 秒、最长 60 秒，见 VoiceRecording.kt）。
 */
@Composable
fun Composer(
    controller: ChatController,
    placeholder: String,
    onSent: () -> Unit,
    isGroup: Boolean = false,
    canMentionAll: Boolean = false,
    /** 工具面板里的工具（image / file / poll / redPacket / gift / poke）交给聊天页去开面板 */
    onTool: (String) -> Unit = {},
    /** 聊天区被点了一下就自增：收起键盘和面板 */
    dismissToken: Int = 0,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    // 草稿：离开时没发的文字存本地，回来还在（会话列表那一行显示「[草稿] …」）
    var value by remember { mutableStateOf(Drafts.get(controller.conversationId).let { TextFieldValue(it, TextRange(it.length)) }) }
    // 文字里 @ 到的成员（发送时只保留文字里还在的）
    val mentions = remember { mutableListOf<Pair<Long, String>>() }
    // 可保存：从表情面板进「收藏表情」管理页再回来，面板还开着（停在收藏页）
    var panel by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Panel.None) }
    var voiceMode by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(dismissToken) {
        if (dismissToken == 0) return@LaunchedEffect
        if (panel != Panel.None) panel = Panel.None
        keyboard?.hide()
        focusManager.clearFocus()
    }
    val imeHeight = WindowInsets.ime.getBottom(density)
    var lastKeyboard by remember { mutableIntStateOf(0) }
    if (imeHeight > lastKeyboard) lastKeyboard = imeHeight
    val panelHeight: Dp = with(density) { if (lastKeyboard > 200) lastKeyboard.toDp() - 24.dp else 280.dp }
    val quote by controller.quote

    LaunchedEffect(value.text) {
        delay(400)
        Drafts.set(controller.conversationId, value.text)
    }
    androidx.compose.runtime.DisposableEffect(controller.conversationId) {
        onDispose { Drafts.set(controller.conversationId, value.text) }
    }
    // 聚焦输入框：不当场 requestFocus——从语音模式切回来时输入框这一帧还没组合出来，直接请求会抛异常闪退。
    // 改成记一次请求，等输入框出现后的下一帧再聚焦；万一还是不行（比如正处在别的模式）也只是不聚焦
    var focusRequests by remember { mutableIntStateOf(0) }
    fun requestInputFocus() { focusRequests++ }
    LaunchedEffect(focusRequests, voiceMode) {
        if (focusRequests == 0 || voiceMode) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focus.requestFocus() }
        // 输入框原本就有焦点时 requestFocus 不会再弹键盘（比如刚被点消息区收起）：显式弹一次
        keyboard?.show()
    }
    // 设置了引用（右滑消息 / 长按菜单「引用」）：收起表情 / 更多面板、切回文字输入，等引用条出来后聚焦并弹键盘
    val quoteRequests = controller.quoteRequests.intValue
    // 输入框重新进入组合时（比如盖过它的整页关掉）不重放之前那次引用
    val quoteSeen = remember { controller.quoteRequests.intValue }
    LaunchedEffect(quoteRequests) {
        if (quoteRequests == quoteSeen) return@LaunchedEffect
        panel = Panel.None
        voiceMode = false
        requestInputFocus()
    }
    // 长按头像 @ 他：在光标处插入「@名字 」并登记成真正的 @（发送时带上 mention），语音模式下先切回文字
    val mentionRequest = controller.mentionRequest.value
    LaunchedEffect(mentionRequest) {
        val (userId, name) = mentionRequest ?: return@LaunchedEffect
        controller.mentionRequest.value = null
        voiceMode = false
        val start = value.selection.start
        val end = value.selection.end
        val before = value.text.substring(0, start)
        // 前面紧挨着文字时补一个空格，免得和前一个词粘在一起
        val label = (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + "@$name "
        value = TextFieldValue(before + label + value.text.substring(end), TextRange(start + label.length))
        mentions.add(userId to name)
        requestInputFocus()
    }
    // 机器人消息里的「填入输入框」按钮
    val insertText = controller.insertRequest.value
    LaunchedEffect(insertText) {
        if (insertText != null) {
            val start = value.selection.start
            value = TextFieldValue(value.text.substring(0, start) + insertText + value.text.substring(value.selection.end), TextRange(start + insertText.length))
            controller.insertRequest.value = null
            requestInputFocus()
        }
    }
    val mentionQuery = if (isGroup && !voiceMode) mentionQueryAt(value.text, value.selection.start) else null
    val commandPrefix = if (isGroup && !voiceMode && value.text.startsWith("/") && !value.text.contains(' ') && !value.text.contains('\n')) value.text else null

    // 键盘弹出时收起面板；面板开着时系统返回先收面板（不离开聊天）
    LaunchedEffect(imeHeight > 0) { if (imeHeight > 0) panel = Panel.None }
    BackHandler(enabled = panel != Panel.None) { panel = Panel.None }

    // 乐观发送：点了就清空输入框、消息立刻出现在列表末尾（发送中 / 失败的状态画在消息旁边），不等接口，可以连发
    fun send() {
        val text = value.text.trim()
        if (text.isEmpty()) return
        val finalText = value.text.trimEnd()
        val usedMentions = mentions.filter { (_, name) -> finalText.contains("@$name") }.distinctBy { it.first }
        val all = canMentionAll && finalText.contains("@全体成员")
        if (controller.sendText(finalText, usedMentions, all)) {
            value = TextFieldValue("")
            mentions.clear()
            Drafts.set(controller.conversationId, "")
            ink.kuke.chat.ui.components.Haptics.confirm(view)
            onSent()
        } else {
            ink.kuke.chat.ui.components.Haptics.reject(view)
        }
    }

    // 按住说话：录音状态在这里持有，输入卡里的「按住说话」管手势，下面的录音浮层管显示；离开聊天页时丢弃没发完的录音
    val voice = remember { VoiceSession(context.applicationContext, scope) }
    voice.send = { file, duration, waveform -> controller.sendVoice(file, duration, waveform) }
    voice.onSent = onSent
    androidx.compose.runtime.DisposableEffect(voice) { onDispose { voice.finish(send = false) } }
    val recordingActive = voice.phase != VoiceSession.Phase.Idle
    val reveal = animateFloatAsState(
        if (recordingActive) 1f else 0f,
        if (recordingActive) androidx.compose.animation.core.tween(240, easing = ink.kuke.chat.ui.components.EaseSmooth) else androidx.compose.animation.core.tween(200),
        label = "recReveal",
    )
    val overlayShown by remember { androidx.compose.runtime.derivedStateOf { reveal.value > 0.001f } }

    Box(Modifier.fillMaxWidth()) {
        // 录音时输入卡整体淡出（仍留在组合里：手势还挂在「按住说话」上）
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - reveal.value }.navigationBarsPadding()) {
            quote?.let { q ->
                // 设计稿 .cmp-reply：左侧 2.5dp 强调色竖线，圆角 4/10/10/4，面色底 + 轻影，12.5sp
                val replyShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
                Row(
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp).fillMaxWidth()
                        .shadow(2.dp, replyShape)
                        .background(colors.panel, replyShape)
                        .drawBehind { drawRect(colors.accent, size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height)) }
                        .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.reply, tint = colors.accent, size = 16.dp)
                    Text("回复 ${q.senderName}：${q.preview}", style = type.caption.copy(fontSize = type.caption.fontSize * (12.5f / 12f)), color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Box(Modifier.size(32.dp).clip(CircleShape).clickable { controller.quote.value = null }, contentAlignment = Alignment.Center) {
                        KcIcon(KcIcons.close, tint = colors.subtle, size = 16.dp, contentDescription = "取消引用")
                    }
                }
            }
            if (mentionQuery != null) {
                MentionPanel(controller, mentionQuery.second, canMentionAll) { member ->
                    val (at, query) = mentionQuery
                    val cursor = at + 1 + query.length
                    val label = if (member == null) "@全体成员 " else "@${member.name} "
                    val next = value.text.substring(0, at) + label + value.text.substring(cursor)
                    if (member != null) mentions.add(member.userId to member.name)
                    value = TextFieldValue(next, TextRange(at + label.length))
                }
            } else if (commandPrefix != null) {
                CommandPanel(controller, commandPrefix) { command ->
                    val label = command.command + " "
                    value = TextFieldValue(label, TextRange(label.length))
                }
            }
            Row(
                Modifier
                    // 设计稿 .cmp：外边距 8、圆角 18、最小高 52、柔影
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .shadow(3.dp, RoundedCornerShape(18.dp))
                    .background(colors.panel, RoundedCornerShape(18.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerIcon(if (voiceMode) KcIcons.keyboard else KcIcons.mic, "切换语音") {
                    voiceMode = !voiceMode
                    panel = Panel.None
                    if (voiceMode) keyboard?.hide() else requestInputFocus()
                }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    if (voiceMode) {
                        HoldToTalk(voice)
                    } else {
                        if (value.text.isEmpty()) Text(placeholder, style = type.body, color = colors.subtle, maxLines = 1)
                        BasicTextField(
                            value = value,
                            onValueChange = { next ->
                                if (next.text != value.text && next.text.isNotBlank()) controller.notifyTyping()
                                value = next
                            },
                            textStyle = type.body.copy(color = colors.text),
                            cursorBrush = SolidColor(colors.fill),
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focus),
                        )
                    }
                }
                ComposerIcon(if (panel == Panel.Emoji) KcIcons.keyboard else KcIcons.emoji, "表情", on = panel == Panel.Emoji) {
                    if (panel == Panel.Emoji) {
                        panel = Panel.None
                        requestInputFocus()
                    } else {
                        voiceMode = false
                        keyboard?.hide()
                        panel = Panel.Emoji
                    }
                }
                val hasText = value.text.isNotBlank() && !voiceMode
                // 发送 ⇄ 加号：占同一个位置，一个缩小淡出的同时另一个弹出，不会有一个突然消失、另一个突然出现
                val swap by animateFloatAsState(if (hasText) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f), label = "sendSwap")
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    val rotation by animateFloatAsState(if (panel == Panel.Tools) 45f else 0f, spring(dampingRatio = 0.6f), label = "plus")
                    val plusT = (1f - swap).coerceIn(0f, 1f)
                    if (plusT > 0.01f) {
                        ComposerIcon(
                            KcIcons.circlePlus, "更多",
                            Modifier.graphicsLayer { rotationZ = rotation + 90f * swap; scaleX = 0.4f + 0.6f * plusT; scaleY = scaleX; alpha = plusT },
                            on = panel == Panel.Tools,
                        ) {
                            if (panel == Panel.Tools) panel = Panel.None else {
                                keyboard?.hide()
                                panel = Panel.Tools
                            }
                        }
                    }
                    val sendT = swap.coerceIn(0f, 1.2f)
                    if (swap > 0.01f) {
                        val press = rememberInteraction()
                        Box(
                            Modifier.graphicsLayer { scaleX = 0.4f + 0.6f * sendT; scaleY = scaleX; alpha = swap.coerceIn(0f, 1f); rotationZ = -90f * (1f - swap.coerceIn(0f, 1f)) }
                                .size(40.dp).pressScale(press, 0.84f).clip(RoundedCornerShape(13.dp)).background(colors.fill)
                                .clickable(interactionSource = press, indication = null, enabled = hasText) { send() },
                            contentAlignment = Alignment.Center,
                        ) { KcIcon(KcIcons.send, tint = colors.onFill, size = 18.dp, contentDescription = "发送") }
                    }
                }
            }
            // 表情 / 工具面板：从底部升起 + 淡入，收起时沉下去；两个面板之间互切只做淡入淡出（高度一样）
            androidx.compose.animation.AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(260, easing = ink.kuke.chat.ui.components.EaseSmooth)
                    val sizeSpec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntSize>(260, easing = ink.kuke.chat.ui.components.EaseSmooth)
                    when {
                        initialState == Panel.None -> (androidx.compose.animation.slideInVertically(spec) { it / 3 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)))
                            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)))
                        targetState == Panel.None -> androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(90))
                            .togetherWith(androidx.compose.animation.slideOutVertically(spec) { it / 3 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
                        else -> androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)).togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                    }.using(androidx.compose.animation.SizeTransform(clip = true) { _, _ -> sizeSpec })
                },
                label = "composerPanel",
            ) { shown ->
            when (shown) {
                Panel.Emoji -> EmojiPanel(controller, 316.dp, isGroup = isGroup, onEmoji = { emoji ->
                    val start = value.selection.start
                    val next = value.text.substring(0, start) + emoji + value.text.substring(value.selection.end)
                    value = TextFieldValue(next, TextRange(start + emoji.length))
                }, onSticker = { url -> if (controller.sendSticker(url)) onSent() },
                    onGroupEmoji = { emoji -> if (controller.sendSticker(emoji.url, emoji.id)) onSent() },
                    onCollapse = { panel = Panel.None; requestInputFocus() })
                Panel.Tools -> ToolsPanel(316.dp, isGroup) { tool ->
                    panel = Panel.None
                    when (tool) {
                        // 「图片」交给聊天页：多选（最多 9 张）后弹确认面板，见 ImageSendSheet.kt
                        // @ 群成员 / 机器人指令：往输入框里放一个触发字符，面板自己弹出来
                        "mention" -> {
                            val start = value.selection.start
                            value = TextFieldValue(value.text.substring(0, start) + "@" + value.text.substring(value.selection.end), TextRange(start + 1))
                            requestInputFocus()
                        }
                        "command" -> {
                            value = TextFieldValue("/", TextRange(1))
                            requestInputFocus()
                        }
                        else -> onTool(tool)
                    }
                }
                Panel.None -> Unit
            }
            }
        }
        // 录音浮层：贴着输入卡底边向上铺开，左右通栏，盖住输入卡
        if (recordingActive || overlayShown) VoiceRecordingOverlay(voice, { reveal.value }, Modifier.matchParentSize())
    }
}

@Composable
private fun ComposerIcon(icon: ImageVector, description: String, modifier: Modifier = Modifier, on: Boolean = false, onClick: () -> Unit) {
    val press = rememberInteraction()
    val colors = KcTheme.colors
    Box(
        modifier.size(40.dp).pressScale(press, 0.9f).clip(CircleShape).background(if (on) colors.accentSoft else Color.Transparent)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { KcIcon(icon, tint = if (on) colors.accent else colors.muted, size = 22.dp, contentDescription = description) }
}

/** 表情搜索建议词（同电脑版）：点一个只显示相关表情；再点一次取消 */
private val EmojiKeywords = linkedMapOf(
    "笑" to "😂😆😀😃😄😁😅🤣😊🙂😉",
    "哭" to "😭😢🥺😞😔😟😣😖😫😩",
    "爱心" to "❤️💖💗💓💞💕💘💝💟😍🥰😘",
    "赞" to "👍👏🙌💪👌✌️🤝",
    "庆祝" to "🎉🥳🎂🎁🏆🥇✨🎶",
    "无语" to "🙄😑😐😶😒🤐😬",
    "猫" to "🐱",
    "吃瓜" to "🍉👀🍿☕🧋",
)

/** 最近使用（进程内，最多 24 个）：点过的 Emoji 排前面 */
private val RecentEmojis = androidx.compose.runtime.mutableStateListOf<String>()

/**
 * 表情面板（设计稿 P05）：搜索框 + 建议词胶囊 + 「常用」网格（8 列 27sp）+ 底部分页条（搜索 / 最近 / Emoji / 收藏 / 收起）。
 * 搜索框不弹键盘（弹键盘会顶掉面板），显示当前选中的建议词；底部「搜索」图标回到全部。
 */
@Composable
private fun EmojiPanel(
    controller: ChatController,
    height: Dp,
    isGroup: Boolean,
    onEmoji: (String) -> Unit,
    onSticker: (String) -> Unit,
    onGroupEmoji: (ink.kuke.chat.feature.group.GroupEmoji) -> Unit,
    onCollapse: () -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    // 0 最近 · 1 Emoji · 2 收藏表情 ·（群聊）3 本群表情；可以左右滑切换，底部分页条跟着走
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = 1) { if (isGroup) 4 else 3 }
    val pagerScope = rememberCoroutineScope()
    val tab = pager.currentPage
    fun goTab(page: Int) { pagerScope.launch { pager.animateScrollToPage(page) } }
    var keyword by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val graph = (context.applicationContext as ink.kuke.chat.KukeApp).graph
    val nav = ink.kuke.chat.ui.shell.LocalAppNav.current
    // 收藏表情：和管理页共用 FavoriteStickers 的列表（先显示上次的，第一次翻到收藏页时重拉一次）
    val stickers = FavoriteStickers.items
    var stickersRefreshed by remember { mutableStateOf(false) }
    var stickersFailed by remember { mutableStateOf(false) }
    LaunchedEffect(tab, stickersRefreshed) {
        if (tab == 2 && !stickersRefreshed) {
            stickersRefreshed = true
            stickersFailed = false
            runCatching { FavoriteStickers.load(graph) }.onFailure { stickersFailed = true }
        }
    }
    /** 长按弹出小菜单的那个收藏表情 */
    var stickerMenu by remember { mutableStateOf<FavoriteSticker?>(null) }
    Column(Modifier.fillMaxWidth().height(height).background(colors.panel).drawBehind { drawRect(colors.border, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).clickable { keyword = null; goTab(1) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcIcon(KcIcons.search, tint = colors.subtle, size = 16.dp)
                Text(keyword ?: "搜索表情", style = type.secondary.copy(fontSize = type.secondary.fontSize * (14f / 13.5f)), color = if (keyword == null) colors.subtle else colors.text, modifier = Modifier.padding(start = 8.dp))
            }
        }
        // 关键词只对 Emoji 有用：收藏表情 / 本群表情页不显示
        if (tab < 2) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EmojiKeywords.keys.toList()) { word ->
                val on = keyword == word
                Box(
                    Modifier.height(28.dp).clip(RoundedCornerShape(99.dp)).background(if (on) colors.text else colors.text.copy(alpha = 0.06f))
                        .clickable { keyword = if (on) null else word; goTab(1) }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(word, style = type.caption.copy(fontSize = type.caption.fontSize * (13f / 12f), fontWeight = FontWeight.W500), color = if (on) colors.panel else colors.muted) }
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { tab ->
            when (tab) {
                3 -> ink.kuke.chat.feature.group.GroupEmojiPickerPage(controller.graph, controller.conversationId, onGroupEmoji)
                2 -> {
                    val list = stickers
                    when {
                        list == null && stickersFailed -> StateText("收藏表情没加载出来", error = true, onRetry = { stickersRefreshed = false })
                        list == null -> Text("加载中…", style = type.caption, color = colors.muted, modifier = Modifier.padding(16.dp))
                        else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().padding(8.dp)) {
                            // 「+」进收藏表情管理页：在那里添加、多选删除、拖动排序
                            item(key = "add") {
                                Box(
                                    Modifier.padding(4.dp).size(76.dp).clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                        .clickable { nav.go(ink.kuke.chat.ui.shell.StickersRoute) },
                                    contentAlignment = Alignment.Center,
                                ) { KcIcon(KcIcons.plus, tint = colors.muted, size = 24.dp, contentDescription = "添加和整理表情") }
                            }
                            if (list.isEmpty()) item(span = { GridItemSpan(3) }) {
                                Text("点「+」从相册添加，或长按聊天里的图片添加到表情", style = type.caption, color = colors.muted, modifier = Modifier.padding(8.dp))
                            }
                            items(list, key = { it.assetId }) { sticker ->
                                StickerCell(
                                    sticker,
                                    menuOpen = stickerMenu?.assetId == sticker.assetId,
                                    onClick = { onSticker(sticker.url) },
                                    onLongClick = { stickerMenu = sticker },
                                ) {
                                    StickerMenu(
                                        first = list.firstOrNull()?.assetId == sticker.assetId,
                                        onDismiss = { stickerMenu = null },
                                        onAction = { action ->
                                            stickerMenu = null
                                            when (action) {
                                                "manage" -> nav.go(ink.kuke.chat.ui.shell.StickersRoute)
                                                "front" -> pagerScope.launch {
                                                    runCatching { FavoriteStickers.moveToFront(graph, listOf(sticker.assetId)) }
                                                        .onFailure { toast(context, stickerErrorText(it)) }
                                                }
                                                "delete" -> pagerScope.launch {
                                                    runCatching { FavoriteStickers.delete(graph, listOf(sticker.assetId)) }
                                                        .onSuccess { toast(context, "已删除") }
                                                        .onFailure { toast(context, stickerErrorText(it)) }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    val shown: List<String> = when {
                        tab == 0 -> RecentEmojis.toList()
                        keyword != null -> Emojis.filter { EmojiKeywords[keyword]!!.contains(it) }
                        else -> Emojis
                    }
                    if (shown.isEmpty()) {
                        Text(if (tab == 0) "还没有最近使用的表情" else "没有相关表情", style = type.caption, color = colors.muted, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                            item(span = { GridItemSpan(8) }) {
                                Text(
                                    if (tab == 0) "最近使用" else if (keyword != null) "「$keyword」" else "常用",
                                    style = type.group, color = colors.muted, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(shown) { emoji ->
                                Box(
                                    Modifier.height(42.dp).clip(RoundedCornerShape(8.dp)).clickable {
                                        RecentEmojis.remove(emoji)
                                        RecentEmojis.add(0, emoji)
                                        while (RecentEmojis.size > 24) RecentEmojis.removeAt(RecentEmojis.size - 1)
                                        onEmoji(emoji)
                                    },
                                    contentAlignment = Alignment.Center,
                                ) { Text(emoji, fontSize = 27.sp) }
                            }
                        }
                    }
                }
            }
        }
        // 底部分页条：高 46，上缘发丝线；左 4 个分页，右侧收起
        Row(
            Modifier.fillMaxWidth().height(46.dp).drawBehind { drawRect(colors.border, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmojiTab(KcIcons.search, "搜索", false) { keyword = null; goTab(1) }
            EmojiTab(KcIcons.clock, "最近使用", tab == 0) { goTab(0) }
            EmojiTab(KcIcons.emoji, "Emoji", tab == 1) { goTab(1) }
            EmojiTab(KcIcons.heart, "收藏表情", tab == 2) { goTab(2) }
            if (isGroup) EmojiTab(KcIcons.sticker, "本群表情", tab == 3) { goTab(3) }
            Spacer(Modifier.weight(1f))
            EmojiTab(KcIcons.arrowLeft, "收起", false, onCollapse)
        }
    }
}

@Composable
private fun EmojiTab(icon: ImageVector, label: String, on: Boolean, click: () -> Unit) {
    val colors = KcTheme.colors
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (on) colors.accentSoft else Color.Transparent).clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) { KcIcon(icon, tint = if (on) colors.accent else colors.muted, size = 18.dp, contentDescription = label) }
}

/** 工具面板（设计稿 P06）：图片、文件、@ 群成员、发起投票、红包、礼物、机器人指令、戳一戳。群聊专属的私聊里不出现 */
@Composable
private fun ToolsPanel(height: Dp, isGroup: Boolean, onTool: (String) -> Unit) {
    val colors = KcTheme.colors
    // 色块取自设计稿 .c-*：图标用该色，底是面色白块 + 轻影
    val tools = buildList {
        add(Tool("image", "图片", KcIcons.image, Color(0xFF1F6BFF)))
        // 文件走群文件（私聊没有群文件，服务端 400 not_group）
        if (isGroup) add(Tool("file", "文件", KcIcons.paperclip, Color(0xFF12A679)))
        if (isGroup) add(Tool("mention", "@ 群成员", KcIcons.atSign, Color(0xFF16907C)))
        add(Tool("poll", "发起投票", KcIcons.poll, Color(0xFFF0700A)))
        add(Tool("redPacket", "红包", KcIcons.redPacket, Color(0xFFE5484D)))
        add(Tool("gift", "礼物", KcIcons.gift, Color(0xFFE64F8A)))
        if (isGroup) add(Tool("command", "机器人指令", KcIcons.bot, Color(0xFF7A55D6)))
        if (isGroup) add(Tool("poke", "戳一戳", KcIcons.poke, Color(0xFFE39B00)))
    }
    Box(
        Modifier.fillMaxWidth().height(height).background(colors.chat)
            .drawBehind { drawRect(colors.border, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
    ) {
        LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tools, key = { it.key }) { tool ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    // 设计稿：276 高的网格两行等分（每行约 115dp），图标块贴行首
                    modifier = Modifier.height(115.dp).clip(RoundedCornerShape(12.dp)).clickable { onTool(tool.key) },
                ) {
                    Box(
                        Modifier.size(58.dp).shadow(2.dp, RoundedCornerShape(17.dp)).background(colors.panel, RoundedCornerShape(17.dp)),
                        contentAlignment = Alignment.Center,
                    ) { KcIcon(tool.icon, tint = tool.color, size = 26.dp) }
                    Text(tool.label, style = KcTheme.type.caption, color = colors.muted, maxLines = 1)
                }
            }
        }
    }
}

private class Tool(val key: String, val label: String, val icon: ImageVector, val color: Color)

/** 表情面板「收藏」页的一格：点一下发出，长按弹出小菜单（[menu] 画在这一格上方） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StickerCell(sticker: FavoriteSticker, menuOpen: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, menu: @Composable () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val view = androidx.compose.ui.platform.LocalView.current
    Box(
        Modifier.padding(4.dp).size(76.dp).pressScale(press, 0.92f).clip(RoundedCornerShape(8.dp))
            .background(if (menuOpen) colors.text.copy(alpha = 0.06f) else Color.Transparent)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onLongClick = { ink.kuke.chat.ui.components.Haptics.tick(view); onLongClick() },
                onClick = onClick,
            ),
    ) {
        AsyncImage(
            model = Meta.imageUrl(sticker.url),
            contentDescription = "收藏表情",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (menuOpen) menu()
    }
}

/**
 * 长按收藏表情的小菜单：浮在这一格上方（上面放不下就放下面），样式同长按消息的操作宫格（浮起的白面、图标在上文字在下）。
 * 移到最前（已经是第一个时不出现）· 整理表情（进管理页）· 删除。点外面 / 返回关闭
 */
@Composable
private fun StickerMenu(first: Boolean, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val actions = buildList {
        if (!first) add(MessageAction("front", "移到最前", KcIcons.arrowUp))
        add(MessageAction("manage", "整理表情", KcIcons.layoutGrid))
        add(MessageAction("delete", "删除", KcIcons.trash, danger = true))
    }
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.tween(160, easing = ink.kuke.chat.ui.components.EaseSmooth)) }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = remember(gap) { AboveAnchor(gap) },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Row(
            Modifier
                .graphicsLayer { alpha = appear.value; val sc = 0.9f + 0.1f * appear.value; scaleX = sc; scaleY = sc }
                .padding(8.dp)
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .background(colors.raised, RoundedCornerShape(16.dp))
                .padding(6.dp),
        ) {
            actions.forEach { action ->
                val tint = if (action.danger) colors.danger else colors.text
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onAction(action.key) }.padding(top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KcIcon(action.icon, tint = tint, size = 20.dp)
                    Text(action.label, style = type.caption.copy(fontWeight = FontWeight.W600), color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** 弹出层放在锚点正上方居中，左右不出屏；上面放不下就放到下面。弹出层四周有 8dp 留给阴影的外边距，[gap] 从里面扣，卡片离格子约 4dp */
private class AboveAnchor(private val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val above = anchorBounds.top - popupContentSize.height + gap
        val y = if (above >= 0) above else anchorBounds.bottom - gap
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

/** 收藏表情操作失败的提示：列表在别处变过（409）单独说 */
internal fun stickerErrorText(error: Throwable): String =
    if ((error as? ink.kuke.chat.core.net.ApiException)?.code == FavoriteStickers.ORDER_MISMATCH) "表情列表有变化，已刷新，请重新操作"
    else error.userMessage()
