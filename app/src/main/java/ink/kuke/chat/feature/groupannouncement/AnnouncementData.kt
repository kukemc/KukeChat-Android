package ink.kuke.chat.feature.groupannouncement

import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.ui.parseInstant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 群公告 v2（KukeChat-Server/docs/group-announcements.md）。
 * 路径前缀 /conversations/{id}/announcements；群主 / 管理员发布、修改、删除、看确认统计，普通成员只能看和确认。
 */

const val ANNOUNCEMENT_MAX_LENGTH = 600
const val ANNOUNCEMENT_MAX_IMAGES = 9

@Serializable
data class AnnouncementImage(
    val url: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
) {
    val preview: String get() = thumbnailUrl?.takeIf { it.isNotBlank() } ?: url
}

@Serializable
data class GroupAnnouncement(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long = 0,
    @SerialName("author_id") val authorId: Long? = null,
    val author: User? = null,
    @SerialName("author_display_name") val authorDisplayName: String? = null,
    val content: String = "",
    val images: List<AnnouncementImage> = emptyList(),
    val pinned: Boolean = false,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    val popup: Boolean = false,
    @SerialName("require_confirm") val requireConfirm: Boolean = false,
    @SerialName("send_to_new_members") val sendToNewMembers: Boolean = false,
    @SerialName("guide_nickname") val guideNickname: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("confirmed_count") val confirmedCount: Int = 0,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("my_seen") val mySeen: Boolean = false,
    @SerialName("my_confirmed") val myConfirmed: Boolean = false,
) {
    /** 发布人：群昵称 > 昵称 > 用户名；发布人已注销时是「已注销用户」 */
    val authorName: String
        get() = authorDisplayName?.takeIf { it.isNotBlank() } ?: author?.displayName ?: "已注销用户"
}

@Serializable
data class ConfirmationItem(
    val user: User? = null,
    val nickname: String? = null,
    val role: String = "member",
    @SerialName("confirmed_at") val confirmedAt: String? = null,
) {
    val name: String get() = nickname?.takeIf { it.isNotBlank() } ?: user?.displayName ?: "已注销用户"
}

@Serializable
data class ConfirmationPage(
    @SerialName("announcement_id") val announcementId: Long = 0,
    val status: String = "confirmed",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("confirmed_count") val confirmedCount: Int = 0,
    @SerialName("unconfirmed_count") val unconfirmedCount: Int = 0,
    val items: List<ConfirmationItem> = emptyList(),
    val limit: Int = 50,
    val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** 发布 / 修改的请求体（2.1 节）。修改也整份提交：编辑页上所有选项都摆着 */
data class AnnouncementDraft(
    val content: String,
    val images: List<String>,
    val pinned: Boolean,
    val popup: Boolean,
    val requireConfirm: Boolean,
    val sendToNewMembers: Boolean,
    val guideNickname: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("content", content.trim())
        put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
        put("pinned", pinned)
        put("popup", popup)
        put("require_confirm", requireConfirm)
        put("send_to_new_members", sendToNewMembers)
        put("guide_nickname", guideNickname)
    }
}

class GroupAnnouncementApi(private val graph: AppGraph) {
    private fun base(conversationId: Long) = "/conversations/$conversationId/announcements"

    private fun decode(element: JsonElement): GroupAnnouncement = KcJson.decodeFromJsonElement(GroupAnnouncement.serializer(), element)
    private fun decodeList(element: JsonElement): List<GroupAnnouncement> =
        KcJson.decodeFromJsonElement(ListSerializer(GroupAnnouncement.serializer()), element as? JsonArray ?: JsonArray(emptyList()))

    suspend fun list(conversationId: Long): List<GroupAnnouncement> =
        decodeList(graph.api.get(base(conversationId))).also { AnnouncementHub.remember(it) }

    suspend fun popups(conversationId: Long): List<GroupAnnouncement> =
        decodeList(graph.api.get("${base(conversationId)}/popups")).also { AnnouncementHub.remember(it) }

