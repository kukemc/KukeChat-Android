package ink.kuke.chat.feature.share

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.groupfiles.fileIcon
import ink.kuke.chat.feature.groupplus.PlusButton
import ink.kuke.chat.feature.groupplus.formatBytes
import ink.kuke.chat.feature.groupplus.plusInk
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 分享页：上面是要分享的内容（文字摘要 / 图片缩略图 / 文件名和大小），下面搜索 + 最近聊天 + 好友，可以多选（最多 9 个），
 * 底部一行「留言」和墨色胶囊「发送」。风格同群升级面板：黑白、细描边、大留白。
 */
@Composable
internal fun ShareScreen(model: ShareModel, onClose: () -> Unit, onSent: (ShareOutcome) -> Unit) {
    val k = plusInk()
    val type = KcTheme.type
    val context = LocalContext.current
    val conversations by model.graph.conversations.items.collectAsStateWithLifecycle()
    val loaded by model.graph.conversations.loaded.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { model.loadLists() }

    model.notice?.let { text ->
        LaunchedEffect(text) {
            toast(context, text)
            model.notice = null
        }
    }
    // 从直接分享快捷方式进来：会话在列表里了就选上
    val preselect = model.preselect
    if (preselect != null && model.payload != null) {
        LaunchedEffect(preselect, conversations) {
            conversations.firstOrNull { it.id == preselect }?.let { conversation ->
                model.consumePreselect()
                if (model.selected.none { it.conversationId == preselect }) model.toggle(conversation.shareTarget())
            }
        }
    }

    val payload = model.payload
    Column(Modifier.fillMaxSize().background(KcTheme.colors.panel)) {
        // 顶栏：× · 标题
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val press = rememberInteraction()
            Box(
                Modifier.size(44.dp).pressScale(press, 0.9f).clip(CircleShape).clickable(interactionSource = press, indication = null, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.close, tint = k.ink, size = 22.dp, contentDescription = "关闭") }
            Text("发送给", style = type.title, color = k.ink, modifier = Modifier.padding(start = 4.dp).weight(1f))
            if (model.selected.isNotEmpty()) {
                Text("已选 ${model.selected.size} / ${ShareModel.MAX_TARGETS}", style = type.caption.copy(fontFeatureSettings = "tnum"), color = k.faint, modifier = Modifier.padding(end = 14.dp))
            }
        }

        when {
            model.parsing -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在读取分享的内容…", style = type.secondary, color = k.sub)
            }
            payload == null || payload.isEmpty -> EmptyShare(onClose, Modifier.weight(1f))
            else -> {
                val query = model.query.trim()
                val directUsers = remember(conversations) { conversations.mapNotNull { c -> c.directUser?.id?.takeIf { !c.isGroup } }.toSet() }
                val recent = remember(conversations, query) {
                    conversations.sortedByDescending { it.updatedAt }
                        .filter { query.isEmpty() || it.name().contains(query, ignoreCase = true) }
                        .map { it.shareTarget() }
                }
                val friends = remember(model.friends, directUsers, query) {
                    model.friends.filter { it.id !in directUsers && !it.isBot }
                        .filter { query.isEmpty() || it.displayName.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true) }
                        .map { it.shareTarget() }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item(key = "preview") { PreviewCard(payload) }
                    item(key = "search") { SearchField(model.query, { model.query = it }) }
                    if (model.selected.isNotEmpty()) {
                        item(key = "chosen") { ChosenStrip(model) }
                    }
                    item(key = "h-recent") { SectionLabel("最近聊天") }
                    if (recent.isEmpty()) {
                        item(key = "e-recent") {
                            Text(
                                if (!loaded && conversations.isEmpty()) "正在加载…" else if (query.isNotEmpty()) "没有匹配的聊天" else "还没有聊天",
                                style = type.secondary, color = k.faint, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                            )
                        }
                    }
                    items(recent, key = { it.key }) { target -> TargetRow(model, target) }
                    if (friends.isNotEmpty()) {
                        item(key = "h-friends") { SectionLabel("好友") }
                        items(friends, key = { it.key }) { target -> TargetRow(model, target) }
                    }
                    item(key = "tail") { Spacer(Modifier.height(16.dp)) }
                }
                SendBar(model, onSent)
            }
        }
    }
}

