package ink.kuke.chat.feature.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.components.softShadow
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private const val SAMPLE_MS = 100L
private const val MIN_MS = 1_000L
private const val MAX_MS = 60_000L
/** 录音条上同时显示的波形根数（每根 = 一次采样 = 100ms） */
private const val LIVE_BARS = 24

/**
 * 录音机：AAC / m4a，16kHz、32kbps。后台线程每 100ms 取一次振幅，按分贝映射到 0–100（-48dB 以下算静音）；
 * 录音条上的实时波形和发出去的 metadata.voice.waveform 用的是同一份采样。
 */
class VoiceRecorder(private val context: Context) {
    data class Result(val file: File, val durationMs: Long, val waveform: List<Int>)

    @Volatile
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = ArrayList<Int>()
    private var sampler: Thread? = null

    /** 已经录了多久（没在录 = 0） */
    val elapsedMs: Long get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** 第 [from] 个以后新采到的振幅 */
    fun samples(from: Int): List<Int> = synchronized(levels) {
        if (from >= levels.size) emptyList() else ArrayList(levels.subList(from, levels.size))
    }

    fun start(): Boolean = runCatching {
        val target = File.createTempFile("voice", ".m4a", context.cacheDir)
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(32_000)
        r.setAudioSamplingRate(16_000)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        synchronized(levels) { levels.clear() }
        file = target
        startedAt = SystemClock.elapsedRealtime()
        recorder = r
        sampler = Thread {
            while (recorder === r) {
                val amplitude = runCatching { r.maxAmplitude }.getOrDefault(0)
                synchronized(levels) { levels += amplitudeLevel(amplitude) }
                try {
                    Thread.sleep(SAMPLE_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "voice-level"
            start()
        }
        true
    }.getOrElse {
        recorder = null
        false
    }

    fun stop(): Result? {
        val r = recorder ?: return null
        recorder = null
        val duration = SystemClock.elapsedRealtime() - startedAt
        // 先等采样线程停下，再释放录音机，免得它读一个已释放的对象
        sampler?.let {
            it.interrupt()
            runCatching { it.join(200) }
        }
        sampler = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val target = file ?: return null
        if (!ok) {
            target.delete()
            return null
        }
        val all = synchronized(levels) { levels.toList() }
        // 压成最多 40 根：每段取最大值
        val n = min(40, all.size)
        val waveform = List(n) { i ->
            val from = i * all.size / n
            val to = max((i + 1) * all.size / n, from + 1)
            (from until to).maxOf { all[it] }
        }
        return Result(target, duration, waveform)
    }

    private fun amplitudeLevel(amplitude: Int): Int {
        if (amplitude <= 0) return 0
        val db = 20.0 * log10(amplitude / 32767.0)
        return (((db + 48.0) / 48.0).coerceIn(0.0, 1.0) * 100).toInt()
    }
}

/**
 * 一次「按住说话」：按住中 / 已锁定，手指所在区域（✕ 取消 / 锁定），计时与实时振幅。
 * 输入卡里的 [HoldToTalk] 管手势，[VoiceRecordingOverlay] 管显示，两边共用这一份。
 */
@Stable
internal class VoiceSession(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { Idle, Holding, Locked }
    enum class Zone { None, Cancel, Lock }

    private val recorder = VoiceRecorder(context)
    var phase by mutableStateOf(Phase.Idle)
        private set
    var zone by mutableStateOf(Zone.None)
    /** 已录秒数（只在跳秒时变，计时文字不必每帧重组） */
    var seconds by mutableIntStateOf(0)
        private set
    /** 最近一段振幅（0–100），录音条上从右往左滚动 */
    val bars = mutableStateListOf<Int>()
    /** 最近一次采样到达的帧时间、当前帧时间：波形据此在两次采样之间平滑左移 */
    var sampleAt by mutableLongStateOf(0L)
        private set
    var frameAt by mutableLongStateOf(0L)
        private set
    /** 平滑后的音量 0–1，麦克风外圈跟着它起伏 */
    var level by mutableFloatStateOf(0f)
        private set
    /** 手指相对按下点的水平位移（px），大麦克风轻微跟手 */
    var dragX by mutableFloatStateOf(0f)

    // 浮层上 ✕ 的右缘、锁的左缘（窗口坐标 px），手势判断「滑到 ✕ / 滑到锁」用；浮层第一帧量到之前为 NaN
    var cancelEdge = Float.NaN
    var lockEdge = Float.NaN

    var send: suspend (File, Long, List<Int>) -> Boolean = { _, _, _ -> false }
    var onSent: () -> Unit = {}
    private var consumed = 0

    fun start(): Boolean {
        if (phase != Phase.Idle) return false
        if (!recorder.start()) return false
        bars.clear()
        consumed = 0
        seconds = 0
        level = 0f
        dragX = 0f
        zone = Zone.None
        phase = Phase.Holding
        return true
    }

    /** 右滑到锁上松手：继续录，等点发送 / 取消 */
    fun lock() {
        if (phase != Phase.Holding) return
        phase = Phase.Locked
        zone = Zone.None
        dragX = 0f
    }

    /** 每帧调一次：取新采样、更新计时，到 60 秒自动发送 */
    fun tick(frameMs: Long) {
        if (phase == Phase.Idle) return
        frameAt = frameMs
        val elapsed = recorder.elapsedMs
        val s = (elapsed / 1000).toInt()
        if (s != seconds) seconds = s
        val fresh = recorder.samples(consumed)
        if (fresh.isNotEmpty()) {
            consumed += fresh.size
            bars.addAll(fresh)
            if (bars.size > LIVE_BARS + 1) bars.removeRange(0, bars.size - LIVE_BARS - 1)
            sampleAt = frameMs
        }
        val target = (bars.lastOrNull() ?: 0) / 100f
        level += (target - level) * 0.2f
        if (elapsed >= MAX_MS) finish(send = true)
    }

    /** 结束录音：[send] = 发出去（不足 1 秒提示太短），否则丢弃。重复调用无副作用 */
    fun finish(send: Boolean) {
        if (phase == Phase.Idle) return
        phase = Phase.Idle
        zone = Zone.None
        dragX = 0f
        val result = recorder.stop()
        if (!send) {
            result?.file?.delete()
            return
        }
        if (result == null || result.durationMs < MIN_MS) {
            result?.file?.delete()
            toast(context, "说话时间太短")
            return
        }
        scope.launch {
            try {
                if (this@VoiceSession.send(result.file, result.durationMs, result.waveform)) onSent()
            } finally {
                result.file.delete()
            }
        }
    }
}

/**
 * 按住说话：按下开始录，上滑超过 72dp 或左滑到 ✕ = 松手取消，右滑到锁 = 松手锁定继续录，原地松手发送。
 * 起点离 ✕ / 锁太近时，至少再滑 48dp 才算进入。进出区域轻震一下。
 */
@Composable
internal fun HoldToTalk(session: VoiceSession) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val origin = remember { floatArrayOf(0f, 0f) }
    val nudge = with(density) { 48.dp.toPx() }
    val upCancel = with(density) { 72.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panelMuted)
            .onGloballyPositioned { val p = it.positionInWindow(); origin[0] = p.x; origin[1] = p.y }
            .pointerInput(session) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    if (!session.start()) {
                        Haptics.reject(view)
                        return@awaitEachGesture
                    }
                    down.consume()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val startX = origin[0] + down.position.x
                    var total = Offset.Zero
                    try {
                        while (session.phase == VoiceSession.Phase.Holding) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            total += change.positionChange()
                            change.consume()
                            // ✕ / 锁的位置取浮层实际量到的；还没量到就按屏宽估
                            val width = view.width.toFloat()
                            val cancelX = min((session.cancelEdge.takeIf { !it.isNaN() } ?: (width * 0.28f)) + margin, startX - nudge)
                            val lockX = max((session.lockEdge.takeIf { !it.isNaN() } ?: (width * 0.72f)) - margin, startX + nudge)
                            val x = startX + total.x
                            val next = when {
                                x < cancelX -> VoiceSession.Zone.Cancel
                                x > lockX -> VoiceSession.Zone.Lock
                                -total.y > upCancel -> VoiceSession.Zone.Cancel
                                else -> VoiceSession.Zone.None
                            }
                            if (next != session.zone) {
                                session.zone = next
                                if (next != VoiceSession.Zone.None) Haptics.tick(view)
                            }
                            session.dragX = total.x
                        }
                    } catch (e: CancellationException) {
                        session.finish(send = false)
                        throw e
                    }
                    if (session.phase == VoiceSession.Phase.Holding) {
                        when (session.zone) {
                            VoiceSession.Zone.Cancel -> session.finish(send = false)
                            VoiceSession.Zone.Lock -> {
                                session.lock()
                                Haptics.confirm(view)
                            }
                            VoiceSession.Zone.None -> session.finish(send = true)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("按住说话", style = KcTheme.type.secondary.copy(fontWeight = FontWeight.W600), color = colors.text)
    }
}

/** 进场弹簧：[active] 变真后等 [delayMs] 再弹到 1；退场快速收回 */
@Composable
private fun rememberSpringIn(active: Boolean, delayMs: Long): State<Float> {
    val value = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            delay(delayMs)
            value.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
        } else {
            value.animateTo(0f, tween(140))
        }
    }
    return value.asState()
}

