package ink.kuke.chat.feature.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.feature.bots.rememberOpenBotForUser
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.ChatController
import ink.kuke.chat.feature.chat.Chip
import ink.kuke.chat.feature.chat.KcButton
import ink.kuke.chat.feature.chat.KcConfirm
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.KcSwitch
import ink.kuke.chat.feature.chat.KcTextField
import ink.kuke.chat.feature.chat.MemberLite
import ink.kuke.chat.feature.chat.ReportSheet
import ink.kuke.chat.feature.chat.ReportTarget
import ink.kuke.chat.feature.chat.SheetForm
import ink.kuke.chat.feature.chat.StateText
import ink.kuke.chat.feature.chat.asObj
import ink.kuke.chat.feature.chat.copyText
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.feature.groupplus.GroupPlusEntry
import ink.kuke.chat.feature.groupplus.PlusTierTag
import ink.kuke.chat.feature.groupplus.effectivePlusTier
import ink.kuke.chat.ui.shell.GroupFilesRoute
import ink.kuke.chat.ui.shell.GroupPlusRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.shell.UserRoute
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 点群成员的回调：用户名片面板由 feature/user 提供，合并后把默认实现换成弹 UserCardSheet */
typealias MemberClickHandler = (Long) -> Unit

/**
 * 群聊资料页（设计稿 P07）：资料头 · 成长签到 · 群成员 · 资料管理 · 加群设置 · 发言权限 · 我的设置 · 危险区。
 * 只有能管理的分组（my_role 是群主 / 管理员）才出现；退出 / 解散 / 清空都要确认。
 */
@Composable
fun GroupInfoScreen(graph: AppGraph, conversationId: Long) {
    val nav = LocalAppNav.current
    GroupInfoContent(graph, conversationId, onBack = nav::back, onMemberClick = { nav.go(UserRoute(it)) })
}

