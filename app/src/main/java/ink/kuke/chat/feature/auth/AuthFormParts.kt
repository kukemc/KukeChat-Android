package ink.kuke.chat.feature.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.lucide
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * 登录页的表单零件（设计稿 template.html 的 .tabs / .f / .alert / .btn-pri / .clp）。
 * 只管外观，不含提交 / 校验逻辑（在 LoginScreen.kt）。
 */

/** 设计稿统一的缓动 cubic-bezier(.2,.8,.2,1) */
internal val AuthEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
/** CSS 的 ease */
internal val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** 行高按 CSS 算（单行也撑满行高、文字居中），表单的竖向节奏和设计稿逐像素对齐 */
internal fun lineStyle(lineHeight: Float) = TextStyle(
    lineHeight = lineHeight.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

internal object AuthIcons {
    val smile: ImageVector by lazy { lucide("smile", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M8 14s1.5 2 4 2 4-2 4-2", "M9 9h.01", "M15 9h.01") }
    val arrow: ImageVector by lazy { lucide("arrowRight", "M5 12h14", "m12 5 7 7-7 7") }
    val loader: ImageVector by lazy { lucide("loader", "M21 12a9 9 0 1 1-6.219-8.56") }
}

/** 「登录 | 注册新账号」页签：选中项下面一条蓝线，切换时滑过去（宽度也跟着变） */
@Composable
internal fun AuthTabs(register: Boolean, onSelect: (Boolean) -> Unit, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    // 每个页签的 x / 宽（px），量到了才画下划线；第一次直接到位，之后滑动
    val spots = remember { arrayOfNulls<Pair<Float, Float>>(2) }
    var measured by remember { mutableIntStateOf(0) }
    val inkX = remember { Animatable(0f) }
    val inkW = remember { Animatable(0f) }
    var placedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(register, measured) {
        val t = spots[if (register) 1 else 0] ?: return@LaunchedEffect
        if (!placedOnce || reduceMotion) {
            inkX.snapTo(t.first); inkW.snapTo(t.second); placedOnce = true
        } else {
            launch { inkX.animateTo(t.first, tween(420, easing = AuthEase)) }
            inkW.animateTo(t.second, tween(420, easing = AuthEase))
        }
    }
    val ink = colors.fill
    val line = colors.border
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(line, Offset(0f, size.height - px), Size(size.width, px))
                if (placedOnce) {
                    val h = 2.dp.toPx()
                    drawRoundRect(ink, Offset(inkX.value, size.height - h), Size(inkW.value, h), CornerRadius(h))
                }
            }
            .padding(bottom = 1.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            listOf(false to "登录", true to "注册新账号").forEachIndexed { i, (isRegister, label) ->
                val on = register == isRegister
                val color by animateColorAsState(if (on) colors.text else colors.muted, if (reduceMotion) snap() else tween(300, easing = AuthEase), label = "tab")
                Box(
                    Modifier
                        .onPlaced { c ->
                            val spot = c.positionInParent().x to c.size.width.toFloat()
                            if (spots[i] != spot) { spots[i] = spot; measured++ }
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(isRegister) }
                        // 上面多 8dp 只为了好点（设计稿表单上内边距 24，这边相应减 8）
                        .padding(top = 8.dp, bottom = 10.dp),
                ) {
                    Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = lineStyle(21f))
                }
            }
        }
    }
}

/**
 * 胶囊输入框（Scratch 的参数槽就是胶囊形）。聚焦：蓝色描边 + 外圈 3dp 淡蓝光晕；[error]：红色描边。
 * [eye] 为真时右边有显示 / 隐藏密码的按钮（设计稿只有「密码」有，「确认密码」没有）。
 */
