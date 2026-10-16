package ink.kuke.chat.feature.contacts

import ink.kuke.chat.core.data.slot

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.user.FriendBadgeMark
import ink.kuke.chat.feature.user.IntimacyCache
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcLargeTitle
import ink.kuke.chat.ui.components.UnreadBadge
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.AddFriendRoute
import ink.kuke.chat.ui.shell.BlocksRoute
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.NotificationsRoute
import ink.kuke.chat.ui.shell.SearchRoute
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 联系人入口卡上的「待处理」数；通知中心那边处理完可以直接改它 */
const val PENDING_CACHE_KEY = "contacts.pending"

private sealed interface Row2 {
    data class Header(val letter: Char) : Row2
    data class Person(val user: User) : Row2
}

/**
 * 联系人 tab（设计稿 P08）：三张入口卡 · 好友 / 群聊分段 · 好友按拼音首字母分组 + 右侧快速索引。
 * 长按好友弹底部菜单（发消息 / 查看资料 / 删除好友需确认）。名字旁挂「好友亲密度」徽章。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val social = remember(graph) { SocialApi(graph.api) }
    val view = LocalView.current
    val toast = rememberToast()

    // 上次的好友列表（落盘）和待处理数（内存）：先画出来，再静默刷新
    val friendsCache = remember(graph) { graph.screenCache.friendsSlot() }
    val pendingCache = remember(graph) { graph.screenCache.slot<Int>(PENDING_CACHE_KEY) }
    var friends by remember { mutableStateOf<Load<List<Friendship>>>(friendsCache.get()?.let { Load.Ready(it) } ?: Load.Loading) }
    // null = 还不知道（显示「–」，不显示 0）
    val requests by PendingRequests.items.collectAsStateWithLifecycle()
    val requestBadge by PendingRequests.badge.collectAsStateWithLifecycle()
    // 待处理数按通知列表里还能处理的申请自己数（服务端 pending_count 常常是 0）；还没拉到时先用上次的数
    val pending: Int? = requests?.size ?: pendingCache.get()
    var refreshing by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuUser by remember { mutableStateOf<User?>(null) }
    var deleting by remember { mutableStateOf<User?>(null) }
    remember(graph) { graph.conversations.restoreCached() }
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    val conversationsLoaded by graph.conversations.loaded.collectAsStateWithLifecycle()
    val bonds by IntimacyCache.bonds.collectAsStateWithLifecycle()

    suspend fun load(silent: Boolean) {
        if (!silent) friends = Load.Loading
        runCatching { social.friends() }
            .onSuccess { list -> friends = Load.Ready(list); friendsCache.put(list) }
            .onFailure { error -> if (!silent || friends !is Load.Ready) friends = Load.Failed(error.userMessage()) }
        PendingRequests.refresh(graph)
        PendingRequests.items.value?.let { pendingCache.put(it.size) }
    }

    LaunchedEffect(graph) {
        IntimacyCache.start(graph)
        // 有缓存时静默刷新；没有缓存时初始状态本来就是「加载中」
        load(silent = true)
    }
    LaunchedEffect(graph) { if (!graph.conversations.loaded.value) graph.conversations.refresh() }
    // 好友申请 / 好友变动 / 入群申请：静默刷新（同电脑版 invalidateQueries）
    LaunchedEffect(graph) {
        graph.realtime.events
            .filter { it.type.startsWith("friend") || it.type.startsWith("group.join_request") }
            .collect { load(silent = true) }
    }

    fun openChatWith(user: User) {
        scope.launch {
            runCatching { social.openDirect(user.id) }
                .onSuccess { nav.go(ChatRoute(it)) }
                .onFailure { toast.show(it.userMessage()) }
        }
    }

    val keyword = query.trim().lowercase()
    val groups = remember(conversations, keyword) {
        conversations.filter { it.isGroup }.filter { keyword.isEmpty() || it.name().lowercase().contains(keyword) }
    }
    // 还不知道的数显示「–」：会话列表没拉过也没缓存、好友还在首次加载
    val groupsKnown = conversationsLoaded || conversations.isNotEmpty()
    val groupTotal = remember(conversations, groupsKnown) { if (groupsKnown) conversations.count { it.isGroup } else null }
    val friendUsers = (friends as? Load.Ready)?.value?.mapNotNull { it.other }.orEmpty()
    val friendTotal = if (friends is Load.Ready) friendUsers.size else null
    val rows = remember(friendUsers, keyword) {
        val filtered = friendUsers.filter {
            keyword.isEmpty() || listOf(it.displayName, it.username, it.nickname.orEmpty(), it.bio.orEmpty(), it.id.toString()).any { part -> part.lowercase().contains(keyword) }
        }
        val grouped = filtered.groupBy { PinyinIndex.letterOf(it.displayName) }
        val letters = grouped.keys.sortedWith(compareBy({ it == '#' }, { it }))
        buildList<Row2> {
            letters.forEach { letter ->
                add(Row2.Header(letter))
                grouped.getValue(letter).sortedWith { a, b -> PinyinIndex.compare(a.displayName, b.displayName) }.forEach { add(Row2.Person(it)) }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.list)) {
        Column(Modifier.fillMaxSize()) {
            KcLargeTitle("联系人") {
                IconAction(KcIcons.userPlus, "找人") { nav.go(AddFriendRoute) }
            }
            KcTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = if (tab == 0) "搜索好友或群聊" else "搜索群聊",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
            )
            Spacer(Modifier.height(8.dp))

            val listState = rememberLazyListState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        load(silent = true)
                        graph.conversations.refresh()
                        IntimacyCache.refresh(graph)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                // 顶部两块（入口卡 + 分段）放在列表里随内容滚走
                val topCount = 2
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ink.kuke.chat.ui.shell.LocalBottomBarSpace.current + 8.dp)) {
                        item(key = "entries") {
                            // 设计稿：入口卡左右 12、卡间 8、下方 12
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val pendingNow = pending
                                EntryCard(KcIcons.bell, "通知中心", when { pendingNow == null -> "– 条待处理"; pendingNow > 0 -> "$pendingNow 条待处理"; else -> "暂无待处理" }, Color(0xFF1F6BFF), requestBadge, Modifier.weight(1f)) { nav.go(NotificationsRoute) }
                                EntryCard(KcIcons.shield, "临时会话屏蔽", "管理屏蔽", Color(0xFF6B7385), 0, Modifier.weight(1f)) { nav.go(BlocksRoute) }
                                EntryCard(KcIcons.userPlus, "找人", "加好友 / 加群", Color(0xFF12A679), 0, Modifier.weight(1f)) { nav.go(AddFriendRoute) }
                            }
                        }
                        // 新朋友（同 QQ）：待处理的好友申请 / 入群申请直接摆在外面，行内就能接受或拒绝
                        requests?.takeIf { it.isNotEmpty() }?.let { list ->
                            item(key = "requests") {
                                NewFriendsSection(list, social, onAll = { nav.go(ink.kuke.chat.ui.shell.NotificationCategoryRoute("friend")) }, onError = { toast.show(it) })
                            }
                        }
                        item(key = "tabs") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                UnderlineTab("好友", friendTotal, tab == 0) { tab = 0 }
                                UnderlineTab("群聊", groupTotal, tab == 1) { tab = 1 }
                            }
                        }
                        if (tab == 0) {
                            when (val state = friends) {
                                Load.Loading -> item(key = "loading") { Box(Modifier.fillParentMaxHeightCompat(), contentAlignment = Alignment.TopCenter) { Text("正在加载好友…", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 48.dp)) } }
                                is Load.Failed -> item(key = "failed") {
                                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(state.message, style = type.secondary, color = colors.muted, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(12.dp))
                                        SmallButton("重试", primary = true) { scope.launch { load(silent = false) } }
                                    }
                                }
                                is Load.Ready -> if (rows.isEmpty()) {
                                    item(key = "empty") {
                                        Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(if (keyword.isEmpty()) "还没有好友" else "没有匹配的好友", style = type.secondary, color = colors.muted)
                                            if (keyword.isEmpty()) {
                                                Spacer(Modifier.height(12.dp))
                                                SmallButton("去找人", primary = true) { nav.go(AddFriendRoute) }
                                            }
                                        }
                                    }
                                } else {
                                    items(rows, key = { row -> if (row is Row2.Header) "h${row.letter}" else "u${(row as Row2.Person).user.id}" }) { row ->
                                        when (row) {
                                            is Row2.Header -> Text(
                                                row.letter.toString(), style = type.group, color = colors.muted,
                                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                                            )
                                            is Row2.Person -> FriendRow(
                                                user = row.user,
                                                onClick = { nav.go(UserRoute(row.user.id)) },
                                                onLongClick = { Haptics.tick(view); menuUser = row.user },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            if (groups.isEmpty() && !groupsKnown) {
                                item(key = "g-loading") { Box(Modifier.fillParentMaxHeightCompat(), contentAlignment = Alignment.TopCenter) { Text("正在加载群聊…", style = type.secondary, color = colors.muted, modifier = Modifier.padding(top = 48.dp)) } }
                            } else if (groups.isEmpty()) {
                                item(key = "g-empty") {
                                    Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(if (keyword.isEmpty()) "还没有加入群聊" else "没有匹配的群聊", style = type.secondary, color = colors.muted)
                                        if (keyword.isEmpty()) {
                                            Spacer(Modifier.height(12.dp))
                                            SmallButton("去加群", primary = true) { nav.go(SearchRoute) }
                                        }
                                    }
                                }
                            } else {
                                items(groups, key = { "g${it.id}" }) { group ->
                                    Row(
                                        Modifier.animateItem().fillMaxWidth().clickable { nav.go(ChatRoute(group.id)) }.padding(horizontal = 16.dp).height(64.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Avatar(group.avatarUrl, group.name(), group.id, 44.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(group.name(), style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${group.memberCount} 名成员", style = type.caption, color = colors.muted)
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
                    }

                    // 右侧快速索引：只在好友页、没在搜索时出现
                    val letters = remember(rows) { rows.filterIsInstance<Row2.Header>().map { it.letter } }
                    if (tab == 0 && keyword.isEmpty() && letters.size > 1) {
                        val indexOfLetter = remember(rows) {
                            val map = mutableMapOf<Char, Int>()
                            rows.forEachIndexed { index, row -> if (row is Row2.Header) map[row.letter] = index + topCount }
                            map
                        }
                        QuickIndex(
                            letters = letters,
                            modifier = Modifier.align(Alignment.TopEnd),
                            onPick = { letter -> indexOfLetter[letter]?.let { target -> scope.launch { listState.scrollToItem(target) } } },
                        )
                    }
                }
            }
        }
        ToastHost(toast)
    }

    menuUser?.let { user ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { menuUser = null }, sheetState = sheet, containerColor = colors.panel) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.avatarUrl, user.displayName, user.id, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(user.displayName, style = type.title, color = colors.text)
                        Text("@${user.username}", style = type.caption, color = colors.muted)
                    }
                }
                SheetAction(KcIcons.message, "发消息") { menuUser = null; openChatWith(user) }
                SheetAction(KcIcons.profile, "查看资料") { menuUser = null; nav.go(UserRoute(user.id)) }
                SheetAction(KcIcons.trash, "删除好友", danger = true) { menuUser = null; deleting = user }
            }
        }
    }

    deleting?.let { user ->
        ConfirmDialog(
            title = "删除好友",
            message = "确定删除好友「${user.displayName}」吗？对方不会收到通知，之后需要重新申请才能加回。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                deleting = null
                scope.launch {
                    runCatching { social.deleteFriend(user.id) }
                        .onSuccess { toast.show("已删除好友"); load(silent = true); IntimacyCache.refresh(graph) }
                        .onFailure { toast.show(it.userMessage()) }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

/** 占满剩余高度的占位（LazyColumn 里不能直接 fillMaxSize，给个足够高的盒子让文字靠上） */
private fun Modifier.fillParentMaxHeightCompat(): Modifier = this.fillMaxWidth().height(240.dp)

