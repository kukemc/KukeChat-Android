package ink.kuke.chat.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import ink.kuke.chat.ui.components.softShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import ink.kuke.chat.ui.icons.KcIcon
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.feature.bots.BotScreen
import ink.kuke.chat.feature.bots.BotsScreen
import ink.kuke.chat.feature.chat.ChatScreen
import ink.kuke.chat.feature.chat.ConversationListScreen
import ink.kuke.chat.feature.chat.RedPacketRecordsScreen
import ink.kuke.chat.feature.contacts.AddFriendScreen
import ink.kuke.chat.feature.contacts.BlocksScreen
import ink.kuke.chat.feature.contacts.ContactsScreen
import ink.kuke.chat.feature.contacts.CreateGroupScreen
import ink.kuke.chat.feature.contacts.NotificationCategoryScreen
import ink.kuke.chat.feature.contacts.NotificationsScreen
import ink.kuke.chat.feature.contacts.OnlineUsersScreen
import ink.kuke.chat.feature.contacts.SearchScreen
import ink.kuke.chat.feature.feed.ComposePostScreen
import ink.kuke.chat.feature.feed.FeedScreen
import ink.kuke.chat.feature.feed.PostScreen
import ink.kuke.chat.feature.feed.PostLikesScreen
import ink.kuke.chat.feature.feed.TopicScreen
import ink.kuke.chat.feature.gifts.GiftRecordsScreen
import ink.kuke.chat.feature.gifts.GiftToastHost
import ink.kuke.chat.feature.group.GroupInfoScreen
import ink.kuke.chat.feature.groupfiles.GroupFilesScreen
import ink.kuke.chat.feature.groupplus.GroupPlusScreen
import ink.kuke.chat.feature.search.ChatSearchScreen
import ink.kuke.chat.feature.home.HomeScreen
import ink.kuke.chat.feature.jury.JuryScreen
import ink.kuke.chat.feature.level.LevelScreen
import ink.kuke.chat.feature.level.RankingScreen
import ink.kuke.chat.feature.settings.NavFeatures
import ink.kuke.chat.feature.settings.NavPrefsStore
import ink.kuke.chat.feature.settings.SettingsPageScreen
import ink.kuke.chat.feature.settings.SettingsScreen
import ink.kuke.chat.feature.shop.ShopScreen
import ink.kuke.chat.feature.space.AnnouncementsScreen
import ink.kuke.chat.feature.space.FavoritesScreen
import ink.kuke.chat.feature.space.KeepAliveScreen
import ink.kuke.chat.feature.space.ProfileDrawerHost
import ink.kuke.chat.feature.space.rememberProfileDrawerState
import ink.kuke.chat.feature.space.SpaceScreen
import ink.kuke.chat.feature.tasks.TaskScreen
import ink.kuke.chat.feature.tasks.TasksScreen
import ink.kuke.chat.feature.teamup.TeamupProfileScreen
import ink.kuke.chat.feature.teamup.TeamupScreen
import ink.kuke.chat.feature.user.IntimacyCache
import ink.kuke.chat.feature.user.IntimacyScreen
import ink.kuke.chat.feature.user.UserScreen
import ink.kuke.chat.service.KeepAlive
import ink.kuke.chat.ui.components.EaseSmooth
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import dev.chrisbanes.haze.haze

private const val PUSH_MS = 320
/** 这个进程里有没有自动推出过保活引导（没放开时每次冷启动提醒一次，不反复弹） */
private var keepAliveShown = false

/**
 * 登录之后的外壳：一级页面（底栏四个 tab）在栈底，二级页面推在上面。返回规则全部交给页面栈，不再有手写的返回优先级：
 *   - 二级页面：系统返回 / 侧滑 = 弹出栈顶（NavHost 的 popEnter / popExit 会按侧滑手势进度播放，就是设计稿 M3 / M5 的预测性返回）
 *   - 底栏页面：不是聊天 tab 时，返回先回到聊天 tab；在聊天 tab 不拦截，交给系统画「回到桌面」预览（设计稿 P35）
 * 每个 tab 保留自己的滚动位置（rememberSaveableStateHolder）。
 */
