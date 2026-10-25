package ink.kuke.chat.feature.shop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/*
 * 气泡皮肤的纹样 / 角饰、头像框的装饰：客户端内置的矢量图形（docs/shop.md 2.2、2.4）。
 * 全部照搬电脑版（KukeChat-Client src/components/shop/skin/patterns.ts、ornaments.tsx、frameOrnaments.tsx）的 SVG：
 * 同一套路径、同样的白色高光（#fff 0.5）和压暗（#000 0.16），角饰在 24×24 的画板上，纹样是一小块能无缝平铺的方块。
 * 路径在第一次用到时解析一次，之后全进程共用。样式里只有名字，名字只用来查表。
 */

/** 一层：color 为 null 时用「主体色」（角饰的 color、纹样的 color）；alpha 是这一层自己的不透明度 */
internal class GlyphLayer(val path: Path, val color: Color?, val alpha: Float, val stroke: Stroke?)

internal class Glyph(val layers: List<GlyphLayer>)

private fun n(v: Float): String = String.format(Locale.US, "%.3f", v)

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

private class GlyphBuilder {
    val layers = mutableListOf<GlyphLayer>()
    private var group: android.graphics.Matrix? = null

    private fun put(path: Path, color: Color?, alpha: Float, stroke: Stroke?, matrix: android.graphics.Matrix? = null) {
        matrix?.let { path.asAndroidPath().transform(it) }
        group?.let { path.asAndroidPath().transform(it) }
        layers += GlyphLayer(path, color, alpha, stroke)
    }

    fun fill(d: String, color: Color? = null, alpha: Float = 1f, matrix: android.graphics.Matrix? = null) = put(parse(d), color, alpha, null, matrix)

    fun stroke(d: String, width: Float, color: Color? = null, alpha: Float = 1f, cap: StrokeCap = StrokeCap.Round, join: StrokeJoin = StrokeJoin.Miter, matrix: android.graphics.Matrix? = null) =
        put(parse(d), color, alpha, Stroke(width, miter = 4f, cap = cap, join = join), matrix)

    /** 主体色填充 + 同色描边（SVG 里 fill 和 stroke 都是 currentColor 的那种），描边是圆角接头 */
    fun solid(d: String, width: Float) {
        fill(d)
        stroke(d, width, cap = StrokeCap.Butt, join = StrokeJoin.Round)
    }

    fun highlight(d: String) = fill(d, Color.White, 0.5f)
    fun shade(d: String) = fill(d, Color.Black, 0.16f)

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rotate: Float = 0f, color: Color? = null, alpha: Float = 1f) {
        val p = android.graphics.Path().apply { addOval(android.graphics.RectF(cx - rx, cy - ry, cx + rx, cy + ry), android.graphics.Path.Direction.CW) }
        if (rotate != 0f) p.transform(android.graphics.Matrix().apply { setRotate(rotate, cx, cy) })
        put(p.asComposePath(), color, alpha, null)
    }

    fun ellipseStroke(cx: Float, cy: Float, rx: Float, ry: Float, width: Float, color: Color? = null, alpha: Float = 1f) {
        val p = android.graphics.Path().apply { addOval(android.graphics.RectF(cx - rx, cy - ry, cx + rx, cy + ry), android.graphics.Path.Direction.CW) }
        put(p.asComposePath(), color, alpha, Stroke(width))
    }

    fun circle(cx: Float, cy: Float, r: Float, color: Color? = null, alpha: Float = 1f) = ellipse(cx, cy, r, r, 0f, color, alpha)

    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color? = null, alpha: Float = 1f, matrix: android.graphics.Matrix? = null) {
        val p = android.graphics.Path().apply { addRoundRect(android.graphics.RectF(x, y, x + w, y + h), r, r, android.graphics.Path.Direction.CW) }
        put(p.asComposePath(), color, alpha, null, matrix)
    }

    fun group(matrix: android.graphics.Matrix, block: GlyphBuilder.() -> Unit) {
        val previous = group
        group = matrix
        block()
        group = previous
    }

    fun build() = Glyph(layers.toList())
}

private fun glyph(block: GlyphBuilder.() -> Unit): Glyph = GlyphBuilder().apply(block).build()

private fun rotation(degrees: Float, cx: Float = 0f, cy: Float = 0f) = android.graphics.Matrix().apply { setRotate(degrees, cx, cy) }

/** SVG 的 translate(x y) rotate(r) scale(s)：先缩放、再转、再平移 */
private fun place(x: Float, y: Float, degrees: Float, scale: Float = 1f) = android.graphics.Matrix().apply {
    setScale(scale, scale)
    postRotate(degrees)
    postTranslate(x, y)
}

// ------------------------------------------------------------------ 共用的几种形状（同 patterns.ts）

