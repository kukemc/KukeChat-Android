package ink.kuke.chat.feature.shop

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.settings.CenterText
import ink.kuke.chat.feature.settings.LoadBox
import ink.kuke.chat.feature.settings.Loader
import ink.kuke.chat.feature.settings.Pill
import ink.kuke.chat.feature.settings.PrimaryButton
import ink.kuke.chat.feature.settings.SecondaryButton
import ink.kuke.chat.feature.settings.ToastHost
import ink.kuke.chat.feature.settings.chinaTime
import ink.kuke.chat.feature.settings.openExternal
import ink.kuke.chat.feature.settings.rememberLoader
import ink.kuke.chat.feature.settings.rememberToast
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.SettingsPageRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 商店的暖金（只在商店出现，设计稿 P25） */
private val GoldTop = Color(0xFFFFD66B)
private val GoldBottom = Color(0xFFF5A623)
private val GoldInk = Color(0xFF3B2A00)

private const val RefreshInterval = 20

/**
 * 商店（设计稿 P25）：余额卡（暖金；未绑定 CCW 时换成绑定引导）、我的装扮、类别、商品卡、购买确认底部面板、购买记录。
 * 契约：KukeChat-Server/docs/shop.md。商品卡上的试穿舞台只做纯色 / 渐变 / 文字色 / 边框 / 圆角（见 SkinPaint.kt），
 * 复杂饰品（纹样、角饰、动效）降级为底色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("shop")) { KcJson.decodeFromJsonElement(ShopOverview.serializer(), graph.api.get("/shop")) }
    var showHistory by remember { mutableStateOf(false) }
    var purchaseTarget by remember { mutableStateOf<ShopItem?>(null) }
    val toast = rememberToast()
    // 余额实时变化：带了余额直接换上，没带就静默重拉
    LaunchedEffect(loader) {
        ShopLive.balance.collect { balance -> if (balance != null) loader.mutate { it.copy(balance = balance) } else loader.load(silent = true) }
    }

    KcPage(
        title = "商店",
        onBack = { nav.back() },
        actions = {
            if (loader.data != null) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { showHistory = true }, contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.clock, tint = KcTheme.colors.text, size = 22.dp, contentDescription = "购买记录")
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LoadBox(loader) { shop ->
                ShopContent(graph, loader, shop, toast, onBuy = { purchaseTarget = it })
            }
            ToastHost(toast)
        }
    }

    if (showHistory) HistorySheet(graph) { showHistory = false }
    purchaseTarget?.let { target ->
        loader.data?.let { shop ->
            PurchaseSheet(
                graph = graph, loader = loader, shop = shop, itemId = target.id,
                onDismiss = { purchaseTarget = null },
            )
        }
    }
}

private fun ShopOverview.slotOf(category: String): String = categories.firstOrNull { it.key == category }?.slot ?: category

/** 把装备状态落到商品列表上 */
private fun ShopOverview.withEquipped(equipped: Map<String, String?>): ShopOverview =
    copy(equipped = equipped, items = items.map { it.copy(equipped = it.owned && equipped[slotOf(it.category)] == it.sku) })

