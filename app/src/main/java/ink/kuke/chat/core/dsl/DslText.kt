package ink.kuke.chat.core.dsl

/**
 * 积木文本（Gandi 3.0 DSL）的识别 —— 纯字符串的那一半，和电脑版 src/gandiDsl/detect.ts 一一对应。
 *
 * 聊天列表里每条消息都会过一遍这里，所以只做字符串判断：
 * - 有没有 ```gandi-dsl / ```gandi / ```dsl 围栏，有就把消息切成「文字 / 积木」几段；
 * - 没有围栏时，整条消息「像不像」积木文本（[looksLikeDsl]）。这只是预筛，真正拍板要交给渲染页里的编译器
 *   （[DslRenderer.detect]）：预筛不过的消息永远不会把 WebView 拉起来。
 *
 * 规则见 KukeChat-Client/docs/gandi-dsl-blocks.md「识别规则」。
 */
object DslText {
    /** 认作积木文本的围栏语言名（不分大小写） */
    val FENCE_LANGS = setOf("gandi-dsl", "gandi", "dsl")

    /** 发消息时用的围栏语言名 */
    const val FENCE_LANG = "gandi-dsl"

    /** 服务端的消息长度上限（MAX_MESSAGE_CONTENT_LENGTH），含围栏 */
    const val MAX_LENGTH = 2000

    private val fenceOpen = Regex("""^[ \t]*```[ \t]*([\w-]*)[ \t]*$""")
    private val fenceClose = Regex("""^[ \t]*```[ \t]*$""")

    /** 积木调用的样子：`分类.积木名(`、`define(`、`call[`（同电脑版 BLOCK_CALL） */
    private val blockCall = Regex(
        """(?:^|[^\w$.])(?:when|motion|looks|sound|event|control|sensing|operator|data|procedures|argument|ccw|interpolation)\.[A-Za-z_]\w*\s*\(|(?:^|[^\w$.])(?:define\s*\(|call\s*\[)""",
    )

    /**
     * 按围栏把消息切段。只有积木语言名的围栏切出来；别的围栏（```js）原样留在文字里，里面的 ```dsl 字样也不算。
     * 没闭合的积木围栏一直算到结尾。没有任何积木围栏时返回 null（调用方照普通文字画）。
     */
    fun split(text: String): List<DslSegment>? {
        if (!text.contains("```")) return null
        val lines = text.split('\n')
        val segments = mutableListOf<DslSegment>()
        val buffer = mutableListOf<String>()
        var found = false
        var index = 0

        fun flushText() {
            val value = buffer.joinToString("\n")
            buffer.clear()
            if (value.isNotBlank()) segments += DslSegment.Text(value.trim('\n'))
        }

        while (index < lines.size) {
            val line = lines[index]
            val open = fenceOpen.matchEntire(line)
            val lang = open?.groupValues?.get(1).orEmpty()
            if (open != null && lang.lowercase() in FENCE_LANGS) {
                flushText()
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !fenceClose.matches(lines[index])) {
                    code += lines[index]
                    index++
                }
                index++ // 跳过闭合那一行（没闭合时已经到头）
                found = true
                segments += DslSegment.Block(code.joinToString("\n").trimEnd(), lang.lowercase())
                continue
            }
            if (open != null && lang.isNotEmpty()) {
                // 别的语言的围栏：整段原样当文字
                buffer += line
                index++
                while (index < lines.size && !fenceClose.matches(lines[index])) {
                    buffer += lines[index]
                    index++
                }
                if (index < lines.size) buffer += lines[index]
                index++
                continue
            }
            buffer += line
            index++
        }
        flushText()
        return if (found) segments else null
    }

    /** 没有围栏的整条消息要不要交给编译器再判一次：长度 8–2000，且出现过积木调用 */
    fun looksLikeDsl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 8 || trimmed.length > MAX_LENGTH) return false
        return blockCall.containsMatchIn(trimmed)
    }

    /** 有积木围栏，或预筛通过（只做字符串判断） */
    fun mayContainDsl(text: String): Boolean = split(text) != null || looksLikeDsl(text)

    /** 把一段积木文本包成发消息用的围栏 */
    fun wrap(code: String): String = "```$FENCE_LANG\n${code.trimEnd()}\n```"

    /** 会话列表的一行摘要：积木围栏换成「[积木]」，别的文字照留（压成一行）。没有积木围栏时原样返回 */
    fun preview(text: String): String {
        val segments = split(text) ?: return text
        return segments.map { segment ->
            when (segment) {
                is DslSegment.Block -> "[积木]"
                is DslSegment.Text -> segment.text.replace(Regex("""\s+"""), " ").trim()
            }
        }.filter { it.isNotEmpty() }.joinToString(" ")
    }
}

sealed interface DslSegment {
    data class Text(val text: String) : DslSegment
    data class Block(val code: String, val lang: String = DslText.FENCE_LANG) : DslSegment
}
