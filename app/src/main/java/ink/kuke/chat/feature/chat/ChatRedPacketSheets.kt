package ink.kuke.chat.feature.chat

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.RedPacketRecordsRoute
import ink.kuke.chat.ui.shell.SettingsPageRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 配色（红包自带的红与金，不随主题色变）

internal object RpColors {
    val Red = Color(0xFFE8473A)
    val RedDeep = Color(0xFFC62F24)
    val RedDark = Color(0xFFA9241B)
    val FlapTop = Color(0xFFF2604B)
    val FlapBottom = Color(0xFFE04A3A)
    val Gold = Color(0xFFFFD98A)
    val GoldLight = Color(0xFFFFF0C2)
    val GoldDeep = Color(0xFFE8A93A)
    val Ink = Color(0xFF7A2A0C)
    /** 金额数字：浅色主题偏深金，深色主题偏亮金 */
    @Composable
    fun amount(): Color = if (KcTheme.colors.isDark) Color(0xFFF5C86A) else Color(0xFFC9801A)
}

/** 金币数字加千分位 */
internal fun coins(value: Int): String = "%,d".format(value)

private val dayTime = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val clockOnly = DateTimeFormatter.ofPattern("HH:mm")

/** 红包里的时间：今天只写时分，其它写月日时分 */
internal fun rpTime(iso: String?): String {
    val time = parseInstant(iso)?.atZone(ZoneId.systemDefault()) ?: return ""
    return if (time.toLocalDate() == LocalDate.now()) time.format(clockOnly) else time.format(dayTime)
}

/** 结束状态对应的整句（红包正面 / 详情里用） */
internal fun endedSentence(status: String?): String = when (status) {
    "finished" -> "手慢了，红包派完了"
    "expired" -> "红包已过期"
    "revoked" -> "红包已被撤回"
    else -> ""
}

// ------------------------------------------------------------------ 数据：查 / 领

/** 领红包的结果：领到 / 结束了 / 其实领过（或私聊里自己发的）/ 失败原因 */
internal sealed interface ClaimOutcome {
    data class Claimed(val amount: Int) : ClaimOutcome
    data class Ended(val status: String) : ClaimOutcome
    data object Already : ClaimOutcome
    data class Failed(val message: String) : ClaimOutcome
}

