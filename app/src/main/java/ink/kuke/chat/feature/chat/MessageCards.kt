package ink.kuke.chat.feature.chat

import androidx.compose.animation.core.Animatable
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.components.pressScale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.gifts.GiftColors
import ink.kuke.chat.feature.gifts.GiftThing
import ink.kuke.chat.feature.gifts.Gifts
import ink.kuke.chat.feature.gifts.Wearer
import ink.kuke.chat.feature.gifts.categoryLabel
import ink.kuke.chat.feature.gifts.rememberReducedMotion
import ink.kuke.chat.feature.gifts.termLabel
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.launch
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import java.time.Duration
import java.time.Instant

/** 投票卡（设计稿 P03）：标题行 · 问题 · 选项条（票数条带生长动画）· 底部人数与操作 */
@Composable
internal fun PollCard(message: Message, poll: PollData, callbacks: MessageCallbacks) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val closed = poll.isClosed
    val maxVotes = poll.options.maxOfOrNull { it.votes } ?: 0
    Column(
        Modifier
            .widthIn(min = 240.dp, max = 288.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .background(colors.panel, RoundedCornerShape(12.dp))
            .border(1.dp, colors.accent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KcIcon(KcIcons.poll, tint = colors.accent, size = 16.dp)
            Text(
                "投票 · ${if (poll.multiple) "多选" else "单选"}${if (poll.anonymous) " · 匿名" else ""}",
                style = type.caption,
                color = colors.accent,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
            Text(if (closed) "已结束" else remainingLabel(poll.closesAt), style = type.caption, color = colors.muted)
        }
        Text(poll.title, style = type.rowTitle, color = colors.text)
        poll.options.forEach { option ->
            val mine = option.id in poll.myOptionIds
            val share = if (poll.totalVoters > 0) option.votes.toFloat() / poll.totalVoters else 0f
            val grown by animateFloatAsState(share, tween(520), label = "pollBar")
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(shape)
                    .background(colors.panelMuted)
                    .border(1.dp, if (mine) colors.accent else Color.Transparent, shape)
                    .clickable(enabled = !closed) {
                        val next = when {
                            poll.multiple -> if (mine) poll.myOptionIds - option.id else poll.myOptionIds + option.id
                            mine -> emptyList()
                            else -> listOf(option.id)
                        }
                        callbacks.onVote(message, poll, next)
                    },
            ) {
                Box(Modifier.fillMaxWidth(grown.coerceIn(0f, 1f)).height(40.dp).background(colors.accent.copy(alpha = if (mine) 0.28f else 0.16f)))
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (mine) KcIcon(KcIcons.check, tint = colors.accent, size = 16.dp, modifier = Modifier.padding(end = 6.dp))
                    Text(option.text, style = type.secondary, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        "${(grown * 100).toInt()}%" + if (option.votes == maxVotes && maxVotes > 0 && closed) " 👑" else "",
                        style = type.caption,
                        color = colors.muted,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${poll.totalVoters} 人已投" + if (!poll.anonymous && poll.totalVoters > 0) " · 查看投票人" else "",
                style = type.caption,
                color = if (!poll.anonymous && poll.totalVoters > 0) colors.accent else colors.muted,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !poll.anonymous && poll.totalVoters > 0) { callbacks.onPollVoters(message, poll) },
            )
            if (!closed && callbacks.canClosePoll(message)) {
                Text("结束投票", style = type.caption, color = colors.danger, modifier = Modifier.clickable { callbacks.onClosePoll(message, poll) }.padding(4.dp))
            }
        }
    }
}

/** 截止时间 → 「剩 2 天」；没有截止时间显示「手动结束」 */
private fun remainingLabel(closesAt: String?): String {
    val at = parseInstant(closesAt) ?: return "手动结束"
    val left = Duration.between(Instant.now(), at)
    return when {
        left.isNegative -> "已结束"
        left.toDays() >= 1 -> "剩 ${left.toDays()} 天"
        left.toHours() >= 1 -> "剩 ${left.toHours()} 小时"
        else -> "剩 ${left.toMinutes().coerceAtLeast(1)} 分钟"
    }
}

/** 红包卡的两套底色：可领是正红，已领 / 领完 / 过期 / 撤回是褪色的橘红（同微信拆过的红包） */
private val CardLive = listOf(Color(0xFFF4644C), Color(0xFFDE3B2E))
private val CardLiveStrip = Color(0xFFC73127)
private val CardDim = listOf(Color(0xFFF3A287), Color(0xFFE8876D))
private val CardDimStrip = Color(0xFFD97A61)

