package ink.kuke.chat.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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
    /** 金币、「開」按钮的面：纯色，不做渐变 */
    val CoinFace = Color(0xFFF6CD78)
    /** 金币内圈、描边 */
    val CoinRing = Color(0xFFD9A443)
    /** 红包正面的「信纸」 */
    val Paper = Color(0xFFFFF4DF)
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
    /** 语音红包没念对时带上 [heard]（识别出来的字）和 [attemptsLeft]；[exhausted] = 这个红包的尝试次数用完了 */
    data class Failed(val message: String, val heard: String? = null, val attemptsLeft: Int? = null, val exhausted: Boolean = false) : ClaimOutcome
}

/** 红包读写：聊天页用 ChatController.redPackets 当缓存（卡片跟着变），记录页自带一份 */
internal class RedPacketSource(
    private val graph: AppGraph,
    val cache: SnapshotStateMap<Long, RedPacketState>,
    /** 领到了 / 确认领不了（抢完、过期、撤回、领过）：聊天页用它摘掉「N 个红包未领」里的这一个 */
    private val onSettled: (Long) -> Unit = {},
    /** 接龙红包领到时，服务端以我的身份发出的那条消息（MessageRead）：聊天页直接落进列表（同 id 的 message.created 会去重） */
    private val onMessage: (kotlinx.serialization.json.JsonElement?) -> Unit = {},
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

    /**
     * 语音红包：录音用发语音消息的同一个接口（POST /uploads/message-voice）传上去，再带着地址和语音信息去领。
     * voice_metadata 和普通语音消息的 metadata 一样（{ voice: { duration_ms, … } }），另在顶层放一份 duration_ms
     */
    suspend fun claimVoice(id: Long, file: File, durationMs: Long, waveform: List<Int>): ClaimOutcome {
        val bytes = try {
            withContext(Dispatchers.IO) { file.readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ClaimOutcome.Failed("读不到这段录音，再录一次试试")
        }
        val url = try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "voice.m4a", bytes.toRequestBody("audio/mp4".toMediaType()))
                .build()
            graph.api.upload("/uploads/message-voice", body).asObj()?.s("url") ?: return ClaimOutcome.Failed("录音没有传上去，再试一次")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ClaimOutcome.Failed("录音没有传上去：${e.userMessage()}")
        }
        return claim(id, buildJsonObject {
            put("voice_url", url)
            put("voice_metadata", buildJsonObject {
                put("voice", voiceInfo(durationMs, bytes.size, waveform))
                put("duration_ms", durationMs)
            })
        })
    }

    /** POST /red-packets/{id}/claim（语音红包带 [body]）。领到或确认领不了之后让会话列表重拉，「[有红包]」跟着摘掉 */
    suspend fun claim(id: Long, body: JsonObject? = null): ClaimOutcome = try {
        val result = graph.api.post("/red-packets/$id/claim", body).asObj()
        result?.o("red_packet")?.let(::parseRedPacket)?.let { cache[id] = it }
        result?.get("message")?.takeIf { it is JsonObject }?.let(onMessage)
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
            "not_target" -> {
                load(id)
                onSettled(id)
                val name = cache[id]?.targetName
                ClaimOutcome.Failed(if (name != null) "这是给 $name 的专属红包，只有 Ta 能领" else "这是给别人的专属红包，你领不了")
            }
            "muted_in_group" -> ClaimOutcome.Failed(
                if (cache[id]?.voice == true) "你在这个群里被禁言了。语音红包领了要替你发出这段语音，所以暂时领不了"
                else "你在这个群里被禁言了。接龙红包领了要替你发一句话，所以暂时领不了",
            )
            "chain_send_failed" -> ClaimOutcome.Failed("接龙的那句话没发出去，这次没有领到，稍后再试")
            "voice_required" -> ClaimOutcome.Failed("这是语音红包，要按住说出口令才能领")
            "voice_mismatch" -> (e.detail as? JsonObject).let { detail ->
                ClaimOutcome.Failed(
                    e.message.ifBlank { "没听清，再说一次" },
                    heard = detail?.s("recognized")?.takeIf { it.isNotBlank() },
                    attemptsLeft = detail?.i("attempts_left"),
                    exhausted = detail?.i("attempts_left") == 0,
                )
            }
            "voice_attempts_exhausted" -> ClaimOutcome.Failed("这个红包你已经试了太多次，领不了了", exhausted = true)
            "voice_too_long" -> ClaimOutcome.Failed("说得太长了，口令要在 15 秒内说完")
            "voice_unavailable" -> ClaimOutcome.Failed("语音识别暂时用不了，稍后再试")
            else -> ClaimOutcome.Failed(e.userMessage())
        }
    } catch (e: Exception) {
        ClaimOutcome.Failed(e.userMessage())
    }
}

