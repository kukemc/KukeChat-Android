package ink.kuke.chat.feature.group

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import ink.kuke.chat.feature.groupplus.apiCode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcButton
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.KcTextField
import ink.kuke.chat.feature.chat.SheetForm
import ink.kuke.chat.feature.chat.SheetRow
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.asArr
import ink.kuke.chat.feature.chat.asObj
import ink.kuke.chat.feature.chat.l
import ink.kuke.chat.feature.chat.s
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/** 一个群表情（GroupEmojiRead） */
data class GroupEmoji(val id: Long, val name: String, val url: String)

/**
 * 群表情接口（KukeChat-Server chat_extras.py，同电脑版 api/groupEmojis.ts）+ 按群的进程内缓存。
 * 表情面板和群资料共用；别人增删改名会推 conversation.emojis.updated，挂着的地方收到就重拉。
 */
object GroupEmojis {
    const val MAX = 60
    const val NAME_MAX = 12
    /** 服务端单张上限 1MB（MAX_EMOJI_BYTES，docs/chat-features.md 群表情）；超了本地先压一遍 */
    private const val MAX_BYTES = 1024 * 1024
    /** 从相册一次最多选几张 */
    const val PICK_MAX = 20
    const val EVENT = "conversation.emojis.updated"

    private val cache = mutableStateMapOf<Long, List<GroupEmoji>>()

    fun cached(conversationId: Long): List<GroupEmoji>? = cache[conversationId]

    private fun parse(element: JsonElement): List<GroupEmoji> {
        val array = element.asArr() ?: element.asObj()?.get("items").asArr() ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.asObj() ?: return@mapNotNull null
            GroupEmoji(obj.l("id") ?: return@mapNotNull null, obj.s("name").orEmpty(), obj.s("url") ?: return@mapNotNull null)
        }
    }

    private fun parseOne(element: JsonElement): GroupEmoji? = parse(kotlinx.serialization.json.JsonArray(listOf(element))).firstOrNull()

    suspend fun load(graph: AppGraph, conversationId: Long): List<GroupEmoji> =
        parse(graph.api.get("/conversations/$conversationId/emojis")).also { cache[conversationId] = it }

    /** 服务端会去掉名称里的所有空白，本地先去掉，查重才对得上 */
    fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), "").take(NAME_MAX)

    suspend fun create(graph: AppGraph, conversationId: Long, name: String, imageUrl: String): GroupEmoji? {
        val created = parseOne(graph.api.post("/conversations/$conversationId/emojis", buildJsonObject {
            put("name", cleanName(name))
            put("image_url", imageUrl)
        }))
        if (created != null) cache[conversationId] = cache[conversationId].orEmpty().filter { it.id != created.id } + created
        return created
    }

    suspend fun rename(graph: AppGraph, conversationId: Long, emojiId: Long, name: String) {
        val updated = parseOne(graph.api.patch("/conversations/$conversationId/emojis/$emojiId", buildJsonObject { put("name", cleanName(name)) }))
        if (updated != null) cache[conversationId] = cache[conversationId].orEmpty().map { if (it.id == emojiId) updated else it }
    }

    suspend fun delete(graph: AppGraph, conversationId: Long, emojiId: Long) {
        graph.api.delete("/conversations/$conversationId/emojis/$emojiId")
        cache[conversationId] = cache[conversationId].orEmpty().filter { it.id != emojiId }
    }

    /**
     * 排序（PUT /conversations/{cid}/emojis/order）：[ids] 必须正好是当前全部表情的 id，
     * 不一致服务端返回 409 emoji_order_mismatch（调用方刷新后让用户重排）。返回排好的列表并写回缓存
     */
    suspend fun reorder(graph: AppGraph, conversationId: Long, ids: List<Long>): List<GroupEmoji> {
        val sorted = parse(graph.api.put("/conversations/$conversationId/emojis/order", buildJsonObject {
            put("emoji_ids", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }))
        cache[conversationId] = sorted
        return sorted
    }

    /** 选一张本地图片上传到消息图片通道，返回地址。≤1MB 的原样传（保留 GIF 动图），超了缩到 320px 再压 */
    suspend fun uploadLocal(graph: AppGraph, context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/png"
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这张图片")
        val (bytes, type, ext) = if (raw.size <= MAX_BYTES && mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
            Triple(raw, mime, mime.substringAfter('/').replace("jpeg", "jpg"))
        } else {
            if (mime == "image/gif") error("动图不能超过 1MB")
            val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("这不是一张能识别的图片")
            val scale = minOf(1f, 320f / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
            val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            if (png.size <= MAX_BYTES) Triple(png, "image/png", "png")
            else Triple(ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }, "image/jpeg", "jpg")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "emoji.$ext", bytes.toRequestBody(type.toMediaType()))
            .build()
        graph.api.upload("/uploads/message-image", body).asObj()?.s("url") ?: error("上传没有返回地址")
    }
}

