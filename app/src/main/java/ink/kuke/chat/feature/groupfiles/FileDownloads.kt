package ink.kuke.chat.feature.groupfiles

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.ClientProof
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** 一个群文件在系统下载器里的状态（界面读 FileDownloads.states） */
sealed interface FileDownload {
    /** 下载中；total 不知道时 ≤ 0。paused：系统在等网络 / 等重试 */
    data class Running(val done: Long, val total: Long, val paused: Boolean = false) : FileDownload

    data class Done(val uri: Uri, val mime: String?) : FileDownload

    data class Failed(val reason: Int) : FileDownload
}

/** 下载进度 0..1；总大小未知时用消息里记的大小兜底，再不行返回 null（画不确定进度条） */
fun FileDownload.Running.ratio(fallbackSize: Long): Float? {
    val total = if (total > 0) total else fallbackSize
    return if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "3g2")

/** 能在应用里直接播放的视频（ExoPlayer 认的容器） */
fun isVideoFile(name: String, contentType: String?): Boolean =
    contentType?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

internal fun mimeOf(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "apk") return APK_MIME
    if (ext == "mkv") return "video/x-matroska"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

private const val APK_MIME = "application/vnd.android.package-archive"

/** 文件名清洗成本地能用的名字 */
internal fun safeLocalName(name: String): String = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimStart('.').ifBlank { "file" }.take(120)

/** 群文件的下载地址：直连开着时是 5 分钟的预签名 GET（匿名可用），没开时是要鉴权头的后端下载接口 */
class FileSource(val url: String, val headers: Map<String, String>)

/**
 * 群文件下载：交给系统 DownloadManager（后台下载、通知栏有进度，关面板 / 退出聊天 / 切后台都不影响），
 * 存到公共「下载/KukeChat」（Android 9 及以下存到应用自己的外部目录，不用申请存储权限）。
 *
 * 文件 id → 下载任务 id 记在 SharedPreferences 里：同一个文件重复点不会再下一份，下完了直接打开。
 * 进度靠轮询 DownloadManager（只在有任务在跑时轮询），结果放进 [states] 给面板和文件卡片画进度条。
 */
object FileDownloads {
    private const val PREFS = "group_file_downloads"
    private const val POLL_MS = 600L

    /** fileId → 状态。只在主线程写 */
    val states = mutableStateMapOf<Long, FileDownload>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var poller: Job? = null
    private val starting = mutableSetOf<Long>()
    /** 点了「打开」但还没下完的：下完且应用在前台时自动打开 */
    private val openWhenDone = mutableMapOf<Long, String>()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun manager(context: Context) = context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun recordedId(context: Context, fileId: Long): Long? =
        prefs(context).getLong(fileId.toString(), -1L).takeIf { it >= 0 }

    private fun forget(context: Context, fileId: Long) {
        prefs(context).edit().remove(fileId.toString()).apply()
        states.remove(fileId)
    }

