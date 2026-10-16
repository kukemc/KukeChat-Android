package ink.kuke.chat.feature.level

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.theme.KcTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * 账号等级图标（设计稿 P26，画法同客户端 src/components/level/LevelGlyph.tsx）：
 * 四进制——4 颗星 = 1 月亮，4 月亮 = 1 太阳，4 太阳 = 1 皇冠。画板 16×16，每个图形一个主体色 + 提亮面 + 压暗的边 + 点缀。
 * 图标的金黄色是这里唯一自带的颜色（它们是插画，不跟主题强调色走），深色下另取一组。
 */

enum class LevelIconKind(val label: String, val levels: Int) {
    Crown("皇冠", 64), Sun("太阳", 16), Moon("月亮", 4), Star("星星", 1),
}

/** 一种图标几个 */
data class LevelPart(val kind: LevelIconKind, val count: Int)

/** 把等级拆成图标：从大到小，每种尽量多拿。23 级 = 1 太阳 1 月亮 3 星星 */
fun splitLevel(level: Int): List<LevelPart> {
    var rest = level.coerceAtLeast(0)
    val parts = mutableListOf<LevelPart>()
    for (kind in LevelIconKind.entries) {
        val count = rest / kind.levels
        if (count > 0) {
            parts += LevelPart(kind, count)
            rest -= count * kind.levels
        }
    }
    return parts
}

fun describeParts(parts: List<LevelPart>): String = parts.joinToString(" ") { "${it.count} ${it.kind.label}" }

private class Palette(val a: Color, val b: Color, val c: Color, val d: Color)

private fun palette(kind: LevelIconKind, dark: Boolean): Palette = when (kind) {
    LevelIconKind.Star -> if (dark) Palette(Color(0xFFFFD04A), Color(0xFFFFE996), Color(0xFFC98A0A), Color.Transparent)
    else Palette(Color(0xFFFFC933), Color(0xFFFFE996), Color(0xFFDD9100), Color.Transparent)
    LevelIconKind.Moon -> if (dark) Palette(Color(0xFFFFECB5), Color(0xFFFFFAE6), Color(0xFFB58A34), Color(0xFFFFC24A))
    else Palette(Color(0xFFFFE394), Color(0xFFFFFAE6), Color(0xFFC48F24), Color(0xFFF2B02E))
    LevelIconKind.Sun -> if (dark) Palette(Color(0xFFFFA63A), Color(0xFFFFD591), Color(0xFFC85C0C), Color(0xFFFF9226))
    else Palette(Color(0xFFFF9A26), Color(0xFFFFD591), Color(0xFFDC6205), Color(0xFFFF8412))
    LevelIconKind.Crown -> if (dark) Palette(Color(0xFFFFC42E), Color(0xFFFFE796), Color(0xFFB57A0C), Color(0xFFF0565B))
    else Palette(Color(0xFFF8B716), Color(0xFFFFE796), Color(0xFFBD7700), Color(0xFFE5484D))
}

private fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

private fun pointOf(cx: Float, cy: Float, radius: Float, degrees: Float): Offset {
    val angle = degrees * PI.toFloat() / 180f
    return Offset(cx + radius * sin(angle), cy - radius * cos(angle))
}

