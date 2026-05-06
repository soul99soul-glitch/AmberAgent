package me.rerere.rikkahub.data.agent.runtime

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.toAgentToolFailurePayload
import me.rerere.rikkahub.data.agent.tools.ToolRisk
import me.rerere.rikkahub.data.agent.tools.invocationPolicy

private const val TAG = "AgentToolDispatcher"

class AgentToolDispatcher(
    private val json: Json,
    private val permissionDecisionResolver: PermissionDecisionResolver,
) {
    fun shouldPauseForApproval(
        toolDef: Tool?,
        tool: UIMessagePart.Tool,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
    ): Boolean = permissionDecisionResolver.shouldPauseForApproval(
        toolDef = toolDef,
        tool = tool,
        autoApproveTools = autoApproveTools,
        autoApproveHighRiskTools = autoApproveHighRiskTools,
        autoApprovedToolNames = autoApprovedToolNames,
    )

    fun resolveDecision(
        toolDef: Tool?,
        tool: UIMessagePart.Tool,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
    ): PermissionDecision = permissionDecisionResolver.resolve(
        toolDef = toolDef,
        tool = tool,
        autoApproveTools = autoApproveTools,
        autoApproveHighRiskTools = autoApproveHighRiskTools,
        autoApprovedToolNames = autoApprovedToolNames,
        invocationContext = invocationContext,
    )

    suspend fun executeBatch(
        tools: List<UIMessagePart.Tool>,
        toolDefinitions: Map<String, Tool>,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
        prefetchedTools: Map<String, UIMessagePart.Tool> = emptyMap(),
    ): List<UIMessagePart.Tool> {
        val reused = tools.mapNotNull { tool ->
            prefetchedTools[tool.toolCallId]?.takeIf { prefetched ->
                prefetched.toolName == tool.toolName && prefetched.input == tool.input && prefetched.output.isNotEmpty()
            }
        }
        val remaining = tools.filterNot { tool -> reused.any { it.toolCallId == tool.toolCallId } }
        if (remaining.isEmpty()) return reused
        val executed = if (remaining.size > 1 && remaining.all { tool -> canRunInParallel(tool, toolDefinitions[tool.toolName]) }) {
            coroutineScope {
                remaining.map { tool ->
                    async {
                        execute(
                            tool = tool,
                            toolDef = toolDefinitions[tool.toolName],
                            autoApproveTools = autoApproveTools,
                            autoApproveHighRiskTools = autoApproveHighRiskTools,
                            autoApprovedToolNames = autoApprovedToolNames,
                        )
                    }
                }.awaitAll().filterNotNull()
            }
        } else {
            val executed = ArrayList<UIMessagePart.Tool>()
            remaining.forEach { tool ->
                execute(
                    tool = tool,
                    toolDef = toolDefinitions[tool.toolName],
                    autoApproveTools = autoApproveTools,
                    autoApproveHighRiskTools = autoApproveHighRiskTools,
                    autoApprovedToolNames = autoApprovedToolNames,
                )?.let { executed += it }
            }
            executed
        }
        return tools.mapNotNull { tool ->
            reused.find { it.toolCallId == tool.toolCallId } ?: executed.find { it.toolCallId == tool.toolCallId }
        }
    }

    suspend fun execute(
        tool: UIMessagePart.Tool,
        toolDef: Tool?,
        autoApproveTools: Boolean = false,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
    ): UIMessagePart.Tool? {
        val decision = resolveDecision(
            toolDef = toolDef,
            tool = tool,
            autoApproveTools = autoApproveTools,
            autoApproveHighRiskTools = autoApproveHighRiskTools,
            autoApprovedToolNames = autoApprovedToolNames,
            invocationContext = invocationContext,
        )
        val tracedTool = tool.withPermissionTrace(decision.trace.toJson())
        return when (tool.approvalState) {
            is ToolApprovalState.Denied -> {
                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                tracedTool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(
                                buildJsonObject {
                                    put("status", "denied")
                                    put("message", "Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                    put("permission_trace", decision.trace.toJson())
                                }
                            )
                        )
                    )
                )
            }

            is ToolApprovalState.Answered -> {
                val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                tracedTool.copy(output = listOf(UIMessagePart.Text(answer)))
            }

            is ToolApprovalState.Pending -> null

            else -> if (decision.action == PermissionDecisionAction.DENY) {
                tracedTool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(
                                buildJsonObject {
                                    put("status", "failed")
                                    put("message", decision.reason)
                                    put("recoverable", false)
                                    put("permission_trace", decision.trace.toJson())
                                }
                            )
                        )
                    )
                )
            } else runCatching {
                val resolved = toolDef ?: error("Tool ${tool.toolName} not found")
                val args = json.parseToJsonElement(tool.input.ifBlank { "{}" })
                logInfo("execute: ${resolved.name} args=$args")
                tracedTool.copy(output = resolved.execute(args))
            }.getOrElse { error ->
                logError("execute failed for ${tool.toolName}", error)
                tracedTool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(
                                buildJsonObject {
                                    error.toAgentToolFailurePayload().forEach { (key, value) -> put(key, value) }
                                    put("permission_trace", decision.trace.toJson())
                                }
                            )
                        )
                    )
                )
            }
        }
    }

    private fun canRunInParallel(tool: UIMessagePart.Tool, toolDef: Tool?): Boolean {
        if (tool.approvalState !is ToolApprovalState.Auto) return false
        val policy = toolDef?.invocationPolicy(tool.input) ?: return false
        return policy.concurrencySafe &&
            !policy.mutates &&
            !policy.needsApproval &&
            policy.risk == ToolRisk.Normal &&
            policy.parallelGroup != null
    }

    private fun UIMessagePart.Tool.withPermissionTrace(trace: JsonObject): UIMessagePart.Tool {
        val existing = metadata?.jsonObject?.toMutableMap() ?: mutableMapOf()
        existing["permission_trace"] = trace
        return copy(metadata = JsonObject(existing))
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }
}
