package ink.kuke.chat.feature.feed

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.KcPage
import kotlinx.coroutines.launch

/** 大佬入住的独立数据源：社区主页「查看全部」用，不依赖动态页的 ViewModel */
class CreatorsViewModel(graph: AppGraph) : ViewModel() {
    private val api = PostsApi(graph.api)
    val state = CreatorsState()

    fun load(reset: Boolean, pull: Boolean = false) {
        if (!reset && (!state.hasMore || state.loadingMore || state.loading)) return
        if (reset) { if (pull) state.refreshing = true else state.loading = !state.loaded } else state.loadingMore = true
        state.error = null
        viewModelScope.launch {
            runCatching { api.creators(30, if (reset) 0 else state.items.size) }
                .onSuccess { page -> state.apply(page, reset) }
                .onFailure { if (state.items.isEmpty()) state.error = it.userMessage() }
            state.loading = false
            state.loadingMore = false
            state.refreshing = false
        }
    }
}

/** 全屏的大佬入住页（主页「查看全部」打开）。内容和动态页的「大佬入住」分区是同一个 CreatorsHall */
@Composable
fun CreatorsHallPage(graph: AppGraph, onBack: () -> Unit, onOpenUser: (Long) -> Unit) {
    val vm = graphViewModel("creators-page") { CreatorsViewModel(graph) }
    // 有缓存先显示缓存，同时静默刷新（loading 只在没数据时才会亮）
    LaunchedEffect(Unit) {
        if (!vm.state.loading && (!vm.state.loaded || vm.state.needsRefresh)) {
            vm.state.needsRefresh = false
            vm.load(reset = true)
        }
    }
    KcPage(title = "大佬入住", onBack = onBack) {
        CreatorsHall(
            state = vm.state,
            onRetry = { vm.load(reset = true) },
            onRefresh = { vm.load(reset = true, pull = true) },
            onLoadMore = { vm.load(reset = false) },
            onOpen = onOpenUser,
            modifier = Modifier.fillMaxSize(),
            showTitle = false,
        )
    }
}
