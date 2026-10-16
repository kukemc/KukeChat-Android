package ink.kuke.chat.feature.level

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.jury.Leaderboard
import ink.kuke.chat.feature.settings.CenterText
import ink.kuke.chat.feature.settings.LoadBox
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.feature.settings.rememberLoader
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.shell.IntimacyRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 排行榜上有哪些榜：功能没开放（接口 404、enabled=false）的页签不出现 */
private enum class Board { Level, Charm, Intimacy, Jury }

private class Availability(val level: Boolean, val charm: Boolean, val charmLabel: String, val intimacy: Boolean, val jury: Boolean = false)

private suspend fun probe(graph: AppGraph): Availability = coroutineScope {
    val level = async { runCatching { graph.api.get("/levels/rules"); true }.getOrDefault(false) }
    val gifts = async {
        runCatching {
            val obj = graph.api.get("/gifts/catalog") as JsonObject
            (obj["enabled"]?.jsonPrimitive?.booleanOrNull == true) to (obj["charm_label"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null } ?: "心意值")
        }.getOrDefault(false to "心意值")
    }
    val intimacy = async {
        runCatching { (graph.api.get("/intimacy/friends") as JsonObject)["enabled"]?.jsonPrimitive?.booleanOrNull == true }.getOrDefault(false)
    }
    // 风纪榜：配置里下发了 leaderboard 能力才有
    val jury = async {
        runCatching {
            val caps = (graph.api.get("/jury/config") as JsonObject)["capabilities"] as? kotlinx.serialization.json.JsonArray
            caps?.any { it.jsonPrimitive.contentOrNull == "leaderboard" } == true
        }.getOrDefault(false)
    }
    val (charm, label) = gifts.await()
    Availability(level.await(), charm, label, intimacy.await(), jury.await())
}

