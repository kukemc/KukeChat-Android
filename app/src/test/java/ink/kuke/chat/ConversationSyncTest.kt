package ink.kuke.chat

import ink.kuke.chat.core.data.ConversationPatch
import ink.kuke.chat.core.data.ReadThrottle
import ink.kuke.chat.core.model.Conversation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSyncTest {
    private val base = Conversation(id = 7, type = "group", title = "旧名", updatedAt = "2026-11-01T00:00:00Z", memberCount = 3, mentionPending = "me")

    // ------------------------------------------------------------ ConversationPatch

    @Test
    fun lightPatchAppliesWithoutReload() {
        // 旧后端已读后推的那种：只有 id 和 mention_pending（null = 清掉）
        val data = JsonObject(mapOf("conversation_id" to kotlinx.serialization.json.JsonPrimitive(7), "mention_pending" to JsonNull))
        val result = ConversationPatch.apply(base, data)
        assertNull(result.patched!!.mentionPending)
        assertFalse(result.needsReload)
    }

    @Test
    fun snapshotPatchesButStillReloads() {
        // send_conversation_patch 的整份快照：带 type / join_mode 这些列表表示不了的 → 改完还要限频重拉
        val data = buildJsonObject {
            put("id", 7); put("conversation_id", 7); put("type", "group"); put("title", "新名")
            put("all_muted", true); put("join_mode", "open"); put("updated_at", "2026-11-07T00:00:00Z")
        }
        val result = ConversationPatch.apply(base, data)
        val patched = result.patched!!
        assertEquals("新名", patched.title)
        assertTrue(patched.allMuted)
        // updated_at 不抄：列表按它排最近活跃
        assertEquals("2026-11-01T00:00:00Z", patched.updatedAt)
        // 没出现的键不动
        assertEquals("me", patched.mentionPending)
        assertEquals(3, patched.memberCount)
        assertTrue(result.needsReload)
    }

    @Test
    fun nothingRecognisableReloads() {
        val result = ConversationPatch.apply(base, buildJsonObject { put("conversation_id", 7); put("reason", "invite.accepted") })
        assertNull(result.patched)
        assertTrue(result.needsReload)
    }

    @Test
    fun conversationIdFallsBackToId() {
        assertEquals(7L, ConversationPatch.conversationId(buildJsonObject { put("id", 7) }))
        assertEquals(8L, ConversationPatch.conversationId(buildJsonObject { put("conversation_id", 8); put("id", 7) }))
        assertNull(ConversationPatch.conversationId(buildJsonObject { put("reason", "friend_request.accepted") }))
    }

    // ------------------------------------------------------------ ReadThrottle

    private var now = 10_000L
    private val throttle = ReadThrottle(3_000) { now }

    @Test
    fun firstReadSendsImmediatelyAndRepeatsAreSkipped() {
        assertEquals(ReadThrottle.Step.Send(100), throttle.offer(1, 100, foreground = true))
        // 同一条 / 更早的：不再报
        assertEquals(ReadThrottle.Step.Skip, throttle.offer(1, 100, foreground = true))
        assertEquals(ReadThrottle.Step.Skip, throttle.offer(1, 90, foreground = true))
    }

    @Test
    fun burstInsideWindowSendsLatestOnceAtTheEnd() {
        throttle.offer(1, 100, foreground = true)
        now += 500
        assertEquals(ReadThrottle.Step.Wait(2_500), throttle.offer(1, 101, foreground = true))
        now += 500
        // 已经排了尾随发送：后来的只更新要发的 id
        assertEquals(ReadThrottle.Step.Skip, throttle.offer(1, 105, foreground = true))
        assertEquals(ReadThrottle.Step.Skip, throttle.offer(1, 103, foreground = true))
        now += 2_000
        assertEquals(105L, throttle.takeDue(1, foreground = true))
        assertNull(throttle.takeDue(1, foreground = true))
    }

    @Test
    fun conversationsAreThrottledIndependently() {
        assertEquals(ReadThrottle.Step.Send(100), throttle.offer(1, 100, foreground = true))
        assertEquals(ReadThrottle.Step.Send(200), throttle.offer(2, 200, foreground = true))
    }

    @Test
    fun backgroundReadsAreHeldUntilForeground() {
        assertEquals(ReadThrottle.Step.Skip, throttle.offer(1, 100, foreground = false))
        assertEquals(ReadThrottle.Step.Skip, throttle.offer(1, 120, foreground = false))
        assertEquals(mapOf(1L to 120L), throttle.drain())
        assertTrue(throttle.drain().isEmpty())
    }

    @Test
    fun failedSendIsRetriedLater() {
        throttle.offer(1, 100, foreground = true)
        throttle.failed(1, 100)
        now += 3_000
        // 同一条再来一次：重报
        assertEquals(ReadThrottle.Step.Send(100), throttle.offer(1, 100, foreground = true))
        throttle.succeeded(1, 100)
        now += 3_000
        assertEquals(ReadThrottle.Step.Skip, throttle.offer(1, 100, foreground = true))
    }
}
