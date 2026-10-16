package ink.kuke.chat.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.feature.feed.CcwCreator
import ink.kuke.chat.feature.feed.CreatorsHallPage
import ink.kuke.chat.feature.feed.ErrorBox
import ink.kuke.chat.feature.feed.LoadingBox
import ink.kuke.chat.feature.feed.compactCount
import ink.kuke.chat.feature.feed.graphViewModel
import ink.kuke.chat.feature.feed.tap
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import java.time.LocalDate
import java.time.LocalTime

private val Gold = Color(0xFFE9A112)
private val Silver = Color(0xFF8E98AB)
private val Bronze = Color(0xFFC0743A)

private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when {
        hour < 5 -> "夜深了"
        hour < 12 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
}

/** 数字的千分位（12,840）或 M/k 缩写（3.21M），设计稿的写法 */
private fun bigNumber(value: Long): String = when {
    value >= 1_000_000 -> "%.2fM".format(value / 1_000_000.0)
    else -> "%,d".format(value)
}

/**
 * 社区主页（设计稿 P16）：问候、社区概况（四个数字）、本周活跃柱图、人气群聊前三（加入 / 申请 / 进入）、大佬入住横滑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val vm = graphViewModel("home") { HomeViewModel(graph) }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    var requesting by remember { mutableStateOf<HomeGroup?>(null) }
    var hall by remember { mutableStateOf(false) }

    KcPage(title = "社区主页", onBack = { nav.back() }) {
        when {
            vm.loading && vm.stats == null -> LoadingBox()
            vm.error != null && vm.stats == null -> ErrorBox(vm.error.orEmpty(), { vm.load() })
            else -> PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.load(pull = true) }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item("hero") { Hero(me?.displayName ?: "") }
                    vm.stats?.let { stats -> item("stats") { StatsBlock(stats) } }
                    item("groups-head") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("人气群聊", style = KcTheme.type.title, color = colors.text, modifier = Modifier.weight(1f))
                            Text("换一批", style = KcTheme.type.secondary, color = colors.accent, modifier = Modifier.tap(scale = 0.94f) { vm.reshuffle() }.padding(8.dp))
                        }
                    }
                    if (vm.groups.isEmpty()) {
                        item("groups-empty") {
                            Text("暂时还没有可推荐的公开群聊", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
                        }
                    } else {
                        items(vm.groups.take(3), key = { "g-${it.id}" }) { group ->
                            val rank = vm.groups.indexOf(group)
                            GroupRow(
                                rank = rank,
                                group = group,
                                busy = vm.busy[group.id] == true,
                                error = vm.groupErrors[group.id],
                                onAction = {
                                    when {
                                        group.joined -> nav.go(ChatRoute(group.id))
                                        group.joinRequestStatus == "pending" -> {}
                                        group.needsRequest -> requesting = group
                                        else -> vm.join(group) { nav.go(ChatRoute(it)) }
                                    }
                                },
                            )
                        }
                    }
                    if (vm.creators.isNotEmpty()) {
                        item("creators-head") {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("大佬入住", style = KcTheme.type.title, color = colors.text, modifier = Modifier.weight(1f))
                                Text(
                                    if (vm.creatorTotal > 0) "查看全部 ${vm.creatorTotal}" else "查看全部",
                                    style = KcTheme.type.secondary, color = colors.accent,
                                    modifier = Modifier.tap(scale = 0.94f) { hall = true }.padding(8.dp),
                                )
                            }
                        }
                        item("creators-row") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(vm.creators, key = { it.id }) { creator -> CreatorChip(creator) { nav.go(UserRoute(creator.id)) } }
                            }
                        }
                    }
                }
            }
        }
    }

    requesting?.let { group ->
        JoinRequestDialog(
            group = group,
            busy = vm.busy[group.id] == true,
            error = vm.groupErrors[group.id],
            onSubmit = { answer -> vm.request(group, answer) { requesting = null } },
            onDismiss = { requesting = null },
        )
    }
    if (hall) {
        // 大佬入住的完整榜单：全屏盖在主页上，返回键回到主页
        Dialog(onDismissRequest = { hall = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize()) {
                CreatorsHallPage(graph, onBack = { hall = false }, onOpenUser = { hall = false; nav.go(UserRoute(it)) })
            }
        }
    }
}

@Composable
private fun Hero(name: String) {
    val colors = KcTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.ok))
            Text("KukeChat · 社区运行中", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(start = 6.dp))
        }
        Text("${greeting()}，$name 👋", style = KcTheme.type.display, color = colors.text, modifier = Modifier.padding(top = 8.dp))
        Text("这是你与同好们共同生活的社区，每一组数字都在此刻真实跳动。", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StatsBlock(stats: HomeStats) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("注册用户", bigNumber(stats.registeredUsers), "公开群聊 ${stats.publicGroups}", Modifier.weight(1f))
            StatCard("累计消息", bigNumber(stats.messages), "在册消息", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeekCard(stats.messagesLast7Days, Modifier.weight(1.4f))
            StatCard("群聊总数", bigNumber(stats.groups), "好友关系 ${compactCount(stats.friendships.toInt())}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, hint: String, modifier: Modifier) {
    val colors = KcTheme.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(14.dp)) {
        Text(label, style = KcTheme.type.caption, color = colors.muted)
        Text(value, style = KcTheme.type.number, color = colors.text, modifier = Modifier.padding(top = 4.dp))
        Text(hint, style = KcTheme.type.caption, color = colors.subtle, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * 本周消息活跃度。接口只给「近七天消息总数」，没有按天的拆分，
 * 所以七根柱只表示「周一到今天」的节奏示意（今天一根用强调色实底），不代表每天的真实数值。
 */
