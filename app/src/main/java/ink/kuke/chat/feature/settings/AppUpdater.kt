package ink.kuke.chat.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import ink.kuke.chat.BuildConfig
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 应用内更新，走我们自己的发版服务（KukeChat-Server/docs/releases.md）：
 *   1. GET /api/v1/releases/latest?platform=android&channel=stable&current=<VERSION_NAME>（带客户端证明头，不需要登录）；
 *      新接口不可用（旧后端 404、网络问题）或还没有发布过版本时，退回 <apiRoot>/desktop-update.json 的 mobile_version / android_url；
 *   2. 用 OkHttp 把 APK 下到 cacheDir/updates，边下边回调进度，下完核对大小和 sha256；
 *   3. 用 FileProvider 交给系统安装器。没有「安装未知应用」权限时先带用户去设置页。
 */
object AppUpdater {
    const val PLATFORM = "android"
    const val CHANNEL = "stable"

    data class Info(
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String?,
        val hasUpdate: Boolean,
        /** 当前版本低于 min_supported_version：必须更新才能继续用 */
        val forced: Boolean = false,
        val build: Long? = null,
        val changelog: String = "",
        val publishedAt: String? = null,
        val size: Long? = null,
        val sha256: String? = null,
    )

    /** 更新日志页的一条 */
    data class Release(val version: String, val build: Long?, val changelog: String, val publishedAt: String?, val size: Long?)

    sealed interface InstallResult {
        data object Started : InstallResult
        data object NeedPermission : InstallResult
        data class Failed(val message: String) : InstallResult
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** 只取前面的数字部分（2.0.0-dev → 2.0.0），按段比较 */
    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }.split('.').map { it.toIntOrNull() ?: 0 }

    fun compare(left: String, right: String): Int {
        val a = parts(left)
        val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 发给服务器的当前版本：只要 x.y.z 部分（2.0.0-dev 这种本地构建后缀服务器不一定认） */
    private fun currentForServer(): String = parts(currentVersion).joinToString(".").ifEmpty { "0.0.0" }

    suspend fun check(api: Api): Info {
        val fromService = runCatching { checkService(api) }.getOrNull()
        if (fromService != null) return fromService
        return checkLegacy(api.http)
    }

    /** 新接口；没有已发布的版本时返回 null（交给旧元数据兜底） */
    private suspend fun checkService(api: Api): Info? {
        val current = currentForServer()
        val query = "platform=$PLATFORM&channel=$CHANNEL&current=${URLEncoder.encode(current, "UTF-8")}"
        val obj = api.get("/releases/latest?$query") as? JsonObject ?: return null
        val latest = obj["latest"] as? JsonObject ?: return null
        val artifact = latest["artifact"] as? JsonObject
            ?: (latest["artifacts"] as? JsonArray)?.firstOrNull() as? JsonObject
        val version = latest.string("version")?.removePrefix("v")?.trim().orEmpty()
        if (version.isEmpty()) return null
        val url = Endpoints.absolute(artifact?.string("url"))
        val available = obj.bool("update_available") == true && url != null
        return Info(
            currentVersion = currentVersion,
            latestVersion = version,
            downloadUrl = url,
            hasUpdate = available,
            forced = available && obj.bool("forced") == true,
            build = latest.long("build"),
            changelog = latest.string("changelog").orEmpty(),
            publishedAt = latest.string("published_at"),
            size = artifact?.long("size")?.takeIf { it > 0 },
            sha256 = artifact?.string("sha256")?.trim()?.lowercase()?.takeIf { it.length == 64 },
        )
    }

    /** 旧的 /desktop-update.json（在 API 根目录，不在 /api/v1 下，不需要令牌和证明头） */
    private suspend fun checkLegacy(http: OkHttpClient): Info = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(Endpoints.apiRoot + "/desktop-update.json").header("Cache-Control", "no-cache").build()
        val text = http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("更新检查失败：HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val obj = KcJson.parseToJsonElement(text) as? JsonObject ?: throw IOException("更新信息格式不对")
        val latest = (obj.string("mobile_version") ?: obj.string("version"))?.removePrefix("v")?.trim().orEmpty()
        val url = Endpoints.absolute(obj.string("android_url") ?: obj.string("url"))
        val newer = latest.isNotEmpty() && compare(latest, currentVersion) > 0 && url != null
        Info(currentVersion, latest.ifEmpty { currentVersion }, url, newer)
    }

    /** 更新日志：已发布的安卓版本，从新到旧 */
    suspend fun history(api: Api, limit: Int = 30): List<Release> {
        val obj = api.get("/releases?platform=$PLATFORM&channel=$CHANNEL&limit=$limit") as? JsonObject ?: return emptyList()
        val items = obj["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val version = item.string("version") ?: return@mapNotNull null
            val artifact = item["artifact"] as? JsonObject ?: (item["artifacts"] as? JsonArray)?.firstOrNull() as? JsonObject
            Release(version, item.long("build"), item.string("changelog").orEmpty(), item.string("published_at"), artifact?.long("size"))
        }
    }

    /** 下载安装包，返回本地文件。onProgress 给 0..1（不知道总大小时给 -1）。下完核对大小和 sha256，不对就删掉并抛错 */
    suspend fun download(context: Context, http: OkHttpClient, info: Info, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = info.downloadUrl ?: throw IOException("没有可下载的安装包")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.endsWith(".apk") || it.name.endsWith(".part") }?.forEach { it.delete() }
        val partial = File(dir, "kukechat-update.part")
        val target = File(dir, "kukechat-update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).build()).await().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) throw IOException("下载失败：HTTP ${response.code}")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size ?: -1L
            var downloaded = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastEmit > 64 * 1024) {
                            lastEmit = downloaded
                            onProgress(if (total > 0) (downloaded.toFloat() / total).coerceAtMost(1f) else -1f)
                        }
                    }
                }
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        val sizeBad = info.size != null && partial.length() != info.size
        val hashBad = info.sha256 != null && actualHash != info.sha256
        if (sizeBad || hashBad) {
            partial.delete()
            throw IOException("安装包校验失败，请重新下载")
        }
        if (info.size == null && partial.length() < 1024L * 1024L) {
            partial.delete()
            throw IOException("下载的安装包不完整")
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("保存安装包失败")
        }
        onProgress(1f)
        target
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, apk: File): InstallResult {
        if (!canInstall(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return InstallResult.NeedPermission
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            InstallResult.Started
        }.getOrElse { InstallResult.Failed("无法打开安装器（${it.message ?: "未配置 FileProvider"}）") }
    }

    /** 1536000 → "1.5 MB" */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** "2026-10-16T08:00:00Z" → "2026-10-16" */
    fun shortDate(iso: String?): String? = iso?.takeIf { it.length >= 10 }?.substring(0, 10)

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