@Composable
fun MainShell(graph: AppGraph, openConversationId: Long?, onConsumedOpen: () -> Unit, openInviteToken: String? = null, onConsumedInvite: () -> Unit = {}) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val appNav = remember(nav) {
        object : AppNav {
            override fun go(route: Any) = nav.navigate(route)
            override fun back() {
                BackFx.gesture.value = false
                nav.popBackStack()
            }
        }
    }

    LaunchedEffect(Unit) {
        IntimacyCache.start(graph)
        if (!keepAliveShown && !KeepAlive.ready(context)) {
            keepAliveShown = true
            nav.navigate(KeepAliveRoute)
        }
    }
    LaunchedEffect(openConversationId) {
        if (openConversationId != null) {
            nav.navigate(ChatRoute(openConversationId)) { launchSingleTop = true }
            onConsumedOpen()
        }
    }

    // 从邀请链接打开（深链接）：推邀请页
    LaunchedEffect(openInviteToken) {
        if (openInviteToken != null) {
            nav.navigate(InviteRoute(openInviteToken)) { launchSingleTop = true }
            onConsumedInvite()
        }
    }

    PredictiveBackHost {
    CompositionLocalProvider(LocalAppNav provides appNav, LocalNavCtl provides nav) {
      Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = TabsRoute,
            modifier = Modifier.fillMaxSize().background(KcTheme.colors.list),
            // 进入下一级：新页从右侧推入，旧页左移 28%；返回完全倒放（侧滑时由手势驱动）
            enterTransition = { slideInHorizontally(tween(PUSH_MS, easing = EaseSmooth)) { it } },
            exitTransition = { slideOutHorizontally(tween(PUSH_MS, easing = EaseSmooth)) { -it * 28 / 100 } },
            // 返回（含侧滑预测性返回）的视觉全部在 PopRound 里按手势进度画（设计稿 M5 / P33 / P34）：
            // 这里只登记「谁在离开 / 谁在露出」，并放一段几乎看不见的渐变撑住转场时长，让页面在动画期间不被提前移除。
            popEnterTransition = {
                BackFx.enterId = targetState.id
                fadeIn(tween(BACK_MS, easing = LinearEasing), initialAlpha = 0.999f)
            },
            popExitTransition = {
                BackFx.exitId = initialState.id
                fadeOut(tween(BACK_MS, easing = LinearEasing), targetAlpha = 0.999f)
            },
        ) {
            page<TabsRoute> { Tabs(graph, nav) }
            page<ChatRoute> {
                val route = it.toRoute<ChatRoute>()
                ChatScreen(graph, route.conversationId, onBack = { appNav.back() }, focusMessageId = route.focusMessageId)
            }
            page<GroupPlusRoute> {
                val route = it.toRoute<GroupPlusRoute>()
                GroupPlusScreen(graph, route.conversationId, reason = route.reason)
            }
            page<GroupFilesRoute> { GroupFilesScreen(graph, it.toRoute<GroupFilesRoute>().conversationId) }
            page<ChatSearchRoute> {
                val route = it.toRoute<ChatSearchRoute>()
                ChatSearchScreen(graph, route.conversationId, route.query)
            }
            page<GroupInfoRoute> { GroupInfoScreen(graph, it.toRoute<GroupInfoRoute>().conversationId) }
            page<KeepAliveRoute> { KeepAliveScreen(onBack = { appNav.back() }) }

            // 联系人与人
            page<UserRoute> { UserScreen(graph, it.toRoute<UserRoute>().userId) }
            page<IntimacyRoute> { IntimacyScreen(graph, it.toRoute<IntimacyRoute>().friendId) }
            page<NotificationsRoute> { NotificationsScreen(graph) }
            page<NotificationCategoryRoute> { NotificationCategoryScreen(graph, it.toRoute<NotificationCategoryRoute>().category) }
            page<SearchRoute> { SearchScreen(graph) }
            page<AddFriendRoute> { AddFriendScreen(graph) }
            page<CreateGroupRoute> { CreateGroupScreen(graph) }
            page<BlocksRoute> { BlocksScreen(graph) }
            page<OnlineUsersRoute> { OnlineUsersScreen(graph) }

            // 动态与社区
            page<PostRoute> { PostScreen(graph, it.toRoute<PostRoute>().postId) }
            page<ComposePostRoute> { ComposePostScreen(graph) }
            page<PostLikesRoute> { PostLikesScreen(graph, it.toRoute<PostLikesRoute>().postId) }
            page<TopicRoute> { TopicScreen(graph, it.toRoute<TopicRoute>().name) }
            page<HomeRoute> { HomeScreen(graph) }
            page<TeamupRoute> { TeamupScreen(graph) }
            page<TeamupProfileRoute> { TeamupProfileScreen(graph, it.toRoute<TeamupProfileRoute>().profileId) }

            // 设置、商店、等级
            page<SettingsRoute> { SettingsScreen(graph) }
            page<SettingsPageRoute> { SettingsPageScreen(graph, it.toRoute<SettingsPageRoute>().page) }
            page<ShopRoute> { ShopScreen(graph) }
            page<RedPacketRecordsRoute> { RedPacketRecordsScreen(graph) }
            page<LevelRoute> { LevelScreen(graph) }
            page<RankingRoute> { RankingScreen(graph) }
            page<GiftRecordsRoute> { GiftRecordsScreen(graph) }
            page<FavoritesRoute> { FavoritesScreen(graph) }
            page<AnnouncementsRoute> { AnnouncementsScreen(graph) }

            // 任务、机器人、风纪
            page<TasksRoute> { TasksScreen(graph) }
            page<TaskRoute> { TaskScreen(graph, it.toRoute<TaskRoute>().taskId) }
            page<GroupTasksRoute> { ink.kuke.chat.feature.tasks.GroupTasksScreen(graph, it.toRoute<GroupTasksRoute>().conversationId) }
            page<InviteRoute> { ink.kuke.chat.feature.invite.InviteScreen(graph, it.toRoute<InviteRoute>().token) }
            page<BotsRoute> { BotsScreen(graph) }
            page<BotRoute> { BotScreen(graph, it.toRoute<BotRoute>().botId) }
            page<JuryRoute> { JuryScreen(graph) }
        }
        BackArrow()
        // 友谊升级 / 好友成就解锁的庆祝卡片，盖在所有页面上面
        ink.kuke.chat.feature.user.IntimacyCelebrationHost(graph)
        // 全局浮层：实时事件提示（顶部胶囊）、庆生
        LiveEvents(graph, appNav)
        ink.kuke.chat.ui.components.NoticeHost()
        ink.kuke.chat.feature.birthday.BirthdayCelebration(graph)
        // 「有人送了我礼物」的顶部提示：停在哪个页面都会出现
        GiftToastHost(graph)
      }
    }
    }
}