/** 四角星（闪光）：四个尖朝上下左右，边往里收 */
internal fun sparklePath(cx: Float, cy: Float, r: Float): String {
    val a = 0.1f * r
    val b = 0.46f * r
    fun p(x: Float, y: Float) = "${n(cx + x)} ${n(cy + y)}"
    return "M${p(0f, -r)}C${p(a, -b)} ${p(b, -a)} ${p(r, 0f)}C${p(b, a)} ${p(a, b)} ${p(0f, r)}C${p(-a, b)} ${p(-b, a)} ${p(-r, 0f)}C${p(-b, -a)} ${p(-a, -b)} ${p(0f, -r)}Z"
}

/** 爱心：s 是半宽，(cx, cy) 是中心 */
internal fun heartPath(cx: Float, cy: Float, s: Float): String {
    fun p(x: Float, y: Float) = "${n(cx + x * s)} ${n(cy + y * s)}"
    return "M${p(0f, 0.92f)}C${p(-0.7f, 0.42f)} ${p(-1f, 0.05f)} ${p(-1f, -0.36f)}C${p(-1f, -0.68f)} ${p(-0.76f, -0.9f)} ${p(-0.5f, -0.9f)}" +
        "C${p(-0.29f, -0.9f)} ${p(-0.1f, -0.78f)} ${p(0f, -0.58f)}C${p(0.1f, -0.78f)} ${p(0.29f, -0.9f)} ${p(0.5f, -0.9f)}" +
        "C${p(0.76f, -0.9f)} ${p(1f, -0.68f)} ${p(1f, -0.36f)}C${p(1f, 0.05f)} ${p(0.7f, 0.42f)} ${p(0f, 0.92f)}Z"
}

/** 一朵小云：(x, y) 是左上角，k 是缩放，原始大小约 22×13 */
private fun cloudPath(x: Float, y: Float, k: Float): String {
    fun p(px: Float, py: Float) = "${n(x + px * k)} ${n(y + py * k)}"
    fun r(v: Float) = n(v * k)
    return "M${p(5f, 13f)}A${r(4.2f)} ${r(4.2f)} 0 0 1 ${p(4.6f, 4.7f)}A${r(5.6f)} ${r(5.6f)} 0 0 1 ${p(15.2f, 3.6f)}A${r(4.8f)} ${r(4.8f)} 0 0 1 ${p(17f, 13f)}Z"
}

/** 一片花瓣：尖朝下、顶上有个小缺口，以原点为中心，高约 14 */
private const val PETAL = "M0 7C-3.6 4.2 -5 0.6 -4 -2.8C-3.4 -4.8 -1.9 -6.2 -0.9 -6.6L0 -5L0.9 -6.6C1.9 -6.2 3.4 -4.8 4 -2.8C5 0.6 3.6 4.2 0 7Z"

// ------------------------------------------------------------------ 纹样（patterns.ts）

internal class PatternTile(val width: Float, val height: Float, val glyph: Glyph)

private const val NOISE_SIZE = 32
private val NOISE_LEVELS = floatArrayOf(1f, 0.62f, 0.34f)

/** 固定种子的伪随机数（和电脑版同一个算法、同一个种子）：细颗粒每次画出来都是同一张 */
private fun noisePaths(): List<String> {
    var state = 20260212L
    fun random(): Double {
        state = (state * 1664525L + 1013904223L) % 4294967296L
        return state / 4294967296.0
    }
    val paths = Array(NOISE_LEVELS.size) { StringBuilder() }
    repeat(96) {
        val x = (random() * NOISE_SIZE).toInt()
        val y = (random() * NOISE_SIZE).toInt()
        paths[(random() * NOISE_LEVELS.size).toInt()].append("M$x ${y}h1v1h-1z")
    }
    return paths.map { it.toString() }
}

/** 电路的走线和焊点（40×40 一块）。从左边 y=10、y=30 进来的线，右边同一高度有出去的那一截；上下的 x=8、x=30 也一样 */
private const val CIRCUIT_TRACES = "M0 10h12l6 6v8h10M30 0v4l6 6h4M40 30H30l-4-4v-6M0 30h5M8 40v-6l5-5M8 0v3M30 40v-3"
private val CIRCUIT_PADS = listOf(28f to 24f, 26f to 20f, 13f to 29f, 5f to 30f, 8f to 3f, 30f to 37f)

// 后五个是 2.0.10 加的
val PatternKeys = setOf("dots", "grid", "stripes", "waves", "stars", "hearts", "pixels", "clouds", "petals", "noise", "scanlines", "circuit", "fizz", "ruled", "confetti")

private val tiles = HashMap<String, PatternTile>()

