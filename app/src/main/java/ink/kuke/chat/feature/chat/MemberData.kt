package ink.kuke.chat.feature.chat

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ink.kuke.chat.core.data.slot

/** 群成员的精简视图（GET /conversations/{id}/members 的一项）。聊天里的 @ 面板、送礼选人、群资料页共用 */
data class MemberLite(
    val userId: Long,
    val name: String,
    val username: String,
    val avatarUrl: String?,
    val role: String,
    val title: String?,
    val groupNickname: String?,
    val muted: Boolean,
    val level: Int?,
    val isBot: Boolean,
) {
    val isOwner: Boolean get() = role == "owner"
    val isAdmin: Boolean get() = role == "admin"
}

fun parseMember(obj: JsonObject): MemberLite? {
    val user = obj.o("user")
    val userId = obj.l("user_id") ?: user?.l("id") ?: return null
    val groupNickname = obj.s("nickname")?.takeIf { it.isNotBlank() }
    val username = user?.s("username").orEmpty()
    return MemberLite(
        userId = userId,
        name = groupNickname ?: user?.s("nickname")?.takeIf { it.isNotBlank() } ?: username.ifBlank { "用户 $userId" },
        username = username,
        avatarUrl = user?.s("avatar_url"),
        role = obj.s("role") ?: "member",
        title = obj.s("title")?.takeIf { it.isNotBlank() },
        groupNickname = groupNickname,
        muted = obj.b("muted") == true,
        level = obj.i("level"),
        isBot = user?.b("is_bot") == true,
    )
}

/** 缓存里的一个群成员：只留身份标签和 @ 名字要用的 */
@kotlinx.serialization.Serializable
data class CachedMember(val id: Long, val role: String, val name: String)

/**
 * 群成员角色 / 名字的缓存（按会话，内存 + 磁盘，走 [ink.kuke.chat.core.data.ScreenCache]，按登录用户隔离、退出登录清掉）。
 * 进聊天先用它画身份标签（「管理员」「群主」），成员列表拉回来再覆盖：不然每次进群标签都先是普通成员、几秒后才变。
 * 消息本身不带发送者的角色（只有 sender_title / sender_level），角色只能从成员列表来。
 */
object MemberDirectory {
    private val serializer = kotlinx.serialization.builtins.ListSerializer(CachedMember.serializer())

    private fun slot(graph: ink.kuke.chat.core.AppGraph, conversationId: Long) =
        graph.screenCache.slot<List<CachedMember>>("chat.members.$conversationId", serializer)

    /** 上次看到的成员（内存没有就同步读磁盘，文件很小）；没有返回 null */
    fun cached(graph: ink.kuke.chat.core.AppGraph, conversationId: Long): List<CachedMember>? = slot(graph, conversationId).get()

    /** 拉一次完整成员列表并写回缓存 */
    suspend fun refresh(graph: ink.kuke.chat.core.AppGraph, conversationId: Long): List<CachedMember> {
        val list = parseMemberPage(graph.api.get("/conversations/$conversationId/members")).items
            .map { CachedMember(it.userId, it.role, it.name) }
        slot(graph, conversationId).put(list)
        return list
    }
}

/** 成员接口带分页参数时返回 {items, total, has_more}，否则是数组：两种都认 */
data class MemberPage(val items: List<MemberLite>, val total: Int, val hasMore: Boolean)

fun parseMemberPage(element: JsonElement, offset: Int = 0): MemberPage {
    val array = element.asArr() ?: element.asObj()?.a("items")
    val items = array?.mapNotNull { it.asObj()?.let(::parseMember) }.orEmpty()
    val obj = element.asObj()
    val total = obj?.i("total") ?: items.size
    val hasMore = obj?.b("has_more") ?: (offset + items.size < total)
    return MemberPage(items, total, hasMore)
}

/**
 * 会话草稿：输入了没发就离开，会话列表那一行显示「[草稿] …」，回来输入框里还在。
 * 只存在本机（SharedPreferences），退出登录不清（同一台设备同一个人的习惯）。
 */
object Drafts {
    private val state = MutableStateFlow<Map<Long, String>>(emptyMap())
    val all: StateFlow<Map<Long, String>> = state.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("kuke_drafts", Context.MODE_PRIVATE)
        prefs = p
        state.value = p.all.mapNotNull { (key, value) -> key.removePrefix("d_").toLongOrNull()?.let { id -> (value as? String)?.let { id to it } } }.toMap()
    }

    fun get(conversationId: Long): String = state.value[conversationId].orEmpty()

    fun set(conversationId: Long, text: String) {
        val clean = text.trimEnd()
        if (clean.isBlank()) {
            if (state.value.containsKey(conversationId)) {
                state.value = state.value - conversationId
                prefs?.edit()?.remove("d_$conversationId")?.apply()
            }
            return
        }
        if (state.value[conversationId] == clean) return
        state.value = state.value + (conversationId to clean)
        prefs?.edit()?.putString("d_$conversationId", clean)?.apply()
    }
}

/**
 * 在会话列表里「删除」的会话：本地先藏起来，对方有新消息（updated_at 变了）再出现。
 * 服务端没有「隐藏会话」，删除只对应「清空聊天记录」（私聊）/「退出群聊」（群），这里负责让列表立刻干净。
 */
object HiddenConversations {
    private val state = MutableStateFlow<Map<Long, String>>(emptyMap())
    val all: StateFlow<Map<Long, String>> = state.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("kuke_hidden_conversations", Context.MODE_PRIVATE)
        prefs = p
        state.value = p.all.mapNotNull { (key, value) -> key.removePrefix("h_").toLongOrNull()?.let { id -> (value as? String)?.let { id to it } } }.toMap()
    }

    /** stamp 取隐藏时会话的 updated_at：之后 updated_at 比它新（来了新消息）就不再隐藏 */
    fun hide(conversationId: Long, stamp: String) {
        state.value = state.value + (conversationId to stamp)
        prefs?.edit()?.putString("h_$conversationId", stamp)?.apply()
    }
}
