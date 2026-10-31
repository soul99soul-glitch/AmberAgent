package app.amber.core.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextPhaseRouterTest {
    @Test
    fun pendingToolCallsKeepTheCurrentSession() {
        val decision = ContextPhaseRouter.decide(
            pendingTools = true,
            awaitingApproval = false,
            acceptanceSatisfied = true,
        )
        assertEquals(ContextPhaseRouter.ACTION_CONTINUE, decision.action)
        assertEquals("pending_tool_calls", decision.reason)
    }

    @Test
    fun awaitingApprovalKeepsTheCurrentSession() {
        val decision = ContextPhaseRouter.decide(
            pendingTools = false,
            awaitingApproval = true,
            acceptanceSatisfied = true,
        )
        assertEquals(ContextPhaseRouter.ACTION_CONTINUE, decision.action)
        assertEquals("awaiting_approval", decision.reason)
    }

    @Test
    fun missingAcceptanceEvidenceDefaultsToContinue() {
        val decision = ContextPhaseRouter.decide(
            pendingTools = false,
            awaitingApproval = false,
            acceptanceSatisfied = false,
        )
        assertEquals(ContextPhaseRouter.ACTION_CONTINUE, decision.action)
        assertEquals("no_acceptance_evidence", decision.reason)
    }

    @Test
    fun switchingRequiresExplicitAcceptanceEvidence() {
        val decision = ContextPhaseRouter.decide(
            pendingTools = false,
            awaitingApproval = false,
            acceptanceSatisfied = true,
        )
        assertEquals(ContextPhaseRouter.ACTION_SWITCH, decision.action)
        assertEquals("phase_accepted", decision.reason)
    }
}