/** 排行榜（设计稿 P26 的第二页签，独立入口）：活跃榜 · 心意榜 · 亲密榜，页签只在对应功能开启时出现 */
@Composable
fun RankingScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("ranking.probe")) { probe(graph) }
    var wanted by remember { mutableIntStateOf(0) }

    KcPage(title = "排行榜", onBack = { nav.back() }) {
        LoadBox(loader) { available ->
            val boards = buildList {
                if (available.level) add(Board.Level to "活跃榜")
                if (available.charm) add(Board.Charm to available.charmLabel.removeSuffix("值").ifBlank { available.charmLabel } + "榜")
                if (available.intimacy) add(Board.Intimacy to "亲密榜")
                if (available.jury) add(Board.Jury to "风纪榜")
            }
            if (boards.isEmpty()) {
                CenterText("排行榜还没有开放\n当前连接的服务端还没有开放账号等级、礼物或亲密度排行，开放后这里会自动出现。")
            } else {
                val index = wanted.coerceIn(0, boards.lastIndex)
                Column(Modifier.fillMaxSize()) {
                    if (boards.size > 1) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Segmented(boards.map { it.second }, index, { wanted = it })
                        }
                    }
                    when (boards[index].first) {
                        Board.Level -> LevelBoardTab(graph)
                        Board.Charm -> CharmBoard(graph, available.charmLabel)
                        Board.Intimacy -> IntimacyBoard(graph)
                        Board.Jury -> JuryBoard(graph)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 心意榜

@Serializable
private data class CharmItem(val rank: Int = 0, val user: User? = null, val charm: Int = 0)

@Serializable
private data class CharmMe(val rank: Int? = null, val charm: Int = 0)

@Serializable
private data class CharmBoardData(val items: List<CharmItem> = emptyList(), val me: CharmMe? = null)

private val MedalGold = Color(0xFFF5B301)
private val MedalSilver = Color(0xFFA8B0BC)
private val MedalBronze = Color(0xFFC98A5B)

@Composable
private fun RankBadge(rank: Int) {
    val colors = KcTheme.colors
    val medal = when (rank) { 1 -> MedalGold; 2 -> MedalSilver; 3 -> MedalBronze; else -> null }
    Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
        if (medal != null) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(medal), contentAlignment = Alignment.Center) {
                Text(rank.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.W800)
            }
        } else {
            Text(rank.toString(), style = KcTheme.type.secondary, color = colors.muted, fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun CharmBoard(graph: AppGraph, label: String) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    var period by remember { mutableIntStateOf(0) }
    val key = if (period == 0) "all" else "month"
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Segmented(listOf("总榜", "月榜"), period, { period = it }) }
        val loader = rememberLoader(key, cache = graph.screenCache.slot("ranking.charm.$key")) { KcJson.decodeFromJsonElement(CharmBoardData.serializer(), graph.api.get("/gifts/leaderboard?period=$key&limit=50")) }
        LoadBox(loader) { board ->
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (board.me != null) 88.dp else 24.dp)) {
                    if (board.items.isEmpty()) {
                        item { Text("还没有人收到礼物", style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 64.dp)) }
                    }
                    items(board.items, key = { it.rank.toString() + "-" + (it.user?.id ?: 0) }) { item ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { item.user?.let { nav.go(UserRoute(it.id)) } }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RankBadge(item.rank)
                            Avatar(item.user?.avatarUrl, item.user?.displayName ?: "?", tone = item.user?.id ?: 0, size = 40.dp)
                            Text(item.user?.displayName ?: "已注销用户", style = type.rowTitle, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("%,d".format(item.charm), style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
                                Text(label, style = type.caption, color = colors.muted)
                            }
                        }
                    }
                }
                board.me?.let { me ->
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.accentSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("你的位置", style = type.caption, color = colors.accent, modifier = Modifier.padding(end = 10.dp))
                            Text(formatRank(me.rank), style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700, modifier = Modifier.weight(1f))
                            Text("$label ${"%,d".format(me.charm)}", style = type.caption, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 风纪榜

/** 风纪榜：按风纪值排；没公开身份的委员显示为「匿名委员」，点不进主页 */
@Composable
private fun JuryBoard(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    var period by remember { mutableIntStateOf(0) }
    val key = listOf("week", "month", "all")[period]
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Segmented(listOf("本周", "本月", "累计"), period, { period = it }) }
        val loader = rememberLoader(key, cache = graph.screenCache.slot("ranking.jury.$key")) {
            KcJson.decodeFromJsonElement(Leaderboard.serializer(), graph.api.get("/jury/leaderboard?period=$key&limit=50"))
        }
        LoadBox(loader) { board ->
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (board.me != null) 88.dp else 24.dp)) {
                    if (board.items.isEmpty()) {
                        item { Text("本期还没有人上榜", style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 64.dp)) }
                    }
                    items(board.items, key = { "${it.rank}-${it.user?.id ?: 0}" }) { item ->
                        val user = item.user
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (item.me) colors.accentSoft else Color.Transparent)
                                .clickable(enabled = user != null) { user?.let { nav.go(UserRoute(it.id)) } }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RankBadge(item.rank)
                            if (user != null) Avatar(user.avatarUrl, user.displayName, tone = user.id, size = 40.dp)
                            else Box(Modifier.size(40.dp).clip(CircleShape).background(colors.panelMuted), contentAlignment = Alignment.Center) { Text("匿", style = type.caption, color = colors.muted) }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(if (item.me) "我" else user?.displayName ?: "匿名委员", style = type.rowTitle, color = colors.text, maxLines = 1)
                                Text(
                                    listOfNotNull(juryTierName(item.tier), item.votes?.let { "$it 票" }).joinToString(" · "),
                                    style = type.caption, color = colors.muted, maxLines = 1,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("%,d".format(item.points), style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
                                Text("风纪值", style = type.caption, color = colors.muted)
                            }
                        }
                    }
                }
                board.me?.let { me ->
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.accentSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("你的位置", style = type.caption, color = colors.accent, modifier = Modifier.padding(end = 10.dp))
                            Text("第 ${me.rank} 名", style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700, modifier = Modifier.weight(1f))
                            Text("风纪值 ${"%,d".format(me.points)}", style = type.caption, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

private fun juryTierName(tier: String?): String? = when (tier) {
    "trainee" -> "见习委员"
    "regular" -> "正式委员"
    "senior" -> "资深委员"
    else -> null
}

// ------------------------------------------------------------------ 亲密榜

@Serializable
private data class IntimacyItem(
    val rank: Int = 0,
    val friend: User? = null,
    val intimacy: Int = 0,
    val level: Int = 0,
    @SerialName("level_name") val levelName: String = "",
    @SerialName("today_gained") val todayGained: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0,
)

@Serializable
private data class IntimacyList(val enabled: Boolean = false, val items: List<IntimacyItem> = emptyList())

/** 亲密榜：我自己的好友按亲密度排，只有自己看得到 */
@Composable
private fun IntimacyBoard(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val loader = rememberLoader(Unit, cache = graph.screenCache.slot("ranking.intimacy")) { KcJson.decodeFromJsonElement(IntimacyList.serializer(), graph.api.get("/intimacy/friends")) }
    LoadBox(loader) { list ->
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Text("只有你自己看得到，和好友聊天、互动会涨亲密度。", style = type.caption, color = colors.muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (list.items.isEmpty()) {
                item { Text("还没有好友，加几个好友再来看看", style = type.secondary, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 64.dp)) }
            }
            items(list.items, key = { it.friend?.id ?: it.rank.toLong() }) { item ->
                val friend = item.friend ?: return@items
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable { nav.go(IntimacyRoute(friend.id)) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RankBadge(item.rank)
                    Avatar(friend.avatarUrl, friend.displayName, tone = friend.id, size = 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(friend.displayName, style = type.rowTitle, color = colors.text, maxLines = 1)
                        Text(
                            buildString {
                                append("Lv.${item.level}")
                                if (item.levelName.isNotBlank()) append(" ${item.levelName}")
                                if (item.streakDays > 0) append(" · 连续 ${item.streakDays} 天")
                            },
                            style = type.caption, color = colors.muted, maxLines = 1,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("%,d".format(item.intimacy), style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700)
                        if (item.todayGained > 0) Text("今天 +${item.todayGained}", style = type.caption, color = colors.ok)
                    }
                }
            }
        }
    }
}
