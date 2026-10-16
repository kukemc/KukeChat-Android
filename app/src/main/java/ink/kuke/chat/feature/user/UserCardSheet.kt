package ink.kuke.chat.feature.user

import ink.kuke.chat.core.data.slot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.contacts.ConfirmDialog
import ink.kuke.chat.feature.contacts.FilterPill
import ink.kuke.chat.feature.contacts.KcTextField
import ink.kuke.chat.feature.contacts.Load
import ink.kuke.chat.feature.contacts.SocialApi
import ink.kuke.chat.feature.contacts.presenceLabel
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.icons.duotone
import ink.kuke.chat.ui.shell.ChatRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.IntimacyRoute
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

/**
 * 聊天里点头像弹出的用户名片（设计稿 P30）：头部（封面条、头像、名字、等级、群身份）+ 赞 TA + 群管操作。
 * 群管操作只在有权限时出现，判断规则同电脑版 MemberMenu：
 *   群主：可禁言 / 移出非群主；可设置管理员和头衔。管理员：只能禁言 / 移出普通成员。
 * 服务端最终拒绝时，直接展示它的 detail.message。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserCardSheet(graph: AppGraph, userId: Long, conversationId: Long?, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val nav = LocalAppNav.current
    val scope = rememberCoroutineScope()
    val api = remember(graph) { UserApi(graph.api) }
    val social = remember(graph) { SocialApi(graph.api) }
    val me = graph.session.session.value?.user
    val isSelf = me?.id == userId
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    val conversation = conversationId?.let { id -> conversations.firstOrNull { it.id == id } }
    val myRole = conversation?.myRole ?: "member"
    val isGroup = conversation?.isGroup == true

    // 资料和用户主页共用一格缓存（内存）：卡片一弹出来就有内容，再静默刷新
    val profileCache = remember(graph, userId) { graph.screenCache.slot<Profile>(userProfileCacheKey(userId)) }
    var profile by remember { mutableStateOf<Load<Profile>>(profileCache.get()?.let { Load.Ready(it) } ?: Load.Loading) }
    var member by remember { mutableStateOf<GroupMember?>(null) }
    var like by remember { mutableStateOf<LikeState?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pulse by remember { mutableIntStateOf(0) }
    var showMute by remember { mutableStateOf(false) }
    var showCustomMute by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }
    var showTitle by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(userId, conversationId, reload) {
        if (profile !is Load.Ready) profile = Load.Loading
        runCatching { api.profile(userId) }
            .onSuccess { profile = Load.Ready(it); profileCache.put(it) }
            .onFailure { if (profile !is Load.Ready) profile = Load.Failed(it.userMessage()) }
        if (!isSelf) like = api.likeState(userId)
        if (conversationId != null && isGroup) {
            runCatching { api.member(conversationId, userId) }.onSuccess { member = it }
        }
    }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            runCatching { block() }.onFailure { message = it.userMessage() }
            busy = false
        }
    }

    fun openChat(then: (Long) -> Unit) = act {
        val id = social.openDirect(userId)
        then(id)
    }

    val m = member
    val manageable = isGroup && !isSelf && m != null
    val canChangeRole = manageable && myRole == "owner" && m?.role != "owner"
    val canMute = manageable && myRole != "member" && (if (myRole == "owner") m?.role != "owner" else m?.role == "member")
    val canRemove = manageable && myRole != "member" && m?.role != "owner" && (myRole == "owner" || m?.role == "member")

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = colors.panel) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            when (val state = profile) {
                Load.Loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("正在加载名片…", style = type.secondary, color = colors.muted)
                }
                is Load.Failed -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.message, style = type.secondary, color = colors.muted)
                    Spacer(Modifier.height(12.dp))
                    ink.kuke.chat.feature.contacts.SmallButton("重试", primary = true) { reload++ }
                }
                is Load.Ready -> {
                    val p = state.value
                    // 头部：圆角封面条 + 压在上面的头像 + 右侧「赞 TA」
                    val coverShape = RoundedCornerShape(16.dp)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(124.dp)) {
                        Box(Modifier.fillMaxWidth().height(80.dp).clip(coverShape)) {
                            if (!p.profileCoverUrl.isNullOrBlank()) {
                                coil.compose.AsyncImage(
                                    model = ink.kuke.chat.feature.chat.Meta.imageUrl(p.profileCoverUrl),
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.fill.copy(alpha = 0.55f), colors.fill))))
                            }
                        }
                        Box(
                            Modifier.padding(start = 8.dp, top = 40.dp).size(86.dp).background(colors.panel, CircleShape).padding(3.dp),
                        ) { Avatar(p.avatarUrl, p.displayName, p.id, 80.dp, frame = ink.kuke.chat.feature.shop.rememberAvatarFrame(p.id, p.cosmetics)) }
                        if (!isSelf) {
                            val likeState = like
                            Row(
                                Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp).height(36.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(colors.panel)
                                    .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp))
                                    .clickable(enabled = likeState != null) {
                                        val ls = likeState ?: return@clickable
                                        if (!ls.canLike) message = likeReasonText(ls.reason)
                                        else act { like = api.like(userId); pulse += 1 }
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val liked = likeState?.likedToday == true
                                KcIcon(KcIcons.heart.let { if (liked) it.duotone() else it }, tint = if (liked) colors.unread else colors.accent, size = 18.dp)
                                Text(
                                    ((likeState?.likeCount ?: p.profileLikeCount) ?: 0).toString(),
                                    style = type.secondary, color = colors.text, fontWeight = FontWeight.W700, modifier = Modifier.padding(start = 6.dp),
                                )
                                Text(if (liked) "已赞" else "赞 TA", style = type.secondary, color = colors.muted, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p.displayName, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.W800, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            ink.kuke.chat.feature.birthday.BirthdayMark(p.isBirthdayToday, 18.dp)
                            LevelChip(p.accountLevel)
                            p.juryBadge?.let { RoleChip(when (it) { "senior" -> "资深风纪委员"; "regular" -> "风纪委员"; else -> "见习风纪委员" }, highlight = true) }
                            if (m != null && isGroup && m.role != "member") RoleChip(roleLabel(m.role), highlight = false, ok = true)
                            FriendBadgeMark(p.id, size = 18.dp, graph = graph)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("@${p.username} · ${presenceLabel(ink.kuke.chat.core.model.User(id = p.id, presenceStatus = p.presenceStatus, presenceText = p.presenceText))}", style = type.caption, color = colors.muted)
                        if (m != null && isGroup && (!m.title.isNullOrBlank() || m.isMuted)) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                m.title?.takeIf { it.isNotBlank() }?.let { RoleChip(it, highlight = false) }
                                if (m.isMuted) RoleChip("已禁言", highlight = false, danger = true)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val sign = p.profileTagline?.takeIf { it.isNotBlank() } ?: p.bio?.takeIf { it.isNotBlank() }
                        Text(sign ?: "这个人很神秘，还没有简介", style = type.body.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified), color = if (sign == null) colors.subtle else colors.text, fontWeight = if (sign == null) FontWeight.W400 else FontWeight.W600, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        // CCW 数据条：绑定了 CCW 才有
                        if (p.ccwBound) {
                            Row(
                                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RoleChip("CCW", highlight = false, ok = true)
                                Text(
                                    buildString {
                                        append("粉丝 ").append(p.ccwFollowers?.let { compactNumber(it) } ?: "–")
                                        append(" · 获赞 ").append(p.ccwLikes?.let { compactNumber(it) } ?: "–")
                                    },
                                    style = type.secondary, color = colors.text, fontWeight = FontWeight.W600, modifier = Modifier.padding(start = 10.dp).weight(1f),
                                )
                            }
                        }
                        // 好友之间：一行「亲密度 Lv.N · 名称」，点进「我们的友谊」；不是好友或服务端没这个功能就不出现
                        val bonds by IntimacyCache.bonds.collectAsStateWithLifecycle()
                        val bond = bonds[p.id]
                        if (!isSelf && bond != null) {
                            Row(
                                Modifier.padding(top = 10.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.unread.copy(alpha = 0.08f))
                                    .clickable { onDismiss(); nav.go(IntimacyRoute(p.id)) }.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KcIcon(KcIcons.heartHandshake, tint = colors.unread, size = 17.dp)
                                Text("亲密度", style = type.secondary, color = colors.muted, modifier = Modifier.padding(start = 8.dp))
                                Text(
                                    if (bond.levelName.isNotBlank()) "Lv.${bond.level} · ${bond.levelName}" else "Lv.${bond.level}",
                                    style = type.secondary, color = colors.text, fontWeight = FontWeight.W700, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                                )
                                if (bond.todayGained > 0) Text("今日 +${bond.todayGained}", style = type.caption, color = colors.unread, modifier = Modifier.padding(end = 4.dp))
                                KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // 基础操作：个人主页（描边）+ 发消息（主色）
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel).border(1.dp, colors.borderStrong, RoundedCornerShape(12.dp))
                                .clickable { onDismiss(); nav.go(UserRoute(userId)) }.padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KcIcon(KcIcons.home, tint = colors.text, size = 18.dp)
                            Text("个人主页", style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W700, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (!isSelf) {
                            Row(
                                Modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(colors.fill)
                                    .clickable(enabled = !busy) { openChat { id -> onDismiss(); nav.go(ChatRoute(id)) } }.padding(horizontal = 22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KcIcon(KcIcons.message, tint = colors.onFill, size = 18.dp)
                                Text("发消息", style = type.rowTitle, color = colors.onFill, fontWeight = FontWeight.W700, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }

                    message?.let {
                        Text(it, style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    }

                    // 群管操作：一排图标 + 文案；按权限出现，举报始终在最后
                    if (!isSelf) {
                        Text(
                            if (canMute || canChangeRole || canRemove) "群管操作" else "更多",
                            style = type.group, color = colors.muted, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            ActionTile(KcIcons.poke, "戳一戳", Modifier.weight(1f), enabled = !busy) {
                                act {
                                    val cid = conversationId ?: social.openDirect(userId)
                                    api.poke(cid, userId)
                                    message = "已戳了 ${p.displayName}"
                                }
                            }
                            if (canChangeRole && m != null) {
                                ActionTile(if (m.role == "admin") KcIcons.shield else KcIcons.shieldCheck, if (m.role == "admin") "取消管理员" else "设为管理员", Modifier.weight(1f)) {
                                    act {
                                        member = api.setRole(conversationId!!, userId, if (m.role == "admin") "member" else "admin")
                                        message = if (m.role == "admin") "已取消管理员" else "已设为管理员"
                                    }
                                }
                                ActionTile(KcIcons.badgeCheck, "群头衔", Modifier.weight(1f)) { titleText = m.title.orEmpty(); showTitle = true }
                            }
                            if (canMute && m != null) {
                                if (m.isMuted) ActionTile(KcIcons.volume, "解除禁言", Modifier.weight(1f)) {
                                    act { member = api.setMute(conversationId!!, userId, null); message = "已解除禁言" }
                                } else ActionTile(KcIcons.muted, "禁言", Modifier.weight(1f)) { showMute = !showMute }
                            }
                            if (canRemove) ActionTile(KcIcons.trash, "移出", Modifier.weight(1f), tint = colors.danger) { confirmRemove = true }
                            ActionTile(KcIcons.flag, "举报", Modifier.weight(1f), tint = colors.danger) { showReport = true }
                        }
                        if (showMute && canMute) {
                            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("10分钟" to 10L, "1小时" to 60L, "12小时" to 720L, "1天" to 1440L).forEach { (label, minutes) ->
                                    FilterPill(label, selected = false) {
                                        showMute = false
                                        act { member = api.setMute(conversationId!!, userId, minutes); message = "已禁言 $label" }
                                    }
                                }
                                FilterPill("自定义", selected = false) { customMinutes = ""; showCustomMute = true }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomMute) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCustomMute = false },
            containerColor = colors.panel,
            title = { Text("自定义禁言时长", style = type.title, color = colors.text) },
            text = { KcTextField(customMinutes, { if (it.all(Char::isDigit) && it.length <= 6) customMinutes = it }, "分钟数（例如 30）", icon = null) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val minutes = customMinutes.toLongOrNull()
                    showCustomMute = false
                    showMute = false
                    if (minutes != null && minutes > 0) act { member = api.setMute(conversationId!!, userId, minutes); message = "已禁言 $minutes 分钟" }
                }) { Text("确定", color = colors.accent, fontWeight = FontWeight.W600) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showCustomMute = false }) { Text("取消", color = colors.muted) } },
        )
    }

    if (showTitle) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTitle = false },
            containerColor = colors.panel,
            title = { Text("群头衔", style = type.title, color = colors.text) },
            text = { KcTextField(titleText, { if (it.length <= 12) titleText = it }, "留空清除头衔", icon = null) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showTitle = false
                    act {
                        member = api.setTitle(conversationId!!, userId, titleText.trim().ifEmpty { null })
                        message = if (titleText.isBlank()) "群头衔已清除" else "群头衔已更新"
                    }
                }) { Text("保存", color = colors.accent, fontWeight = FontWeight.W600) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showTitle = false }) { Text("取消", color = colors.muted) } },
        )
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "移出群聊",
            message = "确定把「${(profile as? Load.Ready)?.value?.displayName ?: "该成员"}」移出本群吗？",
            confirmLabel = "移出",
            danger = true,
            onConfirm = {
                confirmRemove = false
                act {
                    api.removeMember(conversationId!!, userId)
                    graph.conversations.refreshSoon(0)
                    onDismiss()
                }
            },
            onDismiss = { confirmRemove = false },
        )
    }

    if (showReport) {
        val name = (profile as? Load.Ready)?.value?.displayName ?: "该用户"
        ReportUserSheet(api, userId, name, conversationId, onDismiss = { showReport = false }, onDone = { showReport = false; message = it })
    }
}

@Composable
private fun RoleChip(text: String, highlight: Boolean, danger: Boolean = false, ok: Boolean = false) {
    val colors = KcTheme.colors
    val bg = when {
        danger -> colors.danger.copy(alpha = 0.12f)
        ok -> colors.ok.copy(alpha = 0.14f)
        highlight -> colors.accentSoft
        else -> colors.panelMuted
    }
    val fg = when {
        danger -> colors.danger
        ok -> colors.ok
        highlight -> colors.accent
        else -> colors.muted
    }
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.W600)
    }
}

@Composable
private fun ManageRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).height(48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        KcIcon(icon, tint = if (danger) colors.danger else colors.text, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = KcTheme.type.body, color = if (danger) colors.danger else colors.text, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = KcTheme.type.secondary, color = colors.subtle)
    }
}

/** 21000 → 2.1w，名片上 CCW 数据的写法 */
private fun compactNumber(n: Int): String = if (n >= 10000) String.format("%.1fw", n / 10000f).replace(".0w", "w") else n.toString()