/** 红包读写：聊天页用 ChatController.redPackets 当缓存（卡片跟着变），记录页自带一份 */
internal class RedPacketSource(
    private val graph: AppGraph,
    val cache: SnapshotStateMap<Long, RedPacketState>,
    /** 领到了 / 确认领不了（抢完、过期、撤回、领过）：聊天页用它摘掉「N 个红包未领」里的这一个 */
    private val onSettled: (Long) -> Unit = {},
) {
    /** 拉最新状态；失败返回给人看的原因 */
    suspend fun load(id: Long): String? = try {
        graph.api.get("/red-packets/$id").asObj()?.let(::parseRedPacket)?.let { cache[id] = it }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    /** POST /red-packets/{id}/claim。领到或确认领不了之后让会话列表重拉，「[有红包]」跟着摘掉 */
    suspend fun claim(id: Long): ClaimOutcome = try {
        val result = graph.api.post("/red-packets/$id/claim").asObj()
        result?.o("red_packet")?.let(::parseRedPacket)?.let { cache[id] = it }
        graph.conversations.refreshSoon()
        onSettled(id)
        ClaimOutcome.Claimed(result?.i("amount") ?: 0)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        when (e.code) {
            "finished", "expired", "revoked" -> {
                load(id)
                graph.conversations.refreshSoon()
                onSettled(id)
                ClaimOutcome.Ended(e.code)
            }
            "already_claimed", "own_packet" -> {
                load(id)
                onSettled(id)
                ClaimOutcome.Already
            }
            "not_member" -> ClaimOutcome.Failed("你不在这个会话里，领不了这个红包")
            "not_found" -> ClaimOutcome.Failed("这个红包不存在了")
            "muted" -> ClaimOutcome.Failed("你的账号正在禁言中，不能领红包")
            else -> ClaimOutcome.Failed(e.userMessage())
        }
    } catch (e: Exception) {
        ClaimOutcome.Failed(e.userMessage())
    }
}

// ------------------------------------------------------------------ 发红包

private data class RpLimits(val maxTotal: Int, val maxCount: Int, val minEach: Int, val dailyLimit: Int, val expireHours: Int)
private data class RpConfig(val enabled: Boolean, val limits: RpLimits, val sentToday: Int)
private data class RpBalance(val bound: Boolean, val balance: Int, val stale: Boolean)
private data class RpProblem(val field: String, val text: String)

private fun JsonObject.d(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun parseConfig(obj: JsonObject?): RpConfig {
    val limits = obj?.o("limits")
    fun positive(key: String, fallback: Int) = limits?.i(key)?.takeIf { it > 0 } ?: fallback
    return RpConfig(
        enabled = obj?.b("enabled") == true,
        limits = RpLimits(positive("max_total", 500), positive("max_count", 100), positive("min_each", 1), positive("daily_send_limit", 20), positive("expire_hours", 24)),
        sentToday = (obj?.i("sent_today") ?: 0).coerceAtLeast(0),
    )
}

/** 输入框里的字读成正整数；空着或不是正整数为 null */
private fun wholeNumber(text: String): Int? = text.trim().takeIf { it.length in 1..9 }?.toIntOrNull()?.takeIf { it > 0 }

/** 按服务端规则先查一遍（上限来自 GET /red-packets/config）。拼手气要求总额 ≥ 个数 × 每份最少 */
private fun validate(kind: String, amountText: String, countText: String, amount: Int?, count: Int?, limits: RpLimits): RpProblem? {
    if (amountText.isNotBlank() && amount == null) return RpProblem("amount", "金额要填正整数")
    if (countText.isNotBlank() && count == null) return RpProblem("count", "个数要填正整数")
    if (count != null && count > limits.maxCount) return RpProblem("count", "一个红包最多分成 ${limits.maxCount} 个")
    if (amount == null || count == null) return null
    if (kind == "normal" && amount < limits.minEach) return RpProblem("amount", "每个至少 ${coins(limits.minEach)} 币")
    if (kind == "lucky" && amount.toLong() < count.toLong() * limits.minEach) {
        return RpProblem("amount", "总金额太少：$count 个红包至少要 ${coins(count * limits.minEach)} 币（每个至少 ${coins(limits.minEach)} 币）")
    }
    val total = if (kind == "normal") amount.toLong() * count else amount.toLong()
    if (total > limits.maxTotal) return RpProblem("amount", "一个红包的总额不能超过 ${coins(limits.maxTotal)} 币，现在是 ${"%,d".format(total)}")
    return null
}

/** 发红包失败的说明（契约 docs/red-packets.md 3.2 的错误码） */
private fun sendFailure(e: Throwable): String {
    val api = e as? ApiException ?: return e.userMessage()
    val detail = api.detail as? JsonObject
    return when (api.code) {
        "disabled" -> "红包功能现在关着，暂时发不了"
        "not_member" -> "你已经不在这个会话里了，发不了红包"
        "muted" -> if (detail?.s("muted_until") != null) "你正在禁言中，不能发红包" else "你在这个群里被禁言了（或群开了全员禁言），不能在这里发红包"
        "ccw_not_bound" -> "还没有绑定 CCW 账号，绑定后才能发红包"
        "invalid_amount" -> "金额不对：要是正整数，并且在上限以内"
        "invalid_count" -> "个数不对：要是正整数，并且在上限以内"
        "invalid_kind" -> "这里不能发这种红包（私聊只能发普通红包）"
        "greeting_rejected" -> api.message.ifBlank { "祝福语没有通过审核，换一句试试" }
        "rate_limited" -> "发得太快了，这次没有扣币，稍等一会儿再发"
        "insufficient_balance" -> detail?.i("shortfall")?.takeIf { it > 0 }?.let { "金币不够，还差 ${coins(it)} 个" } ?: "金币不够"
        "daily_limit" -> "今天发红包的次数用完了，明天再来"
        "verification_unavailable" -> "现在核实不了余额，这次没有扣币，稍后再试"
        else -> api.message
    }
}

/**
 * 发红包（契约 docs/red-packets.md）：群里可选拼手气（金额填总额）/ 普通（金额填单个），私聊只有普通、1 个。
 * 限制取 GET /red-packets/config，余额取 GET /shop；总额、余额、发完剩多少随输入实时算，不合规当场说。
 * [mutedReason] 不为空（全员禁言等）时整张表单只读，按钮不可点。
 */
@Composable
internal fun RedPacketSendSheet(controller: ChatController, isGroup: Boolean, mutedReason: String? = null, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<RpConfig?>(null) }
    var balance by remember { mutableStateOf<RpBalance?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf(if (isGroup) "lucky" else "normal") }
    var amountText by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 服务端说禁言 / 今天发满了：之后都按发不了算
    var serverMuted by remember { mutableStateOf<String?>(null) }
    var serverDaily by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        loadError = null
        coroutineScope {
            val shop = async { runCatching { controller.graph.api.get("/shop").asObj()?.o("balance") }.getOrNull() }
            runCatching { parseConfig(controller.graph.api.get("/red-packets/config").asObj()) }
                .onSuccess { config = it }
                .onFailure { if (it is CancellationException) throw it; loadError = it.userMessage() }
            shop.await()?.let { RpBalance(it.b("bound") == true, (it.d("balance") ?: 0.0).toInt(), it.b("stale") == true) }?.let { balance = it }
        }
    }

    fun edit(apply: () -> Unit) {
        apply()
        failure = null
    }

    KcSheet(onDismiss = { if (!busy) onDismiss() }, maxHeight = 720.dp) {
        // 顶栏：图标 · 标题 · 红包记录
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(RpColors.Red), contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.redPacket, tint = RpColors.Gold, size = 17.dp)
            }
            Text("发红包", style = type.title, color = colors.text, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                    onDismiss()
                    nav.go(RedPacketRecordsRoute)
                }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("红包记录", style = type.secondary, color = colors.muted)
                KcIcon(KcIcons.chevron, tint = colors.subtle, size = 14.dp)
            }
        }
        val cfg = config
        when {
            cfg == null && loadError != null -> StateText(loadError!!, error = true, onRetry = { attempt++ })
            cfg == null -> StateText("加载中…")
            else -> {
                val limits = cfg.limits
                val amount = wholeNumber(amountText)
                val count = if (isGroup) wholeNumber(countText) else 1
                val total = if (amount == null || count == null) null else if (kind == "normal") amount.toLong() * count else amount.toLong()
                val problem = validate(kind, amountText, if (isGroup) countText else "1", amount, count, limits)
                val dailyLeft = (limits.dailyLimit - cfg.sentToday).coerceAtLeast(0)
                val dailyUsedUp = dailyLeft == 0 || serverDaily
                val closed = !cfg.enabled
                val muted = mutedReason ?: serverMuted
                val bal = balance
                val unbound = bal != null && !bal.bound
                val after = if (bal != null && total != null) bal.balance - total else null
                val short = after != null && after < 0
                val locked = muted != null || closed
                val ready = !locked && !unbound && !dailyUsedUp && amount != null && count != null && problem == null && !short

                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isGroup) {
                        Segmented(listOf("拼手气红包", "普通红包"), if (kind == "lucky") 0 else 1, { edit { kind = if (it == 0) "lucky" else "normal" } })
                        Text(
                            if (kind == "lucky") "总金额随机分给每个人，看谁手气好" else "每个人领到的一样多",
                            style = type.caption,
                            color = colors.subtle,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    RpField(
                        label = if (kind == "lucky") "总金额" else if (isGroup) "单个金额" else "金额",
                        value = amountText,
                        onChange = { edit { amountText = it.filter(Char::isDigit).take(7) } },
                        unit = "币",
                        placeholder = "0",
                        bad = problem?.field == "amount",
                        big = true,
                        enabled = !locked,
                    )
                    if (isGroup) {
                        RpField(
                            label = "红包个数",
                            value = countText,
                            onChange = { edit { countText = it.filter(Char::isDigit).take(4) } },
                            unit = "个",
                            placeholder = "最多 ${limits.maxCount}",
                            bad = problem?.field == "count",
                            enabled = !locked,
                        )
                    }
                    RpField(
                        label = "祝福语",
                        value = greeting,
                        onChange = { if (it.length <= 30) edit { greeting = it } },
                        unit = "${greeting.length}/30",
                        placeholder = "恭喜发财，大吉大利",
                        numeric = false,
                        enabled = !locked,
                    )

                    problem?.let { RpNote(it.text, danger = true) }
                    muted?.let { RpNote(it, danger = true) }
                    if (closed) RpNote("红包功能现在关着，暂时发不了", danger = true)
                    if (dailyUsedUp && !closed) RpNote("今天已经发了 ${cfg.sentToday} 个红包，到每天 ${limits.dailyLimit} 个的上限了，明天再来", danger = true)
                    if (unbound) RpNote("红包里装的是金币，绑定 CCW 账号后才能发。", danger = false, action = "去绑定") {
                        onDismiss()
                        nav.go(SettingsPageRoute("security"))
                    }

                    // 总额
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CoinGlyph(26.dp)
                            Text(
                                total?.let { "%,d".format(it) } ?: "0",
                                fontSize = 40.sp,
                                fontWeight = FontWeight.W700,
                                color = if (total != null) colors.text else colors.subtle,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(
                            if (kind == "normal" && isGroup && amount != null && count != null) "${coins(amount)} 币 × $count 个" else "红包总额（金币）",
                            style = type.caption,
                            color = colors.muted,
                        )
                    }
                    // 余额
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KcIcon(KcIcons.wallet, tint = colors.muted, size = 16.dp)
                        Text("余额 ${bal?.let { coins(it.balance) } ?: "—"} 币", style = type.secondary, color = colors.text, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        Text(
                            when {
                                after == null -> ""
                                after < 0 -> "不够，还差 ${"%,d".format(-after)}"
                                else -> "发完剩 ${"%,d".format(after)}"
                            },
                            style = type.secondary,
                            color = if (short) colors.danger else colors.muted,
                        )
                    }
                    if (bal?.stale == true) RpNote("上次向 CCW 核实没有成功，余额可能不是最新的", danger = false)
                    failure?.let { RpNote(it, danger = true) }

                    RedButton(
                        text = when {
                            busy -> "正在塞进红包…"
                            ready && total != null -> "塞进红包 · ${"%,d".format(total)} 币"
                            else -> "塞进红包"
                        },
                        enabled = ready && !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        if (amount == null || count == null) return@RedButton
                        busy = true
                        failure = null
                        scope.launch {
                            runCatching {
                                controller.graph.api.post("/red-packets", buildJsonObject {
                                    put("conversation_id", controller.conversationId)
                                    put("kind", kind)
                                    put("amount", amount)
                                    put("count", count)
                                    if (greeting.isNotBlank()) put("greeting", greeting.trim())
                                }).asObj()
                            }.onSuccess { result ->
                                controller.accept(result?.get("message"))
                                result?.o("red_packet")?.let(::parseRedPacket)?.let { controller.redPackets[it.id] = it }
                                onDismiss()
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                when ((e as? ApiException)?.code) {
                                    "muted" -> serverMuted = sendFailure(e)
                                    "daily_limit" -> serverDaily = true
                                    "disabled" -> config = cfg.copy(enabled = false)
                                    else -> failure = sendFailure(e)
                                }
                            }
                            busy = false
                        }
                    }
                    Text(
                        "${limits.expireHours} 小时没领完，剩下的金币退回余额" + (if (!dailyUsedUp) "；今天还能发 $dailyLeft 个" else "") + "。金币没有现金价值。",
                        style = type.caption,
                        color = colors.subtle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 发红包表单的一行：左标签、右输入（金额行大号数字）、单位；不合规时描红边 */
@Composable
private fun RpField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    unit: String,
    placeholder: String,
    bad: Boolean = false,
    big: Boolean = false,
    numeric: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val style = if (big) type.body.copy(fontSize = 26.sp, fontWeight = FontWeight.W700) else type.body
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (big) 64.dp else 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelMuted)
            .border(1.dp, if (bad) colors.danger else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.rowTitle, color = colors.text, modifier = Modifier.width(76.dp))
        Box(Modifier.weight(1f), contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = colors.subtle), maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = style.copy(color = if (bad) colors.danger else colors.text, textAlign = if (numeric) TextAlign.End else TextAlign.Start),
                cursorBrush = SolidColor(RpColors.Red),
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(unit, style = type.secondary, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 表单里的一句提示：红（不能发）/ 黄（提醒），可带一个文字按钮 */
@Composable
private fun RpNote(text: String, danger: Boolean, action: String? = null, onAction: () -> Unit = {}) {
    val colors = KcTheme.colors
    val tint = if (danger) colors.danger else colors.warn
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(KcIcons.alertCircle, tint = tint, size = 15.dp)
        Text(text, style = KcTheme.type.caption, color = tint, modifier = Modifier.padding(start = 8.dp).weight(1f))
        if (action != null) Text(action, style = KcTheme.type.caption, color = colors.accent, fontWeight = FontWeight.W600, modifier = Modifier.clickable(onClick = onAction).padding(start = 8.dp))
    }
}

/** 红包主按钮：红色渐变、按下微缩 */
@Composable
internal fun RedButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = rememberInteraction()
    Box(
        modifier
            .height(50.dp)
            .pressScale(press)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(RpColors.FlapTop, RpColors.RedDeep)))
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KcTheme.type.rowTitle, color = Color.White, fontWeight = FontWeight.W600)
    }
}

