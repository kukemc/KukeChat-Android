package ink.kuke.chat.feature.gifts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.shop.ShopBalance
import ink.kuke.chat.feature.shop.ShopOverview
import ink.kuke.chat.feature.shop.formatCoins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * 礼物的数据层（契约：KukeChat-Server/docs/gifts.md，对照客户端 api/gifts.ts、components/gifts/data.ts）。
 * 响应形状松散：缺字段补默认值、形状不对的条目丢掉。金额、心意值一律是非负整数。
 */

/** 默认的「心意值」叫法；界面上以 /gifts/catalog 的 charm_label 为准 */
const val DefaultCharmLabel = "心意值"

/** 目录里的礼物 / 记录、消息里带的礼物摘要 */
data class GiftRef(val key: String, val name: String, val icon: String, val price: Int, val tier: Int)

/** 记录、消息里带的商品摘要。style 不可信，画之前过 SkinPaint 的校验 */
data class GiftItemRef(val sku: String, val name: String, val category: String, val style: JsonElement?, val durationDays: Int?)

/** 记录里的人（UserPublic 的一小部分） */
data class GiftUser(val id: Long, val name: String, val avatarUrl: String?, val charm: Int?, val birthday: Boolean)

data class GiftLimits(val messageMax: Int = 60, val countMax: Int = 99, val dailySendLimit: Int = 200)

data class GiftCatalog(val enabled: Boolean, val charmLabel: String, val gifts: List<GiftRef>, val limits: GiftLimits, val sentToday: Int)

data class GiftRecord(
    val id: Long,
    val kind: String,
    val gift: GiftRef?,
    val item: GiftItemRef?,
    val count: Int,
    val amount: Int,
    val fromUser: GiftUser?,
    val toUser: GiftUser?,
    val conversationId: Long?,
    val messageId: Long?,
    val message: String?,
    val onBirthday: Boolean,
    val createdAt: String,
)

data class GiftWallEntry(val kind: String, val gift: GiftRef?, val item: GiftItemRef?, val count: Int, val lastAt: String) {
    val key: String get() = if (kind == "item") "item:${item?.sku.orEmpty()}" else "gift:${gift?.key.orEmpty()}"
    val label: String get() = item?.name ?: gift?.name ?: "礼物"
}

data class GiftWall(val charm: Int, val totalCount: Int, val summary: List<GiftWallEntry>)

data class GiftRecordPage(val items: List<GiftRecord>, val total: Int, val sum: Int)

/** POST /gifts 的回包：落了聊天消息的话 message 是那条消息的原始 JSON */
data class GiftSendResult(val record: GiftRecord?, val message: JsonElement?, val balance: ShopBalance?)

// ------------------------------------------------------------------ 解析

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun tierOf(value: Double?): Int = when (value?.toInt()) { 2 -> 2; 3 -> 3; else -> 1 }

fun parseGiftRef(element: JsonElement?): GiftRef? {
    val o = element.obj() ?: return null
    val key = o.str("key")?.takeIf { it.isNotEmpty() } ?: return null
    return GiftRef(key, o.str("name")?.trim()?.ifBlank { null } ?: "礼物", o.str("icon").orEmpty(), (o.num("price") ?: 0.0).coerceAtLeast(0.0).toInt(), tierOf(o.num("tier")))
}

fun parseGiftItemRef(element: JsonElement?): GiftItemRef? {
    val o = element.obj() ?: return null
    val sku = o.str("sku")?.takeIf { it.isNotEmpty() } ?: return null
    val days = o.num("duration_days")?.toInt()?.takeIf { it > 0 }
    return GiftItemRef(sku, o.str("name")?.trim()?.ifBlank { null } ?: "装扮", o.str("category").orEmpty(), o["style"], days)
}

fun parseGiftUser(element: JsonElement?): GiftUser? {
    val o = element.obj() ?: return null
    val id = o.long("id") ?: return null
    val name = o.str("nickname")?.takeIf { it.isNotBlank() } ?: o.str("display_name")?.takeIf { it.isNotBlank() } ?: o.str("username")?.takeIf { it.isNotBlank() } ?: "用户 $id"
    return GiftUser(id, name, o.str("avatar_url"), o.num("charm")?.toInt()?.coerceAtLeast(0), o.bool("is_birthday_today") == true)
}

