package ink.kuke.chat.feature.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/**
 * 收藏表情管理（表情面板「收藏」页的「+」进来）：
 *   - 第一格「添加」：相册多选（一次最多 [STICKER_PICK_MAX] 张）→ 逐张上传 → 加进收藏，新加的排在最前
 *   - 长按表情拖动排序，松手就保存（PUT /stickers/favorites/order）
 *   - 「选择」进多选：点表情勾选，底部「移到最前」「删除(n)」；点一下表情也直接进多选并选中它
 * 列表和表情面板共用 FavoriteStickers，这里改完回到聊天，面板里已经是新的。
 */
@Composable
fun StickerManagerScreen(graph: AppGraph) {
    val nav = LocalAppNav.current
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val cached = FavoriteStickers.items
    var failed by remember { mutableStateOf(false) }
    var reloads by remember { mutableStateOf(0) }
    LaunchedEffect(reloads) {
        failed = false
        runCatching { FavoriteStickers.load(graph) }.onFailure { failed = true }
    }

    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }
    /** 保存排序 / 删除 / 移到最前进行中：这时不让再拖、不让再点批量操作 */
    var busy by remember { mutableStateOf(false) }
    /** 添加进度（「正在添加 3/8」） */
    var adding by remember { mutableStateOf<String?>(null) }

    fun exitSelecting() {
        selecting = false
        selected.clear()
    }
    BackHandler(enabled = selecting && !confirmDelete) { exitSelecting() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(STICKER_PICK_MAX)) { uris ->
        if (uris.isEmpty() || adding != null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = addFavoriteStickers(graph, context, uris) { adding = it }
            adding = null
            toast(context, result)
        }
    }

    fun write(block: suspend () -> Unit, success: String?, after: () -> Unit = {}) {
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess { success?.let { toast(context, it) }; after() }
                .onFailure { toast(context, stickerErrorText(it)) }
            busy = false
        }
    }

    // ---------------------------------------------------------------- 拖动排序
    // 本地顺序副本：拖的时候在这里换位置，松手再提交；共享列表变了（保存回来、删除、重拉）就跟着重置
    val order = remember(cached) { mutableStateListOf<FavoriteSticker>().apply { addAll(cached.orEmpty()) } }
    val grid = rememberLazyGridState()
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val maxStep = with(density) { 14.dp.toPx() }
    var dragKey by remember { mutableStateOf<Long?>(null) }
    /** 拖起时那一格在网格里的左上角，加上手指移动的累计量 = 浮起那一格现在该在的位置 */
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    /** 松手后浮起那一格滑回格子里 */
    var settleKey by remember { mutableStateOf<Long?>(null) }
    val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    fun infoOf(key: Long): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** 浮起那一格的中心压到别的表情上了：把它挪到那个位置（别的格子用 animateItem 平滑让开） */
    fun checkSwap() {
        val key = dragKey ?: return
        val info = infoOf(key) ?: return
        val from = order.indexOfFirst { it.assetId == key }
        // 上一次换位还没排版完（网格里它还在旧位置；第一格是「添加」）：等下一帧，免得来回换
        if (from < 0 || info.index != from + 1) return
        val center = dragOrigin + dragDelta + Offset(info.size.width / 2f, info.size.height / 2f)
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.key is Long && other.key != key &&
                center.x >= other.offset.x && center.x < other.offset.x + other.size.width &&
                center.y >= other.offset.y && center.y < other.offset.y + other.size.height
        } ?: return
        val to = order.indexOfFirst { it.assetId == target.key }
        if (to < 0) return
        // 挪动的是第一个可见项时网格会跟着它跳，先记住滚动位置再原样请求回去
        val firstIndex = grid.firstVisibleItemIndex
        val firstOffset = grid.firstVisibleItemScrollOffset
        order.add(to, order.removeAt(from))
        grid.requestScrollToItem(firstIndex, firstOffset)
        Haptics.tick(view)
    }

    fun endDrag() {
        val key = dragKey ?: return
        val info = infoOf(key)
        val rest = if (info != null) dragOrigin + dragDelta - info.offset.toOffset() else Offset.Zero
        dragKey = null
        settleKey = key
        scope.launch {
            settle.snapTo(rest)
            settle.animateTo(Offset.Zero, spring(dampingRatio = 0.8f, stiffness = 600f))
            if (settleKey == key) settleKey = null
        }
        val ids = order.map { it.assetId }
        if (ids != cached.orEmpty().map { it.assetId }) write({ FavoriteStickers.reorder(graph, ids) }, success = null)
    }

    // 拖到上下边缘时自动滚动（越靠边越快）
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        while (dragKey != null) {
            val key = dragKey ?: break
            val info = infoOf(key)
            if (info != null) {
                val y = dragOrigin.y + dragDelta.y + info.size.height / 2f
                val height = grid.layoutInfo.viewportSize.height
                val step = when {
                    y < edge -> -maxStep * ((edge - y) / edge).coerceIn(0f, 1f)
                    y > height - edge -> maxStep * ((y - (height - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (step != 0f) {
                    grid.scrollBy(step)
                    checkSwap()
                }
            }
            withFrameNanos { }
        }
    }

    val list = order
    KcPage(
        title = "收藏表情",
        subtitle = when {
            cached == null -> null
            busy && !selecting -> "保存中…"
            selecting -> "已选 ${selected.size} 个"
            else -> "${cached.size} 个"
        },
        onBack = { if (selecting) exitSelecting() else nav.back() },
        actions = {
            if (!cached.isNullOrEmpty()) {
                if (selecting) {
                    val all = selected.size == list.size
                    PillText(if (all) "全不选" else "全选", filled = false) {
                        selected.clear()
                        if (!all) selected.addAll(list.map { it.assetId })
                    }
                }
                PillText(if (selecting) "完成" else "选择", filled = selecting, modifier = Modifier.padding(start = 8.dp, end = 8.dp)) {
                    if (selecting) exitSelecting() else selecting = true
                }
            }
        },
    ) {
        when {
            cached == null && failed -> StateText("收藏表情没加载出来", error = true, onRetry = { reloads++ })
            cached == null -> StateText("加载中…")
            else -> Column(Modifier.fillMaxSize()) {
                Text(
                    adding ?: if (selecting) "点表情勾选，可以批量删除或移到最前" else "长按拖动调整顺序，表情面板按这个顺序显示；新添加的排在最前",
                    style = type.caption,
                    color = colors.muted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp),
                )
                LazyVerticalGrid(
                    GridCells.Fixed(4),
                    state = grid,
                    modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(selecting, busy, order) {
                        if (selecting || busy) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at ->
                                val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                    info.key is Long &&
                                        at.x >= info.offset.x && at.x < info.offset.x + info.size.width &&
                                        at.y >= info.offset.y && at.y < info.offset.y + info.size.height
                                }
                                if (hit != null) {
                                    dragKey = hit.key as Long
                                    dragOrigin = hit.offset.toOffset()
                                    dragDelta = Offset.Zero
                                    Haptics.tick(view)
                                }
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                        ) { change, amount ->
                            if (dragKey == null) return@detectDragGesturesAfterLongPress
                            change.consume()
                            dragDelta += amount
                            checkSwap()
                        }
                    },
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 24.dp),
                ) {
                    if (!selecting) item(key = "add") {
                        AddStickerTile(busy = adding != null) {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                    if (list.isEmpty()) item(span = { GridItemSpan(3) }) {
                        Text("还没有收藏表情。点「添加」从相册选，或长按聊天里的图片添加到表情。", style = type.caption, color = colors.muted, modifier = Modifier.padding(8.dp))
                    }
                    items(list, key = { it.assetId }) { sticker ->
                        val key = sticker.assetId
                        val lifted = dragKey == key
                        val floating = lifted || settleKey == key
                        val lift by animateFloatAsState(if (lifted) 1f else 0f, label = "stickerLift")
                        StickerTile(
                            sticker,
                            selecting = selecting,
                            checked = key in selected,
                            modifier = (if (floating) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                                .graphicsLayer {
                                    if (lifted) {
                                        val info = infoOf(key)
                                        if (info != null) {
                                            translationX = dragOrigin.x + dragDelta.x - info.offset.x
                                            translationY = dragOrigin.y + dragDelta.y - info.offset.y
                                        }
                                    } else if (settleKey == key) {
                                        translationX = settle.value.x
                                        translationY = settle.value.y
                                    }
                                    val sc = 1f + 0.08f * lift
                                    scaleX = sc
                                    scaleY = sc
                                    shadowElevation = 10.dp.toPx() * lift
                                    shape = RoundedCornerShape(12.dp)
                                },
                        ) {
                            // 长按拖起后原地松手：格子自己的点击也会收到抬起，这一下不算点击
                            if (dragKey != null || settleKey == key) return@StickerTile
                            if (!selecting) selecting = true
                            if (key in selected) selected.remove(key) else selected.add(key)
                        }
                    }
                }
                if (selecting) {
                    // 底部操作条：移到最前 · 删除(n)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    Row(
                        Modifier.fillMaxWidth().background(colors.panel)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val none = selected.isEmpty()
                        KcButton("移到最前", {
                            val ids = list.map { it.assetId }.filter { it in selected }
                            write({ FavoriteStickers.moveToFront(graph, ids) }, success = "已移到最前") { exitSelecting() }
                        }, modifier = Modifier.weight(1f), enabled = !none && !busy, primary = false)
                        KcButton(if (none) "删除" else "删除(${selected.size})", { confirmDelete = true }, modifier = Modifier.weight(1f), enabled = !none && !busy, danger = true)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val ids = selected.toList()
        KcConfirm(
            "删除表情",
            "删除选中的 ${ids.size} 个收藏表情？已经发出的消息不受影响。",
            "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                write({ FavoriteStickers.delete(graph, ids) }, success = "已删除 ${ids.size} 个表情") { exitSelecting() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 顶栏右侧的小胶囊按钮：墨色实心（当前状态）或描边 */
@Composable
private fun PillText(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Text(
        text,
        style = KcTheme.type.secondary.copy(fontWeight = FontWeight.W600),
        color = if (filled) colors.onFill else colors.text,
        modifier = modifier.clip(RoundedCornerShape(99.dp))
            .then(if (filled) Modifier.background(colors.fill) else Modifier.border(1.dp, colors.border, RoundedCornerShape(99.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun AddStickerTile(busy: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    Column(
        Modifier.padding(5.dp).aspectRatio(1f).pressScale(press, 0.94f).clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = null, enabled = !busy, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (busy) SendSpinner(18.dp, colors.muted, null)
        else KcIcon(KcIcons.plus, tint = colors.text, size = 22.dp, contentDescription = "添加表情")
        Text(if (busy) "添加中" else "添加", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 管理页的一格：白底圆角里放表情；多选时右上角是勾选圈，选中的微微缩小 */
@Composable
private fun StickerTile(sticker: FavoriteSticker, selecting: Boolean, checked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shrink by animateFloatAsState(if (checked) 0.88f else 1f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "stickerCheck")
    Box(
        modifier.padding(5.dp).aspectRatio(1f).pressScale(press, 0.94f).clip(RoundedCornerShape(12.dp)).background(colors.panel)
            .then(if (checked) Modifier.border(1.5.dp, colors.fill, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
    ) {
        AsyncImage(
            model = Meta.imageUrl(sticker.url),
            contentDescription = "收藏表情",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer { scaleX = shrink; scaleY = shrink },
        )
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                    .background(if (checked) colors.fill else Color.White.copy(alpha = 0.85f))
                    .then(if (checked) Modifier else Modifier.border(1.5.dp, colors.borderStrong, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) KcIcon(KcIcons.check, tint = colors.onFill, size = 13.dp)
            }
        }
    }
}
