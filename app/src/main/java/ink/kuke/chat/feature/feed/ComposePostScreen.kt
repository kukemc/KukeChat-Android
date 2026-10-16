package ink.kuke.chat.feature.feed

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

private const val DRAFT_FILE = "kukechat_post_draft"

private fun loadDraft(context: Context, userId: Long): Pair<String, String> {
    val prefs = context.getSharedPreferences(DRAFT_FILE, Context.MODE_PRIVATE)
    return (prefs.getString("content_$userId", "") ?: "") to (prefs.getString("visibility_$userId", "public") ?: "public")
}

private fun saveDraft(context: Context, userId: Long, content: String, visibility: String) {
    val prefs = context.getSharedPreferences(DRAFT_FILE, Context.MODE_PRIVATE).edit()
    if (content.isBlank() && visibility == "public") prefs.remove("content_$userId").remove("visibility_$userId")
    else prefs.putString("content_$userId", content).putString("visibility_$userId", visibility)
    prefs.apply()
}

/** 打开发表页前预填的正文（话题页「发动态」用）；路由不带参数，用这个进程内的交接位，取一次就清掉 */
object ComposePostPrefill {
    private var text: String? = null
    fun set(value: String) { text = value }
    fun take(): String? = text.also { text = null }
}

/**
 * 发表动态（设计稿 P13）：全屏页，「发布」在右上角，无内容时置灰。
 * 文字 ≤ 2000、图片最多 9 张（上传前压缩到 3MB 内）、# / @ 就地联想（末尾有「创建新话题」）、可见范围三选一。
 * 文字和可见范围会存成草稿，下次打开还在。
 */
