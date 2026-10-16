package ink.kuke.chat.feature.auth

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.scale as scaleBy

/*
 * 积木的绘制，坐标都是积木坐标（调用方已缩放、已平移到积木左上角）。
 *
 * 接缝（同设计稿 seamless / setJoin）：外轮廓描边宽 2、画在填充下面（只露出外侧 1）；
 * 和上一块重叠的顶边那段（含凹口）描边裁掉；底边垫一条本色，盖住自己底边的描边。
 * 一串积木从上往下画，下面的盖在上面的之上——上一块的凸起从下一块的凹口里露出来。
 */

private val FlagDark = PathParser().parsePathString(
    "M20.8 3.7c-.4-.2-.9-.1-1.2.2-2 1.6-4.8 1.6-6.8 0-2.3-1.9-5.6-2.3-8.3-1v-.4c0-.6-.5-1-1-1s-1 .4-1 1v18.8c0 .5.5 1 1 1h.1c.5 0 1-.5 1-1v-6.4c1-.7 2.1-1.2 3.4-1.3 1.2 0 2.4.4 3.4 1.2 2.9 2.3 7 2.3 9.8 0 .3-.2.4-.5.4-.9V4.7c0-.5-.3-.9-.8-1zm-.3 10.2C18 16 14.4 16 11.9 14c-1.1-.9-2.5-1.4-4-1.4-1.2.1-2.3.5-3.4 1.1V4c2.5-1.4 5.5-1.1 7.7.6 2.4 1.9 5.7 1.9 8.1 0h.2l.1.1-.1 9.2z",
).toPath()
private val FlagLight = PathParser().parsePathString(
    "M20.6 4.8l-.1 9.1v.1c-2.5 2-6.1 2-8.6 0-1.1-.9-2.5-1.4-4-1.4-1.2.1-2.3.5-3.4 1.1V4c2.5-1.4 5.5-1.1 7.7.6 2.4 1.9 5.7 1.9 8.1 0h.2c0 .1.1.1.1.2z",
).toPath()

// 下拉三角（scratch3 的 sb3-dropdownArrow，缩放 12/12.71）
private val ArrowShadow = PathParser().parsePathString(
    "M12.71 2.44A2.41 2.41 0 0 1 12 4.16L8.08 8.08a2.45 2.45 0 0 1-3.45 0L.72 4.16A2.42 2.42 0 0 1 0 2.44 2.48 2.48 0 0 1 .71.71C1 .47 1.43 0 6.36 0s5.39.46 5.64.71a2.44 2.44 0 0 1 .71 1.73z",
).toPath()
private val ArrowWhite = PathParser().parsePathString(
    "M6.36 7.79a1.43 1.43 0 0 1-1-.42L1.42 3.45a1.44 1.44 0 0 1 0-2c.56-.56 9.31-.56 9.87 0a1.44 1.44 0 0 1 0 2L7.37 7.37a1.43 1.43 0 0 1-1.01.42z",
).toPath()
private const val ARROW_SCALE = 0.94413847f

// KukeChat 扩展图标（src/extension/assets.ts 的 iconSvg，128 视口缩到 40）
private val ExtBubble = PathParser().parsePathString(
    "M31 42c0-8.284 6.716-15 15-15h36c8.284 0 15 6.716 15 15v21c0 8.284-6.716 15-15 15H59.8L41.2 94.6C38.6 96.9 34.5 95 34.5 91.5V77.7C28.1 75.9 23.5 70 23.5 63V42H31Z",
).toPath()

private val GlowCore = Color(0xFFFFF200)
private val GlowSoft = Color(0xFFFFE400)
private val EdgeStroke = Stroke(width = 2f, join = StrokeJoin.Round)
private val Hairline = Stroke(width = 1f)

/** 每块积木复用的路径（避免每帧分配） */
internal class BlockPaths {
    val outline = Path()
    val tmp = Path()
    private var shape: BlockShape? = null
    private var w = Float.NaN
    private var body = Float.NaN
    private var line0 = Float.NaN
    private var armTop = Float.NaN

    fun outlineFor(l: BlockLayout): Path {
        if (l.shape != shape || l.w != w || l.body != body || l.line0 != line0 || l.armTop != armTop) {
            outlinePath(outline, l)
            shape = l.shape; w = l.w; body = l.body; line0 = l.line0; armTop = l.armTop
        }
        return outline
    }
}

