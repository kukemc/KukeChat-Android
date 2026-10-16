package ink.kuke.chat.feature.auth

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * 积木画板（设计稿 template.html 的 sync / morph / switchBlocks / leave / arrive / launch / 拖动吸附 / 运行）：
 *   · 文字变了：重新排版，把每个零件的位置 / 宽度从当前值插值到新值（320ms），旧字淡出、新字淡入；高度不变，下面的积木不跳。
 *   · 登录 ⇄ 注册：共有的积木原地变形；独有的往右下脱离淡出（错开 40ms）；新的从左下错开飞入、带一点回弹吸附上去。
 *   · 注册页填昵称的第一个字时「将 [昵称] 设为」接到「说」下面，清空时脱离。
 *   · 正在飞进来的积木先不接缝，落下那一刻再接上。
 *   · 拖动：按住一块往外拖，它和下面那截一起被拿起（放大 1.04 + 阴影）；靠近别的积木 40 单位内出现灰色剪影、
 *     插入点下面的积木让位；松手吸附上去或者留在原地成一串。帽子只能在最上面。
 *   · 点一下积木（或者点登录 / 创建账号）就从上到下「运行」那一串：逐块亮黄边，「说」冒出对话气泡。
 *   · 系统关了动画（开发者选项里动画时长为 0）：全部瞬时。
 * 坐标是积木坐标（Scratch 1 单位）；画的时候按 [BlockStage] 的缩放画到屏幕上。
 */

private val LeaveEase = CubicBezierEasing(0.4f, 0f, 0.7f, 0.2f)
private val SnapEase = CubicBezierEasing(0.3f, 1.22f, 0.5f, 1f)
private val PopEase = CubicBezierEasing(0.19f, 1.18f, 0.22f, 1f)
private val LiftEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val FadeOutEase = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val FadeInEase = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private const val MORPH_MS = 320
private const val SNAP_RADIUS = 40f
private const val LIFT_SCALE = 1.04f
private val LayerPaint = Paint()

/** 主串左上角（设计稿 homeXY = (26, 10)，再加 scratchblocks SVG 里积木的 (2, 1) 偏移） */
internal const val HOME_X = 28f
internal const val HOME_Y = 11f

private val BOUND = setOf("say", "pw", "nick", "ifc")

@Stable
internal class LiveBlock(val id: String, layout: BlockLayout, var code: String) {
    var from by mutableStateOf(layout)
    var to by mutableStateOf(layout)
    /** 变形进度（线性时间 0..1，几何用 AuthEase 插值） */
    val morph = AnimFloat(1f)
    val x = AnimFloat(HOME_X)
    val y = AnimFloat(HOME_Y)
    val alpha = AnimFloat(1f)
    val scale = AnimFloat(1f)
    val glow = AnimFloat(0f)
    var arriving by mutableStateOf(false)
    /** 等 layout 之后再起飞（值 = 延迟 ms） */
    var pendingLaunch: Int? = null
    private var targetX = HOME_X
    private var targetY = HOME_Y
    val paths = BlockPaths()
    val isHat get() = id == "hat"

    fun current(): BlockLayout {
        val t = morph.value
        return if (t >= 1f) to else lerpLayout(from, to, AuthEase.transform(t))
    }

    /** 旧字 / 新字的透明度（旧字 150ms 淡出；新字晚 40ms、200ms 淡入） */
    fun fades(): Pair<Float, Float> {
        if (morph.value >= 1f) return 0f to 1f
        val ms = morph.value * MORPH_MS
        val o = 1f - FadeOutEase.transform((ms / 150f).coerceIn(0f, 1f))
        val n = FadeInEase.transform(((ms - 40f) / 200f).coerceIn(0f, 1f))
        return o to n
    }

    /** 记下落点，返回落点有没有变 */
    fun retarget(tx: Float, ty: Float): Boolean {
        val changed = tx != targetX || ty != targetY
        targetX = tx
        targetY = ty
        return changed
    }
}

