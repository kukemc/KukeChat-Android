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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * 商品样式（气泡皮肤 docs/shop.md 2.2、头像框 2.4）的 Android 画法。
 * style 来自网络，哪怕服务端校验过这里也当它不可信：只读契约里的字段，颜色只认 #rgb / #rrggbb / #rrggbbaa，
 * 背景只认一个颜色或 linear / radial / conic 渐变；任何一处不合法，整件当「画不出」处理（和客户端 parseBubbleSkin 一致）。
 *
 * 已支持：纯色 / 线性与径向渐变 / 文字色 / 边框 / 圆角 / 深色覆盖。
 * 降级：纹样 pattern、角饰 ornaments、动画 animation、阴影 shadow 暂不画（皮肤仍然可辨认，底色和字色是对的）。
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

private class CssRadialBrush(private val stops: List<Pair<Float, Color>>) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        RadialGradientShader(Offset(size.width / 2f, size.height / 2f), max(size.width, size.height) / 2f, stops.map { it.second }, stops.map { it.first })
}

fun SkinFill.toBrush(): Brush = when (this) {
    is SkinFill.Solid -> SolidColor(color)
    is SkinFill.Linear -> CssLinearBrush(angle, stops)
    is SkinFill.Radial -> CssRadialBrush(stops)
    // 圆环用扫描渐变；色标位置按 0..1 绕一圈
    is SkinFill.Conic -> Brush.sweepGradient(*stops.map { it.first to it.second }.toTypedArray())
}

// ------------------------------------------------------------------ 气泡皮肤

class SkinPaint(val fill: SkinFill, val text: Color, val link: Color, val border: Pair<Color, Float>?)

class BubbleSkin(val light: SkinPaint, val dark: SkinPaint, val radius: Int?) {
    fun paint(isDark: Boolean) = if (isDark) dark else light
}

private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun readBorder(element: JsonElement?): Pair<Color, Float>? {
    val obj = element as? JsonObject ?: return null
    val color = parseCssColor(obj.str("color")) ?: return null
    val width = obj["width"]?.jsonPrimitive?.doubleOrNull ?: return null
    if (width < 0 || width > 3) return null
    return if (width == 0.0) null else color to width.toFloat()
}