private sealed interface Sub {
    data object Members : Sub
    data object JoinRequests : Sub
    data object EditName : Sub
    data object EditDescription : Sub
    data object EditJoinQuestion : Sub
    data object EditNickname : Sub
    data object EditRemark : Sub
    data object MessageSetting : Sub
    data object ConfirmClear : Sub
    data object ConfirmLeave : Sub
    data object Report : Sub
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupInfoContent(graph: AppGraph, conversationId: Long, onBack: () -> Unit, onMemberClick: MemberClickHandler) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(conversationId) { GroupModel(graph, conversationId, scope) }
    val openBot = rememberOpenBotForUser(graph)
    val nav = LocalAppNav.current
    // 群升级档位（实时：group.* 事件会让会话列表刷新）
    val liveConversation = graph.conversations.items.collectAsStateWithLifecycle().value.firstOrNull { it.id == conversationId }
    val plusTier = liveConversation?.effectivePlusTier ?: "free"
    var sub by remember { mutableStateOf<Sub?>(null) }
    val report = remember(conversationId) { ChatController(graph, conversationId, scope) }
    // 新增区块（机器人、排行榜、群表情、邀请 / 分享、游戏模式…）的状态与面板
    val extras = rememberGroupExtras(graph, conversationId)
    LaunchedEffect(conversationId) { model.reload() }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.changeAvatar(context, uri) { msg -> toast(context, msg ?: "群头像已更新") }
    }

    fun say(result: String?) {
        if (result != null) toast(context, result)
    }

    KcPage(title = "群聊资料", onBack = onBack) {
        val detail = model.detail
        when {
            model.load == GroupLoad.Loading && detail == null -> StateText("加载中…")
            model.load == GroupLoad.Failed -> StateText(model.error ?: "加载失败", error = true, onRetry = { model.reload() })
            model.load == GroupLoad.NotMember || detail == null -> NotMemberView(graph, conversationId, onJoined = { model.reload() })
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 资料头
                Card {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clickable(enabled = detail.manager) { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Avatar(detail.avatarUrl, detail.title, tone = detail.id, size = 64.dp, plusTier = plusTier)
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(detail.title, style = KcTheme.type.title, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                PlusTierTag(plusTier, Modifier.padding(start = 6.dp), large = true)
                            }
                            Text("群号 ${detail.id} · ${detail.memberCount} 名成员", style = KcTheme.type.caption, color = colors.muted)
                            if (detail.description.isNotBlank()) Text(detail.description, style = KcTheme.type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        // 分享群聊：转发群名片 / 复制邀请链接
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).clickable { extras.sheet = GroupExtra.Share },
                            contentAlignment = Alignment.Center,
                        ) { KcIcon(KcIcons.share, tint = colors.text, size = 20.dp, contentDescription = "分享群聊") }
                    }
                }

                // 群升级：档位 / 到期 / 人数 / 群文件。群主管理员可以购买，其他成员只看权益
                GroupPlusEntry(
                    graph = graph,
                    conversationId = conversationId,
                    manager = detail.manager,
                    onOpen = { nav.go(GroupPlusRoute(conversationId)) },
                    onOpenFiles = { nav.go(GroupFilesRoute(conversationId)) },
                )

                // 成长：等级、签到（可附留言）、排行榜入口
                GroupGrowthCard(model, extras, levelTitle = detail.levelTitles.takeIf { detail.levelTitlesEnabled }?.lastOrNull { model.checkin != null && it.first <= model.checkin!!.level }?.second)

                // 群机器人
                GroupBotsSection(extras, manager = detail.manager)

                // 群成员
                Card {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { sub = Sub.Members }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("群成员", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
                            Text("查看全部 ${detail.memberCount} 人", style = KcTheme.type.caption, color = colors.muted)
                            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
                        }
                        val preview = model.members?.items.orEmpty().take(if (detail.manager) 8 else 9)
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            maxItemsInEachRow = 5,
                        ) {
                            // 成员里的机器人点了直接进机器人详情
                            preview.forEach { member -> MemberTile(member) { if (member.isBot) openBot(member.userId) else onMemberClick(member.userId) } }
                            // 末尾的「邀请 / 移除」虚线位
                            DashedTile("邀请", KcIcons.plus) {
                                // 群主 / 管理员：从好友里拉人；普通成员：复制邀请链接
                                if (detail.manager) extras.sheet = GroupExtra.Invite else scope.launch {
                                    runCatching { graph.api.post("/invites/group/$conversationId").asObj()?.str("url") }
                                        .onSuccess { url ->
                                            if (!url.isNullOrBlank()) {
                                                copyText(context, url)
                                                toast(context, "邀请链接已复制，发给想邀请的人")
                                            }
                                        }
                                        .onFailure { toast(context, it.userMessage()) }
                                }
                            }
                            if (detail.manager) DashedTile("移除", KcIcons.minus) { sub = Sub.Members }
                        }
                    }
                }

                // 群表情：全员可看，群主 / 管理员维护
                GroupEmojiEntry(graph, extras, manager = detail.manager)

                // 群公告：全员可看；群主 / 管理员在列表右上角发布
                Card {
                    GroupRow(KcIcons.announcement, "群公告", liveConversation?.announcement?.takeIf { it.isNotBlank() } ?: if (detail.manager) "发布公告" else "暂无") {
                        nav.go(ink.kuke.chat.ui.shell.GroupAnnouncementsRoute(conversationId))
                    }
                }

                // 资料管理
                if (detail.manager) {
                    Card {
                        Column {
                            GroupRow(KcIcons.edit, "群名称", detail.title) { sub = Sub.EditName }
                            GroupRow(KcIcons.info, "群简介", detail.description.ifBlank { "未设置" }) { sub = Sub.EditDescription }
                            GroupRow(KcIcons.layoutGrid, "群分类", detail.category?.takeIf { it.isNotBlank() } ?: "未设置") { extras.sheet = GroupExtra.Category }
                            GroupRow(KcIcons.userPlus, "入群申请", if (detail.pendingRequests > 0) "${detail.pendingRequests} 条待处理" else "无", highlight = detail.pendingRequests > 0) { sub = Sub.JoinRequests }
                            SwitchRow(KcIcons.award, "显示等级头衔", detail.levelTitlesEnabled, hint = detail.levelTitles.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "LV${it.first}+${it.second}" }) { on ->
                                model.patch("/settings", buildJsonObject { put("level_titles_enabled", on) }, ::say)
                            }
                        }
                    }
                    // 加群设置
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("加群方式", style = KcTheme.type.rowTitle, color = colors.text)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("approval" to "需要审核", "question" to "回答问题", "invite_only" to "仅邀请", "open" to "自由加入").forEach { (key, label) ->
                                    Chip(label, detail.joinMode == key) {
                                        if (key == "question" && detail.joinQuestion.isBlank()) {
                                            toast(context, "请先填写加群问题")
                                            sub = Sub.EditJoinQuestion
                                        } else {
                                            model.patch("/settings", buildJsonObject {
                                                put("join_mode", key)
                                                // 「回答问题」和自动审批互斥
                                                if (key == "question") put("auto_approve", false)
                                            }, ::say)
                                        }
                                    }
                                }
                            }
                            if (detail.joinMode == "question") {
                                Text("问题：${detail.joinQuestion.ifBlank { "未设置" }}", style = KcTheme.type.secondary, color = colors.accent, modifier = Modifier.clickable { sub = Sub.EditJoinQuestion })
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("加群自动审批", style = KcTheme.type.secondary, color = colors.text, modifier = Modifier.weight(1f))
                                    KcSwitch(detail.autoApprove, { on -> model.patch("/settings", buildJsonObject { put("auto_approve", on) }, ::say) })
                                }
                            }
                        }
                    }
                    // 发言权限
                    Card {
                        Column {
                            SwitchRow(KcIcons.muted, "全员禁言", detail.allMuted) { on -> model.patch("/settings", buildJsonObject { put("all_muted", on) }, ::say) }
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("发言限频", style = KcTheme.type.rowTitle, color = colors.text)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0 to "未开启", 10 to "每分钟 10 条", 5 to "每分钟 5 条").forEach { (value, label) ->
                                        Chip(label, detail.rateLimit == value) {
                                            model.patch("/settings", buildJsonObject { put("message_rate_limit_per_minute", value) }, ::say)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 任务设置
                    GroupTaskSettings(detail, model)
                    // 游戏模式：只有群主能配置
                    if (detail.owner) GameModeSection(extras)
                }

                // 我的设置
                Card {
                    Column {
                        GroupRow(KcIcons.profile, "我的本群昵称", detail.myNickname.ifBlank { "未设置" }) { sub = Sub.EditNickname }
                        GroupRow(KcIcons.edit, "群聊备注", detail.myRemark.ifBlank { "未设置" }) { sub = Sub.EditRemark }
                        SwitchRow(KcIcons.pin, "设为置顶", detail.pinned) { on -> mySettings(model, buildJsonObject { put("pinned", on) }, ::say) }
                        SwitchRow(KcIcons.bellOff, "消息免打扰", detail.doNotDisturb) { on ->
                            mySettings(model, buildJsonObject {
                                put("do_not_disturb", on)
                                put("message_setting", if (on) "silent" else "notify")
                            }, ::say)
                        }
                        GroupRow(
                            KcIcons.bell,
                            "消息设置",
                            when (detail.messageSetting) {
                                "silent" -> "接收不提醒"
                                "ignore" -> "屏蔽消息"
                                else -> "接收并提醒"
                            },
                        ) { sub = Sub.MessageSetting }
                    }
                }

                // 危险区
                Card {
                    Column {
                        GroupRow(KcIcons.trash, "清空聊天记录", null, danger = true) { sub = Sub.ConfirmClear }
                        GroupRow(KcIcons.flag, "举报群聊", null, danger = true) { sub = Sub.Report }
                        GroupRow(KcIcons.logout, if (detail.owner) "解散群聊" else "退出群聊", null, danger = true) { sub = Sub.ConfirmLeave }
                    }
                }
                Box(Modifier.height(24.dp))
            }
        }

        // 子页面 / 面板
        when (val current = sub) {
            null -> Unit
            Sub.Members -> MembersPage(
                graph = graph,
                conversationId = conversationId,
                manager = detail?.manager == true,
                myId = graph.session.session.value?.user?.id,
                onBack = { sub = null },
                onMemberClick = onMemberClick,
                onRemoved = { model.reload(quiet = true) },
            )
            Sub.JoinRequests -> JoinRequestsSheet(graph, conversationId, onDismiss = { sub = null }, onChanged = { model.reload(quiet = true) })
            Sub.EditName -> TextEditSheet("群名称", detail?.title.orEmpty(), "群名称", 30, singleLine = true, onDismiss = { sub = null }) { text ->
                model.patch("/profile", buildJsonObject { put("title", text) }, ::say)
            }
            Sub.EditDescription -> TextEditSheet("群简介", detail?.description.orEmpty(), "介绍一下这个群", 200, singleLine = false, onDismiss = { sub = null }) { text ->
                model.patch("/profile", buildJsonObject { put("description", text) }, ::say)
            }
            Sub.EditJoinQuestion -> TextEditSheet("加群问题", detail?.joinQuestion.orEmpty(), "例如：你的班级 / 暗号？", 60, singleLine = true, onDismiss = { sub = null }) { text ->
                model.patch("/settings", buildJsonObject {
                    put("join_question", text)
                    if (text.isNotBlank()) {
                        put("join_mode", "question")
                        put("auto_approve", false)
                    }
                }, ::say)
            }
            Sub.EditNickname -> TextEditSheet("我的本群昵称", detail?.myNickname.orEmpty(), "只在这个群里显示", 20, singleLine = true, onDismiss = { sub = null }) { text ->
                mySettings(model, buildJsonObject { put("nickname", text) }, ::say)
            }
            Sub.EditRemark -> TextEditSheet("群聊备注", detail?.myRemark.orEmpty(), "只有你自己看得到", 30, singleLine = true, onDismiss = { sub = null }) { text ->
                mySettings(model, buildJsonObject { put("remark", text) }, ::say)
            }
            Sub.MessageSetting -> KcSheet(onDismiss = { sub = null }, title = "消息设置") {
                listOf("notify" to "接收并提醒", "silent" to "接收不提醒", "ignore" to "屏蔽消息").forEach { (key, label) ->
                    ink.kuke.chat.feature.chat.SheetRow(label, {
                        sub = null
                        mySettings(model, buildJsonObject {
                            put("message_setting", key)
                            put("do_not_disturb", key != "notify")
                        }, ::say)
                    }, selected = detail?.messageSetting == key)
                }
                Box(Modifier.height(8.dp))
            }
            Sub.ConfirmClear -> KcConfirm("清空聊天记录", "只清空你这边的聊天记录，其他成员不受影响。", "清空", danger = true, onConfirm = {
                sub = null
                scope.launch {
                    runCatching { graph.api.post("/conversations/$conversationId/clear") }
                        .onSuccess {
                            graph.conversations.refreshSoon(0)
                            toast(context, "聊天记录已清空")
                        }
                        .onFailure { toast(context, it.userMessage()) }
                }
            }, onDismiss = { sub = null })
            Sub.ConfirmLeave -> {
                val owner = detail?.owner == true
                KcConfirm(
                    title = if (owner) "解散群聊" else "退出群聊",
                    text = if (owner) "解散后所有成员都会被移出，聊天记录无法恢复。" else "退出后不会再收到这个群的消息。",
                    confirmLabel = if (owner) "解散" else "退出",
                    danger = true,
                    onConfirm = {
                        sub = null
                        scope.launch {
                            runCatching {
                                if (owner) graph.api.delete("/conversations/$conversationId") else graph.api.delete("/conversations/$conversationId/members/me")
                            }.onSuccess {
                                graph.conversations.refresh()
                                onBack()
                            }.onFailure { toast(context, it.userMessage()) }
                        }
                    },
                    onDismiss = { sub = null },
                )
            }
            Sub.Report -> ReportSheet(
                report,
                ReportTarget("conversation", conversationId, "群聊：${detail?.title.orEmpty()}", conversationId),
                onDismiss = { sub = null },
            )
        }
        GroupExtrasHost(extras, model, onMemberAdded = { model.reload(quiet = true) })
    }
}

