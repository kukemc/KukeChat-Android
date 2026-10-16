package ink.kuke.chat.feature.user

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 好友成就徽章：一枚奖章，外框按 tier 换（1 铜、2 银、3 金、4 彩虹），中间是系列自己的图形。
 * 图形名（icon）由服务端下发，画法移植自 KukeChat-Client 的 BadgeGlyph.tsx（48×48 画板）：
 * 不描边、靠一块暗面和一道高光分出体积，所以名字旁 14–16dp 的小号也认得出。
 * 认不出的名字画一颗星；没揭晓的隐藏成就（mystery）画问号；没解锁（locked）整体去色变淡。
 */
@Composable
fun BadgeGlyph(icon: String, tier: Int, size: Dp = 40.dp, locked: Boolean = false, mystery: Boolean = false, modifier: Modifier = Modifier) {
    val key = if (mystery) "unknown" else if (icon in ArtNames) icon else "fallback"
    Canvas(modifier.size(size)) {
        val unit = this.size.width / 48f
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            if (locked) {
                val paint = Paint().apply {
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                    alpha = 0.5f
                }
                drawIntoCanvas { it.saveLayer(Rect(0f, 0f, 48f, 48f), paint) }
                drawBadge(key, tier.coerceIn(1, 4))
                drawIntoCanvas { it.restore() }
            } else {
                drawBadge(key, tier.coerceIn(1, 4))
            }
        }
    }
}

// ---------------------------------------------------------------- 配色

private val RAINBOW = listOf(0xFFFF5A5F, 0xFFFF9F3A, 0xFFFFD43B, 0xFF57CC72, 0xFF3FA8FF, 0xFF9A6CFF).map { Color(it) }
private val GREEN = Color(0xFF4FBF6F)
private val GREEN_LO = Color(0xFF36A057)
private val PINK = Color(0xFFFF7FB0)
private val PINK_LO = Color(0xFFE9578F)
private val BLUE = Color(0xFF45B0FF)
private val BLUE_LO = Color(0xFF2588E3)
private val PURPLE = Color(0xFFA06BFF)
private val PURPLE_LO = Color(0xFF7D48E6)
private val YELLOW = Color(0xFFFFCC33)
private val YELLOW_LO = Color(0xFFF2A516)
private val WHITE = Color.White
private val GLINT = Color.White.copy(alpha = 0.62f)
private val SHINE = Color.White.copy(alpha = 0.65f)

private val DISC = mapOf(
    "clover" to 0xFFE3F7E4, "sparkle" to 0xFFFFF4D6, "rings" to 0xFFFFE5EF, "moon" to 0xFF2F3A78, "sun" to 0xFFFFF1CC,
    "balloons" to 0xFFFFE5F1, "weekend" to 0xFFE2F2FF, "gems" to 0xFFE6F4FF, "cake" to 0xFFFFF0E6, "hearts" to 0xFFFFE8EE,
    "house" to 0xFFFFF3DF, "firework" to 0xFF26285C, "sunmoon" to 0xFF3A3F86, "rainbow" to 0xFFDFF2FF, "chaos" to 0xFFEDE6D9,
    "harmony" to 0xFFF3E6FF, "horn" to 0xFFEFE7FF, "unknown" to 0xFFE8EAF0,
)

private val ArtNames = DISC.keys

// ---------------------------------------------------------------- 画图小工具

private fun r2(v: Float): Float = Math.round(v * 100f) / 100f

private fun pt(cx: Float, cy: Float, radius: Float, degrees: Float): Offset {
    val a = degrees * PI.toFloat() / 180f
    return Offset(cx + radius * cos(a), cy + radius * sin(a))
}

private fun on(cx: Float, cy: Float, radius: Float, degrees: Float): String {
    val p = pt(cx, cy, radius, degrees)
    return "${r2(p.x)} ${r2(p.y)}"
}

/** 圆弧路径：角度按屏幕方向（0 在右，90 在下，270 在上） */
private fun arcD(radius: Float, from: Float, to: Float, cx: Float = 24f, cy: Float = 24f): String =
    "M${on(cx, cy, radius, from)}A$radius $radius 0 ${if (to - from > 180f) 1 else 0} 1 ${on(cx, cy, radius, to)}"

