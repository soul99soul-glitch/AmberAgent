package app.amber.feature.runtime

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.toAgentToolFailurePayload
import app.amber.core.ai.GenerationFailureClassifier
import app.amber.core.ai.GenerationRetrySetting
import app.amber.feature.tools.ToolEffectClass
import app.amber.feature.tools.ToolRisk
import app.amber.feature.tools.effectClass
import app.amber.feature.tools.invocationPolicy

private const val TAG = "AgentToolDispatcher"

/** Metadata binding a pending composite resume to its original checkpoint. */
const val TOOL_RESUME_PROVENANCE_METADATA_KEY = "__amber_recipe_resume_provenance"
private const val RECIPE_CHECKPOINT_INPUT_KEY = "__amber_recipe_checkpoint"
private const val RECIPE_RESUME_APPROVED_INPUT_KEY = "__amber_recipe_resume_approved"
private const val RECIPE_INVOCATION_INPUT_KEY = "__amber_recipe_invocation"

/**
 * Ledger write-ahead context for one generation run (P1-02). Non-null only
 * when the durable runtime path is active; a run keeps the same context from
 * start to finish (the runId is never swapped mid-run).
 */
data class ToolLedgerContext(
    val runId: String,
    val turnId: Int,
    val ledger: ToolEffectLedger,
    val messagePersistenceCursor: String? = null,
)

/**
 * A composite tool reached a nested human-approval checkpoint before it
 * performed the requested operation. The dispatcher turns this into the
 * normal top-level pending tool state so the run coordinator can persist
 * WAITING_USER and resume the same call later.
 */
class ToolExecutionPause(
    val resumeInput: String,
    message: String,
    val resumeProvenance: String? = null,
) : RuntimeException(message)

