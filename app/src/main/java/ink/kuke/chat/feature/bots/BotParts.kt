package ink.kuke.chat.feature.bots

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.ChoiceDialog
import ink.kuke.chat.feature.tasks.DialogCard
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.icons.solid
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 在线用绿点，停用 / 离线是灰点；叠在头像右下角 */
@Composable
internal fun BotAvatar(bot: Bot, size: Dp, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    Box(modifier.size(size)) {
        Avatar(bot.avatarUrl ?: bot.user?.avatarUrl, bot.name, bot.accountId, size = size)
        val dot = size * 0.26f
        Box(
            Modifier.align(Alignment.BottomEnd).size(dot).clip(CircleShape)
                .background(if (!bot.disabled && bot.online) colors.ok else colors.subtle)
                .border(2.dp, colors.panel, CircleShape),
        )
    }
}

/** 五颗星，支持半星取整显示（评分 4.8 显示 5 颗亮星里的 4.8 / 5 填充） */
@Composable
internal fun Stars(value: Double, size: Dp = 14.dp) {
    val colors = KcTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 1..5) {
            val filled = value >= index - 0.25
            KcIcon(if (filled) KcIcons.star.solid() else KcIcons.star, tint = if (filled) colors.star else colors.subtle, size = size)
        }
    }
}

/** 把机器人加进一个群：只列自己是群主 / 管理员的群，和电脑版一致 */
@Composable
internal fun InstallToGroupDialog(graph: AppGraph, bot: Bot, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val conversations by graph.conversations.items.collectAsState()
    val groups = remember(conversations) { conversations.filter { it.isGroup && (it.myRole == "owner" || it.myRole == "admin") } }
    val api = remember { BotApi(graph.api) }
    var busy by remember { mutableStateOf(false) }
    if (groups.isEmpty()) {
        DialogCard(onDismiss, "添加到群聊") {
            Text("你还没有管理的群聊。只有群主和管理员可以把机器人加进群。", style = KcTheme.type.secondary, color = colors.muted)
            SoftButton("知道了", onDismiss, Modifier.fillMaxWidth())
        }
        return
    }
    ChoiceDialog<Long>(
        title = if (busy) "正在添加…" else "添加「${bot.name}」到哪个群",
        options = groups.map { it.id to it.name() },
        selected = null,
        onPick = { id ->
            if (busy) return@ChoiceDialog
            busy = true
            scope.launch {
                runCatching { api.install(bot.id, id) }
                    .onSuccess { Haptics.confirm(view); onDone("已添加到「${groups.firstOrNull { it.id == id }?.name().orEmpty()}」") }
                    .onFailure { Haptics.reject(view); onDone(it.userMessage()) }
                busy = false
            }
        },
        onDismiss = onDismiss,
    )
}



/** Key 只显示这一次：提醒复制，关闭后再也看不到 */
@Composable
internal fun KeyDialog(key: String, title: String, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    DialogCard(onDismiss, title) {
        Text("这是完整的机器人 Key，只会显示这一次。请立刻复制并妥善保存，不要放进公开仓库或网页源码。", style = KcTheme.type.secondary, color = colors.muted)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(12.dp)) {
            Text(key, style = KcTheme.type.body.copy(fontSize = 13.sp), color = colors.text)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(if (copied) "已复制" else "复制 Key", {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("bot-key", key))
                copied = true
            }, Modifier.weight(1f))
            PrimaryButton("我已保存", onDismiss, Modifier.weight(1f))
        }
    }
}

internal val BotWarm = Color(0xFFF59F00)
