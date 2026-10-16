package ink.kuke.chat.feature.feed

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ComposePostRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.PostRoute
import ink.kuke.chat.ui.shell.TopicRoute
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 话题页的进程内缓存：同一个话题再次打开先显示上次的第一屏，再静默刷新 */
private object TopicCache {
    class Entry(val posts: List<Post>, val hasMore: Boolean)

    val lists = HashMap<String, Entry>()
    val counts = HashMap<String, Int>()

    fun key(name: String, sort: String) = "${name.lowercase()}|$sort"
}

/** 话题动态走 /posts/feed 的 topic 参数，范围用「广场」（公开动态） */
private const val TOPIC_SCOPE = "square"

/**
 * 一个话题的状态：最新 / 热门两份列表各自缓存。
 * 实时事件只带 post_id：有人发了新动态 → 静默合并第一页；删除 → 直接移除。
 */
class TopicViewModel(private val graph: AppGraph, val name: String) : ViewModel() {
    private val api = PostsApi(graph.api)
    var sort by mutableStateOf("latest")
        private set
    var count by mutableStateOf(TopicCache.counts[name.lowercase()])
        private set
    var notice by mutableStateOf<String?>(null)

    private val lists = HashMap<String, PostList>()

    val current: PostList get() = lists.getOrPut(sort) { seeded(sort) }

    /** 新建列表时先灌入缓存，标成过期，显示后静默刷新 */
    private fun seeded(sort: String): PostList = PostList().apply {
        TopicCache.lists[TopicCache.key(name, sort)]?.let { cached ->
            posts.addAll(cached.posts)
            hasMore = cached.hasMore
            loaded = true
            stale = true
        }
    }

    init {
        viewModelScope.launch {
            graph.realtime.events.collect { event ->
                when (event.type) {
                    "post.created" -> { lists.values.forEach { it.stale = true }; current.stale = false; merge(current, sort) }
                    "post.deleted" -> event.data["post_id"]?.jsonPrimitive?.longOrNull?.let { id -> lists.values.forEach { list -> list.posts.removeAll { it.id == id } } }
                }
            }
        }
        viewModelScope.launch { graph.realtime.reconnected.collect { merge(current, sort) } }
        loadCount()
    }

    fun ensureLoaded() {
        val list = current
        if (!list.loaded && !list.loading) load(list, sort, reset = true)
        else if (list.stale && !list.loading) { list.stale = false; merge(list, sort) }
    }

    fun selectSort(next: String) {
        if (next == sort) return
        sort = next
        ensureLoaded()
    }

    fun refresh() {
        load(current, sort, reset = true, pull = true)
        loadCount()
    }

    fun retry() = load(current, sort, reset = true)

    fun loadMore() {
        val list = current
        if (list.hasMore && !list.loadingMore && !list.loading && !list.refreshing && list.posts.isNotEmpty()) load(list, sort, reset = false)
    }

    private fun cache(list: PostList, sort: String) {
        TopicCache.lists[TopicCache.key(name, sort)] = TopicCache.Entry(list.posts.take(POST_PAGE_SIZE * 2), list.hasMore || list.posts.size > POST_PAGE_SIZE * 2)
        // 接口不单独给话题详情：从动态自带的 topics 里顺手拿帖子数
        list.posts.asSequence().flatMap { it.topics }.firstOrNull { it.name.equals(name, true) && it.postCount > 0 }?.let { setCount(it.postCount) }
    }

    private fun setCount(value: Int) {
        count = value
        TopicCache.counts[name.lowercase()] = value
    }

    private fun loadCount() {
        viewModelScope.launch {
            runCatching { api.searchTopics(name, 8) }.onSuccess { found ->
                found.firstOrNull { it.name.equals(name, true) }?.let { setCount(it.postCount) }
            }
        }
    }

