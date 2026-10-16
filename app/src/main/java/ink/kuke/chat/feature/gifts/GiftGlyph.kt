package ink.kuke.chat.feature.gifts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 礼物的内置插画：十二个矢量图形，外加一个认不出名字时用的礼物盒。逐笔移植自客户端 components/gifts/GiftGlyph.tsx。
 *
 * 服务器只下发图形的名字（icon），图形本身画在这里。这些是装饰插画，不是界面图标，所以不走 KcIcons、不跟主题着色。
 * 画法和客户端一致：48×48 画板，不描边，右下压暗面、左上点白色高光，折角用同色圆头描边磨圆。
 * 每个图形第一次用到时拼成一个 ImageVector，之后复用（VectorPainter 自带位图缓存）。
 */

val GiftIconNames = listOf("flower", "lollipop", "cake", "trophy", "rocket", "castle", "star", "heart", "balloon", "crown", "cat", "icecream")

/** icon 是服务器下发的原样字符串；不认识的名字画成礼物盒 */
@Composable
fun GiftGlyph(icon: String, size: Dp, modifier: Modifier = Modifier) {
    Image(giftVector(icon), contentDescription = null, modifier = modifier.size(size))
}

private val cache = HashMap<String, ImageVector>()

fun giftVector(icon: String): ImageVector {
    val key = if (icon in GiftIconNames) icon else "box"
    return cache.getOrPut(key) {
        val g = Glyph(ImageVector.Builder(name = "gift-$key", defaultWidth = 48.dp, defaultHeight = 48.dp, viewportWidth = 48f, viewportHeight = 48f))
        when (key) {
            "flower" -> g.flower()
            "lollipop" -> g.lollipop()
            "cake" -> g.cake()
            "trophy" -> g.trophy()
            "rocket" -> g.rocket()
            "castle" -> g.castle()
            "star" -> g.star()
            "heart" -> g.heart()
            "balloon" -> g.balloon()
            "crown" -> g.crown()
            "cat" -> g.cat()
            "icecream" -> g.icecream()
            else -> g.box()
        }
        g.b.build()
    }
}

// ------------------------------------------------------------------ 几何小工具（和客户端同名同义）

private fun r2(value: Double): Double = (value * 100).roundToLong() / 100.0
private fun n(value: Double): String = r2(value).let { if (it == 0.0) "0" else it.toString() }

/** 圆上的一点；角度按屏幕方向算：0 在右，90 在下，270 在上 */
private fun on(cx: Double, cy: Double, radius: Double, degrees: Double): String {
    val a = Math.toRadians(degrees)
    return "${n(cx + radius * cos(a))} ${n(cy + radius * sin(a))}"
}

/** 圆右下的一弯暗面：外沿贴着圆，内沿是一段更平的弧 */
private fun shadeArc(cx: Double, cy: Double, radius: Double, from: Double = -38.0, to: Double = 128.0): String {
    val flat = n(radius * 1.32)
    return "M${on(cx, cy, radius, from)}A${n(radius)} ${n(radius)} 0 0 1 ${on(cx, cy, radius, to)}A$flat $flat 0 0 0 ${on(cx, cy, radius, from)}Z"
}

/** 圆左上的一道高光（描边用），radius 传比圆小一圈的半径 */
private fun glintArc(cx: Double, cy: Double, radius: Double, from: Double = 196.0, to: Double = 250.0): String =
    "M${on(cx, cy, radius, from)}A${n(radius)} ${n(radius)} 0 0 1 ${on(cx, cy, radius, to)}"

/** 五角星的十个顶点，尖朝上 */
private fun starPoints(cx: Double, cy: Double, outer: Double, inner: Double): String =
    (0 until 10).joinToString(" ") { on(cx, cy, if (it % 2 == 0) outer else inner, -90.0 + it * 36) }