private fun starPts(cx: Float, cy: Float, outer: Float, inner: Float, points: Int = 5, rotate: Float = -90f): List<Offset> =
    List(points * 2) { i -> pt(cx, cy, if (i % 2 == 0) outer else inner, rotate + i * 180f / points) }

private fun pathOf(d: String): Path = PathParser().parsePathString(d).toPath()

private fun DrawScope.fillD(d: String, color: Color) = drawPath(pathOf(d), color)

private fun DrawScope.strokeD(d: String, color: Color, width: Float, cap: StrokeCap = StrokeCap.Round) =
    drawPath(pathOf(d), color, style = Stroke(width = width, cap = cap, join = StrokeJoin.Round))

private fun DrawScope.disc(cx: Float, cy: Float, r: Float, color: Color) = drawCircle(color, r, Offset(cx, cy))

private fun DrawScope.ringAt(cx: Float, cy: Float, r: Float, color: Color, width: Float) =
    drawCircle(color, r, Offset(cx, cy), style = Stroke(width))

private fun DrawScope.oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
    drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))

private fun DrawScope.rrect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r, r))

private fun polyPath(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}

private fun DrawScope.poly(points: List<Offset>, color: Color, strokeWidth: Float = 0f) {
    val path = polyPath(points)
    drawPath(path, color)
    if (strokeWidth > 0f) drawPath(path, color, style = Stroke(strokeWidth, join = StrokeJoin.Round))
}

private fun DrawScope.drawStar(cx: Float, cy: Float, outer: Float, inner: Float, color: Color, points: Int = 5, rotate: Float = -90f, strokeWidth: Float = 0f) =
    poly(starPts(cx, cy, outer, inner, points, rotate), color, strokeWidth)

// ---------------------------------------------------------------- 外框与整体

private fun DrawScope.drawBadge(key: String, tier: Int) {
    if (tier >= 3) {
        val ribbonA = if (tier == 4) Color(0xFFFF6B9A) else Color(0xFFE2574C)
        val ribbonB = if (tier == 4) Color(0xFF7D6BFF) else Color(0xFFB83C33)
        fillD("M15.6 36.8L12.4 46.4L16.6 44.6L19 48L22 39.4Z", ribbonA)
        fillD("M32.4 36.8L35.6 46.4L31.4 44.6L29 48L26 39.4Z", ribbonB)
    }
    drawRim(tier)
    disc(24f, 24f, 16.4f, Color(DISC[key] ?: 0xFFFFF6D8))
    withTransform({
        translate(24f, 24f)
        scale(0.82f, 0.82f, pivot = Offset.Zero)
        translate(-24f, -24f)
    }) {
        when (key) {
            "clover" -> clover()
            "sparkle" -> sparkle()
            "rings" -> rings()
            "moon" -> moon()
            "sun" -> sun()
            "balloons" -> balloons()
            "weekend" -> weekend()
            "gems" -> gems()
            "cake" -> cake()
            "hearts" -> hearts()
            "house" -> house()
            "firework" -> firework()
            "sunmoon" -> sunMoon()
            "rainbow" -> rainbow()
            "chaos" -> chaos()
            "harmony" -> harmony()
            "horn" -> horn()
            "unknown" -> unknown()
            else -> fallback()
        }
    }
}

private fun DrawScope.drawRim(tier: Int) {
    if (tier == 4) {
        disc(24f, 24f, 21.6f, WHITE)
        RAINBOW.forEachIndexed { i, c -> strokeD(arcD(19.4f, -90f + i * 60f, -90f + (i + 1) * 60f + 0.6f), c, 4.6f, StrokeCap.Butt) }
        strokeD(arcD(21.4f, 192f, 262f), GLINT, 1.3f)
        return
    }
    val (base, lo, hi) = when (tier) {
        1 -> Triple(Color(0xFFD39256), Color(0xFFB06D37), Color(0xFFEFBB86))
        2 -> Triple(Color(0xFFBCC7D6), Color(0xFF8E9BB0), Color(0xFFE6ECF4))
        else -> Triple(Color(0xFFFFCB3D), Color(0xFFEF9F1A), Color(0xFFFFE58C))
    }
    disc(24f, 24f, 21.6f, base)
    strokeD(arcD(21.6f, -30f, 120f), lo, 3.2f, StrokeCap.Butt)
    ringAt(24f, 24f, 17.6f, lo.copy(alpha = 0.55f), 1f)
    strokeD(arcD(19.6f, 196f, 252f), hi, 1.5f)
}

