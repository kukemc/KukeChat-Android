package ink.kuke.chat.feature.spark

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.Spark
import ink.kuke.chat.core.model.SparkDetail
import ink.kuke.chat.core.model.SparkLevelRule
import ink.kuke.chat.core.model.SparkRelightResult
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.ui.components.Notice
import ink.kuke.chat.ui.components.NoticeTone
import ink.kuke.chat.ui.components.Notices
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 服务端没给规则时的兜底（和 docs/sparks.md 第 1 节一致） */
private val DEFAULT_LEVELS = listOf(
    SparkLevelRule(1, "火苗", "ember", 1),
    SparkLevelRule(2, "小火花", "spark", 3),
    SparkLevelRule(3, "火花", "flame", 7),
    SparkLevelRule(4, "大火花", "blaze", 30),
    SparkLevelRule(5, "烈焰", "inferno", 100),
    SparkLevelRule(6, "永恒之火", "eternal", 365),
)

private val TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

private fun shortTime(iso: String?): String? = parseInstant(iso)?.let(TIME::format)

/**
 * 私聊顶栏名字旁的火花：图标 + 天数，点开说明面板。会话没有火花时什么也不画。
 * 顶栏拿不到 graph，从 Application 取。
 */
@Composable
fun SparkHeaderEntry(conversation: Conversation, friendId: Long) {
    val spark = conversation.spark ?: return
    if (!spark.visible) return
    val graph = (LocalContext.current.applicationContext as KukeApp).graph
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SparkMark(spark, size = 16.dp, modifier = Modifier.padding(start = 2.dp), onClick = { open = true })
        // 今天还没续上：名字旁一句轻提示（快熄灭时说得更明白）
        if (!spark.todayLit && !spark.isOut) {
            Text(
                if (spark.isDying) "快熄灭了" else "今天还没续火花",
                fontSize = 11.sp,
                color = KcTheme.colors.subtle,
                maxLines = 1,
                modifier = Modifier.clickable { open = true },
            )
        }
    }
    if (open) SparkSheet(graph, friendId, conversation.id, spark, onDismiss = { open = false })
}

