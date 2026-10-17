package ink.kuke.chat

import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.ui.stripMarkdown
import ink.kuke.chat.feature.chat.BotBlock
import ink.kuke.chat.feature.chat.botBlocks
import ink.kuke.chat.feature.chat.isBotRichMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 机器人消息里的 <markdown> 包裹、<at id/> 标签（同电脑版 botMarkdown 的规则） */
class BotMarkupTest {
    private fun bot(content: String, metadata: String? = null) = Message(
        id = 1, conversationId = 1, senderId = 9, sender = User(id = 9, isBot = true),
        content = content, metadata = metadata?.let { Json.parseToJsonElement(it) as JsonObject },
    )

    private fun texts(blocks: List<BotBlock>) = blocks.filterIsInstance<BotBlock.Text>()

    @Test
    fun unclosedMarkdownWrapperIsStrippedAndParsed() {
        val message = bot("<markdown># 标题\n你好 <at id=\"2050\"/>\n<markdown>")
        assertTrue(isBotRichMessage(message))
        val blocks = texts(botBlocks(message, mapOf(2050L to "小明")))
        assertEquals("标题", blocks[0].text)
        assertEquals(1, blocks[0].level)
        assertEquals("你好 @小明", blocks[1].text)
    }

    @Test
    fun atFallsBackToMentionsThenUserId() {
        val withMeta = bot("<at id=\"7\"/> 来了", """{"mentions":[{"user_id":7,"name":"阿夸"}]}""")
        assertEquals("@阿夸 来了", texts(botBlocks(withMeta))[0].text)
        val bare = bot("<at id=\"2050\"/> 来了")
        assertEquals("@用户2050 来了", texts(botBlocks(bare))[0].text)
    }

    @Test
    fun serverParsedElementsWithBrokenWrapperAreFlattened() {
        // 服务端没认出成对标签：拆成 text("<markdown># 标题\n") + at + text("\n<markdown>")
        val message = bot(
            "<markdown># 标题 @用户 2050 <markdown>",
            """{"elements":[{"type":"text","content":"<markdown># 标题\n欢迎 "},{"type":"at","attrs":{"id":2050,"name":"用户 2050"}},{"type":"text","content":"\n<markdown>"}]}""",
        )
        val blocks = texts(botBlocks(message, mapOf(2050L to "小红")))
        assertEquals("标题", blocks[0].text)
        assertEquals("欢迎 @小红", blocks[1].text)
    }

    @Test
    fun previewStripsTags() {
        assertEquals("标题 你好 @", stripMarkdown("<markdown># 标题\n你好 <at id=\"1\"/></markdown>"))
    }
}
