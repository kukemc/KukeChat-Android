package ink.kuke.chat.feature.groupplus

import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.await
import ink.kuke.chat.core.net.ClientProof
import ink.kuke.chat.core.ui.parseInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant

/*
 * 群升级 / 群文件 / 群待办 / 精华 的数据与接口（契约：KukeChat-Server/docs/group-plus.md）。
 * 只声明界面用得到的字段；服务端给的 detail.message 是可以直接展示的中文。
 */

// ------------------------------------------------------------------ 档位

object PlusTiers {
    val order = listOf("free", "pro", "pro_plus", "max")

    fun rank(key: String?): Int = order.indexOf(key ?: "free").coerceAtLeast(0)

    fun label(key: String?): String = when (key) {
        "pro" -> "Pro"
        "pro_plus" -> "Pro+"
        "max" -> "MAX"
        else -> "免费"
    }

    /** 永久档位（后台给的）的到期时间是 2999-12-31 的哨兵值：年份 >= 2999 就当永久（docs/group-plus.md 9.2） */
    private const val PERMANENT_YEAR = 2999

    /** 是不是永久档位。服务端给了 permanent 就用它；旧服务端没有这个字段时按到期年份判断。免费档恒为 false */
    fun isPermanent(tier: String?, expiresAt: String?, flag: Boolean = false): Boolean {
        if (tier.isNullOrBlank() || tier == "free") return false
        if (flag) return true
        val until = parseInstant(expiresAt) ?: return false
        return until.atZone(java.time.ZoneOffset.UTC).year >= PERMANENT_YEAR
    }

    /** 到期时间已过就当免费（到期降级没有实时事件，客户端自己判断） */
    fun effective(tier: String?, expiresAt: String?, now: Instant = Instant.now()): String {
        if (tier.isNullOrBlank() || tier == "free") return "free"
        val until = parseInstant(expiresAt) ?: return tier
        return if (until.isAfter(now)) tier else "free"
    }
}

/** 会话上的有效档位。私聊恒为 free */
val Conversation.effectivePlusTier: String
    get() = if (isGroup) PlusTiers.effective(plusTier, plusExpiresAt) else "free"

// ------------------------------------------------------------------ 数据

@Serializable
data class PlusTierSpec(
    val key: String,
    val name: String = "",
    val rank: Int = 0,
    @SerialName("member_limit") val memberLimit: Int = 200,
    @SerialName("message_limit") val messageLimit: Int = 1000,
    @SerialName("file_enabled") val fileEnabled: Boolean = false,
    /** 群文件空间；Pro 解锁了群文件但没有空间（0），只能传临时文件；未解锁为 null */
    @SerialName("file_quota_bytes") val fileQuotaBytes: Long? = null,
    /** 这一档临时文件（24 小时、不占空间）的单个上限；未解锁为 null。旧服务端没有这个字段 */
    @SerialName("file_temp_max_bytes") val fileTempMaxBytes: Long? = null,
    @SerialName("featured_limit") val featuredLimit: Int = 100,
    @SerialName("price_month") val priceMonth: Long = 0,
    @SerialName("price_year") val priceYear: Long = 0,
)

@Serializable
data class PlusPlans(
    val enabled: Boolean = true,
    @SerialName("year_months_paid") val yearMonthsPaid: Int = 10,
    @SerialName("month_days") val monthDays: Int = 30,
    @SerialName("year_days") val yearDays: Int = 365,
    @SerialName("file_max_bytes") val fileMaxBytes: Long = 104857600,
    @SerialName("file_temp_ttl_hours") val fileTempTtlHours: Int = 24,
    @SerialName("file_ttl_options_hours") val fileTtlOptionsHours: List<Int> = listOf(24, 168, 720),
    @SerialName("file_grace_days") val fileGraceDays: Int = 30,
    val tiers: List<PlusTierSpec> = emptyList(),
)

@Serializable
data class PlusLimits(
    /** 这个群现在的人数上限；null = 不限（后台给这个群单独设的，同时 member_limit_unlimited 为 true） */
    @SerialName("member_limit") val memberLimit: Int? = 200,
    @SerialName("member_limit_unlimited") val memberLimitUnlimited: Boolean = false,
    @SerialName("message_limit") val messageLimit: Int = 1000,
    @SerialName("file_enabled") val fileEnabled: Boolean = false,
    @SerialName("file_quota_bytes") val fileQuotaBytes: Long? = null,
    @SerialName("file_temp_max_bytes") val fileTempMaxBytes: Long? = null,
    @SerialName("featured_limit") val featuredLimit: Int = 100,
) {
    /** 人数不限 */
    val unlimitedMembers: Boolean get() = memberLimitUnlimited || memberLimit == null

    /** 群成员满了没有（不限的群永远不满） */
    fun membersFull(count: Int): Boolean = !unlimitedMembers && count >= (memberLimit ?: Int.MAX_VALUE)

    /** 「312 / 1000」或「312 / 不限」 */
    fun memberText(count: Int, format: (Int) -> String = { it.toString() }): String =
        "${format(count)} / ${if (unlimitedMembers) "不限" else format(memberLimit ?: 0)}"
}