private val starPath: Path by lazy {
    Path().apply {
        for (i in 0 until 10) {
            val p = pointOf(8f, 8.55f, if (i % 2 == 0) 7f else 3.15f, i * 36f)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
}

private val starFacets: Path by lazy {
    Path().apply {
        for (i in 0 until 5) {
            val tip = pointOf(8f, 8.55f, 6.5f, i * 72f)
            val inner = pointOf(8f, 8.55f, 2.9f, i * 72f - 36f)
            moveTo(tip.x, tip.y); lineTo(inner.x, inner.y); lineTo(8f, 8.55f); close()
        }
    }
}

private val moonBody by lazy { svgPath("M13.9 9.9A6.4 6.4 0 1 1 6.1 2.1A5.8 5.8 0 0 0 13.9 9.9Z") }
private val moonShade by lazy { svgPath("M4 5.3A5.5 5.5 0 0 0 3.7 11.5") }
private val moonSpark by lazy { svgPath("M11.9 2.2Q12.2 4 14 4.3Q12.2 4.6 11.9 6.4Q11.6 4.6 9.8 4.3Q11.6 4 11.9 2.2Z") }
private val crownBody by lazy { svgPath("M2.75 11.3L1.75 5.5L5.3 8.1L8 3.6L10.7 8.1L14.25 5.5L13.25 11.3Z") }

private fun DrawScope.fillAndEdge(path: Path, fill: Color, edge: Color) {
    drawPath(path, fill)
    drawPath(path, edge, style = Stroke(width = 0.9f, join = StrokeJoin.Round))
}

private fun DrawScope.line(path: Path, color: Color, width: Float) {
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.drawGlyph(kind: LevelIconKind, p: Palette) {
    when (kind) {
        LevelIconKind.Star -> {
            fillAndEdge(starPath, p.a, p.c)
            drawPath(starFacets, p.b)
        }
        LevelIconKind.Moon -> {
            fillAndEdge(moonBody, p.a, p.c)
            line(moonShade, p.b, 1.1f)
            drawPath(moonSpark, p.d)
        }
        LevelIconKind.Sun -> {
            val rays = Path().apply {
                for (i in 0 until 8) {
                    val from = pointOf(8f, 8f, 5.6f, i * 45f)
                    val to = pointOf(8f, 8f, 7.1f, i * 45f)
                    moveTo(from.x, from.y); lineTo(to.x, to.y)
                }
            }
            line(rays, p.d, 1.5f)
            drawCircle(p.a, radius = 3.75f, center = Offset(8f, 8f))
            drawCircle(p.c, radius = 3.75f, center = Offset(8f, 8f), style = Stroke(0.9f))
            line(svgPath("M5.9 7.5A2.3 2.3 0 0 1 7.6 5.85"), p.b, 1.1f)
        }
        LevelIconKind.Crown -> {
            fillAndEdge(crownBody, p.a, p.c)
            drawRoundRect(p.c, topLeft = Offset(2.7f, 11.9f), size = Size(10.6f, 2.1f), cornerRadius = CornerRadius(1.05f))
            for ((x, y, r) in listOf(Triple(1.75f, 4.9f, 1.05f), Triple(8f, 3f, 1.1f), Triple(14.25f, 4.9f, 1.05f))) {
                drawCircle(p.b, r, Offset(x, y))
                drawCircle(p.c, r, Offset(x, y), style = Stroke(0.7f))
            }
            drawCircle(p.d, 1.2f, Offset(8f, 9.4f))
            line(svgPath("M3.55 7.9L3.95 10.3"), Color.White.copy(alpha = 0.7f), 0.9f)
        }
    }
}

/** 一个图标 */
@Composable
fun LevelGlyph(kind: LevelIconKind, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    val dark = KcTheme.colors.isDark
    val colors = remember(kind, dark) { palette(kind, dark) }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 16f, pivot = Offset.Zero) { drawGlyph(kind, colors) }
    }
}

/**
 * 等级图标串（同 QQ：星 / 月 / 日 / 冠，图标本身就表示等级，旁边不挂数字）。
 *   compact = true：图标太多时只保留前 6 个，给挤的地方用
 *   showNumber：只在等级页这类明确讲等级的地方才显示数字
 * 0 级什么都不画。
 */
@Composable
fun LevelIcons(level: Int, modifier: Modifier = Modifier, size: Dp = 13.dp, compact: Boolean = false, showNumber: Boolean = false) {
    if (level < 1) return
    val parts = remember(level) { splitLevel(level) }
    if (parts.isEmpty()) return
    Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
        var budget = if (compact) 6 else Int.MAX_VALUE
        parts.forEach { part ->
            repeat(minOf(part.count, budget)) { LevelGlyph(part.kind, size) }
            budget -= minOf(part.count, budget)
        }
        if (showNumber) {
            Text(level.toString(), color = KcTheme.colors.muted, fontSize = (size.value * 0.9f).sp, fontWeight = FontWeight.W700, modifier = Modifier.padding(start = 3.dp))
        }
    }
}
