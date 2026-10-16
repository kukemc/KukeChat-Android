package ink.kuke.chat.feature.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/**
 * 表情回应的「最近使用」，记在本机。只是一串 emoji，不按账号分开。
 * 电脑版对应 KukeChat-Client/src/components/chat/desktop/reactions/reactionRecent.ts，同样最多记 16 个。
 */
object ReactionRecents {
    private const val PREFS = "kukechat.reactions"
    private const val KEY = "recent"

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(' ')?.filter { it.isNotBlank() }?.take(MaxRecentReactions).orEmpty()

    /** 用过的表情挪到最前面 */
    fun remember(context: Context, emoji: String) {
        val next = (listOf(emoji) + read(context).filter { it != emoji }).take(MaxRecentReactions)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString(" ")).apply()
    }
}

/** 页签上的代表表情去色：选中时才恢复颜色，面板整体保持墨色灰阶 */
private fun Modifier.grayscale(enabled: Boolean): Modifier = if (!enabled) this else drawWithCache {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(size.toRect(), paint)
            drawContent()
            canvas.restore()
        }
    }
}

/**
 * 长按消息时菜单上方的表情回应：
 * - 收起：一行快捷表情（最近使用在前，不够用默认常用补）+ 「+」展开；
 * - 展开：顶部分类页签 + 可上下滑动的分类网格，点页签跳到对应分类；高度不超过 [maxPanelHeight]。
 * 点一个就交给 [onPick]（沿用原来的切换逻辑：已经回应过的再点是取消），新加的回应记进最近使用。
 */
@Composable
fun ReactionPicker(
    message: Message,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    maxPanelHeight: Dp,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    // 打开时取一次，菜单开着的时候不跟着变
    val recent = remember { ReactionRecents.read(context) }
    val mine = remember(message.reactions) { message.reactions.filter { it.reactedByMe }.map { it.emoji }.toSet() }
    val pick: (String) -> Unit = { emoji ->
        if (emoji !in mine) ReactionRecents.remember(context, emoji)
        onPick(emoji)
    }

    if (!expanded) {
        val quick = remember(recent) { (recent + QuickReactions).distinct().take(6) }
        Row(
            modifier.shadow(8.dp, CircleShape).background(colors.raised, CircleShape).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            quick.forEach { emoji ->
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .then(if (emoji in mine) Modifier.background(colors.text.copy(alpha = 0.06f)) else Modifier)
                        .clickable { pick(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 26.sp) }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.text.copy(alpha = 0.06f)).clickable { onExpandedChange(true) },
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.plus, tint = colors.muted, size = 18.dp, contentDescription = "展开更多表情") }
        }
        return
    }

    val categories = remember(recent) {
        val frequent = if (recent.isNotEmpty()) ReactionCategory("recent", "最近使用", "🕘", recent)
        else ReactionCategory("recent", "常用", "🕘", DefaultFrequentReactions)
        listOf(frequent) + ReactionCategories
    }
    // 网格里每个分类是「标题（占满一行）+ 表情」，先算出每个标题在列表里的下标，页签跳转和跟随滚动都靠它
    val headerIndex = remember(categories) {
        var index = 0
        categories.map { category -> index.also { index += 1 + category.emojis.size } }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    val followed by remember(headerIndex) {
        derivedStateOf {
            if (!gridState.canScrollForward && gridState.firstVisibleItemIndex > 0) headerIndex.lastIndex
            else headerIndex.indexOfLast { it <= gridState.firstVisibleItemIndex }.coerceAtLeast(0)
        }
    }
    val active = jumpTarget ?: followed
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier.fillMaxWidth()
            .shadow(8.dp, shape).background(colors.raised, shape).border(0.5.dp, colors.border, shape)
            .clip(shape),
    ) {
        // 页签行：左边分类（窄屏可横滑），右边收起
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                categories.forEachIndexed { index, category ->
                    val selected = index == active
                    Column(
                        Modifier.width(38.dp).clip(RoundedCornerShape(10.dp)).clickable {
                            jumpTarget = index
                            scope.launch {
                                try { gridState.animateScrollToItem(headerIndex[index]) } finally { jumpTarget = null }
                            }
                        }.padding(top = 6.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            category.icon,
                            fontSize = 19.sp,
                            modifier = Modifier.grayscale(!selected),
                            color = colors.text.copy(alpha = if (selected) 1f else 0.42f),
                        )
                        Box(
                            Modifier.padding(top = 5.dp).size(width = 14.dp, height = 2.dp).clip(CircleShape)
                                .background(if (selected) colors.text else colors.text.copy(alpha = 0f)),
                        )
                    }
                }
            }
            Box(
                Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape).background(colors.text.copy(alpha = 0.06f)).clickable { onExpandedChange(false) },
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.chevronDown, tint = colors.muted, size = 16.dp, contentDescription = "收起") }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 42.dp),
            state = gridState,
            modifier = Modifier.fillMaxWidth().height(min(maxPanelHeight - 56.dp, 340.dp)),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 10.dp),
        ) {
            categories.forEach { category ->
                item(key = "h-${category.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    Text(
                        category.title,
                        color = colors.muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W600,
                        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                category.emojis.forEach { emoji ->
                    item(key = "${category.id}-$emoji", contentType = "emoji") {
                        val reacted = emoji in mine
                        Box(
                            Modifier.padding(1.dp).size(42.dp).clip(RoundedCornerShape(12.dp))
                                .then(
                                    if (reacted) Modifier.background(colors.text.copy(alpha = 0.06f)).border(1.dp, colors.borderStrong, RoundedCornerShape(12.dp))
                                    else Modifier,
                                )
                                .clickable { pick(emoji) },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, fontSize = 25.sp) }
                    }
                }
            }
        }
    }
}