/** 我自己的会话设置（昵称、备注、置顶、免打扰、消息设置）：PATCH /conversations/{id}/members/me/settings */
private fun mySettings(model: GroupModel, body: JsonObject, done: (String?) -> Unit) = model.patch("/members/me/settings", body, done)

@Composable
private fun NotMemberView(graph: AppGraph, conversationId: Long, onJoined: () -> Unit) {
    val colors = KcTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("群聊 $conversationId", style = KcTheme.type.title, color = colors.text)
        Text("你还不是这个群的成员", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp), textAlign = TextAlign.Center)
        KcButton(if (busy) "处理中…" else "加入群聊", {
            busy = true
            scope.launch {
                runCatching { graph.api.post("/conversations/$conversationId/join") }
                    .onSuccess {
                        graph.conversations.refresh()
                        toast(context, "已加入")
                        onJoined()
                    }
                    .onFailure { toast(context, it.userMessage()) }
                busy = false
            }
        }, enabled = !busy, modifier = Modifier.width(160.dp))
    }
}

// ------------------------------------------------------------------ 小部件

@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(KcTheme.colors.panel)) { content() }
}

/** 每行前面的双色图标块（设计稿：扫读更快） */
@Composable
internal fun IconBlock(icon: ImageVector, tint: Color) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        KcIcon(icon, tint = tint, size = 18.dp)
    }
}

