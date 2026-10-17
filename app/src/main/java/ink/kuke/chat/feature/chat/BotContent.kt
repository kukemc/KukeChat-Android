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

/**
 * 是不是要走机器人结构化渲染：机器人发的文字消息，带 markdown 标记或 elements；
 * 或者正文里直接写了 `<markdown>…</markdown>` 包裹、`<at id="…"/>` 标签（没走服务端的元素解析，原样落库的）。
 */
fun isBotRichMessage(message: Message): Boolean {
    if (message.type != "text" || message.sender?.isBot != true) return false
    val meta = message.metadata
    if (meta?.b("markdown") == true || meta?.a("elements")?.isNotEmpty() == true) return true
    return markdownWrapperTag.containsMatchIn(message.content) || atTag.containsMatchIn(message.content)
}

/** 群成员 id → 显示名（聊天页按会话提供）：机器人消息里的 `<at id/>` 用它把 id 换成名字 */
val LocalMemberNames = androidx.compose.runtime.compositionLocalOf<Map<Long, String>> { emptyMap() }

/** `<markdown>` / `</markdown>` / `<md>`：整段包裹标签。结尾常被写成没有斜杠的 `<markdown>`，开闭都当包裹去掉 */
private val markdownWrapperTag = Regex("""<\s*/?\s*(markdown|md)\b[^<>]*>""", RegexOption.IGNORE_CASE)

/** `<at id="12"/>`、`<mention user_id="12"/>`、`<at_all/>`（同电脑版 botMarkdown 的写法，斜杠可省） */
private val atTag = Regex("""<\s*(at_all|mention_all|at|mention)\b([^<>]*?)/?\s*>""", RegexOption.IGNORE_CASE)

/** 已经换成名字的 @：用私用区字符包起来，inlineMarkdown 认出来画成强调色 */
private const val MENTION_OPEN = ''
private const val MENTION_CLOSE = ''

/** 服务端在没给名字时补的「用户 12」：当作没名字，优先用成员列表里的 */
private val fallbackMentionName = Regex("""^用户\s*\d+$""")

/** @ 的显示名：标签上写的名字 > 群成员名 > 消息 metadata.mentions 里的名字 > 「用户12」（同电脑版的取名顺序） */
private fun mentionName(id: Long?, explicit: String?, message: Message, names: Map<Long, String>): String {
    explicit?.trim()?.takeIf { it.isNotEmpty() && !fallbackMentionName.matches(it) }?.let { return it }
    if (id == null) return "用户"
    names[id]?.takeIf { it.isNotBlank() }?.let { return it }
    val fromMeta = message.metadata?.a("mentions")?.firstNotNullOfOrNull { item ->
        val obj = item.asObj() ?: return@firstNotNullOfOrNull null
        if ((obj.l("user_id") ?: obj.l("id")) == id) obj.s("name") else null
    }
    fromMeta?.trim()?.takeIf { it.isNotEmpty() && !fallbackMentionName.matches(it) }?.let { return it }
    return "用户$id"
}

private fun mentionToken(name: String): String = "$MENTION_OPEN@$name$MENTION_CLOSE"

private fun tagAttrs(raw: String): Map<String, String> =
    attrPattern.findAll(raw).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } }) }

/** 正文里的 `<at …/>` 换成 @名字 标记 */
private fun replaceAtTags(text: String, message: Message, names: Map<Long, String>): String = atTag.replace(text) { match ->
    val tag = match.groupValues[1].lowercase()
    if (tag == "at_all" || tag == "mention_all") return@replace mentionToken("全体成员")
    val attrs = tagAttrs(match.groupValues[2])
    val id = (attrs["id"] ?: attrs["user_id"] ?: attrs["userId"])?.trim()?.toLongOrNull()
    mentionToken(mentionName(id, attrs["name"] ?: attrs["nickname"] ?: attrs["username"], message, names))
}

/** 去掉整段的 `<markdown>` 包裹；没有包裹返回 null */
private fun unwrapMarkdown(text: String): String? =
    if (markdownWrapperTag.containsMatchIn(text)) markdownWrapperTag.replace(text, "").trim('\n', '\r') else null

/**
 * 机器人的 Markdown 消息：不套气泡，白面卡片。
 * 支持标题、列表、引用、代码块、加粗、行内代码、链接，表格降级成一行行文本；
 * <button>/<link> 标签和 metadata.elements 里的按钮渲染成按钮组，点击走 onButton（填入输入框 / 回调 / 打开链接）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BotContent(message: Message, onButton: (Message, BotButton) -> Unit) {
    val colors = KcTheme.colors
    val names = LocalMemberNames.current
    val blocks = remember(message.id, message.content, message.metadata, names) { botBlocks(message, names) }
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

/** 只有文字类元素（没有按钮、图片、引用…）：包裹标签写坏时可以拼回原文整体当 Markdown 解析 */
private val textualElementTypes = setOf("text", "plain", "p", "markdown", "md", "br", "at", "mention", "at_all", "mention_all")