@Composable
internal fun AuthField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    aside: String? = null,
    hint: String? = null,
    secret: Boolean = false,
    eye: Boolean = secret,
    keyboard: KeyboardType = KeyboardType.Text,
    done: Boolean = false,
    onDone: () -> Unit = {},
    error: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var focused by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        when {
            error -> colors.danger.copy(alpha = 0.65f)
            focused -> colors.fill
            else -> colors.borderStrong
        },
        tween(180), label = "fieldBorder",
    )
    val ring by animateColorAsState(
        when {
            !focused -> Color.Transparent
            error -> colors.danger.copy(alpha = 0.15f)
            else -> colors.fill.copy(alpha = 0.18f)
        },
        tween(180), label = "fieldRing",
    )
    val iconTint by animateColorAsState(if (focused) colors.accent else colors.subtle, tween(180), label = "fieldIcon")
    val panel = colors.panel
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = lerp(colors.muted, colors.text, 0.74f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, style = lineStyle(18.75f))
            if (aside != null) Text(aside, color = colors.subtle, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, style = lineStyle(18.75f))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .drawBehind {
                    val r = size.height / 2f
                    val g = 3.dp.toPx()
                    if (ring.alpha > 0f) {
                        drawRoundRect(ring, Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(r + g))
                    }
                    drawRoundRect(panel, Offset.Zero, size, CornerRadius(r))
                    val s = 1.dp.toPx()
                    drawRoundRect(border, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), CornerRadius(r - s / 2), style = Stroke(s))
                }
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcIcon(icon, tint = iconTint, size = 16.dp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = type.body.copy(color = colors.text, fontSize = 14.5.sp),
                cursorBrush = SolidColor(colors.fill),
                visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else keyboard,
                    imeAction = if (done) ImeAction.Done else ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 6.dp)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, color = colors.subtle, style = type.body.copy(fontSize = 14.5.sp))
                        inner()
                    }
                },
            )
            if (eye) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { visible = !visible },
                    contentAlignment = Alignment.Center,
                ) {
                    KcIcon(
                        if (visible) KcIcons.eyeOff else KcIcons.eye,
                        tint = colors.muted,
                        size = 16.dp,
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                    )
                }
            }
        }
        if (hint != null) Text(hint, color = colors.subtle, fontSize = 12.sp, style = lineStyle(18f), modifier = Modifier.padding(top = 6.dp))
    }
}

/** 错误 / 提示条：[tone] 决定红（错误）、琥珀（封禁、过期这类要留意的）还是绿（完成） */
@Composable
internal fun AuthAlert(text: String, tone: Color, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val bg = lerp(colors.panel, tone, 0.08f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .drawBehind {
                val s = 1.dp.toPx()
                drawRoundRect(tone.copy(alpha = 0.24f), Offset(s / 2, s / 2), Size(size.width - s, size.height - s), CornerRadius(12.dp.toPx() - s / 2), style = Stroke(s))
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KcIcon(KcIcons.alertCircle, tint = tone, size = 15.dp, modifier = Modifier.padding(top = 2.dp))
        Text(text, color = tone, fontSize = 13.sp, style = lineStyle(19.5f))
    }
}

/*
 * 主按钮的外形 = 一条路径：圆角 8 的矩形 + 顶部 Scratch 凹口 + 底部凸起（原版凹口深 8，这里按 0.75 压到 6），
 * 凹口起点距左边 16、宽 32。只有一条路径，所以没有接缝线（设计稿 btnPath）。单位 dp。
 */
private fun buttonPath(w: Float, h: Float, r: Float = 8f): Path = Path().apply {
    moveTo(0f, r)
    arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
    lineTo(16f, 0f)
    relativeCubicTo(2f, 0f, 3f, 0.75f, 4f, 1.5f)
    relativeLineTo(4f, 3f)
    relativeCubicTo(1f, 0.75f, 2f, 1.5f, 4f, 1.5f)
    relativeLineTo(12f, 0f)
    relativeCubicTo(2f, 0f, 3f, -0.75f, 4f, -1.5f)
    relativeLineTo(4f, -3f)
    relativeCubicTo(1f, -0.75f, 2f, -1.5f, 4f, -1.5f)
    lineTo(w - r, 0f)
    arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
    lineTo(w, h - r)
    arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
    lineTo(48f, h)
    relativeCubicTo(-2f, 0f, -3f, 0.75f, -4f, 1.5f)
    relativeLineTo(-4f, 3f)
    relativeCubicTo(-1f, 0.75f, -2f, 1.5f, -4f, 1.5f)
    relativeLineTo(-12f, 0f)
    relativeCubicTo(-2f, 0f, -3f, -0.75f, -4f, -1.5f)
    relativeLineTo(-4f, -3f)
    relativeCubicTo(-1f, -0.75f, -2f, -1.5f, -4f, -1.5f)
    lineTo(r, h)
    arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
    close()
}

/**
 * 主按钮。[busy] 时变淡并转圈（文字立刻换）；登录 ⇄ 注册换文字时淡出上移、再淡入（设计稿 swap）。
 * 底部凸起画在按钮外面 6dp，不占布局高度。
 */
@Composable
internal fun BlockButton(
    text: String,
    busy: Boolean,
    showArrow: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val colors = KcTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            busy -> lerp(colors.panel, colors.fill, 0.82f)
            pressed -> lerp(Color.Black, colors.fill, 0.84f)
            else -> colors.fill
        },
        if (reduceMotion) snap() else tween(300, easing = AuthEase), label = "btn",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawWithCache {
                val d = density
                val path = buttonPath(size.width / d, size.height / d)
                onDrawBehind { scale(d, d, Offset.Zero) { drawPath(path, fill) } }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        var wasBusy by remember { mutableStateOf(busy) }
        val animate = !reduceMotion && !busy && !wasBusy
        SideEffectBusy(busy) { wasBusy = it }
        SwapContent(Triple(text, showArrow, busy), animate) { (t, arrow, b) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (b) Spinner(colors.onFill)
                Text(t, color = colors.onFill, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.145.sp)
                if (arrow && !b) KcIcon(AuthIcons.arrow, tint = colors.onFill, size = 16.dp, modifier = Modifier.offsetX((-2).dp))
            }
        }
    }
}

