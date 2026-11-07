package ink.kuke.chat

import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.feature.contacts.GroupJoinRequest
import ink.kuke.chat.feature.contacts.sortedJoinRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinRequestTest {
    @Test
    fun pendingFirstThenNewest() {
        val list = listOf(
            GroupJoinRequest(1, status = "rejected", createdAt = "2026-11-05T00:00:00Z"),
            GroupJoinRequest(2, status = "pending", createdAt = "2026-11-01T00:00:00Z"),
            GroupJoinRequest(3, status = "accepted", createdAt = "2026-11-06T00:00:00Z"),
            GroupJoinRequest(4, status = "pending", createdAt = "2026-11-03T00:00:00Z"),
        )
        assertEquals(listOf(4L, 2L, 3L, 1L), list.sortedJoinRequests({ it.status }, { it.createdAt }).map { it.id })
    }

    @Test
    fun inviteOnlyGroupHidesInviteForMembers() {
        val group = Conversation(id = 1, type = "group", joinMode = "invite_only")
        assertFalse(group.canShareInvite)
        assertTrue(group.copy(myRole = "admin").canShareInvite)
        assertTrue(group.copy(joinMode = "approval").canShareInvite)
        // 旧缓存没有 join_mode：先放行，失败再提示
        assertTrue(group.copy(joinMode = null).canShareInvite)
        assertFalse(Conversation(id = 2, type = "direct").canShareInvite)
    }
}
