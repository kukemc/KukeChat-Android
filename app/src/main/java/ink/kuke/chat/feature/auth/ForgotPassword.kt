package ink.kuke.chat.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 找回密码（同电脑版 authForms.useForgotPassword）：
 *   先查账号 → 绑定了 CCW 的用验证码自助重置（在 CCW 指定作品下评论验证码）；
 *   没绑定或不方便的走人工申诉，由管理员核对 CCW 主页后处理。
 * 接口都不需要登录。
 */

@Serializable
private data class AccountLookup(val exists: Boolean = false, @SerialName("ccw_bound") val ccwBound: Boolean = false)

@Serializable
private data class CcwChallenge(val code: String = "", @SerialName("comment_url") val commentUrl: String = "", @SerialName("expires_in") val expiresIn: Int = 0)

private val UsernamePattern = Regex("^[A-Za-z0-9_.-]+$")
private val CcwProfilePattern = Regex("^https://www\\.ccw\\.site/student/[^\\s/]+/?$")

private enum class Lookup { Idle, Checking, Exists, Missing }
private enum class ResetMode { Appeal, Ccw }

/** 「忘记密码？」底部抽屉。重置成功后把用户名交回登录表单 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForgotPasswordSheet(graph: AppGraph, initialUsername: String, onDismiss: () -> Unit, onReset: (String) -> Unit) {
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var username by remember { mutableStateOf(initialUsername.trim()) }
    var lookup by remember { mutableStateOf(Lookup.Idle) }
    var ccwBound by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(ResetMode.Appeal) }
    var challenge by remember { mutableStateOf<CcwChallenge?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var requesting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var autoVerify by remember { mutableStateOf(false) }
    var ccwDone by remember { mutableStateOf(false) }
    var ccwError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    // 人工申诉
    var profileUrl by remember { mutableStateOf("") }
    var requestedPassword by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var appealError by remember { mutableStateOf<String?>(null) }

    val name = username.trim()
    val nameInvalid = name.isNotEmpty() && !UsernamePattern.matches(name)
    val checkable = name.length >= 3 && !nameInvalid

    // 用户名停顿 0.5 秒后查一次账号；换了用户名，之前的验证码作废
    LaunchedEffect(name) {
        mode = ResetMode.Appeal
        challenge = null
        ccwError = null
        ccwDone = false
        autoVerify = false
        if (!checkable) { lookup = Lookup.Idle; ccwBound = false; return@LaunchedEffect }
        lookup = Lookup.Checking
        delay(500)
        runCatching { KcJson.decodeFromJsonElement(AccountLookup.serializer(), graph.api.get("/auth/account-lookup?username=$name")) }
            .onSuccess { lookup = if (it.exists) Lookup.Exists else Lookup.Missing; ccwBound = it.ccwBound }
            .onFailure { lookup = Lookup.Idle }
    }

    suspend fun confirmOnce() {
        val code = challenge?.code ?: return
        confirming = true
        runCatching {
            graph.api.post("/auth/password-reset/ccw/confirm", buildJsonObject { put("username", name); put("code", code); put("new_password", newPassword) })
        }.onSuccess {
            autoVerify = false
            ccwDone = true
            ccwError = null
        }.onFailure {
            if ((it as? ApiException)?.status == 404) {
                ccwError = "暂未在评论区检测到验证码，已开启每 5 秒自动重试；确认评论发送成功后会自动完成重置。"
                autoVerify = true
            } else {
                autoVerify = false
                ccwError = it.userMessage()
            }
        }
        confirming = false
    }

    // 评论还没被抓到时每 5 秒再试
    LaunchedEffect(autoVerify, ccwDone) {
        while (autoVerify && !ccwDone) {
            delay(5000)
            if (!confirming) confirmOnce()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("找回密码", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.W700)
            Text("先输入用户名查一下账号。绑定了 CCW 的账号可以用验证码自助重置；没绑定的可以提交人工申诉。", color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp)

            AuthField(
                "用户名", username, { username = it }, "输入你的用户名", KcIcons.profile, keyboard = KeyboardType.Ascii,
                hint = when {
                    nameInvalid -> "用户名仅可使用英文字母、数字、下划线、点和短横线。"
                    lookup == Lookup.Checking -> "正在查询账号…"
                    lookup == Lookup.Missing -> "没有找到这个账号，检查一下拼写。"
                    lookup == Lookup.Exists && ccwBound -> "找到了 · 已绑定 CCW，可以用验证码自助重置。"
                    lookup == Lookup.Exists -> "找到了 · 没有绑定 CCW，请提交人工申诉。"
                    else -> null
                },
            )

            when {
                // ───── 自助重置完成 ─────
                ccwDone -> {
                    AuthAlert("密码已重置，用新密码登录吧。", colors.ok)
                    BlockButton("回去登录", busy = false, showArrow = true, reduceMotion = true, onClick = { onReset(name) })
                }
                // ───── CCW 验证码 ─────
                mode == ResetMode.Ccw && challenge != null -> {
                    val current = challenge!!
                    StepCard("1", "复制验证码") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(current.code, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.W700, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            LinkText(if (copied) "已复制" else "复制") {
                                clipboard.setText(AnnotatedString(current.code))
                                copied = true
                                scope.launch { delay(2000); copied = false }
                            }
                        }
                        Text("${current.expiresIn / 60} 分钟内有效", color = colors.subtle, fontSize = 12.sp)
                    }
                    StepCard("2", "到 CCW 作品下发一条评论，内容就是这个验证码") {
                        LinkText("打开 CCW 作品页") { runCatching { uri.openUri(current.commentUrl) } }
                        Text("要用绑定的那个 CCW 账号评论。", color = colors.subtle, fontSize = 12.sp)
                    }
                    StepCard("3", "设置新密码") {
                        AuthField("新密码", newPassword, { newPassword = it }, "至少 6 位", KcIcons.lock, secret = true, done = true,
                            hint = if (newPassword.isNotEmpty() && newPassword.length < 6) "密码至少 6 位" else null)
                    }
                    ccwError?.let { AuthAlert(it, if (autoVerify) colors.warn else colors.danger) }
                    BlockButton(
                        if (confirming) "正在验证…" else "我已评论，完成重置", busy = confirming, showArrow = false, reduceMotion = true,
                        onClick = {
                            if (newPassword.length < 6) { ccwError = "新密码至少 6 位"; return@BlockButton }
                            ccwError = null
                            scope.launch { confirmOnce() }
                        },
                    )
                    LinkText("改用人工申诉") { mode = ResetMode.Appeal; autoVerify = false }
                }
                // ───── 已绑定 CCW：先领验证码 ─────
                lookup == Lookup.Exists && ccwBound && mode == ResetMode.Appeal && !submitted -> {
                    ccwError?.let { AuthAlert(it, colors.danger) }
                    BlockButton(
                        if (requesting) "正在获取验证码…" else "用 CCW 验证码重置", busy = requesting, showArrow = true, reduceMotion = true,
                        onClick = {
                            requesting = true
                            ccwError = null
                            scope.launch {
                                runCatching {
                                    KcJson.decodeFromJsonElement(CcwChallenge.serializer(), graph.api.post("/auth/password-reset/ccw/challenge", buildJsonObject { put("username", name) }))
                                }.onSuccess { challenge = it; mode = ResetMode.Ccw }.onFailure { ccwError = it.userMessage() }
                                requesting = false
                            }
                        },
                    )
                    AppealForm(
                        profileUrl, { profileUrl = it }, requestedPassword, { requestedPassword = it }, reason, { reason = it },
                        submitting, appealError, collapsedTitle = "不方便评论？提交人工申诉",
                    ) {
                        submitAppeal(graph, name, profileUrl, requestedPassword, reason, onStart = { submitting = true; appealError = null }) { error ->
                            submitting = false
                            if (error == null) submitted = true else appealError = error
                        }.let { scope.launch { it() } }
                    }
                }
                // ───── 人工申诉 ─────
                submitted -> AuthAlert("申诉已提交。管理员核对你的 CCW 主页后会处理，通过后用你填写的新密码登录。", colors.ok)
                lookup == Lookup.Exists -> AppealForm(
                    profileUrl, { profileUrl = it }, requestedPassword, { requestedPassword = it }, reason, { reason = it },
                    submitting, appealError, collapsedTitle = null,
                ) {
                    submitAppeal(graph, name, profileUrl, requestedPassword, reason, onStart = { submitting = true; appealError = null }) { error ->
                        submitting = false
                        if (error == null) submitted = true else appealError = error
                    }.let { scope.launch { it() } }
                }
            }
        }
    }
}

/** 校验并提交人工申诉，返回要在协程里跑的那一段 */
private fun submitAppeal(
    graph: AppGraph,
    username: String,
    profileUrl: String,
    password: String,
    reason: String,
    onStart: () -> Unit,
    onEnd: (String?) -> Unit,
): suspend () -> Unit = suspend {
    val url = profileUrl.trim()
    val problem = when {
        !CcwProfilePattern.matches(url) -> "CCW 主页地址的格式是 https://www.ccw.site/student/xxxx"
        password.length < 6 -> "新密码至少 6 位"
        reason.trim().length < 10 -> "情况说明至少 10 个字"
        else -> null
    }
    if (problem != null) {
        onEnd(problem)
    } else {
        onStart()
        runCatching {
            graph.api.post("/auth/password-reset-requests", buildJsonObject {
                put("username", username)
                put("ccw_profile_url", url)
                put("requested_password", password)
                put("reason", reason.trim())
            })
        }.onSuccess { onEnd(null) }.onFailure { onEnd(it.userMessage()) }
    }
}

