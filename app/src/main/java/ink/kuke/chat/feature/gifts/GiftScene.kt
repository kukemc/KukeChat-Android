package ink.kuke.chat.feature.gifts

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.ui.theme.KcTheme
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 送礼物时放的一段动画（对照客户端 GiftScene / GiftHosts）。
 *   二档  在聊天区里放：礼物从左右两边抛进来，落地弹一下，溅出一圈彩屑，再飘上去淡掉；送了多个就错开抛几个（最多 5 个）。
 *   三档  全屏：火箭和城堡各有专门的一幕（升空 / 拔地而起加烟花），其余的礼物共用一幕（放光、撒彩屑、礼物蹦到正中）。
 * 一次只播一个，播完才轮到下一个；三档点一下（或返回键）跳过。系统关了动画（开发者选项「动画时长缩放」为 0）时什么都不播。
 * 粒子的角度、远近由序号算出来（noise），同样的参数每次画出来一样。
 *
 * 聊天页的用法：val stage = rememberGiftStage()；GiftArrivals(stage, messages) 盯着新到的礼物消息；
 * 消息区里放 GiftStageArea(stage)，最外层放 GiftStageFullscreen(stage)。别处想播一段直接 stage.play(effect)。
 */

/** 每一档放多久（毫秒） */
private const val TossMs = 2200
private const val GrandMs = 2800

/** 队列里最多攒这么多个：群里连着刷礼物时不至于排到几分钟以后去 */
private const val QueueMax = 6

data class GiftEffect(val id: Long, val tier: Int, val icon: String, val name: String, val count: Int, val fromName: String, val toName: String)

@Stable
class GiftStage internal constructor(private val motion: Boolean) {
    internal val queue = mutableStateListOf<GiftEffect>()
    internal val current: GiftEffect? get() = queue.firstOrNull()

    /** 排一段动效；同一份礼物只排一次，一档（卡片里的小动画）不进队列 */
    fun play(effect: GiftEffect) {
        Gifts.markFresh(effect.id)
        if (!motion || effect.tier < 2 || queue.size >= QueueMax || queue.any { it.id == effect.id }) return
        queue.add(effect)
    }

    internal fun finish(id: Long) {
        queue.removeAll { it.id == id }
    }
}

