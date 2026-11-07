package ink.kuke.chat.core.data

import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.LevelTitle
import ink.kuke.chat.core.net.KcJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * conversation.updated 的原地修补（不用整份重拉会话列表）。
 *
 * 服务端发的 conversation.updated 有两种：
 *   - 轻量补丁：只带会话 id 和改了的那几个字段（比如旧后端已读后推的 {conversation_id, mention_pending}）—— 照着改就完了；
 *   - 整份快照（send_conversation_patch）：群资料全套字段 + type / join_mode 这些列表模型里没有的。它其实是「这个会话有变化」的
 *     通用信号 —— 成员进出（member_count）、我的置顶 / 免打扰、入群申请数、撤回后的最后一条，这些都不在快照里，所以改完还要
 *     （限频地）重拉一次。
 * 不认识任何字段的（只有 reason、只有 conversation_id）也要重拉。
 */
object ConversationPatch {
    /** 不算「改了什么」的键：会话 id、原因。updated_at 也不抄 —— 列表按它排「最近活跃」，群资料改一下不该把会话顶上去 */
    private val META = setOf("id", "conversation_id", "reason", "updated_at")

    /** 能直接抄到 [Conversation] 上的键 */
    private val PATCHABLE = setOf(
        "title", "display_title", "avatar_url", "announcement", "all_muted", "message_rate_limit_per_minute", "is_temporary",
        "member_count", "pending_join_request_count", "red_packet_pending", "mention_pending",
        "plus_tier", "plus_expires_at", "file_default_ttl_hours", "level_titles_enabled", "level_titles", "group_todo",
    )

    class Result(
        /** 改过的会话；payload 里一个能抄的字段都没有时为 null */
        val patched: Conversation?,
        /** 改完还得整份重拉一次（快照 / 不认识的字段 / 什么都没抄上） */
        val needsReload: Boolean,
    )

