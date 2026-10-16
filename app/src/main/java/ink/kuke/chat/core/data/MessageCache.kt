package ink.kuke.chat.core.data

import android.content.Context
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.realtime.RealtimeClient
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 每个会话最近的几十条消息：进入聊天页时先拿它填满列表（不再先空白），再静默拉最新的合并进去。
 * 内存一份（同一进程里重复进出），磁盘一份（冷启动也不空白）。退出登录时清掉。
 * 服务端数据才是准的：磁盘上的只当「先看一眼」，拉回来以后会被覆盖。
 */
class MessageCache(context: Context, private val scope: CoroutineScope, realtime: RealtimeClient) {
    private val dir = File(context.applicationContext.filesDir, "message-cache").apply { mkdirs() }
    private val memory = ConcurrentHashMap<Long, List<Message>>()
    private val writers = ConcurrentHashMap<Long, Job>()

    init {
        // 实时推送一直在收：不管聊天页开没开，已缓存会话的新消息、撤回都直接记进来，进入会话时看到的就是最新的
        scope.launch {
            realtime.events.collect { event ->
                when (event.type) {
                    "message.created" -> decode(event.data)?.let { message ->
                        val current = memory[message.conversationId] ?: loadDisk(message.conversationId)
                        if (current.isNotEmpty() && current.none { it.id == message.id }) put(message.conversationId, (current + message).sortedBy { it.id })
                    }
                    "message.recalled" -> decode(event.data)?.let { message ->
                        val current = memory[message.conversationId] ?: loadDisk(message.conversationId)
                        if (current.any { it.id == message.id }) {
                            put(message.conversationId, current.map { if (it.id == message.id) it.copy(recalledAt = message.recalledAt ?: it.recalledAt) else it })
                        }
                    }
                }
            }
        }
    }

    private fun decode(element: JsonElement): Message? = runCatching { KcJson.decodeFromJsonElement(Message.serializer(), element) }.getOrNull()

    private fun loadDisk(conversationId: Long): List<Message> = runCatching {
        val file = File(dir, "$conversationId.json")
        if (file.exists()) KcJson.decodeFromString(ListSerializer(Message.serializer()), file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    /** 同步读：内存没有就读磁盘（文件很小）。没有缓存返回空列表 */
    fun get(conversationId: Long): List<Message> {
        memory[conversationId]?.let { return it }
        val loaded = loadDisk(conversationId)
        if (loaded.isNotEmpty()) memory[conversationId] = loaded
        return loaded
    }

    /** 存下最新的一批（时间正序，只留最后 [KEEP] 条）；磁盘写入合并、延后，不挡界面 */
    fun put(conversationId: Long, messages: List<Message>) {
        val tail = messages.takeLast(KEEP)
        if (tail.isEmpty()) return
        memory[conversationId] = tail
        writers[conversationId]?.cancel()
        writers[conversationId] = scope.launch(Dispatchers.IO) {
            delay(800)
            runCatching { File(dir, "$conversationId.json").writeText(KcJson.encodeToString(ListSerializer(Message.serializer()), tail)) }
        }
    }

    fun remove(conversationId: Long) {
        memory.remove(conversationId)
        writers.remove(conversationId)?.cancel()
        File(dir, "$conversationId.json").delete()
    }

    fun clear() {
        memory.clear()
        writers.values.forEach { it.cancel() }
        writers.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object { const val KEEP = 60 }
}
