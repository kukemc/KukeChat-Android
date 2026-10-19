package ink.kuke.chat.feature.selfchat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.ClientProof
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.await
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.chat.OutgoingFile
import ink.kuke.chat.feature.groupfiles.FileDownloads
import ink.kuke.chat.feature.groupfiles.FileSource
import ink.kuke.chat.feature.groupfiles.StreamingFileBody
import ink.kuke.chat.feature.groupplus.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import java.time.Instant
import java.util.concurrent.TimeUnit

/*
 * 「我的设备」的临时文件（KukeChat-Server docs/self-chat.md 第 5 节）：
 *   开单 POST /conversations/{cid}/transfers → PUT 文件字节到 upload.url（直传对象存储，或经后端 /content）
 *   → POST .../complete {client_nonce} 落一条文件消息（metadata.transfer）→ 下载 /link（直连）或 /download（经后端）。
 * 24 小时后过期（410 file_expired），不能收藏、不能转发；撤回文件消息 = 删除文件。
 */

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** 文件消息里的 metadata.transfer（和群文件的 metadata.file 是两种东西，见 5.3） */
class TransferMeta(
    val id: Long,
    val name: String,
    val size: Long,
    val contentType: String?,
    val expiresAt: String?,
    private val expiredFlag: Boolean,
) {
    val expiresInstant: Instant? get() = parseInstant(expiresAt)

    /** 过期：服务端算的 expired、下载时拿到过 410、或者按 expires_at 和本机时间比（过期时服务端不推事件） */
    fun isExpired(now: Instant = Instant.now()): Boolean =
        expiredFlag || SelfTransfers.expired[id] == true || expiresInstant?.let { !it.isAfter(now) } == true
}

fun transferOf(message: Message): TransferMeta? {
    val obj = message.metadata?.get("transfer") as? JsonObject ?: return null
    val id = obj.long("id") ?: return null
    return TransferMeta(id, obj.str("name")?.takeIf { it.isNotBlank() } ?: "文件", obj.long("size") ?: 0L, obj.str("content_type"), obj.str("expires_at"), obj.bool("expired") == true)
}

/** GET .../transfers 里的一项（还能下载的） */
class TransferItem(val id: Long, val name: String, val size: Long, val contentType: String?, val device: String?, val expiresAt: String?, val messageId: Long?) {
    fun meta() = TransferMeta(id, name, size, contentType, expiresAt, false)
}

/** GET .../transfers：还能下载的文件 + 额度。上限不要写死，从这里读 */
class TransferList(
    val items: List<TransferItem>,
    val maxFileBytes: Long,
    val quotaBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val ttlHours: Int,
    val direct: Boolean,
)

/** 开好的上传单 */
internal class SelfTransferTicket(val id: Long, val direct: Boolean, val url: String, val headers: List<Pair<String, String>>, val expiresAt: Instant?) {
    /** 直传地址 15 分钟就过期：留 30 秒余量 */
    val urlExpired: Boolean get() = expiresAt?.let { !it.isAfter(Instant.now().plusSeconds(30)) } == true
}

object SelfTransfers {
    /** 单个文件的默认上限（服务端 SELF_TRANSFER_MAX_BYTES 默认 1 GB）：还没查到额度时先按它挡 */
    const val DEFAULT_MAX_FILE = 1L shl 30

    /** 下载时拿到 410 的（过期了 / 被撤回了）：卡片立刻置灰 */
    val expired = mutableStateMapOf<Long, Boolean>()

    /** 上一次查到的额度（选文件前用来挡超限的） */
    @Volatile var lastList: TransferList? = null
        private set

    /** 系统下载器状态表里的键：负的 transfer id，和群文件 id 不撞 */
    fun downloadKey(transferId: Long): Long = -transferId

    fun markExpired(transferId: Long) {
        expired[transferId] = true
    }