// ------------------------------------------------------------------ 发红包

private data class RpLimits(val maxTotal: Int, val maxCount: Int, val minEach: Int, val dailyLimit: Int, val expireHours: Int)
/** [features]：服务端支持的玩法（chain 接龙、exclusive 专属）；旧服务端没有这个字段，当成空 */
private data class RpConfig(val enabled: Boolean, val limits: RpLimits, val sentToday: Int, val features: Set<String> = emptySet())
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
        features = obj?.a("features")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty().toSet(),
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
        "invalid_count" -> "个数不对：要是正整数，并且在上限以内（专属红包只能 1 个）"
        "invalid_kind" -> "这里不能发这种红包（私聊和专属红包只能是普通红包）"
        "greeting_rejected" -> api.message.ifBlank { "祝福语没有通过审核，换一句试试" }
        "chain_text_rejected" -> api.message.ifBlank { "接龙口令没有通过审核，换一句试试" }
        "voice_text_rejected" -> api.message.ifBlank { "语音口令没有通过审核，换一句试试" }
        "invalid_target" -> when (detail?.s("reason")) {
            "direct" -> "私聊里不能发专属红包"
            "self" -> "专属红包不能发给自己"
            "not_member" -> "Ta 已经不在这个群里了，换一位试试"
            "bot" -> "专属红包不能发给机器人"
            else -> "专属红包要指定一位群成员"
        }
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
    var kindChoice by remember { mutableStateOf(if (isGroup) "lucky" else "normal") }
    val kind = kindChoice
    var amountText by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 服务端说禁言 / 今天发满了：之后都按发不了算
    var serverMuted by remember { mutableStateOf<String?>(null) }
    var serverDaily by remember { mutableStateOf(false) }
    // 接龙：领的人会自动以自己的身份发出口令；专属：只有选中的那位群成员能领（只在群里、服务端支持时出现）
    var chainOn by remember { mutableStateOf(false) }
    var chainText by remember { mutableStateOf("") }
    // 语音红包：领的人要按住说出这句口令（私聊、群聊都行），和接龙二选一
    var voiceOn by remember { mutableStateOf(false) }
    var voiceText by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<MemberLite?>(null) }
    var picking by remember { mutableStateOf(false) }

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
                val canChain = isGroup && "chain" in cfg.features
                val canExclusive = isGroup && "exclusive" in cfg.features
                val pickedTarget = target.takeIf { canExclusive }
                val canVoice = "voice" in cfg.features
                val voicing = canVoice && voiceOn
                val chaining = canChain && chainOn && !voicing
                // 专属红包只能是普通红包、1 个：类型和个数锁死
                val kind = if (pickedTarget != null) "normal" else kind
                val multi = isGroup && pickedTarget == null
                val amount = wholeNumber(amountText)
                val count = if (multi) wholeNumber(countText) else 1
                val total = if (amount == null || count == null) null else if (kind == "normal") amount.toLong() * count else amount.toLong()
                val problem = validate(kind, amountText, if (multi) countText else "1", amount, count, limits)
                    ?: if (chaining && chainText.isBlank() && amount != null) RpProblem("chain", "接龙口令还没填：领的人会自动发出这句话") else null
                    ?: if (voicing && voiceText.isBlank() && amount != null) RpProblem("voice", "语音口令还没填：领的人要说出这句话才能领") else null
                val dailyLeft = (limits.dailyLimit - cfg.sentToday).coerceAtLeast(0)
                val dailyUsedUp = dailyLeft == 0 || serverDaily
                val closed = !cfg.enabled
                val muted = mutedReason ?: serverMuted
                val bal = balance
                val unbound = bal != null && !bal.bound
                val after = if (bal != null && total != null) bal.balance - total else null
                val short = after != null && after < 0
                val locked = muted != null || closed
                val ready = !locked && !unbound && !dailyUsedUp && amount != null && count != null && problem == null && !short && !(chaining && chainText.isBlank()) && !(voicing && voiceText.isBlank())

                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isGroup) {
                        Box(Modifier.graphicsLayer { alpha = if (pickedTarget != null) 0.45f else 1f }) {
                            Segmented(listOf("拼手气红包", "普通红包"), if (kind == "lucky") 0 else 1, { if (pickedTarget == null) edit { kindChoice = if (it == 0) "lucky" else "normal" } })
                        }
                        Text(
                            when {
                                pickedTarget != null -> "专属红包只能是普通红包、1 个，类型和个数已固定"
                                kind == "lucky" -> "总金额随机分给每个人，看谁手气好"
                                else -> "每个人领到的一样多"
                            },
                            style = type.caption,
                            color = colors.subtle,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (canExclusive) {
                        RpTargetRow(pickedTarget, enabled = !locked, onPick = { picking = true }, onClear = { edit { target = null } })
                    }
                    RpField(
                        label = if (kind == "lucky") "总金额" else if (multi) "单个金额" else "金额",
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
                            value = if (pickedTarget != null) "1" else countText,
                            onChange = { edit { countText = it.filter(Char::isDigit).take(4) } },
                            unit = "个",
                            placeholder = "最多 ${limits.maxCount}",
                            bad = problem?.field == "count",
                            enabled = !locked && pickedTarget == null,
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
                    if (canChain) {
                        RpSwitchRow(
                            label = "接龙",
                            hint = if (chaining) "领的人会自动在群里发出这句口令" else "领取时自动发一句口令",
                            checked = chainOn,
                            enabled = !locked,
                            onChange = { edit { chainOn = it; if (it) voiceOn = false } },
                        )
                        if (chaining) {
                            RpField(
                                label = "口令",
                                value = chainText,
                                // 口令不能换行：粘贴进来的换行折成空格
                                onChange = { raw -> raw.replace(Regex("""[\r\n\u2028\u2029]+"""), " ").let { if (it.length <= 30) edit { chainText = it } } },
                                unit = "${chainText.length}/30",
                                placeholder = "例如：谢谢老板",
                                numeric = false,
                                bad = problem?.field == "chain",
                                enabled = !locked,
                            )
                        }
                    }
                    if (canVoice) {
                        RpSwitchRow(
                            label = "语音红包",
                            hint = if (voicing) "领的人要按住说出这句口令" else "说出口令才能领",
                            checked = voicing,
                            enabled = !locked,
                            onChange = { on ->
                                edit {
                                    voiceOn = on
                                    if (on) {
                                        chainOn = false
                                        // 默认先填上祝福语，改不改随意
                                        if (voiceText.isBlank()) voiceText = greeting.trim().ifBlank { "恭喜发财，大吉大利" }.replace(Regex("""[\r\n\u2028\u2029]+"""), " ").take(30)
                                    }
                                }
                            },
                        )
                        if (voicing) {
                            RpField(
                                label = "口令",
                                value = voiceText,
                                onChange = { raw -> raw.replace(Regex("""[\r\n\u2028\u2029]+"""), " ").let { if (it.length <= 30) edit { voiceText = it } } },
                                unit = "${voiceText.length}/30",
                                placeholder = "要大家念的口令",
                                numeric = false,
                                bad = problem?.field == "voice",
                                enabled = !locked,
                            )
                        }
                    }

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
                                    if (chaining) put("chain_text", chainText.trim())
                                    if (voicing) put("voice_text", voiceText.trim())
                                    pickedTarget?.let { put("target_user_id", it.userId) }
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
    // 选专属红包的领取人：叠在发红包面板上面，选好回来，已经填的都还在
    if (picking) {
        MemberPickSheet(controller, title = "专属红包给谁", excludeMe = true, onDismiss = { picking = false }) {
            edit { target = it }
            picking = false
        }
    }
}

/** 专属红包的领取人：没选时是一行「指定一位群成员」，选了显示头像 + 名字，右边可以清掉 */
@Composable
private fun RpTargetRow(target: MemberLite?, enabled: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelMuted)
            .clickable(enabled = enabled, onClick = onPick)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("专属", style = type.rowTitle, color = colors.text, modifier = Modifier.width(76.dp))
        if (target == null) {
            Text("指定一位群成员，只有 Ta 能领", style = type.body, color = colors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
        } else {
            Avatar(target.avatarUrl, target.name, tone = target.userId, size = 24.dp)
            Text(target.name, style = type.body, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.close, tint = colors.muted, size = 16.dp, contentDescription = "不指定") }
        }
    }
}

