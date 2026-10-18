package ink.kuke.chat.feature.groupannouncement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.groupplus.PlusButton
import ink.kuke.chat.feature.groupplus.plusInk
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.GroupAnnouncementRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 进群弹窗（docs/group-announcements.md 第 4 节）：进入群聊时拉一次 GET /announcements/popups，按发布时间从旧到新逐条弹出。
 * 需要确认的点「确认收到」（/confirm）才算处理；不需要确认的「我知道了」或关掉都算看过（/seen）。
 * 需要确认的也能先关掉（记一次看过，不算处理），下次进群还会弹。
 * 正开着这个群时收到 created / updated 且 popup = true，重拉一次。自己发布的不弹给自己。
 */
@Composable
fun AnnouncementPopupHost(graph: AppGraph, conversationId: Long, enabled: Boolean) {
    if (!enabled) return
    val api = remember(graph) { GroupAnnouncementApi(graph) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val myId = graph.session.session.collectAsStateWithLifecycle().value?.user?.id
    val queue = remember(conversationId) { mutableStateListOf<GroupAnnouncement>() }
    // 这次进群里已经关掉 / 处理过的：重拉时不再弹
    val handled = remember(conversationId) { mutableSetOf<Long>() }
    var tick by remember(conversationId) { mutableIntStateOf(0) }
    var nickname by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    LaunchedEffect(conversationId, tick) {
        // 实时事件触发的重拉：稍等一下，让服务端的弹窗状态落定
        if (tick > 0) delay(500)
        runCatching { api.popups(conversationId) }.onSuccess { list ->
            val fresh = list.filter { it.id !in handled && (myId == null || it.authorId != myId) && !AnnouncementHub.isDeleted(it.id) }
            queue.clear()
            queue.addAll(fresh)
        }
    }
    LaunchedEffect(conversationId) {
        AnnouncementHub.popupHints.collect { if (it == conversationId) tick++ }
    }
    LaunchedEffect(conversationId) {
        AnnouncementHub.changes.collect { (cid, aid) ->
            if (cid == conversationId && aid != 0L && AnnouncementHub.isDeleted(aid)) queue.removeAll { it.id == aid }
        }
    }

    val current = queue.firstOrNull()
    if (current != null) {
        fun done(item: GroupAnnouncement) {
            handled += item.id
            queue.removeAll { it.id == item.id }
        }
        // 关掉 / 「我知道了」：记看过
        fun close(item: GroupAnnouncement) {
            done(item)
            scope.launch { runCatching { api.seen(conversationId, item.id) } }
        }
        key(current.id) {
            AnnouncementPopup(
                item = current,
                onClose = { close(current) },
                onConfirm = {
                    scope.launch {
                        runCatching { api.confirm(conversationId, current.id) }
                            .onSuccess { done(current) }
                            .onFailure {
                                toast(context, it.userMessage())
                                // 公告已经不需要确认 / 被删了：当作处理完
                                close(current)
                            }
                    }
                },
                onNickname = { nickname = true },
                onOpenImage = { index -> viewing = current.images.map { it.url } to index },
            )
        }
    }
    if (nickname) NicknameSheet(graph, conversationId, onDismiss = { nickname = false })
    viewing?.let { (urls, start) -> ink.kuke.chat.feature.feed.ImageViewer(urls, start) { viewing = null } }
}

@Composable
private fun AnnouncementPopup(
    item: GroupAnnouncement,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    onNickname: () -> Unit,
    onOpenImage: (Int) -> Unit,
) {
    val k = plusInk()
    var busy by remember { mutableStateOf(false) }
    KcSheet(onDismiss = { if (!busy) onClose() }, maxHeight = 640.dp) {
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KcIcon(KcIcons.announcement, tint = k.ink, size = 16.dp)
                Text("群公告", color = k.ink, fontSize = 13.5.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 6.dp))
                if (item.editedAt != null) AnnTag("已更新", Modifier.padding(start = 8.dp), faint = true)
                Box(Modifier.weight(1f))
                if (item.requireConfirm) AnnTag("需确认")
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.author?.avatarUrl, item.authorName, tone = item.authorId ?: 0, size = 32.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(item.authorName, color = k.ink, fontSize = 14.5.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(announcementTime(item.createdAt), color = k.sub, fontSize = 12.sp)
                }
            }
            Column(Modifier.padding(top = 14.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(item.content, color = k.ink, fontSize = 15.5.sp, lineHeight = 24.sp)
                if (item.images.isNotEmpty()) AnnouncementImageGrid(item.images, Modifier.padding(top = 14.dp), onOpen = onOpenImage)
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.guideNickname) PlusButton("修改群昵称", onNickname, modifier = Modifier.weight(1f), primary = false)
                if (item.requireConfirm) {
                    PlusButton(if (busy) "提交中…" else "确认收到", {
                        busy = true
                        onConfirm()
                    }, modifier = Modifier.weight(1f), enabled = !busy)
                } else {
                    PlusButton("我知道了", onClose, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 聊天里的公告卡片

/**
 * 聊天里的公告卡片（type = system，metadata.system_event = group.announcement.card，第 5 节）：
 * 「群公告」标签（修改后发的卡片标「公告已更新」）、正文前几行、首图、发布人；点开公告详情。
 * 公告被删（metadata.deleted 或收到 group.announcement.deleted）后显示「该公告已删除」、不能点。
 * 是系统消息：没有长按菜单，不能转发、不进多选。
 */
@Composable
fun AnnouncementCard(message: Message, card: AnnouncementCardMeta, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val deletedIds by AnnouncementHub.deletedIds.collectAsState()
    val deleted = card.deleted || card.announcementId in deletedIds
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(colors.panel)
                .border(1.dp, colors.border, shape)
                .clickable(enabled = !deleted) { nav.go(GroupAnnouncementRoute(message.conversationId, card.announcementId)) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KcIcon(KcIcons.announcement, tint = if (deleted) colors.subtle else colors.text, size = 15.dp)
                Text("群公告", color = if (deleted) colors.subtle else colors.text, fontSize = 13.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 6.dp))
                if (card.action == "updated" && !deleted) AnnTag("公告已更新", Modifier.padding(start = 8.dp), faint = true)
                Box(Modifier.weight(1f))
                if (card.requireConfirm && !deleted) Text("需确认", color = colors.muted, fontSize = 11.5.sp)
            }
            if (deleted) {
                Text("该公告已删除", color = colors.subtle, fontSize = 13.5.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                Row(Modifier.padding(top = 9.dp)) {
                    Text(
                        card.summary.ifBlank { message.content },
                        color = colors.text, fontSize = 14.5.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val thumb = card.imageThumbnailUrl ?: card.imageUrl
                    if (thumb != null) AnnouncementThumb(thumb, card.imageCount, 56.dp, Modifier.padding(start = 10.dp))
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${card.authorName} ${if (card.action == "updated") "更新了公告" else "发布"}",
                        color = colors.subtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("查看", color = colors.muted, fontSize = 12.sp)
                    KcIcon(KcIcons.chevron, tint = colors.subtle, size = 14.dp)
                }
            }
        }
    }
}
