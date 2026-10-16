package ink.kuke.chat.feature.groupfiles

import ink.kuke.chat.core.data.slot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.chat.Chip
import ink.kuke.chat.feature.chat.KcButton
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.KcSwitch
import ink.kuke.chat.feature.chat.SheetForm
import ink.kuke.chat.feature.chat.SheetRow
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.fullTime
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.groupplus.FileQuota
import ink.kuke.chat.feature.groupplus.GroupFile
import ink.kuke.chat.feature.groupplus.GroupPlusApi
import ink.kuke.chat.feature.groupplus.formatBytes
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.GroupPlusRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant

private const val PAGE = 30

/**
 * 群文件（Pro 起解锁；Pro 只有 24 小时临时文件，Pro+ / MAX 有群空间，空间满了还能传临时文件）：配额条 · 文件列表（上传人、大小、剩余有效期 / 永久）· 上传（系统文件选择器，流式上传带进度）。
 * 点文件弹操作面板：打开 / 保存到下载 / 转永久（群主管理员）/ 删除（上传人或管理）。
 * 三种状态：locked 引导升级；active 正常；grace 宽限期可读可下载可删、不能上传。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupFilesScreen(graph: AppGraph, conversationId: Long) {
    val nav = LocalAppNav.current
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { GroupPlusApi(graph) }
    val conversation = graph.conversations.find(conversationId)
    val staff = conversation?.myRole == "owner" || conversation?.myRole == "admin"
    val me = graph.session.session.value?.user

    // 上次看到的第一页（内存）：先摆出来，再静默刷新；翻页照旧
    val cache = remember(graph, conversationId) { graph.screenCache.slot<GroupFilesSnapshot>("groupfiles.$conversationId") }
    val cached = remember(cache) { cache.get() }
    var files by remember { mutableStateOf(cached?.files ?: emptyList()) }
    var total by remember { mutableStateOf(cached?.total ?: 0) }
    var quota by remember { mutableStateOf(cached?.quota) }
    var loading by remember { mutableStateOf(cached == null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<GroupFile?>(null) }
    var confirmDelete by remember { mutableStateOf<GroupFile?>(null) }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var progress by remember { mutableStateOf<Pair<PickedFile, Float>?>(null) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    var needUpgrade by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    /** silent：已有内容时不显示下拉转圈（进页面、实时事件触发的刷新） */
    suspend fun load(reset: Boolean, silent: Boolean = false) {
        if (reset) {
            if (files.isEmpty()) loading = true else if (!silent) refreshing = true
        } else {
            loadingMore = true
        }
        runCatching { api.files(conversationId, "", PAGE, if (reset) 0 else files.size) }
            .onSuccess { page ->
                files = if (reset) page.items else files + page.items.filter { n -> files.none { it.id == n.id } }
                total = page.total
                quota = page.quota
                error = null
                if (reset) cache.put(GroupFilesSnapshot(files.take(PAGE), total, quota))
            }
            .onFailure { if (reset && files.isEmpty()) error = it.userMessage() else toast(context, it.userMessage()) }
        loading = false
        refreshing = false
        loadingMore = false
    }

    LaunchedEffect(conversationId, tick) { load(true, silent = true) }

    // 实时：别人传了 / 删了 / 转永久；档位变化后配额状态也会变
    LaunchedEffect(conversationId) {
        var pending: Job? = null
        graph.realtime.events.collect { event ->
            if (event.type == "group.file.updated" || event.type == "group.plus.updated") {
                val id = (event.data["conversation_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                if (id == conversationId) {
                    pending?.cancel()
                    pending = scope.launch {
                        delay(300)
                        load(true, silent = true)
                    }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val info = describePicked(context, uri)
            val max = quota?.maxFileBytes ?: 104857600L
            when {
                info.size == 0L -> toast(context, "不能上传空文件")
                info.size > max -> toast(context, "文件超过 ${formatBytes(max)}，不能上传")
                else -> picked = info
            }
        }
    }

    val state = quota?.state ?: "locked"
    val canUpload = state == "active"

    KcPage(
        title = "群文件",
        onBack = { nav.back() },
        actions = {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(enabled = progress == null) {
                    when (state) {
                        "active" -> picker.launch(arrayOf("*/*"))
                        "grace" -> toast(context, "群文件处于宽限期，只能下载和删除，续费后恢复上传")
                        else -> needUpgrade = "升级到 Pro 才能使用群文件"
                    }
                },
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.upload, tint = if (canUpload && progress == null) colors.text else colors.subtle, size = 22.dp, contentDescription = "上传文件") }
        },
    ) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { load(true) } }, modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            // 翻到底自动加载下一页
            LaunchedEffect(listState, files.size, total) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                    if (!loading && !loadingMore && files.size < total && last >= files.size - 3) load(false)
                }
            }
            when {
                loading && files.isEmpty() -> StateText("正在加载…", Modifier.fillMaxWidth().padding(top = 80.dp))
                error != null && files.isEmpty() -> StateText(error!!, Modifier.fillMaxWidth().padding(top = 80.dp), error = true, onRetry = { tick++ })
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "quota") {
                        QuotaCard(
                            quota = quota ?: FileQuota(),
                            staff = staff,
                            onTtl = { hours ->
                                scope.launch {
                                    runCatching { api.setDefaultTtl(conversationId, hours) }
                                        .onSuccess {
                                            toast(context, "之后上传的文件默认保留 ${ttlLabel(hours)}")
                                            graph.conversations.refreshSoon(0)
                                            load(true)
                                        }
                                        .onFailure { toast(context, it.userMessage()) }
                                }
                            },
                            onUpgrade = { nav.go(GroupPlusRoute(conversationId, reason = "files")) },
                        )
                    }
                    progress?.let { (file, ratio) ->
                        item(key = "uploading") {
                            UploadProgressRow(file, ratio) {
                                uploadJob?.cancel()
                                progress = null
                                toast(context, "已取消上传")
                            }
                        }
                    }
                    if (files.isEmpty()) {
                        item(key = "empty") {
                            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                KcIcon(KcIcons.folder, tint = colors.subtle, size = 36.dp)
                                Text(
                                    if (state == "locked") "群文件还没有解锁" else "还没有文件",
                                    style = type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp),
                                )
                                Text(
                                    if (state == "locked") "升级到 Pro 后，所有成员都可以在这里分享文件。" else "点右上角的上传按钮，把文件分享给群成员。",
                                    style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
                                )
                            }
                        }
                    } else {
                        items(files, key = { it.id }) { file ->
                            FileRow(file, onClick = { acting = file })
                        }
                        if (loadingMore) item(key = "more") { Text("加载中…", style = type.caption, color = colors.subtle, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
                    }
                }
            }
        }
    }

    // 选好文件后：确认面板（文件名、大小、是否在群里发消息、是否设为永久）
    picked?.let { file ->
        UploadSheet(
            file = file,
            staff = staff,
            defaultTtlHours = quota?.defaultTtlHours ?: 24,
            onDismiss = { picked = null },
            onConfirm = { announce, permanent ->
                picked = null
                progress = file to 0f
                uploadJob = scope.launch {
                    runCatching {
                        uploadGroupFile(graph, context, conversationId, file, announce, permanent) { sent ->
                            progress = file to (if (file.size > 0) (sent.toFloat() / file.size).coerceIn(0f, 1f) else 0f)
                        }
                    }.onSuccess {
                        toast(context, "上传成功")
                        load(true)
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        val code = (error as? ink.kuke.chat.core.net.ApiException)?.code
                        toast(context, error.userMessage())
                        if (code == "files_locked" || code == "quota_exceeded") needUpgrade = error.userMessage()
                        load(true)
                    }
                    progress = null
                }
            },
        )
    }

    acting?.let { file ->
        FileActionSheet(
            graph = graph,
            conversationId = conversationId,
            file = file,
            canManage = staff,
            isUploader = file.uploaderId != null && file.uploaderId == me?.id,
            onDismiss = { acting = null },
            onChanged = {
                acting = null
                scope.launch { load(true) }
            },
            onDelete = {
                acting = null
                confirmDelete = file
            },
        )
    }
    confirmDelete?.let { file ->
        KcConfirm(
            title = "删除文件",
            text = "「${file.name}」会从群文件里删除并释放空间，已经发到群里的文件消息会显示文件已不存在。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    runCatching { api.deleteFile(conversationId, file.id) }
                        .onSuccess {
                            files = files.filter { it.id != file.id }
                            total = (total - 1).coerceAtLeast(0)
                            toast(context, "已删除")
                            load(true)
                        }
                        .onFailure { toast(context, it.userMessage()) }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
    needUpgrade?.let { text ->
        KcConfirm(
            title = "需要升级群",
            text = text,
            confirmLabel = "去升级",
            onConfirm = {
                needUpgrade = null
                nav.go(GroupPlusRoute(conversationId, reason = "files"))
            },
            onDismiss = { needUpgrade = null },
        )
    }
}

// ------------------------------------------------------------------ 配额卡

@Composable
private fun QuotaCard(quota: FileQuota, staff: Boolean, onTtl: (Int) -> Unit, onUpgrade: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (quota.state) {
            "locked" -> {
                Text("升级到 Pro 解锁群文件", style = type.title, color = colors.text)
                Text(
                    "Pro 可以传 24 小时临时文件；Pro+ 有 200MB、MAX 有 1GB 群空间，空间用完后也能继续传临时文件。所有成员都能上传。",
                    style = type.secondary, color = colors.muted,
                )
                KcButton("了解群升级", onUpgrade, Modifier.fillMaxWidth())
            }
            else -> {
                val used = quota.usedBytes
                val limit = quota.quotaBytes ?: 0L
                val ratio = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
                val temp = quota.tempMaxBytes
                if (limit > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("空间用量", style = type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                        Text("${formatBytes(used)} / ${formatBytes(limit)}", style = type.secondary, color = if (ratio >= 0.9f) colors.warn else colors.muted)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.panelMuted)) {
                        Box(Modifier.fillMaxWidth(ratio).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (ratio >= 0.9f) colors.warn else colors.fill))
                    }
                    // 空间够时不提单个大小；快满了才说「新文件会变成临时文件」
                    val fullHint = if (temp != null && temp > 0 && quota.freeBytes < temp) " · 空间不够时存为 24 小时临时文件（单个 ${formatBytes(temp)} 内）" else ""
                    Text("${quota.fileCount} 个文件$fullHint", style = type.caption, color = colors.muted)
                } else {
                    // Pro：没有群空间，只能传临时文件
                    Text("临时文件", style = type.rowTitle, color = colors.text)
                    Text("${quota.fileCount} 个文件 · 文件保存 24 小时${temp?.let { " · 单个最大 ${formatBytes(it)}" } ?: ""}", style = type.caption, color = colors.muted)
                }
                if (quota.state == "grace") {
                    Text(
                        "群档位已到期，群文件处于宽限期：可以下载和删除，不能上传" +
                            (quota.graceUntil?.let { "。${fullTime(it)} 之后所有文件会被清理，续费可恢复" } ?: "") + "。",
                        style = type.caption, color = colors.warn,
                    )
                    KcButton("去续费", onUpgrade, Modifier.fillMaxWidth(), primary = false)
                } else if (limit > 0) {
                    Text("新上传的文件默认保留 ${ttlLabel(quota.defaultTtlHours)}，管理员可以把文件转为永久。", style = type.caption, color = colors.muted)
                    if (staff) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(24, 168, 720).forEach { hours -> Chip(ttlLabel(hours), quota.defaultTtlHours == hours) { if (quota.defaultTtlHours != hours) onTtl(hours) } }
                        }
                    }
                }
            }
        }
    }
}