fun parseGiftRecord(element: JsonElement?): GiftRecord? {
    val o = element.obj() ?: return null
    val id = o.long("id") ?: return null
    val kind = if (o.str("kind") == "item") "item" else "gift"
    val gift = parseGiftRef(o["gift"])
    val item = parseGiftItemRef(o["item"])
    if ((kind == "gift" && gift == null) || (kind == "item" && item == null)) return null
    return GiftRecord(
        id = id,
        kind = kind,
        gift = if (kind == "gift") gift else null,
        item = if (kind == "item") item else null,
        count = if (kind == "item") 1 else (o.num("count")?.toInt() ?: 1).coerceAtLeast(1),
        amount = (o.num("amount") ?: 0.0).coerceAtLeast(0.0).toInt(),
        fromUser = parseGiftUser(o["from_user"]),
        toUser = parseGiftUser(o["to_user"]),
        conversationId = o.long("conversation_id"),
        messageId = o.long("message_id"),
        message = o.str("message")?.trim()?.ifBlank { null },
        onBirthday = o.bool("on_birthday") == true,
        createdAt = o.str("created_at").orEmpty(),
    )
}

private fun parseCatalog(element: JsonElement): GiftCatalog {
    val o = element.obj() ?: JsonObject(emptyMap())
    val limits = o["limits"].obj()
    val gifts = o.arr("gifts").orEmpty().mapNotNull { raw ->
        val g = raw.obj() ?: return@mapNotNull null
        if (g.bool("is_active") == false) return@mapNotNull null
        val ref = parseGiftRef(g) ?: return@mapNotNull null
        if (ref.price <= 0) return@mapNotNull null
        ref to (g.num("sort") ?: 0.0)
    }.sortedBy { it.second }.map { it.first }
    fun positive(key: String, fallback: Int) = limits?.num(key)?.toInt()?.takeIf { it > 0 } ?: fallback
    return GiftCatalog(
        enabled = o.bool("enabled") == true,
        charmLabel = o.str("charm_label")?.trim()?.ifBlank { null } ?: DefaultCharmLabel,
        gifts = gifts,
        limits = GiftLimits(positive("message_max", 60), positive("count_max", 99), positive("daily_send_limit", 200)),
        sentToday = o.num("sent_today")?.toInt() ?: 0,
    )
}

private fun parseRecordPage(element: JsonElement): GiftRecordPage {
    val o = element.obj() ?: JsonObject(emptyMap())
    val items = o.arr("items").orEmpty().mapNotNull(::parseGiftRecord)
    return GiftRecordPage(items, o.num("total")?.toInt() ?: items.size, (o.num("sum") ?: 0.0).coerceAtLeast(0.0).toInt())
}

private fun parseWallEntry(element: JsonElement): GiftWallEntry? {
    val o = element.obj() ?: return null
    val kind = if (o.str("kind") == "item") "item" else "gift"
    val gift = parseGiftRef(o["gift"])
    val item = parseGiftItemRef(o["item"])
    if ((kind == "gift" && gift == null) || (kind == "item" && item == null)) return null
    return GiftWallEntry(kind, if (kind == "gift") gift else null, if (kind == "item") item else null, (o.num("count")?.toInt() ?: 1).coerceAtLeast(1), o.str("last_at").orEmpty())
}

// ------------------------------------------------------------------ 请求

