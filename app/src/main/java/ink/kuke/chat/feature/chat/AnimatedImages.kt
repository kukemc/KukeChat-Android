package ink.kuke.chat.feature.chat

import android.content.Context
import android.net.Uri

/**
 * 动图格式：按文件头认，不看扩展名和系统给的 MIME。
 * QQ / 微信存下来的表情经常叫 .jpg、MIME 报 image/jpeg（或 webp、或干脆没有），里面其实是 GIF / 动态 WebP / APNG；
 * 按 MIME 判断会把它当静态图压成 JPEG，别人收到就不动了。
 */
internal enum class AnimatedFormat(val mime: String, val ext: String) {
    Gif("image/gif", "gif"),
    WebP("image/webp", "webp"),
    Png("image/png", "png"),
}

internal object AnimatedImages {
    /** 动图（发消息、加收藏表情）原样上传的上限，同服务端 MAX_IMAGE_UPLOAD_BYTES；超了直接报错，不偷偷压成静态图 */
    const val MAX_BYTES = 3 * 1024 * 1024
    /** 判断用的文件头长度：APNG 的 acTL 在第一个 IDAT 之前，正常文件几 KB 内就能看到 */
    const val HEAD_BYTES = 256 * 1024

    const val TOO_LARGE = "动图超过 3MB，发不了"

    /**
     * 看文件头是不是动图：GIF（GIF87a / GIF89a，单帧 GIF 也当动图原样传）、带动画标记的 WebP、带 acTL 的 PNG（APNG）。
     * 静态的 WebP / PNG / JPEG 返回 null。[bytes] 可以只是文件开头一段（读不全的块就当没有）。
     */
    fun detect(bytes: ByteArray): AnimatedFormat? = when {
        isGif(bytes) -> AnimatedFormat.Gif
        isAnimatedWebP(bytes) -> AnimatedFormat.WebP
        isApng(bytes) -> AnimatedFormat.Png
        else -> null
    }

    /** 只读文件开头一段来判断（不把整张图读进内存）；读不到就当不是动图 */
    fun detect(context: Context, uri: Uri): AnimatedFormat? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(HEAD_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                detect(buffer.copyOf(read))
            }
        }.getOrNull()

    private fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && b.ascii(0, 4) == "GIF8" && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) && b[5] == 'a'.code.toByte()

    /** RIFF....WEBP；VP8X 块的标记字节（偏移 20）带动画位 0x02，或者文件里有 ANIM 块 */
    private fun isAnimatedWebP(b: ByteArray): Boolean {
        if (b.size < 16 || b.ascii(0, 4) != "RIFF" || b.ascii(8, 4) != "WEBP") return false
        if (b.ascii(12, 4) == "VP8X" && b.size > 20 && (b[20].toInt() and 0x02) != 0) return true
        // 逐块看有没有 ANIM：块 = 4 字节类型 + 4 字节小端长度 + 数据（奇数长度补一个字节）
        var offset = 12
        while (offset + 8 <= b.size) {
            val type = b.ascii(offset, 4)
            if (type == "ANIM" || type == "ANMF") return true
            val size = b.leInt(offset + 4).toLong() and 0xFFFFFFFFL
            val next = offset + 8L + size + (size and 1L)
            if (next <= offset || next > Int.MAX_VALUE) return false
            offset = next.toInt()
        }
        return false
    }

    /** PNG 签名，且第一个 IDAT 之前有 acTL 块（块 = 4 字节大端长度 + 4 字节类型 + 数据 + 4 字节 CRC） */
    private fun isApng(b: ByteArray): Boolean {
        if (b.size < 8 || !PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] }) return false
        var offset = 8
        while (offset + 8 <= b.size) {
            val length = b.beInt(offset).toLong() and 0xFFFFFFFFL
            when (b.ascii(offset + 4, 4)) {
                "acTL" -> return true
                "IDAT", "IEND" -> return false
            }
            val next = offset + 12L + length
            if (next > Int.MAX_VALUE) return false
            offset = next.toInt()
        }
        return false
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        if (offset + length > size) "" else String(this, offset, length, Charsets.ISO_8859_1)

    private fun ByteArray.leInt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.beInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)
}
