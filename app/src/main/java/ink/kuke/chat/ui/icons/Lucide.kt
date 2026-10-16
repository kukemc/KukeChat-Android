package ink.kuke.chat.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon as M3Icon

/**
 * lucide 线稿：24×24 画板、线宽 1.9、圆头圆角（同电脑版 Icon.tsx 的 strokeWidth={1.9}）。
 * 描边画成黑色，用的时候由 Icon 的 tint 染色。
 */
internal fun lucide(name: String, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (d in paths) {
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

/** 双色选中态（设计稿：描边 + 18% 同色填充）：同一套路径，多一层半透明填充 */
private val duotoneCache = mutableMapOf<ImageVector, ImageVector>()

fun ImageVector.duotone(): ImageVector = duotoneCache.getOrPut(this) {
    val source = this
    val builder = ImageVector.Builder(name = "${source.name}-duotone", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    val root = source.root
    for (index in 0 until root.size) {
        val node = root[index] as? androidx.compose.ui.graphics.vector.VectorPath ?: continue
        builder.addPath(pathData = node.pathData, fill = SolidColor(Color.Black.copy(alpha = 0.18f)))
        builder.addPath(
            pathData = node.pathData,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    builder.build()
}

/** 实心版（填充 100% + 同色描边）：评分星等用 */
private val solidCache = mutableMapOf<ImageVector, ImageVector>()

fun ImageVector.solid(): ImageVector = solidCache.getOrPut(this) {
    val source = this
    val builder = ImageVector.Builder(name = "${source.name}-solid", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    val root = source.root
    for (index in 0 until root.size) {
        val node = root[index] as? androidx.compose.ui.graphics.vector.VectorPath ?: continue
        builder.addPath(
            pathData = node.pathData, fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }
    builder.build()
}

@Composable
fun KcIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, contentDescription: String? = null) {
    M3Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}