@Composable
private fun EmptyShare(onClose: () -> Unit, modifier: Modifier) {
    val k = plusInk()
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("没有可以分享的内容", style = KcTheme.type.title, color = k.ink)
        Text("这次分享里没有文字、图片或文件，或者读取失败了。回到原应用重新分享试试。", style = KcTheme.type.secondary, color = k.sub, modifier = Modifier.padding(top = 8.dp))
        PlusButton("关闭", onClick = onClose, primary = false, modifier = Modifier.padding(top = 24.dp).width(160.dp))
    }
}

/** 要分享的内容：一张细描边的大圆角卡片 */
@Composable
private fun PreviewCard(payload: SharePayload) {
    val k = plusInk()
    val type = KcTheme.type
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(k.wash).border(1.dp, k.line, shape).padding(16.dp)) {
            Text(payload.summary(), style = type.caption, color = k.faint, fontWeight = FontWeight.W500, letterSpacing = 0.02.em)
            if (payload.hasText) {
                Text(
                    payload.text, style = type.body, color = k.ink, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                payload.firstLink()?.let { link ->
                    val host = runCatching { android.net.Uri.parse(link).host }.getOrNull()?.removePrefix("www.")
                    if (!host.isNullOrBlank()) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            KcIcon(KcIcons.link, tint = k.sub, size = 13.dp)
                            Text(host, style = type.caption, color = k.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            val images = payload.images
            if (images.isNotEmpty()) {
                val shown = images.take(5)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEachIndexed { index, item ->
                        val tile = RoundedCornerShape(10.dp)
                        Box(Modifier.size(58.dp).clip(tile).background(k.wash2).border(0.5.dp, k.line, tile)) {
                            AsyncImage(model = item.uri, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            val more = images.size - shown.size
                            if (index == shown.lastIndex && more > 0) {
                                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                    Text("+$more", color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600)
                                }
                            }
                        }
                    }
                }
            }
            val files = payload.files
            if (files.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    files.take(3).forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(k.wash2), contentAlignment = Alignment.Center) {
                                KcIcon(fileIcon(item.name, item.mime), tint = k.ink, size = 18.dp)
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(item.name, style = type.secondary, color = k.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (item.size >= 0) formatBytes(item.size) else "未知大小", style = type.caption.copy(fontFeatureSettings = "tnum"), color = k.faint)
                            }
                        }
                    }
                    if (files.size > 3) Text("还有 ${files.size - 3} 个", style = type.caption, color = k.faint)
                }
            }
        }
        // 说明：同一时间最多两行，灰色小字
        val hints = buildList {
            if (payload.files.isNotEmpty()) add("视频和文件会发到群文件；私聊暂时只能发送文字和图片")
            if (payload.textTruncated) add("文字超过 ${SharePayload.MAX_TEXT} 字，只发送前 ${SharePayload.MAX_TEXT} 字")
            if (payload.skipped.isNotEmpty()) add("有 ${payload.skipped.size} 项读不到或不支持，已跳过")
        }
        hints.forEach { Text(it, style = type.caption, color = k.sub, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val k = plusInk()
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(50)).background(k.wash2).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcIcon(KcIcons.search, tint = k.faint, size = 16.dp)
        Box(Modifier.padding(start = 8.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("搜索聊天、好友", style = KcTheme.type.secondary, color = k.faint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = KcTheme.type.secondary.copy(color = k.ink),
                cursorBrush = SolidColor(k.ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                KcIcon(KcIcons.close, tint = k.faint, size = 14.dp, contentDescription = "清空")
            }
        }
    }
}

/** 已选的：一排小头像，点一下去掉 */
@Composable
private fun ChosenStrip(model: ShareModel) {
    val k = plusInk()
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(model.selected.toList(), key = { it.key }) { target ->
            Column(Modifier.width(52.dp).clickable { model.toggle(target) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    if (target.self) ink.kuke.chat.feature.selfchat.DeviceAvatar(44.dp) else Avatar(target.avatar, target.name, tone = target.tone, size = 44.dp)
                    Box(
                        Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(k.ink).border(1.5.dp, KcTheme.colors.panel, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { KcIcon(KcIcons.close, tint = k.onInk, size = 9.dp, contentDescription = "去掉") }
                }
                Text(target.name, style = KcTheme.type.caption, color = k.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val k = plusInk()
    Text(text, style = KcTheme.type.caption, color = k.faint, fontWeight = FontWeight.W500, letterSpacing = 0.04.em, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
private fun TargetRow(model: ShareModel, target: ShareTarget) {
    val k = plusInk()
    val type = KcTheme.type
    val chosen = model.selected.any { it.key == target.key }
    val eligibility = model.eligibility(target)
    val blocked = eligibility is Eligibility.Blocked
    val note = when (eligibility) {
        is Eligibility.Blocked -> eligibility.reason
        // 部分能发的说明只在选中后显示，免得满屏都是提示
        is Eligibility.Partial -> eligibility.reason.takeIf { chosen }
        Eligibility.Checking -> "正在查看群文件状态…"
        Eligibility.Ok -> null
    }
    val press = rememberInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(interactionSource = press, indication = null) { model.toggle(target) }
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .alpha(if (blocked) 0.42f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.pressScale(press, 0.97f)) {
            if (target.self) ink.kuke.chat.feature.selfchat.DeviceAvatar(44.dp) else Avatar(target.avatar, target.name, tone = target.tone, size = 44.dp, plusTier = target.plusTier)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(target.name, style = type.rowTitle, color = k.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = note ?: target.detail
            if (line != null) {
                Text(
                    line, style = type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (note != null && eligibility !is Eligibility.Checking) k.warn.copy(alpha = 0.9f) else k.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        SelectMark(chosen, Modifier.padding(start = 12.dp))
    }
}

/** 选中标记：未选是 1.5dp 灰圈；选中是墨色实心圆 + 勾（弹一下） */
@Composable
private fun SelectMark(chosen: Boolean, modifier: Modifier = Modifier) {
    val k = plusInk()
    val scale by animateFloatAsState(if (chosen) 1f else 0.6f, spring(dampingRatio = 0.5f, stiffness = 800f), label = "mark")
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).border(1.5.dp, k.lineStrong, CircleShape))
        if (chosen) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(k.ink),
                contentAlignment = Alignment.Center,
            ) { KcIcon(KcIcons.check, tint = k.onInk, size = 13.dp) }
        }
    }
}

/** 底部：留言 + 发送。选了目标才出现留言框 */
@Composable
private fun SendBar(model: ShareModel, onSent: (ShareOutcome) -> Unit) {
    val k = plusInk()
    val type = KcTheme.type
    Column(Modifier.fillMaxWidth().background(KcTheme.colors.panel).imePadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(k.wash2).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (model.note.isEmpty()) Text("留言（可选）", style = type.secondary, color = k.faint)
                BasicTextField(
                    value = model.note,
                    onValueChange = { if (it.length <= 500) model.note = it },
                    maxLines = 4,
                    textStyle = type.secondary.copy(color = k.ink),
                    cursorBrush = SolidColor(k.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val count = model.selected.size
            PlusButton(
                text = when {
                    model.sending -> "准备中…"
                    count > 1 -> "发送（$count）"
                    else -> "发送"
                },
                onClick = { model.send(onSent) },
                enabled = model.canSend,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        AnimatedVisibility(visible = model.selected.isEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text("选择要发送的聊天，可以多选", style = type.caption, color = k.faint, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
        }
    }
}
