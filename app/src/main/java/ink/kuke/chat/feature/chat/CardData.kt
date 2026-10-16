package ink.kuke.chat.feature.chat

import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.gifts.GiftItemRef
import ink.kuke.chat.feature.gifts.GiftRef
import ink.kuke.chat.feature.gifts.parseGiftItemRef
import ink.kuke.chat.feature.gifts.parseGiftRef
import ink.kuke.chat.ui.shell.BotRoute
import ink.kuke.chat.ui.shell.GroupInfoRoute
import ink.kuke.chat.ui.shell.PostRoute
import ink.kuke.chat.ui.shell.TaskRoute
import ink.kuke.chat.ui.shell.TeamupProfileRoute
import ink.kuke.chat.ui.shell.UserRoute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

// 消息里几种卡片（投票、红包、礼物、合并转发、分享名片、机器人组件）的数据读取，格式见 KukeChat-Server docs/chat-features.md、red-packets.md、gifts.md。

// ------------------------------------------------------------------ 投票

data class PollOption(val id: Long, val text: String, val votes: Int)

data class PollData(
    val id: Long,
    val title: String,
    val options: List<PollOption>,
    val multiple: Boolean,
    val anonymous: Boolean,
    val closesAt: String?,
    val closed: Boolean,
    val totalVoters: Int,
    val myOptionIds: List<Long>,
) {
    /** 到了截止时间服务端不推事件，客户端自己按 closes_at 显示为已结束 */
    val isClosed: Boolean get() = closed || (closesAt?.let { parseInstant(it)?.isBefore(Instant.now()) } == true)
}

fun pollOf(message: Message): PollData? {
    val poll = message.metadata?.o("poll") ?: return null
    val id = poll.l("id") ?: return null
    val options = poll.a("options")?.mapNotNull { element ->
        val option = element.asObj() ?: return@mapNotNull null
        PollOption(option.l("id") ?: return@mapNotNull null, option.s("text").orEmpty(), option.i("votes") ?: 0)
    }.orEmpty()
    return PollData(
        id = id,
        title = poll.s("title") ?: poll.s("question") ?: "投票",
        options = options,
        multiple = poll.b("multiple") == true,
        anonymous = poll.b("anonymous") == true,
        closesAt = poll.s("closes_at"),
        closed = poll.b("closed") == true,
        totalVoters = poll.i("total_voters") ?: 0,
        myOptionIds = poll.a("my_option_ids")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.orEmpty(),
    )
}

// ------------------------------------------------------------------ 红包

data class RedPacketMeta(val id: Long, val kind: String, val greeting: String, val count: Int)

fun redPacketMetaOf(message: Message): RedPacketMeta? {
    val packet = message.metadata?.o("red_packet") ?: return null
    return RedPacketMeta(
        id = packet.l("id") ?: return null,
        kind = packet.s("kind") ?: "normal",
        greeting = packet.s("greeting")?.takeIf { it.isNotBlank() } ?: "恭喜发财，大吉大利",
        count = packet.i("count") ?: 1,
    )
}

data class RedPacketClaim(val userId: Long?, val name: String, val avatarUrl: String?, val amount: Int, val luckiest: Boolean, val createdAt: String? = null)

/** GET /red-packets/{id} 的结果：金额类字段在「没领过且进行中」时为 null（服务端的可见性规则） */
data class RedPacketState(
    val id: Long,
    val kind: String,
    val greeting: String,
    val senderId: Long?,
    val senderName: String,
    val senderAvatar: String?,
    val total: Int?,
    val count: Int,
    val claimedCount: Int,
    val claimedAmount: Int?,
    val status: String,
    val myAmount: Int?,
    val claims: List<RedPacketClaim>?,
    val conversationId: Long? = null,
    val refundedAmount: Int? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val finishedAt: String? = null,
) {
    val active: Boolean get() = status == "active"
    val claimedByMe: Boolean get() = myAmount != null
    val lucky: Boolean get() = kind == "lucky"

    /** 状态还是 active 但已过 expires_at：服务端过期事件最多晚一分钟，本地先按过期显示 */
    fun expiredAt(now: Instant = Instant.now()): Boolean =
        status == "expired" || (status == "active" && parseInstant(expiresAt)?.let { !it.isAfter(now) } == true)

    /** 结束原因：finished / expired / revoked；进行中为 null */
    val endedStatus: String?
        get() = when {
            status != "active" -> status
            expiredAt() -> "expired"
            else -> null
        }

    val statusLabel: String
        get() = when {
            claimedByMe -> "已领取"
            else -> redPacketStatusText(endedStatus)
        }
}

