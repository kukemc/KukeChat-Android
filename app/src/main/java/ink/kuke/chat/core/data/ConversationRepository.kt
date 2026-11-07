package ink.kuke.chat.core.data

import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.realtime.ConnectionState
import ink.kuke.chat.core.realtime.RealtimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 会话列表：界面（聊天 tab）和后台服务（通知要知道会话名、免打扰）共用这一份。
 *
 * 能原地改的就原地改，整份重拉（GET /conversations，几十 KB，是服务端最贵的接口）只在真需要时做：
 *   - message.created：改最后一条、未读 +1（正在看的会话不加）、挪到前面；
 *   - conversation.read / spark.updated / 轻量的 conversation.updated：只改对应字段（[ConversationPatch]）；
 *   - conversation.typing、conversation.member.poked 这类和列表无关的：不理（以前被 conversation.* 兜底整份重拉，有人打字就每 2–4 秒拉一次）；
 *   - 群资料快照、成员进出、好友变化这类改不全的：限频重拉（[THROTTLED_GAP_MS] 内最多一次）；
 *   - 重连：断线之后没整份拉过才补拉，离上次至少 [RECONNECT_GAP_MS]、带随机抖动（服务端重启时所有手机会同时重连）。
 * 每次整份拉回来都存一份到 [ScreenCache]（落盘），冷启动时 [restoreCached] 先把上次的列表摆出来。
 */