/** 一枚小金币：金色径向渐变 + 内圈 */
@Composable
internal fun CoinGlyph(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(RpColors.GoldLight, RpColors.Gold, RpColors.GoldDeep), center = Offset(r * 0.7f, r * 0.7f), radius = r * 1.3f), r)
        drawCircle(RpColors.GoldDeep.copy(alpha = 0.7f), r * 0.66f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.12f))
    }
}

// ------------------------------------------------------------------ 开红包 / 领取详情

/** 聊天页入口：缓存用 controller.redPackets，私聊 / 群从会话列表判断 */
@Composable
internal fun RedPacketOpenSheet(controller: ChatController, meta: RedPacketMeta, onDismiss: () -> Unit) {
    val source = remember(controller) { RedPacketSource(controller.graph, controller.redPackets, controller::settlePendingRedPacket) }
    val isGroup = controller.graph.conversations.find(controller.conversationId)?.isGroup ?: true
    RedPacketDialog(source, meta, isGroup, controller.myId, initialDetail = false, onDismiss = onDismiss)
}

/**
 * 点开一个红包后的整屏弹层，两种画面：
 *   正面  经典红包：封口上一枚金色「開」，点它转起来去领；领到后封口掀开，换到详情，金额从 0 数上去。
 *         领不了（抢完 / 过期 / 被撤回）就写明原因，给「看看大家的手气」。
 *   详情  我领了多少、谁领了多少、手气最佳、进度与用时；领过的、私聊里自己发的、[initialDetail]（记录页）直接是它。
 */
