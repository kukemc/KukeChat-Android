package ink.kuke.chat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.groupfiles.FileDownload
import ink.kuke.chat.feature.groupfiles.FileDownloads
import ink.kuke.chat.feature.groupfiles.KcProgressBar
import ink.kuke.chat.feature.groupfiles.fileIcon
import ink.kuke.chat.feature.groupfiles.isVideoFile
import ink.kuke.chat.feature.groupfiles.ratio
import ink.kuke.chat.feature.groupplus.formatBytes
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
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

/** 群文件消息的卡片：图标 · 文件名 · 大小 · 有效期。点开走下载面板（视频直接进播放页）；在下载时底部画进度条 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageFileCard(meta: FileMeta, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val expired = meta.isExpired()
    val video = isVideoFile(meta.name, meta.contentType)
    LaunchedEffect(meta.id) { FileDownloads.refresh(context, meta.id) }
    val download = FileDownloads.states[meta.id]
    Column(
        Modifier
            .widthIn(min = 220.dp, max = 270.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .background(colors.panel, RoundedCornerShape(12.dp))
            .border(1.dp, colors.accent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onLongClick = LocalMessageLongPress.current, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(if (video && !expired) colors.fill else colors.accentSoft), contentAlignment = Alignment.Center) {
                if (video && !expired) {
                    KcIcon(KcIcons.play, tint = colors.onFill, size = 20.dp)
                } else {
                    KcIcon(fileIcon(meta.name, meta.contentType), tint = if (expired) colors.subtle else colors.accent, size = 22.dp)
                }
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(meta.name, style = type.rowTitle, color = if (expired) colors.subtle else colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    formatBytes(meta.size) + " · " + when {
                        expired -> "已过期"
                        download is FileDownload.Running -> download.ratio(meta.size)?.let { "下载中 ${(it * 100).toInt()}%" } ?: "下载中"
                        download is FileDownload.Done -> "已下载"
                        meta.permanent -> "永久"
                        else -> "限时保留"
                    },
                    style = type.caption, color = if (expired) colors.warn else colors.muted,
                )
            }
        }
        if (download is FileDownload.Running && !expired) {
            KcProgressBar(download.ratio(meta.size), Modifier.padding(top = 10.dp), height = 3.dp)
        }
    }
}

/**
 * 发送中的文件：和文件消息同一张卡片（图标 · 文件名 · 大小），底部画上传进度，右侧 × 取消（取消上传、卡片消失）。
 * 失败时写明原因，下面「重试」「删除」两个胶囊。传完由服务端落文件消息，这张卡片原地换成它。
 */
@Composable
internal fun PendingFileCard(file: OutgoingFile, failed: Boolean, error: String?, onCancel: () -> Unit, onRetry: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val picked = file.picked
    val progress = file.progress
    val status = when {
        failed -> "失败"
        progress != null && progress >= 1f -> "处理中…"
        progress != null && file.sent > 0 -> "上传中 ${(progress * 100).toInt()}%"
        else -> "上传中"
    }
    Column(
        Modifier
            .widthIn(min = 220.dp, max = 270.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .background(colors.panel, RoundedCornerShape(12.dp))
            .border(1.dp, (if (failed) colors.danger else colors.accent).copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                KcIcon(fileIcon(picked.name, picked.mime), tint = if (failed) colors.subtle else colors.accent, size = 22.dp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(picked.name, style = type.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (picked.size >= 0) formatBytes(picked.size) else "未知大小") + " · " + status,
                    style = type.caption, color = if (failed) colors.danger else colors.muted,
                )
            }
            if (!failed) {
                val press = rememberInteraction()
                Box(
                    Modifier.padding(start = 8.dp).size(26.dp).pressScale(press, 0.9f).clip(CircleShape).background(colors.panelMuted)
                        .clickable(interactionSource = press, indication = null, onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) { KcIcon(KcIcons.close, tint = colors.muted, size = 14.dp, contentDescription = "取消发送") }
            }
        }
        if (failed) {
            error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileCapsule("重试", ink = true, onClick = onRetry)
                FileCapsule("删除", ink = false, onClick = onCancel)
            }
        } else {
            KcProgressBar(progress?.takeIf { file.sent > 0 }, Modifier.padding(top = 10.dp), height = 3.dp)
        }
    }
}

/** 卡片里的小胶囊按钮：ink = 墨色实心（主操作），否则浅灰底 */
@Composable
private fun FileCapsule(label: String, ink: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Box(
        Modifier.height(30.dp).pressScale(press).clip(CircleShape).background(if (ink) colors.text else colors.panelMuted)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = KcTheme.type.caption, color = if (ink) colors.panel else colors.text) }
}
