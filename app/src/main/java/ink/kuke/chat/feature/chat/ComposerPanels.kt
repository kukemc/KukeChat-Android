package ink.kuke.chat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import java.net.URLEncoder

/** 输入框里光标前正在打的 @ 查询（没有就是 null）：从最近的 @ 到光标之间不含空白、不超过 20 字 */
internal fun mentionQueryAt(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor <= 0 || cursor > text.length) return null
    val at = text.lastIndexOf('@', cursor - 1)
    if (at < 0) return null
    // @ 前面如果是字母数字，多半是邮箱，不弹
    if (at > 0 && text[at - 1].isLetterOrDigit()) return null
    val query = text.substring(at + 1, cursor)
    if (query.length > 20 || query.any { it.isWhitespace() }) return null
    return at to query
}

/**
 * @成员联想（群聊）：输入 @ 弹出，GET /conversations/{id}/members/search?q=&limit=12；
 * 空查询时给前 12 个成员。管理员多一项「全体成员」。选中后 Composer 把 @名字 写进文字并记下 user_id（发送时写进 metadata.mentions）。
 */
@Composable
internal fun MentionPanel(controller: ChatController, query: String, canMentionAll: Boolean, onPick: (MemberLite?) -> Unit) {
    val colors = KcTheme.colors
    var members by remember { mutableStateOf<List<MemberLite>>(emptyList()) }
    LaunchedEffect(query) {
        delay(if (query.isEmpty()) 0 else 200)
        val cid = controller.conversationId
        runCatching {
            val element = if (query.isEmpty()) controller.graph.api.get("/conversations/$cid/members?limit=12&offset=0")
            else controller.graph.api.get("/conversations/$cid/members/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=12")
            parseMemberPage(element).items
        }.onSuccess { list -> members = list.filter { it.userId != controller.myId } }
    }
    val showAll = canMentionAll && ("全体成员".contains(query) || query.isEmpty())
    if (members.isEmpty() && !showAll) return
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(14.dp))
            .background(colors.raised, RoundedCornerShape(14.dp))
            .heightIn(max = 240.dp),
    ) {
        LazyColumn {
            if (showAll) item(key = "all") {
                Row(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("@全体成员", style = KcTheme.type.rowTitle, color = colors.accent)
                }
            }
            items(members, key = { it.userId }) { member ->
                Row(Modifier.fillMaxWidth().clickable { onPick(member) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(member.avatarUrl, member.name, tone = member.userId, size = 32.dp)
                    Text(member.name, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    if (member.isOwner || member.isAdmin) Text(if (member.isOwner) "群主" else "管理员", style = KcTheme.type.caption, color = colors.warn)
                }
            }
        }
    }
}

/** 机器人指令面板：输入 / 弹出，列出本群机器人的指令（GET /bots/conversations/{id}/bots），按已输入的前缀过滤 */
@Composable
internal fun CommandPanel(controller: ChatController, prefix: String, onPick: (BotCommand) -> Unit) {
    val colors = KcTheme.colors
    LaunchedEffect(Unit) { controller.loadBotCommands() }
    val all = controller.botCommands.value ?: return
    val list = all.filter { it.command.startsWith(prefix, ignoreCase = true) }
    if (list.isEmpty()) return
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(14.dp))
            .background(colors.raised, RoundedCornerShape(14.dp))
            .heightIn(max = 240.dp),
    ) {
        LazyColumn {
            items(list, key = { "${it.botId}${it.command}" }) { command ->
                Row(Modifier.fillMaxWidth().clickable { onPick(command) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(command.botAvatar, command.botName, tone = command.botId, size = 30.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(command.signature, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            command.botName + if (command.description.isNotBlank()) " · ${command.description}" else "",
                            style = KcTheme.type.caption,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