/** 好友资料页上的一行「火花」：点开说明面板。自己拉一次详情；拉不到（旧后端 / 不是好友）就不画。 */
@Composable
fun SparkProfileRow(graph: AppGraph, friendId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var detail by remember(friendId) { mutableStateOf<SparkDetail?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(friendId) {
        detail = runCatching { KcJson.decodeFromJsonElement(SparkDetail.serializer(), graph.api.get("/friends/$friendId/spark")) }.getOrNull()
    }
    val d = detail ?: return
    // 会话列表里的同一份（spark.updated 实时更新）优先
    val items by graph.conversations.items.collectAsState()
    val live = d.conversationId?.let { id -> items.firstOrNull { it.id == id } }?.spark
    val spark = live ?: d.spark
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(colors.panel).clickable { open = true }.padding(horizontal = 16.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SparkGlyph(if (spark.isOut) spark.lostLevelKey else spark.levelKey, size = 22.dp, dim = spark.isDying, out = spark.isOut || spark.status == "none")
        Spacer(Modifier.width(12.dp))
        Text("火花", style = type.rowTitle, color = colors.text)
        Spacer(Modifier.weight(1f))
        Text(summary(spark), style = type.secondary, color = colors.muted)
        Spacer(Modifier.width(4.dp))
        KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
    }
    if (open) SparkSheet(graph, friendId, d.conversationId, spark, onDismiss = { open = false }, preloaded = d)
}

private fun summary(spark: Spark): String = when (spark.status) {
    "none" -> "还没点燃"
    "out" -> if (spark.canRelight) "已熄灭 · 可重燃" else "已熄灭"
    else -> "${spark.levelName ?: "火花"} · ${spark.days} 天"
}

/**
 * 火花说明面板：等级、天数、历史最高、下一级还差几天、今天续没续、规则；能重燃时给墨色胶囊按钮（先确认会花多少金币）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkSheet(graph: AppGraph, friendId: Long, conversationId: Long?, initial: Spark?, onDismiss: () -> Unit, preloaded: SparkDetail? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    var detail by remember(friendId) { mutableStateOf(preloaded) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(friendId) {
        runCatching { KcJson.decodeFromJsonElement(SparkDetail.serializer(), graph.api.get("/friends/$friendId/spark")) }
            .onSuccess { detail = it }
            .onFailure { if (detail == null && initial == null) Notices.show(Notice("spark-load", it.userMessage(), tone = NoticeTone.Warning)) }
    }
    val items by graph.conversations.items.collectAsState()
    val convId = conversationId ?: detail?.conversationId
    val live = convId?.let { id -> items.firstOrNull { it.id == id } }?.spark
    val spark = live ?: detail?.spark ?: initial ?: Spark()
    val rules = detail?.rules
    val levels = rules?.levels?.takeIf { it.isNotEmpty() } ?: DEFAULT_LEVELS

    fun relight() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = KcJson.decodeFromJsonElement(SparkRelightResult.serializer(), graph.api.post("/friends/$friendId/spark/relight"))
                detail = detail?.copy(spark = result.spark) ?: SparkDetail(friendId, convId, result.spark)
                convId?.let { graph.conversations.applySpark(it, result.spark) }
                Notices.show(Notice("spark-relit", "火花重燃了", "恢复到连续 ${result.spark.days} 天", tone = NoticeTone.Success))
                confirming = false
            } catch (error: ApiException) {
                val obj = error.detail as? kotlinx.serialization.json.JsonObject
                val shortfall = obj?.get("shortfall")?.jsonPrimitive?.intOrNull
                val text = if (error.status == 402 && shortfall != null && shortfall > 0) "${error.message}，还差 $shortfall 金币" else error.message
                Notices.show(Notice("spark-relit", text, tone = NoticeTone.Warning))
                confirming = false
                // 状态可能刚变（对方抢先重燃 / 过了期限）：重拉一次
                runCatching { KcJson.decodeFromJsonElement(SparkDetail.serializer(), graph.api.get("/friends/$friendId/spark")) }.onSuccess { detail = it }
            } catch (error: Exception) {
                Notices.show(Notice("spark-relit", error.userMessage(), tone = NoticeTone.Warning))
            } finally {
                busy = false
            }
        }
    }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = colors.panel) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 主视觉：大图标 + 天数 + 等级名
            val out = spark.isOut || spark.status == "none"
            SparkGlyph(if (spark.isOut) spark.lostLevelKey else spark.levelKey, size = 64.dp, dim = spark.isDying, out = out)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                val number = if (spark.isOut) spark.lostDays else spark.days
                Text(number.toString(), fontSize = 40.sp, fontWeight = FontWeight.W700, color = if (out) colors.subtle else colors.text)
                Text(" 天", style = type.secondary, color = colors.muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                when (spark.status) {
                    "none" -> "还没点燃"
                    "out" -> "火花熄灭了"
                    else -> spark.levelName ?: "火花"
                },
                style = type.title, color = colors.text,
            )
            Spacer(Modifier.height(8.dp))
            Text(statusLine(spark), style = type.secondary, color = if (spark.isDying || spark.isOut) colors.text else colors.muted, textAlign = TextAlign.Center)

            // 数字两格：历史最高 / 下一级
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCell("历史最高", "${spark.bestDays} 天", Modifier.weight(1f))
                val next = when {
                    spark.isOut || spark.status == "none" -> "—"
                    spark.daysToNext == null -> "已满级"
                    else -> "还差 ${spark.daysToNext} 天"
                }
                StatCell(spark.nextLevelName?.takeIf { !spark.isOut && spark.status != "none" }?.let { "下一级「$it」" } ?: "下一级", next, Modifier.weight(1f))
            }

            // 等级一排
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                levels.forEach { lv ->
                    val reached = !spark.isOut && spark.days >= lv.minDays
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        SparkGlyph(lv.key, size = 22.dp, out = !reached)
                        Text(lv.name, fontSize = 10.sp, color = if (reached) colors.text else colors.subtle, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                        Text("${lv.minDays} 天", fontSize = 9.sp, color = colors.subtle, maxLines = 1)
                    }
                }
            }

            // 重燃
            if (spark.isOut) {
                Spacer(Modifier.height(20.dp))
                when {
                    spark.canRelight -> {
                        InkPill("花 ${spark.relightCost} 金币重燃", enabled = !busy, modifier = Modifier.widthIn(min = 200.dp)) { confirming = true }
                        shortTime(spark.relightDeadline)?.let {
                            Text("$it 前可以重燃，恢复到 ${spark.lostDays} 天", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    spark.relightBlock == "cooldown" -> Text(
                        "每对好友 ${rules?.relightCooldownDays ?: 30} 天内只能重燃一次" + (shortTime(spark.relightCooldownUntil)?.let { "，$it 后可再用" } ?: ""),
                        style = type.caption, color = colors.muted, textAlign = TextAlign.Center,
                    )
                    else -> Text("已经过了重燃期限，重新聊起来就能再点燃", style = type.caption, color = colors.muted)
                }
            }

            // 规则
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Spacer(Modifier.height(16.dp))
            Text("规则", style = type.group, color = colors.muted, modifier = Modifier.fillMaxWidth())
            val lines = rules?.rules?.takeIf { it.isNotEmpty() } ?: listOf(
                "同一天里你们双方都至少发一条消息，火花天数 +1。",
                "昨天没续上，火花快熄灭；连续两天没续上，火花熄灭。",
                "熄灭后 3 天内，可以花 100 金币重燃，恢复原来的天数。",
            )
            lines.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(4.dp).clip(CircleShape).background(colors.subtle))
                    Text(line, style = type.secondary, color = colors.muted, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirming) {
        SparkConfirm(
            title = "重燃火花",
            text = "会花 ${spark.relightCost} 金币，把火花恢复到连续 ${spark.lostDays} 天。每对好友 ${rules?.relightCooldownDays ?: 30} 天内只能重燃一次。",
            confirmLabel = if (busy) "重燃中…" else "花 ${spark.relightCost} 金币",
            onConfirm = ::relight,
            onDismiss = { if (!busy) confirming = false },
        )
    }
}

private fun statusLine(spark: Spark): String = when (spark.status) {
    "lit" -> "今天已经续上了"
    "pending" -> when {
        spark.iSpokeToday -> "今天还没续火花，你已经发过了，等 TA 回一句"
        spark.friendSpokeToday -> "今天还没续火花，TA 已经说过话了，回一句就能续上"
        else -> "今天还没续火花，你们各发一条消息就能续上"
    }
    "dying" -> "火花快熄灭了，今天聊两句就能续上"
    "out" -> if (spark.lostDays > 0) "连续两天没续上，原来的 ${spark.lostDays} 天熄灭了" else "火花熄灭了"
    else -> "同一天里你们都发一条消息，就能点燃火花"
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    val colors = KcTheme.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(colors.text.copy(alpha = 0.04f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = KcTheme.type.caption, color = colors.muted, maxLines = 1)
        Text(value, style = KcTheme.type.rowTitle.copy(fontWeight = FontWeight.W600), color = colors.text, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
    }
}

/** 墨色胶囊按钮 */
@Composable
private fun InkPill(text: String, enabled: Boolean = true, secondary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Box(
        modifier
            .height(44.dp)
            .pressScale(press)
            .clip(RoundedCornerShape(50))
            .background(if (secondary) colors.text.copy(alpha = 0.06f) else colors.text.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(interactionSource = press, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KcTheme.type.rowTitle.copy(fontWeight = FontWeight.W600), color = if (secondary) colors.text else colors.panel)
    }
}

@Composable
private fun SparkConfirm(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(22.dp),
        ) {
            Text(title, style = KcTheme.type.title, color = colors.text)
            Text(text, style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkPill("取消", secondary = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                InkPill(confirmLabel, modifier = Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}
