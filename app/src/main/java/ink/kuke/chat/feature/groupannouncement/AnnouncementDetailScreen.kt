package ink.kuke.chat.feature.groupannouncement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.groupplus.PlusButton
import ink.kuke.chat.feature.groupplus.plusInk
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.GroupAnnouncementConfirmsRoute
import ink.kuke.chat.ui.shell.GroupAnnouncementEditRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/**
 * 公告详情：发布人 · 时间 · 已编辑，正文（保留换行、可选中复制），多图（点开看大图）。
 * 需要确认的有底部「确认收到」；开了「引导修改群昵称」的底部多一个「修改群昵称」。
 * 群主 / 管理员：右上角编辑、删除（二次确认），正文下面是确认情况入口。打开就记一次「看过」（/seen）。
 */
@Composable
fun AnnouncementDetailScreen(graph: AppGraph, conversationId: Long, announcementId: Long) {
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val k = plusInk()
    val api = remember(graph) { GroupAnnouncementApi(graph) }
    val conversation = graph.conversations.items.collectAsStateWithLifecycle().value.firstOrNull { it.id == conversationId }
    val manager = isGroupManager(conversation?.myRole)
    var item by remember(announcementId) { mutableStateOf(AnnouncementHub.cached(announcementId)) }
    var gone by remember(announcementId) { mutableStateOf(AnnouncementHub.isDeleted(announcementId)) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingNickname by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(announcementId, attempt) {
        error = null
        runCatching { api.get(conversationId, announcementId) }
            .onSuccess { item = it }
            .onFailure {
                if (it is ApiException && it.status == 404) {
                    gone = true
                    AnnouncementHub.deleted(conversationId, announcementId)
                } else if (item == null) error = it.userMessage()
            }
    }
    // 打开详情 = 看过
    LaunchedEffect(announcementId) {
        if (!AnnouncementHub.isDeleted(announcementId)) runCatching { api.seen(conversationId, announcementId) }.onSuccess { item = item?.copy(mySeen = true) }
    }
    // 实时：被修改（推送里带整条公告）、被删除、确认人数变了
    LaunchedEffect(announcementId) {
        AnnouncementHub.changes.collect { (cid, aid) ->
            if (cid != conversationId || aid != announcementId) return@collect
            if (AnnouncementHub.isDeleted(announcementId)) gone = true else AnnouncementHub.cached(announcementId)?.let { item = it }
        }
    }

    val current = item
    KcPage(
        title = "群公告",
        onBack = nav::back,
        subtitle = conversation?.name(),
        actions = {
            if (manager && current != null && !gone) {
                TopIcon(KcIcons.squarePen, "编辑") { nav.go(GroupAnnouncementEditRoute(conversationId, announcementId)) }
                TopIcon(KcIcons.trash, "删除") { confirmDelete = true }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(KcTheme.colors.panel)) {
            when {
                gone -> StateText("该公告已删除")
                current == null && error != null -> StateText(error!!, error = true, onRetry = { attempt++ })
                current == null -> StateText("加载中…")
                else -> Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 28.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(current.author?.avatarUrl, current.authorName, tone = current.authorId ?: 0, size = 40.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(current.authorName, color = k.ink, fontSize = 15.5.sp, fontWeight = FontWeight.W600, maxLines = 1)
                                val time = announcementTime(current.createdAt) + if (current.editedAt != null) " · 已编辑" else ""
                                Text(time, color = k.sub, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        if (current.pinned || current.requireConfirm) {
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (current.pinned) AnnTag("置顶", strong = true)
                                if (current.requireConfirm) {
                                    if (current.myConfirmed) AnnTag("已确认", faint = true) else AnnTag("需确认")
                                }
                            }
                        }
                        SelectionContainer(Modifier.padding(top = 16.dp)) {
                            Text(current.content, color = k.ink, fontSize = 16.sp, lineHeight = 26.sp)
                        }
                        if (current.images.isNotEmpty()) {
                            AnnouncementImageGrid(current.images, Modifier.padding(top = 18.dp)) { viewing = it }
                        }
                        if (manager) ManagerPanel(current) { nav.go(GroupAnnouncementConfirmsRoute(conversationId, announcementId)) }
                    }
                    if (current.requireConfirm || current.guideNickname) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (current.guideNickname) {
                                PlusButton("修改群昵称", { editingNickname = true }, modifier = Modifier.weight(1f), primary = !current.requireConfirm || current.myConfirmed)
                            }
                            if (current.requireConfirm) {
                                if (current.myConfirmed) {
                                    PlusButton("已确认收到", {}, modifier = Modifier.weight(1f), enabled = false, primary = false)
                                } else {
                                    PlusButton(if (busy) "提交中…" else "确认收到", {
                                        busy = true
                                        scope.launch {
                                            runCatching { api.confirm(conversationId, announcementId) }
                                                .onSuccess {
                                                    item = it.copy(mySeen = true, myConfirmed = true)
                                                    toast(context, "已确认收到")
                                                }
                                                .onFailure {
                                                    toast(context, it.userMessage())
                                                    attempt++
                                                }
                                            busy = false
                                        }
                                    }, modifier = Modifier.weight(1f), enabled = !busy)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewing?.let { start ->
        current?.let { ink.kuke.chat.feature.feed.ImageViewer(it.images.map { image -> image.url }, start) { viewing = null } }
    }
    if (editingNickname) NicknameSheet(graph, conversationId, onDismiss = { editingNickname = false })
    if (confirmDelete) {
        KcConfirm(
            title = "删除公告",
            text = "删除后所有成员都看不到这条公告，聊天里的公告卡片会显示「该公告已删除」。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    runCatching { api.delete(conversationId, announcementId) }
                        .onSuccess {
                            graph.conversations.refreshSoon(0)
                            toast(context, "公告已删除")
                            nav.back()
                        }
                        .onFailure { toast(context, it.userMessage()) }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 管理员看的：这条公告开了哪些选项 + 确认情况入口 */
@Composable
private fun ManagerPanel(item: GroupAnnouncement, onConfirms: () -> Unit) {
    val k = plusInk()
    val options = buildList {
        if (item.popup) add("弹窗展示")
        if (item.sendToNewMembers) add("发送给新成员")
        if (item.guideNickname) add("引导修改群昵称")
    }
    Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (options.isNotEmpty()) Text(options.joinToString(" · "), color = k.faint, fontSize = 12.5.sp)
        if (item.requireConfirm) {
            val press = rememberInteraction()
            Row(
                Modifier.fillMaxWidth().pressScale(press, 0.98f).clip(RoundedCornerShape(14.dp)).background(k.wash)
                    .clickable(interactionSource = press, indication = null, onClick = onConfirms)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("确认情况", color = k.ink, fontSize = 15.sp, fontWeight = FontWeight.W500)
                    val unconfirmed = (item.memberCount - item.confirmedCount).coerceAtLeast(0)
                    Text("已确认 ${item.confirmedCount} 人 · 未确认 $unconfirmed 人", color = k.sub, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                }
                KcIcon(KcIcons.chevron, tint = k.faint, size = 18.dp)
            }
        }
    }
}

@Composable
internal fun TopIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val press = rememberInteraction()
    Box(
        Modifier.size(44.dp).pressScale(press, 0.9f).clip(CircleShape).clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { KcIcon(icon, tint = KcTheme.colors.text, size = 21.dp, contentDescription = label) }
}