private val GOLD = Color(0xFFFFCB3D)
private val GOLD_HI = Color(0xFFFFE487)
private val GOLD_LO = Color(0xFFF0A11C)
private val RED = Color(0xFFFF5470)
private val RED_LO = Color(0xFFE2315C)
private val PINK = Color(0xFFFF8FB3)
private val PINK_LO = Color(0xFFEF6292)
private val BLUE = Color(0xFF45B7FF)
private val BLUE_LO = Color(0xFF2590E6)
private val GREEN = Color(0xFF5CCB7F)
private val GREEN_LO = Color(0xFF3FAE6A)
private val WHITE = Color.White

private fun hex(v: Long) = Color(0xFF000000 or v)

/** 拼图形的小 DSL：path / circle / ellipse / rect / polygon 都换成 path 交给 ImageVector */
private class Glyph(val b: ImageVector.Builder) {
    fun fill(d: String, color: Color, alpha: Float = 1f) {
        b.addPath(addPathNodes(d), fill = SolidColor(color), fillAlpha = alpha)
    }

    /** 同色描边磨圆折角（SVG 的 stroke + strokeLinejoin="round"） */
    fun fillRound(d: String, color: Color, width: Float) {
        b.addPath(addPathNodes(d), fill = SolidColor(color), stroke = SolidColor(color), strokeLineWidth = width, strokeLineJoin = StrokeJoin.Round, strokeLineMiter = 4f)
    }

    fun line(d: String, color: Color, width: Float, alpha: Float = 1f, round: Boolean = true) {
        b.addPath(addPathNodes(d), stroke = SolidColor(color), strokeAlpha = alpha, strokeLineWidth = width, strokeLineCap = if (round) StrokeCap.Round else StrokeCap.Butt)
    }

    /** 高光：一道圆头的白线 */
    fun glint(d: String, width: Float, alpha: Float = 0.62f) = line(d, WHITE, width, alpha)

    /** 高光：一小块白 */
    fun shine(d: String) = fill(d, WHITE, 0.6f)

    fun circle(cx: Double, cy: Double, r: Double, color: Color, alpha: Float = 1f) = fill(circlePath(cx, cy, r), color, alpha)
    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, color: Color, alpha: Float = 1f) = fill(ellipsePath(cx, cy, rx, ry), color, alpha)
    fun rect(x: Double, y: Double, w: Double, h: Double, rx: Double = 0.0, color: Color, alpha: Float = 1f) = fill(rectPath(x, y, w, h, rx), color, alpha)
    fun polygon(points: String, color: Color, strokeWidth: Float = 0f) {
        val d = polygonPath(points)
        if (strokeWidth > 0f) fillRound(d, color, strokeWidth) else fill(d, color)
    }

    fun group(translateX: Float = 0f, translateY: Float = 0f, scaleX: Float = 1f, scaleY: Float = 1f, rotate: Float = 0f, pivotX: Float = 0f, pivotY: Float = 0f, block: Glyph.() -> Unit) {
        b.addGroup(rotate = rotate, pivotX = pivotX, pivotY = pivotY, scaleX = scaleX, scaleY = scaleY, translationX = translateX, translationY = translateY)
        block()
        b.clearGroup()
    }
}

private fun circlePath(cx: Double, cy: Double, r: Double) = ellipsePath(cx, cy, r, r)
private fun ellipsePath(cx: Double, cy: Double, rx: Double, ry: Double) =
    "M${n(cx - rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx + rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx - rx)} ${n(cy)}Z"

private fun rectPath(x: Double, y: Double, w: Double, h: Double, rx: Double): String {
    if (rx <= 0) return "M${n(x)} ${n(y)}H${n(x + w)}V${n(y + h)}H${n(x)}Z"
    val r = n(rx)
    return "M${n(x + rx)} ${n(y)}H${n(x + w - rx)}A$r $r 0 0 1 ${n(x + w)} ${n(y + rx)}V${n(y + h - rx)}A$r $r 0 0 1 ${n(x + w - rx)} ${n(y + h)}" +
        "H${n(x + rx)}A$r $r 0 0 1 ${n(x)} ${n(y + h - rx)}V${n(y + rx)}A$r $r 0 0 1 ${n(x + rx)} ${n(y)}Z"
}

private val numberPattern = Regex("-?\\d*\\.?\\d+(?:e-?\\d+)?")

