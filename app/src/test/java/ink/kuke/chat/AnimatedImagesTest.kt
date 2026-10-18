package ink.kuke.chat

import ink.kuke.chat.feature.chat.AnimatedFormat
import ink.kuke.chat.feature.chat.AnimatedImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 按文件头认动图：扩展名 / MIME 不可信（QQ、微信存的动图常叫 .jpg） */
class AnimatedImagesTest {
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    private fun be(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun bytes(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun webpChunk(type: String, payload: ByteArray) = bytes(ascii(type), le(payload.size), payload, if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = bytes(ascii("WEBP"), *chunks)
        return bytes(ascii("RIFF"), le(body.size), body)
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun pngChunk(type: String, payload: ByteArray) = bytes(be(payload.size), ascii(type), payload, ByteArray(4))

    @Test
    fun gifIsAnimatedWhateverTheName() {
        assertEquals(AnimatedFormat.Gif, AnimatedImages.detect(bytes(ascii("GIF89a"), ByteArray(20))))
        assertEquals(AnimatedFormat.Gif, AnimatedImages.detect(bytes(ascii("GIF87a"), ByteArray(20))))
        assertNull(AnimatedImages.detect(ascii("GIF")))
    }

    @Test
    fun jpegIsStatic() {
        assertNull(AnimatedImages.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)))
        assertNull(AnimatedImages.detect(ByteArray(0)))
    }

    @Test
    fun webpByVp8xFlagOrAnimChunk() {
        // VP8X 标记字节（文件偏移 20）带 0x02
        val flagged = webp(webpChunk("VP8X", byteArrayOf(0x02, 0, 0, 0, 9, 0, 0, 9, 0, 0)))
        assertEquals(AnimatedFormat.WebP, AnimatedImages.detect(flagged))
        // 标记没开，但后面有 ANIM 块
        val withAnim = webp(webpChunk("VP8X", ByteArray(10)), webpChunk("ANIM", ByteArray(6)))
        assertEquals(AnimatedFormat.WebP, AnimatedImages.detect(withAnim))
        // 静态：VP8X 只有透明度标记 0x10，后面是 VP8L 图像数据（奇数长度要补一个字节）
        val still = webp(webpChunk("VP8X", byteArrayOf(0x10, 0, 0, 0, 9, 0, 0, 9, 0, 0)), webpChunk("ALPH", ByteArray(5)), webpChunk("VP8L", ByteArray(12)))
        assertNull(AnimatedImages.detect(still))
        assertNull(AnimatedImages.detect(webp(webpChunk("VP8 ", ByteArray(30)))))
    }

    @Test
    fun apngNeedsActlBeforeIdat() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val apng = bytes(pngSignature, ihdr, pngChunk("acTL", ByteArray(8)), pngChunk("IDAT", ByteArray(20)), pngChunk("IEND", ByteArray(0)))
        assertEquals(AnimatedFormat.Png, AnimatedImages.detect(apng))
        val still = bytes(pngSignature, ihdr, pngChunk("IDAT", ByteArray(20)), pngChunk("acTL", ByteArray(8)), pngChunk("IEND", ByteArray(0)))
        assertNull(AnimatedImages.detect(still))
        // 只读到一半的文件头：读不全就当静态
        assertNull(AnimatedImages.detect(bytes(pngSignature, ihdr).copyOf(20)))
    }

    @Test
    fun formatCarriesUploadTypeAndExtension() {
        assertEquals("image/gif" to "gif", AnimatedFormat.Gif.mime to AnimatedFormat.Gif.ext)
        assertEquals("image/webp" to "webp", AnimatedFormat.WebP.mime to AnimatedFormat.WebP.ext)
        assertEquals("image/png" to "png", AnimatedFormat.Png.mime to AnimatedFormat.Png.ext)
    }
}