private fun Color.brighten(f: Float): Color =
    if (f <= 1f) this else Color((red * f).coerceAtMost(1f), (green * f).coerceAtMost(1f), (blue * f).coerceAtMost(1f), alpha)

/**
 * 画一块积木。
 * [above] / [below]：和上 / 下一块重叠的宽度（0 = 没接着别的积木）。
 * [old]、[oldAlpha]、[newAlpha]：文字变了时，旧字淡出、新字淡入。
 * [boolT]：0 灰 → 1 绿；[boolPulse]：变绿那一下的提亮（0..1）。[glow]：运行时的黄色描边（0..1）。
 */
internal fun DrawScope.drawBlock(
    l: BlockLayout,
    paths: BlockPaths,
    above: Float,
    below: Float,
    old: BlockLayout? = null,
    oldAlpha: Float = 0f,
    newAlpha: Float = 1f,
    boolT: Float = 1f,
    boolPulse: Float = 0f,
    glow: Float = 0f,
) {
    val outline = paths.outlineFor(l)
    val cat = l.cat
    if (glow > 0f) {
        drawPath(outline, GlowSoft.copy(alpha = 0.22f * glow), style = Stroke(width = 11f, join = StrokeJoin.Round))
        drawPath(outline, GlowSoft.copy(alpha = 0.45f * glow), style = Stroke(width = 6.5f, join = StrokeJoin.Round))
        drawPath(outline, GlowCore.copy(alpha = glow), style = Stroke(width = 3.5f, join = StrokeJoin.Round))
    }
    // 1. 外轮廓描边（被上一块盖住的顶边裁掉）
    if (above > 0f) {
        clipRect(1f, -4f, above - 5f, 8.5f, ClipOp.Difference) { drawPath(outline, cat.stroke, style = EdgeStroke) }
    } else {
        drawPath(outline, cat.stroke, style = EdgeStroke)
    }
    // 2. 填充
    drawPath(outline, cat.fill)
    // 3. 底边垫一条本色，盖住被下一块接上的那段描边
    if (below > 0f && below > 8f) drawRect(cat.fill, Offset(2f, l.body - 3f), Size(below - 8f, 3f))
    // 4. 零件
    val tmp = paths.tmp
    for (i in l.items.indices) {
        val it = l.items[i]
        when (it.kind) {
            ItemKind.Text -> {
                val prev = old?.items?.getOrNull(i)
                if (prev != null && prev.kind == ItemKind.Text && prev.text != it.text && oldAlpha > 0f) {
                    prev.layout?.let { lay -> drawText(lay, prev.fill, Offset(prev.x, prev.y), alpha = oldAlpha) }
                }
                val fading = prev != null && prev.kind == ItemKind.Text && prev.text != it.text
                it.layout?.let { lay -> drawText(lay, it.fill, Offset(it.x, it.y), alpha = if (fading) newAlpha else 1f) }
            }
            ItemKind.Pill -> {
                val r = CornerRadius(it.h / 2f)
                drawRoundRect(it.fill, Offset(it.x, it.y), Size(it.w, it.h), r)
                it.stroke?.let { s ->
                    val c = if (it.tinted) lerp(BoolOffStroke, s, boolT) else s
                    drawRoundRect(c, Offset(it.x, it.y), Size(it.w, it.h), r, style = Hairline)
                }
            }
            ItemKind.Drop -> {
                val r = CornerRadius(4f)
                drawRoundRect(it.fill, Offset(it.x, it.y), Size(it.w, it.h), r)
                it.stroke?.let { s -> drawRoundRect(s, Offset(it.x, it.y), Size(it.w, it.h), r, style = Hairline) }
            }
            ItemKind.Hex -> {
                hexPath(tmp, it.x, it.y, it.w, it.h)
                val fill = (if (it.tinted) lerp(BoolOffFill, it.fill, boolT) else it.fill).brighten(1f + 0.25f * boolPulse)
                drawPath(tmp, fill)
                it.stroke?.let { s -> drawPath(tmp, if (it.tinted) lerp(BoolOffStroke, s, boolT) else s, style = Hairline) }
            }
            ItemKind.HexSlot -> { hexPath(tmp, it.x, it.y, it.w, it.h); drawPath(tmp, it.fill) }
            ItemKind.InnerStack -> {
                stackPath(tmp, it.w, it.h, it.x, it.y)
                drawPath(tmp, it.fill)
                it.stroke?.let { s -> drawPath(tmp, s, style = Hairline) }
            }
            ItemKind.Flag -> translate(it.x, it.y) {
                drawPath(FlagDark, Color(0xFF45993D))
                drawPath(FlagLight, Color(0xFF4CBF56))
            }
            ItemKind.Icon -> drawExtIcon(it.x, it.y)
            ItemKind.VLine -> drawLine(it.stroke ?: cat.stroke, Offset(it.x, it.y), Offset(it.x, it.y + it.h), strokeWidth = 1f, cap = StrokeCap.Round)
            ItemKind.Arrow -> translate(it.x, it.y) {
                scaleBy(ARROW_SCALE, ARROW_SCALE, Offset.Zero) {
                    drawPath(ArrowShadow, Color(0xFF231F20).copy(alpha = 0.1f))
                    drawPath(ArrowWhite, Color.White)
                }
            }
        }
    }
}