private fun polygonPath(points: String): String {
    val v = numberPattern.findAll(points).map { it.value }.toList()
    return buildString {
        for (i in 0 until v.size / 2) append(if (i == 0) "M" else "L").append(v[i * 2]).append(' ').append(v[i * 2 + 1])
        append('Z')
    }
}

// ------------------------------------------------------------------ 十二个图形

/** 花：五片花瓣绕着花心 (24, 17.5)；背光的三瓣在轮廓上压一弯暗面 */
private fun Glyph.flower() {
    line("M24 29C23 35 23.4 40.5 24.6 45.4", GREEN_LO, 3f)
    fill("M24.2 39.4C27 33.6 32.4 32 37.2 33.2C35.6 38.8 30 41.8 24.2 39.4Z", GREEN)
    fill("M24.2 39.4C29 38.8 33.6 36.8 37.2 33.2C35.6 38.8 30 41.8 24.2 39.4Z", GREEN_LO)
    val petals = listOf(listOf(-90.0), listOf(198.0), listOf(-18.0, -38.0, 92.0), listOf(126.0, 44.0, 128.0), listOf(54.0, -30.0, 128.0)).map { spec ->
        val a = Math.toRadians(spec[0])
        val x = r2(24 + 8.2 * cos(a))
        val y = r2(17.5 + 8.2 * sin(a))
        Triple(x, y, if (spec.size == 3) shadeArc(x, y, 7.4, spec[1], spec[2]) else "")
    }
    petals.forEach { (x, y, _) -> circle(x, y, 7.4, PINK) }
    fill(petals.joinToString("") { it.third }, PINK_LO)
    glint(glintArc(16.2, 14.97, 4.6), 1.8f)
    glint(glintArc(24.0, 9.3, 4.6), 1.8f)
    circle(24.0, 17.5, 5.6, GOLD)
    fill(shadeArc(24.0, 17.5, 5.6), GOLD_LO)
    circle(22.2, 15.7, 1.5, WHITE, 0.6f)
}

/** 棒棒糖：紫色的圆糖，一圈浅色的螺旋，米色的棍 */
private fun Glyph.lollipop() {
    rect(21.9, 29.0, 4.2, 17.2, 2.1, hex(0xE9CFA6))
    fill("M24.4 29h1.7v15.1a2.1 2.1 0 0 1-1.7 2.06Z", hex(0xCFAB7C))
    circle(24.0, 17.5, 14.6, hex(0xA97BFF))
    line("M24 17.5a3 3 0 0 1 6 0a6 6 0 0 1-12 0a9 9 0 0 1 18 0a11.6 11.6 0 0 1-23.2 0", hex(0xFFE0F5), 3f)
    fill(shadeArc(24.0, 17.5, 14.6), hex(0x4A1FB4), 0.3f)
    glint(glintArc(24.0, 17.5, 11.4, 200.0, 246.0), 2.2f)
}

/** 蛋糕：盘子上一块圆蛋糕，粉色奶油往下滴，顶上两颗樱桃一根蜡烛 */
private fun Glyph.cake() {
    ellipse(24.0, 41.6, 20.0, 4.0, hex(0xB5C3DE))
    ellipse(24.0, 40.7, 17.6, 2.5, hex(0xD3DCEF))
    fill("M8 24H40V37a4 4 0 0 1-4 4H12a4 4 0 0 1-4-4Z", hex(0xFFCF96))
    rect(8.0, 33.4, 32.0, 2.6, color = hex(0xFFF0DA))
    fill("M33 24H40V37a4 4 0 0 1-4 4H31.4C32.6 35.6 33 30 33 24Z", hex(0xB8641A), 0.3f)
    rect(22.0, 9.4, 4.0, 9.6, 1.4, BLUE)
    line("M22 13.6l4-2.2M22 17l4-2.2", WHITE, 1.2f, 0.75f, round = false)
    fill("M24 1.6C26.4 4.2 27.2 5.8 27.2 7.2A3.2 3.2 0 0 1 20.8 7.2C20.8 5.8 21.6 4.2 24 1.6Z", hex(0xFF9D2E))
    circle(24.0, 7.3, 1.5, hex(0xFFE680))
    circle(14.0, 16.6, 3.0, RED)
    circle(34.0, 16.6, 3.0, RED_LO)
    circle(13.0, 15.6, 0.9, WHITE, 0.6f)
    val drips = listOf(4.6, 9.4, 4.6, 6.4, 10.4, 4.6).joinToString("") { "q-2.67 ${n(it)}-5.33 0" }
    fill("M8 27V22a4 4 0 0 1 4-4H36a4 4 0 0 1 4 4V27${drips}Z", PINK)
    fill("M34.67 18H36a4 4 0 0 1 4 4V27q-2.67 4.6-5.33 0Z", PINK_LO)
    glint("M12.6 21.6H21", 1.8f)
}

