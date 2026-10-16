package ink.kuke.chat.feature.user

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.contacts.KcTextField
import ink.kuke.chat.feature.contacts.SmallButton
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 账号等级（个人主页、资料卡）：等级图标串 + 具体的「Lv.N」，看不懂星星月亮的人也能一眼看出几级 */
@Composable
fun LevelChip(level: Int, modifier: Modifier = Modifier) {
    if (level <= 0) return
    val colors = KcTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(99.dp)).background(colors.accentSoft).padding(start = 6.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ink.kuke.chat.feature.level.LevelIcons(level, size = 14.dp, compact = true)
        Text("Lv.$level", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.W700, modifier = Modifier.padding(start = 4.dp))
    }
}

/** 一个竖排的操作按钮：图标块 + 文案。不可用时变灰；赞出时心形弹簧放大（pulse 变化触发） */
@Composable
fun ActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = KcTheme.colors.text,
    enabled: Boolean = true,
    pulse: Int = 0,
    onClick: () -> Unit,
) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            scale.snapTo(1f)
            scale.animateTo(1.35f, spring(dampingRatio = 0.4f, stiffness = 600f))
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
        }
    }
    Column(
        modifier
            .pressScale(press)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, interactionSource = press, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.panelMuted), contentAlignment = Alignment.Center) {
            KcIcon(icon, tint = if (enabled) tint else colors.subtle, size = 22.dp, modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value })
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = KcTheme.type.caption, color = if (enabled) colors.text else colors.subtle, maxLines = 1)
    }
}

/** 举报用户底部面板：理由来自 GET /reports/options，「其他」要写说明 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportUserSheet(api: UserApi, userId: Long, name: String, conversationId: Long?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<ReportOptions?>(null) }
    var picked by remember { mutableStateOf<ReportReason?>(null) }
    var details by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { options = api.reportOptions() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = colors.panel) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("举报 $name", style = type.title, color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text("选择举报原因。举报会交给风纪委员和管理员核实，不会通知对方。", style = type.caption, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            val current = options
            if (current == null) {
                Text("正在加载举报选项…", style = type.secondary, color = colors.muted)
            } else {
                current.reasons.forEach { reason ->
                    val selected = picked?.reason == reason.reason
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = reason }.height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(reason.reason, style = type.body, color = colors.text, modifier = Modifier.weight(1f))
                        if (selected) KcIcon(KcIcons.check, tint = colors.accent, size = 20.dp)
                    }
                }
                val reason = picked
                if (reason != null) {
                    Spacer(Modifier.height(8.dp))
                    KcTextField(
                        details, { if (it.length <= 300) details = it },
                        if (reason.detailsRequired) "请说明情况（至少 ${current.detailsMinLength} 字）" else "补充说明（可选）",
                        icon = null, singleLine = false, minHeight = 80.dp,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = type.caption, color = colors.danger)
                }
                Spacer(Modifier.height(16.dp))
                val ready = reason != null && (!reason.detailsRequired || details.trim().length >= current.detailsMinLength)
                SmallButton(
                    if (sending) "提交中…" else "提交举报",
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = ready && !sending,
                ) {
                    val chosen = picked ?: return@SmallButton
                    sending = true
                    error = null
                    scope.launch {
                        runCatching { api.reportUser(userId, chosen.reason, details, conversationId) }
                            .onSuccess { onDone("举报已提交，感谢你维护社区") }
                            .onFailure { error = it.userMessage() }
                        sending = false
                    }
                }
            }
        }
    }
}

/** 一行带标题的信息（统计、简介等用） */
@Composable
fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = KcTheme.type.number.copy(fontSize = 19.sp), color = KcTheme.colors.text)
        Text(label, style = KcTheme.type.caption, color = KcTheme.colors.muted)
    }
}

/** 数字缩写：12,800 → 1.3w，和电脑版一致的口径（≥1 万用 w） */
fun shortCount(value: Int): String = when {
    value >= 10_000 -> "${(value / 1000) / 10.0}w"
    else -> value.toString()
}

@Composable
fun Divider1(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(KcTheme.colors.border))
}