@Composable
internal fun RedPacketDialog(
    source: RedPacketSource,
    meta: RedPacketMeta,
    isGroup: Boolean,
    myId: Long?,
    initialDetail: Boolean,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(meta.id, attempt) { loadError = source.load(meta.id) }
    val packet = source.cache[meta.id]

    var opening by remember { mutableStateOf(false) }
    var revealing by remember { mutableStateOf(false) }
    var justClaimed by remember { mutableStateOf(false) }
    var wantDetail by remember { mutableStateOf(initialDetail) }
    var endedBy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val flap = remember { Animatable(0f) }

    // 到期那一刻自己刷新（服务端不推过期事件）
    var tick by remember { mutableIntStateOf(0) }
    val expiresAt = parseInstant(packet?.expiresAt)
    LaunchedEffect(expiresAt, packet?.status) {
        if (packet?.status == "active" && expiresAt != null) {
            val wait = Duration.between(Instant.now(), expiresAt).toMillis()
            if (wait > 0) { delay(wait + 50); tick++ }
        }
    }
    remember(tick) { tick }

    val mine = myId != null && packet?.senderId == myId
    val ownDirect = mine && !isGroup
    val ended = packet?.endedStatus ?: endedBy
    val busy = opening || revealing
    val showDetail = !busy && (wantDetail || packet?.claimedByMe == true || ownDirect)

    fun open() {
        if (busy) return
        Haptics.tick(view)
        error = null
        opening = true
        scope.launch {
            val started = SystemClock.uptimeMillis()
            val outcome = source.claim(meta.id)
            // 至少转一会儿：接口很快时不至于一闪而过
            val wait = 820 - (SystemClock.uptimeMillis() - started)
            if (wait > 0) delay(wait)
            when (outcome) {
                is ClaimOutcome.Claimed -> {
                    Haptics.confirm(view)
                    revealing = true
                    opening = false
                    justClaimed = true
                    flap.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 160f))
                    wantDetail = true
                    revealing = false
                }
                is ClaimOutcome.Ended -> {
                    Haptics.reject(view)
                    endedBy = outcome.status
                    opening = false
                }
                ClaimOutcome.Already -> {
                    wantDetail = true
                    opening = false
                }
                is ClaimOutcome.Failed -> {
                    Haptics.reject(view)
                    error = outcome.message
                    opening = false
                }
            }
        }
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = EaseSmooth)) }
    val dismiss = { if (!busy) onDismiss() }
    BackHandler(onBack = dismiss)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = showDetail,
            transitionSpec = {
                (fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.75f, stiffness = 380f), initialScale = 0.9f))
                    .togetherWith(fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 1.05f))
            },
            label = "redPacket",
        ) { detail ->
            if (detail) {
                ClaimDetail(
                    packet = packet,
                    meta = meta,
                    isGroup = isGroup,
                    myId = myId,
                    mine = mine,
                    ended = ended,
                    justClaimed = justClaimed,
                    loadError = loadError,
                    onRetry = { attempt++ },
                    onBackToOpen = { wantDetail = false },
                    onClose = dismiss,
                )
            } else {
                EnvelopeFace(
                    packet = packet,
                    meta = meta,
                    ended = ended,
                    mine = mine,
                    opening = opening,
                    revealing = revealing,
                    flap = flap.value,
                    error = error,
                    loadError = loadError,
                    onOpen = ::open,
                    onRetry = { attempt++ },
                    onViewClaims = { wantDetail = true },
                    onClose = dismiss,
                )
            }
        }
    }
}

