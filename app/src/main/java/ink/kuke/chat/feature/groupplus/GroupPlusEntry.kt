package ink.kuke.chat.feature.groupplus

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.chat.fullTime
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant

/**
 * 群资料页里的「群升级」入口卡：当前档位（档位标签 + 到期时间）· 人数用量 · 群文件状态。
 * 群主 / 管理员显示「升级 / 续费」，普通成员只看档位信息（点进去是只读的权益对比）。
 * 自己拉 GET /conversations/{id}/plus；服务端还没有这个功能（404）时显示一张灰的「群升级 · 暂未开放」，
 * 其它失败静默收起，不影响群资料页其它内容。
 */
@Composable
fun GroupPlusEntry(graph: AppGraph, conversationId: Long, manager: Boolean, onOpen: () -> Unit, onOpenFiles: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val api = remember(graph) { GroupPlusApi(graph) }
    // 上次的档位信息（内存）：群资料页一打开就有这块，不再晚半秒弹出来把下面顶开
    val cache = remember(graph, conversationId) { graph.screenCache.slot<PlusInfo>("groupplus.info.$conversationId") }
    var info by remember { mutableStateOf(cache.get()) }
    var tick by remember { mutableStateOf(0) }
    var unavailable by remember { mutableStateOf(false) }
    LaunchedEffect(conversationId, tick) {
        runCatching { api.info(conversationId) }
            .onSuccess { info = it; cache.put(it); unavailable = false }
            .onFailure { if ((it as? ink.kuke.chat.core.net.ApiException)?.status == 404) { unavailable = true; info = null } }
    }
    LaunchedEffect(conversationId) {
        graph.realtime.events.collect { event ->
            val id = (event.data["conversation_id"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (id == conversationId && (event.type == "group.plus.updated" || event.type == "group.file.updated")) tick++
        }
    }
    if (unavailable) {
        PlusEntryUnavailable()
        return
    }
    val plus = info ?: return
    val tier = PlusTiers.effective(plus.tier, plus.expiresAt)
    val permanent = PlusTiers.isPermanent(tier, plus.expiresAt, plus.permanent)
    val until = parseInstant(plus.expiresAt)
    val daysLeft = if (permanent) null else until?.let { ((Duration.between(Instant.now(), it).seconds + 86399) / 86400) }
    val memberFull = plus.limits.membersFull(plus.usage.memberCount)

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            // 同电脑版的黑白档位层级：MAX 墨色实底 + 王冠，其它档位浅灰底
            val k = plusInk()
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (tier == "max") k.ink else k.wash2),
                contentAlignment = Alignment.Center,
            ) {
                if (tier == "max") CrownMark(k.onInk, 17.dp) else KcIcon(KcIcons.sparkles, tint = k.ink, size = 18.dp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("群升级", style = type.rowTitle, color = colors.text)
                    PlusTierTag(tier, Modifier.padding(start = 8.dp))
                }
                Text(
                    when {
                        tier == "free" -> "群成员 ${plus.limits.memberText(plus.usage.memberCount)} · 升级获得更大群与群文件"
                        permanent -> "永久 · 不会到期"
                        until != null -> "${fullTime(plus.expiresAt)} 到期" + (daysLeft?.let { if (it <= 3) "（剩 ${maxOf(it, 0)} 天）" else "" } ?: "")
                        else -> "生效中"
                    },
                    style = type.caption, color = if (daysLeft != null && daysLeft <= 3 && tier != "free") colors.warn else colors.muted,
                )
            }
            Text(
                if (!manager || permanent) "查看权益" else if (tier == "free") "去升级" else "续费 / 升级",
                style = type.secondary, color = colors.accent, fontWeight = FontWeight.W600,
            )
            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
        }
        if (tier != "free") {
            Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("群成员 ${plus.limits.memberText(plus.usage.memberCount)}", style = type.caption, color = if (memberFull) colors.warn else colors.muted, modifier = Modifier.weight(1f))
                Text("云端保留 ${plus.limits.messageLimit} 条消息", style = type.caption, color = colors.muted)
            }
        }
        if (memberFull) {
            Text(
                if (manager) "群人数已达上限，升级后可以容纳更多成员。" else "群人数已达上限，请群主或管理员升级后再邀请新成员。",
                style = type.caption, color = colors.warn,
                modifier = Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp, bottom = 10.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenFiles).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.folder, tint = colors.accent, size = 19.dp)
            }
            Text("群文件", style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text(
                when (plus.files.state) {
                    "active" -> plus.files.quotaBytes?.takeIf { it > 0 }?.let { "${formatBytes(plus.files.usedBytes)} / ${formatBytes(it)}" } ?: "临时文件"
                    "grace" -> "宽限期，只读"
                    else -> "升级到 Pro 解锁"
                },
                style = type.secondary, color = if (plus.files.state == "grace") colors.warn else colors.muted,
            )
            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
        }
    }
}

/** 服务端还没有群升级：灰掉的入口卡，不可点，只告诉大家这个功能会来 */
@Composable
private fun PlusEntryUnavailable() {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted), contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.rocket, tint = colors.subtle, size = 19.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("群升级 · 暂未开放", style = type.rowTitle, color = colors.muted)
            Text("更大的群与群文件即将上线，敬请期待", style = type.caption, color = colors.subtle)
        }
    }
}