/** 系统「动画时长缩放」为 0 时当作要求减少动态效果 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

@Composable
fun rememberGiftStage(): GiftStage {
    val reduced = rememberReducedMotion()
    return remember(reduced) { GiftStage(!reduced) }
}

/** 一条礼物消息 → 动效（不是礼物消息、已撤回、数据读不出来都是 null） */
fun giftEffectOf(message: Message): GiftEffect? {
    if (message.type != "gift" || message.isRecalled) return null
    val meta = message.metadata?.get("gift") as? kotlinx.serialization.json.JsonObject ?: return null
    val id = (meta["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return null
    val gift = parseGiftRef(meta["gift"])
    val item = parseGiftItemRef(meta["item"])
    val count = (meta["count"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
    val to = parseGiftUser(meta["to_user"])
    return GiftEffect(id, gift?.tier ?: 1, gift?.icon.orEmpty(), item?.name ?: gift?.name ?: "礼物", if (item != null) 1 else count, message.senderName, to?.name ?: "Ta")
}

/**
 * 盯着会话里的消息：新到的礼物消息（实时推来的，或者自己刚送出、回包落进列表的）排进动效。
 * 只算「刚发生」的：进会话时已经在列表里的、加载更早消息翻出来的都不播（看 created_at 是不是一分钟以内）。
 */
@Composable
fun GiftArrivals(stage: GiftStage, messages: List<Message>) {
    val seen = remember { HashSet<Long>() }
    LaunchedEffect(stage) {
        snapshotFlow { messages.lastOrNull()?.id to messages.size }.collect {
            val now = Instant.now()
            messages.takeLast(12).forEach { message ->
                if (message.type != "gift" || !seen.add(message.id)) return@forEach
                val at = parseInstant(message.createdAt) ?: return@forEach
                if (Duration.between(at, now).abs().seconds > 60) return@forEach
                giftEffectOf(message)?.let(stage::play)
            }
        }
    }
}

// ------------------------------------------------------------------ 两层宿主

/** 聊天区那一层：正在播的是二档时画在消息区上面（不挡操作） */
@Composable
fun BoxScope.GiftStageArea(stage: GiftStage) {
    val effect = stage.current?.takeIf { it.tier == 2 } ?: return
    androidx.compose.runtime.key(effect.id) {
        val t = rememberProgress(effect, TossMs) { stage.finish(effect.id) }
        Box(Modifier.matchParentSize()) {
            val painter = rememberVectorPainter(giftVector(effect.icon))
            Canvas(Modifier.fillMaxSize()) { drawToss(t(), painter, effect.count) }
            Caption(effect, t, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        }
    }
}

/** 全屏那一层：三档铺满整个聊天页，点一下或返回键跳过 */
@Composable
fun BoxScope.GiftStageFullscreen(stage: GiftStage) {
    val effect = stage.current?.takeIf { it.tier >= 3 } ?: return
    androidx.compose.runtime.key(effect.id) {
        val done = { stage.finish(effect.id) }
        val t = rememberProgress(effect, GrandMs, done)
        BackHandler(onBack = done)
        Box(
            Modifier.matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = done),
        ) {
            val painter = rememberVectorPainter(giftVector(effect.icon))
            val kind = when (effect.icon) { "rocket" -> 1; "castle" -> 2; else -> 0 }
            Canvas(Modifier.fillMaxSize()) {
                val p = t()
                val dim = fade(p, 0f, 0.1f, 0.86f, 1f)
                drawRect(Color(0xFF0B0716).copy(alpha = 0.62f * dim))
                when (kind) {
                    1 -> drawRocket(p, painter)
                    2 -> drawCastle(p, painter, effect.count)
                    else -> drawGrand(p, painter, effect.count)
                }
            }
            Caption(effect, t, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
            Text(
                "点一下跳过", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp).graphicsLayer { alpha = fade(t(), 0.15f, 0.3f, 0.86f, 1f) },
            )
        }
    }
}

/** 一幕的进度 0→1（线性，各段的缓动在画的时候算）；放完调用 onDone */
@Composable
private fun rememberProgress(effect: GiftEffect, ms: Int, onDone: () -> Unit): () -> Float {
    val anim = remember(effect.id) { Animatable(0f) }
    LaunchedEffect(effect.id) {
        anim.animateTo(1f, tween(ms, easing = LinearEasing))
        onDone()
    }
    return { anim.value }
}

/** 「A 送给 B 蛋糕 ×3」的胶囊：开头落下来，结尾淡掉 */
@Composable
private fun Caption(effect: GiftEffect, t: () -> Float, modifier: Modifier) {
    val colors = KcTheme.colors
    Row(
        modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 340.dp)
            .graphicsLayer {
                val p = t()
                val a = fade(p, 0.06f, 0.18f, 0.86f, 1f)
                alpha = a
                translationY = (1f - easeOutBack(seg(p, 0.06f, 0.22f))) * -18.dp.toPx()
            }
            .clip(CircleShape)
            .background(Color(0xFF1C1424).copy(alpha = 0.86f))
            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GiftGlyph(effect.icon, 24.dp)
        val rose = if (colors.isDark) Color(0xFFFF93C4) else Color(0xFFFF8FC0)
        Text(effect.fromName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
        Text(" 送给 ", color = rose, fontSize = 13.sp, fontWeight = FontWeight.W600)
        Text(effect.toName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(" ${effect.name}", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1)
        if (effect.count > 1) Text(" ×${effect.count}", color = Color(0xFFFFCB3D), fontSize = 14.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.W800)
    }
}

// ------------------------------------------------------------------ 缓动与粒子

private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)
private fun easeInCubic(x: Float) = x * x * x
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
}

/** 先淡入（a→b）再淡出（c→d） */
private fun fade(t: Float, a: Float, b: Float, c: Float, d: Float): Float = min(seg(t, a, b), 1f - seg(t, c, d))

/** 由序号得到的 0 到 1 之间的固定小数，代替随机数 */
private fun noise(seed: Float): Float {
    val v = sin(seed * 12.9898f + 4.1414f) * 43758.5453f
    return v - floor(v)
}

private val Confetti = listOf(Color(0xFFFFCB3D), Color(0xFFFF8FB3), Color(0xFF45B7FF), Color(0xFF5CCB7F), Color(0xFFA97BFF), Color(0xFFFF9A2E))

private fun starPath(r: Float, inner: Float = 0.45f): Path = Path().apply {
    for (i in 0 until 10) {
        val a = (-90f + i * 36f) * PI.toFloat() / 180f
        val rr = if (i % 2 == 0) r else r * inner
        if (i == 0) moveTo(rr * cos(a), rr * sin(a)) else lineTo(rr * cos(a), rr * sin(a))
    }
    close()
}

/** 四角的小闪光 */
private fun DrawScope.sparkle(center: Offset, r: Float, color: Color, alpha: Float) {
    if (alpha <= 0f || r <= 0f) return
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x, center.y, center.x + r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + r)
        quadraticTo(center.x, center.y, center.x - r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - r)
        close()
    }
    drawPath(p, color, alpha = alpha)
}

/**
 * 从一点炸开的一圈彩屑：p 是这段炸开的进度，reach 是最远飞多远；gravity 让后半程往下坠一点。
 * shapes：0 圆点 · 1 纸片 · 2 星
 */
private fun DrawScope.burst(center: Offset, p: Float, count: Int, seed: Float, reach: Float, shapes: IntArray, colors: List<Color>, gravity: Float = 0f, unit: Float = 1f) {
    if (p <= 0f || p >= 1f) return
    val out = easeOutCubic(p)
    val alpha = 1f - p.pow(2.2f)
    for (i in 0 until count) {
        val angle = ((i + noise(seed + i * 3.1f) * 0.7f) * (360f / count)) * PI.toFloat() / 180f
        val dist = reach * (0.5f + noise(seed + i * 7.7f) * 0.5f) * out
        val spin = (noise(seed + i * 1.3f) - 0.5f) * 900f * p
        val pos = Offset(center.x + cos(angle) * dist, center.y + sin(angle) * dist + gravity * p * p)
        val color = colors[i % colors.size]
        when (shapes[i % shapes.size]) {
            0 -> drawCircle(color, radius = 3.4f * unit, center = pos, alpha = alpha)
            1 -> rotate(spin, pos) { drawRoundRect(color, Offset(pos.x - 3f * unit, pos.y - 5.5f * unit), Size(6f * unit, 11f * unit), CornerRadius(1.6f * unit), alpha = alpha) }
            else -> rotate(spin, pos) { translate(pos.x, pos.y) { drawPath(starPath(6f * unit), color, alpha = alpha) } }
        }
    }
}

/** 礼物图形：中心在 center，边长 size */
private fun DrawScope.glyph(painter: VectorPainter, center: Offset, size: Float, rotation: Float = 0f, scaleX: Float = 1f, scaleY: Float = 1f, alpha: Float = 1f) {
    if (alpha <= 0f || size <= 0f) return
    withTransform({
        translate(center.x - size / 2, center.y - size / 2)
        rotate(rotation, Offset(size / 2, size / 2))
        scale(scaleX, scaleY, Offset(size / 2, size / 2))
    }) {
        with(painter) { draw(Size(size, size), alpha = alpha) }
    }
}

private val Twinkles = listOf(-0.7f to -0.56f, 0.72f to -0.44f, -0.62f to 0.22f, 0.66f to 0.28f, -0.34f to -0.78f, 0.36f to -0.74f)

private fun DrawScope.twinkles(center: Offset, radius: Float, t: Float, unit: Float) {
    Twinkles.forEachIndexed { i, (x, y) ->
        val local = seg(t, 0.18f + i * 0.05f, 0.95f)
        if (local <= 0f) return@forEachIndexed
        val pulse = sin(local * PI.toFloat() * 3f).coerceAtLeast(0f)
        sparkle(Offset(center.x + x * radius, center.y + y * radius), (5f + 5f * pulse) * unit, Color(0xFFFFF4C9), pulse * (1f - seg(t, 0.85f, 1f)))
    }
}

// ------------------------------------------------------------------ 二档：抛礼物

private fun DrawScope.drawToss(t: Float, painter: VectorPainter, count: Int) {
    val unit = density
    val copies = count.coerceIn(1, 5)
    val step = if (copies > 3) 0.13f else 0.17f
    val w = size.width
    val h = size.height
    val landY = h * 0.5f
    val glyphSize = (if (copies > 3) 80f else 96f) * unit
    val leave = seg(t, 0.74f, 1f)
    for (i in 0 until copies) {
        val slot = i - (copies - 1) / 2f
        val fromLeft = i % 2 == 0
        val d = i * 0.07f
        val f = seg(t, d, d + 0.3f)
        if (f <= 0f) continue
        val ef = easeOutCubic(f)
        val startX = w / 2 + (if (fromLeft) -0.62f else 0.62f) * w
        val endX = w / 2 + slot * step * w
        val x = startX + (endX - startX) * ef
        val arc = h * 0.26f
        var y = (landY + h * 0.16f) + (landY - (landY + h * 0.16f)) * f - arc * 4f * f * (1f - f)
        val tilt = slot * 5f + if (fromLeft) -3f else 3f
        val rot = tilt + (1f - ef) * (if (fromLeft) -240f else 240f)
        // 落地弹一下：先压扁再弹起
        val hop = seg(t, d + 0.3f, d + 0.48f)
        val squash = sin(hop * PI.toFloat())
        val sx = 1f + 0.14f * squash * (1f - hop)
        val sy = 1f - 0.14f * squash * (1f - hop)
        y -= sin(seg(t, d + 0.36f, d + 0.5f) * PI.toFloat()) * 14f * unit
        // 最后飘上去淡掉
        y -= easeInCubic(leave) * h * 0.2f
        glyph(painter, Offset(x, y), glyphSize, rot, sx, sy, alpha = 1f - easeInCubic(leave))
    }
    burst(Offset(w / 2, landY), seg(t, 0.3f, 0.82f), 12, 2f, min(w, h) * 0.34f, intArrayOf(2, 1, 0), Confetti, gravity = 30f * unit, unit = unit)
}

// ------------------------------------------------------------------ 三档：通用一幕

private fun DrawScope.drawGrand(t: Float, painter: VectorPainter, count: Int) {
    val unit = density
    val c = Offset(size.width / 2, size.height * 0.44f)
    val shortSide = min(size.width, size.height)
    val show = fade(t, 0.04f, 0.2f, 0.84f, 1f)
    // 光晕
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE487).copy(alpha = 0.55f), Color(0xFFFFCB3D).copy(alpha = 0.18f), Color.Transparent), center = c, radius = shortSide * 0.5f),
        radius = shortSide * 0.5f * (0.7f + 0.3f * easeOutCubic(seg(t, 0.04f, 0.3f))), center = c, alpha = show,
    )
    // 放射光：十二道粗细相间的光芒，慢慢转
    val rayR = shortSide * 0.62f * (0.6f + 0.4f * easeOutCubic(seg(t, 0.04f, 0.32f)))
    val rays = Brush.radialGradient(
        0.1f to Color(0xFFFFE487).copy(alpha = 0.85f), 0.45f to Color(0xFFFFCB3D).copy(alpha = 0.32f), 0.8f to Color(0xFFFFCB3D).copy(alpha = 0.07f), 1f to Color.Transparent,
        center = c, radius = rayR,
    )
    rotate(t * 70f, c) {
        for (i in 0 until 12) {
            val half = if (i % 2 == 0) 0.108f else 0.056f
            rotate(i * 30f, c) {
                val path = Path().apply {
                    moveTo(c.x, c.y)
                    lineTo(c.x - half * rayR, c.y - rayR)
                    lineTo(c.x + half * rayR, c.y - rayR)
                    close()
                }
                drawPath(path, rays, alpha = show)
            }
        }
    }
    burst(c, seg(t, 0.12f, 0.9f), 30, 5f, shortSide * 0.62f, intArrayOf(1, 0, 1, 2), Confetti, gravity = 120f * unit, unit = unit * 1.15f)
    twinkles(c, 150f * unit, t, unit)
    // 主角：蹦到正中，轻轻浮动，最后放大淡出
    val pop = easeOutBack(seg(t, 0.06f, 0.3f))
    val bob = sin(seg(t, 0.3f, 0.86f) * PI.toFloat() * 2f) * 6f * unit
    val out = seg(t, 0.86f, 1f)
    val s = pop * (1f + 0.12f * out)
    glyph(painter, Offset(c.x, c.y + bob), 200f * unit, rotation = (1f - pop) * -20f, scaleX = s, scaleY = s, alpha = 1f - out)
    if (count > 1) timesMark(Offset(c.x + 84f * unit, c.y + 70f * unit), count, seg(t, 0.28f, 0.42f), 1f - out, unit)
}