    suspend fun list(graph: AppGraph, conversationId: Long): TransferList {
        val obj = graph.api.get("/conversations/$conversationId/transfers") as? JsonObject ?: error("读不到临时文件列表")
        val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.long("id") ?: return@mapNotNull null
            TransferItem(id, item.str("name") ?: "文件", item.long("size") ?: 0L, item.str("content_type"), item.str("device"), item.str("expires_at"), item.long("message_id"))
        }
        return TransferList(
            items = items,
            maxFileBytes = obj.long("max_file_bytes") ?: DEFAULT_MAX_FILE,
            quotaBytes = obj.long("quota_bytes") ?: 0L,
            usedBytes = obj.long("used_bytes") ?: 0L,
            freeBytes = obj.long("free_bytes") ?: 0L,
            ttlHours = (obj["ttl_hours"] as? JsonPrimitive)?.intOrNull ?: 24,
            direct = obj.bool("direct") == true,
        ).also { lastList = it }
    }

    /** 取下载地址：优先 /link（5 分钟的预签名直链，不用鉴权）；409 direct_download_unavailable 时退到带鉴权的 /download */
    suspend fun resolveSource(graph: AppGraph, conversationId: Long, transferId: Long): FileSource {
        try {
            val result = graph.api.get("/conversations/$conversationId/transfers/$transferId/link") as? JsonObject
            val url = result?.str("url")
            if (!url.isNullOrBlank()) return FileSource(url, emptyMap())
        } catch (e: ApiException) {
            if (e.status != 409) throw e
        }
        val apiPath = "/conversations/$conversationId/transfers/$transferId/download"
        val headers = ClientProof.headers("GET", apiPath).toMutableMap()
        graph.session.token?.let { headers["Authorization"] = "Bearer $it" }
        // 系统下载器不走 OkHttp 拦截器：设备标识头手动带上
        headers.putAll(graph.api.device.headers())
        return FileSource(Endpoints.api + apiPath, headers)
    }

    /** 下载（交给系统下载器，存到 下载/KukeChat）。410 → 「文件已过期」并把卡片置灰 */
    fun download(graph: AppGraph, context: Context, conversationId: Long, meta: TransferMeta, openAfter: Boolean) {
        FileDownloads.downloadWith(
            context, downloadKey(meta.id), meta.name, meta.size, openAfter, "KukeChat 临时文件",
            onError = { error ->
                if (error is ApiException && (error.status == 410 || error.code == "file_expired")) {
                    markExpired(meta.id)
                    "文件已过期"
                } else if (error is ApiException && error.code == "transfer_not_found") {
                    markExpired(meta.id)
                    "文件已经不在了"
                } else null
            },
        ) { resolveSource(graph, conversationId, meta.id) }
    }
}

/** 开单 / complete 的错误换成一句好懂的话（服务端的 message 已经是中文，这里补上具体的数字） */
private fun friendly(e: ApiException): Exception {
    val detail = e.detail as? JsonObject
    val text = when (e.code) {
        "file_too_large" -> detail?.long("max_file_bytes")?.let { "单个文件不能超过 ${formatBytes(it)}" }
        "transfer_quota_exceeded" -> {
            val quota = detail?.long("quota_bytes")
            val used = detail?.long("used_bytes")
            if (quota != null && used != null) "临时文件空间不够了（已用 ${formatBytes(used)} / 共 ${formatBytes(quota)}），等之前的文件过期，或撤回之前的文件消息再发"
            else "临时文件空间不够了，等之前的文件过期，或撤回之前的文件消息再发"
        }
        "empty_file" -> "这是空文件，不能发送"
        "upload_not_found" -> "上传单已过期，点「重试」重新上传"
        "upload_incomplete", "upload_size_mismatch" -> "文件没有完整传上去，点「重试」重新上传"
        "rate_limited" -> "发得太快了，歇一会儿再点「重试」"
        else -> if (e.status == 413 && e.code == null) "文件太大，服务器收不下" else null
    }
    return if (text != null) Exception(text, e) else e
}

/** 大小读不到（部分文件提供方不给 SIZE）：用文件描述符量一下 */
private fun measure(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length > 0 } }
}.getOrNull()

/**
 * 发一个临时文件：开单 → PUT → complete（带 [nonce]）。返回 complete 回来的那条文件消息（原样 JSON，可能为 null）。
 * 状态记在 [file] 上，重试时跳过已经做完的步骤：传完了只再 complete；上传单还在（直传地址没过期）就接着往它 PUT。
 * 取消协程会取消正在进行的 HTTP 请求。
 */