/** 结束状态的短标签 */
fun redPacketStatusText(status: String?): String = when (status) {
    "finished" -> "已领完"
    "expired" -> "已过期"
    "revoked" -> "已撤回"
    "active" -> "领取中"
    else -> ""
}

/** GET /red-packets/pending 的一项：我还能领的红包（不带金额）。聊天页「N 个红包未领」用 */
data class PendingRedPacket(val id: Long, val messageId: Long, val kind: String, val expiresAt: Instant?, val createdAt: Instant?)

fun parsePendingRedPacket(obj: JsonObject): PendingRedPacket? {
    val id = obj.l("id") ?: return null
    val messageId = obj.l("message_id") ?: return null
    return PendingRedPacket(id, messageId, obj.s("kind") ?: "normal", parseInstant(obj.s("expires_at")), parseInstant(obj.s("created_at")))
}

private fun displayName(user: JsonObject?): String =
    user?.s("display_name")?.takeIf { it.isNotBlank() } ?: user?.s("nickname")?.takeIf { it.isNotBlank() } ?: user?.s("username")?.takeIf { it.isNotBlank() } ?: "用户"

fun parseRedPacket(obj: JsonObject): RedPacketState? {
    val id = obj.l("id") ?: return null
    val sender = obj.o("sender")
    return RedPacketState(
        id = id,
        kind = obj.s("kind") ?: "normal",
        greeting = obj.s("greeting")?.takeIf { it.isNotBlank() } ?: "恭喜发财，大吉大利",
        senderId = sender?.l("id"),
        senderName = displayName(sender),
        senderAvatar = sender?.s("avatar_url"),
        total = obj.i("total"),
        count = (obj.i("count") ?: 1).coerceAtLeast(1),
        claimedCount = obj.i("claimed_count") ?: 0,
        claimedAmount = obj.i("claimed_amount"),
        status = obj.s("status") ?: "active",
        myAmount = obj.o("my_claim")?.i("amount"),
        claims = obj.a("claims")?.mapNotNull { element ->
            val claim = element.asObj() ?: return@mapNotNull null
            val user = claim.o("user")
            RedPacketClaim(
                userId = user?.l("id"),
                name = displayName(user),
                avatarUrl = user?.s("avatar_url"),
                amount = claim.i("amount") ?: 0,
                luckiest = claim.b("is_luckiest") == true,
                createdAt = claim.s("created_at"),
            )
        },
        conversationId = obj.l("conversation_id"),
        refundedAmount = obj.i("refunded_amount"),
        expiresAt = obj.s("expires_at"),
        createdAt = obj.s("created_at"),
        finishedAt = obj.s("finished_at"),
    )
}

// ------------------------------------------------------------------ 礼物

/**
 * 消息上的 metadata.gift。前六项是老字段（预览、摘要还在用）；其余是卡片要的：礼物 id、类别、礼物 / 商品摘要、
 * 收礼人、送礼人（来自消息本身）和消息时间（判断是不是刚到的）。
 */
/**
 * 消息上的 metadata.gift。前六项是老字段（预览、摘要还在用）；其余是卡片要的：礼物 id、类别、礼物 / 商品摘要、
 * 收礼人、送礼人（来自消息本身）和消息时间（判断是不是刚到的）。
 */
