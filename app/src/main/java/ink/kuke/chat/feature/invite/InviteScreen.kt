package ink.kuke.chat.feature.invite

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcButton
import ink.kuke.chat.feature.chat.KcTextField
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.asObj
import ink.kuke.chat.feature.chat.b
import ink.kuke.chat.feature.chat.i
import ink.kuke.chat.feature.chat.l
import ink.kuke.chat.feature.chat.o
import ink.kuke.chat.feature.chat.s
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * 邀请链接（KukeChat-Server services/invites.py）：服务端发的是 `{INVITE_BASE_URL}?invite=<token>`，
 * 电脑版也只认查询参数 invite（ChatDashboard.tsx）。这里不看域名，只认参数，所以换部署地址不用改代码；
 * 另外认 `kukechat://invite/<token>` 这种应用内链接。token 长度和服务端路径校验一致（6–80）。
 */
object InviteLinks {
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{6,80}$")

    fun tokenOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
        val token = when {
            uri.scheme == "kukechat" && uri.host == "invite" -> uri.lastPathSegment ?: uri.getQueryParameter("token")
            uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "kukechat" -> runCatching { uri.getQueryParameter("invite") }.getOrNull()
            else -> null
        }?.trim()
        return token?.takeIf { tokenPattern.matches(it) }
    }
}

private class InviteInfo(
    val isGroup: Boolean,
    val targetId: Long,
    val title: String,
    val avatarUrl: String?,
    val subtitle: String,
    val description: String?,
    val alreadyJoined: Boolean,
    val alreadyFriends: Boolean,
    /**
     * 群邀请点「加入」会怎样（看邀请人此刻在群里的身份，服务端 docs/chat-features.md 第 14 节）：
     * join 直接进群；request 提交入群申请；answer 要先回答入群问题再提交申请；forbidden 仅群主 / 管理员能邀请。
     * 旧服务端不返回，当 join。
     */
    val joinAction: String,
    val joinQuestion: String?,
)

/** POST /invites/{token}/accept 失败时给人看的话：422 是没答入群问题，403 是这个群只能由群主 / 管理员邀请 */
private fun inviteAcceptError(e: Throwable): String {
    val api = e as? ApiException ?: return e.userMessage()
    return when (api.status) {
        422 -> "请先回答入群问题"
        403 -> api.message.takeIf { msg -> msg.any { it.code >= 0x4E00 } } ?: "这个群只能由群主或管理员邀请加入"
        404 -> "邀请链接已失效，或者对应的群聊不存在了"
        else -> e.userMessage()
    }
}

/**
 * 打开一条邀请链接：先 GET /invites/{token} 看是哪个群 / 谁，确认后 POST /invites/{token}/accept。
 * 加群成功直接进群聊；普通成员发的邀请在要审核的群里会落成入群申请（status = pending），不进群；
 * 加好友是发出申请（对方同意后成为好友）。
 */
