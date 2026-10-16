package ink.kuke.chat.feature.user

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.components.softShadow
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.IntimacyRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 友谊升级 / 好友成就解锁的庆祝提示（实时事件 intimacy.level.up / intimacy.badge.unlocked，同客户端 IntimacyToasts.tsx）。
 * 顶部弹出一张卡片：弹簧落下 + 一小撮彩纸，7 秒后自己收起；上滑收起，点卡片或「看看」进「我们的友谊」。
 * 系统关了动画（动画时长缩放 = 0）时只淡入淡出、不撒彩纸。服务端没有这个功能时收不到事件，自然什么也不出。
 */

/** 一条待展示的庆祝：key 相同的新事件会顶掉旧的 */
private data class Celebration(
    val key: String,
    val friendId: Long,
    val title: String,
    val subtitle: String?,
    val badge: BadgeRef?,
    val level: Int,
    val duration: Long,
)

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
private fun JsonObject.int(name: String): Int = (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
private fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** 事件里带的好友名；没有就查亲密榜缓存，再没有叫「好友」 */
private fun friendName(data: JsonObject, friendId: Long): String =
    (data["friend"] as? JsonObject)?.str("display_name")?.takeIf { it.isNotBlank() }
        ?: IntimacyCache.bond(friendId)?.friend?.displayName?.takeIf { it.isNotBlank() }
        ?: "好友"

private fun parseCelebration(type: String, data: JsonObject): Celebration? {
    val friendId = data.long("friend_id") ?: return null
    val name = friendName(data, friendId)
    return when (type) {
        "intimacy.badge.unlocked" -> {
            val badges = (data["badges"] as? JsonArray).orEmpty().mapNotNull { runCatching { KcJson.decodeFromJsonElement(BadgeRef.serializer(), it) }.getOrNull() }
            // 一次解锁好几枚只出一条：写档次最高那枚和总数
            val first = badges.maxByOrNull { it.tier } ?: return null
            Celebration(
                key = "badge:$friendId:" + badges.joinToString(",") { "${it.series}.${it.level}" },
                friendId = friendId,
                title = if (badges.size > 1) "和 $name 解锁了「${first.name}」等 ${badges.size} 枚好友徽章" else "和 $name 解锁了好友徽章「${first.name}」",
                subtitle = first.seriesName.takeIf { it.isNotBlank() && it != first.name },
                badge = first,
                level = 0,
                duration = 7000,
            )
        }
        "intimacy.level.up" -> {
            val level = data.int("level")
            val levelName = data.str("level_name")
            Celebration(
                key = "level:$friendId",
                friendId = friendId,
                title = if (levelName.isNotBlank()) "和 $name 的友谊升到了「$levelName」" else "和 $name 的友谊升级了",
                subtitle = "友谊等级 Lv.$level",
                badge = null,
                level = level,
                duration = 6500,
            )
        }
        else -> null
    }
}

private fun reducesMotion(context: android.content.Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** 挂在 MainShell 最上层，盖在所有页面上面；一次只出一张，其余排队（最多排 4 张） */
@Composable
fun IntimacyCelebrationHost(graph: AppGraph) {
    val queue = remember { mutableStateListOf<Celebration>() }
    LaunchedEffect(graph) {
        graph.realtime.events.collect { event ->
            val item = parseCelebration(event.type, event.data) ?: return@collect
            val same = queue.indexOfFirst { it.key == item.key }
            when {
                same > 0 -> queue[same] = item
                same == 0 -> Unit // 正在展示同一条，不打断
                queue.size < 4 -> queue.add(item)
            }
        }
    }
    val current = queue.firstOrNull() ?: return
    Box(Modifier.fillMaxWidth()) {
        // key 保证换下一张时动画状态从头来
        androidx.compose.runtime.key(current.key) {
            CelebrationCard(current, onDone = { queue.remove(current) })
        }
    }
}

@Composable
private fun BoxScope.CelebrationCard(item: Celebration, onDone: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduced = remember { reducesMotion(context) }

    // enter：0 藏在屏幕上方，1 落定；drag：上滑时跟手的位移（px，≤ 0）
    val enter = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val halo = remember { Animatable(0f) }
    val leaving = remember { booleanArrayOf(false) }

    fun leave(then: () -> Unit = {}) {
        if (leaving[0]) return
        leaving[0] = true
        scope.launch {
            enter.animateTo(0f, tween(if (reduced) 120 else 220))
            onDone()
            then()
        }
    }

    LaunchedEffect(Unit) {
        Haptics.confirm(view)
        if (reduced) {
            enter.animateTo(1f, tween(160))
        } else {
            launch { enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f)) }
            launch { delay(120); burst.animateTo(1f, tween(1500, easing = LinearEasing)) }
            launch { delay(80); halo.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 260f)) }
        }
        delay(item.duration)
        leave()
    }

    val open = {
        leave { nav.go(IntimacyRoute(item.friendId)) }
    }
    val hidePx = with(density) { 140.dp.toPx() }
    val accent = colors.unread
    val press = rememberInteraction()

    Box(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val p = enter.value
                translationY = (1f - p) * -hidePx + drag.value
                alpha = if (reduced) p.coerceIn(0f, 1f) else (p * 1.6f).coerceIn(0f, 1f)
                val s = if (reduced) 1f else 0.94f + 0.06f * p
                scaleX = s
                scaleY = s
            }
            .pointerInput(Unit) {
                // 上滑收起；往下拉只给一点阻尼感
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag.value < -40.dp.toPx()) leave() else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    val next = drag.value + if (amount > 0f && drag.value >= 0f) amount * 0.2f else amount
                    scope.launch { drag.snapTo(next.coerceAtMost(24.dp.toPx())) }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressScale(press, 0.98f)
                .softShadow(blur = 22.dp, offsetY = 8.dp, color = Color.Black.copy(alpha = 0.18f), corner = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.raised)
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.13f), colors.warn.copy(alpha = 0.05f), Color.Transparent)))
                .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .clickable(interactionSource = press, indication = null, onClick = open)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左边：徽章 / 等级章，底下一圈光晕弹出来
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                val h = if (reduced) 1f else halo.value
                Box(
                    Modifier.matchParentSize().graphicsLayer { scaleX = 0.5f + 0.5f * h; scaleY = scaleX; alpha = (h * 0.9f).coerceIn(0f, 1f) }
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0f)))),
                )
                Box(Modifier.graphicsLayer { scaleX = 0.7f + 0.3f * h; scaleY = scaleX; rotationZ = (1f - h) * -18f }) {
                    if (item.badge != null) BadgeGlyph(icon = item.badge.icon, tier = item.badge.tier, size = 42.dp)
                    else LevelMedal(item.level)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    item.title,
                    style = type.secondary.copy(fontWeight = FontWeight.W700),
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let {
                    Text(it, style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(colors.fill)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = open)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("看看", style = type.caption.copy(fontWeight = FontWeight.W700), color = colors.onFill) }
        }
        // 彩纸：从左侧徽章处向外炸开，带一点重力，1.5 秒内落完淡出
        if (!reduced && burst.value in 0.001f..0.999f) {
            Confetti(item.key, burst.value, Modifier.matchParentSize())
        }
    }
}

