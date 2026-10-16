package ink.kuke.chat.feature.contacts

import ink.kuke.chat.core.data.slot

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/** 在线用户：GET /users/online，点人进资料页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineUsersScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val social = remember(graph) { SocialApi(graph.api) }

    // 上次的在线列表（内存）：先摆出来，再静默刷新
    val cache = remember(graph) { graph.screenCache.slot<OnlineUsers>("online.users") }
    var state by remember { mutableStateOf<Load<OnlineUsers>>(cache.get()?.let { Load.Ready(it) } ?: Load.Loading) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load(silent: Boolean) {
        if (!silent) state = Load.Loading
        runCatching { social.onlineUsers() }
            .onSuccess { state = Load.Ready(it); cache.put(it) }
            .onFailure { if (!silent || state !is Load.Ready) state = Load.Failed(it.userMessage()) }
    }
    LaunchedEffect(graph) { load(silent = true) }

    val count = (state as? Load.Ready)?.value?.onlineCount
    KcPage(title = if (count != null) "在线用户 · $count" else "在线用户", onBack = { nav.back() }) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(true); refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val current = state) {
                Load.Loading -> LoadingBox()
                is Load.Failed -> ErrorRetry(current.message, onRetry = { scope.launch { load(false) } })
                is Load.Ready -> if (current.value.users.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("现在没有其他人在线", style = type.secondary, color = colors.muted) }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(current.value.users, key = { it.id }) { user ->
                            Row(
                                Modifier.fillMaxWidth().background(colors.panel).clickable { nav.go(UserRoute(user.id)) }.padding(horizontal = 16.dp).height(64.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(user.avatarUrl, user.displayName, user.id, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(user.displayName, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${presenceLabel(user)} · ${user.bio?.takeIf { it.isNotBlank() } ?: "这个人很神秘，还没有简介"}",
                                        style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
