package ink.kuke.chat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.ui.theme.KcTheme

/** 是不是要走机器人结构化渲染：机器人发的文字消息，带 markdown 标记或 elements */
fun isBotRichMessage(message: Message): Boolean {
    if (message.type != "text" || message.sender?.isBot != true) return false
    val meta = message.metadata ?: return false
    return meta.b("markdown") == true || (meta.a("elements")?.isNotEmpty() == true)
}

/**
 * 机器人的 Markdown 消息：不套气泡，白面卡片。
 * 支持标题、列表、引用、代码块、加粗、行内代码、链接，表格降级成一行行文本；
 * <button>/<link> 标签和 metadata.elements 里的按钮渲染成按钮组，点击走 onButton（填入输入框 / 回调 / 打开链接）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BotContent(message: Message, onButton: (Message, BotButton) -> Unit) {
    val colors = KcTheme.colors
    val blocks = remember(message.id, message.content, message.metadata) { botBlocks(message) }
    Column(
        Modifier
            .widthIn(min = 120.dp, max = 300.dp)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is BotBlock.Text -> Text(inlineMarkdown(block.text, colors.accent), style = block.style(), color = colors.text)
                is BotBlock.Code -> Text(
                    block.code,
                    style = KcTheme.type.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = colors.text,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.panelMuted).padding(horizontal = 10.dp, vertical = 8.dp),
                )
                is BotBlock.Buttons -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    block.buttons.forEach { raw ->
                        val button = withComponentState(message, raw)
                        val link = button.action == "open"
                        val tint = variantColor(button.variant) ?: if (link) colors.accent else colors.text
                        Text(
                            button.label,
                            style = KcTheme.type.secondary,
                            color = if (button.disabled) colors.subtle else tint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.panelMuted)
                                .border(1.dp, if (link) colors.accent.copy(alpha = 0.5f) else colors.borderStrong, RoundedCornerShape(10.dp))
                                .clickable(enabled = !button.disabled) { onButton(message, button) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun variantColor(variant: String?): Color? = when (variant) {
    "success" -> Color(0xFF16A34A)
    "danger" -> Color(0xFFDC2626)
    "warning" -> Color(0xFFD97706)
    "primary" -> Color(0xFF0A84FF)
    else -> null
}

internal sealed interface BotBlock {
    data class Text(val text: String, val level: Int = 0, val quote: Boolean = false) : BotBlock {
        @Composable
        fun style() = when {
            level in 1..2 -> KcTheme.type.title
            level >= 3 -> KcTheme.type.rowTitle
            else -> KcTheme.type.body
        }
    }

    data class Code(val code: String) : BotBlock
    data class Buttons(val buttons: List<BotButton>) : BotBlock
}

private val buttonTag = Regex("""<\s*(button|btn|link)\b([^<>]*?)(?:/\s*>|>(.*?)<\s*/\s*\1\s*>)""", RegexOption.IGNORE_CASE)
private val attrPattern = Regex("""([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")

/** message → 渲染块。先看 elements，没有就把 content 当 Markdown 解析 */
internal fun botBlocks(message: Message): List<BotBlock> {
    val elements = message.metadata?.a("elements")?.mapNotNull { it.asObj() }.orEmpty()
    if (elements.isEmpty()) return markdownBlocks(message.content)
    val out = mutableListOf<BotBlock>()
    val pending = mutableListOf<BotButton>()
    fun flush() {
        if (pending.isNotEmpty()) out += BotBlock.Buttons(pending.toList())
        pending.clear()
    }
    elements.forEachIndexed { index, element ->
        val type = (element.s("type") ?: element.s("name") ?: return@forEachIndexed).lowercase().replace('-', '_')
        val content = element.s("content").orEmpty()
        when (type) {
            "button", "btn", "link" -> botButtonFromAttrs(if (type == "link") "link" else "button", element.o("attrs"), content, index)?.let { pending += it }
            "markdown", "md" -> {
                flush()
                out += markdownBlocks(content)
            }
            "br" -> flush()
            "at_all", "mention_all" -> {
                flush()
                out += BotBlock.Text("@全体成员")
            }
            "at", "mention" -> {
                flush()
                val attrs = element.o("attrs")
                out += BotBlock.Text("@" + (attrs?.s("name") ?: attrs?.s("nickname") ?: attrs?.s("username") ?: "用户"))
            }
            else -> {
                flush()
                if (content.isNotBlank()) out += BotBlock.Text(content)
            }
        }
    }
    flush()
    return out.ifEmpty { markdownBlocks(message.content) }
}

internal fun markdownBlocks(raw: String): List<BotBlock> {
    val out = mutableListOf<BotBlock>()
    val code = mutableListOf<String>()
    var inCode = false
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) out += BotBlock.Text(paragraph.joinToString("\n"))
        paragraph.clear()
    }
    for (line in raw.take(8000).lines().take(120)) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out += BotBlock.Code(code.joinToString("\n"))
                code.clear()
            } else {
                flushParagraph()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            code += line
            continue
        }
        // 只含按钮标签的一行 → 按钮组
        val tags = buttonTag.findAll(line).toList()
        if (tags.isNotEmpty() && buttonTag.replace(line, "").trim().trim('|', '｜', '、', '，', ',', ';', '；', '/', ' ').isEmpty()) {
            flushParagraph()
            val buttons = tags.mapNotNull { match ->
                val attrs = attrPattern.findAll(match.groupValues[2]).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } }) }
                val obj = kotlinx.serialization.json.JsonObject(attrs.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
                val kind = if (match.groupValues[1].equals("link", true)) "link" else "button"
                val content = match.groupValues[3]
                // 标签式按钮没写 action_id 时用文案兜底（同电脑版）
                val withId = if (obj.s("action_id") == null && obj.s("id") == null && obj.s("key") == null && obj.s("value") == null && (obj.s("action") ?: "callback") == "callback") {
                    kotlinx.serialization.json.JsonObject(obj + ("action_id" to kotlinx.serialization.json.JsonPrimitive(obj.s("label") ?: content.trim())))
                } else obj
                botButtonFromAttrs(kind, withId, content, null)
            }
            if (buttons.isNotEmpty()) out += BotBlock.Buttons(buttons)
            continue
        }
        val text = buttonTag.replace(line, "")
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()
            Regex("""^#{1,6}\s+""").containsMatchIn(trimmed) -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                out += BotBlock.Text(trimmed.drop(level).trim(), level = level)
            }
            Regex("""^[-*+]\s+""").containsMatchIn(trimmed) -> paragraph += "• " + trimmed.drop(1).trim()
            trimmed.startsWith(">") -> paragraph += "│ " + trimmed.removePrefix(">").trim()
            trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                val cells = trimmed.trim('|').split('|').map { it.trim() }
                // 表头分隔线（---|---）直接丢掉；其余降级成「a ｜ b」一行
                if (!cells.all { Regex("""^:?-{3,}:?$""").matches(it) }) paragraph += cells.joinToString("  ｜  ")
            }
            else -> paragraph += text
        }
    }
    if (inCode && code.isNotEmpty()) out += BotBlock.Code(code.joinToString("\n"))
    flushParagraph()
    return out.ifEmpty { listOf(BotBlock.Text(raw)) }
}

private val inlinePattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)|(https?://[^\s<>"'，。、）)]+)""")

private fun inlineMarkdown(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlinePattern.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]?.value
        val code = match.groups[2]?.value
        val label = match.groups[3]?.value
        val href = match.groups[4]?.value
        val bare = match.groups[5]?.value
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = linkColor.copy(alpha = 0.12f))) { append(code) }
            label != null && href != null -> withLink(LinkAnnotation.Url(href, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(label) }
            bare != null -> withLink(LinkAnnotation.Url(bare, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(bare) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
