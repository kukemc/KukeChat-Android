package ink.kuke.chat.feature.shop

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ink.kuke.chat.ui.components.rememberReducedMotion
import ink.kuke.chat.ui.theme.KcTheme
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * 气泡皮肤、头像框的画法（解析在 SkinPaint.kt，图形在 SkinGlyphs.kt）。参数、时长、幅度都照搬电脑版 styles/shop.css 的 .kcsk / .kcaf。
 *
 * 聊天列表里每条消息都可能带皮肤，所以：
 *   · 只用 drawWithCache 画，不给每条消息多建 composable；路径、画笔、纹样的平铺块在缓存里，纹样是一张小图做成的平铺着色器；
 *   · 会动的皮肤才有时钟（rememberInfiniteTransition），只在绘制阶段读它：动起来只重画，不重组；
 *     会动的那一层前后各垫一个 graphicsLayer，每帧只重录这一层，底色、阴影和气泡里的文字都不重画；
 *   · LazyColumn 只组合可见的消息，屏幕外的不跑动画；同屏的气泡用 seed（消息 id / 用户 id）错开节奏；
 *   · 系统关了动画（rememberReducedMotion）时 animation 当作 none、animated 当作 false，没有时钟。
 */

// ------------------------------------------------------------------ 时间

/** CSS 的 ease-in-out */
private val CssEase = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val ShimmerEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val ShineEase = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)

/** 秒。一小时一圈，回绕时最多跳一下 */
@Composable
private fun rememberSkinClock(): State<Float> =
    rememberInfiniteTransition(label = "skin").animateFloat(0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing), RepeatMode.Restart), label = "skinT")

/** 一轮里走到哪了（0..1）。delay 是 CSS 的负延迟（秒） */
private fun cycle(t: Float, duration: Float, delay: Float): Float {
    val x = (t - delay) / duration
    return x - floor(x)
}

/** infinite alternate + ease-in-out：0 → 1 → 0 … */
private fun alternate(t: Float, duration: Float, delay: Float): Float {
    val x = (t - delay) / duration
    val k = floor(x)
    var f = x - k
    if (k.toLong() % 2L != 0L) f = 1f - f
    return CssEase.transform(f)
}

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

private fun seedStep(seed: Long): Int = (((seed % 9) + 9) % 9).toInt()

// ------------------------------------------------------------------ 模糊

private val BlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

/** CSS 的模糊半径（px）→ Android 的 BlurMaskFilter 半径（Skia：sigma = r × 0.57735 + 0.5；CSS：sigma = blur / 2） */
private fun blurPaint(color: Color, cssBlurPx: Float, stroke: Float = 0f): android.graphics.Paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    val radius = (cssBlurPx / 2f - 0.5f) / 0.57735f
    if (radius > 0.5f) {
        if (BlurSupported) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    }
    // 8.x 的硬件画布不认 maskFilter：退成不模糊的淡色
    val fade = if (radius > 0.5f && !BlurSupported) 0.4f else 1f
    this.color = color.copy(alpha = color.alpha * fade).toArgb()
    if (stroke > 0f) {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
    }
}

private fun DrawScope.drawNative(path: Path, paint: android.graphics.Paint, alpha: Float = 1f) {
    val base = paint.alpha
    if (alpha < 1f) paint.alpha = (base * alpha.coerceIn(0f, 1f)).roundToInt()
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
    paint.alpha = base
}

private fun outlinePath(shape: Shape, size: Size, density: Density, layoutDirection: LayoutDirection): Path =
    Path().apply { addOutline(shape.createOutline(size, layoutDirection, density)) }

// ------------------------------------------------------------------ 纹样

private val patternBrushes = LinkedHashMap<String, ShaderBrush>()

/** 一块纹样画成小图，做成平铺的着色器。按（图案、颜色、不透明度、缩放、屏幕密度）记住 */
internal fun patternBrush(pattern: SkinPattern, density: Float): ShaderBrush? {
    val tile = patternTile(pattern.key) ?: return null
    val key = "${pattern.key}|${pattern.color.value}|${pattern.opacity}|${pattern.scale}|$density"
    patternBrushes[key]?.let { return it }
    val w = max(1, (tile.width * pattern.scale * density).roundToInt())
    val h = max(1, (tile.height * pattern.scale * density).roundToInt())
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        scale(w / tile.width, h / tile.height, pivot = Offset.Zero) {
            drawGlyphLayers(tile.glyph, pattern.color, pattern.opacity)
        }
    }
    val brush = ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    if (patternBrushes.size >= 64) patternBrushes.remove(patternBrushes.keys.first())
    patternBrushes[key] = brush
    return brush
}

