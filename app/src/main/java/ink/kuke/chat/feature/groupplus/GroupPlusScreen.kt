package ink.kuke.chat.feature.groupplus

import ink.kuke.chat.core.data.slot

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.settings.LoadBox
import ink.kuke.chat.feature.settings.rememberLoader
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.components.softShadow
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.GroupFilesRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 档位卡点「购买」后要确认的东西：选的档位、周期 */
internal data class PurchaseIntent(val tier: String, val period: String)

/** 购买成功后庆祝用：新的档位信息 + 这次是开通 / 续期 / 升级 */
internal data class PurchaseDone(val info: PlusInfo, val action: String)

/**
 * 群升级（Pro / Pro+ / MAX），视觉同电脑版 UpgradePanel：
 * 大标题 + 群名 · 浅灰条带里的当前档位与三条细用量 · 选择档位 + 月付 / 年付 · 横滑的档位卡（选中的居中）·
 * 临时文件说明 · 免费版基线 · 一句提示 · 底部合计 + 墨色主按钮。顶栏右边是「购买记录」。
 * 点主按钮先拿报价（不扣币），在确认面板里看清权益变化、新到期时间和扣费，再提交购买。
 * 非群主 / 管理员只能看档位信息。
 */
@Composable
fun GroupPlusScreen(graph: AppGraph, conversationId: Long, reason: String? = null) {
    val nav = LocalAppNav.current
    val api = remember(graph) { GroupPlusApi(graph) }
    // 结果为 null = 服务端还没有群升级（404），显示「暂未开放」而不是报错重试
    val loader = rememberLoader(conversationId, cache = graph.screenCache.slot<Pair<PlusPlans, PlusInfo>?>("groupplus.$conversationId")) {
        try {
            val plans = api.plans()
            val info = api.info(conversationId)
            plans to info
        } catch (error: ink.kuke.chat.core.net.ApiException) {
            if (error.status == 404) null else throw error
        }
    }
    val conversations by graph.conversations.items.collectAsState()
    val groupName = conversations.firstOrNull { it.id == conversationId }?.name() ?: "群聊"
    var period by remember { mutableStateOf("month") }
    var intent by remember { mutableStateOf<PurchaseIntent?>(null) }
    var celebrate by remember { mutableStateOf<PurchaseDone?>(null) }
    var showRecords by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // 档位变化（别的管理员买了 / 后台赠送或取消）：重新读
    LaunchedEffect(conversationId) {
        graph.realtime.events.collect { event ->
            if (event.type == "group.plus.updated" && (event.data["conversation_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() == conversationId) {
                loader.load()
            }
        }
    }

    val data = loader.data
    val canSeeRecords = data?.second?.canPurchase == true
    // 大标题滚出去之后，顶栏才出现小标题和分隔线
    val scrolled = with(LocalDensity.current) { scroll.value > 56.dp.toPx() }

    if (showRecords) BackHandler { showRecords = false }

    PlusPage(
        title = if (showRecords) "购买记录" else "群升级",
        showTitle = showRecords || scrolled,
        onBack = { if (showRecords) showRecords = false else nav.back() },
        action = if (!showRecords && canSeeRecords) ({ PlusTextAction("购买记录") { showRecords = true } }) else null,
    ) {
        if (showRecords) {
            RecordsView(api, conversationId)
            return@PlusPage
        }
        Box(Modifier.fillMaxSize()) {
            LoadBox(loader) { result ->
                if (result == null) {
                    PlusUnavailable()
                    return@LoadBox
                }
                val (plans, info) = result
                PlusBody(
                    plans = plans,
                    info = info,
                    groupName = groupName,
                    reason = reason,
                    period = period,
                    scroll = scroll,
                    onPeriod = { period = it },
                    onBuy = { tier -> intent = PurchaseIntent(tier, period) },
                    onOpenFiles = { nav.go(GroupFilesRoute(conversationId)) },
                )
            }
            intent?.let { current ->
                val plans = data?.first
                val info = data?.second
                if (plans != null && info != null) {
                    PurchaseSheet(
                        api = api,
                        conversationId = conversationId,
                        plans = plans,
                        intent = current,
                        onDismiss = { intent = null },
                        onGoShop = {
                            intent = null
                            nav.go(ink.kuke.chat.ui.shell.ShopRoute)
                        },
                        onBind = {
                            intent = null
                            nav.go(ink.kuke.chat.ui.shell.SettingsPageRoute("security"))
                        },
                        onPurchased = { updated, action ->
                            intent = null
                            celebrate = PurchaseDone(updated, action)
                            loader.load()
                            graph.conversations.refreshSoon(0)
                        },
                    )
                }
            }
            celebrate?.let { PurchaseSuccessOverlay(it.info, it.action) { celebrate = null } }
        }
    }
}

// ------------------------------------------------------------------ 页面骨架

/** 整页白底（同电脑版面板）；顶栏不画分隔线，内容滚过大标题后才出现小标题和一道细线 */
@Composable
private fun PlusPage(title: String, showTitle: Boolean, onBack: () -> Unit, action: (@Composable () -> Unit)?, content: @Composable () -> Unit) {
    val colors = KcTheme.colors
    val k = plusInk()
    val titleAlpha by animateFloatAsState(if (showTitle) 1f else 0f, tween(160), label = "plusTitle")
    Column(Modifier.fillMaxSize().background(colors.panel)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val press = rememberInteraction()
            Box(
                Modifier.size(44.dp).pressScale(press, 0.9f).clip(CircleShape).clickable(interactionSource = press, indication = null, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.chevronLeft, tint = k.ink, size = 24.dp, contentDescription = "返回") }
            Text(
                title, style = KcTheme.type.title, color = k.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(titleAlpha),
            )
            action?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).alpha(titleAlpha).background(k.line))
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun PlusTextAction(text: String, onClick: () -> Unit) {
    val k = plusInk()
    Text(
        text,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.W500,
        color = k.sub,
        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ------------------------------------------------------------------ 选档位

@Composable
private fun PlusBody(
    plans: PlusPlans,
    info: PlusInfo,
    groupName: String,
    reason: String?,
    period: String,
    scroll: ScrollState,
    onPeriod: (String) -> Unit,
    onBuy: (String) -> Unit,
    onOpenFiles: () -> Unit,
) {
    val k = plusInk()
    val scope = rememberCoroutineScope()
    val paid = remember(plans) { plans.tiers.filter { it.key != "free" }.sortedBy { it.rank } }
    val freePlan = plans.tiers.firstOrNull { it.key == "free" }
    val currentTier = PlusTiers.effective(info.tier, info.expiresAt)
    val currentRank = PlusTiers.rank(currentTier)
    val showTemp = plans.tiers.any { it.fileTempMaxBytes != null }
    // 默认选中：比当前高的第一档；已经是最高档就停在它自己上（续期）
    val defaultPage = (paid.indexOfFirst { PlusTiers.rank(it.key) > currentRank }.takeIf { it >= 0 } ?: paid.lastIndex).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = defaultPage) { paid.size }
    val selected = paid.getOrNull(pager.currentPage)
    val selectedRank = PlusTiers.rank(selected?.key)
    // 永久档位（后台给的）：服务端不卖（409 already_permanent），这里也不给购买按钮
    val permanent = PlusTiers.isPermanent(currentTier, info.expiresAt, info.permanent)
    val canBuy = info.canPurchase && plans.enabled && !permanent
    val downgrade = selected != null && currentTier != "free" && selectedRank < currentRank
    val yearly = period == "year"

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).then(if (canBuy) Modifier else Modifier.navigationBarsPadding())) {
            // 大标题 + 群名
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 18.dp)) {
                Text("群升级", color = k.ink, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.025).em)
                Text(groupName, color = k.sub, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }

            StatusStrip(info, currentTier, onOpenFiles)

            upgradeReasonText(reason)?.let { PlusNote(it, icon = KcIcons.info, strong = true, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) }
            if (permanent) {
                PlusNote("这个群已经是永久 ${PlusTiers.label(currentTier)}，不会到期，也不用再购买。下面是各档位的权益。", icon = KcIcons.info, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
            } else if (!plans.enabled) {
                PlusNote("群升级暂时停止购买，已有的档位照常生效。", tone = NoteTone.Warn, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
            } else if (!info.canPurchase) {
                PlusNote("只有群主和管理员可以购买群升级，你可以在这里查看各档位的权益。", modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
            }

            // 选择档位 + 月付 / 年付
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选择档位", color = k.ink, fontSize = 16.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.01).em, modifier = Modifier.weight(1f))
                PeriodSwitch(period, plans, onPeriod)
            }

            if (paid.isNotEmpty()) {
                // 横滑：卡片占屏宽约八成，选中的居中，左右露出相邻的一截
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = 36.dp),
                    pageSpacing = 12.dp,
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { page ->
                    val spec = paid[page]
                    // 上下留出阴影的位置（Pager 会裁掉自己边界外的东西）
                    Box(Modifier.padding(top = 4.dp, bottom = 26.dp)) {
                        TierCard(
                            spec = spec,
                            plans = plans,
                            period = period,
                            showTemp = showTemp,
                            current = spec.key == currentTier,
                            selected = page == pager.currentPage,
                            onClick = { scope.launch { pager.animateScrollToPage(page) } },
                        )
                    }
                }
            }

            if (showTemp) {
                Row(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(k.wash).padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    KcIcon(KcIcons.clock, tint = k.ink, size = 14.dp, modifier = Modifier.padding(top = 3.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = k.ink, fontWeight = FontWeight.W600)) { append("临时文件 ") }
                            append("保存 ${plans.fileTempTtlHours} 小时，不占群空间。Pro 群的文件都是临时文件；Pro+ 和 MAX 的群空间用完后，也能继续传临时文件。")
                        },
                        color = k.sub, fontSize = 12.5.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            freePlan?.let {
                Text(
                    "免费版：${formatLimit(it.memberLimit)} 人 · ${formatLimit(it.messageLimit)} 条云端消息 · 精华 ${formatLimit(it.featuredLimit)} 条 · 不含群文件",
                    color = k.faint, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                )
            }
            // 卡片下面最多一条提示：不能降级 > 续期 / 升级说明
            val hint: Pair<NoteTone, String>? = when {
                !canBuy || selected == null -> null
                downgrade -> NoteTone.Warn to "当前 ${PlusTiers.label(currentTier)} 还没到期，到期之前不能购买更低的档位。"
                currentTier != "free" && selected.key == currentTier -> NoteTone.Info to "购买同档位是续期：在原来的到期时间上顺延。"
                currentTier != "free" && selectedRank > currentRank -> NoteTone.Info to "升级立即生效，原档位剩余的时间会作废；确认前会告诉你具体天数。"
                else -> null
            }
            hint?.let { (tone, text) ->
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
                    val tint = when (tone) { NoteTone.Warn -> k.warn; NoteTone.Danger -> k.bad; NoteTone.Info -> k.sub }
                    KcIcon(if (tone == NoteTone.Info) KcIcons.info else KcIcons.alertCircle, tint = tint, size = 14.dp, modifier = Modifier.padding(top = 2.5.dp))
                    Text(text, color = tint, fontSize = 12.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 7.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        // 底部：合计 + 主按钮（全宽）
        if (canBuy && selected != null) {
            val price = if (yearly) selected.priceYear else selected.priceMonth
            val label = when {
                currentTier == "free" -> "开通 ${PlusTiers.label(selected.key)}"
                selected.key == currentTier -> "续期 ${PlusTiers.label(selected.key)}"
                else -> "升级到 ${PlusTiers.label(selected.key)}"
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${PlusTiers.label(selected.key)} · ${if (yearly) "年付" else "月付"}", color = k.sub, fontSize = 12.sp, lineHeight = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoinMark(16.dp)
                        Text(
                            formatCoins(price), color = k.ink, fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.02).em,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                PlusButton(label, onClick = { onBuy(selected.key) }, modifier = Modifier.weight(1f), enabled = !downgrade)
            }
        }
    }
}

// ------------------------------------------------------------------ 来意说明 / 暂未开放

/** 从「某项到了上限」跳进来时顶部那句说明（同客户端 UpgradePanel 的 REASON_TEXT）；手动进来没有 */
internal fun upgradeReasonText(reason: String?): String? = when (reason) {
    "members" -> "群人数已经满了：升级后可以容纳更多成员。"
    "featured" -> "精华消息已经达到上限：升级后可以保存更多精华。"
    "files" -> "群文件需要 Pro 及以上的档位才能使用。"
    "messages" -> "升级后，云端会保留更多聊天记录。"
    else -> null
}

/** 服务端还没有群升级：友好的空状态，不给重试按钮 */
@Composable
private fun PlusUnavailable() {
    val k = plusInk()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(k.wash2), contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.sparkles, tint = k.ink, size = 28.dp)
        }
        Text("群升级暂未开放", color = k.ink, fontSize = 18.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(top = 18.dp))
        Text(
            "更大的群、群文件和更长的云端记录正在路上，敬请期待。",
            color = k.sub, fontSize = 13.5.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ------------------------------------------------------------------ 当前档位 + 用量

@Composable
private fun StatusStrip(info: PlusInfo, tier: String, onOpenFiles: () -> Unit) {
    val k = plusInk()
    val limits = info.limits
    val usage = info.usage
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.wash).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当前", color = k.sub, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            if (tier == "free") Text("免费版", color = k.ink, fontSize = 13.sp, fontWeight = FontWeight.W600)
            else PlusTierTag(tier, large = true)
            Spacer(Modifier.width(10.dp))
            val permanent = PlusTiers.isPermanent(tier, info.expiresAt, info.permanent)
            val remain = if (permanent) null else remainingText(info.expiresAt)
            Text(
                buildAnnotatedString {
                    when {
                        tier == "free" -> append("升级后立即生效")
                        permanent -> withStyle(SpanStyle(color = k.ink, fontWeight = FontWeight.W600)) { append("永久 · 不会到期") }
                        info.expiresAt == null -> append("生效中")
                        else -> {
                            if (remain != null) {
                                append("还剩 ")
                                withStyle(SpanStyle(color = k.ink, fontWeight = FontWeight.W600)) { append(remain) }
                                append(" · ")
                            }
                            append("${plusTime(info.expiresAt)} 到期")
                        }
                    }
                },
                color = k.sub, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // 人数不限（后台单独设的）：只写人数，进度条留空
            Meter("成员", limits.memberText(usage.memberCount) { formatLimit(it) }, usage.memberCount.toLong(), if (limits.unlimitedMembers) 0 else (limits.memberLimit ?: 0).toLong(), Modifier.weight(1f))
            Meter("精华", "${formatLimit(usage.featuredCount)} / ${formatLimit(limits.featuredLimit)}", usage.featuredCount.toLong(), limits.featuredLimit.toLong(), Modifier.weight(1f))
            val quota = limits.fileQuotaBytes
            val filesModifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenFiles)
            when {
                limits.fileEnabled && quota != null && quota > 0 ->
                    Meter("群文件", "${formatBytes(usage.fileBytesUsed)} / ${formatBytes(quota)}", usage.fileBytesUsed, quota, filesModifier)
                limits.fileEnabled -> Meter("群文件", "仅临时文件", 0, 0, filesModifier, muted = true)
                else -> Meter("群文件", "未解锁", 0, 0, filesModifier, muted = true, locked = true)
            }
        }
        // 档位过期后的宽限期：文件还在，只能下载 / 删除，不能上传
        if (info.files.state == "grace") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "群文件宽限期：${info.files.fileCount} 个文件（${formatBytes(info.files.usedBytes)}），" +
                        (info.files.graceUntil?.let { "${plusTime(it)} 前可下载、删除，不能上传；" } ?: "") + "续费后恢复",
                    color = k.warn, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f),
                )
                Text(
                    "管理", color = k.ink, fontSize = 12.5.sp, fontWeight = FontWeight.W600,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).clickable(onClick = onOpenFiles).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 一项用量：名字、数字、一条 3dp 的细进度条（75% 变琥珀、90% 变红） */
