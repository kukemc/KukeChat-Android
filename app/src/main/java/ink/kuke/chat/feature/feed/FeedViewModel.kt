package ink.kuke.chat.feature.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 动态页的四个分区（设计稿 P11） */
enum class FeedSection(val label: String, val scope: String?) {
    Friends("好友动态", "friends"),
    Square("动态广场", "square"),
    Mine("我的空间", "mine"),
    Creators("大佬入住", null),
}

/** 一条分区 / 排序组合（话题页也用）对应的一份列表和它的加载状态 */
class PostList {
    val posts = mutableStateListOf<Post>()
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var hasMore = true

    /** 别处发生了变化（有人发了新动态），下次看到这份列表时要静默刷新 */
    var stale = false

    /** 内容是从缓存摆出来的（上次的第一页），看到时要静默整页重拉一次 */
    var revalidate = false
}

/** 大佬入住的进程内缓存：再次进入先显示上次的名单，再静默刷新 */
private object CreatorsCache {
    var items: List<CcwCreator> = emptyList()
    var total = 0
    var hasMore = false
}

class CreatorsState {
    val items = mutableStateListOf<CcwCreator>()
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var hasMore = false
    var total by mutableStateOf(0)

    /** 数据来自缓存，第一次显示时要静默刷新一次 */
    var needsRefresh = false

    init {
        if (CreatorsCache.items.isNotEmpty()) {
            items.addAll(CreatorsCache.items)
            total = CreatorsCache.total
            hasMore = CreatorsCache.hasMore
            loaded = true
            needsRefresh = true
        }
    }

    /** 合并一页结果；第一页同时写回缓存 */
    fun apply(page: CreatorPage, reset: Boolean) {
        if (reset) items.clear()
        items.addAll(page.items.filter { incoming -> items.none { it.id == incoming.id } })
        hasMore = page.hasMore
        total = page.total
        loaded = true
        CreatorsCache.items = items.toList()
        CreatorsCache.total = total
        CreatorsCache.hasMore = hasMore
    }
}

/**
 * 动态页的状态。放在 ViewModel 里，切换底栏、进详情再回来列表原样还在。
 * 实时事件（post.created / updated / deleted / like.updated 等）只带 post_id，
 * 所以：新增 → 静默刷新当前列表；变更 → 取这一条回来替换；删除 → 直接移除。
 */
class FeedViewModel(private val graph: AppGraph) : ViewModel() {
    val api = PostsApi(graph.api)
    var section by mutableStateOf(FeedSection.Friends)
        private set
    var sort by mutableStateOf("latest")
        private set
    val topics = mutableStateListOf<PostTopic>()

    init {
        graph.screenCache.get("feed.topics", ListSerializer(PostTopic.serializer()))?.let { topics.addAll(it) }
    }
    var notice by mutableStateOf<String?>(null)
    val creators = CreatorsState()

    private val lists = HashMap<String, PostList>()
    private var topicsLoaded = false
    private var started = false

    private fun key(section: FeedSection = this.section, sort: String = this.sort) = "${section.scope}|$sort"

    /** 当前分区的列表；读它会随 section / sort 变化重组 */
    val current: PostList get() = lists.getOrPut(key()) { seeded(key()) }

    // ------------------------------------------------------------ 缓存：每份列表的第一页落盘，冷启动不空白

    private val postsSerializer = ListSerializer(Post.serializer())

    private fun seeded(listKey: String): PostList = PostList().apply {
        val cached = graph.screenCache.get("feed.$listKey", postsSerializer)
        if (!cached.isNullOrEmpty()) {
            posts.addAll(cached)
            loaded = true
            revalidate = true
        }
    }

