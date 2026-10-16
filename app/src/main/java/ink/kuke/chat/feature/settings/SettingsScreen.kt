package ink.kuke.chat.feature.settings

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.appearance.AppearanceScreen
import ink.kuke.chat.feature.appearance.AppearanceStore
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.SettingsPageRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 首页行尾要显示的几个状态，进页面时静默拉一次，拉不到就不显示 */
private data class Summary(
    val ccwBound: Boolean? = null,
    val pendingReports: Int? = null,
    val points: Int? = null,
)

private suspend fun loadSummary(graph: AppGraph): Summary {
    val me = runCatching { graph.fetchMyProfile() }.getOrNull()
    val reports = runCatching {
        val element = graph.api.get("/reports/me")
        val array = (element as? JsonArray) ?: ((element as? JsonObject)?.get("items") as? JsonArray)
        array?.count { (it as? JsonObject)?.string("status") == "pending" }
    }.getOrNull()
    val points = runCatching { (graph.api.get("/moderation/me") as? JsonObject)?.get("points")?.jsonPrimitive?.intOrNull }.getOrNull()
    return Summary(me?.let { !it.ccwStudentOid.isNullOrBlank() }, reports, points)
}

/**
 * 设置首页（设计稿 P28）：分组与顺序同电脑版 220 侧栏——账号（我的资料、账号与安全）· 偏好（外观、通用、底栏与功能排序）·
 * 社区秩序（举报记录、处罚与申诉）· 其他（关于与更新、支持 KukeChat）。行尾显示当前值，有更新时红点。
 */
@Composable
fun SettingsScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    val appearance by AppearanceStore.shared(context).state.collectAsStateWithLifecycle()
    val navOrder by NavPrefsStore.shared(context).order.collectAsStateWithLifecycle()
    // 行尾的当前值：先用上次的，再静默刷新
    val summaryCache = remember(graph) { graph.screenCache.slot<Summary>("settings.summary") }
    var summary by remember { mutableStateOf(summaryCache.get() ?: Summary()) }
    LaunchedEffect(Unit) { summary = loadSummary(graph).also { summaryCache.put(it) } }
    val (updateText, updateDot) = rememberUpdateBadge(graph)
    var confirmLogout by remember { mutableStateOf(false) }

    fun open(page: String) = nav.go(SettingsPageRoute(page))

    KcPage(title = "设置", onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            // 资料头：点进「我的资料」
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.panel)
                    .clickable { open("profile") }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(me?.avatarUrl, me?.displayName.orEmpty(), tone = me?.id ?: 0, size = 52.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(me?.displayName.orEmpty(), style = type.title, color = colors.text, maxLines = 1)
                    Text("@${me?.username.orEmpty()} · 我的资料、在线状态", style = type.caption, color = colors.muted, maxLines = 1)
                }
                ink.kuke.chat.ui.icons.KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
            }

            GroupLabel("账号")
            GroupCard {
                SettingsRow("我的资料", icon = KcIcons.profile, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFF1F6BFF), onClick = { open("profile") })
                RowDivider()
                SettingsRow(
                    "账号与安全", icon = KcIcons.shieldCheck, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFF12A679),
                    value = when (summary.ccwBound) { true -> "CCW 已绑定"; false -> "CCW 未绑定"; null -> null },
                    onClick = { open("security") },
                )
            }

            GroupLabel("偏好")
            GroupCard {
                SettingsRow("外观", icon = KcIcons.palette, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFF7A55D6), value = appearance.summary, onClick = { open("appearance") })
                RowDivider()
                SettingsRow("通用", icon = KcIcons.sliders, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFF6B7385), value = "通知 · 提示音 · 隐私", onClick = { open("general") })
                RowDivider()
                SettingsRow(
                    "底栏与功能排序", icon = KcIcons.layoutGrid, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFF0B97E8),
                    value = navOrder.take(2).mapNotNull { NavFeatures.byId(it)?.label }.joinToString(" · "),
                    onClick = { open("nav") },
                )
            }

            GroupLabel("社区秩序")
            GroupCard {
                SettingsRow(
                    "举报记录", icon = KcIcons.flag, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFFF0700A),
                    value = summary.pendingReports?.takeIf { it > 0 }?.let { "$it 条处理中" },
                    onClick = { open("reports") },
                )
                RowDivider()
                SettingsRow(
                    "处罚与申诉", icon = KcIcons.gavel, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFFE5484D),
                    value = summary.points?.let { "记分 $it" },
                    onClick = { open("penalties") },
                )
            }

            GroupLabel("其他")
            GroupCard {
                SettingsRow("关于与更新", icon = KcIcons.info, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFF12A679), value = updateText, valueColor = if (updateDot) colors.accent else colors.muted, dot = updateDot, onClick = { open("about") })
                RowDivider()
                SettingsRow("支持 KukeChat", icon = KcIcons.heart, tile = true, iconTint = androidx.compose.ui.graphics.Color(0xFFE64F8A), value = "爱发电赞助 · CCW 作品投币", onClick = { open("about") })
            }

            GroupLabel("")
            GroupCard {
                SettingsRow("退出登录", icon = KcIcons.logout, danger = true, chevron = false, onClick = { confirmLogout = true })
            }
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录？",
            text = "退出后需要重新登录才能收到消息。",
            confirmText = "退出登录",
            danger = true,
            onConfirm = { graph.signOut() },
            onDismiss = { confirmLogout = false },
        )
    }
}

/** 设置的二级页：page ∈ profile / security / appearance / general / nav / reports / penalties / about / changelog */
@Composable
fun SettingsPageScreen(graph: AppGraph, page: String) {
    val nav = LocalAppNav.current
    val context = LocalContext.current
    if (page == "appearance") {
        AppearanceScreen(graph, AppearanceStore.shared(context))
        return
    }
    val title = when (page) {
        "profile" -> "我的资料"
        "security" -> "账号与安全"
        "general" -> "通用"
        "nav" -> "底栏与功能排序"
        "reports" -> "举报记录"
        "penalties" -> "处罚与申诉"
        "about" -> "关于与更新"
        "changelog" -> "更新日志"
        else -> "设置"
    }
    KcPage(title = title, onBack = { nav.back() }) {
        when (page) {
            "profile" -> ProfilePage(graph)
            "security" -> SecurityPage(graph)
            "general" -> GeneralPage(graph)
            "nav" -> NavPrefsPage()
            "reports" -> ReportsPage(graph)
            "penalties" -> PenaltiesPage(graph)
            "about" -> AboutPage(graph)
            "changelog" -> ChangelogPage(graph)
            else -> CenterText("没有这个设置页")
        }
    }
}
