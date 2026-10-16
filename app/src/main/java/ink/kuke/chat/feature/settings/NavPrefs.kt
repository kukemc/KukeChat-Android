package ink.kuke.chat.feature.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import ink.kuke.chat.ui.components.Haptics
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.AnnouncementsRoute
import ink.kuke.chat.ui.shell.BotsRoute
import ink.kuke.chat.ui.shell.FavoritesRoute
import ink.kuke.chat.ui.shell.HomeRoute
import ink.kuke.chat.ui.shell.JuryRoute
import ink.kuke.chat.ui.shell.LevelRoute
import ink.kuke.chat.ui.shell.RankingRoute
import ink.kuke.chat.ui.shell.ShopRoute
import ink.kuke.chat.ui.shell.TasksRoute
import ink.kuke.chat.ui.shell.TeamupRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.navPrefsData by preferencesDataStore(name = "nav_prefs")

/**
 * 「底栏与功能排序」里能排的功能。排在前两位的会成为底栏中间两个按钮（聊天、空间固定在两头），其余出现在空间页的功能宫格里。
 * route 为 null 的（联系人、动态）是底栏页本身，不在宫格里跳转，只能放在底栏上。
 */
class NavFeature(val id: String, val label: String, val route: Any?, val icon: () -> ImageVector)

object NavFeatures {
    val all: List<NavFeature> = listOf(
        NavFeature("contacts", "联系人", null) { KcIcons.contacts },
        NavFeature("feed", "动态", null) { KcIcons.feed },
        NavFeature("teamup", "组队中心", TeamupRoute) { KcIcons.users },
        NavFeature("tasks", "任务", TasksRoute) { KcIcons.listChecks },
        NavFeature("bots", "机器人", BotsRoute) { KcIcons.bot },
        NavFeature("jury", "风纪", JuryRoute) { KcIcons.scale },
        NavFeature("shop", "商店", ShopRoute) { KcIcons.store },
        NavFeature("home", "主页", HomeRoute) { KcIcons.home },
        NavFeature("favorites", "收藏", FavoritesRoute) { KcIcons.bookmark },
        NavFeature("announcements", "公告", AnnouncementsRoute) { KcIcons.announcement },
        NavFeature("level", "账号等级", LevelRoute) { KcIcons.star },
        NavFeature("ranking", "排行榜", RankingRoute) { KcIcons.trophy },
    )
    val defaultOrder: List<String> = all.map { it.id }
    fun byId(id: String): NavFeature? = all.firstOrNull { it.id == id }
}

/**
 * 底栏按钮与空间功能排序，存 DataStore。外壳读 [order]：前两项是底栏的中间两个按钮，其余是空间页宫格顺序。
 * 存下来的列表可能缺新功能或带已删除的功能，读出时统一修正。
 */
class NavPrefsStore(context: Context) {
    private val data = context.applicationContext.navPrefsData
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringPreferencesKey("order")

    private val _order = MutableStateFlow(runBlocking { normalize(data.data.first()[key]) })
    val order: StateFlow<List<String>> = _order.asStateFlow()

    /** 底栏中间的两个功能 id（联系人 / 动态 是底栏页，其余是跳转的页面） */
    val bottomIds: List<String> get() = _order.value.take(2)

    fun set(order: List<String>) {
        val next = normalize(order.joinToString(","))
        _order.value = next
        scope.launch { data.edit { it[key] = next.joinToString(",") } }
    }

    fun move(from: Int, to: Int) {
        val list = _order.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        set(list)
    }

    fun reset() = set(NavFeatures.defaultOrder)

    private fun normalize(raw: String?): List<String> {
        val known = NavFeatures.defaultOrder.toSet()
        val saved = raw.orEmpty().split(',').map { it.trim() }.filter { it in known }.distinct()
        return saved + NavFeatures.defaultOrder.filter { it !in saved }
    }

    companion object {
        @Volatile private var instance: NavPrefsStore? = null
        fun shared(context: Context): NavPrefsStore =
            instance ?: synchronized(this) { instance ?: NavPrefsStore(context.applicationContext).also { instance = it } }
    }
}

/** 一行的高度：60dp 内容 + 1dp 分隔线（第一行的分隔线不画，但位置留着，拖动时每行间距一样） */
private val RowPitch = 61.dp

/**
 * 设置 › 底栏与功能排序：按住右侧把手直接拖（或按住整行稍等一下再拖），前两项是底栏按钮。
 * 拖起的行微微放大浮起、跟着手指走；越过别的行时那一行平滑让开，每换一次位置轻震一下；
 * 「底栏按钮 / 空间页功能宫格」的标注拖动中实时变，松手才写入。
 */
