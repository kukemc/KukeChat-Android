package ink.kuke.chat.feature.gifts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.feature.shop.parseAvatarFrame
import ink.kuke.chat.feature.shop.parseBubbleSkin
import ink.kuke.chat.feature.shop.toBrush
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

/** 礼物自己的玫红（和客户端 --kgf 一致），深色下提亮一档；金色是第二点缀 */
object GiftColors {
    val rose @Composable get() = if (KcTheme.colors.isDark) Color(0xFFFF7DB8) else Color(0xFFD9468F)
    val roseHi @Composable get() = if (KcTheme.colors.isDark) Color(0xFFFF93C4) else Color(0xFFF0609F)
    val roseLo @Composable get() = if (KcTheme.colors.isDark) Color(0xFFE7609F) else Color(0xFFC2337C)
    val roseSoft @Composable get() = rose.copy(alpha = if (KcTheme.colors.isDark) 0.16f else 0.12f)
    val gold @Composable get() = if (KcTheme.colors.isDark) Color(0xFFFFC35C) else Color(0xFFE59A1F)
}

/** 头像框小样套在谁的头像上；不给就用一个「礼」字占位头像 */
data class Wearer(val id: Long, val name: String, val avatarUrl: String?)

/**
 * 「送的那样东西」长什么样（礼物卡片、礼物墙、送礼面板、记录、提示共用）：
 *   · 目录里的礼物：内置矢量图形；
 *   · 头像框：套在一个头像上的小样；
 *   · 气泡：一条穿着这件皮肤的小消息。
 * 商品的 style 不可信，校验过不了就画成「没有装扮」的样子；不认识的类别给一个衣服图标。size 是图形的边长。
 */
@Composable
fun GiftThing(gift: GiftRef?, item: GiftItemRef?, size: Dp, modifier: Modifier = Modifier, wearer: Wearer? = null, sampleText: String = "你好呀") {
    if (item == null) {
        GiftGlyph(gift?.icon.orEmpty(), size, modifier)
        return
    }
    when (item.category) {
        "frame" -> Box(modifier.size(size), contentAlignment = Alignment.Center) { FramedAvatar(item.style, wearer, size * 0.74f) }
        "bubble" -> Box(modifier.widthIn(max = size * 2.2f), contentAlignment = Alignment.Center) { MiniBubble(item.style, sampleText, size) }
        else -> Box(modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(GiftColors.roseSoft), contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.shirt, tint = GiftColors.rose, size = size * 0.5f)
        }
    }
}

/** 套了头像框的头像。框的尺寸按 40dp 头像定，这里按比例放大；有光晕时在外圈晕一层 */
@Composable
fun FramedAvatar(style: kotlinx.serialization.json.JsonElement?, wearer: Wearer?, avatarSize: Dp, modifier: Modifier = Modifier) {
    val frame = remember(style) { parseAvatarFrame(style) }
    val k = avatarSize.value / 40f
    val ring = ((frame?.width ?: 0f) * k).dp
    val gap = ((frame?.gap ?: 0f) * k).dp
    val brush = remember(frame) { frame?.ring?.toBrush() }
    val glow = frame?.glow
    Box(
        modifier
            .size(avatarSize + (ring + gap) * 2)
            .drawBehind {
                if (glow != null) {
                    val r = size.minDimension / 2
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f * glow.alpha), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = r * 1.32f), radius = r * 1.32f)
                }
            }
            .let { if (brush != null) it.border(ring, brush, CircleShape) else it }
            .padding(ring + gap),
        contentAlignment = Alignment.Center,
    ) {
        if (wearer != null) {
            Avatar(wearer.avatarUrl, wearer.name, wearer.id, avatarSize)
        } else {
            Box(Modifier.size(avatarSize).clip(CircleShape).background(Color(0xFFF06595)), contentAlignment = Alignment.Center) {
                Text("礼", color = Color.White, fontSize = (avatarSize.value * 0.4f).sp)
            }
        }
    }
}

/** 一条穿着这件气泡皮肤的小消息。字号跟着 size 走，礼物墙那种小格子里也是完整的一条气泡 */
@Composable
fun MiniBubble(style: kotlinx.serialization.json.JsonElement?, text: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val skin = remember(style) { parseBubbleSkin(style) }
    val paint = skin?.paint(colors.isDark)
    val brush = remember(paint) { paint?.fill?.toBrush() }
    val scale = (size.value / 64f).coerceIn(0.55f, 1.3f)
    val shape = RoundedCornerShape(((skin?.radius ?: 14) * scale).dp)
    var m = modifier.clip(shape)
    m = if (brush != null) m.background(brush) else m.background(colors.bubbleOut)
    paint?.border?.let { (color, width) -> m = m.border((width * scale).coerceAtLeast(0.5f).dp, color, shape) }
    Box(m.padding(horizontal = (11 * scale).dp, vertical = (7 * scale).dp)) {
        Text(text, color = paint?.text ?: colors.onBubbleOut, fontSize = (13.5f * scale).sp, lineHeight = (18f * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