/** 表单里的开关行：左标签、中间一句说明、右开关 */
@Composable
private fun RpSwitchRow(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelMuted)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.rowTitle, color = colors.text, modifier = Modifier.width(76.dp))
        Text(hint, style = type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
        KcSwitch(checked, onChange, enabled)
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

/** 红包主按钮：纯红、按下微缩 */
@Composable
internal fun RedButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = rememberInteraction()
    Box(
        modifier
            .height(50.dp)
            .pressScale(press)
            .clip(RoundedCornerShape(14.dp))
            .background(RpColors.Red)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KcTheme.type.rowTitle, color = Color.White, fontWeight = FontWeight.W600)
    }
}

/** 一枚小金币：纯色金面 + 内圈 */
@Composable
internal fun CoinGlyph(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(RpColors.CoinFace, r)
        drawCircle(RpColors.CoinRing, r * 0.64f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.11f))
    }
}

// ------------------------------------------------------------------ 开红包 / 领取详情

/** 聊天页入口：缓存用 controller.redPackets，私聊 / 群从会话列表判断 */
@Composable
internal fun RedPacketOpenSheet(controller: ChatController, meta: RedPacketMeta, onDismiss: () -> Unit) {
    val source = remember(controller) { RedPacketSource(controller.graph, controller.redPackets, controller::settlePendingRedPacket, controller::accept) }
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

    // 语音红包：按住说出口令（录音机和聊天里发语音的是同一个，最长 15 秒），松手上传并领取
    val context = LocalContext.current
    val voiceSession = remember { VoiceSession(context.applicationContext, scope, maxMs = VOICE_CLAIM_MAX_MS) }
    DisposableEffect(voiceSession) { onDispose { voiceSession.finish(send = false) } }
    val recording = voiceSession.phase != VoiceSession.Phase.Idle
    LaunchedEffect(recording) { while (recording && isActive) withFrameMillis { voiceSession.tick(it) } }
    var heard by remember { mutableStateOf<String?>(null) }
    var attemptsLeft by remember { mutableStateOf<Int?>(null) }
    var voiceExhausted by remember { mutableStateOf(false) }

    val mine = myId != null && packet?.senderId == myId
    val ownDirect = mine && !isGroup
    val ended = packet?.endedStatus ?: endedBy
    val busy = opening || revealing || recording
    // 给别人的专属红包（包括我发给别人的）：只能看，不能领
    val forOthers = packet?.forOthers(myId) ?: meta.forOthers(myId)
    val showDetail = !busy && (wantDetail || packet?.claimedByMe == true || ownDirect)

    /** 领取（普通 / 接龙点「開」，语音红包松手后）：至少转一会儿，再按结果掀封口 / 写原因 */
    suspend fun settle(claim: suspend () -> ClaimOutcome) {
        error = null
        heard = null
        opening = true
        val started = SystemClock.uptimeMillis()
        val outcome = claim()
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
                heard = outcome.heard
                outcome.attemptsLeft?.let { attemptsLeft = it }
                if (outcome.exhausted) voiceExhausted = true
                opening = false
            }
        }
    }

    fun open() {
        if (busy) return
        Haptics.tick(view)
        scope.launch { settle { source.claim(meta.id) } }
    }

    voiceSession.send = { file, duration, waveform ->
        settle { source.claimVoice(meta.id, file, duration, waveform) }
        true
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = EaseSmooth)) }
    val dismiss = { if (!busy) onDismiss() }
    BackHandler(onBack = { if (recording) voiceSession.finish(send = false) else dismiss() })
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
                    forOthers = forOthers,
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
                    myId = myId,
                    forOthers = forOthers,
                    opening = opening,
                    revealing = revealing,
                    flap = flap.value,
                    error = error,
                    loadError = loadError,
                    voice = VoiceClaimUi(voiceSession, heard, attemptsLeft, voiceExhausted),
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
    myId: Long?,
    forOthers: Boolean,
    opening: Boolean,
    revealing: Boolean,
    flap: Float,
    error: String?,
    loadError: String?,
    voice: VoiceClaimUi,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onViewClaims: () -> Unit,
    onClose: () -> Unit,
) {
    val type = KcTheme.type
    val pop = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 320f)) }
    val isVoice = packet?.voice ?: meta.voice
    val voiceText = packet?.voiceText ?: meta.voiceText
    val recording = voice.session.phase != VoiceSession.Phase.Idle
    // 语音红包：还能领的时候底部换成「按住说出口令」的状态区，「開」换成按住说话的麦克风
    val voiceMode = isVoice && packet != null && ended == null && !packet.claimedByMe && !forOthers
    val canOpen = packet != null && ended == null && !packet.claimedByMe && !forOthers && !(isVoice && voice.exhausted)
    val chainText = packet?.chainText ?: meta.chainText
    val targetId = packet?.targetUserId ?: meta.targetUserId
    val targetName = packet?.targetName ?: meta.targetName ?: "Ta"
    BoxWithConstraints(
        Modifier
            .widthIn(max = 310.dp)
            .fillMaxWidth(0.8f)
            .aspectRatio(0.66f)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .shadow(24.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(RpColors.RedDeep)
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
                .background(RpColors.Paper),
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
                voiceMode -> VoiceClaimStatus(voice, opening = opening || revealing, error = error)
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
            if (forOthers && ended == null && packet != null) {
                Text(
                    if (mine) "专属红包只有 $targetName 能领" else "这是给 $targetName 的专属红包，只有 Ta 能领",
                    style = type.caption,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
            if (packet != null && (ended != null || mine || forOthers)) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !opening, onClick = onViewClaims).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            mine && ended == null -> "查看领取进度"
                            forOthers -> "查看详情"
                            else -> "看看大家的手气"
                        },
                        style = type.secondary,
                        color = RpColors.Gold,
                    )
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
                // 封口下沿先垫一道柔和的影子（往下错开几像素），再画纯色封口：靠影子分层次，不靠渐变
                translate(top = 3.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.12f)) }
                drawPath(path, RpColors.FlapBottom)
            }
            // 封口上的字只占「開」以上的那一截：口令 / 祝福语太长时自动缩小字号，不会压到「開」底下
            val compact = voiceMode || chainText != null
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(flapHeight - coinSize / 2 - 10.dp)
                    .padding(top = if (compact) 30.dp else 40.dp, start = 22.dp, end = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val avatarSize = if (compact) 46.dp else 56.dp
                Box(Modifier.size(avatarSize + 4.dp).clip(CircleShape).background(RpColors.CoinFace.copy(alpha = 0.7f)).padding(2.dp)) {
                    Avatar(packet?.senderAvatar, packet?.senderName ?: "红包", tone = packet?.senderId ?: 0, size = avatarSize)
                }
                Text(
                    "${packet?.senderName ?: "有人"} 发出的红包",
                    style = type.secondary,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (packet != null || meta.kind == "lucky" || chainText != null || targetId != null || isVoice) {
                    Text(
                        when {
                            targetId != null && targetId == myId -> "给你的" + redPacketKindLabel(packet?.kind ?: meta.kind, chainText != null, true, isVoice)
                            targetId != null -> "给 $targetName 的" + redPacketKindLabel(packet?.kind ?: meta.kind, chainText != null, true, isVoice)
                            else -> redPacketKindLabel(packet?.kind ?: meta.kind, chainText != null, false, isVoice)
                        },
                        style = type.caption,
                        color = RpColors.Gold.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 剩下的高度给口令 / 祝福语（放不下就缩字号）和下面那行小字
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val voiceLine = voiceMode && voiceText != null
                    val chainLine = chainText != null && ended == null && !forOthers && packet?.claimedByMe != true
                    FitText(
                        text = if (voiceLine) "「$voiceText」" else if (ended != null) endedSentence(ended) else packet?.greeting ?: meta.greeting,
                        maxSize = if (voiceLine) 22.sp else 21.sp,
                        minSize = 14.sp,
                        maxLines = 3,
                        fontWeight = if (voiceLine) FontWeight.W700 else FontWeight.W600,
                        color = RpColors.GoldLight,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        // 语音红包：口令是大字，祝福语退到下面一行（「按住说出口令领取」在「開」下面，这里不重复）
                        voiceLine -> Text(
                            packet?.greeting ?: meta.greeting,
                            style = type.caption,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // 接龙：先说清楚领了会替我发什么
                        chainLine -> Text(
                            "领取后会以你的身份发出「$chainText」",
                            style = type.caption,
                            color = Color.White.copy(alpha = 0.78f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            // 关闭
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp).size(36.dp).clip(CircleShape).clickable(enabled = !opening && !recording, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.close, tint = Color.White.copy(alpha = 0.85f), size = 20.dp, contentDescription = "关闭") }
        }

        // 「開」
        if (voiceMode && (canOpen || opening || revealing)) {
            val holdEnabled = canOpen && !opening && !revealing
            OpenCoin(
                spinning = opening,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = flapHeight - coinSize / 2 - 4.dp)
                    .graphicsLayer {
                        val s = (1f - flap) * (1f + 0.14f * voice.session.level)
                        scaleX = s
                        scaleY = s
                        alpha = 1f - flap
                    },
                size = coinSize,
                enabled = holdEnabled && !recording,
                onClick = {},
                hold = rememberVoiceHold(voice.session, holdEnabled),
                glyph = {
                    KcIcon(
                        KcIcons.mic,
                        tint = if (voice.session.zone == VoiceSession.Zone.Cancel) RpColors.RedDeep else RpColors.Ink,
                        size = 40.dp,
                        contentDescription = "按住说出口令领取",
                    )
                },
            )
        } else if (canOpen || opening || revealing) {
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

/** 语音红包领取时的录音状态（录音机、上次识别结果、剩余次数），从 [RedPacketDialog] 传给红包正面 */
@Stable
internal class VoiceClaimUi(val session: VoiceSession, val heard: String?, val attemptsLeft: Int?, val exhausted: Boolean)

/** 语音红包一次最多录多久（服务端超过 15 秒回 voice_too_long） */
private const val VOICE_CLAIM_MAX_MS = 15_000L

/**
 * 按住说出口令：按下开始录（没有麦克风权限先要权限，同聊天里的「按住说话」），上滑超过 72dp 松手 = 取消，原地松手 = 上传并领取。
 * 到 15 秒自动结束并提交。
 */
@Composable
private fun rememberVoiceHold(session: VoiceSession, enabled: Boolean): Modifier {
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(context, "没有麦克风权限，领不了语音红包")
    }
    val canStart by rememberUpdatedState(enabled)
    val upCancel = with(density) { 72.dp.toPx() }
    return Modifier.pointerInput(session) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (!canStart) return@awaitEachGesture
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                askMic.launch(Manifest.permission.RECORD_AUDIO)
                return@awaitEachGesture
            }
            if (!session.start()) {
                Haptics.reject(view)
                toast(context, "麦克风打不开，稍后再试")
                return@awaitEachGesture
            }
            down.consume()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            var total = Offset.Zero
            try {
                while (session.phase == VoiceSession.Phase.Holding) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    total += change.positionChange()
                    change.consume()
                    val next = if (-total.y > upCancel) VoiceSession.Zone.Cancel else VoiceSession.Zone.None
                    if (next != session.zone) {
                        session.zone = next
                        if (next != VoiceSession.Zone.None) Haptics.tick(view)
                    }
                }
            } catch (e: CancellationException) {
                session.finish(send = false)
                throw e
            }
            if (session.phase == VoiceSession.Phase.Holding) session.finish(send = session.zone != VoiceSession.Zone.Cancel)
        }
    }
}