    suspend fun get(conversationId: Long, id: Long): GroupAnnouncement =
        decode(graph.api.get("${base(conversationId)}/$id")).also { AnnouncementHub.remember(listOf(it)) }

    suspend fun create(conversationId: Long, draft: AnnouncementDraft): GroupAnnouncement =
        decode(graph.api.post(base(conversationId), draft.toJson())).also { AnnouncementHub.changed(it) }

    suspend fun update(conversationId: Long, id: Long, draft: AnnouncementDraft): GroupAnnouncement =
        decode(graph.api.patch("${base(conversationId)}/$id", draft.toJson())).also { AnnouncementHub.changed(it) }

    suspend fun delete(conversationId: Long, id: Long) {
        graph.api.delete("${base(conversationId)}/$id")
        AnnouncementHub.deleted(conversationId, id)
    }

    /** 标记我看过（弹窗关掉 / 打开详情） */
    suspend fun seen(conversationId: Long, id: Long) {
        graph.api.post("${base(conversationId)}/$id/seen")
        AnnouncementHub.patchLocal(id) { it.copy(mySeen = true) }
    }

    suspend fun confirm(conversationId: Long, id: Long): GroupAnnouncement =
        decode(graph.api.post("${base(conversationId)}/$id/confirm")).also { AnnouncementHub.changed(it.copy(mySeen = true, myConfirmed = true)) }

    suspend fun confirmations(conversationId: Long, id: Long, status: String, offset: Int, limit: Int = 50): ConfirmationPage =
        KcJson.decodeFromJsonElement(
            ConfirmationPage.serializer(),
            graph.api.get("${base(conversationId)}/$id/confirmations?status=$status&limit=$limit&offset=$offset"),
        )
}

/**
 * 群公告的进程内状态：最近见过的公告（打开详情先摆出来再刷新）、被删除的公告 id（聊天里的公告卡片变成「该公告已删除」），
 * 以及「某个群的公告变了」的信号（列表 / 详情 / 确认统计收到就重拉）。
 * 实时事件（group.announcement.created / updated / deleted / confirmations）由 [start] 订阅，整个进程一份。
 */
object AnnouncementHub {
    private val cache = ConcurrentHashMap<Long, GroupAnnouncement>()

    private val _deleted = MutableStateFlow<Set<Long>>(emptySet())
    val deletedIds: StateFlow<Set<Long>> = _deleted.asStateFlow()

