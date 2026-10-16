package ink.kuke.chat.feature.groupfiles

import ink.kuke.chat.core.data.slot

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
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
import ink.kuke.chat.feature.groupplus.GroupFileFolder
import ink.kuke.chat.feature.groupplus.PlusTiers
import ink.kuke.chat.feature.groupplus.apiCode
import ink.kuke.chat.feature.groupplus.detailLong
import ink.kuke.chat.feature.groupplus.detailString
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
 * 点文件弹操作面板：打开 / 保存到下载 / 转永久（群主管理员）/ 移动到…（文件夹）/ 删除（上传人或管理）。
 * 三种状态：locked 引导升级；active 正常；grace 宽限期可读可下载可删、不能上传。
 * 文件夹（Pro+ / MAX，一层，docs/group-plus.md 5.5）：根目录最上面列文件夹，点进去看里面的文件、在里面上传就直接进这个文件夹；
 * 权限只看列表返回的 can_manage_folders（新建、重命名、移入）/ can_tidy_folders（删除、移回根目录）。旧服务端没有这些字段时界面和原来一样。
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

    // 上次看到的根目录第一页（内存）：先摆出来，再静默刷新；翻页照旧
    val cache = remember(graph, conversationId) { graph.screenCache.slot<GroupFilesSnapshot>("groupfiles.$conversationId") }
    val cached = remember(cache) { cache.get() }
    var files by remember { mutableStateOf(cached?.files ?: emptyList()) }
    var total by remember { mutableStateOf(cached?.total ?: 0) }
    var quota by remember { mutableStateOf(cached?.quota) }
    var folders by remember { mutableStateOf(cached?.folders ?: emptyList()) }
    var canManageFolders by remember { mutableStateOf(cached?.canManageFolders ?: false) }
    var canTidyFolders by remember { mutableStateOf(cached?.canTidyFolders ?: false) }
    var folderLimit by remember { mutableStateOf(cached?.folderLimit ?: 50) }
    var loading by remember { mutableStateOf(cached == null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<GroupFile?>(null) }
    var confirmDelete by remember { mutableStateOf<GroupFile?>(null) }
    var moving by remember { mutableStateOf<GroupFile?>(null) }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var progress by remember { mutableStateOf<Pair<PickedFile, Float>?>(null) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    var needUpgrade by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    // 现在看的位置：folder 为 null 是根目录；搜索时跨所有文件夹
    var folder by remember { mutableStateOf<GroupFileFolder?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchText = if (searchOpen) query.trim() else ""
    val searching = searchText.isNotEmpty()
    val folderId = folder?.id

    // 现在列表里摆的是哪个位置的内容（文件夹 id, 搜索词）
    var loadedKey by remember { mutableStateOf<Pair<Long?, String>?>(if (cached != null) null to "" else null) }
    var addMenu by remember { mutableStateOf(false) }
    var folderEditor by remember { mutableStateOf<FolderEditor?>(null) }
    var folderActing by remember { mutableStateOf<GroupFileFolder?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<GroupFileFolder?>(null) }

    /** 切换位置（进 / 出文件夹、开始 / 结束搜索）：清掉旧列表，显示「正在加载」 */
    fun resetList() {
        files = emptyList()
        total = 0
        error = null
        loading = true
    }

    fun openFolder(target: GroupFileFolder) {
        if (folder?.id == target.id) return
        folder = target
        resetList()
    }

    fun goRoot() {
        if (folder == null) return
        folder = null
        resetList()
    }

    /** silent：已有内容时不显示下拉转圈（进页面、实时事件触发的刷新） */
    suspend fun load(reset: Boolean, silent: Boolean = false) {
        // 直接读状态：实时事件那个协程里拿到的是第一次组合时的 load，不能用组合时算好的值
        val viewFolder = folder?.id
        val viewQuery = if (searchOpen) query.trim() else ""
        if (reset) {
            if (files.isEmpty()) loading = true else if (!silent) refreshing = true
        } else {
            loadingMore = true
        }
        runCatching { api.files(conversationId, viewQuery, PAGE, if (reset) 0 else files.size, folderId = viewFolder) }
            .onSuccess { page ->
                // 等结果的时候换了位置：这一页作废
                if (viewFolder != folder?.id || viewQuery != (if (searchOpen) query.trim() else "")) return@onSuccess
                loadedKey = viewFolder to viewQuery
                files = if (reset) page.items else files + page.items.filter { n -> files.none { it.id == n.id } }
                total = page.total
                quota = page.quota
                folders = page.folders
                canManageFolders = page.canManageFolders
                canTidyFolders = page.canTidyFolders
                folderLimit = page.folderLimit
                // 文件夹被别人改了名：顶栏跟着变
                if (viewFolder != null) page.folder?.let { folder = it }
                error = null
                if (reset && viewFolder == null && viewQuery.isEmpty()) {
                    cache.put(GroupFilesSnapshot(files.take(PAGE), total, quota, folders, canManageFolders, canTidyFolders, folderLimit))
                }
            }
            .onFailure {
                if (viewFolder != folder?.id || viewQuery != (if (searchOpen) query.trim() else "")) return@onFailure
                if (viewFolder != null && it.apiCode == "folder_not_found") {
                    toast(context, "这个文件夹已经被删除了，里面的文件在根目录")
                    goRoot()
                } else if (reset && files.isEmpty()) {
                    error = folderErrorMessage(it)
                } else {
                    toast(context, folderErrorMessage(it))
                }
            }
        loading = false
        refreshing = false
        loadingMore = false
    }

    // 位置或搜索词变了就重拉；搜索词输入时等一会儿再发
    LaunchedEffect(conversationId, tick, folderId, searchText) {
        // 换了搜索词 / 关了搜索：旧结果先撤掉，免得把搜索结果当成根目录
        if (loadedKey != (folderId to searchText)) resetList()
        if (searchText.isNotEmpty()) delay(300)
        load(true, silent = true)
    }

    // 实时：别人传了 / 删了 / 转永久 / 移动了；文件夹新建 / 改名 / 删除；档位变化后配额状态也会变
    LaunchedEffect(conversationId) {
        var pending: Job? = null
        graph.realtime.events.collect { event ->
            if (event.type == "group.file.updated" || event.type == "group.plus.updated" || event.type == "group.file.folders.changed") {
                val id = (event.data["conversation_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                if (id == conversationId) {
                    if (event.type == "group.file.folders.changed") {
                        val action = (event.data["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        val target = (event.data["folder_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                        // 正在看的文件夹被删了：回根目录
                        if (action == "deleted" && target != null && target == folder?.id) {
                            toast(context, "「${folder?.name}」已被删除，里面的文件已移回根目录")
                            goRoot()
                        }
                    }
                    pending?.cancel()
                    pending = scope.launch {
                        delay(300)
                        load(true, silent = true)
                    }
                }
            }
        }
    }

    // 返回键：先关搜索，再从文件夹回根目录
    BackHandler(enabled = searchOpen || folder != null) {
        if (searchOpen) {
            searchOpen = false
            query = ""
        } else {
            goRoot()
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

    fun startUpload() {
        when (state) {
            "active" -> {
                val current = folder
                if (current != null && quota?.foldersEnabled == false) {
                    toast(context, "群档位已不是 Pro+ / MAX，不能再往文件夹里上传，可以回到根目录上传")
                } else {
                    picker.launch(arrayOf("*/*"))
                }
            }
            "grace" -> toast(context, "群文件处于宽限期，只能下载和删除，续费后恢复上传")
            else -> needUpgrade = "升级到 Pro 才能使用群文件"
        }
    }

    fun startCreateFolder() {
        if (folders.size >= folderLimit) toast(context, "每个群最多 $folderLimit 个文件夹，先删掉不用的再新建")
        else folderEditor = FolderEditor(null)
    }

    // 根目录、不在搜索时，群管才看得到「新建文件夹」：右上角的加号弹出「上传文件 / 新建文件夹」
    val showAddMenu = canManageFolders && folder == null && !searchOpen

    KcPage(
        title = folder?.name ?: "群文件",
        subtitle = if (folder != null) "群文件 · ${folder?.fileCount ?: 0} 个文件" else null,
        onBack = {
            when {
                searchOpen -> { searchOpen = false; query = "" }
                folder != null -> goRoot()
                else -> nav.back()
            }
        },
        actions = {
            if (state != "locked" && !searchOpen) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { searchOpen = true },
                    contentAlignment = Alignment.Center,
                ) { KcIcon(KcIcons.search, tint = colors.text, size = 22.dp, contentDescription = "搜索群文件") }
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(enabled = progress == null) {
                    if (showAddMenu && state == "active") addMenu = true else startUpload()
                },
                contentAlignment = Alignment.Center,
            ) {
                KcIcon(
                    if (showAddMenu && state == "active") KcIcons.plus else KcIcons.upload,
                    tint = if (canUpload && progress == null) colors.text else colors.subtle,
                    size = 22.dp,
                    contentDescription = if (showAddMenu) "上传文件或新建文件夹" else "上传文件",
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (searchOpen) {
                SearchBar(query, onChange = { query = it }, onCancel = { searchOpen = false; query = "" })
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { load(true) } }, modifier = Modifier.fillMaxWidth().weight(1f)) {
                val listState = rememberLazyListState()
                // 翻到底自动加载下一页
                LaunchedEffect(listState, files.size, total) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                        if (!loading && !loadingMore && files.size < total && last >= listState.layoutInfo.totalItemsCount - 3) load(false)
                    }
                }
                val showFolders = folder == null && !searchOpen
                when {
                    searchOpen && !searching -> StateText("输入文件名，搜索所有文件夹里的文件", Modifier.fillMaxWidth().padding(top = 80.dp))
                    loading && files.isEmpty() -> StateText("正在加载…", Modifier.fillMaxWidth().padding(top = 80.dp))
                    error != null && files.isEmpty() -> StateText(error!!, Modifier.fillMaxWidth().padding(top = 80.dp), error = true, onRetry = { tick++ })
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (folder == null && !searchOpen) {
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
                        if (showFolders && folders.isNotEmpty()) {
                            items(folders, key = { "folder-${it.id}" }) { item ->
                                FolderRow(
                                    item,
                                    onClick = { openFolder(item) },
                                    onLongClick = if (canManageFolders || canTidyFolders) ({ folderActing = item }) else null,
                                )
                            }
                            if (files.isNotEmpty()) item(key = "root-label") {
                                Text("根目录文件", style = type.caption, color = colors.subtle, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                            }
                        }
                        if (files.isEmpty()) {
                            // 根目录有文件夹时不再占一大块「还没有文件」
                            if (!(showFolders && folders.isNotEmpty())) item(key = "empty") {
                                val (title, hint) = when {
                                    searching -> "没有找到相关文件" to "换个关键词试试，搜索会查所有文件夹。"
                                    folder != null -> "文件夹是空的" to (if (canUpload) "点右上角上传，文件会直接放进这个文件夹。" else "这里还没有文件。")
                                    state == "locked" -> "群文件还没有解锁" to "升级到 Pro 后，所有成员都可以在这里分享文件。"
                                    else -> "还没有文件" to "点右上角的上传按钮，把文件分享给群成员。"
                                }
                                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    KcIcon(if (searching) KcIcons.search else KcIcons.folder, tint = colors.subtle, size = 36.dp)
                                    Text(title, style = type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp))
                                    Text(hint, style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
                                }
                            }
                        } else {
                            items(files, key = { it.id }) { file ->
                                FileRow(file, showFolder = searching, onClick = { acting = file })
                            }
                            if (loadingMore) item(key = "more") { Text("加载中…", style = type.caption, color = colors.subtle, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (addMenu) {
        KcSheet(onDismiss = { addMenu = false }, title = "添加") {
            SheetRow("上传文件", { addMenu = false; startUpload() }, icon = KcIcons.upload)
            SheetRow("新建文件夹", { addMenu = false; startCreateFolder() }, icon = KcIcons.folder, detail = "${folders.size} / $folderLimit")
            Box(Modifier.height(8.dp))
        }
    }

    // 选好文件后：确认面板（文件名、大小、放到哪、是否在群里发消息、是否设为永久）
    picked?.let { file ->
        val target = folder
        UploadSheet(
            file = file,
            staff = staff,
            defaultTtlHours = quota?.defaultTtlHours ?: 24,
            folderName = target?.name,
            onDismiss = { picked = null },
            onConfirm = { announce, permanent ->
                picked = null
                progress = file to 0f
                uploadJob = scope.launch {
                    runCatching {
                        uploadGroupFile(graph, context, conversationId, file, announce, permanent, target?.id) { sent ->
                            progress = file to (if (file.size > 0) (sent.toFloat() / file.size).coerceIn(0f, 1f) else 0f)
                        }
                    }.onSuccess { saved ->
                        // 传进文件夹却落在了根目录：空间不够变成了临时文件，或者文件夹 / 档位在这期间变了
                        toast(
                            context,
                            when {
                                target == null || saved == null || saved.folderId == target.id -> "上传成功"
                                saved.temporary -> "空间不够，已作为 24 小时临时文件放在根目录"
                                else -> "文件夹暂时不可用，文件已放在根目录"
                            },
                        )
                        load(true)
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        val code = error.apiCode
                        toast(context, folderErrorMessage(error))
                        if (code == "files_locked" || code == "quota_exceeded") needUpgrade = error.userMessage()
                        if (code == "folder_not_found") goRoot()
                        load(true)
                    }
                    progress = null
                }
            },
        )
    }

    acting?.let { file ->
        val canMove = canManageFolders || (canTidyFolders && file.folderId != null)
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
            onMove = if (canMove) ({ acting = null; moving = file }) else null,
        )
    }
    moving?.let { file ->
        MoveSheet(
            file = file,
            folders = folders,
            canMoveIn = canManageFolders,
            onDismiss = { moving = null },
            onPick = { target ->
                moving = null
                if (target?.id == file.folderId) return@MoveSheet
                scope.launch {
                    runCatching { api.moveFiles(conversationId, listOf(file.id), target?.id) }
                        .onSuccess {
                            toast(context, if (target == null) "已移回根目录" else "已移到「${target.name}」")
                            load(true)
                        }
                        .onFailure {
                            toast(context, folderErrorMessage(it))
                            load(true, silent = true)
                        }
                }
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

    folderActing?.let { item ->
        KcSheet(onDismiss = { folderActing = null }, title = item.name) {
            Text(
                "${item.fileCount} 个文件 · ${formatBytes(item.totalSize)}",
                style = type.caption, color = colors.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            SheetRow("打开", { folderActing = null; openFolder(item) }, icon = KcIcons.folder)
            if (canManageFolders) SheetRow("重命名", { folderActing = null; folderEditor = FolderEditor(item) }, icon = KcIcons.edit)
            if (canTidyFolders) SheetRow("删除文件夹", { folderActing = null; confirmDeleteFolder = item }, icon = KcIcons.trash, danger = true)
            Box(Modifier.height(8.dp))
        }
    }
    folderEditor?.let { editor ->
        FolderNameDialog(
            title = if (editor.folder == null) "新建文件夹" else "重命名文件夹",
            initial = editor.folder?.name.orEmpty(),
            confirmLabel = if (editor.folder == null) "新建" else "保存",
            onDismiss = { folderEditor = null },
            onSubmit = { name ->
                val target = editor.folder
                runCatching {
                    if (target == null) api.createFolder(conversationId, name) else api.renameFolder(conversationId, target.id, name)
                }.fold(
                    onSuccess = { saved ->
                        folderEditor = null
                        if (target == null) {
                            folders = folders + saved
                            toast(context, "已新建文件夹「${saved.name}」")
                        } else {
                            folders = folders.map { if (it.id == saved.id) saved else it }
                            if (folder?.id == saved.id) folder = saved
                            toast(context, "已重命名")
                        }
                        scope.launch { load(true, silent = true) }
                        null
                    },
                    onFailure = { folderErrorMessage(it) },
                )
            },
        )
    }
    confirmDeleteFolder?.let { item ->
        KcConfirm(
            title = "删除文件夹",
            text = "「${item.name}」会被删除，里面的文件会移回根目录" + (if (item.fileCount > 0) "（${item.fileCount} 个）" else "") + "，不会删除文件本身。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                confirmDeleteFolder = null
                scope.launch {
                    runCatching { api.deleteFolder(conversationId, item.id) }
                        .onSuccess { moved ->
                            folders = folders.filter { it.id != item.id }
                            toast(context, if (moved > 0) "已删除文件夹，$moved 个文件移回了根目录" else "已删除文件夹")
                            if (folder?.id == item.id) goRoot()
                            load(true, silent = true)
                        }
                        .onFailure { toast(context, folderErrorMessage(it)) }
                }
            },
            onDismiss = { confirmDeleteFolder = null },
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

/** 新建（folder 为 null）或重命名哪个文件夹 */
private class FolderEditor(val folder: GroupFileFolder?)

/** 群文件 / 文件夹接口的错误码换成好懂的中文（docs/group-plus.md 5.3、5.5）；不认识的用服务端给的文案 */
internal fun folderErrorMessage(error: Throwable): String {
    val api = error as? ApiException ?: return error.userMessage()
    return when (api.code) {
        "folders_require_plus" -> "文件夹是 Pro+ / MAX 群的功能" + (api.detailString("tier")?.let { "，这个群现在是 ${PlusTiers.label(it)}" } ?: "")
        "not_group_staff" -> "只有群主和管理员可以管理文件夹和移动文件"
        "folder_not_found" -> "文件夹不存在，可能已经被删除了"
        "folder_name_taken" -> "已经有同名的文件夹了（不区分大小写）"
        "folder_limit" -> "每个群最多 ${api.detailLong("limit") ?: 50} 个文件夹，先删掉不用的再新建"
        "invalid_folder_name" -> "文件夹名要 1 到 40 个字，不能有换行、/、\\ 或看不见的字符"
        "file_not_found" -> "文件已经不存在了，刷新一下再试"
        "file_not_movable" -> {
            val detail = api.detail as? kotlinx.serialization.json.JsonObject
            val expired = (detail?.get("expired_ids") as? kotlinx.serialization.json.JsonArray).orEmpty().isNotEmpty()
            if (expired) "已过期的文件不能放进文件夹" else "24 小时临时文件不能放进文件夹，转为永久后再移动"
        }
        "files_locked" -> if (api.detailString("state") == "grace") "群文件处于宽限期，只能下载和删除，续费后恢复上传" else "这个群还没有解锁群文件，升级到 Pro 后可用"
        "muted" -> "你现在被禁言了，暂时不能上传文件"
        "file_too_large" -> "文件太大了：剩余空间放不下，也超过了临时文件的上限" + (api.detailLong("temp_max_bytes")?.let { "（${formatBytes(it)}）" } ?: "")
        "quota_exceeded" -> "群文件空间不够了，先删掉一些文件或者升级群"
        "empty_file" -> "不能上传空文件"
        "rate_limited" -> "发得太快了，文件没有保存，稍后再试"
        else -> if (api.code == null && api.status == 422) "请求有误，刷新一下再试" else error.userMessage()
    }
}

// ------------------------------------------------------------------ 文件夹

/** 顶栏下面的搜索条：跨所有文件夹按文件名搜 */
@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onCancel: () -> Unit) {
    val colors = KcTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().background(colors.panel).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(40.dp).clip(CircleShape).background(colors.panelMuted).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcIcon(KcIcons.search, tint = colors.subtle, size = 18.dp)
            Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("搜索文件名", style = KcTheme.type.body, color = colors.subtle)
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = KcTheme.type.body.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Box(Modifier.size(24.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.close, tint = colors.subtle, size = 16.dp, contentDescription = "清空")
                }
            }
        }
        Text(
            "取消",
            style = KcTheme.type.rowTitle,
            color = colors.text,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** 文件夹的图标块：灰阶底 + 发丝描边，和文件的强调色图标区分开 */
@Composable
private fun FolderTile(size: androidx.compose.ui.unit.Dp = 42.dp, muted: Boolean = false) {
    val colors = KcTheme.colors
    Box(
        Modifier.size(size).clip(RoundedCornerShape(11.dp)).background(colors.panelMuted).border(1.dp, colors.border, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) { KcIcon(KcIcons.folder, tint = if (muted) colors.subtle else colors.text, size = size * 0.52f) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(folder: GroupFileFolder, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderTile()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.name, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (folder.fileCount == 0) "空文件夹" else "${folder.fileCount} 个文件 · ${formatBytes(folder.totalSize)}",
                style = type.caption, color = colors.muted, maxLines = 1,
            )
        }
        KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
    }
}

/**
 * 「移动到…」：根目录 + 全部文件夹，当前位置打勾。
 * 临时文件 / 过期文件不能进文件夹；降档后（只有 can_tidy）只能移回根目录。不能选的项变灰，并在上面说明原因。
 */
@Composable
private fun MoveSheet(file: GroupFile, folders: List<GroupFileFolder>, canMoveIn: Boolean, onDismiss: () -> Unit, onPick: (GroupFileFolder?) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val reason = when {
        !canMoveIn -> "群档位已不是 Pro+ / MAX，文件夹只能整理：可以把文件移回根目录，不能再移进文件夹。"
        file.expired -> "文件已过期，不能放进文件夹，只能留在根目录。"
        file.temporary -> "这是空间不够时传的 24 小时临时文件，不占群空间，所以不能放进文件夹。管理员把它转为永久文件后就可以移动了。"
        else -> null
    }
    val folderEnabled = reason == null
    KcSheet(onDismiss = onDismiss, title = "移动到…") {
        Text(
            "「${file.name}」",
            style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        reason?.let {
            Text(
                it,
                style = type.caption, color = colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MoveTarget("根目录", null, KcIcons.home, selected = file.folderId == null, enabled = true) { onPick(null) }
            folders.forEach { item ->
                MoveTarget(
                    item.name,
                    "${item.fileCount} 个文件",
                    KcIcons.folder,
                    selected = file.folderId == item.id,
                    enabled = folderEnabled || file.folderId == item.id,
                ) { onPick(item) }
            }
            if (folders.isEmpty()) {
                Text(
                    "还没有文件夹。回到根目录，点右上角的加号新建一个。",
                    style = type.caption, color = colors.subtle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            Box(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MoveTarget(label: String, detail: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val tint = if (enabled) colors.text else colors.subtle
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(icon, tint = tint, size = 22.dp, modifier = Modifier.padding(end = 14.dp))
        Text(label, style = KcTheme.type.rowTitle, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, style = KcTheme.type.secondary, color = if (enabled) colors.muted else colors.subtle, maxLines = 1)
        if (selected) KcIcon(KcIcons.check, tint = colors.text, size = 20.dp, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * 新建 / 重命名文件夹的输入框（居中，和 KcConfirm 同一套外观）。
 * 本地只挡明显不行的（空、超长、换行、/、\），其余交给服务端；onSubmit 返回错误文案（null 表示成功，由调用方关掉）。
 */
@Composable
private fun FolderNameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onSubmit: suspend (String) -> String?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val name = value.text.trim()
    val localError = when {
        name.codePointCount(0, name.length) > 40 -> "最多 40 个字"
        name.any { it == '/' || it == '\\' || it == '\n' || it == '\r' } -> "不能有换行、/ 或 \\"
        else -> null
    }
    val canSubmit = !busy && name.isNotEmpty() && localError == null && name != initial
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = { if (!busy) onDismiss() })

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        scope.launch {
            error = onSubmit(name)
            busy = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(colors.scrim).imePadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!busy) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
        ) {
            Text(title, style = type.title, color = colors.text)
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted)
                    .border(1.dp, if (error != null || localError != null) colors.danger.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) Text("文件夹名", style = type.body, color = colors.subtle)
                BasicTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    singleLine = true,
                    textStyle = type.body.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Text(
                error ?: localError ?: "1 到 40 个字，同一个群里不能重名",
                style = type.caption,
                color = if (error != null || localError != null) colors.danger else colors.subtle,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KcButton("取消", { if (!busy) onDismiss() }, modifier = Modifier.weight(1f), primary = false)
                KcButton(if (busy) "请稍候…" else confirmLabel, { submit() }, modifier = Modifier.weight(1f), enabled = canSubmit)
            }
        }
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

/** 点按、长按都弹操作面板；showFolder（搜索结果）时多一行「在哪个文件夹」 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: GroupFile, showFolder: Boolean = false, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val gone = file.expired
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel).combinedClickable(onClick = onClick, onLongClick = onClick).padding(12.dp),
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
            if (showFolder) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KcIcon(if (file.folderId != null) KcIcons.folder else KcIcons.home, tint = colors.subtle, size = 13.dp)
                    Text(
                        file.folderName ?: "根目录",
                        style = type.caption, color = colors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
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
private fun UploadSheet(file: PickedFile, staff: Boolean, defaultTtlHours: Int, folderName: String? = null, onDismiss: () -> Unit, onConfirm: (announce: Boolean, permanent: Boolean) -> Unit) {
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
            if (folderName != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.folder, tint = colors.text, size = 18.dp)
                    Text("放进「$folderName」", style = type.secondary, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
                Text("空间不够时会作为 24 小时临时文件放在根目录。", style = type.caption, color = colors.muted)
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
    onMove: (() -> Unit)? = null,
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
        if (onMove != null) SheetRow("移动到…", { if (working == null) onMove() }, icon = KcIcons.folder, detail = file.folderName ?: "根目录")
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

/** 群文件页根目录第一页的快照（只在内存，退出登录清掉） */
private class GroupFilesSnapshot(
    val files: List<GroupFile>,
    val total: Int,
    val quota: FileQuota?,
    val folders: List<GroupFileFolder>,
    val canManageFolders: Boolean,
    val canTidyFolders: Boolean,
    val folderLimit: Int,
)
