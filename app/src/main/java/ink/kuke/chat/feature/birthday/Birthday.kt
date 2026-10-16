package ink.kuke.chat.feature.birthday

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.ui.components.rememberReducedMotion
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 名字旁的小蛋糕：今天是这个人的生日（payload 的 is_birthday_today） */
@Composable
fun BirthdayMark(show: Boolean, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    if (!show) return
    KcIcon(KcIcons.cake, tint = Color(0xFFE8618C), size = size, contentDescription = "今天生日", modifier = modifier.padding(start = 4.dp))
}

private const val SCENE_MS = 4200L
private const val PREFS = "birthday"

/** 这一天（北京时间）这台设备播过没有：服务端标记失败时也不重播 */
private fun todayKey(userId: Long): String = "$userId:${LocalDate.now(ZoneId.of("Asia/Shanghai"))}"

private fun playedLocally(context: Context, key: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("played", null) == key
private fun rememberPlayed(context: Context, key: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("played", key).apply()

/**
 * 庆生（同客户端 BirthdayCelebration）：进主界面时 GET /users/me/birthday 说 celebrate_today，就全屏播一段蛋糕 + 彩带 +「生日快乐」，
 * 点一下跳过、几秒后自己收场；减少动态效果时换成静态贺卡，点「收下祝福」关。结束后 POST /users/me/birthday/celebrated。
 * 旧后端没有生日接口（404）时什么都不做。整个应用挂一个（MainShell）。
 */
@Composable
fun BirthdayCelebration(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    var showing by remember { mutableStateOf(false) }
    val user = graph.session.session.collectAsStateWithLifecycle().value?.user ?: return

    LaunchedEffect(user.id) {
        val info = runCatching { graph.api.get("/users/me/birthday") as? JsonObject }.getOrNull() ?: return@LaunchedEffect
        if ((info["celebrate_today"] as? JsonPrimitive)?.booleanOrNull != true) return@LaunchedEffect
        val key = todayKey(user.id)
        if (playedLocally(context, key)) {
            // 播过了只是服务端不知道：补一次标记
            runCatching { graph.api.post("/users/me/birthday/celebrated") }
            return@LaunchedEffect
        }
        rememberPlayed(context, key)
        showing = true
    }
    if (!showing) return

    val close: () -> Unit = {
        if (showing) {
            showing = false
            scope.launch { runCatching { graph.api.post("/users/me/birthday/celebrated") } }
        }
    }
    LaunchedEffect(Unit) {
        if (!reduced) {
            delay(SCENE_MS)
            close()
        }
    }
    BackHandler(onBack = close)
    BirthdayScene(user.displayName, still = reduced, onClose = close)
}

private class Confetti(val x: Float, val delay: Float, val speed: Float, val drift: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

private val ConfettiColors = listOf(Color(0xFFFF6B9A), Color(0xFFFFC94D), Color(0xFF5CC8FF), Color(0xFF8C7BFF), Color(0xFF4FD1A5), Color(0xFFFF8A4C))

@Composable
private fun BirthdayScene(name: String, still: Boolean, onClose: () -> Unit) {
    val colors = KcTheme.colors
    val enter = remember { Animatable(if (still) 1f else 0f) }
    val fall = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        launch { enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 260f)) }
        fall.animateTo(1f, tween(SCENE_MS.toInt(), easing = LinearEasing))
    }
    val pieces = remember {
        val random = Random(7)
        List(54) {
            Confetti(
                x = random.nextFloat(), delay = random.nextFloat() * 0.35f, speed = 0.75f + random.nextFloat() * 0.6f,
                drift = (random.nextFloat() - 0.5f) * 0.18f, spin = (random.nextFloat() - 0.5f) * 900f,
                color = ConfettiColors[it % ConfettiColors.size], w = 5f + random.nextFloat() * 4f, h = 9f + random.nextFloat() * 6f,
            )
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.55f * enter.value.coerceIn(0f, 1f)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !still, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        if (!still) {
            Canvas(Modifier.fillMaxSize()) {
                val t = fall.value
                pieces.forEach { p ->
                    val local = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
                    if (local <= 0f) return@forEach
                    val y = -20.dp.toPx() + (size.height + 40.dp.toPx()) * local * p.speed
                    val x = size.width * (p.x + p.drift * sin(local * 6f))
                    val alpha = if (local > 0.85f) (1f - local) / 0.15f else 1f
                    rotate(p.spin * local, Offset(x, y)) {
                        drawRect(p.color.copy(alpha = alpha), Offset(x - p.w / 2, y - p.h / 2), Size(p.w.dp.toPx() / 2.2f, p.h.dp.toPx() / 2.2f))
                    }
                }
            }
        }
        val e = enter.value
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .padding(horizontal = 28.dp)
                .graphicsLayer { scaleX = 0.82f + 0.18f * e; scaleY = scaleX; alpha = e.coerceIn(0f, 1f) }
                .background(colors.panel, RoundedCornerShape(26.dp))
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                // 蛋糕后面一圈柔光
                Canvas(Modifier.size(96.dp)) {
                    val glow = Color(0xFFFFB7CB)
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent)))
                    if (!still) for (i in 0 until 8) {
                        val a = Math.toRadians((i * 45.0) + fall.value * 90.0)
                        val r = size.minDimension * 0.44f
                        drawCircle(Color(0xFFFFC94D).copy(alpha = 0.8f), radius = 2.5.dp.toPx(), center = Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat()))
                    }
                }
                Box(Modifier.size(64.dp).background(Color(0xFFFFE3EC), CircleShape), contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.cake, tint = Color(0xFFE8618C), size = 36.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("生日快乐", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.W800)
            Spacer(Modifier.height(4.dp))
            Text(name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.W600, maxLines = 1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("愿新的一岁，所想皆如愿。", style = KcTheme.type.secondary, color = colors.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            if (still) {
                Box(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(colors.fill).clickable(onClick = onClose).padding(horizontal = 28.dp, vertical = 11.dp),
                ) { Text("收下祝福", color = colors.onFill, fontSize = 15.sp, fontWeight = FontWeight.W700) }
            } else {
                Text("点一下跳过", style = KcTheme.type.caption, color = colors.subtle)
            }
        }
    }
}
