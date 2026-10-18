package ink.kuke.chat.feature.groupannouncement

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.chat.uploadMessageImage
import ink.kuke.chat.feature.groupplus.PlusButton
import ink.kuke.chat.feature.groupplus.PlusNote
import ink.kuke.chat.feature.groupplus.plusInk
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 编辑页上的一张图：已上传的有 url；刚选的先显示本地图，传完换成 url */
private class EditImage(val key: Long, val url: String?, val local: Uri?) {
    val uploading: Boolean get() = url == null
}

private var imageKeys = 0L

/**
 * 发布 / 编辑公告（announcementId = 0 是新发布）。正文 1–600 字、最多 9 张图（选完立刻上传到 /uploads/message-image），
 * 开关：使用弹窗展示、需群成员确认收到；「高级设置」：设为置顶、发送给新成员（全群只能有一条）、引导群成员修改群昵称。
 * 修改也把所有字段整份提交。有改动时返回要先确认放弃。
 */
@Composable
fun AnnouncementEditorScreen(graph: AppGraph, conversationId: Long, announcementId: Long) {
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val k = plusInk()
    val api = remember(graph) { GroupAnnouncementApi(graph) }
    val editing = announcementId > 0
    val conversation = graph.conversations.items.collectAsStateWithLifecycle().value.firstOrNull { it.id == conversationId }

    var source by remember { mutableStateOf(if (editing) AnnouncementHub.cached(announcementId) else null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var filled by remember { mutableStateOf(false) }

    var text by remember { mutableStateOf("") }
    val images = remember { mutableStateListOf<EditImage>() }
    var popup by remember { mutableStateOf(false) }
    var requireConfirm by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var sendToNew by remember { mutableStateOf(false) }
    var guideNickname by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }

    fun fill(from: GroupAnnouncement) {
        text = from.content
        images.clear()
        images.addAll(from.images.map { EditImage(++imageKeys, it.url, null) })
        popup = from.popup
        requireConfirm = from.requireConfirm
        pinned = from.pinned
        sendToNew = from.sendToNewMembers
        guideNickname = from.guideNickname
        advanced = from.pinned || from.sendToNewMembers || from.guideNickname
        filled = true
    }

    LaunchedEffect(announcementId, attempt) {
        if (!editing) {
            filled = true
            return@LaunchedEffect
        }
        source?.let { if (!filled) fill(it) }
        runCatching { api.get(conversationId, announcementId) }
            .onSuccess { fresh ->
                // 已经开始改了就不覆盖输入，只换掉对照的原值
                if (!filled || !dirtyAgainst(source, text, images, popup, requireConfirm, pinned, sendToNew, guideNickname)) fill(fresh)
                source = fresh
            }
            .onFailure { if (source == null) loadError = it.userMessage() }
    }

    val dirty = filled && if (editing) dirtyAgainst(source, text, images, popup, requireConfirm, pinned, sendToNew, guideNickname)
    else text.isNotBlank() || images.isNotEmpty() || popup || requireConfirm || pinned || sendToNew || guideNickname
    val length = text.trim().length
    val uploading = images.any { it.uploading }
    val canSubmit = filled && length in 1..ANNOUNCEMENT_MAX_LENGTH && !uploading && !busy

    fun leave() {
        if (dirty && !busy) askLeave = true else nav.back()
    }
    BackHandler(enabled = dirty && !busy) { askLeave = true }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ANNOUNCEMENT_MAX_IMAGES)) { uris ->
        val room = ANNOUNCEMENT_MAX_IMAGES - images.size
        if (uris.size > room) toast(context, "最多 $ANNOUNCEMENT_MAX_IMAGES 张图片")
        uris.take(room.coerceAtLeast(0)).forEach { uri ->
            val placeholder = EditImage(++imageKeys, null, uri)
            images.add(placeholder)
            scope.launch {
                runCatching { uploadMessageImage(graph, context, uri) }
                    .onSuccess { url ->
                        val index = images.indexOfFirst { it.key == placeholder.key }
                        if (index >= 0) images[index] = EditImage(placeholder.key, url, uri)
                    }
                    .onFailure {
                        images.removeAll { image -> image.key == placeholder.key }
                        toast(context, "图片上传失败：${it.userMessage()}")
                    }
            }
        }
    }

    fun submit() {
        if (!canSubmit) return
        busy = true
        val draft = AnnouncementDraft(
            content = text,
            images = images.mapNotNull { it.url },
            pinned = pinned,
            popup = popup,
            requireConfirm = requireConfirm,
            sendToNewMembers = sendToNew,
            guideNickname = guideNickname,
        )
        scope.launch {
            runCatching { if (editing) api.update(conversationId, announcementId, draft) else api.create(conversationId, draft) }
                .onSuccess {
                    graph.conversations.refreshSoon(0)
                    toast(context, if (editing) "公告已更新" else "公告已发布")
                    nav.back()
                }
                .onFailure { toast(context, it.userMessage()) }
            busy = false
        }
    }

    KcPage(
        title = if (editing) "编辑公告" else "发布公告",
        onBack = ::leave,
        subtitle = conversation?.name(),
        actions = {
            PlusButton(
                when {
                    busy -> if (editing) "保存中…" else "发布中…"
                    editing -> "保存"
                    else -> "发布"
                },
                ::submit,
                modifier = Modifier.padding(end = 10.dp),
                enabled = canSubmit,
                height = 34.dp,
            )
        },
    ) {
        Box(Modifier.fillMaxSize().background(KcTheme.colors.panel)) {
            if (editing && !filled) {
                if (loadError != null) StateText(loadError!!, error = true, onRetry = { loadError = null; attempt++ })
                else StateText("加载中…")
                return@Box
            }
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
                // 正文
                Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                    val style = TextStyle(color = k.ink, fontSize = 16.sp, lineHeight = 25.sp)
                    if (text.isEmpty()) Text("输入公告内容，群成员都能看到", style = style.copy(color = k.faint))
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= ANNOUNCEMENT_MAX_LENGTH) text = it else text = it.take(ANNOUNCEMENT_MAX_LENGTH) },
                        textStyle = style,
                        cursorBrush = SolidColor(k.ink),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    )
                }
                Text(
                    "$length/$ANNOUNCEMENT_MAX_LENGTH",
                    color = if (length > ANNOUNCEMENT_MAX_LENGTH) KcTheme.colors.danger else k.faint,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.End).padding(end = 20.dp, top = 4.dp),
                )

                // 图片
                ImagePickerGrid(
                    images = images,
                    onAdd = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemove = { key -> images.removeAll { it.key == key } },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
                )

                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(8.dp).background(k.wash))
                AnnSwitchRow("使用弹窗展示", "群成员进入群聊时弹出这条公告", popup) { popup = it }
                AnnDivider()
                AnnSwitchRow("需群成员确认收到", "成员要点「确认收到」，你可以查看谁确认了", requireConfirm) { requireConfirm = it }
                AnnDivider()

                // 高级设置
                Row(
                    Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("高级设置", color = k.ink, fontSize = 15.sp, fontWeight = FontWeight.W500, modifier = Modifier.weight(1f))
                    KcIcon(if (advanced) KcIcons.chevronUp else KcIcons.chevronDown, tint = k.faint, size = 18.dp)
                }
                if (advanced) {
                    AnnSwitchRow("设为置顶", "置顶的公告排在列表最前面", pinned) { pinned = it }
                    AnnDivider()
                    AnnSwitchRow("发送给新成员", "新成员入群后会弹出这条公告。全群只能有一条，开启后会取消其它公告的这一项", sendToNew) { sendToNew = it }
                    AnnDivider()
                    AnnSwitchRow("引导群成员修改群昵称", "公告底部显示「修改群昵称」按钮", guideNickname) { guideNickname = it }
                }
                PlusNote(
                    if (editing) "改了正文、图片或选项会在群聊里发一张「公告已更新」卡片（只改置顶不发）；成员已有的确认记录保留" else "发布后会在群聊里发一张公告卡片",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
                )
                Box(Modifier.navigationBarsPadding())
            }
        }
    }

    if (askLeave) {
        KcConfirm(
            title = if (editing) "放弃修改？" else "放弃发布？",
            text = "刚才填写的内容不会保存。",
            confirmLabel = "放弃",
            danger = true,
            onConfirm = {
                askLeave = false
                nav.back()
            },
            onDismiss = { askLeave = false },
        )
    }
}