@Serializable
data class PlusUsage(
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("featured_count") val featuredCount: Int = 0,
    @SerialName("file_bytes_used") val fileBytesUsed: Long = 0,
    @SerialName("file_count") val fileCount: Int = 0,
)

/** 群文件空间状态：locked 未解锁 / active 可用 / grace 宽限期（可读不可传） */
@Serializable
data class FileQuota(
    val state: String = "locked",
    val tier: String? = null,
    @SerialName("grace_until") val graceUntil: String? = null,
    @SerialName("quota_bytes") val quotaBytes: Long? = null,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("default_ttl_hours") val defaultTtlHours: Int = 24,
    @SerialName("max_file_bytes") val maxFileBytes: Long = 104857600,
    /** 剩余空间 = max(0, quota_bytes - used_bytes) */
    @SerialName("free_bytes") val freeBytes: Long = 0,
    /** 这一档临时文件的单个上限；未解锁为 null */
    @SerialName("temp_max_bytes") val tempMaxBytes: Long? = null,
    /** 档位能不能用文件夹（有效档位 Pro+ / MAX）。旧服务端没有这个字段 */
    @SerialName("folders_enabled") val foldersEnabled: Boolean = false,
    /** 服务端开了直传直下（预签名 PUT 到私有桶）：上传先走 /files/uploads，走不通再退回 multipart。旧服务端没有这个字段 */
    val direct: Boolean = false,
)

@Serializable
data class PlusInfo(
    @SerialName("conversation_id") val conversationId: Long = 0,
    val tier: String = "free",
    @SerialName("tier_name") val tierName: String = "免费",
    @SerialName("expires_at") val expiresAt: String? = null,
    /** 永久档位（后台给的）：不能再购买。旧服务端没有这个字段，用 PlusTiers.isPermanent 兜底 */
    val permanent: Boolean = false,
    val limits: PlusLimits = PlusLimits(),
    val usage: PlusUsage = PlusUsage(),
    val files: FileQuota = FileQuota(),
    @SerialName("can_purchase") val canPurchase: Boolean = false,
)

@Serializable
data class PlusQuote(
    @SerialName("conversation_id") val conversationId: Long = 0,
    val action: String = "new",
    val tier: String = "pro",
    val period: String = "month",
    val days: Int = 30,
    val price: Long = 0,
    @SerialName("current_tier") val currentTier: String = "free",
    @SerialName("current_expires_at") val currentExpiresAt: String? = null,
    @SerialName("new_expires_at") val newExpiresAt: String? = null,
    @SerialName("forfeited_seconds") val forfeitedSeconds: Long = 0,
    @SerialName("forfeited_days") val forfeitedDays: Int = 0,
    @SerialName("forfeit_notice") val forfeitNotice: String? = null,
    val balance: Long = 0,
    val enough: Boolean = true,
)

@Serializable
data class PlusRecord(
    val id: Long = 0,
    val source: String = "purchase",
    val action: String = "new",
    val tier: String? = null,
    val period: String? = null,
    val days: Int = 0,
    val amount: Long = 0,
    @SerialName("new_tier") val newTier: String? = null,
    @SerialName("new_expires_at") val newExpiresAt: String? = null,
    /** 购买人；后台赠送 / 取消为 null */
    val user: PlusUploader? = null,
    @SerialName("created_at") val createdAt: String = "",
)

class PlusRecordsPage(val items: List<PlusRecord>, val total: Int)

@Serializable
data class PlusUploader(
    val id: Long = 0,
    val nickname: String? = null,
    val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username.ifBlank { "用户 $id" }
}

@Serializable
data class GroupFile(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long = 0,
    val name: String = "file",
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
    val uploader: PlusUploader? = null,
    @SerialName("uploader_id") val uploaderId: Long? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    val permanent: Boolean = false,
    val expired: Boolean = false,
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("download_path") val downloadPath: String? = null,
    /** 24 小时临时文件（空间放不下时传的，不占配额，不能放进文件夹） */
    val temporary: Boolean = false,
    /** 所在的文件夹，根目录为 null（旧服务端没有） */
    @SerialName("folder_id") val folderId: Long? = null,
    @SerialName("folder_name") val folderName: String? = null,
)

