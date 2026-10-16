package ink.kuke.chat.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

private val deadlines = listOf("不限时" to null, "1 小时" to Duration.ofHours(1), "24 小时" to Duration.ofHours(24), "3 天" to Duration.ofDays(3), "7 天" to Duration.ofDays(7))

/** 发起投票（POST /conversations/{id}/polls）：标题 1–80 字，2–10 个选项每个 1–40 字，截止时间一分钟后到 30 天内 */
@Composable
internal fun PollCreateSheet(controller: ChatController, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multiple by remember { mutableStateOf(false) }
    var anonymous by remember { mutableStateOf(false) }
    var deadline by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    val valid = title.trim().isNotEmpty() && title.trim().length <= 80 && filled.size >= 2 && filled.size == filled.distinct().size

    KcSheet(onDismiss = onDismiss, title = "发起投票", maxHeight = 640.dp) {
        SheetForm {
            KcTextField(title, { if (it.length <= 80) title = it }, "投票标题（最多 80 字）")
            options.forEachIndexed { index, value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KcTextField(value, { if (it.length <= 40) options[index] = it }, "选项 ${index + 1}", Modifier.weight(1f))
                    if (options.size > 2) {
                        KcIcon(KcIcons.close, tint = colors.muted, size = 20.dp, modifier = Modifier.padding(start = 10.dp).clickable { options.removeAt(index) })
                    }
                }
            }
            if (options.size < 10) {
                Text("+ 添加选项", style = KcTheme.type.secondary, color = colors.accent, modifier = Modifier.clickable { options.add("") }.padding(vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("多选", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                KcSwitch(multiple, { multiple = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("匿名投票", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                KcSwitch(anonymous, { anonymous = it })
            }
            Text("截止时间", style = KcTheme.type.secondary, color = colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                deadlines.forEachIndexed { index, (label, _) -> Chip(label, deadline == index) { deadline = index } }
            }
            error?.let { Text(it, style = KcTheme.type.caption, color = colors.danger) }
            KcButton(if (busy) "发起中…" else "发起投票", {
                busy = true
                error = null
                scope.launch {
                    runCatching {
                        val body = buildJsonObject {
                            put("title", title.trim())
                            put("options", buildJsonArray { filled.forEach { add(JsonPrimitive(it)) } })
                            put("multiple", multiple)
                            put("anonymous", anonymous)
                            val span = deadlines[deadline].second
                            if (span == null) put("closes_at", JsonNull) else put("closes_at", Instant.now().plus(span).toString())
                        }
                        controller.graph.api.post("/conversations/${controller.conversationId}/polls", body)
                    }.onSuccess {
                        controller.accept(it)
                        onDismiss()
                    }.onFailure { error = it.userMessage() }
                    busy = false
                }
            }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 某个选项的投票人（非匿名投票）：GET …/polls/{pid}/options/{oid}/voters?limit=50&offset= */
@Composable
internal fun PollVotersSheet(controller: ChatController, poll: PollData, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    var optionId by remember { mutableStateOf(poll.options.firstOrNull { it.votes > 0 }?.id ?: poll.options.firstOrNull()?.id ?: 0L) }
    var voters by remember { mutableStateOf<List<Pair<String, String?>>?>(null) }
    var total by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(optionId, attempt) {
        voters = null
        error = null
        runCatching { controller.graph.api.get("/conversations/${controller.conversationId}/polls/${poll.id}/options/$optionId/voters?limit=100&offset=0").asObj() }
            .onSuccess { obj ->
                total = obj?.i("total") ?: 0
                voters = obj?.a("items")?.mapNotNull { element ->
                    val user = element.asObj() ?: return@mapNotNull null
                    (user.s("nickname")?.takeIf { it.isNotBlank() } ?: user.s("username") ?: "用户") to user.s("avatar_url")
                }.orEmpty()
            }
            .onFailure { error = it.userMessage() }
    }

    KcSheet(onDismiss = onDismiss, title = poll.title, maxHeight = 560.dp) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            poll.options.forEach { option -> Chip("${option.text} · ${option.votes}", optionId == option.id) { optionId = option.id } }
        }
        Box(Modifier.heightIn(min = 160.dp, max = 420.dp).padding(top = 8.dp)) {
            val list = voters
            when {
                error != null -> StateText(error!!, error = true, onRetry = { attempt++ })
                list == null -> StateText("加载中…")
                list.isEmpty() -> StateText("还没有人选这一项")
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(list) { (name, avatar) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(avatar, name, tone = name.hashCode().toLong(), size = 36.dp)
                            Text(name, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (total > list.size) item { Text("共 $total 人，仅显示前 ${list.size} 人", style = KcTheme.type.caption, color = colors.subtle, modifier = Modifier.padding(20.dp)) }
                }
            }
        }
    }
}