/**
 * 录音浮层（设计稿 P32）：底部通栏渐变压暗盖住输入卡；强调色实底录音条（呼吸红点 · 计时 · 实时波形，下方柔光），
 * 提示「松开发送 · 上滑到 ✕ 取消」；下排左 ✕、中间白色大麦克风（外圈随音量起伏）、右锁。
 * 手指滑到 ✕ 上它变红、录音条也变红；滑到锁上它变强调色。锁定后中间变成发送键，✕ 可点取消。
 *
 * 放在输入卡同一个 Box 里（[modifier] 给 matchParentSize），自己按底边对齐向上撑高，宽度和输入卡一样是通栏；
 * 不用 Popup —— Popup 按锚点居中对齐，锚点不在屏幕正中时右边会漏一条缝，也盖不住输入卡。
 */
@Composable
internal fun VoiceRecordingOverlay(session: VoiceSession, reveal: () -> Float, modifier: Modifier) {
    val colors = KcTheme.colors
    val active = session.phase != VoiceSession.Phase.Idle
    val locked = session.phase == VoiceSession.Phase.Locked
    val zone = session.zone

    LaunchedEffect(active) {
        while (active && isActive) withFrameMillis { session.tick(it) }
    }
    BackHandler(enabled = locked) { session.finish(send = false) }

    val pillIn = rememberSpringIn(active, 0)
    val sideIn = rememberSpringIn(active, 60)
    val micIn = rememberSpringIn(active, 30)

    Box(
        modifier
            .wrapContentHeight(Alignment.Bottom, unbounded = true)
            .fillMaxWidth()
            .requiredHeight(420.dp)
            .graphicsLayer { alpha = reveal() }
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color.Black.copy(alpha = 0.74f), 1f to Color.Black.copy(alpha = 0.86f)))
            // 录音中吞掉落在浮层上的触摸，下面的消息列表不跟着滚
            .then(if (active) Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } } else Modifier),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RecordingPill(session, zone == VoiceSession.Zone.Cancel, pillIn)
            RecordingHint(session, locked, zone)
            Row(
                Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SideButton(
                    KcIcons.close, "取消",
                    highlighted = zone == VoiceSession.Zone.Cancel,
                    highlight = colors.danger,
                    enter = sideIn,
                    onPlaced = { session.cancelEdge = it },
                    edgeRight = true,
                    onClick = if (locked) ({ session.finish(send = false) }) else null,
                )
                BigMic(session, locked, zone == VoiceSession.Zone.Cancel, micIn)
                SideButton(
                    KcIcons.lock, "锁定",
                    highlighted = zone == VoiceSession.Zone.Lock || locked,
                    highlight = colors.fill,
                    enter = sideIn,
                    onPlaced = { session.lockEdge = it },
                    edgeRight = false,
                    onClick = null,
                )
            }
        }
    }
}