private fun elementType(element: kotlinx.serialization.json.JsonObject): String =
    (element.s("type") ?: element.s("name")).orEmpty().lowercase().replace('-', '_')

/** 文字类元素拼回原文（at 还原成标签，名字后面统一解析） */
private fun flattenElements(elements: List<kotlinx.serialization.json.JsonObject>): String = buildString {
    elements.forEach { element ->
        val attrs = element.o("attrs")
        when (elementType(element)) {
            "br" -> append('\n')
            "at_all", "mention_all" -> append("<at_all/>")
            "at", "mention" -> {
                val id = attrs?.l("id") ?: attrs?.l("user_id") ?: attrs?.l("userId")
                val name = (attrs?.s("name") ?: attrs?.s("nickname") ?: attrs?.s("username"))?.replace("\"", "")
                append("<at")
                if (id != null) append(" id=\"$id\"")
                if (!name.isNullOrBlank()) append(" name=\"$name\"")
                append("/>")
            }
            else -> append(element.s("content").orEmpty())
        }
    }
}

/**
 * message → 渲染块。规则照电脑版（botMarkdown.tsx）：
 * - 有 elements 就按元素画；markdown 元素当 Markdown，at 元素画成 @名字；
 * - 没有 elements：带 markdown 标记、或正文被 `<markdown>` 包着（结尾写成 `<markdown>` 也认）就去掉包裹当 Markdown，否则当普通文字；
 * - 正文、Markdown 里的 `<at id="…"/>` 都换成 @名字（[names] 是群成员名）。
 */
internal fun botBlocks(message: Message, names: Map<Long, String> = emptyMap()): List<BotBlock> {
    val elements = message.metadata?.a("elements")?.mapNotNull { it.asObj() }.orEmpty()
    val markdownFlag = message.metadata?.b("markdown") == true
    // 只有文字类元素、而且文字里有包裹标签（多半是结尾写成了 <markdown>，服务端没认出成对的标签）：拼回原文整体解析
    val wrappedText = elements.isNotEmpty() &&
        elements.all { elementType(it) in textualElementTypes } &&
        elements.any { markdownWrapperTag.containsMatchIn(it.s("content").orEmpty()) }
    if (elements.isEmpty() || wrappedText) {
        val source = if (elements.isEmpty()) message.content else flattenElements(elements)
        val unwrapped = unwrapMarkdown(source)
        val text = replaceAtTags(unwrapped ?: source, message, names)
        return if (markdownFlag || unwrapped != null) markdownBlocks(text) else listOf(BotBlock.Text(text))
    }
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
                out += markdownBlocks(replaceAtTags(unwrapMarkdown(content) ?: content, message, names))
            }
            "br" -> flush()
            "at_all", "mention_all" -> {
                flush()
                out += BotBlock.Text(mentionToken("全体成员"))
            }
            "at", "mention" -> {
                flush()
                val attrs = element.o("attrs")
                val id = attrs?.l("id") ?: attrs?.l("user_id") ?: attrs?.l("userId")
                out += BotBlock.Text(mentionToken(mentionName(id, attrs?.s("name") ?: attrs?.s("nickname") ?: attrs?.s("username"), message, names)))
            }
            else -> {
                flush()
                if (content.isNotBlank()) out += BotBlock.Text(replaceAtTags(content, message, names))
            }
        }
    }
    flush()
    return out.ifEmpty { markdownBlocks(replaceAtTags(unwrapMarkdown(message.content) ?: message.content, message, names)) }
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

private val inlinePattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)|(https?://[^\s<>"'，。、）)]+)|([^]*)""")

private fun inlineMarkdown(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlinePattern.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]?.value
        val code = match.groups[2]?.value
        val label = match.groups[3]?.value
        val href = match.groups[4]?.value
        val bare = match.groups[5]?.value
        val mention = match.groups[6]?.value
        when {
            mention != null -> withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.W600)) { append(mention) }
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = linkColor.copy(alpha = 0.12f))) { append(code) }
            label != null && href != null -> withLink(LinkAnnotation.Url(href, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(label) }
            bare != null -> withLink(LinkAnnotation.Url(bare, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(bare) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
