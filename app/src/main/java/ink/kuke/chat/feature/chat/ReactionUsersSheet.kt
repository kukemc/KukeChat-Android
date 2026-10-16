package ink.kuke.chat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ink.kuke.chat.core.model.Reaction
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 长按一个表情回应：底部面板列出都有谁回应了（后端的 reactions[].users，见 MessageReactionSummary）。
 * 顶上一排页签按表情切换，进来时选中长按的那个。放在 Dialog 里，这样从消息行内部就能盖住整个屏幕。
 */
@Composable
internal fun ReactionUsersSheet(reactions: List<Reaction>, initialEmoji: String, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    var selected by remember { mutableStateOf(initialEmoji) }
    val current = reactions.firstOrNull { it.emoji == selected } ?: reactions.firstOrNull() ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        KcSheet(onDismiss = onDismiss, title = "表情回应", maxHeight = 520.dp) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                reactions.forEach { reaction ->
                    val active = reaction.emoji == current.emoji
                    Text(
                        "${reaction.emoji} ${reaction.count}",
                        style = KcTheme.type.body,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) colors.text else colors.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (active) colors.fill.copy(alpha = 0.08f) else colors.panel)
                            .border(1.dp, if (active) colors.borderStrong else colors.border, RoundedCornerShape(999.dp))
                            .clickable { selected = reaction.emoji }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val users = current.users
            if (users.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("${current.count} 人回应了 ${current.emoji}", style = KcTheme.type.body, color = colors.muted)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(users, key = { it.id }) { user ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(user.avatarUrl, user.displayName, tone = user.id, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(user.displayName, style = KcTheme.type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                    // 后端只带了一部分人时（count 比名单多），把差额说出来
                    if (current.count > users.size) {
                        item {
                            Text("还有 ${current.count - users.size} 人", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
