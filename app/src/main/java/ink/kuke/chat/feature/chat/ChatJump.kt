package ink.kuke.chat.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.components.softShadow
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 发给「已经开着的那个聊天页」的跳转请求：(会话 id, 消息 id)。
 * 从聊天页进全局搜索、再点结果时，外壳弹回聊天页并放一个请求在这里，聊天页取走后就地跳转（不再叠一个新的聊天页）。
 */
object ChatJumps {
    val pending = mutableStateOf<Pair<Long, Long>?>(null)
}

/** 聊天列表滚动时要看的几样东西，合成一个值给 snapshotFlow 用 */
internal data class ListProbe(val last: Int, val first: Int, val jumping: Boolean, val edge: Long?, val loadingNewer: Boolean)

/**
 * 把一条消息滚到视口中间（聊天列表是 reverseLayout，最新在 index 0、贴底）。
 * [leading]：列表里排在服务端消息前面的项数（发送中 / 失败的本地消息），下标要加上它。
 * 目标已经在屏幕上（[smooth]）就直接平滑滚过去；不在就先 scrollToItem 让它贴在底部内边距上，量出高度，
 * 再往回滚 (视口 - 高度) / 2，比视口还高就让它的顶边对齐视口顶 —— 这几步中间的画面会跳，调用方要在这期间把列表藏起来。
 * 返回目标在不在列表里。
 */
internal suspend fun LazyListState.centerOn(messages: List<Message>, messageId: Long, leading: Int = 0, smooth: Boolean = false): Boolean {
    // 列表刚整段换过：等它重组完再算下标、量尺寸
    if (!smooth) repeat(2) { withFrameNanos { } }
    val found = messages.asReversed().indexOfFirst { it.id == messageId }
    if (found < 0) return false
    val index = found + leading
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return true
    val viewport = info.viewportSize.height
    val wanted = if (item.size >= viewport) viewport - item.size else (viewport - item.size) / 2
    // 倒排：item.offset 是它的底边离视口底（含内边距）的距离，挪到 wanted 要往回滚的量
    val delta = wanted - (item.offset + info.beforeContentPadding)
    // 倒排列表里往回滚（负值）= 内容往上走；最新的几条下面没东西可滚，自然停在能到的位置
    if (delta != 0) {
        if (smooth) animateScrollBy(-delta.toFloat()) else scrollBy(-delta.toFloat())
    }
    return true
}

/** 这条消息现在在不在屏幕上（[leading] 同 centerOn） */
internal fun LazyListState.isShowing(messages: List<Message>, messageId: Long, leading: Int = 0): Boolean {
    val found = messages.asReversed().indexOfFirst { it.id == messageId }
    return found >= 0 && layoutInfo.visibleItemsInfo.any { it.index == found + leading }
}

/** 墨色胶囊（深色主题反转成浅色）：浮在消息区上的小按钮 */
@Composable
internal fun InkCapsule(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier
            .pressScale(press, 0.94f)
            .softShadow(blur = 12.dp, offsetY = 3.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.34f else 0.14f), corner = 15.dp)
            .clip(shape)
            .background(colors.text)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .height(30.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 「🧧 N 个红包未领」：这个会话里我还能领的红包。点一下跳到那条红包消息，多个时从最早的开始逐个轮。
 * 领完、过期、被抢完后自动消失（数据见 ChatController.pendingRedPackets；本地缓存里已知领不了的也先摘掉）。
 */
@Composable
internal fun PendingRedPacketPill(controller: ChatController, modifier: Modifier = Modifier, onJump: (Long) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val items = controller.pendingRedPackets.filter { pending ->
        val state = controller.redPackets[pending.id]
        state == null || (state.active && !state.claimedByMe && !state.expiredAt())
    }.sortedWith(compareBy({ it.createdAt }, { it.messageId }))
    var cursor by remember { mutableIntStateOf(0) }
    // 消失时留住最后的数字，淡出过程中不闪成 0
    var shown by remember { mutableIntStateOf(0) }
    if (items.isNotEmpty()) shown = items.size
    AnimatedVisibility(
        visible = items.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)),
        exit = fadeOut(tween(160)) + scaleOut(tween(180), targetScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)),
    ) {
        InkCapsule(onClick = {
            if (items.isNotEmpty()) {
                val target = items[cursor % items.size]
                cursor = (cursor + 1) % items.size
                onJump(target.messageId)
            }
        }) {
            Text("🧧", style = type.caption)
            Text(
                "$shown 个红包未领",
                style = type.caption.copy(fontWeight = FontWeight.W600),
                color = colors.panel,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}