// ---------------------------------------------------------------- 各系列的图形

private fun DrawScope.clover() {
    val leaf = "M24 22.5C21.2 20.6 18.6 18.4 18.6 15.9A2.85 2.85 0 0 1 24 14.7A2.85 2.85 0 0 1 29.4 15.9C29.4 18.4 26.8 20.6 24 22.5Z"
    strokeD("M24.5 23.5C25.4 27.6 27.4 30.8 30.2 33.2", GREEN_LO, 2.2f)
    listOf(0f, 90f, 180f, 270f).forEach { angle ->
        withTransform({ rotate(angle, pivot = Offset(24f, 22.5f)) }) { fillD(leaf, if (angle % 180f == 0f) GREEN else GREEN_LO) }
    }
    disc(24f, 22.5f, 1.6f, Color(0xFFFFE36B))
    strokeD("M20.2 15.6a1.7 1.7 0 0 1 2.1-.6", GLINT, 1.2f)
}

private fun DrawScope.harmony() {
    poly(starPts(24f, 24f, 11.5f, 6.4f, 6), PURPLE, 1.4f)
    poly(starPts(24f, 24f, 11.5f, 6.4f, 6, 30f), PURPLE_LO.copy(alpha = 0.35f))
    poly(starPts(24f, 24f, 5.6f, 2.6f), WHITE.copy(alpha = 0.92f))
    disc(17.5f, 17f, 1.3f, SHINE)
}

private fun DrawScope.rings() {
    ringAt(20f, 24f, 6.6f, PINK, 3.6f)
    ringAt(28f, 24f, 6.6f, BLUE, 3.6f)
    strokeD(arcD(6.6f, -60f, 10f, 20f, 24f), PINK, 3.6f)
    strokeD(arcD(6.6f, 200f, 235f, 20f, 24f), WHITE.copy(alpha = 0.6f), 1.2f)
}

private fun DrawScope.moon() {
    val d = "M24 13.2A10.8 10.8 0 0 0 24 34.8A6 10.8 0 0 1 24 13.2Z"
    withTransform({ rotate(-28f, pivot = Offset(24f, 24f)) }) {
        fillD(d, Color(0xFFFFE27A))
        strokeD(d, Color(0xFFFFE27A), 1.2f)
    }
    drawStar(31.2f, 17.6f, 2.8f, 1.2f, WHITE)
    drawStar(30f, 29.5f, 1.8f, 0.8f, Color(0xFFC8D2FF))
    disc(35f, 24f, 0.9f, Color(0xFFC8D2FF))
}

private fun DrawScope.sun() {
    repeat(8) { i -> strokeD("M${on(24f, 24f, 9.4f, i * 45f)}L${on(24f, 24f, 13f, i * 45f)}", YELLOW_LO, 2.6f) }
    disc(24f, 24f, 7.4f, YELLOW)
    strokeD("M24 31.4a7.4 7.4 0 0 0 7.4-7.4", YELLOW_LO, 2.4f, StrokeCap.Butt)
    strokeD(arcD(5.2f, 200f, 250f), GLINT, 1.4f)
}

private fun DrawScope.balloons() {
    fun balloon(x: Float, y: Float, fill: Color, lo: Color) {
        strokeD("M$x ${y + 5.2f}C${x - 0.6f} ${y + 8f} ${x + 1.4f} ${y + 9.6f} 24 35", Color(0xFF8A8FA3), 0.9f, StrokeCap.Butt)
        oval(x, y, 4.4f, 5.2f, fill)
        strokeD("M${x + 4.1f} ${y + 1.6f}A4.4 5.2 0 0 1 ${x - 1.4f} ${y + 5f}", lo, 1.6f, StrokeCap.Butt)
        strokeD("M${x - 2.6f} ${y - 2f}a2.2 2.6 0 0 1 1.6-2", GLINT, 1.1f)
    }
    balloon(18.2f, 21.5f, BLUE, BLUE_LO)
    balloon(29.8f, 21.5f, BLUE, BLUE_LO)
    balloon(24f, 17.4f, YELLOW, YELLOW_LO)
}

