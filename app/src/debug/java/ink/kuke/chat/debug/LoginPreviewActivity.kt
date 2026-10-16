package ink.kuke.chat.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.SignedOut
import ink.kuke.chat.feature.auth.LoginScreen
import ink.kuke.chat.ui.theme.KukeTheme

/**
 * 开发版专用：adb shell am start -n ink.kuke.chat.dev/ink.kuke.chat.debug.LoginPreviewActivity [--es notice expired|suspended]
 * 直接显示登录页，不动当前登录状态；点登录会真的请求接口，核对界面时别点。
 */
class LoginPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as KukeApp).graph
        val notice = when (intent.getStringExtra("notice")) {
            "expired" -> SignedOut.Expired
            else -> null
        }
        setContent { KukeTheme { LoginScreen(graph, notice, onSignedIn = { finish() }) } }
    }
}