@Composable
private fun SideEffectBusy(busy: Boolean, onChange: (Boolean) -> Unit) {
    LaunchedEffect(busy) { onChange(busy) }
}

private fun Modifier.offsetX(dx: androidx.compose.ui.unit.Dp) = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) { p.place(dx.roundToPx(), 0) }
}

@Composable
private fun Spinner(tint: Color) {
    val t = rememberInfiniteTransition(label = "spin")
    val deg by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "deg")
    KcIcon(AuthIcons.loader, tint = tint, size = 17.dp, modifier = Modifier.rotate(deg))
}

/**
 * 换内容：旧的淡出并上移 5（130ms），新的从下 5 淡入（260ms）；连续切换以最后一次为准，
 * 半路切回原来的就直接淡回来（同设计稿 swap）。[animate] 为假时立刻换。
 */
@Composable
internal fun <T> SwapContent(target: T, animate: Boolean, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    var shown by remember { mutableStateOf(target) }
    val alpha = remember { Animatable(1f) }
    val dy = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target == shown) {
            if (alpha.value < 1f) {
                launch { dy.animateTo(0f, tween(200, easing = AuthEase)) }
                alpha.animateTo(1f, tween(200, easing = CssEase))
            }
            return@LaunchedEffect
        }
        if (!animate) {
            shown = target
            alpha.snapTo(1f); dy.snapTo(0f)
            return@LaunchedEffect
        }
        val out = CubicBezierEasing(0.4f, 0f, 1f, 1f)
        launch { dy.animateTo(-5f, tween(130, easing = out)) }
        alpha.animateTo(0f, tween(130, easing = out))
        shown = target
        dy.snapTo(5f)
        launch { dy.animateTo(0f, tween(260, easing = AuthEase)) }
        alpha.animateTo(1f, tween(260, easing = AuthEase))
    }
    Box(modifier.graphicsLayer { this.alpha = alpha.value; translationY = dy.value * density }) { content(shown) }
}

/**
 * 只在一种模式里出现的东西：高度从 0 展开到内容高、同时淡入（设计稿 .clp：高度 .42s，透明度 .3s 晚 60ms 开始；
 * 收起时透明度 .22s）。[delayMs] 用来错开几个一起展开 / 收起的。完全收起后从界面上拿掉（不能聚焦）。
 * 四周留 3dp 不裁，给聚焦光晕。
 */
@Composable
internal fun Collapsible(open: Boolean, delayMs: Int, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val frac = remember { Animatable(if (open) 1f else 0f) }
    val alpha = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) {
        val t = if (open) 1f else 0f
        if (reduceMotion) { frac.snapTo(t); alpha.snapTo(t); return@LaunchedEffect }
        launch { frac.animateTo(t, tween(420, delayMs, AuthEase)) }
        alpha.animateTo(t, if (open) tween(300, delayMs + 60, CssEase) else tween(220, delayMs, CssEase))
    }
    val gone by remember { derivedStateOf { frac.value == 0f } }
    if (!open && gone) return
    Box(
        Modifier
            .fillMaxWidth()
            .layout { m, c ->
                val p = m.measure(c.copy(minHeight = 0))
                val h = (p.height * frac.value).roundToInt()
                layout(p.width, h) { p.place(0, 0) }
            }
            .graphicsLayer { this.alpha = alpha.value }
            .drawWithContent {
                val g = 3.dp.toPx()
                val bottom = if (frac.value >= 1f) size.height + g else size.height
                clipRect(-g, -g, size.width + g, bottom) { this@drawWithContent.drawContent() }
            },
    ) { content() }
}

/** 动画用的浮点数：可以同步 snap（下一帧就是新值），动画在给定的协程作用域里跑，新动画打断旧的 */
internal class AnimFloat(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    private var job: kotlinx.coroutines.Job? = null

    fun snap(v: Float) {
        job?.cancel(); job = null
        value = v
    }

    fun animate(
        scope: kotlinx.coroutines.CoroutineScope,
        target: Float,
        spec: androidx.compose.animation.core.AnimationSpec<Float>,
        onEnd: (() -> Unit)? = null,
    ) {
        job?.cancel()
        val start = value
        job = scope.launch {
            androidx.compose.animation.core.animate(start, target, animationSpec = spec) { v, _ -> value = v }
            onEnd?.invoke()
        }
    }
}
