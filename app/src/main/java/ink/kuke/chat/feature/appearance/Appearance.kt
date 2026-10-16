package ink.kuke.chat.feature.appearance

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ink.kuke.chat.ui.theme.FontScales
import ink.kuke.chat.ui.theme.KcColors
import ink.kuke.chat.ui.theme.darkKcColors
import ink.kuke.chat.ui.theme.lightKcColors

/*
 * 外观设置（设计稿 P29，对应电脑版 src/theme/themes.ts 的 ThemeSettings 的手机子集）。
 * 背景图与材质（半透明 / 玻璃）手机版暂不做，见 REPORT。
 */

enum class DarkMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }
enum class BubbleKind(val label: String) { Solid("纯色"), Soft("柔和"), Custom("自选") }
enum class Density(val label: String) { Cozy("标准"), Compact("紧凑") }
enum class AvatarShape(val label: String) { Circle("圆形"), Rounded("圆角方形") }

/** 主题预设（同电脑版 THEME_PRESETS）：accentLight 是饱满的实底色，accentDark 是深色下给文字、图标用的提亮版 */
@Immutable
data class ThemePreset(
    val id: String,
    val name: String,
    val tagline: String,
    val accentLight: Color,
    val accentDark: Color,
    /** 界面底色里掺入多少强调色（百分比） */
    val tint: Int,
    val bubble: BubbleKind,
)

private fun hex(value: Long) = Color(0xFF000000 or value)

val ThemePresets: List<ThemePreset> = listOf(
    ThemePreset("classic", "经典", "冷调灰配一抹蓝", hex(0x1F6BFF), hex(0x5B93FF), 0, BubbleKind.Solid),
    ThemePreset("sky", "晴空", "纯色气泡，干净利落", hex(0x0099FF), hex(0x2AA7FF), 3, BubbleKind.Solid),
    ThemePreset("mint", "薄荷", "清爽的绿", hex(0x12B886), hex(0x34D3A0), 5, BubbleKind.Soft),
    ThemePreset("sakura", "樱花", "柔和的粉", hex(0xF06595), hex(0xFF8FB3), 6, BubbleKind.Soft),
    ThemePreset("sunset", "落日", "暖橙色调", hex(0xF76707), hex(0xFF922B), 5, BubbleKind.Solid),
    ThemePreset("lavender", "薰衣草", "安静的紫", hex(0x7950F2), hex(0x9775FA), 6, BubbleKind.Soft),
    ThemePreset("ocean", "深海", "青蓝色调", hex(0x0C8599), hex(0x3BC9DB), 7, BubbleKind.Solid),
    ThemePreset("graphite", "石墨", "极简黑白灰", hex(0x495057), hex(0xADB5BD), 0, BubbleKind.Solid),
    ThemePreset("pine", "松林", "沉稳的墨绿", hex(0x2B8A3E), hex(0x51CF66), 5, BubbleKind.Solid),
    ThemePreset("amber", "琥珀", "明亮的暖黄", hex(0xF59F00), hex(0xFFC94D), 4, BubbleKind.Soft),
    ThemePreset("draft", "草稿纸", "适合聊代码", hex(0x3B5BDB), hex(0x748FFC), 2, BubbleKind.Solid),
    ThemePreset("harbor", "雾港", "清冷的蓝", hex(0x1C7ED6), hex(0x4DABF7), 4, BubbleKind.Solid),
    ThemePreset("nebula", "星云", "深邃的紫", hex(0x7048E8), hex(0x9F8CFF), 6, BubbleKind.Soft),
    ThemePreset("ember", "余烬", "暖色调", hex(0xE8590C), hex(0xFF922B), 5, BubbleKind.Solid),
)

/** 强调色 12 色（同电脑版 ACCENT_SWATCHES） */
val AccentSwatches: List<Color> = listOf(
    0x1F6BFFL, 0x0099FFL, 0x12B886L, 0x40C057L, 0xF59F00L, 0xF76707L,
    0xFA5252L, 0xF06595L, 0xBE4BDBL, 0x7950F2L, 0x0C8599L, 0x495057L,
).map { hex(it) }

/** 「自选」气泡色的可选颜色 */
val BubbleSwatches: List<Color> = listOf(
    0x1F6BFFL, 0x12B886L, 0xF06595L, 0xF76707L, 0x7950F2L, 0x0C8599L, 0x2B8A3EL, 0xF59F00L, 0x495057L, 0x222B45L,
).map { hex(it) }

const val RadiusMin = 4
const val RadiusMax = 20
const val RadiusDefault = 14

/**
 * 用户的外观选择。null 表示「跟随预设」。DataStore 里一项一个键（见 AppearanceStore）。
 */
