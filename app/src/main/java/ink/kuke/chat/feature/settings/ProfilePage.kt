package ink.kuke.chat.feature.settings

import ink.kuke.chat.core.data.slot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneOffset

private data class ProfileDraft(
    val nickname: String,
    val avatarUrl: String,
    val bio: String,
    val title: String,
    val tagline: String,
    val status: String,
    val location: String,
    val interests: String,
    val layout: String,
    val cardStyle: String,
    val accent: String,
    val cover: String,
) {
    companion object {
        fun from(p: MyProfile) = ProfileDraft(
            nickname = p.nickname.orEmpty(),
            avatarUrl = p.avatarUrl.orEmpty(),
            bio = p.bio.orEmpty(),
            title = p.profileTitle.orEmpty(),
            tagline = p.profileTagline.orEmpty(),
            status = p.profileStatus.orEmpty(),
            location = p.profileLocation.orEmpty(),
            interests = p.profileInterests.orEmpty(),
            layout = p.profileLayout?.takeIf { it in Layouts.map { l -> l.first } } ?: "classic",
            cardStyle = p.profileCardStyle?.takeIf { it in CardStyles.map { c -> c.first } } ?: "soft",
            accent = p.profileAccentColor?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#168bff",
            cover = p.profileCoverPendingUrl ?: p.profileCoverUrl.orEmpty(),
        )
    }
}

private val Layouts = listOf("classic" to "经典", "banner" to "沉浸封面", "compact" to "紧凑")
private val CardStyles = listOf("soft" to "柔和", "glass" to "玻璃", "solid" to "纯色")
private val ProfileAccents = listOf("#168bff", "#7c3aed", "#ff4f86", "#f97316", "#10b981", "#111827")

