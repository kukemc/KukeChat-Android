package ink.kuke.chat.feature.feed

import ink.kuke.chat.core.data.slot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 一层楼：根评论 + 挂在它下面的所有回复（不管回复嵌了几层，都平铺到根下，同电脑版 buildCommentThreads） */
data class CommentThread(val root: PostComment, val replies: List<PostComment>)

fun buildThreads(comments: List<PostComment>): List<CommentThread> {
    val byId = comments.associateBy { it.id }
    fun rootOf(comment: PostComment): PostComment {
        var current = comment
        val seen = HashSet<Long>()
        while (true) {
            val parentId = current.parentId ?: break
            val parent = byId[parentId] ?: break
            if (!seen.add(current.id)) break
            current = parent
        }
        return current
    }
    val replies = LinkedHashMap<Long, MutableList<PostComment>>()
    val roots = ArrayList<PostComment>()
    for (comment in comments) {
        val root = rootOf(comment)
        if (root.id == comment.id) {
            roots += comment
            replies.getOrPut(comment.id) { ArrayList() }
        } else {
            replies.getOrPut(root.id) { ArrayList() } += comment
        }
    }
    return roots.map { CommentThread(it, replies[it.id].orEmpty().sortedBy { reply -> parseInstant(reply.createdAt)?.toEpochMilli() ?: 0L }) }
}

/** 动态详情的状态：这条动态、评论、好友（@ 联想与「好友」标签）、发评论 / 回复 */
class PostViewModel(private val graph: AppGraph, val postId: Long) : ViewModel() {
    val api = PostsApi(graph.api)
    var post by mutableStateOf<Post?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var gone by mutableStateOf(false)
        private set
    val comments = mutableStateListOf<PostComment>()
    var commentsError by mutableStateOf<String?>(null)
        private set
    var hotFirst by mutableStateOf(true)
    val friends = mutableStateListOf<User>()
    var replyTo by mutableStateOf<PostComment?>(null)
    var sending by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
    var friendRequested by mutableStateOf(false)
        private set
    private val expanded = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()

    fun isExpanded(rootId: Long) = expanded[rootId] == true
    fun toggleExpanded(rootId: Long) { expanded[rootId] = !isExpanded(rootId) }

    private var refetch: Job? = null

    /** 上次看到的这条动态和评论（内存）：再进来先摆出来，再静默刷新 */
    private val cache = graph.screenCache.slot<Pair<Post, List<PostComment>>>("post.$postId")

    private fun saveSnapshot() {
        post?.let { cache.put(it to comments.toList()) }
    }

    init {
        cache.get()?.let { (cachedPost, cachedComments) ->
            post = cachedPost
            comments.addAll(cachedComments)
            loading = false
        }
        load()
        viewModelScope.launch { runCatching { api.friends() }.onSuccess { friends.addAll(it) } }
        viewModelScope.launch {
            graph.realtime.events.collect { event ->
                val id = event.data["post_id"]?.jsonPrimitive?.longOrNull ?: return@collect
                if (id != postId) return@collect
                when (event.type) {
                    "post.deleted" -> { gone = true; graph.screenCache.remove(cache.key) }
                    "post.updated", "post.like.updated", "post.comment.created", "post.comment.deleted", "post.comment.like.updated" -> {
                        refetch?.cancel()
                        refetch = viewModelScope.launch { delay(300); loadSilently() }
                    }
                }
            }
        }
    }

    fun load() {
        loading = post == null
        error = null
        viewModelScope.launch {
            runCatching { api.post(postId) }
                .onSuccess { post = it; saveSnapshot() }
                .onFailure {
                    if (it is ApiException && it.status == 404) { gone = true; graph.screenCache.remove(cache.key) }
                    error = it.userMessage()
                }
            loading = false
        }
        loadComments()
    }