internal fun ttlLabel(hours: Int): String = when {
    hours % 24 == 0 -> "${hours / 24} 天"
    else -> "$hours 小时"
}

// ------------------------------------------------------------------ 文件行

internal fun expiryText(file: GroupFile, now: Instant = Instant.now()): String {
    if (file.permanent || file.expiresAt == null) return "永久"
    if (file.expired) return "已过期"
    val until = parseInstant(file.expiresAt) ?: return ""
    val left = Duration.between(now, until)
    return when {
        left.isNegative || left.isZero -> "已过期"
        left.toDays() >= 1 -> "剩 ${left.toDays()} 天"
        left.toHours() >= 1 -> "剩 ${left.toHours()} 小时"
        else -> "剩 ${maxOf(left.toMinutes(), 1)} 分钟"
    }
}

internal fun fileIcon(name: String, contentType: String?) = when {
    contentType?.startsWith("image/") == true -> KcIcons.image
    contentType?.startsWith("video/") == true -> KcIcons.video
    contentType?.startsWith("audio/") == true -> KcIcons.music
    name.substringAfterLast('.', "").lowercase() in setOf("zip", "rar", "7z", "gz", "tar") -> KcIcons.folder
    else -> KcIcons.paperclip
}

@Composable
private fun FileRow(file: GroupFile, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val gone = file.expired
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            KcIcon(fileIcon(file.name, file.contentType), tint = if (gone) colors.subtle else colors.accent, size = 22.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, style = type.rowTitle, color = if (gone) colors.subtle else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                file.uploader?.let { Avatar(it.avatarUrl, it.displayName, file.uploaderId ?: it.id, 16.dp) }
                Text(
                    (file.uploader?.displayName ?: "已注销用户") + " · ${formatBytes(file.size)}",
                    style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (file.uploader != null) 5.dp else 0.dp),
                )
            }
        }
        val label = expiryText(file)
        Text(
            label,
            style = type.caption,
            fontWeight = FontWeight.W600,
            color = when {
                gone -> colors.subtle
                file.permanent -> colors.ok
                label.contains("分钟") || label.contains("小时") -> colors.warn
                else -> colors.muted
            },
        )
    }
}

