package ink.kuke.chat.feature.bots

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SectionLabel
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private const val COMMANDS_MAX = 3000

/**
 * 创建 / 编辑机器人（POST /bots，PATCH /bots/{id}）。
 * 指令按电脑版的约定一行一条：「/指令 <必填参数> [可选参数] 说明」，下面的预览是保存后别人看到的样子。
 * 创建成功后完整 Key 只显示一次。
 */
@Composable
internal fun BotEditorPage(graph: AppGraph, bot: Bot?, onBack: () -> Unit, onSaved: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val api = remember { BotApi(graph.api) }

    var name by remember { mutableStateOf(bot?.name.orEmpty()) }
    var avatarUrl by remember { mutableStateOf(bot?.avatarUrl) }
    var description by remember { mutableStateOf(bot?.description.orEmpty()) }
    var functions by remember { mutableStateOf(bot?.functions.orEmpty()) }
    var commands by remember { mutableStateOf(bot?.commands.orEmpty()) }
    var isPublic by remember { mutableStateOf(bot?.isPublic ?: true) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createdKey by remember { mutableStateOf<String?>(null) }

    // 头像：系统选图器 → 读出字节 → POST /uploads/avatar（字段名 file）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        error = null
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: error("读不到这张图片")
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "avatar", bytes.toRequestBody(mime.toMediaTypeOrNull()))
                    .build()
                graph.api.upload("/uploads/avatar", body)
            }.onSuccess { result ->
                (result as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull?.let { avatarUrl = it }
            }.onFailure { error = it.userMessage() }
            uploading = false
        }
    }

    fun save() {
        if (name.isBlank()) {
            error = "给机器人起个名字"
            return
        }
        saving = true
        error = null
        val body = buildJsonObject {
            put("name", name.trim())
            put("avatar_url", avatarUrl?.let { JsonPrimitive(it) } ?: JsonNull)
            put("description", description.trim())
            put("functions", functions.trim())
            put("commands", commands.trim())
            put("is_public", isPublic)
        }
        scope.launch {
            runCatching {
                if (bot == null) api.create(body) else { api.update(bot.id, body); null }
            }.onSuccess { result ->
                Haptics.confirm(view)
                if (result != null && result.key.isNotBlank()) createdKey = result.key else onSaved()
            }.onFailure {
                Haptics.reject(view)
                error = it.userMessage()
            }
            saving = false
        }
    }

    KcPage(title = if (bot == null) "创建机器人" else "编辑资料", onBack = onBack) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).clickable(enabled = !uploading) { picker.launch("image/*") }) {
                        Avatar(avatarUrl, name.ifBlank { "机" }, bot?.accountId ?: 0, size = 64.dp)
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(if (uploading) "正在上传…" else "点头像更换", style = type.secondary, color = colors.accent)
                        Text("方形图片效果最好", style = type.caption, color = colors.muted)
                    }
                }
                SectionLabel("名称")
                PlainField(name, { name = it.take(40) }, "机器人名称（必填）")
                SectionLabel("简介")
                PlainField(description, { description = it.take(500) }, "一句话说明它能做什么", singleLine = false, minHeight = 76.dp)
                SectionLabel("功能列表", trailing = "一行一条")
                PlainField(functions, { functions = it.take(1000) }, "例如：回答积木问题\n查询作品信息", singleLine = false, minHeight = 76.dp)
                SectionLabel("指令", trailing = "${commands.length}/$COMMANDS_MAX")
                PlainField(commands, { commands = it.take(COMMANDS_MAX) }, "/help 查看全部指令\n/积木 <名称> 查询积木用法\n/队伍 <操作> [名称] 创建 / 加入队伍", singleLine = false, minHeight = 110.dp)
                Text("一行一条：「/指令」后面接 <必填参数>、[可选参数]，再写说明。尖括号是必填，方括号是可选。", style = type.caption, color = colors.muted)

                val preview = remember(commands) { parseBotCommands(commands) }
                if (preview.isNotEmpty()) {
                    SectionLabel("别人看到的样子")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        preview.forEach { line ->
                            Column {
                                Text((listOf(line.command) + line.params).joinToString(" "), style = type.secondary, color = colors.accent)
                                if (line.description.isNotBlank()) Text(line.description, style = type.caption, color = colors.muted)
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("公开到广场", style = type.body, color = colors.text)
                        Text(if (isPublic) "所有人都能在广场找到并添加" else "只有你自己能看到和使用", style = type.caption, color = colors.muted)
                    }
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                error?.let { Text(it, style = type.secondary, color = colors.danger) }
            }
            Box(Modifier.fillMaxWidth().background(colors.panel).navigationBarsPadding().padding(12.dp)) {
                PrimaryButton(if (saving) "保存中…" else if (bot == null) "创建机器人" else "保存", ::save, Modifier.fillMaxWidth(), enabled = !saving && !uploading)
            }
        }
    }

    createdKey?.let { key ->
        KeyDialog(key, "机器人已创建", onDismiss = { createdKey = null; onSaved() })
    }
}
