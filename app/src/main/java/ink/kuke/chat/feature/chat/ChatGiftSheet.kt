package ink.kuke.chat.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.feature.gifts.GiftRecipient
import ink.kuke.chat.feature.gifts.GiftSendSheet
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 聊天里的送礼入口：面板本身在 feature/gifts/GiftSendSheet（个人主页也用它），这里只管「送给谁」。
 * 私聊里收礼人就是对方；群聊里先选一位群成员（选人面板叠在送礼面板上面，选好回来，面板里已经填的东西都还在）。
 * 送出后回包里的礼物消息直接落进列表，动效由聊天页的 GiftStage 看到新礼物消息时播。
 */
@Composable
internal fun GiftSheet(controller: ChatController, conversation: Conversation?, onDismiss: () -> Unit) {
    val isGroup = conversation?.isGroup == true
    val direct = conversation?.directUser
    var recipient by remember {
        mutableStateOf(direct?.let { GiftRecipient(it.id, it.displayName, it.avatarUrl, it.isBirthdayToday) })
    }
    var picking by remember { mutableStateOf(isGroup && recipient == null) }

    // 群里一进来先选人：选好之前只露出选人面板，不叠两层遮罩
    if (!(picking && recipient == null)) GiftSendSheet(
        graph = controller.graph,
        recipient = recipient,
        conversationId = controller.conversationId,
        onChangeRecipient = if (isGroup) ({ picking = true }) else null,
        onDismiss = onDismiss,
        onSent = { result -> controller.accept(result.message) },
    )
    if (picking) {
        MemberPickSheet(controller, title = "送礼物给谁", excludeMe = true, onDismiss = { if (recipient == null) onDismiss() else picking = false }) {
            recipient = GiftRecipient(it.userId, it.name, it.avatarUrl)
            picking = false
        }
    }
}

/** 选一位群成员（带搜索）：送礼选收礼人用 */
@Composable
internal fun MemberPickSheet(controller: ChatController, title: String, excludeMe: Boolean, onDismiss: () -> Unit, onPick: (MemberLite) -> Unit) {
    val colors = KcTheme.colors
    var query by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<MemberLite>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(query, attempt) {
        kotlinx.coroutines.delay(if (query.isEmpty()) 0 else 300)
        error = null
        runCatching {
            val cid = controller.conversationId
            if (query.isBlank()) parseMemberPage(controller.graph.api.get("/conversations/$cid/members?limit=50&offset=0")).items
            else parseMemberPage(controller.graph.api.get("/conversations/$cid/members/search?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&limit=30")).items
        }.onSuccess { members = it }.onFailure { error = it.userMessage() }
    }
    KcSheet(onDismiss = onDismiss, title = title, maxHeight = 560.dp) {
        Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) { KcTextField(query, { query = it }, "搜索成员", Modifier.fillMaxWidth()) }
        val list = members?.filter { !(excludeMe && it.userId == controller.myId) && !it.isBot }
        when {
            error != null -> StateText(error!!, error = true, onRetry = { attempt++ })
            list == null -> StateText("加载中…")
            list.isEmpty() -> StateText("没有找到成员")
            else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(list, key = { it.userId }) { member ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(member) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(member.avatarUrl, member.name, tone = member.userId, size = 38.dp)
                        Text(member.name, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    }
                }
            }
        }
    }
}
