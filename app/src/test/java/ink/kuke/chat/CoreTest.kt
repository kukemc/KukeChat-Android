package ink.kuke.chat

import ink.kuke.chat.core.net.ClientProof
import ink.kuke.chat.core.ui.stripMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    // 期望值由 Python hashlib 按服务端 request_guard.py 的算法算出，两端必须一致，否则所有接口 403
    @Test
    fun clientProofMatchesServerAlgorithm() {
        assertEquals(
            "564ebdaabbfe1996b3afa282630715b5b7cdd56c483ed585d7b9f39a553d7d42",
            ClientProof.proof("get", "/conversations", "1760590000000", "abcdef0123456789"),
        )
        assertEquals(
            "824de7fe985f16d48d9c730e3d0bff28c8290b73130c6d3fb64a6874b3975fbb",
            ClientProof.proof("POST", "/auth/login", "1760590000000", "abcdef0123456789"),
        )
    }

    @Test
    fun clientProofIgnoresQueryString() {
        assertEquals(
            ClientProof.proof("GET", "/conversations/1/messages", "1", "abcdefabcdef"),
            ClientProof.proof("GET", "/conversations/1/messages?limit=50&before_id=9", "1", "abcdefabcdef"),
        )
    }

    @Test
    fun clientProofHeadersAreWellFormed() {
        val headers = ClientProof.headers("GET", "/friends")
        assertTrue(headers.getValue("X-Kuke-Client").startsWith("kukechat-web/"))
        assertEquals(16, headers.getValue("X-Kuke-Nonce").length)
        assertEquals(64, headers.getValue("X-Kuke-Proof").length)
    }

    @Test
    fun stripMarkdownRemovesSyntaxButKeepsText() {
        assertEquals("签到成功 用户：欣晨", stripMarkdown("# 签到成功\n**用户：**欣晨"))
        assertEquals("标题 正文 链接", stripMarkdown("## 标题\n> 正文 [链接](https://a.b)"))
        assertEquals("a b", stripMarkdown("- a\n- b"))
    }
}
