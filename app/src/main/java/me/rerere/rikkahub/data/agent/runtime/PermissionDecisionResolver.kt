package me.rerere.rikkahub.data.agent.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.ToolInvocationPolicy
import me.rerere.rikkahub.data.agent.tools.ToolRisk
import me.rerere.rikkahub.data.agent.tools.invocationPolicy
import me.rerere.rikkahub.utils.JsonInstant
import java.util.UUID

enum class PermissionDecisionAction {
    ALLOW,
    ASK,
    DENY,
}

data class PermissionDecision(
    val action: PermissionDecisionAction,
    val reason: String,
    val source: String,
    val trace: PermissionDecisionTrace,
)

data class PermissionDecisionTrace(
    val traceId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val invocationContext: ToolInvocationContext,
    val policy: ToolInvocationPolicy?,
    val autoApproveTools: Boolean,
    val autoApproveHighRiskTools: Boolean,
    val autoApprovedByRun: Boolean,
    val approvalState: String,
    val action: PermissionDecisionAction,
    val source: String,
    val reason: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("trace_id", traceId)
        put("tool_name", toolName)
        put("invocation_context", invocationContext.name.lowercase())
        put("approval_state", approvalState)
        put("action", action.name.lowercase())
        put("source", source)
        put("reason", reason)
        put("auto_approve_tools", autoApproveTools)
        put("auto_approve_high_risk_tools", autoApproveHighRiskTools)
        put("auto_approved_by_run", autoApprovedByRun)
        policy?.let { policy ->
            put("policy", buildJsonObject {
                put("category", policy.category)
                put("risk", policy.risk.name.lowercase())
                put("mutates", policy.mutates)
                put("needs_approval", policy.needsApproval)
                put("auto_approvable", policy.autoApprovable)
                put("concurrency_safe", policy.concurrencySafe)
                policy.parallelGroup?.let { put("parallel_group", it) }
                policy.requiresForegroundAppPackage?.let { put("requires_foreground_app_package", it) }
                put("speculative_eligible", policy.speculativeEligible)
                policy.speculativeBlockReason?.let { put("speculative_block_reason", it) }
                put("output_budget_chars", policy.outputBudgetChars)
                put("hard_blocked", policy.hardBlocked)
                put("mandatory_approval", policy.mandatoryApproval)
                policy.reason?.let { put("reason", it) }
            })
        }
    }
}

