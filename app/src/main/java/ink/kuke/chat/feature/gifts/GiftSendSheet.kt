package ink.kuke.chat.feature.gifts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.groupplus.CoinMark
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.feature.shop.ShopItem
import ink.kuke.chat.feature.shop.ShopOverview
import ink.kuke.chat.feature.shop.canRender
import ink.kuke.chat.feature.shop.formatCoins
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.GiftRecordsRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.SettingsPageRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import java.time.Instant

/** 收礼人。wornSkus 是对方身上正装备着的商品（用来提前看出「已永久拥有」） */
data class GiftRecipient(val id: Long, val name: String, val avatarUrl: String?, val birthday: Boolean = false, val wornSkus: Set<String> = emptySet())

/** 数量的几个快捷档；超过上限的不显示 */
private val CountPresets = listOf(1, 5, 10, 66, 99)

/** 商店里现在能送的商品：有价格、在售、这个版本画得出来的气泡和头像框 */
private fun giftableItems(shop: ShopOverview?): List<ShopItem> {
    val now = Instant.now()
    return shop?.items.orEmpty().filter { item ->
        item.category in setOf("frame", "bubble") && item.price > 0 && !item.delisted &&
            parseInstant(item.availableUntil)?.isAfter(now) != false && canRender(item)
    }
}

/**
 * 送礼面板（对照客户端 SendGiftDialog）。两个页签：「礼物」（目录里的礼物，选数量）和「装扮」（商店里能送的气泡、头像框，带小样，标明限时 / 永久）；
 * 下面是留言、花费、现在的余额、送完剩多少。余额不够当场说并给「刷新余额 / 去投币」，没绑定 CCW 时整个面板换成绑定引导。
 *
 * conversationId 不为 null：在会话里送，落一条礼物消息，送完直接关掉（卡片和动效在聊天里）；
 * 为 null：从个人主页送，不落消息，送完在面板里说一声「送到了」。
 * 群里先选人：recipient 为 null 时面板只显示「选择收礼人」，onChangeRecipient 由外面接选人面板。
 */
