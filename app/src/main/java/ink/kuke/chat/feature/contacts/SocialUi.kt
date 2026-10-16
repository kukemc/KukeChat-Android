package ink.kuke.chat.feature.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay

/** 联系人、通知、搜索、用户页共用的小部件。颜色字号全部走 KcTheme。 */

@Composable
fun LoadingBox(text: String = "正在加载…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted)
    }
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, style = KcTheme.type.secondary, color = KcTheme.colors.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        SmallButton("重试", primary = true, onClick = onRetry)
    }
}

/** 按钮：主按钮用强调色底，次按钮用面色 + 发丝线；按下 0.96 倍 */
@Composable
fun SmallButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shape = RoundedCornerShape(10.dp)
    val background = when {
        !enabled -> colors.panelMuted
        primary -> colors.fill
        else -> colors.panel
    }
    val content = when {
        !enabled -> colors.subtle
        primary -> colors.onFill
        danger -> colors.danger
        else -> colors.text
    }
    Box(
        modifier
            .pressScale(press)
            .height(36.dp)
            .clip(shape)
            .background(background)
            .then(if (primary || !enabled) Modifier else Modifier.border(1.dp, colors.borderStrong, shape))
            .clickable(enabled = enabled, interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

/** 搜索 / 输入框：面色底、圆角 10，左侧放图标 */
@Composable
fun KcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = KcIcons.search,
    singleLine: Boolean = true,
    minHeight: Dp = 44.dp,
    onDone: (() -> Unit)? = null,
) {
    val colors = KcTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            KcIcon(icon, tint = colors.subtle, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = KcTheme.type.body, color = colors.subtle)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = KcTheme.type.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Default),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty() && singleLine) {
            Box(Modifier.size(24.dp).clip(CircleShape).clickable { onValueChange("") }, contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.close, tint = colors.subtle, size = 16.dp, contentDescription = "清除")
            }
        }
    }
}

/** 一个小标签（药丸）：筛选、分段都用它。选中是强调色底，数量跟在后面 */
@Composable
fun FilterPill(label: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shape = RoundedCornerShape(99.dp)
    // 设计稿 .chip：高 32、13sp / 550；默认文字色 6% 底，选中是文字色实底配面色字
    Row(
        Modifier
            .pressScale(press)
            .height(32.dp)
            .clip(shape)
            .background(if (selected) colors.text else colors.text.copy(alpha = 0.06f))
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) colors.panel else colors.muted, fontSize = 13.sp, fontWeight = FontWeight.W600)
        if (count != null && count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(if (count > 99) "99+" else count.toString(), color = if (selected) colors.panel.copy(alpha = 0.7f) else colors.subtle, fontSize = 12.sp)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KcTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = { Text(title, style = KcTheme.type.title, color = colors.text) },
        text = { Text(message, style = KcTheme.type.body, color = colors.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = if (danger) colors.danger else colors.accent, fontWeight = FontWeight.W600) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.muted) } },
    )
}

/** 轻提示：底部一条药丸，2.2 秒后自己消失 */
@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    private var seq by mutableStateOf(0)

    fun show(text: String) {
        message = text
        seq += 1
    }

    internal fun key() = seq
    internal fun clear() { message = null }
}

@Composable
fun rememberToast(): ToastState = remember { ToastState() }

@Composable
fun BoxScope.ToastHost(state: ToastState) {
    val colors = KcTheme.colors
    val text = state.message
    LaunchedEffect(state.key()) {
        if (state.message != null) {
            delay(2200)
            state.clear()
        }
    }
    AnimatedVisibility(
        visible = text != null,
        // 设计稿：顶部胶囊从状态栏下方弹簧掉落，停留 2s
        enter = fadeIn() + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f)) { -it },
        exit = fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 },
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp),
    ) {
        Box(Modifier.clip(RoundedCornerShape(22.dp)).background(colors.text.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(text.orEmpty(), color = colors.chat, fontSize = 13.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 区段小标题（12sp 加粗 + 字距，设计稿 group 样式） */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = KcTheme.type.group, color = KcTheme.colors.muted, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** 一块白色面（panel 底，圆角 12） */
@Composable
fun Surface12(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(KcTheme.colors.panel)) { content() }
}

/** 用透明度叠出的强调淡底，用于图标块 */
fun Color.soft(alpha: Float = 0.14f): Color = this.copy(alpha = alpha)
