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

/** 编辑器预览停手多久再画（同电脑版 300 ms，手机上打字慢一点，取 350） */
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
    }
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

private const val DSL_TEMPLATE = """when.flagclicked(() => {
  looks.say("你好！");
  repeat(10, () => {
    motion.movesteps(10);
    motion.turnright(15);
  });
});"""

/**
 * 输入框工具面板里的「积木」：写积木文本、实时预览（停手 350 ms 再画）、报错逐条列出，
 * 发出去的是一个 ```gandi-dsl 围栏。消息上限 2000 字（含围栏），超了不能发。
 */
@Composable
internal fun DslEditorSheet(controller: ChatController, onDismiss: () -> Unit, onSent: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    val dark = colors.isDark
    val scale = dslScale()
    val length = if (code.isBlank()) 0 else DslText.wrap(code).length
    val overLimit = length > DslText.MAX_LENGTH
    var preview by remember { mutableStateOf<DslRenderResult?>(null) }
    var previewing by remember { mutableStateOf(false) }
    LaunchedEffect(code, dark, scale) {
        if (code.isBlank()) {
            preview = null
            previewing = false
            return@LaunchedEffect
        }
        previewing = true
        delay(DSL_PREVIEW_DEBOUNCE_MS)
        preview = DslRenderer.render(code, dark, scale)
        previewing = false
    }

    KcSheet(onDismiss = onDismiss, title = "发送积木", maxHeight = 720.dp) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("积木文本（Gandi 3.0 DSL）", style = type.secondary, color = colors.muted, modifier = Modifier.weight(1f))
                Text("$length/${DslText.MAX_LENGTH}", style = type.caption, color = if (overLimit) colors.danger else colors.subtle)
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 220.dp)
                    .clip(RoundedCornerShape(12.dp)).background(colors.panelMuted)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val codeStyle = type.body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
                if (code.isEmpty()) Text(DSL_TEMPLATE, style = codeStyle, color = colors.subtle)
                BasicTextField(
                    value = code,
                    onValueChange = { code = it },
                    textStyle = codeStyle.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.fill),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (code.isEmpty()) {
                Text("用模板开始", style = type.caption, color = colors.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { code = DSL_TEMPLATE }.padding(vertical = 2.dp))
            }
            Text("预览", style = type.secondary, color = colors.muted)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 260.dp)
                    .clip(RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                val current = preview
                when {
                    code.isBlank() -> Text("在上面写积木文本，这里实时显示积木", style = type.caption, color = colors.subtle, modifier = Modifier.align(Alignment.Center))
                    current == null -> Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                        SendSpinner(14.dp, colors.muted, null)
                        Text("正在生成积木…", style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
                    }
                    current.ok && current.bitmap != null -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val natural = current.width.dp
                        val w = if (natural < maxWidth) natural else maxWidth
                        Image(
                            remember(current.bitmap) { current.bitmap.asImageBitmap() },
                            contentDescription = "积木预览",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.width(w).height(w * (current.height / current.width)),
                        )
                    }
                    current.renderFailed -> Text("这里画不了积木，发出去以后对方在能画积木的地方会看到积木", style = type.caption, color = colors.muted)
                    else -> DslDiagnosticList(current.diagnostics)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KcButton("取消", onDismiss, modifier = Modifier.weight(1f), primary = false)
                KcButton(
                    "发送",
                    {
                        if (controller.sendText(DslText.wrap(code))) {
                            onDismiss()
                            onSent()
                        } else toast(context, "发送失败")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = code.isNotBlank() && !overLimit,
                )
            }
        }
    }
}