/** 校验并解析气泡皮肤的 style。不认识的 version、背景和文字色缺一个 → null（按没装备处理） */
fun parseBubbleSkin(style: JsonElement?): BubbleSkin? {
    val obj = style as? JsonObject ?: return null
    if ((obj["version"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull != 1) return null
    val fill = parseFill(obj.str("background")) ?: return null
    val text = parseCssColor(obj.str("text")) ?: return null
    val link = parseCssColor(obj.str("link")) ?: text
    val border = readBorder(obj["border"])
    val radius = obj["radius"]?.jsonPrimitive?.intOrNull?.takeIf { it in 4..22 }
    val light = SkinPaint(fill, text, link, border)
    val dark = (obj["dark"] as? JsonObject)?.let { d ->
        // dark 里有任何一项不合法 → 整个深色覆盖作废，沿用浅色（避免「新底色配旧字色」）
        val darkFill = if (d.containsKey("background")) parseFill(d.str("background")) ?: return@let null else fill
        val darkText = if (d.containsKey("text")) parseCssColor(d.str("text")) ?: return@let null else text
        val darkLink = if (d.containsKey("link")) parseCssColor(d.str("link")) ?: return@let null else link
        val darkBorder = if (d.containsKey("border")) readBorder(d["border"]) else border
        SkinPaint(darkFill, darkText, darkLink, darkBorder)
    } ?: light
    return BubbleSkin(light, dark, radius)
}

/** 一条用皮肤画的气泡 */
@Composable
fun SkinBubble(skin: BubbleSkin?, text: String, modifier: Modifier = Modifier, fallbackRadius: Int = 14) {
    val colors = KcTheme.colors
    val radius = (skin?.radius ?: fallbackRadius).dp
    val shape = RoundedCornerShape(radius)
    val paint = skin?.paint(colors.isDark)
    val brush = remember(paint) { paint?.fill?.toBrush() }
    var m = modifier.clip(shape)
    m = if (brush != null) m.background(brush) else m.background(colors.bubbleOut)
    paint?.border?.let { (color, width) -> m = m.border(width.dp, color, shape) }
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
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.chat).padding(10.dp),
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

/**
 * 头像框。宽度、留白按 40dp 头像计；dark* 是深色覆盖（没有就沿用浅色）。
 * spin = animation 为 spin（只对圆环渐变有意义）；其余动效、角饰暂不画。
 */
class AvatarFrame(
    val ring: SkinFill,
    val width: Float,
    val gap: Float,
    val glow: Color?,
    val glowBlur: Float = 8f,
    val darkRing: SkinFill? = null,
    val darkGlow: Color? = null,
    val spin: Boolean = false,
) {
    fun ringFor(dark: Boolean): SkinFill = if (dark) darkRing ?: ring else ring
    fun glowFor(dark: Boolean): Color? = if (dark && darkRing != null) darkGlow else glow
}

private fun readFrameGlow(element: JsonElement?): Pair<Color, Float>? {
    val obj = element as? JsonObject ?: return null
    val color = parseCssColor(obj.str("color")) ?: return null
    val blur = obj["blur"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull()?.toFloat() ?: return null } ?: 8f
    if (blur !in 0f..16f || blur == 0f) return null
    return color to blur
}

/** 头像框 style（docs/shop.md 2.4）：ring.background / width / gap，glow.color / blur，animation，dark。尺寸按 40dp 头像计 */
fun parseAvatarFrame(style: JsonElement?): AvatarFrame? {
    val obj = style as? JsonObject ?: return null
    if ((obj["version"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull != 1) return null
    val ring = obj["ring"] as? JsonObject ?: return null
    val fill = parseFill(ring.str("background"), allowConic = true) ?: return null
    val width = runCatching { ring["width"]?.jsonPrimitive?.doubleOrNull }.getOrNull()?.toFloat()?.takeIf { it in 2f..6f } ?: return null
    val gap = runCatching { ring["gap"]?.jsonPrimitive?.doubleOrNull }.getOrNull()?.toFloat()?.coerceIn(0f, 3f) ?: 0f
    val glow = readFrameGlow(obj["glow"])
    // 深色覆盖：任何一项不合法 → 整个作废，沿用浅色
    val dark = obj["dark"] as? JsonObject
    var darkRing: SkinFill? = null
    var darkGlow: Color? = glow?.first
    if (dark != null) {
        val ringBg = (dark["ring"] as? JsonObject)?.str("background")
        val parsedRing = ringBg?.let { parseFill(it, allowConic = true) }
        val glowOk = !dark.containsKey("glow") || readFrameGlow(dark["glow"]) != null
        if ((ringBg == null || parsedRing != null) && glowOk) {
            darkRing = parsedRing ?: fill
            if (dark.containsKey("glow")) darkGlow = readFrameGlow(dark["glow"])?.first
        }
    }
    val spin = obj.str("animation") == "spin"
    return AvatarFrame(fill, width, gap, glow?.first, glow?.second ?: 8f, darkRing, darkGlow, spin)
}

/**
 * 在头像外面画环和外发光，不占布局（画在自身边界之外，父布局别裁切）。
 * [rounded] 跟随外观里的「圆角方形头像」；[spinDegrees] 是转动中的圆环渐变角度。
 */
fun Modifier.avatarFrame(frame: AvatarFrame, brush: Brush, glow: Color?, rounded: Boolean, spinDegrees: () -> Float = { 0f }): Modifier = drawBehind {
    val u = size.minDimension / 40f
    val gap = frame.gap * u
    val stroke = frame.width * u
    val inset = gap + stroke / 2f
    val c = center
    if (glow != null) {
        val outer = size.minDimension / 2f + gap + stroke
        val reach = outer + frame.glowBlur * u
        drawCircle(
            Brush.radialGradient(
                0f to glow.copy(alpha = glow.alpha * 0.5f),
                (outer / reach) to glow.copy(alpha = glow.alpha * 0.4f),
                1f to Color.Transparent,
                center = c, radius = reach,
            ),
            radius = reach, center = c,
        )
    }
    if (rounded) {
        val corner = size.minDimension * 0.28f + inset
        drawRoundRect(
            brush,
            topLeft = Offset(-inset, -inset),
            size = Size(size.width + inset * 2, size.height + inset * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
    } else {
        rotate(spinDegrees(), c) {
            drawCircle(brush, radius = size.minDimension / 2f + inset, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
    }
}

/** 头像框试穿：一个占位头像（首字母圆）套上环 */
@Composable
fun FrameStage(style: JsonElement?, label: String, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val frame = remember(style) { parseAvatarFrame(style) }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.chat).padding(14.dp), contentAlignment = Alignment.Center) {
        val ringWidth = (frame?.width ?: 0f) * 1.6f
        Box(
            Modifier
                .size(64.dp)
                .let { m -> if (frame != null) m.border(ringWidth.dp, frame.ring.toBrush(), CircleShape) else m }
                .padding(((frame?.gap ?: 0f) * 1.6f + ringWidth).dp.coerceAtLeast(0.dp))
                .clip(CircleShape)
                .background(colors.fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.take(1).ifBlank { "我" }, color = colors.onFill, fontSize = 20.sp)
        }
    }
}

/** 这个版本画不画得出这件商品（style 过不了对应类别的校验就是画不出） */
fun canRender(item: ShopItem): Boolean = if (item.category == "frame") parseAvatarFrame(item.style) != null else parseBubbleSkin(item.style) != null

private fun androidx.compose.ui.unit.Dp.coerceAtLeast(min: androidx.compose.ui.unit.Dp) = if (this < min) min else this
