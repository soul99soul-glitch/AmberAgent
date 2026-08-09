package app.amber.core.service

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGenerationForegroundServiceLeaseTest {
    @Test
    fun `chat and novel leases cannot collide when raw ids match`() {
        val rawId = "shared-id"
        val chat = chatGenerationOwnerKey(rawId)
        val novel = novelGenerationOwnerKey(rawId)
        val activeLeases = mutableMapOf(chat to Unit, novel to Unit)

        assertNotEquals(chat, novel)
        activeLeases.removeChatGeneration(rawId)
        assertFalse(chat in activeLeases)
        assertTrue(novel in activeLeases)
    }

    @Test
    fun `stale stop cannot remove a newer lease for the same novel run`() {
        val runId = "same-run"
        val ownerKey = novelGenerationOwnerKey(runId)
        val activeLeases = ConcurrentHashMap<String, TestLease>()
        activeLeases[ownerKey] = TestLease("lease-a")
        activeLeases[ownerKey] = TestLease("lease-b")

        assertFalse(
            activeLeases.removeNovelGenerationIfLeaseMatches(runId, "lease-a", TestLease::token),
        )
        assertEquals(TestLease("lease-b"), activeLeases[ownerKey])

        assertTrue(
            activeLeases.removeNovelGenerationIfLeaseMatches(runId, "lease-b", TestLease::token),
        )
        assertFalse(activeLeases.containsKey(ownerKey))
    }

    private data class TestLease(val token: String)
}
