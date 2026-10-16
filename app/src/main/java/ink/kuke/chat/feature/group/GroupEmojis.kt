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
    /** 服务端上限 512KB；超了本地先压一遍 */
    private const val MAX_BYTES = 512 * 1024
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

    /** 选一张本地图片上传到消息图片通道，返回地址。≤512KB 的原样传（保留 GIF 动图 / 透明底），超了缩到 320px 再压 */
    suspend fun uploadLocal(graph: AppGraph, context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/png"
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这张图片")
        val (bytes, type, ext) = if (raw.size <= MAX_BYTES && mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
            Triple(raw, mime, mime.substringAfter('/').replace("jpeg", "jpg"))
        } else {
            if (mime == "image/gif") error("动图不能超过 512KB")
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

@Composable
private fun EmojiCell(emoji: GroupEmoji, compact: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Column(
        Modifier.padding(4.dp).pressScale(press, 0.92f).clip(RoundedCornerShape(10.dp)).clickable(interactionSource = press, indication = null, onClick = onClick).padding(vertical = 4.dp),
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
    data class Name(val imageUrl: String?, val localUri: Uri?, val initial: String) : EmojiDialog
    data object Favorites : EmojiDialog
    data class Actions(val emoji: GroupEmoji) : EmojiDialog
    data class Rename(val emoji: GroupEmoji) : EmojiDialog
    data class Delete(val emoji: GroupEmoji) : EmojiDialog
}

/**
 * 群表情管理页：全体成员能看，群主和管理员可以从相册 / 收藏表情添加、改名、删除（上限 [GroupEmojis.MAX] 个）。
 * 叠在群资料页上，系统返回关闭。
 */
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
    val full = emojis.size >= GroupEmojis.MAX
    BackHandler(enabled = dialog == null, onBack = onBack)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) dialog = EmojiDialog.Name(null, uri, "")
    }

    fun run(block: suspend () -> Unit, success: String) {
        busy = true
        scope.launch {
            runCatching { block() }.onSuccess { toast(context, success); dialog = null }.onFailure { toast(context, it.userMessage()) }
            busy = false
        }
    }

    KcPage(title = "群表情", subtitle = "${emojis.size} / ${GroupEmojis.MAX}", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            if (canManage) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddTile(KcIcons.imagePlus, "从相册添加", enabled = !full && !busy, modifier = Modifier.weight(1f)) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    AddTile(KcIcons.heart, "从收藏添加", enabled = !full && !busy, modifier = Modifier.weight(1f)) { dialog = EmojiDialog.Favorites }
                }
                if (full) Text("已达上限 ${GroupEmojis.MAX} 个，删掉一些才能继续添加", style = type.caption, color = colors.warn, modifier = Modifier.padding(horizontal = 16.dp))
            }
            when {
                list == null -> StateText("加载中…")
                emojis.isEmpty() -> StateText(if (canManage) "还没有群表情，点上面添加第一个" else "本群还没有表情")
                else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(emojis, key = { it.id }) { emoji ->
                        EmojiCell(emoji, compact = false) { if (canManage) dialog = EmojiDialog.Actions(emoji) }
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        is EmojiDialog.Name -> NewEmojiSheet(current, busy, onDismiss = { if (!busy) dialog = null }) { name ->
            val clean = GroupEmojis.cleanName(name)
            when {
                clean.isEmpty() -> toast(context, "表情名称不能为空")
                emojis.any { it.name == clean } -> toast(context, "这个群里已经有同名的表情")
                else -> run({
                    val url = current.imageUrl ?: GroupEmojis.uploadLocal(graph, context, current.localUri ?: error("没有选图片"))
                    GroupEmojis.create(graph, conversationId, clean, url)
                }, "已添加「$clean」")
            }
        }
        EmojiDialog.Favorites -> FavoritePickSheet(graph, onDismiss = { dialog = null }) { url -> dialog = EmojiDialog.Name(url, null, "") }
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
        KcIcon(icon, tint = if (enabled) colors.accent else colors.subtle, size = 18.dp)
        Text(label, style = KcTheme.type.secondary, color = if (enabled) colors.text else colors.subtle, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 选好图片之后：预览 + 起名字 */
@Composable
private fun NewEmojiSheet(draft: EmojiDialog.Name, busy: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val colors = KcTheme.colors
    var name by remember(draft) { mutableStateOf(draft.initial) }
    KcSheet(onDismiss = onDismiss, title = "添加群表情") {
        SheetForm {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = draft.localUri ?: Endpoints.absolute(draft.imageUrl),
                    contentDescription = "预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted),
                )
            }
            KcTextField(name, { name = it.take(GroupEmojis.NAME_MAX) }, "表情名称，最多 ${GroupEmojis.NAME_MAX} 个字")
            KcButton(if (busy) "上传中…" else "添加", { onSubmit(name) }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth())
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
