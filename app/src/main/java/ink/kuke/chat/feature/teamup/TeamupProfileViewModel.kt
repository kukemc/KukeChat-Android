package ink.kuke.chat.feature.teamup

import ink.kuke.chat.core.data.slot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.feed.asItems
import ink.kuke.chat.feature.feed.decodeOne
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class TeamupThread(val root: TeamupComment, val replies: List<TeamupComment>)

/** 一层楼：不管回复嵌了几层，都挂到最顶上那条留言下面 */
fun buildTeamupThreads(comments: List<TeamupComment>): List<TeamupThread> {
    val byId = comments.associateBy { it.id }
    fun rootOf(comment: TeamupComment): TeamupComment {
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
    val replies = LinkedHashMap<Long, MutableList<TeamupComment>>()
    val roots = ArrayList<TeamupComment>()
    for (comment in comments) {
        val root = rootOf(comment)
        if (root.id == comment.id) {
            roots += comment
            replies.getOrPut(comment.id) { ArrayList() }
        } else {
            replies.getOrPut(root.id) { ArrayList() } += comment
        }
    }
    fun time(c: TeamupComment) = parseInstant(c.createdAt)?.toEpochMilli() ?: 0L
    return roots.sortedBy(::time).map { TeamupThread(it, replies[it.id].orEmpty().sortedBy(::time)) }
}

/** 名片详情：名片、留言板、好友关系（加好友 / 接受 / 已申请）、发留言 */
class TeamupProfileViewModel(private val graph: AppGraph, val profileId: Long) : ViewModel() {
    private val api = TeamupApi(graph.api)

    var profile by mutableStateOf<TeamupProfile?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val comments = mutableStateListOf<TeamupComment>()
    var commentsError by mutableStateOf<String?>(null)
        private set

    /** 我已经向对方发过好友申请 / 对方向我发过（带申请 id，可以直接接受） */
    var requestSent by mutableStateOf(false)
        private set
    var incomingRequestId by mutableStateOf<Long?>(null)
        private set
    var relationBusy by mutableStateOf(false)
        private set

    var replyTo by mutableStateOf<TeamupComment?>(null)
    var sending by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)

    /** 申请发出 / 接受成功后递增，界面据此播放「组队申请已发送」庆祝动画 */
    var celebrate by mutableStateOf(0)
        private set

    private var refetch: Job? = null

    /** 上次看到的名片和留言（内存）：再进来先摆出来，再静默刷新 */
    private val cache = graph.screenCache.slot<Pair<TeamupProfile, List<TeamupComment>>>("teamup.profile.$profileId")

    private fun saveSnapshot() {
        profile?.let { cache.put(it to comments.toList()) }
    }

    init {
        cache.get()?.let { (cachedProfile, cachedComments) ->
            profile = cachedProfile
            comments.addAll(cachedComments)
            loading = false
        }
        load()
        viewModelScope.launch {
            graph.realtime.events.collect { event ->
                if (!event.type.startsWith("teamup.")) return@collect
                val id = event.data["profile_id"]?.jsonPrimitive?.longOrNull
                if (id != null && id != profileId) return@collect
                refetch?.cancel()
                refetch = viewModelScope.launch { delay(400); silent() }
            }
        }
    }

    fun load() {
        loading = profile == null
        error = null
        viewModelScope.launch {
            runCatching { api.profile(profileId) }
                .onSuccess { profile = it; saveSnapshot(); loadRelations() }
                .onFailure {
                    error = if (it is ApiException) it.message else it.userMessage()
                    // 缓存里有、服务端已经没了：别继续摆旧的
                    if (it is ApiException && it.status == 404) { profile = null; graph.screenCache.remove(cache.key) }
                }
            loading = false
        }
        loadComments()
    }

    private fun silent() {
        viewModelScope.launch {
            runCatching { api.profile(profileId) }.onSuccess { profile = it }
            runCatching { api.comments(profileId) }.onSuccess { comments.clear(); comments.addAll(it) }
            saveSnapshot()
        }
    }

    private fun loadComments() {
        viewModelScope.launch {
            runCatching { api.comments(profileId) }
                .onSuccess { commentsError = null; comments.clear(); comments.addAll(it); saveSnapshot() }
                .onFailure { commentsError = it.userMessage() }
        }
    }

    private fun loadRelations() {
        viewModelScope.launch {
            runCatching { graph.api.get("/friends/requests/outgoing").asItems() }.onSuccess { items ->
                requestSent = items.any { element ->
                    val obj = element as? JsonObject
                    obj?.get("receiver_id")?.jsonPrimitive?.longOrNull == profile?.userId && obj?.get("status")?.jsonPrimitive?.contentOrNull == "pending"
                }
            }
            runCatching { graph.api.get("/friends/requests/incoming").asItems() }.onSuccess { items ->
                val match = items.firstOrNull { element ->
                    val obj = element as? JsonObject
                    obj?.get("requester_id")?.jsonPrimitive?.longOrNull == profile?.userId && obj?.get("status")?.jsonPrimitive?.contentOrNull == "pending"
                } as? JsonObject
                incomingRequestId = match?.get("id")?.jsonPrimitive?.longOrNull
            }
        }
    }

    fun requestFriend() {
        val target = profile?.userId ?: return
        if (relationBusy) return
        relationBusy = true
        viewModelScope.launch {
            runCatching { graph.api.post("/friends/requests", buildJsonObject { put("receiver_id", target) }) }
                .onSuccess { result ->
                    // 对方先发过申请时服务端直接通过，这时已经是好友
                    val accepted = (result as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull == "accepted"
                    if (accepted) profile = profile?.copy(isFriend = true) else requestSent = true
                    celebrate++
                    runCatching { graph.conversations.refresh() }
                }
                .onFailure { notice = it.userMessage() }
            relationBusy = false
        }
    }

    fun acceptFriend() {
        val id = incomingRequestId ?: return
        if (relationBusy) return
        relationBusy = true
        viewModelScope.launch {
            runCatching { graph.api.post("/friends/requests/$id/accept") }
                .onSuccess {
                    profile = profile?.copy(isFriend = true)
                    incomingRequestId = null
                    celebrate++
                    runCatching { graph.conversations.refresh() }
                }
                .onFailure { notice = it.userMessage() }
            relationBusy = false
        }
    }

    val threads: List<TeamupThread> get() = buildTeamupThreads(comments)

    suspend fun send(content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty() || sending) return false
        sending = true
        return try {
            val body = buildJsonObject {
                put("content", text)
                replyTo?.id?.let { put("parent_id", it) }
            }
            val created = graph.api.post("/teamup/profiles/$profileId/comments", body).decodeOne<TeamupComment>()
            comments.add(created)
            profile = profile?.let { it.copy(commentCount = it.commentCount + 1) }
            replyTo = null
            true
        } catch (e: Exception) {
            notice = e.userMessage()
            false
        } finally {
            sending = false
        }
    }

    fun toggleLike(comment: TeamupComment) {
        update(comment.id) { it.copy(likedByMe = !it.likedByMe, likeCount = (it.likeCount + if (it.likedByMe) -1 else 1).coerceAtLeast(0)) }
        viewModelScope.launch {
            runCatching { graph.api.post("/teamup/profiles/$profileId/comments/${comment.id}/like") }
                .onSuccess { result ->
                    val obj = result as? JsonObject
                    val liked = obj?.get("liked")?.jsonPrimitive?.booleanOrNull
                    val count = obj?.get("like_count")?.jsonPrimitive?.intOrNull
                    if (liked != null && count != null) update(comment.id) { it.copy(likedByMe = liked, likeCount = count) }
                }
                .onFailure { update(comment.id) { comment }; notice = it.userMessage() }
        }
    }

    private fun update(id: Long, change: (TeamupComment) -> TeamupComment) {
        val index = comments.indexOfFirst { it.id == id }
        if (index >= 0) comments[index] = change(comments[index])
    }

    fun delete(comment: TeamupComment) {
        viewModelScope.launch {
            runCatching { graph.api.delete("/teamup/profiles/$profileId/comments/${comment.id}") }
                .onSuccess {
                    loadComments()
                    profile = profile?.let { it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0)) }
                }
                .onFailure { notice = it.userMessage() }
        }
    }

    fun onProfileSaved(saved: TeamupProfile) {
        profile = saved
    }
}
