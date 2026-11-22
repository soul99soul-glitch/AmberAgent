package app.amber.feature.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.ACCESSIBILITY_L1_READ_TOOLS
import app.amber.feature.tools.ACCESSIBILITY_L3_ACTION_TOOLS
import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy
import app.amber.feature.tools.ToolRisk
import app.amber.feature.tools.invocationPolicy
import app.amber.feature.tools.isAccessibilityL1ReadTool
import app.amber.feature.tools.isAccessibilityL3ActionTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Council Resolution mandate for Android accessibility automation:
 * - Cross-app external write actions default to L3 risk and require explicit step-by-step
 *   user confirmation before every step (never silently bypassed by auto-approve, high-risk
 *   auto-approve, run_trust, or CapabilityPolicy.AUTO).
 * - Read-only screen observations and screenshots stay L1 and are not intercepted by the L3 guard.
 * - Approval preview clearly shows L3 risk, target app, action type, and operation summary.
 */
class AccessibilityL3GuardTest {
    private val resolver = PermissionDecisionResolver()

    private fun toolDef(
        name: String,
        needsApproval: Boolean = false,
        allowsAutoApproval: Boolean = true,
    ): Tool = Tool(
        name = name,
        description = name,
        needsApproval = needsApproval,
        allowsAutoApproval = allowsAutoApproval,
        execute = { _: JsonElement -> emptyList() },
    )

    private fun toolCall(
        name: String,
        input: String = "{}",
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        metadata: kotlinx.serialization.json.JsonObject? = null,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "call-$name",
        toolName = name,
        input = input,
        approvalState = approvalState,
        metadata = metadata,
    )

    @Test
    fun accessibilityWriteActionsDefaultToL3AndNeverSilentlySkipStepConfirmation() {
        val writeActions = listOf(
            "screen_click",
            "screen_long_click",
            "screen_swipe",
            "screen_input_text",
            "screen_tap_text",
            "screen_back",
            "screen_home",
            "screen_open_app",
            "screen_open_url",
            "screen_scroll_until",
            "screen_collect_list",
            "screen_run_goal",
            "screen_run_flow",
            "screen_add_app",
        )
        assertEquals(ACCESSIBILITY_L3_ACTION_TOOLS, writeActions.toSet())

        val permissiveCapabilityState = CapabilityPermissionState(
            policies = mapOf(
                Capability.SCREEN_CONTROL to CapabilityPolicy.AUTO,
                Capability.SCREEN_AUTOMATION to CapabilityPolicy.AUTO,
            ),
        )

        for (toolName in writeActions) {
            assertTrue("$toolName must be L3", isAccessibilityL3ActionTool(toolName))
            val def = toolDef(toolName, needsApproval = true, allowsAutoApproval = false)
            val policy = def.invocationPolicy("{}")
            assertEquals("$toolName must be ToolRisk.High", ToolRisk.High, policy.risk)
            assertEquals("$toolName must report L3 riskLevel", "L3", policy.riskLevel)
            assertTrue("$toolName must require approval", policy.needsApproval)
            assertTrue("$toolName must be mandatoryApproval", policy.mandatoryApproval)
            assertTrue("$toolName must be alwaysAsk", policy.alwaysAsk)
            assertFalse("$toolName must not be autoApprovable", policy.autoApprovable)

            // Even with ALL auto-approve switches, run_trust from step 1, and capability AUTO,
            // every step must still ASK until delegation ledger grants authority.
            val decision = resolver.resolve(
                toolDef = def,
                tool = toolCall(toolName),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
                autoApprovedToolNames = setOf(toolName),
                capabilityPermissions = permissiveCapabilityState,
            )
            assertEquals("$toolName must require explicit step confirmation", PermissionDecisionAction.ASK, decision.action)
            assertEquals("accessibility_l3_guard", decision.source)
            assertEquals(
                "L3",
                decision.trace.toJson()["policy"]!!.jsonObject["risk_level"]?.jsonPrimitive?.content,
            )

            // Explicit per-step user approval unlocks only that approved call.
            val approvedDecision = resolver.resolve(
                toolDef = def,
                tool = toolCall(toolName, approvalState = ToolApprovalState.Approved),
                autoApproveTools = false,
                autoApproveHighRiskTools = false,
            )
            assertEquals("$toolName approved step should run", PermissionDecisionAction.ALLOW, approvedDecision.action)

            // Notification / lockscreen surface may never approve L3 accessibility actions.
            assertTrue(
                "$toolName must require in-app approval only",
                AgentLiveStatusNotifier.requiresInAppApprovalOnly(toolCall(toolName)),
            )
        }
    }