/** 拦住点击：点卡片本身不关闭弹层 */
private fun Modifier.swallowClicks(): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
}

/** 红包正面：底座 + 弧形封口（掀开时绕顶边翻起）+ 「開」金币（领取中绕 Y 轴旋转） */
@Composable
private fun EnvelopeFace(
    packet: RedPacketState?,
    meta: RedPacketMeta,
    ended: String?,
    mine: Boolean,
    opening: Boolean,
    revealing: Boolean,
    flap: Float,
    error: String?,
    loadError: String?,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onViewClaims: () -> Unit,
    onClose: () -> Unit,
) {
    val type = KcTheme.type
    val pop = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 320f)) }
    val canOpen = packet != null && ended == null && !packet.claimedByMe
    BoxWithConstraints(
        Modifier
            .widthIn(max = 310.dp)
            .fillMaxWidth(0.8f)
            .aspectRatio(0.66f)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .shadow(24.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(RpColors.RedDeep, RpColors.RedDark)))
            .swallowClicks(),
    ) {
        val flapHeight = maxHeight * 0.66f
        val coinSize = 92.dp

        // 底座里的「信纸」：封口掀开时升起
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = flapHeight * (0.55f - 0.4f * flap))
                .fillMaxWidth(0.82f)
                .height(maxHeight * 0.42f)
                .graphicsLayer { alpha = flap }
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFFFF7E6), Color(0xFFFFE7BE)))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CoinGlyph(40.dp)
            Text("恭喜发财", style = type.rowTitle, color = RpColors.Ink, modifier = Modifier.padding(top = 8.dp))
        }

        // 底部：提示 / 错误 / 看手气
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp).graphicsLayer { alpha = 1f - flap },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                error != null -> Text(error, style = type.caption, color = RpColors.GoldLight, textAlign = TextAlign.Center)
                packet == null && loadError != null -> Text(
                    "红包没有加载出来 · 重试",
                    style = type.caption,
                    color = RpColors.GoldLight,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(6.dp),
                )
                packet == null -> Text("正在看看还有没有…", style = type.caption, color = Color.White.copy(alpha = 0.75f))
            }
            if (mine && ended != null && ended != "finished") {
                Text("未领完的金币已退回余额", style = type.caption, color = Color.White.copy(alpha = 0.8f))
            }
            if (packet != null && (ended != null || mine)) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !opening, onClick = onViewClaims).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (mine && ended == null) "查看领取进度" else "看看大家的手气", style = type.secondary, color = RpColors.Gold)
                    KcIcon(KcIcons.chevron, tint = RpColors.Gold, size = 14.dp)
                }
            }
        }

        // 封口
        Box(
            Modifier
                .fillMaxWidth()
                .height(flapHeight)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    rotationX = -105f * flap
                    cameraDistance = 14f * density
                    alpha = 1f - (flap * 1.4f).coerceAtMost(1f)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val dip = size.height * 0.16f
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height - dip)
                    quadraticTo(size.width / 2f, size.height + dip * 0.9f, 0f, size.height - dip)
                    close()
                }
                drawPath(path, Brush.verticalGradient(listOf(RpColors.FlapTop, RpColors.FlapBottom), endY = size.height))
                // 封口下沿一道暗影，显出层次
                drawPath(path, Color.Black.copy(alpha = 0.10f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 44.dp, start = 22.dp, end = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(RpColors.Gold.copy(alpha = 0.6f)).padding(2.dp)) {
                    Avatar(packet?.senderAvatar, packet?.senderName ?: "红包", tone = packet?.senderId ?: 0, size = 56.dp)
                }
                Text(
                    "${packet?.senderName ?: "有人"} 发出的红包",
                    style = type.secondary,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (packet != null || meta.kind == "lucky") {
                    Text(
                        if ((packet?.kind ?: meta.kind) == "lucky") "拼手气红包" else "普通红包",
                        style = type.caption,
                        color = RpColors.Gold.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    if (ended != null) endedSentence(ended) else packet?.greeting ?: meta.greeting,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.W600,
                    color = RpColors.GoldLight,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            // 关闭
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp).size(36.dp).clip(CircleShape).clickable(enabled = !opening, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.close, tint = Color.White.copy(alpha = 0.85f), size = 20.dp, contentDescription = "关闭") }
        }

        // 「開」
        if (canOpen || opening || revealing) {
            OpenCoin(
                spinning = opening,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = flapHeight - coinSize / 2 - 4.dp)
                    .graphicsLayer {
                        val s = 1f - flap
                        scaleX = s
                        scaleY = s
                        alpha = s
                    },
                size = coinSize,
                enabled = canOpen && !opening,
                onClick = onOpen,
            )
        }
    }
}

