package ink.kuke.chat.feature.settings

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val CCW_SITE = "https://www.ccw.site"

private fun compact(value: Int?): String = when {
    value == null -> "—"
    value >= 10000 -> "%.1fw".format(value / 10000f)
    value >= 1000 -> "%.1fk".format(value / 1000f)
    else -> value.toString()
}

/** 设置 › 账号与安全：用户名 / ID、CCW 绑定、通过 CCW 评论验证码改密码 */
@Composable
fun SecurityPage(graph: AppGraph) {
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot(MY_PROFILE_CACHE_KEY)) { graph.fetchMyProfile() }
    val toast = rememberToast()
    Box(Modifier.fillMaxWidth().background(KcTheme.colors.chat)) {
        LoadBox(loader) { profile ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
                val context = LocalContext.current
                val uiScope = rememberCoroutineScope()
                GroupLabel("登录信息")
                GroupCard {
                    SettingsRow("用户名", icon = KcIcons.profile, value = "@${profile.username}", chevron = false)
                    RowDivider()
                    SettingsRow("用户 ID", icon = KcIcons.info, value = profile.id.toString(), chevron = false, onClick = {
                        context.copyToClipboard("用户 ID", profile.id.toString())
                        toast.show("已复制用户 ID", uiScope)
                    })
                }
                GroupLabel("CCW 账号")
                CcwCard(graph, profile, toast, onChanged = { loader.load() })
                GroupLabel("修改密码")
                PasswordCard(graph, profile, toast)
            }
        }
        ToastHost(toast)
    }
}

