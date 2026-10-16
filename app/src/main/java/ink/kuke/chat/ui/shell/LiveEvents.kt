package ink.kuke.chat.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.level.LevelGlyph
import ink.kuke.chat.feature.level.LevelIconKind
import ink.kuke.chat.feature.level.LevelIcons
import ink.kuke.chat.feature.level.formatDays
import ink.kuke.chat.feature.level.splitLevel
import ink.kuke.chat.feature.shop.Cosmetics
import ink.kuke.chat.feature.shop.ShopLive
import ink.kuke.chat.feature.shop.ShopOverview
import ink.kuke.chat.feature.shop.expiryLabel
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.Notice
import ink.kuke.chat.ui.components.NoticeAction
import ink.kuke.chat.ui.components.NoticeTone
import ink.kuke.chat.ui.components.Notices
import ink.kuke.chat.ui.components.rememberReducedMotion
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.sin

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/**
 * 主界面上的实时事件：升级、资料被赞、装扮到手 / 快到期 / 到期、余额变化、装扮变化。
 * 提示都走 [Notices]（顶部胶囊），缓存刷新各自交给 Cosmetics / ShopLive。MainShell 里挂一个。
 */
@Composable
internal fun LiveEvents(graph: AppGraph, nav: AppNav) {
    val user = graph.session.session.collectAsStateWithLifecycle().value?.user
    // 登录用户（含持久化的装扮）一变就同步给装扮表；退出登录全部清掉
    LaunchedEffect(user) {
        if (user == null) {
            Cosmetics.clear()
            Notices.clear()
        } else Cosmetics.bindMe(user)
    }
    // 启动时拉一次自己：登录时存下的用户对象可能是旧的装扮
    LaunchedEffect(user?.id) { if (user != null) Cosmetics.refreshMe(graph) }

    LaunchedEffect(graph) {
        graph.realtime.events.collect { event ->
            val data = event.data
            when (event.type) {
                "user.cosmetics.updated" -> Cosmetics.onUpdated(graph, data)
                "shop.balance.changed" -> ShopLive.onBalanceChanged(graph, data)
                "account.level.up" -> onLevelUp(graph, data, nav)
                "user.profile.liked" -> onProfileLiked(data)
                "shop.item.granted", "shop.item.expiring", "shop.item.expired" -> onShopItem(graph, event.type, data, nav, this)
            }
        }
    }
}

// ------------------------------------------------------------------ 升级

private suspend fun onLevelUp(graph: AppGraph, data: JsonObject, nav: AppNav) {
    val level = (data["level"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 } ?: return
    val days = data.num("active_days")
    graph.session.session.value?.user?.let { me -> if (me.accountLevel < level) runCatching { graph.session.updateUser(me.copy(accountLevel = level)) } }
    val daysText = days?.let { "累计活跃 ${formatDays(it)} 天" }
    // 正好换出一个月亮 / 太阳 / 皇冠：庆祝卡；平常多一颗星：普通提示
    val milestone = when {
        level % 64 == 0 -> LevelIconKind.Crown
        level % 16 == 0 -> LevelIconKind.Sun
        level % 4 == 0 -> LevelIconKind.Moon
        else -> null
    }
    if (milestone != null) {
        val count = splitLevel(level).firstOrNull { it.kind == milestone }?.count ?: 1
        Notices.show(
            Notice(
                key = "account.level.up",
                title = "Lv.$level · ${if (count == 1) "点亮了第一个${milestone.label}" else "又多了一个${milestone.label}"}",
                subtitle = listOfNotNull(daysText, "点这里看我的等级").joinToString(" · "),
                tone = NoticeTone.Success,
                hero = { LevelHero(milestone, level) },
                durationMs = 10_000,
                onClick = { nav.go(LevelRoute) },
            ),
        )
        return
    }
    Notices.show(
        Notice(
            key = "account.level.up",
            title = "升到 Lv.$level",
            subtitle = daysText,
            tone = NoticeTone.Success,
            leading = {
                Box(Modifier.size(36.dp).background(KcTheme.colors.warn.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    LevelGlyph(splitLevel(level).first().kind, 20.dp)
                }
            },
            actions = listOf(NoticeAction("看我的等级") { Notices.dismiss("account.level.up"); nav.go(LevelRoute) }),
            durationMs = 6500,
        ),
    )
}

private val Sparks = listOf(-62f to 0.86f, -18f to 1f, 28f to 0.9f, 74f to 0.95f, 128f to 0.88f, 172f to 1f, -118f to 0.95f, -152f to 0.82f)

/** 升级庆祝卡上半部：大图标、后面慢转的光芒、四周几粒碎光，下面一串等级图标。关了动画时都不动 */
@Composable
private fun LevelHero(kind: LevelIconKind, level: Int) {
    val reduced = rememberReducedMotion()
    val gold = Color(0xFFFFC94D)
    val turn: State<Float> = if (reduced) remember { mutableFloatStateOf(0f) } else
        rememberInfiniteTransition(label = "levelRays").animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "levelRaysT")
    val twinkle: State<Float> = if (reduced) remember { mutableFloatStateOf(1f) } else
        rememberInfiniteTransition(label = "levelSpark").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "levelSparkT")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 10.dp)) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(112.dp)) {
                val c = center
                val r = size.minDimension / 2f
                drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.45f), Color.Transparent), center = c, radius = r * 0.8f))
                rotate(turn.value, c) {
                    for (i in 0 until 12) {
                        val a = Math.toRadians(i * 30.0)
                        val b = Math.toRadians(i * 30.0 + 9.0)
                        val path = Path().apply {
                            moveTo(c.x, c.y)
                            lineTo(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                            lineTo(c.x + r * cos(b).toFloat(), c.y + r * sin(b).toFloat())
                            close()
                        }
                        drawPath(path, Brush.radialGradient(listOf(gold.copy(alpha = 0.32f), Color.Transparent), center = c, radius = r))
                    }
                }
                Sparks.forEachIndexed { i, (deg, dist) ->
                    val a = Math.toRadians(deg.toDouble() - 90.0)
                    val d = r * 0.86f * dist
                    val alpha = if (i % 2 == 0) twinkle.value else 1.35f - twinkle.value
                    drawCircle(gold.copy(alpha = alpha.coerceIn(0f, 1f)), radius = (if (i % 3 == 0) 3f else 2f).dp.toPx(), center = Offset(c.x + d * cos(a).toFloat(), c.y + d * sin(a).toFloat()))
                }
            }
            LevelGlyph(kind, 52.dp)
        }
        Text("Lv.$level", color = KcTheme.colors.text, fontSize = 22.sp, fontWeight = FontWeight.W800)
        LevelIcons(level, size = 16.dp, compact = true, modifier = Modifier.padding(top = 4.dp))
    }
}