internal suspend fun sendSelfTransfer(graph: AppGraph, context: Context, conversationId: Long, file: OutgoingFile, nonce: String): JsonElement? {
    val picked = file.picked
    val size = picked.size.takeIf { it > 0 } ?: withContext(Dispatchers.IO) { measure(context, picked.uri) } ?: error("读不到这个文件的大小，换个方式选择试试")
    if (!file.uploaded) {
        val ticket = (file.ticket as? SelfTransferTicket)?.takeIf { !(it.direct && it.urlExpired) }
            ?: openTicket(graph, conversationId, picked.name, size, picked.mime).also { file.ticket = it }
        file.sent = 0
        try {
            putContent(graph, context, conversationId, ticket, picked.uri, size) { file.sent = it }
        } catch (e: ApiException) {
            // 直传被对象存储拒了（签名过期之类）/ 上传单作废了：下次重新开单
            if (ticket.direct || e.code == "upload_not_found") file.ticket = null
            throw friendly(e)
        }
        file.uploaded = true
    }
    val current = file.ticket as? SelfTransferTicket ?: error("上传单丢了，请重新发送")
    val result = try {
        graph.api.post("/conversations/$conversationId/transfers/${current.id}/complete", buildJsonObject { put("client_nonce", nonce) }) as? JsonObject
    } catch (e: ApiException) {
        when (e.code) {
            // 上传单作废了：从头来
            "upload_not_found" -> {
                file.ticket = null
                file.uploaded = false
            }
            // 存储里没有 / 大小不对：重新 PUT 再 complete
            "upload_incomplete", "upload_size_mismatch" -> file.uploaded = false
        }
        throw friendly(e)
    }
    return result?.get("message")?.takeIf { it is JsonObject }
}

private suspend fun openTicket(graph: AppGraph, conversationId: Long, name: String, size: Long, mime: String): SelfTransferTicket {
    val created = try {
        graph.api.post("/conversations/$conversationId/transfers", buildJsonObject {
            put("name", name)
            put("size", size)
            if (mime.isNotBlank()) put("content_type", mime)
        }) as? JsonObject
    } catch (e: ApiException) {
        throw friendly(e)
    } ?: error("开不了上传单，稍后再试")
    val id = created.long("transfer_id") ?: (created["transfer"] as? JsonObject)?.long("id") ?: error("开不了上传单，稍后再试")
    val upload = created["upload"] as? JsonObject ?: error("开不了上传单，稍后再试")
    val headers = (upload["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.orEmpty()
    return SelfTransferTicket(id, upload.bool("direct") == true, upload.str("url") ?: error("开不了上传单，稍后再试"), headers, parseInstant(upload.str("expires_at")))
}

/**
 * 第 2 步：把文件字节作为请求体 PUT 上去，边读边写（不读进内存），回报已发送字节。
 * 直传：原样带 upload.headers，不带 Authorization / 客户端证明头（拦截器只给自家 /api/v1 加头）。
 * 经后端：upload.url 是 /api/v1/... 相对路径，带登录令牌和客户端证明头（证明里的 path 不含 /api/v1）。
 */
private suspend fun putContent(graph: AppGraph, context: Context, conversationId: Long, ticket: SelfTransferTicket, uri: Uri, size: Long, onProgress: (Long) -> Unit) {
    // 签名里的 Content-Type：请求体的类型必须和它一致（OkHttp 用请求体的类型覆盖请求头）
    val signedType = ticket.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second ?: "application/octet-stream"
    val body = StreamingFileBody(context, uri, signedType, size, onProgress)
    val builder = Request.Builder()
    if (ticket.direct) {
        builder.url(ticket.url)
    } else {
        val absolute = Endpoints.absolute(ticket.url) ?: error("上传地址不对")
        val apiPath = absolute.removePrefix(Endpoints.api).substringBefore('?').ifBlank { "/conversations/$conversationId/transfers/${ticket.id}/content" }
        builder.url(absolute)
        ClientProof.headers("PUT", apiPath).forEach { (k, v) -> builder.header(k, v) }
        graph.session.token?.let { builder.header("Authorization", "Bearer $it") }
    }
    ticket.headers.forEach { (k, v) -> if (!k.equals("Content-Type", ignoreCase = true)) builder.header(k, v) }
    builder.put(body)
    // 大文件经后端时服务端落盘要一会儿：回包等久一点
    val client = graph.api.http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
    withContext(Dispatchers.IO) {
        client.newCall(builder.build()).await().use { response ->
            if (!response.isSuccessful) throw errorOf(response, ticket.direct)
        }
    }
}

/** 把失败的 PUT 换成 ApiException（经后端时读 detail 里的 code / message，同 Api.parse） */
private fun errorOf(response: Response, direct: Boolean): ApiException {
    val code = response.code
    if (direct) return ApiException(code, "direct_put_failed", "上传失败（$code），点「重试」再传一次", null)
    val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
    val element = runCatching { KcJson.parseToJsonElement(text) }.getOrNull()
    val detail = (element as? JsonObject)?.get("detail")
    val apiCode = (detail as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
    val message = when (detail) {
        is JsonObject -> detail["message"]?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> detail.contentOrNull
        else -> null
    } ?: if (code == 413) "文件太大，服务器收不下" else "上传失败（$code），点「重试」再传一次"
    return ApiException(code, apiCode, message, detail)
}