@Composable
internal fun GroupRow(icon: ImageVector, label: String, value: String?, danger: Boolean = false, highlight: Boolean = false, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val tint = if (danger) colors.danger else colors.accent
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBlock(icon, tint)
        Text(label, style = KcTheme.type.rowTitle, color = if (danger) colors.danger else colors.text, modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (value != null) Text(value, style = KcTheme.type.secondary, color = if (highlight) colors.unread else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp))
        if (!danger) KcIcon(KcIcons.chevron, tint = colors.subtle, size = 18.dp)
    }
}

@Composable
internal fun SwitchRow(icon: ImageVector, label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    val colors = KcTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBlock(icon, colors.accent)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = KcTheme.type.rowTitle, color = colors.text)
            if (hint != null) Text(hint, style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KcSwitch(checked, onChange)
    }
}

@Composable
private fun MemberTile(member: MemberLite, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.width(64.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(member.avatarUrl, member.name, tone = member.userId, size = 46.dp)
        Text(member.name, style = KcTheme.type.caption, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DashedTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Column(Modifier.width(64.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).border(1.5.dp, colors.borderStrong, RoundedCornerShape(23.dp)), contentAlignment = Alignment.Center) {
            KcIcon(icon, tint = colors.muted, size = 20.dp)
        }
        Text(label, style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 单行 / 多行文字编辑面板 */
@Composable
private fun TextEditSheet(title: String, initial: String, placeholder: String, maxLength: Int, singleLine: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    KcSheet(onDismiss = onDismiss, title = title) {
        SheetForm {
            KcTextField(text, { if (it.length <= maxLength) text = it }, placeholder, singleLine = singleLine, minHeight = if (singleLine) 44.dp else 100.dp)
            KcButton("保存", {
                onSave(text.trim())
                onDismiss()
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}
