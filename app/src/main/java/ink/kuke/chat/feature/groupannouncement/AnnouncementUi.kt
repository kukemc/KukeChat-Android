package ink.kuke.chat.feature.groupannouncement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.KcSheet
import ink.kuke.chat.feature.chat.KcTextField
import ink.kuke.chat.feature.chat.SheetForm
import ink.kuke.chat.feature.chat.asArr
import ink.kuke.chat.feature.chat.asObj
import ink.kuke.chat.feature.chat.l
import ink.kuke.chat.feature.chat.s
import ink.kuke.chat.feature.chat.toast
import ink.kuke.chat.feature.groupplus.PlusButton
import ink.kuke.chat.feature.groupplus.plusInk
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 小标签：strong = 墨色实底（置顶），否则描边（需确认 / 已编辑） */
@Composable
internal fun AnnTag(text: String, modifier: Modifier = Modifier, strong: Boolean = false, faint: Boolean = false) {
    val k = plusInk()
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = when {
            strong -> k.onInk
            faint -> k.sub
            else -> k.ink
        },
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.W600,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .then(
                when {
                    strong -> Modifier.background(k.ink)
                    faint -> Modifier.background(k.wash2)
                    else -> Modifier.border(1.dp, k.lineStrong, shape)
                },
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * 公告里的图：一张时按宽铺开（最高 240dp），多张三列方格。点开大图走动态那边的多图查看器（左右滑）。
 */
@Composable
internal fun AnnouncementImageGrid(images: List<AnnouncementImage>, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    if (images.isEmpty()) return
    val k = plusInk()
    if (images.size == 1) {
        AsyncImage(
            model = Endpoints.absolute(images[0].url),
            contentDescription = "公告图片",
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(k.wash).clickable { onOpen(0) },
        )
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 6.dp
        val cell: Dp = (maxWidth - gap * 2) / 3
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            images.chunked(3).forEachIndexed { row, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    chunk.forEachIndexed { col, image ->
                        val index = row * 3 + col
                        AsyncImage(
                            model = Endpoints.absolute(image.preview),
                            contentDescription = "公告图片 ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(cell).clip(RoundedCornerShape(10.dp)).background(k.wash).clickable { onOpen(index) },
                        )
                    }
                }
            }
        }
    }
}

/** 首图缩略图（列表、卡片）：右上角带张数 */
@Composable
internal fun AnnouncementThumb(url: String?, count: Int, size: Dp, modifier: Modifier = Modifier) {
    val k = plusInk()
    val absolute = Endpoints.absolute(url) ?: return
    Box(modifier.size(size).clip(RoundedCornerShape(10.dp)).background(k.wash)) {
        AsyncImage(model = absolute, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        if (count > 1) {
            Text(
                "$count 张",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.W600,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 修改自己的群昵称（公告开了「引导群成员修改群昵称」时的底部按钮用）：
 * PATCH /conversations/{id}/members/me/settings { nickname }，和群资料页「我的本群昵称」同一个接口、同样最多 20 字。
 * 当前昵称从会话列表里这一项的 my_nickname 取。
 */
@Composable
internal fun NicknameSheet(graph: AppGraph, conversationId: Long, onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val k = plusInk()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(conversationId) {
        runCatching {
            graph.api.get("/conversations").asArr().orEmpty().firstNotNullOfOrNull { it.asObj()?.takeIf { obj -> obj.l("id") == conversationId } }?.s("my_nickname")
        }.getOrNull()?.let { if (!touched) text = it }
    }
    KcSheet(onDismiss = onDismiss, title = "修改群昵称") {
        SheetForm {
            Text("群昵称只在这个群里显示", color = k.sub, fontSize = 13.sp)
            KcTextField(text, { if (it.length <= 20) { text = it; touched = true } }, "输入你在本群的昵称")
            PlusButton(if (busy) "保存中…" else "保存", {
                busy = true
                scope.launch {
                    runCatching { graph.api.patch("/conversations/$conversationId/members/me/settings", buildJsonObject { put("nickname", text.trim()) }) }
                        .onSuccess {
                            graph.conversations.refreshSoon(0)
                            toast(context, "群昵称已修改")
                            onSaved()
                            onDismiss()
                        }
                        .onFailure { toast(context, it.userMessage()) }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

/** 一行开关（编辑页）：标题 + 一句说明 + 开关 */
@Composable
internal fun AnnSwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val k = plusInk()
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = k.ink, fontSize = 15.sp, fontWeight = FontWeight.W500)
            if (hint != null) Text(hint, color = k.sub, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
        ink.kuke.chat.feature.chat.KcSwitch(checked, onChange)
    }
}

@Composable
internal fun AnnDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(plusInk().line))
}

@Composable
internal fun AnnSpacer(width: Dp) = Box(Modifier.width(width))