internal fun patternTile(key: String): PatternTile? = tiles.getOrPut(key) {
    when (key) {
        "dots" -> PatternTile(18f, 18f, glyph { circle(4.5f, 4.5f, 1.6f); circle(13.5f, 13.5f, 1.6f) })
        "grid" -> PatternTile(18f, 18f, glyph { stroke("M18 .5H.5V18", 1f, cap = StrokeCap.Butt) })
        "stripes" -> PatternTile(14f, 14f, glyph { stroke("M-3.5 3.5l7-7M0 14L14 0M10.5 17.5l7-7", 3f, cap = StrokeCap.Butt) })
        "waves" -> PatternTile(36f, 22f, glyph {
            stroke("M0 6q9-6 18 0t18 0", 1.5f)
            stroke("M-18 17q9-6 18 0t18 0t18 0", 1.5f)
        })
        "stars" -> PatternTile(46f, 46f, glyph {
            fill(sparklePath(10f, 11f, 4.6f)); fill(sparklePath(33f, 29f, 3.2f)); fill(sparklePath(37f, 8f, 1.9f))
            circle(21f, 37f, 1f); circle(24f, 17f, 0.8f); circle(5f, 30f, 0.7f); circle(42f, 42f, 0.6f)
        })
        "hearts" -> PatternTile(32f, 30f, glyph {
            fill(heartPath(0f, 0f, 4.4f), matrix = place(9f, 9f, -12f))
            fill(heartPath(0f, 0f, 3.2f), matrix = place(24f, 22f, 14f))
        })
        "pixels" -> PatternTile(20f, 20f, glyph {
            fill("M0 0h5v5h-5z"); fill("M10 5h5v5h-5z", alpha = 0.55f); fill("M5 15h5v5h-5z", alpha = 0.78f); fill("M15 10h5v5h-5z", alpha = 0.4f)
        })
        "clouds" -> PatternTile(66f, 40f, glyph { fill(cloudPath(5f, 3f, 1f)); fill(cloudPath(38f, 23f, 0.7f)) })
        "petals" -> PatternTile(44f, 44f, glyph {
            fill(PETAL, matrix = place(11f, 11f, -32f, 0.8f))
            fill(PETAL, matrix = place(33f, 28f, 38f, 0.62f))
            fill(PETAL, matrix = place(31f, 7f, 96f, 0.4f))
            fill(PETAL, matrix = place(9f, 35f, -80f, 0.46f))
        })
        "noise" -> PatternTile(NOISE_SIZE.toFloat(), NOISE_SIZE.toFloat(), glyph {
            noisePaths().forEachIndexed { level, d -> if (d.isNotEmpty()) fill(d, alpha = NOISE_LEVELS[level]) }
        })
        // 扫描线：老显示器那样，每三像素一道细横线
        "scanlines" -> PatternTile(6f, 3f, glyph { fill("M0 0h6v1h-6z") })
        // 电路：几条走线拐着弯，末端一个焊点
        "circuit" -> PatternTile(40f, 40f, glyph {
            stroke(CIRCUIT_TRACES, 1.2f, join = StrokeJoin.Round)
            CIRCUIT_PADS.forEach { (x, y) -> circle(x, y, 1.8f) }
        })
        // 汽水泡：大小不一的空心小圈，夹着两粒实心的
        "fizz" -> PatternTile(30f, 44f, glyph {
            ellipseStroke(7f, 35f, 3.2f, 3.2f, 1.1f); ellipseStroke(21f, 22f, 2.3f, 2.3f, 1.1f); ellipseStroke(9f, 11f, 1.6f, 1.6f, 1.1f); ellipseStroke(24f, 5f, 1.1f, 1.1f, 1.1f)
            circle(16f, 40f, 1f); circle(3f, 22f, 0.8f)
        })
        // 信纸横线：每 22 像素一道
        "ruled" -> PatternTile(10f, 22f, glyph { fill("M0 20.5h10v1h-10z") })
        // 彩纸屑：几条歪着的小纸条和圆点，深浅不一
        "confetti" -> PatternTile(44f, 44f, glyph {
            roundRect(5f, 6f, 6f, 2.4f, 1f, matrix = rotation(28f, 8f, 7.2f))
            roundRect(28f, 9f, 5f, 2.2f, 1f, alpha = 0.7f, matrix = rotation(-35f, 30.5f, 10.1f))
            circle(20f, 22f, 1.6f, alpha = 0.85f)
            roundRect(33f, 30f, 6f, 2.4f, 1f, matrix = rotation(62f, 36f, 31.2f))
            roundRect(8f, 33f, 5f, 2.2f, 1f, alpha = 0.6f, matrix = rotation(-12f, 10.5f, 34.1f))
            circle(40f, 18f, 1f, alpha = 0.7f)
            circle(24f, 40f, 1.2f, alpha = 0.5f)
        })
        else -> return null
    }
}

// ------------------------------------------------------------------ 角饰（ornaments.tsx），24×24 画板

// cursor 往后六个、headphones / flame 是 2.0.10 加的
val BubbleOrnamentKeys = setOf("sparkle", "star", "moon", "sun", "heart", "petal", "cloud", "paw", "leaf", "crown", "pixel_heart", "bolt", "snow", "cursor", "stamp", "cherry", "note", "koi", "planet")
val FrameOnlyOrnamentKeys = setOf("cat_ears", "halo", "horns", "laurel", "wings", "bow", "headphones", "flame")

