package ink.kuke.chat.feature.groupfiles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.groupplus.GroupFile
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

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
private class StreamingFileBody(
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
): GroupFile? {
    val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", picked.name, StreamingFileBody(context, picked.uri, picked.mime, picked.size, onProgress))
        .addFormDataPart("announce", announce.toString())
        .addFormDataPart("permanent", permanent.toString())
    if (folderId != null) builder.addFormDataPart("folder_id", folderId.toString())
    val result = graph.api.upload("/conversations/$conversationId/files", builder.build())
    return ((result as? JsonObject)?.get("file"))?.let { runCatching { KcJson.decodeFromJsonElement(GroupFile.serializer(), it) }.getOrNull() }
}