private fun dirtyAgainst(
    source: GroupAnnouncement?,
    text: String,
    images: List<EditImage>,
    popup: Boolean,
    requireConfirm: Boolean,
    pinned: Boolean,
    sendToNew: Boolean,
    guideNickname: Boolean,
): Boolean {
    if (source == null) return false
    return text != source.content || images.map { it.url } != source.images.map { it.url } ||
        popup != source.popup || requireConfirm != source.requireConfirm || pinned != source.pinned ||
        sendToNew != source.sendToNewMembers || guideNickname != source.guideNickname
}

/** 四列方格：已选的图（右上角 × 去掉，上传中盖一层半透明）+ 末尾的「添加」格 */
@Composable
private fun ImagePickerGrid(images: List<EditImage>, onAdd: () -> Unit, onRemove: (Long) -> Unit, modifier: Modifier = Modifier) {
    val k = plusInk()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        val cell: Dp = (maxWidth - gap * 3) / 4
        val tiles: List<EditImage?> = images + if (images.size < ANNOUNCEMENT_MAX_IMAGES) listOf(null) else emptyList()
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            tiles.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { image ->
                        if (image == null) {
                            Column(
                                Modifier.size(cell).clip(RoundedCornerShape(12.dp)).border(1.dp, k.lineStrong, RoundedCornerShape(12.dp)).clickable(onClick = onAdd),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                KcIcon(KcIcons.imagePlus, tint = k.sub, size = 22.dp)
                                Text("${images.size}/$ANNOUNCEMENT_MAX_IMAGES", color = k.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        } else {
                            Box(Modifier.size(cell).clip(RoundedCornerShape(12.dp)).background(k.wash)) {
                                AsyncImage(
                                    model = image.local ?: Endpoints.absolute(image.url),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(cell),
                                )
                                if (image.uploading) {
                                    Box(Modifier.size(cell).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                        Text("上传中", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.W600)
                                    }
                                }
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f)).clickable { onRemove(image.key) },
                                    contentAlignment = Alignment.Center,
                                ) { KcIcon(KcIcons.close, tint = Color.White, size = 12.dp, contentDescription = "去掉这张") }
                            }
                        }
                    }
                }
            }
        }
    }
}
