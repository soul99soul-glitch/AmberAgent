package app.amber.feature.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.modelcouncil.ExternalCliToolRegistry
import app.amber.feature.tools.EXTERNAL_CLI_COUNCIL_RUNNER_TYPES
import app.amber.feature.tools.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDecisionResolverTest {
    private val resolver = PermissionDecisionResolver()

    @Test
    fun traceExplainsCronApprovalHold() {
        val decision = resolver.resolve(
            toolDef = approvalTool("cron_task_create", allowsAutoApproval = false),
            tool = toolCall("cron_task_create"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("ui", decision.source)
        assertEquals("cron_task_create", decision.trace.toolName)
        assertTrue(decision.trace.autoApproveTools)
        assertFalse(decision.trace.autoApproveHighRiskTools)
        assertFalse(decision.trace.policy!!.autoApprovable)
    }

    @Test
    fun traceExplainsAlwaysAskHold() {
        val decision = resolver.resolve(
            toolDef = approvalTool("sms_send", allowsAutoApproval = false),
            tool = toolCall("sms_send"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("always_ask", decision.source)
        val policy = decision.trace.policy!!
        assertEquals("high", policy.risk.name.lowercase())
        assertTrue(policy.alwaysAsk)
    }

    @Test
    fun highRiskToolStillHoldsWithoutHighRiskAutoApproval() {
        val decision = resolver.resolve(
            toolDef = approvalTool("http_request", allowsAutoApproval = false),
            tool = toolCall("http_request", """{"method":"POST"}"""),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("risk", decision.source)
        assertEquals(ToolRisk.High, decision.trace.policy!!.risk)
    }

    @Test
    fun writeActionToolFamiliesAskButReadOnlySiblingsAllowWithRegularAutoApproval() {
        val writeOrActionTools = listOf(
            "terminal_execute",
            "terminal_job_start",
            "terminal_job_stop",
            "terminal_install_packages",
            "terminal_workspace_flush",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_session_stop",
            "screen_click",
            "screen_long_click",
            "screen_swipe",
            "screen_input_text",
            "screen_open_app",
            "screen_open_url",
            "screen_tap_text",
            "screen_scroll_until",
            "icloud_write",
        )
        writeOrActionTools.forEach { toolName ->
            val decision = resolver.resolve(
                toolDef = approvalTool(toolName, allowsAutoApproval = false),
                tool = toolCall(toolName),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
            )

            assertEquals("$toolName should still ask", PermissionDecisionAction.ASK, decision.action)
            assertFalse("$toolName should not be auto-approvable", decision.trace.policy!!.autoApprovable)
        }

        val readOnlyTools = listOf(
            "terminal_job_read",
            "terminal_job_wait",
            "terminal_session_read",
            "screen_read_ui",
            "screen_find_text",
            "screen_screenshot",
            "screen_wait_for_text",
            "icloud_status",
            "icloud_list",
            "icloud_stat",
            "icloud_read",
            "icloud_search",
        )
        readOnlyTools.forEach { toolName ->
            val decision = resolver.resolve(
                toolDef = readOnlyTool(toolName),
                tool = toolCall(toolName),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
            )

            assertEquals("$toolName should stay read-only", PermissionDecisionAction.ALLOW, decision.action)
            assertFalse("$toolName should not need approval", decision.trace.policy!!.needsApproval)
        }
    }

    @Test
    fun implicitMutatingOrDangerousCategoryToolsFailClosedForAutoApproval() {
        val mutatingName = resolver.resolve(
            toolDef = approvalTool("adapter_create", allowsAutoApproval = true),
            tool = toolCall("adapter_create"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )
        val updateName = resolver.resolve(
            toolDef = approvalTool("adapter_update", allowsAutoApproval = true),
            tool = toolCall("adapter_update"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )
        val cloudCategory = resolver.resolve(
            toolDef = approvalTool("icloud_status", allowsAutoApproval = true),
            tool = toolCall("icloud_status"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ASK, mutatingName.action)
        val mutatingNamePolicy = mutatingName.trace.policy!!
        assertFalse(mutatingNamePolicy.autoApprovable)
        assertEquals(PermissionDecisionAction.ASK, updateName.action)
        val updateNamePolicy = updateName.trace.policy!!
        assertTrue(updateNamePolicy.mutates)
        assertFalse(updateNamePolicy.autoApprovable)
        assertEquals(PermissionDecisionAction.ASK, cloudCategory.action)
        val cloudCategoryPolicy = cloudCategory.trace.policy!!
        assertEquals("cloud", cloudCategoryPolicy.category)
        assertFalse(cloudCategoryPolicy.autoApprovable)
    }

    @Test
    fun runPlanUpdateKeepsInternalReadOnlyPolicyDespiteUpdateToken() {
        val decision = resolver.resolve(
            toolDef = readOnlyTool("run_plan_update"),
            tool = toolCall("run_plan_update"),
            autoApproveTools = false,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        val policy = decision.trace.policy!!
        assertFalse(policy.mutates)
        assertFalse(policy.needsApproval)
    }

    @Test
    fun missingToolExplainsToolSearchRecovery() {
        val decision = resolver.resolve(
            toolDef = null,
            tool = toolCall("screen_screenshot"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.DENY, decision.action)
        assertEquals("tool_lookup", decision.source)
        assertTrue(decision.reason.contains("tool_search"))
        assertTrue(decision.reason.contains("screen_screenshot"))
    }

    @Test
    fun subAgentCanUseHighRiskAutoApprovalWhenExplicitlyEnabled() {
        val decision = resolver.resolve(
            toolDef = approvalTool("http_request"),
            tool = toolCall("http_request", """{"method":"POST"}"""),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            invocationContext = ToolInvocationContext.SubAgent,
        )

        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        assertEquals("settings_high_risk_subagent", decision.source)
    }

    @Test
    fun subAgentStartUsesGlobalAutoApprovalInMainContext() {
        val decision = resolver.resolve(
            toolDef = readOnlyTool("subagent_start"),
            tool = toolCall("subagent_start"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )

        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        assertEquals("settings", decision.source)
        val policy = decision.trace.policy!!
        assertEquals("subagent", policy.category)
        assertTrue(policy.mutates)
        assertTrue(policy.needsApproval)
        assertTrue(policy.autoApprovable)
        assertEquals(ToolRisk.Normal, policy.risk)
    }

    @Test
    fun subAgentCannotSilentlyStartNestedSubAgent() {
        val decision = resolver.resolve(
            toolDef = readOnlyTool("subagent_start"),
            tool = toolCall("subagent_start"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            invocationContext = ToolInvocationContext.SubAgent,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("subagent", decision.source)
    }

    @Test
    fun subAgentSensitiveToolStillAsksEvenWithHighRiskAutoApproval() {
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
    fun subAgentHistoryReadRequiresGrantForSilentApproval() {
        val decision = resolver.resolve(
            toolDef = approvalTool("session_read"),
            tool = toolCall("session_read", """{"session_id":"session-1"}"""),
            autoApproveTools = false,
            autoApproveHighRiskTools = false,
            invocationContext = ToolInvocationContext.SubAgent,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("subagent", decision.source)
    }

    @Test
    fun subAgentHistoryReadWithGrantIsSilentlyApproved() {
        val decision = resolver.resolve(
            toolDef = approvalTool("session_read"),
            tool = toolCall("session_read", """{"session_id":"session-1","grant_id":"grant-1"}"""),
            autoApproveTools = false,
            autoApproveHighRiskTools = false,
            invocationContext = ToolInvocationContext.SubAgent,
        )

        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        assertEquals("subagent_history", decision.source)
    }

    @Test
    fun subAgentStillNeedsApprovalWithoutHighRiskAutoApproval() {
        val decision = resolver.resolve(
            toolDef = approvalTool("http_request"),
            tool = toolCall("http_request", """{"method":"POST"}"""),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
            invocationContext = ToolInvocationContext.SubAgent,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("subagent", decision.source)
    }

    @Test
    fun subAgentCloudToolStillAsksEvenWithHighRiskAutoApproval() {
        val decision = resolver.resolve(
            toolDef = approvalTool("icloud_status"),
            tool = toolCall("icloud_status"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            invocationContext = ToolInvocationContext.SubAgent,
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("subagent", decision.source)
        assertEquals("cloud", decision.trace.policy!!.category)
    }

    @Test
    fun directPhoneCallAlwaysAsksButDialerCanUseHighRiskAutoApproval() {
        val directCall = resolver.resolve(
            toolDef = approvalTool("call_phone", allowsAutoApproval = false),
            tool = toolCall("call_phone", """{"phone_number":"123","direct_call":true}"""),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
        )
        val dialer = resolver.resolve(
            toolDef = approvalTool("call_phone", allowsAutoApproval = false),
            tool = toolCall("call_phone", """{"phone_number":"123","direct_call":false}"""),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
        )

        assertEquals(PermissionDecisionAction.ASK, directCall.action)
        assertEquals("always_ask", directCall.source)
        assertTrue(directCall.trace.policy!!.alwaysAsk)
        assertEquals(PermissionDecisionAction.ALLOW, dialer.action)
        assertEquals("settings_high_risk", dialer.source)
        assertFalse(dialer.trace.policy!!.alwaysAsk)
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

    @Test
    fun externalCliRunnerTypeGuardStaysInSyncWithRegistry() {
        assertTrue(EXTERNAL_CLI_COUNCIL_RUNNER_TYPES.contains("external_cli"))
        assertTrue(EXTERNAL_CLI_COUNCIL_RUNNER_TYPES.contains("cli"))
        assertTrue(EXTERNAL_CLI_COUNCIL_RUNNER_TYPES.containsAll(ExternalCliToolRegistry.supportedToolIds))

        (setOf("external_cli", "cli") + ExternalCliToolRegistry.supportedToolIds).forEach { runnerType ->
            val decision = resolver.resolve(
                toolDef = approvalTool("model_council_start", allowsAutoApproval = false),
                tool = toolCall(
                    name = "model_council_start",
                    input = """
                        {
                          "planned_seats": [
                            {
                              "name": "External CLI",
                              "runner_type": "$runnerType"
                            }
                          ]
                        }
                    """.trimIndent(),
                ),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
            )

            val policy = decision.trace.policy!!
            assertEquals(PermissionDecisionAction.ASK, decision.action)
            assertEquals(ToolRisk.Sensitive, policy.risk)
            assertTrue(policy.mandatoryApproval)
        }
    }

    private fun approvalTool(name: String, allowsAutoApproval: Boolean = true) = Tool(
        name = name,
        description = "",
        needsApproval = true,
        allowsAutoApproval = allowsAutoApproval,
        execute = { emptyList() },
    )

    private fun readOnlyTool(name: String) = Tool(
        name = name,
        description = "",
        needsApproval = false,
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
