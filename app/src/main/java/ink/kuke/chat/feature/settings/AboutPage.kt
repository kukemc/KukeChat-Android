package ink.kuke.chat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.SettingsPageRoute
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

private const val SPONSOR_URL = "https://afdian.com/a/kukemc"
private const val CCW_SUPPORT_URL = "https://www.ccw.site/detail/66d52d2366bfcb0e0b42e7c8"
private val Developers = listOf(
    "kukemc" to "https://www.ccw.site/student/610b508176415b2f27e0f851",
    "白猫" to "https://www.ccw.site/student/6173f57f48cf8f4796fc860e",
)

/** 设置 › 关于与更新：版本、检查更新（有新版本可下载安装）、开发者、支持 */
@Composable
fun AboutPage(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val nav = LocalAppNav.current
    val phase by UpdateCenter.phase.collectAsState()

    LaunchedEffect(Unit) { if (UpdateCenter.phase.value == UpdateCenter.Phase.Idle) UpdateCenter.check(graph, manual = false) }

    Box(Modifier.fillMaxWidth().background(colors.chat)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            // 应用头
            Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.fill), contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.message, tint = colors.onFill, size = 28.dp)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text("KukeChat", style = type.title, color = colors.text)
                    Text("v${AppUpdater.currentVersion}", style = type.caption, color = colors.muted)
                }
            }

            GroupLabel("软件更新")
            GroupCard {
                Column(Modifier.padding(14.dp)) {
                    when (val s = phase) {
                        UpdateCenter.Phase.Idle, UpdateCenter.Phase.Checking -> Text("正在检查更新…", style = type.secondary, color = colors.muted)
                        is UpdateCenter.Phase.UpToDate -> Text("已是最新版本（v${s.version}）", style = type.secondary, color = colors.ok)
                        is UpdateCenter.Phase.Available -> Text("发现新版本 v${s.info.latestVersion}${if (s.info.forced) "（需要更新）" else ""}", style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600)
                        is UpdateCenter.Phase.Downloading -> {
                            Text("正在下载 v${s.info.latestVersion}${if (s.progress >= 0f) "  ${(s.progress * 100).toInt()}%" else ""}", style = type.secondary, color = colors.text)
                            Spacer(Modifier.height(10.dp))
                            if (s.progress >= 0f) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = colors.text, trackColor = colors.panelMuted)
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.text, trackColor = colors.panelMuted)
                        }
                        is UpdateCenter.Phase.Ready -> Text("v${s.info.latestVersion} 已下载，点「安装」完成更新", style = type.secondary, color = colors.ok)
                        is UpdateCenter.Phase.Error -> Text(s.message, style = type.secondary, color = colors.danger)
                    }
                    Spacer(Modifier.height(12.dp))
                    when (val s = phase) {
                        is UpdateCenter.Phase.Available -> PrimaryButton("查看更新", onClick = { UpdateCenter.open() }, modifier = Modifier.fillMaxWidth())
                        is UpdateCenter.Phase.Ready -> PrimaryButton("安装", onClick = { UpdateCenter.install(context) }, modifier = Modifier.fillMaxWidth())
                        is UpdateCenter.Phase.Downloading -> SecondaryButton("查看进度", onClick = { UpdateCenter.open() }, modifier = Modifier.fillMaxWidth())
                        UpdateCenter.Phase.Checking, UpdateCenter.Phase.Idle -> Unit
                        is UpdateCenter.Phase.Error -> SecondaryButton(if (s.info != null) "查看更新" else "检查更新", onClick = { if (s.info != null) UpdateCenter.open() else UpdateCenter.check(graph, manual = true) }, modifier = Modifier.fillMaxWidth())
                        else -> SecondaryButton("检查更新", onClick = { UpdateCenter.check(graph, manual = true) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                RowDivider(inset = 0)
                SettingsRow("更新日志", subtitle = "看看每个版本改了什么", onClick = { nav.go(SettingsPageRoute("changelog")) })
            }

            GroupLabel("开发者")
            GroupCard {
                Developers.forEachIndexed { index, (name, url) ->
                    if (index > 0) RowDivider()
                    SettingsRow(name, icon = KcIcons.profile, onClick = { context.openExternal(url) }, trailing = {
                        KcIcon(KcIcons.external, tint = colors.subtle, size = 16.dp)
                    })
                }
            }

            GroupLabel("支持 KukeChat")
            GroupCard {
                SettingsRow("爱发电赞助", icon = KcIcons.heart, iconTint = colors.danger, subtitle = "直接支持开发、维护和服务器费用", onClick = { context.openExternal(SPONSOR_URL) }, trailing = {
                    KcIcon(KcIcons.external, tint = colors.subtle, size = 16.dp)
                })
                RowDivider()
                SettingsRow("CCW 作品投币", icon = KcIcons.star, iconTint = colors.star, subtitle = "不方便赞助的话，去作品页投个币也是很大的鼓励", onClick = { context.openExternal(CCW_SUPPORT_URL) }, trailing = {
                    KcIcon(KcIcons.external, tint = colors.subtle, size = 16.dp)
                })
            }
            Text(
                "KukeChat 由个人独立开发和维护，服务器等费用也由开发者承担。",
                style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/** 设置首页「关于与更新」行尾要显示的内容：有新版本时是「v2.0.1 可更新」+ 红点 */
@Composable
fun rememberUpdateBadge(graph: AppGraph): Pair<String, Boolean> {
    LaunchedEffect(Unit) { UpdateCenter.checkOnStartup(graph) }
    val info by UpdateCenter.latest.collectAsState()
    val current = info
    return if (current != null && current.hasUpdate) "v${current.latestVersion} 可更新" to true else "v${AppUpdater.currentVersion}" to false
}