    private fun saveFirstPage(listKey: String, list: PostList) =
        graph.screenCache.put("feed.$listKey", list.posts.take(POST_PAGE_SIZE), postsSerializer)

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch { graph.realtime.events.collect(::onEvent) }
        viewModelScope.launch { graph.realtime.reconnected.collect { refreshSilently() } }
    }

    /** 进入页面 / 切换分区后调：没加载过就加载，标记过期的静默刷新 */
    fun ensureLoaded() {
        if (section == FeedSection.Creators) {
            if (!creators.loading && (!creators.loaded || creators.needsRefresh)) {
                creators.needsRefresh = false
                loadCreators(reset = true)
            }
            return
        }
        val list = current
        if (!list.loaded && !list.loading) load(list, reset = true)
        // 缓存摆出来的：整页静默重拉（loaded 已是 true，不会转圈）
        else if (list.revalidate && !list.loading) { list.revalidate = false; list.stale = false; load(list, reset = true) }
        else if (list.stale && !list.loading) { list.stale = false; refreshSilently() }
        if (!topicsLoaded) loadTopics()
    }

    fun selectSection(next: FeedSection) {
        if (next == section) return
        section = next
        sort = "latest"
        ensureLoaded()
    }

    fun selectSort(next: String) {
        if (next == sort) return
        sort = next
        ensureLoaded()
    }

    fun refresh() {
        if (section == FeedSection.Creators) loadCreators(reset = true, pull = true) else load(current, reset = true, pull = true)
        loadTopics()
    }

    fun retry() = if (section == FeedSection.Creators) loadCreators(reset = true) else load(current, reset = true)

    fun loadMore() {
        if (section == FeedSection.Creators) {
            if (creators.hasMore && !creators.loadingMore && !creators.loading) loadCreators(reset = false)
            return
        }
        val list = current
        if (list.hasMore && !list.loadingMore && !list.loading && !list.refreshing && list.posts.isNotEmpty()) load(list, reset = false)
    }

    private fun load(list: PostList, reset: Boolean, pull: Boolean = false) {
        val scope = section.scope ?: return
        val sortNow = sort
        val listKey = key()
        if (reset) {
            if (pull) list.refreshing = true else list.loading = !list.loaded
        } else {
            list.loadingMore = true
        }
        list.error = null
        viewModelScope.launch {
            runCatching { api.feed(scope, null, sortNow, if (reset) null else list.posts.lastOrNull()?.id) }
                .onSuccess { page ->
                    if (reset) {
                        list.posts.clear()
                        list.posts.addAll(page)
                    } else {
                        list.posts.addAll(page.filter { incoming -> list.posts.none { it.id == incoming.id } })
                    }
                    list.hasMore = page.size >= POST_PAGE_SIZE
                    list.loaded = true
                    if (reset) saveFirstPage(listKey, list)
                }
                .onFailure {
                    // 已经有内容时不把整页换成错误，只在顶部提示
                    if (list.posts.isEmpty()) list.error = it.userMessage() else notice = it.userMessage()
                }
            list.loading = false
            list.loadingMore = false
            list.refreshing = false
        }
    }

    /** 实时事件、重连后用：不显示下拉动画，合并进当前列表 */
    private fun refreshSilently() {
        val scope = section.scope ?: return
        val list = current
        val sortNow = sort
        val listKey = key()
        viewModelScope.launch {
            runCatching { api.feed(scope, null, sortNow, null) }.onSuccess { page ->
                if (sortNow == "latest") {
                    // 最新排序：新的顶在最前，已有的原地更新
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
                saveFirstPage(listKey, list)
            }
        }
    }

    private fun loadTopics() {
        viewModelScope.launch {
            runCatching { api.trending(8) }.onSuccess {
                topics.clear()
                topics.addAll(it)
                graph.screenCache.put("feed.topics", it, ListSerializer(PostTopic.serializer()))
                topicsLoaded = true
            }
        }
    }

    private fun loadCreators(reset: Boolean, pull: Boolean = false) {
        if (reset) { if (pull) creators.refreshing = true else creators.loading = !creators.loaded } else creators.loadingMore = true
        creators.error = null
        viewModelScope.launch {
            runCatching { api.creators(30, if (reset) 0 else creators.items.size) }
                .onSuccess { page -> creators.apply(page, reset) }
                .onFailure { if (creators.items.isEmpty()) creators.error = it.userMessage() else notice = it.userMessage() }
            creators.loading = false
            creators.loadingMore = false
            creators.refreshing = false
        }
    }

    // ------------------------------------------------------------ 操作

    /** 替换所有列表里的同一条动态 */
    fun replace(post: Post) {
        lists.values.forEach { list ->
            val index = list.posts.indexOfFirst { it.id == post.id }
            if (index >= 0) list.posts[index] = post
        }
    }

    private fun remove(postId: Long) {
        lists.values.forEach { list -> list.posts.removeAll { it.id == postId } }
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
                .onSuccess { remove(post.id) }
                .onFailure { notice = it.userMessage() }
        }
    }

    fun togglePin(post: Post) {
        viewModelScope.launch {
            runCatching { api.pin(post.id, post.pinnedAt == null) }
                .onSuccess { updated ->
                    replace(updated)
                    // 置顶顺序由服务端排，重取一遍让它落到正确位置
                    refreshSilently()
                }
                .onFailure { notice = it.userMessage() }
        }
    }

    // ------------------------------------------------------------ 实时事件

    private val pendingFetch = HashMap<Long, Job>()

    private fun onEvent(event: ink.kuke.chat.core.realtime.RealtimeEvent) {
        when (event.type) {
            "post.created" -> {
                lists.values.forEach { it.stale = true }
                // 当前正看着的列表马上刷新（过期标记在 ensureLoaded 里被清掉）
                if (started && section != FeedSection.Creators) {
                    current.stale = false
                    refreshSilently()
                }
            }
            "post.deleted" -> event.data["post_id"]?.jsonPrimitive?.longOrNull?.let(::remove)
            "post.updated", "post.like.updated", "post.comment.created", "post.comment.deleted" -> {
                val id = event.data["post_id"]?.jsonPrimitive?.longOrNull ?: return
                if (lists.values.none { list -> list.posts.any { it.id == id } }) return
                // 同一条短时间内连发多次只取一回
                pendingFetch[id]?.cancel()
                pendingFetch[id] = viewModelScope.launch {
                    delay(300)
                    runCatching { api.post(id) }
                        .onSuccess(::replace)
                        .onFailure { if (it is ApiException && (it.status == 404 || it.status == 403)) remove(id) }
                    pendingFetch.remove(id)
                }
            }
        }
    }
}
