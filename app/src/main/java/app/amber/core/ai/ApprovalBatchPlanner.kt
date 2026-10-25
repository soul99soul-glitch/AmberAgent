package app.amber.core.ai

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.jev.JevApprovalTriage
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
    private val approvalTriage: JevApprovalTriage? = null,
    private val resolveDecision: (Tool?, UIMessagePart.Tool) -> PermissionDecision,
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
                waitingTools.none { jevTightened(it) }
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
            val afterGate = if (escalation.isEmpty()) {
                tool to decision
            } else {
                tool.copy(metadata = escalationMetadata(tool.metadata, escalation)) to
                    decision.copy(action = PermissionDecisionAction.ASK)
            }
            val triage = runTriage(afterGate.first, afterGate.second, recentUserTexts)
            when (triage?.applied) {
                JevApprovalTriage.Posture.ASK ->
                    afterGate.first.copy(metadata = triageMetadata(afterGate.first.metadata, triage)) to
                        afterGate.second.copy(action = PermissionDecisionAction.ASK)
                else -> afterGate
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
        val updatedTools = tools.map { tool ->
            val toolDef = toolDefinitions.find { it.name == tool.toolName }
            val decision = resolveDecision(toolDef, tool)
            val escalation = autoApprovalEscalation(tool, decision, recentUserTexts)
            val ledgerMetadata = preparedEffectIds[tool.toolCallId]?.let { effectId ->
                buildJsonObject {
                    put("effect_id", effectId)
                    put("run_id", runId)
                }
            } ?: JsonObject(emptyMap())
            val afterGate = if (escalation.isEmpty()) {
                tool to decision
            } else {
                tool.copy(metadata = escalationMetadata(tool.metadata, escalation)) to
                    decision.copy(action = PermissionDecisionAction.ASK)
            }
            val triage = runTriage(afterGate.first, afterGate.second, recentUserTexts)
            val triagedTool = if (triage == null) {
                afterGate.first
            } else {
                afterGate.first.copy(metadata = triageMetadata(afterGate.first.metadata, triage))
            }
            val tightenedToAsk = triage != null && triage.applied == JevApprovalTriage.Posture.ASK
            when {
                // Jev 复核与分诊只把自动批准收紧为人工审批，从不放行。
                escalation.isNotEmpty() || tightenedToAsk -> {
                    hasPendingApproval = true
                    triagedTool.copy(
                        approvalState = ToolApprovalState.Pending,
                        metadata = mergeToolMetadata(
                            triagedTool.metadata,
                            decision.trace.toJson(),
                            ledgerMetadata,
                        ),
                    )
                }
                decision.action == PermissionDecisionAction.ASK -> {
                    hasPendingApproval = true
                    triagedTool.copy(
                        approvalState = ToolApprovalState.Pending,
                        metadata = mergeToolMetadata(triagedTool.metadata, decision.trace.toJson(), ledgerMetadata),
                    )
                }
                tool.approvalState is ToolApprovalState.Pending -> {
                    hasPendingApproval = true
                    triagedTool
                }
                else -> if (ledgerMetadata.isEmpty() && triagedTool.metadata == tool.metadata) {
                    triagedTool
                } else {
                    triagedTool.copy(metadata = mergeToolMetadata(triagedTool.metadata, null, ledgerMetadata))
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

    private suspend fun runTriage(
        tool: UIMessagePart.Tool,
        decision: PermissionDecision,
        recentUserTexts: List<String>,
    ): JevApprovalTriage.Result? {
        val gate = approvalTriage ?: return null
        val original = when {
            decision.action == PermissionDecisionAction.DENY -> JevApprovalTriage.Posture.DENY
            decision.action == PermissionDecisionAction.ASK ||
                tool.approvalState is ToolApprovalState.Pending -> JevApprovalTriage.Posture.ASK
            else -> JevApprovalTriage.Posture.ALLOW
        }
        val result = gate.assess(
            toolName = tool.toolName,
            input = tool.input,
            recentUserTexts = recentUserTexts,
            original = original,
            runKey = runId,
        )
        return result.takeIf { it.writeMetadata }
    }

    private fun jevTightened(tool: UIMessagePart.Tool): Boolean =
        tool.metadata?.containsKey(JevAutoApprovalGate.METADATA_KEY) == true ||
            tool.metadata?.containsKey(JevApprovalTriage.METADATA_KEY) == true

    private fun triageMetadata(
        metadata: JsonObject?,
        result: JevApprovalTriage.Result,
    ): JsonObject = JsonObject(metadata.orEmpty() + (
        JevApprovalTriage.METADATA_KEY to JevApprovalTriage.metadata(result)
    ))

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
