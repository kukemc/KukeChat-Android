package ink.kuke.chat.feature.jury

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.feature.chat.VoicePlayer
import ink.kuke.chat.feature.feed.ImageViewer
import ink.kuke.chat.ui.theme.KcTheme
import kotlin.math.abs

/**
 * 匿名证据：被举报消息 + 上下文。发言人只有「被举报人」「成员A」这类代称。
 * 默认显示离目标消息近的几条，其余折叠（rules.context_near）。
 */
@Composable
internal fun CaseEvidence(
    kind: String,
    messages: List<CaseMessage>,
    post: CasePost?,
    contextNear: Int,
    modifier: Modifier = Modifier,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var expanded by remember(messages.size, post) { mutableStateOf(false) }
    // 点图片看大图：(这一组图片, 从第几张开始)
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    viewer?.let { (urls, start) -> ImageViewer(urls, start) { viewer = null } }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind == "post" || (post != null && messages.isEmpty())) {
            post?.let { p ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel)
                        .border(1.5.dp, colors.danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("被举报人 · 公开动态", style = type.caption, color = colors.danger, fontWeight = FontWeight.W600)
                    Text(p.content ?: "（内容已不可见）", style = type.body, color = colors.text)
                    p.imageUrls.forEachIndexed { index, url -> EvidenceImage(url) { viewer = p.imageUrls to index } }
                }
            }
            return@Column
        }

        val hiddenCount = messages.count { it.offset != null && abs(it.offset) > contextNear }
        val shown = if (expanded || hiddenCount == 0) messages else messages.filter { it.offset == null || abs(it.offset) <= contextNear }
        if (hiddenCount > 0 && !expanded) {
            Text(
                "展开更多上下文（${hiddenCount} 条）", style = type.secondary, color = colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = true }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        shown.forEach { message ->
            val target = message.isTarget
            // 被举报人在上下文里说的每一句都标出来（淡边框 + 名字着色 + 正文加粗），被举报的那一条再额外强调
            val reported = !target && message.speaker == "被举报人"
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (target) colors.danger.copy(alpha = 0.08f) else if (reported) colors.danger.copy(alpha = 0.03f) else colors.panel)
                    .then(
                        if (target) Modifier.border(1.5.dp, colors.danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        else if (reported) Modifier.border(1.dp, colors.danger.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                        else Modifier,
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.speaker + if (target) " · 被举报" else "", style = type.caption,
                        color = if (target || reported) colors.danger else colors.muted, fontWeight = FontWeight.W600,
                    )
                }
                MessageBody(message, bold = reported) { urls, index -> viewer = urls to index }
            }
        }
    }
}

/**
 * 一条证据消息的正文：语音给播放条，图片消息（content 是图片地址）和附带的 images 画成图，
 * 内容为空（撤回 / 删除，或服务端没给）显示灰字占位，不留白。
 */
@Composable
private fun MessageBody(message: CaseMessage, bold: Boolean = false, onOpenImage: (List<String>, Int) -> Unit) {
    val colors = KcTheme.colors
    val content = message.content?.takeIf { it.isNotBlank() }
    val pictures = buildList {
        if (message.type == "image" && content != null) add(content)
        addAll(message.images)
    }
    val text = content?.takeIf { message.type != "image" && message.type != "voice" }
    val voice = content?.takeIf { message.type == "voice" }
    if (text == null && voice == null && pictures.isEmpty()) {
        Text("这条消息已被撤回或删除", style = KcTheme.type.body, color = colors.subtle)
        return
    }
    if (voice != null) EvidenceVoice(key = "jury-voice:${message.offset}:$voice", url = voice)
    if (text != null) Text(text, style = KcTheme.type.body, color = colors.text, fontWeight = if (bold) FontWeight.W600 else null)
    pictures.forEachIndexed { index, url -> EvidenceImage(url) { onOpenImage(pictures, index) } }
}

/**
 * 语音证据：播放 / 暂停 + 进度条 + 时长。播放复用聊天的 [VoicePlayer]（同一时间只放一条），
 * 这一条从界面上移除（离开页面、换题）时如果还在放就停掉并释放播放器。
 */
