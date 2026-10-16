package ink.kuke.chat.feature.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 商品样式（气泡皮肤 docs/shop.md 2.2、头像框 2.4）的 Android 解析。画法在 SkinDraw.kt，内置图形在 SkinGlyphs.kt。
 * 规则照搬电脑版（KukeChat-Client src/components/shop/skin/bubbleSkin.ts、avatarFrame.ts）：
 *   · style 来自网络，哪怕服务端校验过这里也当它不可信：只读契约里的字段，颜色只认 #rgb / #rrggbb / #rrggbbaa，
 *     背景只认一个颜色或 linear / radial（头像框的环另认 conic）渐变；纹样、角饰只是一个名字，图形是客户端内置的；
 *   · 不认识的 version、不认识的字段 → 整件作废，按没装备处理；底色 / 字色（头像框是环）不合法 → 整件作废；
 *   · 其余可空的字段不合法 → 只丢掉这一个；纹样、角饰的 key（以及角、方位）不认识 → 跳过那一项，其余照常画；
 *   · dark 里有任何一项不合法 → 整个深色覆盖作废，沿用浅色那一套。
 *
 * 已支持（和电脑版同一套图形和参数）：
 *   气泡 —— 纯色 / 线性与径向渐变、文字与链接色、边框、圆角、阴影（最多两层，偏移 模糊 [扩散] 颜色）、
 *           纹样 pattern（十种，裁在气泡形状内）、角饰 ornaments（十三种，骑在四个角上）、
 *           动效 flow / shimmer / breathe、角饰的闪 / 晃 / 浮 / 跳，深色覆盖（含 shadow、pattern）；
 *   头像框 —— 纯色 / 线性 / 径向 / conic 渐变的环、留白、外发光、装饰（十九种，八个方位，成对的猫耳 / 角 / 翅膀 / 月桂拆成左右两片），
 *           动效 spin / pulse / shimmer、装饰的闪 / 晃 / 浮 / 跳 / 抖耳 / 扇翅，深色覆盖；尺寸都按 40dp 头像等比缩放。
 *   系统关了动画（rememberReducedMotion）时 animation 当作 none、animated 当作 false，纹样和角饰本身照常画。
 * 降级：
 *   · 阴影、光晕的高斯模糊在 Android 8.x（API 26–27）的硬件画布上画不出来，退成不模糊的淡色；
 *   · 角饰 / 装饰的 drop-shadow 描边用「贴身暗边 + 错位 1dp 的淡影」近似，不做模糊。
 */

// ------------------------------------------------------------------ 颜色与渐变

private val HexColor = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/** CSS 的 #rgb / #rrggbb / #rrggbbaa → Color（注意 8 位是 rrggbbaa，不是 Android 的 aarrggbb） */
fun parseCssColor(raw: String?): Color? {
    val text = raw?.trim() ?: return null
    if (!HexColor.matches(text)) return null
    val hex = text.drop(1)
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("") + "ff"
        6 -> hex + "ff"
        else -> hex
    }
    val r = full.substring(0, 2).toInt(16)
    val g = full.substring(2, 4).toInt(16)
    val b = full.substring(4, 6).toInt(16)
    val a = full.substring(6, 8).toInt(16)
    return Color(r, g, b, a)
}

sealed interface SkinFill {
    data class Solid(val color: Color) : SkinFill
    data class Linear(val angle: Float, val stops: List<Pair<Float, Color>>) : SkinFill
    data class Radial(val stops: List<Pair<Float, Color>>) : SkinFill
    data class Conic(val from: Float, val stops: List<Pair<Float, Color>>) : SkinFill

    /** 主色：渐变取居中的那个色标（用来做降级色、阴影色） */
    val main: Color
        get() = when (this) {
            is Solid -> color
            is Linear -> stops[stops.size / 2].second
            is Radial -> stops[stops.size / 2].second
            is Conic -> stops[stops.size / 2].second
        }
}

private val GradientRegex = Regex("^(linear|radial|conic)-gradient\\(([^()]*)\\)$", RegexOption.IGNORE_CASE)
private val AngleRegex = Regex("^(-?\\d{1,4}(?:\\.\\d{1,3})?)(deg|grad|rad|turn)$", RegexOption.IGNORE_CASE)

private fun parseAngle(token: String): Float? {
    val m = AngleRegex.matchEntire(token.trim()) ?: return null
    val v = m.groupValues[1].toFloat()
    return when (m.groupValues[2].lowercase()) {
        "deg" -> v
        "grad" -> v * 0.9f
        "rad" -> (v * 180f / Math.PI).toFloat()
        else -> v * 360f
    }
}

