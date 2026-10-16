package ink.kuke.chat.feature.teamup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.feature.feed.ErrorBox
import ink.kuke.chat.feature.feed.LoadingBox
import ink.kuke.chat.feature.feed.SmallTag
import ink.kuke.chat.feature.feed.compactCount
import ink.kuke.chat.feature.feed.graphViewModel
import ink.kuke.chat.feature.feed.tap
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.TeamupProfileRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 组队中心（设计稿 P17）：赛季横幅、技能方向、「此刻值得认识的人」横滑推荐、名片流、发布 / 管理我的名片。
 * 编辑名片是这个页面内部的全屏状态（不另开路由）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamupScreen(graph: AppGraph) {
    val colors = KcTheme.colors
    val nav = LocalAppNav.current
    val vm = graphViewModel("teamup") { TeamupViewModel(graph) }
    var editing by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(vm.notice) {
        if (vm.notice != null) { delay(2600); vm.notice = null }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= listState.layoutInfo.totalItemsCount - 3) vm.loadMore()
        }
    }

    if (editing) {
        BackHandler { editing = false }
        TeamupEditorScreen(graph, vm.mine, onClose = { editing = false }, onSaved = { saved -> vm.onSaved(saved); editing = false })
        return
    }

    KcPage(title = "组队中心", subtitle = "GameJam · 14 天极限创作", onBack = { nav.back() }) {
        Box(Modifier.fillMaxSize()) {
            when {
                vm.loading && vm.profiles.isEmpty() && vm.event == null -> LoadingBox()
                vm.error != null && vm.profiles.isEmpty() -> ErrorBox(vm.error.orEmpty(), { vm.loadAll() })
                else -> PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.loadAll(pull = true) }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                        item("banner") {
                            SeasonBanner(vm, onExplore = {
                                // 滚到「全部名片」：横幅 + 技能行之后，有推荐时再多两项
                                scope.launch { listState.animateScrollToItem(if (vm.recommended().isNotEmpty() && vm.skill == null) 4 else 2) }
                            })
                        }
                        item("skills") { SkillRow(vm) }
                        val recommended = vm.recommended()
                        if (recommended.isNotEmpty() && vm.skill == null) {
                            item("rec-head") {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("此刻值得认识的人", style = KcTheme.type.title, color = colors.text)
                                        Text("每次刷新都会重新计算", style = KcTheme.type.caption, color = colors.subtle)
                                    }
                                    Text("换一批缘分", style = KcTheme.type.secondary, color = colors.accent, modifier = Modifier.tap(scale = 0.94f) { vm.shuffle() }.padding(8.dp))
                                }
                            }
                            item("rec-row") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(recommended, key = { "r-${it.first.id}" }) { (profile, tag) ->
                                        RecommendCard(profile, tag) { nav.go(TeamupProfileRoute(profile.id)) }
                                    }
                                }
                            }
                        }
                        item("list-head") {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("全部名片", style = KcTheme.type.title, color = colors.text, modifier = Modifier.weight(1f))
                                listOf("hot" to "热度", "latest" to "最新").forEach { (value, label) ->
                                    val on = vm.sort == value
                                    Text(
                                        label, style = KcTheme.type.secondary, color = if (on) colors.accent else colors.subtle,
                                        modifier = Modifier.tap(scale = 0.94f) { vm.selectSort(value) }.padding(start = 4.dp).clip(RoundedCornerShape(12.dp))
                                            .background(if (on) colors.accentSoft else Color.Transparent).padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                        if (vm.profiles.isEmpty()) {
                            item("empty") {
                                Text(
                                    if (vm.skill != null) "这个方向还没有名片，做第一个吧" else "还没有人发布组队名片，做第一个吧",
                                    style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.fillMaxWidth().padding(40.dp),
                                )
                            }
                        } else {
                            items(vm.profiles, key = { it.id }) { profile ->
                                ProfileCard(profile, Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) { nav.go(TeamupProfileRoute(profile.id)) }
                            }
                            if (vm.loadingMore) item("more") { LoadingBox(Modifier.fillMaxWidth().height(56.dp)) }
                        }
                    }
                }
            }

            // 发布 / 管理我的名片
            val hasMine = vm.mine != null
            if (vm.mineLoaded || vm.profiles.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).tap(scale = 0.96f) { if (hasMine) managing = true else editing = true }
                        .clip(RoundedCornerShape(26.dp)).background(colors.fill).padding(horizontal = 22.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KcIcon(if (hasMine) KcIcons.settings else KcIcons.plus, tint = colors.onFill, size = 20.dp)
                    Text(if (hasMine) "管理我的名片" else "发布组队名片", style = KcTheme.type.rowTitle, color = colors.onFill, modifier = Modifier.padding(start = 8.dp))
                }
            }
            vm.notice?.let { message ->
                Text(
                    message, style = KcTheme.type.secondary, color = colors.panel,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(22.dp)).background(colors.text.copy(alpha = 0.88f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (managing) {
        val mine = vm.mine
        ModalBottomSheet(onDismissRequest = { managing = false }, containerColor = colors.panel) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("我的名片", style = KcTheme.type.title, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                if (mine != null) {
                    Text(mine.headline, style = KcTheme.type.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
                    val state = when {
                        mine.moderationStatus == "pending" -> "审核中，通过后才会出现在广场"
                        mine.moderationStatus == "rejected" -> "未通过审核，请修改后重新保存"
                        mine.recruiting -> "招募中"
                        else -> "已满员（暂停招募）"
                    }
                    Text(state, style = KcTheme.type.caption, color = colors.subtle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                SheetAction("编辑名片", KcIcons.edit) { managing = false; editing = true }
                if (mine != null) {
                    SheetAction("在广场里查看", KcIcons.eye) { managing = false; nav.go(TeamupProfileRoute(mine.id)) }
                    if (mine.recruiting) SheetAction(if (vm.acting) "处理中…" else "暂停招募（标记满员）", KcIcons.pause, enabled = !vm.acting) { vm.closeMine(); managing = false }
                    else SheetAction(if (vm.acting) "处理中…" else "重新开始招募", KcIcons.play, enabled = !vm.acting) { vm.reopenMine(); managing = false }
                    SheetAction("删除名片", KcIcons.trash, danger = true) { managing = false; confirmDelete = true }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除我的组队名片？") },
            text = { Text("名片和上面的留言都会消失，之后可以重新发布。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteMine() }) { Text("删除", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SheetAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().tap(enabled = enabled, scale = 0.98f, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(icon, tint = if (danger) colors.danger else colors.muted, size = 22.dp)
        Text(label, style = KcTheme.type.rowTitle, color = if (danger) colors.danger else colors.text, modifier = Modifier.padding(start = 14.dp))
    }
}

/** 赛季横幅：当前赛季 · 距结束 · 招募中 N / M · 进度条 */
@Composable
private fun SeasonBanner(vm: TeamupViewModel, onExplore: () -> Unit) {
    val colors = KcTheme.colors
    val event = vm.event
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    val stats = vm.stats
    val recruiting = event?.recruitingCount ?: stats?.recruitingCount ?: 0
    val total = event?.profileCount ?: stats?.profileCount ?: 0
    val remain = vm.remainingSeconds(now)
    // 横幅是品牌渐变，固定色（和主题无关）
    val gradient = Brush.linearGradient(listOf(Color(0xFF1F6BFF), Color(0xFF7950F2)))
    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(gradient)) {
        event?.coverUrl?.let { cover ->
            AsyncImage(Endpoints.absolute(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().alpha(0.28f))
        }
        Column(Modifier.padding(18.dp)) {
            Text(if (event != null && event.isActive) "当前赛季" else "组队广场", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.W700, letterSpacing = 1.sp)
            Text(
                event?.let { listOf(it.title, it.theme.takeIf { theme -> theme.isNotBlank() }?.let { theme -> "「$theme」" }).filterNotNull().joinToString(" · ") } ?: "找到你的搭档",
                color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.W800, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (event != null && remain != null) Text("距结束 ${countdown(remain)}", color = Color.White, fontSize = 13.sp)
                Text("招募中 $recruiting / $total", color = Color.White, fontSize = 13.sp)
            }
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                val fraction = if (total > 0) (recruiting.toFloat() / total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "开始探索", color = Color(0xFF1F6BFF), fontSize = 13.5.sp, fontWeight = FontWeight.W700,
                    modifier = Modifier.tap(scale = 0.95f, onClick = onExplore).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    "换一批缘分", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.W600,
                    modifier = Modifier.tap(scale = 0.95f) { vm.shuffle() }.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SkillRow(vm: TeamupViewModel) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val all = vm.skill == null
        Text(
            "全部", style = KcTheme.type.secondary, color = if (all) colors.onFill else colors.text,
            modifier = Modifier.tap(scale = 0.95f) { vm.selectSkill(null) }.clip(RoundedCornerShape(18.dp)).background(if (all) colors.fill else colors.panel).padding(horizontal = 14.dp, vertical = 8.dp),
        )
        SkillList.forEach { meta ->
            val on = vm.skill == meta.key
            val count = vm.skillCounts.firstOrNull { it.skill == meta.key }?.count
            Row(
                Modifier.tap(scale = 0.95f) { vm.selectSkill(meta.key) }.clip(RoundedCornerShape(18.dp)).background(if (on) colors.fill else colors.panel).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcIcon(meta.icon, tint = if (on) colors.onFill else colors.accent, size = 16.dp)
                Text(meta.label, style = KcTheme.type.secondary, color = if (on) colors.onFill else colors.text, modifier = Modifier.padding(start = 5.dp))
                if (count != null) Text(" $count", style = KcTheme.type.caption, color = if (on) colors.onFill.copy(alpha = 0.8f) else colors.subtle)
            }
        }
    }
}

/** 招募状态：招募中（绿点呼吸）/ 已满员 / 审核中 / 未通过 */
@Composable
internal fun StatusBadge(profile: TeamupProfile) {
    val colors = KcTheme.colors
    when {
        profile.moderationStatus == "pending" -> SmallTag("审核中", colors.warn, colors.warn.copy(alpha = 0.14f))
        profile.moderationStatus == "rejected" -> SmallTag("未通过", colors.danger, colors.danger.copy(alpha = 0.14f))
        profile.recruiting -> {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val alpha by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
            Row(
                Modifier.clip(RoundedCornerShape(5.dp)).background(colors.ok.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(colors.ok))
                Text("招募中", color = colors.ok, fontSize = 10.5.sp, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 4.dp))
            }
        }
        else -> SmallTag("已满员", colors.muted, colors.panelMuted)
    }
}

/** 名片：封面条 · 头像 · 招募状态 · 一句话招募 · 擅长 + 想找 · 浏览 / 留言 / 代表作 */
@Composable
private fun ProfileCard(profile: TeamupProfile, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Column(modifier.fillMaxWidth().tap(scale = 0.98f, onClick = onClick).clip(RoundedCornerShape(16.dp)).background(colors.panel)) {
        if (profile.backgroundUrl != null) {
            AsyncImage(Endpoints.absolute(profile.backgroundUrl), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(72.dp))
        } else {
            Box(Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(listOf(colors.accent.copy(alpha = 0.5f), colors.accentSoft))))
        }
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile.author?.avatarUrl, profile.name, profile.userId, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(profile.name, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile.isFriend) Text("已是好友", style = KcTheme.type.caption, color = colors.accent)
                }
                StatusBadge(profile)
            }
            Text(profile.headline, style = KcTheme.type.body, color = colors.text, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            val intro = introPlain(profile.intro)
            if (intro.isNotBlank()) Text(intro, style = KcTheme.type.secondary, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (profile.skills.isNotEmpty() || profile.lookingFor.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    profile.skills.take(4).forEach { item ->
                        Text(
                            "${skillMeta(item.skill).label} ${levelLabel(item.level)}", style = KcTheme.type.caption, color = colors.text,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.panelMuted).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    if (profile.lookingFor.isNotEmpty()) {
                        Text("想找", style = KcTheme.type.caption, color = colors.subtle)
                        profile.lookingFor.take(3).forEach { skill ->
                            Text(
                                skillMeta(skill).label, style = KcTheme.type.caption, color = colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${compactCount(profile.viewCount)} 浏览", style = KcTheme.type.caption, color = colors.subtle)
                Text("${profile.commentCount} 留言", style = KcTheme.type.caption, color = colors.subtle)
                if (profile.ccwCreations.isNotEmpty()) Text("${profile.ccwCreations.size} 代表作", style = KcTheme.type.caption, color = colors.subtle)
            }
        }
    }
}

@Composable
private fun RecommendCard(profile: TeamupProfile, tag: String, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Column(
        Modifier.width(220.dp).tap(scale = 0.97f, onClick = onClick).clip(RoundedCornerShape(16.dp)).background(colors.panel).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.author?.avatarUrl, profile.name, profile.userId, 40.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(profile.name, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tag, style = KcTheme.type.caption, color = colors.accent, fontWeight = FontWeight.W600)
            }
        }
        Text(profile.headline, style = KcTheme.type.secondary, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).height(40.dp))
        Text(
            profile.skills.take(3).joinToString(" ") { "${skillMeta(it.skill).label}${levelLabel(it.level)}" }.ifBlank { "暂未填写技能" },
            style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
