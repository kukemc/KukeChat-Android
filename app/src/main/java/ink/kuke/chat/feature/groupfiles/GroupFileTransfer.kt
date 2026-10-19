package ink.kuke.chat.feature.groupfiles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.await
import ink.kuke.chat.feature.groupplus.GroupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.URLEncoder

/** 选中的本地文件（来自系统文件选择器的 content:// Uri） */
class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

/** 读文件名和大小；size 读不到时为 -1 */
fun describePicked(context: Context, uri: Uri): PickedFile {
    var name = "file"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return PickedFile(uri, name, size, mime)
}

/**
 * 流式 multipart 文件体：不把 100MB 读进内存，边读边写，并回报已发送字节（界面画进度条）。
 */
internal class StreamingFileBody(
    private val context: Context,
    private val uri: Uri,
    private val mime: String,
    private val length: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = runCatching { mime.toMediaType() }.getOrNull() ?: "application/octet-stream".toMediaType()
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("读不到这个文件")
        input.use { stream ->
            val buffer = ByteArray(32 * 1024)
            var sent = 0L
            var lastEmit = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sent += read
                if (sent - lastEmit >= 128 * 1024) {
                    lastEmit = sent
                    onProgress(sent)
                }
            }
            onProgress(sent)
        }
    }
}

/**
 * 上传群文件（POST /conversations/{cid}/files，multipart：file / announce / permanent / folder_id）。
 * 走 Api.upload，所以令牌、客户端证明头、错误解析（ApiException）都和别的接口一致。
 * 返回服务端记下的文件（解析不了为 null）：变成临时文件时会忽略 folder_id 放根目录，以返回的 folder_id 为准。
 */
suspend fun uploadGroupFile(
    graph: AppGraph,
    context: Context,
    conversationId: Long,
    picked: PickedFile,
    announce: Boolean,
    permanent: Boolean,
    folderId: Long? = null,
    onProgress: (Long) -> Unit,
): GroupFile? = parseUploaded(uploadMultipart(graph, context, conversationId, picked, announce, permanent, folderId, onProgress)).file

/** 上传结果（GroupFileUploadResult）：登记的文件，和 announce 时落进群里的那条文件消息（原样 JSON，调用方自己解析） */
class GroupFileUploaded(val file: GroupFile?, val message: JsonElement?)

private fun parseUploaded(result: JsonElement): GroupFileUploaded {
    val obj = result as? JsonObject
    val file = obj?.get("file")?.let { runCatching { KcJson.decodeFromJsonElement(GroupFile.serializer(), it) }.getOrNull() }
    return GroupFileUploaded(file, obj?.get("message")?.takeIf { it is JsonObject })
}

private suspend fun uploadMultipart(
    graph: AppGraph,
    context: Context,
    conversationId: Long,
    picked: PickedFile,
    announce: Boolean,
    permanent: Boolean,
    folderId: Long?,
    onProgress: (Long) -> Unit,
): JsonElement {
    val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", picked.name, StreamingFileBody(context, picked.uri, picked.mime, picked.size, onProgress))
        .addFormDataPart("announce", announce.toString())
        .addFormDataPart("permanent", permanent.toString())
    if (folderId != null) builder.addFormDataPart("folder_id", folderId.toString())
    return graph.api.upload("/conversations/$conversationId/files", builder.build())
}

/**
 * 发一个群文件：[direct]（FileQuota.direct）为 true 时先走直传 —— 开上传单拿预签名 PUT、直接传桶、再 complete；
 * 走不通（服务端没开直传 409 direct_upload_unavailable、旧服务端没有接口、一个字节都没发出去就断了）退回 multipart。
 * 大小读不到时没法直传（签名里带 Content-Length），直接 multipart。
 * 取消协程会取消正在进行的 HTTP 请求（Call.await 的 invokeOnCancellation）。
 */
suspend fun sendGroupFile(
    graph: AppGraph,
    context: Context,
    conversationId: Long,
    picked: PickedFile,
    direct: Boolean,
    announce: Boolean = true,
    permanent: Boolean = false,
    onProgress: (Long) -> Unit,
): GroupFileUploaded {
    if (direct && picked.size > 0) {
        uploadDirect(graph, context, conversationId, picked, announce, permanent, onProgress)?.let { return parseUploaded(it) }
        onProgress(0)
    }
    return parseUploaded(uploadMultipart(graph, context, conversationId, picked, announce, permanent, null, onProgress))
}

private suspend fun uploadDirect(
    graph: AppGraph,
    context: Context,
    conversationId: Long,
    picked: PickedFile,
    announce: Boolean,
    permanent: Boolean,
    onProgress: (Long) -> Unit,
): JsonElement? {
    val ticket = try {
        graph.api.post("/conversations/$conversationId/files/uploads", buildJsonObject {
            put("name", picked.name)
            put("size", picked.size)
            put("content_type", picked.mime)
            put("permanent", permanent)
        }) as? JsonObject
    } catch (e: ApiException) {
        if (e.code == "direct_upload_unavailable" || e.status == 404 || e.status == 405) return null
        throw e
    } ?: return null
    val uploadId = (ticket["upload_id"] as? JsonPrimitive)?.contentOrNull ?: return null
    val url = (ticket["url"] as? JsonPrimitive)?.contentOrNull ?: return null
    val headers = (ticket["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.orEmpty()
    // 签名里的 Content-Type（application/octet-stream）：请求体的类型必须和它一致，OkHttp 会用请求体的类型覆盖请求头
    val signedType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second ?: "application/octet-stream"
    var sent = 0L
    try {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .put(StreamingFileBody(context, picked.uri, signedType, picked.size) { sent = it; onProgress(it) })
            headers.forEach { (k, v) -> if (!k.equals("Content-Type", ignoreCase = true)) request.header(k, v) }
            graph.api.http.newCall(request.build()).await().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "direct_put_failed", "上传失败（${response.code}），稍后再试", null)
            }
        }
    } catch (e: ApiException) {
        throw e
    } catch (e: IOException) {
        // 一个字节都没发出去就断了：退回经后端上传，别让人卡住
        if (sent == 0L) return null
        throw e
    }
    return graph.api.post(
        "/conversations/$conversationId/files/uploads/${URLEncoder.encode(uploadId, "UTF-8")}/complete",
        buildJsonObject { put("announce", announce) },
    )
}
