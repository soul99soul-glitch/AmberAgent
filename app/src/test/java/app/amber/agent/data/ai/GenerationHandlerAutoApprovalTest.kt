package app.amber.core.ai

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.PermissionDecisionResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerAutoApprovalTest {
    private val resolver = PermissionDecisionResolver()
    @Test
    fun pausesForApprovalWhenAutoApproveIsDisabled() {
        assertTrue(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("terminal_execute"),
                tool = toolCall("terminal_execute"),
                autoApproveTools = false,
            )
        )
    }

    @Test
    fun bypassesApprovalForRegularToolsWhenAutoApproveIsEnabled() {
        assertFalse(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("safe_lookup"),
                tool = toolCall("safe_lookup"),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun terminalExecuteStillPausesWithRegularAutoApprove() {
        assertTrue(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("terminal_execute", allowsAutoApproval = false),
                tool = toolCall("terminal_execute"),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun keepsHighRiskToolsManualWhenRegularAutoApproveIsEnabled() {
        assertTrue(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("http_request", allowsAutoApproval = false),
                tool = toolCall("http_request", """{"method":"POST"}"""),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun bypassesHighRiskToolsOnlyWhenHighRiskAutoApproveIsEnabled() {
        assertFalse(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("http_request", allowsAutoApproval = false),
                tool = toolCall("http_request", """{"method":"POST"}"""),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
            )
        )
    }

    @Test
    fun alwaysAskToolsPauseEvenWithHighRiskAutoApproveEnabled() {
        assertTrue(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("sms_send", allowsAutoApproval = false),
                tool = toolCall("sms_send"),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
            )
        )
    }

    @Test
    fun keepsAskUserAsManualApprovalEvenWhenAutoApproveIsEnabled() {
        assertTrue(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("ask_user"),
                tool = toolCall("ask_user"),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun neverPausesForToolsThatDoNotNeedApproval() {
        assertFalse(
            resolver.shouldPauseForApproval(
                toolDef = Tool(
                    name = "file_read",
                    description = "",
                    needsApproval = false,
                    execute = { emptyList() },
                ),
                tool = toolCall("file_read"),
                autoApproveTools = false,
            )
        )
    }

    @Test
    fun httpGetAndHeadDoNotPauseEvenWhenHttpToolRequiresApproval() {
        assertFalse(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("http_request", allowsAutoApproval = false),
                tool = toolCall("http_request", """{"method":"GET","url":"https://example.com"}"""),
                autoApproveTools = false,
            )
        )
        assertFalse(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("http_request", allowsAutoApproval = false),
                tool = toolCall("http_request", """{"method":"HEAD","url":"https://example.com"}"""),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun httpMutatingMethodsAlwaysPause() {
        listOf("POST", "PUT", "PATCH", "DELETE").forEach { method ->
            assertTrue(
                resolver.shouldPauseForApproval(
                    toolDef = approvalTool("http_request", allowsAutoApproval = false),
                    tool = toolCall("http_request", """{"method":"$method","url":"https://example.com"}"""),
                    autoApproveTools = true,
                )
            )
        }
    }

    @Test
    fun memoryToolReadOperationsDoNotPause() {
        assertFalse(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("memory_tool", allowsAutoApproval = false),
                tool = toolCall("memory_tool", """{"operation":"search","query":"Q代"}"""),
                autoApproveTools = false,
            )
        )
    }

    @Test
    fun memoryToolWriteOperationsPause() {
        assertTrue(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("memory_tool", allowsAutoApproval = false),
                tool = toolCall("memory_tool", """{"operation":"delete","id":"memory-1"}"""),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun cronCreateCannotUseGlobalAutoApproval() {
        assertTrue(
            resolver.shouldPauseForApproval(
                toolDef = approvalTool("cron_task_create", allowsAutoApproval = false),
                tool = toolCall("cron_task_create", """{"prompt":"daily brief","cron_expression":"30 8 * * *"}"""),
                autoApproveTools = true,
            )
        )
    }

    private fun approvalTool(name: String, allowsAutoApproval: Boolean = true) = Tool(
        name = name,
        description = "",
        needsApproval = true,
        allowsAutoApproval = allowsAutoApproval,
        execute = { emptyList() },
    )

    private fun toolCall(name: String, input: String = "{}") = UIMessagePart.Tool(
        toolCallId = "call_$name",
        toolName = name,
        input = input,
    )
}
