package me.rerere.rikkahub.data.agent.runtime

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
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

    private fun approvalTool(name: String, allowsAutoApproval: Boolean = true) = Tool(
        name = name,
        description = "",
        needsApproval = true,
        allowsAutoApproval = allowsAutoApproval,
        execute = { emptyList() },
    )

    private fun toolCall(name: String) = UIMessagePart.Tool(
        toolCallId = "call_$name",
        toolName = name,
        input = "{}",
    )
}