    /** 查一个下载任务；任务被用户删掉了（或下好的文件被删了）返回 null */
    private suspend fun query(context: Context, downloadId: Long): FileDownload? = withContext(Dispatchers.IO) {
        val dm = manager(context)
        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(downloadId)) }.getOrNull() ?: return@withContext null
        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = runCatching { dm.getUriForDownloadedFile(downloadId) }.getOrNull() ?: return@withContext null
                    // 用户在文件管理里删了文件：当没下载过
                    val exists = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
                    if (!exists) return@withContext null
                    FileDownload.Done(uri, runCatching { dm.getMimeTypeForDownloadedFile(downloadId) }.getOrNull())
                }
                DownloadManager.STATUS_FAILED -> FileDownload.Failed(reason)
                DownloadManager.STATUS_PAUSED -> FileDownload.Running(done, total, paused = true)
                else -> FileDownload.Running(done, total)
            }
        }
    }

    /** 现查一个文件的下载状态（播放页用：下好了就放本地文件） */
    suspend fun current(context: Context, fileId: Long): FileDownload? {
        val app = context.applicationContext
        val id = recordedId(app, fileId) ?: return null
        val state = query(app, id)
        if (state == null) forget(app, fileId) else states[fileId] = state
        if (state is FileDownload.Running) ensurePolling(app)
        return state
    }

    /** 读一个文件当前的下载状态（文件卡片、面板出现时调一次；在跑就开始轮询） */
    fun refresh(context: Context, fileId: Long) {
        val app = context.applicationContext
        val id = recordedId(app, fileId) ?: return
        scope.launch {
            val state = query(app, id)
            if (state == null) forget(app, fileId) else states[fileId] = state
            if (state is FileDownload.Running) ensurePolling(app)
        }
    }

    private fun ensurePolling(app: Context) {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val running = states.filterValues { it is FileDownload.Running }.keys.toList()
                if (running.isEmpty()) break
                for (fileId in running) {
                    val id = recordedId(app, fileId)
                    val state = id?.let { query(app, it) }
                    if (state == null) {
                        forget(app, fileId)
                        openWhenDone.remove(fileId)
                        continue
                    }
                    states[fileId] = state
                    when (state) {
                        is FileDownload.Done -> openWhenDone.remove(fileId)?.let { name ->
                            // 应用在后台时系统不让弹界面：不自动打开，用户点通知栏就能打开
                            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) open(app, state, name)
                        }
                        is FileDownload.Failed -> {
                            openWhenDone.remove(fileId)
                            toast(app, "下载失败，请重试")
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * 开始下载（或复用已有的）。openAfter：下完自动用系统应用打开（已经下好的立刻打开）。
     * 在自己的作用域里跑：调用方的面板关了也不会打断。
     */
    fun download(graph: AppGraph, context: Context, conversationId: Long, fileId: Long, name: String, size: Long, openAfter: Boolean) {
        val app = context.applicationContext
        if (fileId in starting) return
        starting += fileId
        scope.launch {
            try {
                val existing = recordedId(app, fileId)?.let { query(app, it) }
                when (existing) {
                    is FileDownload.Done -> {
                        states[fileId] = existing
                        if (openAfter) open(app, existing, name) else toast(app, "已经下载过了，在 下载/KukeChat 里")
                        return@launch
                    }
                    is FileDownload.Running -> {
                        states[fileId] = existing
                        if (openAfter) openWhenDone[fileId] = name
                        toast(app, if (openAfter) "正在下载，下完会自动打开" else "正在下载，可在通知栏查看进度")
                        ensurePolling(app)
                        return@launch
                    }
                    is FileDownload.Failed -> recordedId(app, fileId)?.let { old -> runCatching { manager(app).remove(old) } }
                    null -> Unit
                }
                val source = resolveSource(graph, conversationId, fileId)
                val downloadId = withContext(Dispatchers.IO) { enqueue(app, source, name) }
                prefs(app).edit().putLong(fileId.toString(), downloadId).apply()
                states[fileId] = FileDownload.Running(0, size)
                if (openAfter) openWhenDone[fileId] = name
                toast(app, if (openAfter) "已开始下载，下完会自动打开" else "已开始下载，可在通知栏查看进度")
                ensurePolling(app)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                toast(app, error.userMessage())
            } finally {
                starting -= fileId
            }
        }
    }

    /** 取下载 / 播放用的地址：优先预签名直链（每次现取，5 分钟过期），服务器没开直连时退回带鉴权头的下载接口 */
    suspend fun resolveSource(graph: AppGraph, conversationId: Long, fileId: Long): FileSource {
        try {
            val result = graph.api.get("/conversations/$conversationId/files/$fileId/link")
            val url = (result as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            if (!url.isNullOrBlank()) return FileSource(url, emptyMap())
        } catch (error: ApiException) {
            // 409 direct_download_unavailable（服务器没开直连）/ 404 旧服务端没有这个接口：走下载接口；其余（过期、没权限）照常报错
            if (error.status != 409 && !(error.status == 404 && error.code == null)) throw error
        }
        return backendSource(graph, conversationId, fileId)
    }

    /** 后端下载接口（要令牌 + 客户端证明头；证明头 5 分钟有效，每次请求现算） */
    fun backendSource(graph: AppGraph, conversationId: Long, fileId: Long): FileSource {
        val apiPath = "/conversations/$conversationId/files/$fileId/download"
        val headers = ClientProof.headers("GET", apiPath).toMutableMap()
        graph.session.token?.let { headers["Authorization"] = "Bearer $it" }
        return FileSource(Endpoints.api + apiPath, headers)
    }

    private fun enqueue(app: Context, source: FileSource, name: String): Long {
        val local = safeLocalName(name)
        val request = DownloadManager.Request(Uri.parse(source.url))
            .setTitle(name)
            .setDescription("KukeChat 群文件")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        mimeOf(name)?.let { request.setMimeType(it) }
        source.headers.forEach { (key, value) -> request.addRequestHeader(key, value) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "KukeChat/$local")
        } else {
            // Android 9 及以下写公共目录要存储权限：放应用自己的外部目录（卸载时一起删）
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KukeChat").apply { mkdirs() }
            request.setDestinationUri(Uri.fromFile(File(dir, local)))
        }
        return manager(app).enqueue(request)
    }

    /** 用系统应用打开下好的文件；安装包走系统安装流程 */
    fun open(context: Context, done: FileDownload.Done, name: String) {
        val app = context.applicationContext
        val mime = mimeOf(name) ?: done.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "*/*"
        if (mime == APK_MIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            toast(app, "请允许 KukeChat 安装应用，返回后再点「打开」")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(done.uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(app, "没有能打开这个文件的应用，文件在 下载/KukeChat 里")
        } catch (error: Exception) {
            toast(app, "打不开这个文件（${error.message ?: "未知错误"}）")
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
