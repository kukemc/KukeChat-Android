package ink.kuke.chat.feature.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import ink.kuke.chat.feature.groupfiles.PickedFile
import ink.kuke.chat.feature.groupfiles.describePicked
import java.io.File
import java.util.UUID

/**
 * 从别的应用分享进来的内容（ACTION_SEND / ACTION_SEND_MULTIPLE）。
 * 文字整段保留（B 站分享是「【标题】 https://b23.tv/xxx」，标题和链接都要）；附件按类型分成图片 / 视频 / 文件。
 */
class SharePayload(
    /** 要发的文字（已截到服务端上限以内） */
    val text: String,
    /** 原文超过了一条消息的上限，被截断了 */
    val textTruncated: Boolean,
    val items: List<ShareItem>,
    /** 读不了 / 不支持的附件（界面上提一句） */
    val skipped: List<String>,
) {
    val images: List<ShareItem> get() = items.filter { it.kind == ShareKind.Image }
    /** 只能走群文件的：视频和其他文件 */
    val files: List<ShareItem> get() = items.filter { it.kind != ShareKind.Image }
    val hasText: Boolean get() = text.isNotBlank()
    val isEmpty: Boolean get() = text.isBlank() && items.isEmpty()
    /** 私聊能发的部分（文字、图片） */
    val hasDirectSendable: Boolean get() = hasText || images.isNotEmpty()

    /** 「2 张图片 · 1 个视频 · 3 个文件」 */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (hasText) parts += if (isLinkOnly()) "链接" else "文字"
        images.size.takeIf { it > 0 }?.let { parts += "$it 张图片" }
        items.count { it.kind == ShareKind.Video }.takeIf { it > 0 }?.let { parts += "$it 个视频" }
        items.count { it.kind == ShareKind.File }.takeIf { it > 0 }?.let { parts += "$it 个文件" }
        return parts.joinToString(" · ")
    }

    /** 文字里的第一个链接（预览卡片上显示域名） */
    fun firstLink(): String? = LinkRegex.find(text)?.value

    private fun isLinkOnly(): Boolean = text.trim().let { it.isNotEmpty() && LinkRegex.matches(it) }

    companion object {
        /** 服务端一条消息最多 2000 字（MessageCreate.content） */
        const val MAX_TEXT = 2000
        /** 一次最多收几个附件（和聊天里选文件一致） */
        const val MAX_ITEMS = 18
        private val LinkRegex = Regex("""https?://[^\s<>"'，。！？、）】]+""")

        /** 解析分享 Intent；不是分享（或什么都没带）时返回 null。要查 ContentResolver，放 IO 线程调 */
        fun parse(context: Context, intent: Intent?): SharePayload? {
            intent ?: return null
            if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
            val texts = buildList {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::add)
                runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull()?.forEach { add(it.toString()) }
            }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            var text = texts.joinToString("\n")
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            // 有的应用把标题放在 EXTRA_SUBJECT、正文只有链接：拼在前面；正文里已经有标题就不重复
            if (subject.isNotEmpty() && !text.contains(subject)) text = if (text.isEmpty()) subject else "$subject\n$text"

            val uris = LinkedHashSet<Uri>()
            streamsOf(intent).forEach { uris += it }
            // 只放在 ClipData 里的（部分应用这样分享）
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    item.uri?.let { uris += it }
                    if (text.isEmpty() && item.uri == null) item.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { text = it }
                }
            }

            val skipped = mutableListOf<String>()
            val items = uris.filter { uri ->
                safe(context, uri).also { if (!it) skipped += "不支持的来源" }
            }.mapNotNull { uri ->
                val described = runCatching { describePicked(context, uri) }.getOrNull()
                if (described == null) {
                    skipped += "读不到的附件"
                    return@mapNotNull null
                }
                val mime = described.mime.takeIf { it != "application/octet-stream" } ?: intent.type?.takeIf { !it.endsWith("/*") } ?: described.mime
                ShareItem(uri, described.name, described.size, mime, kindOf(mime, described.name))
            }
            val kept = items.take(MAX_ITEMS)
            if (items.size > MAX_ITEMS) skipped += "超出的 ${items.size - MAX_ITEMS} 个附件"
            val truncated = text.length > MAX_TEXT
            return SharePayload(text.take(MAX_TEXT), truncated, kept, skipped)
        }

        @Suppress("DEPRECATION")
        private fun streamsOf(intent: Intent): List<Uri> {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                val list = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else runCatching { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
                return list.orEmpty().filterNotNull()
            }
            val single = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
            return listOfNotNull(single)
        }

        /** 不读自己的私有文件：别的应用可能塞一个指向本应用数据目录的 file:// 或本应用 FileProvider 的地址 */
        private fun safe(context: Context, uri: Uri): Boolean = when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> uri.authority != "${context.packageName}.fileprovider"
            ContentResolver.SCHEME_FILE -> {
                val path = runCatching { File(uri.path ?: "").canonicalPath }.getOrNull()
                val data = runCatching { File(context.applicationInfo.dataDir).canonicalPath }.getOrNull()
                path != null && data != null && !path.startsWith(data)
            }
            else -> false
        }

        private fun kindOf(mime: String, name: String): ShareKind {
            val lower = mime.lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                // SVG 解不成位图，当文件发
                lower.startsWith("image/") && lower != "image/svg+xml" -> ShareKind.Image
                lower.startsWith("video/") -> ShareKind.Video
                lower == "application/octet-stream" && ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif") -> ShareKind.Image
                lower == "application/octet-stream" && ext in setOf("mp4", "mov", "mkv", "webm", "3gp") -> ShareKind.Video
                else -> ShareKind.File
            }
        }
    }
}

