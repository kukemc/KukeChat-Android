package ink.kuke.chat.feature.user

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.contacts.ErrorRetry
import ink.kuke.chat.feature.contacts.LoadingBox
import ink.kuke.chat.feature.contacts.Load
import ink.kuke.chat.feature.contacts.SmallButton
import ink.kuke.chat.feature.contacts.SocialApi
import ink.kuke.chat.feature.contacts.ToastHost
import ink.kuke.chat.feature.contacts.presenceLabel
import ink.kuke.chat.feature.gifts.GiftRecipient
import ink.kuke.chat.feature.gifts.GiftSendSheet
import ink.kuke.chat.feature.gifts.GiftWallSection
import ink.kuke.chat.feature.gifts.Gifts
import ink.kuke.chat.feature.gifts.coins
import ink.kuke.chat.feature.contacts.relativeTime
import ink.kuke.chat.feature.contacts.rememberToast
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.IntimacyRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.PostRoute
import ink.kuke.chat.ui.shell.SettingsPageRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 用户资料页 / 个人空间（设计稿 P14）：沉浸封面 + 跨在交界处的头像 + 名字 / 等级 / 签名 + 统计 + 操作 + 动态。
 * 自己看自己：操作区换成「编辑资料」。好友之间多一行「我们的友谊」，点进亲密度页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(graph: AppGraph, userId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { UserApi(graph.api) }
    val social = remember(graph) { SocialApi(graph.api) }
    val toast = rememberToast()
    val me = graph.session.session.value?.user
    val isSelf = me?.id == userId
    val bonds by IntimacyCache.bonds.collectAsStateWithLifecycle()

    // 上次看到的这个人的主页（内存）：先摆出来，再静默刷新
    val cache = remember(graph, userId) { graph.screenCache.slot<UserSnapshot>("user.$userId") }
    val cached = remember(cache) { cache.get() }
    val profileCache = remember(graph, userId) { graph.screenCache.slot<Profile>(userProfileCacheKey(userId)) }
    var profile by remember { mutableStateOf<Load<Profile>>((cached?.profile ?: profileCache.get())?.let { Load.Ready(it) } ?: Load.Loading) }
    var stats by remember { mutableStateOf(cached?.stats) }
    var posts by remember { mutableStateOf<Load<List<ProfilePost>>>(cached?.posts?.let { Load.Ready(it) } ?: Load.Loading) }
    var like by remember { mutableStateOf(cached?.like) }
    var isFriend by remember { mutableStateOf(cached?.isFriend ?: false) }
    var isPending by remember { mutableStateOf(cached?.isPending ?: false) }
    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pulse by remember { mutableIntStateOf(0) }
    var showReport by remember { mutableStateOf(false) }
    var showGift by remember { mutableStateOf(false) }
    val openBot = ink.kuke.chat.feature.bots.rememberOpenBotForUser(graph)
    // 礼物：开关和「心意值」的叫法看目录；心意值以送礼回包 / 收礼事件里的最新值为准
    val giftCatalog by Gifts.catalog.collectAsStateWithLifecycle()
    val liveCharm by Gifts.charm.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Gifts.ensureCatalog(graph) }

    fun openGift(p: Profile) {
        when {
            p.isBot -> toast.show("不能给机器人送礼物")
            else -> scope.launch {
                if (Gifts.ensureCatalog(graph)?.enabled == true) showGift = true else toast.show("礼物功能暂未开放")
            }
        }
    }

    suspend fun loadAll(silent: Boolean) {
        if (!silent) {
            profile = Load.Loading
            posts = Load.Loading
        }
        coroutineScope {
            val p = async { runCatching { api.profile(userId) } }
            val s = async { runCatching { api.postStats(userId) } }
            val l = async { if (isSelf) null else api.likeState(userId) }
            val ps = async { runCatching { api.posts(userId) } }
            val f = async { if (isSelf) null else runCatching { social.friends() }.getOrNull() }
            val o = async { if (isSelf) null else runCatching { social.outgoingFriendRequests() }.getOrNull() }
            p.await().onSuccess { profile = Load.Ready(it) }.onFailure { if (!silent || profile !is Load.Ready) profile = Load.Failed(it.userMessage()) }
            stats = s.await().getOrNull() ?: stats
            like = l.await() ?: like
            ps.await().onSuccess { posts = Load.Ready(it) }.onFailure { if (!silent || posts !is Load.Ready) posts = Load.Failed(it.userMessage()) }
            f.await()?.let { list -> isFriend = list.any { it.other?.id == userId } }
            o.await()?.let { list -> isPending = list.any { it.status == "pending" && it.receiverId == userId } }
        }
        (profile as? Load.Ready)?.value?.let {
            cache.put(UserSnapshot(it, stats, (posts as? Load.Ready)?.value, like, isFriend, isPending))
            profileCache.put(it)
        }
    }

    LaunchedEffect(userId) {
        IntimacyCache.start(graph)
        // 有缓存时静默刷新；没有缓存时初始状态本来就是「加载中」
        loadAll(silent = true)
    }

    fun openChat(then: (Long) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { social.openDirect(userId) }.onSuccess(then).onFailure { toast.show(it.userMessage()) }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(colors.chat)) {
        when (val state = profile) {
            Load.Loading -> LoadingBox("正在加载资料…")
            is Load.Failed -> ErrorRetry(state.message, onRetry = { scope.launch { loadAll(silent = false) } })
            is Load.Ready -> {
                val p = state.value
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { refreshing = true; loadAll(silent = true); refreshing = false } },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                        item(key = "head") {
                            ProfileHeader(
                                p = p, isSelf = isSelf, stats = stats,
                                friendBond = bonds[p.id],
                                isFriend = isFriend,
                                charm = (liveCharm[p.id] ?: p.charm)?.takeIf { giftCatalog?.enabled == true && !p.isBot }?.let { shortCount(it) to (giftCatalog?.charmLabel ?: "心意值") },
                                trailing = {
                                    if (isSelf) {
                                        SmallButton("编辑资料", primary = true, modifier = Modifier.width(104.dp)) { nav.go(SettingsPageRoute("profile")) }
                                    } else {
                                        SmallButton("发消息", modifier = Modifier.width(88.dp).border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp))) { openChat { nav.go(ChatRoute(it)) } }
                                        val likeState = like
                                        if (likeState != null) {
                                            Spacer(Modifier.width(8.dp))
                                            SmallButton(if (likeState.likedToday) "已赞" else "赞 TA", primary = true, modifier = Modifier.width(88.dp)) {
                                                if (!likeState.canLike) {
                                                    toast.show(likeReasonText(likeState.reason))
                                                } else {
                                                    scope.launch {
                                                        runCatching { api.like(userId) }
                                                            .onSuccess { like = it; pulse += 1 }
                                                            .onFailure { toast.show(it.userMessage()) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        if (!isSelf) item(key = "actions") {
                            Column(Modifier.fillMaxWidth().background(colors.panel).padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    // 机器人加不了好友：这一格换成「查看机器人」，进它的机器人详情
                                    if (p.isBot) {
                                        ActionTile(KcIcons.bot, "查看机器人", Modifier.weight(1f)) { openBot(userId) }
                                    } else ActionTile(
                                        KcIcons.userPlus,
                                        if (isFriend) "已是好友" else if (isPending) "已申请" else "加好友",
                                        Modifier.weight(1f),
                                        enabled = !isFriend && !isPending,
                                    ) {
                                        scope.launch {
                                            runCatching { social.sendFriendRequest(userId) }
                                                .onSuccess { isPending = true; toast.show("好友申请已发送") }
                                                .onFailure { toast.show(it.userMessage()) }
                                        }
                                    }
                                    ActionTile(KcIcons.poke, "戳一戳", Modifier.weight(1f)) {
                                        openChat { cid ->
                                            scope.launch {
                                                runCatching { api.poke(cid, userId) }
                                                    .onSuccess { toast.show("已戳了 ${p.displayName}") }
                                                    .onFailure { toast.show(it.userMessage()) }
                                            }
                                        }
                                    }
                                    ActionTile(KcIcons.gift, "送礼物", Modifier.weight(1f)) { openGift(p) }
                                    ActionTile(KcIcons.flag, "举报", Modifier.weight(1f), tint = colors.danger) { showReport = true }
                                }
                            }
                        }
                        // 好友之间：我们的友谊
                        val bond = bonds[p.id]
                        if (!isSelf && isFriend && bond != null) {
                            item(key = "bond") {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp).background(colors.panel).clickable { nav.go(IntimacyRoute(p.id)) }.padding(horizontal = 16.dp).height(56.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    KcIcon(KcIcons.heartHandshake, tint = colors.unread, size = 22.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("我们的友谊", style = type.rowTitle, color = colors.text)
                                    Spacer(Modifier.width(8.dp))
                                    FriendBadgeMark(p.id, size = 18.dp)
                                    Spacer(Modifier.weight(1f))
                                    Text("Lv.${bond.level} ${bond.levelName}", style = type.secondary, color = colors.muted)
                                    Spacer(Modifier.width(4.dp))
                                    KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
                                }
                            }
                        }
                        // 好友之间：火花（docs/sparks.md）
                        if (!isSelf && isFriend) {
                            item(key = "spark") { ink.kuke.chat.feature.spark.SparkProfileRow(graph, p.id) }
                        }
                        item(key = "gifts") {
                            GiftWallSection(graph, p.id, p.displayName, p.avatarUrl, isSelf, p.isBot, liveCharm[p.id] ?: p.charm, onSend = { openGift(p) })
                        }
                        item(key = "posts-title") {
                            Text("动态", style = type.group, color = colors.muted, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                        }
                        when (val list = posts) {
                            Load.Loading -> item(key = "posts-loading") { Text("正在加载动态…", style = type.secondary, color = colors.muted, modifier = Modifier.padding(16.dp)) }
                            is Load.Failed -> item(key = "posts-failed") {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(list.message, style = type.secondary, color = colors.muted, modifier = Modifier.weight(1f))
                                    SmallButton("重试", primary = true) { scope.launch { runCatching { api.posts(userId) }.onSuccess { posts = Load.Ready(it) }.onFailure { e -> toast.show(e.userMessage()) } } }
                                }
                            }
                            is Load.Ready -> if (list.value.isEmpty()) {
                                item(key = "posts-empty") { Text(if (isSelf) "你还没有发布过动态" else "TA 还没有发布过动态", style = type.secondary, color = colors.muted, modifier = Modifier.padding(16.dp)) }
                            } else {
                                items(list.value, key = { "p${it.id}" }) { post ->
                                    PostCard(post, showStatus = isSelf) { nav.go(PostRoute(post.id)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 返回按钮：悬浮在封面左上角，加载中 / 失败时也在
        val press = rememberInteraction()
        Box(
            Modifier.statusBarsPadding().padding(start = 8.dp, top = 6.dp).size(40.dp).pressScale(press, 0.9f).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = press, indication = null) { nav.back() },
            contentAlignment = Alignment.Center,
        ) { KcIcon(KcIcons.chevronLeft, tint = Color.White, size = 22.dp, contentDescription = "返回") }

        ToastHost(toast)
    }

    if (showReport) {
        val name = (profile as? Load.Ready)?.value?.displayName ?: "该用户"
        ReportUserSheet(api, userId, name, conversationId = null, onDismiss = { showReport = false }, onDone = { showReport = false; toast.show(it) })
    }
    // 从个人主页送礼：不落聊天消息，送完在面板里说一声
    val giftTarget = (profile as? Load.Ready)?.value
    if (showGift && giftTarget != null) {
        val worn = giftTarget.cosmetics?.values?.mapNotNull { ((it as? kotlinx.serialization.json.JsonObject)?.get("sku") as? kotlinx.serialization.json.JsonPrimitive)?.content }?.toSet().orEmpty()
        GiftSendSheet(
            graph = graph,
            recipient = GiftRecipient(giftTarget.id, giftTarget.displayName, giftTarget.avatarUrl, giftTarget.isBirthdayToday, worn),
            conversationId = null,
            onChangeRecipient = null,
            onDismiss = { showGift = false },
        )
    }
}

@Composable
private fun ProfileHeader(p: Profile, isSelf: Boolean, stats: PostStats?, friendBond: FriendBond?, isFriend: Boolean, charm: Pair<String, String>? = null, trailing: @Composable () -> Unit = {}) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val coverHeight = 236.dp
    val overlap = 40.dp
    val avatarSize = 92.dp
    val cover = Endpoints.absolute(p.profileCoverUrl)
    Box(Modifier.fillMaxWidth()) {
        // 沉浸封面：有封面图用图，没有就用强调色渐变
        Box(Modifier.fillMaxWidth().height(coverHeight).background(Brush.verticalGradient(listOf(colors.fill, colors.fill.copy(alpha = 0.55f))))) {
            if (cover != null) {
                SubcomposeAsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), loading = {}, error = {})
            }
            // 上下压暗：顶部护住状态栏和返回键，底部护住白字统计（浅色封面上白字也看得清）
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.3f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        }
        // 内容面以 24dp 圆角升起压住封面
        Column(
            Modifier.fillMaxWidth().padding(top = coverHeight - overlap)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(colors.panel)
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(avatarSize / 2 + 10.dp + 2.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { trailing() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.displayName, style = type.display.copy(fontSize = 22.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ink.kuke.chat.feature.birthday.BirthdayMark(p.isBirthdayToday, 18.dp)
                Spacer(Modifier.width(8.dp))
                ink.kuke.chat.feature.user.LevelChip(p.accountLevel)
                if (isFriend) {
                    Spacer(Modifier.width(6.dp))
                    FriendBadgeMark(p.id, size = 18.dp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "@${p.username}" + (if (p.isBot) " · 机器人" else "") + " · " + (if (isSelf) "我" else presenceLabelOf(p)),
                style = type.caption, color = colors.muted,
            )
            val tagline = p.profileTagline?.takeIf { it.isNotBlank() } ?: p.bio?.takeIf { it.isNotBlank() }
            Spacer(Modifier.height(8.dp))
            Text(tagline ?: "这个人很神秘，还没有简介", style = type.body, color = if (tagline == null) colors.subtle else colors.text)
            val meta = listOfNotNull(p.profileTitle, p.profileLocation).filter { it.isNotBlank() }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta.joinToString(" · "), style = type.caption, color = colors.muted)
            }
            Spacer(Modifier.height(14.dp))
        }
        // 统计放在封面右下（设计稿 P14）
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = coverHeight - overlap - 70.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val stat: @Composable (String, String) -> Unit = { v, l ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val shade = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, 1f), 6f)
                    Text(v, style = type.number.copy(fontSize = 18.sp, shadow = shade), color = Color.White)
                    Text(l, style = type.caption.copy(shadow = shade), color = Color.White.copy(alpha = 0.9f))
                }
            }
            if (p.ccwBound) {
                stat(shortCount(p.ccwFollowing ?: 0), "关注"); stat(shortCount(p.ccwFollowers ?: 0), "粉丝"); stat(shortCount(p.ccwLikes ?: 0), "获赞")
            } else {
                // 还没拿到的数显示「–」，不显示 0
                stat(stats?.postCount?.let(::shortCount) ?: "–", "动态"); stat(stats?.likeCount?.let(::shortCount) ?: "–", "获赞"); stat(stats?.commentCount?.let(::shortCount) ?: "–", "评论")
            }
            p.profileLikeCount?.let { stat(shortCount(it), "资料赞") }
            charm?.let { (value, label) -> stat(value, label) }
        }
        // 头像跨在封面与内容面的交界处
        Box(
            // 不裁切：头像框画在头像外面
            Modifier.padding(start = 16.dp, top = coverHeight - overlap - avatarSize / 2).size(avatarSize + 8.dp)
                .background(colors.panel, CircleShape).padding(4.dp),
        ) { Avatar(p.avatarUrl, p.displayName, p.id, avatarSize, frame = ink.kuke.chat.feature.shop.rememberAvatarFrame(p.id, p.cosmetics)) }
    }
}

private fun presenceLabelOf(p: Profile): String =
    presenceLabel(ink.kuke.chat.core.model.User(id = p.id, presenceStatus = p.presenceStatus, presenceText = p.presenceText))

@Composable
private fun PostCard(post: ProfilePost, showStatus: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel).clickable(onClick = onClick).padding(14.dp),
    ) {
        if (post.pinnedAt != null) Text("置顶", style = type.caption, color = colors.accent, fontWeight = FontWeight.W600)
        Text(post.content.ifBlank { if (post.imageUrls.isNotEmpty()) "[图片]" else "（没有文字）" }, style = type.body, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (post.imageUrls.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("${post.imageUrls.size} 张图片", style = type.caption, color = colors.muted)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(relativeTime(post.createdAt), style = type.caption, color = colors.subtle)
            if (showStatus && post.moderationStatus != "approved") {
                Spacer(Modifier.width(8.dp))
                Text(if (post.moderationStatus == "pending") "审核中" else "未通过审核", style = type.caption, color = colors.warn)
            }
            Spacer(Modifier.weight(1f))
            KcIcon(KcIcons.heart, tint = colors.subtle, size = 14.dp)
            Spacer(Modifier.width(3.dp))
            Text(post.likeCount.toString(), style = type.caption, color = colors.muted)
            Spacer(Modifier.width(12.dp))
            KcIcon(KcIcons.message, tint = colors.subtle, size = 14.dp)
            Spacer(Modifier.width(3.dp))
            Text(post.commentCount.toString(), style = type.caption, color = colors.muted)
        }
    }
}

/** 某人资料在页面缓存里的 key：用户主页和资料卡共用 */
internal fun userProfileCacheKey(userId: Long) = "user.profile.$userId"

/** 用户主页的一份快照（只在内存，退出登录清掉） */
private class UserSnapshot(
    val profile: Profile,
    val stats: PostStats?,
    val posts: List<ProfilePost>?,
    val like: LikeState?,
    val isFriend: Boolean,
    val isPending: Boolean,
)
