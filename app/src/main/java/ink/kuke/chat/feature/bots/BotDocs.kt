package ink.kuke.chat.feature.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 接入文档：把电脑版 docs/bot-api.md 里开发者最先要用的几块搬过来（地址、鉴权、接口表、消息元素）。
 * 地址取自构建配置，不写死服务器。完整文档在项目仓库的 docs/bot-api.md。
 */
@Composable
internal fun BotDocsPage(onBack: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    KcPage(title = "接入文档", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("机器人通过 REST 接口发消息，通过 WebSocket 收事件。机器人 Key 只在创建或重置时显示一次，不要放进公开仓库、网页源码或客户端包。", style = type.secondary, color = colors.muted)

            DocSection("基础信息") {
                Code("API Base URL\n${Endpoints.api}\n\nWebSocket\n${Endpoints.ws.trimEnd('/')}/bot/ws?key=<机器人Key>")
                Code("Authorization: Bot <机器人Key>\nX-Kuke-Bot-Key: <机器人Key>   （备用）")
            }
            DocSection("机器人运行 API（Bot Key 鉴权）") {
                Table(
                    listOf(
                        "GET" to "/bot-api/me  机器人自身信息",
                        "GET" to "/bot-api/conversations  所在的群聊和私聊",
                        "GET" to "/bot-api/conversations/{id}/members  群成员公开信息",
                        "GET" to "/bot-api/conversations/{id}/messages?limit=50  最近消息",
                        "POST" to "/bot-api/conversations/{id}/messages  发送消息",
                        "POST" to "/bot-api/users/{user_id}/messages  临时私聊发私信",
                        "POST" to "/bot-api/conversations/{id}/messages/{mid}/recall  撤回消息",
                        "POST" to "/bot-api/uploads/image  上传图片",
                        "POST" to "/bot-api/uploads/voice  上传语音",
                    ),
                )
            }
            DocSection("机器人管理 API（用户 Bearer Token）") {
                Table(
                    listOf(
                        "POST" to "/bots  创建机器人，返回一次性完整 Key",
                        "GET" to "/bots/mine  我创建的机器人",
                        "GET" to "/bots/square?q=&limit=30&offset=0  公开广场",
                        "PATCH" to "/bots/{id}  修改资料、公开状态或停用",
                        "POST" to "/bots/{id}/rotate-key  重置 Key",
                        "POST" to "/bots/{id}/install  添加到群聊",
                        "DELETE" to "/bots/{id}/installations/{conversation_id}  从群移出",
                    ),
                )
            }
            DocSection("消息元素") {
                Table(
                    listOf(
                        "at" to "@指定用户  <at id=\"12\"/>",
                        "at_all" to "@全体  <at_all/>",
                        "quote" to "引用消息  <quote id=\"1001\" preview=\"原消息\"/>",
                        "img" to "图片  <img src=\"上传接口返回的地址\"/>",
                        "markdown" to "Markdown 内容  <markdown># 标题</markdown>",
                        "button" to "按钮  action=\"input\" 填入输入框，action=\"callback\" 回调机器人",
                        "link" to "链接  <link href=\"https://…\">文字</link>",
                    ),
                )
            }
            DocSection("WebSocket 事件") {
                Text("连上 /bot/ws 之后，机器人会收到新消息、按钮回调（message.interaction）、成员变动等事件。事件字段和更多示例见项目仓库 docs/bot-api.md。", style = type.secondary, color = colors.muted)
            }
        }
    }
}

@Composable
private fun DocSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = KcTheme.type.title, color = KcTheme.colors.text)
        content()
    }
}

@Composable
private fun Code(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KcTheme.colors.panelMuted).padding(12.dp)) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = KcTheme.colors.text)
    }
}

@Composable
private fun Table(rows: List<Pair<String, String>>) {
    val colors = KcTheme.colors
    Card {
        rows.forEachIndexed { index, (method, text) ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                Text(method, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.accent, modifier = Modifier.padding(end = 10.dp).width(64.dp))
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.text, modifier = Modifier.weight(1f))
            }
            if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 12.dp).height(1.dp).background(colors.border))
        }
    }
}
