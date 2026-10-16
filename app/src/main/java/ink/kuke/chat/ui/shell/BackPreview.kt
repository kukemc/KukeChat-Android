package ink.kuke.chat.ui.shell

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.softShadow
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 返回转场的总时长（按钮返回 = 完全倒放前进动画） */
internal const val BACK_MS = 320

/**
 * 手势进度转给 NavHost 时乘的系数：侧滑时转场最多走到 40%（只是让上一页露出来），
 * 松手确认后剩下的部分至少约 220ms —— 就是设计稿的「松手 220ms 滑出」。页面怎么缩、怎么动由下面自己按手势进度画。
 */
internal const val SEEK_SCALE = 0.4f

/** 设计稿：进度 35% 为阈值，到阈值时页面已经缩到 0.9 倍（下限），再往里拖只有箭头跟手 */
private const val THRESHOLD = 0.35f

/** 侧滑返回的共享状态（只在主线程读写） */
internal object BackFx {
    /** 正在离开 / 正在露出的页面（popExit / popEnter 转场开始时登记） */
    var exitId: String? = null
    var enterId: String? = null
    /** 这次返回是不是侧滑手势（否则是按钮 / 返回键：完全倒放前进动画） */
    val gesture = mutableStateOf(false)
    /** 手势进度 0..1；取消时弹簧退回 0 */
    val progress = Animatable(0f)
    val touchY = mutableFloatStateOf(0f)
    /** 手指抬起、确认返回 */
    val released = mutableStateOf(false)
    /** 确认返回那一刻的转场进度（露出的上一页据此淡掉压暗） */
    var releaseAt = -1f
}

/**
 * 接管系统的返回手势，再原样转给页面栈：这样既拿得到手势的进度、边缘、手指高度（画箭头、缩放页面），
 * 页面栈和各页面的 BackHandler 也照常工作。页面栈里没有要处理返回的（聊天 tab 栈底）时不拦截，交给系统画「回到桌面」。
 */
@Composable
internal fun PredictiveBackHost(content: @Composable () -> Unit) {
    val activityOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var childEnabled by remember { mutableStateOf(false) }
    val child = remember { OnBackPressedDispatcher(null) { enabled -> childEnabled = enabled } }
    val owner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher: OnBackPressedDispatcher get() = child
            override val lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    val relay = remember {
        object : OnBackPressedCallback(false) {
            var inGesture = false
            var ticked = false
            var cancelJob: Job? = null

            fun seek(e: BackEventCompat, p: Float) = BackEventCompat(e.touchX, e.touchY, (p * SEEK_SCALE).coerceIn(0f, 1f), e.swipeEdge)

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (cancelJob?.isActive == true) {
                    cancelJob?.cancel()
                    child.dispatchOnBackCancelled()
                }
                inGesture = true
                ticked = false
                BackFx.gesture.value = true
                BackFx.released.value = false
                BackFx.releaseAt = -1f
                BackFx.touchY.floatValue = backEvent.touchY
                scope.launch { BackFx.progress.snapTo(0f) }
                child.dispatchOnBackStarted(seek(backEvent, 0f))
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                BackFx.touchY.floatValue = backEvent.touchY
                // 不直接跳到手势进度：用一根很紧的弹簧追过去，快速拖动时页面也是连续变化的，不会一下子跳到位
                scope.launch { BackFx.progress.animateTo(backEvent.progress, spring(dampingRatio = 1f, stiffness = 700f)) }
                if (!ticked && backEvent.progress >= THRESHOLD) {
                    ticked = true
                    Haptics.tick(view)
                }
                child.dispatchOnBackProgressed(seek(backEvent, backEvent.progress))
            }

            override fun handleOnBackCancelled() {
                inGesture = false
                val edge = BackEventCompat.EDGE_LEFT
                // 设计稿：未过阈值松手 → 弹回原位（260ms 弹簧）。页面栈的转场跟着一起退，上一页一直露到弹完
                cancelJob = scope.launch {
                    BackFx.progress.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 420f)) {
                        child.dispatchOnBackProgressed(BackEventCompat(0f, BackFx.touchY.floatValue, (value.coerceAtLeast(0f) * SEEK_SCALE), edge))
                    }
                    child.dispatchOnBackCancelled()
                    BackFx.gesture.value = false
                    BackFx.exitId = null
                    BackFx.enterId = null
                }
            }

            override fun handleOnBackPressed() {
                if (inGesture) {
                    inGesture = false
                    BackFx.released.value = true
                    child.onBackPressed()
                    // 滑出播完后收尾：之后按按钮返回走的是普通的「前进倒放」，不能还带着这次手势的缩放
                    scope.launch {
                        kotlinx.coroutines.delay(BACK_MS + 80L)
                        BackFx.gesture.value = false
                        BackFx.released.value = false
                        BackFx.progress.snapTo(0f)
                    }
                } else {
                    BackFx.gesture.value = false
                    child.onBackPressed()
                }
            }
        }
    }
    LaunchedEffect(childEnabled) { relay.isEnabled = childEnabled }
    DisposableEffect(activityOwner, lifecycleOwner) {
        activityOwner.onBackPressedDispatcher.addCallback(lifecycleOwner, relay)
        onDispose { relay.remove() }
    }
    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) { content() }
}