/** 奖杯：金杯，两只耳，杯身一颗星，下面是木底座 */
private fun Glyph.trophy() {
    line("M13 9.5H8.6a1.6 1.6 0 0 0-1.6 1.7C7.4 16.6 10 19.8 14.5 20.6M35 9.5h4.4a1.6 1.6 0 0 1 1.6 1.7C40.6 16.6 38 19.8 33.5 20.6", GOLD_LO, 3f)
    rect(21.0, 25.0, 6.0, 9.0, color = GOLD_LO)
    fill("M14 4.5H34a1.5 1.5 0 0 1 1.5 1.5V16a11.5 11.5 0 0 1-23 0V6A1.5 1.5 0 0 1 14 4.5Z", GOLD)
    fill("M29 4.5H34a1.5 1.5 0 0 1 1.5 1.5V16A11.5 11.5 0 0 1 24 27.5C28.6 23 29.6 14 29 4.5Z", GOLD_LO)
    rect(11.0, 3.6, 26.0, 3.6, 1.8, GOLD_HI)
    polygon(starPoints(24.0, 16.0, 4.6, 2.0), hex(0xFFF4C9), 1f)
    glint("M16.4 10.6V16a7.6 7.6 0 0 0 1.9 5", 2f)
    rect(15.0, 32.0, 18.0, 4.8, 2.0, GOLD)
    rect(11.5, 36.0, 25.0, 8.6, 2.4, hex(0xAD745C))
    fill("M30 36h4.1a2.4 2.4 0 0 1 2.4 2.4v3.8a2.4 2.4 0 0 1-2.4 2.4H30Z", hex(0x8C5846))
    rect(18.6, 38.7, 10.8, 3.2, 1.2, GOLD_HI)
}

/** 火箭：竖着的，灰蓝的箭身，红色的头和尾翼，一扇圆窗，尾巴喷火 */
private fun Glyph.rocket() {
    fill("M19.4 37.9h9.2C28.6 42 26.6 45 24 47.4C21.4 45 19.4 42 19.4 37.9Z", hex(0xFF9A2E))
    fill("M21.6 37.9h4.8C26.4 40.4 25.4 42.2 24 43.8C22.6 42.2 21.6 40.4 21.6 37.9Z", hex(0xFFE066))
    fillRound("M14.5 23.5C9.6 26.4 7.4 31.4 7.8 38.6L14.5 34.5Z", RED, 1.2f)
    fillRound("M33.5 23.5C38.4 26.4 40.6 31.4 40.2 38.6L33.5 34.5Z", RED_LO, 1.2f)
    fill("M17.5 34.4h13l-1.4 3.6h-10.2z", hex(0x7D89A8))
    fill("M24 2C31 8 33.5 16 33.5 25V34.5H14.5V25C14.5 16 17 8 24 2Z", hex(0xCDD8F3))
    fill("M28.6 13.78H31.88C33.02 17.27 33.5 21.04 33.5 25V34.5H28.6Z", hex(0xA5B5DE))
    fill("M24 2C27.92 5.36 30.43 9.35 31.88 13.78H16.12C17.57 9.35 20.08 5.36 24 2Z", RED)
    fill("M24 2C27.92 5.36 30.43 9.35 31.88 13.78H28.6C28 9.6 26.6 5.6 24 2Z", RED_LO)
    glint("M19.7 11C20.4 9.1 21.4 7.5 22.6 6.2", 1.6f)
    glint("M18.4 17.4V31", 2f, 0.8f)
    circle(24.0, 21.6, 5.1, hex(0xF3F6FF))
    circle(24.0, 21.6, 3.5, BLUE)
    fill(shadeArc(24.0, 21.6, 3.5), BLUE_LO)
    circle(22.8, 20.4, 1.0, WHITE, 0.6f)
    rect(22.6, 28.6, 2.8, 7.6, 1.4, RED_LO)
}