private fun parsePosition(token: String, conic: Boolean): Float? = when {
    token.endsWith("%") -> token.dropLast(1).toFloatOrNull()?.let { it / 100f }
    conic -> parseAngle(token)?.let { it / 360f }
    else -> null
}

/** 一个颜色，或一个渐变（里面只能有角度、颜色和百分比）。允许 conic 时用于头像框 */
fun parseFill(raw: String?, allowConic: Boolean = false): SkinFill? {
    val text = raw?.trim() ?: return null
    if (text.length > 400) return null
    parseCssColor(text)?.let { return SkinFill.Solid(it) }
    val m = GradientRegex.matchEntire(text) ?: return null
    val kind = m.groupValues[1].lowercase()
    if (kind == "conic" && !allowConic) return null
    var angle = if (kind == "linear") 180f else 0f
    var from = 0f
    val raws = mutableListOf<Pair<Float?, Color>>()
    val parts = m.groupValues[2].split(',')
    for ((index, part) in parts.withIndex()) {
        val tokens = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (index == 0 && kind == "linear" && tokens.size == 1 && parseAngle(tokens[0]) != null) {
            angle = parseAngle(tokens[0])!!; continue
        }
        if (index == 0 && kind == "conic" && tokens.size == 2 && tokens[0].equals("from", ignoreCase = true) && parseAngle(tokens[1]) != null) {
            from = parseAngle(tokens[1])!!; continue
        }
        val color = parseCssColor(tokens[0])
        if (color != null) {
            if (tokens.size > 3) return null
            val position = tokens.getOrNull(1)?.let { parsePosition(it, kind == "conic") ?: return null }
            raws += position to color
            continue
        }
        // 两个色标之间单独的百分比（过渡中点）：忽略
        if (tokens.size == 1 && tokens[0].endsWith("%") && raws.isNotEmpty() && index < parts.lastIndex) continue
        return null
    }
    if (raws.size < 2 || raws.size > 12) return null
    val stops = raws.mapIndexed { i, (pos, color) -> (pos ?: (i.toFloat() / (raws.size - 1))).coerceIn(0f, 1f) to color }
    return when (kind) {
        "linear" -> SkinFill.Linear(angle, stops)
        "radial" -> SkinFill.Radial(stops)
        else -> SkinFill.Conic(from, stops)
    }
}

/** 按 CSS 的规则把角度换成渐变线：长度 = |w·sin| + |h·cos|，穿过中心 */
private class CssLinearBrush(private val angle: Float, private val stops: List<Pair<Float, Color>>) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val rad = Math.toRadians(angle.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val length = abs(size.width * dx) + abs(size.height * dy)
        val center = Offset(size.width / 2f, size.height / 2f)
        val start = Offset(center.x - dx * length / 2f, center.y - dy * length / 2f)
        val end = Offset(center.x + dx * length / 2f, center.y + dy * length / 2f)
        return LinearGradientShader(start, end, stops.map { it.second }, stops.map { it.first })
    }
}

/** CSS 默认的 radial-gradient：椭圆、farthest-corner（半径是半宽、半高的 √2 倍） */
private class CssRadialBrush(private val stops: List<Pair<Float, Color>>) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val center = Offset(w / 2f, h / 2f)
        val shader = RadialGradientShader(center, w / 2f * 1.41421356f, stops.map { it.second }, stops.map { it.first })
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, h / w, center.x, center.y) })
        return shader
    }
}

/** CSS 的 conic-gradient 从正上方顺时针起算（Android 的扫描渐变从正右方起），再加上 from 的角度；extra 是额外转多少度 */
internal class CssConicBrush(private val from: Float, private val stops: List<Pair<Float, Color>>, private val extra: Float = 0f) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val center = Offset(size.width / 2f, size.height / 2f)
        val shader = SweepGradientShader(center, stops.map { it.second }, stops.map { it.first })
        shader.setLocalMatrix(android.graphics.Matrix().apply { setRotate(from - 90f + extra, center.x, center.y) })
        return shader
    }
}

fun SkinFill.toBrush(): Brush = when (this) {
    is SkinFill.Solid -> SolidColor(color)
    is SkinFill.Linear -> CssLinearBrush(angle, stops)
    is SkinFill.Radial -> CssRadialBrush(stops)
    is SkinFill.Conic -> CssConicBrush(from, stops)
}