/** 语音红包正面底部：录音中（计时 + 实时波形 + 怎么取消）/ 识别中 / 没念对（听到的是什么、还能试几次）/ 等人按住 */
@Composable
private fun VoiceClaimStatus(voice: VoiceClaimUi, opening: Boolean, error: String?) {
    val type = KcTheme.type
    val session = voice.session
    val recording = session.phase != VoiceSession.Phase.Idle
    val cancel = session.zone == VoiceSession.Zone.Cancel
    when {
        recording -> {
            val s = session.seconds
            val max = (session.maxMs / 1000).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%d:%02d / %d:%02d".format(s / 60, s % 60, max / 60, max % 60),
                    style = type.caption,
                    color = RpColors.GoldLight,
                    fontWeight = FontWeight.W600,
                )
                LiveWave(session, Modifier.padding(start = 10.dp).width(96.dp).height(18.dp))
            }
            Text(
                if (cancel) "松开手指，取消这次录音" else "说完松开 · 上滑取消",
                style = type.caption,
                color = if (cancel) RpColors.Gold else Color.White.copy(alpha = 0.8f),
            )
        }
        opening -> Text("识别中…", style = type.secondary, color = RpColors.GoldLight, fontWeight = FontWeight.W600)
        else -> {
            if (error != null) {
                Text(error, style = type.secondary, color = RpColors.GoldLight, fontWeight = FontWeight.W600, textAlign = TextAlign.Center)
                voice.heard?.let {
                    Text("听到的是：$it", style = type.caption, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!voice.exhausted) voice.attemptsLeft?.let {
                    Text("还能再试 $it 次", style = type.caption, color = Color.White.copy(alpha = 0.6f))
                }
            } else {
                Text("按住说出口令领取", style = type.secondary, color = RpColors.Gold, fontWeight = FontWeight.W600)
            }
        }
    }
}

