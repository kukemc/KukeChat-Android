package ink.kuke.chat.feature.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.feature.bots.rememberOpenBotForUser
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.KcTextField
import ink.kuke.chat.feature.chat.MemberLite
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.a
import ink.kuke.chat.feature.chat.asArr
import ink.kuke.chat.feature.chat.asObj
import ink.kuke.chat.feature.chat.l
import ink.kuke.chat.feature.chat.parseMemberPage
import ink.kuke.chat.feature.chat.s
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.groupplus.apiCode
import ink.kuke.chat.feature.groupplus.detailLong
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

private const val PAGE = 30

/**
 * 成员列表：分页 GET /conversations/{id}/members?limit=&offset=&search=，搜索防抖 300ms。
 * 点成员 → onMemberClick（用户名片）；群主 / 管理员对非群主的成员多一个「移除」。
 */
@Composable
internal fun MembersPage(
    graph: AppGraph,
    conversationId: Long,
    manager: Boolean,
    myId: Long?,
    onBack: () -> Unit,
    onMemberClick: MemberClickHandler,
    onRemoved: () -> Unit,
) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val openBot = rememberOpenBotForUser(graph)
    val scope = rememberCoroutineScope()
    val members = remember { mutableStateListOf<MemberLite>() }
    var query by remember { mutableStateOf("") }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<MemberLite?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)

    suspend fun load(reset: Boolean) {
        loading = true
        error = null
        val offset = if (reset) 0 else members.size
        runCatching {
            val search = query.trim().takeIf { it.isNotEmpty() }?.let { "&search=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            parseMemberPage(graph.api.get("/conversations/$conversationId/members?limit=$PAGE&offset=$offset$search"), offset)
        }.onSuccess { page ->
            if (reset) members.clear()
            members.addAll(page.items.filter { incoming -> members.none { it.userId == incoming.userId } })
            total = page.total
            hasMore = page.hasMore
        }.onFailure { error = it.userMessage() }
        loading = false
    }

    LaunchedEffect(query, attempt) {
        if (query.isNotEmpty()) delay(300)
        load(reset = true)
    }
    // 翻到末尾自动加载下一页
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (members.isNotEmpty() && last >= members.size - 3 && hasMore && !loading) load(reset = false)
        }
    }

    KcPage(title = "群成员 $total", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(12.dp)) { KcTextField(query, { query = it }, "搜索成员", Modifier.fillMaxWidth()) }
            when {
                error != null && members.isEmpty() -> StateText(error!!, error = true, onRetry = { attempt++ })
                loading && members.isEmpty() -> StateText("加载中…")
                members.isEmpty() -> StateText("没有找到成员")
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(members, key = { it.userId }) { member ->
                        Row(
                            // 成员里的机器人点了直接进机器人详情
                            Modifier.fillMaxWidth().clickable { if (member.isBot) openBot(member.userId) else onMemberClick(member.userId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(member.avatarUrl, member.name, tone = member.userId, size = 44.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(member.name, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (member.isOwner) RoleTag("群主", colors.warn) else if (member.isAdmin) RoleTag("管理员", colors.accent)
                                    member.title?.let { RoleTag(it, colors.ok) }
                                }
                                val sub = listOfNotNull(member.level?.let { "LV$it" }, if (member.muted) "已禁言" else null).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = KcTheme.type.caption, color = colors.muted)
                            }
                            if (manager && !member.isOwner && member.userId != myId) {
                                Text("移除", style = KcTheme.type.secondary, color = colors.danger, modifier = Modifier.clickable { removing = member }.padding(8.dp))
                            }
                        }
                    }
                    if (loading) item { Text("加载中…", style = KcTheme.type.caption, color = colors.subtle, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
                }
            }
        }
    }

    removing?.let { member ->
        KcConfirm("移除成员", "确定把「${member.name}」移出群聊吗？", "移除", danger = true, onConfirm = {
            removing = null
            scope.launch {
                runCatching { graph.api.delete("/conversations/$conversationId/members/${member.userId}") }
                    .onSuccess {
                        members.removeAll { it.userId == member.userId }
                        total = (total - 1).coerceAtLeast(0)
                        onRemoved()
                    }
                    .onFailure { toast(context, it.userMessage()) }
            }
        }, onDismiss = { removing = null })
    }
}