private val LocalNavCtl = staticCompositionLocalOf<NavHostController?> { null }

/** 二级页面：和 composable<T> 一样，只是返回时离开的页面会随动画进度（含预测性返回手势）把圆角越拉越大，最后几乎成圆 */
private inline fun <reified T : Any> NavGraphBuilder.page(noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit) {
    composable<T> { entry -> PopRound(entry) { content(entry) } }
}

@Composable
private fun Tabs(graph: AppGraph, nav: NavHostController) {
    val context = LocalContext.current
    val prefs = remember { NavPrefsStore.shared(context) }
    val order by prefs.order.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("chat") }
    val holder = rememberSaveableStateHolder()
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    val unread = conversations.filter { !it.quiet }.sumOf { it.unreadCount }
    // 联系人角标：待处理的好友申请 / 入群申请（同 QQ「新朋友」）
    // 只算还没看过的申请（看过通知中心后不再提醒）
    val requestBadge by ink.kuke.chat.feature.contacts.PendingRequests.badge.collectAsStateWithLifecycle()
    LaunchedEffect(graph) {
        ink.kuke.chat.feature.contacts.PendingRequests.refresh(graph)
        // 新申请、别的设备上看过 / 忽略了：重拉一次，角标跟着变
        graph.realtime.events.collect { event ->
            val t = event.type
            if (t == "notification.seen" || t == "friend.request.ignored" || t.startsWith("friend_request.") || t.startsWith("friend.request") || t.startsWith("group.join_request")) {
                ink.kuke.chat.feature.contacts.PendingRequests.refresh(graph)
            }
        }
    }

    // 底栏：聊天、空间固定在两头，中间两项取「底栏与功能排序」的前两项
    val middle = order.take(2).mapNotNull(NavFeatures::byId).map { feature -> BarItem(feature.id, feature.label, feature.icon) }
    val items = listOf(BarItem("chat", "聊天") { KcIcons.message }) + middle + BarItem("space", "空间") { KcIcons.profile }
    val pageTabs = setOf("chat", "contacts", "feed", "space")
    val current = if (items.any { it.id == tab }) tab else "chat"

    BackHandler(enabled = current != "chat") { tab = "chat" }

    // 「我的」侧边抽屉：只有聊天 tab 能拉出（头像 / 右滑），盖住整个外壳含底栏；离开聊天 tab 直接收起
    val drawer = rememberProfileDrawerState()
    LaunchedEffect(current) { if (current != "chat") drawer.snapClosed() }

    ProfileDrawerHost(graph, drawer, swipeEnabled = current == "chat") {
    // 底栏浮在页面上（Box 叠放），页面铺满整屏、按 LocalBottomBarSpace 在底部留空
    val barHaze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize().background(KcTheme.colors.list)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBottomBarSpace provides bottomBarSpace()) {
        // 底栏几项是平级的，切换不左右滑：旧页淡出、新页淡入并上移 10dp（设计稿 M2）
        AnimatedContent(
            targetState = current,
            // 页面是底栏毛玻璃的模糊来源
            modifier = Modifier.fillMaxSize().haze(barHaze),
            transitionSpec = {
                (fadeIn(tween(210, delayMillis = 60, easing = EaseSmooth)) + slideInVertically(tween(210, easing = EaseSmooth)) { 10 * it / 892 })
                    .togetherWith(fadeOut(tween(90)))
            },
            label = "tab",
        ) { page ->
            holder.SaveableStateProvider(page) {
                when (page) {
                    "chat" -> ConversationListScreen(graph, onOpen = { nav.navigate(ChatRoute(it)) }, drawer = drawer)
                    "contacts" -> ContactsScreen(graph)
                    "feed" -> FeedScreen(graph)
                    "space" -> SpaceScreen(graph)
                    // tab 只会被设成 pageTabs 里的几项，别的功能是推页面
                    else -> Unit
                }
            }
        }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
        KcBottomBar(
            items = items,
            current = current,
            badges = mapOf("chat" to unread, "contacts" to requestBadge),
            dots = emptySet(),
            haze = barHaze,
            onSelect = { id ->
                // 排序里选了有独立页面的功能（任务、商店…）：点它是推页面，不是切 tab
                if (id in pageTabs) tab = id else NavFeatures.byId(id)?.route?.let { route -> nav.navigate(route) }
            },
        )
        }
    }
    }
}
