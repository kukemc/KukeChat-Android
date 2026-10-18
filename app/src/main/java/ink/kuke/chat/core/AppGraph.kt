package ink.kuke.chat.core

import android.content.Context
import ink.kuke.chat.core.data.ConversationRepository
import ink.kuke.chat.core.data.MessageCache
import ink.kuke.chat.core.data.ScreenCache
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.DeviceBan
import ink.kuke.chat.core.net.DeviceIdentity
import ink.kuke.chat.core.net.Suspension
import ink.kuke.chat.core.realtime.RealtimeClient
import ink.kuke.chat.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 为什么被登出了：令牌失效（重新登录）或者账号被封（带原因） */
sealed interface SignedOut {
    data object Expired : SignedOut
    data class Suspended(val info: Suspension) : SignedOut
    data object PasswordReset : SignedOut
    /** 这台设备被封（403 device_banned）：换号也登不上，登录页一直挂着说明 */
    data class DeviceBanned(val info: DeviceBan) : SignedOut
}

/**
 * 进程里的单例们（手写的依赖容器，不引 Hilt）。界面和后台服务拿的是同一份：同一个会话列表、同一条 WebSocket。
 */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = SessionStore(appContext)

    private val _signedOut = MutableStateFlow<SignedOut?>(null)
    /** 界面看到它就回登录页并说明原因 */
    val signedOut: StateFlow<SignedOut?> = _signedOut.asStateFlow()

    val api = Api(
        tokenProvider = { session.token },
        onSuspended = { signOut(SignedOut.Suspended(it)) },
        onUnauthorized = { if (session.token != null) signOut(SignedOut.Expired) },
        device = DeviceIdentity.create(appContext),
        // 登录着就退出；在登录页（登录 / 注册被拒）只挂上说明
        onDeviceBanned = { if (session.token != null) signOut(SignedOut.DeviceBanned(it)) else _signedOut.value = SignedOut.DeviceBanned(it) },
    )

    init {
        // 还没有设备令牌：启动时在后台要一个（登录 / 注册前还会再等一下）；读令牌文件也放后台
        scope.launch(Dispatchers.IO) { api.primeDevice() }
    }

    val realtime = RealtimeClient(
        http = api.http,
        tokenProvider = { session.token },
        onAuthLost = { reason -> signOut(if (reason == "auth.password_reset") SignedOut.PasswordReset else SignedOut.Expired) },
    )

    val messageCache = MessageCache(appContext, scope, realtime)

    /** 各页面「上次看到的数据」：先画旧的再静默刷新。按登录用户隔离，退出登录清空 */
    val screenCache = ScreenCache(java.io.File(appContext.filesDir, "screen-cache"), scope) { session.session.value?.user?.id }

    val conversations = ConversationRepository(api, realtime, scope, screenCache) { session.session.value?.user?.id }

    fun signOut(reason: SignedOut? = null) {
        realtime.stop()
        _signedOut.value = reason
        scope.launch {
            session.clear()
            conversations.clear()
            ink.kuke.chat.feature.contacts.PendingRequests.clear()
            messageCache.clear()
            screenCache.clear()
        }
        scope.launch(Dispatchers.Main) {
            ink.kuke.chat.feature.chat.Outbox.clear()
            ink.kuke.chat.feature.chat.FavoriteStickers.clear()
        }
        ink.kuke.chat.service.RealtimeService.stop(appContext)
        ink.kuke.chat.service.Notifier.cancelAll(appContext)
        ink.kuke.chat.service.CatchUpWorker.cancel(appContext)
        // 系统分享面板 / 桌面长按里的会话快捷方式是这个账号的，一起撤掉
        ink.kuke.chat.feature.share.ShareShortcuts.clear(appContext)
    }

    fun acknowledgeSignOut() {
        _signedOut.value = null
    }
}
