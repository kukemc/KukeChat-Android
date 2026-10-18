package ink.kuke.chat.feature.auth

import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.BuildConfig
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.SignedOut
import ink.kuke.chat.core.model.TokenResponse
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.session.Session
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min

private enum class FieldId { User, Nick, Pass, Conf }

/**
 * 登录 / 注册。接口同电脑版（POST /auth/login、/auth/register）；注册多出昵称、确认密码。
 * 外观是设计稿「积木」（KukeChat-Client/dev/login-concepts/live/template.html 的手机版）：
 * 上半是点阵工作区（字标 + 淡淡的虚拟积木描边 + 一串跟着表单实时变的真 Scratch 积木），下半是表单。
 * 键盘弹起时工作区压扁、积木缩小，镜头跟着正在填的那块积木走。
 * 提交 / 校验 / 封禁提示 / 找回密码的逻辑和之前完全一致，只换了外观。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(graph: AppGraph, signedOut: SignedOut?, onSignedIn: () -> Unit) {
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    // 只用于外观：哪个输入框标红、哪个输入框正在填（镜头跟随）
    var errorField by remember { mutableStateOf<FieldId?>(null) }
    var focused by remember { mutableStateOf<FieldId?>(null) }

    val notice = when (signedOut) {
        is SignedOut.Suspended -> buildString {
            append("账号已被封禁")
            signedOut.info.reason?.let { append("：$it") }
            append(if (signedOut.info.permanent) "（永久）" else signedOut.info.bannedUntil?.let { "，解封时间 $it" } ?: "")
        }
        SignedOut.Expired -> "登录已过期，请重新登录"
        SignedOut.PasswordReset -> "密码已被重置，请用新密码登录"
        is SignedOut.DeviceBanned -> buildString {
            append("此设备已被限制使用")
            signedOut.info.reason?.takeIf { it.isNotBlank() }?.let { append("：$it") }
            append(if (signedOut.info.permanent) "（永久）" else signedOut.info.expiresAt?.let { "，解除时间 ${banTime(it)}" } ?: "")
        }
        null -> null
    }

    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val focusManager = LocalFocusManager.current
    val requesters = remember { FieldId.entries.associateWith { FocusRequester() } }
    val bring = remember { FieldId.entries.associateWith { BringIntoViewRequester() } }

    // 积木跟着表单变（名字 / 昵称按设计稿 clean 处理；密码只用长度）
    val measurer = rememberTextMeasurer()
    val vars = AuthVars(register, AuthVars.clean(username.trim()), AuthVars.clean(nickname.trim()), password, confirm)
    val scene = remember(measurer) { BlockScene(measurer, scope, reduceMotion, vars) }
    LaunchedEffect(vars) { scene.update(vars) }

    fun edited() {
        error = null
        errorField = null
    }

    fun submit() {
        if (busy) return
        val name = username.trim()
        val problem = when {
            name.isEmpty() -> "请输入用户名" to FieldId.User
            password.isEmpty() -> "请输入密码" to FieldId.Pass
            register && !Regex("^[A-Za-z0-9_.-]{3,50}$").matches(name) -> "用户名为 3–50 位字母、数字或 _ . -" to FieldId.User
            register && password.length < 6 -> "密码至少 6 位" to FieldId.Pass
            register && password != confirm -> "两次输入的密码不一样" to FieldId.Conf
            else -> null
        }
        error = problem?.first
        errorField = problem?.second
        if (problem != null) {
            runCatching { requesters.getValue(problem.second).requestFocus() }
            return
        }
        busy = true
        focusManager.clearFocus()
        scene.runMain()
        scope.launch {
            runCatching {
                val body = if (register) {
                    JsonObject(buildMap {
                        put("username", JsonPrimitive(name))
                        put("password", JsonPrimitive(password))
                        nickname.trim().takeIf { it.isNotEmpty() }?.let { put("nickname", JsonPrimitive(it)) }
                    })
                } else {
                    JsonObject(mapOf("username_or_email" to JsonPrimitive(name), "password" to JsonPrimitive(password), "remember_me" to JsonPrimitive(true)))
                }
                var token = KcJson.decodeFromJsonElement(TokenResponse.serializer(), graph.api.post(if (register) "/auth/register" else "/auth/login", body))
                if (token.accessToken == null && register) {
                    // 注册接口没直接给令牌：再登录一次（同电脑版 authForms）
                    token = KcJson.decodeFromJsonElement(
                        TokenResponse.serializer(),
                        graph.api.post("/auth/login", JsonObject(mapOf("username_or_email" to JsonPrimitive(name), "password" to JsonPrimitive(password)))),
                    )
                }
                val accessToken = token.accessToken ?: error("登录没有返回令牌")
                val user = token.user ?: error("登录没有返回用户")
                graph.session.save(Session(accessToken, user))
            }.onSuccess {
                graph.acknowledgeSignOut()
                onSignedIn()
            }.onFailure {
                // 设备被封：上面的说明条已经写清楚了，不再重复一行红字；其余（含 too_many_accounts_on_device、
                // device_required、registration_ip_throttled）直接显示服务端给的 detail.message
                error = if ((it as? ApiException)?.deviceBan != null) null else it.userMessage()
            }
            busy = false
        }
    }

    fun select(toRegister: Boolean) {
        if (toRegister == register) return
        register = toRegister
        error = null
        errorField = null
        // 焦点在要收起的输入框里 → 挪回用户名
        if (!toRegister && (focused == FieldId.Nick || focused == FieldId.Conf)) runCatching { requesters.getValue(FieldId.User).requestFocus() }
    }

    fun focusOf(id: FieldId): (Boolean) -> Unit = { on ->
        if (on) focused = id else if (focused == id) focused = null
    }

    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeUp by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }

    // 键盘弹起后把正在填的输入框滚进可见范围（等工作区压扁完再补一次）
    LaunchedEffect(imeUp, focused) {
        val f = focused ?: return@LaunchedEffect
        if (!imeUp) return@LaunchedEffect
        delay(60)
        runCatching { bring.getValue(f).bringIntoView() }
        delay(420)
        runCatching { bring.getValue(f).bringIntoView() }
    }

    val dark = colors.isDark
    val wsBg = if (dark) Color(0xFF111217) else Color(0xFFF3F4F8)
    val dotColor = if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFF121626).copy(alpha = 0.09f)
    val outlineColor = if (dark) Color(0xFFC9D0E6) else Color(0xFF1A1F33)
    val outlineStrength = if (dark) 0.62f else 1f
    val border = colors.border

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.panel)) {
        // 键盘弹起，或窗口本来就矮（小窗 / 横屏）：工作区压扁，积木缩到 0.6
        val compressed = imeUp || maxHeight < 600.dp
        val motion = if (reduceMotion) snap<Float>() else tween(420, easing = AuthEase)
        val wsHeight by animateDpAsState(
            statusTop + if (compressed) 176.dp else 304.dp,
            if (reduceMotion) snap() else tween(420, easing = AuthEase), label = "ws",
        )
        val unit by animateFloatAsState(if (compressed) 0.6f else 0.8f, motion, label = "unit")
        val mask by animateFloatAsState(if (compressed) 1f else 0f, motion, label = "mask")
        // 镜头：对准正在填的那块积木（设计稿 pan()）
        val panTarget = run {
            if (!compressed) return@run 0f
            val id = when (focused) {
                FieldId.User -> if (register) "ifc" else "say"
                FieldId.Nick -> "nick"
                FieldId.Pass -> if (register) "ifc" else "pw"
                FieldId.Conf -> "ifc"
                null -> null
            } ?: return@run 0f
            val (top, h) = scene.blockSpan(id) ?: return@run 0f
            val view = 124f / 0.6f - 30f // 压扁后的画板高度 / 缩放，再留出底部淡出
            -max(0f, min(top - 6f, top + h - view)) * 0.6f
        }
        val pan by animateFloatAsState(panTarget, motion, label = "pan")

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))) {
            // ───── 工作区：点阵底 + 虚拟积木描边 + 积木 + 字标 ─────
            Box(
                Modifier
                    .fillMaxWidth()
                    .layout { m, c ->
                        val h = wsHeight.roundToPx().coerceAtMost(c.maxHeight)
                        val p = m.measure(Constraints.fixed(c.maxWidth, h))
                        layout(c.maxWidth, h) { p.place(0, 0) }
                    }
                    .clipToBounds()
                    .drawWithCache {
                        val step = 22.dp.toPx()
                        val r = 1.25.dp.toPx()
                        val dots = Path()
                        var y = step / 2f
                        while (y < size.height + r) {
                            var x = step / 2f
                            while (x < size.width + r) {
                                dots.addOval(androidx.compose.ui.geometry.Rect(Offset(x, y), r))
                                x += step
                            }
                            y += step
                        }
                        val line = 1.dp.toPx()
                        onDrawWithContent {
                            drawRect(wsBg)
                            drawPath(dots, dotColor)
                            drawContent()
                            drawRect(border, Offset(0f, size.height - line), Size(size.width, line))
                        }
                    },
            ) {
                OutlineLayer(outlineColor, outlineStrength, statusTop - 40.dp, reduceMotion, Modifier.fillMaxSize())
                BlockStage(
                    scene, dark = dark, unit = { unit }, panY = { pan }, mask = { mask },
                    modifier = Modifier.fillMaxSize().padding(top = statusTop + 52.dp).clipToBounds(),
                )
                Wordmark(Modifier.padding(start = 22.dp, top = statusTop + 12.dp))
            }

            // ───── 表单 ─────
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewport)
                            // 设计稿上内边距 24：页签自己多留了 8 给手指
                            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 26.dp),
                    ) {
                        notice?.let {
                            Box(Modifier.padding(top = 8.dp, bottom = 8.dp)) { AuthAlert(it, colors.warn) }
                        }
                        AuthTabs(register, onSelect = ::select, reduceMotion = reduceMotion)
                        Spacer(Modifier.height(24.dp))

                        AuthField(
                            "用户名", username, { username = it; edited() }, if (register) "字母、数字、_ . -" else "输入用户名",
                            KcIcons.profile, keyboard = KeyboardType.Ascii, error = errorField == FieldId.User,
                            focusRequester = requesters.getValue(FieldId.User),
                            onFocus = focusOf(FieldId.User), modifier = Modifier.bringIntoViewRequester(bring.getValue(FieldId.User)),
                        )
                        // 展开：提示 → 昵称 → 确认密码 依次；收起反过来（设计稿 setMode 的错开时间）
                        Collapsible(register, if (register) 40 else 90, reduceMotion) {
                            Text("3–50 个字符，之后不能修改。", color = colors.subtle, fontSize = 12.sp, style = lineStyle(18f), modifier = Modifier.padding(top = 6.dp))
                        }
                        Collapsible(register, if (register) 70 else 50, reduceMotion) {
                            AuthField(
                                "昵称", nickname, { nickname = it; edited() }, "显示给别人看的名字", AuthIcons.smile, aside = "选填",
                                focusRequester = requesters.getValue(FieldId.Nick),
                                onFocus = focusOf(FieldId.Nick), modifier = Modifier.padding(top = 14.dp).bringIntoViewRequester(bring.getValue(FieldId.Nick)),
                            )
                        }
                        AuthField(
                            "密码", password, { password = it; edited() }, if (register) "至少 6 位" else "输入密码",
                            KcIcons.lock, secret = true, eye = true, done = !register, onDone = ::submit,
                            error = errorField == FieldId.Pass,
                            focusRequester = requesters.getValue(FieldId.Pass),
                            onFocus = focusOf(FieldId.Pass), modifier = Modifier.padding(top = 14.dp).bringIntoViewRequester(bring.getValue(FieldId.Pass)),
                        )
                        Collapsible(register, if (register) 140 else 0, reduceMotion) {
                            AuthField(
                                "确认密码", confirm, { confirm = it; edited() }, "再输入一次", KcIcons.lock,
                                secret = true, eye = false, done = true, onDone = ::submit, error = errorField == FieldId.Conf,
                                focusRequester = requesters.getValue(FieldId.Conf),
                                onFocus = focusOf(FieldId.Conf), modifier = Modifier.padding(top = 14.dp).bringIntoViewRequester(bring.getValue(FieldId.Conf)),
                            )
                        }
                        Collapsible(!register, if (register) 0 else 110, reduceMotion) {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("登录后会自动保存登录状态", color = colors.muted, fontSize = 12.5.sp, style = lineStyle(18.75f), modifier = Modifier.weight(1f))
                                Text(
                                    "忘记密码？", color = colors.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, style = lineStyle(18.75f),
                                    modifier = Modifier.clickable { forgot = true }.padding(start = 12.dp),
                                )
                            }
                        }
                        error?.let {
                            AuthAlert(it, colors.danger, Modifier.padding(top = 14.dp))
                        }
                        BlockButton(
                            text = if (busy) (if (register) "正在创建…" else "正在登录…") else (if (register) "创建账号" else "登录"),
                            busy = busy,
                            showArrow = !register,
                            onClick = ::submit,
                            reduceMotion = reduceMotion,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        if (forgot) {
                            ForgotPasswordSheet(graph, username, onDismiss = { forgot = false }) { name ->
                                forgot = false
                                username = name
                                password = ""
                                error = null
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "NEXT · v${BuildConfig.VERSION_NAME} · Android",
                            color = colors.subtle,
                            fontSize = 11.5.sp,
                            style = lineStyle(17.25f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 解除时间：能解析就按本地时间显示到分钟，否则原样 */
private fun banTime(iso: String): String =
    ink.kuke.chat.core.ui.parseInstant(iso)?.atZone(java.time.ZoneId.systemDefault())?.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: iso