private fun DrawScope.weekend() {
    rrect(14f, 15.5f, 20f, 18f, 3.4f, WHITE)
    fillD("M14 22v-3.1a3.4 3.4 0 0 1 3.4-3.4h13.2a3.4 3.4 0 0 1 3.4 3.4V22Z", BLUE)
    rrect(18f, 12.8f, 2.4f, 5.4f, 1.2f, BLUE_LO)
    rrect(27.6f, 12.8f, 2.4f, 5.4f, 1.2f, BLUE_LO)
    fillD("M24 31.2c-2.6-1.7-4.6-3.4-4.6-5.3a2.3 2.3 0 0 1 4.6-.8a2.3 2.3 0 0 1 4.6.8c0 1.9-2 3.6-4.6 5.3Z", PINK)
}

private fun DrawScope.gems() {
    fun gem(x: Float, y: Float, s: Float) {
        val outline = "M$x ${y - s}L${x + s * 0.72f} ${y}L$x ${y + s}L${x - s * 0.72f} ${y}Z"
        fillD(outline, BLUE)
        strokeD(outline, BLUE, 1f, StrokeCap.Butt)
        fillD("M$x ${y - s}L${x + s * 0.72f} ${y}L$x ${y + s}Z", BLUE_LO)
        strokeD("M${x - s * 0.34f} ${y - s * 0.4f}L${x - s * 0.1f} ${y - s * 0.72f}", GLINT, 1.1f)
    }
    gem(24f, 18.6f, 6.2f)
    gem(18.4f, 28.6f, 5f)
    gem(29.6f, 28.6f, 5f)
}

private fun DrawScope.cake() {
    rrect(23f, 11.6f, 2f, 5.4f, 1f, BLUE)
    fillD("M24 8.6c1.4 1.4 1.8 2.6.9 3.4a1.3 1.3 0 0 1-1.8 0c-.9-.8-.5-2 .9-3.4Z", Color(0xFFFF9F3A))
    rrect(17.2f, 17f, 13.6f, 7.2f, 2.4f, PINK)
    rrect(13.6f, 23.2f, 20.8f, 10f, 2.8f, Color(0xFFFFD9A8))
    strokeD("M13.6 26.6c2.6 1.6 4.4 1.6 6.9 0s4.4-1.6 6.9 0 4.4 1.6 7 0", PINK_LO, 2f)
    strokeD("M19.4 19.6h4", GLINT, 1.3f)
}

private fun DrawScope.horn() {
    val d = "M15.6 34.2L31.4 13.2L22.4 36.4Z"
    fillD(d, Color(0xFFF6D8FF))
    strokeD(d, Color(0xFFF6D8FF), 1.6f, StrokeCap.Butt)
    strokeD("M17.6 31.8l5.3 1.9M20.6 27.6l5.4 1.9M23.6 23.4l5 1.8M26.4 19.4l3.6 1.3", PURPLE, 1.5f)
    drawStar(33.2f, 12.6f, 4.4f, 1.9f, YELLOW)
    disc(13.6f, 18f, 1.2f, PURPLE)
    disc(35f, 27f, 1f, PURPLE)
}

private fun DrawScope.house() {
    val body = "M14.2 23.4L24 14.6L33.8 23.4V33a1.8 1.8 0 0 1-1.8 1.8H16a1.8 1.8 0 0 1-1.8-1.8Z"
    fillD(body, Color(0xFFFFD9A8))
    strokeD(body, Color(0xFFFFD9A8), 1f, StrokeCap.Butt)
    strokeD("M11.8 24.2L24 13.2L36.2 24.2", Color(0xFFE8743B), 3.2f)
    rrect(21.2f, 27.4f, 5.6f, 7.4f, 1.2f, Color(0xFFB0743F))
    fillD("M29.6 26.4c-1.1-.8-2-1.5-2-2.3a1 1 0 0 1 2-.4a1 1 0 0 1 2 .4c0 .8-.9 1.5-2 2.3Z", PINK)
}

private fun DrawScope.firework() {
    val colors = listOf(0xFFFF6B8B, 0xFFFFD43B, 0xFF6BD0FF, 0xFF9EF08A).map { Color(it) }
    repeat(12) { i ->
        val angle = i * 30f
        val color = colors[i % colors.size]
        strokeD("M${on(24f, 24f, 4f, angle)}L${on(24f, 24f, 10.2f, angle)}", color, 1.9f)
        val p = pt(24f, 24f, 12.6f, angle)
        disc(p.x, p.y, 1.15f, color)
    }
    disc(24f, 24f, 2.2f, WHITE)
}

