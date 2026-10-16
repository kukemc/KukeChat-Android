package ink.kuke.chat.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.messagePreview
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

data class MessageAction(val key: String, val label: String, val icon: ImageVector, val danger: Boolean = false)

/**
 * 操作宫格（同电脑版右键菜单的顺序）：复制 引用 转发 多选 收藏 / 设为精华 撤回 删除 举报 添加到表情。
 * 「设为精华」只给群主 / 管理员；「撤回」给自己的消息（时限由服务端判断，超时会返回提示）和群管；
 * 红包、礼物消息服务端不允许转发，这里不给「转发」；「添加到表情」只在图片上出现。
 */
fun actionsFor(message: Message, mine: Boolean, staff: Boolean): List<MessageAction> = buildList {
    val copyable = message.type == "text" || message.content.isNotBlank() && message.type !in listOf("image", "voice", "red_packet", "gift", "poll", "forward_bundle")
    if (copyable) add(MessageAction("copy", "复制", KcIcons.copy))
    add(MessageAction("quote", "引用", KcIcons.quote))
    if (message.type !in listOf("red_packet", "gift", "system")) add(MessageAction("forward", "转发", KcIcons.forward))
    add(MessageAction("select", "多选", KcIcons.checkSquare))
    add(MessageAction("bookmark", "收藏", KcIcons.bookmark))
    if (staff) add(MessageAction("feature", if (message.featured) "取消精华" else "设为精华", KcIcons.star))
    // 群待办：群主 / 管理员；文本 / 图片 / 语音 / 合并转发 / 投票（文件消息是文本消息）。红包、礼物、系统消息服务端 422
    if (staff && !message.isRecalled && message.type in listOf("text", "image", "voice", "forward_bundle", "poll")) {
        add(MessageAction("todo", "群待办", KcIcons.checkSquare))
    }
    if (mine || staff) add(MessageAction("recall", "撤回", KcIcons.recall))
    add(MessageAction("delete", "删除", KcIcons.trash, danger = true))
    if (!mine) add(MessageAction("report", "举报", KcIcons.flag, danger = true))
    if (message.type == "image") add(MessageAction("sticker", "添加到表情", KcIcons.imagePlus))
}

/**
 * 长按消息（设计稿 P04）：下层内容由外层模糊 + 压暗，被按住的消息以原样（头像 + 昵称 + 气泡）抬起居中，
 * 上方是快捷回应胶囊（末尾「+」展开分类表情面板，见 ReactionPicker.kt），下方是 5 列操作宫格。他人消息靠左缩进 44dp（让开头像），自己的靠右。
 * 点空白处 / 系统返回关闭。
 */
@Composable
fun MessageActionsLayer(
    message: Message,
    mine: Boolean,
    staff: Boolean,
    isGroup: Boolean,
    onReaction: (String) -> Unit,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val appear = remember { Animatable(0f) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = EaseSmooth)) }
    BackHandler(onBack = onDismiss)
    val noop = remember { MessageCallbacks({}, {}, {}, { _, _ -> }, {}, {}) }
    val side = if (mine) Alignment.End else Alignment.Start
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        val panelMax = (maxHeight * 0.58f).coerceAtMost(400.dp)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .graphicsLayer { val sc = 0.94f + 0.06f * appear.value; scaleX = sc; scaleY = sc }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            horizontalAlignment = side,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 表情回应：收起是一行快捷表情 +「+」，展开是可滑动的分类面板（ReactionPicker.kt）。
            // 展开时把操作宫格收起来、抬起的消息压矮，面板最高占可用高度的一半多一点，菜单不会被挤出屏幕
            ReactionPicker(
                message = message,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                maxPanelHeight = panelMax,
                onPick = onReaction,
                modifier = Modifier.padding(start = if (mine) 0.dp else 44.dp, end = if (mine) 44.dp else 0.dp)
                    .then(if (expanded) Modifier.widthIn(max = 400.dp) else Modifier),
            )
            // 抬起的消息：原样渲染一条（回调全空，不会再次触发长按）
            Box(Modifier.fillMaxWidth().heightIn(max = if (expanded) 160.dp else 280.dp).clipToBounds()) {
                MessageRow(message = message, mine = mine, isGroup = isGroup, showName = isGroup, callbacks = noop)
            }
            // 操作宫格 5 × N（展开表情面板时收起）
            val actions = actionsFor(message, mine, staff)
            if (!expanded) Column(
                Modifier.padding(start = if (mine) 0.dp else 44.dp, end = if (mine) 44.dp else 0.dp)
                    .widthIn(max = 320.dp).fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(18.dp)).background(colors.raised, RoundedCornerShape(18.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                actions.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { action ->
                            val tint = if (action.danger) colors.danger else colors.text
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onAction(action.key) }.padding(top = 10.dp, bottom = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                KcIcon(action.icon, tint = tint, size = 22.dp)
                                Text(action.label, style = type.caption.copy(fontSize = type.caption.fontSize * (12.5f / 12f), fontWeight = FontWeight.W600), color = tint, maxLines = 1)
                            }
                        }
                        repeat(5 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("KukeChat", text))
}

/** 全屏看图：双指缩放、拖动，点一下 / 返回关闭 */
@Composable
fun ImageViewer(url: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = "图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.close, tint = Color.White, size = 22.dp, contentDescription = "关闭")
        }
    }
}