// ------------------------------------------------------------------ 角饰的小动作（.kcsk-orn--* / .kcaf-orn--*）

/** 在已经平移到装饰中心的坐标系里套上动作。origin 是相对中心的变换原点 */
private inline fun DrawScope.withMotion(motion: OrnamentMotion?, t: Float, delay: Float, box: Float, density: Float, crossinline block: DrawScope.(alpha: Float) -> Unit) {
    if (motion == null) {
        block(1f)
        return
    }
    when (motion) {
        OrnamentMotion.Twinkle -> {
            val f = alternate(t, 3.6f, delay)
            val s = lerp(1f, 0.84f, f)
            withTransform({ rotate(lerp(0f, 10f, f), Offset.Zero); scale(s, s, Offset.Zero) }) { block(lerp(1f, 0.6f, f)) }
        }
        OrnamentMotion.Sway -> withTransform({ rotate(lerp(-7f, 7f, alternate(t, 4.4f, delay)), Offset.Zero) }) { block(1f) }
        OrnamentMotion.Float -> translate(0f, lerp(-1.5f, 1.5f, alternate(t, 4.8f, delay)) * density) { block(1f) }
        OrnamentMotion.Pulse -> {
            val s = lerp(0.94f, 1.08f, alternate(t, 3.2f, delay))
            withTransform({ scale(s, s, Offset.Zero) }) { block(1f) }
        }
        OrnamentMotion.Twitch -> {
            // 耳朵绕着根部（50% 92%）偶尔抖一下：0–84% 不动，88% -9°，92% 5°，96% -3°
            val c = cycle(t, 6.5f, delay)
            val deg = when {
                c < 0.84f -> 0f
                c < 0.88f -> lerp(0f, -9f, CssEase.transform((c - 0.84f) / 0.04f))
                c < 0.92f -> lerp(-9f, 5f, CssEase.transform((c - 0.88f) / 0.04f))
                c < 0.96f -> lerp(5f, -3f, CssEase.transform((c - 0.92f) / 0.04f))
                else -> lerp(-3f, 0f, CssEase.transform((c - 0.96f) / 0.04f))
            }
            withTransform({ rotate(deg, Offset(0f, box * 0.42f)) }) { block(1f) }
        }
        OrnamentMotion.Flap -> withTransform({ rotate(lerp(5f, -8f, alternate(t, 3.6f, delay)), Offset(-box * 0.42f, box * 0.14f)) }) { block(1f) }
    }
}

// ------------------------------------------------------------------ 气泡

private const val SHIMMER_SKEW = -16f

/**
 * 给气泡铺上皮肤：阴影 → 底色 → 呼吸的光晕 → 流动的渐变 → 纹样 → 扫光 → （内容、边框）→ 角饰。
 * 只管画，不管内边距：调用方接着 .padding(…) 放内容。角饰探出气泡之外，外面的布局别裁切。
 */