/** 一串积木：左上角（积木坐标）+ 从上到下的积木 */
@Stable
internal class StackModel(x: Float, y: Float, blocks: List<LiveBlock>) {
    var x by mutableFloatStateOf(x)
    var y by mutableFloatStateOf(y)
    val blocks = mutableStateListOf<LiveBlock>().apply { addAll(blocks) }
    val height get() = blocks.sumOf { it.to.body.toDouble() }.toFloat()
    fun topOf(i: Int): Float { var yy = y; for (k in 0 until i) yy += blocks[k].to.body; return yy }
}

internal enum class SnapType { Insert, Top }

/** 吸附点：插到 [stack] 的第 [at] 块前面（Insert），或者把整串顶到 [stack] 上面（Top）；(gx, gy) 是剪影位置 */
internal class Snap(val type: SnapType, val stack: StackModel, val at: Int, val gx: Float, val gy: Float)

/** 正被拖着的那截：(gx, gy) 是手指相对这截左上角的位置 */
@Stable
internal class DragGroup(val blocks: List<LiveBlock>, val gx: Float, val gy: Float, x: Float, y: Float) {
    var x by mutableFloatStateOf(x)
    var y by mutableFloatStateOf(y)
    val lift = AnimFloat(0f)
    val hat get() = blocks.first().isHat
    val height get() = blocks.sumOf { it.to.body.toDouble() }.toFloat()
}

