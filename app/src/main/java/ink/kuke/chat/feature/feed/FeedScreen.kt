package ink.kuke.chat.feature.feed

import ink.kuke.chat.core.data.slot
import ink.kuke.chat.feature.contacts.PENDING_CACHE_KEY

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.ui.components.KcLargeTitle
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ComposePostRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.PostRoute
import ink.kuke.chat.ui.shell.PostLikesRoute
import ink.kuke.chat.ui.shell.SearchRoute
import ink.kuke.chat.ui.shell.TopicRoute
import ink.kuke.chat.ui.shell.NotificationsRoute
import ink.kuke.chat.feature.contacts.SocialApi
import ink.kuke.chat.ui.components.UnreadBadge
import ink.kuke.chat.ui.components.Avatar
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay

/**
 * 动态 tab（设计稿 P11）：分区（好友动态 · 动态广场 · 我的空间 · 大佬入住）、最新 / 热门、热门话题药丸、
 * 帖子卡、无限滚动、下拉刷新、「发布动态」扩展浮动按钮（列表上滑时收成圆形）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val vm = graphViewModel("feed") { FeedViewModel(graph) }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var sharing by remember { mutableStateOf<Post?>(null) }
    var deleting by remember { mutableStateOf<Post?>(null) }
    var editing by remember { mutableStateOf<Post?>(null) }
    var reposting by remember { mutableStateOf<Post?>(null) }

    // 待处理数和联系人页共用一格缓存，进页面先显示上次的
    val pendingCache = remember(graph) { graph.screenCache.slot<Int>(PENDING_CACHE_KEY) }
    var pending by remember { mutableStateOf(pendingCache.get() ?: 0) }
    LaunchedEffect(Unit) {
        vm.start()
        vm.ensureLoaded()
        runCatching { SocialApi(graph.api).notifications(1) }.onSuccess { pending = it.pendingCount; pendingCache.put(it.pendingCount) }
    }
    LaunchedEffect(vm.notice) {
        if (vm.notice != null) {
            delay(2600)
            vm.notice = null
        }
    }

    // 浮动按钮：手指上滑（内容往上走）收成圆形，下滑展开
    var fabExpanded by remember { mutableStateOf(true) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) fabExpanded = false else if (available.y > 6f) fabExpanded = true
                return Offset.Zero
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.list)) {
        KcLargeTitle("动态") {
            FeedIconAction(KcIcons.search, "搜索") { nav.go(SearchRoute) }
            Box {
                FeedIconAction(KcIcons.bell, "通知") { nav.go(NotificationsRoute) }
                if (pending > 0) UnreadBadge(pending, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp))
            }
        }
        SectionBar(vm.section, onSelect = vm::selectSection)
        Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(connection)) {
            if (vm.section == FeedSection.Creators) {
                CreatorsHall(
                    state = vm.creators,
                    onRetry = vm::retry,
                    onRefresh = vm::refresh,
                    onLoadMore = vm::loadMore,
                    onOpen = { nav.go(UserRoute(it)) },
                )
            } else {
                PostFeed(
                    vm = vm,
                    meId = me?.id,
                    meName = me?.displayName.orEmpty(),
                    meAvatar = me?.avatarUrl,
                    onCompose = { nav.go(ComposePostRoute) },
                    onOpenPost = { nav.go(PostRoute(it)) },
                    onUser = { nav.go(UserRoute(it)) },
                    onTopic = { nav.go(TopicRoute(it)) },
                    onImage = { urls, index -> viewer = urls to index },
                    onShare = { sharing = it },
                    onDelete = { deleting = it },
                    onEdit = { editing = it },
                    onRepost = { reposting = it },
                    onLikes = { nav.go(PostLikesRoute(it)) },
                )
                ExtendedFloatingActionButton(
                    onClick = { nav.go(ComposePostRoute) },
                    expanded = fabExpanded,
                    icon = { KcIcon(KcIcons.plus, tint = colors.onFill, size = 22.dp) },
                    text = { Text("发布动态") },
                    containerColor = colors.fill,
                    contentColor = colors.onFill,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = ink.kuke.chat.ui.shell.LocalBottomBarSpace.current),
                )
            }
            vm.notice?.let { message ->
                Text(
                    message,
                    style = KcTheme.type.secondary,
                    color = colors.panel,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(22.dp)).background(colors.text.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    viewer?.let { (urls, index) -> ImageViewer(urls, index) { viewer = null } }
    sharing?.let { post ->
        ShareSheet(graph, "分享动态", postShareText(post), postShareCard(post)) { sharing = null }
    }
    editing?.let { post ->
        EditPostSheet(graph, post, onDismiss = { editing = null }) { saved -> editing = null; vm.replace(saved) }
    }
    reposting?.let { post ->
        RepostSheet(graph, post, onDismiss = { reposting = null }) { reposting = null; vm.notice = "已转发到你的动态"; vm.refresh() }
    }
    deleting?.let { post ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条动态？") },
            text = { Text("删除后评论和点赞也会一起消失，无法恢复。") },
            confirmButton = { TextButton(onClick = { vm.delete(post); deleting = null }) { Text("删除", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

internal fun postShareText(post: Post): String {
    val summary = post.content.trim().ifEmpty { "分享了一条动态" }
    return "[动态] ${post.authorName}：$summary".take(500)
}

internal fun postShareCard(post: Post) = shareCard(
    "type" to "post",
    "post_id" to post.id,
    "author_id" to post.authorId,
    "author_name" to post.authorName,
    "author_avatar_url" to post.author?.avatarUrl,
    "content" to post.content,
    "created_at" to post.createdAt,
)

@Composable
private fun FeedIconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).tap(scale = 0.9f, onClick = onClick), contentAlignment = Alignment.Center) {
        KcIcon(icon, tint = KcTheme.colors.text, size = 22.dp, contentDescription = description)
    }
}

/** 设计稿 P11：下划线分区选项卡，底部一条发丝线 */
@Composable
private fun SectionBar(selected: FeedSection, onSelect: (FeedSection) -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            FeedSection.entries.forEach { section ->
                val on = section == selected
                Column(Modifier.fillMaxHeight().tap(scale = 0.95f) { onSelect(section) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (section == FeedSection.Creators) KcIcon(KcIcons.sparkles, tint = colors.accent, size = 16.dp, modifier = Modifier.padding(end = 4.dp))
                        Text(section.label, style = KcTheme.type.rowTitle, color = if (on) colors.text else colors.muted, fontWeight = if (on) FontWeight.W700 else FontWeight.W600)
                    }
                    Box(Modifier.width(26.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) colors.fill else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostFeed(
    vm: FeedViewModel,
    meId: Long?,
    meName: String,
    meAvatar: String?,
    onCompose: () -> Unit,
    onOpenPost: (Long) -> Unit,
    onUser: (Long) -> Unit,
    onTopic: (String) -> Unit,
    onImage: (List<String>, Int) -> Unit,
    onShare: (Post) -> Unit,
    onDelete: (Post) -> Unit,
    onEdit: (Post) -> Unit,
    onRepost: (Post) -> Unit,
    onLikes: (Long) -> Unit,
) {
    val colors = KcTheme.colors
    val list = vm.current
    val listState = rememberLazyListState()

    // 滚到末尾前几条就加载下一页
    LaunchedEffect(listState, list) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (list.posts.isNotEmpty() && last >= list.posts.size - 2) vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        FilterBar(vm, onTopic)
        PullToRefreshBox(isRefreshing = list.refreshing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            when {
                list.loading && !list.loaded -> LoadingBox()
                list.error != null && list.posts.isEmpty() -> ErrorBox(list.error.orEmpty(), vm::retry)
                list.loaded && list.posts.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item { EmptyFeed(vm) }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp + ink.kuke.chat.ui.shell.LocalBottomBarSpace.current),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item("composer") { ComposerEntry(meName, meId ?: 0L, meAvatar, onCompose) }
                    items(list.posts, key = { it.id }) { post ->
                        val own = post.authorId == meId
                        Box(Modifier.animateItem()) { PostCard(
                            post = post,
                            actions = PostCardActions(
                                onOpen = { onOpenPost(post.id) },
                                onUser = onUser,
                                onLike = { vm.toggleLike(post) },
                                onComment = { onOpenPost(post.id) },
                                onShare = { onShare(post) },
                                onTopic = onTopic,
                                onImage = onImage,
                                onPin = if (own) ({ vm.togglePin(post) }) else null,
                                onDelete = if (own) ({ onDelete(post) }) else null,
                                onEdit = if (own) ({ onEdit(post) }) else null,
                                onRepost = if (post.moderationStatus == "approved") ({ onRepost(post) }) else null,
                                onLikes = { onLikes(post.id) },
                            ),
                        ) }
                    }
                    item("footer") {
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                            when {
                                list.loadingMore -> Text("正在加载…", style = KcTheme.type.caption, color = colors.subtle)
                                !list.hasMore -> Text("没有更多了", style = KcTheme.type.caption, color = colors.subtle)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(vm: FeedViewModel, onTopic: (String) -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 分段：最新 / 热门
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.active).padding(3.dp)) {
            listOf("latest" to "最新", "hot" to "热门").forEach { (value, label) ->
                val on = vm.sort == value
                Text(
                    label,
                    style = KcTheme.type.secondary,
                    fontWeight = if (on) FontWeight.W700 else FontWeight.W600,
                    color = if (on) colors.text else colors.muted,
                    modifier = Modifier.tap(scale = 0.94f) { vm.selectSort(value) }.clip(RoundedCornerShape(10.dp))
                        .background(if (on) colors.panel else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 热门话题：点开进话题页（不再就地筛选）
            vm.topics.forEach { topic ->
                Text(
                    "#${topic.name}",
                    style = KcTheme.type.secondary,
                    fontWeight = FontWeight.W600,
                    color = colors.muted,
                    modifier = Modifier.tap(scale = 0.95f) { onTopic(topic.name) }.clip(RoundedCornerShape(16.dp))
                        .background(colors.active).padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 设计稿 P11：头像 + 「分享你的新鲜事…」+ 图片图标，点开进入发表页 */
@Composable
private fun ComposerEntry(meName: String, meId: Long, avatar: String?, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().tap(scale = 0.98f, onClick = onClick).clip(RoundedCornerShape(16.dp)).background(colors.panel).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatar, meName, meId, 40.dp)
        Text("分享你的新鲜事…", style = KcTheme.type.body, color = colors.subtle, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        KcIcon(KcIcons.image, tint = colors.muted, size = 22.dp)
    }
}

@Composable
private fun EmptyFeed(vm: FeedViewModel) {
    val text = when {
        vm.section == FeedSection.Friends -> "好友们还没有发布动态，去动态广场看看吧"
        vm.section == FeedSection.Mine -> "你还没有发布过动态，点右下角发布第一条"
        else -> "广场上还没有动态"
    }
    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, modifier = Modifier.padding(32.dp))
    }
}
