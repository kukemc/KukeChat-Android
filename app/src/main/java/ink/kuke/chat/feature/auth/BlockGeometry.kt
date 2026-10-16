package ink.kuke.chat.feature.auth

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max

/*
 * 积木排版 + 外形路径。逐行移植 scratchblocks@3.7.1 的 scratch3 渲染器（scratch3/blocks.js 的 BlockView.draw、
 * InputView.draw，scratch3/draw.js 的 getTop / getRightAndBottom / getArm / hatRect / mouthRect），数值一个不改：
 *   一行高 40（命令）/ 32（其他）+ 上下内边距（帽子 24/8，其余 4/4）→ 命令 48、帽子 64、扩展 56、布尔 / 报告 40；
 *   参数槽高 32，文本槽左右内边距 11、最窄 40；下拉 = 文字 + 42；零件间距 8，相邻文字 4.448（一个空格）；
 *   第一个参数槽对齐到凹口右侧（x ≥ 40）；最窄宽度：C 形 160、帽子 100、命令 64、报告 48；
 *   凹口：起点 x=12、宽 32、深 8；圆角 4；C 形的嘴缩进 16，底臂 = 29 + 3。
 * 文字：500 字重 16px（scratchblocks 的 12pt），基线 = 行中线 + 6.1；颜色白色，参数槽里 #575E75。
 * 所有数值都是「积木坐标」（Scratch 100% 缩放下 1 单位 = 1px），画的时候整体缩放。
 */

internal enum class ItemKind { Text, Pill, Drop, Hex, HexSlot, InnerStack, Flag, Icon, VLine, Arrow }

/** 积木里的一个零件，坐标相对积木左上角。[tinted]：颜色跟「两次密码是否一致」走 */
internal data class Item(
    val kind: ItemKind,
    val x: Float,
    val y: Float,
    val w: Float = 0f,
    val h: Float = 0f,
    val fill: Color = Color.Unspecified,
    val stroke: Color? = null,
    val text: String = "",
    val layout: TextLayoutResult? = null,
    val tinted: Boolean = false,
)

/** 排好版的一块积木。[body] = 在串里占的高度（下一块的 y = 本块 y + body），底部凸起不算 */
internal class BlockLayout(
    val shape: BlockShape,
    val cat: Cat,
    val w: Float,
    val body: Float,
    /** C 形：第一行高度（上臂底边）/ 下臂顶边 */
    val line0: Float,
    val armTop: Float,
    val items: List<Item>,
) {
    val hasNotch get() = shape == BlockShape.Stack || shape == BlockShape.Hat || shape == BlockShape.CBlock
    /** 和 scratchblocks 渲染出的 SVG 一样高（含凸起 + 上下各 1 的留白），镜头跟随用 */
    val fullHeight get() = body + if (hasNotch) 10f else 2f
}

internal const val LITERAL_COLOR = 0xFF575E75
private const val LABEL_SIZE = 16f
/** 积木文字的基线在行中线下 6.1（scratchblocks：文字框高 12、y=13.1、再上移 1） */
private const val BASELINE_FROM_CENTER = 6.1f

private enum class KidType { Label, Flag, Icon, Line, Str, Drop, BoolSlot, Bool }

private class Kid(val type: KidType, val w: Float, val h: Float, val label: TextLayoutResult? = null, val text: String = "", val sub: BlockLayout? = null) {
    var x = 0f
    val isLabel get() = type == KidType.Label
    val isIcon get() = type == KidType.Flag || type == KidType.Icon
}

private val unitDensity = Density(1f, 1f)
private val labelStyle = TextStyle(fontSize = LABEL_SIZE.sp, fontWeight = FontWeight.Medium)

internal fun TextMeasurer.blockText(text: String): TextLayoutResult =
    measure(AnnotatedString(text), labelStyle, softWrap = false, maxLines = 1, density = unitDensity)

