package ink.kuke.chat.feature.contacts

import ink.kuke.chat.core.data.slot

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.listTime
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import androidx.compose.material3.TextField
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val Tabs = listOf("all" to "全部", "messages" to "聊天记录", "users" to "用户", "groups" to "群聊")

private const val FRIEND_SENT = "好友申请已发送"
private const val GROUP_JOINED = "已加入群聊"
private const val GROUP_REQUESTED = "已提交加群申请"

/** 聊天记录行的预览文案（同客户端 messagePreview 的主要分支） */
private fun previewOf(message: Message): String = when {
    message.isRecalled -> "消息已撤回"
    message.type == "image" -> if (message.content.isBlank()) "[图片]" else "${message.content} [图片]"
    message.type == "voice" -> "[语音]"
    message.type == "forward_bundle" -> "[聊天记录]"
    message.type == "red_packet" -> "[红包]"
    message.type == "gift" -> "[礼物]"
    message.type == "system" -> message.content.ifBlank { "系统消息" }
    else -> message.content.ifBlank { "[空消息]" }
}

/**
 * 全局搜索（设计稿 P10）：全部 / 聊天记录 / 用户 / 群聊；空关键词显示推荐用户 + 推荐群聊。
 * 聊天记录没有全站接口：和客户端 globalSearch.ts 一样，只在「我自己的会话」里逐个调单会话搜索再合并。
 */
