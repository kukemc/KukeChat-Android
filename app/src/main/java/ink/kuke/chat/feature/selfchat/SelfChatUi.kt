package ink.kuke.chat.feature.selfchat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.appearance.AvatarShape
import ink.kuke.chat.feature.appearance.LocalAppearance
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.KcSwitch
import ink.kuke.chat.feature.chat.LocalMessageLongPress
import ink.kuke.chat.feature.chat.SheetRow
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.groupfiles.FileDownload
import ink.kuke.chat.feature.groupfiles.FileDownloadStatus
import ink.kuke.chat.feature.groupfiles.FileDownloads
import ink.kuke.chat.feature.groupfiles.KcProgressBar
import ink.kuke.chat.feature.groupfiles.fileIcon
import ink.kuke.chat.feature.groupfiles.ratio
import ink.kuke.chat.feature.groupplus.formatBytes
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * 「我的电脑」的头像：浅灰底上一个墨色显示器线稿（本地画，不用 avatar_url，见 docs/self-chat.md 1.3）。
 * 形状跟着外观设置走（圆 / 圆角方），和普通头像排在一起不跳。
 */
@Composable
fun DeviceAvatar(size: Dp, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val rounded = LocalAppearance.current.avatarShape == AvatarShape.Rounded
    val shape = if (rounded) RoundedCornerShape((size.value * 0.28f).dp) else CircleShape
    Box(
        modifier.size(size).clip(shape).background(colors.panelMuted).border(1.dp, colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        KcIcon(KcIcons.monitor, tint = colors.text, size = size * 0.46f, contentDescription = SELF_PEER)
    }
}

private const val SELF_PEER = ink.kuke.chat.core.data.SelfDevice.PEER_LABEL

/** 长文件名：中间省略，头尾（扩展名）都留着 —— 「一个很长的安装…v2.0.9.apk」 */
fun middleEllipsis(name: String, max: Int = 26): String {
    if (name.length <= max) return name
    val tail = (max * 0.42f).toInt().coerceAtLeast(6)
    val head = (max - tail - 1).coerceAtLeast(4)
    return name.take(head) + "…" + name.takeLast(tail)
}

/** 「23 小时后过期」「12 分钟后过期」「已过期」 */
fun expiryLabel(meta: TransferMeta, now: Instant = Instant.now()): String {
    if (meta.isExpired(now)) return "已过期"
    val until = meta.expiresInstant ?: return "24 小时内有效"
    val left = Duration.between(now, until)
    return if (left.toHours() >= 1) "${left.toHours()} 小时后过期" else "${maxOf(left.toMinutes(), 1)} 分钟后过期"
}

/** 每 30 秒重算一次的「现在」：卡片上的倒计时、过期置灰不用等服务端（过期时服务端不推事件） */
@Composable
private fun rememberTickingNow(): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    return now
}

/**
 * 临时文件消息的卡片：类型图标 · 文件名（中间省略）· 大小 · 剩余有效期；过期了整张置灰写「已过期」。
 * 在下载时底部画进度条。点开交给聊天页（下载面板）；长按出菜单（没有收藏 / 转发）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TransferCard(meta: TransferMeta, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val now = rememberTickingNow()
    val expired = meta.isExpired(now)
    val key = SelfTransfers.downloadKey(meta.id)
    LaunchedEffect(key) { FileDownloads.refresh(context, key) }
    val download = FileDownloads.states[key]
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .widthIn(min = 228.dp, max = 272.dp)
            .shadow(if (expired) 0.dp else 1.dp, shape)
            .background(colors.panel, shape)
            .border(1.dp, colors.border, shape)
            .clip(shape)
            .combinedClickable(onLongClick = LocalMessageLongPress.current, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (expired) colors.panelMuted else colors.text.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                KcIcon(fileIcon(meta.name, meta.contentType), tint = if (expired) colors.subtle else colors.text, size = 22.dp)
            }
            Column(Modifier.padding(start = 11.dp).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    middleEllipsis(meta.name), style = type.rowTitle.copy(fontWeight = FontWeight.W600),
                    color = if (expired) colors.subtle else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    download is FileDownload.Done -> "已下载"
                    expired -> "已过期"
                    download is FileDownload.Running -> download.ratio(meta.size)?.let { "下载中 ${(it * 100).toInt()}%" } ?: "下载中"
                    else -> expiryLabel(meta, now)
                }
                Text("${formatBytes(meta.size)} · $status", style = type.caption, color = if (expired && download !is FileDownload.Done) colors.subtle else colors.muted, maxLines = 1)
            }
        }
        if (download is FileDownload.Running && !expired) {
            KcProgressBar(download.ratio(meta.size), Modifier.padding(top = 10.dp), height = 3.dp)
        }
    }
}

/** 点开临时文件：下载状态 + 「打开」「保存到下载」。已下载的过期了也能打开本地那份 */
@Composable
fun TransferSheet(graph: AppGraph, conversationId: Long, meta: TransferMeta, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val key = SelfTransfers.downloadKey(meta.id)
    LaunchedEffect(key) { FileDownloads.refresh(context, key) }
    val state = FileDownloads.states[key]
    val now = rememberTickingNow()
    val expired = meta.isExpired(now)
    KcSheet(onDismiss = onDismiss, title = middleEllipsis(meta.name, 30)) {
        Text(
            "${formatBytes(meta.size)} · ${expiryLabel(meta, now)}",
            style = type.caption, color = if (expired) colors.subtle else colors.muted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp),
        )
        FileDownloadStatus(key, meta.size, Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 10.dp))
        val done = state as? FileDownload.Done
        if (expired && done == null) {
            Text(
                "临时文件只保存 24 小时，这个文件已经过期，不能再下载了。",
                style = type.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        } else {
            SheetRow(
                "打开",
                {
                    if (done != null) {
                        FileDownloads.open(context, done, meta.name)
                        onDismiss()
                    } else {
                        SelfTransfers.download(graph, context, conversationId, meta, openAfter = true)
                    }
                },
                icon = KcIcons.external,
                detail = if (state is FileDownload.Running) "下完自动打开" else null,
            )
            SheetRow(
                "保存到下载",
                { SelfTransfers.download(graph, context, conversationId, meta, openAfter = false) },
                icon = KcIcons.download,
                detail = when (state) {
                    is FileDownload.Done -> "已保存"
                    is FileDownload.Running -> "下载中"
                    else -> null
                },
            )
        }
        Box(Modifier.height(8.dp))
    }
}