    private fun loadComments() {
        viewModelScope.launch {
            runCatching { api.comments(postId) }
                .onSuccess { commentsError = null; comments.clear(); comments.addAll(it); saveSnapshot() }
                .onFailure { commentsError = it.userMessage() }
        }
    }

    private suspend fun loadSilently() {
        runCatching { api.post(postId) }.onSuccess { post = it }
        runCatching { api.comments(postId) }.onSuccess { comments.clear(); comments.addAll(it) }
        saveSnapshot()
    }

    val threads: List<CommentThread>
        get() {
            val all = buildThreads(comments)
            return if (hotFirst) all.sortedWith(compareByDescending<CommentThread> { it.root.likeCount }.thenByDescending { parseInstant(it.root.createdAt)?.toEpochMilli() ?: 0L })
            else all.sortedByDescending { parseInstant(it.root.createdAt)?.toEpochMilli() ?: 0L }
        }

    fun isFriend(userId: Long) = friends.any { it.id == userId }

    fun toggleLike() {
        val current = post ?: return
        val optimistic = current.copy(likedByMe = !current.likedByMe, likeCount = (current.likeCount + if (current.likedByMe) -1 else 1).coerceAtLeast(0))
        post = optimistic
        viewModelScope.launch {
            runCatching { api.toggleLike(postId) }
                .onSuccess { post = optimistic.copy(likedByMe = it.liked, likeCount = it.count) }
                .onFailure { post = current; notice = it.userMessage() }
        }
    }

    fun toggleCommentLike(comment: PostComment) {
        update(comment.id) { it.copy(likedByMe = !it.likedByMe, likeCount = (it.likeCount + if (it.likedByMe) -1 else 1).coerceAtLeast(0)) }
        viewModelScope.launch {
            runCatching { api.toggleCommentLike(postId, comment.id) }
                .onSuccess { result -> update(comment.id) { it.copy(likedByMe = result.liked, likeCount = result.count) } }
                .onFailure { update(comment.id) { comment }; notice = it.userMessage() }
        }
    }

    private fun update(id: Long, change: (PostComment) -> PostComment) {
        val index = comments.indexOfFirst { it.id == id }
        if (index >= 0) comments[index] = change(comments[index])
    }

    fun deleteComment(comment: PostComment) {
        viewModelScope.launch {
            runCatching { api.deleteComment(postId, comment.id) }
                .onSuccess {
                    // 删掉的评论连同它名下的回复一起消失（服务端同样处理），整份重取最稳
                    loadComments()
                    post = post?.let { it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0)) }
                }
                .onFailure { notice = it.userMessage() }
        }
    }

    /** 发评论或回复；成功返回 true（界面据此清空输入框） */
    suspend fun send(content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty() || sending) return false
        sending = true
        return try {
            val created = api.createComment(postId, text, replyTo?.id, extractMentionIds(text, friends))
            comments.add(created)
            post = post?.let { it.copy(commentCount = it.commentCount + 1) }
            replyTo = null
            true
        } catch (e: Exception) {
            notice = e.userMessage()
            false
        } finally {
            sending = false
        }
    }

    fun sendFriendRequest() {
        val authorId = post?.authorId ?: return
        viewModelScope.launch {
            runCatching {
                graph.api.post("/friends/requests", buildJsonObject { put("receiver_id", authorId) })
            }.onSuccess {
                friendRequested = true
                notice = "好友申请已发送"
            }.onFailure { notice = it.userMessage() }
        }
    }

    fun deletePost(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { api.delete(postId) }.onSuccess { onDone() }.onFailure { notice = it.userMessage() }
        }
    }

    /** 编辑保存后换上服务端返回的那一份 */
    fun replacePost(updated: Post) {
        post = updated
        saveSnapshot()
    }

    fun togglePin() {
        val current = post ?: return
        viewModelScope.launch {
            runCatching { api.pin(postId, current.pinnedAt == null) }.onSuccess { post = it }.onFailure { notice = it.userMessage() }
        }
    }
}