/** 金色「開」：按下弹簧回缩；[spinning] 时绕 Y 轴匀速旋转，停下时落回正面 */
@Composable
private fun OpenCoin(
    spinning: Boolean,
    modifier: Modifier,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    /** 语音红包：换掉点击手势（按住说话），[glyph] 换掉「開」字 */
    hold: Modifier? = null,
    glyph: (@Composable () -> Unit)? = null,
) {
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
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(RpColors.CoinFace)
            // 纯色金面 + 一道细内圈，像一枚压出来的币
            .drawBehind { drawCircle(RpColors.CoinRing.copy(alpha = 0.55f), this.size.minDimension / 2f - 7.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())) }
            .then(hold ?: Modifier.clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Box(Modifier.graphicsLayer { scaleX = if (back) -1f else 1f }) { glyph() }
        } else {
            Text(
                "開",
                fontSize = 40.sp,
                fontWeight = FontWeight.W700,
                color = RpColors.Ink,
                modifier = Modifier.graphicsLayer { scaleX = if (back) -1f else 1f },
            )
        }
    }
}

/**
 * 放不下就缩字号的文字：从 [maxSize] 起，每次排版溢出（超出行数或高度）就小 1sp，最小到 [minSize]（还放不下才省略）。
 * 字号定下来之前不画，免得看到一帧大字。
 */
