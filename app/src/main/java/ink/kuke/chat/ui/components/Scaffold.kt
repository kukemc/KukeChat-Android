package ink.kuke.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 二级页面的外壳：56dp 顶栏（返回 · 标题 · 右侧操作）+ 发丝线 + 内容。底色是 chat 层。
 * 所有二级页面都用它，保证顶栏高度、返回按钮、标题字号一致（设计稿「一级页面大标题，二级页面 17sp 标题」）。
 */
@Composable
fun KcPage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    val colors = KcTheme.colors
    Column(modifier.fillMaxSize().background(colors.chat)) {
        Row(Modifier.fillMaxWidth().background(colors.panel).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val press = rememberInteraction()
            Box(
                Modifier.size(44.dp).pressScale(press, 0.9f).clip(CircleShape).clickable(interactionSource = press, indication = null, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.chevronLeft, tint = colors.text, size = 24.dp, contentDescription = "返回") }
            // 设计稿 .ab-t：标题下可以带一行 12sp 的灰色副标题
            Column(Modifier.weight(1f)) {
                Text(title, style = KcTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** 一级页面（底栏页）的大标题行 */
@Composable
fun KcLargeTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KcTheme.type.display, color = KcTheme.colors.text, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun KcEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, modifier = Modifier.padding(32.dp))
    }
}
