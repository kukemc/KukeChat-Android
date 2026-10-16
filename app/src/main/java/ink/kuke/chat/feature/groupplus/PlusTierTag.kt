package ink.kuke.chat.feature.groupplus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 群升级的视觉令牌（同电脑版 styles/group-plus.css 的 --kgp-*）：克制的黑白。
 * 档位不靠颜色区分，靠层级 —— Pro 描边、Pro+ 浅灰底、MAX 墨色实底 + 小王冠。全页唯一的彩色是金币。
 * 墨色取主题的正文色：浅色主题下近黑，深色主题下近白；灰阶都是它的不同透明度。
 */
@Immutable
data class PlusInk(
    val ink: Color,
    val onInk: Color,
    val line: Color,
    val lineStrong: Color,
    val wash: Color,
    val wash2: Color,
    val sub: Color,
    val faint: Color,
    val warn: Color,
    val bad: Color,
    val dark: Boolean,
)

@Composable
fun plusInk(): PlusInk {
    val colors = KcTheme.colors
    val ink = colors.text
    val dark = colors.isDark
    return PlusInk(
        ink = ink,
        onInk = colors.panel,
        line = ink.copy(alpha = 0.1f),
        lineStrong = ink.copy(alpha = if (dark) 0.24f else 0.22f),
        wash = ink.copy(alpha = if (dark) 0.05f else 0.045f),
        wash2 = ink.copy(alpha = if (dark) 0.085f else 0.075f),
        sub = ink.copy(alpha = if (dark) 0.58f else 0.56f),
        faint = ink.copy(alpha = 0.4f),
        warn = colors.warn,
        bad = colors.danger,
        dark = dark,
    )
}

/** 王冠：16×16 的网格上画，小到 8dp 也认得出（五个尖、一条底座）。同电脑版 CrownGlyph */
val CrownGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "plusCrown", defaultWidth = 16.dp, defaultHeight = 16.dp, viewportWidth = 16f, viewportHeight = 16f)
        .addPath(pathData = PathParser().parsePathString("M2.2 5.1 5.4 7.6 8 3.2l2.6 4.4 3.2-2.5-1.1 6.2H3.3L2.2 5.1Z").toNodes(), fill = SolidColor(Color.Black))
        .addPath(pathData = PathParser().parsePathString("M4.2 11.9h7.6a0.8 0.8 0 0 1 0 1.6H4.2a0.8 0.8 0 0 1 0-1.6Z").toNodes(), fill = SolidColor(Color.Black))
        .build()
}

@Composable
fun CrownMark(tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Icon(CrownGlyph, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/**
 * 群名后面的档位小标签。free 不画。
 * 尺寸三档：compact 跟会话列表的名字，默认跟顶栏 / 搜索结果，large 跟群资料页的大标题、升级页。
 * 聊天顶栏、会话列表、群资料、搜索结果统一用它，和电脑版的 kgp-tag 一致。
 */
@Composable
fun PlusTierTag(tier: String?, modifier: Modifier = Modifier, compact: Boolean = false, large: Boolean = false) {
    if (tier.isNullOrBlank() || tier == "free") return
    val k = plusInk()
    val (bg, fg, stroke) = when (tier) {
        "max" -> Triple(k.ink, k.onInk, Color.Transparent)
        "pro_plus" -> Triple(k.wash2, k.ink, Color.Transparent)
        else -> Triple(Color.Transparent, k.sub, k.lineStrong)
    }
    val font = when {
        large -> 11.5.sp
        compact -> 9.5.sp
        else -> 10.sp
    }
    val shape = RoundedCornerShape(if (large) 6.dp else 5.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = if (large) 20.dp else if (compact) 15.dp else 16.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .padding(horizontal = if (large) 7.dp else if (compact) 4.5.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 3.dp else 2.dp),
    ) {
        if (tier == "max") CrownMark(fg, if (large) 11.dp else 9.dp)
        Text(
            PlusTiers.label(tier),
            color = fg,
            fontSize = font,
            lineHeight = font,
            fontWeight = FontWeight.W600,
            letterSpacing = 0.02.em,
            maxLines = 1,
        )
    }
}

/**
 * 群头像右下角的徽记：只有 MAX 有（同电脑版 PlusCornerBadge）—— 墨色小圆 + 王冠，外圈一道面板色描边。
 * Pro / Pro+ 只靠群名后的标签区分。
 */
@Composable
fun PlusAvatarBadge(tier: String, avatarSize: Dp, modifier: Modifier = Modifier) {
    if (tier != "max") return
    val k = plusInk()
    val ring = KcTheme.colors.panel
    val badge = (avatarSize.value * 0.32f).coerceIn(12f, 22f).dp
    Box(
        modifier
            .offset(2.dp, 2.dp)
            .size(badge + 4.dp)
            .background(ring, CircleShape)
            .padding(2.dp)
            .background(k.ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CrownMark(k.onInk, badge * 0.6f)
    }
}

/** 金币图标（后台与客户端用的是同一个「金币」概念；安卓没有现成矢量，画一个金色圆）。群升级页唯一的彩色 */
@Composable
fun CoinMark(size: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(Brush.linearGradient(listOf(Color(0xFFFFD45A), Color(0xFFF2A100))), CircleShape)
            .border(1.dp, Color(0xFFE08E00), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("币", color = Color(0xFF7A4A00), fontSize = (size.value * 0.58f).sp, lineHeight = (size.value * 0.7f).sp, fontWeight = FontWeight.W800)
    }
}
