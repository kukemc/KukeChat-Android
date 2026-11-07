package ink.kuke.chat

import ink.kuke.chat.core.dsl.DslSegment
import ink.kuke.chat.core.dsl.DslText
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.ui.messagePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 积木围栏切段、预筛、包围栏、会话列表摘要（同电脑版 src/gandiDsl/gandiDsl.test.mjs 的字符串部分） */
class DslTextTest {
    @Test
    fun fencesAreSplitOutAndOtherLanguagesStayText() {
        assertEquals(
            listOf(DslSegment.Text("看这个："), DslSegment.Block("looks.say(\"hi\");", "gandi-dsl"), DslSegment.Text("好用吧")),
            DslText.split("看这个：\n```gandi-dsl\nlooks.say(\"hi\");\n```\n好用吧"),
        )
        assertTrue(DslText.split("```GANDI\nx\n```")!![0] is DslSegment.Block)
        assertEquals("gandi", (DslText.split("```GANDI\nx\n```")!![0] as DslSegment.Block).lang)
        assertEquals("dsl", (DslText.split("```dsl\nx\n```")!![0] as DslSegment.Block).lang)
        assertEquals("gandi-dsl", (DslText.split("  ``` gandi-dsl \nx\n  ```  ")!![0] as DslSegment.Block).lang)
        assertNull(DslText.split("```js\nlooks.say(1)\n```"))
        assertNull(DslText.split("普通消息"))
        assertNull(DslText.split("行内 ```dsl 不算围栏"))
    }

    @Test
    fun unclosedFenceRunsToEnd() {
        assertEquals(listOf(DslSegment.Block("looks.say(1)", "gandi-dsl")), DslText.split("```gandi-dsl\nlooks.say(1)"))
    }

    @Test
    fun dslInsideOtherFenceIsIgnored() {
        assertNull(DslText.split("```js\n```dsl\n```"))
        // js 围栏之后的积木围栏照样认
        val segments = DslText.split("```js\nlet a = 1\n```\n```dsl\nlooks.say(1)\n```")!!
        assertEquals(DslSegment.Text("```js\nlet a = 1\n```"), segments[0])
        assertEquals(DslSegment.Block("looks.say(1)", "dsl"), segments[1])
    }

    @Test
    fun multipleFencesAndBlankTextAreHandled() {
        val segments = DslText.split("\n\n```dsl\na()\n```\n   \n```gandi\nb()\n```\n\n结尾\n")!!
        assertEquals(listOf(DslSegment.Block("a()", "dsl"), DslSegment.Block("b()", "gandi"), DslSegment.Text("结尾")), segments)
    }

    @Test
    fun wrapRoundTrips() {
        val code = "when.flagclicked(() => {\n  looks.say(\"你好\");\n});"
        val wrapped = DslText.wrap("$code\n\n")
        assertEquals("```gandi-dsl\n$code\n```", wrapped)
        assertEquals(listOf(DslSegment.Block(code, "gandi-dsl")), DslText.split(wrapped))
    }

    @Test
    fun preFilterAcceptsBlockCalls() {
        for (text in listOf(
            "when.flagclicked(() => { looks.say(\"你好\"); motion.movesteps(10); });",
            "looks.say(\"hi\")",
            "define(\"跳 %n 下\", (n) => { motion.changeyby(n); }); when.thisspriteclicked(() => { call[\"跳 %n 下\"](3); });",
            "when.broadcastreceived(go, () => { looks.say(1); })",
            "control.wait (1)",
            // 预筛只看字面：句子里提到积木也会过，交给编译器刷掉
            "我觉得 looks.say(\"hi\") 这个积木挺好用的",
        )) assertTrue(text, DslText.looksLikeDsl(text))
    }

    @Test
    fun preFilterRejectsOrdinaryText() {
        for (text in listOf(
            "你好呀，今天吃什么",
            "hello world looks.say hi",
            "x = 1",
            "if (a) { b = 1 }",
            "const a = 1; console.log(a)",
            "document.getElementById(\"app\").remove()",
            "foo.looks.say(1)",
            "mylooks.say(1)",
            "looks.say",
            "a()",
            "looks.say(\"" + "长".repeat(DslText.MAX_LENGTH) + "\")",
        )) assertFalse(text, DslText.looksLikeDsl(text))
    }

    @Test
    fun mayContainDsl() {
        assertTrue(DslText.mayContainDsl("```dsl\nx\n```"))
        assertTrue(DslText.mayContainDsl("looks.say(1)"))
        assertFalse(DslText.mayContainDsl("普通消息"))
    }

    @Test
    fun previewReplacesFences() {
        assertEquals("看这个： [积木] 好用吧", DslText.preview("看这个：\n```gandi-dsl\nlooks.say(\"hi\");\n```\n好用吧"))
        assertEquals("[积木]", DslText.preview("```dsl\nx\n```"))
        assertEquals("looks.say(1)", DslText.preview("looks.say(1)"))
        assertEquals("[积木]", messagePreview(Message(id = 1, conversationId = 1, content = "```gandi-dsl\nlooks.say(1)\n```")))
        assertEquals("看 [积木]", messagePreview(Message(id = 1, conversationId = 1, content = "看\n```gandi\n**x**\n```")))
    }
}