/** 金色「開」：按下弹簧回缩；[spinning] 时绕 Y 轴匀速旋转，停下时落回正面 */
@Composable
private fun OpenCoin(spinning: Boolean, modifier: Modifier, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val spin = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (isActive) spin.animateTo(spin.value + 360f, tween(560, easing = LinearEasing))
        } else if (spin.value % 360f != 0f) {
            spin.animateTo(ceil(spin.value / 360f) * 360f, spring(dampingRatio = 0.55f, stiffness = 260f))
        }
    }
    // 呼吸：等人点的时候轻轻放大缩小
    val breath = remember { Animatable(1f) }
    LaunchedEffect(enabled) {
        if (enabled) {
            while (isActive) {
                breath.animateTo(1.06f, tween(800, easing = EaseSmooth))
                breath.animateTo(1f, tween(800, easing = EaseSmooth))
            }
        } else breath.animateTo(1f)
    }
    val press = rememberInteraction()
    val angle = spin.value % 360f
    val back = angle in 90f..270f
    Box(
        modifier
            .size(size)
            .pressScale(press, 0.9f)
            .graphicsLayer {
                rotationY = spin.value
                cameraDistance = 12f * density
                scaleX *= breath.value
                scaleY *= breath.value
            }
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(RpColors.GoldLight, RpColors.Gold, RpColors.GoldDeep)))
            .border(3.dp, RpColors.GoldDeep.copy(alpha = 0.55f), CircleShape)
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "開",
            fontSize = 40.sp,
            fontWeight = FontWeight.W700,
            color = RpColors.Ink,
            modifier = Modifier.graphicsLayer { scaleX = if (back) -1f else 1f },
        )
    }
}