/** 「×N」：金色描白的大字，弹一下出来 */
private fun DrawScope.timesMark(at: Offset, count: Int, p: Float, alpha: Float, unit: Float) {
    if (p <= 0f) return
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f * unit
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD_ITALIC)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val s = easeOutBack(p)
    withTransform({ scale(s, s, at) }) {
        drawContext.canvas.nativeCanvas.apply {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 6f * unit
            paint.color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255)
            drawText("×$count", at.x, at.y, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.argb((alpha * 255).toInt(), 0xF0, 0xA1, 0x1C)
            drawText("×$count", at.x, at.y, paint)
        }
    }
}

// ------------------------------------------------------------------ 三档：火箭

private fun DrawScope.drawRocket(t: Float, painter: VectorPainter) {
    val unit = density
    val w = size.width
    val h = size.height
    val show = fade(t, 0.02f, 0.12f, 0.88f, 1f)
    // 往下划过的星：横向位置、快慢、亮度都由序号定
    for (i in 0 until 14) {
        val x = w * (0.04f + i * 0.067f + noise(i + 11f) * 0.04f)
        val speed = 0.19f + noise(i + 23f) * 0.09f
        val wait = noise(i + 37f) * 0.14f
        val local = ((t - wait) / speed) % 1f
        if (t < wait) continue
        val y = -0.15f * h + local * h * 1.3f
        val len = (40f + 50f * noise(i + 5f)) * unit
        drawLine(Color.White.copy(alpha = (0.35f + noise(i + 41f) * 0.55f) * show), Offset(x, y), Offset(x, y + len), strokeWidth = 2f * unit, cap = StrokeCap.Round)
    }
    // 位置：升到中间 → 悬着抖 → 冲出屏幕顶
    val rise = easeOutCubic(seg(t, 0.04f, 0.36f))
    val launch = easeInCubic(seg(t, 0.62f, 0.94f))
    val cy = (h + 140f * unit) + (h * 0.46f - (h + 140f * unit)) * rise - launch * (h * 0.46f + 260f * unit)
    val shaking = seg(t, 0.3f, 0.66f) > 0f && launch == 0f
    val cx = w / 2 + if (shaking) sin(t * 260f) * 2.6f * unit else 0f
    val rocketSize = 200f * unit
    // 烟：悬停时从尾巴往两边鼓出来
    for (i in 0 until 6) {
        val local = seg(t, 0.3f + i * 0.03f, 0.72f + i * 0.03f)
        if (local <= 0f || local >= 1f) continue
        val px = (noise(i + 3f) - 0.5f) * 1.8f
        val pos = Offset(cx + px * rocketSize * 0.5f * easeOutCubic(local), cy + rocketSize * 0.5f + 12f * unit * local)
        drawCircle(Color(0xFFE8E2F2), radius = (18f + 34f * local) * unit, center = pos, alpha = 0.55f * (1f - local) * show)
    }
    // 点火的一圈冲击波
    val ring = seg(t, 0.6f, 0.86f)
    if (ring in 0.001f..0.999f) drawCircle(Color(0xFFFFE487), radius = (20f + 180f * easeOutCubic(ring)) * unit, center = Offset(cx, cy + rocketSize * 0.45f), alpha = 0.8f * (1f - ring), style = Stroke(width = 4f * unit * (1f - ring) + 1f))
    // 尾焰：一直在抖，点火后变长
    val flicker = 1f + 0.16f * sin(t * 120f) + 0.08f * sin(t * 47f)
    val flameLen = (54f + 70f * seg(t, 0.56f, 0.7f)) * unit * flicker * rise
    val flameW = 34f * unit
    val top = cy + rocketSize * 0.36f
    fun flame(widthK: Float, lenK: Float, color: Color) {
        val hw = flameW * widthK / 2
        val len = flameLen * lenK
        val p = Path().apply {
            moveTo(cx - hw, top)
            lineTo(cx + hw, top)
            cubicTo(cx + hw * 1.1f, top + len * 0.32f, cx + hw * 0.5f, top + len * 0.7f, cx, top + len)
            cubicTo(cx - hw * 0.5f, top + len * 0.7f, cx - hw * 1.1f, top + len * 0.32f, cx - hw, top)
            close()
        }
        drawPath(p, color, alpha = show)
    }
    flame(1f, 1f, Color(0xFFFF8A2E).copy(alpha = 0.92f))
    flame(0.64f, 0.76f, Color(0xFFFFC94A))
    flame(0.34f, 0.5f, Color(0xFFFFF1B0))
    glyph(painter, Offset(cx, cy), rocketSize, alpha = show)
}