@Composable
private fun ShopContent(graph: AppGraph, loader: Loader<ShopOverview>, shop: ShopOverview, toast: ink.kuke.chat.feature.settings.ToastState, onBuy: (ShopItem) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    var activeKey by remember { mutableStateOf<String?>(null) }
    var equipBusy by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var readyAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    // 冷却倒计时：只在等待时每秒走一次
    LaunchedEffect(readyAt) {
        while (readyAt > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val wait = ((readyAt - now + 999) / 1000).toInt().coerceAtLeast(0)

    // 只显示这个版本认识的类别（客户端遇到不认识的类别就不显示它的商品）
    val known = setOf("bubble", "frame")
    val categories = shop.categories.filter { it.key in known }
    val category = categories.firstOrNull { it.key == activeKey } ?: categories.firstOrNull()
    val items = shop.items.filter { it.category == category?.key }.sortedWith(compareByDescending<ShopItem> { it.equipped }.thenByDescending { it.owned })

    fun equip(item: ShopItem?, slot: String) {
        scope.launch {
            equipBusy = item?.sku ?: slot
            runCatching {
                graph.api.put("/shop/equipped", buildJsonObject {
                    put("slot", slot)
                    if (item != null) put("sku", item.sku) else put("sku", JsonPrimitive(null as String?))
                })
            }.onSuccess { element ->
                val map = ((element as? JsonObject)?.get("equipped") as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content } ?: shop.equipped
                loader.mutate { it.withEquipped(map) }
                // 自己的头像框、气泡马上跟着换（不等 user.cosmetics.updated）
                graph.scope.launch { Cosmetics.refreshMe(graph) }
                toast.show(if (item != null) "已换上「${item.name}」" else "已卸下", scope)
            }.onFailure { toast.show(it.userMessage(), scope) }
            equipBusy = null
        }
    }

    fun refreshBalance() {
        if (refreshing || wait > 0) return
        scope.launch {
            refreshing = true
            refreshError = null
            runCatching { KcJson.decodeFromJsonElement(ShopBalance.serializer(), graph.api.post("/shop/balance/refresh")) }
                .onSuccess { balance ->
                    loader.mutate { it.copy(balance = balance) }
                    readyAt = System.currentTimeMillis() + RefreshInterval * 1000L
                }
                .onFailure { error ->
                    val retry = ((error as? ApiException)?.detail as? JsonObject)?.get("retry_after")?.jsonPrimitive?.doubleOrNull
                    if (error is ApiException && error.code == "refresh_too_fast" && retry != null) {
                        readyAt = System.currentTimeMillis() + (retry * 1000).toLong()
                        refreshError = "刷新太频繁了，${Math.ceil(retry).toInt()} 秒后再试。"
                    } else refreshError = error.userMessage()
                }
            refreshing = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- 余额卡
        item(key = "hero") {
            val balance = shop.balance
            if (!balance.bound) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(GoldTop, GoldBottom))).padding(18.dp),
                ) {
                    Text("绑定 CCW 账号后才能购买", style = type.title, color = GoldInk, fontWeight = FontWeight.W700)
                    Text(
                        "金币是你在 CCW 给 KukeChat 官方作品投出的币，按绑定的 CCW 账号核实；以前投过的也算数。没绑定也可以先逛逛。",
                        style = type.secondary, color = GoldInk.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("去绑定 CCW 账号", onClick = { nav.go(SettingsPageRoute("security")) }, container = GoldInk, content = Color.White)
                }
            } else {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(GoldTop, GoldBottom))).padding(18.dp),
                ) {
                    Text("金币余额", style = type.caption, color = GoldInk.copy(alpha = 0.75f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatCoins(balance.balance), color = GoldInk, fontSize = 36.sp, fontWeight = FontWeight.W800, modifier = Modifier.weight(1f))
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            KcIcon(KcIcons.wallet, tint = GoldInk, size = 22.dp)
                        }
                    }
                    Text(
                        "累计投币 ${formatCoins(balance.donated)} · 已花 ${formatCoins(balance.spent)}" + balanceExtras(balance) + " · " + verifiedLabel(balance.verifiedAt),
                        style = type.caption, color = GoldInk.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.5f))
                                .clickable(enabled = !refreshing && wait == 0) { refreshBalance() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (refreshing) "核实中…" else if (wait > 0) "$wait 秒后可刷新" else "刷新余额", color = GoldInk, style = type.rowTitle, fontWeight = FontWeight.W600)
                        }
                        val canDonate = shop.donateUrl?.startsWith("http") == true
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(GoldInk.copy(alpha = if (canDonate) 1f else 0.35f))
                                .clickable(enabled = canDonate) { context.openExternal(shop.donateUrl.orEmpty()) },
                            contentAlignment = Alignment.Center,
                        ) { Text("去投币", color = Color.White, style = type.rowTitle, fontWeight = FontWeight.W600) }
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (balance.stale) Text("上次向 CCW 核实没有成功，这里显示的是之前的余额，可能不是最新的。过一会儿再刷新。", style = type.caption, color = colors.warn)
                    refreshError?.let { Text(it, style = type.caption, color = colors.danger) }
                    if (shop.donateUrl.isNullOrBlank()) Text("暂时不能充值：服务端还没有配置投币的官方作品。", style = type.caption, color = colors.warn)
                    else Text("金币怎么来：点「去投币」打开 KukeChat 官方作品页，投币后回来点「刷新余额」。币只用来换这里的装扮，没有现金价值。", style = type.caption, color = colors.muted)
                }
            }
        }

        // ---- 我的装扮
        if (categories.isNotEmpty()) {
            item(key = "wardrobe") {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.panel).padding(14.dp)) {
                    Text("我的装扮", style = type.title, color = colors.text, modifier = Modifier.padding(bottom = 6.dp))
                    categories.forEach { cat ->
                        val sku = shop.equipped[cat.slot]
                        val item = sku?.let { s -> shop.items.firstOrNull { it.sku == s } }
                        val expiry = item?.let { expiryLabel(it.expiresAt, now) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cat.label, style = type.caption, color = colors.muted)
                                    Text("  " + (item?.name ?: if (sku != null) "已装备一件装扮" else "没有装备"), style = type.rowTitle, color = colors.text)
                                    if (expiry != null) Pill(expiry.first, if (expiry.second) colors.danger else colors.warn, Modifier.padding(start = 6.dp))
                                }
                                Text(
                                    if (item != null) categoryHint(cat.key, true) else categoryHint(cat.key, false),
                                    style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (item != null && item.canRenew()) SecondaryButton("续期", onClick = { onBuy(item) }, modifier = Modifier.padding(end = 6.dp))
                            if (sku != null) SecondaryButton(if (equipBusy == cat.slot) "卸下中…" else "卸下", enabled = equipBusy == null, onClick = { equip(null, cat.slot) })
                        }
                    }
                }
            }
        }

        // ---- 类别页签
        if (categories.size > 1) {
            item(key = "tabs") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { cat ->
                        val on = cat.key == category?.key
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) colors.fill else colors.panel).clickable { activeKey = cat.key }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(cat.label, style = type.secondary, color = if (on) colors.onFill else colors.text, fontWeight = FontWeight.W600) }
                    }
                }
            }
        } else if (categories.size == 1) {
            item(key = "single") {
                Text("${categories[0].label} · ${items.size} 件", style = type.group, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }

        if (categories.isEmpty()) {
            item(key = "empty") { Text("还没有上架商品，商品上架后会出现在这里。", style = type.secondary, color = colors.muted, modifier = Modifier.padding(32.dp)) }
        } else if (items.isEmpty()) {
            item(key = "none") { Text("这个类别还没有商品。", style = type.secondary, color = colors.muted, modifier = Modifier.padding(32.dp)) }
        } else {
            items(items.chunked(2), key = { row -> row.first().id }) { pair ->
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            ItemCard(
                                item = item, level = shop.level, now = now,
                                busy = equipBusy == item.sku,
                                onEquip = { equip(item, shop.slotOf(item.category)) },
                                onUnequip = { equip(null, shop.slotOf(item.category)) },
                                onBuy = { onBuy(item) },
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun categoryHint(category: String, equipped: Boolean): String = when (category) {
    "frame" -> if (equipped) "你的头像会带着这个框，别人也看得到。" else "头像现在没有框。在下面挑一件换上。"
    else -> if (equipped) "你发出的消息会用这个气泡，别人也看得到。" else "用的是主题自带的气泡。在下面挑一件换上。"
}

private fun balanceExtras(balance: ShopBalance): String = buildString {
    fun add(label: String, value: Double?, sign: String) { if (value != null && value > 0) append(" · $label $sign${formatCoins(value)}") }
    add("发出红包", balance.redPacketSent, "−")
    add("抢到红包", balance.redPacketReceived, "+")
    add("红包退回", balance.redPacketRefunded, "+")
    add("送出礼物", balance.giftSpent, "−")
    add("群升级", balance.groupPlusSpent, "−")
    balance.granted?.takeIf { it != 0.0 }?.let { append(" · 后台赠送 ${if (it > 0) "+" else "−"}${formatCoins(kotlin.math.abs(it))}") }
}

private fun verifiedLabel(iso: String?): String {
    val at = parseInstant(iso) ?: return "还没有核实过"
    val seconds = ((System.currentTimeMillis() - at.toEpochMilli()) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "刚刚核实"
        seconds < 3600 -> "${seconds / 60} 分钟前核实"
        seconds < 86400 -> "${seconds / 3600} 小时前核实"
        else -> "${chinaTime(iso)} 核实"
    }
}

// ------------------------------------------------------------------ 商品卡

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(item: ShopItem, level: Int, now: Long, busy: Boolean, onEquip: () -> Unit, onUnequip: () -> Unit, onBuy: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val action = item.action()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.panel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item.category == "frame") FrameStage(item.style, "我") else BubbleStage(item.style, item.previewText)
        Text(item.name, style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1)
        // 标签：使用中 / 已拥有 / 有效期 / 新品·热门·限时 / 已下架 / 需更新
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (action) {
                ItemAction.Equipped -> Pill("使用中", colors.ok)
                ItemAction.Owned -> Pill("已拥有", colors.muted)
                else -> Unit
            }
            val owned = action == ItemAction.Equipped || action == ItemAction.Owned
            if (owned) expiryLabel(item.expiresAt, now)?.let { Pill(it.first, if (it.second) colors.danger else colors.warn) }
            else durationLabel(item.durationDaysOrNull)?.let { Pill(it, colors.warn) }
            if (item.delisted) Pill("已下架", colors.muted)
            else when (item.tag) {
                "new" -> Pill("新品", colors.accent)
                "hot" -> Pill("热门", colors.warn)
                "limited" -> Pill("限时", colors.danger)
            }
            if (!canRender(item)) Pill("需更新客户端", colors.warn)
        }
        if (item.description.isNotBlank()) Text(item.description, style = type.caption, color = colors.muted, maxLines = 2)
        PriceLine(item, action)
        ItemButton(item, action, level, busy, onEquip, onUnequip, onBuy)
    }
}

@Composable
private fun PriceLine(item: ShopItem, action: ItemAction) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    when {
        action == ItemAction.Equipped || action == ItemAction.Owned ->
            if (item.canRenew() && item.price > 0) Text("续期 ${formatCoins(item.price)} / ${durationLabel(item.durationDaysOrNull)}", style = type.caption, color = colors.muted)
            else Text(if (action == ItemAction.Equipped) "正在使用" else "已在你的装扮里", style = type.caption, color = colors.muted)
        action == ItemAction.Claim || action == ItemAction.LevelLocked ->
            Text(item.unlockLevel?.let { "Lv.$it 解锁" } ?: "等级解锁", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600)
        action == ItemAction.NotForSale -> Text("活动获得", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600)
        item.price > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(GoldBottom))
            Text(" ${formatCoins(item.price)}", style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
            val extra = listOfNotNull(durationLabel(item.durationDaysOrNull)?.let { "/ $it" }, item.unlockLevel?.let { "或 Lv.$it 解锁" }).joinToString(" ")
            if (extra.isNotBlank()) Text(" $extra", style = type.caption, color = colors.muted)
        }
        else -> Text(item.unlockLevel?.let { "Lv.$it 解锁" } ?: "免费", style = type.secondary, color = colors.text, fontWeight = FontWeight.W600)
    }
}