/** 群文件夹（Pro+ / MAX，一层）：file_count / total_size 只算没过期的文件 */
@Serializable
data class GroupFileFolder(
    val id: Long,
    val name: String = "",
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("total_size") val totalSize: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
)

/**
 * 群文件列表的一页。folders 每次都是这个群的全部文件夹；folder 是现在看的文件夹（根目录 / 搜索为 null）。
 * 旧服务端没有文件夹字段：folders 为空、两个权限为 false，界面保持原样。
 */
class FilesPage(
    val items: List<GroupFile>,
    val total: Int,
    val quota: FileQuota,
    val folders: List<GroupFileFolder> = emptyList(),
    val folder: GroupFileFolder? = null,
    val search: Boolean = false,
    val canManageFolders: Boolean = false,
    val canTidyFolders: Boolean = false,
    val folderLimit: Int = 50,
)

/** 精华列表的一项（FeaturedMessageRead）：message 是快照构造的消息 */
class FeaturedItem(
    val id: Long,
    val messageId: Long?,
    val message: ink.kuke.chat.core.model.Message,
    val messageExists: Boolean,
    val recalled: Boolean,
    val snapshotMissing: Boolean,
    val createdAt: String,
    val setByName: String?,
)

class FeaturedPage(val items: List<FeaturedItem>, val total: Int, val hasMore: Boolean)

// ------------------------------------------------------------------ 错误码

/** 服务端 detail.code；不是 ApiException 返回 null */
val Throwable.apiCode: String? get() = (this as? ApiException)?.code

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** 错误 detail 里的数字字段（余额不足带 balance / total / shortfall） */
fun ApiException.detailLong(key: String): Long? = detail.obj()?.long(key)
fun ApiException.detailString(key: String): String? = detail.obj()?.str(key)

// ------------------------------------------------------------------ 接口

class GroupPlusApi(private val graph: AppGraph) {
    private val api get() = graph.api

    suspend fun plans(): PlusPlans = KcJson.decodeFromJsonElement(PlusPlans.serializer(), api.get("/group-plus/plans"))

    suspend fun info(cid: Long): PlusInfo = KcJson.decodeFromJsonElement(PlusInfo.serializer(), api.get("/conversations/$cid/plus"))

    suspend fun quote(cid: Long, tier: String, period: String): PlusQuote =
        KcJson.decodeFromJsonElement(
            PlusQuote.serializer(),
            api.post("/conversations/$cid/plus/quote", buildJsonObject { put("tier", tier); put("period", period) }),
        )

    /** 报价里的余额是库里的旧数（从没打开过商店的人是 0）；不够时先向后端核实一次再报价。失败就忽略，交给购买接口去判断。 */
    suspend fun refreshBalance() {
        runCatching { api.post("/shop/balance/refresh") }
    }

    class PurchaseResult(val plus: PlusInfo, val balance: Long?)

    suspend fun purchase(cid: Long, tier: String, period: String, confirmForfeit: Boolean, expectedPrice: Long): PurchaseResult {
        val result = api.post(
            "/conversations/$cid/plus/purchase",
            buildJsonObject {
                put("tier", tier)
                put("period", period)
                if (confirmForfeit) put("confirm_forfeit", true)
                put("expected_price", expectedPrice)
            },
        )
        val obj = result.obj()
        val plus = obj?.get("plus")?.let { runCatching { KcJson.decodeFromJsonElement(PlusInfo.serializer(), it) }.getOrNull() } ?: info(cid)
        val balance = obj?.get("balance").obj()?.long("balance")
        return PurchaseResult(plus, balance)
    }

    suspend fun records(cid: Long, limit: Int = 20, offset: Int = 0): PlusRecordsPage {
        val obj = api.get("/conversations/$cid/plus/records?limit=$limit&offset=$offset").obj()
        val items = (obj?.get("items") as? JsonArray).orEmpty().mapNotNull { runCatching { KcJson.decodeFromJsonElement(PlusRecord.serializer(), it) }.getOrNull() }
        return PlusRecordsPage(items, (obj?.long("total") ?: (offset + items.size).toLong()).toInt())
    }

    // ---- 群文件

