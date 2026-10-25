package ink.kuke.chat.feature.shop

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.net.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/*
 * 气泡皮肤的专属字体（docs/shop.md 2.6，同电脑版 skin/skinFont.ts）。
 *
 * 服务端把字体放在样式旁边（cosmetics.bubble.font、商品的 font），不放进 style：老客户端遇到不认识的字段会把整件皮肤作废。
 * 形状是 { key, name, woff2, ttf }，安卓用 ttf。地址来自网络，这里只认 https、没有空白引号括号反斜杠尖括号的；key 只能是小写字母数字。
 *
 * 加载：第一次有气泡要用某款字体时才去下载（IO 线程），存到 cacheDir/skin-fonts/<key>-<地址的 sha256>.ttf，下次直接读本地。
 * 服务端换字体文件会换文件名，所以不用管过期；同一款字体换了新文件时，把旧文件删掉。
 * 下载中、下载失败都用原来的字体；失败了这个进程里不再重试。缺的字系统会落到默认字体上。
 */

class SkinFont(val key: String, val name: String, val url: String) {
    val id: String get() = "$key|$url"
}

private val FontKey = Regex("^[a-z0-9_-]{1,32}$")
private val SafeUrl = Regex("^https://[^\\s\"'()\\\\<>]{1,500}$")

/** 从服务端给的 font 对象里读出能用的那部分；不合法返回 null（按没有字体处理，皮肤照常画） */
fun readSkinFont(element: JsonElement?): SkinFont? {
    val obj = element as? JsonObject ?: return null
    val key = (obj["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val url = (obj["ttf"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!FontKey.matches(key) || url.length > 500 || !SafeUrl.matches(url)) return null
    val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: key
    return SkinFont(key, name, url)
}

object SkinFonts {
    /** 字体文件最大多少（现有的 1.4–3.6 MB），超过就当下载失败 */
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val DIR = "skin-fonts"

    /** id → 字体族；下载完才有。Compose 状态：字体到了，用它的气泡跟着重组 */
    private val ready = mutableStateMapOf<String, FontFamily>()
    /** 已经开始过的（下载中、成功、失败都算）。只在主线程读写 */
    private val started = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun familyOf(font: SkinFont): FontFamily? = ready[font.id]

    /** 确保某款字体已经加载（或正在下载）。重复调用只下载一次；失败了这个进程里不再试。主线程调 */
    fun ensure(context: Context, font: SkinFont) {
        if (!started.add(font.id)) return
        val app = context.applicationContext
        scope.launch {
            val family = runCatching { FontFamily(load(app, font)) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { ready[font.id] = family }
        }
    }

    private fun fileName(font: SkinFont): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(font.url.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${font.key}-${digest.take(24)}.ttf"
    }

    private suspend fun load(context: Context, font: SkinFont): Typeface {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, fileName(font))
        if (target.length() > 0) {
            typefaceOf(target)?.let { return it }
            target.delete()
        }
        val partial = File(dir, target.name + ".part")
        val http = (context as KukeApp).graph.api.http
        http.newCall(Request.Builder().url(font.url).build()).await().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) throw IOException("font ${response.code}")
            if (body.contentLength() > MAX_BYTES) throw IOException("font too large")
            var total = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("font too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        val typeface = typefaceOf(partial)
        if (typeface == null || !partial.renameTo(target)) {
            partial.delete()
            throw IOException("font unreadable")
        }
        // 同一款字体的旧文件（换过文件名的）用不到了；别的字体、正在下载的不动
        val sameKey = Regex("^${Regex.escape(font.key)}-[0-9a-f]{24}\\.ttf$")
        dir.listFiles()?.forEach { if (it.name != target.name && sameKey.matches(it.name)) it.delete() }
        return typeface
    }

    /** 读不出来（文件坏了）时系统会退回默认字体，这里当作失败 */
    private fun typefaceOf(file: File): Typeface? =
        runCatching { Typeface.createFromFile(file) }.getOrNull()?.takeIf { it != Typeface.DEFAULT }
}

/** 气泡文字用的专属字体族：没有字体、还没下载完、下载失败都是 null（用原来的字体）。第一次用到时开始下载 */
@Composable
fun rememberSkinFontFamily(font: SkinFont?): FontFamily? {
    if (font == null) return null
    val context = LocalContext.current
    LaunchedEffect(font.id) { SkinFonts.ensure(context, font) }
    return SkinFonts.familyOf(font)
}