private fun TextLayoutResult.textWidth(): Float = if (layoutInput.text.isEmpty()) 0f else getLineRight(0) - getLineLeft(0)

private fun isCommand(s: BlockShape) = s == BlockShape.Stack || s == BlockShape.Cap || s == BlockShape.CBlock || s == BlockShape.CBlockFinal

/** scratchblocks 的 horizontalPadding：零件和积木左右边缘的距离，跟积木外形有关 */
private fun hpad(shape: BlockShape, k: Kid): Float = when (shape) {
    BlockShape.Reporter -> when (k.type) {
        KidType.Flag, KidType.Icon -> 16f
        KidType.Label, KidType.Drop, KidType.BoolSlot, KidType.Bool -> 12f
        KidType.Str -> 4f
        KidType.Line -> 8f
    }
    BlockShape.Boolean -> when (k.type) {
        KidType.Flag, KidType.Icon -> 24f
        KidType.Label, KidType.Drop, KidType.Str -> 20f
        KidType.BoolSlot, KidType.Bool -> 8f
        KidType.Line -> 8f
    }
    else -> 8f
}

private fun margin(a: Kid, b: Kid) = if (a.isLabel && b.isLabel) 4.447998f else 8f

private fun kidsOf(spec: Spec, tm: TextMeasurer): List<Kid> = buildList {
    for (p in spec.parts) when (p) {
        is Part.Label -> tm.blockText(p.text).let { add(Kid(KidType.Label, it.textWidth(), 12f, it, p.text)) }
        Part.Flag -> add(Kid(KidType.Flag, 20f, 21f))
        Part.ExtIcon -> { add(Kid(KidType.Icon, 40f, 40f)); add(Kid(KidType.Line, 1f, 40f)) }
        is Part.Str -> {
            val l = tm.blockText(p.text)
            val tw = l.textWidth()
            val px = if (tw >= 18f) 11f else (40f - tw) / 2f
            add(Kid(KidType.Str, tw + 2 * px, 32f, l, p.text))
        }
        is Part.Drop -> tm.blockText(p.text).let { add(Kid(KidType.Drop, it.textWidth() + 42f, 32f, it, p.text)) }
        Part.BoolSlot -> add(Kid(KidType.BoolSlot, 48f, 32f))
        is Part.Bool -> {
            val sub = layoutSpec(Spec(BlockShape.Boolean, p.cat, p.parts), tm, tinted = p.tinted)
            add(Kid(KidType.Bool, sub.w, sub.body, sub = sub))
        }
    }
}