private fun DrawScope.chaos() {
    val points = List(40) { i ->
        val t = i / 39f
        val radius = 1f + t * 10f
        val angle = t * 600f - 90f
        pt(23f + sin(t * 9f) * 0.8f, 24f, radius, angle)
    }
    val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
    drawPath(path, Color(0xFF8A5A2B), style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    withTransform({ rotate(18f, pivot = Offset(32f, 13.7f)) }) {
        rrect(29.6f, 11.2f, 5f, 5f, 1f, WHITE)
        drawRoundRect(Color(0xFFD5C7B1), Offset(29.6f, 11.2f), Size(5f, 5f), CornerRadius(1f, 1f), style = Stroke(0.8f))
    }
    drawStar(14.2f, 32.6f, 3f, 1.3f, YELLOW)
}

private fun DrawScope.sparkle() {
    fun spark(x: Float, y: Float, r: Float): String =
        "M$x ${y - r}Q${x + r * 0.18f} ${y - r * 0.18f} ${x + r} ${y}Q${x + r * 0.18f} ${y + r * 0.18f} $x ${y + r}" +
            "Q${x - r * 0.18f} ${y + r * 0.18f} ${x - r} ${y}Q${x - r * 0.18f} ${y - r * 0.18f} $x ${y - r}Z"
    fillD(spark(21.6f, 25.4f, 10.4f), YELLOW)
    withTransform({ translate(1.2f, 1.2f) }) { fillD(spark(21.6f, 25.4f, 10.4f), YELLOW_LO.copy(alpha = 0.35f)) }
    fillD(spark(31.6f, 15.6f, 4.6f), PINK)
    disc(33.4f, 29.6f, 1.4f, PINK)
    disc(18.6f, 22f, 1.3f, SHINE)
}

private fun DrawScope.hearts() {
    val heart = "M0 6.2C-3.9 3.6-7 1-7-2.2A3.6 3.6 0 0 1 0-3.6A3.6 3.6 0 0 1 7-2.2C7 1 3.9 3.6 0 6.2Z"
    withTransform({
        translate(19.4f, 23.4f); rotate(-14f, pivot = Offset.Zero); scale(1.18f, 1.18f, pivot = Offset.Zero)
    }) { fillD(heart, PINK) }
    withTransform({
        translate(28.6f, 26.6f); rotate(12f, pivot = Offset.Zero); scale(1.18f, 1.18f, pivot = Offset.Zero)
    }) {
        fillD(heart, PURPLE)
        fillD(heart, PURPLE_LO.copy(alpha = 0.3f))
    }
    disc(15.8f, 20.4f, 1.2f, SHINE)
}

private fun DrawScope.rainbow() {
    RAINBOW.forEachIndexed { i, c -> strokeD(arcD(13f - i * 1.9f, 180f, 360f, 24f, 31f), c, 2f) }
    disc(13.6f, 31.4f, 3.4f, WHITE)
    disc(17.6f, 30.2f, 4f, WHITE)
    disc(21.2f, 32f, 2.8f, WHITE)
}

private fun DrawScope.sunMoon() {
    repeat(8) { i -> strokeD("M${on(19f, 19f, 6.6f, i * 45f)}L${on(19f, 19f, 8.8f, i * 45f)}", YELLOW, 1.8f) }
    disc(19f, 19f, 5f, YELLOW)
    val d = "M29.5 22.6A6.9 6.9 0 0 0 29.5 36.4A3.9 6.9 0 0 1 29.5 22.6Z"
    withTransform({ rotate(-28f, pivot = Offset(29.5f, 29.5f)) }) {
        fillD(d, Color(0xFFC8D2FF))
        strokeD(d, Color(0xFFC8D2FF), 1f, StrokeCap.Butt)
    }
    drawStar(35.4f, 23.4f, 1.8f, 0.8f, WHITE)
}

private fun DrawScope.unknown() {
    strokeD("M19.4 20.2A4.8 4.8 0 1 1 26.6 24.4C25 25.3 24 26.2 24 28.2", Color(0xFF8B92A8), 3.4f)
    disc(24f, 33.4f, 2.1f, Color(0xFF8B92A8))
}

private fun DrawScope.fallback() {
    drawStar(24f, 24.6f, 11f, 5f, YELLOW, strokeWidth = 1.6f)
    disc(20.2f, 21f, 1.2f, SHINE)
}