/** 渐变的「居中的那个色标」（电脑版取 stops[(n-1)/2]），去掉透明度：光晕、发光的回退色 */
internal val SkinFill.middle: Color
    get() = when (this) {
        is SkinFill.Solid -> color
        is SkinFill.Linear -> stops[(stops.size - 1) / 2].second
        is SkinFill.Radial -> stops[(stops.size - 1) / 2].second
        is SkinFill.Conic -> stops[(stops.size - 1) / 2].second
    }.copy(alpha = 1f)

internal val SkinFill.isGradient: Boolean get() = this !is SkinFill.Solid

// ------------------------------------------------------------------ JSON 小工具

private fun JsonElement?.absent(): Boolean = this == null || this is JsonNull

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

private fun JsonElement?.inRange(min: Double, max: Double): Double? = number()?.takeIf { it in min..max }

private fun JsonObject.onlyKnown(known: Set<String>): Boolean = keys.all { it in known }

private fun readColor(element: JsonElement?): Color? = parseCssColor(element.string())

private fun round1(v: Double, step: Double) = (Math.round(v / step) * step)

/** 可空对象字段的读取结果：值不合法（只丢这一项）/ 里面有不认识的字段（整件作废）/ 正常（可能是 null：跳过） */
private sealed interface Read<out T> {
    data class Ok<T>(val value: T) : Read<T>
    data object Invalid : Read<Nothing>
    data object UnknownField : Read<Nothing>
}

private fun <T> Read<T>.valueOrNull(): T? = (this as? Read.Ok<T>)?.value

// ------------------------------------------------------------------ 气泡皮肤

/** 一层阴影（CSS 像素 = dp）：偏移、模糊、扩散、颜色 */
class SkinShadow(val x: Float, val y: Float, val blur: Float, val spread: Float, val color: Color)

/** 纹样：key 是内置图案的名字，opacity 0–0.6，scale 0.5–2 */
class SkinPattern(val key: String, val color: Color, val opacity: Float, val scale: Float)

/**
 * 一种配色下的气泡。shadows 按 CSS 的顺序（第一层在最上面）；glow 是 breathe 光晕的颜色：
 * 有阴影取阴影的第一个颜色，否则取底色居中的那个色标。
 */
class SkinPaint(
    val fill: SkinFill,
    val text: Color,
    val link: Color,
    val border: Pair<Color, Float>?,
    val shadows: List<SkinShadow> = emptyList(),
    val pattern: SkinPattern? = null,
    val glow: Color = shadows.firstOrNull()?.color?.copy(alpha = 1f) ?: fill.middle,
)

enum class BubbleAnimation { None, Flow, Shimmer, Breathe }

/** 角饰：corner ∈ tl / tr / bl / br，size 10–28（dp） */
class BubbleOrnament(val key: String, val corner: String, val color: Color, val size: Int, val animated: Boolean)

class BubbleSkin(
    val light: SkinPaint,
    val dark: SkinPaint,
    val radius: Int?,
    val animation: BubbleAnimation = BubbleAnimation.None,
    val ornaments: List<BubbleOrnament> = emptyList(),
) {
    fun paint(isDark: Boolean) = if (isDark) dark else light

    /**
     * 角饰探出气泡上、下沿的部分超过原有空隙（3dp）时，给气泡垫出来的外边距（dp）。
     * 伸进气泡里的那一截不超过 8dp（落在内边距里，盖不到字），其余的探在外面，至少探出 40%。
     */
    val marginTop: Int = rise('t')
    val marginBottom: Int = rise('b')

    private fun rise(edge: Char): Int {
        val r = ornaments.filter { it.corner[0] == edge }.maxOfOrNull { max(it.size * ORNAMENT_RISE, it.size - ORNAMENT_INSET) - ORNAMENT_FREE_SPACE } ?: 0f
        return ceil(max(0f, r)).toInt()
    }
}

internal const val ORNAMENT_RISE = 0.4f
internal const val ORNAMENT_INSET = 8f
private const val ORNAMENT_FREE_SPACE = 3f

private val BUBBLE_FIELDS = setOf("version", "background", "text", "link", "border", "radius", "shadow", "animation", "pattern", "ornaments", "dark")
private val BUBBLE_DARK_FIELDS = setOf("background", "text", "link", "border", "shadow", "pattern")
private val BORDER_FIELDS = setOf("color", "width")
private val PATTERN_FIELDS = setOf("key", "color", "opacity", "scale")
private val BUBBLE_ORNAMENT_FIELDS = setOf("key", "corner", "color", "size", "animated")
private val CORNERS = setOf("tl", "tr", "bl", "br")
private val LENGTH = Regex("^(-?\\d{1,3}(?:\\.\\d{1,2})?)(px)?$", RegexOption.IGNORE_CASE)

