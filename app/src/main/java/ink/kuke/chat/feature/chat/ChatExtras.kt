package ink.kuke.chat.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.invite.InviteLinks
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ------------------------------------------------------------------ 「+1」复读

/** 能被复读的内容：文字原样再发；表情（贴图）按同一张图再发 */
internal data class RepeatContent(val key: String, val text: String?, val stickerUrl: String?, val groupEmojiId: Long?)

/** 太长的消息复读出去就是刷屏（同电脑版 REPEAT_MAX_LENGTH） */
private const val REPEAT_MAX_LENGTH = 200

/** 一条消息能不能被「+1」复读出去（同电脑版 repeatableContentOf，额外认贴图）；不能返回 null */
internal fun repeatableContentOf(message: Message?): RepeatContent? {
    if (message == null || message.isRecalled) return null
    return when (message.type) {
        "text" -> {
            // 机器人的 Markdown / 交互元素、带引用 / 图片 / 名片的消息复读出去都会走样
            if (isBotRichMessage(message) || Meta.isMarkdown(message) || Meta.quote(message) != null || Meta.images(message).isNotEmpty() || Meta.card(message) != null) return null
            val text = message.content.trim()
            if (text.isEmpty() || text.length > REPEAT_MAX_LENGTH) null else RepeatContent("t:$text", text, null, null)
        }
        "image" -> if (Meta.isSticker(message)) {
            val url = message.content.trim().takeIf { it.isNotEmpty() } ?: return null
            RepeatContent("s:$url", null, url, message.metadata?.l("group_emoji_id"))
        } else null
        else -> null
    }
}

/** 列表最后两条内容相同 → 返回要挂「+1」的那条（最新一条）和内容；被新消息顶下去就消失（同电脑版 MessageList） */
internal fun repeatTarget(messages: List<Message>): Pair<Long, RepeatContent>? {
    if (messages.size < 2) return null
    val last = messages[messages.size - 1]
    val content = repeatableContentOf(last) ?: return null
    return if (repeatableContentOf(messages[messages.size - 2])?.key == content.key) last.id to content else null
}

/** 气泡旁的「+1」小胶囊：出现时弹一下，点一下跟着复读 */
@Composable
internal fun RepeatPill(enabled: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
    Box(
        Modifier
            .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value.coerceIn(0f, 1f) }
            .pressScale(press, 0.86f)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text("+1", style = KcTheme.type.caption.copy(fontWeight = FontWeight.W700), color = if (enabled) colors.accent else colors.subtle)
    }
}

/** 复读：原样把同一句 / 同一张表情再发一次 */
internal fun ChatController.repeat(content: RepeatContent): Boolean = when {
    content.text != null -> sendText(content.text)
    content.stickerUrl != null -> sendSticker(content.stickerUrl, content.groupEmojiId)
    else -> false
}

// ------------------------------------------------------------------ 临时会话

/**
 * 临时会话提示条（对方不是好友）：加好友 · 关闭会话 · 屏蔽此人（同电脑版 MessagePanel 顶栏的三个按钮）。
 * 关闭 / 屏蔽后会话从列表消失，聊天页随之自动退出。
 */
@Composable
internal fun TempConversationBanner(graph: AppGraph, conversation: Conversation, onLeft: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val other = conversation.directUser

    fun act(path: String, success: String) {
        busy = true
        scope.launch {
            runCatching { graph.api.post("/conversations/${conversation.id}/temporary/$path") }
                .onSuccess {
                    toast(context, success)
                    graph.conversations.refresh()
                    onLeft()
                }
                .onFailure { toast(context, it.userMessage()) }
            busy = false
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp).clip(RoundedCornerShape(12.dp)).background(colors.warn.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(KcIcons.shield, tint = colors.warn, size = 16.dp)
        Text("临时会话 · 对方还不是你的好友", style = type.caption, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (other != null && !requested) BannerAction("加好友", colors.accent, enabled = !busy) {
            busy = true
            scope.launch {
                runCatching {
                    graph.api.post("/friends/requests", buildJsonObject { put("receiver_id", other.id) })
                }.onSuccess { requested = true; toast(context, "好友申请已发送") }.onFailure { toast(context, it.userMessage()) }
                busy = false
            }
        }
        BannerAction("关闭", colors.muted, enabled = !busy) { confirm = "close" }
        BannerAction("屏蔽", colors.danger, enabled = !busy) { confirm = "block" }
    }

    when (confirm) {
        "close" -> KcConfirm("关闭临时会话", "会话会从列表移除，对方之后仍然可以再联系你。", "关闭", onConfirm = {
            confirm = null
            act("close", "临时会话已关闭")
        }, onDismiss = { confirm = null })
        "block" -> KcConfirm("屏蔽此人的临时会话", "屏蔽后「${other?.displayName ?: "对方"}」不能再通过临时会话给你发消息。可以在「联系人 · 临时会话屏蔽」里解除。", "屏蔽", danger = true, onConfirm = {
            confirm = null
            act("block", "已屏蔽此人的临时会话")
        }, onDismiss = { confirm = null })
    }
}

@Composable
private fun BannerAction(label: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    val press = rememberInteraction()
    Text(
        label,
        style = KcTheme.type.caption.copy(fontWeight = FontWeight.W600),
        color = color.copy(alpha = if (enabled) 1f else 0.5f),
        modifier = Modifier.pressScale(press, 0.92f).clip(RoundedCornerShape(8.dp)).clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ 游戏模式

/** 游戏模式下玩家从作品里发来的消息（metadata.game_mode，服务端 game_mode.py） */
internal fun isGameMessage(message: Message): Boolean = message.metadata?.b("game_mode") == true

/** 消息下方的小注脚「来自作品内」：对齐气泡那一侧（他人消息让开头像） */
@Composable
internal fun GameSourceNote(mine: Boolean) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp, start = if (mine) 0.dp else 46.dp),
        horizontalArrangement = if (mine) androidx.compose.foundation.layout.Arrangement.End else androidx.compose.foundation.layout.Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(KcIcons.gamepad2, tint = colors.subtle, size = 12.dp)
        Text("来自作品内聊天", style = KcTheme.type.caption, color = colors.subtle, modifier = Modifier.padding(start = 3.dp))
    }
}

// ------------------------------------------------------------------ 邀请链接

/** 聊天里点到邀请链接（?invite=…）就进应用内的邀请页，其余链接照常交给系统 */
internal class InviteAwareUriHandler(private val fallback: UriHandler, private val onInvite: (String) -> Unit) : UriHandler {
    override fun openUri(uri: String) {
        val token = InviteLinks.tokenOf(uri)
        if (token != null) onInvite(token) else fallback.openUri(uri)
    }
}
