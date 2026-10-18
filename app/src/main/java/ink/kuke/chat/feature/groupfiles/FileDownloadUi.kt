package ink.kuke.chat.feature.groupfiles

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.feature.chat.SheetRow
import ink.kuke.chat.feature.groupplus.formatBytes
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

/** 细进度条：ratio 为 null 时画来回走的不确定进度 */
@Composable
fun KcProgressBar(ratio: Float?, modifier: Modifier = Modifier, height: Dp = 4.dp, track: Color = KcTheme.colors.panelMuted, bar: Color = KcTheme.colors.fill) {
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        if (ratio != null) {
            val shown by animateFloatAsState(ratio.coerceIn(0f, 1f), tween(300), label = "progress")
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceAtLeast(0.02f)).clip(RoundedCornerShape(height / 2)).background(bar))
        } else {
            val transition = rememberInfiniteTransition(label = "indeterminate")
            val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
            val segment = maxWidth * 0.32f
            Box(Modifier.fillMaxHeight().width(segment).offset(x = (maxWidth + segment) * phase - segment).clip(RoundedCornerShape(height / 2)).background(bar))
        }
    }
}

/** 面板里的下载状态：进度条 + 「已下载 x / y · 42%」；下完显示保存位置 */
@Composable
fun FileDownloadStatus(fileId: Long, size: Long, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    when (val state = FileDownloads.states[fileId]) {
        is FileDownload.Running -> Column(modifier) {
            val ratio = state.ratio(size)
            KcProgressBar(ratio, height = 6.dp)
            val total = if (state.total > 0) state.total else size
            val amount = if (total > 0) "${formatBytes(state.done)} / ${formatBytes(total)}" else formatBytes(state.done)
            val text = when {
                state.paused -> "等待网络… $amount"
                ratio != null -> "正在下载 ${(ratio * 100).toInt()}% · $amount"
                else -> "正在下载 · $amount"
            }
            Text(text, style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
        }
        is FileDownload.Done -> Text("已保存到 下载/KukeChat", style = type.caption, color = colors.ok, modifier = modifier)
        is FileDownload.Failed -> Text("上次下载失败，可以重新下载", style = type.caption, color = colors.warn, modifier = modifier)
        null -> Unit
    }
}

/**
 * 文件面板里的下载相关几行：播放（视频）/ 打开 / 保存到下载。
 * 下载交给系统 DownloadManager：点完面板就能关，下载不受影响。
 */
@Composable
fun FileDownloadRows(graph: AppGraph, conversationId: Long, fileId: Long, name: String, size: Long, contentType: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(fileId) { FileDownloads.refresh(context, fileId) }
    val state = FileDownloads.states[fileId]
    FileDownloadStatus(fileId, size, Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 10.dp))
    if (isVideoFile(name, contentType)) {
        SheetRow("播放", {
            VideoPlayerActivity.open(context, conversationId, fileId, name, size)
            onDismiss()
        }, icon = KcIcons.play)
    }
    SheetRow(
        "打开",
        {
            val done = state as? FileDownload.Done
            if (done != null) {
                FileDownloads.open(context, done, name)
                onDismiss()
            } else {
                // 没下过：先在后台下，下完自动打开；面板留着看进度，随时可以关
                FileDownloads.download(graph, context, conversationId, fileId, name, size, openAfter = true)
            }
        },
        icon = KcIcons.external,
        detail = if (state is FileDownload.Running) "下完自动打开" else null,
    )
    SheetRow(
        "保存到下载",
        { FileDownloads.download(graph, context, conversationId, fileId, name, size, openAfter = false) },
        icon = KcIcons.download,
        detail = when (state) {
            is FileDownload.Done -> "已保存"
            is FileDownload.Running -> "下载中"
            else -> null
        },
    )
}