data class GiftMeta(
    val name: String,
    val icon: String,
    val count: Int,
    val toName: String?,
    val message: String?,
    val tier: Int,
    val id: Long = 0,
    val kind: String = "gift",
    val gift: GiftRef? = null,
    val item: GiftItemRef? = null,
    val toUserId: Long? = null,
    val toAvatar: String? = null,
    val senderId: Long? = null,
    val fromName: String = "",
    val createdAt: String = "",
)

fun giftMetaOf(message: Message): GiftMeta? {
    val gift = message.metadata?.o("gift") ?: return null
    val ref = parseGiftRef(gift["gift"])
    val item = parseGiftItemRef(gift["item"])
    val kind = if (gift.s("kind") == "item" && item != null) "item" else "gift"
    val to = gift.o("to_user")
    return GiftMeta(
        name = if (kind == "item") item?.name ?: "装扮" else ref?.name ?: "礼物",
        icon = ref?.icon ?: "gift",
        count = if (kind == "item") 1 else (gift.i("count") ?: 1).coerceAtLeast(1),
        toName = to?.s("display_name"),
        message = gift.s("message")?.takeIf { it.isNotBlank() },
        tier = ref?.tier ?: 1,
        id = gift.l("id") ?: 0,
        kind = kind,
        gift = ref,
        item = if (kind == "item") item else null,
        toUserId = to?.l("id"),
        toAvatar = to?.s("avatar_url"),
        senderId = message.senderId,
        fromName = message.senderName,
        createdAt = message.createdAt,
    )
}

// ------------------------------------------------------------------ 合并转发

data class ForwardItem(val id: Long?, val senderName: String, val senderAvatar: String?, val type: String, val content: String, val createdAt: String?)

data class ForwardBundle(val title: String, val note: String?, val count: Int, val items: List<ForwardItem>)

fun forwardOf(message: Message): ForwardBundle? {
    if (message.type != "forward_bundle") return null
    // 老数据把快照放在 content 的 JSON 里
    val meta = message.metadata ?: runCatching { KcJson.parseToJsonElement(message.content) as? JsonObject }.getOrNull() ?: return ForwardBundle("聊天记录", null, 0, emptyList())
    val source = listOf("snapshots", "messages", "items", "forwarded_messages").firstNotNullOfOrNull { meta.a(it) }.orEmpty()
    val items = source.mapNotNull { element ->
        val snap = element.asObj() ?: return@mapNotNull null
        val sender = snap.o("sender")
        ForwardItem(
            id = snap.l("id") ?: snap.l("message_id"),
            senderName = snap.s("sender_name") ?: snap.s("sender_display_name") ?: sender?.s("nickname") ?: sender?.s("username") ?: "未知用户",
            senderAvatar = snap.s("sender_avatar_url") ?: sender?.s("avatar_url"),
            type = snap.s("type") ?: "text",
            content = snap.s("content") ?: snap.s("text") ?: "",
            createdAt = snap.s("created_at"),
        )
    }
    val direct = (meta.s("source_conversation_type") ?: meta.s("conversation_type")) == "direct"
    return ForwardBundle(
        title = meta.s("title") ?: meta.s("source_title") ?: if (direct) "聊天记录" else "群聊的聊天记录",
        note = meta.s("note"),
        count = (meta.i("count") ?: meta.i("message_count") ?: items.size).coerceAtLeast(items.size),
        items = items,
    )
}

/** 转发快照一行的预览 */
fun forwardItemPreview(item: ForwardItem): String = when (item.type) {
    "image" -> "[图片]"
    "voice" -> "[语音]"
    "forward_bundle" -> "[转发消息]"
    "poll" -> "[投票]"
    "red_packet" -> "[红包]"
    "gift" -> "[礼物]"
    else -> item.content.ifBlank { "[${item.type}]" }
}

// ------------------------------------------------------------------ 分享名片