private fun DrawScope.drawExtIcon(x: Float, y: Float) {
    translate(x, y) {
        val k = 40f / 128f
        drawRoundRect(Color(0xFF171717), Offset.Zero, Size(40f, 40f), CornerRadius(34f * k))
        drawRoundRect(Color(0xFF262626), Offset(10f * k, 10f * k), Size(108f * k, 108f * k), CornerRadius(28f * k))
        drawRoundRect(Color(0xFF525252), Offset(10f * k, 10f * k), Size(108f * k, 108f * k), CornerRadius(28f * k), style = Stroke(2f * k))
        scaleBy(k, k, Offset.Zero) {
            drawPath(ExtBubble, Color(0xFFF4F4F5))
            drawCircle(Color(0xFF171717), 5.8f, Offset(50f, 54f))
            drawCircle(Color(0xFF737373), 5.8f, Offset(64f, 54f))
            drawCircle(Color(0xFF171717), 5.8f, Offset(78f, 54f))
        }
    }
}

private val BubbleBorder = Color(0xFFD9D9D9) // rgba(0,0,0,.15) 叠在白底上
private val BubbleText = Color(0xFF575E75)

/**
 * 「说」的对话气泡（同设计稿 .sb-say）：左下角对着积木右上，带一条向左下的小尾巴。
 * [left]、[bottom]：气泡外框的左边 / 底边；[scale]、[alpha]：弹出 / 收起动画（以尾巴附近为原点缩放）。
 */
internal fun DrawScope.drawSayBubble(text: TextLayoutResult, left: Float, bottom: Float, scale: Float, alpha: Float) {
    if (alpha <= 0f) return
    val tw = text.size.width.toFloat()
    val w = tw + 26f + 4f
    val h = 14f * 1.3f + 14f + 4f
    scaleBy(scale, scale, Offset(left + 18f, bottom)) {
        val top = bottom - h
        drawRoundRect(Color.White.copy(alpha = alpha), Offset(left, top), Size(w, h), CornerRadius(16f))
        drawRoundRect(BubbleBorder.copy(alpha = alpha), Offset(left + 1f, top + 1f), Size(w - 2f, h - 2f), CornerRadius(15f), style = Stroke(2f))
        // 尾巴：12×12 的方块 skewY(-38°)，只描左边和下边
        val pb = bottom - 2f
        val tail = Path().apply {
            moveTo(left + 16f, bottom - 3f)
            lineTo(left + 16f, pb + 13.7f)
            lineTo(left + 28f, pb + 4.3f)
            lineTo(left + 28f, bottom - 3f)
            close()
        }
        drawPath(tail, Color.White.copy(alpha = alpha))
        drawLine(BubbleBorder.copy(alpha = alpha), Offset(left + 17f, pb + 1f), Offset(left + 17f, pb + 12.9f), 2f)
        drawLine(BubbleBorder.copy(alpha = alpha), Offset(left + 17f, pb + 12.9f), Offset(left + 28f, pb + 4.3f), 2f, cap = StrokeCap.Round)
        drawText(text, BubbleText, Offset(left + 2f + 13f, top + 2f + 7f + (14f * 1.3f - text.size.height) / 2f), alpha = alpha)
    }
}
