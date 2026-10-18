package ink.kuke.chat.feature.bots

import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.tasks.asItems
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 机器人接口（KukeChat-Server app/api/v1/bots.py）。只声明界面用得到的字段。 */
@Serializable
data class Bot(
    val id: Long,
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("owner_id") val ownerId: Long = 0,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val description: String? = null,
    val functions: String? = null,
    val commands: String? = null,
    @SerialName("is_public") val isPublic: Boolean = true,
    val status: String = "active",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val user: User? = null,
    val owner: User? = null,
    val online: Boolean = false,
    @SerialName("rating_average") val ratingAverage: Double? = null,
    @SerialName("review_count") val reviewCount: Int = 0,
    @SerialName("install_count") val installCount: Int = 0,
    @SerialName("my_review") val myReview: BotReview? = null,
) {
    val disabled: Boolean get() = status == "disabled"

    /** 机器人自己的账号（头像取色用 id）。列表接口不一定带 user，缺的时候用资料拼 */
    val accountId: Long get() = user?.id ?: userId.takeIf { it > 0 } ?: id
}

@Serializable
data class BotReviewReply(
    val id: Long,
    @SerialName("review_id") val reviewId: Long = 0,
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("parent_id") val parentId: Long? = null,
    @SerialName("parent_author") val parentAuthor: User? = null,
    val content: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val user: User? = null,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
)

@Serializable
data class BotReview(
    val id: Long,
    @SerialName("bot_id") val botId: Long = 0,
    @SerialName("user_id") val userId: Long = 0,
    val rating: Double = 0.0,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val user: User? = null,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
    val replies: List<BotReviewReply> = emptyList(),
)

@Serializable
data class BotSquareStats(
    @SerialName("total_bots") val totalBots: Int = 0,
    @SerialName("online_bots") val onlineBots: Int = 0,
    @SerialName("installed_groups") val installedGroups: Int = 0,
    @SerialName("total_installs") val totalInstalls: Int = 0,
    @SerialName("total_reviews") val totalReviews: Int = 0,
)

@Serializable
data class BotSquarePage(
    val items: List<Bot> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val stats: BotSquareStats = BotSquareStats(),
)

@Serializable
data class BotMetrics(
    @SerialName("total_sent_messages") val totalSent: Int = 0,
    @SerialName("total_received_messages") val totalReceived: Int = 0,
    @SerialName("today_sent_messages") val todaySent: Int = 0,
    @SerialName("today_received_messages") val todayReceived: Int = 0,
    @SerialName("active_installs") val activeInstalls: Int = 0,
    @SerialName("disabled_installs") val disabledInstalls: Int = 0,
    @SerialName("installed_groups") val installedGroups: Int = 0,
    @SerialName("total_members_reached") val membersReached: Int = 0,
    @SerialName("review_count") val reviewCount: Int = 0,
    @SerialName("rating_average") val ratingAverage: Double? = null,
    @SerialName("reaction_count") val reactionCount: Int = 0,
)

@Serializable
data class BotTrendPoint(val date: String = "", val sent: Int = 0, val received: Int = 0)

@Serializable
data class BotRatingBucket(val rating: Int = 0, val count: Int = 0)

@Serializable
data class BotDashInstall(
    @SerialName("conversation_id") val conversationId: Long = 0,
    val title: String = "",
    val enabled: Boolean = true,
    @SerialName("sent_messages") val sentMessages: Int = 0,
    @SerialName("received_messages") val receivedMessages: Int = 0,
    @SerialName("member_count") val memberCount: Int = 0,
)

@Serializable
data class BotDashboard(
    val bot: Bot,
    val metrics: BotMetrics = BotMetrics(),
    val trend: List<BotTrendPoint> = emptyList(),
    @SerialName("rating_distribution") val ratingDistribution: List<BotRatingBucket> = emptyList(),
    val installations: List<BotDashInstall> = emptyList(),
)

/** 创建 / 重置 Key 的返回：完整 Key 只出现这一次 */
data class BotKey(val bot: Bot?, val key: String)

internal class BotApi(private val api: Api) {
    private fun <T> list(element: JsonElement, serializer: KSerializer<T>): List<T> =
        KcJson.decodeFromJsonElement(ListSerializer(serializer), element.asItems())

    suspend fun square(query: String, offset: Int = 0, limit: Int = 30): BotSquarePage {
        val q = if (query.isBlank()) "" else "&q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return KcJson.decodeFromJsonElement(BotSquarePage.serializer(), api.get("/bots/square?limit=$limit&offset=$offset$q"))
    }

