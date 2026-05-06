package me.rerere.rikkahub.data.agent.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant

interface SubAgentRunner {
    suspend fun run(
        settings: Settings,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        tools: List<Tool>,
    ): SubAgentResult
}

class GenerationSubAgentRunner(
    private val generationHandler: GenerationHandler,
) : SubAgentRunner {
    override suspend fun run(
        settings: Settings,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        tools: List<Tool>,
    ): SubAgentResult {
        val model = settings.getCurrentChatModel() ?: error("Current chat model is not configured")
        val assistant = settings.getCurrentAssistant().toSubAgentAssistant(definition)
        val messages = listOf(UIMessage.user(buildTaskPrompt(definition, task)))
        var latest = messages

        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            assistant = assistant,
            memories = emptyList(),
            tools = tools,
            maxSteps = definition.maxTurns,
            processingStatus = MutableStateFlow("SubAgent ${definition.id}"),
            conversation = null,
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) {
                latest = chunk.messages
            }
        }

        val pendingTool = latest.lastOrNull()?.getTools()
            ?.firstOrNull { it.approvalState is ToolApprovalState.Pending }
        if (pendingTool != null) {
            return SubAgentResult(
                status = SubAgentRunStatus.APPROVAL_REQUIRED,
                summary = "Subagent requested approval for ${pendingTool.toolName}.",
                risks = listOf("Subagent cannot self-approve sensitive tools."),
                recommendedNextSteps = listOf("Main agent should decide whether to ask the user for approval in the parent conversation."),
            )
        }

        val text = latest.lastOrNull()?.toText().orEmpty().take(definition.outputBudgetChars)
        return SubAgentResult(
            status = SubAgentRunStatus.COMPLETED,
            summary = text.ifBlank { "Subagent completed without text output." },
        )
    }

    private fun Assistant.toSubAgentAssistant(definition: SubAgentDefinition) = copy(
        name = definition.name,
        systemPrompt = definition.systemPrompt,
        streamOutput = false,
        contextMessageSize = 0,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        localTools = emptyList(),
        enabledSkills = emptySet(),
    )

    private fun buildTaskPrompt(definition: SubAgentDefinition, task: SubAgentTaskSpec): String = """
        You are running as subagent `${definition.id}`.

        Objective:
        ${task.objective}

        Output format:
        ${task.outputFormat}

        Tools and sources guidance:
        ${task.toolsAndSources}

        Boundaries:
        ${task.boundaries}

        Context from parent:
        ${task.context.ifBlank { "(none)" }}

        ${historyGrantPrompt(task)}

        Return only the useful result for the supervisor. Do not ask the user follow-up questions.
    """.trimIndent()

    private fun historyGrantPrompt(task: SubAgentTaskSpec): String {
        if (task.sessionGrantId.isBlank() && task.sourceSessionIds.isEmpty() && task.historyQuery.isBlank()) {
            return ""
        }
        return """
            Historical session scope:
            - session_grant_id: ${task.sessionGrantId.ifBlank { "(none)" }}
            - source_session_ids: ${task.sourceSessionIds.joinToString(", ").ifBlank { "(none)" }}
            - history_query: ${task.historyQuery.ifBlank { "(none)" }}
            - shard: ${task.shardIndex + 1}/${task.shardCount.coerceAtLeast(1)}

            When using session_read or session_expand, include the session_grant_id in the tool input.
            Keep source_message_ids in your evidence whenever the tool returns them.
        """.trimIndent()
    }
}