@Composable
fun Modifier.bubbleSkin(skin: BubbleSkin, paint: SkinPaint, shape: Shape, seed: Long = 0): Modifier {
    val reduced = rememberReducedMotion()
    val darkTheme = KcTheme.colors.isDark
    val density = LocalDensity.current.density
    val animation = if (reduced) BubbleAnimation.None else skin.animation
    val flow = animation == BubbleAnimation.Flow && paint.fill.isGradient
    val shimmer = animation == BubbleAnimation.Shimmer
    val breathe = animation == BubbleAnimation.Breathe
    val ornaments = skin.ornaments
    val moving = flow || shimmer || breathe || (!reduced && ornaments.any { it.animated })
    val clock = if (moving) rememberSkinClock() else null
    val step = seedStep(seed)
    val fxDelay = -(step * 0.9f)
    val fillBrush = remember(paint) { paint.fill.toBrush() }
    val flowBrush = remember(paint, flow) { if (flow) paint.fill.toBrush() else null }
    val pattern = remember(paint.pattern, density) { paint.pattern?.let { patternBrush(it, density) } }
    val glyphs = remember(ornaments) { ornaments.map { ornamentGlyph(it.key) } }

    var m = this
    if (skin.marginTop > 0 || skin.marginBottom > 0) m = m.padding(top = skin.marginTop.dp, bottom = skin.marginBottom.dp)
    // 不动的一层：阴影、底色
    m = m.drawWithCache {
        val path = outlinePath(shape, size, this, layoutDirection)
        // CSS 里第一层阴影在最上面，所以倒着画；只画在气泡外面（和 box-shadow 一样）
        val shadows = paint.shadows.asReversed().map { s ->
            val spread = s.spread * density
            val p = outlinePath(shape, Size(max(0f, size.width + spread * 2), max(0f, size.height + spread * 2)), this, layoutDirection)
            p.translate(Offset(s.x * density - spread, s.y * density - spread))
            p to blurPaint(s.color, s.blur * density)
        }
        onDrawBehind {
            if (shadows.isNotEmpty()) clipPath(path, ClipOp.Difference) { shadows.forEach { (p, sp) -> drawNative(p, sp) } }
            drawPath(path, fillBrush)
        }
    }
    if (moving) m = m.graphicsLayer()
    m = m.drawWithCache {
        val w = size.width
        val h = size.height
        val path = outlinePath(shape, size, this, layoutDirection)
        // breathe：外沿一圈（0 0 9px 1px 70%、0 0 2px 80%）加里面一层薄薄的亮（inset 0 0 12px 白 0.2），之后只改不透明度
        val glowOuter = if (breathe) {
            val spreadPath = outlinePath(shape, Size(w + 2 * density, h + 2 * density), this, layoutDirection).apply { translate(Offset(-density, -density)) }
            listOf(path to blurPaint(paint.glow.copy(alpha = 0.8f), 2f * density), spreadPath to blurPaint(paint.glow.copy(alpha = 0.7f), 9f * density))
        } else emptyList()
        val glowInner = if (breathe) blurPaint(Color.White.copy(alpha = 0.2f), 12f * density, stroke = 24f * density) else null
        val bandWidth = w * 0.52f
        val shine = if (shimmer) {
            val peak = Color.White.copy(alpha = if (darkTheme) 0.16f else 0.3f)
            Brush.horizontalGradient(0f to Color.White.copy(alpha = 0f), 0.5f to peak, 1f to Color.White.copy(alpha = 0f), startX = -bandWidth / 2f, endX = bandWidth / 2f)
        } else null
        val skew = tan(Math.toRadians(SHIMMER_SKEW.toDouble())).toFloat()
        onDrawWithContent {
            val t = clock?.value ?: 0f
            if (breathe) {
                val a = lerp(0.2f, 1f, alternate(t, 4.5f, fxDelay))
                clipPath(path, ClipOp.Difference) { glowOuter.forEach { (p, gp) -> drawNative(p, gp, a) } }
                if (glowInner != null) clipPath(path) { drawNative(path, glowInner, a) }
            }
            if (flowBrush != null || pattern != null || shine != null) {
                clipPath(path) {
                    if (flowBrush != null) {
                        // flow：同一幅渐变放大一圈（四周各 60%），在气泡后面来回平移 ±17% / ±12%，13 秒走一趟
                        val f = alternate(t, 13f, fxDelay)
                        val dx = lerp(-0.17f, 0.17f, f) * w * 2.2f
                        val dy = lerp(-0.12f, 0.12f, f) * h * 2.2f
                        inset(-0.6f * w + dx, -0.6f * h + dy, -0.6f * w - dx, -0.6f * h - dy) { drawRect(flowBrush) }
                    }
                    if (pattern != null) drawRect(pattern)
                    if (shine != null) {
                        // shimmer：一道斜光（宽 52%、skewX -16°），八秒里前 68% 停在左边外面，之后扫到右边外面
                        val c = cycle(t, 8f, fxDelay)
                        val pos = if (c < 0.68f) -1.4f else lerp(-1.4f, 2.4f, ShimmerEase.transform((c - 0.68f) / 0.32f))
                        withTransform({ translate(bandWidth / 2f + pos * bandWidth, h / 2f) }) {
                            drawIntoCanvas { it.nativeCanvas.skew(skew, 0f) }
                            drawRect(shine, topLeft = Offset(-bandWidth / 2f, -h * 0.8f), size = Size(bandWidth, h * 1.6f))
                        }
                    }
                }
            }
            drawContent()
            // 角饰：横向最多探出 7dp，竖向伸进气泡里不超过 8dp，至少探出 40%
            ornaments.forEachIndexed { index, o ->
                val glyph = glyphs[index] ?: return@forEachIndexed
                val s = o.size * density
                val ox = min(o.size * 0.38f, 7f) * density
                val oy = max(o.size * ORNAMENT_RISE, o.size - ORNAMENT_INSET) * density
                val left = if (o.corner[1] == 'l') -ox else w - s + ox
                val top = if (o.corner[0] == 't') -oy else h - s + oy
                val motion = if (o.animated && !reduced) ornamentMotion(o.key).takeIf { it != OrnamentMotion.Twitch && it != OrnamentMotion.Flap } else null
                val delay = -((step + index * 4) % 9) * 0.6f
                translate(left + s / 2f, top + s / 2f) {
                    withMotion(motion, t, delay, s, density) { alpha ->
                        translate(-s / 2f, -s / 2f) { drawOrnamentGlyph(glyph, o.color, s, density, alpha = alpha) }
                    }
                }
            }
        }
    }
    if (moving) m = m.graphicsLayer()
    paint.border?.let { (color, width) -> m = m.border(width.dp, color, shape) }
    return m
}