    suspend fun mine(): List<Bot> = list(api.get("/bots/mine"), Bot.serializer())
    suspend fun bot(id: Long): Bot = KcJson.decodeFromJsonElement(Bot.serializer(), api.get("/bots/$id"))
    /** 按机器人的用户账号（users.is_bot）取机器人：聊天里点机器人头像 / 名字时用它换 bot_id */
    suspend fun botByUser(userId: Long): Bot = KcJson.decodeFromJsonElement(Bot.serializer(), api.get("/bots/by-user/$userId"))
    suspend fun dashboard(id: Long): BotDashboard = KcJson.decodeFromJsonElement(BotDashboard.serializer(), api.get("/bots/$id/dashboard"))
    suspend fun reviews(id: Long): List<BotReview> = list(api.get("/bots/$id/reviews"), BotReview.serializer())

    suspend fun saveReview(id: Long, rating: Int, content: String) {
        api.put("/bots/$id/reviews/me", buildJsonObject {
            put("rating", rating)
            if (content.isNotBlank()) put("content", content.trim())
        })
    }

    suspend fun replyReview(botId: Long, reviewId: Long, content: String, parentId: Long?) {
        api.post("/bots/$botId/reviews/$reviewId/replies", buildJsonObject {
            put("content", content.trim())
            if (parentId != null) put("parent_id", parentId)
        })
    }

    suspend fun likeReview(botId: Long, reviewId: Long, replyId: Long? = null) {
        api.post(if (replyId != null) "/bots/$botId/reviews/$reviewId/replies/$replyId/like" else "/bots/$botId/reviews/$reviewId/like")
    }

    suspend fun install(botId: Long, conversationId: Long) {
        api.post("/bots/$botId/install", buildJsonObject {
            put("conversation_id", conversationId)
            put("receive_messages", true)
            put("receive_member_events", true)
        })
    }

    suspend fun create(body: JsonObject): BotKey {
        val result = api.post("/bots", body) as JsonObject
        val bot = result["bot"]?.let { runCatching { KcJson.decodeFromJsonElement(Bot.serializer(), it) }.getOrNull() }
        return BotKey(bot, result["key"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    suspend fun update(id: Long, body: JsonObject): Bot = KcJson.decodeFromJsonElement(Bot.serializer(), api.patch("/bots/$id", body))

    suspend fun rotateKey(id: Long): String =
        ((api.post("/bots/$id/rotate-key") as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull.orEmpty()

    suspend fun delete(id: Long) { api.delete("/bots/$id") }

    /** 和机器人开一个临时私聊，返回会话编号 */
    suspend fun directChat(botUserId: Long): Long {
        val result = api.post("/conversations/direct", buildJsonObject {
            put("user_id", botUserId)
            put("temporary", true)
        }) as JsonObject
        return result["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: error("没拿到会话")
    }
}

/** 一条指令：/指令 <必填> [可选] 说明（约定见电脑版 utils/botCommands.ts，一行一条） */
internal data class BotCommandLine(val command: String, val params: List<String>, val description: String)

private val ParamToken = Regex("""^([<\[])([^\s<>\[\]:]+)(?::([^<>\[\]]*))?([>\]])(?=\s|$)""")

internal fun parseBotCommands(text: String?): List<BotCommandLine> {
    val result = mutableListOf<BotCommandLine>()
    for (raw in (text ?: "").lines()) {
        val line = raw.trim()
        if (!line.startsWith("/")) {
            // 说明的续行并回上一条
            if (line.isNotEmpty() && result.isNotEmpty()) {
                val last = result.removeAt(result.lastIndex)
                result += last.copy(description = (last.description + " " + line).trim())
            }
            continue
        }
        val command = line.takeWhile { !it.isWhitespace() }
        var rest = line.removePrefix(command).trim()
        val params = mutableListOf<String>()
        while (true) {
            val match = ParamToken.find(rest) ?: break
            if ((match.groupValues[1] == "<") != (match.groupValues[4] == ">")) break
            params += if (match.groupValues[1] == "<") "<${match.groupValues[2]}>" else "[${match.groupValues[2]}]"
            rest = rest.substring(match.value.length).trim()
        }
        result += BotCommandLine(command, params, rest)
    }
    return result
}

/** 评分：半星写一位小数，整数不带小数 */
internal fun ratingText(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

internal fun countText(value: Int): String = when {
    value >= 100000 -> "${value / 10000}万"
    value >= 10000 -> "%.1f万".format(value / 10000.0)
    value >= 1000 -> "%,d".format(value)
    else -> value.toString()
}

/** 只留年月日；解析不出来返回空串 */
internal fun dateText(iso: String?): String {
    val instant = ink.kuke.chat.core.ui.parseInstant(iso) ?: return ""
    return instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString().replace('-', '/')
}