/** 手势进度 → 预览程度：到阈值时到底 */
private fun previewOf(p: Float) = (p / THRESHOLD).coerceIn(0f, 1f)

/**
 * 每个二级页面外面包一层，画返回 / 前进时的层级（设计稿 M5 / P33 / P34）：
 *  - 侧滑：当前页缩放 1 → 0.9、向手指方向平移 ≤ 26dp、圆角 0 → 32dp，带柔和投影；上一页压暗 30% → 16%。
 *    松手确认：缩小后的页面向外滑出；取消：弹回原位
 *  - 按钮 / 返回键：前进动画完全倒放（当前页向右滑走，上一页从左边 28% 处回来）
 *  - 前进：推进来的页面左缘带一条投影
 */
@Composable
internal fun AnimatedContentScope.PopRound(entry: NavBackStackEntry, content: @Composable () -> Unit) {
    val id = entry.id
    val exitF by transition.animateFloat(transitionSpec = { tween(BACK_MS, easing = LinearEasing) }, label = "backExit") { if (it == EnterExitState.PostExit) 1f else 0f }
    val enterLeft by transition.animateFloat(transitionSpec = { tween(BACK_MS, easing = LinearEasing) }, label = "backEnter") { if (it == EnterExitState.PreEnter) 1f else 0f }
    val gesture = BackFx.gesture.value
    val p = if (gesture) BackFx.progress.value.coerceAtLeast(0f) else 0f
    val isExit = BackFx.exitId == id && (exitF > 0f || (gesture && p > 0f))
    val isEnter = BackFx.enterId == id && enterLeft > 0f && !isExit
    val pushIn = !isExit && !isEnter && enterLeft > 0f

    // 确认返回那一刻的转场进度：之后页面从当时的样子滑出，和转场同时结束
    var releaseAt by remember { mutableFloatStateOf(-1f) }
    if (isExit && gesture && BackFx.released.value && releaseAt < 0f) SideEffect { releaseAt = exitF; BackFx.releaseAt = exitF }
    if (!isExit && releaseAt >= 0f) SideEffect { releaseAt = -1f }

    val pp = if (isExit && gesture) previewOf(p) else 0f
    val slide = when {
        !isExit -> 0f
        gesture -> if (releaseAt >= 0f) ((exitF - releaseAt) / (1f - releaseAt).coerceAtLeast(0.001f)).coerceIn(0f, 1f) else 0f
        else -> EaseSmooth.transform(exitF)
    }
    val dir = 1f
    val corner = 32.dp * pp
    val edge = when {
        isExit -> (if (gesture) pp else (exitF * 10f).coerceAtMost(1f)) * (1f - 0.6f * slide)
        pushIn -> (enterLeft * 5f).coerceAtMost(1f)
        else -> 0f
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                when {
                    isExit -> {
                        val out = if (gesture) slide * slide * (3f - 2f * slide) else slide
                        scaleX = 1f - 0.1f * pp
                        scaleY = scaleX
                        translationX = dir * (26.dp.toPx() * pp + size.width * out)
                    }
                    // 按钮返回时上一页从左边 28% 处回来（前进动画的倒放）；侧滑时上一页不动，只压暗
                    isEnter && !gesture -> translationX = -size.width * 0.28f * (1f - EaseSmooth.transform(1f - enterLeft))
                }
            }
            .then(
                if (edge > 0f) Modifier
                    .then(if (pp > 0f) Modifier.softShadow(blur = 22.dp, offsetY = 4.dp, color = Color.Black.copy(alpha = 0.22f * edge), corner = corner) else Modifier)
                    .drawBehind {
                        if (pp > 0f) return@drawBehind
                        // 没有缩放（按钮返回、前进）时，左缘一条投影分出上下层
                        val w = 22.dp.toPx()
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f * edge)), startX = -w, endX = 0f),
                            topLeft = Offset(-w, 0f), size = Size(w, size.height),
                        )
                    }
                else Modifier,
            )
            .then(if (corner > 0.dp) Modifier.clip(RoundedCornerShape(corner)) else Modifier)
            .drawWithContent {
                drawContent()
                if (isEnter) {
                    val alpha = if (gesture) {
                        val rel = BackFx.releaseAt
                        val f = 1f - enterLeft
                        val fade = if (rel >= 0f) ((f - rel) / (1f - rel).coerceAtLeast(0.001f)).coerceIn(0f, 1f) else 0f
                        (0.30f - 0.14f * previewOf(BackFx.progress.value)) * (1f - fade)
                    } else {
                        // 设计稿 M3：被盖住的页压暗 14%，返回时倒放
                        0.14f * enterLeft
                    }
                    if (alpha > 0f) drawRect(Color.Black.copy(alpha = alpha))
                }
            },
    ) { content() }
}