// ------------------------------------------------------------------ 三档：城堡

private class Firework(val x: Float, val y: Float, val at: Float, val colors: List<Color>)

private val Fireworks = listOf(
    Firework(0.22f, 0.26f, 0.3f, listOf(Color(0xFFFFCB3D), Color(0xFFFFE487))),
    Firework(0.78f, 0.21f, 0.41f, listOf(Color(0xFFFF8FB3), Color(0xFFFFD0E0))),
    Firework(0.5f, 0.13f, 0.52f, listOf(Color(0xFF45B7FF), Color(0xFFB5E3FF))),
    Firework(0.71f, 0.4f, 0.62f, listOf(Color(0xFFA97BFF), Color(0xFFFFE487))),
)

private fun DrawScope.drawCastle(t: Float, painter: VectorPainter, count: Int) {
    val unit = density
    val w = size.width
    val h = size.height
    val show = fade(t, 0.02f, 0.12f, 0.86f, 1f)
    // 烟花：先一下闪光，再炸开一圈光线
    Fireworks.forEachIndexed { index, fw ->
        val p = seg(t, fw.at, fw.at + 0.3f)
        if (p <= 0f || p >= 1f) return@forEachIndexed
        val c = Offset(w * fw.x, h * fw.y)
        drawCircle(fw.colors[1], radius = 26f * unit * easeOutCubic(seg(p, 0f, 0.3f)), center = c, alpha = (1f - seg(p, 0.1f, 0.4f)) * 0.9f)
        val out = easeOutCubic(p)
        for (i in 0 until 12) {
            val a = ((i + noise(50f + index * 9 + i * 3.1f) * 0.7f) * 30f) * PI.toFloat() / 180f
            val reach = (60f + 40f * noise(50f + index * 9 + i * 7.7f)) * unit
            val r1 = reach * out
            val r0 = r1 * (0.4f + 0.5f * p)
            drawLine(fw.colors[i % 2], Offset(c.x + cos(a) * r0, c.y + sin(a) * r0), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), strokeWidth = 3f * unit, cap = StrokeCap.Round, alpha = (1f - p.pow(1.6f)) * show)
        }
    }
    val castleSize = 240f * unit
    val groundY = h * 0.44f + castleSize * 0.46f
    val cx = w / 2
    twinkles(Offset(cx, h * 0.44f), 170f * unit, t, unit)
    // 地面：一道柔和的椭圆光
    drawOval(
        Brush.radialGradient(listOf(Color(0xFF5CCB7F).copy(alpha = 0.45f), Color.Transparent), center = Offset(cx, groundY), radius = castleSize * 0.7f),
        topLeft = Offset(cx - castleSize * 0.7f, groundY - 18f * unit), size = Size(castleSize * 1.4f, 36f * unit), alpha = show,
    )
    // 从地里升起来：只露出地面以上的部分，升的时候抖一点
    val rise = easeOutCubic(seg(t, 0.04f, 0.38f))
    val shake = if (rise in 0.01f..0.99f) sin(t * 300f) * 2f * unit else 0f
    val cy = groundY - castleSize * 0.46f + (1f - rise) * castleSize
    clipRect(0f, 0f, w, groundY + 2f * unit) {
        glyph(painter, Offset(cx + shake, cy), castleSize, alpha = show)
    }
    // 脚下扬尘
    for (i in 0 until 6) {
        val local = seg(t, 0.06f + i * 0.02f, 0.5f + i * 0.02f)
        if (local <= 0f || local >= 1f) continue
        val dir = if (i % 2 == 0) -1f else 1f
        val px = dir * (1.2f + noise(i + 5f) * 1.4f) * 40f * unit * easeOutCubic(local)
        drawCircle(Color(0xFFD9CDE8), radius = (14f + 22f * local) * unit, center = Offset(cx + dir * castleSize * 0.3f + px, groundY - 8f * unit - 20f * unit * local), alpha = 0.6f * (1f - local) * show)
    }
    if (count > 1) timesMark(Offset(cx + 100f * unit, groundY - 20f * unit), count, seg(t, 0.36f, 0.5f), show, unit)
}
