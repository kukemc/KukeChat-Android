package ink.kuke.chat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 乐观发送：点发送立刻在列表末尾放一条本地消息（PendingMessage），后台上传、发出；
 * POST 返回或 message.created 带回同一个 client_nonce（谁先到都行）时，把本地那条原地换成服务端的消息。
 * 只放内存、按会话存（切出会话再回来还在，重启就没了），不写消息缓存。
 * 同一会话的 POST 串行（上传可以并行），服务端顺序 = 发送顺序。
 */

/** 一张待发的图：本地 Uri；上传成功后记下地址，重发时直接复用 */
class OutgoingImage(val uri: Uri) {
    var url by mutableStateOf<String?>(null)
    /** 上传进度 0–1（拿不到时停在 0，界面画成转圈） */
    var progress by mutableFloatStateOf(0f)
}

/** 待发的语音：录完就把字节读进内存（录音文件发完即删，重发要用） */
class OutgoingVoice(val bytes: ByteArray, val durationMs: Long) {
    var url: String? = null
}

class PendingMessage internal constructor(
    val nonce: String,
    val kind: Kind,
    val text: String,
    val images: List<OutgoingImage>,
    val stickerUrl: String?,
    val voice: OutgoingVoice?,
    /** 除媒体地址之外的 metadata（引用、@、表情标记、语音信息…） */
    private val meta: JsonObject?,
) {
    enum class Kind { Text, Images, Sticker, Voice }

    /** 列表里画的那条：结构和服务端回来的一样（同样的 type / content / metadata），图片用本地 Uri。由 ChatController 填上发送人 */
    lateinit var preview: Message
        internal set

    var failed by mutableStateOf(false)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    /** 第几次尝试（重发自增）：界面据此重新计「超过 1 秒才转圈」 */
    var attempt by mutableIntStateOf(0)
        internal set
    var startedAt: Long = SystemClock.uptimeMillis()
        internal set
    internal var job: Job? = null

    val hasLocalImages: Boolean get() = kind == Kind.Images

    /** 消息的 type / content / metadata。[media] 是图片地址（预览时是本地 Uri，发送时是上传后的 url） */
    internal fun shape(media: List<String>, voiceUrl: String?): Triple<String, String, JsonObject?> = when (kind) {
        Kind.Text -> Triple("text", text, meta)
        Kind.Sticker -> Triple("image", stickerUrl.orEmpty(), meta)
        Kind.Voice -> Triple("voice", voiceUrl.orEmpty(), meta)
        Kind.Images ->
            // 单张且没有说明：沿用 type=image、content=地址；否则一条文字消息带 metadata.images（图文同条）
            if (media.size == 1 && text.isBlank()) Triple("image", media[0], meta)
            else Triple("text", text, JsonObject((meta ?: JsonObject(emptyMap())) + ("images" to JsonArray(media.map(::JsonPrimitive)))))
    }

    internal fun previewShape() = shape(images.map { it.uri.toString() }, null)
}

/** 发出去的图：服务端地址 → 本地 Uri。换成服务端消息后继续画本地这张（同一个 Coil 缓存键），不重新加载、不闪 */
object LocalImages {
    private val map = ConcurrentHashMap<String, String>()

    fun put(url: String, uri: Uri) {
        map[url] = uri.toString()
        Meta.imageUrl(url)?.let { map[it] = uri.toString() }
    }

    /** AsyncImage 用的 model：发过的图用本地那张，其余照常补成绝对地址 */
    fun model(raw: String?): String? = raw?.let { map[it] } ?: Meta.imageUrl(raw)

    fun clear() = map.clear()
}

object Outbox {
    private val boxes = HashMap<Long, ConversationOutbox>()

    fun of(graph: AppGraph, conversationId: Long): ConversationOutbox =
        boxes.getOrPut(conversationId) { ConversationOutbox(graph, conversationId) }

    /** 退出登录：丢掉所有没发完的 */
    fun clear() {
        boxes.values.forEach { it.close() }
        boxes.clear()
        LocalImages.clear()
    }
}

class ConversationOutbox internal constructor(private val graph: AppGraph, val conversationId: Long) {
    /** 发送中 / 失败的本地消息，按发送顺序 */
    val items = mutableStateListOf<PendingMessage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 上一条的 POST：下一条等它结束（成功失败都算）再发，保证顺序 */
    private var tail: CompletableDeferred<Unit>? = null
    /** 服务端消息 id → 本地 nonce：换成服务端消息后列表 key 不变（不闪、不跳） */
    private val keys = HashMap<Long, String>()
    private var localSeq = 0L

    interface Listener {
        fun onConfirmed(message: Message)
        fun onFailed(reason: String)
    }

    /** 开着的聊天页（ChatController）；没开时确认的消息直接记进消息缓存 */
    internal var listener: Listener? = null

    internal fun nextLocalId(): Long = -(++localSeq)

    /** 列表 key：发出过的消息沿用本地那条的 key */
    fun keyOf(message: Message): Any = keys[message.id]?.let { "n:$it" } ?: message.id

    fun keyOf(pending: PendingMessage): Any = "n:${pending.nonce}"

    internal fun enqueue(pending: PendingMessage) {
        items.add(pending)
        run(pending)
    }

    fun retry(pending: PendingMessage) {
        if (!pending.failed || items.none { it === pending }) return
        pending.failed = false
        pending.error = null
        pending.startedAt = SystemClock.uptimeMillis()
        pending.attempt++
        run(pending)
    }

