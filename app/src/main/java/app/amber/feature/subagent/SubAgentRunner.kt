package app.amber.feature.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationHandler
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.settings.getCurrentChatModel
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
        val isolatedSettings = settings.toIsolatedSubAgentSettings()
        // Per-role model: explicit override → fallback to current chat model.
        // If the user removed/renamed the configured model after saving the override,
        // fall through silently rather than failing the run.
        val model = definition.modelId?.let { settings.findModelById(it) }
            ?: settings.getCurrentChatModel()
            ?: error("Current chat model is not configured")
        val assistant = settings.getCurrentAssistant().toIsolatedSubAgentAssistant(definition)
        val messages = listOf(UIMessage.user(buildTaskPrompt(definition, task)))
        val reportCapture = SubAgentReportCapture()
        var latest = messages
        var supervisorDisplayText = ""

        generationHandler.generateText(
            settings = isolatedSettings,
            model = model,
            messages = messages,
            assistant = assistant,
            memories = emptyList(),
            tools = tools + reportCapture.tool(),
            maxSteps = definition.maxTurns,
            processingStatus = MutableStateFlow("SubAgent ${definition.id}"),
            autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls,
            autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
            invocationContext = ToolInvocationContext.SubAgent,
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

        supervisorDisplayText = renderAssistantTranscriptForDisplay(latest).take(definition.outputBudgetChars)
        if (supervisorDisplayText.isNotBlank()) liveText.value = supervisorDisplayText

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

        if (!reportCapture.hasReport) {
            latest = latest + UIMessage.user(
                """
                Internal supervisor reminder: call `${SUBAGENT_REPORT_TOOL_NAME}` now with the compact structured result.
                The report tool is injected directly into this subagent run and may not appear in tools_list/catalog output.
                Do not repeat the full visible answer; keep any final text short.
                """.trimIndent()
            )
            generationHandler.generateText(
                settings = isolatedSettings,
                model = model,
                messages = latest,
                assistant = assistant,
                memories = emptyList(),
                tools = tools + reportCapture.tool(),
                maxSteps = SUBAGENT_REPORT_RETRY_STEPS,
                processingStatus = MutableStateFlow("SubAgent ${definition.id} report"),
                autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls,
                autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
                invocationContext = ToolInvocationContext.SubAgent,
                conversation = null,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    latest = chunk.messages
                }
            }
        }

        val finalDisplayText = supervisorDisplayText
            .ifBlank { renderAssistantTranscriptForDisplay(latest).take(definition.outputBudgetChars) }
        // Final write to liveText so a sheet opened/refreshed AFTER completion still shows the
        // human-facing answer. A report-only retry is intentionally not allowed to replace the
        // user's visible transcript with internal reminder chatter.
        if (finalDisplayText.isNotBlank()) liveText.value = finalDisplayText
        return reportCapture.resultOrFallback(finalDisplayText)
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

    private fun renderAssistantTranscriptForDisplay(messages: List<UIMessage>): String =
        messages
            .filter { it.role == MessageRole.ASSISTANT }
            .map { it.toText().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")

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

        Subagent reporting:
        - Keep writing normal Markdown for the human live panel; do not print JSON or machine-only wrappers.
        - Before you finish, call `${SUBAGENT_REPORT_TOOL_NAME}` once with the compact result the supervisor should consume: summary, findings, evidence, risks, recommended_next_steps, and confidence.
        - `${SUBAGENT_REPORT_TOOL_NAME}` is an internal injected tool for this subagent run. It may not appear in tools_list or tool catalog output; use it anyway when you are done.
        - The report tool is not the human-facing answer. It is the structured channel back to the main agent.

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

internal fun Assistant.toIsolatedSubAgentAssistant(definition: SubAgentDefinition) = copy(
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
    presetMessages = emptyList(),
    quickMessageIds = emptySet(),
    regexes = emptyList(),
    mcpServers = emptySet(),
    localTools = emptyList(),
    modeInjectionIds = emptySet(),
    lorebookIds = emptySet(),
    enabledSkills = emptySet(),
    enableTimeReminder = false,
    messageTemplate = "{{ message }}",
    temperature = definition.temperature ?: temperature,
    reasoningLevel = definition.reasoningLevel ?: reasoningLevel,
)

internal fun Settings.toIsolatedSubAgentSettings(): Settings = copy(
    agentRuntime = agentRuntime.copy(
        enableCoreMemory = false,
        enableShortTermMemory = false,
        enableLongTermMemory = false,
        enableRecentChatsReference = false,
        enableTimeReminder = false,
        agentSoulMarkdown = "",
        generativeUi = agentRuntime.generativeUi.copy(enabled = false),
        speculativeToolExecution = agentRuntime.speculativeToolExecution.copy(enabled = false),
    )
)

private const val SUBAGENT_REPORT_RETRY_STEPS = 2
