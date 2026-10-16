package ink.kuke.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.service.Notifier

/**
 * 开发版专用：
 *   adb shell am broadcast -a ink.kuke.chat.DEBUG_FAKE_MESSAGE -p ink.kuke.chat.dev --el conversation <会话 id> --es text "你好" [--ez mention true]
 *     用会话列表里那个会话的名字和设置，走和真消息一样的「该不该弹」判断，再弹通知
 *   adb shell am broadcast -a ink.kuke.chat.DEBUG_DUMP -p ink.kuke.chat.dev
 *     在 logcat（标签 KcDebug）里打出连接状态和会话数
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KukeApp).graph
        when (intent.action) {
            "ink.kuke.chat.DEBUG_DUMP" -> Log.i(TAG, "state=${graph.realtime.state.value} conversations=${graph.conversations.items.value.size} visible=${graph.conversations.visibleConversationId}")
            "ink.kuke.chat.DEBUG_FAKE_MESSAGE" -> {
                val me = graph.session.session.value?.user ?: run { Log.w(TAG, "not signed in"); return }
                val list = graph.conversations.items.value
                val id = intent.getLongExtra("conversation", 0L)
                val conversation = list.firstOrNull { it.id == id } ?: list.firstOrNull { !it.quiet && !it.isGroup } ?: list.firstOrNull { !it.quiet } ?: run { Log.w(TAG, "no conversations"); return }
                val sender = conversation.directUser ?: User(id = -1, username = "test", nickname = "测试发送者")
                val mention = intent.getBooleanExtra("mention", false)
                val meta = if (mention) kotlinx.serialization.json.buildJsonObject {
                    put("mentions", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject { put("user_id", kotlinx.serialization.json.JsonPrimitive(me.id)) })
                    })
                } else null
                val message = Message(
                    id = System.currentTimeMillis(),
                    conversationId = conversation.id,
                    senderId = sender.id,
                    sender = sender,
                    content = intent.getStringExtra("text") ?: "这是一条测试消息",
                    metadata = meta,
                    createdAt = java.time.OffsetDateTime.now().toString(),
                )
                val allowed = Notifier.shouldNotify(conversation, message, me.id, graph.conversations.visibleConversationId, appInForeground = false)
                Log.i(TAG, "fake message in ${conversation.id} (${conversation.name()}) quiet=${conversation.quiet} allowed=$allowed")
                if (allowed) Notifier.showMessage(context, conversation, message, me.id, conversation.unreadCount + 1)
            }
        }
    }

    companion object {
        private const val TAG = "KcDebug"
    }
}