@Composable
fun ComposePostScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { PostsApi(graph.api) }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    val userId = me?.id ?: 0L

    val draft = remember {
        val saved = loadDraft(context, userId)
        // 从话题页进来：在草稿前面补上「#话题 」（草稿里已经有就不重复加）
        val prefill = ComposePostPrefill.take()
        if (prefill == null || saved.first.contains(prefill.trim(), ignoreCase = true)) saved
        else (prefill + saved.first) to saved.second
    }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.first, TextRange(draft.first.length))) }
    var visibility by rememberSaveable { mutableStateOf(draft.second) }
    val images = remember { mutableStateListOf<Uri>() }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var publishing by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(false) }
    val uploaded = remember { HashMap<Uri, String>() }
    var friends by remember { mutableStateOf<List<User>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        friends = runCatching { api.friends() }.getOrDefault(emptyList())
    }
    // 离开页面时把没发出去的文字存成草稿
    DisposableEffect(Unit) {
        onDispose { if (!published) saveDraft(context, userId, field.text, visibility) }
    }

    fun addImages(picked: List<Uri>) {
        val room = POST_MAX_IMAGES - images.size
        if (room <= 0) { error = "最多只能上传 $POST_MAX_IMAGES 张图片"; return }
        if (picked.size > room) error = "最多只能上传 $POST_MAX_IMAGES 张图片，多选的已忽略"
        images.addAll(picked.filter { it !in images }.take(room))
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(POST_MAX_IMAGES)) { addImages(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> addImages(listOf(uri)) } }
    fun launchPicker() {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (POST_MAX_IMAGES - images.size > 1) pickMany.launch(request) else pickOne.launch(request)
    }

    val text = field.text
    val canPublish = (text.isNotBlank() || images.isNotEmpty()) && text.length <= POST_MAX_LENGTH && !publishing

    fun publish() {
        if (!canPublish) return
        publishing = true
        error = null
        scope.launch {
            try {
                val urls = ArrayList<String>()
                images.forEachIndexed { index, uri ->
                    progress = "正在上传图片 ${index + 1}/${images.size}"
                    urls += uploaded.getOrPut(uri) { api.uploadImage(context, uri) }
                }
                progress = "正在发布…"
                val content = text.trim()
                api.create(content, visibility, urls, extractMentionIds(content, friends), extractTopicNames(content))
                published = true
                saveDraft(context, userId, "", "public")
                nav.back()
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                publishing = false
                progress = null
            }
        }
    }

    val trigger = inputTrigger(text, field.selection.end)
    val topics = rememberTopicSuggestions(api, trigger)

    KcPage(
        title = "发表动态",
        onBack = { if (!publishing) nav.back() },
        actions = {
            Text(
                if (publishing) "发布中" else "发布",
                style = type.rowTitle,
                color = if (canPublish) colors.onFill else colors.subtle,
                modifier = Modifier.padding(end = 12.dp).tap(enabled = canPublish, scale = 0.94f) { publish() }
                    .clip(RoundedCornerShape(16.dp)).background(if (canPublish) colors.fill else colors.panelMuted)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().background(colors.panel).imePadding()) {
          Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 输入区
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                if (text.isEmpty()) Text("分享点什么…", style = type.body, color = colors.subtle)
                BasicTextField(
                    value = field,
                    onValueChange = { if (it.text.length <= POST_MAX_LENGTH) field = it },
                    enabled = !publishing,
                    textStyle = type.body.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (trigger != null) {
                SuggestionPopup(
                    trigger = trigger,
                    friends = friends,
                    topics = topics,
                    onPickUser = { user ->
                        val (newText, cursor) = replaceTrigger(text, field.selection.end, "@${user.displayName} ")
                        field = TextFieldValue(newText, TextRange(cursor))
                    },
                    onPickTopic = { name ->
                        val (newText, cursor) = replaceTrigger(text, field.selection.end, "#$name ")
                        field = TextFieldValue(newText, TextRange(cursor))
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // 图片：横向缩略图 + 末尾「图片 n/9」添加块
            if (images.isNotEmpty()) {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.toList().forEach { uri ->
                        Box(Modifier.size(116.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted)) {
                            AsyncImage(uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).tap(enabled = !publishing, scale = 0.9f) { images.remove(uri); uploaded.remove(uri) }
                                    .clip(CircleShape).background(colors.scrim),
                                contentAlignment = Alignment.Center,
                            ) { KcIcon(KcIcons.close, tint = androidx.compose.ui.graphics.Color.White, size = 14.dp) }
                        }
                    }
                    if (images.size < POST_MAX_IMAGES) {
                        Column(
                            Modifier.size(116.dp).tap(enabled = !publishing, scale = 0.96f) { launchPicker() }.clip(RoundedCornerShape(10.dp)).background(colors.panelMuted),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            KcIcon(KcIcons.imagePlus, tint = colors.muted, size = 26.dp)
                            Text("图片 ${images.size}/$POST_MAX_IMAGES", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // 审核提示（强调色左线说明块）
            Row(Modifier.padding(top = 18.dp).fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxSize().clip(RoundedCornerShape(2.dp)).background(colors.accent))
                Text(
                    "输入 # 添加话题，输入 @ 提醒好友；图片可以直接粘贴。发布后会先进入审核。",
                    style = type.secondary, color = colors.muted, modifier = Modifier.padding(start = 10.dp),
                )
            }
            progress?.let { Text(it, style = type.secondary, color = colors.accent, modifier = Modifier.padding(top = 8.dp)) }
            error?.let { Text(it, style = type.secondary, color = colors.danger, modifier = Modifier.padding(top = 8.dp)) }
          }

          // 吸底：可见范围三选一 + 工具行
          Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
          Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("public", "friends", "private").forEach { value ->
                    val on = visibility == value
                    Text(
                        visibilityLabel(value), style = type.secondary,
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.W700 else androidx.compose.ui.text.font.FontWeight.W600,
                        color = if (on) colors.accent else colors.muted,
                        modifier = Modifier.tap(enabled = !publishing, scale = 0.96f) { visibility = value }
                            .clip(RoundedCornerShape(16.dp)).background(if (on) colors.accentSoft else colors.active)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                fun insert(token: String) {
                    val start = field.selection.min
                    val t = field.text.substring(0, start) + token + field.text.substring(field.selection.max)
                    if (t.length <= POST_MAX_LENGTH) field = TextFieldValue(t, TextRange(start + token.length))
                }
                listOf<Pair<androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>(
                    KcIcons.image to { launchPicker() },
                    KcIcons.hash to { insert("#") },
                    KcIcons.atSign to { insert("@") },
                ).forEach { (icon, action) ->
                    Box(Modifier.size(44.dp).tap(enabled = !publishing, scale = 0.9f, onClick = action), contentAlignment = Alignment.Center) {
                        KcIcon(icon, tint = colors.muted, size = 22.dp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${text.length} / $POST_MAX_LENGTH",
                    style = type.caption,
                    color = if (text.length >= POST_MAX_LENGTH) colors.danger else colors.subtle,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
          }
        }
    }
}