@Composable
private fun CodeBox(code: String, toast: ToastState) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panelMuted)
            .clickable { context.copyToClipboard("验证码", code); toast.show("验证码已复制", scope) }
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(code, color = colors.accent, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.W700, letterSpacing = 3.sp)
        Text("点一下复制", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CcwCard(graph: AppGraph, profile: MyProfile, toast: ToastState, onChanged: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bound = !profile.ccwStudentOid.isNullOrBlank()
    var challenge by remember { mutableStateOf<JsonObject?>(null) }
    var loadingChallenge by remember { mutableStateOf(!bound) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "sync" | "unbind"

    suspend fun applyChallenge(element: kotlinx.serialization.json.JsonElement) {
        challenge = element.orNullObject()
        if (challenge?.string("status") == "verified") {
            // 绑定成功：用户资料已变，让外层重新拉，并刷新会话里的用户
            runCatching { graph.session.updateUser(KcJson.decodeFromJsonElement(User.serializer(), graph.api.get("/auth/me"))) }
            onChanged()
        }
    }

    LaunchedEffect(bound) {
        if (!bound) {
            runCatching { applyChallenge(graph.api.get("/ccw/binding/me")) }
            loadingChallenge = false
        }
    }
    // 验证码发出之后每 5 秒自动检查一次
    val pending = challenge?.string("status") == "pending"
    LaunchedEffect(pending, bound) {
        while (pending && !bound) {
            delay(5000)
            runCatching { applyChallenge(graph.api.post("/ccw/binding/check")) }
        }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }.onFailure { toast.show(it.userMessage(), scope) }
            busy = false
        }
    }

    GroupCard {
        Column(Modifier.padding(14.dp)) {
            if (bound) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile.ccwAvatarUrl, profile.ccwName ?: "C", tone = profile.id, size = 52.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(profile.ccwName ?: profile.ccwStudentOid.orEmpty(), style = type.title, color = colors.text, maxLines = 1)
                        Text(profile.ccwBio?.takeIf { it.isNotBlank() } ?: "没有公开简介", style = type.caption, color = colors.muted, maxLines = 1)
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("粉丝" to profile.ccwFollowerCount, "获赞" to profile.ccwLikeCount, "作品" to profile.ccwCreationCount, "浏览" to profile.ccwViewCount).forEach { (label, value) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(compact(value), style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600)
                            Text(label, style = type.caption, color = colors.muted)
                        }
                    }
                }
                Text("资料同步于 ${chinaTime(profile.ccwSyncedAt)}", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("打开 CCW 主页", onClick = { context.openExternal("$CCW_SITE/student/${profile.ccwStudentOid}") }, modifier = Modifier.weight(1f))
                    SecondaryButton("刷新数据", enabled = !busy, onClick = {
                        run { graph.api.post("/ccw/me/sync", buildJsonObject { put("sync_profile", false) }); toast.show("已刷新", scope); onChanged() }
                    }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("用 CCW 资料覆盖", onClick = { confirm = "sync" }, modifier = Modifier.weight(1f))
                    SecondaryButton("解绑", danger = true, onClick = { confirm = "unbind" }, modifier = Modifier.weight(1f))
                }
            } else {
                Text("绑定后可以同步 CCW 资料，并在忘记密码时自助找回。", style = type.secondary, color = colors.muted)
                Spacer(Modifier.height(10.dp))
                listOf("生成验证码", "用你的 CCW 账号，把验证码发到指定作品的评论区", "系统每 5 秒自动检查一次，检测到就绑定成功").forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = type.secondary, color = colors.text, modifier = Modifier.padding(vertical = 2.dp))
                }
                val status = challenge?.string("status")
                if (challenge != null && status != "expired") {
                    Spacer(Modifier.height(12.dp))
                    CodeBox(challenge?.string("code").orEmpty(), toast)
                    Text(
                        if (status == "verified") "绑定成功，资料马上刷新" else "${chinaTime(challenge?.string("expires_at"))} 前有效 · 正在自动检查",
                        style = type.caption, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                if (status == "conflict") {
                    Text("这个 CCW 账号已经绑定了其他 KukeChat 账号。", style = type.caption, color = colors.warn, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(if (challenge != null) "重新生成验证码" else "生成验证码", loading = busy || loadingChallenge, modifier = Modifier.weight(1f), onClick = {
                        run { applyChallenge(graph.api.post("/ccw/binding/challenge")) }
                    })
                    val url = challenge?.string("creation_url")
                    if (url != null) SecondaryButton("打开评论作品", onClick = { context.openExternal(url) })
                }
                if (challenge != null) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("立即检查", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        run { applyChallenge(graph.api.post("/ccw/binding/check")) }
                    })
                }
            }
        }
    }

    when (confirm) {
        "sync" -> ConfirmDialog(
            "用 CCW 资料覆盖？", "会用 CCW 上的昵称、头像、简介和封面覆盖你现在的 KukeChat 资料。账号、密码和聊天记录不受影响。", "覆盖",
            onConfirm = { run { graph.api.post("/ccw/me/sync", buildJsonObject { put("sync_profile", true) }); toast.show("已覆盖", scope); onChanged() } },
            onDismiss = { confirm = null },
        )
        "unbind" -> ConfirmDialog(
            "解绑 CCW 账号？", "解绑后主页不再展示 CCW 资料，也不能再通过 CCW 验证码修改密码。之后可以重新绑定。", "解绑", danger = true,
            onConfirm = { run { graph.api.delete("/ccw/me"); toast.show("已解绑", scope); onChanged() } },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun PasswordCard(graph: AppGraph, profile: MyProfile, toast: ToastState) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var commentUrl by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    suspend fun submit() {
        busy = true
        runCatching { graph.api.post("/auth/password/change/confirm", buildJsonObject { put("code", code.orEmpty()); put("new_password", password) }) }
            .onSuccess { done = true; waiting = false; error = ""; password = ""; code = null }
            .onFailure {
                // 404：还没在评论区看到验证码，每 5 秒自动重试，评论发出后会自动完成
                if (it is ApiException && it.status == 404) { waiting = true; error = "还没在评论区看到验证码，每 5 秒自动重试，评论发出后会自动完成。" }
                else { waiting = false; error = it.userMessage() }
            }
        busy = false
    }

    LaunchedEffect(waiting, code) {
        while (waiting && code != null) {
            delay(5000)
            submit()
        }
    }

    GroupCard {
        Column(Modifier.padding(14.dp)) {
            when {
                profile.ccwStudentOid.isNullOrBlank() -> Text("修改密码需要先绑定 CCW 账号，用来验证是你本人。", style = type.secondary, color = colors.muted)
                done -> {
                    Text("密码已修改，下次登录请使用新密码。", style = type.secondary, color = colors.ok)
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("再次修改", onClick = { done = false })
                }
                code != null -> {
                    CodeBox(code.orEmpty(), toast)
                    Text(
                        "10 分钟内有效。用已绑定的 CCW 账号把验证码发到指定作品的评论区，然后填写新密码提交。",
                        style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 10.dp),
                    )
                    commentUrl?.let { url -> SecondaryButton("打开评论作品", onClick = { context.openExternal(url) }, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(12.dp))
                    KcTextField(password, { password = it }, hint = "新密码，至少 6 位", maxLength = 64)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("提交", enabled = password.length >= 6, loading = busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        error = ""
                        scope.launch { submit() }
                    })
                }
                else -> {
                    Text("通过 CCW 评论验证码确认是本人，不需要旧密码。", style = type.secondary, color = colors.muted)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("开始修改密码", loading = busy, onClick = {
                        scope.launch {
                            busy = true
                            runCatching { graph.api.post("/auth/password/change/challenge") as JsonObject }
                                .onSuccess { code = it.string("code"); commentUrl = it.string("comment_url"); error = "" }
                                .onFailure { error = it.userMessage() }
                            busy = false
                        }
                    })
                }
            }
            if (error.isNotBlank()) Text(error, style = type.caption, color = if (waiting) colors.warn else colors.danger, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