/** animated 时怎么动：闪、晃、浮、跳、亮灭（气泡和头像框共用），耳朵抖、翅膀扇、火苗窜（头像框专用） */
enum class OrnamentMotion { Twinkle, Sway, Float, Pulse, Blink, Twitch, Flap, Burn }

fun ornamentMotion(key: String): OrnamentMotion = when (key) {
    "sparkle", "star", "sun", "snow", "bolt" -> OrnamentMotion.Twinkle
    "moon", "cloud" -> OrnamentMotion.Float
    "petal", "leaf", "paw", "crown" -> OrnamentMotion.Sway
    "heart", "pixel_heart" -> OrnamentMotion.Pulse
    "cursor" -> OrnamentMotion.Blink
    "stamp", "cherry", "koi" -> OrnamentMotion.Sway
    "note", "planet" -> OrnamentMotion.Float
    "cat_ears" -> OrnamentMotion.Twitch
    "halo", "laurel", "headphones" -> OrnamentMotion.Float
    "flame" -> OrnamentMotion.Burn
    "horns" -> OrnamentMotion.Pulse
    "wings" -> OrnamentMotion.Flap
    "bow" -> OrnamentMotion.Sway
    else -> OrnamentMotion.Twinkle
}

private fun point(radius: Float, degrees: Float): String {
    val a = Math.toRadians(degrees.toDouble())
    return "${n((12 + radius * sin(a)).toFloat())} ${n((12 - radius * cos(a)).toFloat())}"
}

private val STAR_POINTS: String = "M" + (0 until 10).joinToString("L") { point(if (it % 2 == 0) 9.6f else 4.3f, it * 36f) } + "Z"
private val SUN_RAYS: String = (0 until 8).joinToString("") { "M${point(7.6f, it * 45f)}L${point(10.4f, it * 45f)}" }
private const val SNOW_ARM = "M12 12V2.6M12 6.2l-2.4-2.3M12 6.2l2.4-2.3"
private val PIXEL_HEART: String = listOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...").flatMapIndexed { y, row ->
    row.mapIndexedNotNull { x, cell -> if (cell == 'X') "M${n(1.5f + x * 3)} ${n(3f + y * 3)}h3v3h-3z" else null }
}.joinToString("")