/** 8 种状态：购买 · 免费领取 · 等级不够 · 装备 · 卸下 · 暂不出售 · 已下架 · 暂不可用（+ 续期） */
@Composable
private fun ItemButton(item: ShopItem, action: ItemAction, level: Int, busy: Boolean, onEquip: () -> Unit, onUnequip: () -> Unit, onBuy: () -> Unit) {
    when (action) {
        ItemAction.Equipped, ItemAction.Owned -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.canRenew()) SecondaryButton("续期", onClick = onBuy, modifier = Modifier.weight(1f))
                if (action == ItemAction.Equipped) SecondaryButton(if (busy) "卸下中…" else "卸下", enabled = !busy, onClick = onUnequip, modifier = Modifier.weight(1f))
                else PrimaryButton(if (busy) "装备中…" else "装备", enabled = !busy, onClick = onEquip, modifier = Modifier.weight(1f))
            }
        }
        ItemAction.Buy, ItemAction.Claim ->
            PrimaryButton(if (action == ItemAction.Claim || item.price <= 0) "免费领取" else "购买", onClick = onBuy, modifier = Modifier.fillMaxWidth())
        ItemAction.LevelLocked -> SecondaryButton("等级不够 · 你是 Lv.$level", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        ItemAction.NotForSale -> SecondaryButton("暂不出售", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        ItemAction.Delisted -> SecondaryButton("已下架", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------ 购买确认

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseSheet(graph: AppGraph, loader: Loader<ShopOverview>, shop: ShopOverview, itemId: Long, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val item = shop.items.firstOrNull { it.id == itemId } ?: run { onDismiss(); return }
    val renewing = item.canRenew()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var shortfall by remember { mutableStateOf<Double?>(null) }
    var donateUrl by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var equipping by remember { mutableStateOf(false) }
    val free = item.price <= 0 || item.kind == "level"
    val balance = shop.balance
    val slot = shop.slotOf(item.category)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panel) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (done) "已到手" else if (renewing) "续期「${item.name}」" else if (free) "领取「${item.name}」" else "购买「${item.name}」",
                style = type.title, color = colors.text,
            )
            if (item.category == "frame") FrameStage(item.style, "我") else BubbleStage(item.style, item.previewText)

            if (done) {
                val current = loader.data?.items?.firstOrNull { it.id == item.id } ?: item
                val until = current.expiresAt?.let { chinaTime(it).take(10) }
                Text(
                    "「${item.name}」${if (renewing) "已续期" else "已经是你的了"}" + (until?.let { "，可以用到 $it" } ?: "") +
                        if (current.equipped) "，正在使用中。" else "。现在就换上？",
                    style = type.secondary, color = colors.text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("稍后", onClick = onDismiss, modifier = Modifier.weight(1f))
                    if (!current.equipped) {
                        PrimaryButton("现在换上", loading = equipping, modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                equipping = true
                                runCatching { graph.api.put("/shop/equipped", buildJsonObject { put("slot", slot); put("sku", item.sku) }) }
                                    .onSuccess { element ->
                                        val map = ((element as? JsonObject)?.get("equipped") as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content } ?: shop.equipped
                                        loader.mutate { it.withEquipped(map) }
                                        graph.scope.launch { Cosmetics.refreshMe(graph) }
                                        onDismiss()
                                    }
                                    .onFailure { failure = it.userMessage() }
                                equipping = false
                            }
                        })
                    }
                }
            } else {
                // 账单
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BillRow("商品", item.name)
                    BillRow("价格", if (free) (item.unlockLevel?.let { "Lv.$it 免费解锁" } ?: "免费") else "${formatCoins(item.price)} 金币")
                    durationLabel(item.durationDaysOrNull)?.let { BillRow("有效期", if (renewing) "再加 $it" else it) }
                    if (!free && balance.bound) {
                        BillRow("当前余额", "${formatCoins(balance.balance)} 金币")
                        BillRow("购买后余额", "${formatCoins(balance.balance - item.price)} 金币")
                    }
                }
                val unbound = !free && !balance.bound
                val notEnough = !free && balance.bound && balance.balance < item.price
                if (unbound) Text("还没有绑定 CCW 账号。金币是按你绑定的 CCW 账号核实的，绑定后才能购买。", style = type.caption, color = colors.warn)
                if (notEnough) Text("金币不够，还差 ${formatCoins(item.price - balance.balance)} 个。", style = type.caption, color = colors.warn)
                failure?.let { Text(it, style = type.caption, color = colors.danger) }
                shortfall?.let { Text("还差 ${formatCoins(it)} 个金币。", style = type.caption, color = colors.danger) }

                when {
                    unbound -> PrimaryButton("去绑定 CCW 账号", modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); nav.go(SettingsPageRoute("security")) })
                    notEnough || shortfall != null -> {
                        val url = donateUrl ?: shop.donateUrl
                        PrimaryButton("去投币", enabled = !url.isNullOrBlank(), modifier = Modifier.fillMaxWidth(), onClick = { url?.let { context.openExternal(it) } })
                    }
                    else -> PrimaryButton(
                        text = if (renewing) "确认续期" else if (free) "免费领取" else "确认购买",
                        loading = busy, modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                busy = true
                                failure = null
                                shortfall = null
                                runCatching { KcJson.decodeFromJsonElement(PurchaseResult.serializer(), graph.api.post("/shop/items/${item.id}/purchase")) }
                                    .onSuccess { result ->
                                        done = true
                                        if (result.item != null) {
                                            loader.mutate { overview ->
                                                val next = overview.copy(
                                                    items = overview.items.map { if (it.id == result.item.id) result.item else it },
                                                    balance = result.balance ?: overview.balance,
                                                )
                                                result.equipped?.let { next.withEquipped(it) } ?: next
                                            }
                                        } else loader.load()
                                    }
                                    .onFailure { error ->
                                        failure = describePurchaseError(error, item)
                                        if (error is ApiException && error.code == "insufficient_coins") {
                                            val detail = error.detail as? JsonObject
                                            shortfall = detail?.get("shortfall")?.jsonPrimitive?.doubleOrNull?.let { Math.ceil(it).coerceAtLeast(1.0) }
                                            donateUrl = (detail?.get("donate_url") as? JsonPrimitive)?.content?.takeIf { it.startsWith("http") }
                                            failure = "金币不够。"
                                        }
                                        // 状态已变（别处买了、已下架）：重新拉一次商品
                                        if (error is ApiException && error.code in setOf("already_owned", "item_unavailable", "item_not_found")) loader.load()
                                    }
                                busy = false
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun describePurchaseError(error: Throwable, item: ShopItem): String {
    if (error is ApiException) {
        when (error.code) {
            "item_not_found" -> return "这件商品不存在了，可能已经被移除。"
            "item_unavailable" -> return "这件商品已经下架或过期，现在买不了了。"
            "already_owned" -> return "你已经拥有这件商品了，不用再买。"
            "ccw_not_bound" -> return "还没有绑定 CCW 账号。金币是按你绑定的 CCW 账号核实的，绑定后才能购买。"
            "item_locked" -> return item.unlockLevel?.let { "这件商品不出售，账号等级达到 Lv.$it 后可以免费解锁。" } ?: "这件商品不出售，现在还拿不到。"
            "verification_unavailable" -> return "现在连不上 CCW，没法核实你的余额，稍后再试。这次没有扣币。"
        }
    }
    return error.userMessage()
}

@Composable
private fun BillRow(label: String, value: String) {
    Row {
        Text(label, style = KcTheme.type.secondary, color = KcTheme.colors.muted, modifier = Modifier.weight(1f))
        Text(value, style = KcTheme.type.secondary, color = KcTheme.colors.text, fontWeight = FontWeight.W600)
    }
}

// ------------------------------------------------------------------ 购买记录

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(graph: AppGraph, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("shop.purchases")) { KcJson.decodeFromJsonElement(PurchasePage.serializer(), graph.api.get("/shop/purchases?limit=100&offset=0")) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panel) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).heightIn(min = 240.dp, max = 560.dp)) {
            Text("购买记录", style = type.title, color = colors.text, modifier = Modifier.padding(bottom = 8.dp))
            when (val state = loader.state) {
                is ink.kuke.chat.feature.settings.Load.Loading -> CenterText("正在加载…", Modifier.height(200.dp))
                is ink.kuke.chat.feature.settings.Load.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(state.message, style = type.secondary, color = colors.muted)
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("重试", onClick = { loader.load() })
                }
                is ink.kuke.chat.feature.settings.Load.Ready -> {
                    if (state.data.items.isEmpty()) Text("还没有购买记录", style = type.secondary, color = colors.muted, modifier = Modifier.padding(24.dp))
                    else LazyColumn {
                        items(state.data.items, key = { it.id }) { record ->
                            val source = when (record.source) {
                                "gift" -> (record.fromUser?.displayName?.let { "$it 送的" } ?: "朋友送的")
                                "level" -> "等级解锁"
                                "grant" -> "后台赠送"
                                else -> "购买"
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(record.item.name.ifBlank { "商品" }, style = type.rowTitle, color = if (record.revokedAt != null) colors.muted else colors.text)
                                    Text(
                                        "$source · ${chinaTime(record.createdAt)}" + (record.revokedAt?.let { " · 已被收回" + if (record.refunded) "，币已退回" else "" } ?: ""),
                                        style = type.caption, color = colors.muted,
                                    )
                                }
                                Text(if (record.price > 0) formatCoins(record.price) else "免费", style = type.rowTitle, color = colors.text)
                            }
                        }
                    }
                }
            }
        }
    }
}