    private fun load(list: PostList, sort: String, reset: Boolean, pull: Boolean = false) {
        if (reset) {
            if (pull) list.refreshing = true else list.loading = !list.loaded
        } else {
            list.loadingMore = true
        }
        list.error = null
        viewModelScope.launch {
            runCatching { api.feed(TOPIC_SCOPE, name, sort, if (reset) null else list.posts.lastOrNull()?.id) }
                .onSuccess { page ->
                    if (reset) {
                        list.posts.clear()
                        list.posts.addAll(page)
                    } else {
                        list.posts.addAll(page.filter { incoming -> list.posts.none { it.id == incoming.id } })
                    }
                    list.hasMore = page.size >= POST_PAGE_SIZE
                    list.loaded = true
                    cache(list, sort)
                }
                .onFailure { if (list.posts.isEmpty()) list.error = it.userMessage() else notice = it.userMessage() }
            list.loading = false
            list.loadingMore = false
            list.refreshing = false
        }
    }

    /** 静默刷新：不动滚动和分页，最新排序把新的顶到最前，已有的原地更新 */
    private fun merge(list: PostList, sort: String) {
        viewModelScope.launch {
            runCatching { api.feed(TOPIC_SCOPE, name, sort, null) }.onSuccess { page ->
                if (!list.loaded) {
                    list.posts.addAll(page)
                    list.hasMore = page.size >= POST_PAGE_SIZE
                } else if (sort == "latest") {
                    page.reversed().forEach { incoming ->
                        val index = list.posts.indexOfFirst { it.id == incoming.id }
                        if (index >= 0) list.posts[index] = incoming else list.posts.add(0, incoming)
                    }
                } else {
                    page.forEach { incoming ->
                        val index = list.posts.indexOfFirst { it.id == incoming.id }
                        if (index >= 0) list.posts[index] = incoming
                    }
                }
                list.loaded = true
                cache(list, sort)
            }
        }
    }

    private fun replace(post: Post) {
        lists.values.forEach { list ->
            val index = list.posts.indexOfFirst { it.id == post.id }
            if (index >= 0) list.posts[index] = post
        }
    }

    fun toggleLike(post: Post) {
        val optimistic = post.copy(likedByMe = !post.likedByMe, likeCount = (post.likeCount + if (post.likedByMe) -1 else 1).coerceAtLeast(0))
        replace(optimistic)
        viewModelScope.launch {
            runCatching { api.toggleLike(post.id) }
                .onSuccess { result -> replace(optimistic.copy(likedByMe = result.liked, likeCount = result.count)) }
                .onFailure { replace(post); notice = it.userMessage() }
        }
    }

    fun delete(post: Post) {
        viewModelScope.launch {
            runCatching { api.delete(post.id) }
                .onSuccess { lists.values.forEach { list -> list.posts.removeAll { it.id == post.id } } }
                .onFailure { if (it is ApiException && it.status == 404) lists.values.forEach { list -> list.posts.removeAll { p -> p.id == post.id } } else notice = it.userMessage() }
        }
    }
}