/** 设置 › 我的资料：头像、昵称、简介、个人主页自定义、生日 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePage(graph: AppGraph) {
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot(MY_PROFILE_CACHE_KEY)) { graph.fetchMyProfile() }
    val toast = rememberToast()
    Box(Modifier.fillMaxWidth().background(KcTheme.colors.chat)) {
        LoadBox(loader) { profile -> ProfileForm(graph, profile, toast) { saved -> loader.mutate { saved } } }
        ToastHost(toast)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileForm(graph: AppGraph, profile: MyProfile, toast: ToastState, onSaved: (MyProfile) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(profile) { ProfileDraft.from(profile) }
    var draft by remember(profile) { mutableStateOf(original) }
    var uploading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val dirty = draft != original

    fun pickInto(path: String, maxEdge: Int, apply: (ProfileDraft, String) -> ProfileDraft): (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch {
            uploading = true
            runCatching { graph.uploadImage(context, uri, path, maxEdge) }
                .onSuccess { url -> draft = apply(draft, url) }
                .onFailure { toast.show(it.userMessage(), scope) }
            uploading = false
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), pickInto("/uploads/avatar", 512) { d, url -> d.copy(avatarUrl = url) })
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), pickInto("/uploads/profile-cover", 1600) { d, url -> d.copy(cover = url) })

    fun save() {
        scope.launch {
            saving = true
            val body = buildJsonObject {
                // 只发改过的字段；空串是「清空」，服务端按空处理
                if (draft.nickname != original.nickname) put("nickname", draft.nickname.trim())
                if (draft.avatarUrl != original.avatarUrl && draft.avatarUrl.isNotBlank()) put("avatar_url", draft.avatarUrl)
                if (draft.bio != original.bio) put("bio", draft.bio)
                if (draft.title != original.title) put("profile_title", draft.title)
                if (draft.tagline != original.tagline) put("profile_tagline", draft.tagline)
                if (draft.status != original.status) put("profile_status", draft.status)
                if (draft.location != original.location) put("profile_location", draft.location)
                if (draft.interests != original.interests) put("profile_interests", draft.interests)
                if (draft.layout != original.layout) put("profile_layout", draft.layout)
                if (draft.cardStyle != original.cardStyle) put("profile_card_style", draft.cardStyle)
                if (draft.accent != original.accent) put("profile_accent_color", draft.accent)
                if (draft.cover != original.cover) put("profile_cover_url", draft.cover)
            }
            runCatching { graph.api.patch("/users/me", body) }
                .onSuccess { element ->
                    val saved = KcJson.decodeFromJsonElement(MyProfile.serializer(), element)
                    runCatching { graph.session.updateUser(KcJson.decodeFromJsonElement(User.serializer(), element)) }
                    onSaved(saved)
                    toast.show("已保存", scope)
                }
                .onFailure { toast.show(it.userMessage(), scope) }
            saving = false
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
        GroupLabel("基本资料")
        GroupCard {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(draft.avatarUrl.ifBlank { null }, draft.nickname.ifBlank { profile.username }, tone = profile.id, size = 64.dp)
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(if (uploading) "上传中…" else "更换头像", enabled = !uploading, onClick = {
                            avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    }
                }
                Spacer(Modifier.height(14.dp))
                FieldLabel("昵称")
                KcTextField(draft.nickname, { draft = draft.copy(nickname = it) }, hint = profile.username, maxLength = 50)
                Spacer(Modifier.height(12.dp))
                FieldLabel("简介")
                KcTextField(draft.bio, { draft = draft.copy(bio = it) }, hint = "介绍一下自己吧", singleLine = false, minLines = 3, maxLength = 300)
            }
        }

        GroupLabel("个人主页")
        GroupCard {
            Column(Modifier.padding(14.dp)) {
                // 封面
                val cover = Endpoints.absolute(draft.cover.ifBlank { null })
                Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted), contentAlignment = Alignment.Center) {
                    if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(100.dp))
                    else Text("没有设置主页背景图", style = type.caption, color = colors.subtle)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (uploading) "上传中…" else "主页背景图", enabled = !uploading, onClick = {
                        coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    if (draft.cover.isNotBlank()) SecondaryButton("移除背景", onClick = { draft = draft.copy(cover = "") })
                }
                Spacer(Modifier.height(14.dp))
                FieldLabel("主页标题")
                KcTextField(draft.title, { draft = draft.copy(title = it) }, hint = "我的空间", maxLength = 40)
                Spacer(Modifier.height(12.dp))
                FieldLabel("主页状态")
                KcTextField(draft.status, { draft = draft.copy(status = it) }, hint = "今天在线", maxLength = 40)
                Spacer(Modifier.height(12.dp))
                FieldLabel("一句话介绍")
                KcTextField(draft.tagline, { draft = draft.copy(tagline = it) }, hint = "例如：独立游戏制作中", maxLength = 80)
                Spacer(Modifier.height(12.dp))
                FieldLabel("所在地")
                KcTextField(draft.location, { draft = draft.copy(location = it) }, hint = "可选", maxLength = 40)
                Spacer(Modifier.height(12.dp))
                FieldLabel("兴趣标签（用逗号分隔）")
                KcTextField(draft.interests, { draft = draft.copy(interests = it) }, hint = "编程, 音乐", maxLength = 120)
                Spacer(Modifier.height(12.dp))
                FieldLabel("页面布局")
                Segmented(Layouts.map { it.second }, Layouts.indexOfFirst { it.first == draft.layout }.coerceAtLeast(0), { draft = draft.copy(layout = Layouts[it].first) })
                Spacer(Modifier.height(12.dp))
                FieldLabel("卡片风格")
                Segmented(CardStyles.map { it.second }, CardStyles.indexOfFirst { it.first == draft.cardStyle }.coerceAtLeast(0), { draft = draft.copy(cardStyle = CardStyles[it].first) })
                Spacer(Modifier.height(12.dp))
                FieldLabel("强调色")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileAccents.forEach { hexColor ->
                        val color = Color(android.graphics.Color.parseColor(hexColor))
                        val on = draft.accent.equals(hexColor, ignoreCase = true)
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(color).clickable { draft = draft.copy(accent = hexColor) },
                            contentAlignment = Alignment.Center,
                        ) { if (on) KcIcon(KcIcons.check, tint = Color.White, size = 18.dp) }
                    }
                }
            }
        }

        Box(Modifier.padding(16.dp)) {
            PrimaryButton(if (saving) "保存中…" else "保存资料", onClick = ::save, enabled = dirty && !uploading, loading = saving, modifier = Modifier.fillMaxWidth())
        }

        GroupLabel("生日")
        BirthdayCard(graph, toast)
    }
}

@Composable
private fun BirthdayCard(graph: AppGraph, toast: ToastState) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(Unit) { graph.api.get("/users/me/birthday") }
    var year by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var showYear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fromServer by remember { mutableStateOf<String?>(null) }

    // 服务端返回的生日灌进输入框（只在数据变化时灌一次）
    val raw = loader.data
    val signature = raw?.toString()
    if (raw != null && signature != fromServer) {
        fromServer = signature
        val obj = raw as? JsonObject
        val b = obj?.get("birthday") as? JsonObject
        year = b?.get("year")?.jsonPrimitive?.intOrNull?.toString().orEmpty()
        month = b?.get("month")?.jsonPrimitive?.intOrNull?.toString().orEmpty()
        day = b?.get("day")?.jsonPrimitive?.intOrNull?.toString().orEmpty()
        showYear = obj?.get("show_year")?.jsonPrimitive?.booleanOrNull == true
    }

    GroupCard {
        Column(Modifier.padding(14.dp)) {
            if (loader.state is Load.Failed) {
                Text("生日功能暂时不可用", style = type.secondary, color = colors.muted)
                return@Column
            }
            Text("生日当天别人能看到「今天生日」。资料上默认只显示几月几日，年份要你打开下面的开关才会公开。", style = type.caption, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1.4f)) { FieldLabel("年"); KcTextField(year, { year = it.filter(Char::isDigit) }, hint = "2008", numeric = true, maxLength = 4) }
                Column(Modifier.weight(1f)) { FieldLabel("月"); KcTextField(month, { month = it.filter(Char::isDigit) }, hint = "10", numeric = true, maxLength = 2) }
                Column(Modifier.weight(1f)) { FieldLabel("日"); KcTextField(day, { day = it.filter(Char::isDigit) }, hint = "12", numeric = true, maxLength = 2) }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("公开出生年份", style = type.secondary, color = colors.text, modifier = Modifier.weight(1f))
                KcSwitch(showYear, { showYear = it })
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("保存生日", modifier = Modifier.weight(1f), loading = busy, onClick = {
                    val y = year.toIntOrNull(); val m = month.toIntOrNull(); val d = day.toIntOrNull()
                    val valid = y != null && m != null && d != null && runCatching { LocalDate.of(y, m, d) }.getOrNull()?.let { it.year >= 1900 && !it.isAfter(LocalDate.now(ZoneOffset.ofHours(8))) } == true
                    if (!valid) { toast.show("请填写有效的日期", scope); return@PrimaryButton }
                    scope.launch {
                        busy = true
                        runCatching {
                            graph.api.put("/users/me/birthday", buildJsonObject { put("year", y!!); put("month", m!!); put("day", d!!); put("show_year", showYear) })
                        }.onSuccess { toast.show("生日已保存", scope); loader.load() }.onFailure { toast.show(it.userMessage(), scope) }
                        busy = false
                    }
                })
                SecondaryButton("清空", onClick = {
                    scope.launch {
                        busy = true
                        runCatching { graph.api.delete("/users/me/birthday") }
                            .onSuccess { toast.show("已清空", scope); loader.load() }
                            .onFailure { toast.show(it.userMessage(), scope) }
                        busy = false
                    }
                })
            }
        }
    }
    Spacer(Modifier.width(0.dp))
}