    @Test
    fun disabledCapabilityStillDeniesAccessibilityWriteActions() {
        val disabled = CapabilityPermissionState(
            policies = mapOf(Capability.SCREEN_CONTROL to CapabilityPolicy.DISABLED),
        )
        val decision = resolver.resolve(
            toolDef = toolDef("screen_click", needsApproval = true, allowsAutoApproval = false),
            tool = toolCall("screen_click", """{"x":100,"y":200}"""),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            capabilityPermissions = disabled,
        )
        assertEquals(PermissionDecisionAction.DENY, decision.action)
        assertEquals("capability", decision.source)
    }

    @Test
    fun readOnlyScreenObservationsAndScreenshotsRemainL1AndAreNotBlockedByL3Guard() {
        val readOnlyNoApproval = listOf(
            "screen_read_ui",
            "screen_find_text",
            "screen_wait_for_text",
            "screen_status",
            "screen_apps",
            "screen_stop",
        )
        for (toolName in readOnlyNoApproval) {
            assertTrue(isAccessibilityL1ReadTool(toolName))
            assertFalse(isAccessibilityL3ActionTool(toolName))
            val def = toolDef(toolName, needsApproval = false, allowsAutoApproval = true)
            val policy = def.invocationPolicy("{}")
            assertEquals("L1", policy.riskLevel)
            assertFalse(policy.mutates)
            assertFalse(policy.mandatoryApproval)
            assertFalse(policy.alwaysAsk)
            assertFalse(policy.needsApproval)

            val decision = resolver.resolve(
                toolDef = def,
                tool = toolCall(toolName),
                autoApproveTools = false,
                autoApproveHighRiskTools = false,
            )
            assertEquals("$toolName must remain read-only ALLOW", PermissionDecisionAction.ALLOW, decision.action)
        }

        // screen_screenshot stays L1 (not blocked by accessibility L3 guard) and preserves legacy auto-approve behavior.
        assertTrue("screen_screenshot" in ACCESSIBILITY_L1_READ_TOOLS)
        assertFalse(isAccessibilityL3ActionTool("screen_screenshot"))
        val screenshotDef = toolDef("screen_screenshot", needsApproval = true, allowsAutoApproval = true)
        val screenshotPolicy = screenshotDef.invocationPolicy("{}")
        assertEquals("L1", screenshotPolicy.riskLevel)
        assertFalse(screenshotPolicy.mandatoryApproval)
        val screenshotDecision = resolver.resolve(
            toolDef = screenshotDef,
            tool = toolCall("screen_screenshot"),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
            capabilityPermissions = CapabilityPermissionState(),
        )
        assertEquals(PermissionDecisionAction.ALLOW, screenshotDecision.action)
    }

    @Test
    fun foregroundChangeRejectsApprovedGestureBeforeExecution() = kotlinx.coroutines.test.runTest {
        var foreground: String? = "target.app"
        val dispatcher = AgentToolDispatcher(Json, resolver, activeScreenPackage = { foreground })
        val call = dispatcher.captureApprovalTarget(toolCall("screen_click", approvalState = ToolApprovalState.Approved))
        var executions = 0
        val definition = Tool(name = "screen_click", description = "test", execute = {
            executions++
            emptyList()
        })
        suspend fun execute() = dispatcher.execute(call, definition, autoApproveTools = false)
        foreground = "different.app"
        assertTrue(execute()!!.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("approvedTargetChanged"))
        foreground = null
        execute()
        assertEquals(0, executions)
        foreground = "target.app"
        execute()
        assertEquals(1, executions)
    }

