package ink.kuke.chat.feature.teamup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.feed.CcwCreation
import ink.kuke.chat.feature.feed.PostsApi
import ink.kuke.chat.feature.feed.decodeOne
import ink.kuke.chat.feature.feed.tap
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_IMAGES = 9
private const val MAX_WORKS = 6
private const val MAX_SKILLS = 8
private const val HEADLINE_MAX = 120
private const val INTRO_MAX = 2000
private const val CONTACT_MAX = 200

/** 上传中的一张图：本地选中的 uri + 上传结果（remoteUrl 有值才算好了），已有的图只有 remoteUrl */
private class StagedImage(val id: String, val uri: Uri?, val remoteUrl: String?) {
    var url by mutableStateOf(remoteUrl)
    var uploading by mutableStateOf(uri != null && remoteUrl == null)
    var error by mutableStateOf<String?>(null)
    val preview: Any? get() = uri ?: Endpoints.absolute(remoteUrl)
}

/** 作品链接解析出的预览；解析中 / 失败有各自的状态 */
private class WorkItem(val ref: CcwRef) {
    var preview by mutableStateOf<CcwCreation?>(null)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
}

/**
 * 编辑我的名片（PUT /teamup/profiles/me），全屏表单。
 * 代表作不单独存：保存时把作品链接拼到介绍末尾，打开时再从介绍里解析出来（和电脑版一致）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamupEditorScreen(graph: AppGraph, profile: TeamupProfile?, onClose: () -> Unit, onSaved: (TeamupProfile) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val posts = remember { PostsApi(graph.api) }

    var headline by remember { mutableStateOf(profile?.headline.orEmpty()) }
    var intro by remember { mutableStateOf(stripCcwLinks(profile?.intro.orEmpty())) }
    val skills = remember { mutableStateListOf<SkillItem>().apply { addAll(profile?.skills.orEmpty()) } }
    val lookingFor = remember { mutableStateListOf<String>().apply { addAll(profile?.lookingFor.orEmpty()) } }
    var contactNote by remember { mutableStateOf(profile?.contactNote.orEmpty()) }
    val images = remember {
        mutableStateListOf<StagedImage>().apply {
            profile?.imageUrls.orEmpty().forEachIndexed { index, url -> add(StagedImage("existing-$index", null, url)) }
        }
    }
    var background by remember { mutableStateOf(profile?.backgroundUrl?.let { StagedImage("existing-bg", null, it) }) }
    val works = remember { mutableStateListOf<WorkItem>() }
    var workInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun fetchPreview(work: WorkItem) {
        scope.launch {
            runCatching {
                val suffix = work.ref.accessKey?.let { "?accessKey=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                graph.api.get("/ccw/creations/${work.ref.oid}$suffix").decodeOne<CcwCreation>()
            }.onSuccess { work.preview = it }.onFailure { work.failed = true }
            work.loading = false
        }
    }
    // 打开已有名片时，把介绍末尾的作品链接还原成作品卡
    remember(profile?.id) {
        extractCcwRefs(profile?.intro.orEmpty(), MAX_WORKS).forEach { ref -> WorkItem(ref).also { works.add(it); fetchPreview(it) } }
        true
    }

    fun upload(item: StagedImage, uri: Uri, path: String = "/uploads/teamup-image") {
        scope.launch {
            runCatching { posts.uploadImage(context, uri, path) }
                .onSuccess { item.url = it; item.error = null }
                .onFailure { item.error = it.userMessage() }
            item.uploading = false
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        val room = MAX_IMAGES - images.size
        uris.take(room.coerceAtLeast(0)).forEach { uri ->
            val staged = StagedImage("staged-${System.nanoTime()}", uri, null)
            images.add(staged)
            upload(staged, uri)
        }
    }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val staged = StagedImage("bg-${System.nanoTime()}", uri, null)
            background = staged
            upload(staged, uri)
        }
    }

    val busyUploading = images.any { it.uploading } || background?.uploading == true
    val canSave = headline.isNotBlank() && !saving && !busyUploading && works.none { it.loading }

    fun save() {
        if (headline.isBlank()) { error = "请填写一句话招募标题"; return }
        if (busyUploading) { error = "图片还在上传中，请稍候"; return }
        saving = true
        error = null
        scope.launch {
            try {
                val links = works.filter { !it.failed }.map { ccwWorkUrl(it.ref) }
                val combined = (listOf(intro.trim()) + links).filter { it.isNotBlank() }.joinToString("\n").trim()
                val body = buildJsonObject {
                    put("headline", headline.trim())
                    put("intro", combined)
                    put("skills", buildJsonArray {
                        skills.forEach { add(buildJsonObject { put("skill", it.skill); put("level", it.level) }) }
                    })
                    put("looking_for", buildJsonArray { lookingFor.forEach { add(JsonPrimitive(it)) } })
                    put("image_urls", buildJsonArray { images.mapNotNull { it.url }.forEach { add(JsonPrimitive(it)) } })
                    put("background_url", background?.url?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("contact_note", contactNote.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("status", profile?.status ?: "recruiting")
                }
                val saved = KcJson.decodeFromJsonElement(TeamupProfile.serializer(), graph.api.put("/teamup/profiles/me", body))
                onSaved(saved)
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                saving = false
            }
        }
    }

    KcPage(
        title = if (profile == null) "发布组队名片" else "编辑组队名片",
        onBack = { if (!saving) onClose() },
        actions = {
            Text(
                if (saving) "保存中" else "保存",
                style = type.rowTitle,
                color = if (canSave) colors.onFill else colors.subtle,
                modifier = Modifier.padding(end = 12.dp).tap(enabled = canSave, scale = 0.94f) { save() }
                    .clip(RoundedCornerShape(16.dp)).background(if (canSave) colors.fill else colors.panelMuted).padding(horizontal = 16.dp, vertical = 7.dp),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Field("一句话招募", "${headline.length}/$HEADLINE_MAX") {
                TextBox(headline, { headline = it.take(HEADLINE_MAX) }, "例如：寻找擅长像素美术的搭档，一起做 Roguelike", minHeight = 44)
            }

            Field("我擅长（最多 $MAX_SKILLS 项，选中后可改熟练度）") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkillList.forEach { meta ->
                        val on = skills.any { it.skill == meta.key }
                        Chip(meta.label, on) {
                            if (on) skills.removeAll { it.skill == meta.key }
                            else if (skills.size < MAX_SKILLS) skills.add(SkillItem(meta.key, "skilled"))
                        }
                    }
                }
                skills.toList().forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(skillMeta(item.skill).label, style = type.secondary, color = colors.text, modifier = Modifier.weight(1f))
                        SkillLevels.forEach { level ->
                            val on = item.level == level
                            Text(
                                levelLabel(level), style = type.caption, color = if (on) colors.accent else colors.muted,
                                modifier = Modifier.padding(start = 6.dp).tap(scale = 0.94f) {
                                    val index = skills.indexOfFirst { it.skill == item.skill }
                                    if (index >= 0) skills[index] = item.copy(level = level)
                                }.clip(RoundedCornerShape(10.dp)).background(if (on) colors.accentSoft else colors.panelMuted).padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            Field("想找的队友") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkillList.forEach { meta ->
                        val on = meta.key in lookingFor
                        Chip(meta.label, on) {
                            if (on) lookingFor.remove(meta.key) else if (lookingFor.size < MAX_SKILLS) lookingFor.add(meta.key)
                        }
                    }
                }
            }

            Field("个人介绍", "${intro.length}/$INTRO_MAX") {
                TextBox(intro, { intro = it.take(INTRO_MAX) }, "介绍你的经验、风格、可投入时间和期待的合作方式。支持 ## 标题、- 列表、**加粗** 等 Markdown。", minHeight = 140)
            }

            Field("封面背景") {
                val bg = background
                if (bg == null) {
                    Row(
                        Modifier.fillMaxWidth().height(72.dp).tap(scale = 0.98f) { pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            .clip(RoundedCornerShape(12.dp)).background(colors.panel),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KcIcon(KcIcons.imagePlus, tint = colors.muted, size = 22.dp)
                        Text("选择封面图（可选）", style = type.secondary, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted)) {
                        AsyncImage(bg.preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        UploadOverlay(bg)
                        RemoveDot(Modifier.align(Alignment.TopEnd)) { background = null }
                    }
                }
            }

            Field("作品配图", "${images.size}/$MAX_IMAGES") {
                val tiles: List<StagedImage?> = images.toList() + if (images.size < MAX_IMAGES) listOf(null) else emptyList()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { item ->
                                if (item == null) {
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f).tap(scale = 0.96f) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                            .clip(RoundedCornerShape(10.dp)).background(colors.panel),
                                        contentAlignment = Alignment.Center,
                                    ) { KcIcon(KcIcons.imagePlus, tint = colors.muted, size = 26.dp) }
                                } else {
                                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted)) {
                                        AsyncImage(item.preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                        UploadOverlay(item)
                                        RemoveDot(Modifier.align(Alignment.TopEnd)) { images.remove(item) }
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Field("共创代表作（CCW 作品链接，最多 $MAX_WORKS 个）", "${works.size}/$MAX_WORKS") {
                works.forEach { work ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(colors.panelMuted)) {
                            work.preview?.coverUrl?.let { AsyncImage(Endpoints.absolute(it), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(
                                when {
                                    work.loading -> "解析中…"
                                    work.failed -> "解析失败（${work.ref.oid.take(8)}…），保存时会忽略"
                                    else -> work.preview?.title?.ifBlank { null } ?: "CCW 作品"
                                },
                                style = type.secondary, color = if (work.failed) colors.danger else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            work.preview?.authorName?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.caption, color = colors.muted) }
                        }
                        Box(Modifier.size(32.dp).tap(scale = 0.9f) { works.remove(work) }, contentAlignment = Alignment.Center) { KcIcon(KcIcons.close, tint = colors.muted, size = 18.dp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TextBox(workInput, { workInput = it }, "粘贴 ccw.site/detail/… 作品链接", minHeight = 44) }
                    Text(
                        "添加", style = type.rowTitle, color = colors.onFill,
                        modifier = Modifier.padding(start = 8.dp).tap(scale = 0.95f) {
                            val ref = extractCcwRefs(workInput, 1).firstOrNull()
                            when {
                                ref == null -> error = "请输入有效的 CCW 作品链接"
                                works.any { it.ref.oid == ref.oid } -> workInput = ""
                                works.size >= MAX_WORKS -> error = "最多添加 $MAX_WORKS 个代表作"
                                else -> { error = null; WorkItem(ref).also { works.add(it); fetchPreview(it) }; workInput = "" }
                            }
                        }.clip(RoundedCornerShape(12.dp)).background(colors.fill).padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                }
            }

            Field("联系备注（可选）", "${contactNote.length}/$CONTACT_MAX") {
                TextBox(contactNote, { contactNote = it.take(CONTACT_MAX) }, "例如：优先加好友私聊，或说明你的时区 / 在线时间", minHeight = 44)
            }

            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.accent))
                Text("名片提交后会先进入审核，通过后才会出现在组队广场。", style = type.secondary, color = colors.muted, modifier = Modifier.padding(start = 10.dp))
            }

            error?.let { Text(it, style = type.secondary, color = colors.danger) }
        }
    }
}

@Composable
private fun Field(label: String, counter: String? = null, content: @Composable () -> Unit) {
    val colors = KcTheme.colors
    Column {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = KcTheme.type.group, color = colors.subtle, modifier = Modifier.weight(1f))
            if (counter != null) Text(counter, style = KcTheme.type.caption, color = colors.subtle)
        }
        content()
    }
}

@Composable
private fun TextBox(value: String, onChange: (String) -> Unit, hint: String, minHeight: Int) {
    val colors = KcTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(horizontal = 14.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text(hint, style = KcTheme.type.body, color = colors.subtle)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = KcTheme.type.body.copy(color = colors.text) as TextStyle,
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Text(
        label, style = KcTheme.type.secondary, color = if (on) colors.onFill else colors.text,
        modifier = Modifier.tap(scale = 0.95f, onClick = onClick).clip(RoundedCornerShape(16.dp)).background(if (on) colors.fill else colors.panel).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun UploadOverlay(item: StagedImage) {
    if (item.uploading) {
        Box(Modifier.fillMaxSize().background(Color(0x55000000)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
        }
    } else if (item.error != null) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Text(item.error.orEmpty(), color = Color.White, style = KcTheme.type.caption, modifier = Modifier.padding(6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RemoveDot(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(4.dp).size(24.dp).tap(scale = 0.9f, onClick = onClick).clip(CircleShape).background(KcTheme.colors.scrim),
        contentAlignment = Alignment.Center,
    ) { KcIcon(KcIcons.close, tint = Color.White, size = 14.dp) }
}