/** 月桂的一片叶子：从 (x, y) 朝 degrees 方向长出去的梭形 */
private fun leaf(x: Float, y: Float, length: Float, width: Float, degrees: Float): String {
    val a = Math.toRadians(degrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    val tipX = x + length * c
    val tipY = y + length * s
    val midX = x + length * 0.46f * c
    val midY = y + length * 0.46f * s
    return "M${n(x)} ${n(y)}Q${n(midX - width * s)} ${n(midY + width * c)} ${n(tipX)} ${n(tipY)}Q${n(midX + width * s)} ${n(midY - width * c)} ${n(x)} ${n(y)}Z"
}

/** 邮票四边的齿孔：每边五个小圆 */
private val STAMP_HOLES: String = listOf(4.8f, 8.4f, 12f, 15.6f, 19.2f).joinToString("") { at ->
    val dot = "a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0"
    "M${n(at - 1.1f)} 3${dot}M${n(at - 1.1f)} 21${dot}M1.9 ${n(at)}${dot}M19.9 ${n(at)}$dot"
}

private const val LAUREL_STEM = "M2.4 11.2Q12 14.6 21.6 9.4"
private val LAUREL_OUTER = listOf(leaf(3.6f, 11.9f, 6.4f, 1.9f, 44f), leaf(8.2f, 12.9f, 6.6f, 2f, 36f), leaf(13f, 13f, 6.4f, 1.9f, 26f), leaf(17.4f, 11.9f, 5.6f, 1.7f, 14f)).joinToString("")
private val LAUREL_INNER = listOf(leaf(5.2f, 11.9f, 5.6f, 1.7f, -50f), leaf(9.8f, 12.6f, 5.8f, 1.8f, -58f), leaf(14.4f, 12.2f, 5.4f, 1.7f, -66f)).joinToString("")
private val LAUREL_TIP = leaf(19.2f, 10.6f, 4.4f, 1.5f, -30f)
private const val WING_FEATHERS = "M4.6 15.4C8.4 14.6 12.6 12.4 15.8 8.6M6.2 17.4C10.4 17 14.6 15.2 18.2 12M8.4 19C11.8 19 15 18 17.6 16.2"
private const val BOW_TAILS = "M10.6 13.4L7 21.6L9.9 20.4L11.4 22.6L12.9 14.6ZM13.4 13.4L17 21.6L14.1 20.4L12.6 22.6L11.1 14.6Z"

private val White = Color.White
private val Black = Color.Black

private fun buildOrnament(key: String): Glyph? = when (key) {
    "sparkle" -> glyph {
        fill(sparklePath(10.5f, 13f, 9.4f)); fill(sparklePath(19.6f, 4.9f, 3.6f)); highlight(sparklePath(10.5f, 13f, 3.4f))
    }
    "star" -> glyph {
        solid(STAR_POINTS, 2.4f)
        stroke("M8.7 9.4L10.5 5.8", 1.5f, White, 0.6f)
    }
    "moon" -> glyph {
        fill("M20.6 14.6A9.3 9.3 0 1 1 9.4 3.4A7.3 7.3 0 0 0 20.6 14.6Z")
        highlight("M7.2 7.2A7.6 7.6 0 0 0 5.4 14A9 9 0 0 1 7.2 7.2Z")
        fill(sparklePath(18.4f, 6f, 2.8f))
    }
    "sun" -> glyph {
        stroke(SUN_RAYS, 2.1f)
        circle(12f, 12f, 5.2f)
        val d = "M8.6 11.2A3.6 3.6 0 0 1 11.6 8.5A4.6 4.6 0 0 0 8.6 11.2Z"
        highlight(d)
        stroke(d, 0.9f, White, 0.5f)
    }
    "heart" -> glyph {
        fill(heartPath(12f, 12.4f, 9.6f))
        stroke("M6.2 8.6C6.2 6.9 7.5 5.8 8.9 5.8", 1.5f, White, 0.6f)
    }
    "petal" -> glyph {
        group(rotation(-24f, 12f, 12f)) {
            fill("M12 22C7.2 18.2 4.2 13.4 4.9 8.6C5.4 5.2 7.6 2.6 9.9 2L12 6.2L14.1 2C16.4 2.6 18.6 5.2 19.1 8.6C19.8 13.4 16.8 18.2 12 22Z")
            shade("M12 21.2C10.5 18.6 10.3 16 12 12.6C13.7 16 13.5 18.6 12 21.2Z")
            stroke("M7.3 10C7.4 8 8.2 6.2 9.4 5.2", 1.3f, White, 0.6f)
        }
    }
    "cloud" -> glyph {
        fill("M6.8 19A4.6 4.6 0 0 1 6.1 9.9A6.3 6.3 0 0 1 18 8.9A5.1 5.1 0 0 1 17.6 19Z")
        stroke("M7.4 11.6A4.7 4.7 0 0 1 11.4 6.9", 1.4f, White, 0.6f)
    }
    "paw" -> glyph {
        fill("M12 11.6C8.7 11.6 6.2 14.3 6.2 17.1C6.2 19.3 7.9 20.5 9.8 20.5C10.8 20.5 11.3 20.1 12 20.1C12.7 20.1 13.2 20.5 14.2 20.5C16.1 20.5 17.8 19.3 17.8 17.1C17.8 14.3 15.3 11.6 12 11.6Z")
        ellipse(4.9f, 10.4f, 1.9f, 2.5f, -22f)
        ellipse(9.2f, 5.9f, 2f, 2.7f, -8f)
        ellipse(14.8f, 5.9f, 2f, 2.7f, 8f)
        ellipse(19.1f, 10.4f, 1.9f, 2.5f, 22f)
        ellipse(10.2f, 15.2f, 1.6f, 1.1f, -24f, White, 0.5f)
    }
    "leaf" -> glyph {
        fill("M4.3 19.9C3.4 11 8.6 4.2 19.9 4.1C20.3 14.9 13.9 20.6 4.3 19.9Z")
        stroke("M4.9 19.3L15.6 8.6M9 15.2V10.9M9 15.2H13.4", 1.1f, Black, 0.2f)
        stroke("M7 11C8.6 8.3 11.2 6.6 14.2 6", 1.2f, White, 0.5f)
    }
    "crown" -> glyph {
        solid("M4.3 17.4L3 7.8L8.2 11.8L12 4.9L15.8 11.8L21 7.8L19.7 17.4Z", 1.6f)
        roundRect(4f, 18.2f, 16f, 2.4f, 1.2f)
        roundRect(4f, 18.2f, 16f, 2.4f, 1.2f, Black, 0.16f)
        circle(12f, 14f, 1.5f, White, 0.5f)
        circle(7.6f, 15f, 1f, White, 0.5f)
        circle(16.4f, 15f, 1f, White, 0.5f)
    }
    "pixel_heart" -> glyph {
        fill(PIXEL_HEART)
        highlight("M4.5 6h3v3h-3z")
        shade("M16.5 12h3v3h-3zM13.5 15h3v3h-3zM10.5 18h3v3h-3z")
    }
    "bolt" -> glyph {
        solid("M13.9 2.2L4.9 13.3H11L9.7 21.8L19.1 10.2H12.7Z", 1.5f)
        highlight("M12.4 4.8L7.4 11.6H10Z")
    }
    "snow" -> glyph {
        for (angle in 0 until 360 step 60) stroke(SNOW_ARM, 1.7f, join = StrokeJoin.Round, matrix = rotation(angle.toFloat(), 12f, 12f))
    }
    // 光标：命令行的提示符「>」加一个方块光标
    "cursor" -> glyph {
        stroke("M2.6 6.6L8.2 12L2.6 17.4", 2.6f, join = StrokeJoin.Round)
        roundRect(11.4f, 4.6f, 9.6f, 14.8f, 1f)
        roundRect(12.8f, 6f, 2.4f, 5.6f, 0.6f, White, 0.5f)
    }
    // 邮票：齿孔边的方块，中间一块白底上是山和太阳
    "stamp" -> glyph {
        group(rotation(8f, 12f, 12f)) {
            roundRect(3f, 3f, 18f, 18f, 0.8f)
            fill(STAMP_HOLES, White, 0.92f)
            roundRect(6f, 6f, 12f, 12f, 0.6f, White, 0.9f)
            fill("M7.4 16.6L10.6 11.8L12.6 14.4L14.2 12.4L16.6 16.6Z")
            circle(14.6f, 9.2f, 1.5f)
        }
    }
    // 樱桃：两颗挂在一根分叉的梗上，梗顶一片叶子
    "cherry" -> glyph {
        stroke("M7.6 13.6C8.6 9 11.6 5.4 16 3.2M16.4 13.6C16.2 9.6 16.2 6.2 16 3.2", 1.5f, Color(0xFF6B4A2B))
        fill("M16 3.2C18 1.6 20.8 1.8 22 3.4C20.4 5 18 5.2 16 3.2Z", Color(0xFF4CAF50))
        circle(7.4f, 16.8f, 4.6f)
        circle(16.6f, 17.6f, 4.2f)
        shade("M16.6 21.8A4.2 4.2 0 0 0 20.6 16.4A4.6 4.6 0 0 1 16.6 21.8Z")
        circle(5.6f, 15f, 1.3f, White, 0.5f)
        circle(15f, 16f, 1.1f, White, 0.5f)
    }
    // 音符：两个连起来的八分音符
    "note" -> glyph {
        stroke("M8.6 17.4V6L19.8 3.4V14.8", 2f, join = StrokeJoin.Round)
        fill("M8.6 6L19.8 3.4V6.8L8.6 9.4Z")
        ellipse(6f, 17.8f, 3.4f, 2.6f, -20f)
        ellipse(17.2f, 15.2f, 3.4f, 2.6f, -20f)
        ellipse(5f, 17f, 1.2f, 0.7f, -20f, White, 0.5f)
        ellipse(16.2f, 14.4f, 1.2f, 0.7f, -20f, White, 0.5f)
    }
    // 锦鲤：头朝左，身上几块白斑，尾巴分叉
    "koi" -> glyph {
        fill("M18.4 12.4L23 8C22.5 10.6 22.5 14.2 23.2 17Z")
        fill("M2.6 12.6C5.6 7.4 12 5.8 16.6 9C18 10 18.8 11.2 19 12.4C18.8 13.6 18 14.8 16.6 15.8C12 19 5.6 17.8 2.6 12.6Z")
        fill("M10.6 16.6L9 20.4L12.8 17.8Z")
        shade("M10.6 16.6L9 20.4L12.8 17.8Z")
        fill("M8.4 9.6C10.2 8.4 12.6 8.4 14.2 9.8C12.8 11.4 10.2 11.6 8.4 9.6ZM12.8 14.6C14 13.4 15.8 13.2 17 14.2C15.8 15.4 14 15.6 12.8 14.6Z", White, 0.88f)
        circle(5.6f, 11.6f, 0.95f, Color(0xFF1A1A1A))
        stroke("M4.6 14.6C6.6 16.6 10 17.2 13 16.2", 1f, Black, 0.16f)
    }
    // 行星：一个球套着一圈斜着的环，环在球后面的那半截先画；前面那半截先垫一道白边，和球分开
    "planet" -> glyph {
        group(rotation(-20f, 12f, 12f)) {
            stroke("M1.6 12A10.4 3.4 0 0 1 22.4 12", 1.7f)
            circle(12f, 12f, 6.4f)
            shade("M12 18.4A6.4 6.4 0 0 0 18.4 12A5.4 5.4 0 0 1 12 18.4Z")
            stroke("M8.2 9.4A4.6 4.6 0 0 1 11.6 7.2", 1.3f, White, 0.6f)
            stroke("M1.6 12A10.4 3.4 0 0 0 22.4 12", 3.4f, White, 0.9f)
            stroke("M1.6 12A10.4 3.4 0 0 0 22.4 12", 1.7f)
        }
    }
    // ---- 头像框专用（frameOrnaments.tsx）。成对的只画右边那一片，左边那片由调用方镜像
    "cat_ears" -> glyph {
        solid("M3 22.4C3.2 14.2 7.2 6.6 14.4 2.5C15.5 1.9 16.8 2.6 17 3.9C17.9 10.3 19.6 16.4 21.6 22.4Z", 1.3f)
        fill("M7.6 21.8C8.1 15.6 10.6 10.4 14.4 7.2C15.2 12.4 16.4 17.4 17.9 21.8Z", Color(0xFFFF9FB8), 0.92f)
        stroke("M9.9 21.8C10.5 17.2 12 13.4 14 10.8", 1.1f, White, 0.5f)
        stroke("M5 16.4C5.9 11.6 8.6 7.4 12.6 4.6", 1.2f, White, 0.55f)
        stroke("M19.4 20.4C18.2 16.6 17.3 12.6 16.7 8.6", 1.3f, Black, 0.14f)
    }
    "halo" -> glyph {
        ellipseStroke(12f, 12f, 9.4f, 3.7f, 5.6f, alpha = 0.28f)
        ellipseStroke(12f, 12f, 9.4f, 3.7f, 2.7f)
        stroke("M3.4 13.4A9.4 3.7 0 0 0 20.6 13.4", 1.1f, Black, 0.16f)
        stroke("M4.2 10.2A9.4 3.7 0 0 1 14.6 8.5", 1.1f, White, 0.85f)
        fill("M20.2 4.2L20.9 6.1L22.8 6.8L20.9 7.5L20.2 9.4L19.5 7.5L17.6 6.8L19.5 6.1Z", White, 0.9f)
    }
    "horns" -> glyph {
        solid("M4.6 22.4C3.6 15.8 6.4 10 11.8 6.6C14.6 4.9 17.6 3.6 19.8 1.8C20.7 7.4 20 12.8 17.4 17.2C16.3 19.2 14.9 20.9 13.4 22.4Z", 1.1f)
        shade("M19.4 3.6C19.8 8.2 19 12.6 16.8 16.4C15.8 18.2 14.6 19.8 13.2 21.2C15.4 17.4 16.6 13.4 16.6 9.2C16.6 7.6 17.6 5.4 19.4 3.6Z")
        stroke("M6.2 16.6C8.6 15.4 11.4 15.6 14 17.2M8 12C10.4 11 13 11.4 15.6 13M11.4 8C13.2 7.4 15 7.8 16.8 9", 1f, Black, 0.2f)
        stroke("M6.4 19.6C6.2 15.2 8.2 11.2 12 8.6", 1.3f, White, 0.55f)
    }
    "laurel" -> glyph {
        stroke(LAUREL_STEM, 1.5f)
        fill(LAUREL_OUTER)
        fill(LAUREL_INNER)
        shade(LAUREL_INNER)
        fill(LAUREL_TIP)
        stroke("M5.2 13.4L7.8 15.6M9.8 14.2L12.6 16M14.4 14L17.2 15.2", 0.9f, White, 0.55f)
    }
    "wings" -> glyph {
        solid("M1.6 15.2C2.4 8.6 8 3.6 16.4 2.2C19.4 1.7 21.9 2.3 23 3.4C21.5 4.3 20.4 5.5 19.8 7C21.3 7 22.4 7.5 23 8.4C21.3 9.2 20 10.4 19.2 11.9C20.5 12.1 21.4 12.7 21.9 13.6C19.9 14.3 18.3 15.4 17.2 16.9C18.2 17.3 18.9 17.9 19.2 18.8C14.2 21.9 7 21.6 1.6 15.2Z", 0.9f)
        shade("M1.6 15.2C6.4 20.6 13 21.4 19.2 18.8C18.9 17.9 18.2 17.3 17.2 16.9C13 18.6 7.6 18.6 1.6 15.2Z")
        stroke(WING_FEATHERS, 0.9f, Black, 0.18f)
        stroke("M3.8 12.2C5.6 8 9.8 5 15.6 3.9", 1.2f, White, 0.7f)
    }
    "bow" -> glyph {
        solid(BOW_TAILS, 0.8f)
        shade(BOW_TAILS)
        solid("M11.4 11.6C8.4 6.8 4.4 4.9 2.5 6.3C0.9 8.6 1.2 14.4 2.9 16.5C5.2 17.6 8.9 15.6 11.4 11.6Z", 1f)
        solid("M12.6 11.6C15.6 6.8 19.6 4.9 21.5 6.3C23.1 8.6 22.8 14.4 21.1 16.5C18.8 17.6 15.1 15.6 12.6 11.6Z", 1f)
        stroke("M10.4 11.7C8.2 12 5.8 13.2 3.6 15.2M13.6 11.7C15.8 12 18.2 13.2 20.4 15.2", 1f, Black, 0.2f)
        stroke("M3.4 9.4C3.8 8 4.8 7.2 6.2 7.4M17.8 7.4C19.2 7.2 20.2 8 20.6 9.4", 1.2f, White, 0.65f)
        roundRect(9.7f, 9.1f, 4.6f, 5.4f, 1.9f)
        roundRect(9.7f, 9.1f, 4.6f, 5.4f, 1.9f, Black, 0.16f)
        stroke("M10.9 10.4C11.3 9.9 12 9.8 12.6 10", 0.9f, White, 0.6f)
    }
    // 耳机：一道头梁，两只耳罩，耳罩上一圈软垫
    "headphones" -> glyph {
        stroke("M3.4 16.4C3.4 9.4 7.2 4.8 12 4.8C16.8 4.8 20.6 9.4 20.6 16.4", 2.4f)
        stroke("M5.4 10.6C6.6 8 9 6.4 12 6.4", 1f, White, 0.55f)
        roundRect(0.8f, 13.2f, 5.6f, 9f, 2.6f)
        roundRect(17.6f, 13.2f, 5.6f, 9f, 2.6f)
        roundRect(4.4f, 14.6f, 2.4f, 6.2f, 1.2f, Black, 0.16f)
        roundRect(17.2f, 14.6f, 2.4f, 6.2f, 1.2f, Black, 0.16f)
        stroke("M2.2 15.6V19.4M19 15.6V19.4", 1.1f, White, 0.55f)
    }
    // 火苗：三簇连在一起的火，中间高、两边矮；外焰用主体色，里面一团亮黄的内焰
    "flame" -> glyph {
        fill("M12 23.2C6.6 23.2 1.6 21 1 17C0.6 14.2 2 11.8 3.2 9.8C4.2 11.4 4.6 12.8 4.4 14C5.6 12.6 6.4 10.6 6.4 8.2C8.2 9.4 9 11 9.2 12.4C10.4 10 11.4 6.4 11.6 1.6C15.2 4.4 16.4 8.6 15.6 12.4C16.4 11.2 17.6 10.2 18 8.2C19.4 10 19.6 12.2 19.6 14C20.4 12.8 20.6 11.2 20.8 9.8C22.4 11.8 23.4 14.4 23 17C22.4 21 17.4 23.2 12 23.2Z")
        fill("M12 21.8C8.6 21.8 6.4 20.4 6.6 18.2C6.8 16.4 8.4 15.2 9.2 13.6C10.2 14.6 10.6 15.6 10.6 16.6C11.6 15.4 12.4 13.6 12.4 11.4C14.8 13 16.8 15.4 17 17.8C17.2 20.2 15.2 21.8 12 21.8Z", Color(0xFFFFE08A), 0.95f)
        stroke("M3 15.6C3.2 14 3.8 12.8 4.6 11.8M9.6 9.6C10.4 8 10.8 6.4 11 4.8", 1.1f, White, 0.5f)
    }
    else -> null
}

private val ornaments = HashMap<String, Glyph?>()

internal fun ornamentGlyph(key: String): Glyph? = ornaments.getOrPut(key) { buildOrnament(key) }

// ------------------------------------------------------------------ 画

private val OutlineInk = Color(15, 18, 30)

/**
 * 在 (0,0)–(box,box) 里画一个 24×24 画板的角饰。shadow 为 true 时先垫一圈贴身的暗边和一层很轻的投影
 * （电脑版是 drop-shadow(0 0 .5px rgba(15,18,30,.5)) drop-shadow(0 1px 1.5px rgba(15,18,30,.2))，这里用描边和错位近似，不用模糊）。
 * [px] 是 1 个 CSS 像素在这里是多少（即 density）。
 */
internal fun DrawScope.drawOrnamentGlyph(glyph: Glyph, color: Color, box: Float, px: Float, shadow: Boolean = true, alpha: Float = 1f) {
    val k = box / 24f
    scale(k, k, pivot = Offset.Zero) {
        if (shadow) {
            val outline = px / k
            translate(0f, px / k) {
                for (layer in glyph.layers) {
                    if (layer.color != null) continue
                    drawPath(layer.path, OutlineInk, alpha = 0.2f * layer.alpha * color.alpha * alpha, style = layer.stroke ?: Fill)
                }
            }
            for (layer in glyph.layers) {
                if (layer.color != null) continue
                val width = (layer.stroke?.width ?: 0f) + outline
                drawPath(layer.path, OutlineInk, alpha = 0.5f * layer.alpha * color.alpha * alpha, style = Stroke(width, cap = layer.stroke?.cap ?: StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        drawGlyphLayers(glyph, color, alpha)
    }
}

/** 按画板坐标画（调用方负责缩放）。主体色的透明度和每层自己的透明度相乘 */
internal fun DrawScope.drawGlyphLayers(glyph: Glyph, color: Color, alpha: Float = 1f) {
    for (layer in glyph.layers) {
        val c = layer.color ?: color
        drawPath(layer.path, c, alpha = (layer.alpha * alpha).coerceIn(0f, 1f), style = layer.stroke ?: Fill)
    }
}
