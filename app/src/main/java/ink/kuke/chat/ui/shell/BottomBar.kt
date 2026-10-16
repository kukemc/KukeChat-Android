package ink.kuke.chat.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.components.UnreadBadge
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.theme.KcTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/** 底栏上的一项：聊天、空间固定在两头，中间两项来自「底栏与功能排序」 */
class BarItem(val id: String, val label: String, val icon: () -> ImageVector)

private val BarHeight = 52.dp

/**
 * 底栏在屏幕底部占掉的高度（细线 + 栏高 + 手势条）。底栏叠在页面上面，一级页面的列表用它在底部留空，
 * 最后一条才不会被挡住；浮动按钮也要往上抬这么多。不在一级页面里时是 0。
 */
val LocalBottomBarSpace = staticCompositionLocalOf { 0.dp }

/** 底栏占掉的高度：和 KcBottomBar 里的布局一致 */
@Composable
fun bottomBarSpace(): Dp {
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return BarHeight + navBottom + 0.5.dp
}

/**
 * 底栏：和页面同一个底色（很浅的毛玻璃，传了 [haze] 才有）、平铺到屏幕两边，上沿一根细线和列表分开——和聊天列表一样是平的，
 * 靠近屏幕圆角的地方没有任何方块形状，不会和手机的圆角打架。
 * 选中项用 App 的强调色（双色实心图标 + 强调色加粗文字），其余灰色；切换时图标轻轻弹一下。
 * 底栏叠在页面上面（外壳用 Box 叠放），一级页面按 LocalBottomBarSpace 在底部留空。
 */
@Composable
fun KcBottomBar(items: List<BarItem>, current: String, badges: Map<String, Int>, dots: Set<String>, haze: HazeState? = null, onSelect: (String) -> Unit) {
    val colors = KcTheme.colors
    // 很浅的毛玻璃：背后的列表模糊后透出一点颜色，叠 76% 的列表底色，所以静止时和页面几乎一样，滚动时才看得出细节
    val surface = if (haze != null) {
        Modifier.hazeChild(haze, HazeStyle(backgroundColor = colors.list, tint = HazeTint(colors.list.copy(alpha = 0.76f)), blurRadius = 22.dp, noiseFactor = 0f))
    } else {
        Modifier.background(colors.list)
    }
    Column(Modifier.fillMaxWidth().then(surface)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(BarHeight)
                .padding(horizontal = 8.dp),
        ) {
            items.forEach { tab ->
                val selected = tab.id == current
                val tint by animateColorAsState(if (selected) colors.accent else colors.muted, tween(200), label = "tint")
                val lift by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 650f), label = "lift")
                val interaction = rememberInteraction()
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(tab.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    // 图标和文字整体往下 4dp：上沿有细线，视觉重心正中会显得偏高
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 4.dp)) {
                        Box(Modifier.graphicsLayer { scaleX = 1f + 0.08f * lift; scaleY = scaleX }) {
                            KcIcon(if (selected) tab.icon().duotone() else tab.icon(), tint = tint, size = 23.dp)
                            val count = badges[tab.id] ?: 0
                            if (count > 0) {
                                UnreadBadge(count, modifier = Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-5).dp))
                            } else if (tab.id in dots) {
                                Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).width(7.dp).height(7.dp).background(colors.unread, RoundedCornerShape(4.dp)))
                            }
                        }
                        Text(
                            tab.label,
                            color = tint,
                            fontSize = 10.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
