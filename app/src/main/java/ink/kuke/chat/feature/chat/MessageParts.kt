package ink.kuke.chat.feature.chat

import android.media.MediaPlayer
import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.Endpoints
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 消息 metadata 的读取（同 KukeChat-Client 的 messageMeta.ts），缺了、形状不对一律当没有 */
object Meta {
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    data class Quote(val messageId: Long?, val senderName: String, val preview: String)
    data class Voice(val durationMs: Long, val waveform: List<Int>)
    data class Card(val kind: String, val title: String, val subtitle: String?, val avatarUrl: String?)

    /**
     * 引用（同电脑版 quoteMetadataOf）：先看 metadata.elements 里的 quote 元素（机器人消息），再看 metadata.quote。
     * 预览为空时按被引用消息的类型补一个（[语音]、[图片]…）；什么都没有就当没有引用，不画空框。
     */
    fun quote(message: Message): Quote? {
        val meta = message.metadata ?: return null
        val element = (meta["elements"] as? JsonArray)?.firstNotNullOfOrNull { el ->
            (el as? JsonObject)?.takeIf { it.str("type") == "quote" }?.get("attrs") as? JsonObject
        }
        val quote = element ?: meta["quote"] as? JsonObject ?: return null
        val id = quote.long("message_id") ?: quote.long("id") ?: quote.long("messageId")
        val name = (quote.str("sender_name") ?: quote.str("senderName") ?: quote.str("name"))?.takeIf { it.isNotBlank() } ?: "未知用户"
        val preview = (quote.str("preview") ?: quote.str("content"))?.takeIf { it.isNotBlank() }
            ?: when (quote.str("type")) {
                "voice" -> "[语音]"
                "image" -> "[图片]"
                "poll" -> "[投票]"
                "red_packet" -> "[红包]"
                "gift" -> "[礼物]"
                "forward_bundle" -> "[聊天记录]"
                "file" -> "[文件]"
                else -> null
            }
            ?: if (id != null) "[消息]" else return null
        return Quote(id, name, preview)
    }

    fun isSticker(message: Message): Boolean =
        message.type == "image" && (message.metadata?.get("sticker")?.jsonPrimitive?.booleanOrNull == true || message.content.contains("/stickers/"))

    /** 文字消息里附带的图片（metadata.images） */
    fun images(message: Message): List<String> =
        (message.metadata?.get("images") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    fun voice(message: Message): Voice {
        val voice = message.metadata?.get("voice") as? JsonObject
        val waveform = (voice?.get("waveform") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        return Voice(voice?.long("duration_ms") ?: 0L, waveform)
    }

    fun isMarkdown(message: Message): Boolean = message.metadata?.get("markdown")?.jsonPrimitive?.booleanOrNull == true

    fun mentions(message: Message): List<String> =
        (message.metadata?.get("mentions") as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }.orEmpty()

    /** 名片、礼物、红包、投票、合并转发：先统一画成一张卡片（交互在后面几步加） */
    fun card(message: Message): Card? {
        val meta = message.metadata
        when (message.type) {
            "gift" -> {
                val gift = meta?.get("gift") as? JsonObject
                val name = (gift?.get("gift") as? JsonObject)?.str("name") ?: (gift?.get("item") as? JsonObject)?.str("name") ?: "礼物"
                val count = gift?.long("count") ?: 1
                val to = (gift?.get("to_user") as? JsonObject)?.str("display_name")
                return Card("gift", if (count > 1) "$name ×$count" else name, listOfNotNull(to?.let { "送给 $it" }, gift?.str("message")?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }, null)
            }
            "red_packet" -> {
                val packet = meta?.get("red_packet") as? JsonObject
                return Card("redPacket", packet?.str("greeting")?.takeIf { it.isNotBlank() } ?: "恭喜发财，大吉大利", "KukeChat 红包", null)
            }
            "poll" -> {
                val poll = meta?.get("poll") as? JsonObject
                return Card("poll", poll?.str("question") ?: poll?.str("title") ?: "投票", "投票", null)
            }
            "forward_bundle" -> return Card("forward", meta?.str("title") ?: "聊天记录", "合并转发的消息", null)
        }
        val share = meta?.get("share_card") as? JsonObject ?: return null
        return when (share.str("type")) {
            "group" -> Card("group", share.str("title") ?: "群聊", "群聊 · ${share.long("member_count") ?: 0} 名成员", share.str("avatar_url"))
            "user" -> Card("user", share.str("name") ?: "用户", share.str("bio") ?: "个人名片", share.str("avatar_url"))
            "post" -> Card("post", share.str("author_name")?.let { "$it 的动态" } ?: "动态", share.str("content"), share.str("author_avatar_url"))
            "bot" -> Card("bot", share.str("name") ?: "机器人", share.str("description") ?: "机器人", share.str("avatar_url"))
            "teamup" -> Card("teamup", share.str("title") ?: share.str("name") ?: "组队名片", "组队名片", share.str("avatar_url"))
            "task" -> Card("task", share.str("title") ?: "任务", "任务", null)
            else -> null
        }
    }

    fun imageUrl(url: String?): String? = Endpoints.absolute(url)
}

private val urlPattern = Regex("""https?://[^\s<>"'，。、）)]+""")

/**
 * 文字内容：@成员高亮、链接可点。机器人的 Markdown 消息做一个轻量的渲染（标题、加粗、行内代码、列表），
 * 不支持的语法原样显示，不会丢字。
 */
fun richText(message: Message, mine: Boolean, accent: Color, onFill: Color): AnnotatedString {
    val text = message.content
    val mentionNames = Meta.mentions(message).map { "@$it" }
    val linkColor = if (mine) onFill else accent
    return buildAnnotatedString {
        val lines = text.split('\n')
        lines.forEachIndexed { index, rawLine ->
            var line = rawLine
            var heading = false
            if (Meta.isMarkdown(message)) {
                Regex("""^\s{0,3}#{1,6}\s+""").find(line)?.let { line = line.removeRange(it.range); heading = true }
                Regex("""^\s*[-*+]\s+""").find(line)?.let { line = "• " + line.removeRange(it.range) }
            }
            if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            appendInline(line, Meta.isMarkdown(message), mentionNames, linkColor)
            if (heading) pop()
            if (index < lines.lastIndex) append('\n')
        }
    }
}

private fun AnnotatedString.Builder.appendInline(line: String, markdown: Boolean, mentions: List<String>, linkColor: Color) {
    // 先切出 **加粗** 和 `代码`（只在 Markdown 消息里），再在普通文字里找链接和 @
    val tokens = if (markdown) Regex("""\*\*(.+?)\*\*|`([^`]+)`""").findAll(line).toList() else emptyList()
    var cursor = 0
    for (token in tokens) {
        appendPlain(line.substring(cursor, token.range.first), mentions, linkColor)
        val bold = token.groups[1]?.value
        if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendPlain(bold, mentions, linkColor) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = linkColor.copy(alpha = 0.12f))) { append(token.groups[2]?.value.orEmpty()) }
        cursor = token.range.last + 1
    }
    appendPlain(line.substring(cursor), mentions, linkColor)
}

