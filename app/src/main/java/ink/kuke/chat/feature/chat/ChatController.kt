package ink.kuke.chat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.Reaction
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.messagePreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder

/** 正在回复的那条（输入卡上方的引用条） */
data class QuoteDraft(val messageId: Long, val senderId: Long?, val senderName: String, val preview: String, val type: String, val content: String, val createdAt: String)

/**
 * 一个会话的聊天状态：消息（时间正序）、加载更早的、发送、回应、撤回、删除、收藏、精华，以及这个会话的实时事件。
 * 还管：正在输入、戳一戳、投票、红包、转发、多选、机器人指令。
 * 服务端：GET /conversations/{id}/messages?before_id=&limit=（时间正序）；POST …/messages {content, type, metadata}。
 */
class ChatController(val graph: AppGraph, val conversationId: Long, private val scope: CoroutineScope) {
    val messages = mutableStateListOf<Message>()
    val loadingOlder = mutableStateOf(false)
    val reachedStart = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val quote = mutableStateOf<QuoteDraft?>(null)
    /** 这个会话发送中 / 发送失败的本地消息（进程内单例，离开聊天页也接着发） */
    val outbox: ConversationOutbox = Outbox.of(graph, conversationId)
    private var listener: Job? = null

    /** 正在输入的人（用户 id → 名字）。服务端没有「停止输入」事件：对方输入时约 3 秒推一次，这边每次续 5 秒 */
    val typing = mutableStateMapOf<Long, String>()
    private val typingExpiry = mutableMapOf<Long, Job>()
    private var lastTypingSentAt = 0L

    /** 被戳的人：头像抖一下。token 每次自增，同一个人连续被戳也能重新触发 */
    data class PokeEvent(val userId: Long, val token: Int)
    val poked = mutableStateOf<PokeEvent?>(null)
    private var pokeToken = 0

    /** 多选模式 */
    val selecting = mutableStateOf(false)
    val selected = mutableStateListOf<Long>()

    /** 「以下是新消息」：进入会话时的第一条未读。只在第一次拉到消息时算一次 */
    val newDividerId = mutableStateOf<Long?>(null)
    private var dividerDone = false
    private val unreadAtEntry = graph.conversations.find(conversationId)?.unreadCount ?: 0

    /** 红包状态缓存（红包 id → 最新状态）。卡片出现时按需拉，red_packet.updated 事件续上 */
    val redPackets = mutableStateMapOf<Long, RedPacketState>()
    private val redPacketLoading = mutableSetOf<Long>()

    /** 机器人按钮「填入输入框」：Composer 取走后清空 */
    val insertRequest = mutableStateOf<String?>(null)
    /** 长按头像 @ 他：(用户 id, 显示名)，输入框插入「@名字 」并登记成真正的 @ 后清空 */
    val mentionRequest = mutableStateOf<Pair<Long, String>?>(null)

    /** 本群机器人的指令（输入 / 弹出）。null = 还没拉 */
    val botCommands = mutableStateOf<List<BotCommand>?>(null)

    /** 一句话提示（已复制、已添加……），界面用 Toast 显示后清空 */
    val notice = mutableStateOf<String?>(null)

    val myId: Long? get() = graph.session.session.value?.user?.id

    /**
     * 「上下文模式」：列表只是历史中的一段（从搜索 / 引用 / 群待办跳过来，目标不在已加载范围里）。
     * 这时实时推来的新消息和这一段之间有空洞，所以列表只到 contextEdgeId 为止，往下滑逐页补齐，接上最新后退出（同电脑版）。
     * 这期间不写消息缓存（缓存只放最新的一段，MessageCache 自己照样收实时消息），「回到最新」直接换回最新一页。
     */
    val contextEdgeId = mutableStateOf<Long?>(null)
    val loadingNewer = mutableStateOf(false)
    val inContext: Boolean get() = contextEdgeId.value != null
    /** 列表整段换掉（跳转、回到最新）时自增：换之前发出的分页请求回来就作废 */
    private var generation = 0
    /** 第一次拉最新一页结束了（成功或失败）：带着目标消息进来的跳转等它 */
    val initialLoaded = mutableStateOf(false)