@Immutable
data class Appearance(
    val mode: DarkMode = DarkMode.System,
    val presetId: String = "classic",
    /** 自定义强调色（ARGB）；null 跟随预设 */
    val accent: Long? = null,
    /** 我的气泡样式；null 跟随预设 */
    val bubbleKind: BubbleKind? = null,
    /** bubbleKind 为 Custom 时的颜色 */
    val bubbleColor: Long? = null,
    val bubbleRadius: Int = RadiusDefault,
    val density: Density = Density.Cozy,
    val avatarShape: AvatarShape = AvatarShape.Circle,
    /** FontScales 的下标，0..4，默认 1（标准） */
    val fontIndex: Int = 1,
) {
    val preset: ThemePreset get() = ThemePresets.firstOrNull { it.id == presetId } ?: ThemePresets.first()
    val fontScale: Float get() = FontScales[fontIndex.coerceIn(0, FontScales.lastIndex)]
    val effectiveBubble: BubbleKind get() = bubbleKind ?: preset.bubble
    val isDefault: Boolean get() = this == Appearance()

    /** 外观页行尾的一句话摘要：「经典 · 跟随系统」 */
    val summary: String get() = "${preset.name} · ${mode.label}"

    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        DarkMode.System -> systemDark
        DarkMode.Light -> false
        DarkMode.Dark -> true
    }

    /** 实底用的强调色（主按钮、选中、自己的纯色气泡） */
    val accentFill: Color get() = accent?.let { Color(it.toInt()) } ?: preset.accentLight

    /** 文字、图标用的强调色：深色下提亮 */
    fun accentText(dark: Boolean): Color = when {
        accent != null -> if (dark) lerp(accentFill, Color.White, 0.25f) else accentFill
        dark -> preset.accentDark
        else -> preset.accentLight
    }
}

/** 映射给主题用的结果：colors 是完整的一套 KcColors，其余是主题之外的外观量 */
@Immutable
data class ResolvedTheme(
    val dark: Boolean,
    val colors: KcColors,
    /** 强调色实底（= colors.fill），单列出来给 KukeTheme(accent = …) */
    val accent: Color,
    val fontScale: Float,
    /** 自己的气泡上的文字颜色（柔和 / 浅色自选气泡上要用深色字） */
    val bubbleOutText: Color,
    val bubbleRadius: Dp,
    val compact: Boolean,
    val roundedAvatars: Boolean,
)

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun Color.over(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha),
    green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha),
    alpha = 1f,
)

/**
 * 把外观算成一整套颜色。
 * 层次靠明度，所以「主题色调」只是把强调色按预设的 tint 百分比掺进 rail / list / chat / panelMuted，面之间的明度关系不变。
 */
fun Appearance.resolve(systemDark: Boolean): ResolvedTheme {
    val dark = isDark(systemDark)
    val fill = accentFill
    var colors = if (dark) darkKcColors(fill) else lightKcColors(fill)
    val textAccent = accentText(dark)
    val tint = preset.tint / 100f
    fun mix(base: Color) = if (tint > 0f) lerp(base, fill, if (dark) tint * 0.45f else tint * 0.7f) else base
    colors = colors.copy(
        rail = mix(colors.rail),
        list = mix(colors.list),
        chat = mix(colors.chat),
        panelMuted = mix(colors.panelMuted),
        accent = textAccent,
        accentSoft = fill.copy(alpha = if (dark) 0.2f else 0.14f).over(colors.panel),
    )
    var outText = colors.onFill
    when (effectiveBubble) {
        BubbleKind.Solid -> colors = colors.copy(bubbleOut = fill)
        BubbleKind.Soft -> {
            val soft = fill.copy(alpha = if (dark) 0.34f else 0.2f).over(colors.panel)
            colors = colors.copy(bubbleOut = soft)
            outText = colors.text
        }
        BubbleKind.Custom -> {
            val custom = bubbleColor?.let { Color(it.toInt()) } ?: fill
            colors = colors.copy(bubbleOut = custom)
            outText = if (custom.luminance() > 0.62f) Color(0xFF12141A) else Color.White
        }
    }
    colors = colors.copy(onBubbleOut = outText)
    return ResolvedTheme(
        dark = dark,
        colors = colors,
        accent = fill,
        fontScale = fontScale,
        bubbleOutText = outText,
        bubbleRadius = bubbleRadius.coerceIn(RadiusMin, RadiusMax).dp,
        compact = density == Density.Compact,
        roundedAvatars = avatarShape == AvatarShape.Rounded,
    )
}

/**
 * 给外壳用的简化映射：KukeTheme(dark, accent, fontScale) 现有的三个参数。
 * 想把主题色调（tint）、柔和气泡、气泡圆角也接上，需要 KukeTheme 多收一个 colors，见 NEEDS.md。
 */
@Immutable
data class ThemeInputs(val dark: Boolean, val accent: Color, val fontScale: Float)

fun Appearance.toThemeInputs(systemDark: Boolean): ThemeInputs = ThemeInputs(isDark(systemDark), accentFill, fontScale)

/** 主题之外、聊天等界面要读的外观量（气泡圆角、密度、头像形状） */
val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** Color 转成能存进 DataStore 的 Long（ARGB） */
fun Color.toStoredLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

