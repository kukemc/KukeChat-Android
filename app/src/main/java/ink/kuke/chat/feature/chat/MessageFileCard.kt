package ink.kuke.chat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.groupfiles.fileIcon
import ink.kuke.chat.feature.groupplus.formatBytes
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.theme.KcTheme
import java.time.Instant

/** 文件消息的 metadata.file（docs/group-plus.md 5.2）。用卡片里的 expires_at 自己判断过期，不等服务端事件 */
class FileMeta(val id: Long, val name: String, val size: Long, val contentType: String?, val expiresAt: String?, val permanent: Boolean) {
    fun isExpired(now: Instant = Instant.now()): Boolean {
        if (permanent || expiresAt == null) return false
        val until = parseInstant(expiresAt) ?: return false
        return !until.isAfter(now)
    }
}

/**
 * 文件消息里的 metadata.file 是上传那一刻的快照：之后被转成永久（或转回限时）卡片并不知道，
 * 24 小时一过就会错误地显示「已过期」。这里放群文件列表里查到的最新状态（permanent / expires_at），覆盖快照。
 * 只存 mutableStateMap：卡片读它，更新后自动重组。
 */
object LiveFiles {
    class State(val permanent: Boolean, val expiresAt: String?)

    val states = mutableStateMapOf<Long, State>()

    fun apply(items: List<ink.kuke.chat.feature.groupplus.GroupFile>) {
        items.forEach { states[it.id] = State(it.permanent, it.expiresAt) }
    }
}

fun fileMetaOf(message: Message): FileMeta? {
    val file = message.metadata?.o("file") ?: return null
    val id = file.l("id") ?: return null
    val live = LiveFiles.states[id]
    return FileMeta(
        id, file.s("name") ?: "文件", file.l("size") ?: 0L, file.s("content_type"),
        if (live != null) live.expiresAt else file.s("expires_at"),
        if (live != null) live.permanent else file.b("permanent") == true,
    )
}

/** 群文件消息的卡片：图标 · 文件名 · 大小 · 有效期。点开走下载面板 */
@Composable
internal fun MessageFileCard(meta: FileMeta, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val expired = meta.isExpired()
    Row(
        Modifier
            .widthIn(min = 220.dp, max = 270.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .background(colors.panel, RoundedCornerShape(12.dp))
            .border(1.dp, colors.accent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            KcIcon(fileIcon(meta.name, meta.contentType), tint = if (expired) colors.subtle else colors.accent, size = 22.dp)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(meta.name, style = type.rowTitle, color = if (expired) colors.subtle else colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatBytes(meta.size) + " · " + when {
                    expired -> "已过期"
                    meta.permanent -> "永久"
                    else -> "限时保留"
                },
                style = type.caption, color = if (expired) colors.warn else colors.muted,
            )
        }
    }
}