@Composable
private fun Meter(label: String, value: String, used: Long, limit: Long, modifier: Modifier, muted: Boolean = false, locked: Boolean = false) {
    val k = plusInk()
    val ratio = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val shown = if (used > 0) maxOf(ratio, 0.03f) else 0f
    val animated by animateFloatAsState(shown, spring(dampingRatio = 1f, stiffness = 120f), label = "meter")
    val tone = when {
        ratio >= 0.9f -> k.bad
        ratio >= 0.75f -> k.warn
        else -> k.ink
    }
    Column(modifier) {
        Text(label, color = k.sub, fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 1)
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (locked) KcIcon(KcIcons.lock, tint = k.faint, size = 11.dp, modifier = Modifier.padding(end = 3.dp))
            Text(
                value,
                color = if (muted) k.faint else k.ink,
                fontSize = 12.5.sp, lineHeight = 16.sp,
                fontWeight = if (muted) FontWeight.W500 else FontWeight.W600,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(k.wash2)) {
            Box(Modifier.fillMaxWidth(animated).height(3.dp).clip(RoundedCornerShape(50)).background(tone))
        }
    }
}

// ------------------------------------------------------------------ 月付 / 年付

@Composable
private fun PeriodSwitch(period: String, plans: PlusPlans, onChange: (String) -> Unit) {
    val k = plusInk()
    val colors = KcTheme.colors
    val months = maxOf(1, Math.round(plans.yearDays.toFloat() / plans.monthDays.coerceAtLeast(1)))
    val saved = (months - plans.yearMonthsPaid).coerceAtLeast(0)
    val pill = RoundedCornerShape(50)
    Row(Modifier.clip(pill).background(k.wash2).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("month" to "月付", "year" to "年付").forEach { (key, label) ->
            val on = period == key
            val bg by animateColorAsState(
                if (!on) Color.Transparent else if (k.dark) k.ink.copy(alpha = 0.14f) else colors.panel,
                tween(160), label = "period",
            )
            Row(
                Modifier
                    .height(30.dp)
                    .shadow(if (on && !k.dark) 1.5.dp else 0.dp, pill, clip = false)
                    .clip(pill)
                    .background(bg)
                    .clickable { onChange(key) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = if (on) k.ink else k.sub, fontSize = 13.sp, fontWeight = if (on) FontWeight.W600 else FontWeight.W500)
                if (key == "year" && saved > 0) {
                    Text("省 $saved 个月", color = if (on) k.ink else k.sub, fontSize = 11.sp, fontWeight = FontWeight.W500, modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 档位卡

/** 卡片上档位名下面的一句话 */
private fun tierLine(key: String): String = when (key) {
    "pro" -> "更大的群，更久的聊天记录"
    "pro_plus" -> "专属群空间，资料长久保存"
    "max" -> "为大型社区打造的旗舰档位"
    else -> ""
}

@Composable
private fun TierCard(
    spec: PlusTierSpec,
    plans: PlusPlans,
    period: String,
    showTemp: Boolean,
    current: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val k = plusInk()
    val colors = KcTheme.colors
    val yearly = period == "year"
    val months = maxOf(1, Math.round(plans.yearDays.toFloat() / plans.monthDays.coerceAtLeast(1)))
    val price = if (yearly) spec.priceYear else spec.priceMonth
    val shape = RoundedCornerShape(18.dp)
    // 选中：1.5dp 墨色描边 + 一道又长又淡的投影；没选：1dp 细线
    val stroke by animateColorAsState(if (selected) k.ink else k.line, tween(200), label = "cardStroke")
    val strokeWidth by animateDpAsState(if (selected) 1.5.dp else 1.dp, tween(200), label = "cardStrokeWidth")
    val lift by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "cardLift")
    val press = rememberInteraction()
    Column(
        Modifier
            .fillMaxWidth()
            .softShadow(blur = 14.dp, offsetY = 16.dp, color = Color.Black.copy(alpha = (if (k.dark) 0.3f else 0.11f) * lift), corner = 18.dp)
            .clip(shape)
            .background(colors.panel)
            .background(if (k.dark) k.ink.copy(alpha = 0.025f) else Color.Transparent)
            .border(strokeWidth, stroke, shape)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(start = 22.dp, end = 20.dp, top = 22.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spec.key == "max") CrownMark(k.ink, 15.dp, Modifier.padding(end = 6.dp))
            Text(PlusTiers.label(spec.key), color = k.ink, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.015).em)
            if (current) {
                Text(
                    "当前", color = k.sub, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.W500,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(k.wash2).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Radio(selected)
        }
        Text(tierLine(spec.key), color = k.sub, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))

        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            CoinMark(18.dp)
            Text(
                formatCoins(price), color = k.ink, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.W600, letterSpacing = (-0.035).em,
                modifier = Modifier.padding(start = 7.dp),
            )
            Text(
                if (yearly) "/ 年" else "/ 月", color = k.sub, fontSize = 12.5.sp,
                modifier = Modifier.align(Alignment.Bottom).padding(start = 6.dp, bottom = 5.dp),
            )
        }
        // 月付时提示年付的总价，年付时折算到每月：两种周期下这一行都有字，切换时卡片高度不跳
        Text(
            if (yearly) "折合每月 ${formatCoins(Math.round(spec.priceYear.toDouble() / months))} 币" else "年付 ${formatCoins(spec.priceYear)} 币",
            color = k.sub, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp),
        )

        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(1.dp).background(k.line))
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            perksOf(spec, showTemp).forEach { PerkRow(it) }
        }
    }
}