@Composable
fun SearchScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val social = remember(graph) { SocialApi(graph.api) }
    val me = graph.session.session.value?.user
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    val toast = rememberToast()

    var input by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("all") }
    var reloadTick by remember { mutableStateOf(0) }

    // 没输入时的「推荐用户 / 推荐群」：上次的先摆出来（内存），再静默刷新
    val recUsersCache = remember(graph) { graph.screenCache.slot<List<User>>("search.recommend.users") }
    val recGroupsCache = remember(graph) { graph.screenCache.slot<List<GroupInfo>>("search.recommend.groups") }
    var users by remember { mutableStateOf<Load<List<User>>>(recUsersCache.get()?.let { Load.Ready(it) } ?: Load.Loading) }
    var groups by remember { mutableStateOf<Load<List<GroupInfo>>>(recGroupsCache.get()?.let { Load.Ready(it) } ?: Load.Loading) }
    // 聊天记录：按会话聚合的命中数（GET /search/messages/summary），点会话进入该会话的搜索页
    var messages by remember { mutableStateOf<Load<ink.kuke.chat.feature.search.SearchSummary>>(Load.Ready(ink.kuke.chat.feature.search.SearchSummary(0, emptyList()))) }
    val friendsCache = remember(graph) { graph.screenCache.friendsSlot() }
    var friendIds by remember { mutableStateOf(friendsCache.get().orEmpty().mapNotNull { it.other?.id }.toSet()) }
    var pendingFriendIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingGroupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val friendResult = remember { mutableStateMapOf<Long, String>() }
    val groupResult = remember { mutableStateMapOf<Long, String>() }
    var working by remember { mutableStateOf(false) }
    var answerFor by remember { mutableStateOf<GroupInfo?>(null) }
    var answerText by remember { mutableStateOf("") }

    // 输入停顿 350ms 才发请求
    LaunchedEffect(input) {
        delay(350)
        keyword = input.trim()
    }

    // 我已经是谁的好友 / 已经申请了谁 / 已经申请了哪些群：决定按钮状态
    LaunchedEffect(graph) {
        runCatching { social.friends() }.onSuccess { list -> friendIds = list.mapNotNull { it.other?.id }.toSet(); friendsCache.put(list) }
        runCatching { social.outgoingFriendRequests() }.onSuccess { list -> pendingFriendIds = list.filter { it.status == "pending" }.map { it.receiverId }.toSet() }
        runCatching { social.outgoingJoinRequests() }.onSuccess { list -> pendingGroupIds = list.filter { it.status == "pending" }.map { it.conversationId }.toSet() }
    }

    LaunchedEffect(keyword, reloadTick) {
        val recommending = keyword.isEmpty()
        // 推荐有缓存就先用缓存，刷新失败也保留；搜索结果不缓存
        val cachedUsers = if (recommending) recUsersCache.get() else null
        val cachedGroups = if (recommending) recGroupsCache.get() else null
        users = cachedUsers?.let { Load.Ready(it) } ?: Load.Loading
        groups = cachedGroups?.let { Load.Ready(it) } ?: Load.Loading
        val usersJob = launch {
            runCatching { if (recommending) social.recommendedUsers() else social.searchUsers(keyword) }
                .onSuccess { users = Load.Ready(it); if (recommending) recUsersCache.put(it) }
                .onFailure { if (cachedUsers == null) users = Load.Failed(it.userMessage()) }
        }
        val groupsJob = launch {
            runCatching { if (recommending) social.recommendedGroups() else social.searchGroups(keyword) }
                .onSuccess { list ->
                    val joinable = list.filter { it.joinMode != "invite_only" }
                    groups = Load.Ready(joinable)
                    if (recommending) recGroupsCache.put(joinable)
                }
                .onFailure { if (cachedGroups == null) groups = Load.Failed(it.userMessage()) }
        }
        if (recommending) {
            messages = Load.Ready(ink.kuke.chat.feature.search.SearchSummary(0, emptyList()))
        } else {
            messages = Load.Loading
            runCatching { ink.kuke.chat.feature.search.ChatSearchApi(graph).summary(keyword) }
                .onSuccess { messages = Load.Ready(it) }
                .onFailure { messages = Load.Failed(it.userMessage()) }
        }
        usersJob.join()
        groupsJob.join()
    }

    fun sendFriend(user: User) {
        if (working) return
        working = true
        scope.launch {
            runCatching { social.sendFriendRequest(user.id) }
                .onSuccess { friendResult[user.id] = FRIEND_SENT; pendingFriendIds = pendingFriendIds + user.id }
                .onFailure { friendResult[user.id] = it.userMessage() }
            working = false
        }
    }

    fun join(group: GroupInfo, answer: String? = null) {
        if (working) return
        working = true
        scope.launch {
            val result = runCatching {
                if (group.needsApproval) {
                    social.requestJoinGroup(group.id, answer = answer)
                    GROUP_REQUESTED
                } else {
                    social.joinGroup(group.id)
                    GROUP_JOINED
                }
            }
            result.onSuccess {
                groupResult[group.id] = it
                if (it == GROUP_REQUESTED) pendingGroupIds = pendingGroupIds + group.id else graph.conversations.refreshSoon(0)
            }.onFailure {
                groupResult[group.id] = if (it is ApiException) it.message else "加群失败，请稍后重试"
            }
            working = false
        }
    }

    // 设计稿 P10：页面铺列表底色，顶部搜索栏 + 下划线分页是面色
    Column(Modifier.fillMaxSize().background(colors.list)) {
        Column(Modifier.background(colors.panel).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { nav.back() }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.chevronLeft, tint = colors.text, size = 24.dp, contentDescription = "返回")
                }
                KcTextField(input, { input = it }, "搜索聊天记录、用户、群聊", Modifier.weight(1f), onDone = { keyword = input.trim() })
            }
            // 设计稿 .tabs：高 44、15sp，选中加粗 + 3dp 强调色下划线，分页间距 20
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Tabs.forEach { (key, label) ->
                    val on = tab == key
                    Box(
                        Modifier.height(44.dp).clickable { tab = key }.drawBehind {
                            if (on) drawRoundRect(colors.fill, topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), size.height - 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), 3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = type.rowTitle.copy(fontSize = type.rowTitle.fontSize * (15f / 15.5f)), color = if (on) colors.text else colors.muted, fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.W700 else androidx.compose.ui.text.font.FontWeight.W500)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        Box(Modifier.fillMaxSize()) {
            val recommending = keyword.isEmpty()
            val showMessages = !recommending && (tab == "all" || tab == "messages")
            val showUsers = tab == "all" || tab == "users"
            val showGroups = tab == "all" || tab == "groups"
            val limit = if (tab == "all") 3 else Int.MAX_VALUE

            // 当前页签涉及的数据全部失败时给整页重试；全部加载中给整页提示
            val states = buildList {
                if (showUsers) add(users)
                if (showGroups) add(groups)
                if (showMessages) add(messages)
            }
            val allFailed = states.isNotEmpty() && states.all { it is Load.Failed }
            val allLoading = states.isNotEmpty() && states.all { it is Load.Loading }

            when {
                allLoading -> LoadingBox("正在搜索…")
                allFailed -> ErrorRetry((states.first() as Load.Failed).message, onRetry = { reloadTick++ })
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (recommending) {
                        item(key = "rec") { GroupLabel("为你推荐") }
                    } else {
                        item(key = "hint") { Text("“$keyword” 的搜索结果", style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    }

                    if (showMessages) {
                        item(key = "h-msg") { SectionHeader("聊天记录", more = tab == "all" && ((messages as? Load.Ready)?.value?.items?.size ?: 0) > limit) { tab = "messages" } }
                        when (val state = messages) {
                            Load.Loading -> item(key = "m-loading") { SectionNote("正在搜索聊天记录…") }
                            is Load.Failed -> item(key = "m-failed") { SectionRetry(state.message) { reloadTick++ } }
                            is Load.Ready -> if (state.value.items.isEmpty()) {
                                item(key = "m-empty") { SectionNote(if (conversations.isEmpty()) "还没有会话可搜索" else "没有找到相关聊天记录") }
                            } else {
                                items(state.value.items.take(limit), key = { "m${it.conversation.id}" }) { entry ->
                                    ink.kuke.chat.feature.search.ConversationHitRow(entry, keyword) {
                                        nav.go(ink.kuke.chat.ui.shell.ChatSearchRoute(entry.conversation.id, keyword))
                                    }
                                }
                            }
                        }
                    }

                    if (showUsers) {
                        item(key = "h-users") { SectionHeader(if (recommending) "推荐用户" else "用户", more = tab == "all" && ((users as? Load.Ready)?.value?.size ?: 0) > limit) { tab = "users" } }
                        when (val state = users) {
                            Load.Loading -> item(key = "u-loading") { SectionNote("正在搜索用户…") }
                            is Load.Failed -> item(key = "u-failed") { SectionRetry(state.message) { reloadTick++ } }
                            is Load.Ready -> if (state.value.isEmpty()) {
                                item(key = "u-empty") { SectionNote(if (recommending) "暂时没有推荐的用户" else "没有找到相关用户") }
                            } else {
                                items(state.value.take(limit), key = { "u${it.id}" }) { user ->
                                    val isSelf = user.id == me?.id
                                    val text = friendResult[user.id]
                                    val isFriend = user.id in friendIds
                                    val isPending = user.id in pendingFriendIds || text == FRIEND_SENT
                                    Column {
                                        Row(Modifier.fillMaxWidth().clickable { nav.go(UserRoute(user.id)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Avatar(user.avatarUrl, user.displayName, user.id, 44.dp)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(user.displayName, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    "@${user.username}" + (user.bio?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: if (user.isBot) " · 机器人" else ""),
                                                    style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (!isSelf) {
                                                Spacer(Modifier.width(8.dp))
                                                val label = if (isFriend) "已是好友" else if (isPending) "已申请" else "加好友"
                                                SmallButton(label, primary = !isFriend && !isPending, enabled = !isFriend && !isPending && !working) { sendFriend(user) }
                                            }
                                        }
                                        if (text != null && text != FRIEND_SENT) {
                                            Text(text, style = type.caption, color = colors.danger, modifier = Modifier.padding(start = 72.dp, bottom = 8.dp, end = 16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showGroups) {
                        item(key = "h-groups") { SectionHeader(if (recommending) "推荐群聊" else "群聊", more = tab == "all" && ((groups as? Load.Ready)?.value?.size ?: 0) > limit) { tab = "groups" } }
                        when (val state = groups) {
                            Load.Loading -> item(key = "g-loading") { SectionNote("正在搜索群聊…") }
                            is Load.Failed -> item(key = "g-failed") { SectionRetry(state.message) { reloadTick++ } }
                            is Load.Ready -> if (state.value.isEmpty()) {
                                item(key = "g-empty") { SectionNote(if (recommending) "暂时没有推荐的群聊" else "没有找到相关群聊") }
                            } else {
                                items(state.value.take(limit), key = { "g${it.id}" }) { group ->
                                    val text = groupResult[group.id]
                                    val joined = group.joined || text == GROUP_JOINED || conversations.any { it.id == group.id }
                                    val pending = text == GROUP_REQUESTED || group.id in pendingGroupIds || group.joinRequestStatus == "pending"
                                    Column {
                                        Row(
                                            Modifier.fillMaxWidth().clickable { if (joined) nav.go(ChatRoute(group.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Avatar(group.avatarUrl, group.name, group.id, 44.dp, plusTier = group.plusTier)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(group.name, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                    ink.kuke.chat.feature.groupplus.PlusTierTag(group.plusTier, Modifier.padding(start = 6.dp))
                                                }
                                                Text("群号 ${group.id} · ${group.memberCount} 名成员", style = type.caption, color = colors.muted, maxLines = 1)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            when {
                                                joined -> SmallButton("进入群聊") { nav.go(ChatRoute(group.id)) }
                                                pending -> SmallButton("待审核", enabled = false) {}
                                                else -> SmallButton(if (group.needsApproval) "申请加入" else "加入", primary = true, enabled = !working) {
                                                    if (group.needsApproval && !group.joinQuestion.isNullOrBlank()) {
                                                        answerText = ""
                                                        answerFor = group
                                                    } else {
                                                        join(group)
                                                    }
                                                }
                                            }
                                        }
                                        if (text != null && text != GROUP_JOINED && text != GROUP_REQUESTED) {
                                            Text(text, style = type.caption, color = colors.danger, modifier = Modifier.padding(start = 72.dp, bottom = 8.dp, end = 16.dp))
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

    // 需要回答入群问题的群：先问答案，取消就不提交
    answerFor?.let { group ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { answerFor = null },
            containerColor = colors.panel,
            title = { Text("入群验证", style = type.title, color = colors.text) },
            text = {
                Column {
                    Text(group.joinQuestion.orEmpty(), style = type.body, color = colors.muted)
                    Spacer(Modifier.height(10.dp))
                    KcTextField(answerText, { answerText = it }, "你的回答", icon = null)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val answer = answerText.trim()
                    answerFor = null
                    join(group, answer.ifEmpty { null })
                }) { Text("提交申请", color = colors.accent, fontWeight = FontWeight.W600) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { answerFor = null }) { Text("取消", color = colors.muted) } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, more: Boolean, onMore: () -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KcTheme.type.group, color = colors.muted, modifier = Modifier.weight(1f))
        if (more) Text("更多", style = KcTheme.type.caption, color = colors.accent, modifier = Modifier.clickable(onClick = onMore).padding(8.dp))
    }
}

@Composable
private fun SectionNote(text: String) {
    Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, modifier = Modifier.fillMaxWidth().background(KcTheme.colors.panel).padding(16.dp))
}

@Composable
private fun SectionRetry(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(KcTheme.colors.panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = KcTheme.type.secondary, color = KcTheme.colors.muted, modifier = Modifier.weight(1f))
        SmallButton("重试", primary = true, onClick = onRetry)
    }
}

/** 会话的展示名（备注 > 显示名 > 群名 > 私聊对方），供别处复用 */
internal fun Conversation.searchTitle(): String = name()
