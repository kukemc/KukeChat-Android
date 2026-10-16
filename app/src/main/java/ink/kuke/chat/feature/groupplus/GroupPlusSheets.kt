package ink.kuke.chat.feature.groupplus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.rememberReducedMotion
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun actionLabel(action: String): String = when (action) {
    "renew" -> "续期"
    "upgrade" -> "升级"
    else -> "开通"
}

/**
 * 购买前确认面板（同电脑版的确认页）：先 POST …/plus/quote（不扣币）拿报价，
 * 列出权益变化（旧值划掉 → 新值）、到期时间、扣多少币和买完剩下多少；
 * 升级会作废剩余时间时要先勾选确认，按钮才能点。确认后才 POST …/plus/purchase。
 */
@Composable
internal fun PurchaseSheet(
    api: GroupPlusApi,
    conversationId: Long,
    plans: PlusPlans,
    intent: PurchaseIntent,
    onDismiss: () -> Unit,
    onGoShop: () -> Unit,
    onBind: () -> Unit,
    onPurchased: (PlusInfo, String) -> Unit,
) {
    val k = plusInk()
    val view = LocalView.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var quote by remember { mutableStateOf<PlusQuote?>(null) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<NoteTone, String>?>(null) }
    var unbound by remember { mutableStateOf(false) }
    var shortfall by remember { mutableStateOf<Long?>(null) }
    var donateUrl by remember { mutableStateOf<String?>(null) }
    var donated by remember { mutableStateOf(false) }
    var confirmForfeit by remember { mutableStateOf(false) }
    val showTemp = plans.tiers.any { it.fileTempMaxBytes != null }

    LaunchedEffect(intent, attempt) {
        quote = null
        quoteError = null
        confirmForfeit = false
        runCatching {
            val first = api.quote(conversationId, intent.tier, intent.period)
            if (first.enough) first else {
                // 报价里的余额是库里的旧数：不够时先向 CCW 核实一次再报价
                api.refreshBalance()
                api.quote(conversationId, intent.tier, intent.period)
            }
        }
            .onSuccess { quote = it }
            .onFailure {
                if (it is ApiException && it.code == "ccw_not_bound") unbound = true
                quoteError = it.userMessage()
            }
    }

    fun back() {
        if (!busy) onDismiss()
    }

    fun purchase(current: PlusQuote) {
        if (busy) return
        val needForfeit = current.forfeitedSeconds > 0
        if (needForfeit && !confirmForfeit) return
        busy = true
        notice = null
        scope.launch {
            runCatching { api.purchase(conversationId, intent.tier, intent.period, confirmForfeit = needForfeit, expectedPrice = current.price) }
                .onSuccess {
                    Haptics.confirm(view)
                    onPurchased(it.plus, current.action)
                }
                .onFailure { error ->
                    Haptics.reject(view)
                    val api2 = error as? ApiException
                    when (api2?.code) {
                        // 价格 / 档位关系变了：没扣币，重新报价让用户再确认一次
                        "price_changed", "plan_changed" -> {
                            notice = NoteTone.Warn to error.userMessage()
                            attempt++
                        }
                        "forfeit_not_confirmed" -> {
                            notice = NoteTone.Warn to "这次升级会作废原档位的剩余时间，请先确认。"
                            attempt++
                        }
                        "ccw_not_bound" -> unbound = true
                        "insufficient_balance" -> {
                            shortfall = api2.detailLong("shortfall") ?: 0L
                            donateUrl = api2.detailString("donate_url")
                        }
                        else -> notice = NoteTone.Danger to error.userMessage()
                    }
                    busy = false
                }
        }
    }

    fun refreshBalance() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            api.refreshBalance()
            refreshing = false
            shortfall = null
            notice = null
            attempt++
        }
    }

    KcSheet(onDismiss = ::back, title = null, maxHeight = 700.dp) {
        Text(
            "确认购买", color = k.ink, fontSize = 20.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.02).em,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
        )
        val current = quote
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                quoteError != null && current == null -> {
                    Text(quoteError!!, color = k.bad, fontSize = 13.5.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
                }
                current == null -> {
                    notice?.let { (tone, text) -> PlusNote(text, tone = tone) }
                    Text("正在计算价格…", color = k.sub, fontSize = 13.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
                }
                else -> ConfirmBody(
                    quote = current,
                    plans = plans,
                    showTemp = showTemp,
                    confirmForfeit = confirmForfeit,
                    onForfeit = { confirmForfeit = it },
                    notice = notice,
                    shortfall = shortfall,
                    donated = donated,
                    refreshing = refreshing,
                    unbound = unbound,
                    onRefresh = ::refreshBalance,
                    donateLabel = if (!donateUrl.isNullOrBlank()) "去投币" else "去商店",
                    onDonate = {
                        val url = donateUrl
                        if (!url.isNullOrBlank()) {
                            donated = true
                            runCatching { uri.openUri(url) }
                        } else onGoShop()
                    },
                )
            }
        }

        // 底部：返回 + 主按钮
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlusButton("返回", onClick = ::back, primary = false, enabled = !busy)
            when {
                unbound -> PlusButton("去绑定 CCW 账号", onClick = onBind, modifier = Modifier.weight(1f))
                quoteError != null && current == null -> PlusButton("重试", onClick = { attempt++ }, modifier = Modifier.weight(1f))
                current == null -> PlusButton("正在报价…", onClick = {}, modifier = Modifier.weight(1f), enabled = false)
                else -> {
                    val short = !current.enough || shortfall != null
                    val needForfeit = current.forfeitedSeconds > 0
                    PlusButton(
                        if (busy) "正在处理…" else "确认${actionLabel(current.action)}（${formatCoins(current.price)} 币）",
                        onClick = { purchase(current) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !short && (!needForfeit || confirmForfeit),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmBody(
    quote: PlusQuote,
    plans: PlusPlans,
    showTemp: Boolean,
    confirmForfeit: Boolean,
    onForfeit: (Boolean) -> Unit,
    notice: Pair<NoteTone, String>?,
    shortfall: Long?,
    donated: Boolean,
    refreshing: Boolean,
    unbound: Boolean,
    onRefresh: () -> Unit,
    donateLabel: String,
    onDonate: () -> Unit,
) {
    val k = plusInk()
    val target = plans.tiers.firstOrNull { it.key == quote.tier }
    val before = plans.tiers.firstOrNull { it.key == quote.currentTier } ?: plans.tiers.firstOrNull { it.key == "free" }
    val after = quote.balance - quote.price
    val short = !quote.enough || shortfall != null
    val missing = maxOf(if (after < 0) -after else 0L, shortfall ?: 0L)

    // 档位 + 动作 + 周期
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.wash).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlusTierTag(quote.tier, large = true)
        Text(
            "${actionLabel(quote.action)} · ${if (quote.period == "year") "年付" else "月付"} ${quote.days} 天",
            color = k.ink, fontSize = 15.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 10.dp),
        )
    }

    // 权益变化：只列变了的项
    val diffs = if (target != null && before != null) {
        val from = perksOf(before, showTemp)
        perksOf(target, showTemp).mapIndexedNotNull { index, perk -> from.getOrNull(index)?.takeIf { it.value != perk.value }?.let { perk to it } }
    } else emptyList()
    if (diffs.isNotEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, k.line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            diffs.forEach { (perk, from) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KcIcon(perk.icon, tint = k.faint, size = 14.dp)
                    Text(perk.label, color = k.sub, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Text(from.value, color = k.faint, fontSize = 12.5.sp, textDecoration = TextDecoration.LineThrough, maxLines = 1)
                    KcIcon(KcIcons.chevron, tint = k.faint, size = 12.dp, modifier = Modifier.padding(horizontal = 4.dp))
                    Text(perk.value, color = k.ink, fontSize = 12.5.sp, fontWeight = FontWeight.W600, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.widthIn(min = 64.dp))
                }
            }
        }
    } else {
        PlusNote("档位权益不变，有效期会顺延。")
    }

    // 账单
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        if (quote.action == "renew") BillRow("原到期时间", plusTime(quote.currentExpiresAt))
        else BillRow("当前档位", if (quote.currentTier == "free") "免费版" else PlusTiers.label(quote.currentTier))
        BillRow("新的到期时间", plusTime(quote.newExpiresAt))
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("将扣除", color = k.ink, fontSize = 14.sp, fontWeight = FontWeight.W500, modifier = Modifier.weight(1f))
            CoinMark(16.dp)
            Text(formatCoins(quote.price), color = k.ink, fontSize = 18.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.02).em, modifier = Modifier.padding(start = 6.dp))
        }
        BillRow("现在的余额", formatCoins(quote.balance))
        BillRow("买完剩下", if (after < 0) "不够，还差 ${formatCoins(-after)}" else formatCoins(after), bad = after < 0)
    }

    // 升级会作废剩余时间：勾选确认
    if (quote.forfeitedSeconds > 0) {
        val border by animateColorAsState(if (confirmForfeit) k.lineStrong else k.warn.copy(alpha = 0.45f), tween(160), label = "forfeitBorder")
        val bg by animateColorAsState(if (confirmForfeit) k.wash else k.warn.copy(alpha = 0.08f), tween(160), label = "forfeitBg")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, border, RoundedCornerShape(14.dp))
                .clickable { onForfeit(!confirmForfeit) }.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            CheckBox(confirmForfeit, Modifier.padding(top = 1.dp))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    quote.forfeitNotice?.takeIf { it.isNotBlank() } ?: "升级后原来 ${PlusTiers.label(quote.currentTier)} 剩余的 ${quote.forfeitedDays} 天将作废",
                    color = k.ink, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.W600,
                )
                Text("勾选表示我知道这部分时间不会折算，也不退款。", color = k.sub, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }

    notice?.let { (tone, text) -> PlusNote(text, tone = tone) }

    if (short && !unbound) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.warn.copy(alpha = 0.08f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                (if (missing > 0) "金币不够，还差 ${formatCoins(missing)} 个。" else "金币不够。") +
                    if (donated) "投完了吗？回来点「刷新余额」。" else "去 KukeChat 官方作品页投币，回来点「刷新余额」。",
                color = k.warn, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlusButton(if (refreshing) "核实中…" else "刷新余额", onClick = onRefresh, primary = false, height = 36.dp, enabled = !refreshing, modifier = Modifier.weight(1f))
                PlusButton(donateLabel, onClick = onDonate, height = 36.dp, modifier = Modifier.weight(1f))
            }
        }
    }
    if (unbound) PlusNote("还没有绑定 CCW 账号，绑定之后才能用金币购买。", tone = NoteTone.Warn)

    Text(
        "群升级是花掉金币换来的，购买后不退款；到期后群里已有的内容不会被删除，只是回到免费档的上限（群文件有 ${plans.fileGraceDays} 天宽限期）。",
        color = k.faint, fontSize = 12.sp, lineHeight = 18.sp,
    )
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun BillRow(label: String, value: String, bad: Boolean = false) {
    val k = plusInk()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = k.sub, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (bad) k.bad else k.ink, fontSize = 13.5.sp, fontWeight = FontWeight.W500)
    }
}

