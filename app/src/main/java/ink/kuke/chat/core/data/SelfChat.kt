package ink.kuke.chat.core.data

import android.content.Context
import ink.kuke.chat.core.model.Message
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 「我的设备」会话（docs/self-chat.md）里「哪台设备发的」：消息的 sender_id 永远是自己，左右、未读、通知都按 metadata.device 的设备类算。
 * 这台是手机（X-Kuke-Platform: android）：android / ios 是「这类设备」，desktop / web（以及不认识的值，服务端按 web 算）是「电脑」。
 */
object SelfDevice {
    /** 本端的设备类 */
    private const val MINE = "phone"

    /** 另一类设备在这台手机上的叫法（左边消息的名字、通知里的发送人） */
    const val PEER_LABEL = "我的电脑"

    fun deviceOf(message: Message): String? = (message.metadata?.get("device") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun classOf(device: String): String = if (device == "android" || device == "ios") "phone" else "computer"

    /**
     * 另一类设备（电脑）发的：画在左边、算本地未读、弹通知。
     * 没有 device 的（发送中的本地消息、理论上不会有的旧数据）当成这类设备发的，放右边（2 节）。
     */
    fun fromOtherDevice(message: Message): Boolean = deviceOf(message)?.let { classOf(it) != MINE } ?: false
}

/**
 * 「我的设备」的本地未读（3.3：服务端恒为 0，未读按人记不按设备，只能客户端自己算）。
 * 按登录用户分开存在 SharedPreferences（后台服务、补拉通知也要读）：
 *   seen    本机上次看到的最后一条消息 id
 *   unread  本地未读数（另一类设备发来、还没在这台手机上看过的）
 *   counted 已经算进未读（或确认不用算）的最大消息 id：重拉列表时只有比它新的才去补算
 */
class SelfChatStore(context: Context, private val userId: () -> Long?) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(name: String): String? = userId()?.let { "$name:$it" }

    private fun long(name: String): Long = key(name)?.let { prefs.getLong(it, 0L) } ?: 0L

    val seen: Long get() = long("seen")
    val counted: Long get() = long("counted")
    val unread: Int get() = key("unread")?.let { prefs.getInt(it, 0) } ?: 0

    @Synchronized
    fun write(unread: Int? = null, seen: Long? = null, counted: Long? = null) {
        val editor = prefs.edit()
        unread?.let { value -> key("unread")?.let { editor.putInt(it, value.coerceAtLeast(0)) } }
        seen?.let { value -> key("seen")?.let { editor.putLong(it, maxOf(value, this.seen)) } }
        counted?.let { value -> key("counted")?.let { editor.putLong(it, maxOf(value, this.counted)) } }
        editor.apply()
    }

    /** 在这台手机上看到了 [messageId]：未读清零 */
    fun markSeen(messageId: Long) = write(unread = 0, seen = messageId, counted = messageId)

    /** 来了一条另一类设备的消息、会话没开着：+1 */
    @Synchronized
    fun increment(messageId: Long) {
        if (messageId <= counted) return
        write(unread = unread + 1, counted = messageId)
    }

    /** 这条不用算未读（自己这类设备发的、正开着会话）：只记「算到这儿了」 */
    fun noteCounted(messageId: Long) = write(counted = messageId)

    fun clear() = prefs.edit().clear().apply()

    private companion object { const val PREFS = "self_chat" }
}