/** 名片点击要去的页面：群 / 用户 / 动态 / 机器人 / 组队名片 / 任务。读不出 id 返回 null */
fun shareRouteOf(message: Message): Any? {
    val card = message.metadata?.o("share_card") ?: return null
    return when (card.s("type")) {
        // 带邀请 token 的群名片走邀请页（直接加入，不用审核）；旧名片没有 token，进群资料页
        "group" -> card.s("invite_token")?.takeIf { it.isNotBlank() }?.let { ink.kuke.chat.ui.shell.InviteRoute(it) }
            ?: (card.l("conversation_id") ?: card.l("id"))?.let(::GroupInfoRoute)
        "user" -> (card.l("user_id") ?: card.l("id"))?.let(::UserRoute)
        "post" -> (card.l("post_id") ?: card.l("id"))?.let(::PostRoute)
        "bot" -> (card.l("bot_id") ?: card.l("id"))?.let(::BotRoute)
        "teamup" -> (card.l("profile_id") ?: card.l("id"))?.let(::TeamupProfileRoute)
        "task" -> (card.s("task_id") ?: card.l("id")?.toString())?.let(::TaskRoute)
        else -> null
    }
}

// ------------------------------------------------------------------ 机器人指令

data class BotCommand(val botId: Long, val botName: String, val botAvatar: String?, val command: String, val signature: String, val description: String)

/** Bot.commands 是自由文本，一行一条「/指令 <必填> [可选] 说明」；不合约定的行忽略 */
fun parseBotCommands(botId: Long, botName: String, avatar: String?, text: String?): List<BotCommand> =
    text.orEmpty().lines().mapNotNull { raw ->
        val line = raw.trim()
        if (!line.startsWith("/")) return@mapNotNull null
        val command = line.substringBefore(' ').trim()
        if (command.length < 2) return@mapNotNull null
        var rest = line.removePrefix(command).trim()
        val params = mutableListOf<String>()
        val token = Regex("""^([<\[])([^\s<>\[\]:]+)(?::([^<>\[\]]*))?([>\]])(?=\s|$)""")
        while (true) {
            val match = token.find(rest) ?: break
            params += match.value
            rest = rest.removeRange(match.range).trim()
        }
        BotCommand(botId, botName, avatar, command, (listOf(command) + params.map { it.replace(Regex(":[^>\\]]*"), "") }).joinToString(" "), rest)
    }

// ------------------------------------------------------------------ 机器人按钮组件

data class BotButton(
    val kind: String,
    val action: String,
    val componentId: String?,
    val actionId: String?,
    val value: String?,
    val href: String?,
    val label: String,
    val elementIndex: Int?,
    val disabled: Boolean,
    val variant: String?,
)

/** 消息 metadata.component_state[componentId] 覆盖按钮的文案、禁用、配色 */
fun withComponentState(message: Message, button: BotButton): BotButton {
    val state = button.componentId?.let { message.metadata?.o("component_state")?.o(it) } ?: return button
    return button.copy(
        label = state.s("label") ?: button.label,
        disabled = state.b("disabled") == true,
        variant = state.s("variant") ?: button.variant,
    )
}

/** metadata.elements 里的 button / link 元素 → 按钮 */
fun botButtonFromAttrs(kind: String, attrs: JsonObject?, content: String, index: Int?): BotButton? {
    fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { attrs?.s(it)?.takeIf { v -> v.isNotBlank() } }
    val label = (pick("label", "text", "title") ?: content).trim().ifBlank { if (kind == "button") "按钮" else "链接" }
    val raw = (pick("action", "type", "mode") ?: if (kind == "link") "open" else "callback").lowercase().replace('-', '_')
    val action = when (raw) {
        "input", "insert", "fill", "preset", "text" -> "input"
        "open" -> "open"
        else -> "callback"
    }
    val componentId = pick("component_id", "componentId", "button_id", "buttonId", "id")
    val actionId = pick("action_id", "actionId", "id", "key")
    val value = pick("value", "input", "command")
    val href = pick("href", "url")
    if (action == "input" && value == null) return null
    if (action == "callback" && actionId == null) return null
    if (action == "open" && (href == null || !href.startsWith("http"))) return null
    return BotButton(kind, action, componentId, actionId, value, href, label.take(80), index, false, null)
}