class PermissionDecisionResolver {
    fun resolve(
        toolDef: Tool?,
        tool: UIMessagePart.Tool,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean,
        autoApprovedToolNames: Set<String> = emptySet(),
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
    ): PermissionDecision {
        fun decision(
            action: PermissionDecisionAction,
            reason: String,
            source: String,
            policy: ToolInvocationPolicy?,
        ): PermissionDecision {
            val trace = PermissionDecisionTrace(
                toolName = tool.toolName,
                invocationContext = invocationContext,
                policy = policy,
                autoApproveTools = autoApproveTools,
                autoApproveHighRiskTools = autoApproveHighRiskTools,
                autoApprovedByRun = tool.toolName in autoApprovedToolNames,
                approvalState = tool.approvalState.javaClass.simpleName,
                action = action,
                source = source,
                reason = reason,
            )
            return PermissionDecision(action, reason, source, trace)
        }
        if (toolDef == null) {
            return decision(
                PermissionDecisionAction.DENY,
                "Tool not found or not exposed. If this tool came from tools_list, call tool_search with query=\"${tool.toolName}\" first, then retry.",
                "tool_lookup",
                null
            )
        }
        val policy = toolDef.invocationPolicy(tool.input)
        if (tool.approvalState !is ToolApprovalState.Auto) {
            return decision(PermissionDecisionAction.ALLOW, "User already decided.", "approval_state", policy)
        }
        if (policy.hardBlocked) {
            return decision(PermissionDecisionAction.DENY, policy.reason ?: "Tool invocation is blocked.", "policy", policy)
        }
        // Mandatory approval gate — stricter than regular auto-approval and
        // prior in-run trust, but still respects the explicit "auto approve
        // high-risk tools" setting. Users who enable both toggles are opting
        // into unattended execution for tools like WebMount eval and external
        // CLI council seats.
        if (policy.mandatoryApproval) {
            if (autoApproveTools && autoApproveHighRiskTools) {
                return decision(
                    PermissionDecisionAction.ALLOW,
                    "Mandatory approval was bypassed by explicit high-risk auto-approval settings.",
                    "settings_high_risk_mandatory",
                    policy,
                )
            }
            return decision(
                PermissionDecisionAction.ASK,
                "Tool requires explicit human approval unless high-risk auto-approval is enabled.",
                "mandatory_approval",
                policy,
            )
        }
        if (tool.toolName == ASK_USER_TOOL_NAME && policy.needsApproval) {
            return decision(PermissionDecisionAction.ASK, "ask_user always needs a human answer.", "hitl", policy)
        }
        if (invocationContext == ToolInvocationContext.SubAgent) {
            if (tool.toolName in HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT && tool.hasSessionGrant()) {
                // Historian subagent's whole job is reading history; it has no channel
                // back to the user to ask for approval. Only pre-approve when the
                // manager already minted a bounded SessionAccessGrant for this run.
                return decision(
                    PermissionDecisionAction.ALLOW,
                    "Historian subagent pre-approved for read-only history tool.",
                    "subagent_history",
                    policy,
                )
            }
            if (policy.requiresSubAgentApproval()) {
                if (
                    autoApproveTools &&
                    autoApproveHighRiskTools &&
                    tool.toolName != ASK_USER_TOOL_NAME &&
                    (policy.risk == ToolRisk.High || policy.mandatoryApproval)
                ) {
                    return decision(
                        PermissionDecisionAction.ALLOW,
                        "Sub Agent approval was bypassed by explicit high-risk auto-approval settings.",
                        "settings_high_risk_subagent",
                        policy,
                    )
                }
                return decision(PermissionDecisionAction.ASK, "Sub Agent context cannot silently run this tool.", "subagent", policy)
            }
        }
        if (!policy.needsApproval) {
            return decision(PermissionDecisionAction.ALLOW, "Tool is read-only for this invocation.", "policy", policy)
        }
        if (policy.risk == ToolRisk.High && !autoApproveHighRiskTools) {
            return decision(PermissionDecisionAction.ASK, "High-risk invocation requires explicit approval.", "risk", policy)
        }
        if (tool.toolName in autoApprovedToolNames && tool.toolName != ASK_USER_TOOL_NAME && policy.risk != ToolRisk.High) {
            return decision(PermissionDecisionAction.ALLOW, "Tool was approved earlier in this run.", "run_trust", policy)
        }
        if (autoApproveTools && autoApproveHighRiskTools && policy.risk == ToolRisk.High) {
            return decision(PermissionDecisionAction.ALLOW, "High-risk auto-approval allowed this invocation.", "settings_high_risk", policy)
        }
        if (autoApproveTools && policy.autoApprovable) {
            return decision(PermissionDecisionAction.ALLOW, "Global auto-approval allowed this invocation.", "settings", policy)
        }
        return decision(PermissionDecisionAction.ASK, "Tool requires approval.", "ui", policy)
    }

    fun shouldPauseForApproval(
        toolDef: Tool?,
        tool: UIMessagePart.Tool,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
    ): Boolean = resolve(
        toolDef = toolDef,
        tool = tool,
        autoApproveTools = autoApproveTools,
        autoApproveHighRiskTools = autoApproveHighRiskTools,
        autoApprovedToolNames = autoApprovedToolNames,
    ).action == PermissionDecisionAction.ASK

    private fun ToolInvocationPolicy.requiresSubAgentApproval(): Boolean =
        mutates || risk != ToolRisk.Normal || category in setOf("screen", "terminal", "system", "external_file", "office")

    private fun UIMessagePart.Tool.hasSessionGrant(): Boolean =
        runCatching {
            (JsonInstant.parseToJsonElement(input.ifBlank { "{}" }) as? JsonObject)
                ?.get("grant_id")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.isNotBlank() == true
        }.getOrDefault(false)
}

/**
 * Tools the historian subagent must be able to run silently — Sensitive risk in normal
 * context (PII exposure of historical chat) but the subagent's whole purpose is reading
 * past sessions and it has no way to ask the user for approval. Main-agent calls still
 * flow through the regular Sensitive-risk approval path.
 */
private val HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT = setOf(
    "session_read",
    "session_expand",
)

enum class ToolInvocationContext {
    Normal,
    SubAgent,
    Cron,
    ModelCouncil,
}

private const val ASK_USER_TOOL_NAME = "ask_user"
