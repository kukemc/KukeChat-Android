package ink.kuke.chat.core.data

import ink.kuke.chat.core.net.KcJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 页面数据的「上次看到的样子」：再次打开页面先拿它直接画出来，再静默拉最新的覆盖（stale-while-revalidate）。
 *
 * - 内存一份，按 key 存任意对象；带 serializer 的另存磁盘一份（`screen-cache/<用户 id>/`），冷启动也不空白。
 * - 按当前登录用户隔离：换号后读不到上一个号的；退出登录时 [clear] 全部删掉。
 * - 只存这个用户自己从服务端拿到过的东西，服务端数据才是准的，拉回来就覆盖。
 *
 * 不依赖 Android（目录由外面传进来），方便在 JVM 单元测试里跑。
 */
class ScreenCache(
    private val dir: File?,
    private val scope: CoroutineScope,
    private val writeDelayMs: Long = 800,
    private val userId: () -> Long?,
) {
    private val memory = ConcurrentHashMap<String, Any>()
    private val writers = ConcurrentHashMap<String, Job>()

    /** 当前用户下的完整 key；没登录返回 null（什么都不存不取） */
    private fun slot(key: String): String? = userId()?.let { "$it/$key" }

    private fun file(slot: String): File? = dir?.let { root ->
        val (user, key) = slot.split('/', limit = 2)
        File(File(root, user), key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
    }

    /** 只读内存 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? = slot(key)?.let { memory[it] as? T }

    /** 内存没有就读磁盘（文件很小，同步读，同 MessageCache） */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, serializer: KSerializer<T>): T? {
        val slot = slot(key) ?: return null
        (memory[slot] as? T)?.let { return it }
        val loaded = runCatching {
            file(slot)?.takeIf { it.exists() }?.let { KcJson.decodeFromString(serializer, it.readText()) }
        }.getOrNull() ?: return null
        memory[slot] = loaded
        return loaded
    }

    /** 只存内存 */
    fun put(key: String, value: Any) {
        slot(key)?.let { memory[it] = value }
    }

    /** 内存 + 磁盘；磁盘写入合并、延后，不挡界面 */
    fun <T : Any> put(key: String, value: T, serializer: KSerializer<T>) {
        val slot = slot(key) ?: return
        memory[slot] = value
        val target = file(slot) ?: return
        writers.remove(slot)?.cancel()
        writers[slot] = scope.launch(Dispatchers.IO) {
            delay(writeDelayMs)
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(KcJson.encodeToString(serializer, value))
            }
        }
    }

    fun remove(key: String) {
        val slot = slot(key) ?: return
        memory.remove(slot)
        writers.remove(slot)?.cancel()
        file(slot)?.delete()
    }

    /** 等还没落盘的写完（测试用） */
    suspend fun flush() {
        writers.values.toList().forEach { it.join() }
    }

    /** 退出登录：内存、磁盘全清 */
    fun clear() {
        writers.values.forEach { it.cancel() }
        writers.clear()
        memory.clear()
        dir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** 一个页面用的一格缓存：带 serializer 的会落盘，不带的只在内存 */
class CacheSlot<T>(private val cache: ScreenCache, val key: String, private val serializer: KSerializer<T & Any>? = null) {
    fun get(): T? = if (serializer != null) cache.get(key, serializer) else cache.get<Any>(key)?.let {
        @Suppress("UNCHECKED_CAST")
        it as T
    }

    fun put(value: T) {
        if (value == null) return
        if (serializer != null) cache.put(key, value, serializer) else cache.put(key, value)
    }
}

fun <T> ScreenCache.slot(key: String, serializer: KSerializer<T & Any>? = null): CacheSlot<T> = CacheSlot(this, key, serializer)