@Composable
private fun WeekCard(total: Long, modifier: Modifier) {
    val colors = KcTheme.colors
    val today = LocalDate.now().dayOfWeek.value - 1
    val shape = listOf(0.5f, 0.68f, 0.44f, 0.8f, 0.62f, 0.36f, 0.9f)
    val days = listOf("一", "二", "三", "四", "五", "六", "日")
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(14.dp)) {
        Text("本周消息活跃度", style = KcTheme.type.caption, color = colors.muted)
        Text("%,d".format(total), style = KcTheme.type.number, color = colors.text, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().height(54.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            shape.forEachIndexed { index, ratio ->
                Box(
                    Modifier.weight(1f).height((46 * ratio).dp).clip(RoundedCornerShape(3.dp))
                        .background(if (index == today) colors.fill else colors.accentSoft),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            days.forEachIndexed { index, label ->
                Text(
                    if (index == today) "今" else label,
                    fontSize = 10.sp,
                    color = if (index == today) colors.accent else colors.subtle,
                    fontWeight = if (index == today) FontWeight.W700 else FontWeight.W400,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GroupRow(rank: Int, group: HomeGroup, busy: Boolean, error: String?, onAction: () -> Unit) {
    val colors = KcTheme.colors
    val medal = listOf(Gold, Silver, Bronze).getOrElse(rank) { colors.muted }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${rank + 1}", color = medal, fontSize = 18.sp, fontWeight = FontWeight.W800, modifier = Modifier.width(26.dp))
            Avatar(group.avatarUrl, group.name, group.id, 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(group.name, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${"%,d".format(group.memberCount)} 成员 · 本周 ${compactCount(group.recentMessageCount)} 消息",
                    style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val pending = !group.joined && group.joinRequestStatus == "pending"
            val filled = !group.joined && !pending
            Text(
                if (busy) "处理中…" else group.actionLabel,
                style = KcTheme.type.caption,
                color = when {
                    filled -> colors.onFill
                    pending -> colors.subtle
                    else -> colors.accent
                },
                fontWeight = FontWeight.W600,
                modifier = Modifier.tap(enabled = !busy && !pending, scale = 0.94f, onClick = onAction)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (filled) colors.fill else colors.accentSoft.takeIf { !pending } ?: colors.panelMuted)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (error != null) Text(error, style = KcTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 6.dp, start = 26.dp))
    }
}

@Composable
private fun CreatorChip(creator: CcwCreator, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.width(84.dp).tap(scale = 0.95f, onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(creator.avatarUrl, creator.displayName, creator.id, 56.dp)
        Text(creator.displayName, style = KcTheme.type.caption, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("${compactCount(creator.followerCount)} 粉丝", fontSize = 10.5.sp, color = colors.subtle)
    }
}

@Composable
private fun JoinRequestDialog(group: HomeGroup, busy: Boolean, error: String?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    var answer by remember { mutableStateOf("") }
    val question = group.joinMode == "question"
    val ready = !busy && (!question || answer.isNotBlank())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (question) "回答入群问题" else "申请加入「${group.name}」") },
        text = {
            Column {
                if (question) Text(group.joinQuestion?.takeIf { it.isNotBlank() } ?: "请回答群主设置的问题", style = KcTheme.type.secondary, color = colors.muted)
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(12.dp)) {
                    if (answer.isEmpty()) Text(if (question) "你的回答" else "申请说明（选填）", style = KcTheme.type.body, color = colors.subtle)
                    BasicTextField(
                        value = answer,
                        onValueChange = { if (it.length <= 200) answer = it },
                        textStyle = KcTheme.type.body.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error != null) Text(error, style = KcTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { onSubmit(answer.trim()) }) { Text(if (busy) "提交中…" else "提交申请") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