    fun discard(pending: PendingMessage) {
        pending.job?.cancel()
        items.remove(pending)
    }

    /**
     * 服务端的消息带着 client_nonce 回来了（POST 返回或 message.created）：摘掉本地那条，记下 key 映射。
     * 调用方在同一帧里把服务端消息放进列表，界面上就是原地替换。
     */
    fun confirm(message: Message): Boolean {
        val nonce = message.clientNonce ?: return false
        val pending = items.firstOrNull { it.nonce == nonce } ?: return keys[message.id] == nonce
        keys[message.id] = nonce
        items.remove(pending)
        return true
    }

    private fun run(pending: PendingMessage) {
        val previous = tail
        val done = CompletableDeferred<Unit>()
        tail = done
        pending.job = scope.launch {
            try {
                upload(pending)
                previous?.await()
                // 等的时候被删了，或者已经被实时事件确认了
                if (items.none { it === pending }) return@launch
                val (type, content, metadata) = pending.shape(pending.images.map { it.url!! }, pending.voice?.url)
                val body = buildJsonObject {
                    put("content", content)
                    put("type", type)
                    metadata?.let { put("metadata", it) }
                    put("client_nonce", pending.nonce)
                }
                val sent = decode(graph.api.post("/conversations/$conversationId/messages", body))
                done.complete(Unit)
                if (sent == null) {
                    // 发出去了但读不出回包：等 message.created 补上，本地这条先撤掉
                    items.remove(pending)
                    return@launch
                }
                val echoed = if (sent.clientNonce == null) sent.copy(clientNonce = pending.nonce) else sent
                confirm(echoed)
                deliver(echoed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (items.any { it === pending }) {
                    val reason = e.userMessage()
                    pending.error = reason
                    pending.failed = true
                    listener?.onFailed(reason)
                }
            } finally {
                done.complete(Unit)
            }
        }
    }

    private fun deliver(message: Message) {
        listener?.let {
            it.onConfirmed(message)
            return
        }
        // 聊天页没开：记进消息缓存，下次进来直接看到
        val current = graph.messageCache.get(conversationId)
        if (current.isNotEmpty() && current.none { it.id == message.id }) graph.messageCache.put(conversationId, (current + message).sortedBy { it.id })
    }

    /** 图片并行上传（已经传过的跳过）；语音传一次 */
    private suspend fun upload(pending: PendingMessage) {
        val context = graph.appContext
        coroutineScope {
            pending.images.filter { it.url == null }.map { image ->
                async {
                    image.progress = 0f
                    val url = uploadMessageImage(graph, context, image.uri) { p -> image.progress = p }
                    LocalImages.put(url, image.uri)
                    image.url = url
                }
            }.awaitAll()
        }
        val voice = pending.voice
        if (voice != null && voice.url == null) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "voice.m4a", ProgressBody(voice.bytes, "audio/mp4".toMediaType(), null))
                .build()
            val uploaded = graph.api.upload("/uploads/message-voice", body)
            voice.url = (uploaded as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: error("上传没有返回地址")
        }
    }

    private fun decode(element: JsonElement): Message? = runCatching { KcJson.decodeFromJsonElement(Message.serializer(), element) }.getOrNull()

    internal fun close() {
        scope.cancel()
        items.clear()
        listener = null
    }
}

/** 聊天列表里的一行：服务端消息（pending = null）或本地消息（message = 它的 preview） */
internal class ChatEntry(val message: Message, val pending: PendingMessage?)

/** 随机 client_nonce：32 位十六进制（后端只收 [A-Za-z0-9_-]，1–64 位） */
internal fun newClientNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "")

private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
private const val MAX_EDGE = 2560

/** 读出图片、压到服务端的上限以内（3MB、1200 万像素），GIF 原样上传；返回图片地址。[onProgress] 报上传进度 */
internal suspend fun uploadMessageImage(graph: AppGraph, context: Context, uri: Uri, onProgress: ((Float) -> Unit)? = null): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "image/jpeg"
    val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这张图片")
    val (bytes, type, ext) = if (mime == "image/gif" && raw.size <= MAX_IMAGE_BYTES) {
        Triple(raw, "image/gif", "gif")
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("这不是一张能识别的图片")
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        var quality = 88
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream); stream.toByteArray() }
            quality -= 10
        } while (out.size > MAX_IMAGE_BYTES && quality > 30)
        Triple(out, "image/jpeg", "jpg")
    }
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", "image.$ext", ProgressBody(bytes, type.toMediaType(), onProgress))
        .build()
    val uploaded = graph.api.upload("/uploads/message-image", body)
    (uploaded as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: error("上传没有返回地址")
}

/** 分块写出、边写边报进度的请求体 */
private class ProgressBody(private val bytes: ByteArray, private val type: MediaType, private val onProgress: ((Float) -> Unit)?) : RequestBody() {
    override fun contentType(): MediaType = type
    override fun contentLength(): Long = bytes.size.toLong()
    override fun writeTo(sink: BufferedSink) {
        var written = 0
        while (written < bytes.size) {
            val n = minOf(16 * 1024, bytes.size - written)
            sink.write(bytes, written, n)
            written += n
            onProgress?.invoke(written.toFloat() / bytes.size)
        }
    }
}
