package ink.kuke.chat.feature.groupfiles

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.ClientProof
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.await
import ink.kuke.chat.feature.groupplus.GroupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
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

/** 文件名清洗成本地能用的名字 */
private fun safeLocalName(name: String): String = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifBlank { "file" }.take(120)

/**
 * 带鉴权下载到应用缓存（cache/group-files，已在 file_paths.xml 里配了 FileProvider 路径）。
 * 下载接口要 Authorization + 客户端证明头，不能用系统 DownloadManager 的匿名直链。
 */
suspend fun downloadGroupFile(
    graph: AppGraph,
    context: Context,
    downloadPath: String,
    fileId: Long,
    name: String,
    onProgress: (Float) -> Unit,
): File = withContext(Dispatchers.IO) {
    val apiPath = downloadPath.removePrefix("/api/v1")
    val dir = File(context.cacheDir, "group-files").apply { mkdirs() }
    val target = File(dir, "$fileId-${safeLocalName(name)}")
    val builder = Request.Builder().url(Endpoints.api + apiPath).get()
    ClientProof.headers("GET", apiPath).forEach { (key, value) -> builder.header(key, value) }
    graph.session.token?.let { builder.header("Authorization", "Bearer $it") }
    graph.api.http.newCall(builder.build()).await().use { response ->
        val body = response.body
        if (!response.isSuccessful || body == null) {
            val text = body?.string().orEmpty()
            val detail = (runCatching { KcJson.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.get("detail")
            val code = (detail as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
            val message = (detail as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "下载失败（${response.code}）"
            throw ApiException(response.code, code, message, detail)
        }
        val total = body.contentLength()
        var done = 0L
        var lastEmit = 0L
        body.byteStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (done - lastEmit >= 128 * 1024) {
                        lastEmit = done
                        onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        }
    }
    onProgress(1f)
    target
}

private fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}

/** 用别的应用打开下载好的文件。找不到能打开的应用返回 false */
fun openDownloaded(context: Context, file: File, name: String): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(Intent.createChooser(intent, "打开文件").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.isSuccess
}

/** 另存到系统「下载」目录（Android 10+ 用 MediaStore，不需要存储权限）。低版本返回 false */
suspend fun saveToDownloads(context: Context, file: File, name: String): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeLocalName(name))
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name).takeIf { it != "*/*" } ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/KukeChat")
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return@runCatching false
        true
    }.getOrDefault(false)
}
