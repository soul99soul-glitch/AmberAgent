package app.amber.feature.modelcouncil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.Tool
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.tools.createAskUserTool
import app.amber.core.ai.tools.createSearchTools
import app.amber.core.ai.tools.createTimeTool
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.feature.runtime.AgentToolDispatcher
import kotlin.uuid.Uuid

/**
 * App-module implementation of [CouncilHostToolProvider]. Owns the host's
 * tool-augmented generation loop by reusing the project's existing primitives:
 * [ProviderCatalog.streamText] + [MessageStreamAccumulator] +
 * [AgentToolDispatcher.executeBatch] + the read-only tool factories.
 *
 * Kept deliberately minimal next to [app.amber.core.ai.ChatRunCoordinator] — no
 * speculative execution, transformers, or approval UI. It serves a single host
 * turn with a bounded number of tool round-trips, and is the ONLY place in the
 * council that exposes tools. `ask_user` is surfaced (not executed) so the room
 * can drive its own HITL flow.
 *
 * The tool set is read-only and explicitly allowlisted here ([HOST_TOOL_NAMES]),
 * so all calls are auto-approved — no per-call permission gate.
 */
class AppCouncilHostToolProvider(
    private val providerCatalog: ProviderCatalog,
    private val toolDispatcher: AgentToolDispatcher,
    ) : CouncilHostToolProvider {

    override fun isAvailable(settings: Settings): Boolean =
        createSearchTools(settings, includeWebViewFallbackGuidance = false).any { it.name in HOST_TOOL_NAMES }

    override suspend fun generateWithTools(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        reasoningLevel: ReasoningLevel,
        maxToolRounds: Int,
        timeoutMs: Long,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit,
    ): HostToolOutcome {
        val model = settings.findModelById(modelId)
            ?: return HostToolOutcome.Done("", listOf("Host model not found: $modelId"))
        val provider = model.findProvider(settings.providers)
            ?: return HostToolOutcome.Done("", listOf("Provider not found for host model."))
        val providerImpl = providerCatalog.text(provider)

        // The "relatively strong" host tool set: web search + page scrape + time.
        // ask_user is included so the host CAN ask; it is intercepted, not run.
        val tools: List<Tool> = buildList {
            addAll(
                createSearchTools(settings, includeWebViewFallbackGuidance = false)
                    .filter { it.name in HOST_TOOL_NAMES }
            )
            add(createTimeTool())
            add(createAskUserTool())
        }
        val toolDefs = tools.associateBy { it.name }

        var messages: List<UIMessage> = buildList {
            add(UIMessage.system(systemPrompt))
            add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(userPrompt))))
        }
        val warnings = mutableListOf<String>()

        for (round in 0..maxToolRounds) {
            val params = TextGenerationParams(
                model = model,
                tools = tools,
                reasoningLevel = reasoningLevel,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            )
            val accumulator = MessageStreamAccumulator(messages, model)
            val streamed = runCatching {
                withTimeoutOrNull(timeoutMs) {
                    providerImpl.stream(provider, messages, params).collect { chunk ->
                        accumulator.append(chunk)
                        // Forward only the assistant's own cumulative text for live display.
                        val delta = chunk.choices.firstOrNull()?.delta?.parts
                            ?.filterIsInstance<UIMessagePart.Text>()
                            ?.sumOf { it.text.length } ?: 0
                        if (delta > 0) {
                            val snap = accumulator.snapshot()
                            val fullText = snap.lastOrNull()?.parts
                                ?.filterIsInstance<UIMessagePart.Text>()
                                ?.joinToString("") { it.text }
                                .orEmpty()
                            if (fullText.isNotEmpty()) onChunk(fullText.take(outputBudgetChars))
                        }
                    }
                }
            }
            streamed.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (streamed.getOrNull() == null) {
                warnings += "主持人调研超时（${timeoutMs}ms）。"
            }

            messages = accumulator.snapshot()
            val toolCalls = messages.lastOrNull()?.getTools()
                ?.filter { !it.isExecuted }
                .orEmpty()

            // No tool calls → final text reached.
            if (toolCalls.isEmpty()) {
                val finalText = messages.lastOrNull()?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
                    .take(outputBudgetChars)
                return HostToolOutcome.Done(finalText, warnings)
            }

            // ask_user: surface it for the room's HITL flow, do not execute.
            val askUser = toolCalls.firstOrNull { it.toolName == ASK_USER_TOOL_NAME }
            if (askUser != null) {
                return HostToolOutcome.AskUser(
                    rawPayload = askUser.input,
                    displayQuestion = askUser.displayQuestion(),
                )
            }

            // Budget exhausted: stop with whatever text the model produced.
            if (round == maxToolRounds) {
                val finalText = messages.lastOrNull()?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
                    .take(outputBudgetChars)
                warnings += "主持人已完成 $maxToolRounds 轮调研，基于已获取的信息继续。"
                return HostToolOutcome.Done(finalText, warnings)
            }

            // Execute the (read-only, allowlisted) tool calls inline.
            val executed = runCatching {
                toolDispatcher.executeBatch(
                    tools = toolCalls,
                    toolDefinitions = toolDefs,
                    autoApproveTools = true,
                    autoApprovedToolNames = toolDefs.keys.toSet(),
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                warnings += "Tool execution failed: ${error.message ?: error::class.java.simpleName}"
                emptyList()
            }
            if (executed.isEmpty()) {
                val finalText = messages.lastOrNull()?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
                    .take(outputBudgetChars)
                return HostToolOutcome.Done(finalText, warnings)
            }
            // Write executed outputs back into the last assistant message (mirrors
            // the coordinator's in-place update), then loop so the model sees results.
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executed.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
        }
        return HostToolOutcome.Done("", warnings)
    }

    companion object {
        /** Read-only, broadly-useful tools the host may use. */
        val HOST_TOOL_NAMES = setOf("search_web", "scrape_web", "time")
        const val ASK_USER_TOOL_NAME = "ask_user"
    }
}

private fun UIMessagePart.Tool.displayQuestion(): String =
    runCatching {
        Json.parseToJsonElement(input)
            .jsonObject["questions"]
            ?.jsonArray
            ?.mapNotNull { question ->
                question.jsonObject["question"]?.jsonPrimitive?.contentOrNull?.trim()
            }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            .orEmpty()
    }.getOrDefault("")
        .ifBlank { input }