/** 右上角的单选圈：没选是空心圈，选中是墨色实心 + 勾 */
@Composable
private fun Radio(on: Boolean) {
    val k = plusInk()
    val fill by animateColorAsState(if (on) k.ink else Color.Transparent, tween(180), label = "radio")
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(fill).border(1.5.dp, if (on) Color.Transparent else k.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) KcIcon(KcIcons.check, tint = k.onInk, size = 12.dp)
    }
}

@Composable
private fun PerkRow(perk: PlusPerk) {
    val k = plusInk()
    Row(verticalAlignment = Alignment.CenterVertically) {
        KcIcon(if (perk.off) KcIcons.lock else perk.icon, tint = k.faint, size = 15.dp)
        Text(perk.label, color = k.sub, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 10.dp))
        Text(
            perk.value,
            color = if (perk.off) k.faint else k.ink,
            fontSize = 13.sp,
            fontWeight = if (perk.off) FontWeight.W400 else FontWeight.W600,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ 购买记录

@Composable
private fun RecordsView(api: GroupPlusApi, conversationId: Long) {
    val k = plusInk()
    val pageSize = 10
    var offset by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<PlusRecordsPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conversationId, offset, attempt) {
        error = null
        runCatching { api.records(conversationId, pageSize, offset) }.onSuccess { page = it }.onFailure { error = it.userMessage() }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val current = page
            when {
                error != null && current == null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = k.bad, fontSize = 13.5.sp, textAlign = TextAlign.Center)
                    PlusButton("重试", onClick = { attempt++ }, primary = false, height = 36.dp, modifier = Modifier.padding(top = 14.dp))
                }
                current == null -> Text("加载中…", color = k.sub, fontSize = 13.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp))
                current.items.isEmpty() -> Text("还没有购买记录。", color = k.sub, fontSize = 13.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp))
                else -> current.items.forEachIndexed { index, record ->
                    RecordRow(record)
                    if (index < current.items.lastIndex) Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(1.dp).background(k.line))
                }
            }
            if (error != null && page != null) Text(error!!, color = k.bad, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
        val current = page
        if (current != null && current.total > pageSize) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${offset + 1}–${minOf(offset + pageSize, current.total)} / ${current.total}", color = k.sub, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                PlusButton("上一页", onClick = { offset = maxOf(0, offset - pageSize) }, primary = false, height = 34.dp, enabled = offset > 0)
                PlusButton("下一页", onClick = { offset += pageSize }, primary = false, height = 34.dp, enabled = offset + pageSize < current.total)
            }
        }
    }
}

