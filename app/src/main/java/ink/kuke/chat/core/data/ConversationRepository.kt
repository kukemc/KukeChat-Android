package ink.kuke.chat.core.data

import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.realtime.RealtimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 会话列表：界面（聊天 tab）和后台服务（通知要知道会话名、免打扰）共用这一份。
 *
 * 新消息（message.created）直接改到对应会话上：更新最后一条、未读 +1（正在看的会话不加）、挪到前面。
 * 其余会影响列表的事件（会话改名、被移出、已读……）一律防抖后整份重拉 —— 列表不大，重拉最不容易出错。
 * 每次整份拉回来都存一份到 [ScreenCache]（落盘），冷启动时 [restoreCached] 先把上次的列表摆出来。
 */
class ConversationRepository(
    private val api: Api,
    private val realtime: RealtimeClient,
    private val scope: CoroutineScope,
    private val cache: ScreenCache,
    private val currentUserId: () -> Long?,
) {
    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    val items: StateFlow<List<Conversation>> = _items.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 正在看的会话（界面打开聊天页时设置），它的新消息不算未读、不弹通知 */
    @Volatile var visibleConversationId: Long? = null

    private val refreshLock = Mutex()
    private var pendingRefresh: Job? = null

    init {
        scope.launch {
            realtime.events.collect { event ->
                when {
                    event.type == "message.created" -> runCatching { KcJson.decodeFromJsonElement(Message.serializer(), event.data) }.getOrNull()?.let(::applyMessage)
                    event.type == "conversation.read" -> applyRead(event.data)
                    // 红包结束了（抢完 / 过期 / 撤回）：「[有红包]」可能该摘掉了，以服务端为准重拉
                    event.type == "red_packet.updated" && event.data["status"]?.jsonPrimitive?.content.let { it != null && it != "active" } -> refreshSoon()
                    event.type == "message.recalled" || event.type.startsWith("conversation.") || event.type.startsWith("group.") ||
                        event.type.startsWith("friend") -> refreshSoon()
                }
            }
        }
        scope.launch { realtime.reconnected.collect { refreshSoon(0) } }
    }

    suspend fun refresh(): Result<List<Conversation>> = runCatching {
        refreshLock.withLock {
            val list = KcJson.decodeFromJsonElement(ListSerializer(Conversation.serializer()), api.get("/conversations") as JsonArray)
            _items.value = sort(list)
            _loaded.value = true
            cache.put(CACHE_KEY, _items.value, ListSerializer(Conversation.serializer()))
            _items.value
        }
    }

    /** 还没拉过、列表为空时，先摆出上次存下的列表（loaded 仍是 false，进页面照常刷新） */
    fun restoreCached() {
        if (_loaded.value || _items.value.isNotEmpty()) return
        cache.get(CACHE_KEY, ListSerializer(Conversation.serializer()))?.let { cached ->
            _items.compareAndSet(emptyList(), cached)
        }
    }

    fun refreshSoon(delayMs: Long = 400) {
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(delayMs)
            refresh()
        }
    }

    fun find(id: Long): Conversation? = _items.value.firstOrNull { it.id == id }

    fun clear() {
        _items.value = emptyList()
        _loaded.value = false
    }

    /** 我读到了这一条（打开会话、看到最新消息时调）：本地未读清零，再告诉服务端 */
    fun markRead(conversationId: Long, lastMessageId: Long) {
        _items.update { list -> list.map { if (it.id == conversationId) it.copy(unreadCount = 0, mentionPending = null) else it } }
        scope.launch { runCatching { api.post("/conversations/$conversationId/read", JsonObject(mapOf("last_read_message_id" to kotlinx.serialization.json.JsonPrimitive(lastMessageId)))) } }
    }

    private fun applyMessage(message: Message) {
        val existing = find(message.conversationId)
        if (existing == null) {
            refreshSoon(0)
            return
        }
        val mine = message.senderId != null && message.senderId == currentUserId()
        val countsAsUnread = !mine && message.type != "system" && visibleConversationId != message.conversationId
        val packetPending = message.type == "red_packet" && redPacketClaimable(message, existing, mine)
        _items.update { list ->
            sort(list.map {
                if (it.id != message.conversationId) it
                else it.copy(
                    lastMessage = message,
                    updatedAt = message.createdAt,
                    unreadCount = it.unreadCount + if (countsAsUnread) 1 else 0,
                    redPacketPending = it.redPacketPending || packetPending,
                )
            })
        }
    }

    /**
     * 新来的红包消息算不算「我还能领」（会话列表的「[有红包]」，docs/red-packets.md 第 6 节）：
     * 私聊里自己发的不算；专属红包只对目标本人算。清掉靠领取后 / 红包结束后的整份重拉。
     */
    private fun redPacketClaimable(message: Message, conversation: Conversation, mine: Boolean): Boolean {
        if (mine && !conversation.isGroup) return false
        val packet = message.metadata?.get("red_packet") as? JsonObject ?: return false
        val target = (packet["target_user_id"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
        return target == null || target == currentUserId()
    }

    private fun applyRead(data: JsonObject) {
        val conversationId = data["conversation_id"]?.jsonPrimitive?.longOrNull ?: return
        val userId = data["user_id"]?.jsonPrimitive?.longOrNull
        // 我在别的设备上读过了：这边也清零
        if (userId != null && userId == currentUserId()) {
            _items.update { list -> list.map { if (it.id == conversationId) it.copy(unreadCount = 0, mentionPending = null) else it } }
        }
    }

    /** 置顶的在前，然后按最近更新 */
    private fun sort(list: List<Conversation>): List<Conversation> =
        list.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })

    val totalUnread: Int get() = _items.value.filter { !it.quiet }.sumOf { it.unreadCount }

    private companion object { const val CACHE_KEY = "conversations" }
}
