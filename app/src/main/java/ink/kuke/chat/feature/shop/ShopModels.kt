package ink.kuke.chat.feature.shop

import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.ui.parseInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/*
 * 商店接口的数据（契约：KukeChat-Server/docs/shop.md）。商品的 style 原样留着（JsonElement），
 * 画之前由 BubbleSkin.kt 的 parse 校验。
 */

@Serializable
data class ShopCategory(val key: String = "", val label: String = "", val slot: String = "")

@Serializable
data class ShopItem(
    val id: Long,
    val sku: String = "",
    val category: String = "",
    val name: String = "",
    val description: String = "",
    val price: Double = 0.0,
    @SerialName("unlock_level") val unlockLevel: Int? = null,
    val style: JsonElement? = null,
    @SerialName("preview_text") val previewText: String = "",
    val tag: String? = null,
    @SerialName("available_until") val availableUntil: String? = null,
    @SerialName("duration_days") val durationDays: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    val owned: Boolean = false,
    val equipped: Boolean = false,
    val obtainable: String? = null,
) {
    /** 已经下架：以 is_active 为准，早期后端没有这个字段时看限时销售的下架时间是否已过 */
    val delisted: Boolean
        get() = isActive?.let { !it } ?: (parseInstant(availableUntil)?.let { !it.isAfter(Instant.now()) } == true)

    val kind: String get() = obtainable ?: if (owned) "owned" else "locked"
    val durationDaysOrNull: Int? get() = durationDays?.takeIf { it > 0 }
}

@Serializable
data class ShopBalance(
    val bound: Boolean = false,
    val donated: Double = 0.0,
    val spent: Double = 0.0,
    val balance: Double = 0.0,
    @SerialName("verified_at") val verifiedAt: String? = null,
    val stale: Boolean = false,
    @SerialName("red_packet_sent") val redPacketSent: Double? = null,
    @SerialName("red_packet_received") val redPacketReceived: Double? = null,
    @SerialName("red_packet_refunded") val redPacketRefunded: Double? = null,
    @SerialName("gift_spent") val giftSpent: Double? = null,
    @SerialName("group_plus_spent") val groupPlusSpent: Double? = null,
    /** 后台赠送的币（赠送 − 扣除，可能是负数；已经算在 balance 里，docs/shop.md 5.2）。旧服务端没有 */
    val granted: Double? = null,
)

/** GET /shop */
@Serializable
data class ShopOverview(
    val categories: List<ShopCategory> = emptyList(),
    val items: List<ShopItem> = emptyList(),
    val balance: ShopBalance = ShopBalance(),
    val equipped: Map<String, String?> = emptyMap(),
    @SerialName("donate_url") val donateUrl: String? = null,
    val level: Int = 0,
)

/** POST /shop/items/{id}/purchase */
@Serializable
data class PurchaseResult(
    val item: ShopItem? = null,
    val balance: ShopBalance? = null,
    val equipped: Map<String, String?>? = null,
)

@Serializable
data class PurchaseItemRef(val sku: String = "", val name: String = "", val category: String = "")

@Serializable
data class PurchaseRecord(
    val id: Long,
    val item: PurchaseItemRef = PurchaseItemRef(),
    val price: Double = 0.0,
    val source: String = "purchase",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("revoked_at") val revokedAt: String? = null,
    val refunded: Boolean = false,
    @SerialName("duration_days") val durationDays: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("from_user") val fromUser: User? = null,
)

@Serializable
data class PurchasePage(val items: List<PurchaseRecord> = emptyList(), val total: Int = 0)

/** 一件商品现在能做什么（同客户端 itemAction） */
enum class ItemAction { Equipped, Owned, Buy, Claim, LevelLocked, NotForSale, Delisted }

fun ShopItem.action(): ItemAction = when {
    owned || kind == "owned" || kind == "renew" -> if (equipped) ItemAction.Equipped else ItemAction.Owned
    delisted -> ItemAction.Delisted
    kind == "level" -> ItemAction.Claim
    kind == "buy" -> ItemAction.Buy
    unlockLevel != null -> ItemAction.LevelLocked
    else -> ItemAction.NotForSale
}

/** 已经拥有的限时商品现在能不能续期 */
fun ShopItem.canRenew(): Boolean = kind == "renew" && !delisted && durationDaysOrNull != null

fun formatCoins(value: Double): String = "%,d".format(Math.round(value))

/** 「30 天」；永久返回 null */
fun durationLabel(days: Int?): String? = days?.takeIf { it > 0 }?.let { "$it 天" }

/** 到期标签：「还剩 5 天」「今天到期」「已到期」，没有到期时间返回 null；soon = 不超过 3 天 */
fun expiryLabel(expiresAt: String?, now: Long): Pair<String, Boolean>? {
    val at = parseInstant(expiresAt)?.toEpochMilli() ?: return null
    // 到期时间是服务端的钟算的，本机钟慢几秒时刚买的 30 天会被往上取整成 31 天；让出一分钟的误差
    val ref = now + 60_000
    val day = 86_400_000L
    if (at <= ref) return "已到期" to true
    val left = at - ref
    val days = Math.ceil(left / day.toDouble()).toInt()
    return (if (left <= day) "今天到期" else "还剩 $days 天") to (days <= 3)
}
