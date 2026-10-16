package ink.kuke.chat.feature.bots

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.SectionLabel
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme

/** 创建者的仪表盘：发送 / 接收量、接入群数、近期趋势、评分分布、接入的群。只读 */
@Composable
internal fun BotDashboardPage(graph: AppGraph, bot: Bot, onBack: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val api = remember { BotApi(graph.api) }
    // 上次的仪表盘（内存）：先摆出来，再静默刷新
    val cache = remember(graph, bot.id) { graph.screenCache.slot<BotDashboard>("bot.dashboard.${bot.id}") }
    var data by remember { mutableStateOf(cache.get()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(bot.id, tick) {
        error = null
        runCatching { api.dashboard(bot.id) }.onSuccess { data = it; cache.put(it) }.onFailure { error = it.userMessage() }
    }

    KcPage(title = "${bot.name} · 仪表盘", onBack = onBack) {
        val dash = data
        when {
            dash == null && error != null -> ErrorBlock(error!!, { tick++ })
            dash == null -> CenterText("正在加载…")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val m = dash.metrics
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("今日发送", m.todaySent, Modifier.weight(1f))
                    Metric("今日接收", m.todayReceived, Modifier.weight(1f))
                    Metric("接入群聊", m.installedGroups, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("累计发送", m.totalSent, Modifier.weight(1f))
                    Metric("累计接收", m.totalReceived, Modifier.weight(1f))
                    Metric("触达成员", m.membersReached, Modifier.weight(1f))
                }

                SectionLabel("近期趋势", trailing = "发送 / 接收")
                Card {
                    Column(Modifier.padding(14.dp)) {
                        if (dash.trend.isEmpty()) {
                            Text("暂时没有数据", style = type.secondary, color = colors.subtle)
                        } else {
                            val maxValue = dash.trend.maxOf { maxOf(it.sent, it.received) }.coerceAtLeast(1)
                            val sentColor = colors.accent
                            val receivedColor = colors.subtle
                            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                                val slot = size.width / dash.trend.size
                                val barWidth = (slot * 0.34f).coerceAtLeast(2f)
                                dash.trend.forEachIndexed { index, point ->
                                    val x = index * slot + slot * 0.15f
                                    val sentHeight = size.height * point.sent / maxValue
                                    val receivedHeight = size.height * point.received / maxValue
                                    drawRect(sentColor, Offset(x, size.height - sentHeight), Size(barWidth, sentHeight))
                                    drawRect(receivedColor, Offset(x + barWidth + 2f, size.height - receivedHeight), Size(barWidth, receivedHeight))
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text(dash.trend.first().date.takeLast(5), style = type.caption, color = colors.subtle, modifier = Modifier.weight(1f))
                                Text(dash.trend.last().date.takeLast(5), style = type.caption, color = colors.subtle)
                            }
                        }
                    }
                }

                SectionLabel("评分分布", trailing = m.ratingAverage?.let { "平均 ${ratingText(it)} · ${m.reviewCount} 条" })
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val buckets = (5 downTo 1).map { star -> star to (dash.ratingDistribution.firstOrNull { it.rating == star }?.count ?: 0) }
                        val maxCount = buckets.maxOf { it.second }.coerceAtLeast(1)
                        buckets.forEach { (star, count) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$star ★", style = type.caption, color = colors.muted, modifier = Modifier.width(34.dp))
                                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.panelMuted)) {
                                    Box(Modifier.fillMaxWidth(count.toFloat() / maxCount).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.star))
                                }
                                Text("$count", style = type.caption, color = colors.text, modifier = Modifier.width(36.dp).padding(start = 8.dp))
                            }
                        }
                    }
                }

                SectionLabel("接入的群聊", trailing = "${dash.installations.size} 个")
                if (dash.installations.isEmpty()) {
                    Text("还没有群聊接入", style = type.secondary, color = colors.subtle)
                }
                dash.installations.forEach { install ->
                    Card {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(install.title, style = type.rowTitle, color = colors.text, maxLines = 1)
                                Text("${install.memberCount} 人 · 发送 ${install.sentMessages} · 接收 ${install.receivedMessages}", style = type.caption, color = colors.muted)
                            }
                            Text(if (install.enabled) "启用中" else "已停用", style = type.caption, color = if (install.enabled) colors.ok else colors.subtle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(countText(value), style = KcTheme.type.number, color = colors.text)
        Text(label, style = KcTheme.type.caption, color = colors.muted)
    }
}