/**
 * 「我的电脑」的会话设置（替代群资料 / 对方资料）：说明 · 置顶 · 免打扰 · 查找聊天记录 · 临时文件（剩余有效期、额度）· 清空聊天记录。
 * 没有成员、举报、退出 —— 这个会话只有自己，不能退出也不能删除（docs/self-chat.md 1.3、第 4 节）。
 */
@Composable
fun SelfInfoPage(
    graph: AppGraph,
    conversationId: Long,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onOpenTransfer: (TransferMeta) -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    var list by remember { mutableStateOf(SelfTransfers.lastList) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { SelfTransfers.list(graph, conversationId) }.onSuccess { list = it }.onFailure { error = it.userMessage() }
    }
    fun patch(body: kotlinx.serialization.json.JsonObject) {
        scope.launch {
            runCatching { graph.api.patch("/conversations/$conversationId/members/me/settings", body) }
                .onSuccess { graph.conversations.refresh() }
                .onFailure { toast(context, it.userMessage()) }
        }
    }
    KcPage(title = SELF_PEER, onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            // 头部：设备头像 + 一句说明
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DeviceAvatar(64.dp)
                Text(SELF_PEER, style = type.title, color = colors.text, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "在自己的手机和电脑之间传消息和文件。\n只有你自己看得到；文件 24 小时后过期，过期后不能下载。",
                    style = type.secondary, color = colors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp),
                )
            }
            Group {
                ToggleRow("置顶", conversation?.pinned == true) { on -> patch(buildJsonObject { put("pinned", on) }) }
                Divider()
                ToggleRow("免打扰", conversation?.quiet == true) { on ->
                    patch(buildJsonObject {
                        put("message_setting", if (on) "silent" else "notify")
                        put("do_not_disturb", on)
                    })
                }
            }
            Group {
                SheetRow("查找聊天记录", onSearch, icon = KcIcons.search)
            }
            SectionTitle("临时文件")
            Group {
                val current = list
                when {
                    current == null && error != null -> Text(
                        error!!, style = type.secondary, color = colors.danger,
                        modifier = Modifier.fillMaxWidth().clickable { attempt++ }.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    current == null -> Text("加载中…", style = type.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                    else -> {
                        QuotaRow(current)
                        if (current.items.isEmpty()) {
                            Divider()
                            Text("还没有有效的文件。在聊天里点「+」→「文件」发一个试试。", style = type.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                        }
                        current.items.forEach { item ->
                            Divider()
                            TransferListRow(item) { onOpenTransfer(item.meta()) }
                        }
                    }
                }
            }
            Group {
                SheetRow("清空聊天记录", onClear, icon = KcIcons.trash, danger = true)
            }
            Text(
                "清空聊天记录只是不再显示这些记录，已发出的文件照常保留到过期；撤回文件消息会立刻删除文件。",
                style = type.caption, color = colors.subtle, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val colors = KcTheme.colors
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.panel),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = KcTheme.type.caption.copy(fontWeight = FontWeight.W600), color = KcTheme.colors.subtle, modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(1.dp).background(KcTheme.colors.border))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = KcTheme.type.rowTitle, color = KcTheme.colors.text, modifier = Modifier.weight(1f))
        KcSwitch(checked, onChange)
    }
}

/** 额度：已用 / 总量的细条 + 单个文件上限 */
@Composable
private fun QuotaRow(list: TransferList) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已用 ${formatBytes(list.usedBytes)}", style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            if (list.quotaBytes > 0) Text("共 ${formatBytes(list.quotaBytes)}", style = type.caption, color = colors.muted)
        }
        if (list.quotaBytes > 0) {
            KcProgressBar((list.usedBytes.toFloat() / list.quotaBytes).coerceIn(0f, 1f), Modifier.padding(top = 10.dp), height = 4.dp)
        }
        Text("单个文件最大 ${formatBytes(list.maxFileBytes)} · ${list.ttlHours} 小时后自动过期", style = type.caption, color = colors.subtle, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TransferListRow(item: TransferItem, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val meta = item.meta()
    val now = rememberTickingNow()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(colors.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            KcIcon(fileIcon(item.name, item.contentType), tint = colors.text, size = 19.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(middleEllipsis(item.name, 30), style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val from = when (item.device) {
                "android", "ios" -> "手机"
                null -> null
                else -> "电脑"
            }
            Text(
                listOfNotNull(formatBytes(item.size), from?.let { "来自$it" }, expiryLabel(meta, now)).joinToString(" · "),
                style = type.caption, color = colors.muted, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
    }
}