/**
 * 红包卡：红底金封（「福」字金章）+ 祝福语 + 状态，底条写种类与进度。
 * 已领 / 已领完 / 已过期 / 已撤回换成褪色底、金章变淡；到点过期自己刷新一次（服务端不推过期事件）。
 */
@Composable
internal fun RedPacketCard(message: Message, meta: RedPacketMeta, callbacks: MessageCallbacks) {
    val type = KcTheme.type
    LaunchedEffect(meta.id) { callbacks.loadRedPacket(meta.id) }
    val state = callbacks.redPacketState(meta.id)
    var tick by remember { mutableIntStateOf(0) }
    val expiresAt = parseInstant(state?.expiresAt)
    LaunchedEffect(expiresAt, state?.status) {
        if (state?.status == "active" && expiresAt != null) {
            val wait = Duration.between(Instant.now(), expiresAt).toMillis()
            if (wait > 0) {
                kotlinx.coroutines.delay(wait + 50)
                tick++
            }
        }
    }
    val ended = remember(state, tick) { state?.endedStatus }
    val dim = state != null && (state.claimedByMe || ended != null)
    val count = state?.count ?: meta.count
    val stateText = when {
        state == null -> "领取红包"
        state.claimedByMe -> "已领取 ${state.myAmount} 币"
        ended == "finished" && count == 1 -> "已被领取"
        ended == "finished" -> "已领完"
        ended == "expired" -> "已过期"
        ended == "revoked" -> "已撤回"
        else -> "领取红包"
    }
    val press = rememberInteraction()
    val fade by animateFloatAsState(if (dim) 1f else 0f, tween(300), label = "rpDim")
    Column(
        Modifier
            .widthIn(min = 232.dp, max = 256.dp)
            .pressScale(press, 0.97f)
            .shadow(if (dim) 0.5.dp else 2.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = null) { callbacks.onOpenRedPacket(message, meta) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(lerp(CardLive[0], CardDim[0], fade), lerp(CardLive[1], CardDim[1], fade))))
                .drawBehind {
                    // 右上一道浅弧，像信封的封口
                    drawCircle(Color.White.copy(alpha = 0.08f), radius = size.height * 1.1f, center = Offset(size.width * 1.02f, -size.height * 0.35f))
                },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer { alpha = 1f - 0.35f * fade }
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFFFFF0C2), Color(0xFFFFD98A), Color(0xFFE8A93A))))
                        .border(1.5.dp, Color(0xFFE8A93A).copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("福", fontSize = 19.sp, fontWeight = FontWeight.W700, color = Color(0xFFB0281C)) }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(meta.greeting, style = type.rowTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stateText, style = type.caption, color = Color(0xFFFFE9B0).copy(alpha = if (dim) 0.95f else 0.9f), modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(lerp(CardLiveStrip, CardDimStrip, fade)).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcIcon(KcIcons.redPacket, tint = Color.White.copy(alpha = 0.8f), size = 12.dp)
            Text(
                if ((state?.kind ?: meta.kind) == "lucky") "拼手气红包" else "KukeChat 红包",
                style = type.caption,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            if (state != null && count > 1) Text("${state.claimedCount.coerceAtMost(count)}/$count", style = type.caption, color = Color.White.copy(alpha = 0.82f))
        }
    }
}

/**
 * 礼物卡（对照客户端 GiftCard）：「谁 送给 谁」· 礼物图形（或商品小样：头像框套在收礼人头像上 / 一条穿着这件皮肤的气泡）·
 * 名字 ×数量 · 留言。送的是装扮、而且收礼人是我：底下多一行「去装扮」。
 * 刚到的那一下（礼物弹出来、光晕闪一次、数量跳一下）只在消息刚到时播一次，翻历史、滚回来都是静止的卡片。
 */