/** 方形勾选框：没勾是细线框，勾上是墨色实底 + 勾 */
@Composable
private fun CheckBox(on: Boolean, modifier: Modifier = Modifier) {
    val k = plusInk()
    val fill by animateColorAsState(if (on) k.ink else Color.Transparent, tween(140), label = "check")
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier.size(18.dp).clip(shape).background(fill).border(1.5.dp, if (on) Color.Transparent else k.lineStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) KcIcon(KcIcons.check, tint = k.onInk, size = 12.dp)
    }
}

/**
 * 购买成功：克制的庆祝 —— 墨色圆章弹簧放大 + 一圈淡出的光晕（同电脑版 kgp-done），震动在提交成功时已触发。
 * 系统关了动画时直接静态显示。点「完成」或空白处关闭。
 */
@Composable
internal fun PurchaseSuccessOverlay(info: PlusInfo, action: String, onDismiss: () -> Unit) {
    val k = plusInk()
    val colors = KcTheme.colors
    val reduced = rememberReducedMotion()
    val tier = PlusTiers.effective(info.tier, info.expiresAt)
    val pop = remember { Animatable(if (reduced) 1f else 0.4f) }
    val ring = remember { Animatable(if (reduced) 1f else 0f) }
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    BackHandler(onBack = onDismiss)
    if (!reduced) {
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(180)) }
        LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f)) }
        LaunchedEffect(Unit) {
            delay(120)
            ring.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }.background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 32.dp).widthIn(max = 360.dp).fillMaxWidth()
                .graphicsLayer { val s = 0.96f + 0.04f * appear.value; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(24.dp)).background(colors.panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
                // 光晕：从圆章大小扩散开并淡出
                Box(
                    Modifier.size(60.dp).graphicsLayer {
                        val s = 1f + 0.73f * ring.value
                        scaleX = s; scaleY = s
                        alpha = (1f - ring.value) * 0.3f
                    }.background(k.ink, CircleShape),
                )
                Box(
                    Modifier.size(60.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                        .background(k.ink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tier == "max") CrownMark(k.onInk, 26.dp) else KcIcon(KcIcons.check, tint = k.onInk, size = 28.dp)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (action == "renew") "已续期" else "已升级为", color = k.ink, fontSize = 18.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.015).em)
                if (tier != "free") PlusTierTag(tier, Modifier.padding(start = 8.dp), large = true)
            }
            if (info.expiresAt != null) {
                Text("有效期至 ${plusTime(info.expiresAt)}", color = k.sub, fontSize = 13.5.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Text(
                "群成员、云端消息和精华的上限已经生效。",
                color = k.sub, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            PlusButton("完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
        }
    }
}