/** 一个红包「多久被抢完」 */
private fun tookText(packet: RedPacketState): String? {
    val start = parseInstant(packet.createdAt) ?: return null
    val end = parseInstant(packet.finishedAt) ?: return null
    val seconds = Duration.between(start, end).seconds.coerceAtLeast(1)
    return when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3600 -> "${(seconds / 60.0).roundToInt()} 分钟"
        else -> "${(seconds / 3600.0).roundToInt()} 小时"
    }
}

/** 领取详情顶上那句概况：已领几个、共多少币、多久抢完；金额只在看得到时写 */
private fun summaryText(packet: RedPacketState, ended: String?, mine: Boolean): String {
    val claimed = packet.claimedCount.coerceAtMost(packet.count)
    val progress = if (packet.total != null) "已领 $claimed/${packet.count} 个，共 ${coins(packet.claimedAmount ?: 0)}/${coins(packet.total)} 币" else "已领 $claimed/${packet.count} 个"
    val totalText = packet.total?.let { "共 ${coins(it)} 币" }.orEmpty()
    return when (ended) {
        "finished" -> if (packet.count == 1) "红包$totalText，已被领取" else "${packet.count} 个红包$totalText，" + (tookText(packet)?.let { "${it}被抢完" } ?: "已被抢完")
        "revoked" -> "红包已被撤回。$progress"
        "expired" -> "红包已过期。$progress"
        else -> progress + if (mine) "，等待领取" else ""
    }
}

