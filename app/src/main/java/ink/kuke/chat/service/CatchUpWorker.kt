package ink.kuke.chat.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.realtime.ConnectionState
import java.util.concurrent.TimeUnit

/**
 * 兜底补拉：系统大约每 15 分钟（有网时）唤醒一次。常驻连接还活着就什么都不做；
 * 连接没了（服务被系统杀掉、拉不起来）就拉一遍会话列表，有没提醒过的新消息就补一条通知，再试着把常驻服务拉起来。
 * 被杀之后最多晚十几分钟收到提醒，总比完全收不到强。
 */
class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as KukeApp).graph
        val session = graph.session.session.value ?: graph.session.load() ?: return Result.success()
        if (graph.realtime.state.value == ConnectionState.Connected) return Result.success()

        val list = graph.conversations.refresh().getOrElse { return Result.retry() }
        val myId = session.user.id
        for (conversation in list) {
            val last = conversation.lastMessage ?: continue
            if (conversation.unreadCount <= 0 || last.id <= notifiedUpTo(applicationContext, conversation.id)) continue
            if (!Notifier.shouldNotify(conversation, last, myId, visibleConversationId = null, appInForeground = false)) continue
            Notifier.showMessage(applicationContext, conversation, last, myId, conversation.unreadCount)
            markNotified(applicationContext, conversation.id, last.id)
        }
        // 能拉起来最好（Android 12+ 从后台多半不让，失败只记日志）
        RealtimeService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "kukechat-catch-up"
        private const val PREFS = "notified_up_to"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatchUpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        /** 每个会话已经提醒到哪一条：常驻服务弹了通知也记一笔，补拉时不重复提醒 */
        fun markNotified(context: Context, conversationId: Long, messageId: Long) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(conversationId.toString(), 0L) < messageId) {
                prefs.edit().putLong(conversationId.toString(), messageId).apply()
            }
        }

        private fun notifiedUpTo(context: Context, conversationId: Long): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(conversationId.toString(), 0L)
    }
}
