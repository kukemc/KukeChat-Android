package ink.kuke.chat.feature.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ink.kuke.chat.core.dsl.DslDiagnostic
import ink.kuke.chat.core.dsl.DslRenderResult
import ink.kuke.chat.core.dsl.DslRenderer
import ink.kuke.chat.core.dsl.DslSegment
import ink.kuke.chat.core.dsl.DslText
import ink.kuke.chat.core.model.Message
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 聊天里的积木（Gandi 3.0 DSL）：消息里的 ```gandi-dsl 围栏（或整条就是积木文本）画成真正的 Scratch / Gandi 积木。
 * 识别规则和电脑版一致（core/dsl/DslText.kt），画图交给 core/dsl/DslRenderer.kt 的隐藏 WebView。
 * 契约见 KukeChat-Client/docs/gandi-dsl-blocks.md。
 */

/** 卡片里积木图的限高：更长的脚本点「展开全部」 */
private val DSL_COLLAPSED_HEIGHT = 320.dp

/** 输入框积木预览停手多久再画（同电脑版 300 ms，手机上打字慢一点，取 350） */
private const val DSL_PREVIEW_DEBOUNCE_MS = 350L

/** 渲染倍率：按设备密度画，3 倍以上看不出差别，只多占内存 */
@Composable
private fun dslScale(): Float = LocalDensity.current.density.coerceIn(1f, 3f)

/**
 * 这条消息要不要按积木画：有积木围栏就切段；没有围栏但像积木的整条消息，等渲染页判定（判定前、判定不是都返回 null，照普通文字画）。
 * 只看文字消息，机器人富文本、卡片在前面已经分走了。
 */
@Composable
internal fun rememberDslSegments(message: Message): List<DslSegment>? {
    val content = message.content
    val fenced = remember(content) { if (message.type == "text") DslText.split(content) else null }
    if (fenced != null) return fenced
    val candidate = remember(content) { message.type == "text" && message.metadata?.get("images") == null && DslText.looksLikeDsl(content) }
    if (!candidate) return null
    val detected by produceState<Boolean?>(null, content) { value = DslRenderer.detect(content) }
    return if (detected == true) remember(content) { listOf(DslSegment.Block(content.trim())) } else null
}

/** 带积木的消息正文：围栏外的文字照常（@、链接都在），每段积木一张卡片 */
@Composable
internal fun DslMessageParts(message: Message, segments: List<DslSegment>, mine: Boolean, textColor: Color, rich: (String) -> AnnotatedString, fontFamily: FontFamily?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is DslSegment.Text -> Text(remember(segment.text, rich) { rich(segment.text) }, style = KcTheme.type.body, color = textColor, fontFamily = fontFamily)
                is DslSegment.Block -> DslBlockCard(segment.code, mine)
            }
        }
    }
}

/**
 * 积木卡片：默认显示积木图（限高 320dp，可展开），可切到「代码」；复制代码、保存图片。
 * 编译出错时不画半截图，显示带行号的代码（出错行标红）和逐条报错（行号、原因、改法）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DslBlockCard(code: String, mine: Boolean, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = colors.isDark
    val scale = dslScale()
    var retry by remember(code) { mutableStateOf(0) }
    val result by produceState(DslRenderer.cached(code, dark, scale), code, dark, scale, retry) {
        if (value == null || value?.renderFailed == true) {
            value = null
            value = DslRenderer.render(code, dark, scale)
        }
    }
    var showCode by rememberSaveable(code) { mutableStateOf(false) }
    var expanded by rememberSaveable(code) { mutableStateOf(false) }
    val current = result
    val compileError = current != null && !current.ok && !current.renderFailed
    val longPress = LocalMessageLongPress.current

    var pendingSave by remember { mutableStateOf<Bitmap?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bitmap = pendingSave
        pendingSave = null
        if (granted && bitmap != null) scope.launch { saveDslImage(context, bitmap, colors.panel) }
        else if (!granted) toast(context, "没有存储权限，存不了图片")
    }

    Column(
        modifier
            .fillMaxWidth()
            .widthIn(min = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        // 标题行：积木图标 · 状态 · 积木 / 代码 切换 · 复制 · 保存
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            KcIcon(KcIcons.blocks, tint = if (compileError) colors.danger else colors.muted, size = 14.dp)
            Text(
                if (compileError) "积木文本有错误" else "积木",
                style = type.caption,
                color = if (compileError) colors.danger else colors.muted,
                modifier = Modifier.padding(start = 5.dp).weight(1f),
            )
            if (current?.ok == true) {
                DslSegmented(showCode) { showCode = it }
            }
            DslIconButton(KcIcons.copy, "复制代码") {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("积木代码", code))
                toast(context, "已复制积木代码")
            }
            val bitmap = current?.bitmap
            if (current?.ok == true && bitmap != null) {
                DslIconButton(KcIcons.download, "保存图片") {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingSave = bitmap
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else scope.launch { saveDslImage(context, bitmap, colors.panel) }
                }
            }
        }
        when {
            current == null -> Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SendSpinner(14.dp, colors.muted, null)
                Text("正在生成积木…", style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
            }
            current.ok && current.bitmap != null && !showCode -> {
                val bitmap = current.bitmap
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    // 原大小放得下就原大小，放不下按宽度缩小
                    val natural = current.width.dp
                    val shownWidth: Dp = if (natural < maxWidth) natural else maxWidth
                    val shownHeight: Dp = shownWidth * (current.height / current.width)
                    val tall = shownHeight > DSL_COLLAPSED_HEIGHT
                    Column {
                        Box(
                            Modifier
                                .then(if (tall && !expanded) Modifier.height(DSL_COLLAPSED_HEIGHT) else Modifier)
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(onLongClick = longPress, onClick = { if (tall) expanded = !expanded }),
                        ) {
                            Image(
                                image,
                                contentDescription = "积木",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.width(shownWidth).height(shownHeight),
                            )
                            if (tall && !expanded) {
                                Box(
                                    Modifier.align(Alignment.BottomCenter).width(shownWidth).height(48.dp)
                                        .background(Brush.verticalGradient(listOf(colors.panel.copy(alpha = 0f), colors.panel))),
                                )
                            }
                        }
                        if (tall) {
                            Text(
                                if (expanded) "收起" else "展开全部",
                                style = type.caption,
                                color = colors.accent,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
                                    .clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            else -> {
                if (current.renderFailed) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (current.diagnostics.any { it.code == "E_TIMEOUT" }) "积木生成超时，先显示代码" else "这里画不了积木，先显示代码",
                            style = type.caption,
                            color = colors.muted,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text("重试", style = type.caption, color = colors.accent, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).clickable { retry++ }.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
                DslCodeView(code, if (compileError) current.diagnostics else emptyList(), maxHeight = if (expanded) Dp.Unspecified else DSL_COLLAPSED_HEIGHT)
                if (compileError) DslDiagnosticList(current.diagnostics, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
            }
        }
        DslAbout()
    }
}

/** 卡片底部的说明：这是什么、怎么用、去哪看文档（同电脑版 DslAbout）。 */
@Composable
private fun DslAbout() {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(colors.border, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text("这是 Gandi 3.0 的 DSL 积木语法，可以直接粘贴进 Gandi 编辑器，也能在编辑器里把积木复制为 DSL。", style = type.caption, color = colors.subtle)
        DslDocsLink(Modifier.padding(top = 2.dp), "查看语法文档")
    }
}

/** 打开 Gandi 3.0 DSL 语法文档 */
@Composable
private fun DslDocsLink(modifier: Modifier = Modifier, label: String = "语法文档") {
    val colors = KcTheme.colors
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    Text(
        label,
        style = KcTheme.type.caption,
        color = colors.accent,
        modifier = modifier.clip(RoundedCornerShape(6.dp))
            .clickable { runCatching { uri.openUri(DslText.DOCS_URL) }.onFailure { toast(context, "打不开链接") } }
            .padding(vertical = 2.dp),
    )
}

/** 积木 / 代码 两段切换 */
@Composable
private fun DslSegmented(showCode: Boolean, onChange: (Boolean) -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(colors.panelMuted).padding(2.dp)) {
        listOf(false to "积木", true to "代码").forEach { (code, label) ->
            val on = showCode == code
            Text(
                label,
                style = KcTheme.type.caption,
                color = if (on) colors.text else colors.muted,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) colors.panel else Color.Transparent)
                    .clickable { onChange(code) }.padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun DslIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KcIcon(icon, tint = KcTheme.colors.muted, size = 16.dp, contentDescription = label)
    }
}

/** 带行号的代码（等宽、可选中、横向可滚），出错的行标红 */
@Composable
internal fun DslCodeView(code: String, diagnostics: List<DslDiagnostic>, modifier: Modifier = Modifier, maxHeight: Dp = Dp.Unspecified) {
    val colors = KcTheme.colors
    val errorLines = remember(diagnostics) { diagnostics.mapNotNull { it.line }.toSet() }
    val text = remember(code, errorLines, colors) {
        val lines = code.split('\n')
        val gutter = lines.size.toString().length
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                val number = index + 1
                val error = number in errorLines
                withStyle(SpanStyle(color = if (error) colors.danger else colors.subtle)) { append(number.toString().padStart(gutter)); append("  ") }
                if (error) withStyle(SpanStyle(color = colors.danger, background = colors.danger.copy(alpha = 0.10f))) { append(line) } else append(line)
                if (index < lines.lastIndex) append('\n')
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.panelMuted)
            .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = colors.text, softWrap = false)
        }
    }
}

/** 逐条报错：位置 · 原因，下面一行灰字是改法 */
@Composable
internal fun DslDiagnosticList(diagnostics: List<DslDiagnostic>, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        diagnostics.take(20).forEach { item ->
            Column {
                Row {
                    Text(
                        item.line?.let { line -> "第 $line 行" + (item.column?.let { ":$it" } ?: "") } ?: "整段",
                        style = KcTheme.type.caption,
                        color = colors.danger,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(item.message, style = KcTheme.type.caption, color = colors.text)
                }
                item.fix?.takeIf { it.isNotBlank() }?.let { Text("改法：$it", style = KcTheme.type.caption, color = colors.muted) }
            }
        }
        if (diagnostics.size > 20) Text("还有 ${diagnostics.size - 20} 条", style = KcTheme.type.caption, color = colors.subtle)
    }
}

/**
 * 存到相册（图片/KukeChat）：渲染出来的 PNG 是透明底，存之前铺一层卡片底色、留点边，在相册里看着像一张图。
 * Android 10+ 走 MediaStore 不要权限；9 及以下要 WRITE_EXTERNAL_STORAGE（调用方先申请）。
 */
private suspend fun saveDslImage(context: Context, bitmap: Bitmap, background: Color) {
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            val pad = (bitmap.width / 40).coerceIn(16, 48)
            val out = Bitmap.createBitmap(bitmap.width + pad * 2, bitmap.height + pad * 2, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(background.toArgb())
                drawBitmap(bitmap, pad.toFloat(), pad.toFloat(), null)
            }
            val name = "KukeChat 积木 ${System.currentTimeMillis()}.png"
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KukeChat")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
                try {
                    resolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("open failed")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KukeChat").apply { mkdirs() }
                val file = java.io.File(dir, name)
                file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            }
            out.recycle()
        }.isSuccess
    }
    toast(context, if (ok) "已保存到相册" else "保存失败")
}

/** 输入框上方积木预览的结果：对应哪段文字、渲染页的结果。发送时看它决定要不要包成积木围栏。 */
internal class ComposerDslPreview(val text: String, val result: DslRenderResult?)

/**
 * 输入框上方的积木预览：输入框里写着（或粘贴了）积木文本时出现，停手 350 ms 再画；编得过显示积木，编不过列出错误。
 * 点 × 这段就按普通文字发（改了文字后重新出现）。规则同电脑版 DslComposerPreview.tsx。
 */
@Composable
internal fun DslComposerPreview(preview: ComposerDslPreview, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val current = preview.result
    val title = when {
        current == null -> "正在识别积木…"
        current.ok && current.bitmap != null -> "积木预览 · 发出去对方看到的就是积木"
        current.renderFailed -> "识别为积木（这里画不了预览，对方能看到积木）"
        else -> "积木文本有错，改好之前发出去是普通文字"
    }
    val error = current != null && !current.ok && !current.renderFailed
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp).fillMaxWidth()
            .shadow(2.dp, shape).background(colors.panel, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current == null) SendSpinner(12.dp, colors.muted, null) else KcIcon(KcIcons.blocks, tint = if (error) colors.danger else colors.muted, size = 14.dp)
            Text(title, style = type.caption, color = if (error) colors.danger else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            DslDocsLink(Modifier.padding(horizontal = 4.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.close, tint = colors.subtle, size = 14.dp, contentDescription = "不当积木，按普通文字发")
            }
        }
        if (current != null && (current.ok && current.bitmap != null || error)) {
            Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                if (current.ok && current.bitmap != null) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val natural = current.width.dp
                        val w = if (natural < maxWidth) natural else maxWidth
                        Image(
                            remember(current.bitmap) { current.bitmap.asImageBitmap() },
                            contentDescription = "积木预览",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.width(w).height(w * (current.height / current.width)),
                        )
                    }
                } else DslDiagnosticList(current.diagnostics)
            }
        }
    }
}

/**
 * 输入框文字对应的积木预览：不像积木 → null；像 → 停手 [DSL_PREVIEW_DEBOUNCE_MS] 再交给渲染页画（结果里的 text 是画的那一版）。
 */
@Composable
internal fun rememberComposerDslPreview(text: String, enabled: Boolean): ComposerDslPreview? {
    val dark = KcTheme.colors.isDark
    val scale = dslScale()
    val candidate = enabled && DslText.composerCandidate(text)
    var preview by remember { mutableStateOf<ComposerDslPreview?>(null) }
    LaunchedEffect(text, candidate, dark, scale) {
        if (!candidate) {
            preview = null
            return@LaunchedEffect
        }
        delay(DSL_PREVIEW_DEBOUNCE_MS)
        preview = ComposerDslPreview(text, DslRenderer.render(text, dark, scale))
    }
    // 打字时先留着上一次的图（不闪）；发送时按 text 对得上才算数
    return if (candidate) preview ?: ComposerDslPreview(text, null) else null
}

/**
 * 发送前：整条消息被渲染页认作积木（[DslRenderResult.detected]，且是这段文字的结果）→ 包成 ```gandi-dsl 围栏发，
 * 对方认不全的地方也一定显示成积木。已经有围栏、还没判完、包了超长 → 原样发。
 */
internal fun dslContentForSend(text: String, preview: ComposerDslPreview?): String {
    if (preview == null || preview.text.trim() != text.trim() || preview.result?.detected != true) return text
    if (DslText.split(text) != null) return text
    val wrapped = DslText.wrap(text.trim())
    return if (wrapped.length <= DslText.MAX_LENGTH) wrapped else text
}