@Composable
private fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val press = rememberInteraction()
    Box(
        Modifier.size(44.dp).pressScale(press, 0.9f).clip(CircleShape).clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { KcIcon(icon, tint = KcTheme.colors.text, size = 22.dp, contentDescription = description) }
}

@Composable
private fun EntryCard(icon: ImageVector, title: String, sub: String, tint: Color, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Column(
        modifier
            .pressScale(press)
            .shadow(2.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(12.dp),
    ) {
        Box {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                KcIcon(icon, tint = tint, size = 20.dp)
            }
            if (badge > 0) UnreadBadge(badge, modifier = Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = KcTheme.type.rowTitle.copy(fontSize = 14.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun UnderlineTab(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.clickable(onClick = onClick).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = KcTheme.type.rowTitle, color = if (selected) colors.text else colors.muted, fontWeight = if (selected) FontWeight.W700 else FontWeight.W500)
            // null = 还不知道，显示「–」而不是 0
            if (count == null || count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(count?.toString() ?: "–", style = KcTheme.type.caption, color = colors.subtle)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(22.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) colors.fill else Color.Transparent))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendRow(user: User, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        // 设计稿 .row.nl：行直接铺在列表底色上，头像与文字间距 14
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 16.dp, end = 28.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.avatarUrl, user.displayName, user.id, 44.dp, frame = ink.kuke.chat.feature.shop.rememberAvatarFrame(user.id, user.cosmetics))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(user.displayName, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ink.kuke.chat.feature.birthday.BirthdayMark(user.isBirthdayToday)
                Spacer(Modifier.width(6.dp))
                FriendBadgeMark(user.id, size = 16.dp)
            }
            Text(
                "${presenceLabel(user)} · ${user.bio?.takeIf { it.isNotBlank() } ?: "这个人很神秘，还没有简介"}",
                style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).height(52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        KcIcon(icon, tint = if (danger) colors.danger else colors.text, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = KcTheme.type.body, color = if (danger) colors.danger else colors.text)
    }
}

/** 右侧竖排字母：点按或上下拖动直接跳到对应分组，拖动时中间浮出大字母 */
@Composable
private fun QuickIndex(letters: List<Char>, modifier: Modifier, onPick: (Char) -> Unit) {
    val colors = KcTheme.colors
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<Char?>(null) }
    val current by derivedStateOf { active }
    val view = LocalView.current
    fun pick(y: Float) {
        val index = ((y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
        val letter = letters[index]
        if (letter != active) {
            active = letter
            Haptics.tick(view)
            onPick(letter)
        }
    }
    // 索引只覆盖列表区（避开入口卡和分段条）
    Box(modifier.fillMaxHeight().padding(top = 168.dp), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier
                .padding(end = 2.dp)
                .width(24.dp)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(letters) { detectTapGestures(onTap = { pick(it.y); active = null }) }
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ -> pick(change.position.y) }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { letter ->
                Text(
                    letter.toString(),
                    // 设计稿：10sp 粗体、subtle 色，紧凑贴右上
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    color = if (letter == current) colors.accent else colors.subtle,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
    current?.let { letter ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(colors.text.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                Text(letter.toString(), color = colors.chat, fontSize = 30.sp, fontWeight = FontWeight.W700)
            }
        }
    }
}

/** 联系人页的「新朋友」：最多摆 3 条待处理申请，其余点「查看全部」进通知中心 */
@Composable
private fun NewFriendsSection(list: List<UnifiedNotification>, social: SocialApi, onAll: () -> Unit, onError: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val busy = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.panel),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onAll).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("新朋友", style = type.rowTitle.copy(fontWeight = FontWeight.W700), color = colors.text)
            Text("${list.size}", style = type.caption, color = colors.muted, modifier = Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(if (list.size > 3) "查看全部" else "通知中心", style = type.caption, color = colors.muted)
            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
        }
        list.take(3).forEach { n ->
            val friend = n.friendRequestId != null
            val name = n.actor?.displayName ?: n.title
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(n.actor?.avatarUrl, name, tone = n.actor?.id ?: 0, size = 40.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (friend) "请求添加你为好友" else "申请加入 ${n.conversationTitle ?: "群聊"}",
                        style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                val working = busy[n.id] == true
                // 忽略：对方那边仍是「等待验证」，看不出被拒
                fun ignore() {
                    if (working) return
                    busy[n.id] = true
                    scope.launch {
                        runCatching { social.ignoreFriendRequest(n.friendRequestId!!) }
                            .onSuccess { PendingRequests.remove(n.id) }
                            .onFailure { onError(if ((it as? ink.kuke.chat.core.net.ApiException)?.status == 404) "服务器暂未支持忽略，请先更新服务端" else it.userMessage()) }
                        busy.remove(n.id)
                    }
                }
                fun act(accept: Boolean) {
                    if (working) return
                    busy[n.id] = true
                    scope.launch {
                        runCatching {
                            when {
                                friend && accept -> social.acceptFriendRequest(n.friendRequestId!!)
                                friend -> social.rejectFriendRequest(n.friendRequestId!!)
                                accept -> social.acceptJoinRequest(n.groupJoinRequestId!!)
                                else -> social.rejectJoinRequest(n.groupJoinRequestId!!)
                            }
                        }.onSuccess { PendingRequests.remove(n.id) }
                            .onFailure { onError(it.userMessage()) }
                        busy.remove(n.id)
                    }
                }
                if (friend) SmallPill("忽略", colors.text.copy(alpha = 0.06f), colors.text, working) { ignore() }
                else SmallPill("拒绝", colors.text.copy(alpha = 0.06f), colors.text, working) { act(false) }
                Spacer(Modifier.width(8.dp))
                SmallPill(if (friend) "接受" else "通过", colors.fill, colors.onFill, working) { act(true) }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SmallPill(text: String, bg: Color, fg: Color, disabled: Boolean, onClick: () -> Unit) {
    val press = rememberInteraction()
    Box(
        Modifier.height(32.dp).pressScale(press).clip(RoundedCornerShape(99.dp)).background(bg)
            .clickable(interactionSource = press, indication = null, enabled = !disabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = KcTheme.type.secondary.copy(fontWeight = FontWeight.W600), color = fg.copy(alpha = if (disabled) 0.5f else 1f)) }
}