    /** folderId 为 null 是根目录；带了 q 就是跨文件夹搜索（folderId 被服务端忽略） */
    suspend fun files(cid: Long, q: String = "", limit: Int = 30, offset: Int = 0, folderId: Long? = null): FilesPage {
        val query = buildString {
            append("limit=$limit&offset=$offset")
            if (q.isNotBlank()) append("&q=").append(URLEncoder.encode(q.trim(), "UTF-8"))
            else if (folderId != null) append("&folder_id=").append(folderId)
        }
        val obj = api.get("/conversations/$cid/files?$query").obj()
        val items = (obj?.get("items") as? JsonArray).orEmpty().mapNotNull { runCatching { KcJson.decodeFromJsonElement(GroupFile.serializer(), it) }.getOrNull() }
        val quota = obj?.get("quota")?.let { runCatching { KcJson.decodeFromJsonElement(FileQuota.serializer(), it) }.getOrNull() } ?: FileQuota()
        val folders = (obj?.get("folders") as? JsonArray).orEmpty().mapNotNull { runCatching { KcJson.decodeFromJsonElement(GroupFileFolder.serializer(), it) }.getOrNull() }
        val folder = obj?.get("folder").obj()?.let { runCatching { KcJson.decodeFromJsonElement(GroupFileFolder.serializer(), it) }.getOrNull() }
        return FilesPage(
            items = items,
            total = (obj?.long("total") ?: items.size.toLong()).toInt(),
            quota = quota,
            folders = folders,
            folder = folder,
            search = obj?.bool("search") ?: false,
            canManageFolders = obj?.bool("can_manage_folders") ?: false,
            canTidyFolders = obj?.bool("can_tidy_folders") ?: false,
            folderLimit = obj?.long("folder_limit")?.toInt() ?: 50,
        )
    }

    /**
     * 聊天里文件卡片要的最新状态（转永久 / 转限时）：根目录只列根目录的文件，放进文件夹的要再按文件夹拉。
     * 只拉有文件的文件夹，最多 20 个；单个文件夹失败就跳过。
     */
    suspend fun filesForCards(cid: Long): List<GroupFile> {
        val root = files(cid, limit = 100)
        val inFolders = root.folders.filter { it.fileCount > 0 }.take(20).flatMap { folder ->
            runCatching { files(cid, limit = 100, folderId = folder.id).items }.getOrDefault(emptyList())
        }
        return root.items + inFolders
    }

    /** 群文件配额和状态（GET /conversations/{cid}/files/usage）：聊天里发文件前用它挡掉没解锁 / 超大的 */
    suspend fun usage(cid: Long): FileQuota = KcJson.decodeFromJsonElement(FileQuota.serializer(), api.get("/conversations/$cid/files/usage"))

    suspend fun createFolder(cid: Long, name: String): GroupFileFolder =
        KcJson.decodeFromJsonElement(GroupFileFolder.serializer(), api.post("/conversations/$cid/files/folders", buildJsonObject { put("name", name) }))

    suspend fun renameFolder(cid: Long, folderId: Long, name: String): GroupFileFolder =
        KcJson.decodeFromJsonElement(GroupFileFolder.serializer(), api.patch("/conversations/$cid/files/folders/$folderId", buildJsonObject { put("name", name) }))

    /** 删文件夹，里面的文件移回根目录；返回移回去的文件数 */
    suspend fun deleteFolder(cid: Long, folderId: Long): Int =
        api.delete("/conversations/$cid/files/folders/$folderId").obj()?.long("moved_to_root")?.toInt() ?: 0

    /** 批量移动（全部成功或一个都不动）；folderId 为 null = 移回根目录。返回移动的个数 */
    suspend fun moveFiles(cid: Long, fileIds: List<Long>, folderId: Long?): Int {
        val body = buildJsonObject {
            put("file_ids", JsonArray(fileIds.map { JsonPrimitive(it) }))
            if (folderId == null) put("folder_id", JsonNull) else put("folder_id", folderId)
        }
        return api.post("/conversations/$cid/files/move", body).obj()?.long("moved")?.toInt() ?: fileIds.size
    }

    suspend fun setPermanent(cid: Long, fileId: Long, permanent: Boolean): GroupFile =
        KcJson.decodeFromJsonElement(GroupFile.serializer(), api.patch("/conversations/$cid/files/$fileId", buildJsonObject { put("permanent", permanent) }))

    suspend fun deleteFile(cid: Long, fileId: Long) {
        api.delete("/conversations/$cid/files/$fileId")
    }

    suspend fun setDefaultTtl(cid: Long, hours: Int) {
        api.patch("/conversations/$cid/settings", buildJsonObject { put("file_default_ttl_hours", hours) })
    }

    // ---- 精华（分页信息在响应头 X-Total-Count / X-Has-More）

