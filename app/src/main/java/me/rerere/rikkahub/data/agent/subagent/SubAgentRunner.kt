package me.rerere.rikkahub.data.agent.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant

interface SubAgentRunner {
    /**
     * Run a subagent task. [liveText] receives the running assistant text as it streams in;
     * UI code can subscribe via [SubAgentManager.liveTextFlow] to render real-time output.
     * Pass a no-op flow if you don't need live updates.
     */
    suspend fun run(
        settings: Settings,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        tools: List<Tool>,
        liveText: MutableStateFlow<String>,
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
        liveText: MutableStateFlow<String>,
    ): SubAgentResult {
        // Per-role model: explicit override → fallback to current chat model.
        // If the user removed/renamed the configured model after saving the override,
        // fall through silently rather than failing the run.
        val model = definition.modelId?.let { settings.findModelById(it) }
            ?: settings.getCurrentChatModel()
            ?: error("Current chat model is not configured")
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
                // Stream the assistant's accumulated content to subscribers (UI live view).
                // Include reasoning so the user has something to watch during the (often long)
                // thinking phase — UIMessage.toText() skips Reasoning parts entirely, which would
                // leave the sheet blank for reasoning-heavy models like deepseek-v4-pro.
                val assistantContent = latest.lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?.let(::renderAssistantContentForLive)
                    .orEmpty()
                if (assistantContent != liveText.value) liveText.value = assistantContent
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
        // Final write to liveText so a sheet opened/refreshed AFTER completion still shows the
        // canonical answer (the in-loop renderer above includes reasoning prefix; once we know
        // the run finished cleanly the user wants the clean text only).
        if (text.isNotBlank()) liveText.value = text
        return SubAgentResult(
            status = SubAgentRunStatus.COMPLETED,
            summary = text.ifBlank { "Subagent completed without text output." },
        )
    }

    /**
     * Render an in-flight assistant message for the live-view sheet. Reasoning is shown above
     * the answer in a Markdown blockquote so the user sees progress during long reasoning
     * phases (deepseek-v4-pro, gpt-5 high, claude opus etc. can sit in reasoning for many
     * seconds before producing any visible text).
     */
    private fun renderAssistantContentForLive(message: UIMessage): String {
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
            .joinToString("\n") { it.reasoning }
            .trim()
        val text = message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (reasoning.isBlank() && text.isBlank()) return ""
        return buildString {
            if (reasoning.isNotBlank()) {
                append("> 💭 ")
                append(reasoning.replace("\n", "\n> "))
                if (text.isNotBlank()) append("\n\n")
            }
            if (text.isNotBlank()) append(text)
        }
    }

    private fun Assistant.toSubAgentAssistant(definition: SubAgentDefinition) = copy(
        name = definition.name,
        systemPrompt = definition.systemPrompt,
        // streamOutput must be true so GenerationHandler emits per-token Messages chunks.
        // Without it the underlying provider buffers the whole response and we get just one
        // chunk at the end — UI live view stays empty until the run finishes, then jumps
        // straight to the final text. Cost: per-chunk transformer pass, negligible.
        streamOutput = true,
        contextMessageSize = 0,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        localTools = emptyList(),
        enabledSkills = emptySet(),
        temperature = definition.temperature ?: temperature,
        reasoningLevel = definition.reasoningLevel ?: reasoningLevel,
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