@Composable
private fun AppealForm(
    profileUrl: String, onProfile: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    reason: String, onReason: (String) -> Unit,
    submitting: Boolean, error: String?, collapsedTitle: String?,
    onSubmit: () -> Unit,
) {
    val colors = KcTheme.colors
    var open by remember { mutableStateOf(collapsedTitle == null) }
    if (!open) {
        LinkText(collapsedTitle.orEmpty()) { open = true }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("人工申诉", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.W600)
        AuthField("CCW 主页", profileUrl, onProfile, "https://www.ccw.site/student/…", KcIcons.link, keyboard = KeyboardType.Uri,
            hint = if (profileUrl.isNotBlank() && !CcwProfilePattern.matches(profileUrl.trim())) "格式：https://www.ccw.site/student/xxxx" else "管理员用它核对你是不是账号的主人。")
        AuthField("新密码", password, onPassword, "审核通过后生效，至少 6 位", KcIcons.lock, secret = true)
        AuthField("情况说明", reason, { onReason(it.take(2000)) }, "说说账号的情况，至少 10 个字", KcIcons.edit)
        error?.let { AuthAlert(it, colors.danger) }
        BlockButton(if (submitting) "正在提交…" else "提交申诉", busy = submitting, showArrow = false, reduceMotion = true, onClick = onSubmit)
    }
}

/** 编号步骤卡：圆形序号 + 标题 + 内容 */
@Composable
private fun StepCard(index: String, title: String, content: @Composable () -> Unit) {
    val colors = KcTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.panelMuted).border(1.dp, colors.border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(colors.fill), contentAlignment = Alignment.Center) {
                Text(index, color = colors.onFill, fontSize = 12.sp, fontWeight = FontWeight.W700)
            }
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 10.dp))
        }
        content()
    }
}

/** 登录页下方的次级链接（「忘记密码？」等） */
@Composable
internal fun LinkText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = KcTheme.colors.accent, fontSize = 13.sp, fontWeight = FontWeight.W500,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