/** 城堡：三座尖顶塔楼，前面一道带垛口的门楼，顶上插旗 */
private fun Glyph.castle() {
    line("M24 7.4V2", hex(0x8A76D8), 1.3f)
    fillRound("M24.4 1.7L30 3.7L24.4 5.7Z", GOLD, 0.8f)
    rect(18.5, 16.0, 11.0, 20.0, color = hex(0xC9B8FF))
    rect(26.3, 16.0, 3.2, 20.0, color = hex(0xA892F0))
    rect(5.0, 24.6, 10.0, 20.0, 1.2, hex(0xC9B8FF))
    rect(12.0, 24.6, 3.0, 20.0, color = hex(0xA892F0))
    rect(33.0, 24.6, 10.0, 20.0, 1.2, hex(0xC9B8FF))
    fill("M40 24.6h1.8a1.2 1.2 0 0 1 1.2 1.2v17.6a1.2 1.2 0 0 1-1.2 1.2H40Z", hex(0xA892F0))
    fillRound("M24 7L31.5 17H16.5Z", PINK, 1.6f)
    fillRound("M24 7L31.5 17H27.2C26.8 13.4 25.7 10 24 7Z", PINK_LO, 1.6f)
    fillRound("M10 14.4L16.2 25.4H3.8Z", PINK, 1.6f)
    fillRound("M10 14.4L16.2 25.4H12.6C12.3 21.5 11.4 17.8 10 14.4Z", PINK_LO, 1.6f)
    fillRound("M38 14.4L44.2 25.4H31.8Z", PINK, 1.6f)
    fillRound("M38 14.4L44.2 25.4H40.6C40.3 21.5 39.4 17.8 38 14.4Z", PINK_LO, 1.6f)
    glint("M21.2 14.2L22.8 11.2M7.6 22.6L9 19.8M35.6 22.6L37 19.8", 1.4f)
    fill("M14 29.2h3.2v2.8h2.4v-2.8h3.2v2.8h2.4v-2.8h3.2v2.8h2.4v-2.8H34V44.6H14Z", hex(0xE0D5FF))
    fill("M30.8 29.2H34V44.6H30.8Z", hex(0xC9B8FF))
    fill("M20.4 44.6V39.4a3.6 3.6 0 0 1 7.2 0V44.6Z", hex(0x6F55CC))
    fill("M24 35.8a3.6 3.6 0 0 1 3.6 3.6V44.6H24Z", hex(0x5A41B4))
    rect(22.5, 20.6, 3.0, 5.0, 1.5, hex(0x6F55CC))
    rect(8.6, 29.6, 2.8, 4.6, 1.4, hex(0x6F55CC))
    rect(36.6, 29.6, 2.8, 4.6, 1.4, hex(0x6F55CC))
    rect(2.6, 43.4, 42.8, 3.0, 1.5, GREEN)
    fill("M30 43.4h13.9a1.5 1.5 0 0 1 0 3H30Z", GREEN_LO)
}

/** 星星：圆角的金色五角星，左上一片亮面、右下一片暗面，像切过的宝石 */
private fun Glyph.star() {
    polygon(starPoints(24.0, 25.0, 20.0, 9.2), GOLD, 3.6f)
    polygon("24 25 ${on(24.0, 25.0, 20.0, 198.0)} ${on(24.0, 25.0, 9.2, 234.0)} ${on(24.0, 25.0, 20.0, -90.0)}", GOLD_HI)
    polygon("24 25 ${on(24.0, 25.0, 20.0, -18.0)} ${on(24.0, 25.0, 9.2, 18.0)} ${on(24.0, 25.0, 20.0, 54.0)} ${on(24.0, 25.0, 9.2, 90.0)}", GOLD_LO)
    glint("M17.6 19.4L21.6 11.2", 2.2f, 0.8f)
}