@Composable
fun NavPrefsPage() {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val store = remember { NavPrefsStore.shared(context) }
    val order by store.order.collectAsStateWithLifecycle()
    val pitch = with(density) { RowPitch.toPx() }

    // 拖动中改的是这份本地列表；没在拖时跟着存储走
    val items = remember { mutableStateListOf<String>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(order) {
        if (dragging == null && items.toList() != order) {
            items.clear()
            items.addAll(order)
        }
    }
    val scope = rememberCoroutineScope()
    val settle = remember { Animatable(0f) }

    fun startDrag(id: String) {
        dragging = id
        dragOffset = 0f
        Haptics.tick(view)
    }
    fun onDrag(dy: Float) {
        val id = dragging ?: return
        dragOffset += dy
        var index = items.indexOf(id)
        // 越过相邻一行的一半就换位，换完把偏移扣掉一行，手指下的位置不变
        while (dragOffset > pitch / 2 && index < items.lastIndex) {
            items.add(index + 1, items.removeAt(index))
            index++
            dragOffset -= pitch
            Haptics.tick(view)
        }
        while (dragOffset < -pitch / 2 && index > 0) {
            items.add(index - 1, items.removeAt(index))
            index--
            dragOffset += pitch
            Haptics.tick(view)
        }
    }
    fun endDrag() {
        if (dragging == null) return
        store.set(items.toList())
        val from = dragOffset
        scope.launch {
            // 松手：从手指放下的位置弹回它的格子
            settle.snapTo(from)
            dragOffset = 0f
            settle.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f))
            dragging = null
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = dragging == null).navigationBarsPadding().padding(bottom = 24.dp)) {
        Text(
            "聊天和空间固定在底栏。按住右侧的把手拖动排序：前两项是底栏中间的两个按钮，其余功能显示在空间页的功能宫格里。",
            style = type.caption,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        GroupCard {
            items.forEachIndexed { index, id ->
                key(id) {
                    val feature = NavFeatures.byId(id)
                    if (feature != null) {
                        val inBottom = index < 2
                        val active = dragging == id
                        // 别的行换了位置：从原来的位置平滑滑到新位置
                        val shift = remember { Animatable(0f) }
                        var lastIndex by remember { mutableIntStateOf(index) }
                        LaunchedEffect(index) {
                            if (index != lastIndex && !active) {
                                shift.snapTo((lastIndex - index) * pitch + shift.value)
                                lastIndex = index
                                shift.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 700f))
                            } else {
                                lastIndex = index
                            }
                        }
                        val lift by animateFloatAsState(if (active) 1f else 0f, label = "lift")
                        val rowShape = RoundedCornerShape(14.dp * lift)
                        val dividerHidden = index == 0 || active || (dragging != null && items.getOrNull(index - 1) == dragging)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .height(RowPitch)
                                .zIndex(if (active) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (active) dragOffset + settle.value else shift.value
                                    scaleX = 1f + 0.025f * lift
                                    scaleY = scaleX
                                    shadowElevation = 12.dp.toPx() * lift
                                    shape = rowShape
                                    clip = false
                                }
                                .background(colors.panel, rowShape)
                                // 按住整行稍等一下也能拖
                                .pointerInput(id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { startDrag(id) },
                                        onDragEnd = { endDrag() },
                                        onDragCancel = { endDrag() },
                                    ) { change, amount ->
                                        change.consume()
                                        onDrag(amount.y)
                                    }
                                },
                        ) {
                            Box(Modifier.alpha(if (dividerHidden) 0f else 1f)) { RowDivider() }
                            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                KcIcon(feature.icon(), tint = if (inBottom) colors.accent else colors.muted, size = 20.dp)
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text("${index + 1}. ${feature.label}", style = type.rowTitle, color = colors.text)
                                    Text(if (inBottom) "底栏按钮" else "空间页功能宫格", style = type.caption, color = if (inBottom) colors.accent else colors.muted)
                                }
                                // 拖动把手：按住立刻就能拖
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .pointerInput(id) {
                                            detectDragGestures(
                                                onDragStart = { startDrag(id) },
                                                onDragEnd = { endDrag() },
                                                onDragCancel = { endDrag() },
                                            ) { change, amount ->
                                                change.consume()
                                                onDrag(amount.y)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    KcIcon(KcIcons.gripVertical, tint = if (active) colors.text else colors.subtle, size = 20.dp, contentDescription = "拖动排序")
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(16.dp)) {
            SecondaryButton("恢复默认顺序", onClick = { store.reset() }, modifier = Modifier.fillMaxWidth())
        }
    }
}
