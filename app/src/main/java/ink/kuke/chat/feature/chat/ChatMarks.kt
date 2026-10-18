package ink.kuke.chat.feature.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * 本机记下「已经看过」的红包和 @我：看过的不再算进聊天页右上角的提示胶囊（有的红包就是不想领，别一直挂着）。
 * 「看过」= 点胶囊跳过去了，或者那条气泡真的在屏幕上出现过。
 * 按账号分开存（SharedPreferences，一个账号一行逗号串），每类只留最近 [CAP] 个，重启应用后还在。
 * 值放在 Compose 状态里，标记以后胶囊上的数字立刻跟着变。
 */
object SeenMarks {
    private const val FILE = "kuke_seen_marks"
    private const val CAP = 500

    private var prefs: SharedPreferences? = null
    private var owner: Long? = null

    /** 看过的红包 id（红包本身的 id，不是消息 id） */
    val redPackets = mutableStateOf<Set<Long>>(emptySet())
    /** 看过的 @我 消息 id */
    val mentions = mutableStateOf<Set<Long>>(emptySet())

    /** 换账号（或第一次用）时把这个账号的记录读进来 */
    fun bind(context: Context, userId: Long?) {
        if (userId == null) return
        if (prefs != null && owner == userId) return
        val p = prefs ?: context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { prefs = it }
        owner = userId
        redPackets.value = read(p, "rp_$userId")
        mentions.value = read(p, "mn_$userId")
    }

    fun markRedPackets(ids: Collection<Long>) = mark(redPackets, "rp", ids)

    fun markMentions(ids: Collection<Long>) = mark(mentions, "mn", ids)

    private fun mark(state: androidx.compose.runtime.MutableState<Set<Long>>, prefix: String, ids: Collection<Long>) {
        val fresh = ids.filter { it !in state.value }
        if (fresh.isEmpty()) return
        // LinkedHashSet 保持加入顺序：超出上限时丢最早的
        val next = LinkedHashSet(state.value).apply { addAll(fresh) }
        while (next.size > CAP) next.remove(next.first())
        state.value = next
        val user = owner ?: return
        prefs?.edit()?.putString("${prefix}_$user", next.joinToString(","))?.apply()
    }

    private fun read(p: SharedPreferences, key: String): Set<Long> =
        p.getString(key, null).orEmpty().split(',').mapNotNullTo(LinkedHashSet()) { it.trim().toLongOrNull() }
}

/** 聊天页「有人@我」里的一项：[kind] = "me"（@了我）/ "all"（@全体成员） */
data class MentionItem(val messageId: Long, val kind: String)

/**
 * 语音「转文字」的结果和展开状态（消息 id → 状态），只在这次打开应用期间记住。
 * [Entry.text] 为 null = 正在转；展开着的会画在语音气泡下面（MessageRow 的 VoiceTranscriptNote）。
 */
object VoiceTranscripts {
    data class Entry(val text: String?, val expanded: Boolean)

    val entries = mutableStateMapOf<Long, Entry>()

    fun expanded(messageId: Long): Boolean = entries[messageId]?.expanded == true

    fun collapse(messageId: Long) {
        entries[messageId]?.let { entries[messageId] = it.copy(expanded = false) }
    }
}
