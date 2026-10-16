package ink.kuke.chat

import ink.kuke.chat.core.data.ScreenCache
import ink.kuke.chat.core.data.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ScreenCacheTest {
    @Serializable
    data class Item(val id: Long, val name: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serializer = ListSerializer(Item.serializer())

    @Test
    fun memoryIsScopedToSignedInUser() {
        var user: Long? = 1
        val cache = ScreenCache(null, scope) { user }
        cache.put("k", "a")
        assertEquals("a", cache.get<String>("k"))
        // 换号：读不到上一个号的
        user = 2
        assertNull(cache.get<String>("k"))
        // 没登录：不存不取
        user = null
        cache.put("k", "b")
        assertNull(cache.get<String>("k"))
        user = 1
        assertEquals("a", cache.get<String>("k"))
    }

    @Test
    fun persistedValueSurvivesNewInstance() = runBlocking {
        val dir = Files.createTempDirectory("screen-cache").toFile()
        val first = ScreenCache(dir, scope, writeDelayMs = 0) { 7 }
        first.put("contacts.friends", listOf(Item(1, "甲"), Item(2, "乙")), serializer)
        first.flush()
        // 冷启动：新实例、内存为空，从磁盘读回
        val second = ScreenCache(dir, scope, writeDelayMs = 0) { 7 }
        assertEquals(listOf(Item(1, "甲"), Item(2, "乙")), second.get("contacts.friends", serializer))
        // 别的用户读不到
        val other = ScreenCache(dir, scope, writeDelayMs = 0) { 8 }
        assertNull(other.get("contacts.friends", serializer))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun clearWipesMemoryAndDisk() = runBlocking {
        val dir = Files.createTempDirectory("screen-cache").toFile()
        val cache = ScreenCache(dir, scope, writeDelayMs = 0) { 7 }
        cache.put("feed.friends|latest|", listOf(Item(1, "x")), serializer)
        cache.put("shop", 42)
        cache.flush()
        cache.clear()
        assertNull(cache.get<Int>("shop"))
        assertNull(cache.get("feed.friends|latest|", serializer))
        assertEquals(0, dir.listFiles()?.size ?: 0)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun slotSkipsNullAndRemoves() {
        val cache = ScreenCache(null, scope) { 1 }
        val slot = cache.slot<Int?>("pending")
        slot.put(null)
        assertNull(slot.get())
        slot.put(3)
        assertEquals(3, slot.get())
        cache.remove(slot.key)
        assertNull(slot.get())
    }
}
