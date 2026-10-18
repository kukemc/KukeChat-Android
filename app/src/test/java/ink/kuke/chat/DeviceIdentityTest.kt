package ink.kuke.chat

import ink.kuke.chat.core.net.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceIdentityTest {
    private val good = "kd_AAAAAAAAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB"

    private fun identity(dir: File, prefs: MutableMap<String, String>, androidId: String? = "abc") = DeviceIdentity(
        file = File(dir, "device_token"),
        prefsRead = { prefs["t"] },
        prefsWrite = { prefs["t"] = it },
        machineSource = { androidId },
    )

    @Test
    fun tokenFormatIsChecked() {
        assertTrue(DeviceIdentity.isValidToken(good))
        assertTrue(DeviceIdentity.isValidToken("kd_abc-_DEF12.xyz_-98765"))
        assertFalse(DeviceIdentity.isValidToken(null))
        assertFalse(DeviceIdentity.isValidToken(""))
        assertFalse(DeviceIdentity.isValidToken("kd_short.AAAAAAAAAAAA"))
        assertFalse(DeviceIdentity.isValidToken("xx_AAAAAAAAAAAA.BBBBBBBBBBBB"))
        assertFalse(DeviceIdentity.isValidToken("kd_AAAAAAAAAAAA.BBBB BBBBBBB"))
        // 两段都在 40 以内但总长超过 64
        assertFalse(DeviceIdentity.isValidToken("kd_" + "A".repeat(40) + "." + "B".repeat(30)))
    }

    @Test
    fun machineHashIsSha256OfPrefixedAndroidId() {
        // 期望值：python -c "import hashlib;print(hashlib.sha256(b'KukeChat-machine-v1|abc').hexdigest())"
        assertEquals("712dd1fa50fffe44e331d639a8d779b3c498d2ad10ed55734255cfba5afc319b", DeviceIdentity.machineHash("abc"))
        assertNull(identity(Files.createTempDirectory("kd").toFile(), mutableMapOf(), androidId = null).machine)
        assertNull(identity(Files.createTempDirectory("kd").toFile(), mutableMapOf(), androidId = " ").machine)
    }

    @Test
    fun acceptPersistsToBothCopies() {
        val dir = Files.createTempDirectory("kd").toFile()
        val prefs = mutableMapOf<String, String>()
        val id = identity(dir, prefs)
        assertNull(id.token())
        assertFalse(id.accept("bogus"))
        assertTrue(id.accept(good))
        assertFalse(id.accept(good))
        assertEquals(good, File(dir, "device_token").readText())
        assertEquals(good, prefs["t"])
        assertEquals(good, id.headers()[DeviceIdentity.HEADER_DEVICE])
        assertEquals("android", id.headers()[DeviceIdentity.HEADER_PLATFORM])
    }

    @Test
    fun restoresFromWhicheverCopySurvives() {
        // 只剩 SharedPreferences：读回来并补写文件
        val dir = Files.createTempDirectory("kd").toFile()
        val prefs = mutableMapOf("t" to good)
        assertEquals(good, identity(dir, prefs).token())
        assertEquals(good, File(dir, "device_token").readText())

        // 只剩文件：读回来并补写 SharedPreferences
        val dir2 = Files.createTempDirectory("kd").toFile()
        File(dir2, "device_token").writeText(good)
        val prefs2 = mutableMapOf<String, String>()
        assertEquals(good, identity(dir2, prefs2).token())
        assertEquals(good, prefs2["t"])

        // 坏数据当作没有
        val dir3 = Files.createTempDirectory("kd").toFile()
        File(dir3, "device_token").writeText("garbage")
        assertNull(identity(dir3, mutableMapOf("t" to "also-bad")).token())
    }
}
