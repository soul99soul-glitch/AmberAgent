package app.amber.agent.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OpportunityEntityTest {
    @Test
    fun stable_opportunity_id_uses_dedupe_key() {
        val first = stableOpportunityId("meeting_prep|calendar:event-1")
        val second = stableOpportunityId("meeting_prep|calendar:event-1")

        assertEquals(first, second)
        assertEquals(32, first.length)
    }

    @Test
    fun stable_opportunity_id_changes_with_source_identity() {
        val first = stableOpportunityId("meeting_prep|calendar:event-1")
        val second = stableOpportunityId("meeting_prep|calendar:event-2")

        assertNotEquals(first, second)
    }

    @Test
    fun reference_anchor_id_is_deterministic() {
        val first = stableReferenceAnchorId("dep-1|mine#1|upstream#metric")
        val second = stableReferenceAnchorId("dep-1|mine#1|upstream#metric")

        assertEquals(first, second)
        assertEquals(32, first.length)
    }
}
