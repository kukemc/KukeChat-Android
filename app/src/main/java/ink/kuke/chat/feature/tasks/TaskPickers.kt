package ink.kuke.chat.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.model.User
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** 截止时间选择的结果：时间为 null 表示清除；remind 是提前多少分钟提醒（null 不提醒） */
internal data class DueResult(val due: LocalDateTime?, val remindMinutes: Int?)

private enum class DueStage { Main, Date, Time }

/**
 * 截止时间：日期 + 可选的具体时间 + 提醒。不带具体时间的记成当天 0 点（和电脑版一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuePickerDialog(initial: LocalDateTime?, initialRemind: Int?, onConfirm: (DueResult) -> Unit, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    var stage by remember { mutableStateOf(DueStage.Main) }
    var date by remember { mutableStateOf(initial?.toLocalDate()) }
    var hasTime by remember { mutableStateOf(initial != null && !(initial.hour == 0 && initial.minute == 0)) }
    var time by remember { mutableStateOf(initial?.toLocalTime()?.takeIf { hasTime } ?: LocalTime.of(18, 0)) }
    var remind by remember { mutableStateOf(initialRemind) }

    when (stage) {
        DueStage.Date -> {
            // DatePicker 用 UTC 午夜的毫秒数表示一天，转换时必须按 UTC 解释，否则东八区会差一天
            val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { stage = DueStage.Main },
                confirmButton = {
                    SoftButton("确定", onClick = {
                        state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        stage = DueStage.Main
                    })
                },
                dismissButton = { SoftButton("取消", onClick = { stage = DueStage.Main }) },
                colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = colors.panel),
            ) { DatePicker(state = state) }
        }
        DueStage.Time -> {
            val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
            DialogCard(onDismiss = { stage = DueStage.Main }, title = "选择时间") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("取消", { stage = DueStage.Main }, Modifier.weight(1f))
                    PrimaryButton("确定", {
                        time = LocalTime.of(state.hour, state.minute)
                        hasTime = true
                        stage = DueStage.Main
                    }, Modifier.weight(1f))
                }
            }
        }
        DueStage.Main -> DialogCard(onDismiss, "截止时间") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).clickable { stage = DueStage.Date }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("日期", style = KcTheme.type.body, color = colors.muted, modifier = Modifier.weight(1f))
                Text(date?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } ?: "选择日期", style = KcTheme.type.body, color = if (date == null) colors.subtle else colors.text)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("具体时间", style = KcTheme.type.body, color = colors.muted, modifier = Modifier.weight(1f))
                if (hasTime) {
                    Text(
                        "%02d:%02d".format(time.hour, time.minute), style = KcTheme.type.body, color = colors.accent, fontWeight = FontWeight.W600,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { stage = DueStage.Time }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Switch(checked = hasTime, onCheckedChange = { hasTime = it; if (it) stage = DueStage.Time })
            }
            Text("提醒", style = KcTheme.type.group, color = colors.muted)
            Column {
                RemindOptions.forEach { (minutes, label) ->
                    val enabled = date != null || minutes == null
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { remind = minutes }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = KcTheme.type.body, color = if (!enabled) colors.subtle else if (remind == minutes) colors.accent else colors.text, fontWeight = if (remind == minutes) FontWeight.W600 else FontWeight.W400, modifier = Modifier.weight(1f))
                        if (remind == minutes) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("清除", { onConfirm(DueResult(null, null)) }, Modifier.weight(1f), danger = true)
                PrimaryButton("确定", {
                    val picked = date
                    if (picked == null) onConfirm(DueResult(null, null))
                    else onConfirm(DueResult(LocalDateTime.of(picked, if (hasTime) time else LocalTime.MIDNIGHT), remind))
                }, Modifier.weight(1f))
            }
        }
    }
}

/** 多选成员（负责人 / 关注人）。members 是任务所在会话的成员 */
@Composable
internal fun MemberPickDialog(title: String, members: List<User>, selected: Set<Long>, onConfirm: (Set<Long>) -> Unit, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    var picked by remember { mutableStateOf(selected) }
    DialogCard(onDismiss, title) {
        if (members.isEmpty()) {
            Text("还没有可选的成员", style = KcTheme.type.secondary, color = colors.muted)
        } else {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                members.forEach { user ->
                    val on = user.id in picked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { picked = if (on) picked - user.id else picked + user.id }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(user.avatarUrl, user.displayName, user.id, size = 34.dp)
                        Text(user.displayName, style = KcTheme.type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(if (on) colors.fill else colors.panelMuted),
                            contentAlignment = Alignment.Center,
                        ) { if (on) KcIcon(KcIcons.check, tint = colors.onFill, size = 14.dp) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("取消", onDismiss, Modifier.weight(1f))
            PrimaryButton("确定", { onConfirm(picked) }, Modifier.weight(1f))
        }
    }
}