@Composable
fun InviteScreen(graph: AppGraph, token: String) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<InviteInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    // 回答入群问题（join_action = answer）
    var answer by remember { mutableStateOf("") }
    // 已经落成入群申请、等管理员审核
    var submitted by remember { mutableStateOf(false) }
    val path = "/invites/${URLEncoder.encode(token, "UTF-8")}"

    LaunchedEffect(token, attempt) {
        error = null
        runCatching { graph.api.get(path).asObj() ?: error("邀请链接无效") }
            .onSuccess { obj ->
                val group = obj.o("group")
                val user = obj.o("user")
                val isGroup = obj.s("target_type") == "group"
                info = InviteInfo(
                    isGroup = isGroup,
                    targetId = obj.l("target_id") ?: 0,
                    title = if (isGroup) group?.s("display_title")?.takeIf { it.isNotBlank() } ?: group?.s("title") ?: "群聊"
                    else user?.s("nickname")?.takeIf { it.isNotBlank() } ?: user?.s("username") ?: "用户",
                    avatarUrl = (if (isGroup) group else user)?.s("avatar_url"),
                    subtitle = if (isGroup) "群号 ${group?.l("id") ?: obj.l("target_id")} · ${group?.i("member_count") ?: 0} 名成员" else "@${user?.s("username").orEmpty()}",
                    description = (if (isGroup) group?.s("description") else user?.s("bio"))?.takeIf { it.isNotBlank() },
                    alreadyJoined = obj.b("already_joined") == true,
                    alreadyFriends = obj.b("already_friends") == true,
                    joinAction = if (isGroup) obj.s("join_action")?.takeIf { it.isNotBlank() } ?: "join" else "join",
                    joinQuestion = group?.s("join_question")?.trim()?.takeIf { it.isNotBlank() },
                )
            }
            .onFailure { error = if ((it as? ApiException)?.status == 404) "邀请链接已失效，或者对应的群聊 / 用户不存在了" else it.userMessage() }
    }

    KcPage(title = "邀请", onBack = { nav.back() }) {
        val current = info
        when {
            error != null && current == null -> StateText(error!!, error = true, onRetry = { attempt++ })
            current == null -> StateText("正在读取邀请…")
            else -> {
                val pop = remember { Animatable(0.92f) }
                LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 400f)) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.padding(top = 24.dp))
                    Column(
                        Modifier.widthIn(max = 380.dp).fillMaxWidth().graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.92f) / 0.08f).coerceIn(0f, 1f) }
                            .clip(RoundedCornerShape(20.dp)).background(colors.panel).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(if (current.isGroup) "邀请你加入群聊" else "邀请你添加好友", style = type.caption, color = colors.muted)
                        Avatar(current.avatarUrl, current.title, tone = current.targetId, size = 76.dp)
                        Text(current.title, style = type.title, color = colors.text, textAlign = TextAlign.Center)
                        Text(current.subtitle, style = type.caption, color = colors.muted)
                        current.description?.let { Text(it, style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, maxLines = 4) }
                        val action = current.joinAction
                        val needsAnswer = current.isGroup && !current.alreadyJoined && action == "answer" && !submitted
                        if (needsAnswer) {
                            Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("入群问题", style = type.caption, color = colors.muted)
                                Text(current.joinQuestion ?: "请回答群主设置的问题", style = type.body, color = colors.text)
                                KcTextField(answer, { answer = it.take(200) }, "你的回答（必填）", modifier = Modifier.fillMaxWidth(), singleLine = false, minHeight = 72.dp)
                            }
                        }
                        if (submitted) Text("已提交申请，等待管理员审核", style = type.secondary, color = colors.muted, textAlign = TextAlign.Center)
                        Box(Modifier.padding(top = 6.dp))
                        val forbidden = current.isGroup && !current.alreadyJoined && action == "forbidden"
                        val label = when {
                            submitted -> "返回"
                            done != null -> done!!
                            current.alreadyJoined -> "进入群聊"
                            current.alreadyFriends -> "查看资料"
                            busy -> "处理中…"
                            forbidden -> "需要群主或管理员邀请"
                            current.isGroup && action == "request" -> "申请加入"
                            current.isGroup && action == "answer" -> "提交申请"
                            current.isGroup -> "加入群聊"
                            else -> "添加好友"
                        }
                        KcButton(label, {
                            when {
                                submitted -> nav.back()
                                current.alreadyJoined -> nav.go(ChatRoute(current.targetId))
                                current.alreadyFriends -> nav.go(UserRoute(current.targetId))
                                done != null -> nav.back()
                                forbidden -> Unit
                                else -> {
                                    busy = true
                                    scope.launch {
                                        val body = if (current.isGroup && action == "answer") buildJsonObject { put("answer", answer.trim()) } else null
                                        runCatching { graph.api.post("$path/accept", body).asObj() }
                                            .onSuccess { result ->
                                                val conversationId = result?.l("conversation_id")
                                                when {
                                                    // 邀请人是普通成员、群要审核：落成了入群申请，不进群
                                                    current.isGroup && result?.s("status") == "pending" -> {
                                                        submitted = true
                                                        toast(context, "已提交申请，等待管理员审核")
                                                    }
                                                    current.isGroup && conversationId != null -> {
                                                        graph.conversations.refresh()
                                                        toast(context, "已加入「${current.title}」")
                                                        nav.back()
                                                        nav.go(ChatRoute(conversationId))
                                                    }
                                                    else -> done = if (result?.s("status") == "self") "这是你自己的名片" else "好友申请已发送"
                                                }
                                            }
                                            // 409 group_full：群满员，服务端给的是中文原因；422 没答问题；403 仅限群主 / 管理员邀请
                                            .onFailure { toast(context, inviteAcceptError(it)) }
                                        busy = false
                                    }
                                }
                            }
                        }, enabled = !busy && !forbidden && !(needsAnswer && answer.isBlank()), modifier = Modifier.fillMaxWidth())
                    }
                    if (current.isGroup) Row(verticalAlignment = Alignment.CenterVertically) {
                        KcIcon(KcIcons.shieldCheck, tint = colors.subtle, size = 14.dp)
                        val hint = when (current.joinAction) {
                            "request", "answer" -> "这个群需要管理员审核，提交后等待通过"
                            "forbidden" -> "这个群只能由群主或管理员邀请加入"
                            else -> "通过邀请链接加群不需要审核"
                        }
                        Text(hint, style = type.caption, color = colors.subtle, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}
