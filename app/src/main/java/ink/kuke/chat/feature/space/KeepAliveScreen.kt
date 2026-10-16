package ink.kuke.chat.feature.space

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ink.kuke.chat.service.KeepAlive
import ink.kuke.chat.service.Notifier
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 「后台接收消息」引导：通知权限、省电策略 / 电池优化、自启动三项，各自一个直达按钮。
 * 从设置页回来（ON_RESUME）重新检测；前两项能查到状态，自启动查不到，只给入口和说明。
 * 登录后发现前两项没放开会自动推出这一页一次（MainShell），以后从「空间 › 设置 › 后台接收消息」进。
 */
@Composable
fun KeepAliveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = KcTheme.colors
    val type = KcTheme.type
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    // 从系统设置页回来时 tick 变了，重新检测
    val notifications = remember(tick) { Notifier.canPost(context) }
    val battery = remember(tick) { KeepAlive.ignoringBatteryOptimizations(context) }
    val vendor = KeepAlive.vendor

    Column(Modifier.fillMaxSize().background(colors.chat)) {
        Row(Modifier.fillMaxWidth().background(colors.panel).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.chevronLeft, tint = colors.text, size = 24.dp, contentDescription = "返回")
            }
            Text("后台接收消息", style = type.title, color = colors.text)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (notifications && battery) "都设置好了。KukeChat 在后台也能及时收到消息。"
                else "${vendor.label}会在 App 退到后台后很快把它冻结，消息就收不到了。按下面几步放开，KukeChat 才能像 QQ、微信一样在后台及时提醒你。",
                style = type.secondary,
                color = colors.muted,
            )
            Step(
                icon = KcIcons.bell,
                title = "允许通知",
                hint = "不然新消息没法提醒你",
                done = notifications,
                action = "去开启",
                onClick = { KeepAlive.open(context, listOf(KeepAlive.notificationSettings(context))) },
            )
            Step(
                icon = KcIcons.batteryFull,
                title = "不限制后台耗电",
                hint = KeepAlive.batteryHint(),
                done = battery,
                action = "去设置",
                onClick = { KeepAlive.open(context, KeepAlive.batteryIntents(context)) },
            )
            Step(
                icon = KcIcons.refresh,
                title = "允许自启动",
                hint = KeepAlive.autostartHint() + "，开机后能自己连上",
                done = null,
                action = "去设置",
                onClick = { KeepAlive.open(context, KeepAlive.autostartIntents(context)) },
            )
            Text(
                "放开之后 KukeChat 会在通知栏常驻一条「正在后台接收消息」，这是安卓要求的，可以在系统通知设置里把「保持连接」这一类收起来。耗电和 QQ、微信的后台差不多。",
                style = type.caption,
                color = colors.subtle,
            )
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, hint: String, done: Boolean?, action: String, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 双色图标块
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            KcIcon(icon, tint = colors.accent, size = 22.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = type.rowTitle, color = colors.text)
            Spacer(Modifier.height(2.dp))
            Text(hint, style = type.caption, color = colors.muted)
        }
        if (done == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KcIcon(KcIcons.checkCircle, tint = colors.ok, size = 18.dp)
                Text("已开启", style = type.caption, color = colors.ok, modifier = Modifier.padding(start = 4.dp))
            }
        } else {
            Text(
                action,
                style = type.secondary,
                color = colors.onFill,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.fill).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