@Stable
internal class BlockScene(
    private val tm: TextMeasurer,
    private val scope: CoroutineScope,
    val reduceMotion: Boolean,
    initial: AuthVars,
) {
    val stacks = mutableStateListOf<StackModel>()
    val leaving = mutableStateListOf<LiveBlock>()
    var drag by mutableStateOf<DragGroup?>(null)
        private set
    var preview by mutableStateOf<Snap?>(null)
        private set
    var vars = initial
        private set
    val boolT = AnimFloat(if (initial.passwordsMatch) 1f else 0f)
    val boolPulse = AnimFloat(0f)
    private var boolOn = initial.passwordsMatch

    /** 画板大小（积木坐标），绘制时更新，放下积木时用来把它拉回画板内 */
    var stageW = 0f
    var stageH = 0f

    // 运行
    var bubbleText by mutableStateOf<TextLayoutResult?>(null)
        private set
    var bubbleBlock by mutableStateOf<LiveBlock?>(null)
        private set
    val bubbleScale = AnimFloat(1f)
    val bubbleAlpha = AnimFloat(0f)
    private var runJob: Job? = null

    init {
        stacks += StackModel(HOME_X, HOME_Y, AuthBlocks.wantIds(initial).map { make(it) })
        layout(instant = true)
    }

    private fun make(id: String): LiveBlock {
        val s = AuthBlocks.spec(id, vars)
        return LiveBlock(id, layoutSpec(s, tm), s.code)
    }

    private fun allLive(): List<LiveBlock> = stacks.flatMap { it.blocks } + (drag?.blocks ?: emptyList())

    private fun findStack(id: String): StackModel? = stacks.firstOrNull { s -> s.blocks.any { it.id == id } }

    /** 表单变了（每帧最多一次，由 LaunchedEffect 合并） */
    fun update(v: AuthVars) {
        if (v == vars) return
        val modeChanged = v.register != vars.register
        vars = v
        if (modeChanged) switchBlocks() else sync(withLayout = true)
    }

    private fun sync(withLayout: Boolean) {
        for (lb in allLive()) {
            if (lb.id !in BOUND) continue
            val s = AuthBlocks.spec(lb.id, vars)
            if (s.code == lb.code) continue
            lb.code = s.code
            morph(lb, layoutSpec(s, tm))
        }
        setBool()
        if (vars.register && withLayout) {
            val has = vars.nick.isNotEmpty()
            val nick = allLive().firstOrNull { it.id == "nick" }
            if (has && nick == null) {
                val s = findStack("say") ?: findStack("hat") ?: stacks.firstOrNull()
                    ?: StackModel(HOME_X, HOME_Y, emptyList()).also { stacks += it }
                val sayAt = s.blocks.indexOfFirst { it.id == "say" }
                val nb = make("nick")
                s.blocks.add(if (sayAt >= 0) sayAt + 1 else s.blocks.size, nb)
                arrive(nb, 0)
                layout()
            } else if (!has && nick != null) {
                leave(nick, 0)
                layout()
            }
        }
    }

    private fun morph(lb: LiveBlock, next: BlockLayout) {
        val cur = lb.current()
        if (reduceMotion || !sameStructure(cur, next)) {
            lb.from = next; lb.to = next; lb.morph.snap(1f)
            return
        }
        lb.from = cur
        lb.to = next
        lb.morph.snap(0f)
        lb.morph.animate(scope, 1f, tween(MORPH_MS, easing = LinearEasing))
    }

    private fun setBool() {
        val on = vars.passwordsMatch
        if (on == boolOn) return
        boolOn = on
        if (reduceMotion) { boolT.snap(if (on) 1f else 0f); return }
        boolT.animate(scope, if (on) 1f else 0f, tween(320, easing = AuthEase))
        if (on) {
            // 变绿那一下轻轻亮一下（brightness 1 → 1.25 → 1）
            boolPulse.snap(0f)
            boolPulse.animate(scope, 0f, keyframes { durationMillis = 520; 0f at 0; 1f at 260; 0f at 520 })
        }
    }

    private fun leave(lb: LiveBlock, delayMs: Int) {
        if (drag?.blocks?.contains(lb) == true) endDrag(cancelled = true)
        for (s in stacks) s.blocks.remove(lb)
        stacks.removeAll { it.blocks.isEmpty() }
        lb.arriving = false
        lb.pendingLaunch = null
        lb.glow.snap(0f)
        if (reduceMotion) return
        leaving += lb
        val spec = tween<Float>(320, delayMs, LeaveEase)
        lb.x.animate(scope, lb.x.value + 34f, spec)
        lb.y.animate(scope, lb.y.value + 8f, spec)
        lb.scale.animate(scope, 0.98f, spec)
        lb.alpha.animate(scope, 0f, tween(240, delayMs, CssEase))
        scope.launch {
            delay(delayMs + 380L)
            leaving.remove(lb)
        }
    }

    private fun arrive(lb: LiveBlock, delayMs: Int) {
        if (reduceMotion) return
        lb.arriving = true
        lb.pendingLaunch = delayMs
    }

    /** 按模型摆好：已经在场的平滑移过去（.4s），刚建的从左下方起飞；有插入预览时下面的积木让位 */
    private fun layout(instant: Boolean = false) {
        val p = preview
        val gap = drag?.height ?: 0f
        for (s in stacks) {
            var y = s.y
            s.blocks.forEachIndexed { i, lb ->
                if (p != null && p.type == SnapType.Insert && p.stack === s && p.at == i) y += gap
                place(lb, s.x, y, instant)
                y += lb.to.body
            }
        }
    }

    private fun place(lb: LiveBlock, tx: Float, ty: Float, instant: Boolean) {
        val changed = lb.retarget(tx, ty)
        val pending = lb.pendingLaunch
        when {
            pending != null -> launch(lb, tx, ty, pending)
            instant || reduceMotion -> { lb.x.snap(tx); lb.y.snap(ty) }
            lb.arriving && !changed -> Unit // 还在飞，落点没变
            else -> {
                val spec = if (lb.arriving) tween(460, easing = SnapEase) else tween<Float>(400, easing = AuthEase)
                lb.x.animate(scope, tx, spec)
                lb.y.animate(scope, ty, spec)
            }
        }
    }

    private fun launch(lb: LiveBlock, fx: Float, fy: Float, delayMs: Int) {
        lb.pendingLaunch = null
        lb.x.snap(fx - 30f); lb.y.snap(fy + 22f); lb.scale.snap(0.98f); lb.alpha.snap(0f)
        val spec = tween<Float>(460, delayMs, SnapEase)
        lb.x.animate(scope, fx, spec)
        lb.y.animate(scope, fy, spec)
        lb.scale.animate(scope, 1f, spec)
        lb.alpha.animate(scope, 1f, tween(200, delayMs, CssEase))
        scope.launch {
            delay(delayMs + 300L)
            lb.arriving = false
        }
    }

    private fun switchBlocks() {
        cancelRun()
        if (drag != null) endDrag(cancelled = true)
        val want = AuthBlocks.wantIds(vars)
        var k = 0
        for (lb in stacks.flatMap { it.blocks }) if (lb.id !in want) leave(lb, k++ * 40)
        val kept = stacks.flatMap { it.blocks }.associateBy { it.id }
        for (s in stacks) s.blocks.removeAll { it.id in want }
        stacks.removeAll { it.blocks.isEmpty() }
        var i = 0
        val home = StackModel(HOME_X, HOME_Y, want.map { id -> kept[id] ?: make(id).also { arrive(it, 150 + i++ * 70) } })
        stacks.add(0, home)
        sync(withLayout = false)
        layout()
    }

    /** 某块积木的顶边（设计稿坐标：SVG 框的顶）和 SVG 高度，键盘弹起时镜头跟随用 */
    fun blockSpan(id: String): Pair<Float, Float>? {
        val s = findStack(id) ?: return null
        val i = s.blocks.indexOfFirst { it.id == id }
        return (s.topOf(i) - 1f) to s.blocks[i].to.fullHeight
    }

    // ───────── 拖动 / 吸附 ─────────

    /** 手指下面是哪块积木（上层的先算；串里下面的积木盖在上面的之上） */
    fun hitTest(x: Float, y: Float): Pair<StackModel, Int>? {
        for (si in stacks.indices.reversed()) {
            val s = stacks[si]
            for (i in s.blocks.indices.reversed()) {
                val lb = s.blocks[i]
                if (lb.arriving) continue
                val l = lb.to
                if (x >= lb.x.value && x <= lb.x.value + l.w && y >= lb.y.value && y <= lb.y.value + l.body) return s to i
            }
        }
        return null
    }

    fun startDrag(s: StackModel, i: Int, px: Float, py: Float) {
        if (drag != null || i !in s.blocks.indices) return
        val topY = s.topOf(i)
        val picked = s.blocks.subList(i, s.blocks.size).toList()
        repeat(picked.size) { s.blocks.removeAt(s.blocks.size - 1) }
        if (s.blocks.isEmpty()) stacks.remove(s)
        val d = DragGroup(picked, px - s.x, py - topY, s.x, topY)
        picked.forEach { it.scale.snap(1f) }
        drag = d
        preview = null
        if (reduceMotion) d.lift.snap(1f) else d.lift.animate(scope, 1f, tween(160, easing = LiftEase))
        positionDrag(d)
        layout()
    }

    fun moveDrag(px: Float, py: Float) {
        val d = drag ?: return
        d.x = px - d.gx
        d.y = py - d.gy
        positionDrag(d)
        val snap = findSnap(d)
        val p = preview
        val same = snap != null && p != null && snap.stack === p.stack && snap.at == p.at && snap.type == p.type
        if (same || (snap == null && p == null)) return
        preview = snap
        layout()
    }

    private fun positionDrag(d: DragGroup) {
        var yy = 0f
        for (lb in d.blocks) {
            lb.x.snap(d.x)
            lb.y.snap(d.y + yy)
            yy += lb.to.body
        }
    }

    private fun findSnap(d: DragGroup): Snap? {
        var best: Snap? = null
        var bd = SNAP_RADIUS
        for (s in stacks) {
            if (!d.hat) {
                var yy = s.y
                for (at in 1..s.blocks.size) {
                    yy += s.blocks[at - 1].to.body
                    val dist = hypot(d.x - s.x, d.y - yy)
                    if (dist < bd) { bd = dist; best = Snap(SnapType.Insert, s, at, s.x, yy) }
                }
            }
            // 帽子上面不能再接东西
            if (s.blocks.firstOrNull()?.isHat != true) {
                val dist = hypot(d.x - s.x, d.y + d.height - s.y)
                if (dist < bd) { bd = dist; best = Snap(SnapType.Top, s, 0, s.x, s.y - d.height) }
            }
        }
        return best
    }

    fun endDrag(cancelled: Boolean) {
        val d = drag ?: return
        val snap = if (cancelled) null else preview
        preview = null
        drag = null
        val target: StackModel = when {
            snap != null && snap.stack in stacks && snap.type == SnapType.Insert -> {
                snap.stack.blocks.addAll(snap.at.coerceAtMost(snap.stack.blocks.size), d.blocks); snap.stack
            }
            snap != null && snap.stack in stacks -> {
                snap.stack.blocks.addAll(0, d.blocks); snap.stack.y -= d.height; snap.stack
            }
            else -> StackModel(d.x, d.y, d.blocks).also { stacks += it }
        }
        // 拉回画板内（同设计稿：左右留 6，帽子顶上留 14；坐标换成 SVG 框）
        if (stageW > 0f && stageH > 0f) {
            val tw = target.blocks.maxOf { it.to.w } + 4f
            val th = target.height + 10f
            val minY = if (target.blocks.first().isHat) 14f else 2f
            target.x = (target.x - 2f).coerceIn(6f, max(6f, stageW - tw - 6f)).let { floor(it + 0.5f) } + 2f
            target.y = (target.y - 1f).coerceIn(minY, max(minY, stageH - th)).let { floor(it + 0.5f) } + 1f
        }
        // 刚放下的那串放到最上层
        stacks.remove(target)
        stacks += target
        // 从抬起的样子（放大 1.04、以手指为中心）平滑落到位
        val sc = if (reduceMotion) 1f else 1f + (LIFT_SCALE - 1f) * d.lift.value
        var yy = 0f
        for (lb in d.blocks) {
            lb.x.snap(d.x + d.gx - d.gx * sc)
            lb.y.snap(d.y + d.gy + (yy - d.gy) * sc)
            lb.scale.snap(sc)
            if (sc != 1f) lb.scale.animate(scope, 1f, tween(400, easing = AuthEase))
            yy += lb.to.body
        }
        layout()
    }

    // ───────── 运行 ─────────

    /** 运行主串（帽子开头的那串；点登录 / 创建账号时） */
    fun runMain() {
        val s = stacks.firstOrNull { it.blocks.firstOrNull()?.isHat == true } ?: return
        run(s.blocks.toList())
    }

    fun click(s: StackModel) {
        if (s in stacks) run(s.blocks.toList())
    }

    private fun run(ids: List<LiveBlock>) {
        if (runJob?.isActive == true || ids.isEmpty()) return
        runJob = scope.launch {
            var shown = false
            try {
                for (lb in ids) {
                    if (allLive().none { it === lb }) break
                    if (reduceMotion) lb.glow.snap(1f) else lb.glow.animate(scope, 1f, tween(120))
                    if (lb.id == "say") {
                        shown = true
                        bubbleText = tm.measure(
                            AnnotatedString(AuthBlocks.sayText(vars)),
                            TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            softWrap = false, maxLines = 1, density = Density(1f, 1f),
                        )
                        bubbleBlock = lb
                        bubbleAlpha.snap(1f)
                        if (reduceMotion) bubbleScale.snap(1f) else { bubbleScale.snap(0.6f); bubbleScale.animate(scope, 1f, tween(300, easing = PopEase)) }
                    }
                    delay(if (lb.id == "say") 700L else 420L)
                    if (reduceMotion) lb.glow.snap(0f) else lb.glow.animate(scope, 0f, tween(120))
                }
                if (shown) {
                    delay(1300)
                    if (!reduceMotion) {
                        bubbleAlpha.animate(scope, 0f, tween(200))
                        bubbleScale.animate(scope, 0.9f, tween(200))
                        delay(220)
                    }
                }
            } finally {
                ids.forEach { it.glow.snap(0f) }
                bubbleAlpha.snap(0f)
                bubbleBlock = null
            }
        }
    }

    private fun cancelRun() {
        runJob?.cancel()
        runJob = null
        allLive().forEach { it.glow.snap(0f) }
        bubbleAlpha.snap(0f)
        bubbleBlock = null
    }
}

