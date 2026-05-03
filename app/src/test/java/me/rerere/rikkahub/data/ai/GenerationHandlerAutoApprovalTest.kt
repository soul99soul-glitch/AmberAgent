package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerAutoApprovalTest {
    @Test
    fun pausesForApprovalWhenAutoApproveIsDisabled() {
        assertTrue(
            shouldPauseForToolApproval(
                toolDef = approvalTool("terminal_execute"),
                tool = toolCall("terminal_execute"),
                autoApproveTools = false,
            )
        )
    }

    @Test
    fun bypassesApprovalForRegularToolsWhenAutoApproveIsEnabled() {
        assertFalse(
            shouldPauseForToolApproval(
                toolDef = approvalTool("terminal_execute"),
                tool = toolCall("terminal_execute"),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun keepsHighRiskToolsManualWhenRegularAutoApproveIsEnabled() {
        assertTrue(
            shouldPauseForToolApproval(
                toolDef = approvalTool("sms_send", allowsAutoApproval = false),
                tool = toolCall("sms_send"),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun bypassesHighRiskToolsOnlyWhenHighRiskAutoApproveIsEnabled() {
        assertFalse(
            shouldPauseForToolApproval(
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
            shouldPauseForToolApproval(
                toolDef = approvalTool("ask_user"),
                tool = toolCall("ask_user"),
                autoApproveTools = true,
            )
        )
    }

    @Test
    fun neverPausesForToolsThatDoNotNeedApproval() {
        assertFalse(
            shouldPauseForToolApproval(
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
