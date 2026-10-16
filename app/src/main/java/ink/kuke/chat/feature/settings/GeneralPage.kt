package ink.kuke.chat.feature.settings

import ink.kuke.chat.core.data.slot

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.service.KeepAlive
import ink.kuke.chat.service.Notifier
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.KeepAliveRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun Context.openNotificationSettings(channelId: String? = null) {
    val intent = if (channelId == null) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }
    intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/**
 * 设置 › 通用。系统通知和提示音在 Android 上归系统的通知渠道管，这里只负责带用户过去；
 * 「向他人显示正在输入」是服务端的账号设置（PATCH /users/me share_typing）。
 */
@Composable
fun GeneralPage(graph: AppGraph) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notificationsOn = remember(tick) { Notifier.canPost(context) }
    val keepAliveReady = remember(tick) { KeepAlive.ready(context) }

    val loader = rememberLoader(Unit, cache = graph.screenCache.slot(MY_PROFILE_CACHE_KEY)) { graph.fetchMyProfile() }
    var shareTyping by remember { mutableStateOf(true) }
    LaunchedEffect(loader.data) { loader.data?.let { shareTyping = it.shareTyping } }

    Box(Modifier.fillMaxWidth().background(colors.chat)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            GroupLabel("通知")
            GroupCard {
                SettingsRow(
                    "系统通知", icon = KcIcons.bell,
                    value = if (notificationsOn) "已开启" else "未开启",
                    valueColor = if (notificationsOn) colors.ok else colors.unread,
                    onClick = { context.openNotificationSettings() },
                )
                RowDivider()
                SettingsRow("私聊消息提示音", icon = KcIcons.volume, subtitle = "在系统通知渠道里选择铃声、震动", onClick = { context.openNotificationSettings(Notifier.CHANNEL_PRIVATE) })
                RowDivider()
                SettingsRow("群聊消息提示音", icon = KcIcons.volume, onClick = { context.openNotificationSettings(Notifier.CHANNEL_GROUP) })
                RowDivider()
                SettingsRow("@ 提醒提示音", icon = KcIcons.atSign, onClick = { context.openNotificationSettings(Notifier.CHANNEL_MENTION) })
            }

            GroupLabel("隐私")
            GroupCard {
                SwitchRow(
                    "向他人显示我正在输入", checked = shareTyping, icon = KcIcons.keyboard,
                    subtitle = "关闭后，对方看不到「正在输入…」，你也不会收到别人的",
                    enabled = loader.data != null,
                    onChange = { on ->
                        val previous = shareTyping
                        shareTyping = on
                        scope.launch {
                            runCatching { graph.api.patch("/users/me", buildJsonObject { put("share_typing", on) }) }
                                .onFailure { shareTyping = previous; toast.show(it.userMessage(), scope) }
                        }
                    },
                )
            }

            GroupLabel("连接")
            GroupCard {
                SettingsRow(
                    "后台保持连接", icon = KcIcons.bellRing,
                    subtitle = "在后台也能及时收到消息",
                    value = if (keepAliveReady) "已设置" else "需要设置",
                    valueColor = if (keepAliveReady) colors.ok else colors.unread,
                    onClick = { nav.go(KeepAliveRoute) },
                )
            }
            Text(
                "手机系统可能会把后台的应用冻结，设置里的省电策略放开后，消息才能像微信一样及时到达。",
                style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        ToastHost(toast)
    }
}
