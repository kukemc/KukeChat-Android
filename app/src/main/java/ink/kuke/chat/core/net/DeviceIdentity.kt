package ink.kuke.chat.core.net

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * 设备标识（防小号）：/api/v1 下每个请求都带上，服务端据此限制同一台设备的注册数、封禁设备。
 * - X-Kuke-Device：服务端发的不透明令牌（kd_xxx.yyy）。本地没有就不带；响应头 X-Kuke-Device-Issue 给了新的就存下来。
 * - X-Kuke-Machine：sha256("KukeChat-machine-v1|" + ANDROID_ID)，只发哈希不发原值；拿不到 ANDROID_ID 就不带。
 * - X-Kuke-Platform：固定 android。
 *
 * 令牌存两份：应用文件目录里一个文件 + SharedPreferences，哪份还在就用哪份，并把缺的那份补回去
 * （清缓存 / 备份恢复只丢一份的情况都能接上）。读写都加锁，拦截器在 OkHttp 的线程里调。
 */
class DeviceIdentity(
    private val file: File,
    private val prefsRead: () -> String?,
    private val prefsWrite: (String) -> Unit,
    machineSource: () -> String?,
) {
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var token: String? = null

    /** 机器码哈希：只算一次 */
    val machine: String? by lazy {
        runCatching { machineSource() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(::machineHash)
    }

    /** 当前设备令牌（第一次调用时从磁盘读回来） */
    fun token(): String? {
        ensureLoaded()
        return token
    }

    /** 服务端给了令牌（响应头或 /auth/device）：格式对才存。返回是否换了新令牌 */
    fun accept(candidate: String?): Boolean {
        val value = candidate?.trim()
        if (!isValidToken(value)) return false
        synchronized(lock) {
            ensureLoaded()
            if (token == value) return false
            token = value
            writeFile(value!!)
            runCatching { prefsWrite(value) }
        }
        return true
    }

    /** 要附加到 /api/v1 请求上的头 */
    fun headers(): Map<String, String> = buildMap {
        token()?.let { put(HEADER_DEVICE, it) }
        machine?.let { put(HEADER_MACHINE, it) }
        put(HEADER_PLATFORM, PLATFORM)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val fromFile = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.trim()?.takeIf(::isValidToken)
            val fromPrefs = runCatching { prefsRead() }.getOrNull()?.trim()?.takeIf(::isValidToken)
            // 文件那份优先；另一份缺了或不一样就补齐
            val chosen = fromFile ?: fromPrefs
            if (chosen != null) {
                if (fromFile != chosen) writeFile(chosen)
                if (fromPrefs != chosen) runCatching { prefsWrite(chosen) }
            }
            token = chosen
            loaded = true
        }
    }

    /** 先写临时文件再改名，写一半断电也不会留下坏文件 */
    private fun writeFile(value: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(value)
            if (!temp.renameTo(file)) {
                file.writeText(value)
                temp.delete()
            }
        }
    }

    companion object {
        const val HEADER_DEVICE = "X-Kuke-Device"
        const val HEADER_MACHINE = "X-Kuke-Machine"
        const val HEADER_PLATFORM = "X-Kuke-Platform"
        const val HEADER_ISSUE = "X-Kuke-Device-Issue"
        const val PLATFORM = "android"

        private const val PREFS = "device_identity"
        private const val PREFS_KEY = "device_token"
        private val TOKEN = Regex("^kd_[A-Za-z0-9_-]{10,40}\\.[A-Za-z0-9_-]{10,40}$")

        fun isValidToken(value: String?): Boolean = value != null && value.length <= 64 && TOKEN.matches(value)

        /** sha256("KukeChat-machine-v1|" + ANDROID_ID)，64 位小写十六进制 */
        fun machineHash(androidId: String): String =
            MessageDigest.getInstance("SHA-256").digest("KukeChat-machine-v1|$androidId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        @SuppressLint("HardwareIds")
        fun create(context: Context): DeviceIdentity {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DeviceIdentity(
                file = File(app.filesDir, "device_token"),
                prefsRead = { prefs.getString(PREFS_KEY, null) },
                prefsWrite = { prefs.edit().putString(PREFS_KEY, it).apply() },
                machineSource = { Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) },
            )
        }
    }
}