/** 领取详情：红色弧形头图 + 头像、我领到的金额（刚领到时从 0 数上去）、概况与领取名单 */
@Composable
private fun ClaimDetail(
    packet: RedPacketState?,
    meta: RedPacketMeta,
    isGroup: Boolean,
    myId: Long?,
    mine: Boolean,
    ended: String?,
    justClaimed: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    onBackToOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val ownDirect = mine && !isGroup
    Column(
        Modifier
            .widthIn(max = 380.dp)
            .fillMaxWidth(0.9f)
            .heightIn(max = 640.dp)
            .shadow(24.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(colors.panel)
            .swallowClicks(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 头图：红色 + 下沿弧线，头像压在弧线上
        Box(Modifier.fillMaxWidth().height(118.dp)) {
            Canvas(Modifier.fillMaxWidth().height(88.dp)) {
                val dip = 22.dp.toPx()
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height - dip)
                    quadraticTo(size.width / 2f, size.height + dip, 0f, size.height - dip)
                    close()
                }
                drawPath(path, Brush.verticalGradient(listOf(RpColors.FlapTop, RpColors.RedDeep), endY = size.height))
            }
            Box(
                Modifier.padding(8.dp).size(36.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.close, tint = Color.White, size = 20.dp, contentDescription = "关闭") }
            Box(Modifier.align(Alignment.BottomCenter).size(64.dp).clip(CircleShape).background(colors.panel).padding(3.dp)) {
                Avatar(packet?.senderAvatar, packet?.senderName ?: "红包", tone = packet?.senderId ?: 0, size = 58.dp)
            }
        }
        Row(Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${packet?.senderName ?: "有人"} 的红包", style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (isGroup) {
                val lucky = (packet?.kind ?: meta.kind) == "lucky"
                Text(
                    if (lucky) "拼" else "普",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W700,
                    color = Color.White,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(if (lucky) RpColors.GoldDeep else colors.subtle).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            packet?.greeting ?: meta.greeting,
            style = type.secondary,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
        )

        // 金额 / 结果
        Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            when {
                packet?.myAmount != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CountUpAmount(packet.myAmount, animate = justClaimed)
                    Text(if (justClaimed) (if (isGroup) "抢到了，已存入金币余额" else "收下了，已存入金币余额") else "已存入金币余额", style = type.caption, color = colors.muted)
                }
                ownDirect && packet != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    packet.total?.let { CountUpAmount(it, animate = false) }
                    Text(
                        when (ended) {
                            "finished" -> "对方已领取"
                            null -> "等待对方领取"
                            else -> endedSentence(ended)
                        },
                        style = type.caption,
                        color = colors.muted,
                    )
                }
                ended != null -> Text(endedSentence(ended), style = type.title, color = colors.text)
                packet != null -> RedButton("我也开一个", enabled = true, modifier = Modifier.width(180.dp), onClick = onBackToOpen)
            }
        }
        if (packet != null && mine && (ended == "expired" || ended == "revoked") && (packet.refundedAmount ?: 0) > 0) {
            Text(
                "未领完的 ${coins(packet.refundedAmount ?: 0)} 金币已退回余额",
                style = type.caption,
                color = colors.ok,
                modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(colors.ok.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        if (packet != null) {
            Text(
                summaryText(packet, ended, mine),
                style = type.caption,
                color = colors.muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        val claims = packet?.claims
        when {
            packet == null && loadError != null -> StateText("领取详情没有加载出来：$loadError", error = true, onRetry = onRetry)
            packet == null -> StateText("加载中…")
            claims == null -> StateText("领取之后才能看到大家的手气")
            claims.isEmpty() -> StateText(if (ended != null) "没有人领取" else "还没有人领取")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(bottom = 8.dp)) {
                itemsIndexed(claims, key = { index, claim -> "${claim.userId}-$index" }) { _, claim ->
                    ClaimRow(claim, me = claim.userId != null && claim.userId == myId)
                }
            }
        }
    }
}

/** 领取名单的一行：头像 · 名字 / 时间 · 金额 + 手气最佳 */
@Composable
private fun ClaimRow(claim: RedPacketClaim, me: Boolean) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        Modifier.fillMaxWidth().background(if (me) colors.accentSoft else Color.Transparent).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(claim.avatarUrl, claim.name, tone = claim.userId ?: 0, size = 36.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(claim.name + if (me) "（我）" else "", style = type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = rpTime(claim.createdAt)
            if (time.isNotEmpty()) Text(time, style = type.caption, color = colors.subtle)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${coins(claim.amount)} 币", style = type.rowTitle, color = colors.text)
            if (claim.luckiest) {
                Row(
                    Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(RpColors.GoldDeep.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(KcIcons.crown, tint = RpColors.amount(), size = 11.dp)
                    Text("手气最佳", fontSize = 11.sp, color = RpColors.amount(), fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 3.dp))
                }
            }
        }
    }
}

/** 大号金额：[animate] 时从 0 弹性数上去 */
@Composable
private fun CountUpAmount(amount: Int, animate: Boolean) {
    val shown = remember { Animatable(if (animate) 0f else amount.toFloat()) }
    LaunchedEffect(amount) { shown.animateTo(amount.toFloat(), tween(if (animate) 1000 else 0, easing = EaseSmooth)) }
    val pop = remember { Animatable(if (animate) 0.6f else 1f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f)) }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        Text("%,d".format(shown.value.roundToInt()), fontSize = 46.sp, fontWeight = FontWeight.W700, color = RpColors.amount())
        Text("金币", style = KcTheme.type.secondary, color = RpColors.amount(), modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
    }
}
