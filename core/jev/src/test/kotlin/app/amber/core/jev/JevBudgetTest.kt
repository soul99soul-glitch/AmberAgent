package app.amber.core.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevBudgetTest {
    private fun store() = object : JevUsageStore {
        private val values = mutableMapOf<String, JevDailyUsage>()

        override fun load(dayKey: String): JevDailyUsage = values[dayKey] ?: JevDailyUsage()

        override fun store(dayKey: String, usage: JevDailyUsage) {
            values[dayKey] = usage
        }
    }

    @Test
    fun ordinaryBudgetDoesNotStarveScreenAutomation() {
        val budget = JevBudget(store(), clock = { 0L })

        repeat(JevLimits.PER_RUN_MAX_REQUESTS) {
            assertTrue(budget.consume("run-1", bodyBytes = 1, purpose = JevPurpose.MEMORY_RECALL))
        }
        assertFalse(budget.consume("run-1", bodyBytes = 1, purpose = JevPurpose.MEMORY_RECALL))
        assertTrue(budget.consume("run-1", bodyBytes = 1, purpose = JevPurpose.SCREEN_AUTOMATION))

        assertEquals(JevLimits.PER_RUN_MAX_REQUESTS + 1, budget.runUsage("run-1").requests)
    }

    @Test
    fun screenAutomationHasItsOwnRequestAndStateCeilings() {
        val budget = JevBudget(store(), clock = { 0L })

        repeat(JevBudget.SCREEN_AUTOMATION_MAX_REQUESTS) {
            assertTrue(budget.consume("run-1", bodyBytes = 1, purpose = JevPurpose.SCREEN_AUTOMATION))
        }
        assertFalse(budget.consume("run-1", bodyBytes = 1, purpose = JevPurpose.SCREEN_AUTOMATION))

        val stateBudget = JevBudget(store(), clock = { 0L })
        val requestBytes = 64 * 1024L
        repeat(JevBudget.SCREEN_AUTOMATION_MAX_STATE_BYTES / requestBytes.toInt()) {
            assertTrue(stateBudget.consume("run-2", bodyBytes = requestBytes, purpose = JevPurpose.SCREEN_AUTOMATION))
        }
        assertFalse(stateBudget.consume("run-2", bodyBytes = 1, purpose = JevPurpose.SCREEN_AUTOMATION))
    }

    @Test
    fun dailyBudgetIsSharedAcrossPurposes() {
        val budget = JevBudget(store(), clock = { 0L })

        assertTrue(
            budget.consume(
                runKey = null,
                requestCount = JevLimits.DAILY_MAX_REQUESTS,
                bodyBytes = 0,
                purpose = JevPurpose.SCREEN_AUTOMATION,
            ),
        )
        assertFalse(budget.consume(null, bodyBytes = 1, purpose = JevPurpose.MEMORY_RECALL))
        assertEquals(JevLimits.DAILY_MAX_REQUESTS, budget.dailyUsage().requests)
    }
}