private fun Glyph.heart() {
    fill("M24 43C10 33 4 25 4 16.5C4 10 9 5.5 14.5 5.5C18.5 5.5 22 7.6 24 11C26 7.6 29.5 5.5 33.5 5.5C39 5.5 44 10 44 16.5C44 25 38 33 24 43Z", RED)
    fill("M44 16.5C44 25 38 33 24 43C34.5 34 40 26 40 17.5C40 14 39 11 37 8.8C41.2 9.8 44 12.6 44 16.5Z", RED_LO)
    glint("M10.2 17C10.2 13.2 12.8 10.6 16 10.6", 2.8f)
    circle(11.4, 22.0, 1.5, WHITE, 0.6f)
}

/** 气球：蓝色的，底下一个结，拖一根弯弯的线 */
private fun Glyph.balloon() {
    line("M24 38.4C20.6 40.8 27.4 43.4 24 46.8", hex(0x8FA3BD), 1.5f)
    fillRound("M24 33.6L27.2 38.6H20.8Z", BLUE_LO, 1.2f)
    group(translateX = 24f, translateY = 18.6f, scaleY = 1.16f) {
        circle(0.0, 0.0, 14.6, BLUE)
        fill(shadeArc(0.0, 0.0, 14.6), BLUE_LO)
    }
    group(rotate = 28f, pivotX = 17.6f, pivotY = 11.6f) { ellipse(17.6, 11.6, 2.6, 4.6, WHITE, 0.6f) }
}

/** 王冠：三个尖，尖上顶着金珠，箍上镶一红两蓝三颗宝石 */
private fun Glyph.crown() {
    fillRound("M8 35L5 16L15.5 24L24 10L32.5 24L43 16L40 35Z", GOLD, 3f)
    fill("M8 35L5 16L15.5 24L13.6 35Z", GOLD_HI)
    fill("M32.5 24L43 16L40 35H31.4Z", GOLD_LO)
    circle(5.0, 14.4, 3.0, GOLD_HI)
    circle(24.0, 8.4, 3.2, GOLD_HI)
    circle(43.0, 14.4, 3.0, GOLD)
    fill(shadeArc(43.0, 14.4, 3.0), GOLD_LO)
    fill(shadeArc(24.0, 8.4, 3.2), GOLD)
    fillRound("M24 19.4l3.2 4.6l-3.2 4.6l-3.2-4.6Z", RED, 0.9f)
    fill("M24 19.4l3.2 4.6l-3.2 4.6Z", RED_LO)
    rect(6.2, 33.6, 35.6, 9.2, 3.0, GOLD_LO)
    rect(7.8, 34.8, 32.4, 2.0, 1.0, GOLD_HI)
    circle(24.0, 39.4, 2.3, RED)
    circle(14.0, 39.4, 1.9, BLUE)
    circle(34.0, 39.4, 1.9, BLUE)
}

/** 猫：橘猫的脑袋，粉耳朵，额头三道纹 */
private fun Glyph.cat() {
    fillRound("M8 23L8.6 7L21 14.4Z", hex(0xFFAB52), 3f)
    fillRound("M40 23L39.4 7L27 14.4Z", hex(0xF08A2C), 3f)
    fillRound("M11.2 18.4L11.6 11.2L17.2 14.6Z", PINK, 1f)
    fillRound("M36.8 18.4L36.4 11.2L30.8 14.6Z", PINK_LO, 1f)
    group(translateX = 24f, translateY = 28.6f, scaleX = 1.2f) {
        circle(0.0, 0.0, 15.5, hex(0xFFAB52))
        fill(shadeArc(0.0, 0.0, 15.5), hex(0xF08A2C))
    }
    line("M24 14.6v4.6M19.4 15.8l.9 3.8M28.6 15.8l-.9 3.8", hex(0xE57A1C), 1.9f)
    ellipse(24.0, 35.0, 7.6, 5.0, hex(0xFFE6C8))
    circle(11.6, 33.4, 2.5, PINK, 0.6f)
    circle(36.4, 33.4, 2.5, PINK, 0.6f)
    ellipse(16.4, 27.6, 2.4, 3.0, hex(0x5A3520))
    ellipse(31.6, 27.6, 2.4, 3.0, hex(0x5A3520))
    circle(15.7, 26.5, 0.95, WHITE)
    circle(30.9, 26.5, 0.95, WHITE)
    fillRound("M22.3 32.2h3.4L24 34.2Z", hex(0xFF7D95), 0.9f)
    line("M24 34.6C23.4 36.6 21.4 36.9 20.3 35.6M24 34.6C24.6 36.6 26.6 36.9 27.7 35.6", hex(0x5A3520), 1.2f)
    glint("M10.6 24.6C11.4 22.4 12.8 20.6 14.6 19.4", 1.8f, 0.5f)
}

