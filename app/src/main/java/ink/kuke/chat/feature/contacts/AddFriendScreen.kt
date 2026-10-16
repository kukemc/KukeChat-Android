package ink.kuke.chat.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/**
 * 找人 / 加好友：按用户名、邮箱或昵称搜索 → 发好友申请（可带一句附加消息）。
 * 已经是好友 / 已经申请过的人，按钮显示状态而不是操作。
 */
@Composable
fun AddFriendScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val social = remember(graph) { SocialApi(graph.api) }
    val me = graph.session.session.value?.user
    val toast = rememberToast()

    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Load<List<User>>?>(null) }
    val friendsCache = remember(graph) { graph.screenCache.friendsSlot() }
    var friendIds by remember { mutableStateOf(friendsCache.get().orEmpty().mapNotNull { it.other?.id }.toSet()) }
    var pendingIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val sending = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(graph) {
        runCatching { social.friends() }.onSuccess { list -> friendIds = list.mapNotNull { it.other?.id }.toSet(); friendsCache.put(list) }
        runCatching { social.outgoingFriendRequests() }.onSuccess { list -> pendingIds = list.filter { it.status == "pending" }.map { it.receiverId }.toSet() }
    }

    fun search() {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        result = Load.Loading
        scope.launch {
            runCatching { social.searchUsers(keyword) }
                .onSuccess { result = Load.Ready(it) }
                .onFailure { result = Load.Failed(it.userMessage()) }
        }
    }

    KcPage(title = "找人", onBack = { nav.back() }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.background(colors.panel).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KcTextField(query, { query = it }, "用户名、邮箱或昵称", Modifier.weight(1f), onDone = ::search)
                        Spacer(Modifier.width(8.dp))
                        SmallButton("搜索", primary = true, enabled = query.isNotBlank()) { search() }
                    }
                    Spacer(Modifier.height(10.dp))
                    KcTextField(note, { if (it.length <= 60) note = it }, "附加消息（可选，例如：我是 …）", icon = KcIcons.message)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                when (val state = result) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Text("输入对方的用户名或邮箱，找到人再发好友申请", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 48.dp, start = 32.dp, end = 32.dp))
                    }
                    Load.Loading -> LoadingBox("正在搜索…")
                    is Load.Failed -> ErrorRetry(state.message, onRetry = ::search)
                    is Load.Ready -> if (state.value.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Text("没有找到这个人", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 48.dp))
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(state.value, key = { it.id }) { user ->
                                val isSelf = user.id == me?.id
                                val isFriend = user.id in friendIds
                                val isPending = user.id in pendingIds
                                Row(
                                    Modifier.fillMaxWidth().background(colors.panel).clickable { nav.go(UserRoute(user.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(user.avatarUrl, user.displayName, user.id, 44.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(user.displayName, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("@${user.username}", style = type.caption, color = colors.muted, maxLines = 1)
                                    }
                                    if (!isSelf) {
                                        Spacer(Modifier.width(8.dp))
                                        val label = if (isFriend) "已是好友" else if (isPending) "已申请" else "加好友"
                                        SmallButton(label, primary = !isFriend && !isPending, enabled = !isFriend && !isPending && sending[user.id] != true) {
                                            sending[user.id] = true
                                            scope.launch {
                                                runCatching { social.sendFriendRequest(user.id, note) }
                                                    .onSuccess { pendingIds = pendingIds + user.id; toast.show("好友申请已发送") }
                                                    .onFailure { toast.show(it.userMessage()) }
                                                sending.remove(user.id)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ToastHost(toast)
        }
    }
}
