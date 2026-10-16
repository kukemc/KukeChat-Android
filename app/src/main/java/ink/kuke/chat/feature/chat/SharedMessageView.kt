package ink.kuke.chat.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.feature.groupfiles.FileMessageSheet
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme

/*
 * 「只读消息渲染」：精华 / 收藏 / 合并转发详情 / 群待办全文 共用，和聊天页用的是同一个 MessageBody，
 * 所以图片、表情、语音、投票、红包、礼物、名片、机器人 Markdown、引用、撤回、文件、嵌套合并转发都一样画。
 * 只是不接长按 / 右滑 / 回应这些写操作。
 */

/** 只读视图里的外部动作（看图、进嵌套合并转发、文件、名片） */
class ReadOnlyActions(
    val openImage: (String) -> Unit,
    val openBundle: (Message) -> Unit,
    val openFile: (Message) -> Unit,
    val openShare: (Message) -> Unit,
) {
    fun toCallbacks() = MessageCallbacks(
        onLongPress = {},
        onQuote = {},
        onOpenImage = openImage,
        onReaction = { _, _ -> },
        onAvatar = {},
        onJumpToQuote = {},
        onOpenBundle = openBundle,
        onOpenShare = openShare,
        onOpenFile = openFile,
    )
}

/** 嵌套合并转发最多往里点这么多层（再往里只提示）：防止恶意构造的无限嵌套 */
private const val MAX_BUNDLE_DEPTH = 6

/**
 * 只读视图的宿主：持有「看大图 / 嵌套合并转发详情栈 / 文件下载面板」的状态，content 里拿到 actions 用。
 * graph 为 null 时（合并转发里本来就不会有文件消息）不提供文件下载。
 */
@Composable
fun ReadOnlyMessageHost(graph: AppGraph?, content: @Composable (ReadOnlyActions) -> Unit) {
    val nav = LocalAppNav.current
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<String?>(null) }
    val stack = remember { mutableStateListOf<Message>() }
    var file by remember { mutableStateOf<Message?>(null) }
    val actions = remember(graph) {
        ReadOnlyActions(
            openImage = { viewing = it },
            openBundle = { message ->
                if (stack.size >= MAX_BUNDLE_DEPTH) toast(context, "聊天记录嵌套太深，不能继续展开") else stack.add(message)
            },
            openFile = { message -> file = message },
            openShare = { message -> shareRouteOf(message)?.let(nav::go) },
        )
    }
    Box(Modifier.fillMaxSize()) {
        content(actions)
        stack.lastOrNull()?.let { top ->
            // 每一层是自己的全屏页，返回弹一层
            BundleDetailPage(top, onBack = { stack.removeAt(stack.lastIndex) }, onOpenImage = { viewing = it }, onOpenBundle = actions.openBundle)
        }
        file?.let { message ->
            val meta = fileMetaOf(message)
            if (graph != null && meta != null) {
                FileMessageSheet(graph, message.conversationId, meta.id, meta.name, meta.size, meta.isExpired(), onDismiss = { file = null })
            } else {
                file = null
            }
        }
    }
}

/** 一条消息的只读正文：撤回 / 系统消息画成灰色小字，其余走聊天页的 MessageBody */
@Composable
fun ReadOnlyMessageBody(message: Message, actions: ReadOnlyActions, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Box(modifier) {
        when {
            message.isRecalled -> Text("这条消息已被撤回", style = type.secondary, color = colors.subtle)
            message.type == "system" -> Text(message.content, style = type.caption, color = colors.muted)
            else -> MessageBody(message, mine = false, callbacks = actions.toCallbacks())
        }
    }
}

/**
 * 列表里的一条只读消息：头像 · 发送人 · 时间 + 正文。精华、收藏、合并转发详情用同一个行。
 * footer 是行底部的一行小字（比如「来自 某某群」「已过期」）。
 */