class GiftsApi(private val api: Api) {
    /** 旧后端没有礼物接口（404）：返回 null，表示「没有礼物功能」 */
    suspend fun catalog(): GiftCatalog? = try {
        parseCatalog(api.get("/gifts/catalog"))
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    suspend fun send(toUserId: Long, conversationId: Long?, kind: String, giftKey: String?, count: Int, itemSku: String?, message: String): GiftSendResult {
        val body = buildJsonObject {
            put("to_user_id", toUserId)
            if (conversationId != null) put("conversation_id", conversationId) else put("conversation_id", kotlinx.serialization.json.JsonNull)
            put("kind", kind)
            if (kind == "gift") put("gift_key", giftKey) else put("item_sku", itemSku)
            put("count", if (kind == "item") 1 else count)
            put("message", message.trim())
        }
        val o = api.post("/gifts", body).obj() ?: JsonObject(emptyMap())
        val msg = o["message"].obj()?.takeIf { it.long("id") != null && it.long("conversation_id") != null }
        val balance = o["balance"].obj()?.let { runCatching { KcJson.decodeFromJsonElement(ShopBalance.serializer(), it) }.getOrNull() }
        return GiftSendResult(parseGiftRecord(o["record"]), msg, balance)
    }

    suspend fun wall(userId: Long): GiftWall {
        val o = api.get("/users/$userId/gifts").obj() ?: JsonObject(emptyMap())
        val summary = o.arr("summary").orEmpty().mapNotNull(::parseWallEntry)
        return GiftWall((o.num("charm") ?: 0.0).coerceAtLeast(0.0).toInt(), o.num("total_count")?.toInt() ?: summary.sumOf { it.count }, summary)
    }

    suspend fun wallRecords(userId: Long, entry: GiftWallEntry, limit: Int, offset: Int): GiftRecordPage {
        val filter = if (entry.kind == "item") "item_sku=${enc(entry.item?.sku.orEmpty())}" else "gift_key=${enc(entry.gift?.key.orEmpty())}"
        return parseRecordPage(api.get("/users/$userId/gifts/records?limit=$limit&offset=$offset&$filter"))
    }

    /** direction：received / sent */
    suspend fun mine(direction: String, limit: Int, offset: Int): GiftRecordPage =
        parseRecordPage(api.get("/gifts/mine?direction=$direction&limit=$limit&offset=$offset"))

    suspend fun shop(): ShopOverview = KcJson.decodeFromJsonElement(ShopOverview.serializer(), api.get("/shop"))

    suspend fun refreshBalance(): ShopBalance = KcJson.decodeFromJsonElement(ShopBalance.serializer(), api.post("/shop/balance/refresh"))

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}

// ------------------------------------------------------------------ 全局状态

/**
 * 礼物相关的进程内状态：目录（开关、心意值叫法、上限）、各人最新的心意值、刚到的礼物、列表要不要重拉。
 * 心意值以送礼回包 / gift.received 事件里收礼人的值为准，资料页拿它覆盖接口里的旧值。
 */
object Gifts {
    private val catalogState = MutableStateFlow<GiftCatalog?>(null)
    val catalog: StateFlow<GiftCatalog?> = catalogState.asStateFlow()
    private var catalogAt = 0L

    private val charmState = MutableStateFlow<Map<Long, Int>>(emptyMap())
    /** 用户 id → 最新的心意值 */
    val charm: StateFlow<Map<Long, Int>> = charmState.asStateFlow()

    private val tickState = MutableStateFlow(0)
    /** 有新的送礼记录落定：礼物墙、礼物记录看到它变就静默重拉 */
    val tick: StateFlow<Int> = tickState.asStateFlow()

    /** 当前登录的人（礼物卡片判断「你」用；由 GiftToastHost 跟着会话更新） */
    var meId by mutableStateOf<Long?>(null)

    /** 刚到的礼物：礼物 id → 到达时刻。卡片挂上来时还新鲜就播一次小动画 */
    private val fresh = HashMap<Long, Long>()

    /** 服务端说过「对方已永久拥有」的：收礼人 id → sku */
    private val ownedByRecipient = HashMap<Long, MutableSet<String>>()

    /** 目录：一分钟内用缓存；拿不到（旧后端、网络错）返回 null，不抛 */
    suspend fun ensureCatalog(graph: AppGraph, force: Boolean = false): GiftCatalog? {
        val now = System.currentTimeMillis()
        catalogState.value?.let { if (!force && now - catalogAt < 60_000) return it }
        val loaded = runCatching { GiftsApi(graph.api).catalog() }.getOrElse { return catalogState.value }
        catalogAt = now
        catalogState.value = loaded
        return loaded
    }