    /** 变化信号：(会话 id, 公告 id)；公告 id 为 0 表示整个群的公告列表都可能变了 */
    private val _changes = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 32)
    val changes: SharedFlow<Pair<Long, Long>> = _changes.asSharedFlow()

    /** 实时推来的、带弹窗的公告（created / updated 且 popup = true）：正开着的群聊收到就重拉一次弹窗 */
    private val _popupHints = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val popupHints: SharedFlow<Long> = _popupHints.asSharedFlow()

    @Volatile private var startedFor: AppGraph? = null

    fun start(graph: AppGraph) {
        if (startedFor === graph) return
        synchronized(this) {
            if (startedFor === graph) return
            startedFor = graph
        }
        graph.scope.launch {
            graph.realtime.events.collect { event ->
                when (event.type) {
                    "group.announcement.created", "group.announcement.updated" -> {
                        val incoming = runCatching { KcJson.decodeFromJsonElement(GroupAnnouncement.serializer(), event.data) }.getOrNull() ?: return@collect
                        // 推送里的 my_seen / my_confirmed 恒为 false：保留本地值；修改过的公告「看过」以服务端下次返回为准
                        val local = cache[incoming.id]
                        changed(incoming.copy(mySeen = local?.mySeen ?: false, myConfirmed = local?.myConfirmed ?: false))
                        if (incoming.popup) _popupHints.tryEmit(incoming.conversationId)
                    }
                    "group.announcement.deleted" -> {
                        val conversationId = event.data.long("conversation_id") ?: return@collect
                        val id = event.data.long("announcement_id") ?: return@collect
                        deleted(conversationId, id)
                    }
                    "group.announcement.confirmations" -> {
                        val conversationId = event.data.long("conversation_id") ?: return@collect
                        val id = event.data.long("announcement_id") ?: return@collect
                        val confirmed = event.data.int("confirmed_count")
                        val members = event.data.int("member_count")
                        patchLocal(id) { it.copy(confirmedCount = confirmed ?: it.confirmedCount, memberCount = members ?: it.memberCount) }
                        _changes.tryEmit(conversationId to id)
                    }
                }
            }
        }
    }

    fun cached(id: Long): GroupAnnouncement? = cache[id]

    fun isDeleted(id: Long): Boolean = id in _deleted.value

    internal fun remember(items: List<GroupAnnouncement>) {
        items.forEach { cache[it.id] = it }
    }

    internal fun changed(item: GroupAnnouncement) {
        cache[item.id] = item
        _changes.tryEmit(item.conversationId to 0L)
        _changes.tryEmit(item.conversationId to item.id)
    }

    internal fun deleted(conversationId: Long, id: Long) {
        cache.remove(id)
        _deleted.update { it + id }
        _changes.tryEmit(conversationId to 0L)
        _changes.tryEmit(conversationId to id)
    }

    internal fun patchLocal(id: Long, change: (GroupAnnouncement) -> GroupAnnouncement) {
        cache[id]?.let { cache[id] = change(it) }
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

// ------------------------------------------------------------------ 聊天里的公告卡片（第 5 节）

/** 公告卡片消息的 metadata（type = system，system_event = group.announcement.card） */
data class AnnouncementCardMeta(
    val announcementId: Long,
    val action: String,
    val summary: String,
    val imageUrl: String?,
    val imageThumbnailUrl: String?,
    val imageCount: Int,
    val authorId: Long?,
    val authorName: String,
    val requireConfirm: Boolean,
    val deleted: Boolean,
)

fun announcementCardOf(message: Message): AnnouncementCardMeta? {
    if (message.type != "system") return null
    val meta = message.metadata ?: return null
    if (meta.str("system_event") != "group.announcement.card") return null
    val id = (meta["announcement_id"] as? JsonPrimitive)?.longOrNull ?: return null
    return AnnouncementCardMeta(
        announcementId = id,
        action = meta.str("action") ?: "created",
        summary = meta.str("summary").orEmpty(),
        imageUrl = meta.str("image_url")?.takeIf { it.isNotBlank() },
        imageThumbnailUrl = meta.str("image_thumbnail_url")?.takeIf { it.isNotBlank() },
        imageCount = (meta["image_count"] as? JsonPrimitive)?.intOrNull ?: 0,
        authorId = (meta["author_id"] as? JsonPrimitive)?.longOrNull,
        authorName = meta.str("author_name")?.takeIf { it.isNotBlank() } ?: message.senderName,
        requireConfirm = (meta["require_confirm"] as? JsonPrimitive)?.booleanOrNull == true,
        deleted = (meta["deleted"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

// ------------------------------------------------------------------ 时间

private val SameYear = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val OtherYear = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
private val Clock = DateTimeFormatter.ofPattern("HH:mm")

/** 今天 21:08 · 昨天 21:08 · 10月12日 21:08 · 2025年10月12日 21:08 */
fun announcementTime(iso: String?): String {
    val instant = parseInstant(iso) ?: return ""
    val zone = ZoneId.systemDefault()
    val time = instant.atZone(zone)
    val today = java.time.LocalDate.now(zone)
    return when (time.toLocalDate()) {
        today -> "今天 ${time.format(Clock)}"
        today.minusDays(1) -> "昨天 ${time.format(Clock)}"
        else -> if (time.year == today.year) time.format(SameYear) else time.format(OtherYear)
    }
}

/** 群主 / 管理员 */
fun isGroupManager(role: String?): Boolean = role == "owner" || role == "admin"