    @Test
    fun approvalSummaryClearlyShowsTargetAppActionTypeAndOperationSummary() {
        val clickCall = toolCall(
            name = "screen_click",
            input = """{"x":540,"y":1280,"verify_text":"提交"}""",
            metadata = buildJsonObject {
                put(AccessibilityApprovalSummaryFormatter.METADATA_TARGET_APP_KEY, "com.example.shop")
            },
        )
        val clickSummary = AccessibilityApprovalSummaryFormatter.format(
            tool = clickCall,
            arguments = Json.parseToJsonElement(clickCall.input),
            resolveAppLabel = { pkg -> if (pkg == "com.example.shop") "示例商城" else null },
        )
        assertNotNull(clickSummary)
        assertEquals("L3", clickSummary!!.riskLevel)
        assertEquals("示例商城 (com.example.shop)", clickSummary.targetApp)
        assertTrue(clickSummary.actionType.contains("点击坐标"))
        assertTrue(clickSummary.operationSummary.contains("540"))
        assertTrue(clickSummary.operationSummary.contains("1280"))
        assertTrue(clickSummary.operationSummary.contains("提交"))

        val inputCall = toolCall(
            name = "screen_input_text",
            input = """{"text":"帮我查订单"}""",
            metadata = buildJsonObject { put(AccessibilityApprovalSummaryFormatter.METADATA_TARGET_APP_KEY, "com.tencent.mm") },
        )
        val inputSummary = AccessibilityApprovalSummaryFormatter.format(
            tool = inputCall,
            arguments = Json.parseToJsonElement(inputCall.input),
            resolveAppLabel = { "微信" },
        )
        assertNotNull(inputSummary)
        assertEquals("微信 (com.tencent.mm)", inputSummary!!.targetApp)
        assertTrue(inputSummary.actionType.contains("输入文字"))
        assertTrue(inputSummary.operationSummary.contains("5 个字符"))
        assertFalse(inputSummary.operationSummary.contains("帮我查订单"))

        val typedCode = AccessibilityApprovalSummaryFormatter.format(
            tool = inputCall,
            arguments = Json.parseToJsonElement("""{"text":"235913"}"""),
        )!!
        assertFalse(typedCode.operationSummary.contains("235913"))

        // Read-only L1 screen tool produces no L3 summary card.
        val readSummary = AccessibilityApprovalSummaryFormatter.format(
            tool = toolCall("screen_read_ui", "{}"),
            arguments = Json.parseToJsonElement("{}"),
        )
        assertNull(readSummary)

        // screen_open_url shows only host, never userinfo/path/query tokens.
        val urlCall = toolCall(
            name = "screen_open_url",
            input = """{"url":"https://user:secret@pay.example.com/checkout?token=abc123&next=/ok"}""",
        )
        val urlSummary = AccessibilityApprovalSummaryFormatter.format(
            tool = urlCall,
            arguments = Json.parseToJsonElement(urlCall.input),
        )!!
        assertTrue(urlSummary.operationSummary.contains("pay.example.com"))
        assertFalse(urlSummary.operationSummary.contains("secret"))
        assertFalse(urlSummary.operationSummary.contains("checkout"))
        assertFalse(urlSummary.operationSummary.contains("abc123"))

        // Malformed URL with spaces still recovers only host without leaking query.
        assertEquals(
            "docs.example.org",
            AccessibilityApprovalSummaryFormatter.extractUrlHostForDisplay("https://docs.example.org/private?q=hello world"),
        )

        // screen_run_flow type counts raw character length without exposing input text.
        val flowCall = toolCall(
            name = "screen_run_flow",
            input = """{"package_name":"com.example.chat","steps":[{"operation":"type","target":"输入框","text":" 1234 "}]}""",
        )
        val flowSummary = AccessibilityApprovalSummaryFormatter.format(
            tool = flowCall,
            arguments = Json.parseToJsonElement(flowCall.input),
        )!!
        assertTrue(flowSummary.operationSummary.contains("6 个字符"))
        assertFalse(flowSummary.operationSummary.contains("1234"))
    }
}
