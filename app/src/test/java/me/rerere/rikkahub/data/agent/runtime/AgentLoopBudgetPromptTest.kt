package me.rerere.rikkahub.data.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopBudgetPromptTest {
    @Test
    fun largeBudgetStagesAreMutuallyExclusive() {
        assertEquals(null, AgentLoopBudgetPrompt.stage(stepIndex = 0, maxSteps = 256))
        assertEquals(AgentLoopBudgetStage.WARN, AgentLoopBudgetPrompt.stage(stepIndex = 244, maxSteps = 256))
        assertEquals(AgentLoopBudgetStage.TIGHT, AgentLoopBudgetPrompt.stage(stepIndex = 250, maxSteps = 256))
        assertEquals(AgentLoopBudgetStage.FINAL, AgentLoopBudgetPrompt.stage(stepIndex = 254, maxSteps = 256))
    }

    @Test
    fun smallBudgetKeepsInitialToolTurnsAvailable() {
        assertEquals(null, AgentLoopBudgetPrompt.stage(stepIndex = 0, maxSteps = 4))
        assertEquals(AgentLoopBudgetStage.WARN, AgentLoopBudgetPrompt.stage(stepIndex = 1, maxSteps = 4))
        assertEquals(AgentLoopBudgetStage.TIGHT, AgentLoopBudgetPrompt.stage(stepIndex = 2, maxSteps = 4))
        assertEquals(AgentLoopBudgetStage.FINAL, AgentLoopBudgetPrompt.stage(stepIndex = 3, maxSteps = 4))
    }

    @Test
    fun twoStepRecoveryRunKeepsFirstTurnToolCapable() {
        assertEquals(null, AgentLoopBudgetPrompt.stage(stepIndex = 0, maxSteps = 2))
        assertEquals(AgentLoopBudgetStage.FINAL, AgentLoopBudgetPrompt.stage(stepIndex = 1, maxSteps = 2))
        assertFalse(AgentLoopBudgetPrompt.shouldHideTools(stepIndex = 0, maxSteps = 2, hasResumableTools = false))
        assertTrue(AgentLoopBudgetPrompt.shouldHideTools(stepIndex = 1, maxSteps = 2, hasResumableTools = false))
    }

    @Test
    fun finalStageHidesToolsUnlessResumingExistingTools() {
        assertTrue(AgentLoopBudgetPrompt.shouldHideTools(stepIndex = 254, maxSteps = 256, hasResumableTools = false))
        assertFalse(AgentLoopBudgetPrompt.shouldHideTools(stepIndex = 254, maxSteps = 256, hasResumableTools = true))
    }

    @Test
    fun promptContainsStageSpecificInstruction() {
        assertTrue(AgentLoopBudgetPrompt.build(stepIndex = 254, maxSteps = 256).contains("final answer"))
        assertEquals("", AgentLoopBudgetPrompt.build(stepIndex = 0, maxSteps = 256))
    }
}
