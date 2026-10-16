package ink.kuke.chat.feature.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcColors
import ink.kuke.chat.ui.theme.KcTheme

/*
 * 大佬入住：跟随应用主题（浅色 / 深色都用 KcTheme.colors），不再是一整块黑底。
 * 结构：标题 + 一行说明（规则收进 ⓘ）→ 前三名领奖台卡片 → 4 名以后的分组列表。
 * 奖牌色只做点缀（头像描边、名次小圆点、冠军小皇冠），浅色下压深一点，深色下提亮一点。
 */

private fun medal(rank: Int, colors: KcColors): Color = when (rank) {
    1 -> if (colors.isDark) Color(0xFFF2C14E) else Color(0xFFD99A0B)
    2 -> if (colors.isDark) Color(0xFFC3CBD8) else Color(0xFF8E99AB)
    else -> if (colors.isDark) Color(0xFFE0A274) else Color(0xFFB86B34)
}

/** 数字用等宽字形，名次和粉丝数上下对齐 */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** 分组列表左侧到正文的距离：名次 28 + 头像 44 + 间距，分隔线从这里起 */
private val RowInset = 12.dp + 28.dp + 44.dp + 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorsHall(
    state: CreatorsState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 独立页面的顶栏已经写了「大佬入住」，头部就不再重复大标题 */
    showTitle: Boolean = true,
) {
    val total = state.total.coerceAtLeast(state.items.size)
    Box(modifier.fillMaxSize()) {
        when {
            state.loading && !state.loaded -> HallSkeleton(showTitle)
            state.error != null && state.items.isEmpty() -> ErrorBox(state.error.orEmpty(), onRetry)
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, state.items.size) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                        if (state.items.isNotEmpty() && last >= state.items.size - 3) onLoadMore()
                    }
                }
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                        item("hall-head") { HallHeader(total, showTitle) }
                        if (state.items.isEmpty()) {
                            item("hall-empty") { HallEmpty() }
                        } else {
                            item("hall-podium") { Podium(state.items.take(3), onOpen) }
                            val rest = state.items.drop(3)
                            itemsIndexed(rest, key = { _, it -> it.id }) { index, creator ->
                                RankRow(
                                    rank = index + 4,
                                    creator = creator,
                                    first = index == 0,
                                    last = index == rest.lastIndex,
                                    onClick = { onOpen(creator.id) },
                                )
                            }
                            item("hall-foot") {
                                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                                    if (state.loadingMore) LoadingBox(Modifier.size(28.dp))
                                    else if (!state.hasMore && state.items.size > 3) Text("已经到底了", style = KcTheme.type.caption, color = KcTheme.colors.subtle)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 头部：大标题 + 「N 位 CCW 创作者 · 按粉丝数排序」，ⓘ 点开一行入住规则 */
@Composable
private fun HallHeader(total: Int, showTitle: Boolean) {
    val colors = KcTheme.colors
    var showRule by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = if (showTitle) 16.dp else 12.dp, bottom = 12.dp)) {
        if (showTitle) Text("大佬入住", color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.W800)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (showTitle) 2.dp else 0.dp)) {
            Text(
                if (total > 0) "${"%,d".format(total)} 位 CCW 创作者 · 按粉丝数排序" else "CCW 创作者 · 按粉丝数排序",
                style = KcTheme.type.secondary,
                color = colors.muted,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(Modifier.size(32.dp).tap(scale = 0.9f) { showRule = !showRule }, contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.info, tint = if (showRule) colors.accent else colors.subtle, size = 16.dp, contentDescription = "入住规则")
            }
        }
        AnimatedVisibility(showRule, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                "已绑定 CCW、粉丝超过 1,000 的创作者会自动入住，名单每天随 CCW 数据更新。",
                style = KcTheme.type.caption,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp, end = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 领奖台：一张横卡里放前三名，2 - 1 - 3 排列，冠军头像大一圈；顶部一层很淡的金色晕染 */
@Composable
private fun Podium(top: List<CcwCreator>, onOpen: (Long) -> Unit) {
    val colors = KcTheme.colors
    val glow = medal(1, colors).copy(alpha = if (colors.isDark) 0.10f else 0.09f)
    val order = listOf(1, 0, 2).filter { it < top.size }
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.panel)
            .drawBehind { drawRect(Brush.verticalGradient(listOf(glow, Color.Transparent), endY = size.height * 0.7f), size = Size(size.width, size.height)) }
            .padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        order.forEach { index ->
            PodiumSlot(top[index], index + 1, onClick = { onOpen(top[index].id) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PodiumSlot(creator: CcwCreator, rank: Int, onClick: () -> Unit, modifier: Modifier) {
    val colors = KcTheme.colors
    val tint = medal(rank, colors)
    val champion = rank == 1
    val avatar: Dp = if (champion) 68.dp else 54.dp
    Column(modifier.tap(scale = 0.95f, onClick = onClick).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (champion) KcIcon(KcIcons.crown, tint = tint, size = 18.dp, modifier = Modifier.padding(bottom = 4.dp))
        Box(contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.size(avatar + 8.dp).border(2.dp, tint, CircleShape).padding(4.dp), contentAlignment = Alignment.Center) {
                Avatar(creator.avatarUrl, creator.displayName, creator.id, avatar, Modifier.clip(CircleShape))
            }
            Box(
                Modifier.offset(y = 8.dp).size(20.dp).clip(CircleShape).background(colors.panel).padding(2.dp).clip(CircleShape).background(tint),
                contentAlignment = Alignment.Center,
            ) { Text("$rank", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.W800, style = Tabular) }
        }
        Text(
            creator.displayName,
            style = KcTheme.type.secondary,
            fontWeight = FontWeight.W600,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            Text(wanCount(creator.followerCount), color = colors.text, fontSize = if (champion) 15.sp else 13.5.sp, fontWeight = FontWeight.W700, style = Tabular)
            Text(" 粉丝", style = KcTheme.type.caption, color = colors.subtle)
        }
    }
}

/** 4 名以后：分组卡片里的一行，首尾两行带圆角，行间是从正文起的发丝线 */
@Composable
private fun RankRow(rank: Int, creator: CcwCreator, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val r = 18.dp
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp, bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = if (first) 12.dp else 0.dp).fillMaxWidth().clip(shape).background(colors.panel)) {
        if (!first) Box(Modifier.padding(start = RowInset).fillMaxWidth().height(0.7.dp).background(colors.border))
        Row(
            Modifier.fillMaxWidth().tap(scale = 0.98f, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$rank", color = colors.subtle, fontSize = 15.sp, fontWeight = FontWeight.W600, style = Tabular, modifier = Modifier.width(28.dp))
            Avatar(creator.avatarUrl, creator.displayName, creator.id, 44.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(creator.displayName, style = KcTheme.type.rowTitle, fontWeight = FontWeight.W600, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    KcIcon(KcIcons.badgeCheck, tint = colors.accent, size = 15.dp, contentDescription = "CCW 已验证", modifier = Modifier.padding(start = 4.dp))
                }
                Text(
                    creator.intro.ifBlank { "CCW 创作者" },
                    style = KcTheme.type.caption,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(wanCount(creator.followerCount), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.W700, style = Tabular)
                Text("粉丝", fontSize = 11.sp, color = colors.subtle)
            }
        }
    }
}

@Composable
private fun HallEmpty() {
    val colors = KcTheme.colors
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.panel).padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KcIcon(KcIcons.award, tint = colors.subtle, size = 32.dp)
        Text("还没有创作者入住", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(top = 10.dp))
        Text("绑定 CCW 并拥有 1,000 以上粉丝，就会出现在这里", style = KcTheme.type.caption, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 首次加载的骨架：头部、领奖台、几行列表的轮廓，不出现整页转圈 */
@Composable
private fun HallSkeleton(showTitle: Boolean) {
    val colors = KcTheme.colors
    val block = colors.active
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, top = if (showTitle) 18.dp else 14.dp, bottom = 16.dp)) {
            if (showTitle) Box(Modifier.padding(bottom = 8.dp).width(104.dp).height(22.dp).clip(RoundedCornerShape(8.dp)).background(block))
            Box(Modifier.width(180.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
        }
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.panel).padding(vertical = 20.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf(62.dp, 76.dp, 62.dp).forEach { size ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(size).clip(CircleShape).background(block))
                    Box(Modifier.padding(top = 14.dp).width(56.dp).height(11.dp).clip(RoundedCornerShape(6.dp)).background(block))
                    Box(Modifier.padding(top = 6.dp).width(40.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(block))
                }
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.panel)) {
            repeat(5) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(28.dp)) { Box(Modifier.width(12.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(block)) }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(block))
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                        Box(Modifier.width(110.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(block))
                    }
                    Box(Modifier.width(36.dp).height(14.dp).clip(RoundedCornerShape(6.dp)).background(block))
                }
            }
        }
    }
}