/** 本群表情列表：先给缓存，进来重拉一次；挂着时收到更新事件再拉。null = 还没拉到 */
@Composable
fun rememberGroupEmojis(graph: AppGraph, conversationId: Long): List<GroupEmoji>? {
    var failed by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(conversationId) {
        runCatching { GroupEmojis.load(graph, conversationId) }.onFailure { failed = true }
        graph.realtime.events.collect { event ->
            if (event.type == GroupEmojis.EVENT && event.data.l("conversation_id") == conversationId) {
                runCatching { GroupEmojis.load(graph, conversationId) }
            }
        }
    }
    // 缓存是快照状态：本地增删改名、重拉都会直接反映到这里
    return GroupEmojis.cached(conversationId) ?: if (failed) emptyList() else null
}

/** 收藏表情地址（GET /stickers/favorites） */
internal suspend fun favoriteStickerUrls(graph: AppGraph): List<String> =
    (graph.api.get("/stickers/favorites").asArr() ?: graph.api.get("/stickers/favorites").asObj()?.get("items").asArr())
        ?.mapNotNull { it.asObj()?.s("url") }.orEmpty()

// ------------------------------------------------------------------ 表情面板里的「本群表情」页

/** 表情面板第 4 页：本群表情网格（名字在下）；点一下直接发出 */
@Composable
fun GroupEmojiPickerPage(graph: AppGraph, conversationId: Long, onSend: (GroupEmoji) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val list = rememberGroupEmojis(graph, conversationId)
    val emojis = list
    when {
        emojis == null -> Text("加载中…", style = type.caption, color = colors.muted, modifier = Modifier.padding(16.dp))
        emojis.isEmpty() -> Text("本群还没有表情。群主和管理员可以在「群聊资料 · 群表情」里添加。", style = type.caption, color = colors.muted, modifier = Modifier.padding(16.dp))
        else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            item(span = { GridItemSpan(4) }) {
                Text("本群表情 · ${emojis.size}", style = type.group, color = colors.muted, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp))
            }
            items(emojis, key = { it.id }) { emoji -> EmojiCell(emoji, compact = true) { onSend(emoji) } }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(emoji: GroupEmoji, compact: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        Modifier.padding(4.dp).pressScale(press, 0.92f).clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onLongClick = onLongClick?.let { long -> { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); long() } },
                onClick = onClick,
            ).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = Endpoints.absolute(emoji.url),
            contentDescription = emoji.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(if (compact) 64.dp else 68.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(emoji.name, style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}

// ------------------------------------------------------------------ 群资料里的群表情管理

private sealed interface EmojiDialog {
    /** 待添加的一批（相册多选，或从收藏里挑的一张）：每张起个名字再一起传 */
    data class Batch(val drafts: List<EmojiDraft>) : EmojiDialog
    data object Favorites : EmojiDialog
    data class Actions(val emoji: GroupEmoji) : EmojiDialog
    data class Rename(val emoji: GroupEmoji) : EmojiDialog
    data class Delete(val emoji: GroupEmoji) : EmojiDialog
}

/** 一张待添加的群表情：本地图片（相册）或已上传的地址（收藏表情） */
private data class EmojiDraft(val key: String, val localUri: Uri?, val imageUrl: String?, val name: String)

/** 给一批新表情起默认名「表情1」「表情2」…，避开群里已有的和这批里先起的 */
private fun defaultNames(count: Int, taken: Set<String>): List<String> {
    val used = taken.toMutableSet()
    var n = 1
    return List(count) {
        while ("表情$n" in used) n++
        "表情$n".also { used += it }
    }
}

/**
 * 群表情管理页：全体成员能看；群主和管理员可以从相册（多选，一次最多 [GroupEmojis.PICK_MAX] 张）/ 收藏表情添加，
 * 长按某个表情重命名 / 删除，「排序」进入拖动排序（上限 [GroupEmojis.MAX] 个）。叠在群资料页上，系统返回关闭。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GroupEmojiPage(graph: AppGraph, conversationId: Long, canManage: Boolean, onBack: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberGroupEmojis(graph, conversationId)
    val emojis = list.orEmpty()
    var dialog by remember { mutableStateOf<EmojiDialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    /** 批量添加的进度（「正在添加 3/8」）和失败汇总 */
    var progress by remember { mutableStateOf<String?>(null) }
    var batchError by remember { mutableStateOf<String?>(null) }
    val full = emojis.size >= GroupEmojis.MAX
    BackHandler(enabled = dialog == null) { if (sorting) sorting = false else onBack() }

    fun openBatch(drafts: List<EmojiDraft>) {
        batchError = null
        dialog = EmojiDialog.Batch(drafts)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(GroupEmojis.PICK_MAX)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = GroupEmojis.MAX - emojis.size
        if (uris.size > room) toast(context, "最多还能加 $room 个，多选的已忽略")
        val picked = uris.take(room)
        val names = defaultNames(picked.size, emojis.map { it.name }.toSet())
        openBatch(picked.mapIndexed { index, uri -> EmojiDraft(uri.toString(), uri, null, names[index]) })
    }

    fun run(block: suspend () -> Unit, success: String) {
        busy = true
        scope.launch {
            runCatching { block() }.onSuccess { toast(context, success); dialog = null }.onFailure { toast(context, it.userMessage()) }
            busy = false
        }
    }

    /** 逐张上传 + 添加，显示「正在添加 i/n」；全成功关面板，有失败的留在面板里（可改名重试）并汇总原因 */
    fun addBatch(drafts: List<EmojiDraft>) {
        busy = true
        batchError = null
        scope.launch {
            val failed = mutableListOf<Pair<EmojiDraft, String>>()
            drafts.forEachIndexed { index, draft ->
                progress = "正在添加 ${index + 1}/${drafts.size}"
                runCatching {
                    val url = draft.imageUrl ?: GroupEmojis.uploadLocal(graph, context, draft.localUri ?: error("没有选图片"))
                    GroupEmojis.create(graph, conversationId, draft.name, url)
                }.onFailure { failed += draft to it.userMessage() }
            }
            progress = null
            busy = false
            val ok = drafts.size - failed.size
            if (failed.isEmpty()) {
                toast(context, if (drafts.size == 1) "已添加「${drafts[0].name}」" else "已添加 ${drafts.size} 个表情")
                dialog = null
            } else {
                batchError = buildString {
                    append(if (ok > 0) "已添加 $ok 个，${failed.size} 个失败：" else "${failed.size} 个添加失败：")
                    failed.take(5).forEach { (draft, reason) -> append("\n「${draft.name}」$reason") }
                    if (failed.size > 5) append("\n……")
                }
                dialog = EmojiDialog.Batch(failed.map { it.first })
            }
        }
    }

    KcPage(title = "群表情", subtitle = "${emojis.size} / ${GroupEmojis.MAX}", onBack = { if (sorting) sorting = false else onBack() }) {
        if (sorting) {
            EmojiSortList(
                emojis = emojis,
                busy = busy,
                onCancel = { sorting = false },
                onDone = { ids ->
                    if (ids == emojis.map { it.id }) {
                        sorting = false
                        return@EmojiSortList
                    }
                    busy = true
                    scope.launch {
                        runCatching { GroupEmojis.reorder(graph, conversationId, ids) }
                            .onSuccess { toast(context, "已保存排序"); sorting = false }
                            .onFailure { error ->
                                if (error.apiCode == "emoji_order_mismatch") {
                                    // 别人刚增删过表情：刷新列表，留在排序里让他重排
                                    runCatching { GroupEmojis.load(graph, conversationId) }
                                    toast(context, "表情列表有变化，已刷新，请重新排序")
                                } else {
                                    toast(context, error.userMessage())
                                }
                            }
                        busy = false
                    }
                },
            )
            return@KcPage
        }
        Column(Modifier.fillMaxSize()) {
            if (canManage) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddTile(KcIcons.imagePlus, "从相册添加", enabled = !full && !busy, modifier = Modifier.weight(1f)) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    AddTile(KcIcons.heart, "从收藏添加", enabled = !full && !busy, modifier = Modifier.weight(1f)) { dialog = EmojiDialog.Favorites }
                    if (emojis.size >= 2) AddTile(KcIcons.gripVertical, "排序", enabled = !busy, modifier = Modifier.weight(0.7f)) { sorting = true }
                }
                Text(
                    if (full) "已达上限 ${GroupEmojis.MAX} 个，删掉一些才能继续添加" else "长按表情可以重命名或删除。单张不超过 1MB，动图会保留动画。",
                    style = type.caption,
                    color = if (full) colors.warn else colors.muted,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
                )
            }
            when {
                list == null -> StateText("加载中…")
                emojis.isEmpty() -> StateText(if (canManage) "还没有群表情，点上面添加第一个" else "本群还没有表情")
                else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(emojis, key = { it.id }) { emoji ->
                        EmojiCell(
                            emoji,
                            compact = false,
                            onLongClick = if (canManage) ({ dialog = EmojiDialog.Actions(emoji) }) else null,
                        ) { if (canManage) dialog = EmojiDialog.Actions(emoji) }
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        is EmojiDialog.Batch -> BatchEmojiSheet(
            drafts = current.drafts,
            taken = emojis.map { it.name }.toSet(),
            busy = busy,
            progress = progress,
            error = batchError,
            onDismiss = { if (!busy) dialog = null },
            onSubmit = { named -> addBatch(named) },
        )
        EmojiDialog.Favorites -> FavoritePickSheet(graph, onDismiss = { dialog = null }) { url ->
            openBatch(listOf(EmojiDraft(url, null, url, defaultNames(1, emojis.map { it.name }.toSet()).first())))
        }
        is EmojiDialog.Actions -> KcSheet(onDismiss = { dialog = null }, title = current.emoji.name) {
            SheetRow("重命名", { dialog = EmojiDialog.Rename(current.emoji) }, icon = KcIcons.edit)
            SheetRow("删除", { dialog = EmojiDialog.Delete(current.emoji) }, icon = KcIcons.trash, danger = true)
            Box(Modifier.padding(bottom = 8.dp))
        }
        is EmojiDialog.Rename -> {
            var text by remember(current.emoji.id) { mutableStateOf(current.emoji.name) }
            KcSheet(onDismiss = { if (!busy) dialog = null }, title = "重命名表情") {
                SheetForm {
                    KcTextField(text, { text = it.take(GroupEmojis.NAME_MAX) }, "最多 ${GroupEmojis.NAME_MAX} 个字")
                    KcButton(if (busy) "保存中…" else "保存", {
                        val clean = GroupEmojis.cleanName(text)
                        when {
                            clean.isEmpty() -> toast(context, "表情名称不能为空")
                            clean == current.emoji.name -> dialog = null
                            emojis.any { it.id != current.emoji.id && it.name == clean } -> toast(context, "这个群里已经有同名的表情")
                            else -> run({ GroupEmojis.rename(graph, conversationId, current.emoji.id, clean) }, "已改名")
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is EmojiDialog.Delete -> KcConfirm("删除表情", "删除「${current.emoji.name}」后，群成员就不能再从表情面板发它了。已经发出的消息不受影响。", "删除", danger = true, onConfirm = {
            run({ GroupEmojis.delete(graph, conversationId, current.emoji.id) }, "已删除")
        }, onDismiss = { dialog = null })
    }
}

@Composable
private fun AddTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Row(
        modifier.pressScale(press).clip(RoundedCornerShape(12.dp)).background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(icon, tint = if (enabled) colors.text else colors.subtle, size = 18.dp)
        Text(label, style = KcTheme.type.secondary, color = if (enabled) colors.text else colors.subtle, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * 选好图片之后：每张一行（预览 + 名字），一起添加。名字预填「表情1」…，可以改。
 * 传的时候按钮上显示「正在添加 3/8」；失败的留在这里，上面红字汇总原因。
 */
@Composable
private fun BatchEmojiSheet(
    drafts: List<EmojiDraft>,
    taken: Set<String>,
    busy: Boolean,
    progress: String?,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<EmojiDraft>) -> Unit,
) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val names = remember(drafts) {
        androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(drafts.map { it.name }) }
    }
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    KcSheet(onDismiss = onDismiss, title = if (drafts.size == 1) "添加群表情" else "添加 ${drafts.size} 个群表情", maxHeight = minOf(640.dp, screen * 0.85f)) {
        if (error != null) {
            Text(error, style = KcTheme.type.caption, color = colors.danger, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp))
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(drafts.size, key = { drafts[it].key }) { index ->
                val draft = drafts[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = draft.localUri ?: Endpoints.absolute(draft.imageUrl),
                        contentDescription = "预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted),
                    )
                    KcTextField(
                        names.getOrElse(index) { "" },
                        { value -> if (index < names.size) names[index] = value.take(GroupEmojis.NAME_MAX) },
                        "表情名称，最多 ${GroupEmojis.NAME_MAX} 个字",
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            KcButton(
                when {
                    progress != null -> progress
                    busy -> "上传中…"
                    drafts.size == 1 -> "添加"
                    else -> "全部添加（${drafts.size}）"
                },
                {
                    val cleaned = names.map { GroupEmojis.cleanName(it) }
                    val dup = cleaned.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                    when {
                        cleaned.any { it.isEmpty() } -> toast(context, "表情名称不能为空")
                        dup.isNotEmpty() -> toast(context, "名字「${dup.first()}」重复了")
                        cleaned.any { it in taken } -> toast(context, "这个群里已经有叫「${cleaned.first { it in taken }}」的表情")
                        else -> onSubmit(drafts.mapIndexed { index, draft -> draft.copy(name = cleaned[index]) })
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 一行的高度：64dp 内容 + 1dp 分隔线（同设置 › 底栏与功能排序的拖动写法） */
private val SortRowPitch = 65.dp

/**
 * 群表情拖动排序：按住右侧把手直接拖（或按住整行稍等一下再拖）。写法照搬设置 › 底栏与功能排序（NavPrefs.kt）：
 * 拖起的行微微放大浮起、跟着手指走，越过别的行时那一行平滑让开、轻震一下；点「完成」才提交。
 */
@Composable
private fun EmojiSortList(emojis: List<GroupEmoji>, busy: Boolean, onCancel: () -> Unit, onDone: (List<Long>) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pitch = with(density) { SortRowPitch.toPx() }
    val scope = rememberCoroutineScope()
    // 本地排序副本；服务端列表变了（刷新、别人增删）就重置
    val items = remember(emojis) { androidx.compose.runtime.mutableStateListOf<GroupEmoji>().apply { addAll(emojis) } }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val settle = remember { androidx.compose.animation.core.Animatable(0f) }

    fun startDrag(id: Long) {
        if (busy) return
        dragging = id
        dragOffset = 0f
        ink.kuke.chat.ui.components.Haptics.tick(view)
    }
    fun onDrag(dy: Float) {
        val id = dragging ?: return
        dragOffset += dy
        var index = items.indexOfFirst { it.id == id }
        while (dragOffset > pitch / 2 && index < items.lastIndex) {
            items.add(index + 1, items.removeAt(index))
            index++
            dragOffset -= pitch
            ink.kuke.chat.ui.components.Haptics.tick(view)
        }
        while (dragOffset < -pitch / 2 && index > 0) {
            items.add(index - 1, items.removeAt(index))
            index--
            dragOffset += pitch
            ink.kuke.chat.ui.components.Haptics.tick(view)
        }
    }
    fun endDrag() {
        if (dragging == null) return
        val from = dragOffset
        scope.launch {
            settle.snapTo(from)
            dragOffset = 0f
            settle.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 600f))
            dragging = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("按住右侧把手拖动排序，表情面板里按这个顺序显示", style = type.caption, color = colors.muted, modifier = Modifier.weight(1f))
            Text(
                "取消",
                style = type.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600),
                color = colors.text,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(99.dp)).border(1.dp, colors.border, RoundedCornerShape(99.dp))
                    .clickable(enabled = !busy && dragging == null, onClick = onCancel).padding(horizontal = 14.dp, vertical = 7.dp),
            )
            Text(
                if (busy) "保存中…" else "完成",
                style = type.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600),
                color = colors.onFill,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(99.dp)).background(colors.fill)
                    .clickable(enabled = !busy && dragging == null) { onDone(items.map { it.id }) }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState(), enabled = dragging == null)
                .padding(horizontal = 12.dp).padding(bottom = 24.dp)
                .clip(RoundedCornerShape(14.dp)).background(colors.panel).border(1.dp, colors.border, RoundedCornerShape(14.dp)),
        ) {
            items.forEachIndexed { index, emoji ->
                key(emoji.id) {
                    val active = dragging == emoji.id
                    val shift = remember { androidx.compose.animation.core.Animatable(0f) }
                    var lastIndex by remember { mutableStateOf(index) }
                    LaunchedEffect(index) {
                        if (index != lastIndex && !active) {
                            shift.snapTo((lastIndex - index) * pitch + shift.value)
                            lastIndex = index
                            shift.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 700f))
                        } else {
                            lastIndex = index
                        }
                    }
                    val lift by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0f, label = "lift")
                    val rowShape = RoundedCornerShape(14.dp * lift)
                    val dividerHidden = index == 0 || active || (dragging != null && items.getOrNull(index - 1)?.id == dragging)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(SortRowPitch)
                            .zIndex(if (active) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (active) dragOffset + settle.value else shift.value
                                scaleX = 1f + 0.025f * lift
                                scaleY = scaleX
                                shadowElevation = 12.dp.toPx() * lift
                                shape = rowShape
                                clip = false
                            }
                            .background(colors.panel, rowShape)
                            .pointerInput(emoji.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { startDrag(emoji.id) },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = { endDrag() },
                                ) { change, amount ->
                                    change.consume()
                                    onDrag(amount.y)
                                }
                            },
                    ) {
                        Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 72.dp).alpha(if (dividerHidden) 0f else 1f).background(colors.border))
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = Endpoints.absolute(emoji.url),
                                contentDescription = emoji.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Text(
                                "${index + 1}. ${emoji.name}",
                                style = type.rowTitle,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 14.dp),
                            )
                            Box(
                                Modifier.size(48.dp).pointerInput(emoji.id) {
                                    detectDragGestures(
                                        onDragStart = { startDrag(emoji.id) },
                                        onDragEnd = { endDrag() },
                                        onDragCancel = { endDrag() },
                                    ) { change, amount ->
                                        change.consume()
                                        onDrag(amount.y)
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                KcIcon(KcIcons.gripVertical, tint = if (active) colors.text else colors.subtle, size = 20.dp, contentDescription = "拖动排序")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 从我的收藏表情里挑一张 */
@Composable
private fun FavoritePickSheet(graph: AppGraph, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val colors = KcTheme.colors
    var urls by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { favoriteStickerUrls(graph) }.onSuccess { urls = it }.onFailure { failed = it.userMessage() }
    }
    KcSheet(onDismiss = onDismiss, title = "从收藏表情添加", maxHeight = 520.dp) {
        val list = urls
        when {
            failed != null -> StateText(failed!!, error = true)
            list == null -> StateText("加载中…")
            list.isEmpty() -> StateText("还没有收藏表情。长按聊天里的图片可以添加到表情。")
            else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(max = 440.dp).navigationBarsPadding().padding(horizontal = 12.dp)) {
                items(list) { url ->
                    val press = rememberInteraction()
                    AsyncImage(
                        model = Endpoints.absolute(url),
                        contentDescription = "收藏表情",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.padding(4.dp).aspectRatio(1f).pressScale(press, 0.92f).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted)
                            .clickable(interactionSource = press, indication = null) { onPick(url) },
                    )
                }
            }
        }
    }
}