/**
 * 画积木。[unit] = 每个积木单位多少 dp（设计稿手机 0.8，键盘弹起 0.6）；[panY]：镜头上移（dp）；
 * [mask]：0..1，上下沿淡出（键盘弹起时）。三个都是 lambda，只在绘制 / 手势里读，动画时不触发重组。
 */
@Composable
internal fun BlockStage(scene: BlockScene, dark: Boolean, unit: () -> Float, panY: () -> Float, mask: () -> Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .graphicsLayer { compositingStrategy = if (mask() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
            .pointerInput(scene) {
                val slop = viewConfiguration.touchSlop
                fun local(p: Offset): Offset {
                    val k = unit() * density
                    return Offset(p.x / k, (p.y - panY() * density) / k)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (scene.drag != null) return@awaitEachGesture
                    val p0 = local(down.position)
                    // 没点到积木：不管（交给外层）
                    val (stack, at) = scene.hitTest(p0.x, p0.y) ?: return@awaitEachGesture
                    down.consume()
                    var dragging = false
                    var finished = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) {
                                if (dragging) scene.endDrag(cancelled = false) else scene.click(stack)
                                finished = true
                                c.consume()
                                break
                            }
                            if (!dragging && (c.position - down.position).getDistance() > slop) {
                                scene.startDrag(stack, at, p0.x, p0.y)
                                dragging = scene.drag != null
                                if (!dragging) break
                            }
                            if (dragging) {
                                val p = local(c.position)
                                scene.moveDrag(p.x, p.y)
                                c.consume()
                            }
                        }
                    } finally {
                        if (dragging && !finished) scene.endDrag(cancelled = true)
                    }
                }
            },
    ) {
        val k = unit() * density
        val dy = panY() * density
        scene.stageW = size.width / k
        scene.stageH = size.height / k
        withTransform({
            translate(0f, dy)
            scale(k, k, Offset.Zero)
        }) {
            val d = scene.drag
            val p = scene.preview
            // 吸附预览：被拖那截的灰色剪影（在所有积木下面）
            if (d != null && p != null) {
                val ghost = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.2f)
                var yy = p.gy
                for (lb in d.blocks) {
                    translate(p.gx, yy) { drawPath(lb.paths.outlineFor(lb.current()), ghost) }
                    yy += lb.to.body
                }
            }
            for (lb in scene.leaving) drawLive(lb, lb.current(), 0f, 0f, scene)
            for (s in scene.stacks) {
                val gapAt = if (d != null && p != null && p.type == SnapType.Insert && p.stack === s) p.at else -1
                drawRun(s.blocks, gapAt, scene)
            }
            // 被拖着的那截（最上层）：以手指为中心放大 1.04，带阴影
            if (d != null) {
                val lift = d.lift.value
                val sc = if (scene.reduceMotion) 1f else 1f + (LIFT_SCALE - 1f) * lift
                scale(sc, sc, Offset(d.x + d.gx, d.y + d.gy)) {
                    drawLiftShadow(d.blocks, lift, dark)
                    drawRun(d.blocks, -1, scene)
                }
            }
            // 说话气泡（盖在所有积木上面）
            val bb = scene.bubbleBlock
            val bt = scene.bubbleText
            if (bb != null && bt != null && scene.bubbleAlpha.value > 0f) {
                val l = bb.current()
                val maxLeft = size.width / k - (bt.size.width + 30f) - 6f
                val left = min(bb.x.value + l.w - 24f, max(6f, maxLeft))
                drawSayBubble(bt, left, bb.y.value + 5f, scene.bubbleScale.value, scene.bubbleAlpha.value)
            }
        }
        val m = mask()
        if (m > 0f) {
            val top = 18.dp.toPx()
            val bottom = 26.dp.toPx()
            val edge = Color.Black.copy(alpha = 1f - m)
            drawRect(
                Brush.verticalGradient(
                    0f to edge,
                    (top / size.height).coerceIn(0f, 0.5f) to Color.Black,
                    (1f - bottom / size.height).coerceIn(0.5f, 1f) to Color.Black,
                    1f to edge,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** 画一串（[gapAt]：这一块上面断开——插入预览时下面的积木让位，不接缝） */
private fun DrawScope.drawRun(list: List<LiveBlock>, gapAt: Int, scene: BlockScene) {
    val n = list.size
    if (n == 0) return
    val cur = List(n) { list[it].current() }
    for (i in 0 until n) {
        val lb = list[i]
        val joinPrev = i > 0 && i != gapAt && !lb.arriving && !list[i - 1].arriving
        val joinNext = i < n - 1 && i + 1 != gapAt && !lb.arriving && !list[i + 1].arriving
        val above = if (joinPrev) min(cur[i].w, cur[i - 1].w) else 0f
        val below = if (joinNext) min(cur[i].w, cur[i + 1].w) else 0f
        drawLive(lb, cur[i], above, below, scene)
    }
}

private fun DrawScope.drawLive(lb: LiveBlock, l: BlockLayout, above: Float, below: Float, scene: BlockScene) {
    val a = lb.alpha.value
    if (a <= 0f) return
    val s = lb.scale.value
    translate(lb.x.value, lb.y.value) {
        scale(s, s, Offset.Zero) {
            val (oa, na) = lb.fades()
            val body: DrawScope.() -> Unit = {
                drawBlock(
                    l, lb.paths, above, below,
                    old = if (lb.morph.value < 1f) lb.from else null, oldAlpha = oa, newAlpha = na,
                    boolT = scene.boolT.value, boolPulse = scene.boolPulse.value, glow = lb.glow.value,
                )
            }
            if (a < 1f) {
                drawIntoCanvas { c ->
                    LayerPaint.alpha = a
                    c.saveLayer(Rect(-24f, -24f, l.w + 24f, l.fullHeight + 24f), LayerPaint)
                    body()
                    c.restore()
                }
            } else {
                body()
            }
        }
    }
}

/**
 * 抬起时的阴影（设计稿 drop-shadow(0 14px 14px …) + (0 2px 3px …)）：几层逐渐变粗的半透明剪影描边近似模糊，
 * 不用 MaskFilter（低版本硬件加速不支持），各机型一致。
 */
private fun DrawScope.drawLiftShadow(blocks: List<LiveBlock>, strength: Float, dark: Boolean) {
    if (strength <= 0f) return
    val base = if (dark) Color.Black else Color(0xFF121626)
    val soft = (if (dark) 0.11f else 0.05f) * strength
    for (r in 5 downTo 1) {
        for (lb in blocks) {
            val o = lb.paths.outlineFor(lb.current())
            translate(lb.x.value, lb.y.value + 14f * strength) {
                drawPath(o, base.copy(alpha = soft), style = Stroke(width = r * 5f, join = StrokeJoin.Round))
            }
        }
    }
    for (lb in blocks) {
        translate(lb.x.value, lb.y.value + 2f * strength) {
            drawPath(lb.paths.outlineFor(lb.current()), base.copy(alpha = (if (dark) 0.4f else 0.16f) * strength))
        }
    }
}

/**
 * 背景里的虚拟积木描边：一张画布、6 条路径。慢慢漂（44–73 秒一个来回，几个 dp），
 * 用 15fps 的计时驱动就够了（每帧位移远小于 1px），省电。[offsetY]：状态栏比设计稿（40dp）高出的部分。
 */
@Composable
internal fun OutlineLayer(color: Color, strength: Float, offsetY: Dp, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val shapes = remember(tm) { OutlineBlocks.shapes.mapValues { outlineShape(it.value, tm) } }
    var time by remember { mutableFloatStateOf(0f) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                delay(66)
                time = (System.nanoTime() - start) / 1e9f
            }
        }
    }
    val easeInOut = remember { CubicBezierEasing(0.42f, 0f, 0.58f, 1f) }
    Canvas(modifier.graphicsLayer()) {
        val t = time
        val dy0 = offsetY.toPx()
        val d = density
        OutlineBlocks.phone.forEachIndexed { i, p ->
            val sh = shapes[p.shape] ?: return@forEachIndexed
            // 同设计稿：时长 44 + (i·13 mod 30) 秒、负延迟 (i·7 mod 40) 秒、位移 dx/dy，ease-in-out 往返
            val period = 44f + (i * 13) % 30
            val lag = ((i * 7) % 40).toFloat()
            val dx = ((i * 37) % 17 - 8).toFloat()
            val dy = (-6 - (i * 23) % 11).toFloat()
            val phase = if (reduceMotion) 0f else {
                val e = (t + lag) / period
                val cycle = floor(e).toInt()
                val local = e - cycle
                easeInOut.transform(if (cycle % 2 == 0) local else 1f - local)
            }
            val s = p.scale
            val tx = (p.x + dx * phase) * d
            val ty = (p.y + dy * phase) * d + dy0
            val pivot = Offset(sh.svgW * s * d / 2f, sh.svgH * s * d / 2f)
            withTransform({
                translate(tx, ty)
                rotate(p.rotate, pivot)
                scale(s * d, s * d, Offset.Zero)
            }) {
                drawPath(sh.path, color.copy(alpha = p.alpha * strength), style = Stroke(width = 1.1f / s))
            }
        }
    }
}
