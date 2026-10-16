package ink.kuke.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import ink.kuke.chat.feature.appearance.AppearanceStore
import ink.kuke.chat.feature.appearance.LocalAppearance
import ink.kuke.chat.feature.appearance.resolve
import ink.kuke.chat.feature.auth.LoginScreen
import ink.kuke.chat.feature.settings.UpdateHost
import ink.kuke.chat.service.CatchUpWorker
import ink.kuke.chat.service.Notifier
import ink.kuke.chat.service.RealtimeService
import ink.kuke.chat.ui.shell.MainShell
import ink.kuke.chat.ui.theme.KcTheme
import ink.kuke.chat.ui.theme.KukeTheme

class MainActivity : ComponentActivity() {
    /** 从通知点进来时要打开的会话 */
    private var pendingConversation by mutableStateOf<Long?>(null)
    /** 从邀请链接（深链接）打开时的 token；登录后由 MainShell 推邀请页 */
    private var pendingInvite by mutableStateOf<String?>(null)
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        val graph = (application as KukeApp).graph

        setContent {
            val appearanceStore = androidx.compose.runtime.remember { AppearanceStore.shared(this@MainActivity) }
            val appearance by appearanceStore.state.collectAsStateWithLifecycle()
            val resolved = appearance.resolve(androidx.compose.foundation.isSystemInDarkTheme())
            CompositionLocalProvider(LocalAppearance provides appearance) {
            KukeTheme(dark = resolved.dark, accent = resolved.accent, fontScale = resolved.fontScale, resolved = resolved.colors) {
                val session by graph.session.session.collectAsStateWithLifecycle()
                val signedOut by graph.signedOut.collectAsStateWithLifecycle()
                var loaded by androidx.compose.runtime.remember { mutableStateOf(false) }
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

                LaunchedEffect(Unit) {
                    if (graph.session.session.value == null) graph.session.load()
                    loaded = true
                    ready = true
                }
                // 登录着：保持后台连接、定时补拉，第一次进来要通知权限
                LaunchedEffect(session?.token) {
                    if (session != null) {
                        RealtimeService.start(this@MainActivity)
                        CatchUpWorker.schedule(this@MainActivity)
                        graph.realtime.start()
                        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this@MainActivity)) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                Box(Modifier.fillMaxSize().background(KcTheme.colors.list)) {
                    when {
                        !loaded -> Unit
                        session == null -> LoginScreen(graph, signedOut, onSignedIn = {})
                        else -> MainShell(
                            graph,
                            openConversationId = pendingConversation,
                            onConsumedOpen = { pendingConversation = null },
                            openInviteToken = pendingInvite,
                            onConsumedInvite = { pendingInvite = null },
                        )
                    }
                    // 检查更新（不需要登录）；强制更新时对话框挡住整个应用，登录页也一样
                    if (loaded) UpdateHost(graph)
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 回到前台：连接如果在退避等待中，立刻重连
        (application as KukeApp).graph.realtime.nudge()
    }

    private fun readIntent(intent: Intent?) {
        val id = intent?.getLongExtra(Notifier.EXTRA_CONVERSATION_ID, 0L) ?: 0L
        if (id > 0) pendingConversation = id
        if (intent?.action == Intent.ACTION_VIEW) {
            ink.kuke.chat.feature.invite.InviteLinks.tokenOf(intent.dataString)?.let { pendingInvite = it }
        }
    }
}