    fun conversationId(data: JsonObject): Long? =
        (data["conversation_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: (data["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    fun apply(conversation: Conversation, data: JsonObject): Result {
        var current = conversation
        var touched = false
        for ((key, value) in data) {
            if (key !in PATCHABLE) continue
            val next = patchField(current, key, value) ?: continue
            current = next
            touched = true
        }
        // 有列表模型表示不了的键（type、join_mode、slow_mode_seconds……）= 整份快照 = 通用「有变化」信号
        val unknown = data.keys.any { it !in META && it !in PATCHABLE }
        return Result(if (touched) current else null, needsReload = unknown || !touched)
    }

    /** 抄一个字段；值的类型不对（解析不了）返回 null，当没这个字段 */
    private fun patchField(c: Conversation, key: String, value: JsonElement): Conversation? {
        val p = value as? JsonPrimitive
        val isNull = value is JsonNull
        return when (key) {
            "title" -> if (isNull) c.copy(title = null) else p?.contentOrNull?.let { c.copy(title = it) }
            "display_title" -> if (isNull) c.copy(displayTitle = null) else p?.contentOrNull?.let { c.copy(displayTitle = it) }
            "avatar_url" -> if (isNull) c.copy(avatarUrl = null) else p?.contentOrNull?.let { c.copy(avatarUrl = it) }
            "announcement" -> if (isNull) c.copy(announcement = null) else p?.contentOrNull?.let { c.copy(announcement = it) }
            "all_muted" -> p?.booleanOrNull?.let { c.copy(allMuted = it) }
            "message_rate_limit_per_minute" -> if (isNull) c.copy(rateLimit = 0) else p?.intOrNull?.let { c.copy(rateLimit = it) }
            "is_temporary" -> p?.booleanOrNull?.let { c.copy(isTemporary = it) }
            "member_count" -> p?.intOrNull?.let { c.copy(memberCount = it) }
            "pending_join_request_count" -> p?.intOrNull?.let { c.copy(pendingJoinRequests = it) }
            "red_packet_pending" -> p?.booleanOrNull?.let { c.copy(redPacketPending = it) }
            // JSON null = 清掉「有人@我」
            "mention_pending" -> if (isNull) c.copy(mentionPending = null) else p?.contentOrNull?.let { c.copy(mentionPending = it) }
            "plus_tier" -> p?.contentOrNull?.let { c.copy(plusTier = it) }
            "plus_expires_at" -> if (isNull) c.copy(plusExpiresAt = null) else p?.contentOrNull?.let { c.copy(plusExpiresAt = it) }
            "file_default_ttl_hours" -> p?.intOrNull?.let { c.copy(fileDefaultTtlHours = it) }
            "level_titles_enabled" -> if (isNull) c.copy(levelTitlesEnabled = null) else p?.booleanOrNull?.let { c.copy(levelTitlesEnabled = it) }
            "level_titles" -> if (isNull) c.copy(levelTitles = null) else runCatching {
                c.copy(levelTitles = KcJson.decodeFromJsonElement(ListSerializer(LevelTitle.serializer()), value))
            }.getOrNull()
            "group_todo" -> if (isNull) c.copy(groupTodo = null) else (value as? JsonObject)?.let { c.copy(groupTodo = it) }
            else -> null
        }
    }
}

/**
 * 「标记已读」的上报节流（POST /conversations/{id}/read）。本地清未读不归它管，那个一直是立刻的。
 *
 * 每个会话：
 *   - 只报比上次报过的更新的那条（服务端本来也只往前推，重复报纯属浪费）；
 *   - 至多每 [intervalMs] 一次：第一次立刻发，窗口内再来的攒着，窗口一到发攒下的最新那条（尾随发送）；
 *   - App 在后台时不发，攒着等回前台一起发（[drain]）。
 * 纯逻辑、不碰协程和网络，方便单元测试；真正的发送和定时在 ConversationRepository。
 */
class ReadThrottle(private val intervalMs: Long = 3_000, private val clock: () -> Long) {
    sealed interface Step {
        /** 现在就发这条 */
        data class Send(val messageId: Long) : Step
        /** 过 [delayMs] 再调 [takeDue] 取要发的 */
        data class Wait(val delayMs: Long) : Step
        /** 什么都不用做（报过了 / 已经排着 / 在后台攒着） */
        data object Skip : Step
    }

    private class State {
        /** 已经发出去（或正在发）的最大 id */
        var sent = 0L
        /** 服务端确认过的最大 id（发失败时 sent 退回到这里，下次重发） */
        var confirmed = 0L
        var sentAt = Long.MIN_VALUE / 2
        /** 攒着还没发的最大 id，0 = 没有 */
        var pending = 0L
        /** 已经排了一次尾随发送 */
        var scheduled = false
    }

    private val states = HashMap<Long, State>()

    @Synchronized
    fun offer(conversationId: Long, messageId: Long, foreground: Boolean): Step {
        val s = states.getOrPut(conversationId) { State() }
        val target = maxOf(messageId, s.pending)
        if (target <= s.sent) return Step.Skip
        s.pending = target
        if (!foreground || s.scheduled) return Step.Skip
        val elapsed = clock() - s.sentAt
        if (elapsed >= intervalMs) return Step.Send(markSent(s))
        s.scheduled = true
        return Step.Wait(intervalMs - elapsed)
    }

    /** 尾随发送到点了：返回要发的 id（没有就 null）。在后台的话先不发，留给 [drain] */
    @Synchronized
    fun takeDue(conversationId: Long, foreground: Boolean): Long? {
        val s = states[conversationId] ?: return null
        s.scheduled = false
        if (!foreground || s.pending <= s.sent) return null
        return markSent(s)
    }

    /** 回到前台：后台攒下的全部发掉（会话 id → 消息 id） */
    @Synchronized
    fun drain(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for ((id, s) in states) {
            if (!s.scheduled && s.pending > s.sent) out[id] = markSent(s)
        }
        return out
    }

    @Synchronized
    fun succeeded(conversationId: Long, messageId: Long) {
        val s = states[conversationId] ?: return
        if (messageId > s.confirmed) s.confirmed = messageId
    }

    /** 发失败了：退回去，攒着下次（下一条新消息 / 回前台）再报 */
    @Synchronized
    fun failed(conversationId: Long, messageId: Long) {
        val s = states[conversationId] ?: return
        if (s.sent == messageId) s.sent = s.confirmed
        if (messageId > s.pending) s.pending = messageId
    }

    @Synchronized
    fun clear() = states.clear()

    private fun markSent(s: State): Long {
        val id = s.pending
        s.sent = id
        s.pending = 0L
        s.sentAt = clock()
        return id
    }
}
