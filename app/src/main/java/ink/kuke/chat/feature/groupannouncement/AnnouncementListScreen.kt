package ink.kuke.chat.feature.groupannouncement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.groupplus.PlusButton
import ink.kuke.chat.feature.groupplus.plusInk
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.GroupAnnouncementEditRoute
import ink.kuke.chat.ui.shell.GroupAnnouncementRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import java.util.concurrent.ConcurrentHashMap

/** 每个群上次拉到的公告列表：再进来先摆出来，再静默刷新 */
private val lastLists = ConcurrentHashMap<Long, List<GroupAnnouncement>>()

/**
 * 群公告列表（从群资料页、聊天顶部的公告条进来）。顺序以服务端为准：置顶在前，其余按发布时间从新到旧。
 * 每条：发布人 · 时间 · 置顶，正文前三行 + 首图，需确认 / 已确认；群主 / 管理员多一个右上角「发布」，并能看到确认人数。
 */
@Composable
fun AnnouncementListScreen(graph: AppGraph, conversationId: Long) {
    val nav = LocalAppNav.current
    val k = plusInk()
    val api = remember(graph) { GroupAnnouncementApi(graph) }
    val conversation = graph.conversations.items.collectAsStateWithLifecycle().value.firstOrNull { it.id == conversationId }
    val manager = isGroupManager(conversation?.myRole)
    var items by remember(conversationId) { mutableStateOf(lastLists[conversationId]) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(conversationId, attempt) {
        error = null
        runCatching { api.list(conversationId) }
            .onSuccess {
                items = it
                lastLists[conversationId] = it
            }
            .onFailure { if (items == null) error = it.userMessage() }
    }
    // 别人发布 / 修改 / 删除、有人确认：重拉
    LaunchedEffect(conversationId) {
        AnnouncementHub.changes.collect { (cid, _) -> if (cid == conversationId) attempt++ }
    }

    KcPage(
        title = "群公告",
        onBack = nav::back,
        subtitle = conversation?.name(),
        actions = {
            if (manager) PlusButton("发布", { nav.go(GroupAnnouncementEditRoute(conversationId)) }, modifier = Modifier.padding(end = 10.dp), height = 34.dp)
        },
    ) {
        Box(Modifier.fillMaxSize().background(KcTheme.colors.panel)) {
            val list = items
            when {
                list == null && error != null -> StateText(error!!, error = true, onRetry = { attempt++ })
                list == null -> StateText("加载中…")
                list.isEmpty() -> EmptyAnnouncements(manager) { nav.go(GroupAnnouncementEditRoute(conversationId)) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
                    itemsIndexed(list, key = { _, item -> item.id }) { index, item ->
                        AnnouncementRow(item, manager) { nav.go(GroupAnnouncementRoute(conversationId, item.id)) }
                        if (index < list.lastIndex) AnnDivider()
                    }
                    item { Box(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

@Composable
private fun EmptyAnnouncements(manager: Boolean, onCreate: () -> Unit) {
    val k = plusInk()
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(k.wash), contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.announcement, tint = k.sub, size = 24.dp)
        }
        Text("还没有群公告", color = k.ink, fontSize = 16.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(top = 16.dp))
        Text(
            if (manager) "发布的公告会出现在群聊里，也可以设为弹窗、要求成员确认收到" else "群主或管理员发布的公告会显示在这里",
            color = k.sub, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
        if (manager) PlusButton("发布公告", onCreate, modifier = Modifier.padding(top = 22.dp))
    }
}

@Composable
private fun AnnouncementRow(item: GroupAnnouncement, manager: Boolean, onClick: () -> Unit) {
    val k = plusInk()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(item.author?.avatarUrl, item.authorName, tone = item.authorId ?: 0, size = 24.dp)
            Text(
                item.authorName, color = k.ink, fontSize = 13.5.sp, fontWeight = FontWeight.W500, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
            Text(" · ${announcementTime(item.createdAt)}", color = k.sub, fontSize = 12.5.sp, maxLines = 1)
            Box(Modifier.weight(1f))
            if (!item.mySeen) Box(Modifier.padding(start = 8.dp).size(7.dp).clip(CircleShape).background(KcTheme.colors.unread))
            if (item.pinned) AnnTag("置顶", Modifier.padding(start = 8.dp), strong = true)
        }
        Row(Modifier.padding(top = 10.dp)) {
            Text(
                item.content, color = k.ink, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.images.firstOrNull()?.let { first ->
                AnnouncementThumb(first.preview, item.images.size, 64.dp, Modifier.padding(start = 12.dp))
            }
        }
        val edited = item.editedAt != null
        if (item.requireConfirm || edited) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.requireConfirm) {
                    if (item.myConfirmed) AnnTag("已确认", faint = true) else AnnTag("需确认")
                    if (manager) Text("${item.confirmedCount}/${item.memberCount} 人已确认", color = k.sub, fontSize = 12.sp)
                }
                if (edited) Text("已编辑", color = k.faint, fontSize = 12.sp)
            }
        }
    }
}