class ConversationRepository(
    private val api: Api,
    private val realtime: RealtimeClient,
    private val scope: CoroutineScope,
    private val cache: ScreenCache,
    /** 「我的设备」的本地未读（docs/self-chat.md 3.3）；单元测试里可以不给 */
    private val selfStore: SelfChatStore? = null,
    /** App 在不在前台：在后台时已读先不上报，回前台由 [onForeground] 补发 */
    private val isForeground: () -> Boolean = { true },
    /** 毫秒时钟（单元测试里换成假的） */
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentUserId: () -> Long?,
) {
    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    val items: StateFlow<List<Conversation>> = _items.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 正在看的会话（界面打开聊天页时设置），它的新消息不算未读、不弹通知 */
    @Volatile var visibleConversationId: Long? = null

    private val refreshLock = Mutex()
    private val scheduleLock = Any()
    /** 排着还没开始的那次重拉；开始拉了就置空（之后再来的事件另排一次，也不会把正在拉的掐掉） */
    private var pendingRefresh: Job? = null
    private var pendingDueAt = 0L
    /** 上次整份拉的开始 / 成功时间（[clock]） */
    @Volatile private var lastRefreshStartedAt = 0L
    @Volatile private var lastRefreshAt = 0L
    /** 实时连接最近一次断开的时间：重连时据此判断断线之后有没有整份拉过 */
    @Volatile private var lastDisconnectAt = 0L
    /** 收到消息、但列表里没有的会话 → 这个时间之前不再为它重拉（防服务端列表不给的会话每条消息都触发重拉） */
    private val missingUntil = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /** 因为收到它的消息才排的重拉：拉成功了还没有，就记进 [missingUntil] */
    private val awaitingIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private val readThrottle = ReadThrottle(READ_INTERVAL_MS, clock)

    /**
     * 「我的设备」会话的 id（GET /conversations/self 拿到的；每人一个，所有设备同一个）。
     * 拿到之前 / 旧后端没有这个接口时为 null。
     */
    @Volatile var selfConversationId: Long? = null
        private set
    /** 旧后端没有「我的设备」（GET /conversations/self 404 / 422）：不再请求，列表也不带 include_self */
    @Volatile private var selfSupported = true

    init {
        scope.launch { realtime.events.collect { event -> onEvent(event.type, event.data) } }
        scope.launch {
            var wasConnected = false
            realtime.state.collect { state ->
                val connected = state == ConnectionState.Connected
                if (wasConnected && !connected) lastDisconnectAt = clock()
                wasConnected = connected
            }
        }
        scope.launch { realtime.reconnected.collect { onReconnected() } }
    }

    /** 实时事件分发（internal：单元测试直接喂事件） */
    internal fun onEvent(type: String, data: JsonObject) {
        when {
            type == "message.created" -> runCatching { KcJson.decodeFromJsonElement(Message.serializer(), data) }.getOrNull()?.let(::applyMessage)
            type == "conversation.read" -> applyRead(data)
            type == "spark.updated" -> applySparkEvent(data)
            type == "red_packet.updated" -> applyRedPacketUpdate(data)
            // 和会话列表无关、又很频繁的：不理（打字每 2–4 秒一次；戳一戳是聊天页里的动画）
            type == "conversation.typing" || type == "conversation.member.poked" -> Unit
            type == "conversation.updated" -> applyConversationUpdated(data)
            type in IGNORED_EVENTS || type.startsWith("group.file.") || type.startsWith("group.checkin.") -> Unit
            // 撤回（最后一条可能变）、被移出 / 解散、临时会话关闭……：防抖重拉
            type == "message.recalled" || type.startsWith("conversation.") -> refreshSoon()
            // 群 / 好友的变化（成员进出、角色、入群申请、好友通过 / 删除）：限频重拉，同一阵子的合成一次
            type.startsWith("group.") || type.startsWith("friend") -> refreshThrottled()
        }
    }

    private fun onReconnected() {
        // 断线之后已经整份拉过了（比如回前台时刚拉的）：列表就是新的，不用再拉
        if (_loaded.value && lastDisconnectAt > 0L && lastRefreshStartedAt > lastDisconnectAt) return
        val jitter = kotlin.random.Random.nextLong(RECONNECT_JITTER_MS + 1)
        // 离上次整份拉不到 30 秒：推到 30 秒再拉（断断续续的网络下每次重连都拉，一分钟能拉好多回）
        scheduleRefresh(if (_loaded.value) maxOf(jitter, lastRefreshAt + RECONNECT_GAP_MS - clock()) else jitter)
    }

    suspend fun refresh(): Result<List<Conversation>> = runCatching {
        refreshLock.withLock {
            lastRefreshStartedAt = clock()
            // 「我的设备」：先建（取）会话，再带 include_self 拉列表才有它（docs/self-chat.md 1.2，服务端不会在别处替我们建）
            ensureSelf()
            val path = if (selfSupported) "/conversations?include_self=true" else "/conversations"
            val raw = KcJson.decodeFromJsonElement(ListSerializer(Conversation.serializer()), api.get(path) as JsonArray)
            val list = withSelfUnread(raw)
            _items.value = sort(list)
            _loaded.value = true
            lastRefreshAt = clock()
            noteMissing(_items.value)
            cache.put(CACHE_KEY, _items.value, ListSerializer(Conversation.serializer()))
            _items.value
        }
    }

    private fun noteMissing(list: List<Conversation>) {
        if (awaitingIds.isEmpty()) return
        val until = clock() + MISSING_RETRY_MS
        val ids = awaitingIds.toList()
        awaitingIds.removeAll(ids.toSet())
        ids.forEach { id -> if (list.none { it.id == id }) missingUntil[id] = until else missingUntil.remove(id) }
    }

    /** 拉过、而且离上次整份拉不到 [maxAgeMs] 就不拉（后台服务每次 onStartCommand 用它：回到 App 不用每次都拉） */
    suspend fun refreshIfStale(maxAgeMs: Long = STALE_MS): Result<List<Conversation>> {
        if (_loaded.value && clock() - lastRefreshAt < maxAgeMs) return Result.success(_items.value)
        return refresh()
    }

    /**
     * 等列表里出现这个会话（新会话的第一条消息：通知要知道会话名）。自己不发请求 ——
     * 同一条 message.created 在 [applyMessage] 里已经排了重拉，这里只是等它，免得一条消息拉两遍。
     */
    suspend fun awaitConversation(id: Long, timeoutMs: Long = 10_000): Conversation? {
        find(id)?.let { return it }
        return withTimeoutOrNull(timeoutMs) { items.first { list -> list.any { it.id == id } } }?.firstOrNull { it.id == id }
    }

    /** 还没拉过、列表为空时，先摆出上次存下的列表（loaded 仍是 false，进页面照常刷新） */
    fun restoreCached() {
        if (_loaded.value || _items.value.isNotEmpty()) return
        cache.get(CACHE_KEY, ListSerializer(Conversation.serializer()))?.let { cached ->
            _items.compareAndSet(emptyList(), cached)
        }
    }

    /**
     * 过一会儿整份重拉一次。已经排了一次、而且不比这次晚：并进去，不重新计时
     * （以前每次都掐掉重排，事件一直来就一直拉不成）；这次要求更早（比如用户操作要 0 延迟）就提前。
     */
    fun refreshSoon(delayMs: Long = 400) = scheduleRefresh(delayMs)

    /** 限频重拉：离上次整份拉至少 [THROTTLED_GAP_MS]，一阵子里的群 / 好友事件合成一次 */
    private fun refreshThrottled() {
        scheduleRefresh(maxOf(400L, lastRefreshAt + THROTTLED_GAP_MS - clock()))
    }

    private fun scheduleRefresh(delayMs: Long) {
        val wait = delayMs.coerceAtLeast(0L)
        synchronized(scheduleLock) {
            val due = clock() + wait
            val existing = pendingRefresh
            if (existing != null && existing.isActive && pendingDueAt <= due) return
            existing?.cancel()
            pendingDueAt = due
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                if (wait > 0) delay(wait)
                // 开始拉了：后来的事件另排一次（这次拉回来的可能不含它们），也别被后来的掐掉
                synchronized(scheduleLock) { if (pendingRefresh === job) pendingRefresh = null }
                refresh()
            }
            pendingRefresh = job
            job.start()
        }
    }

    fun find(id: Long): Conversation? = _items.value.firstOrNull { it.id == id }

    fun clear() {
        synchronized(scheduleLock) {
            pendingRefresh?.cancel()
            pendingRefresh = null
        }
        readThrottle.clear()
        missingUntil.clear()
        awaitingIds.clear()
        lastRefreshAt = 0L
        lastRefreshStartedAt = 0L
        _items.value = emptyList()
        _loaded.value = false
        selfConversationId = null
        selfSupported = true
    }

    /** 是不是「我的设备」会话（列表里还没有它时按记下的 id 认） */
    fun isSelf(conversationId: Long): Boolean = conversationId == selfConversationId || find(conversationId)?.isSelf == true

    /** GET /conversations/self：幂等，拿到 id 记下。旧后端（没有这个接口）静默跳过；网络问题下次刷新再试 */
    private suspend fun ensureSelf() {
        if (selfConversationId != null || !selfSupported) return
        try {
            val conversation = KcJson.decodeFromJsonElement(Conversation.serializer(), api.get("/conversations/self"))
            if (conversation.isSelf) selfConversationId = conversation.id else selfSupported = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ink.kuke.chat.core.net.ApiException) {
            // 旧后端：没有这个路由（404 / 405），或者把 self 当成会话 id 去解析（422）
            if (e.status == 404 || e.status == 405 || e.status == 422) selfSupported = false
        } catch (_: Exception) {
        }
    }

    /**
     * 列表里「我的设备」的 unread_count 恒为 0：换成本地算的。
     * 离线期间另一台设备发来的（列表上的最后一条比「算到」的新）：拉一页消息，把「上次看到」之后另一类设备发的条数算进未读（3.3）。
     */
    private suspend fun withSelfUnread(list: List<Conversation>): List<Conversation> {
        val store = selfStore ?: return list
        val self = list.firstOrNull { it.isSelf } ?: return list
        selfConversationId = self.id
        val last = self.lastMessage?.id ?: 0L
        when {
            last <= 0L -> Unit
            // 第一次用（本机没记过）：之前的历史不算未读
            store.seen == 0L -> store.markSeen(last)
            last > store.counted -> runCatching {
                val page = KcJson.decodeFromJsonElement(ListSerializer(Message.serializer()), api.get("/conversations/${self.id}/messages?limit=50") as JsonArray)
                val seen = store.seen
                val count = page.count { it.id > seen && !it.isRecalled && it.type != "system" && SelfDevice.fromOtherDevice(it) }
                if (visibleConversationId == self.id) store.markSeen(maxOf(last, page.maxOfOrNull { it.id } ?: 0L))
                else store.write(unread = count, counted = maxOf(last, page.maxOfOrNull { it.id } ?: 0L))
            }
        }
        val unread = store.unread
        return list.map { if (it.id == self.id) it.copy(unreadCount = unread, mentionPending = null) else it }
    }

    /**
     * 我读到了这一条（打开会话、看到最新消息时调）：本地未读立刻清零；告诉服务端走 [ReadThrottle] ——
     * 只报更新的那条、每个会话至多 [READ_INTERVAL_MS] 一次（窗口里来的消息攒着，到点报最新那条），App 在后台时攒着等回前台。
     */
    fun markRead(conversationId: Long, lastMessageId: Long) {
        if (isSelf(conversationId)) {
            // 「我的设备」：已读按人记、调了也没用（3.3），只记本机看到哪儿了
            selfStore?.markSeen(lastMessageId)
            _items.update { list -> list.map { if (it.id == conversationId) it.copy(unreadCount = 0, mentionPending = null) else it } }
            return
        }
        _items.update { list -> list.map { if (it.id == conversationId) it.copy(unreadCount = 0, mentionPending = null) else it } }
        when (val step = readThrottle.offer(conversationId, lastMessageId, isForeground())) {
            is ReadThrottle.Step.Send -> postRead(conversationId, step.messageId)
            is ReadThrottle.Step.Wait -> scope.launch {
                delay(step.delayMs)
                readThrottle.takeDue(conversationId, isForeground())?.let { postRead(conversationId, it) }
            }
            ReadThrottle.Step.Skip -> Unit
        }
    }

    /** App 回到前台：在后台时攒下的已读补报 */
    fun onForeground() {
        readThrottle.drain().forEach { (conversationId, messageId) -> postRead(conversationId, messageId) }
    }

    private fun postRead(conversationId: Long, messageId: Long) {
        scope.launch {
            try {
                api.post("/conversations/$conversationId/read", JsonObject(mapOf("last_read_message_id" to JsonPrimitive(messageId))))
                readThrottle.succeeded(conversationId, messageId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 没报上：攒着，下一条新消息 / 回前台时再报
                readThrottle.failed(conversationId, messageId)
            }
        }
    }

    private fun applyMessage(message: Message) {
        val existing = find(message.conversationId)
        if (existing == null) {
            // 新会话：整份拉一遍就有了。拉成功了还没有的（服务端列表里不给）一分钟内别再为它拉
            if ((missingUntil[message.conversationId] ?: 0L) > clock()) return
            awaitingIds.add(message.conversationId)
            refreshSoon(0)
            return
        }
        val mine = message.senderId != null && message.senderId == currentUserId()
        val countsAsUnread = if (existing.isSelf) {
            // 「我的设备」：另一类设备（电脑）发来的才算未读（3.3）
            val counts = SelfDevice.fromOtherDevice(message) && message.type != "system" && visibleConversationId != message.conversationId
            if (counts) selfStore?.increment(message.id) else selfStore?.noteCounted(message.id)
            counts
        } else !mine && message.type != "system" && visibleConversationId != message.conversationId
        val packetPending = message.type == "red_packet" && redPacketClaimable(message, existing, mine)
        _items.update { list ->
            sort(list.map {
                if (it.id != message.conversationId) it
                else it.copy(
                    lastMessage = message,
                    updatedAt = message.createdAt,
                    unreadCount = if (existing.isSelf && selfStore != null) selfStore.unread else it.unreadCount + if (countsAsUnread) 1 else 0,
                    redPacketPending = it.redPacketPending || packetPending,
                )
            })
        }
    }

    /**
     * 新来的红包消息算不算「我还能领」（会话列表的「[有红包]」，docs/red-packets.md 第 6 节）：
     * 私聊里自己发的不算；专属红包只对目标本人算。清掉靠领取后 / 红包结束后的整份重拉（[redPacketSettled]）。
     */
    private fun redPacketClaimable(message: Message, conversation: Conversation, mine: Boolean): Boolean {
        if (mine && !conversation.isGroup) return false
        val packet = message.metadata?.get("red_packet") as? JsonObject ?: return false
        val target = (packet["target_user_id"] as? JsonPrimitive)?.longOrNull
        return target == null || target == currentUserId()
    }

    private fun applyRead(data: JsonObject) {
        val conversationId = data["conversation_id"]?.jsonPrimitive?.longOrNull ?: return
        val userId = data["user_id"]?.jsonPrimitive?.longOrNull
        // 我在别的设备上读过了：这边也清零（「我的设备」的未读是按设备算的，别的设备读过不算）
        if (userId != null && userId == currentUserId() && !isSelf(conversationId)) {
            // 新后端在已读事件里带上读完之后的 mention_pending（"me" / "all" / null），不再另推 conversation.updated；
            // 旧后端不带这个键：照旧清掉
            val mention = if (data.containsKey("mention_pending")) (data["mention_pending"] as? JsonPrimitive)?.contentOrNull else null
            _items.update { list -> list.map { if (it.id == conversationId) it.copy(unreadCount = 0, mentionPending = mention) else it } }
        }
    }

    /** conversation.updated：能原地改的原地改（[ConversationPatch]）；列表里没有这个会话、或者改不全的，再重拉 */
    private fun applyConversationUpdated(data: JsonObject) {
        val existing = ConversationPatch.conversationId(data)?.let(::find)
        if (existing == null) {
            // 新会话（被拉进群、好友通过）或者根本没带 id（{reason: friend_request.accepted}）：整份拉才知道是什么
            refreshSoon()
            return
        }
        val result = ConversationPatch.apply(existing, data)
        result.patched?.let { patched -> _items.update { list -> list.map { if (it.id == patched.id) patched else it } } }
        if (result.needsReload) refreshThrottled()
    }

    /**
     * red_packet.updated：还在抢（active）的不影响列表；结束了（抢完 / 过期 / 撤回）才看要不要重拉（[redPacketSettled]）。
     * 以前每个结束的红包都整份拉，不管这个会话有没有挂着「[有红包]」
     */
    private fun applyRedPacketUpdate(data: JsonObject) {
        val status = data["status"]?.jsonPrimitive?.contentOrNull ?: return
        if (status == "active") return
        redPacketSettled(data["conversation_id"]?.jsonPrimitive?.longOrNull)
    }

    /**
     * 某个会话里有红包我领完了 / 结束了：它正挂着「[有红包]」才重拉，看还有没有别的能领
     * （领到之后的重拉和随后的结束事件挨着来时合成一次；重拉完摘掉了，结束事件就不再拉）
     */
    fun redPacketSettled(conversationId: Long?) {
        // 不知道是哪个会话（旧后端没带 conversation_id）：照旧重拉
        if (conversationId == null || find(conversationId)?.redPacketPending == true) refreshSoon()
    }

    /** 好友火花变了（spark.updated，docs/sparks.md 第 4 节）：只改这个私聊的 spark，不动排序 */
    private fun applySparkEvent(data: JsonObject) {
        val conversationId = data["conversation_id"]?.jsonPrimitive?.longOrNull ?: return
        val spark = data["spark"]?.let { runCatching { KcJson.decodeFromJsonElement(ink.kuke.chat.core.model.Spark.serializer(), it) }.getOrNull() } ?: return
        applySpark(conversationId, spark)
    }

    /** 本地换掉某个私聊的火花（重燃成功后界面直接调） */
    fun applySpark(conversationId: Long, spark: ink.kuke.chat.core.model.Spark) {
        val shown = spark.takeIf { it.visible }
        _items.update { list -> list.map { if (it.id == conversationId) it.copy(spark = shown) else it } }
    }

    /** 置顶的在前，然后按最近更新 */
    private fun sort(list: List<Conversation>): List<Conversation> =
        list.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })

    val totalUnread: Int get() = _items.value.filter { !it.quiet }.sumOf { it.unreadCount }

    private companion object {
        const val CACHE_KEY = "conversations"
        /** 已读上报：每个会话至多这么久一次 */
        const val READ_INTERVAL_MS = 3_000L
        /** 群 / 好友事件、会话快照触发的重拉：离上次至少隔这么久 */
        const val THROTTLED_GAP_MS = 5_000L
        /** 重连补拉：离上次整份拉至少这么久，再加 0–3 秒随机抖动 */
        const val RECONNECT_GAP_MS = 30_000L
        const val RECONNECT_JITTER_MS = 3_000L
        /** 后台服务启动时：这么久之内拉过就不再拉 */
        const val STALE_MS = 30_000L
        /** 收到消息但列表里没有的会话：重拉过还没有，这么久之内不再为它拉 */
        const val MISSING_RETRY_MS = 60_000L

        /** 和会话列表无关的事件（成员等级、头衔、禁言、公告确认数、群表情……）：不重拉 */
        val IGNORED_EVENTS = setOf(
            "group.member.level_updated", "group.member.title_updated", "group.member.mute_updated",
            "group.announcement.confirmations", "group.announcement.card", "conversation.emojis.updated",
        )
    }
}
