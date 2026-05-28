package app.amber.core.context

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart

object PreparedContextEditor {
    fun edit(
        messages: List<UIMessage>,
        keepRecentMessages: Int,
    ): PreparedContextEditResult {
        val originalTokens = ConversationContextPlanner.estimateTokens(messages)
        val trim = applyStage(
            stage = "trim",
            reason = "long historical tool results are trimmed before compaction planning",
            messages = messages,
        ) { message, index ->
            editMessageTools(message, index, messages.size, keepRecentMessages, ::trimToolResult)
        }
        val clear = applyStage(
            stage = "clear",
            reason = "retriable historical tool results are replaced with placeholders",
            messages = trim.messages,
        ) { message, index ->
            editMessageTools(message, index, trim.messages.size, keepRecentMessages, ::clearToolResult)
        }
        return PreparedContextEditResult(
            messages = clear.messages,
            trace = ContextPreparationTrace(
                originalTokenEstimate = originalTokens,
                finalTokenEstimate = ConversationContextPlanner.estimateTokens(clear.messages),
                steps = listOf(trim.trace, clear.trace),
            )
        )
    }

    private fun applyStage(
        stage: String,
        reason: String,
        messages: List<UIMessage>,
        transform: (UIMessage, Int) -> UIMessage,
    ): StageResult {
        val before = ConversationContextPlanner.estimateTokens(messages)
        var changed = 0
        val edited = messages.mapIndexed { index, message ->
            val next = transform(message, index)
            if (next != message) changed++
            next
        }
        val after = ConversationContextPlanner.estimateTokens(edited)
        return StageResult(
            messages = edited,
            trace = ContextPreparationStepTrace(
                stage = stage,
                reason = reason,
                beforeTokens = before,
                afterTokens = after,
                savedTokens = (before - after).coerceAtLeast(0),
                changedMessages = changed,
            )
        )
    }

    private fun editMessageTools(
        message: UIMessage,
        index: Int,
        messageCount: Int,
        keepRecentMessages: Int,
        transform: (UIMessagePart.Tool) -> UIMessagePart.Tool,
    ): UIMessage {
        if (index >= messageCount - keepRecentMessages.coerceAtLeast(0)) return message
        if (message.hasMultimodalPart()) return message
        var changed = false
        val parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                val next = transform(part)
                if (next != part) changed = true
                next
            } else {
                part
            }
        }
        return if (changed) message.copy(parts = parts) else message
    }

    private fun trimToolResult(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (!tool.canEditPreparedResult()) return tool
        val outputChars = tool.output.outputChars()
        if (outputChars <= TRIM_TOOL_RESULT_AFTER_CHARS) return tool
        val preview = ToolResultCompactor.summarize(tool.output, TRIMMED_TOOL_RESULT_CHARS)
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "trimmed_tool_result")
                        put("tool_name", tool.toolName)
                        put("tool_call_id", tool.toolCallId)
                        put("original_output_chars", outputChars)
                        put("preview", preview)
                    }.toString()
                )
            )
        )
    }

    private fun clearToolResult(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (!tool.canEditPreparedResult()) return tool
        if (!tool.safeToClearPreparedResult()) return tool
        val outputChars = tool.output.outputChars()
        if (outputChars <= CLEAR_TOOL_RESULT_AFTER_CHARS) return tool
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "cleared_tool_result")
                        put("tool_name", tool.toolName)
                        put("tool_call_id", tool.toolCallId)
                        put("input_chars", tool.input.length)
                        put("original_output_chars", outputChars)
                        put("reason", "Historical result was cleared from prepared context only. Original conversation storage is unchanged; call the tool again or expand history if exact output is needed.")
                    }.toString()
                )
            )
        )
    }

    private fun UIMessagePart.Tool.canEditPreparedResult(): Boolean =
        isExecuted &&
            approvalState !is ToolApprovalState.Pending &&
            !output.containsMultimodalPart() &&
            !output.looksFailedOrDenied()

    private fun UIMessagePart.Tool.safeToClearPreparedResult(): Boolean =
        toolName in CLEARABLE_TOOL_NAMES ||
            toolName.startsWith("conversation_") ||
            toolName.startsWith("session_") && toolName !in SENSITIVE_SESSION_TOOLS

    private fun UIMessage.hasMultimodalPart(): Boolean =
        parts.containsMultimodalPart()

    private fun List<UIMessagePart>.containsMultimodalPart(): Boolean =
        any { part ->
            when (part) {
                is UIMessagePart.Image,
                is UIMessagePart.Video,
                is UIMessagePart.Audio,
                is UIMessagePart.Document -> true
                is UIMessagePart.Tool -> part.output.containsMultimodalPart()
                else -> false
            }
        }

    private fun List<UIMessagePart>.looksFailedOrDenied(): Boolean =
        filterIsInstance<UIMessagePart.Text>().any { part ->
            val text = part.text
            text.contains("\"status\":\"failed\"", ignoreCase = true) ||
                text.contains("\"status\":\"denied\"", ignoreCase = true) ||
                text.contains("\"approval_required\"", ignoreCase = true) ||
                text.contains("\"error\"", ignoreCase = true)
        }

    private fun List<UIMessagePart>.outputChars(): Int =
        sumOf { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.length
                is UIMessagePart.Reasoning -> part.reasoning.length
                is UIMessagePart.Tool -> part.input.length + part.output.outputChars()
                else -> part.toString().length
            }
        }

    private data class StageResult(
        val messages: List<UIMessage>,
        val trace: ContextPreparationStepTrace,
    )

    private const val TRIM_TOOL_RESULT_AFTER_CHARS = 16_000
    private const val TRIMMED_TOOL_RESULT_CHARS = 8_000
    private const val CLEAR_TOOL_RESULT_AFTER_CHARS = 2_000

    private val CLEARABLE_TOOL_NAMES = setOf(
        "file_list",
        "file_read",
        "file_search",
        "tools_list",
        "tool_search",
        "tool_policy_explain",
        "conversation_context_status",
        "conversation_search",
        "conversation_expand",
        "agent_runtime_status",
        "agent_task_list",
        "agent_task_read",
        "mcp_list",
    )

    private val SENSITIVE_SESSION_TOOLS = setOf("session_read", "session_expand")
}

data class PreparedContextEditResult(
    val messages: List<UIMessage>,
    val trace: ContextPreparationTrace,
)
