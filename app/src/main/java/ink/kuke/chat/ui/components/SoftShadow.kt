package ink.kuke.chat.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 可以随意淡入淡出、缩放的柔和阴影。
 * 系统的 Modifier.shadow 是按轮廓裁掉内部的：背景半透明或整层有透明度时，会露出一个直角的「洞」。
 * 这里用几层逐渐扩大、逐渐变淡的圆角矩形叠出模糊边，没有这个问题。
 */
fun Modifier.softShadow(blur: Dp, offsetY: Dp, color: Color, corner: Dp): Modifier = drawBehind {
    if (color.alpha <= 0.001f) return@drawBehind
    val layers = 8
    val spread = blur.toPx()
    val dy = offsetY.toPx()
    for (i in layers downTo 1) {
        val t = i / layers.toFloat()
        val grow = spread * t
        val a = color.alpha * (1f - t) * (1f - t) * 0.55f + color.alpha * 0.045f
        drawRoundRect(
            color = color.copy(alpha = a),
            topLeft = Offset(-grow, dy - grow),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(corner.toPx() + grow),
        )
    }
}