    /** 这个会话里我还能领的红包（GET /red-packets/pending），聊天页右上角「N 个红包未领」用。新的在前 */
    val pendingRedPackets = mutableStateListOf<PendingRedPacket>()
    /** 旧后端没有这个接口（404 / 422）：不再请求，提示也不显示 */
    private var pendingSupported = true
    private var pendingJob: Job? = null
    private var pendingExpiryJob: Job? = null

    init {
        // 先用上次缓存的消息把列表填满：进入会话不空白，网络回来再合并最新的
        messages.addAll(graph.messageCache.get(conversationId))
    }

    /** 把当前列表尾部存进缓存（下次进来直接显示） */
    private fun persist() {
        if (messages.isNotEmpty() && !inContext) graph.messageCache.put(conversationId, messages.toList())
    }

    fun start() {
        scope.launch {
            loadLatest()
            initialLoaded.value = true
        }
        refreshPendingRedPackets(0)
        outbox.listener = outboxListener
        listener?.cancel()
        listener = scope.launch {
            graph.realtime.events.collect { event ->
                val data = event.data
                when (event.type) {
                    "message.created" -> decode(data)?.takeIf { it.conversationId == conversationId }?.let { incoming ->
                        // 话发出来了，不用等「正在输入」自己过期
                        incoming.senderId?.let { clearTyping(it) }
                        // 自己刚发的那条（client_nonce 对上）：同一帧里摘掉本地那条、放进服务端这条 = 原地替换
                        outbox.confirm(incoming)
                        upsert(incoming)
                        persist()
                        // 上下文模式下列表尾巴不是最新的那条：按收到的这条记已读
                        if (inContext) graph.conversations.markRead(conversationId, incoming.id) else markRead()
                        if (incoming.type == "red_packet" && incoming.senderId != myId) refreshPendingRedPackets()
                    }
                    "message.recalled" -> decode(data)?.takeIf { it.conversationId == conversationId }?.let { event ->
                        // 撤回事件只带 id / recalled_at，不能整条换掉，否则发送人、内容、metadata 全丢（显示成「用户 0」）
                        update(event.id) { current -> current.copy(recalledAt = event.recalledAt ?: current.recalledAt) }
                    }
                    "message.reaction.updated" -> {
                        val id = data["message_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                        val reactions = (data["reactions"] as? JsonArray)?.let { runCatching { KcJson.decodeFromJsonElement(ListSerializer(Reaction.serializer()), it) }.getOrNull() } ?: return@collect
                        update(id) { message -> message.copy(reactions = mergeMine(message.reactions, reactions)) }
                    }
                    "message.featured.updated" -> {
                        val id = data["message_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                        val featured = data["featured"]?.jsonPrimitive?.contentOrNull == "true"
                        update(id) { it.copy(featured = featured) }
                    }
                    "conversation.typing" -> {
                        if (data["conversation_id"]?.jsonPrimitive?.longOrNull != conversationId) return@collect
                        val uid = data["user_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                        if (uid == myId) return@collect
                        showTyping(uid, data["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "用户 $uid")
                    }
                    "conversation.member.poked" -> {
                        if (data["conversation_id"]?.jsonPrimitive?.longOrNull != conversationId) return@collect
                        val uid = data["user_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                        poked.value = PokeEvent(uid, ++pokeToken)
                    }
                    "poll.updated" -> {
                        if (data["conversation_id"]?.jsonPrimitive?.longOrNull != conversationId) return@collect
                        val messageId = data["message_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                        patchPoll(messageId, data)
                    }
                    "red_packet.updated" -> {
                        if (data["conversation_id"]?.jsonPrimitive?.longOrNull != conversationId) return@collect
                        val id = data["id"]?.jsonPrimitive?.longOrNull ?: return@collect
                        refreshPendingRedPackets()
                        val current = redPackets[id] ?: return@collect
                        redPackets[id] = current.copy(
                            claimedCount = data["claimed_count"]?.jsonPrimitive?.intOrNull ?: current.claimedCount,
                            status = data["status"]?.jsonPrimitive?.contentOrNull ?: current.status,
                        )
                    }
                }
            }
        }
        scope.launch {
            graph.realtime.reconnected.collect {
                loadLatest()
                refreshPendingRedPackets(0)
            }
        }
    }

    private val outboxListener = object : ConversationOutbox.Listener {
        override fun onConfirmed(message: Message) {
            upsert(message)
            persist()
            if (!inContext) markRead()
        }

        override fun onFailed(reason: String) {
            error.value = reason
        }
    }

    /** 离开聊天页：发送队列照常跑，确认的消息改记进消息缓存 */
    fun stop() {
        if (outbox.listener === outboxListener) outbox.listener = null
    }

    private suspend fun loadLatest() {
        // 上下文模式下不往这一段里并最新一页（中间有空洞），等往下滑补齐或点「回到最新」
        if (inContext) return
        val gen = generation
        runCatching { fetch(null) }
            .onSuccess { page ->
                if (gen != generation || inContext) return@onSuccess
                // 缓存和最新一页之间如果隔了很多条（离开得久），接起来会有断档：这时丢掉旧缓存，只用最新一页
                val newestKnown = messages.lastOrNull()?.id
                if (newestKnown != null && page.size >= PAGE && page.first().id > newestKnown) messages.clear()
                // 重连后补拉：合并而不是整份换掉（往上翻过的更早消息留着）
                page.forEach(::upsert)
                reachedStart.value = reachedStart.value || page.size < PAGE
                if (!dividerDone) {
                    dividerDone = true
                    if (unreadAtEntry > 0 && unreadAtEntry < messages.size) newDividerId.value = messages[messages.size - unreadAtEntry].id
                }
                persist()
                markRead()
            }
            .onFailure { error.value = it.userMessage() }
    }

    fun loadOlder() {
        if (loadingOlder.value || reachedStart.value) return
        val oldest = messages.firstOrNull()?.id ?: return
        loadingOlder.value = true
        val gen = generation
        scope.launch {
            runCatching { fetch(oldest) }
                .onSuccess { page ->
                    if (gen != generation) return@onSuccess
                    messages.addAll(0, page.filter { incoming -> messages.none { it.id == incoming.id } })
                    if (page.size < PAGE) reachedStart.value = true
                }
                .onFailure { error.value = it.userMessage() }
            loadingOlder.value = false
        }
    }

    // ------------------------------------------------------------ 跳转、上下文模式（同电脑版 MessageList 的 jumpToMessage）

    /**
     * 让目标消息出现在列表里：已经加载了就什么都不做；没加载就拉它前后各 25 条
     * （GET /conversations/{id}/messages/{mid}/context?before=25&after=25），把列表换成这一段、进入上下文模式。
     * 返回 null = 目标已在列表里（界面接着居中 + 高亮）；否则返回给人看的失败原因（空串 = 被后一次跳转顶掉了，不用提示）。
     */
    suspend fun jumpTo(messageId: Long): String? {
        if (messages.any { it.id == messageId }) return null
        val gen = ++generation
        val list = try {
            parseMessagesLoose(graph.api.get("/conversations/$conversationId/messages/$messageId/context?before=$CONTEXT_SIDE&after=$CONTEXT_SIDE"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "已找到该消息，但无法加载它所在的上下文窗口。"
        }
        if (gen != generation) return ""  // 又跳去别处了：不提示
        if (list.none { it.id == messageId }) return "已找到该消息，但该消息对当前账号不可见或已被清除。"
        val page = list.distinctBy { it.id }.sortedBy { it.id }
        val before = page.count { it.id < messageId }
        val after = page.count { it.id > messageId }
        messages.clear()
        messages.addAll(page)
        loadingOlder.value = false
        reachedStart.value = before < CONTEXT_SIDE
        if (after < CONTEXT_SIDE) {
            // 这一段已经接到最新：顺手把缓存里比它还新的（拉的途中实时到的）接上，不进上下文模式
            contextEdgeId.value = null
            graph.messageCache.get(conversationId).filter { it.id > page.last().id }.forEach(::insert)
            persist()
            markRead()
        } else {
            contextEdgeId.value = page.last().id
        }
        return null
    }

    /** 上下文模式里往下滑到底：从边界往后再拉一页；不足一页说明接上了最新，退出上下文模式 */
    fun loadNewerContext() {
        val edge = contextEdgeId.value ?: return
        if (loadingNewer.value) return
        loadingNewer.value = true
        val gen = generation
        scope.launch {
            runCatching { parseMessagesLoose(graph.api.get("/conversations/$conversationId/messages/$edge/context?before=0&after=$PAGE")) }
                .onSuccess { list ->
                    if (gen != generation || contextEdgeId.value != edge) return@onSuccess
                    val newer = list.filter { it.id > edge }.distinctBy { it.id }.sortedBy { it.id }
                    newer.forEach(::insert)
                    if (newer.size < PAGE) {
                        contextEdgeId.value = null
                        // 拉的途中实时到的那几条在缓存里（这期间列表没收），接上
                        val last = messages.lastOrNull()?.id ?: 0L
                        graph.messageCache.get(conversationId).filter { it.id > last }.forEach(::insert)
                        persist()
                        markRead()
                    } else {
                        contextEdgeId.value = newer.last().id
                    }
                }
                .onFailure { if (gen == generation) error.value = "加载更新消息失败，请稍后重试。" }
            loadingNewer.value = false
        }
    }

    /** 「回到最新」：上下文模式下直接换回最新一页（先用缓存里的最新一段顶上，网络回来再合并） */
    fun resetToLatest() {
        if (!inContext) return
        generation++
        contextEdgeId.value = null
        loadingNewer.value = false
        loadingOlder.value = false
        reachedStart.value = false
        messages.clear()
        messages.addAll(graph.messageCache.get(conversationId))
        scope.launch { loadLatest() }
    }

    private suspend fun fetch(beforeId: Long?): List<Message> {
        val query = "limit=$PAGE" + (beforeId?.let { "&before_id=$it" } ?: "")
        val element = graph.api.get("/conversations/$conversationId/messages?$query")
        return KcJson.decodeFromJsonElement(ListSerializer(Message.serializer()), element as JsonArray)
    }

    private fun markRead() {
        messages.lastOrNull()?.let { graph.conversations.markRead(conversationId, it.id) }
    }

    // ------------------------------------------------------------ 发送

    /** 每次设置引用都自增（同一条再引用一次也算）：输入框据此聚焦、弹键盘 */
    val quoteRequests = androidx.compose.runtime.mutableIntStateOf(0)

    fun quoteMessage(message: Message) {
        quoteRequests.intValue++
        quote.value = QuoteDraft(message.id, message.senderId, message.senderName, messagePreview(message).take(80), message.type, message.content.take(200), message.createdAt)
    }

    private fun baseMetadata(mentions: List<Pair<Long, String>> = emptyList(), mentionAll: Boolean = false): JsonObject? {
        val q = quote.value
        if (q == null && mentions.isEmpty() && !mentionAll) return null
        return buildJsonObject {
            if (q != null) {
                put("quote", buildJsonObject {
                    put("message_id", q.messageId)
                    q.senderId?.let { put("sender_id", it) }
                    put("sender_name", q.senderName)
                    put("preview", q.preview)
                    put("type", q.type)
                    put("content", q.content)
                    put("created_at", q.createdAt)
                })
            }
            if (mentions.isNotEmpty()) {
                put("mentions", buildJsonArray { mentions.forEach { (id, name) -> add(buildJsonObject { put("user_id", id); put("name", name) }) } })
            }
            if (mentionAll) put("mention_all", true)
        }
    }

    // 乐观发送（Outbox.kt）：点发送立刻在列表末尾出现一条本地消息，后台上传、按顺序发出，回来后原地换成服务端那条。
    // 发送不挡输入框，可以连发；失败的留在列表里，点一下重发

    /** 发文字。立刻返回 true（输入框清空）。mentions 是文字里 @ 到的成员，mentionAll 是 @全体成员 */
    fun sendText(text: String, mentions: List<Pair<Long, String>> = emptyList(), mentionAll: Boolean = false): Boolean =
        enqueue(PendingMessage.Kind.Text, text = text, meta = baseMetadata(mentions, mentionAll))

    /** 发图片（1–9 张，可带一行说明）：合成一条消息。单张无说明是 type=image，否则是带 metadata.images 的文字消息 */
    fun sendImages(uris: List<Uri>, caption: String = ""): Boolean {
        if (uris.isEmpty()) return false
        return enqueue(PendingMessage.Kind.Images, text = caption.trim(), images = uris.take(MAX_IMAGES).map(::OutgoingImage), meta = baseMetadata())
    }

    /** 发表情（收藏表情、表情包）：就是一条带 sticker 标记的图片消息 */
    fun sendSticker(url: String, groupEmojiId: Long? = null): Boolean {
        val meta = buildJsonObject {
            baseMetadata()?.forEach { (key, value) -> put(key, value) }
            put("sticker", true)
            // 本群表情：带上来源 id（同电脑版 stickerMessage）
            groupEmojiId?.let { put("group_emoji_id", it) }
        }
        return enqueue(PendingMessage.Kind.Sticker, stickerUrl = url, meta = meta)
    }

    /** 发语音：录音文件发完就会被删，这里先把字节读进内存（重发要用） */
    fun sendVoice(file: File, durationMs: Long, waveform: List<Int>): Boolean {
        val bytes = runCatching { file.readBytes() }.getOrElse {
            error.value = "读不到这段录音"
            return false
        }
        val meta = buildJsonObject {
            baseMetadata()?.forEach { (key, value) -> put(key, value) }
            put("voice", buildJsonObject {
                put("duration_ms", durationMs)
                put("content_type", "audio/mp4")
                put("size", bytes.size)
                put("waveform", buildJsonArray { waveform.forEach { add(JsonPrimitive(it)) } })
            })
        }
        return enqueue(PendingMessage.Kind.Voice, voice = OutgoingVoice(bytes, durationMs), meta = meta)
    }

    private fun enqueue(
        kind: PendingMessage.Kind,
        text: String = "",
        images: List<OutgoingImage> = emptyList(),
        stickerUrl: String? = null,
        voice: OutgoingVoice? = null,
        meta: JsonObject?,
    ): Boolean {
        val pending = PendingMessage(newClientNonce(), kind, text, images, stickerUrl, voice, meta)
        val (type, content, metadata) = pending.previewShape()
        // 发送人信息沿用自己上一条消息的（群名片、头衔、等级），换成服务端那条时名字那行不跳
        val me = graph.session.session.value?.user
        val last = messages.lastOrNull { it.senderId != null && it.senderId == myId && it.sender != null }
        pending.preview = Message(
            id = outbox.nextLocalId(),
            conversationId = conversationId,
            senderId = myId,
            sender = last?.sender ?: me,
            senderDisplayName = last?.senderDisplayName,
            senderTitle = last?.senderTitle,
            senderLevel = last?.senderLevel,
            type = type,
            content = content,
            metadata = metadata,
            createdAt = java.time.Instant.now().toString(),
            clientNonce = pending.nonce,
        )
        quote.value = null
        error.value = null
        outbox.enqueue(pending)
        return true
    }

    /** 本地消息：重发（同一份内容，已上传的图复用地址）/ 删除 */
    fun retryPending(pending: PendingMessage) {
        error.value = null
        outbox.retry(pending)
    }

    fun discardPending(pending: PendingMessage) = outbox.discard(pending)

    /** 读出图片、压缩、上传，返回图片地址（举报附图也用） */
    internal suspend fun uploadImage(context: Context, uri: Uri): String = uploadMessageImage(graph, context, uri)

    // ------------------------------------------------------------ 操作

    fun toggleReaction(message: Message, emoji: String) {
        val mine = message.reactions.firstOrNull { it.emoji == emoji }?.reactedByMe == true
        // 先在本地改，接口回来以服务端为准
        update(message.id) { it.copy(reactions = localToggle(it.reactions, emoji, !mine)) }
        scope.launch {
            runCatching {
                if (mine) graph.api.delete("/conversations/$conversationId/messages/${message.id}/reactions/${URLEncoder.encode(emoji, "UTF-8").replace("+", "%20")}")
                else graph.api.post("/conversations/$conversationId/messages/${message.id}/reactions", buildJsonObject { put("emoji", emoji) })
            }.onFailure {
                update(message.id) { current -> current.copy(reactions = localToggle(current.reactions, emoji, mine)) }
                error.value = it.userMessage()
            }
        }
    }

    fun recall(message: Message) = action { decode(graph.api.post("/conversations/$conversationId/messages/${message.id}/recall"))?.let(::replace) }

    fun deleteForMe(message: Message) = action {
        graph.api.delete("/conversations/$conversationId/messages/${message.id}")
        messages.removeAll { it.id == message.id }
    }

    fun bookmark(message: Message) = action {
        graph.api.post("/conversations/$conversationId/messages/${message.id}/bookmark")
        notice.value = "已收藏"
    }

    fun feature(message: Message) = action { graph.api.post("/conversations/$conversationId/messages/${message.id}/feature") }

    internal fun action(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { error.value = it.userMessage() } }
    }

    // ------------------------------------------------------------ 输入状态、戳一戳

    private fun showTyping(userId: Long, name: String) {
        typing[userId] = name
        typingExpiry[userId]?.cancel()
        typingExpiry[userId] = scope.launch {
            delay(5000)
            typing.remove(userId)
        }
    }

    private fun clearTyping(userId: Long) {
        typingExpiry.remove(userId)?.cancel()
        typing.remove(userId)
    }

    /** 输入框内容变化时调：每 3 秒最多发一次 */
    fun notifyTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < 3000) return
        lastTypingSentAt = now
        graph.realtime.sendTyping(conversationId)
    }

    /** 戳一戳（群聊双击头像）。落进聊天记录的系统消息由 message.created 带回来 */
    fun poke(userId: Long) = action {
        decode(graph.api.post("/conversations/$conversationId/members/$userId/poke"))?.let(::upsert)
    }

    // ------------------------------------------------------------ 投票

    /** 把 poll.updated 事件或投票接口返回的 PollState 补进消息里的 poll 对象（事件没有「我投了哪些」，有就换，没有就保留） */
    private fun patchPoll(messageId: Long, state: JsonObject) {
        update(messageId) { message ->
            val meta = message.metadata ?: return@update message
            val poll = meta.o("poll") ?: return@update message
            val votes = state.a("options")?.mapNotNull { it.asObj() }?.associate { (it.l("id") ?: -1L) to (it.i("votes") ?: 0) }.orEmpty()
            val options = JsonArray(
                poll.a("options").orEmpty().map { option ->
                    val obj = option.asObj() ?: return@map option
                    val v = votes[obj.l("id")] ?: return@map option
                    JsonObject(obj + ("votes" to JsonPrimitive(v)))
                },
            )
            val next = poll.toMutableMap()
            next["options"] = options
            for (key in listOf("total_voters", "closed", "closed_at", "my_option_ids")) state[key]?.let { next[key] = it }
            message.copy(metadata = JsonObject(meta + ("poll" to JsonObject(next))))
        }
    }

    /** 提交完整的选项集合（空数组 = 撤票） */
    fun vote(message: Message, pollId: Long, optionIds: List<Long>) = action {
        val result = graph.api.put("/conversations/$conversationId/polls/$pollId/vote", buildJsonObject { put("option_ids", buildJsonArray { optionIds.forEach { add(JsonPrimitive(it)) } }) })
        result.asObj()?.let { patchPoll(message.id, it) }
    }

    fun closePoll(message: Message, pollId: Long) = action {
        val result = graph.api.post("/conversations/$conversationId/polls/$pollId/close")
        result.asObj()?.let { patchPoll(message.id, it) }
    }

    // ------------------------------------------------------------ 红包

    fun loadRedPacket(id: Long, force: Boolean = false) {
        if (!force && (redPackets.containsKey(id) || redPacketLoading.contains(id))) return
        redPacketLoading.add(id)
        scope.launch {
            runCatching { graph.api.get("/red-packets/$id").asObj()?.let(::parseRedPacket) }
                .onSuccess { it?.let { state -> redPackets[id] = state } }
            redPacketLoading.remove(id)
        }
    }

    /**
     * 重拉「我还能领的红包」：GET /red-packets/pending?conversation_id=…。连续触发（一波领取事件）合并成一次。
     * 旧后端没有这个接口（404 / 422）就静默关掉；别的失败保留现状，下次触发再试。
     */
    fun refreshPendingRedPackets(delayMs: Long = 400) {
        if (!pendingSupported) return
        pendingJob?.cancel()
        pendingJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            try {
                val items = graph.api.get("/red-packets/pending?conversation_id=$conversationId").asObj()?.a("items").orEmpty()
                    .mapNotNull { it.asObj()?.let(::parsePendingRedPacket) }
                pendingRedPackets.clear()
                pendingRedPackets.addAll(items)
                schedulePendingExpiry()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ink.kuke.chat.core.net.ApiException) {
                if (e.status == 404 || e.status == 422) {
                    pendingSupported = false
                    pendingRedPackets.clear()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 自己领过 / 确认领不了的红包：先在本地摘掉（提示立刻消失），再重拉一次以服务端为准 */
    fun settlePendingRedPacket(id: Long) {
        pendingRedPackets.removeAll { it.id == id }
        refreshPendingRedPackets()
    }

    /** 按 expires_at 本地去掉过期的：睡到最近的那个过期时间再摘 */
    private fun schedulePendingExpiry() {
        pendingExpiryJob?.cancel()
        val now = java.time.Instant.now()
        pendingRedPackets.removeAll { it.expiresAt?.let { at -> !at.isAfter(now) } == true }
        val next = pendingRedPackets.mapNotNull { it.expiresAt }.minOrNull() ?: return
        pendingExpiryJob = scope.launch {
            delay(java.time.Duration.between(java.time.Instant.now(), next).toMillis().coerceAtLeast(0) + 500)
            schedulePendingExpiry()
        }
    }

    /** 领红包。成功返回领到的金额；失败把服务端的话放进 error 返回 null */
    suspend fun claimRedPacket(id: Long): Int? = try {
        val result = graph.api.post("/red-packets/$id/claim").asObj()
        result?.o("red_packet")?.let(::parseRedPacket)?.let { redPackets[id] = it }
        settlePendingRedPacket(id)
        result?.i("amount")
    } catch (e: Exception) {
        // 已经领过 / 被抢完 / 过期：把最新状态拉下来，卡片跟着变
        loadRedPacket(id, force = true)
        error.value = e.userMessage()
        null
    }

    // ------------------------------------------------------------ 转发、机器人、表情

    /** 合并转发：把本会话里选中的消息转成一条聊天记录发到目标会话 */
    suspend fun forward(targetConversationId: Long, messageIds: List<Long>, note: String? = null): Boolean = try {
        val body = buildJsonObject {
            put("source_conversation_id", conversationId)
            put("message_ids", buildJsonArray { messageIds.sorted().forEach { add(JsonPrimitive(it)) } })
            note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
        }
        val sent = decode(graph.api.post("/conversations/$targetConversationId/forward-bundle", body))
        if (targetConversationId == conversationId) sent?.let(::upsert)
        notice.value = "已转发"
        true
    } catch (e: Exception) {
        error.value = e.userMessage()
        false
    }

    /** 机器人消息里的按钮：填入输入框 / 回调给机器人（打开链接由界面做） */
    fun interact(message: Message, button: BotButton) {
        when (button.action) {
            "input" -> insertRequest.value = button.value
            "callback" -> action {
                graph.api.post("/conversations/$conversationId/messages/${message.id}/interactions", buildJsonObject {
                    put("kind", button.kind)
                    put("action", button.action)
                    button.componentId?.let { put("component_id", it) }
                    button.actionId?.let { put("action_id", it) }
                    button.value?.let { put("value", it) }
                    put("label", button.label)
                    button.elementIndex?.let { put("element_index", it) }
                })
            }
        }
    }

    fun addToStickers(url: String) = action {
        graph.api.post("/stickers/favorites", buildJsonObject { put("source_url", url) })
        notice.value = "已添加到表情"
    }

    fun loadBotCommands() {
        if (botCommands.value != null) return
        scope.launch {
            val list = runCatching {
                graph.api.get("/bots/conversations/$conversationId/bots").asArr().orEmpty().flatMap { element ->
                    val installation = element.asObj() ?: return@flatMap emptyList()
                    val bot = installation.o("bot") ?: return@flatMap emptyList()
                    if (installation.b("enabled") == false || bot.s("status") == "disabled") return@flatMap emptyList()
                    parseBotCommands(bot.l("id") ?: 0, bot.s("name") ?: "机器人", bot.s("avatar_url"), bot.s("commands"))
                }
            }.getOrDefault(emptyList())
            botCommands.value = list
        }
    }

    /** 发出去的新消息（红包、礼物、投票的接口返回）直接落进列表，不等 message.created */
    fun accept(element: JsonElement?) {
        element?.let(::decode)?.takeIf { it.conversationId == conversationId }?.let(::upsert)
    }

    // ------------------------------------------------------------ 多选

    fun startSelecting(first: Message) {
        selecting.value = true
        selected.clear()
        selected.add(first.id)
    }

    fun toggleSelected(message: Message) {
        if (!selected.remove(message.id)) selected.add(message.id)
        if (selected.isEmpty()) selecting.value = false
    }

    fun stopSelecting() {
        selecting.value = false
        selected.clear()
    }

    /** 选中的消息，按时间正序（撤回的不算） */
    fun selectedMessages(): List<Message> = messages.filter { it.id in selected && !it.isRecalled }

    /** 清空聊天记录（只影响自己）：列表清掉，接口 POST /conversations/{id}/clear */
    fun clearHistory(onDone: () -> Unit) = action {
        graph.api.post("/conversations/$conversationId/clear")
        messages.clear()
        graph.messageCache.remove(conversationId)
        reachedStart.value = true
        graph.conversations.refreshSoon(0)
        onDone()
    }

    // ------------------------------------------------------------ 本地列表

    internal fun decode(element: JsonElement): Message? = runCatching { KcJson.decodeFromJsonElement(Message.serializer(), element) }.getOrNull()

    /** 进列表。上下文模式下比边界新的不收（和这一段之间可能有空洞），已有的照常更新 */
    private fun upsert(message: Message) {
        val edge = contextEdgeId.value
        if (edge != null && message.id > edge && messages.none { it.id == message.id }) return
        insert(message)
    }

    private fun insert(message: Message) {
        val index = messages.indexOfFirst { it.id == message.id }
        when {
            index >= 0 -> messages[index] = message
            messages.isEmpty() || message.id > messages.last().id -> messages.add(message)
            else -> {
                val at = messages.indexOfFirst { it.id > message.id }.takeIf { it >= 0 } ?: messages.size
                messages.add(at, message)
            }
        }
    }

    private fun replace(message: Message) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) messages[index] = message
    }

    private fun update(id: Long, transform: (Message) -> Message) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = transform(messages[index])
    }

    /** 实时事件里的 reacted_by_me 是发事件那个人的视角，不能信：自己有没有点过以本地为准 */
    private fun mergeMine(current: List<Reaction>, incoming: List<Reaction>): List<Reaction> =
        incoming.map { reaction -> reaction.copy(reactedByMe = current.firstOrNull { it.emoji == reaction.emoji }?.reactedByMe ?: false) }

    private fun localToggle(list: List<Reaction>, emoji: String, add: Boolean): List<Reaction> {
        val existing = list.firstOrNull { it.emoji == emoji }
        return when {
            add && existing == null -> list + Reaction(emoji, 1, true)
            add -> list.map { if (it.emoji == emoji) it.copy(count = it.count + 1, reactedByMe = true) else it }
            existing == null -> list
            existing.count <= 1 -> list.filterNot { it.emoji == emoji }
            else -> list.map { if (it.emoji == emoji) it.copy(count = it.count - 1, reactedByMe = false) else it }
        }
    }

    companion object {
        const val PAGE = 50
        /** 跳转时目标前后各拉几条（同电脑版 getMessageContext before / after） */
        const val CONTEXT_SIDE = 25
        /** 一条消息最多几张图（后端不限，客户端限 9 张） */
        const val MAX_IMAGES = 9
    }
}