/** 排一块积木（scratchblocks BlockView.draw 的移植，只支持一行 + 至多一个嘴） */
internal fun layoutSpec(spec: Spec, tm: TextMeasurer, tinted: Boolean = false): BlockLayout {
    val shape = spec.shape
    val command = isCommand(shape)
    val pt = if (shape == BlockShape.Hat) 24f else 4f
    val pb = if (shape == BlockShape.Hat) 8f else 4f
    val kids = kidsOf(spec, tm)

    var lw = 0f
    var lh = if (command) 40f else 32f
    var prev: Kid? = null
    val cmw = if (kids.isNotEmpty()) 48f - hpad(shape, kids[0]) else 48f
    kids.forEachIndexed { i, k ->
        if (prev != null) lw += margin(prev!!, k)
        // 第一个参数槽对齐到凹口右侧
        if (command && !k.isLabel && !k.isIcon && lw < cmw) lw = cmw
        // 扩展图标在凹口下面，行高多 8
        if (k.isIcon && i == 0 && command) lh = max(lh, k.h + 8f)
        k.x = lw
        lw += k.w
        if (!k.isLabel) lh = max(lh, k.h)
        prev = k
    }
    val line0 = lh + pt + pb
    val padL = if (kids.isNotEmpty()) hpad(shape, kids.first()) else 0f
    val padR = if (kids.isNotEmpty()) hpad(shape, kids.last()) else 0f
    val natural = lw + padL + padR
    val hasScript = shape == BlockShape.CBlock || shape == BlockShape.CBlockFinal
    val minW = when {
        hasScript -> 160f
        shape == BlockShape.Hat -> 100f
        command -> 64f
        shape == BlockShape.Reporter -> 48f
        else -> 0f
    }
    val innerW = max(minW, natural)
    // 报告积木比最窄宽度还窄时，文字居中
    val padLeft = if (shape == BlockShape.Reporter) padL + (innerW - natural) / 2f else padL

    val items = ArrayList<Item>()
    for (k in kids) {
        val x = padLeft + k.x
        var y = pt + (line0 - k.h - pt - pb) / 2f
        when (k.type) {
            KidType.Label -> {
                val l = k.label!!
                // 第一行文字再上移 1（scratchblocks 原样）；基线 = 文字框顶 + 13.1
                val baseline = floor(y - 1f) + 13.1f
                items += Item(ItemKind.Text, x, baseline - l.firstBaseline, k.w, text = k.text, layout = l, fill = Color.White)
            }
            KidType.Flag -> items += Item(ItemKind.Flag, x, floor(y - 1f), 20f, 21f)
            KidType.Icon -> {
                // 设计稿把扩展图标放在 y=8（命令）/ 4（报告），正好在行里居中
                y = if (command) (line0 - 40f) / 2f else 4f
                items += Item(ItemKind.Icon, x, y, 40f, 40f)
            }
            KidType.Line -> items += Item(ItemKind.VLine, x, floor(y), 1f, 40f, stroke = spec.cat.stroke)
            KidType.Str -> {
                val top = floor(y)
                val l = k.label!!
                val tw = l.textWidth()
                val px = if (tw >= 18f) 11f else (40f - tw) / 2f
                items += Item(ItemKind.Pill, x, top, k.w, 32f, fill = Color.White, stroke = spec.cat.inputStroke, tinted = tinted)
                items += Item(ItemKind.Text, x + px, top + 16f + BASELINE_FROM_CENTER - l.firstBaseline, tw, text = k.text, layout = l, fill = Color(LITERAL_COLOR))
            }
            KidType.Drop -> {
                val top = floor(y)
                val l = k.label!!
                items += Item(ItemKind.Drop, x, top, k.w, 32f, fill = spec.cat.dropFill, stroke = spec.cat.dropStroke)
                items += Item(ItemKind.Text, x + 11f, top + 16f + BASELINE_FROM_CENTER - l.firstBaseline, k.w - 42f, text = k.text, layout = l, fill = Color.White)
                items += Item(ItemKind.Arrow, x + k.w - 24f, top + 12.850511f)
            }
            KidType.BoolSlot -> items += Item(ItemKind.HexSlot, x, floor(y), 48f, 32f, fill = spec.cat.stroke)
            KidType.Bool -> {
                val sub = k.sub!!
                val top = floor(y)
                items += Item(ItemKind.Hex, x, top, sub.w, sub.body, fill = sub.cat.fill, stroke = sub.cat.stroke, tinted = sub.items.any { it.tinted })
                sub.items.forEach { items += it.copy(x = it.x + x, y = it.y + top) }
            }
        }
    }

    var body = line0
    var armTop = 0f
    var w = innerW
    if (hasScript) {
        val inner = spec.inner.map { layoutSpec(it, tm) }
        // ScriptView（嘴里那串）：上下各留 1；嘴的那一行高度 = max(29, 串高 + 3) - 2，下臂 29
        val scriptH = 2f + inner.sumOf { it.body.toDouble() }.toFloat()
        val childH = max(29f, scriptH + 3f) - 2f
        val scriptW = max(1f, inner.maxOfOrNull { it.w } ?: 0f)
        armTop = line0 + childH - 3f
        body = line0 + childH + 29f
        w = max(innerW, 15f + scriptW)
        var iy = line0
        for (b in inner) {
            items += Item(ItemKind.InnerStack, 16f, iy, b.w, b.body, fill = b.cat.fill, stroke = b.cat.stroke)
            b.items.forEach { items += it.copy(x = it.x + 16f, y = it.y + iy) }
            iy += b.body
        }
    }
    return BlockLayout(shape, spec.cat, w, body, line0, armTop, items)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * 两次排版之间插值（设计稿的 morph：结构不变，只有数字变）。结构对不上（零件数 / 种类不同）就直接用新的。
 * 文字内容沿用新的，交叉淡入淡出由调用方处理。
 */
internal fun lerpLayout(a: BlockLayout, b: BlockLayout, t: Float): BlockLayout {
    if (t >= 1f || a === b) return b
    if (a.items.size != b.items.size || a.shape != b.shape) return b
    val items = ArrayList<Item>(b.items.size)
    for (i in b.items.indices) {
        val ia = a.items[i]
        val ib = b.items[i]
        if (ia.kind != ib.kind) return b
        items += ib.copy(x = lerp(ia.x, ib.x, t), y = lerp(ia.y, ib.y, t), w = lerp(ia.w, ib.w, t), h = lerp(ia.h, ib.h, t))
    }
    return BlockLayout(b.shape, b.cat, lerp(a.w, b.w, t), lerp(a.body, b.body, t), lerp(a.line0, b.line0, t), lerp(a.armTop, b.armTop, t), items)
}

/** 结构相同（可以逐个零件变形） */
internal fun sameStructure(a: BlockLayout, b: BlockLayout): Boolean =
    a.shape == b.shape && a.items.size == b.items.size && a.items.indices.all { a.items[it].kind == b.items[it].kind }

// ───────────────────────── 路径（scratch3/draw.js） ─────────────────────────

/** 顶边凹口：从 (12 或 28, y) 画到右上角圆角结束 */
private fun Path.topNotch(w: Float, y: Float) {
    relativeCubicTo(2f, 0f, 3f, 1f, 4f, 2f)
    relativeLineTo(4f, 4f)
    relativeCubicTo(1f, 1f, 2f, 2f, 4f, 2f)
    relativeLineTo(12f, 0f)
    relativeCubicTo(2f, 0f, 3f, -1f, 4f, -2f)
    relativeLineTo(4f, -4f)
    relativeCubicTo(1f, -1f, 2f, -2f, 4f, -2f)
    lineTo(w - 4f, y)
    arcTo(Rect(w - 8f, y, w, y + 8f), 270f, 90f, false)
}

private fun Path.top(w: Float) {
    moveTo(0f, 4f)
    arcTo(Rect(0f, 0f, 8f, 8f), 180f, 90f, false)
    lineTo(12f, 0f)
    topNotch(w, 0f)
}

private fun Path.hatTop(w: Float) {
    moveTo(0f, 16f)
    relativeCubicTo(25f, -22f, 71f, -22f, 96f, 0f)
    lineTo(w - 4f, 16f)
    arcTo(Rect(w - 8f, 16f, w, 24f), 270f, 90f, false)
}

private fun Path.rightAndBottom(w: Float, y: Float, hasNotch: Boolean, inset: Float) {
    lineTo(w, y - 4f)
    arcTo(Rect(w - 8f, y - 8f, w, y), 0f, 90f, false)
    if (hasNotch) {
        lineTo(inset + 48f, y)
        relativeCubicTo(-2f, 0f, -3f, 1f, -4f, 2f)
        relativeLineTo(-4f, 4f)
        relativeCubicTo(-1f, 1f, -2f, 2f, -4f, 2f)
        relativeLineTo(-12f, 0f)
        relativeCubicTo(-2f, 0f, -3f, -1f, -4f, -2f)
        relativeLineTo(-4f, -4f)
        relativeCubicTo(-1f, -1f, -2f, -2f, -4f, -2f)
    }
    lineTo(inset + 4f, y)
    if (inset == 0f) arcTo(Rect(0f, y - 8f, 8f, y), 90f, 90f, false)
    else arcTo(Rect(inset, y, inset + 8f, y + 8f), 270f, -90f, false)
}

private fun Path.arm(w: Float, armTop: Float) {
    lineTo(16f, armTop - 4f)
    arcTo(Rect(16f, armTop - 8f, 24f, armTop), 180f, -90f, false)
    lineTo(28f, armTop)
    topNotch(w, armTop)
}

/** 命令积木（上凹口下凸起），画进 [out]（先清空） */
internal fun stackPath(out: Path, w: Float, h: Float, x: Float = 0f, y: Float = 0f) {
    out.reset()
    out.top(w)
    out.rightAndBottom(w, h, true, 0f)
    out.close()
    if (x != 0f || y != 0f) out.translate(androidx.compose.ui.geometry.Offset(x, y))
}

internal fun hexPath(out: Path, x: Float, y: Float, w: Float, h: Float) {
    val r = h / 2f
    out.reset()
    out.moveTo(x + r, y)
    out.lineTo(x + w - r, y)
    out.lineTo(x + w, y + r)
    out.lineTo(x + w - r, y + h)
    out.lineTo(x + r, y + h)
    out.lineTo(x, y + r)
    out.close()
}

/** 整块积木的外轮廓 */
internal fun outlinePath(out: Path, l: BlockLayout) {
    out.reset()
    val w = l.w
    when (l.shape) {
        BlockShape.Stack -> { out.top(w); out.rightAndBottom(w, l.body, true, 0f); out.close() }
        BlockShape.Cap -> { out.top(w); out.rightAndBottom(w, l.body, false, 0f); out.close() }
        BlockShape.Hat -> { out.hatTop(w); out.rightAndBottom(w, l.body, true, 0f); out.close() }
        BlockShape.CBlock, BlockShape.CBlockFinal -> {
            out.top(w)
            out.rightAndBottom(w, l.line0, true, 16f)
            out.arm(w, l.armTop)
            out.rightAndBottom(w, l.body, l.shape == BlockShape.CBlock, 0f)
            out.close()
        }
        BlockShape.Reporter -> out.addRoundRect(RoundRect(0f, 0f, w, l.body, CornerRadius(l.body / 2f)))
        BlockShape.Boolean -> hexPath(out, 0f, 0f, w, l.body)
    }
}

/**
 * 「虚拟积木」：只要外形线（积木轮廓 + 参数槽 / 嘴里的积木轮廓），合成一条路径。
 * 偏移 (2, 1) 同 scratchblocks 的 SVG（积木画在 x=2、y=1）。返回路径和 SVG 的宽高。
 */
internal class OutlineShape(val path: Path, val svgW: Float, val svgH: Float)

internal fun outlineShape(spec: Spec, tm: TextMeasurer): OutlineShape {
    val l = layoutSpec(spec, tm)
    val all = Path()
    val tmp = Path()
    outlinePath(tmp, l)
    all.addPath(tmp)
    for (it in l.items) {
        when (it.kind) {
            ItemKind.Pill -> all.addRoundRect(RoundRect(it.x, it.y, it.x + it.w, it.y + it.h, CornerRadius(it.h / 2f)))
            ItemKind.Drop -> all.addRoundRect(RoundRect(it.x, it.y, it.x + it.w, it.y + it.h, CornerRadius(4f)))
            ItemKind.Hex, ItemKind.HexSlot -> { hexPath(tmp, it.x, it.y, it.w, it.h); all.addPath(tmp) }
            ItemKind.InnerStack -> { stackPath(tmp, it.w, it.h, it.x, it.y); all.addPath(tmp) }
            else -> Unit
        }
    }
    all.translate(androidx.compose.ui.geometry.Offset(2f, 1f))
    // ScriptView：上下各 1，末块有凸起再加 8（帽子 / 命令 / 非终结 C）
    return OutlineShape(all, l.w + 4f, l.body + if (l.hasNotch) 10f else 2f)
}