/**
 * 侧滑时边缘的返回箭头（设计稿 M5 / P33 / P34）：白色浮起圆钮（raised 面 + 阴影），纵向跟着手指；
 * 按下时从 0.5 倍滑入，进度 ≥ 35% 变成强调色实底、白色箭头、放大 1.14 倍；松手或取消后收起。
 */
@Composable
internal fun BackArrow() {
    if (!BackFx.gesture.value) return
    val p = BackFx.progress.value.coerceAtLeast(0f)
    val released = BackFx.released.value
    val colors = KcTheme.colors
    val density = LocalDensity.current
    val armed = p >= THRESHOLD
    val shown by animateFloatAsState(if (released || p <= 0.001f) 0f else 1f, tween(140), label = "arrowShown")
    val armScale by animateFloatAsState(if (armed) 1.14f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "arrowArm")
    if (shown <= 0.01f) return
    val appear = (p / 0.12f).coerceIn(0f, 1f)
    val size = 40.dp
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset {
                    val s = size.roundToPx()
                    // 从屏幕外滑进来，停在离左缘 12dp 的地方（整个圆钮都在屏幕内）
                    val x = with(density) { (-size + (size + 12.dp) * appear).roundToPx() }
                    IntOffset(x, (BackFx.touchY.floatValue - s / 2f).roundToInt())
                }
                .graphicsLayer {
                    val scale = (0.5f + 0.5f * appear) * armScale
                    scaleX = scale
                    scaleY = scale
                    alpha = shown
                }
                .softShadow(blur = 8.dp, offsetY = 3.dp, color = Color.Black.copy(alpha = 0.18f), corner = size / 2)
                .size(size)
                .background(if (armed) colors.accent else colors.raised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            KcIcon(
                KcIcons.arrowLeft,
                tint = if (armed) Color.White else colors.text,
                size = 20.dp,
            )
        }
    }
}