    suspend fun featured(cid: Long, limit: Int = 30, offset: Int = 0): FeaturedPage {
        val (element, headers) = getWithHeaders("/conversations/$cid/featured-messages?limit=$limit&offset=$offset")
        val items = (element as? JsonArray).orEmpty().mapNotNull(::parseFeatured)
        val total = headers["X-Total-Count"]?.toIntOrNull() ?: (offset + items.size)
        val hasMore = headers["X-Has-More"]?.equals("true", ignoreCase = true) ?: (items.size >= limit)
        return FeaturedPage(items, total, hasMore)
    }

    suspend fun removeFeatured(cid: Long, featuredId: Long) {
        api.delete("/conversations/$cid/featured-messages/$featuredId")
    }

    // ---- 群待办

    suspend fun todo(cid: Long): GroupTodo? = GroupTodo.parse(api.get("/conversations/$cid/todo").obj()?.get("todo").obj())

    suspend fun setTodo(cid: Long, messageId: Long): GroupTodo? =
        GroupTodo.parse(api.put("/conversations/$cid/todo", buildJsonObject { put("message_id", messageId) }).obj()?.get("todo").obj())

    suspend fun clearTodo(cid: Long) {
        api.delete("/conversations/$cid/todo")
    }

    // ---- 能读响应头的 GET（Api.get 只返回 JSON）

    private suspend fun getWithHeaders(path: String): Pair<JsonElement, Headers> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(Endpoints.api + path).get()
        ClientProof.headers("GET", path).forEach { (key, value) -> builder.header(key, value) }
        graph.session.token?.let { builder.header("Authorization", "Bearer $it") }
        api.http.newCall(builder.build()).await().use { response ->
            val text = response.body?.string().orEmpty()
            val element = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { KcJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
            if (!response.isSuccessful) {
                val detail = (element as? JsonObject)?.get("detail")
                val code = detail.obj()?.str("code")
                val message = when (detail) {
                    is JsonObject -> detail.str("message")
                    is JsonPrimitive -> detail.contentOrNull
                    else -> null
                } ?: "请求失败（${response.code}）"
                throw ApiException(response.code, code, message, detail)
            }
            element to response.headers
        }
    }

    private fun parseFeatured(element: JsonElement): FeaturedItem? {
        val obj = element.obj() ?: return null
        val message = obj["message"]?.let { runCatching { KcJson.decodeFromJsonElement(ink.kuke.chat.core.model.Message.serializer(), it) }.getOrNull() } ?: return null
        return FeaturedItem(
            id = obj.long("id") ?: return null,
            messageId = obj.long("message_id"),
            message = message,
            messageExists = (obj["message_exists"] as? JsonPrimitive)?.contentOrNull != "false",
            recalled = (obj["recalled"] as? JsonPrimitive)?.contentOrNull == "true",
            snapshotMissing = (obj["snapshot_missing"] as? JsonPrimitive)?.contentOrNull == "true",
            createdAt = obj.str("created_at").orEmpty(),
            setByName = obj["set_by"].obj()?.let { it.str("display_name") ?: it.str("nickname") ?: it.str("username") },
        )
    }
}

// ------------------------------------------------------------------ 群待办

/** GroupTodo（docs/group-plus.md 第 7 节） */
class GroupTodo(
    val messageId: Long,
    val messageExists: Boolean,
    val recalled: Boolean,
    val summary: String,
    val snapshotType: String,
    val snapshotContent: String,
    val snapshotMetadata: JsonObject?,
    val senderName: String,
    val senderAvatar: String?,
    val senderId: Long?,
    val senderTitle: String?,
    val snapshotAt: String?,
    val setByName: String?,
    val setAt: String?,
) {
    companion object {
        fun parse(obj: JsonObject?): GroupTodo? {
            obj ?: return null
            val messageId = obj.long("message_id") ?: return null
            val snap = obj["snapshot"].obj()
            val sender = snap?.get("sender").obj()
            val setBy = obj["set_by"].obj()
            return GroupTodo(
                messageId = messageId,
                messageExists = obj.str("message_exists") != "false",
                recalled = obj.str("recalled") == "true",
                summary = obj.str("summary").orEmpty(),
                snapshotType = snap?.str("type") ?: "text",
                snapshotContent = snap?.str("content").orEmpty(),
                snapshotMetadata = snap?.get("metadata").obj(),
                senderName = sender?.let { it.str("display_name") ?: it.str("nickname") ?: it.str("username") } ?: "群成员",
                senderAvatar = sender?.str("avatar_url"),
                senderId = sender?.long("id"),
                senderTitle = sender?.str("title"),
                snapshotAt = snap?.str("created_at"),
                setByName = setBy?.let { it.str("display_name") ?: it.str("nickname") ?: it.str("username") },
                setAt = obj.str("set_at"),
            )
        }
    }
}