enum class ShareKind { Image, Video, File }

/** 一个附件：来源 Uri（别的应用临时授权给我们的），文件名、大小（读不到为 -1）、类型 */
class ShareItem(val uri: Uri, val name: String, val size: Long, val mime: String, val kind: ShareKind)

/**
 * 分享附件的本地副本：别的应用给的读权限在分享页关掉后可能就失效了，后台上传要用的东西得先拷进自己的缓存目录（cache/share/<批次>/）。
 * 交给上传的是本应用 FileProvider 的 content:// 地址（能查到文件名、大小和类型，和聊天里选的文件走同一套代码）。
 *
 * 什么时候删：群文件传完就删（见 ShareSender 的盯梢）；图片发完后聊天里还会用本地这张来画（LocalImages），留到下次进程启动再清。
 */
object ShareCache {
    private const val TAG = "ShareCache"
    private const val DIR = "share"
    /** 图片读进内存再压缩，太大的原图不收 */
    const val MAX_IMAGE_BYTES = 40L * 1024 * 1024

    fun newBatch(context: Context): File = File(File(context.cacheDir, DIR), UUID.randomUUID().toString().replace("-", "")).apply { mkdirs() }

    /** 拷一份；超过 [limit] 字节就放弃（抛 [TooLarge]）。要在 IO 线程调 */
    fun copy(context: Context, batch: File, item: ShareItem, limit: Long): PickedFile {
        if (item.size > limit) throw TooLarge(item.name, limit)
        val safeName = item.name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(120).ifBlank { "file" }
        val target = uniqueFile(batch, safeName)
        var total = 0L
        try {
            val input = context.contentResolver.openInputStream(item.uri) ?: throw ShareCopyError("读不到「${item.name}」")
            input.use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) throw TooLarge(item.name, limit)
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            if (e is ShareCopyError) throw e
            if (e is SecurityException) throw ShareCopyError("没有权限读取「${item.name}」，请在原应用里重新分享")
            Log.w(TAG, "copy failed", e)
            throw ShareCopyError("读不到「${item.name}」")
        }
        if (total == 0L) {
            target.delete()
            throw ShareCopyError("「${item.name}」是空文件，不能发送")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return PickedFile(uri, item.name, total, item.mime)
    }

    /** 本地副本对应的文件（删副本用）；不是本缓存目录里的返回 null */
    fun fileOf(context: Context, uri: Uri): File? {
        if (uri.authority != "${context.packageName}.fileprovider") return null
        // FileProvider 地址：/share/<批次>/<文件名>（name = share，见 res/xml/file_paths.xml）
        val segments = uri.pathSegments
        if (segments.size < 3 || segments[0] != DIR) return null
        val root = File(context.cacheDir, DIR)
        val file = File(File(root, segments[1]), segments.drop(2).joinToString("/"))
        return file.takeIf { runCatching { it.canonicalPath.startsWith(root.canonicalPath) }.getOrDefault(false) }
    }

    fun delete(context: Context, uri: Uri) {
        val file = fileOf(context, uri) ?: return
        file.delete()
        file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
    }

    fun deleteBatch(batch: File) {
        batch.deleteRecursively()
    }

    /**
     * 清掉早于 [olderThanMs] 的批次。进程刚启动时内存里没有任何东西引用这些副本（发件箱、LocalImages 都只在内存），
     * 只留最近一会儿的（同一进程里刚拷好、还在准备发送的）。
     */
    fun sweep(context: Context, olderThanMs: Long) {
        val root = File(context.cacheDir, DIR)
        val cutoff = System.currentTimeMillis() - olderThanMs
        runCatching {
            root.listFiles()?.forEach { batch -> if (batch.lastModified() < cutoff) batch.deleteRecursively() }
        }.onFailure { Log.w(TAG, "sweep failed", it) }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        return file
    }

    class TooLarge(val name: String, val limit: Long) :
        ShareCopyError("「$name」超过 ${ink.kuke.chat.feature.groupplus.formatBytes(limit)}，不能发送")
}

/** 拷贝附件失败：message 直接给人看 */
open class ShareCopyError(message: String) : Exception(message)