@Composable
private fun FitText(
    text: String,
    maxSize: TextUnit,
    minSize: TextUnit,
    maxLines: Int,
    fontWeight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var size by remember(text) { mutableStateOf(maxSize) }
    var settled by remember(text) { mutableStateOf(false) }
    Text(
        text,
        fontSize = size,
        lineHeight = size * 1.3f,
        fontWeight = fontWeight,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size.value > minSize.value) size = (size.value - 1f).sp else settled = true
        },
        modifier = modifier.drawWithContent { if (settled) drawContent() },
    )
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
    forOthers: Boolean,
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
                drawPath(path, RpColors.Red)
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
                val lucky = (packet?.kind ?: meta.kind) == "lucky" && (packet?.targetUserId ?: meta.targetUserId) == null
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
        // 接龙口令、专属对象：细描边的小胶囊，黑白克制
        val chainText = packet?.chainText ?: meta.chainText
        val voiceText = (packet?.voiceText ?: meta.voiceText).takeIf { packet?.voice ?: meta.voice }
        val targetId = packet?.targetUserId ?: meta.targetUserId
        if (chainText != null || targetId != null || voiceText != null) {
            Row(Modifier.padding(top = 8.dp, start = 20.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (targetId != null) RpTag(if (targetId == myId) "给你的专属红包" else "给 ${packet?.targetName ?: meta.targetName ?: "Ta"} 的专属红包")
                if (chainText != null) RpTag("接龙 · $chainText")
                if (voiceText != null) RpTag("语音 · $voiceText")
            }
        }

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
                packet != null && forOthers -> Text(
                    if (packet.claimedCount > 0) "对方已领取" else "等待 ${packet.targetName ?: meta.targetName ?: "Ta"} 领取",
                    style = type.secondary,
                    color = colors.muted,
                )
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

/** 详情里的小标签：细描边胶囊 */
@Composable
private fun RpTag(text: String) {
    val colors = KcTheme.colors
    Text(
        text,
        style = KcTheme.type.caption,
        color = colors.text,
        fontWeight = FontWeight.W600,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(50)).border(1.dp, colors.border, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
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
