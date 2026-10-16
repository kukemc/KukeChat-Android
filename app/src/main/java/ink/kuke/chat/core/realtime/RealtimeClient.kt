package ink.kuke.chat.core.realtime

import android.util.Log
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import kotlin.math.min
import kotlin.random.Random

/** 服务端推来的一个事件：{"type": "...", "data": {...}} */
data class RealtimeEvent(val type: String, val data: JsonObject)

enum class ConnectionState { Idle, Connecting, Connected, Waiting }

/**
 * 整个 App 唯一的一条 WebSocket（/ws?token=）。由后台常驻服务（RealtimeService）持有并启停，界面只订阅 events。
 *
 * 协议（KukeChat-Server/app/websocket/routes.py）：
 *   - 每 25 秒发文本 ping，服务端回文本 pong；10 秒没回就当断了，主动关掉重连。
 *   - 断线重连：min(1s·2^n, 30s) + 0–500ms 抖动；网络恢复时（NetworkWatcher）立刻重连、不等退避。
 *   - 关闭码 1008 = 令牌无效 / 账号被封 / 密码被重置：不再重连，交给 onAuthLost。
 */
class RealtimeClient(
    private val http: OkHttpClient,
    private val tokenProvider: () -> String?,
    private val onAuthLost: (reason: String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()
    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** 重连之后要补拉的东西（会话列表、未读）靠这个信号 */
    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val reconnected: SharedFlow<Unit> = _reconnected.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var lastPongAt = 0L
    private var attempt = 0
    private var heartbeat: Job? = null
    private var retry: Job? = null
    private var hadConnection = false

    /** 开始保持连接（登录后、服务启动时调；重复调用没关系） */
    fun start() {
        wanted = true
        if (socket == null && retry?.isActive != true && _state.value != ConnectionState.Connecting) connect()
    }

    /** 不要连接了（退出登录、被封） */
    fun stop() {
        wanted = false
        retry?.cancel()
        heartbeat?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.value = ConnectionState.Idle
    }

    /**
     * 网络刚恢复 / App 回到前台：没连着就立刻连，不等退避；正在连就让它连完（掐掉重来只会更慢，
     * 小米等系统上网络回调还可能一次触发好几下）；已经连着就发个 ping 确认一下还活着。
     */
    fun nudge() {
        if (!wanted) return
        when (_state.value) {
            ConnectionState.Connected -> socket?.send("ping")
            ConnectionState.Connecting -> Unit
            ConnectionState.Waiting, ConnectionState.Idle -> {
                retry?.cancel()
                attempt = 0
                connect()
            }
        }
    }

    /** 客户端发给服务端的只有 typing（其余走 HTTP） */
    fun sendTyping(conversationId: Long) {
        socket?.send("""{"type":"typing","conversation_id":$conversationId}""")
    }

    private fun connect() {
        val token = tokenProvider()
        if (!wanted || token.isNullOrBlank()) {
            _state.value = ConnectionState.Idle
            return
        }
        socket?.cancel()
        _state.value = ConnectionState.Connecting
        val separator = if (Endpoints.ws.contains('?')) '&' else '?'
        val request = Request.Builder().url("${Endpoints.ws}${separator}token=${URLEncoder.encode(token, "UTF-8")}").build()
        socket = http.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            attempt = 0
            lastPongAt = System.currentTimeMillis()
            Log.i(TAG, "connected")
            _state.value = ConnectionState.Connected
            if (hadConnection) _reconnected.tryEmit(Unit)
            hadConnection = true
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "pong") {
                lastPongAt = System.currentTimeMillis()
                Log.d(TAG, "pong")
                return
            }
            val obj = runCatching { KcJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
            val data = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
            if (type == "account.suspended" || type == "auth.password_reset") {
                wanted = false
                onAuthLost(type)
            }
            _events.tryEmit(RealtimeEvent(type, data))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "server closing code=$code reason=$reason")
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "socket failure: ${t.message}")
            dropped(webSocket, response?.code ?: 0)
        }
    }

    private fun dropped(webSocket: WebSocket, code: Int) {
        if (webSocket !== socket) return
        Log.i(TAG, "dropped code=$code")
        socket = null
        heartbeat?.cancel()
        if (code == 1008) {
            // 令牌失效或账号被封：重连也没用
            wanted = false
            _state.value = ConnectionState.Idle
            onAuthLost(null)
            return
        }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!wanted) {
            _state.value = ConnectionState.Idle
            return
        }
        _state.value = ConnectionState.Waiting
        val delayMs = min(1000L shl min(attempt, 5), 30_000L) + Random.nextLong(0, 500)
        attempt += 1
        retry?.cancel()
        retry = scope.launch {
            delay(delayMs)
            if (wanted) connect()
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive && webSocket === socket) {
                delay(PING_INTERVAL_MS)
                val sentAt = System.currentTimeMillis()
                if (!webSocket.send("ping")) break
                delay(PONG_TIMEOUT_MS)
                if (lastPongAt < sentAt && webSocket === socket) {
                    // 10 秒没回 pong：连接其实已经死了（常见于切网络、休眠），关掉重连
                    webSocket.cancel()
                    dropped(webSocket, 0)
                    break
                }
            }
        }
    }

    companion object {
        private const val TAG = "KcRealtime"
        private const val PING_INTERVAL_MS = 25_000L
        private const val PONG_TIMEOUT_MS = 10_000L
    }
}
