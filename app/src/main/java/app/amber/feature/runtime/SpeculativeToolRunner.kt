package app.amber.feature.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.tools.invocationPolicy
import java.util.concurrent.ConcurrentHashMap

enum class SpeculativeToolStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    DISCARDED,
}

data class SpeculativeToolState(
    val toolCallId: String,
    val toolName: String,
    val input: String,
    val status: SpeculativeToolStatus,
    val result: UIMessagePart.Tool? = null,
    val error: String? = null,
)

class SpeculativeToolRunner(
    private val scope: CoroutineScope,
    private val dispatcher: AgentToolDispatcher,
    private val maxConcurrentTools: Int = 4,
    private val invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
) {
    private val states = ConcurrentHashMap<String, SpeculativeToolState>()
    private val jobs = ConcurrentHashMap<String, Deferred<UIMessagePart.Tool?>>()

    fun observe(
        tools: List<UIMessagePart.Tool>,
        toolDefinitions: Map<String, Tool>,
    ) {
        tools.asSequence()
            .filter { it.toolCallId.isNotBlank() && !it.isExecuted }
            .filter { it.input.isLikelyCompleteJsonObject() }
            .filter { tool ->
                toolDefinitions[tool.toolName]?.invocationPolicy(tool.input)?.speculativeEligible == true
            }
            .take(maxConcurrentTools)
            .forEach { tool ->
                val existing = states[tool.toolCallId]
                if (existing != null && existing.toolName == tool.toolName && existing.input == tool.input) {
                    return@forEach
                }
                if (existing != null) {
                    jobs.remove(tool.toolCallId)?.cancel()
                }
                states[tool.toolCallId] = SpeculativeToolState(
                    toolCallId = tool.toolCallId,
                    toolName = tool.toolName,
                    input = tool.input,
                    status = SpeculativeToolStatus.PENDING,
                )
                jobs[tool.toolCallId] = scope.async {
                    try {
                        val result = dispatcher.execute(
                            tool = tool,
                            toolDef = toolDefinitions[tool.toolName],
                            autoApproveTools = false,
                            autoApproveHighRiskTools = false,
                            invocationContext = invocationContext,
                        )
                        updateStateIfCurrent(tool) { state ->
                            state.copy(
                                status = SpeculativeToolStatus.COMPLETED,
                                result = result,
                            )
                        }
                        result
                    } catch (error: CancellationException) {
                        updateStateIfCurrent(tool) { state ->
                            state.copy(
                                status = SpeculativeToolStatus.CANCELLED,
                                error = error.message ?: error.toString(),
                            )
                        }
                        throw error
                    } catch (error: Throwable) {
                        updateStateIfCurrent(tool) { state ->
                            state.copy(
                                status = SpeculativeToolStatus.FAILED,
                                error = error.message ?: error.toString(),
                            )
                        }
                        null
                    }
                }
            }
    }

    suspend fun reusableResults(finalTools: List<UIMessagePart.Tool>): Map<String, UIMessagePart.Tool> {
        val finalById = finalTools.associateBy { it.toolCallId }
        jobs.toMap().forEach { (toolCallId, job) ->
            val state = states[toolCallId] ?: return@forEach
            val final = finalById[toolCallId]
            if (final == null || final.toolName != state.toolName || final.input != state.input) {
                job.cancel()
                states[toolCallId] = state.copy(status = SpeculativeToolStatus.DISCARDED)
            }
        }
        return finalTools.mapNotNull { final ->
            val state = states[final.toolCallId] ?: return@mapNotNull null
            if (state.toolName != final.toolName || state.input != final.input) return@mapNotNull null
            val result = jobs[final.toolCallId]
                ?.takeIf { it.isCompleted && !it.isCancelled }
                ?.await()
                ?: state.result.takeIf { state.status == SpeculativeToolStatus.COMPLETED }
            result?.let { final.toolCallId to it }
        }.toMap()
    }

    fun snapshot(): List<SpeculativeToolState> = states.values.sortedBy { it.toolCallId }

    private fun String.isLikelyCompleteJsonObject(): Boolean {
        val trimmed = trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    private fun updateStateIfCurrent(
        tool: UIMessagePart.Tool,
        update: (SpeculativeToolState) -> SpeculativeToolState,
    ) {
        states.computeIfPresent(tool.toolCallId) { _, current ->
            if (current.toolName == tool.toolName && current.input == tool.input) {
                update(current)
            } else {
                current
            }
        }
    }
}
