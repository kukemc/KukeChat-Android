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

/** 临时会话屏蔽：被我屏蔽的陌生人（对方不是好友的临时会话），可以解除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocksScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val social = remember(graph) { SocialApi(graph.api) }
    val toast = rememberToast()

    // 上次的屏蔽列表（内存）：先摆出来，再静默刷新
    val cache = remember(graph) { graph.screenCache.slot<List<TempBlock>>("contacts.blocks") }
    var state by remember { mutableStateOf<Load<List<TempBlock>>>(cache.get()?.let { Load.Ready(it) } ?: Load.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirm by remember { mutableStateOf<TempBlock?>(null) }

    suspend fun load(silent: Boolean) {
        if (!silent) state = Load.Loading
        runCatching { social.tempBlocks() }
            .onSuccess { state = Load.Ready(it); cache.put(it) }
            .onFailure { if (!silent || state !is Load.Ready) state = Load.Failed(it.userMessage()) }
    }
    LaunchedEffect(graph) { load(silent = true) }

    KcPage(title = "临时会话屏蔽", onBack = { nav.back() }) {
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; load(true); refreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val current = state) {
                    Load.Loading -> LoadingBox()
                    is Load.Failed -> ErrorRetry(current.message, onRetry = { scope.launch { load(false) } })
                    is Load.Ready -> if (current.value.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("还没有屏蔽任何临时会话", style = type.secondary, color = colors.muted)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                            item(key = "note") {
                                Text(
                                    "屏蔽后，对方不能再通过临时会话给你发消息。解除屏蔽后可以重新联系你。",
                                    style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(current.value, key = { it.id }) { block ->
                                val user = block.blockedUser
                                val name = user?.displayName ?: "用户 ${block.blockedUserId}"
                                Row(
                                    Modifier.fillMaxWidth().background(colors.panel).clickable { nav.go(UserRoute(block.blockedUserId)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Avatar(user?.avatarUrl, name, block.blockedUserId, 44.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("屏蔽于 ${relativeTime(block.createdAt)}", style = type.caption, color = colors.muted)
                                    }
                                    SmallButton("解除屏蔽", enabled = block.id !in removing) { confirm = block }
                                }
                            }
                        }
                    }
                }
            }
            ToastHost(toast)
        }
    }

    confirm?.let { block ->
        ConfirmDialog(
            title = "解除屏蔽",
            message = "解除后「${block.blockedUser?.displayName ?: "对方"}」可以再次通过临时会话联系你。",
            confirmLabel = "解除",
            onConfirm = {
                confirm = null
                removing = removing + block.id
                scope.launch {
                    runCatching { social.deleteTempBlock(block.id) }
                        .onSuccess { toast.show("已解除屏蔽"); load(true) }
                        .onFailure { toast.show(it.userMessage()) }
                    removing = removing - block.id
                }
            },
            onDismiss = { confirm = null },
        )
    }
}
