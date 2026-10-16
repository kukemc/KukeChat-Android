package ink.kuke.chat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class NoticeTone { Info, Success, Warning }

class NoticeAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * 一条顶部胶囊提示。[key] 相同的后来者原地替换前一条（连着来只留最后一条、同一件事改写结果）。
 * [leading] 左边的图形；[hero] 有值时画成大号庆祝卡（升级换出月亮 / 太阳 / 皇冠），点卡片走 [onClick]。
 */
class Notice(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val tone: NoticeTone = NoticeTone.Info,
    val icon: ImageVector? = null,
    val leading: (@Composable () -> Unit)? = null,
    val hero: (@Composable () -> Unit)? = null,
    val actions: List<NoticeAction> = emptyList(),
    val durationMs: Long = 5000,
    val onClick: (() -> Unit)? = null,
) {
    internal val serial = nextSerial++
    internal var leaving by mutableStateOf(false)

    private companion object { var nextSerial = 1L }
}

/** 全局提示队列：任何地方 Notices.show(...)，MainShell 里的 [NoticeHost] 负责画 */
object Notices {
    internal val items = mutableStateListOf<Notice>()

    fun show(notice: Notice) {
        val index = items.indexOfFirst { it.key == notice.key }
        if (index >= 0) items[index] = notice else items.add(0, notice)
        // 最多留 4 条，再多最旧的直接走
        while (items.size > 4) items.removeAt(items.lastIndex)
    }

    fun dismiss(key: String) {
        items.firstOrNull { it.key == key }?.leaving = true
    }

    internal fun remove(notice: Notice) {
        items.remove(notice)
    }

    fun clear() = items.clear()
}

private const val MAX_VISIBLE = 3

/**
 * 顶部胶囊提示的宿主（整个应用一个，盖在 NavHost 上）。新的压在最上面，旧的缩小叠在后面露一条边；
 * 上滑甩掉，到时间自己收起（按住时不计时）；出入场用弹簧，系统关了动画时直接出现 / 消失。
 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val items = Notices.items
    if (items.isEmpty()) return
    val reduced = rememberReducedMotion()
    val heights = remember { mutableStateMapOf<String, Int>() }
    val frontHeight = items.firstOrNull()?.let { heights[it.key] } ?: 0
    Box(modifier.fillMaxSize().statusBarsPadding().padding(top = 6.dp, start = 12.dp, end = 12.dp), contentAlignment = Alignment.TopCenter) {
        items.forEachIndexed { index, notice ->
            if (index >= MAX_VISIBLE) return@forEachIndexed
            // 按 key 保留卡片：同一件事改写内容时不重新入场
            androidx.compose.runtime.key(notice.key) {
                NoticeCard(
                    current = notice,
                    depth = index,
                    frontHeight = frontHeight,
                    reduced = reduced,
                    onMeasured = { heights[notice.key] = it },
                    modifier = Modifier.zIndex((MAX_VISIBLE - index).toFloat()),
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(current: Notice, depth: Int, frontHeight: Int, reduced: Boolean, onMeasured: (Int) -> Unit, modifier: Modifier) {
    val colors = KcTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(current)
    val notice = current
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    val drag = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(0) }
    var holding by remember { mutableStateOf(false) }
    val depthT by animateFloatAsState(depth.toFloat(), if (reduced) tween(0) else spring(dampingRatio = 0.78f, stiffness = 420f), label = "noticeDepth")

    LaunchedEffect(Unit) { if (!reduced) appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 380f)) }
    // 到点收起：只有压在最上面的那条计时，按住不计
    LaunchedEffect(notice.serial, depth, holding) {
        if (depth != 0 || holding) return@LaunchedEffect
        delay(notice.durationMs)
        notice.leaving = true
    }
    LaunchedEffect(notice, notice.leaving) {
        if (!notice.leaving) return@LaunchedEffect
        if (!reduced) appear.animateTo(0f, tween(220))
        Notices.remove(notice)
    }

    val lift = with(density) { 10.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .widthIn(max = 440.dp)
            .onSizeChanged { height = it.height; onMeasured(it.height) }
            .graphicsLayer {
                val a = appear.value
                // 叠在后面的：底边对齐前一张往下露 8dp，越往后越小越淡
                val behind = if (depthT > 0f && frontHeight > 0) (frontHeight - height).toFloat() * depthT.coerceAtMost(1f) else 0f
                translationY = behind + depthT * 8.dp.toPx() + drag.value - (1f - a) * (height + lift)
                val s = 1f - 0.05f * depthT
                scaleX = s; scaleY = s
                alpha = (a.coerceIn(0f, 1f)) * (1f - 0.22f * depthT).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .pointerInput(notice.key, depth) {
                if (depth != 0) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { holding = true },
                    onDragEnd = {
                        holding = false
                        val dismiss = drag.value < -height * 0.3f
                        scope.launch {
                            if (dismiss) {
                                drag.animateTo(-(height + lift) * 1.2f, tween(160))
                                Notices.remove(latest.value)
                            } else drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 600f))
                        }
                    },
                    onDragCancel = { holding = false; scope.launch { drag.animateTo(0f, spring()) } },
                ) { change, amount ->
                    change.consume()
                    // 往下拉有阻尼，往上跟手
                    val next = drag.value + if (drag.value + amount > 0f) amount * 0.25f else amount
                    scope.launch { drag.snapTo(next) }
                }
            },
    ) {
        val shape = RoundedCornerShape(if (notice.hero != null) 24.dp else 22.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .softShadow(blur = 18.dp, offsetY = 6.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.4f else 0.14f), corner = 22.dp)
                .clip(shape)
                .background(colors.raised)
                .then(if (notice.onClick != null && depth == 0) Modifier.clickable { notice.leaving = true; notice.onClick.invoke() } else Modifier)
                .padding(horizontal = 14.dp, vertical = if (notice.hero != null) 14.dp else 10.dp),
        ) {
            notice.hero?.let {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { it() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val leading = notice.leading
                if (leading != null) {
                    Box(Modifier.padding(end = 10.dp)) { leading() }
                } else if (notice.icon != null) {
                    val tint = toneColor(notice.tone)
                    Box(Modifier.padding(end = 10.dp).size(32.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        KcIcon(notice.icon, tint = tint, size = 18.dp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(notice.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.W700, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    notice.subtitle?.let { Text(it, style = KcTheme.type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (notice.hero != null) {
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { notice.leaving = true }, contentAlignment = Alignment.Center) {
                        KcIcon(KcIcons.close, tint = colors.subtle, size = 14.dp, contentDescription = "关闭提示")
                    }
                }
            }
            if (notice.actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    notice.actions.forEachIndexed { i, action ->
                        val primary = i == 0
                        Text(
                            action.label,
                            color = if (primary) colors.onFill else colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.W600,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (primary) colors.fill.copy(alpha = if (action.enabled) 1f else 0.5f) else colors.panelMuted)
                                .clickable(enabled = action.enabled && depth == 0) { action.onClick() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun toneColor(tone: NoticeTone): Color = when (tone) {
    NoticeTone.Info -> KcTheme.colors.accent
    NoticeTone.Success -> KcTheme.colors.ok
    NoticeTone.Warning -> KcTheme.colors.warn
}