@Composable
private fun UploadProgressRow(file: PickedFile, ratio: Float, onCancel: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("正在上传 ${file.name}", style = type.secondary, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("取消", style = type.secondary, color = colors.accent, modifier = Modifier.clickable(onClick = onCancel).padding(start = 12.dp))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.panelMuted)) {
            Box(Modifier.fillMaxWidth(ratio.coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.fill))
        }
        Text("${(ratio * 100).toInt()}% · ${formatBytes(file.size)}", style = type.caption, color = colors.muted)
    }
}

// ------------------------------------------------------------------ 面板

@Composable
private fun UploadSheet(file: PickedFile, staff: Boolean, defaultTtlHours: Int, onDismiss: () -> Unit, onConfirm: (announce: Boolean, permanent: Boolean) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var announce by remember { mutableStateOf(true) }
    var permanent by remember { mutableStateOf(false) }
    KcSheet(onDismiss = onDismiss, title = "上传群文件") {
        SheetForm {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                    KcIcon(fileIcon(file.name, file.mime), tint = colors.accent, size = 24.dp)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(file.name, style = type.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (file.size >= 0) formatBytes(file.size) else "大小未知", style = type.caption, color = colors.muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在群里发一条文件消息", style = type.rowTitle, color = colors.text)
                    Text("关闭后只放进群文件，不通知群成员", style = type.caption, color = colors.muted)
                }
                KcSwitch(announce, { announce = it })
            }
            if (staff) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("设为永久文件", style = type.rowTitle, color = colors.text)
                        Text("不开启则保留 ${ttlLabel(defaultTtlHours)}", style = type.caption, color = colors.muted)
                    }
                    KcSwitch(permanent, { permanent = it })
                }
            } else {
                Text("文件默认保留 ${ttlLabel(defaultTtlHours)}，到期自动删除。", style = type.caption, color = colors.muted)
            }
            KcButton("上传", { onConfirm(announce, permanent) }, Modifier.fillMaxWidth())
        }
    }
}