// ------------------------------------------------------------------ 头像框

/**
 * 头像外圈的装饰（.kcaf）：外发光 → 垫在头像后面的装饰 → （头像）→ 环（spin 转、shimmer 扫光）→ 压在环上的装饰。
 * 不占布局，全部画在自身边界之外（父布局别裁切）；尺寸按 40dp 头像计、按实际大小等比缩放。
 * [rounded] 跟随外观里的「圆角方形头像」；[seed] 传用户 id，把不同人的节奏错开。
 */
@Composable
fun Modifier.frameDecor(frame: AvatarFrame, rounded: Boolean, seed: Long = 0): Modifier {
    val reduced = rememberReducedMotion()
    val fp = frame.paint(KcTheme.colors.isDark)
    val density = LocalDensity.current.density
    val animation = if (reduced) FrameAnimation.None else frame.animation
    val spin = animation == FrameAnimation.Spin && fp.fill.isGradient
    val shine = animation == FrameAnimation.Shimmer
    val pulse = animation == FrameAnimation.Pulse && fp.glow != null
    val moving = spin || shine || pulse || (!reduced && frame.pieces.any { it.animated })
    val clock = if (moving) rememberSkinClock() else null
    val step = seedStep(seed)
    val ringDelay = -(step * 0.9f)
    val ringBrush = remember(fp.fill) { fp.fill.toBrush() }
    val spinBrush = remember(fp.fill, spin) { if (spin) fp.fill.toBrush() else null }
    val shineBrush = remember(shine) {
        if (shine) CssConicBrush(0f, listOf(0f to Color.White.copy(alpha = 0f), 0.66f to Color.White.copy(alpha = 0f), 0.86f to Color.White.copy(alpha = 0.92f), 1f to Color.White.copy(alpha = 0f))) else null
    }
    val pieces = frame.pieces
    val glyphs = remember(pieces) { pieces.map { ornamentGlyph(it.key) } }
    val base = if (moving) this.graphicsLayer() else this
    return base.drawWithCache {
        val avatar = size.minDimension
        val u = avatar / 40f
        val out = (frame.gap + frame.width) * u
        val ringW = frame.width * u
        val box = avatar + out * 2
        // 圆角方形头像：环和头像同心（外圆角 = 头像圆角 + 外扩）；没有留白时往里多盖 0.6dp，免得接缝漏出一条底色
        val outerR = if (rounded) avatar * 0.28f + out else box / 2f
        val inner = ringW + if (frame.gap == 0f) 0.6f * density else 0f
        val ring = Path().apply {
            fillType = PathFillType.EvenOdd
            addRoundRect(RoundRect(0f, 0f, box, box, CornerRadius(outerR)))
            addRoundRect(RoundRect(inner, inner, box - inner, box - inner, CornerRadius(max(0f, outerR - inner))))
        }
        val ringBox = Path().apply { addRoundRect(RoundRect(0f, 0f, box, box, CornerRadius(outerR))) }
        // 外发光：一个和环一样大的空框的投影（box-shadow 0 0 blur·u blur·u·0.1 color），只画在框外
        val glow = fp.glow?.let { (color, blur) ->
            val blurPx = blur * u
            val spread = blurPx * 0.1f
            val p = Path().apply { addRoundRect(RoundRect(-spread, -spread, box + spread, box + spread, CornerRadius(outerR + spread))) }
            Triple(p, blurPaint(color, blurPx), Brush.radialGradient(
                0f to color.copy(alpha = 0f), (box / 2f) / (box / 2f + blurPx) to color, 1f to color.copy(alpha = 0f),
                center = Offset(box / 2f, box / 2f), radius = box / 2f + blurPx,
            ))
        }
        fun DrawScope.drawPiece(index: Int, piece: FramePiece, t: Float, delay: Float) {
            val glyph = glyphs[index] ?: return
            val s = piece.size * u
            val motion = if (piece.animated && !reduced) ornamentMotion(piece.key) else null
            withTransform({
                translate(piece.x * avatar, piece.y * avatar)
                rotate(piece.turn, Offset.Zero)
                if (piece.mirror) scale(-1f, 1f, Offset.Zero)
            }) {
                withMotion(motion, t, delay, s, density) { alpha ->
                    translate(-s / 2f, -s / 2f) { drawOrnamentGlyph(glyph, piece.color, s, density, alpha = alpha) }
                }
            }
        }
        onDrawWithContent {
            val t = clock?.value ?: 0f
            inset(-out, -out, -out, -out) {
                if (glow != null) {
                    val a = if (pulse) lerp(0.3f, 1f, alternate(t, 3.8f, ringDelay)) else 1f
                    clipPath(ringBox, ClipOp.Difference) {
                        if (BlurSupported) drawNative(glow.first, glow.second, a) else drawRect(glow.third, topLeft = Offset(-box, -box), size = Size(box * 3, box * 3), alpha = a)
                    }
                }
            }
            var back = 0
            pieces.forEachIndexed { index, piece -> if (piece.back) drawPiece(index, piece, t, -((step + back++ * 2) % 9) * 0.6f) }
            drawContent()
            inset(-out, -out, -out, -out) {
                if (spinBrush != null) {
                    // spin：一块铺了环底色的方块（四周各大 21%）在环里慢慢转，十二秒一圈
                    clipPath(ring) {
                        rotate(cycle(t, 12f, ringDelay) * 360f, Offset(box / 2f, box / 2f)) {
                            inset(-0.21f * box, -0.21f * box, -0.21f * box, -0.21f * box) { drawRect(spinBrush) }
                        }
                    }
                } else {
                    drawPath(ring, ringBrush)
                }
                if (shineBrush != null) {
                    // shimmer：一段弧形的光沿着环走一圈，七秒里 62% 之后才走，淡入、走完淡出
                    val c = cycle(t, 7f, ringDelay)
                    val opacity = when {
                        c < 0.62f -> 0f
                        c < 0.68f -> ShineEase.transform((c - 0.62f) / 0.06f)
                        c < 0.94f -> 1f
                        else -> 1f - ShineEase.transform((c - 0.94f) / 0.06f)
                    }
                    val angle = if (c < 0.62f) -40f else lerp(-40f, 320f, ShineEase.transform((c - 0.62f) / 0.38f))
                    if (opacity > 0f) clipPath(ring) {
                        rotate(angle, Offset(box / 2f, box / 2f)) {
                            inset(-0.21f * box, -0.21f * box, -0.21f * box, -0.21f * box) { drawRect(shineBrush, alpha = opacity) }
                        }
                    }
                }
            }
            var front = 0
            pieces.forEachIndexed { index, piece -> if (!piece.back) drawPiece(index, piece, t, -((step + front++ * 4 + 1) % 9) * 0.6f) }
        }
    }
}