    /** 一条记录落定：收礼人的心意值换成最新的，礼物墙和记录重拉 */
    fun absorb(record: GiftRecord?) {
        val to = record?.toUser
        if (to?.charm != null) charmState.update { it + (to.id to to.charm) }
        tickState.update { it + 1 }
    }

    fun markFresh(id: Long) {
        val now = System.currentTimeMillis()
        fresh.entries.removeAll { now - it.value > 20_000 }
        fresh[id] = now
    }

    /** 这件礼物是不是刚到（4 秒内） */
    fun isFresh(id: Long): Boolean = fresh[id]?.let { System.currentTimeMillis() - it < 4_000 } == true

    fun noteOwned(userId: Long, sku: String) { ownedByRecipient.getOrPut(userId) { mutableSetOf() } += sku }
    fun knownOwned(userId: Long, sku: String): Boolean = ownedByRecipient[userId]?.contains(sku) == true

    /** 退出登录 / 换号：清掉跟人有关的 */
    fun reset() {
        catalogState.value = null
        catalogAt = 0
        charmState.value = emptyMap()
        fresh.clear()
        ownedByRecipient.clear()
    }
}

// ------------------------------------------------------------------ 文案

/** 送的是什么：「蛋糕 ×3」「头像框「天使」」 */
fun giftThingLabel(kind: String, gift: GiftRef?, item: GiftItemRef?, count: Int): String {
    if (kind == "item" && item != null) return "${categoryLabel(item.category)}「${item.name}」"
    val name = gift?.name ?: "礼物"
    return if (count > 1) "$name ×$count" else name
}

fun categoryLabel(category: String): String = when (category) {
    "frame" -> "头像框"
    "bubble" -> "气泡"
    else -> "装扮"
}

/** 限时 / 永久 */
fun termLabel(durationDays: Int?): String = durationDays?.let { "限时 $it 天" } ?: "永久"

/** 「心意值」→「心意榜」的那种改名同理，这里只要名字本身 */
fun coins(value: Int): String = formatCoins(value.toDouble())

/** 送礼失败该怎么说（对照客户端 describeSendGiftError） */
fun describeSendGiftError(error: Throwable, targetName: String): Pair<String?, String> {
    val e = error as? ApiException ?: return null to error.userMessage()
    val detail = e.detail as? JsonObject
    val text = when (e.code) {
        "disabled" -> "礼物功能现在关着，暂时送不了。"
        "not_member" -> "你或者对方已经不在这个会话里了，在这里送不了。"
        "muted" -> "你正在禁言中，不能送礼物。"
        "ccw_not_bound" -> "还没有绑定 CCW 账号，绑定后才能送礼物。"
        "blocked" -> "$targetName 把你拉黑了，送不了礼物。"
        "invalid_target" -> "不能送给这个人：不能送给自己，或者对方的账号已经不存在。"
        "invalid_gift" -> "这个礼物已经下架了，换一个吧。"
        "invalid_count" -> "数量不对：要是正整数，并且在上限以内。"
        "not_giftable" -> "这件商品现在不能送：已经下架、过了销售期，或者本来就不出售。"
        "message_rejected" -> "留言没有通过审核，改一改再送。"
        "recipient_owns" -> "$targetName 已经永久拥有这件装扮了，换一件送吧。"
        "insufficient_balance" -> detail?.num("shortfall")?.takeIf { it > 0 }?.let { "金币不够，还差 ${formatCoins(it)} 个。" } ?: "金币不够。"
        "daily_limit" -> "今天送礼物的次数用完了，明天再来。"
        "verification_unavailable" -> "现在连不上 CCW，没法核实你的余额，稍后再试。这次没有扣币。"
        else -> if (e.status >= 500) "服务器开小差了，稍后再试。这次没有扣币。" else e.message.ifBlank { "礼物没有送出去，稍后再试。" }
    }
    return e.code to text
}

/** 服务端在余额不够时带回刚核实过的余额 */
fun ApiException.verifiedBalance(): Double? = (detail as? JsonObject)?.num("balance")
