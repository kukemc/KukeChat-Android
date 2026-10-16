package ink.kuke.chat.feature.space

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.chat.ReadOnlyMessageHost
import ink.kuke.chat.feature.chat.SnapshotMessageRow
import ink.kuke.chat.core.ui.messagePreview
import ink.kuke.chat.feature.settings.LoadBox
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.feature.settings.rememberLoader
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 收藏：我在各个会话里收藏的消息（GET /conversations/bookmarks/all），按 全部 / 私聊 / 群聊 筛选。
 * 点一条进入它所在的会话。
 */
@Composable
fun FavoritesScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val colors = KcTheme.colors
    val type = KcTheme.type
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("favorites")) {
        val element = graph.api.get("/conversations/bookmarks/all")
        val array = (element as? JsonArray) ?: ((element as? JsonObject)?.get("items") as? JsonArray) ?: JsonArray(emptyList())
        KcJson.decodeFromJsonElement(ListSerializer(Message.serializer()), array)
    }
    var filter by remember { mutableIntStateOf(0) }

    // 宿主放在页面外层：嵌套合并转发详情 / 看大图是整页覆盖，不能被收藏页自己的顶栏夹住
    ReadOnlyMessageHost(graph) { actions ->
    KcPage(title = "收藏", onBack = { nav.back() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Segmented(listOf("全部", "私聊", "群聊"), filter, { filter = it })
            }
            LoadBox(loader) { all ->
                val byId = conversations.associateBy { it.id }
                val shown = all.filter { message ->
                    val conversation = byId[message.conversationId]
                    when (filter) {
                        1 -> conversation?.isGroup == false
                        2 -> conversation?.isGroup == true
                        else -> true
                    }
                }
                if (shown.isEmpty()) {
                    // 空状态也要能下拉刷新，所以放进可滚动容器
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                KcIcon(KcIcons.bookmark, tint = colors.subtle, size = 32.dp)
                                Text(
                                    if (all.isEmpty()) "还没有收藏的消息" else "这个分类下没有收藏",
                                    style = type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp),
                                )
                                Text("在聊天里长按消息，选择「收藏」，就会出现在这里。", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
                            }
                        }
                    }
                } else {
                    // 和聊天页同一个渲染器：图片 / 表情 / 语音 / 投票 / 红包 / 名片 / 机器人消息 / 嵌套合并转发都能正常显示
                    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.id }) { message ->
                            val conversation = byId[message.conversationId]
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel)) {
                                SnapshotMessageRow(
                                    message,
                                    actions,
                                    footer = "来自 " + (conversation?.name() ?: "会话 ${message.conversationId}"),
                                    onClick = { nav.go(ChatRoute(message.conversationId, message.id)) },
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