@Composable
private fun EvidenceVoice(key: String, url: String) {
    val colors = KcTheme.colors
    // 当前这条（在放或暂停着）/ 正在出声：点圆键暂停后再点接着放
    val current = VoicePlayer.playingId == key
    val playing = VoicePlayer.isPlaying(key)
    // 放过一次就记住总时长，停了以后还能显示
    var knownMs by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(current, VoicePlayer.durationMs) {
        if (current && VoicePlayer.durationMs > 0) knownMs = VoicePlayer.durationMs
    }
    val elapsedSec by remember(key) {
        derivedStateOf { if (VoicePlayer.playingId == key) VoicePlayer.positionMs / 1000 else 0L }
    }
    DisposableEffect(key) {
        onDispose { if (VoicePlayer.playingId == key) VoicePlayer.stop() }
    }
    // 正在拖的位置（0–1）
    var scrub by remember(key) { mutableStateOf<Float?>(null) }
    val button = colors.fill
    val glyph = colors.onFill
    val track = colors.text.copy(alpha = 0.14f)
    Row(
        Modifier.width(260.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(colors.panelMuted)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(32.dp).clip(CircleShape).clickable { VoicePlayer.toggle(key, url) }) {
            drawCircle(button)
            val u = size.minDimension / 32f
            if (playing) {
                val bar = Size(3.4f * u, 11f * u)
                drawRoundRect(glyph, Offset(11.6f * u, 10.5f * u), bar, CornerRadius(1.4f * u))
                drawRoundRect(glyph, Offset(17f * u, 10.5f * u), bar, CornerRadius(1.4f * u))
            } else {
                val tri = Path().apply {
                    moveTo(12.6f * u, 10.6f * u)
                    lineTo(21.6f * u, 16f * u)
                    lineTo(12.6f * u, 21.4f * u)
                    close()
                }
                drawPath(tri, glyph)
            }
        }
        // 进度条：点哪里就从哪里放；横着拖 = 拖进度（拖的时候进度和时间跟着手指，松手才真的跳）
        Canvas(
            Modifier.weight(1f).padding(horizontal = 10.dp).height(24.dp)
                .pointerInput(key, url) {
                    detectTapGestures { offset -> VoicePlayer.seek(key, url, offset.x / size.width.coerceAtLeast(1)) }
                }
                .pointerInput(key, url) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> scrub = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        onDragEnd = { scrub?.let { VoicePlayer.seek(key, url, it) }; scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ ->
                        change.consume()
                        scrub = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                },
        ) {
            val h = 4.dp.toPx()
            val top = (size.height - h) / 2f
            drawRoundRect(track, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2f))
            // 只在画的时候读进度：进度每帧变，也只重画这块画布
            val progress = scrub ?: if (VoicePlayer.playingId == key) VoicePlayer.progress else 0f
            if (progress > 0f) drawRoundRect(button, Offset(0f, top), Size(size.width * progress, h), CornerRadius(h / 2f))
            // 在放 / 暂停 / 拖动时画一个小圆点当把手
            if (scrub != null || VoicePlayer.playingId == key) drawCircle(button, 5.dp.toPx(), Offset((size.width * progress).coerceIn(5.dp.toPx(), size.width - 5.dp.toPx()), size.height / 2f))
        }
        Text(
            when {
                scrub != null && (current && VoicePlayer.durationMs > 0 || knownMs > 0) -> {
                    val total = if (current && VoicePlayer.durationMs > 0) VoicePlayer.durationMs else knownMs
                    "${clock((total * (scrub ?: 0f) / 1000f).toLong())} / ${clock(total / 1000)}"
                }
                current && VoicePlayer.durationMs > 0 -> "${clock(elapsedSec)} / ${clock(VoicePlayer.durationMs / 1000)}"
                current -> "加载中…"
                knownMs > 0 -> clock(knownMs / 1000)
                else -> "语音"
            },
            style = KcTheme.type.caption.copy(fontFeatureSettings = "tnum"), color = colors.muted,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun EvidenceImage(url: String, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val absolute = Endpoints.absolute(url)
    if (absolute == null) {
        Box(Modifier.size(width = 160.dp, height = 80.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted), contentAlignment = Alignment.Center) {
            Text("图片", style = KcTheme.type.caption, color = colors.subtle)
        }
        return
    }
    AsyncImage(
        model = absolute, contentDescription = "证据图片", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted)
            .clickable(onClick = onClick),
    )
}

/** 小标题 + 右侧说明 */
@Composable
internal fun BlockTitle(title: String, aside: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KcTheme.type.group, color = KcTheme.colors.muted, modifier = Modifier.weight(1f))
        if (aside != null) Text(aside, style = KcTheme.type.caption, color = KcTheme.colors.subtle)
    }
}
