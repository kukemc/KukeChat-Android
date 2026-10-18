package ink.kuke.chat.feature.spark

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.model.Spark
import ink.kuke.chat.ui.components.rememberReducedMotion
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 火花图标（docs/sparks.md）。界面里唯一的彩色点缀：按等级换颜色，越高越炫；烈焰 / 永恒之火带轻微呼吸。
 * 快熄灭的变暗，熄灭的画成灰色。全部用 Canvas 画，不引图片。
 */
internal class SparkPalette(val outer: List<Color>, val inner: List<Color>, val sparkles: Int, val animated: Boolean)

internal fun sparkPalette(key: String?): SparkPalette = when (key) {
    "spark" -> SparkPalette(listOf(Color(0xFFFFB238), Color(0xFFFF7A1A)), listOf(Color(0xFFFFF1B8), Color(0xFFFFC94D)), 0, false)
    "flame" -> SparkPalette(listOf(Color(0xFFFF9A2E), Color(0xFFFF4D2E)), listOf(Color(0xFFFFF4C2), Color(0xFFFFB02E)), 0, false)
    "blaze" -> SparkPalette(listOf(Color(0xFFFF6A3D), Color(0xFFE5254B)), listOf(Color(0xFFFFF6CC), Color(0xFFFF9F3D)), 1, false)
    "inferno" -> SparkPalette(listOf(Color(0xFFFFB020), Color(0xFFF0431F), Color(0xFFC0145A)), listOf(Color(0xFFFFFBE0), Color(0xFFFFD84D)), 2, true)
    "eternal" -> SparkPalette(listOf(Color(0xFFFFC857), Color(0xFFF2508E), Color(0xFF7C5CFF)), listOf(Color(0xFFFFFFFF), Color(0xFFB9E6FF)), 3, true)
    // ember 和兜底：小小的橘色火苗
    else -> SparkPalette(listOf(Color(0xFFFFC266), Color(0xFFFF8A3D)), listOf(Color(0xFFFFF0C9), Color(0xFFFFCB7A)), 0, false)
}

private fun flamePath(w: Float, h: Float, inset: Float = 0f): Path = Path().apply {
    // 24×24 的火焰轮廓：底部圆、顶部尖、右侧一个小分叉
    fun x(v: Float) = inset * w + v / 24f * w * (1 - 2 * inset)
    fun y(v: Float) = inset * 1.4f * h + v / 24f * h * (1 - 2 * inset * 1.2f)
    moveTo(x(12f), y(1.5f))
    cubicTo(x(13.2f), y(5.2f), x(17.5f), y(7.4f), x(18.6f), y(11.6f))
    cubicTo(x(19.1f), y(10.2f), x(19f), y(9.1f), x(18.6f), y(8.2f))
    cubicTo(x(20.6f), y(10.1f), x(21f), y(13.4f), x(20.2f), y(16f))
    cubicTo(x(19.1f), y(19.9f), x(15.8f), y(22.5f), x(12f), y(22.5f))
    cubicTo(x(7.6f), y(22.5f), x(4f), y(19.2f), x(4f), y(14.8f))
    cubicTo(x(4f), y(10.4f), x(7.8f), y(8.3f), x(9.2f), y(5.6f))
    cubicTo(x(9.6f), y(7.4f), x(10.4f), y(8.6f), x(11.3f), y(9.3f))
    cubicTo(x(11.2f), y(6.6f), x(11.4f), y(3.9f), x(12f), y(1.5f))
    close()
}

private fun DrawScope.drawFlame(palette: SparkPalette, alpha: Float, gray: Color?) {
    val w = size.width
    val h = size.height
    val outer = flamePath(w, h)
    if (gray != null) {
        drawPath(outer, gray, alpha = alpha)
        return
    }
    drawPath(outer, Brush.verticalGradient(palette.outer, startY = 0f, endY = h), alpha = alpha)
    val inner = flamePath(w, h, inset = 0.22f)
    drawPath(inner, Brush.verticalGradient(palette.inner, startY = h * 0.3f, endY = h), alpha = alpha * 0.95f)
    // 高等级：火焰旁几颗小火星
    val dots = listOf(Offset(0.86f, 0.18f), Offset(0.12f, 0.3f), Offset(0.9f, 0.5f))
    for (i in 0 until palette.sparkles) {
        val p = dots[i]
        drawCircle(palette.inner.first(), radius = w * (0.065f - i * 0.01f), center = Offset(p.x * w, p.y * h), alpha = alpha * 0.9f)
    }
}

/** 只画图形。dim：快熄灭（变暗）；out：熄灭（灰色） */
@Composable
fun SparkGlyph(levelKey: String?, size: Dp = 16.dp, modifier: Modifier = Modifier, dim: Boolean = false, out: Boolean = false) {
    val palette = sparkPalette(levelKey)
    val gray = if (out) KcTheme.colors.subtle else null
    val animate = palette.animated && !dim && !out && !rememberReducedMotion()
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "spark")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "spark-pulse").value
    } else 0f
    val alpha = when {
        out -> 0.9f
        dim -> 0.42f
        else -> 1f
    }
    Canvas(modifier.size(size)) {
        val s = 1f + pulse * 0.07f
        scale(s, s, pivot = Offset(this.size.width / 2f, this.size.height)) {
            drawFlame(palette, alpha * (1f - pulse * 0.08f), gray)
        }
    }
}

/**
 * 名字旁的火花：图标 + 天数。没有可画的（spark 为空、没天数也没得找回）什么都不画。
 * 熄灭了只画灰色图标（不写数字）；快熄灭的图标变暗。onClick 不为空时可点。
 */
@Composable
fun SparkMark(spark: Spark?, modifier: Modifier = Modifier, size: Dp = 14.dp, onClick: (() -> Unit)? = null) {
    if (spark == null || !spark.visible) return
    val colors = KcTheme.colors
    val out = spark.isOut
    val clickable = if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp) else Modifier
    Row(modifier.then(clickable), verticalAlignment = Alignment.CenterVertically) {
        SparkGlyph(if (out) spark.lostLevelKey else spark.levelKey, size = size, dim = spark.isDying, out = out)
        if (!out && spark.days > 0) {
            Text(
                spark.days.toString(),
                fontSize = (size.value * 0.82f).sp,
                fontWeight = FontWeight.W600,
                color = if (spark.isDying) colors.subtle else colors.muted,
                modifier = Modifier.padding(start = 2.dp),
                maxLines = 1,
            )
        }
    }
}
