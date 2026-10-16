package ink.kuke.chat.feature.space

import ink.kuke.chat.core.data.slot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.settings.LoadBox
import ink.kuke.chat.feature.settings.Pill
import ink.kuke.chat.feature.settings.ToastHost
import ink.kuke.chat.feature.settings.chinaTime
import ink.kuke.chat.feature.settings.rememberLoader
import ink.kuke.chat.feature.settings.rememberToast
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
private data class AnnReaction(val emoji: String, val count: Int = 0, @SerialName("reacted_by_me") val reactedByMe: Boolean = false)

@Serializable
private data class Announcement(
    val id: Long,
    val title: String = "",
    val content: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
    val pinned: Boolean = false,
    val author: User? = null,
    val reactions: List<AnnReaction> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
)

private val DefaultQuick = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "👀", "🔥")

private fun dateLabel(iso: String): String = parseInstant(iso)?.let { chinaTime(iso).take(10) } ?: ""

/**
 * 公告：列表 → 详情。详情里有正文（简化的 Markdown：标题、列表、图片、粗体）和表情回应。
 * 回应是开关：同一个表情再点一次取消（POST /announcements/{id}/reactions），先本地改、失败回滚。
 */
@Composable
fun AnnouncementsScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("announcements")) {
        val element = graph.api.get("/announcements")
        val array = (element as? JsonArray) ?: ((element as? JsonObject)?.get("items") as? JsonArray) ?: JsonArray(emptyList())
        KcJson.decodeFromJsonElement(ListSerializer(Announcement.serializer()), array)
    }
    val quickLoader = rememberLoader(Unit, cache = graph.screenCache.slot("announcements.quick")) {
        runCatching {
            val obj = graph.api.get("/announcements/reaction-options") as JsonObject
            (obj["quick"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotEmpty() } }?.distinct()?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: DefaultQuick
    }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    val selected = selectedId?.let { id -> loader.data?.firstOrNull { it.id == id } }
    KcPage(title = if (selected != null) "公告详情" else "公告", onBack = { if (selectedId != null) selectedId = null else nav.back() }) {
        Box(Modifier.fillMaxSize()) {
            if (selected != null) {
                AnnouncementDetail(
                    announcement = selected,
                    quick = quickLoader.data ?: DefaultQuick,
                    onToggle = { emoji ->
                        // 先在本地改，接口回来以服务端为准
                        val before = selected.reactions
                        loader.mutate { list -> list.map { if (it.id == selected.id) it.copy(reactions = toggleLocal(it.reactions, emoji)) else it } }
                        scope.launch {
                            runCatching { graph.api.post("/announcements/${selected.id}/reactions", buildJsonObject { put("emoji", emoji) }) }
                                .onSuccess { element ->
                                    val reactions = ((element as? JsonObject)?.get("reactions") as? JsonArray)?.mapNotNull {
                                        runCatching { KcJson.decodeFromJsonElement(AnnReaction.serializer(), it) }.getOrNull()
                                    }
                                    if (reactions != null) loader.mutate { list -> list.map { if (it.id == selected.id) it.copy(reactions = reactions) else it } }
                                }
                                .onFailure {
                                    loader.mutate { list -> list.map { if (it.id == selected.id) it.copy(reactions = before) else it } }
                                    toast.show(it.userMessage(), scope)
                                }
                        }
                    },
                )
            } else {
                LoadBox(loader) { list ->
                    if (list.isEmpty()) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    KcIcon(KcIcons.announcement, tint = colors.subtle, size = 32.dp)
                                    Text("暂无公告", style = type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp))
                                    Text("还没有发布任何公告，敬请期待～", style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.id }) { item ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).clickable { selectedId = item.id }.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (item.pinned) { Pill("置顶", colors.accent); Box(Modifier.padding(end = 6.dp)) }
                                        Text(item.title, style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600, maxLines = 2, modifier = Modifier.weight(1f))
                                    }
                                    Text(stripForSummary(item.content), style = type.secondary, color = colors.muted, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                                    Text(dateLabel(item.createdAt), style = type.caption, color = colors.subtle, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
            ToastHost(toast)
        }
    }
}

private fun toggleLocal(list: List<AnnReaction>, emoji: String): List<AnnReaction> {
    val mine = list.firstOrNull { it.emoji == emoji }
    return when {
        mine == null -> list + AnnReaction(emoji, 1, true)
        mine.reactedByMe -> list.mapNotNull { if (it.emoji == emoji) (if (it.count <= 1) null else it.copy(count = it.count - 1, reactedByMe = false)) else it }
        else -> list.map { if (it.emoji == emoji) it.copy(count = it.count + 1, reactedByMe = true) else it }
    }
}

private fun stripForSummary(markdown: String): String =
    markdown.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "[图片]").let { ink.kuke.chat.core.ui.stripMarkdown(it) }.take(120)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnouncementDetail(announcement: Announcement, quick: List<String>, onToggle: (String) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
        Endpoints.absolute(announcement.coverUrl)?.let { url ->
            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp)))
            Box(Modifier.padding(top = 12.dp))
        }
        Text(announcement.title, style = type.display.copy(fontSize = (type.display.fontSize.value * 0.8f).sp), color = colors.text)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            announcement.author?.let {
                Avatar(it.avatarUrl, it.displayName, tone = it.id, size = 22.dp)
                Text(it.displayName, style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 6.dp))
            }
            Text(chinaTime(announcement.createdAt), style = type.caption, color = colors.subtle)
            if (announcement.pinned) Pill("置顶", colors.accent, Modifier.padding(start = 8.dp))
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownLite(announcement.content)
        }
        // 表情回应：已有的回应在前，后面补快捷表情
        val shown = announcement.reactions + quick.filter { q -> announcement.reactions.none { it.emoji == q } }.map { AnnReaction(it, 0, false) }
        FlowRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { reaction ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (reaction.reactedByMe) colors.accentSoft else colors.panel)
                        .clickable { onToggle(reaction.emoji) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(reaction.emoji, fontSize = 15.sp)
                    if (reaction.count > 0) {
                        Text(reaction.count.toString(), style = type.caption, color = if (reaction.reactedByMe) colors.accent else colors.muted, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        }
    }
}

/** 公告正文的简化 Markdown：# 标题、- 列表、![](图片)、**粗体** 去标记；其余按段落 */
@Composable
private fun MarkdownLite(content: String) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val image = Regex("^!\\[[^]]*]\\(([^)]+)\\)\\s*$")
    content.split('\n').forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Unit
            image.matches(line.trim()) -> {
                val url = Endpoints.absolute(image.find(line.trim())?.groupValues?.get(1))
                if (url != null) AsyncImage(url, null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
            }
            line.trimStart().startsWith("#") -> {
                val text = line.trimStart().trimStart('#').trim()
                Text(ink.kuke.chat.core.ui.stripMarkdown(text), style = type.title, color = colors.text, modifier = Modifier.padding(top = 6.dp))
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                Text("•  " + ink.kuke.chat.core.ui.stripMarkdown(line.trimStart().drop(2)), style = type.body, color = colors.text)
            }
            else -> Text(ink.kuke.chat.core.ui.stripMarkdown(line), style = type.body, color = colors.text)
        }
    }
}