/**
 * 话题页：顶栏「#话题」· 头部（话题名、动态数、「发动态」）· 最新 / 热门 · 帖子列表。
 * 动态正文里的 #话题、动态页的热门话题药丸都打开这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(graph: AppGraph, name: String) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val vm = graphViewModel("topic:${name.lowercase()}") { TopicViewModel(graph, name) }
    val me = graph.session.session.collectAsStateWithLifecycle().value?.user
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var sharing by remember { mutableStateOf<Post?>(null) }
    var deleting by remember { mutableStateOf<Post?>(null) }
    var reposting by remember { mutableStateOf<Post?>(null) }

    LaunchedEffect(Unit) { vm.ensureLoaded() }
    LaunchedEffect(vm.notice) {
        if (vm.notice != null) {
            delay(2600)
            vm.notice = null
        }
    }

    val compose = {
        ComposePostPrefill.set("#$name ")
        nav.go(ComposePostRoute)
    }

    KcPage(title = "#$name", onBack = { nav.back() }) {
        val list = vm.current
        val listState = rememberLazyListState()
        LaunchedEffect(listState, list) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                // 前面有头部一项，所以按 posts.size 比较正好是倒数第二条
                if (list.posts.isNotEmpty() && last >= list.posts.size - 1) vm.loadMore()
            }
        }
        PullToRefreshBox(isRefreshing = list.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().background(colors.list)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item("topic-head") { TopicHeader(name, vm.count, vm.sort, vm::selectSort, compose) }
                when {
                    list.loading && !list.loaded -> items(3, key = { "skeleton-$it" }) { PostSkeleton() }
                    list.error != null && list.posts.isEmpty() -> item("topic-error") {
                        ErrorBox(list.error.orEmpty(), vm::retry, Modifier.fillMaxWidth().height(280.dp))
                    }
                    list.loaded && list.posts.isEmpty() -> item("topic-empty") { TopicEmpty(compose) }
                    else -> {
                        items(list.posts, key = { it.id }) { post ->
                            val own = post.authorId == me?.id
                            Box(Modifier.animateItem()) {
                                PostCard(
                                    post = post,
                                    actions = PostCardActions(
                                        onOpen = { nav.go(PostRoute(post.id)) },
                                        onUser = { nav.go(UserRoute(it)) },
                                        onLike = { vm.toggleLike(post) },
                                        onComment = { nav.go(PostRoute(post.id)) },
                                        onShare = { sharing = post },
                                        // 同一个话题不再叠一层页面
                                        onTopic = { if (!it.equals(name, true)) nav.go(TopicRoute(it)) },
                                        onImage = { urls, index -> viewer = urls to index },
                                        onDelete = if (own) ({ deleting = post }) else null,
                                        onRepost = if (post.moderationStatus == "approved") ({ reposting = post }) else null,
                                        onLikes = { nav.go(ink.kuke.chat.ui.shell.PostLikesRoute(post.id)) },
                                    ),
                                )
                            }
                        }
                        item("topic-footer") {
                            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                                when {
                                    list.loadingMore -> Text("正在加载…", style = KcTheme.type.caption, color = colors.subtle)
                                    !list.hasMore -> Text("没有更多了", style = KcTheme.type.caption, color = colors.subtle)
                                }
                            }
                        }
                    }
                }
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
    reposting?.let { post -> RepostSheet(graph, post, onDismiss = { reposting = null }) { reposting = null } }
    sharing?.let { post -> ShareSheet(graph, "分享动态", postShareText(post), postShareCard(post)) { sharing = null } }
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

/** 头部：# 图块 + 话题名（大字）+ 动态数；下面一行是最新 / 热门和「发动态」 */
@Composable
private fun TopicHeader(name: String, count: Int?, sort: String, onSort: (String) -> Unit, onCompose: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.panel).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.hash, tint = colors.accent, size = 26.dp)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(name, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.W800, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (count != null && count > 0) "${wanCount(count)} 条动态" else "话题",
                    style = type.caption,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.active).padding(3.dp)) {
                listOf("latest" to "最新", "hot" to "热门").forEach { (value, label) ->
                    val on = sort == value
                    Text(
                        label,
                        style = type.secondary,
                        fontWeight = if (on) FontWeight.W700 else FontWeight.W600,
                        color = if (on) colors.text else colors.muted,
                        modifier = Modifier.tap(scale = 0.94f) { onSort(value) }.clip(RoundedCornerShape(10.dp))
                            .background(if (on) colors.panel else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.tap(scale = 0.94f, onClick = onCompose).clip(RoundedCornerShape(18.dp)).background(colors.fill).padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcIcon(KcIcons.plus, tint = colors.onFill, size = 16.dp)
                Text("发动态", style = type.secondary, fontWeight = FontWeight.W600, color = colors.onFill, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun TopicEmpty(onCompose: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        KcIcon(KcIcons.hash, tint = colors.subtle, size = 34.dp)
        Text("这个话题下还没有动态", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
        Text(
            "发第一条",
            style = KcTheme.type.secondary,
            fontWeight = FontWeight.W600,
            color = colors.accent,
            modifier = Modifier.padding(top = 12.dp).tap(scale = 0.94f, onClick = onCompose).clip(RoundedCornerShape(16.dp))
                .background(colors.accentSoft).padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

/** 帖子骨架：首次加载时代替转圈，版式和 PostCard 大致对齐 */
@Composable
internal fun PostSkeleton() {
    val colors = KcTheme.colors
    val block = colors.active
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel).padding(horizontal = 14.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(block))
            Column(Modifier.padding(start = 10.dp)) {
                Box(Modifier.width(96.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
                Box(Modifier.padding(top = 6.dp).width(64.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(block))
            }
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.72f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
    }
}
