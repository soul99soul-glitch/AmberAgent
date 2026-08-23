package app.amber.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentGenerationForegroundServiceLeaseTest {
    @Test
    fun `chat lease removal targets only the matching owner key`() {
        val rawId = "shared-id"
        val ownerKey = chatGenerationOwnerKey(rawId)
        val activeLeases = mutableMapOf(ownerKey to Unit)

        assertNotEquals(rawId, ownerKey)
        activeLeases.removeChatGeneration(rawId)
        assertFalse(ownerKey in activeLeases)
    }

    @Test
    fun `removing an absent chat generation leaves the map unchanged`() {
        val activeLeases = mutableMapOf<String, Unit>()
        assertNull(activeLeases.removeChatGeneration("never-added"))
        assertFalse(activeLeases.isNotEmpty())
        assertEquals(0, activeLeases.size)
    }
}