/** 强调色实底录音条：呼吸红点 · 计时 · 实时波形；进入取消区变红 */
@Composable
private fun RecordingPill(session: VoiceSession, cancel: Boolean, enter: State<Float>) {
    val colors = KcTheme.colors
    val color by animateColorAsState(if (cancel) colors.danger else colors.fill, tween(180, easing = EaseSmooth), label = "pill")
    Row(
        Modifier
            .graphicsLayer {
                val t = enter.value
                scaleX = 0.86f + 0.14f * t
                scaleY = scaleX
                translationY = (1f - t) * 28.dp.toPx()
                alpha = t.coerceIn(0f, 1f)
            }
            .softShadow(22.dp, 12.dp, color.copy(alpha = 0.55f), 24.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BreathingDot(cancel)
        val s = session.seconds
        Text(
            "%02d:%02d".format(s / 60, s % 60),
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.W700, fontFeatureSettings = "tnum"),
            color = Color.White,
        )
        LiveWave(session, Modifier.width(150.dp).height(28.dp))
    }
}

/** 呼吸红点：白边红芯，0.9 秒一呼一吸；取消区里整条变红时改成白点 */
@Composable
private fun BreathingDot(cancel: Boolean) {
    val breath = rememberInfiniteTransition(label = "breath")
    val t by breath.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = EaseSmooth), RepeatMode.Reverse), label = "dot")
    Box(
        Modifier
            .size(10.dp)
            .graphicsLayer { scaleX = 0.78f + 0.3f * t; scaleY = scaleX; alpha = 0.55f + 0.45f * t }
            .background(if (cancel) Color.White else Color(0xFFFF4D4F), CircleShape)
            .then(if (cancel) Modifier else Modifier.border(1.5.dp, Color.White, CircleShape)),
    )
}

