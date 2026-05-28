package me.rerere.rikkahub.data.agent.runtime

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.toAgentToolFailurePayload
import me.rerere.rikkahub.data.ai.GenerationFailureClassifier
import me.rerere.rikkahub.data.ai.GenerationRetrySetting
import app.amber.feature.tools.ToolRisk
import app.amber.feature.tools.invocationPolicy

private const val TAG = "AgentToolDispatcher"

class AgentToolDispatcher(
    private val json: Json,
    private val permissionDecisionResolver: PermissionDecisionResolver,
    private val hooks: List<ToolInvocationHook> = emptyList(),
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
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
        prefetchedTools: Map<String, UIMessagePart.Tool> = emptyMap(),
        retrySetting: GenerationRetrySetting = GenerationRetrySetting(enabled = false),
    ): List<UIMessagePart.Tool> {
        val reused = tools.mapNotNull { tool ->
            prefetchedTools[tool.toolCallId]?.takeIf { prefetched ->
                prefetched.toolName == tool.toolName && prefetched.input == tool.input && prefetched.output.isNotEmpty()
            }
        }
        val reusedIds = reused.mapTo(HashSet()) { it.toolCallId }
        val remaining = tools.filterNot { tool -> tool.toolCallId in reusedIds }
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
                            invocationContext = invocationContext,
                            retrySetting = retrySetting,
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
                    invocationContext = invocationContext,
                    retrySetting = retrySetting,
                )?.let { executed += it }
            }
            executed
        }
        val reusedById = reused.associateBy { it.toolCallId }
        val executedById = executed.associateBy { it.toolCallId }
        return tools.mapNotNull { tool ->
            reusedById[tool.toolCallId] ?: executedById[tool.toolCallId]
        }
    }

    suspend fun execute(
        tool: UIMessagePart.Tool,
        toolDef: Tool?,
        autoApproveTools: Boolean = false,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
        retrySetting: GenerationRetrySetting = GenerationRetrySetting(enabled = false),
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
            } else executeWithHooks(
                tool = tracedTool,
                toolDef = toolDef,
                decision = decision,
                invocationContext = invocationContext,
                retrySetting = retrySetting,
            )
        }
    }

    private suspend fun executeWithHooks(
        tool: UIMessagePart.Tool,
        toolDef: Tool?,
        decision: PermissionDecision,
        invocationContext: ToolInvocationContext,
        retrySetting: GenerationRetrySetting,
    ): UIMessagePart.Tool {
        var request = ToolInvocationRequest(
            tool = tool,
            toolDef = toolDef,
            parsedArgs = null,
            permissionDecision = decision,
            invocationContext = invocationContext,
            startedAtMs = System.currentTimeMillis(),
        )
        return try {
            val args = json.parseToJsonElement(tool.input.ifBlank { "{}" }).withoutToolDisplayMetadata()
            request = request.copy(parsedArgs = args)
            runBeforeHooks(request)?.let { result ->
                return tool.copy(output = result.output).withHookMetadata(result.metadata)
            }
            val resolved = toolDef ?: error("Tool ${tool.toolName} not found")
            logInfo("execute: ${resolved.name} args=$args")
            val executed = executeResolvedToolWithRetry(
                tool = tool,
                toolDef = resolved,
                retrySetting = retrySetting,
                execute = { resolved.execute(args) },
            )
            val hooked = runAfterHooks(request, ToolInvocationResult(output = executed.output))
            executed.copy(output = hooked.output).withHookMetadata(hooked.metadata)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logError("execute failed for ${tool.toolName}", error)
            val hooked = runErrorHooks(request, error)
            if (hooked != null) {
                tool.copy(output = hooked.output).withHookMetadata(hooked.metadata)
            } else {
                tool.copy(
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

    private suspend fun runBeforeHooks(request: ToolInvocationRequest): ToolInvocationResult? {
        hooks.forEach { hook ->
            val result = runHook("before", request.tool.toolName) {
                hook.before(request)
            }
            if (result != null) return result
        }
        return null
    }

    private suspend fun runAfterHooks(
        request: ToolInvocationRequest,
        initial: ToolInvocationResult,
    ): ToolInvocationResult {
        var result = initial
        hooks.forEach { hook ->
            result = runHook("after", request.tool.toolName) {
                hook.after(request, result)
            } ?: result
        }
        return result
    }

    private suspend fun runErrorHooks(
        request: ToolInvocationRequest,
        error: Throwable,
    ): ToolInvocationResult? {
        hooks.forEach { hook ->
            val result = runHook("onError", request.tool.toolName) {
                hook.onError(request, error)
            }
            if (result != null) return result
        }
        return null
    }

    private suspend fun <T> runHook(
        phase: String,
        toolName: String,
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        logError("tool hook $phase failed for $toolName", error)
        null
    }

    private suspend fun executeResolvedToolWithRetry(
        tool: UIMessagePart.Tool,
        toolDef: Tool,
        retrySetting: GenerationRetrySetting,
        execute: suspend () -> List<UIMessagePart>,
    ): UIMessagePart.Tool {
        val retryable = canRetrySafely(tool, toolDef, retrySetting)
        var retryAttempt = 1
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                return tool.copy(output = execute())
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                val decision = GenerationFailureClassifier.decide(
                    error = error,
                    attempt = retryAttempt,
                    setting = retrySetting,
                )
                if (!retryable || !decision.retryable) throw error
                logInfo(
                    "execute: retry ${tool.toolName} $retryAttempt/${retrySetting.maxRetries} " +
                        "after ${decision.delayMs}ms (${decision.category})"
                )
                delay(decision.delayMs)
                retryAttempt++
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

    private fun canRetrySafely(
        tool: UIMessagePart.Tool,
        toolDef: Tool,
        retrySetting: GenerationRetrySetting,
    ): Boolean {
        if (!retrySetting.enabled) return false
        if (tool.approvalState !is ToolApprovalState.Auto) return false
        val policy = toolDef.invocationPolicy(tool.input)
        return policy.concurrencySafe &&
            !policy.mutates &&
            !policy.needsApproval &&
            policy.risk == ToolRisk.Normal
    }

    private fun UIMessagePart.Tool.withPermissionTrace(trace: JsonObject): UIMessagePart.Tool {
        val existing = metadata?.jsonObject?.toMutableMap() ?: mutableMapOf()
        existing["permission_trace"] = trace
        return copy(metadata = JsonObject(existing))
    }

    private fun UIMessagePart.Tool.withHookMetadata(hookMetadata: JsonObject): UIMessagePart.Tool {
        if (hookMetadata.isEmpty()) return this
        val existing = metadata?.jsonObject?.toMutableMap() ?: mutableMapOf()
        hookMetadata.forEach { (key, value) -> existing[key] = value }
        return copy(metadata = JsonObject(existing))
    }

    private fun JsonElement.withoutToolDisplayMetadata(): JsonElement {
        val obj = runCatching { jsonObject }.getOrNull() ?: return this
        if (obj.keys.none { it in TOOL_DISPLAY_METADATA_KEYS }) return this
        return JsonObject(obj.filterKeys { key -> key !in TOOL_DISPLAY_METADATA_KEYS })
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }
}

private val TOOL_DISPLAY_METADATA_KEYS = setOf("display_title")