class AgentToolDispatcher(
    private val json: Json,
    private val permissionDecisionResolver: PermissionDecisionResolver,
    private val hooks: List<ToolInvocationHook> = emptyList(),
) {
    fun resolveDecision(
        toolDef: Tool?,
        tool: UIMessagePart.Tool,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
        capabilityPermissions: CapabilityPermissionState? = null,
        permissionContext: CapabilityPermissionContext? = null,
    ): PermissionDecision = permissionDecisionResolver.resolve(
        toolDef = toolDef,
        tool = tool,
        autoApproveTools = autoApproveTools,
        autoApproveHighRiskTools = autoApproveHighRiskTools,
        autoApprovedToolNames = autoApprovedToolNames,
        invocationContext = invocationContext,
        capabilityPermissions = capabilityPermissions,
        permissionContext = permissionContext,
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
        ledgerContext: ToolLedgerContext? = null,
        capabilityPermissions: CapabilityPermissionState? = null,
        approvalHistory: CapabilityPermissionStore? = null,
        permissionContext: CapabilityPermissionContext? = null,
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
                            ledgerContext = ledgerContext,
                            capabilityPermissions = capabilityPermissions,
                            approvalHistory = approvalHistory,
                            permissionContext = permissionContext,
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
                    ledgerContext = ledgerContext,
                    capabilityPermissions = capabilityPermissions,
                    approvalHistory = approvalHistory,
                    permissionContext = permissionContext,
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
        ledgerContext: ToolLedgerContext? = null,
        capabilityPermissions: CapabilityPermissionState? = null,
        approvalHistory: CapabilityPermissionStore? = null,
        permissionContext: CapabilityPermissionContext? = null,
    ): UIMessagePart.Tool? {
        val decision = resolveDecision(
            toolDef = toolDef,
            tool = tool,
            autoApproveTools = autoApproveTools,
            autoApproveHighRiskTools = autoApproveHighRiskTools,
            autoApprovedToolNames = autoApprovedToolNames,
            invocationContext = invocationContext,
            capabilityPermissions = capabilityPermissions,
            permissionContext = permissionContext,
        )
        val tracedTool = tool.withPermissionTrace(decision.trace.toJson())
        return when (tool.approvalState) {
            is ToolApprovalState.Denied -> {
                recordDeniedEffect(ledgerContext, tool)
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
                ledgerContext?.let { ctx ->
                    ctx.ledger.getByToolCallId(tool.toolCallId)?.let { effect ->
                        ctx.ledger.finish(effect.effectId, listOf(UIMessagePart.Text(answer)))
                    }
                }
                tracedTool.copy(output = listOf(UIMessagePart.Text(answer)))
            }

            is ToolApprovalState.Pending -> null

            else -> if (decision.action == PermissionDecisionAction.DENY) {
                recordDeniedEffect(ledgerContext, tool)
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
                ledgerContext = ledgerContext,
                approvalHistory = approvalHistory,
            )
        }
    }

    private suspend fun recordDeniedEffect(ledgerContext: ToolLedgerContext?, tool: UIMessagePart.Tool) {
        if (ledgerContext == null) return
        ledgerContext.ledger.getByToolCallId(tool.toolCallId)?.let { effect ->
            if (effect.status != ToolEffectStatus.FAILED) {
                ledgerContext.ledger.fail(effect.effectId, "approval_denied")
            }
        }
    }

    private suspend fun executeWithHooks(
        tool: UIMessagePart.Tool,
        toolDef: Tool?,
        decision: PermissionDecision,
        invocationContext: ToolInvocationContext,
        retrySetting: GenerationRetrySetting,
        ledgerContext: ToolLedgerContext? = null,
        approvalHistory: CapabilityPermissionStore? = null,
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
            validateCompositeResumeProvenance(tool, toolDef, args)
            runBeforeHooks(request)?.let { result ->
                return tool.copy(output = result.output).withHookMetadata(result.metadata)
            }
            val resolved = toolDef ?: error("Tool ${tool.toolName} not found")
            val executionArgs = if (resolved.name.startsWith("recipe_") && args is JsonObject) {
                JsonObject(args.toMutableMap().apply {
                    put(RECIPE_INVOCATION_INPUT_KEY, JsonPrimitive(tool.toolCallId))
                })
            } else {
                args
            }
            logInfo("execute: ${resolved.name} argKeys=${(args as? JsonObject)?.keys.orEmpty()}")
            val executeNested = suspend {
                executeResolvedToolWithRetry(
                    tool = tool,
                    toolDef = resolved,
                    retrySetting = retrySetting,
                    execute = { resolved.execute(executionArgs) },
                )
            }
            val executed = if (resolved.ledgerManaged) {
                executeWithLedger(
                    tool = tool,
                    toolDef = resolved,
                    retrySetting = retrySetting,
                    ledgerContext = ledgerContext,
                    approvalHistory = approvalHistory,
                    execute = executeNested,
                )
            } else {
                // Recipe-like composite tools write their nested effects
                // through the same dispatcher. Keep the outer PREPARED
                // effect reusable while a nested approval is pending, and
                // finish it only after the composite returns normally.
                executeNested().also { result ->
                    ledgerContext?.let { ctx ->
                        ctx.ledger.getByToolCallId(tool.toolCallId)?.let { effect ->
                            ctx.ledger.finish(effect.effectId, result.output)
                        }
                    }
                }
            }
            val hooked = runAfterHooks(request, ToolInvocationResult(output = executed.output))
            executed.copy(output = hooked.output).withHookMetadata(hooked.metadata)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is ToolExecutionPause) {
                // No output means the top-level Tool remains unexecuted, so
                // the existing approval coordinator can resume it. The
                // checkpoint is carried in the input because Tool.execute
                // intentionally receives only JSON arguments.
                val pendingMetadata = error.resumeProvenance?.let { provenance ->
                    val existing = tool.metadata?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    existing[TOOL_RESUME_PROVENANCE_METADATA_KEY] = JsonPrimitive(provenance)
                    JsonObject(existing)
                } ?: tool.metadata
                return tool.copy(
                    input = error.resumeInput,
                    output = emptyList(),
                    approvalState = ToolApprovalState.Pending,
                    metadata = pendingMetadata,
                )
            }
            if (toolDef?.ledgerManaged == false) {
                ledgerContext?.ledger?.getByToolCallId(tool.toolCallId)?.let { effect ->
                    ledgerContext.ledger.fail(effect.effectId, error.errorCategory())
                }
            }
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

    /**
     * P1-02 write-ahead protocol around a single tool execution:
     *
     *  PREPARED → (approval) → STARTED → execute → FINISHED / FAILED
     *
     * - [prepare] is idempotent per (runId, toolCallId), so a resumed approval
     *   round or a post-crash retry reuses the same effect.
     * - OUTCOME_UNKNOWN effects (non-idempotent tool whose outcome was lost)
     *   are never re-executed; the caller gets a structured outcome_unknown
     *   result until the user confirms retry (RECONCILED) or abandons.
     * - A cancellation leaves the effect STARTED: the outcome is unknown and
     *   the recovery rules classify it (readOnly/idempotent retry,
     *   nonIdempotent → OUTCOME_UNKNOWN).
     */
    private suspend fun executeWithLedger(
        tool: UIMessagePart.Tool,
        toolDef: Tool,
        retrySetting: GenerationRetrySetting,
        ledgerContext: ToolLedgerContext?,
        approvalHistory: CapabilityPermissionStore?,
        execute: suspend () -> UIMessagePart.Tool,
    ): UIMessagePart.Tool {
        // P2-01: a user-approved call may only execute when the approval still
        // matches the args about to be executed. If the model re-issued the
        // call with different parameters after the user approved (same
        // toolCallId), the approval is stale and the tool must not run —
        // 同一审批不能用于参数已经变化的调用. The guard also runs on the
        // non-durable path (ledgerContext == null), so a missing ledger must
        // never skip the digest check. Auto-approval paths (decision ALLOW)
        // re-resolve per call and never carry a stale binding.
        if (ledgerContext == null) {
            staleApprovalResult(tool, runId = null, approvalHistory, effectId = null)?.let { return it }
            return execute()
        }
        val ctx = ledgerContext
        // Block first: an existing OUTCOME_UNKNOWN / abandoned effect must not
        // be re-prepared (prepare() would mint a fresh PREPARED effect that
        // bypasses the block).
        ctx.ledger.getByToolCallId(tool.toolCallId)?.let { existing ->
            when (existing.status) {
                ToolEffectStatus.OUTCOME_UNKNOWN -> {
                    logInfo("execute: blocking ${tool.toolName} ${existing.effectId} (outcome unknown)")
                    return tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                json.encodeToString(
                                    buildJsonObject {
                                        put("status", "outcome_unknown")
                                        put("error", "Tool execution outcome is unknown after an interruption; awaiting user confirmation.")
                                        put("effect_id", existing.effectId)
                                        put("recoverable", true)
                                    }
                                )
                            )
                        )
                    )
                }

                ToolEffectStatus.RECONCILED if existing.errorCategory == "abandoned" -> {
                    logInfo("execute: blocking ${tool.toolName} ${existing.effectId} (abandoned by user)")
                    return tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                json.encodeToString(
                                    buildJsonObject {
                                        put("status", "abandoned")
                                        put("error", "The user chose to abandon this tool call after an unknown outcome.")
                                        put("effect_id", existing.effectId)
                                    }
                                )
                            )
                        )
                    )
                }

                else -> Unit
            }
        }
        val effect = ctx.ledger.prepare(
            runId = ctx.runId,
            turnId = ctx.turnId,
            toolCallId = tool.toolCallId,
            toolName = tool.toolName,
            input = tool.input,
            effectClass = toolDef.effectClass(),
            messagePersistenceCursor = ctx.messagePersistenceCursor,
        )
        // P2-01: a user-approved call may only execute when the approval still
        // matches the args about to be executed. If the model re-issued the
        // call with different parameters after the user approved (same
        // toolCallId), the approval is stale and the tool must not run —
        // 同一审批不能用于参数已经变化的调用. Auto-approval paths (decision ALLOW)
        // re-resolve per call and never carry a stale binding.
        staleApprovalResult(tool, runId = ctx.runId, approvalHistory, effectId = effect.effectId)
            ?.let { return it }
        ctx.ledger.markStarted(effect.effectId, approvalDigest(ctx.runId, tool.toolCallId, effect.argsDigest))
        return try {
            val executed = execute()
            ctx.ledger.finish(effect.effectId, executed.output)
            executed
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ctx.ledger.fail(effect.effectId, error.errorCategory())
            throw error
        }
    }

    /**
     * P2-01 stale-approval guard shared by the durable and non-durable paths.
     * Returns a structured `approval_stale` result when the approval record
     * (bound to the digest the user approved) no longer matches the args about
     * to be executed, or null when the call may run. [runId] comes from the
     * call chain (null on the non-durable path, matching approvals recorded
     * without a ledger effect).
     */
    private suspend fun staleApprovalResult(
        tool: UIMessagePart.Tool,
        runId: String?,
        approvalHistory: CapabilityPermissionStore?,
        effectId: String?,
    ): UIMessagePart.Tool? {
        if (tool.approvalState !is ToolApprovalState.Approved || approvalHistory == null) return null
        val currentDigest = argsDigest(tool.input)
        val record = runCatching { approvalHistory.approvalFor(runId, tool.toolCallId) }.getOrNull()
        if (ApprovalGuard.isValid(record?.argsDigest, currentDigest, record?.decision)) return null
        logInfo("execute: blocking ${tool.toolName} ${tool.toolCallId} (stale approval)")
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    json.encodeToString(
                        buildJsonObject {
                            put("status", "approval_stale")
                            put(
                                "error",
                                "The approval no longer matches the current tool arguments; " +
                                    "the tool call was re-issued with different parameters."
                            )
                            if (effectId != null) put("effect_id", effectId)
                            put("recoverable", true)
                        }
                    )
                )
            )
        )
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

    private fun validateCompositeResumeProvenance(
        tool: UIMessagePart.Tool,
        toolDef: Tool?,
        args: JsonElement,
    ) {
        if (toolDef?.ledgerManaged != false) return
        val input = args as? JsonObject ?: return
        val checkpoint = input[RECIPE_CHECKPOINT_INPUT_KEY]?.jsonObject ?: return
        val approved = input[RECIPE_RESUME_APPROVED_INPUT_KEY]
            ?.jsonPrimitive
            ?.contentOrNull == "true"
        if (!approved) return
        val expected = tool.metadata
            ?.jsonObject
            ?.get(TOOL_RESUME_PROVENANCE_METADATA_KEY)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("Composite resume provenance is missing")
        val actual = checkpoint["provenance_digest"]
            ?.jsonPrimitive
            ?.contentOrNull
        require(actual == expected) { "Composite resume checkpoint provenance changed" }
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

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }
}
