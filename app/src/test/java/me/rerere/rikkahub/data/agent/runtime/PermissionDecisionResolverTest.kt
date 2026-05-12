package me.rerere.rikkahub.data.agent.runtime

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDecisionResolverTest {
    private val resolver = PermissionDecisionResolver()

    @Test
    fun traceExplainsCronAutoApproval() {
        val decision = resolver.resolve(
            toolDef = approvalTool("cron_task_create"),
            tool = toolCall("cron_task_create"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        assertEquals("settings", decision.source)
        assertEquals("cron_task_create", decision.trace.toolName)
        assertTrue(decision.trace.autoApproveTools)
        assertFalse(decision.trace.autoApproveHighRiskTools)
    }

    @Test
    fun traceExplainsHighRiskHold() {
        val decision = resolver.resolve(
            toolDef = approvalTool("sms_send", allowsAutoApproval = false),
            tool = toolCall("sms_send"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("risk", decision.source)
        assertEquals("high", decision.trace.policy!!.risk.name.lowercase())
    }

    @Test
    fun subAgentCannotSilentlyUseSensitiveTool() {
        val decision = resolver.resolve(
            toolDef = approvalTool("screen_tap"),
            tool = toolCall("screen_tap"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            invocationContext = ToolInvocationContext.SubAgent,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("subagent", decision.source)
    }

    @Test
    fun mandatoryApprovalCanBeBypassedByExplicitHighRiskAutoApprove() {
        // The high-risk toggle is intentionally the broad "run unattended"
        // switch. mandatoryApproval still blocks regular auto approval and
        // run-trust, but users who enable both toggles have opted into it.
        val decision = resolver.resolve(
            toolDef = mandatoryTool("wm_eval"),
            tool = toolCall("wm_eval"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
        )

        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        assertEquals("settings_high_risk_mandatory", decision.source)
        assertTrue(decision.trace.policy!!.mandatoryApproval)
    }

    @Test
    fun mandatoryApprovalStillRequiresPromptWithRegularAutoApproveOnly() {
        val decision = resolver.resolve(
            toolDef = mandatoryTool("wm_eval"),
            tool = toolCall("wm_eval"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("mandatory_approval", decision.source)
        assertTrue(decision.trace.policy!!.mandatoryApproval)
    }

    @Test
    fun mandatoryApprovalOverridesPriorRunTrust() {
        // Even if the user previously approved this tool earlier in the same
        // run ("trust this tool for the rest of this conversation"), a
        // mandatoryApproval tool must re-ask per invocation unless the
        // user has enabled the explicit high-risk auto-approval override.
        val decision = resolver.resolve(
            toolDef = mandatoryTool("wm_eval"),
            tool = toolCall("wm_eval"),
            autoApproveTools = false,
            autoApproveHighRiskTools = false,
            autoApprovedToolNames = setOf("wm_eval"),
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("mandatory_approval", decision.source)
    }

    @Test
    fun externalCliCouncilSeatCanUseHighRiskAutoApproval() {
        val decision = resolver.resolve(
            toolDef = approvalTool("model_council_start", allowsAutoApproval = false),
            tool = toolCall(
                name = "model_council_start",
                input = """
                    {
                      "allow_external_cli": true,
                      "planned_seats": [
                        {
                          "name": "Gemini CLI",
                          "runner_type": "external_cli",
                          "external_tool": "gemini_cli"
                        }
                      ]
                    }
                """.trimIndent(),
            ),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
        )

        val policy = decision.trace.policy!!
        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        assertEquals("settings_high_risk_mandatory", decision.source)
        assertEquals(ToolRisk.Sensitive, policy.risk)
        assertTrue(policy.mandatoryApproval)
    }

    private fun approvalTool(name: String, allowsAutoApproval: Boolean = true) = Tool(
        name = name,
        description = "",
        needsApproval = true,
        allowsAutoApproval = allowsAutoApproval,
        execute = { emptyList() },
    )

    private fun mandatoryTool(name: String) = Tool(
        name = name,
        description = "",
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { emptyList() },
    )

    private fun toolCall(name: String, input: String = "{}") = UIMessagePart.Tool(
        toolCallId = "call_$name",
        toolName = name,
        input = input,
    )
}
