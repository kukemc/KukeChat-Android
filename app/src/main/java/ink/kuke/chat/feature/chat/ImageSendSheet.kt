package ink.kuke.chat.feature.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ink.kuke.chat.feature.groupplus.PlusButton
import ink.kuke.chat.feature.groupplus.plusInk
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 发图前的确认面板：选好的图排成 4 列缩略图（右上角 × 去掉），可以继续添加（最多 9 张），一行可选的说明文字，
 * 底部「取消」「发送（N）」墨色胶囊（同群升级面板）。多张合成一条消息发，说明文字和图在同一条里。
 */
@Composable
internal fun ImageSendSheet(
    images: List<Uri>,
    caption: String,
    onCaption: (String) -> Unit,
    onRemove: (Uri) -> Unit,
    /** null = 已经 9 张，不再显示「继续添加」 */
    onAdd: (() -> Unit)?,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val k = plusInk()
    val type = KcTheme.type
    KcSheet(onDismiss = onCancel, maxHeight = 640.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发送图片", style = type.title, color = k.ink)
                Spacer(Modifier.weight(1f))
                Text("${images.size} / ${ChatController.MAX_IMAGES}", style = type.caption.copy(fontFeatureSettings = "tnum"), color = k.faint)
            }
            val tiles: List<Uri?> = images + if (onAdd != null) listOf(null) else emptyList()
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { uri ->
                            Box(Modifier.weight(1f).aspectRatio(1f)) {
                                if (uri != null) Thumb(uri, onRemove = { onRemove(uri) }) else AddTile(onAdd ?: {})
                            }
                        }
                        repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            KcTextField(
                value = caption,
                onChange = onCaption,
                placeholder = "添加说明（可选）",
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlusButton("取消", onClick = onCancel, primary = false, modifier = Modifier.weight(1f))
                PlusButton("发送（${images.size}）", onClick = onSend, enabled = images.isNotEmpty(), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Thumb(uri: Uri, onRemove: () -> Unit) {
    val k = plusInk()
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxSize().clip(shape).background(k.wash).border(0.5.dp, k.line, shape)) {
        AsyncImage(model = uri.toString(), contentDescription = "已选图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // 右上角 ×：点按区域 30dp，画出来的小圆 20dp
        Box(
            Modifier.align(Alignment.TopEnd).size(30.dp).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.52f)), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.close, tint = Color.White, size = 12.dp, contentDescription = "去掉这张")
            }
        }
    }
}

@Composable
private fun AddTile(onAdd: () -> Unit) {
    val k = plusInk()
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).border(1.dp, k.lineStrong, shape).clickable(onClick = onAdd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KcIcon(KcIcons.plus, tint = k.sub, size = 20.dp)
        Text("继续添加", color = k.sub, fontSize = 11.5.sp, fontWeight = FontWeight.W500, modifier = Modifier.padding(top = 4.dp))
    }
}