/** 点文件弹出的操作面板：打开 / 保存到下载 / 转永久·转限时 / 删除 */
@Composable
private fun FileActionSheet(
    graph: AppGraph,
    conversationId: Long,
    file: GroupFile,
    canManage: Boolean,
    isUploader: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { GroupPlusApi(graph) }
    var working by remember { mutableStateOf<Float?>(null) }
    val path = file.downloadPath ?: "/api/v1/conversations/$conversationId/files/${file.id}/download"

    fun download(then: (File) -> Unit) {
        if (working != null) return
        working = 0f
        scope.launch {
            runCatching { downloadGroupFile(graph, context, path, file.id, file.name) { working = it } }
                .onSuccess { then(it) }
                .onFailure { toast(context, it.userMessage()) }
            working = null
        }
    }

    KcSheet(onDismiss = { if (working == null) onDismiss() }, title = file.name) {
        Text(
            "${file.uploader?.displayName ?: "已注销用户"} · ${formatBytes(file.size)} · ${fullTime(file.createdAt)}",
            style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
        )
        Text(
            if (file.permanent) "永久保存" else if (file.expired) "已过期" else "${expiryText(file)}（${fullTime(file.expiresAt)} 到期）",
            style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        working?.let { ratio ->
            Text(if (ratio >= 0f) "正在下载 ${(ratio * 100).toInt()}%" else "正在下载…", style = type.secondary, color = colors.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (!file.expired) {
            SheetRow("打开", { download { f -> if (!openDownloaded(context, f, file.name)) toast(context, "没有能打开这个文件的应用，试试「保存到下载」") ; onDismiss() } }, icon = KcIcons.external)
            SheetRow("保存到下载", {
                download { f ->
                    scope.launch {
                        val ok = saveToDownloads(context, f, file.name)
                        toast(context, if (ok) "已保存到 下载/KukeChat" else "当前系统版本不支持直接保存，请用「打开」")
                        onDismiss()
                    }
                }
            }, icon = KcIcons.download)
        }
        if (canManage) {
            SheetRow(
                if (file.permanent) "转回限时保留" else "转为永久文件",
                {
                    scope.launch {
                        runCatching { api.setPermanent(conversationId, file.id, !file.permanent) }
                            .onSuccess {
                                toast(context, if (file.permanent) "已转为限时文件" else "已转为永久文件")
                                onChanged()
                            }
                            .onFailure { toast(context, it.userMessage()) }
                    }
                },
                icon = KcIcons.clock,
            )
        }
        if (canManage || isUploader) SheetRow("删除", onDelete, icon = KcIcons.trash, danger = true)
        Box(Modifier.height(8.dp))
    }
}

/** 聊天里文件消息卡片点开用：不需要整个列表，直接给下载 / 保存操作 */
@Composable
fun FileMessageSheet(graph: AppGraph, conversationId: Long, fileId: Long, name: String, size: Long, expired: Boolean, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<Float?>(null) }
    val path = "/api/v1/conversations/$conversationId/files/$fileId/download"

    fun download(then: (File) -> Unit) {
        if (working != null) return
        working = 0f
        scope.launch {
            runCatching { downloadGroupFile(graph, context, path, fileId, name) { working = it } }
                .onSuccess { then(it) }
                .onFailure { toast(context, it.userMessage()) }
            working = null
        }
    }

    KcSheet(onDismiss = { if (working == null) onDismiss() }, title = name) {
        Text(
            if (expired) "文件已过期" else formatBytes(size),
            style = type.caption, color = if (expired) colors.warn else colors.muted, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        working?.let { ratio ->
            Text(if (ratio >= 0f) "正在下载 ${(ratio * 100).toInt()}%" else "正在下载…", style = type.secondary, color = colors.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (!expired) {
            SheetRow("打开", { download { f -> if (!openDownloaded(context, f, name)) toast(context, "没有能打开这个文件的应用，试试「保存到下载」"); onDismiss() } }, icon = KcIcons.external)
            SheetRow("保存到下载", {
                download { f ->
                    scope.launch {
                        val ok = saveToDownloads(context, f, name)
                        toast(context, if (ok) "已保存到 下载/KukeChat" else "当前系统版本不支持直接保存，请用「打开」")
                        onDismiss()
                    }
                }
            }, icon = KcIcons.download)
        }
        Box(Modifier.height(8.dp))
    }
}

/** 群文件页第一页的快照（只在内存，退出登录清掉） */
private class GroupFilesSnapshot(val files: List<GroupFile>, val total: Int, val quota: FileQuota?)
