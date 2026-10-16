package ink.kuke.chat.feature.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 供「动态 / 主页 / 组队」三个目录共用的小部件和辅助函数 */

/** 页面状态放 ViewModel：切底栏 tab、进详情再返回都不丢列表和滚动位置对应的数据 */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/** 可点元素：按下 0.96 倍，无涟漪（设计稿统一的按压反馈） */
@Composable
fun Modifier.tap(enabled: Boolean = true, scale: Float = 0.96f, onClick: () -> Unit): Modifier {
    val interaction = rememberInteraction()
    return this.pressScale(interaction, scale).clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = KcTheme.colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

/** 失败状态：一句话 + 重试 */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val colors = KcTheme.colors
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        KcIcon(KcIcons.signal, tint = colors.subtle, size = 32.dp)
        Text(message, style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
        Text(
            "重试",
            style = KcTheme.type.rowTitle,
            color = colors.onFill,
            modifier = Modifier.padding(top = 14.dp).tap(onClick = onRetry).clip(RoundedCornerShape(10.dp)).background(colors.fill).padding(horizontal = 22.dp, vertical = 9.dp),
        )
    }
}

@Composable
fun SmallTag(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    // 固定 16dp 高、去掉字体上下留白：之前默认行高把标签撑得很高
    Box(modifier.height(16.dp).clip(RoundedCornerShape(4.dp)).background(background).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = color,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.W600,
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
            maxLines = 1,
        )
    }
}

/** 账号等级胶囊（Lv.16）；0 级不显示 */
@Composable
fun LevelTag(level: Int) {
    if (level <= 0) return
    // 和聊天页、空间页一样用等级图标（月亮 / 星星…加数字），不再是「Lv.5」文字胶囊
    ink.kuke.chat.feature.level.LevelIcons(level, size = 13.dp, compact = true)
}

// ------------------------------------------------------------------ 图片查看器

/** 全屏看图：左右翻页，点一下关闭 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewer(urls: List<String>, start: Int, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (urls.size - 1).coerceAtLeast(0))) { urls.size }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = rememberInteraction(), onClick = onDismiss)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = Endpoints.absolute(urls[page]),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (urls.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${urls.size}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 分享到聊天

/**
 * 分享面板：复制文字，或者转发到一个会话（消息带 share_card，和电脑版 sharePostToConversation 一样是一条 text 消息）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(graph: AppGraph, title: String, text: String, shareCard: JsonObject, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val conversations by graph.conversations.items.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var sendingTo by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, style = KcTheme.type.title, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            status?.let { Text(it, style = KcTheme.type.caption, color = colors.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            Row(
                Modifier.fillMaxWidth().tap(scale = 0.98f) {
                    clipboard.setText(AnnotatedString(text))
                    status = "已复制"
                    scope.launch { delay(700); onDismiss() }
                }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KcIcon(KcIcons.copy, tint = colors.muted, size = 22.dp)
                Text("复制内容", style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(start = 14.dp))
            }
            Text("转发到聊天", style = KcTheme.type.group, color = colors.subtle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (conversations.isEmpty()) {
                Text("还没有会话可以转发", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(conversations, key = { it.id }) { conversation ->
                        Row(
                            Modifier.fillMaxWidth().tap(enabled = sendingTo == null, scale = 0.98f) {
                                sendingTo = conversation.id
                                scope.launch {
                                    runCatching {
                                        val body = buildJsonObject {
                                            put("content", text.take(500))
                                            put("type", "text")
                                            put("metadata", buildJsonObject { put("share_card", shareCard) })
                                        }
                                        graph.api.post("/conversations/${conversation.id}/messages", body)
                                    }.onSuccess {
                                        status = "已发送给 ${conversation.name()}"
                                        delay(800)
                                        onDismiss()
                                    }.onFailure {
                                        status = it.userMessage()
                                        sendingTo = null
                                    }
                                }
                            }.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(conversation.avatarUrl ?: conversation.directUser?.avatarUrl, conversation.name(), conversation.id, 40.dp)
                            Text(conversation.name(), style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
                            if (sendingTo == conversation.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ @ / # 联想

/** 输入框正在输入 # 时，取话题联想：空关键字给热门，否则按关键字搜（防抖 200ms） */
@Composable
fun rememberTopicSuggestions(api: PostsApi, trigger: InputTrigger?): List<PostTopic> {
    var topics by remember { mutableStateOf<List<PostTopic>>(emptyList()) }
    val query = if (trigger != null && !trigger.mention) trigger.query else null
    LaunchedEffect(query) {
        if (query == null) {
            topics = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        topics = runCatching { if (query.isEmpty()) api.trending(6) else api.searchTopics(query, 6) }.getOrDefault(emptyList())
    }
    return topics
}

/**
 * 联想弹层（设计稿 P13：# / @ 就地弹出，末尾始终有「创建新话题」）。
 * @ 列好友；# 列话题，输入了关键字时末尾给「创建新话题 #xxx」。
 */
@Composable
fun SuggestionPopup(
    trigger: InputTrigger,
    friends: List<User>,
    topics: List<PostTopic>,
    onPickUser: (User) -> Unit,
    onPickTopic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(colors.panel).border(1.dp, colors.borderStrong, shape)
            .heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
    ) {
        if (trigger.mention) {
            val matched = friends.filter { it.displayName.contains(trigger.query, true) || it.username.contains(trigger.query, true) }.take(8)
            if (matched.isEmpty()) {
                Text(if (friends.isEmpty()) "还没有可以 @ 的好友" else "没有匹配的好友", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(14.dp))
            }
            matched.forEach { user ->
                Row(Modifier.fillMaxWidth().tap(scale = 0.98f) { onPickUser(user) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.avatarUrl, user.displayName, user.id, 30.dp)
                    Text(user.displayName, style = KcTheme.type.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                }
            }
        } else {
            topics.forEach { topic ->
                Row(Modifier.fillMaxWidth().tap(scale = 0.98f) { onPickTopic(topic.name) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    KcIcon(KcIcons.hash, tint = colors.accent, size = 18.dp)
                    Text(topic.name, style = KcTheme.type.rowTitle, color = colors.text, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(compactCount(topic.postCount), style = KcTheme.type.caption, color = colors.subtle)
                }
            }
            val query = trigger.query
            if (query.isNotEmpty() && topics.none { it.name.equals(query, true) }) {
                Row(Modifier.fillMaxWidth().tap(scale = 0.98f) { onPickTopic(query) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    KcIcon(KcIcons.plus, tint = colors.accent, size = 18.dp)
                    Text("创建新话题 #$query", style = KcTheme.type.rowTitle, color = colors.accent, modifier = Modifier.padding(start = 8.dp))
                }
            } else if (query.isEmpty() && topics.isEmpty()) {
                Text("输入话题名，没有就创建新话题", style = KcTheme.type.secondary, color = colors.muted, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
internal fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit, size: Int = 40) {
    Box(Modifier.size(size.dp).tap(scale = 0.9f, onClick = onClick).clip(CircleShape), contentAlignment = Alignment.Center) {
        KcIcon(icon, tint = tint, size = 22.dp)
    }
}

/** 「JsonObject 里放一个字符串」的小糖，拼 share_card 用 */
internal fun shareCard(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            null -> {}
            is String -> put(key, value)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            is kotlinx.serialization.json.JsonElement -> put(key, value)
            else -> put(key, JsonPrimitive(value.toString()))
        }
    }
}
