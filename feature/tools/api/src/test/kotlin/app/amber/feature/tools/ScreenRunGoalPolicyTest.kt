package app.amber.feature.tools

import app.amber.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRunGoalPolicyTest {
    private fun tool(name: String, mandatoryApproval: Boolean = false) = Tool(
        name = name,
        description = name,
        mandatoryApproval = mandatoryApproval,
        execute = { emptyList() },
    )

    @Test
    fun screenRunGoalIsHighRiskForegroundOnlyAndNonIdempotent() {
        val goal = tool("screen_run_goal")
        val metadata = ToolRegistry.from(listOf(goal)).metadataFor("screen_run_goal")!!
        val policy = goal.invocationPolicy("{}")

        assertEquals("screen", metadata.category)
        assertTrue(metadata.mutates)
        assertEquals(ToolRisk.High, metadata.risk)
        assertTrue(metadata.needsApproval)
        assertTrue(metadata.mandatoryApproval)
        assertEquals(ToolEffectClass.NON_IDEMPOTENT_WRITE, metadata.effectClass)
        assertEquals(Capability.SCREEN_AUTOMATION, metadata.capability)

        assertTrue(policy.mutates)
        assertEquals(ToolRisk.High, policy.risk)
        assertTrue(policy.needsApproval)
        assertTrue(policy.mandatoryApproval)
        assertFalse(policy.autoApprovable)
        assertFalse(policy.concurrencySafe)
        assertFalse(policy.speculativeEligible)
        assertEquals(null, policy.parallelGroup)
    }

    @Test
    fun existingScreenCaptureMappingAndRiskStayUnchanged() {
        val capture = tool("screen_screenshot")
        val metadata = ToolRegistry.from(listOf(capture)).metadataFor("screen_screenshot")!!

        assertEquals("screen", metadata.category)
        assertEquals(ToolRisk.Sensitive, metadata.risk)
        assertEquals(Capability.SCREEN_CAPTURE, metadata.capability)
        assertEquals(ToolEffectClass.READ_ONLY, metadata.effectClass)
    }
}
