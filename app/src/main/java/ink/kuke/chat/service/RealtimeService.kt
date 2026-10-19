package ink.kuke.chat.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.realtime.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 后台常驻服务：只要登录着就一直在，持有整个 App 唯一的那条 WebSocket（AppGraph.realtime），收到新消息就弹通知。
 *
 * 为什么能比旧版活得久：
 *   - 前台服务类型是 remoteMessaging（即时通讯用的），不是旧版的 dataSync —— Android 15 起 dataSync 每天最多跑 6 小时就被系统停掉；
 *   - START_STICKY：被系统回收后，系统资源宽裕时会把它重新拉起来；开机、App 更新后由 BootReceiver 拉起；
 *   - 网络切换（Wi-Fi ↔ 流量）、恢复时立刻重连，不等退避；
 *   - 真被杀了、拉不起来，CatchUpWorker 定时补拉漏掉的消息。
 * 再往上（被国产系统彻底冻结）只能靠用户把 KukeChat 加进电池优化白名单和自启动名单（KeepAliveGuide 引导）。
 */
class RealtimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        goForeground("正在连接…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as KukeApp).graph
        scope.launch {
            // 开机 / 被系统重启时进程是新的，登录状态还没读出来
            val session = graph.session.session.value ?: graph.session.load()
            if (session == null) {
                stopSelf()
                return@launch
            }
            if (collector == null) {
                collector = launch { collectMessages() }
                launch { watchState() }
                watchNetwork()
            }
            graph.realtime.start()
            graph.conversations.refresh()
        }
        return START_STICKY
    }

    private suspend fun collectMessages() {
        val graph = (application as KukeApp).graph
        graph.realtime.events.collect { event ->
            when (event.type) {
                "message.created" -> {
                    val message = runCatching { KcJson.decodeFromJsonElement(Message.serializer(), event.data) }.getOrNull() ?: return@collect
                    val myId = graph.session.session.value?.user?.id ?: return@collect
                    // 会话列表可能还没有它（新会话）：先拉一遍再判断
                    val conversation = graph.conversations.find(message.conversationId)
                        ?: graph.conversations.refresh().getOrNull()?.firstOrNull { it.id == message.conversationId }
                        ?: return@collect
                    val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    if (Notifier.shouldNotify(conversation, message, myId, graph.conversations.visibleConversationId, foreground)) {
                        val unread = graph.conversations.find(conversation.id)?.unreadCount ?: 1
                        Notifier.showMessage(this, conversation, message, myId, unread)
                        CatchUpWorker.markNotified(this, conversation.id, message.id)
                    }
                }
                "conversation.read" -> {
                    // 在别的设备上读过了：通知收掉
                    val conversationId = event.data["conversation_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                    // 「我的设备」的已读是按人记的：别的设备读过不代表这台看过，通知留着
                    if (event.data["user_id"]?.jsonPrimitive?.longOrNull == graph.session.session.value?.user?.id && !graph.conversations.isSelf(conversationId)) {
                        Notifier.cancel(this, conversationId)
                    }
                }
            }
        }
    }

    private suspend fun watchState() {
        val graph = (application as KukeApp).graph
        graph.realtime.state.collect { state ->
            val text = when (state) {
                ConnectionState.Connected -> "已连接，新消息会第一时间提醒你"
                ConnectionState.Connecting, ConnectionState.Waiting -> "正在连接…"
                ConnectionState.Idle -> "连接已断开"
            }
            if (Notifier.canPost(this)) {
                NotificationManagerCompat.from(this).notify(Notifier.SERVICE_NOTIFICATION_ID, Notifier.serviceNotification(this, text))
            }
        }
    }

    private fun watchNetwork() {
        val graph = (application as KukeApp).graph
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = graph.realtime.nudge()
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Notifier.SERVICE_NOTIFICATION_ID, Notifier.serviceNotification(this, text), type)
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KcRealtimeService"

        /** 登录后、App 打开时调。从后台启动被系统拒绝（Android 12+ 的限制）时只记日志，等下次打开 App 再起 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RealtimeService::class.java))
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "Not allowed to start foreground service from background")
                } else {
                    Log.w(TAG, "Failed to start realtime service", e)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeService::class.java))
        }
    }
}