private fun AnnotatedString.Builder.appendPlain(text: String, mentions: List<String>, linkColor: Color) {
    var cursor = 0
    for (match in urlPattern.findAll(text)) {
        appendMentions(text.substring(cursor, match.range.first), mentions, linkColor)
        withLink(LinkAnnotation.Url(match.value, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)))) { append(match.value) }
        cursor = match.range.last + 1
    }
    appendMentions(text.substring(cursor), mentions, linkColor)
}

private fun AnnotatedString.Builder.appendMentions(text: String, mentions: List<String>, linkColor: Color) {
    if (mentions.isEmpty() && "@全体成员" !in text) {
        append(text)
        return
    }
    val targets = (mentions + "@全体成员").sortedByDescending { it.length }
    var cursor = 0
    while (cursor < text.length) {
        val hit = targets.mapNotNull { target -> text.indexOf(target, cursor).takeIf { it >= 0 }?.let { it to target } }.minByOrNull { it.first }
        if (hit == null) {
            append(text.substring(cursor))
            break
        }
        append(text.substring(cursor, hit.first))
        withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.W600)) { append(hit.second) }
        cursor = hit.first + hit.second.length
    }
}

/**
 * 语音播放：同一时间只放一条，再点一下停。
 * [progress] 是当前这条的播放进度（0–1），每帧从 MediaPlayer 读一次，气泡里的波形按它从左到右点亮。
 * [playingId] 是正在放的那条的标识：聊天消息用消息 id；没有消息 id 的地方（风纪委员案件证据）自己给一个不会和 id 撞的键。
 */
object VoicePlayer {
    var playingId by mutableStateOf<Any?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    /** 正在放的那条的总时长（毫秒）；准备好之前是 0 */
    var durationMs by mutableLongStateOf(0L)
        private set
    private var player: MediaPlayer? = null
    private var prepared = false
    /** 还没准备好就要求跳的位置（0–1），准备好后先跳再放 */
    private var pendingSeek: Float? = null

    // 播放中每帧刷新进度；停了就不再续约
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val p = player ?: return
            if (prepared) runCatching {
                val total = p.duration
                if (total > 0) progress = (p.currentPosition.toFloat() / total).coerceIn(0f, 1f)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun toggle(message: Message) = toggle(message.id, message.content)

    /** 播放 / 停止 [key] 对应的语音，[url] 可以是相对地址 */
    fun toggle(key: Any, url: String?) {
        if (playingId == key) {
            stop()
            return
        }
        stop()
        val absolute = Endpoints.absolute(url) ?: return
        playingId = key
        progress = 0f
        player = MediaPlayer().apply {
            setDataSource(absolute)
            setOnPreparedListener {
                prepared = true
                durationMs = it.duration.toLong().coerceAtLeast(0L)
                pendingSeek?.let { f -> if (it.duration > 0) it.seekTo((it.duration * f).toInt()) }
                pendingSeek = null
                it.start()
            }
            setOnCompletionListener {
                progress = 1f
                stop()
            }
            setOnErrorListener { _, _, _ -> stop(); true }
            prepareAsync()
        }
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** 拖动 / 点按语音条：跳到 [fraction]（0–1）。这一条正在放就直接跳；没在放就从这个位置开始放 */
    fun seek(message: Message, fraction: Float) = seek(message.id, message.content, fraction)

    fun seek(key: Any, url: String?, fraction: Float) {
        val f = fraction.coerceIn(0f, 0.999f)
        if (playingId != key) {
            toggle(key, url)
            if (playingId != key) return
        }
        progress = f
        val p = player ?: return
        if (prepared) runCatching { if (p.duration > 0) p.seekTo((p.duration * f).toInt()) } else pendingSeek = f
    }

    fun stop() {
        Choreographer.getInstance().removeFrameCallback(frame)
        pendingSeek = null
        runCatching { player?.release() }
        player = null
        prepared = false
        playingId = null
        progress = 0f
        durationMs = 0L
    }
}