@Composable
private fun RecordRow(record: PlusRecord) {
    val k = plusInk()
    val action = when (record.source) {
        "grant" -> "后台赠送"
        "revoke" -> "后台取消"
        else -> when (record.action) {
            "renew" -> "续期"
            "upgrade" -> "升级"
            "grant" -> "赠送"
            "revoke" -> "取消"
            else -> "开通"
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tier = record.newTier ?: record.tier
                if (!tier.isNullOrBlank() && tier != "free") PlusTierTag(tier, Modifier.padding(end = 8.dp), large = true)
                Text(action, color = k.ink, fontSize = 15.sp, fontWeight = FontWeight.W600)
                record.period?.let { Text(if (it == "year") "年付" else "月付", color = k.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
            }
            Text(
                "${record.user?.displayName ?: "后台"} · ${plusTime(record.createdAt)}",
                color = k.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (record.amount > 0) {
            CoinMark(14.dp)
            Text(formatCoins(record.amount), color = k.ink, fontSize = 15.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 5.dp))
        } else {
            Text("免费", color = k.sub, fontSize = 13.sp)
        }
    }
}

// ------------------------------------------------------------------ 共用小件（确认面板也用）

/** 一项权益：图标、名字、值；off = 这个档位上「没有」（置灰、换成锁） */
internal data class PlusPerk(val icon: ImageVector, val label: String, val value: String, val off: Boolean = false)

/** 群文件这一项：有空间写空间大小；解锁了但没有空间（Pro）是只能传 24 小时的临时文件 */
private fun fileValue(spec: PlusTierSpec): String = when {
    !spec.fileEnabled -> "未解锁"
    (spec.fileQuotaBytes ?: 0) > 0 -> "${formatBytes(spec.fileQuotaBytes!!)} 空间"
    else -> "临时文件"
}

/** showTemp：服务端给了 file_temp_max_bytes 才画「临时文件」那一行（旧服务端没有这个字段） */
internal fun perksOf(spec: PlusTierSpec, showTemp: Boolean): List<PlusPerk> = buildList {
    add(PlusPerk(KcIcons.users, "群成员上限", "${formatLimit(spec.memberLimit)} 人"))
    add(PlusPerk(KcIcons.message, "云端消息", "${formatLimit(spec.messageLimit)} 条"))
    add(PlusPerk(KcIcons.folder, "群文件", fileValue(spec), off = !spec.fileEnabled))
    if (showTemp) {
        val temp = spec.fileTempMaxBytes?.takeIf { it > 0 }
        add(PlusPerk(KcIcons.clock, "临时文件", temp?.let { "单个 ${formatBytes(it)}" } ?: "—", off = temp == null))
    }
    add(PlusPerk(KcIcons.star, "精华上限", "${formatLimit(spec.featuredLimit)} 条"))
}

internal enum class NoteTone { Info, Warn, Danger }

/** 浅灰底的一句说明；warn / danger 换成对应色的淡底 */
@Composable
internal fun PlusNote(text: String, modifier: Modifier = Modifier, tone: NoteTone = NoteTone.Info, icon: ImageVector? = null, strong: Boolean = false) {
    val k = plusInk()
    val (bg, fg) = when (tone) {
        NoteTone.Warn -> k.warn.copy(alpha = 0.1f) to k.warn
        NoteTone.Danger -> k.bad.copy(alpha = 0.09f) to k.bad
        NoteTone.Info -> k.wash to (if (strong) k.ink else k.sub)
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) KcIcon(icon, tint = fg, size = 16.dp, modifier = Modifier.padding(end = 8.dp))
        Text(text, color = fg, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = if (strong) FontWeight.W500 else FontWeight.W400)
    }
}

/** 墨色胶囊主按钮（深色主题下是近白底深字）；primary = false 是浅灰胶囊 */
@Composable
internal fun PlusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    height: Dp = 46.dp,
) {
    val k = plusInk()
    val press = rememberInteraction()
    Box(
        modifier
            .height(height)
            .widthIn(min = 64.dp)
            .pressScale(press, 0.98f)
            .alpha(if (enabled) 1f else if (primary) 0.28f else 0.5f)
            .clip(RoundedCornerShape(50))
            .background(if (primary) k.ink else k.wash2)
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) k.onInk else k.ink,
            fontSize = if (height >= 44.dp) 15.sp else 13.5.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 0.01.em,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ 格式

internal fun formatCoins(value: Long): String = "%,d".format(value)

/** 1000 以内照写；整万写成「2 万」（同电脑版 formatLimit） */
internal fun formatLimit(value: Int): String =
    if (value >= 10_000 && value % 1000 == 0) {
        val wan = value / 10_000.0
        (if (wan % 1.0 == 0.0) "${wan.toInt()}" else "%.1f".format(wan)) + " 万"
    } else "%,d".format(value)

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble()).replace(".0 ", " ")
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble()).replace(".0 ", " ")
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val PlusTimeFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

/** 2026/10/28 16:17（本地时区），同电脑版 */
internal fun plusTime(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(PlusTimeFormat).orEmpty()

/** 还剩多久：不到一小时按分钟，不到两天按小时，其余按天（向上取整）；已过期 / 没有到期时间为 null */
internal fun remainingText(until: String?): String? {
    val end = parseInstant(until) ?: return null
    val millis = Duration.between(Instant.now(), end).toMillis()
    if (millis <= 0) return null
    val minutes = (millis + 59_999) / 60_000
    if (minutes < 60) return "$minutes 分钟"
    val hours = (millis + 3_599_999) / 3_600_000
    if (hours < 48) return "$hours 小时"
    return "${(millis + 86_399_999) / 86_400_000} 天"
}