@Composable
internal fun GiftCard(gift: GiftMeta) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = ink.kuke.chat.ui.shell.LocalAppNav.current
    val reduced = rememberReducedMotion()
    val fresh = remember(gift.id) {
        !reduced && (Gifts.isFresh(gift.id) || parseInstant(gift.createdAt)?.let { Duration.between(it, Instant.now()).abs().seconds < 8 } == true)
    }
    val me = Gifts.meId
    val toMe = gift.toUserId != null && gift.toUserId == me
    val from = if (gift.senderId != null && gift.senderId == me) "你" else gift.fromName.ifBlank { "有人" }
    val to = if (toMe) "你" else gift.toName ?: "Ta"
    val rose = GiftColors.rose
    val gold = GiftColors.gold
    val enter = remember(gift.id) { Animatable(if (fresh) 0f else 1f) }
    val pop = remember(gift.id) { Animatable(if (fresh) 0.3f else 1f) }
    val glow = remember(gift.id) { Animatable(if (fresh) 0f else 0.47f) }
    val bump = remember(gift.id) { Animatable(if (fresh) 0.4f else 1f) }
    if (fresh) LaunchedEffect(gift.id) {
        launch { enter.animateTo(1f, tween(320, easing = ink.kuke.chat.ui.components.EaseSmooth)) }
        launch { glow.animateTo(1f, tween(420)); glow.animateTo(0.47f, tween(980)) }
        launch { kotlinx.coroutines.delay(360); bump.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 480f)) }
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 260f))
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .graphicsLayer {
                val e = enter.value
                alpha = e
                translationY = (1f - e) * 6.dp.toPx()
                scaleX = 0.97f + 0.03f * e
                scaleY = 0.97f + 0.03f * e
            }
            .width(252.dp)
            .shadow(2.dp, shape, ambientColor = rose.copy(alpha = 0.3f), spotColor = rose.copy(alpha = 0.3f))
            .clip(shape)
            .background(colors.panel)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(rose.copy(alpha = 0.15f), Color.Transparent), center = Offset.Zero, radius = size.width * 0.9f))
                drawRect(Brush.radialGradient(listOf(gold.copy(alpha = 0.14f), Color.Transparent), center = Offset(size.width, size.height), radius = size.width * 0.75f))
            }
            .border(1.dp, rose.copy(alpha = if (toMe) 0.45f else 0.22f), shape),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(from, style = type.caption, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" 送给 ", style = type.caption, color = rose, fontWeight = FontWeight.W600)
                Text(to, style = type.caption, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(68.dp).drawBehind {
                        val a = glow.value
                        drawCircle(
                            Brush.radialGradient(0f to gold.copy(alpha = (0.55f * a).coerceIn(0f, 1f)), 0.46f to rose.copy(alpha = (0.26f * a).coerceIn(0f, 1f)), 0.7f to Color.Transparent),
                            radius = size.minDimension / 2 - 4.dp.toPx(),
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    GiftThing(
                        gift.gift, gift.item, 64.dp,
                        Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -20f },
                        wearer = gift.toUserId?.let { Wearer(it, gift.toName ?: "Ta", gift.toAvatar) },
                    )
                }
                Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(gift.name, style = type.rowTitle.copy(fontSize = 15.sp), color = colors.text, fontWeight = FontWeight.W700, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (gift.kind == "gift" && gift.count > 1) {
                            Text(
                                "×${gift.count}", fontSize = 17.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.W800, color = rose,
                                modifier = Modifier.padding(start = 6.dp).graphicsLayer { scaleX = bump.value; scaleY = bump.value },
                            )
                        }
                    }
                    gift.item?.let { Text("${categoryLabel(it.category)} · ${termLabel(it.durationDays)}", style = type.caption, color = colors.muted) }
                    gift.message?.let {
                        Text(
                            it, style = type.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(colors.text.copy(alpha = 0.05f)).padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        if (gift.item != null && toMe) {
            Row(
                Modifier.fillMaxWidth().background(rose.copy(alpha = 0.06f)).drawBehind { drawLine(rose.copy(alpha = 0.18f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已经放进你的装扮里", style = type.caption, color = colors.muted, modifier = Modifier.weight(1f))
                Text(
                    "去装扮", style = type.caption, color = Color.White, fontWeight = FontWeight.W600,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(GiftColors.roseHi, rose)))
                        .clickable { nav.go(ink.kuke.chat.ui.shell.ShopRoute) }.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 合并转发卡：标题 + 前三条摘要 + 「查看 N 条聊天记录」 */
@Composable
internal fun ForwardCard(message: Message, callbacks: MessageCallbacks) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val bundle = forwardOf(message) ?: return
    Column(
        Modifier
            .widthIn(min = 220.dp, max = 270.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .background(colors.panel, RoundedCornerShape(12.dp))
            .clickable { callbacks.onOpenBundle(message) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(bundle.title, style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        bundle.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.caption, color = colors.accent, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        bundle.items.take(3).forEach { item ->
            Text("${item.senderName}：${forwardItemPreview(item)}", style = type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Text("查看 ${bundle.count} 条聊天记录", style = type.caption, color = colors.subtle)
    }
}
