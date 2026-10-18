package ink.kuke.chat.feature.chat

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// ------------------------------------------------------------------ JSON 读取
// 聊天里的卡片数据（投票、红包、礼物、转发、机器人组件）形状松散，统一用「缺了就当没有」的读法，不用 @Serializable 硬套。

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
internal fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.l(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.i(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.b(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.a(key: String): JsonArray? = this[key] as? JsonArray

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

// ------------------------------------------------------------------ 通用界面件

/**
 * 底部面板：压暗背景 + 白面从底部滑入，圆角 20dp。点背景 / 系统返回关闭；键盘弹出时整体上移。
 * 聊天、会话列表、群资料共用，不引 Material 的 ModalBottomSheet（它在 Compose 里和 imePadding 配合不稳）。
 */
@Composable
internal fun KcSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    maxHeight: androidx.compose.ui.unit.Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KcTheme.colors
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(240, easing = EaseSmooth)) }
    BackHandler(onBack = onDismiss)
    // 下滑关闭：拖过 96dp 或甩得够快就关，否则弹回。面板里自己能滚的内容先吃掉手势，拖把手 / 标题区域总能关
    var dragY by remember { mutableFloatStateOf(0f) }
    val dismissPx = with(LocalDensity.current) { 96.dp.toPx() }
    val dragState = rememberDraggableState { delta -> dragY = (dragY + delta).coerceAtLeast(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = (1f - appear.value) * 220.dp.toPx() + dragY }
                .imePadding()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.panel)
                // 拦住点击：点面板本身不关闭
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (dragY > dismissPx || velocity > 1800f) onDismiss()
                        // 调用方不让关（或关闭还没生效）时弹回原位
                        animate(dragY, 0f, animationSpec = tween(180, easing = EaseSmooth)) { value, _ -> dragY = value }
                    },
                )
                .navigationBarsPadding()
                .heightIn(max = maxHeight),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.borderStrong))
            if (title != null) {
                Text(title, style = KcTheme.type.title, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            } else {
                Box(Modifier.height(8.dp))
            }
            content()
        }
    }
}

/** 居中的确认框：危险操作（清空、退群、解散、删除会话）都过它 */
@Composable
internal fun KcConfirm(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KcTheme.colors
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(colors.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
        ) {
            Text(title, style = KcTheme.type.title, color = colors.text)
            Text(text, style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KcButton("取消", onDismiss, modifier = Modifier.weight(1f), primary = false)
                KcButton(confirmLabel, onConfirm, modifier = Modifier.weight(1f), danger = danger)
            }
        }
    }
}

@Composable
internal fun KcButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, danger: Boolean = false) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val bg = when {
        !primary -> colors.panelMuted
        danger -> colors.danger
        else -> colors.fill
    }
    Box(
        modifier
            .height(44.dp)
            .pressScale(press)
            .clip(RoundedCornerShape(12.dp))
            .background(bg.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KcTheme.type.rowTitle, color = if (primary) colors.onFill else colors.text)
    }
}

@Composable
internal fun KcTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = KcTheme.colors
    Box(
        modifier.heightIn(min = minHeight).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = KcTheme.type.body, color = colors.subtle)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = KcTheme.type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.fill),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 面板里的一行：图标 + 文案 + 右侧说明/勾选 */
@Composable
internal fun SheetRow(
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    detail: String? = null,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) KcIcon(icon, tint = if (danger) colors.danger else colors.text, size = 22.dp, modifier = Modifier.padding(end = 14.dp))
        Text(label, style = KcTheme.type.rowTitle, color = if (danger) colors.danger else colors.text, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, style = KcTheme.type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        if (selected) KcIcon(KcIcons.check, tint = colors.accent, size = 20.dp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 开关（设计稿 .sw：40×24，滑块 18，行程 16；关闭态底色是文字色 20%） */
@Composable
internal fun KcSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = KcTheme.colors
    val position = androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 700f), label = "switch")
    Box(
        Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(CircleShape)
            .background((if (checked) colors.fill else colors.text.copy(alpha = 0.2f)).copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .graphicsLayer { translationX = position.value * 16.dp.toPx() }
                .size(18.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color.White),
        )
    }
}

/** 不带滚动容器的小标签条（页签用） */
@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Text(
        text,
        style = KcTheme.type.secondary,
        color = if (selected) colors.onFill else colors.muted,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) colors.fill else colors.panelMuted).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 面板里放表单用的可滚动列 */
@Composable
internal fun SheetForm(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
internal fun StateText(text: String, modifier: Modifier = Modifier, error: Boolean = false, onRetry: (() -> Unit)? = null) {
    val colors = KcTheme.colors
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = KcTheme.type.secondary, color = if (error) colors.danger else colors.muted, textAlign = TextAlign.Center)
        if (onRetry != null) Text("重试", style = KcTheme.type.secondary, color = colors.accent, modifier = Modifier.clickable(onClick = onRetry).padding(8.dp))
    }
}

@Composable
internal fun HorizontalGap(width: androidx.compose.ui.unit.Dp) = Box(Modifier.width(width))
