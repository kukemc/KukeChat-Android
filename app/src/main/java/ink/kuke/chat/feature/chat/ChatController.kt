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
    val sending = mutableStateOf(false)
    val quote = mutableStateOf<QuoteDraft?>(null)
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

    init {
        // 先用上次缓存的消息把列表填满：进入会话不空白，网络回来再合并最新的
        messages.addAll(graph.messageCache.get(conversationId))
    }

    /** 把当前列表尾部存进缓存（下次进来直接显示） */
    private fun persist() {
        if (messages.isNotEmpty()) graph.messageCache.put(conversationId, messages.toList())
    }

    fun start() {
        scope.launch { loadLatest() }
        listener?.cancel()
        listener = scope.launch {
            graph.realtime.events.collect { event ->
                val data = event.data
                when (event.type) {
                    "message.created" -> decode(data)?.takeIf { it.conversationId == conversationId }?.let { incoming ->
                        // 话发出来了，不用等「正在输入」自己过期
                        incoming.senderId?.let { clearTyping(it) }
                        upsert(incoming)
                        persist()
                        markRead()
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
                        val current = redPackets[id] ?: return@collect
                        redPackets[id] = current.copy(
                            claimedCount = data["claimed_count"]?.jsonPrimitive?.intOrNull ?: current.claimedCount,
                            status = data["status"]?.jsonPrimitive?.contentOrNull ?: current.status,
                        )
                    }
                }
            }
        }
        scope.launch { graph.realtime.reconnected.collect { loadLatest() } }
    }

    private suspend fun loadLatest() {
        runCatching { fetch(null) }
            .onSuccess { page ->
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
        scope.launch {
            runCatching { fetch(oldest) }
                .onSuccess { page ->
                    messages.addAll(0, page.filter { incoming -> messages.none { it.id == incoming.id } })
                    if (page.size < PAGE) reachedStart.value = true
                }
                .onFailure { error.value = it.userMessage() }
            loadingOlder.value = false
        }
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

    fun quoteMessage(message: Message) {
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

    /** 发文字。成功返回 true（输入框清空）。mentions 是文字里 @ 到的成员，mentionAll 是 @全体成员 */
    suspend fun sendText(text: String, mentions: List<Pair<Long, String>> = emptyList(), mentionAll: Boolean = false): Boolean =
        post(text, "text", baseMetadata(mentions, mentionAll))

    suspend fun sendImage(context: Context, uri: Uri): Boolean {
        val upload = runCatching { uploadImage(context, uri) }.getOrElse {
            error.value = it.userMessage()
            return false
        }
        return post(upload, "image", baseMetadata())
    }

    /** 发表情（收藏表情、表情包）：就是一条带 sticker 标记的图片消息 */
    suspend fun sendSticker(url: String, groupEmojiId: Long? = null): Boolean {
        val meta = buildJsonObject {
            baseMetadata()?.forEach { (key, value) -> put(key, value) }
            put("sticker", true)
            // 本群表情：带上来源 id（同电脑版 stickerMessage）
            groupEmojiId?.let { put("group_emoji_id", it) }
        }
        return post(url, "image", meta)
    }

    suspend fun sendVoice(file: File, durationMs: Long, waveform: List<Int>): Boolean {
        val uploaded = runCatching {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.readBytes().toRequestBody("audio/mp4".toMediaType()))
                .build()
            graph.api.upload("/uploads/message-voice", body)
        }.getOrElse {
            error.value = it.userMessage()
            return false
        }
        val url = (uploaded as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: return false
        val meta = buildJsonObject {
            baseMetadata()?.forEach { (key, value) -> put(key, value) }
            put("voice", buildJsonObject {
                put("duration_ms", durationMs)
                put("content_type", "audio/mp4")
                put("size", file.length())
                put("waveform", buildJsonArray { waveform.forEach { add(JsonPrimitive(it)) } })
            })
        }
        return post(url, "voice", meta)
    }

    private suspend fun post(content: String, type: String, metadata: JsonObject?): Boolean {
        if (sending.value) return false
        sending.value = true
        return try {
            val body = buildJsonObject {
                put("content", content)
                put("type", type)
                metadata?.let { put("metadata", it) }
            }
            val sent = decode(graph.api.post("/conversations/$conversationId/messages", body))
            sent?.let(::upsert)
            quote.value = null
            error.value = null
            true
        } catch (e: Exception) {
            error.value = e.userMessage()
            false
        } finally {
            sending.value = false
        }
    }

    /** 读出图片、压到服务端的上限以内（3MB、1200 万像素），GIF 原样上传；返回图片地址 */
    internal suspend fun uploadImage(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这张图片")
        val (bytes, type, ext) = if (mime == "image/gif" && raw.size <= MAX_IMAGE_BYTES) {
            Triple(raw, "image/gif", "gif")
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("这不是一张能识别的图片")
            val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
            var quality = 88
            var out: ByteArray
            do {
                out = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream); stream.toByteArray() }
                quality -= 10
            } while (out.size > MAX_IMAGE_BYTES && quality > 30)
            Triple(out, "image/jpeg", "jpg")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "image.$ext", bytes.toRequestBody(type.toMediaType()))
            .build()
        val uploaded = graph.api.upload("/uploads/message-image", body)
        (uploaded as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: error("上传没有返回地址")
    }

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

    /** 领红包。成功返回领到的金额；失败把服务端的话放进 error 返回 null */
    suspend fun claimRedPacket(id: Long): Int? = try {
        val result = graph.api.post("/red-packets/$id/claim").asObj()
        result?.o("red_packet")?.let(::parseRedPacket)?.let { redPackets[id] = it }
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

    private fun upsert(message: Message) {
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
        private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        private const val MAX_EDGE = 2560
    }
}