// ------------------------------------------------------------------ 资料被赞

private fun onProfileLiked(data: JsonObject) {
    if (data.num("like_count") == null) return
    val from = (data["from_user"] as? JsonObject)?.let { runCatching { KcJson.decodeFromJsonElement(User.serializer(), it) }.getOrNull() }
    val name = from?.displayName ?: "有人"
    val today = data.num("today_count")?.toInt() ?: 0
    Notices.show(
        Notice(
            key = "user.profile.liked",
            title = "$name 赞了你",
            subtitle = if (today > 0) "今天 +$today" else null,
            tone = NoticeTone.Info,
            icon = KcIcons.like,
            leading = from?.let { u -> { Avatar(u.avatarUrl, u.displayName, u.id, 32.dp) } },
            durationMs = 4500,
        ),
    )
}

// ------------------------------------------------------------------ 装扮

private val KnownCategories = setOf("bubble", "frame")
private val SourceText = mapOf("level" to "升级解锁", "grant" to "收到赠送", "gift" to "收到礼物")

private fun onShopItem(graph: AppGraph, type: String, data: JsonObject, nav: AppNav, scope: CoroutineScope) {
    val item = data["item"] as? JsonObject ?: return
    val sku = item.str("sku") ?: return
    val category = item.str("category")?.takeIf { it in KnownCategories } ?: return
    val granted = type == "shop.item.granted"
    val name = item.str("name")?.takeIf { it.isNotBlank() } ?: if (granted) "新装扮" else "装扮"
    val key = "$type:$sku"
    val goShop = { Notices.dismiss(key); nav.go(ShopRoute) }

    if (granted) {
        fun wear() {
            Notices.show(Notice(key, "获得新装扮「$name」", "换上中…", NoticeTone.Info, KcIcons.sparkles, actions = listOf(NoticeAction("换上中…", enabled = false) {}), durationMs = 12_000))
            scope.launch {
                val slot = graph.screenCache.get<ShopOverview>("shop")?.categories?.firstOrNull { it.key == category }?.slot ?: category
                runCatching { graph.api.put("/shop/equipped", buildJsonObject { put("slot", slot); put("sku", sku) }) }
                    .onSuccess {
                        Notices.show(Notice(key, "已换上「$name」", if (category == "frame") "你的头像会带着这个框" else "你发出的消息会用这个气泡", NoticeTone.Success, KcIcons.check, durationMs = 2600))
                        Cosmetics.refreshMe(graph)
                    }
                    .onFailure {
                        Notices.show(
                            Notice(
                                key, "没能换上「$name」", it.userMessage(), NoticeTone.Warning, KcIcons.alert,
                                actions = listOf(NoticeAction("再试一次") { wear() }, NoticeAction("去商店看看", onClick = goShop)), durationMs = 6000,
                            ),
                        )
                    }
            }
        }
        Notices.show(
            Notice(
                key, "获得新装扮「$name」", SourceText[data.str("source")] ?: "已经放进你的装扮里", NoticeTone.Success, KcIcons.sparkles,
                actions = listOf(NoticeAction("现在换上") { wear() }, NoticeAction("去商店看看", onClick = goShop)), durationMs = 12_000,
            ),
        )
        return
    }
    val expiring = type == "shop.item.expiring"
    val left = expiryLabel(data.str("expires_at"), System.currentTimeMillis())?.first ?: "还剩不到 3 天"
    Notices.show(
        Notice(
            key,
            if (expiring) "「$name」快到期了" else "「$name」已到期",
            if (expiring) "$left，到期后会自动卸下。续期可以接着用。" else "这件限时装扮已经收回，还想用的话可以再买。",
            NoticeTone.Warning, KcIcons.clock,
            actions = listOf(NoticeAction(if (expiring) "去续期" else "去商店看看", onClick = goShop)), durationMs = 12_000,
        ),
    )
}