/** 友谊等级章：粉→金的圆章，中间写等级数字 */
@Composable
private fun LevelMedal(level: Int) {
    val colors = KcTheme.colors
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.unread, colors.warn)))
            .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KcIcon(KcIcons.heartHandshake, tint = Color.White, size = 14.dp)
            Text("Lv.$level", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.W800, lineHeight = 12.sp)
        }
    }
}

private class Piece(val angle: Float, val speed: Float, val spin: Float, val size: Float, val round: Boolean, val color: Int, val delay: Float)

/** 轻量彩纸：22 片，随机种子取 key，同一条提示每次画法一致 */
@Composable
private fun Confetti(seed: String, t: Float, modifier: Modifier) {
    val colors = KcTheme.colors
    val palette = listOf(colors.unread, colors.warn, colors.accent, colors.ok, colors.star)
    val pieces = remember(seed) {
        val rnd = Random(seed.hashCode())
        List(22) {
            Piece(
                // 主要往右上、右边撒（卡片在屏幕顶上，往下撒会盖住内容）
                angle = (-150f + rnd.nextFloat() * 170f) * (PI.toFloat() / 180f),
                speed = 0.55f + rnd.nextFloat() * 0.6f,
                spin = (rnd.nextFloat() - 0.5f) * 720f,
                size = 4f + rnd.nextFloat() * 4f,
                round = rnd.nextFloat() < 0.35f,
                color = rnd.nextInt(5),
                delay = rnd.nextFloat() * 0.12f,
            )
        }
    }
    Canvas(modifier) {
        val origin = Offset(12.dp.toPx() + 26.dp.toPx(), size.height / 2f)
        val reach = 150.dp.toPx()
        val gravity = 120.dp.toPx()
        pieces.forEach { p ->
            val local = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            // 先快后慢地飞出，再被重力往下拽
            val out = 1f - (1f - local) * (1f - local)
            val x = origin.x + cos(p.angle) * reach * p.speed * out
            val y = origin.y + sin(p.angle) * reach * p.speed * out + gravity * local * local
            val alpha = if (local < 0.7f) 1f else (1f - (local - 0.7f) / 0.3f)
            val color = palette[p.color].copy(alpha = alpha.coerceIn(0f, 1f))
            val s = p.size.dp.toPx()
            if (p.round) {
                drawCircle(color, radius = s * 0.45f, center = Offset(x, y))
            } else {
                rotate(p.spin * local, pivot = Offset(x, y)) {
                    drawRoundRect(color, topLeft = Offset(x - s / 2f, y - s * 0.3f), size = Size(s, s * 0.6f), cornerRadius = CornerRadius(s * 0.15f))
                }
            }
        }
    }
}
