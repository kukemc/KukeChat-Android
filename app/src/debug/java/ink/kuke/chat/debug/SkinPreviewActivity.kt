package ink.kuke.chat.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.shop.SkinBubble
import ink.kuke.chat.feature.shop.parseAvatarFrame
import ink.kuke.chat.feature.shop.parseBubbleSkin
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.theme.KcTheme
import ink.kuke.chat.ui.theme.KukeTheme
import kotlinx.serialization.json.JsonObject

/**
 * 开发版专用：把电脑版 dev/mocks/shop.ts 里的示例气泡皮肤、头像框（assets/skin_mocks.json，同一份数据）画出来，核对和电脑版是否一致。
 *   adb shell am start -n ink.kuke.chat.dev/ink.kuke.chat.debug.SkinPreviewActivity [--ez dark true]
 * 纯展示，不请求接口、不碰登录状态。
 */
class SkinPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = intent.getBooleanExtra("dark", false)
        val mocks = runCatching { KcJson.parseToJsonElement(assets.open("skin_mocks.json").bufferedReader().readText()) as JsonObject }.getOrNull()
        setContent { KukeTheme(dark = dark) { SkinPreview(mocks) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkinPreview(mocks: JsonObject?) {
    val colors = KcTheme.colors
    val skins = (mocks?.get("skins") as? JsonObject).orEmpty()
    val frames = (mocks?.get("frames") as? JsonObject).orEmpty()
    Column(
        Modifier.fillMaxSize().background(colors.chat).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("头像框", color = colors.text, fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalArrangement = Arrangement.spacedBy(30.dp), modifier = Modifier.padding(16.dp)) {
            frames.entries.forEachIndexed { index, (name, style) ->
                val frame = remember(style) { parseAvatarFrame(style) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(null, name, tone = index.toLong(), size = 48.dp, frame = frame)
                    Text(if (frame == null) "$name ✕" else name, color = colors.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
        Text("气泡皮肤", color = colors.text, fontSize = 15.sp)
        skins.entries.forEachIndexed { index, (name, style) ->
            val skin = remember(style) { parseBubbleSkin(style) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(if (skin == null) "$name ✕" else name, color = colors.muted, fontSize = 10.sp)
                Box(Modifier.width(12.dp))
                SkinBubble(skin, "$name：今天也要开心呀，一起去看樱花吧", seed = index.toLong())
            }
        }
    }
}