/** 宽度 0 也是合法的边框：表示不要边框（Ok(null)） */
private fun readBorder(element: JsonElement?): Read<Pair<Color, Float>?> {
    val obj = element as? JsonObject ?: return Read.Invalid
    if (!obj.onlyKnown(BORDER_FIELDS)) return Read.UnknownField
    val color = readColor(obj["color"]) ?: return Read.Invalid
    val width = obj["width"].inRange(0.0, 3.0) ?: return Read.Invalid
    val rounded = round1(width, 0.5).toFloat()
    return Read.Ok(if (rounded > 0f) color to rounded else null)
}

private fun readLength(token: String): Float? {
    val m = LENGTH.matchEntire(token) ?: return null
    val amount = m.groupValues[1].toFloatOrNull() ?: return null
    // 不带单位的只有 0 是合法的长度
    if (amount != 0f && m.groupValues[2].isEmpty()) return null
    return amount
}

/** 「偏移 偏移 模糊 [扩散] 颜色」，最多两层；不接受 inset。数值夹到不会糊住旁边消息的范围里 */
private fun readShadow(element: JsonElement?): List<SkinShadow>? {
    val text = element.string() ?: return null
    if (text.length > 160) return null
    val layers = text.split(',')
    if (layers.size > 2) return null
    return layers.map { layer ->
        val tokens = layer.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size != 4 && tokens.size != 5) return null
        val color = parseCssColor(tokens.last()) ?: return null
        val x = readLength(tokens[0]) ?: return null
        val y = readLength(tokens[1]) ?: return null
        val blur = readLength(tokens[2]) ?: return null
        val spread = if (tokens.size == 5) readLength(tokens[3]) ?: return null else 0f
        if (blur < 0f) return null
        SkinShadow(x.coerceIn(-16f, 16f), y.coerceIn(-16f, 16f), blur.coerceIn(0f, 32f), spread.coerceIn(-6f, 6f), color)
    }
}

/** 纹样。key 不认识、opacity 为 0 → Ok(null)（跳过，不算错） */
private fun readPattern(element: JsonElement?): Read<SkinPattern?> {
    val obj = element as? JsonObject ?: return Read.Invalid
    if (!obj.onlyKnown(PATTERN_FIELDS)) return Read.UnknownField
    val key = obj["key"].string() ?: return Read.Invalid
    if (key !in PatternKeys) return Read.Ok(null)
    val color = readColor(obj["color"]) ?: return Read.Invalid
    val opacity = if (obj["opacity"].absent()) 0.18 else obj["opacity"].inRange(0.0, 0.6) ?: return Read.Invalid
    val scale = if (obj["scale"].absent()) 1.0 else obj["scale"].inRange(0.5, 2.0) ?: return Read.Invalid
    if (opacity == 0.0) return Read.Ok(null)
    return Read.Ok(SkinPattern(key, color, round1(opacity, 0.001).toFloat(), round1(scale, 0.01).toFloat()))
}

