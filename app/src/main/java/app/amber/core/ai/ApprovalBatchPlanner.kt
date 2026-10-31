package app.amber.core.ai

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.jev.JevAutoApprovalGate
import app.amber.feature.runtime.PermissionDecision
import app.amber.feature.runtime.PermissionDecisionAction
import app.amber.feature.runtime.ToolInvocationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** PermissionDecisionResolver 中由自动批准开关放行的来源。 */
private val AUTO_APPROVAL_SOURCES = setOf("settings", "settings_unattended")

/** Plans approval patches; the kernel owns persistence, parking and dispatch. */
internal class ApprovalBatchPlanner(
    private val autoApproveHighRiskTools: Boolean,
    private val invocationContext: ToolInvocationContext,
    private val runId: String?,
    private val autoApprovalGate: JevAutoApprovalGate?,
    private val resolveDecision: (Tool?, UIMessagePart.Tool) -> PermissionDecision,
    private val captureTarget: suspend (UIMessagePart.Tool) -> UIMessagePart.Tool = { it },
) {
    class ResumePlan(
        val canReleaseWaitingToolsWithHighRisk: Boolean,
        val approvalUpdates: List<UIMessagePart.Tool>,
        val readyTools: List<UIMessagePart.Tool>,
    )

    class FreshPlan(
        val updatedTools: List<UIMessagePart.Tool>,
        val hasPendingApproval: Boolean,
    )

    suspend fun planResume(
        stepIndex: Int,
        initialPendingToolCallIds: Set<String>,
        pendingTools: List<UIMessagePart.Tool>,
        waitingTools: List<UIMessagePart.Tool>,
        deferredAutoTools: List<UIMessagePart.Tool>,
        toolDefinitions: Map<String, Tool>,
        recentUserTexts: List<String>,
    ): ResumePlan {
        // Recheck only the initial parked batch and its deferred Auto siblings.
        val canReleaseAllWaitingToolsWithHighRisk =
            stepIndex == 0 &&
                autoApproveHighRiskTools &&
                initialPendingToolCallIds.isNotEmpty() &&
                waitingTools.isNotEmpty() &&
                waitingTools.all { it.toolCallId in initialPendingToolCallIds } &&
                // Jev 复核收紧的调用只能由用户决定，不随高风险开关释放。
                waitingTools.none { it.metadata?.containsKey(JevAutoApprovalGate.METADATA_KEY) == true }
        val autoToolsToRecheck = buildList {
            if (pendingTools.isNotEmpty()) addAll(deferredAutoTools)
            if (canReleaseAllWaitingToolsWithHighRisk) {
                addAll(waitingTools)
                addAll(deferredAutoTools)
            }
        }.distinctBy { it.toolCallId }
        val recheckedAutoTools = autoToolsToRecheck.map { tool ->
            // Recheck a transient Auto copy; never invent a user Approved state.
            val autoTool = tool.copy(approvalState = ToolApprovalState.Auto)
            val decision = resolveDecision(toolDefinitions[tool.toolName], autoTool)
            val escalation = autoApprovalEscalation(autoTool, decision, recentUserTexts)
            if (escalation.isEmpty()) {
                tool to decision
            } else {
                tool.copy(metadata = escalationMetadata(tool.metadata, escalation)) to
                    decision.copy(action = PermissionDecisionAction.ASK)
            }
        }
        if (recheckedAutoTools.any { it.second.action == PermissionDecisionAction.ASK }) {
            // Released siblings stay Auto so a later resume rechecks current policy.
            val updates = recheckedAutoTools.map { (tool, decision) ->
                tool.copy(
                    approvalState = if (decision.action == PermissionDecisionAction.ASK) {
                        ToolApprovalState.Pending
                    } else {
                        ToolApprovalState.Auto
                    },
                    metadata = mergeToolMetadata(
                        tool.metadata,
                        decision.trace.toJson(),
                        JsonObject(emptyMap()),
                    ),
                )
            }
            return ResumePlan(canReleaseAllWaitingToolsWithHighRisk, updates, emptyList())
        }
        // Auto does not satisfy canResumeExecution. Dispatcher rechecks before execution.
        return ResumePlan(
            canReleaseWaitingToolsWithHighRisk = canReleaseAllWaitingToolsWithHighRisk,
            approvalUpdates = emptyList(),
            readyTools = recheckedAutoTools.map { (tool, _) ->
                tool.copy(approvalState = ToolApprovalState.Auto)
            },
        )
    }

    suspend fun planFresh(
        tools: List<UIMessagePart.Tool>,
        toolDefinitions: List<Tool>,
        preparedEffectIds: Map<String, String>,
        recentUserTexts: List<String>,
    ): FreshPlan {
        var hasPendingApproval = false
        val updatedTools = tools.map { original ->
            val tool = captureTarget(original)
            val toolDef = toolDefinitions.find { it.name == tool.toolName }
            val decision = resolveDecision(toolDef, tool)
            val escalation = autoApprovalEscalation(tool, decision, recentUserTexts)
            val ledgerMetadata = preparedEffectIds[tool.toolCallId]?.let { effectId ->
                buildJsonObject {
                    put("effect_id", effectId)
                    put("run_id", runId)
                }
            } ?: JsonObject(emptyMap())
            when {
                // Jev 复核只把自动批准收紧为人工审批。
                escalation.isNotEmpty() -> {
                    hasPendingApproval = true
                    tool.copy(
                        approvalState = ToolApprovalState.Pending,
                        metadata = mergeToolMetadata(
                            tool.metadata,
                            decision.trace.toJson(),
                            ledgerMetadata,
                        ).let { escalationMetadata(it, escalation) },
                    )
                }
                decision.action == PermissionDecisionAction.ASK -> {
                    hasPendingApproval = true
                    tool.copy(
                        approvalState = ToolApprovalState.Pending,
                        metadata = mergeToolMetadata(tool.metadata, decision.trace.toJson(), ledgerMetadata),
                    )
                }
                tool.approvalState is ToolApprovalState.Pending -> {
                    hasPendingApproval = true
                    tool
                }
                else -> if (ledgerMetadata.isEmpty()) {
                    tool
                } else {
                    tool.copy(metadata = mergeToolMetadata(tool.metadata, null, ledgerMetadata))
                }
            }
        }
        return FreshPlan(updatedTools, hasPendingApproval)
    }

    private suspend fun autoApprovalEscalation(
        tool: UIMessagePart.Tool,
        decision: PermissionDecision,
        recentUserTexts: List<String>,
    ): List<JevAutoApprovalGate.Risk> = if (
        autoApprovalGate != null &&
        tool.approvalState == ToolApprovalState.Auto &&
        decision.action == PermissionDecisionAction.ALLOW &&
        decision.source in AUTO_APPROVAL_SOURCES &&
        decision.trace.policy?.needsApproval == true
    ) {
        autoApprovalGate.escalation(
            toolName = tool.toolName,
            input = tool.input,
            recentUserTexts = recentUserTexts,
            runKey = runId,
            userAuthored = invocationContext != ToolInvocationContext.SubAgent,
        )
    } else {
        emptyList()
    }

    private fun escalationMetadata(
        metadata: JsonObject?,
        risks: List<JevAutoApprovalGate.Risk>,
    ): JsonObject = JsonObject(metadata.orEmpty() + (
        JevAutoApprovalGate.METADATA_KEY to JsonArray(risks.map { JsonPrimitive(it.key) })
    ))

    /** Ledger keys win because their effect binding is authoritative for the approval card. */
    private fun mergeToolMetadata(
        existing: JsonObject?,
        permissionTrace: JsonObject?,
        ledger: JsonObject,
    ): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        existing?.forEach { (key, value) -> merged[key] = value }
        if (permissionTrace != null) merged["permission_trace"] = permissionTrace
        ledger.forEach { (key, value) -> merged[key] = value }
        return JsonObject(merged)
    }
}