/** 实时波形：最新的一根在最右，两次采样之间按帧平滑左移；最左一根淡出 */
@Composable
private fun LiveWave(session: VoiceSession, modifier: Modifier) {
    Canvas(modifier) {
        val step = size.width / LIVE_BARS
        val bw = 3.dp.toPx()
        val minH = 3.dp.toPx()
        val frac = ((session.frameAt - session.sampleAt) / SAMPLE_MS.toFloat()).coerceIn(0f, 1f)
        val bars = session.bars
        for (i in 0..LIVE_BARS) {
            val v = bars.getOrNull(bars.size - 1 - i) ?: 0
            val cx = size.width - bw / 2f - (i + frac) * step
            if (cx < -bw) break
            val h = minH + (size.height - minH) * (v / 100f)
            val alpha = (cx / (step * 2f)).coerceIn(0f, 1f)
            drawRoundRect(
                Color.White.copy(alpha = alpha),
                Offset(cx - bw / 2f, (size.height - h) / 2f),
                Size(bw, h),
                CornerRadius(bw / 2f),
            )
        }
    }
}

@Composable
private fun RecordingHint(session: VoiceSession, locked: Boolean, zone: VoiceSession.Zone) {
    val key = when {
        zone == VoiceSession.Zone.Cancel -> "cancel"
        zone == VoiceSession.Zone.Lock -> "lock"
        session.seconds >= 50 -> "countdown"
        locked -> "locked"
        else -> "hold"
    }
    Crossfade(key, animationSpec = tween(160), label = "hint") { which ->
        val style = TextStyle(fontSize = 13.sp)
        val color = Color.White.copy(alpha = 0.78f)
        when (which) {
            "hold" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("松开发送 · 上滑到 ", style = style, color = color)
                KcIcon(KcIcons.close, tint = color, size = 14.dp)
                Text(" 取消", style = style, color = color)
            }
            "cancel" -> Text("松开手指，取消发送", style = style, color = color)
            "lock" -> Text("松开锁定，可以继续说", style = style, color = color)
            "locked" -> Text("已锁定 · 点中间发送，点 ✕ 取消", style = style, color = color)
            else -> Text("${(60 - session.seconds).coerceAtLeast(0)} 秒后自动发送", style = style, color = color)
        }
    }
}