@Composable
private fun RoleTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = KcTheme.type.caption,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}


/** 入群申请：GET /conversations/join-requests/incoming 里属于这个群的待处理项；同意 / 拒绝 */
@Composable
internal fun JoinRequestsSheet(graph: AppGraph, conversationId: Long, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    // 同意时群已满员（group_full）：服务端给的提示，弹框引导去升级
    var fullHint by remember { mutableStateOf<String?>(null) }
    val nav = ink.kuke.chat.ui.shell.LocalAppNav.current

    LaunchedEffect(attempt) {
        error = null
        runCatching {
            val element = graph.api.get("/conversations/join-requests/incoming")
            (element.asArr() ?: element.asObj()?.a("items")).orEmpty().mapNotNull { it.asObj() }
                .filter { it.l("conversation_id") == conversationId && (it.s("status") ?: "pending") == "pending" }
        }.onSuccess { items = it }.onFailure { error = it.userMessage() }
    }

    fun decide(request: JsonObject, accept: Boolean) {
        val id = request.l("id") ?: return
        scope.launch {
            runCatching { graph.api.post("/conversations/join-requests/$id/${if (accept) "accept" else "reject"}", JsonObject(emptyMap())) }
                .onSuccess {
                    graph.conversations.refreshSoon(0)
                    onChanged()
                    attempt++
                }
                .onFailure {
                    if (it.apiCode == "group_full") {
                        val e = it as ink.kuke.chat.core.net.ApiException
                        val count = e.detailLong("member_count")
                        val limit = e.detailLong("limit")
                        fullHint = if (count != null && limit != null) "群里已有 $count 人，达到当前档位上限 $limit 人" else "群人数已达到当前档位上限"
                    } else toast(context, it.userMessage())
                }
        }
    }

    KcSheet(onDismiss = onDismiss, title = "入群申请", maxHeight = 560.dp) {
        val list = items
        Box(Modifier.heightIn(min = 140.dp, max = 460.dp)) {
            when {
                error != null -> StateText(error!!, error = true, onRetry = { attempt++ })
                list == null -> StateText("加载中…")
                list.isEmpty() -> StateText("没有待处理的申请")
                else -> LazyColumn {
                    items(list, key = { it.l("id") ?: it.hashCode().toLong() }) { request ->
                        val user = request.asObj()?.get("requester")?.asObj()
                        val name = user?.s("nickname")?.takeIf { it.isNotBlank() } ?: user?.s("username") ?: "用户 ${request.l("requester_id") ?: ""}"
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(user?.s("avatar_url"), name, tone = request.l("requester_id") ?: 0, size = 42.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(name, style = KcTheme.type.rowTitle, color = colors.text)
                                val note = listOfNotNull(request.s("answer")?.takeIf { it.isNotBlank() }?.let { "回答：$it" }, request.s("message")?.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (note.isNotBlank()) Text(note, style = KcTheme.type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text("拒绝", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.clickable { decide(request, false) }.padding(8.dp))
                            Text("同意", style = KcTheme.type.secondary, color = colors.accent, modifier = Modifier.clickable { decide(request, true) }.padding(8.dp))
                        }
                    }
                }
            }
        }
    }

    // 盖在申请面板上面（KcSheet 是同层浮层，后画的在上）
    fullHint?.let { text ->
        KcConfirm(
            title = "群已满员",
            text = "$text。升级群档位后可以容纳更多成员，再回来通过这条申请。",
            confirmLabel = "去升级",
            onConfirm = {
                fullHint = null
                onDismiss()
                nav.go(ink.kuke.chat.ui.shell.GroupPlusRoute(conversationId, reason = "members"))
            },
            onDismiss = { fullHint = null },
        )
    }
}