@Composable
fun SnapshotMessageRow(
    message: Message,
    actions: ReadOnlyActions,
    modifier: Modifier = Modifier,
    footer: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(message.sender?.avatarUrl, message.senderName, tone = message.senderId ?: 0, size = 38.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.senderName, style = type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (message.createdAt.isNotBlank()) Text(fullTime(message.createdAt), style = type.caption, color = colors.subtle)
            }
            ReadOnlyMessageBody(message, actions)
            if (!footer.isNullOrBlank()) Text(footer, style = type.caption, color = colors.subtle)
        }
        trailing?.invoke()
    }
}

// ------------------------------------------------------------------ 合并转发

/** 合并转发里的每条快照还原成 Message（保留 type / metadata，这样能用同一个渲染器画） */
fun forwardMessagesOf(bundle: Message): List<Message> {
    val meta = bundle.metadata ?: return emptyList()
    val source = listOf("items", "snapshots", "messages", "forwarded_messages").firstNotNullOfOrNull { meta.a(it) }.orEmpty()
    return source.mapIndexedNotNull { index, element ->
        val snap = element.asObj() ?: return@mapIndexedNotNull null
        val sender = snap.o("sender")
        val senderId = snap.l("sender_id") ?: snap.l("user_id") ?: sender?.l("id")
        val name = snap.s("sender_display_name") ?: snap.s("sender_name") ?: sender?.s("nickname") ?: sender?.s("username") ?: "未知用户"
        val avatar = snap.s("sender_avatar_url") ?: sender?.s("avatar_url")
        Message(
            // 快照里的 message_id 可能缺：用负的序号保证 key 唯一
            id = snap.l("message_id") ?: snap.l("id") ?: -(index + 1L),
            conversationId = snap.l("conversation_id") ?: bundle.conversationId,
            senderId = senderId,
            sender = User(id = senderId ?: 0, nickname = name, avatarUrl = avatar),
            senderDisplayName = name,
            type = snap.s("type") ?: "text",
            content = snap.s("content") ?: snap.s("text") ?: "",
            metadata = snap.o("metadata"),
            createdAt = snap.s("created_at") ?: snap.s("time") ?: "",
        )
    }
}

/**
 * 合并转发详情：逐条列出快照，每条都用和聊天页一样的渲染器；里面的合并转发是一张卡片，点进去再开一层。
 * 嵌套深度由 ReadOnlyMessageHost 的栈限制。
 */
@Composable
internal fun BundleDetailPage(message: Message, onBack: () -> Unit, onOpenImage: (String) -> Unit, onOpenBundle: ((Message) -> Unit)? = null) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val bundle = remember(message.id, message.metadata) { forwardOf(message) }
    val items = remember(message.id, message.metadata) { forwardMessagesOf(message) }
    BackHandler(onBack = onBack)
    // 嵌套的合并转发在本页内继续往里开：用本页自己的小栈，不依赖外层
    val nested = remember(message.id) { mutableStateListOf<Message>() }
    val nav = LocalAppNav.current
    val actions = remember(message.id) {
        ReadOnlyActions(
            openImage = { url -> onOpenImage(url) },
            openBundle = { inner -> if (onOpenBundle != null) onOpenBundle(inner) else nested.add(inner) },
            openFile = {},
            openShare = { shareRouteOf(it)?.let(nav::go) },
        )
    }
    Box(Modifier.fillMaxSize()) {
    KcPage(title = bundle?.title ?: "聊天记录", onBack = onBack, subtitle = bundle?.count?.takeIf { it > 0 }?.let { "共 $it 条" }) {
        if (bundle == null || items.isEmpty()) {
            StateText("这份聊天记录读不出来")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                bundle.note?.takeIf { it.isNotBlank() }?.let { note ->
                    item(key = "note") { Text(note, style = type.secondary, color = colors.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }
                itemsIndexed(items, key = { index, item -> "${item.id}-$index" }) { _, item ->
                    SnapshotMessageRow(item, actions)
                }
            }
        }
    }
    // 嵌套的合并转发整页盖在上面（不放进 KcPage 里，免得出现两层顶栏）
    nested.lastOrNull()?.let { inner ->
        BundleDetailPage(inner, onBack = { nested.removeAt(nested.lastIndex) }, onOpenImage = onOpenImage, onOpenBundle = null)
    }
    }
}
