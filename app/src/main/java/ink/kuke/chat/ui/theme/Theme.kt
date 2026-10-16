package ink.kuke.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * KukeChat 的主题（设计稿第 02、11 节）。
 *
 * Material 3 的 ColorScheme 只承载基础色；KukeChat 自己的层次色（rail / list / chat / panel）、气泡、未读等放在 KcColors 里，
 * 用 KcTheme.colors 取。层次靠明度：rail 最深 → list → chat → panel（白色的面），面与面之间压一条发丝线（border）。
 * 强调色只给主按钮、选中、进度和自己的气泡；未读永远红底白字，不跟主题。
 */
@Immutable
data class KcColors(
    val rail: Color,
    val list: Color,
    val chat: Color,
    val panel: Color,
    val panelMuted: Color,
    val raised: Color,
    val fill: Color,
    val onFill: Color,
    /** 文字里用的强调色（深色下提亮） */
    val accent: Color,
    val accentSoft: Color,
    val text: Color,
    val muted: Color,
    val subtle: Color,
    val border: Color,
    val borderStrong: Color,
    val active: Color,
    val bubbleIn: Color,
    val bubbleOut: Color,
    val unread: Color,
    val danger: Color,
    val ok: Color,
    val warn: Color,
    val star: Color,
    val scrim: Color,
    val isDark: Boolean,
    /** 自己的气泡上的文字色（柔和 / 浅色自选气泡要用深色字，外观解析时改写） */
    val onBubbleOut: Color = Color.White,
)

private val Accent = Color(0xFF1F6BFF)

fun lightKcColors(fill: Color = Accent) = KcColors(
    rail = Color(0xFFE2E4EB),
    list = Color(0xFFECEEF3),
    chat = Color(0xFFF5F6F9),
    panel = Color(0xFFFFFFFF),
    panelMuted = Color(0xFFF0F1F5),
    raised = Color(0xFFFFFFFF),
    fill = fill,
    onFill = Color.White,
    accent = fill,
    accentSoft = fill.copy(alpha = 0.14f).compositeOver(Color.White),
    text = Color(0xFF12141A),
    muted = Color(0xFF676D7C),
    subtle = Color(0xFFA0A6B3),
    border = Color(0x14121626),
    borderStrong = Color(0x26121626),
    active = Color(0x17121626),
    bubbleIn = Color(0xFFFFFFFF),
    bubbleOut = fill,
    unread = Color(0xFFEC3D44),
    danger = Color(0xFFE0383E),
    ok = Color(0xFF0D8F69),
    warn = Color(0xFFB06A00),
    star = Color(0xFFE9A112),
    scrim = Color(0x660E1018),
    isDark = false,
)

fun darkKcColors(fill: Color = Accent) = KcColors(
    rail = Color(0xFF090A0C),
    list = Color(0xFF15161B),
    chat = Color(0xFF0E0F12),
    panel = Color(0xFF1B1D23),
    panelMuted = Color(0xFF212329),
    raised = Color(0xFF23252B),
    fill = fill,
    onFill = Color.White,
    accent = if (fill == Accent) Color(0xFF5B93FF) else fill,
    accentSoft = fill.copy(alpha = 0.14f).compositeOver(Color(0xFF1B1D23)),
    text = Color(0xFFE1E4EA),
    muted = Color(0xFF8D93A2),
    subtle = Color(0xFF636979),
    border = Color(0x12FFFFFF),
    borderStrong = Color(0x24FFFFFF),
    active = Color(0x1AFFFFFF),
    bubbleIn = Color(0xFF22242A),
    bubbleOut = fill,
    unread = Color(0xFFF04A50),
    danger = Color(0xFFFF6369),
    ok = Color(0xFF3ECF9F),
    warn = Color(0xFFF0B03F),
    star = Color(0xFFE9A112),
    scrim = Color(0x99000000),
    isDark = true,
)

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

/** 设计稿的字号（sp），全部乘以外观里的字体大小倍数 */
@Immutable
data class KcType(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val rowTitle: TextStyle,
    val secondary: TextStyle,
    val caption: TextStyle,
    val group: TextStyle,
    val number: TextStyle,
)

/** 外观 › 字体大小 5 档，和电脑版 --kc-font-multiplier 一致 */
val FontScales = listOf(0.88f, 1f, 1.08f, 1.16f, 1.26f)

fun kcType(scale: Float): KcType {
    fun s(value: Float): TextUnit = (value * scale).sp
    return KcType(
        display = TextStyle(fontSize = s(26f), fontWeight = FontWeight.W800),
        title = TextStyle(fontSize = s(17f), fontWeight = FontWeight.W600),
        body = TextStyle(fontSize = s(15f), fontWeight = FontWeight.W400, lineHeight = s(21f)),
        rowTitle = TextStyle(fontSize = s(15.5f), fontWeight = FontWeight.W500),
        secondary = TextStyle(fontSize = s(13.5f), fontWeight = FontWeight.W400),
        caption = TextStyle(fontSize = s(12f), fontWeight = FontWeight.W400),
        group = TextStyle(fontSize = s(12f), fontWeight = FontWeight.W700, letterSpacing = 0.09.em),
        number = TextStyle(fontSize = s(22f), fontWeight = FontWeight.W700),
    )
}

/** 圆角与尺寸（dp），设计稿「尺寸令牌」 */
object KcDimens {
    val radiusXs = 6.dp
    val radiusSm = 8.dp
    val radiusMd = 10.dp
    val radiusLg = 12.dp
    val radiusXl = 18.dp
    val radiusSheet = 20.dp
    val bubbleRadius = 14.dp
    val bubbleTail = 4.dp
    val pagePadding = 16.dp
    val cardMargin = 12.dp
    val topBar = 56.dp
    val bottomBar = 64.dp
    val rail = 76.dp
    val conversationRow = 72.dp
    val settingsRow = 52.dp
    val composerMin = 52.dp
}

private val LocalKcColors = staticCompositionLocalOf { lightKcColors() }
private val LocalKcType = staticCompositionLocalOf { kcType(1f) }

object KcTheme {
    val colors: KcColors
        @Composable @ReadOnlyComposable get() = LocalKcColors.current
    val type: KcType
        @Composable @ReadOnlyComposable get() = LocalKcType.current
}

@Composable
fun KukeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accent: Color = Accent,
    fontScale: Float = 1f,
    /** 外观引擎算好的整套颜色；给了就不再按 dark / accent 生成 */
    resolved: KcColors? = null,
    content: @Composable () -> Unit,
) {
    val colors = resolved ?: if (dark) darkKcColors(accent) else lightKcColors(accent)
    val scheme = if (dark) {
        darkColorScheme(primary = colors.fill, onPrimary = colors.onFill, surface = colors.panel, background = colors.list, error = colors.danger)
    } else {
        lightColorScheme(primary = colors.fill, onPrimary = colors.onFill, surface = colors.panel, background = colors.list, error = colors.danger)
    }
    CompositionLocalProvider(LocalKcColors provides colors, LocalKcType provides kcType(fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