/** 冰淇淋：华夫蛋筒上一球草莓味的，边上往下淌，撒了彩针，顶一颗樱桃 */
private fun Glyph.icecream() {
    fillRound("M14.5 25H33.5L24 45.5Z", hex(0xF0B46A), 2.6f)
    fillRound("M28 25H33.5L24 45.5C26.4 39 27.8 32 28 25Z", hex(0xD6924A), 2.6f)
    line("M18 26.5l8.5 9M23 26l6 6.4M30 26.5l-8.5 9M25 26l-6 6.4", hex(0xB8732C), 1.1f, 0.6f)
    line("M26.2 2.8C26.8 1.8 27.8 1.4 29 1.5", GREEN_LO, 1.3f)
    circle(25.4, 5.8, 3.3, RED)
    fill(shadeArc(25.4, 5.8, 3.3), RED_LO)
    fill("M11 19a13 13 0 0 1 26 0v3a3.25 3.25 0 0 1-6.5 0a3.25 3.25 0 0 1-6.5 0a3.25 3.25 0 0 1-6.5 0a3.25 3.25 0 0 1-6.5 0Z", PINK)
    fill("M37 19v3a3.25 3.25 0 0 1-5.6 2.2C33.6 20 34 13.5 31.4 8.3A13 13 0 0 1 37 19Z", PINK_LO)
    glint(glintArc(24.0, 19.0, 9.4, 198.0, 244.0), 2.2f)
    line("M21 13.4l1.6 1.2", BLUE, 1.6f)
    line("M27.4 12l1.8-.8", GOLD_HI, 1.6f)
    line("M23.4 19.6l1.9.5", WHITE, 1.6f)
    line("M28.6 17.4l.7 1.8", hex(0x8FF0C4), 1.6f)
    line("M17.6 19.4l.5 1.8", GOLD_HI, 1.6f)
}

/** 认不出名字时画的礼物盒：紫盒子，金丝带，顶上一个蝴蝶结 */
private fun Glyph.box() {
    fill("M24 13.8C20.5 5.6 11.6 4.4 11 9.6C10.5 14 18.6 14.8 24 13.8Z", GOLD)
    fill("M24 13.8C27.5 5.6 36.4 4.4 37 9.6C37.5 14 29.4 14.8 24 13.8Z", GOLD_LO)
    rect(9.0, 21.0, 30.0, 23.6, 3.2, hex(0x8E7BFF))
    fill("M32 21h7v20.4a3.2 3.2 0 0 1-3.2 3.2H32Z", hex(0x6F59E6))
    rect(20.6, 21.0, 6.8, 23.6, color = GOLD)
    rect(9.0, 23.0, 30.0, 3.0, color = hex(0x2A1A8A), alpha = 0.26f)
    rect(6.0, 14.0, 36.0, 9.6, 3.0, hex(0xA999FF))
    fill("M34 14h5a3 3 0 0 1 3 3v3.6a3 3 0 0 1-3 3H34Z", hex(0x8E7BFF))
    rect(20.6, 14.0, 6.8, 9.6, color = GOLD_HI)
    rect(21.0, 9.8, 6.0, 5.8, 2.2, GOLD_HI)
    glint("M10.2 17.8h6.6", 1.8f)
    glint("M12.6 29.6v9", 2f, 0.45f)
}
