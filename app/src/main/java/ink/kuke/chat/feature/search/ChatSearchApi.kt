package ink.kuke.chat.feature.search

import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/*
 * 聊天记录搜索接口（docs/group-plus.md 第 8 节）。只搜当前用户自己的会话，后台没有对应入口。
 */

/** 搜索结果里的会话摘要 */
class SearchConversation(
    val id: Long,
    val isGroup: Boolean,
    val displayTitle: String,
    val avatarUrl: String?,
    val plusTier: String,
    val directUser: User?,
    /** 「我的设备」会话：名字按本端起（「我的电脑」），头像本地画 */
    val isSelf: Boolean = false,
)

/** 一条命中：message + 片段 + 片段里的高亮区间（UTF-16 下标，和 Kotlin String 一致） */
class SearchHit(
    val message: Message,
    val conversation: SearchConversation?,
    val category: String,
    val snippet: String,
    val snippetHighlights: List<IntRange>,
)

class SearchPage(val hits: List<SearchHit>, val total: Int, val totalCapped: Boolean, val hasMore: Boolean, val nextBeforeId: Long?)

class ConversationHits(val conversation: SearchConversation, val count: Int, val latestMessageId: Long?, val latestAt: String?)

class SearchSummary(val total: Int, val items: List<ConversationHits>)

/** 搜索条件（页面上的搜索框 / 页签 / 筛选） */
data class SearchFilter(
    val query: String = "",
    val category: String = "all",
    val senderId: Long? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
) {
    /** 接口要求 q / conversation_id / sender_id / category / 日期 至少一项，否则 422 query_required */
    val isEmpty: Boolean get() = query.isBlank() && category == "all" && senderId == null && dateFrom == null && dateTo == null
}

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

private fun parseConversation(obj: JsonObject?): SearchConversation? {
    obj ?: return null
    val id = obj.long("id") ?: return null
    val direct = obj["direct_user"].obj()?.let { runCatching { KcJson.decodeFromJsonElement(User.serializer(), it) }.getOrNull() }
    val isGroup = obj.str("type") == "group"
    // 「我的设备」：服务端给的 display_title 是中性的「我的设备」，客户端按 docs/self-chat.md 1.3 换名字
    if (obj.str("type") == ink.kuke.chat.core.model.SELF_TYPE) {
        return SearchConversation(id, false, ink.kuke.chat.core.model.SELF_CHAT_NAME, null, "free", null, isSelf = true)
    }
    val title = obj.str("display_title")?.takeIf { it.isNotBlank() } ?: obj.str("title")?.takeIf { it.isNotBlank() } ?: direct?.displayName ?: "会话 $id"
    return SearchConversation(id, isGroup, title, obj.str("avatar_url") ?: direct?.avatarUrl, obj.str("plus_tier") ?: "free", direct)
}

private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

class ChatSearchApi(private val graph: AppGraph) {
    /** GET /search/messages；conversationId = 0 表示不限定会话 */
    suspend fun search(filter: SearchFilter, conversationId: Long, beforeId: Long? = null, limit: Int = 20): SearchPage {
        val query = buildString {
            append("limit=$limit")
            if (filter.query.isNotBlank()) append("&q=").append(enc(filter.query.trim()))
            if (conversationId > 0) append("&conversation_id=$conversationId")
            filter.senderId?.let { append("&sender_id=$it") }
            if (filter.category != "all") append("&category=").append(filter.category)
            filter.dateFrom?.let { append("&date_from=").append(enc(it)) }
            filter.dateTo?.let { append("&date_to=").append(enc(it)) }
            beforeId?.let { append("&before_id=$it") }
        }
        val obj = graph.api.get("/search/messages?$query").obj()
        val hits = (obj?.get("items") as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element.obj() ?: return@mapNotNull null
            val message = item["message"]?.let { runCatching { KcJson.decodeFromJsonElement(Message.serializer(), it) }.getOrNull() } ?: return@mapNotNull null
            val ranges = (item["snippet_highlights"] as? JsonArray).orEmpty().mapNotNull { pair ->
                val array = pair as? JsonArray ?: return@mapNotNull null
                val start = (array.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val end = (array.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                if (end > start) start until end else null
            }
            SearchHit(
                message = message,
                conversation = parseConversation(item["conversation"].obj()),
                category = item.str("category") ?: "text",
                snippet = item.str("snippet") ?: message.content,
                snippetHighlights = ranges,
            )
        }
        return SearchPage(
            hits = hits,
            total = (obj?.get("total") as? JsonPrimitive)?.intOrNull ?: hits.size,
            totalCapped = (obj?.get("total_capped") as? JsonPrimitive)?.booleanOrNull == true,
            hasMore = (obj?.get("has_more") as? JsonPrimitive)?.booleanOrNull ?: false,
            nextBeforeId = obj?.long("next_before_id") ?: hits.lastOrNull()?.message?.id,
        )
    }

    /** GET /search/messages/summary：按会话聚合的命中数，新的在前 */
    suspend fun summary(query: String, limit: Int = 50): SearchSummary {
        val obj = graph.api.get("/search/messages/summary?q=${enc(query.trim())}&limit=$limit").obj()
        val items = (obj?.get("items") as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element.obj() ?: return@mapNotNull null
            val conversation = parseConversation(item["conversation"].obj()) ?: return@mapNotNull null
            ConversationHits(conversation, (item["count"] as? JsonPrimitive)?.intOrNull ?: 0, item.long("latest_message_id"), item.str("latest_at"))
        }
        return SearchSummary((obj?.get("total") as? JsonPrimitive)?.intOrNull ?: items.sumOf { it.count }, items)
    }
}
