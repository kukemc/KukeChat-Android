package ink.kuke.chat.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import ink.kuke.chat.MainActivity
import ink.kuke.chat.R
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.messagePreview
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 系统通知。渠道（用户可以在系统设置里分别开关、改提示音）：
 *   private  私聊消息        高优先级，弹横幅
 *   group    群聊消息        高优先级
 *   mention  有人 @我         高优先级，免打扰的群里被点名也会提醒
 *   service  保持连接         最低优先级，常驻通知栏、不响不弹（前台服务必须有一条）
 * 每个会话一条通知（id = 会话 id），用 MessagingStyle 把最近几条叠在一起，像 QQ / 微信那样。
 */
object Notifier {
    const val CHANNEL_PRIVATE = "private"
    const val CHANNEL_GROUP = "group"
    const val CHANNEL_MENTION = "mention"
    const val CHANNEL_SERVICE = "service"
    const val EXTRA_CONVERSATION_ID = "kukechat_conversation_id"
    const val SERVICE_NOTIFICATION_ID = 1
    private const val MAX_LINES = 6

    /** 每个会话最近通知过的几条（进程里缓存，进程被杀后从下一条重新开始叠） */
    private val history = mutableMapOf<Long, ArrayDeque<NotificationCompat.MessagingStyle.Message>>()

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannelGroup(NotificationChannelGroup("messages", "消息"))
        fun channel(id: String, name: String, importance: Int, description: String, group: String? = "messages") =
            NotificationChannel(id, name, importance).apply {
                this.description = description
                group?.let { this.group = it }
                if (importance >= NotificationManager.IMPORTANCE_HIGH) {
                    enableVibration(true)
                    setShowBadge(true)
                } else {
                    setShowBadge(false)
                    setSound(null, null)
                }
            }
        manager.createNotificationChannels(
            listOf(
                channel(CHANNEL_PRIVATE, "私聊消息", NotificationManager.IMPORTANCE_HIGH, "好友和临时会话发来的消息"),
                channel(CHANNEL_GROUP, "群聊消息", NotificationManager.IMPORTANCE_HIGH, "群里的新消息"),
                channel(CHANNEL_MENTION, "有人@我", NotificationManager.IMPORTANCE_HIGH, "群里有人提到你，免打扰的群也会提醒"),
                channel(CHANNEL_SERVICE, "保持连接", NotificationManager.IMPORTANCE_MIN, "让 KukeChat 在后台也能及时收到消息，可以关掉这个渠道的显示", group = null),
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 有没有点名 @我（或者 @全体成员） */
    fun mentionsMe(message: Message, myId: Long): Boolean {
        val meta = message.metadata ?: return false
        if (meta["mention_all"]?.jsonPrimitive?.booleanOrNull == true) return true
        val mentions = meta["mentions"] as? JsonArray ?: return false
        return mentions.any { (it as? JsonObject)?.get("user_id")?.jsonPrimitive?.longOrNull == myId }
    }

    /** 这条消息该不该弹通知 */
    fun shouldNotify(conversation: Conversation, message: Message, myId: Long, visibleConversationId: Long?, appInForeground: Boolean): Boolean {
        if (message.senderId == myId || message.type == "system" || message.isRecalled) return false
        if (appInForeground && visibleConversationId == conversation.id) return false
        if (conversation.messageSetting == "ignore") return false
        if (conversation.quiet && !mentionsMe(message, myId)) return false
        return true
    }

    fun showMessage(context: Context, conversation: Conversation, message: Message, myId: Long, unread: Int) {
        if (!canPost(context)) return
        val mention = conversation.isGroup && mentionsMe(message, myId)
        val channel = when {
            mention -> CHANNEL_MENTION
            conversation.isGroup -> CHANNEL_GROUP
            else -> CHANNEL_PRIVATE
        }
        val sender = Person.Builder().setName(message.senderName).setKey("u${message.senderId}").build()
        val me = Person.Builder().setName("我").setKey("me").build()
        val line = NotificationCompat.MessagingStyle.Message(
            (if (mention) "[有人@我] " else "") + messagePreview(message),
            parseMillis(message.createdAt),
            sender,
        )
        val lines = synchronized(history) {
            history.getOrPut(conversation.id) { ArrayDeque() }.apply {
                addLast(line)
                while (size > MAX_LINES) removeFirst()
            }.toList()
        }
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (conversation.isGroup) conversation.name() else null)
            .setGroupConversation(conversation.isGroup)
        lines.forEach(style::addMessage)

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(conversation.name())
            .setContentText(messagePreview(message))
            .setNumber(unread)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(parseMillis(message.createdAt))
            .setContentIntent(openConversation(context, conversation.id))
            .build()
        NotificationManagerCompat.from(context).notify(conversation.id.toInt(), notification)
    }

    /** 打开会话、在别的设备读过了：这个会话的通知收掉 */
    fun cancel(context: Context, conversationId: Long) {
        synchronized(history) { history.remove(conversationId) }
        NotificationManagerCompat.from(context).cancel(conversationId.toInt())
    }

    fun cancelAll(context: Context) {
        synchronized(history) { history.clear() }
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun serviceNotification(context: Context, text: String) = NotificationCompat.Builder(context, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("KukeChat 正在后台接收消息")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(openConversation(context, null))
        .build()

    private fun openConversation(context: Context, conversationId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) } }
        return PendingIntent.getActivity(context, conversationId?.toInt() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun parseMillis(iso: String): Long = runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        .getOrDefault(System.currentTimeMillis())
}