/** 角饰：最多两个。某一个的 key / corner 不认识或取值不合法就跳过那一个；同一个角上只留第一个 */
private fun readBubbleOrnaments(element: JsonElement?): Read<List<BubbleOrnament>> {
    val array = element as? JsonArray ?: return Read.Invalid
    if (array.size > 2) return Read.Invalid
    val result = mutableListOf<BubbleOrnament>()
    for (entry in array) {
        val obj = entry as? JsonObject ?: continue
        if (!obj.onlyKnown(BUBBLE_ORNAMENT_FIELDS)) return Read.UnknownField
        val key = obj["key"].string()?.takeIf { it in BubbleOrnamentKeys } ?: continue
        val corner = obj["corner"].string()?.takeIf { it in CORNERS } ?: continue
        val color = readColor(obj["color"]) ?: continue
        val size = if (obj["size"].absent()) 18.0 else obj["size"].inRange(10.0, 28.0) ?: continue
        if (result.any { it.corner == corner }) continue
        result += BubbleOrnament(key, corner, color, size.roundToInt(), (obj["animated"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true")
    }
    return Read.Ok(result)
}

/** 校验并解析气泡皮肤的 style（规则见文件头）。不是合法的皮肤返回 null，按没装备处理 */
fun parseBubbleSkin(style: JsonElement?): BubbleSkin? {
    val obj = style as? JsonObject ?: return null
    if (obj["version"].number() != 1.0 || !obj.onlyKnown(BUBBLE_FIELDS)) return null
    val fill = parseFill(obj["background"].string()) ?: return null
    val text = readColor(obj["text"]) ?: return null

    val border = if (obj["border"].absent()) Read.Ok(null) else readBorder(obj["border"])
    val pattern = if (obj["pattern"].absent()) Read.Ok(null) else readPattern(obj["pattern"])
    val ornaments = if (obj["ornaments"].absent()) Read.Ok(emptyList()) else readBubbleOrnaments(obj["ornaments"])
    if (border is Read.UnknownField || pattern is Read.UnknownField || ornaments is Read.UnknownField) return null
    val shadow = if (obj["shadow"].absent()) null else readShadow(obj["shadow"])
    val light = SkinPaint(fill, text, readColor(obj["link"]) ?: text, border.valueOrNull(), shadow.orEmpty(), pattern.valueOrNull())

    var dark = light
    val override = obj["dark"] as? JsonObject
    if (override != null) {
        if (!override.onlyKnown(BUBBLE_DARK_FIELDS)) return null
        val darkFill = if (override["background"].absent()) null else parseFill(override["background"].string())
        val darkText = if (override["text"].absent()) null else readColor(override["text"])
        val darkLink = if (override["link"].absent()) null else readColor(override["link"])
        val darkShadow = if (override["shadow"].absent()) null else readShadow(override["shadow"])
        val darkBorder = if (override["border"].absent()) null else readBorder(override["border"])
        val darkPattern = if (override["pattern"].absent()) null else readPattern(override["pattern"])
        if (darkBorder is Read.UnknownField || darkPattern is Read.UnknownField) return null
        val valid = (override["background"].absent() || darkFill != null) &&
            (override["text"].absent() || darkText != null) &&
            (override["link"].absent() || darkLink != null) &&
            (override["shadow"].absent() || darkShadow != null) &&
            darkBorder !is Read.Invalid && darkPattern !is Read.Invalid
        if (valid) {
            val repainted = darkFill != null || darkText != null
            dark = SkinPaint(
                fill = darkFill ?: light.fill,
                text = darkText ?: light.text,
                // 写了 link 就用它；没写时底色或字色换过，浅色那个链接色不一定还读得清，退回深色的字色
                link = darkLink ?: if (repainted) darkText ?: light.text else light.link,
                border = if (darkBorder is Read.Ok) darkBorder.value else light.border,
                shadows = darkShadow ?: light.shadows,
                // 没写就沿用浅色的纹样；写了但 key 不认识就是深色下不要纹样
                pattern = if (darkPattern is Read.Ok) darkPattern.value else light.pattern,
            )
        }
    }

    val radius = obj["radius"].inRange(4.0, 22.0)?.roundToInt()
    val animation = when (obj["animation"].string()) {
        "flow" -> BubbleAnimation.Flow
        "shimmer" -> BubbleAnimation.Shimmer
        "breathe" -> BubbleAnimation.Breathe
        else -> BubbleAnimation.None
    }
    return BubbleSkin(light, dark, radius, animation, ornaments.valueOrNull().orEmpty())
}

/** 一条用皮肤画的气泡（试穿、礼物卡片用）：形状和聊天里自己的消息一样，右上角是小尖角 */
@Composable
fun SkinBubble(skin: BubbleSkin?, text: String, modifier: Modifier = Modifier, fallbackRadius: Int = 14, seed: Long = 0) {
    val colors = KcTheme.colors
    val radius = (skin?.radius ?: fallbackRadius).dp
    val shape = RoundedCornerShape(radius, 4.dp, radius, radius)
    val paint = skin?.paint(colors.isDark)
    val m = if (skin != null && paint != null) modifier.bubbleSkin(skin, paint, shape, seed) else modifier.clip(shape).background(colors.bubbleOut)
    Box(m.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, color = paint?.text ?: colors.onFill, fontSize = 14.sp)
    }
}

/**
 * 试穿舞台：上面一条对方的、下面一条自己的（自己的用这件皮肤）。画不出这件皮肤时自己的那条用主题默认气泡。
 */
@Composable
fun BubbleStage(style: JsonElement?, previewText: String, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val skin = remember(style) { parseBubbleSkin(style) }
    val text = previewText.ifBlank { DefaultPreviewText }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.chat).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(colors.bubbleIn).border(1.dp, colors.border, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(text, color = colors.text, fontSize = 14.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SkinBubble(skin, text)
        }
    }
}

const val DefaultPreviewText = "今天也要开心呀"

// ------------------------------------------------------------------ 头像框

/** 一种配色下的环：底色，外发光（颜色 + 模糊，按 40dp 头像计） */
class FramePaint(val fill: SkinFill, val glow: Pair<Color, Float>?)

enum class FrameAnimation { None, Spin, Pulse, Shimmer }

/**
 * 算好位置的一片装饰（成对的装饰在这里已经拆成左右两片）。
 * x / y 是中心相对头像方块的比例（0.5 是正中），size 按 40dp 头像计，turn 是自身转的角度，mirror 是左右镜像，back 是垫在头像后面。
 */
class FramePiece(val key: String, val color: Color, val animated: Boolean, val back: Boolean, val x: Float, val y: Float, val size: Float, val turn: Float, val mirror: Boolean)

/** 头像框。宽度、留白按 40dp 头像计 */
class AvatarFrame(
    val light: FramePaint,
    val dark: FramePaint,
    val width: Float,
    val gap: Float,
    val animation: FrameAnimation,
    val pieces: List<FramePiece>,
) {
    fun paint(isDark: Boolean) = if (isDark) dark else light

    /** 浅色的环（礼物卡片等只画浅色的地方用） */
    val ring: SkinFill get() = light.fill
    val glow: Color? get() = light.glow?.first
}

private val FRAME_FIELDS = setOf("version", "ring", "glow", "animation", "ornaments", "dark")
private val RING_FIELDS = setOf("background", "width", "gap")
private val GLOW_FIELDS = setOf("color", "blur")
private val FRAME_ORNAMENT_FIELDS = setOf("key", "position", "color", "size", "animated")
private val FRAME_DARK_FIELDS = setOf("ring", "glow")
private val FRAME_DARK_RING_FIELDS = setOf("background")
private val POSITION_ANGLE = mapOf("top" to 0f, "tr" to 45f, "right" to 90f, "br" to 135f, "bottom" to 180f, "bl" to 225f, "left" to 270f, "tl" to 315f)

/** 头像的半径（按 40dp 计）；装饰最多探出头像多远（上下左右各自量） */
private const val FRAME_RADIUS = 20f
private const val MAX_OVERHANG = 11f

/** blur 为 0 → Ok(null)（不要光） */
private fun readGlow(element: JsonElement?, fallbackBlur: Float): Read<Pair<Color, Float>?> {
    val obj = element as? JsonObject ?: return Read.Invalid
    if (!obj.onlyKnown(GLOW_FIELDS)) return Read.UnknownField
    val color = readColor(obj["color"]) ?: return Read.Invalid
    val blur = if (obj["blur"].absent()) fallbackBlur.toDouble() else obj["blur"].inRange(0.0, 16.0) ?: return Read.Invalid
    return Read.Ok(if (blur == 0.0) null else color to round1(blur, 0.1).toFloat())
}

private class FrameOrnamentEntry(val key: String, val position: String, val color: Color, val size: Int, val animated: Boolean)

private fun readFrameOrnaments(element: JsonElement?): Read<List<FrameOrnamentEntry>> {
    val array = element as? JsonArray ?: return Read.Invalid
    if (array.size > 3) return Read.Invalid
    val result = mutableListOf<FrameOrnamentEntry>()
    for (entry in array) {
        val obj = entry as? JsonObject ?: continue
        if (!obj.onlyKnown(FRAME_ORNAMENT_FIELDS)) return Read.UnknownField
        val key = obj["key"].string()?.takeIf { it in BubbleOrnamentKeys || it in FrameOnlyOrnamentKeys } ?: continue
        val position = obj["position"].string()?.takeIf { it in POSITION_ANGLE } ?: continue
        val color = readColor(obj["color"]) ?: continue
        val size = if (obj["size"].absent()) 18.0 else obj["size"].inRange(10.0, 28.0) ?: continue
        if (result.any { it.position == position }) continue
        result += FrameOrnamentEntry(key, position, color, size.roundToInt(), (obj["animated"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true")
    }
    return Read.Ok(result)
}

/**
 * 每种装饰怎么贴到环上（同电脑版 frameOrnaments.tsx 的 LAYOUT）：
 *   pair 成对，spread 是每片偏离方位的角度；back 垫在头像后面；turn out 朝外 / hug 贴着环；lift 中心比环外沿再往外多少（乘 size）；
 *   half 图形在画板里实际占的半宽、半高（占 size 的比例）。
 */
private class FrameLayout(val pair: Float? = null, val back: Boolean = false, val turn: String? = null, val lift: Float? = null, val halfX: Float = 0.46f, val halfY: Float = 0.46f)

private val FRAME_LAYOUT = mapOf(
    "crown" to FrameLayout(turn = "out", lift = 0.2f, halfX = 0.44f, halfY = 0.36f),
    "cat_ears" to FrameLayout(pair = 33f, back = true, turn = "out", lift = 0.2f, halfX = 0.4f, halfY = 0.46f),
    "horns" to FrameLayout(pair = 37f, back = true, turn = "out", lift = 0.22f, halfX = 0.36f, halfY = 0.46f),
    "wings" to FrameLayout(pair = 90f, back = true, lift = 0.26f, halfX = 0.48f, halfY = 0.4f),
    "laurel" to FrameLayout(pair = 27f, turn = "hug", lift = -0.04f, halfX = 0.44f, halfY = 0.3f),
    "halo" to FrameLayout(turn = "out", lift = 0.24f, halfX = 0.46f, halfY = 0.2f),
    "bow" to FrameLayout(turn = "hug", lift = 0.02f, halfX = 0.46f, halfY = 0.34f),
)
private val DEFAULT_FRAME_LAYOUT = FrameLayout()

/** 把角度收到 (-180, 180] */
private fun normalize(degrees: Float): Float {
    val v = ((degrees % 360f) + 360f) % 360f
    return if (v > 180f) v - 360f else v
}

/** 中心先放在「自然」的半径上，再按 MAX_OVERHANG 往里收：上下、左右各自量它探出头像方块多远 */
private fun placePiece(entry: FrameOrnamentEntry, angle: Float, turn: Float, mirror: Boolean, natural: Float, layout: FrameLayout): FramePiece {
    val theta = Math.toRadians(angle.toDouble())
    val s = sin(theta).toFloat()
    val c = cos(theta).toFloat()
    val phi = Math.toRadians(turn.toDouble())
    val extentX = entry.size * (layout.halfX * abs(cos(phi)) + layout.halfY * abs(sin(phi))).toFloat()
    val extentY = entry.size * (layout.halfX * abs(sin(phi)) + layout.halfY * abs(cos(phi))).toFloat()
    var radius = natural
    if (abs(s) > 0.05f) radius = min(radius, (FRAME_RADIUS + MAX_OVERHANG - extentX) / abs(s))
    if (abs(c) > 0.05f) radius = min(radius, (FRAME_RADIUS + MAX_OVERHANG - extentY) / abs(c))
    radius = max(0f, radius)
    return FramePiece(entry.key, entry.color, entry.animated, layout.back, 0.5f + radius * s / 40f, 0.5f - radius * c / 40f, entry.size.toFloat(), turn, mirror)
}

/** 翅膀在环上的方位：position 只决定它长得高一点还是低一点，左右总是成对的 */
private fun wingAngle(position: String): Float = when (position) {
    "top", "tl", "tr" -> 64f
    "bottom", "bl", "br" -> 112f
    else -> 90f
}

private fun buildPieces(ornaments: List<FrameOrnamentEntry>, width: Float, gap: Float): List<FramePiece> {
    val outer = FRAME_RADIUS + gap + width
    val middle = FRAME_RADIUS + gap + width / 2f
    val pieces = mutableListOf<FramePiece>()
    for (entry in ornaments) {
        val layout = FRAME_LAYOUT[entry.key] ?: DEFAULT_FRAME_LAYOUT
        val natural = layout.lift?.let { outer + entry.size * it } ?: middle
        val base = POSITION_ANGLE.getValue(entry.position)
        if (entry.key == "wings") {
            val angle = wingAngle(entry.position)
            val tilt = (angle - 90f) * 0.5f
            pieces += placePiece(entry, angle, tilt, false, natural, layout)
            pieces += placePiece(entry, -angle, -tilt, true, natural, layout)
            continue
        }
        // out：顶上那半圈跟着方位转，底下那半圈保持正的（成对的那几种哪个方位都朝外）；hug：以正下方为正，贴着环转
        fun turnOf(angle: Float): Float = when (layout.turn) {
            "hug" -> {
                val fromTop = normalize(angle)
                if (entry.key == "bow" && abs(fromTop) <= 90f) fromTop else normalize(angle - 180f)
            }
            "out" -> {
                val fromTop = normalize(angle)
                if (layout.pair != null || abs(fromTop) <= 100f) fromTop else 0f
            }
            else -> 0f
        }
        val spread = layout.pair
        if (spread != null) {
            // 贴着环的那种（月桂）右边那枝在方位的逆时针一侧，朝外的那种（耳朵、角）右边那片在顺时针一侧
            val sign = if (layout.turn == "hug") -1f else 1f
            val right = base + sign * spread
            val left = base - sign * spread
            pieces += placePiece(entry, right, turnOf(right), false, natural, layout)
            pieces += placePiece(entry, left, turnOf(left), true, natural, layout)
            continue
        }
        pieces += placePiece(entry, base, turnOf(base), false, natural, layout)
    }
    return pieces
}

/** 没写外发光、但动效是 pulse：拿环上居中的那个颜色（60% 不透明）当光的颜色 */
private fun fallbackGlow(fill: SkinFill): Pair<Color, Float> = fill.middle.copy(alpha = 0x99 / 255f) to 8f

/** 头像框 style（docs/shop.md 2.4，规则见文件头）。不合法返回 null，按没装备处理 */
fun parseAvatarFrame(style: JsonElement?): AvatarFrame? {
    val obj = style as? JsonObject ?: return null
    if (obj["version"].number() != 1.0 || !obj.onlyKnown(FRAME_FIELDS)) return null
    val ring = obj["ring"] as? JsonObject ?: return null
    if (!ring.onlyKnown(RING_FIELDS)) return null
    val fill = parseFill(ring["background"].string(), allowConic = true) ?: return null
    val width = ring["width"].inRange(2.0, 6.0)?.let { round1(it, 0.5).toFloat() } ?: return null
    val gap = ring["gap"].inRange(0.0, 3.0)?.let { round1(it, 0.5).toFloat() } ?: 0f

    val glow = if (obj["glow"].absent()) Read.Ok(null) else readGlow(obj["glow"], 8f)
    val ornaments = if (obj["ornaments"].absent()) Read.Ok(emptyList()) else readFrameOrnaments(obj["ornaments"])
    if (glow is Read.UnknownField || ornaments is Read.UnknownField) return null
    val animation = when (obj["animation"].string()) {
        "spin" -> FrameAnimation.Spin
        "pulse" -> FrameAnimation.Pulse
        "shimmer" -> FrameAnimation.Shimmer
        else -> FrameAnimation.None
    }
    val pulse = animation == FrameAnimation.Pulse
    val lightGlow = glow.valueOrNull()
    val light = FramePaint(fill, lightGlow ?: if (pulse) fallbackGlow(fill) else null)

    var dark = light
    val override = obj["dark"] as? JsonObject
    if (override != null) {
        if (!override.onlyKnown(FRAME_DARK_FIELDS)) return null
        var valid = true
        var darkFill: SkinFill? = null
        val darkRing = override["ring"]
        if (!darkRing.absent()) {
            if (darkRing !is JsonObject) {
                valid = false
            } else if (!darkRing.onlyKnown(FRAME_DARK_RING_FIELDS)) {
                return null
            } else if (!darkRing["background"].absent()) {
                darkFill = parseFill(darkRing["background"].string(), allowConic = true)
                valid = darkFill != null
            }
        }
        val darkGlow = if (override["glow"].absent()) null else readGlow(override["glow"], lightGlow?.second ?: 8f)
        if (darkGlow is Read.UnknownField) return null
        if (valid && darkGlow !is Read.Invalid) {
            val nextFill = darkFill ?: light.fill
            // 没写就沿用浅色的光；写了但 blur 是 0 就是深色下不要光 —— pulse 例外，它得有光可呼吸
            val nextGlow = if (darkGlow == null) lightGlow ?: if (pulse) fallbackGlow(nextFill) else null
            else darkGlow.valueOrNull() ?: if (pulse) fallbackGlow(nextFill) else null
            dark = FramePaint(nextFill, nextGlow)
        }
    }
    return AvatarFrame(light, dark, width, gap, animation, buildPieces(ornaments.valueOrNull().orEmpty(), width, gap))
}

/** 头像框试穿：一个占位头像（首字母圆）套上框。装饰最多探出头像 11/40，四周留够 */
@Composable
fun FrameStage(style: JsonElement?, label: String, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val frame = remember(style) { parseAvatarFrame(style) }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.chat).padding(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp).let { if (frame != null) it.frameDecor(frame, rounded = false) else it }) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.fill), contentAlignment = Alignment.Center) {
                Text(label.take(1).ifBlank { "我" }, color = colors.onFill, fontSize = 20.sp)
            }
        }
    }
}

/** 这个版本画不画得出这件商品（style 过不了对应类别的校验就是画不出） */
fun canRender(item: ShopItem): Boolean = if (item.category == "frame") parseAvatarFrame(item.style) != null else parseBubbleSkin(item.style) != null
