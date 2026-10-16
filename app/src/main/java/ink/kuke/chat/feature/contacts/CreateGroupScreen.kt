package ink.kuke.chat.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 创建群聊：起个群名、从好友里勾选成员（可以先不选），创建成功直接进入群聊。 */
@Composable
fun CreateGroupScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val social = remember(graph) { SocialApi(graph.api) }
    val toast = rememberToast()

    // 好友列表和联系人页共用一格缓存：有就先摆出来，再静默刷新
    val friendsCache = remember(graph) { graph.screenCache.friendsSlot() }
    fun sorted(list: List<Friendship>): List<User> = list.mapNotNull { it.other }.sortedWith { a, b -> PinyinIndex.compare(a.displayName, b.displayName) }
    var friends by remember { mutableStateOf<Load<List<User>>>(friendsCache.get()?.let { Load.Ready(sorted(it)) } ?: Load.Loading) }
    var title by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        if (friends !is Load.Ready) friends = Load.Loading
        runCatching { social.friends() }
            .onSuccess { list ->
                friends = Load.Ready(sorted(list))
                friendsCache.put(list)
            }
            .onFailure { if (friends !is Load.Ready) friends = Load.Failed(it.userMessage()) }
    }

    fun create() {
        if (creating || title.isBlank()) return
        creating = true
        scope.launch {
            runCatching { social.createGroup(title, selected) }
                .onSuccess { id ->
                    graph.conversations.refreshSoon(0)
                    nav.back()
                    nav.go(ChatRoute(id))
                }
                .onFailure { toast.show(it.userMessage()) }
            creating = false
        }
    }

    KcPage(
        title = "创建群聊",
        onBack = { nav.back() },
        actions = {
            Box(Modifier.height(44.dp).padding(end = 8.dp), contentAlignment = Alignment.Center) {
                SmallButton(if (creating) "创建中…" else "创建", primary = true, enabled = title.isNotBlank() && !creating) { create() }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.background(colors.panel).padding(16.dp)) {
                    KcTextField(title, { if (it.length <= 40) title = it }, "群名称", icon = KcIcons.users)
                    Spacer(Modifier.height(8.dp))
                    KcTextField(query, { query = it }, "搜索好友")
                    Spacer(Modifier.height(8.dp))
                    Text(if (selected.isEmpty()) "还没有选成员，创建后也可以再邀请" else "已选 ${selected.size} 位成员", style = type.caption, color = colors.muted)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                when (val state = friends) {
                    Load.Loading -> LoadingBox("正在加载好友…")
                    is Load.Failed -> ErrorRetry(state.message, onRetry = { reload++ })
                    is Load.Ready -> {
                        val keyword = query.trim().lowercase()
                        val shown = state.value.filter { keyword.isEmpty() || it.displayName.lowercase().contains(keyword) || it.username.lowercase().contains(keyword) }
                        if (shown.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Text(if (state.value.isEmpty()) "还没有好友，先去找人吧" else "没有匹配的好友", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 48.dp))
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                                items(shown, key = { it.id }) { user ->
                                    val checked = user.id in selected
                                    Row(
                                        Modifier.fillMaxWidth().background(colors.panel)
                                            .clickable { selected = if (checked) selected - user.id else selected + user.id }
                                            .padding(horizontal = 16.dp).height(60.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Avatar(user.avatarUrl, user.displayName, user.id, 40.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Text(user.displayName, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Box(
                                            Modifier.size(22.dp).clip(CircleShape)
                                                .background(if (checked) colors.fill else colors.panel)
                                                .then(if (checked) Modifier else Modifier.border(1.5.dp, colors.borderStrong, CircleShape)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (checked) KcIcon(KcIcons.check, tint = colors.onFill, size = 14.dp)
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
