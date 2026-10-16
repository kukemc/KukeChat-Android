package ink.kuke.chat.feature.gifts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一条提示停留多久 */
private const val LifetimeMs = 5_000L

/**
 * 「有人送了我礼物」的顶部胶囊（实时事件 gift.received，只发给收礼人，无论有没有落聊天消息）。
 * 停在哪个页面都会出现；点一下过去看：在会话里送的进那个会话（已经在那个会话里就只收起），从主页送的进自己的主页（礼物墙）。
 * 往上划掉；几条连着来就排队一条一条出。收到时顺手把我的心意值、礼物墙、记录刷新掉（Gifts.absorb）。
 * 同时负责让 Gifts 知道当前登录的是谁（礼物卡片判断「你」用），所以挂在主外壳上，整个登录期间只有一个。
 */
@Composable
fun BoxScope.GiftToastHost(graph: AppGraph) {
    val session by graph.session.session.collectAsStateWithLifecycle()
    val me = session?.user?.id
    var lastMe by remember { mutableStateOf(me) }
    LaunchedEffect(me) {
        if (lastMe != null && me != lastMe) Gifts.reset()
        lastMe = me
        Gifts.meId = me
    }

    val queue = remember { mutableStateListOf<GiftRecord>() }
    val shownIds = remember { HashSet<Long>() }
    val view = LocalView.current
    LaunchedEffect(graph) {
        graph.realtime.events.collect { event ->
            if (event.type != "gift.received") return@collect
            val record = parseGiftRecord(event.data["record"]) ?: return@collect
            Gifts.absorb(record)
            if (shownIds.add(record.id) && queue.size < 4) queue.add(record)
        }
    }

    val current = queue.firstOrNull()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(current?.id) {
        if (current == null) return@LaunchedEffect
        visible = true
        Haptics.tick(view)
        delay(LifetimeMs)
        visible = false
        delay(260)
        queue.remove(current)
    }
    val scope = rememberCoroutineScope()
    fun dismiss() {
        val record = current ?: return
        visible = false
        scope.launch {
            delay(260)
            queue.remove(record)
        }
    }

    AnimatedVisibility(
        visible = visible && current != null,
        enter = fadeIn() + slideInVertically(spring(dampingRatio = 0.68f, stiffness = 420f)) { -it } + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp),
    ) {
        var last by remember { mutableStateOf(current) }
        if (current != null) last = current
        last?.let { record -> GiftToast(graph, record, onDismiss = ::dismiss) }
    }
}

@Composable
private fun GiftToast(graph: AppGraph, record: GiftRecord, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val catalog by Gifts.catalog.collectAsStateWithLifecycle()
    val charmLabel = catalog?.charmLabel ?: DefaultCharmLabel
    val from = record.fromUser?.name ?: "有人"
    val here = record.conversationId != null && record.conversationId == graph.conversations.visibleConversationId
    val detail = when {
        record.message != null -> "「${record.message}」"
        record.item != null -> "已经放进你的装扮里"
        else -> "$charmLabel +${coins(record.amount)}"
    }
    val subtitle = if (record.onBirthday) "生日礼物 · $detail" else detail
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val press = rememberInteraction()
    val wiggle = remember { Animatable(0.4f) }
    LaunchedEffect(record.id) { wiggle.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 300f)) }

    Row(
        Modifier
            .widthIn(max = 420.dp)
            .graphicsLayer { translationY = drag.value.coerceAtMost(0f); alpha = 1f + (drag.value / 300f).coerceIn(-1f, 0f) }
            .pointerInput(record.id) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (drag.value < -40f) onDismiss() else drag.animateTo(0f, spring()) } },
                    onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { drag.snapTo((drag.value + amount).coerceAtMost(12f)) }
                }
            }
            .pressScale(press, 0.97f)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.text.copy(alpha = 0.92f))
            .clickable(interactionSource = press, indication = null) {
                onDismiss()
                when {
                    here -> Unit
                    record.conversationId != null -> nav.go(ChatRoute(record.conversationId))
                    else -> Gifts.meId?.let { nav.go(UserRoute(it)) }
                }
            }
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Brush.radialGradient(listOf(GiftColors.gold.copy(alpha = 0.45f), GiftColors.rose.copy(alpha = 0.2f)))),
            contentAlignment = Alignment.Center,
        ) {
            GiftThing(
                record.gift, record.item, 32.dp,
                Modifier.graphicsLayer { scaleX = wiggle.value; scaleY = wiggle.value; rotationZ = (1f - wiggle.value) * -24f },
                wearer = record.toUser?.let { Wearer(it.id, it.name, it.avatarUrl) }, sampleText = "礼",
            )
        }
        Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
            Text(
                "$from 送了你 ${giftThingLabel(record.kind, record.gift, record.item, record.count)}",
                color = colors.chat, fontSize = 13.5.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, color = colors.chat.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!here) KcIcon(KcIcons.chevron, tint = colors.chat.copy(alpha = 0.6f), size = 16.dp, modifier = Modifier.padding(start = 6.dp))
    }
}