/** 下排两侧的圆键（56dp）：手指滑到上面时放大并换成 [highlight] 色；[onPlaced] 报告朝向中间那条边的窗口 x */
@Composable
private fun SideButton(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    highlight: Color,
    enter: State<Float>,
    onPlaced: (Float) -> Unit,
    edgeRight: Boolean,
    onClick: (() -> Unit)?,
) {
    val on by animateFloatAsState(if (highlighted) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f), label = "side")
    val press = rememberInteraction()
    Box(
        Modifier
            .onGloballyPositioned { val b = it.boundsInWindow(); onPlaced(if (edgeRight) b.right else b.left) }
            .size(56.dp)
            .graphicsLayer {
                val t = enter.value
                scaleX = (0.5f + 0.5f * t) * (1f + 0.16f * on)
                scaleY = scaleX
                alpha = t.coerceIn(0f, 1f)
            }
            .pressScale(press, 0.9f)
            .background(lerp(Color.White.copy(alpha = 0.12f), highlight, on.coerceIn(0f, 1f)), CircleShape)
            .then(if (onClick != null) Modifier.clip(CircleShape).clickable(interactionSource = press, indication = null, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { KcIcon(icon, tint = Color.White, size = 24.dp, contentDescription = label) }
}

/** 中间白色大麦克风（92dp）：外两圈光晕随音量起伏，轻微跟手；锁定后换成发送键 */
@Composable
private fun BigMic(session: VoiceSession, locked: Boolean, cancel: Boolean, enter: State<Float>) {
    val colors = KcTheme.colors
    val density = LocalDensity.current
    val limit = with(density) { 36.dp.toPx() }
    val follow by animateFloatAsState((session.dragX * 0.22f).coerceIn(-limit, limit), spring(dampingRatio = 0.8f, stiffness = 700f), label = "follow")
    val shrink by animateFloatAsState(if (cancel) 0.9f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "micShrink")
    val press = rememberInteraction()
    Box(
        Modifier
            .size(136.dp)
            .graphicsLayer {
                val t = enter.value
                scaleX = (0.6f + 0.4f * t) * shrink
                scaleY = scaleX
                alpha = t.coerceIn(0f, 1f)
                translationX = follow
            },
        contentAlignment = Alignment.Center,
    ) {
        // 光晕：内圈 10dp、外圈 22dp（同设计稿 box-shadow），声音越大越往外扩
        Canvas(Modifier.size(136.dp)) {
            val l = session.level
            val core = 46.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.06f + 0.05f * l), radius = core + (22.dp.toPx()) * (1f + 0.6f * l))
            drawCircle(Color.White.copy(alpha = 0.12f + 0.06f * l), radius = core + (10.dp.toPx()) * (1f + 0.8f * l))
        }
        Box(
            Modifier
                .size(92.dp)
                .pressScale(press, 0.92f)
                .background(Color.White, CircleShape)
                .then(if (locked) Modifier.clip(CircleShape).clickable(interactionSource = press, indication = null) { session.finish(send = true) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(locked, animationSpec = tween(180), label = "micIcon") { isLocked ->
                if (isLocked) KcIcon(KcIcons.send, tint = colors.fill, size = 30.dp, contentDescription = "发送")
                else KcIcon(KcIcons.mic, tint = if (cancel) colors.muted else colors.fill, size = 32.dp, contentDescription = "录音中")
            }
        }
    }
}