@Composable
internal fun GiftSendSheet(
    graph: AppGraph,
    recipient: GiftRecipient?,
    conversationId: Long?,
    onChangeRecipient: (() -> Unit)?,
    onDismiss: () -> Unit,
    onSent: (GiftSendResult) -> Unit = {},
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val view = LocalView.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { GiftsApi(graph.api) }
    val catalog by Gifts.catalog.collectAsStateWithLifecycle()
    var catalogTried by remember { mutableStateOf(catalog != null) }
    var shop by remember { mutableStateOf<ShopOverview?>(null) }
    var shopFailed by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var giftKey by remember { mutableStateOf<String?>(null) }
    var itemSku by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableIntStateOf(1) }
    // 对方今天生日：留言先替他写好一句
    var message by remember(recipient?.id) { mutableStateOf(if (recipient?.birthday == true) "生日快乐！" else "") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var donated by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<GiftRecord?>(null) }
    var ownedTick by remember { mutableIntStateOf(0) }

    fun refreshBalance() {
        if (refreshing) return
        refreshing = true
        refreshError = null
        failure = null
        scope.launch {
            runCatching { api.refreshBalance() }
                .onSuccess { b -> shop = shop?.copy(balance = b) }
                .onFailure { refreshError = (it as? ApiException)?.message ?: "余额没有刷新成功，稍后再试。" }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        Gifts.ensureCatalog(graph)
        catalogTried = true
    }
    LaunchedEffect(Unit) {
        runCatching { api.shop() }.onSuccess { s ->
            shop = s
            // 绑定后从没核实过的人余额是 0：替他核实一次，免得一打开就说余额不够
            if (s.balance.bound && s.balance.verifiedAt == null) refreshBalance()
        }.onFailure { shopFailed = true }
    }

    val gifts = catalog?.gifts.orEmpty()
    val items = remember(shop) { giftableItems(shop) }
    val limits = catalog?.limits ?: GiftLimits()
    val charmLabel = catalog?.charmLabel ?: DefaultCharmLabel
    val gift = gifts.firstOrNull { it.key == giftKey } ?: gifts.firstOrNull()
    val item = items.firstOrNull { it.sku == itemSku }
    val targetName = recipient?.name ?: "对方"
    val closed = catalogTried && (catalog == null || catalog?.enabled == false)
    val isItem = tab == 1 && shop != null

    fun owns(entry: ShopItem): Boolean {
        if (ownedTick < 0) return false // 读一下：服务端说过「已拥有」之后重新判断
        val r = recipient ?: return false
        return Gifts.knownOwned(r.id, entry.sku) || (entry.durationDaysOrNull == null && entry.sku in r.wornSkus)
    }

    val balance = shop?.balance
    val total: Int? = if (!isItem) gift?.let { it.price * count } else item?.price?.toInt()
    val localShort = if (balance != null && total != null) (total - balance.balance).coerceAtLeast(0.0) else 0.0
    val short = localShort > 0 || failure?.first == "insufficient_balance"
    val unbound = (balance != null && !balance.bound) || failure?.first == "ccw_not_bound"
    val after = if (balance != null && total != null) balance.balance - total else null
    val blockedItem = isItem && item != null && owns(item)
    val ready = catalog != null && !closed && recipient != null && total != null && !short && !blockedItem && !busy

    fun submit() {
        val r = recipient ?: return
        if (!ready) return
        if (!isItem && gift == null) return
        if (isItem && item == null) return
        busy = true
        failure = null
        val sku = item?.sku
        scope.launch {
            runCatching { api.send(r.id, conversationId, if (isItem) "item" else "gift", gift?.key, count, sku, message) }
                .onSuccess { result ->
                    Haptics.confirm(view)
                    result.balance?.let { b -> shop = shop?.copy(balance = b) }
                    Gifts.absorb(result.record)
                    onSent(result)
                    when {
                        result.message != null -> onDismiss()
                        result.record != null -> sent = result.record
                        else -> onDismiss()
                    }
                }
                .onFailure { error ->
                    Haptics.reject(view)
                    val described = describeSendGiftError(error, targetName)
                    when (described.first) {
                        "recipient_owns" -> if (sku != null) { Gifts.noteOwned(r.id, sku); ownedTick++ }
                        "insufficient_balance" -> (error as? ApiException)?.verifiedBalance()?.let { v -> shop = shop?.let { it.copy(balance = it.balance.copy(balance = v)) } }
                        "disabled", "invalid_gift" -> Gifts.ensureCatalog(graph, force = true)
                        "not_giftable" -> runCatching { api.shop() }.onSuccess { shop = it }
                    }
                    failure = described
                }
            busy = false
        }
    }

    KcSheet(onDismiss = { if (!busy) onDismiss() }, maxHeight = 720.dp) {
        SheetHeader(
            title = if (sent != null) "送到了" else "送礼物",
            onRecords = if (sent == null) ({ onDismiss(); nav.go(GiftRecordsRoute) }) else null,
        )
        AnimatedContent(
            targetState = when {
                sent != null -> 2
                unbound -> 1
                else -> 0
            },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "giftSheet",
        ) { screen ->
            when (screen) {
                2 -> SentView(sent, recipient, charmLabel, onAgain = { sent = null }, onDone = onDismiss)
                1 -> UnboundView(onCancel = onDismiss, onBind = { onDismiss(); nav.go(SettingsPageRoute("security")) })
                else -> Column {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        RecipientCard(recipient, onChangeRecipient)
                        if (shop != null) Segmented(listOf("礼物", "装扮"), tab, { tab = it; failure = null })

                        if (!isItem) {
                            when {
                                !catalogTried -> Quiet("加载中…")
                                gifts.isEmpty() -> Quiet(if (closed) "礼物功能现在关着，暂时送不了。" else "现在没有上架的礼物。")
                                else -> {
                                    GiftGrid(gifts, gift?.key) { giftKey = it; failure = null; Haptics.tick(view) }
                                    CountRow(count, limits.countMax) { count = it; failure = null }
                                }
                            }
                        } else if (items.isEmpty()) {
                            Quiet("商店里现在没有可以送的装扮。")
                        } else {
                            ItemGrid(items, item?.sku, recipient, ::owns) { itemSku = it; failure = null; Haptics.tick(view) }
                            item?.let { picked ->
                                Note(
                                    when {
                                        blockedItem -> "$targetName 已经永久拥有「${picked.name}」了，换一件送吧。"
                                        picked.durationDaysOrNull != null -> "「${picked.name}」是限时装扮：送出后 $targetName 拥有 ${picked.durationDaysOrNull} 天；Ta 已经有的话在原来的到期时间上再加 ${picked.durationDaysOrNull} 天。不会自动换上。"
                                        else -> "「${picked.name}」送出后 $targetName 永久拥有，不会自动换上。Ta 已经有了的话送不出去，不会扣币。"
                                    },
                                    tone = if (blockedItem) NoteTone.Warn else NoteTone.Plain,
                                )
                            }
                        }

                        MessageField(message, limits.messageMax, if (recipient?.birthday == true) "生日快乐！" else "说点什么（可以不写）") { message = it; failure = null }
                        if (closed && catalog != null) Note("礼物功能现在关着，暂时送不了。", NoteTone.Danger)
                        Bill(total, if (!isItem && gift != null && count > 1) "${coins(gift.price)} × $count" else null, balance?.balance, shopFailed, after)

                        AnimatedVisibility(short, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val head = if (localShort > 0) "金币不够，还差 ${formatCoins(localShort)} 个。" else failure?.second ?: "金币不够。"
                                Note(head + if (donated) "投完了吗？回来点「刷新余额」。" else "去 KukeChat 官方作品页投币，回来点「刷新余额」。", NoteTone.Warn)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SmallPill(if (refreshing) "核实中…" else "刷新余额", KcIcons.refresh, enabled = !refreshing, modifier = Modifier.weight(1f)) { refreshBalance() }
                                    val donate = shop?.donateUrl
                                    SmallPill("去投币", KcIcons.external, primary = true, enabled = !donate.isNullOrBlank(), modifier = Modifier.weight(1f)) {
                                        donated = true
                                        donate?.let { runCatching { uri.openUri(it) } }
                                    }
                                }
                            }
                        }
                        if (balance?.stale == true) Note("上次向 CCW 核实没有成功，余额可能不是最新的。", NoteTone.Warn)
                        refreshError?.let { Note(it, NoteTone.Danger) }
                        failure?.takeIf { it.first != "insufficient_balance" && !(blockedItem && it.first == "recipient_owns") }?.let { Note(it.second, NoteTone.Danger) }
                        Text(
                            "送出的金币是花掉了，对方得到的是礼物和等量的$charmLabel，不是金币。" +
                                if (conversationId == null) "从这里送不会发聊天消息，礼物直接出现在 Ta 的礼物墙上。" else "送出后会在聊天里发一条礼物消息；撤回那条消息不会把礼物和金币退回来。",
                            style = type.caption, color = colors.subtle,
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GiftButton("取消", primary = false, enabled = !busy, modifier = Modifier.weight(1f), onClick = onDismiss)
                        GiftButton(
                            when {
                                busy -> "正在送出…"
                                total != null && ready -> "送出（${coins(total)} 币）"
                                else -> "送出"
                            },
                            enabled = ready,
                            modifier = Modifier.weight(1.8f),
                            onClick = ::submit,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 面板里的几块

@Composable
private fun SheetHeader(title: String, onRecords: (() -> Unit)?) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(GiftColors.roseSoft), contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.gift, tint = GiftColors.rose, size = 17.dp)
        }
        Text(title, style = KcTheme.type.title, color = colors.text, modifier = Modifier.padding(start = 10.dp).weight(1f))
        if (onRecords != null) {
            val press = rememberInteraction()
            Row(
                Modifier.pressScale(press).clip(CircleShape).clickable(interactionSource = press, indication = null, onClick = onRecords).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcIcon(KcIcons.history, tint = GiftColors.rose, size = 15.dp)
                Text("礼物记录", style = KcTheme.type.secondary, color = GiftColors.rose, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun RecipientCard(recipient: GiftRecipient?, onChange: (() -> Unit)?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panelMuted)
            .clickable(enabled = onChange != null) { onChange?.invoke() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recipient == null) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(GiftColors.roseSoft), contentAlignment = Alignment.Center) { KcIcon(KcIcons.userPlus, tint = GiftColors.rose, size = 20.dp) }
            Text("选择收礼人", style = type.rowTitle, color = GiftColors.rose, modifier = Modifier.padding(start = 12.dp).weight(1f))
            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
            return@Row
        }
        Avatar(recipient.avatarUrl, recipient.name, recipient.id, 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("送给", style = type.caption, color = colors.muted)
            Text(recipient.name, style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (recipient.birthday) BirthdayChip(Modifier.padding(start = 6.dp))
        if (onChange != null) {
            Text("换一个人", style = type.caption, color = GiftColors.rose, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 8.dp))
            KcIcon(KcIcons.chevron, tint = GiftColors.rose.copy(alpha = 0.7f), size = 14.dp)
        }
    }
}

/** 「今天生日」小胶囊 */
@Composable
fun BirthdayChip(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(GiftColors.gold.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(KcIcons.cake, tint = GiftColors.gold, size = 12.dp)
        Text("今天生日", fontSize = 11.sp, color = GiftColors.gold, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
private fun GiftGrid(gifts: List<GiftRef>, selected: String?, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        gifts.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { g -> GiftTile(g, g.key == selected, Modifier.weight(1f)) { onPick(g.key) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GiftTile(gift: GiftRef, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val lift = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            lift.snapTo(0.82f)
            lift.animateTo(1.12f, spring(dampingRatio = 0.42f, stiffness = 520f))
        } else lift.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f))
    }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().pressScale(press).clip(shape)
                .background(if (selected) GiftColors.roseSoft else colors.panelMuted)
                .border(1.5.dp, if (selected) GiftColors.rose else Color.Transparent, shape)
                .clickable(interactionSource = press, indication = null, onClick = onClick)
                .padding(top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GiftGlyph(gift.icon, 44.dp, Modifier.graphicsLayer { scaleX = lift.value; scaleY = lift.value })
            Text(gift.name, style = KcTheme.type.caption, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CoinMark(11.dp)
                Text(coins(gift.price), fontSize = 11.sp, color = colors.muted, modifier = Modifier.padding(start = 3.dp))
            }
        }
        if (gift.tier > 1) TierFlag(gift.tier, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-4).dp))
    }
}

/** 档位角标：「动效」（聊天区里的一段）/「全屏」 */
@Composable
fun TierFlag(tier: Int, modifier: Modifier = Modifier) {
    val brush = if (tier >= 3) Brush.horizontalGradient(listOf(Color(0xFFFFB648), Color(0xFFF07B1F))) else Brush.horizontalGradient(listOf(GiftColors.roseHi, GiftColors.rose))
    Text(
        if (tier >= 3) "全屏" else "动效",
        fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.W700, color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(brush).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun CountRow(count: Int, max: Int, onChange: (Int) -> Unit) {
    val colors = KcTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("数量", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                StepKey(KcIcons.minus, count > 1) { onChange((count - 1).coerceAtLeast(1)) }
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        val up = targetState > initialState
                        (fadeIn(tween(160)) + androidx.compose.animation.slideInVertically { if (up) it / 2 else -it / 2 }) togetherWith
                            (fadeOut(tween(100)) + androidx.compose.animation.slideOutVertically { if (up) -it / 2 else it / 2 })
                    },
                    label = "count",
                ) { value ->
                    Text("$value", style = KcTheme.type.number.copy(fontSize = 16.sp), color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
                }
                StepKey(KcIcons.plus, count < max) { onChange((count + 1).coerceAtMost(max)) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountPresets.filter { it <= max }.forEach { value ->
                val on = value == count
                val press = rememberInteraction()
                Text(
                    "×$value",
                    style = KcTheme.type.secondary, fontWeight = if (on) FontWeight.W700 else FontWeight.W500,
                    color = if (on) GiftColors.rose else colors.muted,
                    modifier = Modifier.pressScale(press).clip(CircleShape)
                        .background(if (on) GiftColors.roseSoft else colors.panelMuted)
                        .border(1.dp, if (on) GiftColors.rose.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                        .clickable(interactionSource = press, indication = null) { onChange(value) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StepKey(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Box(
        Modifier.size(32.dp).pressScale(press, 0.88f).clip(RoundedCornerShape(9.dp))
            .background(if (enabled) colors.raised else Color.Transparent)
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { KcIcon(icon, tint = if (enabled) colors.text else colors.subtle, size = 16.dp) }
}

@Composable
private fun ItemGrid(items: List<ShopItem>, selected: String?, recipient: GiftRecipient?, owns: (ShopItem) -> Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { entry -> ItemTile(entry, entry.sku == selected, owns(entry), recipient, Modifier.weight(1f)) { onPick(entry.sku) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ItemTile(item: ShopItem, selected: Boolean, owned: Boolean, recipient: GiftRecipient?, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.pressScale(press).clip(shape)
            .background(if (selected) GiftColors.roseSoft else colors.panelMuted)
            .border(1.5.dp, if (selected) GiftColors.rose else Color.Transparent, shape)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(10.dp)).background(colors.chat).graphicsLayer { alpha = if (owned) 0.45f else 1f }, contentAlignment = Alignment.Center) {
            val ref = GiftItemRef(item.sku, item.name, item.category, item.style, item.durationDaysOrNull)
            GiftThing(null, ref, 60.dp, wearer = recipient?.let { Wearer(it.id, it.name, it.avatarUrl) }, sampleText = item.previewText.trim().ifBlank { "你好呀" })
            if (owned) {
                Text(
                    "Ta 已拥有", fontSize = 10.sp, fontWeight = FontWeight.W600, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(item.name, style = KcTheme.type.secondary, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        Row(Modifier.padding(top = 4.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val limited = item.durationDaysOrNull != null
            Text(
                termLabel(item.durationDaysOrNull), fontSize = 10.5.sp, fontWeight = FontWeight.W600,
                color = if (limited) GiftColors.gold else colors.muted,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (limited) GiftColors.gold.copy(alpha = 0.14f) else colors.border).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            CoinMark(11.dp)
            Text(formatCoins(item.price), fontSize = 11.5.sp, color = colors.muted, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

@Composable
private fun MessageField(value: String, max: Int, placeholder: String, onChange: (String) -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("留言", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(end = 12.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = KcTheme.type.body, color = colors.subtle, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= max) onChange(it) },
                singleLine = true,
                textStyle = KcTheme.type.body.copy(color = colors.text),
                cursorBrush = SolidColor(GiftColors.rose),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("${value.length}/$max", style = KcTheme.type.caption, color = colors.subtle, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Bill(total: Int?, breakdown: String?, balance: Double?, shopFailed: Boolean, after: Double?) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panelMuted).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("花费", style = type.secondary, color = colors.muted, modifier = Modifier.weight(1f))
            if (breakdown != null) Text(breakdown, style = type.caption, color = colors.subtle, modifier = Modifier.padding(end = 8.dp))
            if (total != null) {
                CoinMark(15.dp)
                Text(coins(total), style = type.number.copy(fontSize = 18.sp), color = GiftColors.rose, fontWeight = FontWeight.W800, modifier = Modifier.padding(start = 4.dp))
            } else Text("—", style = type.secondary, color = colors.subtle)
        }
        Row {
            Text("现在的余额", style = type.secondary, color = colors.muted, modifier = Modifier.weight(1f))
            Text(balance?.let { formatCoins(it) } ?: if (shopFailed) "—" else "加载中…", style = type.secondary, color = colors.text)
        }
        Row {
            Text("送完剩下", style = type.secondary, color = colors.muted, modifier = Modifier.weight(1f))
            Text(
                when {
                    after == null -> "—"
                    after < 0 -> "不够，还差 ${formatCoins(-after)}"
                    else -> formatCoins(after)
                },
                style = type.secondary, color = if (after != null && after < 0) colors.danger else colors.text, fontWeight = if (after != null && after < 0) FontWeight.W600 else FontWeight.W400,
            )
        }
    }
}

@Composable
private fun SentView(record: GiftRecord?, recipient: GiftRecipient?, charmLabel: String, onAgain: () -> Unit, onDone: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val r = record ?: return
    val name = recipient?.name ?: r.toUser?.name ?: "Ta"
    val pop = remember { Animatable(0.3f) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(r.id) {
        launch { glow.animateTo(1f, tween(900)) }
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
    }
    val rose = GiftColors.rose
    val gold = GiftColors.gold
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(150.dp).drawBehind {
                val a = glow.value
                drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.34f * a), rose.copy(alpha = 0.14f * a), Color.Transparent)), radius = size.minDimension / 2)
            },
            contentAlignment = Alignment.Center,
        ) {
            GiftThing(
                r.gift, r.item, 96.dp,
                Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -18f },
                wearer = recipient?.let { Wearer(it.id, it.name, it.avatarUrl) },
            )
        }
        Text(
            if (r.item != null) "「${r.item.name}」已经送给 $name" else "${giftThingLabel(r.kind, r.gift, r.item, r.count)} 已经送给 $name",
            style = type.title, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            (if (r.item != null) "已经放进 Ta 的装扮里。" else "") + "Ta 的$charmLabel +${coins(r.amount)}，礼物会出现在 Ta 的礼物墙上。",
            style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GiftButton("再送一个", primary = false, modifier = Modifier.weight(1f), onClick = onAgain)
            GiftButton("完成", modifier = Modifier.weight(1f), onClick = onDone)
        }
    }
}

@Composable
private fun UnboundView(onCancel: () -> Unit, onBind: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(GiftColors.gold.copy(alpha = 0.12f)).padding(14.dp)) {
            KcIcon(KcIcons.wallet, tint = GiftColors.gold, size = 22.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text("绑定 CCW 账号后才能送礼物", style = KcTheme.type.rowTitle, color = colors.text, fontWeight = FontWeight.W600)
                Text(
                    "礼物是用金币送的：你在 CCW 给 KukeChat 官方作品投过多少币，这里就有多少，按绑定的 CCW 账号核实。绑定之后，以前投过的也算数。",
                    style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GiftButton("取消", primary = false, modifier = Modifier.weight(1f), onClick = onCancel)
            GiftButton("去绑定 CCW 账号", modifier = Modifier.weight(1.6f), onClick = onBind)
        }
    }
}

// ------------------------------------------------------------------ 小件（礼物几处页面共用）

enum class NoteTone { Plain, Warn, Danger }

@Composable
fun Note(text: String, tone: NoteTone = NoteTone.Plain) {
    val colors = KcTheme.colors
    val (bg, fg) = when (tone) {
        NoteTone.Plain -> colors.panelMuted to colors.muted
        NoteTone.Warn -> colors.warn.copy(alpha = 0.12f) to colors.warn
        NoteTone.Danger -> colors.danger.copy(alpha = 0.1f) to colors.danger
    }
    Text(text, style = KcTheme.type.caption.copy(lineHeight = 18.sp), color = fg, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 12.dp, vertical = 9.dp))
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

/** 礼物的主按钮：玫红渐变；primary = false 时是中性的次要按钮 */
@Composable
fun GiftButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val alpha by animateFloatAsState(if (enabled) 1f else 0.42f, tween(160), label = "giftBtn")
    val bg = if (primary) Brush.verticalGradient(listOf(GiftColors.roseHi, GiftColors.rose)) else SolidColor(colors.panelMuted)
    Box(
        modifier.height(46.dp).pressScale(press).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(13.dp)).background(bg)
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KcTheme.type.rowTitle, color = if (primary) Color.White else colors.text, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@Composable
private fun SmallPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val fg = if (primary) Color.White else colors.text
    Row(
        modifier.height(38.dp).pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.45f }.clip(RoundedCornerShape(11.dp))
            .background(if (primary) SolidColor(colors.warn) else SolidColor(colors.panelMuted))
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(icon, tint = fg, size = 15.dp)
        Text(text, style = KcTheme.type.secondary, color = fg, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 「×3」那种斜体数量 */
@Composable
fun CountMark(count: Int, size: androidx.compose.ui.unit.TextUnit = 16.sp, modifier: Modifier = Modifier) {
    Text("×$count", fontSize = size, fontStyle = FontStyle.Italic, fontWeight = FontWeight.W800, color = GiftColors.rose, modifier = modifier)
}
