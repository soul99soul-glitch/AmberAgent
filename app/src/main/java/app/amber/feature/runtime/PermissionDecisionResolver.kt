package app.amber.feature.runtime

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.ToolInvocationPolicy
import app.amber.feature.tools.ToolRisk
import app.amber.feature.tools.capability
import app.amber.feature.tools.invocationPolicy
import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy
import app.amber.core.ai.tools.TOOL_THEME_PACK_IMPORT
import app.amber.core.utils.JsonInstant
import app.amber.agent.R
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
    val capabilityPolicyScope: CapabilityPermissionScope? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("trace_id", traceId)
        put("tool_name", toolName)
        put("invocation_context", invocationContext.name.lowercase())
        put("approval_state", approvalState)
        put("action", action.name.lowercase())
        put("source", source)
        put("reason", reason)
        capabilityPolicyScope?.let { put("capability_policy_scope", it.name.lowercase()) }
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
                put("mandatory_approval", policy.mandatoryApproval)
                put("always_ask", policy.alwaysAsk)
                policy.reason?.let { put("reason", it) }
            })
        }
    }
}

/**
 * Resolved at the runtime boundary. Action/source/enum values remain stable
 * machine fields; only human-facing decision reasons are localized.
 */
data class PermissionDecisionStrings(
    val toolNotFound: (String) -> String,
    val userAlreadyDecided: String,
    val themePackForeground: String,
    val askUser: String,
    val capabilityPolicyBlocked: (String) -> String,
    val bothAutoApproval: String,
    val alwaysAsk: String,
    val mandatoryBypassed: String,
    val mandatoryApproval: String,
    val historianPreapproved: String,
    val subagentBypassed: String,
    val subagentCannotSilent: String,
    val readOnly: String,
    val highRisk: String,
    val runTrust: String,
    val highRiskAuto: String,
    val globalAuto: String,
    val requiresApproval: String,
    val capabilityDisabled: (String, String) -> String,
    val capabilityAsk: (String, String) -> String,
    val capabilityAutoMandatory: (String, String) -> String,
    val capabilityAutoHighRisk: (String, String) -> String,
    val scope: (CapabilityPermissionScope) -> String,
) {
    companion object {
        fun english(): PermissionDecisionStrings = PermissionDecisionStrings(
            toolNotFound = { toolName ->
                "Tool not found or not exposed. If this tool came from tools_list, " +
                    "call tool_search with query=\"$toolName\" first, then retry."
            },
            userAlreadyDecided = "User already decided.",
            themePackForeground = "Theme package import must be explicitly confirmed in the foreground.",
            askUser = "ask_user always needs a human answer.",
            capabilityPolicyBlocked = { capability -> "Capability $capability policy blocked this invocation." },
            bothAutoApproval = "Both auto-approval toggles allow unattended tool execution.",
            alwaysAsk = "Tool always requires explicit human approval.",
            mandatoryBypassed = "Mandatory approval was bypassed by explicit high-risk auto-approval settings.",
            mandatoryApproval = "Tool requires explicit human approval unless high-risk auto-approval is enabled.",
            historianPreapproved = "Historian subagent pre-approved for read-only history tool.",
            subagentBypassed = "Sub Agent approval was bypassed by explicit high-risk auto-approval settings.",
            subagentCannotSilent = "Sub Agent context cannot silently run this tool.",
            readOnly = "Tool is read-only for this invocation.",
            highRisk = "High-risk invocation requires explicit approval.",
            runTrust = "Tool was approved earlier in this run.",
            highRiskAuto = "High-risk auto-approval allowed this invocation.",
            globalAuto = "Global auto-approval allowed this invocation.",
            requiresApproval = "Tool requires approval.",
            capabilityDisabled = { capability, scope ->
                "Capability $capability is disabled by $scope policy."
            },
            capabilityAsk = { capability, scope ->
                "Capability $capability requires explicit human approval by $scope policy."
            },
            capabilityAutoMandatory = { capability, scope ->
                "Capability $capability $scope auto policy cannot bypass the tool's mandatory per-call approval."
            },
            capabilityAutoHighRisk = { capability, scope ->
                "Capability $capability is high-risk; $scope auto policy requires the explicit high-risk auto-approval setting."
            },
            scope = { it.name.lowercase() },
        )

        fun from(context: Context): PermissionDecisionStrings = PermissionDecisionStrings(
            toolNotFound = { toolName ->
                context.getString(R.string.permission_decision_reason_tool_not_found, toolName)
            },
            userAlreadyDecided = context.getString(R.string.permission_decision_reason_user_already_decided),
            themePackForeground = context.getString(R.string.permission_decision_reason_theme_pack_foreground),
            askUser = context.getString(R.string.permission_decision_reason_ask_user),
            capabilityPolicyBlocked = { capability ->
                context.getString(R.string.permission_decision_reason_capability_policy_blocked, capability)
            },
            bothAutoApproval = context.getString(R.string.permission_decision_reason_both_auto_approval),
            alwaysAsk = context.getString(R.string.permission_decision_reason_always_ask),
            mandatoryBypassed = context.getString(R.string.permission_decision_reason_mandatory_bypassed),
            mandatoryApproval = context.getString(R.string.permission_decision_reason_mandatory_approval),
            historianPreapproved = context.getString(R.string.permission_decision_reason_subagent_history),
            subagentBypassed = context.getString(R.string.permission_decision_reason_subagent_bypassed),
            subagentCannotSilent = context.getString(R.string.permission_decision_reason_subagent_cannot_silent),
            readOnly = context.getString(R.string.permission_decision_reason_read_only),
            highRisk = context.getString(R.string.permission_decision_reason_high_risk),
            runTrust = context.getString(R.string.permission_decision_reason_run_trust),
            highRiskAuto = context.getString(R.string.permission_decision_reason_high_risk_auto),
            globalAuto = context.getString(R.string.permission_decision_reason_global_auto),
            requiresApproval = context.getString(R.string.permission_decision_reason_requires_approval),
            capabilityDisabled = { capability, scope ->
                context.getString(R.string.permission_decision_reason_capability_disabled, capability, scope)
            },
            capabilityAsk = { capability, scope ->
                context.getString(R.string.permission_decision_reason_capability_ask, capability, scope)
            },
            capabilityAutoMandatory = { capability, scope ->
                context.getString(R.string.permission_decision_reason_capability_auto_mandatory, capability, scope)
            },
            capabilityAutoHighRisk = { capability, scope ->
                context.getString(R.string.permission_decision_reason_capability_auto_high_risk, capability, scope)
            },
            scope = { scope ->
                val resId = when (scope) {
                    CapabilityPermissionScope.GLOBAL -> R.string.permission_decision_scope_global
                    CapabilityPermissionScope.ASSISTANT -> R.string.permission_decision_scope_assistant
                    CapabilityPermissionScope.WORKSPACE -> R.string.permission_decision_scope_workspace
                    CapabilityPermissionScope.CONVERSATION -> R.string.permission_decision_scope_conversation
                    CapabilityPermissionScope.SESSION -> R.string.permission_decision_scope_session
                }
                context.getString(resId)
            },
        )
    }
}

class PermissionDecisionResolver(
    private val appContext: Context? = null,
) {
    fun resolve(
        toolDef: Tool?,
        tool: UIMessagePart.Tool,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean,
        autoApprovedToolNames: Set<String> = emptySet(),
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
        capabilityPermissions: CapabilityPermissionState? = null,
        permissionContext: CapabilityPermissionContext? = null,
        strings: PermissionDecisionStrings? = null,
    ): PermissionDecision {
        val localized = strings
            ?: appContext?.let(PermissionDecisionStrings::from)
            ?: PermissionDecisionStrings.english()
        var capabilityPolicyScope: CapabilityPermissionScope? = null
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
                capabilityPolicyScope = capabilityPolicyScope,
            )
            return PermissionDecision(action, reason, source, trace)
        }
        if (toolDef == null) {
            return decision(
                PermissionDecisionAction.DENY,
                localized.toolNotFound(tool.toolName),
                "tool_lookup",
                null
            )
        }
        var policy = toolDef.invocationPolicy(tool.input)
        // MCP calls target an external server whose side effects are not
        // knowable from the generated function schema. Keep both the direct
        // gateway and expanded `mcp__server__tool` entries behind an approval
        // even if a caller accidentally supplied a permissive Tool definition.
        if (tool.toolName == "mcp_call_tool" || tool.toolName.startsWith("mcp__")) {
            policy = policy.copy(
                mutates = true,
                needsApproval = true,
                autoApprovable = false,
                risk = if (policy.risk == ToolRisk.Normal) ToolRisk.Sensitive else policy.risk,
            )
        }
        if (tool.approvalState !is ToolApprovalState.Auto) {
            return decision(PermissionDecisionAction.ALLOW, localized.userAlreadyDecided, "approval_state", policy)
        }
        // Theme package import is a foreground-only user action. Keep this
        // gate ahead of the unattended toggles: even explicit high-risk auto
        // approval must not turn a theme import into a background write.
        if (tool.toolName == TOOL_THEME_PACK_IMPORT) {
            return decision(
                PermissionDecisionAction.ASK,
                localized.themePackForeground,
                "theme_pack_foreground",
                policy,
            )
        }
        if (tool.toolName == ASK_USER_TOOL_NAME && policy.needsApproval) {
            return decision(PermissionDecisionAction.ASK, localized.askUser, "hitl", policy)
        }
        // P2-01 capability layer (only when the capability_permissions flag is
        // on). DISABLED/ASK policies preempt every auto-approval level; the
        // risk floor raises the effective risk of mapped tools so low-level
        // global switches can never bypass high-risk capabilities.
        if (capabilityPermissions != null) {
            val capability = toolDef.capability()
            if (capability != null) {
                val adjustment = adjustForCapability(
                    capability = capability,
                    policy = policy,
                    state = capabilityPermissions,
                    context = permissionContext,
                    autoApproveHighRiskTools = autoApproveHighRiskTools,
                    strings = localized,
                )
                capabilityPolicyScope = adjustment.scope
                if (adjustment.hardDecision != null) {
                    return decision(
                        adjustment.hardDecision,
                        adjustment.reason ?: localized.capabilityPolicyBlocked(capability.id),
                        "capability",
                        policy,
                    )
                }
                policy = adjustment.adjusted
            }
        }
        if (autoApproveTools && autoApproveHighRiskTools) {
            return decision(
                PermissionDecisionAction.ALLOW,
                localized.bothAutoApproval,
                "settings_unattended",
                policy,
            )
        }
        if (policy.alwaysAsk) {
            return decision(PermissionDecisionAction.ASK, localized.alwaysAsk, "always_ask", policy)
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
                    localized.mandatoryBypassed,
                    "settings_high_risk_mandatory",
                    policy,
                )
            }
            return decision(
                PermissionDecisionAction.ASK,
                localized.mandatoryApproval,
                "mandatory_approval",
                policy,
            )
        }
        if (invocationContext == ToolInvocationContext.SubAgent) {
            if (tool.toolName in HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT && tool.hasSessionGrant()) {
                // Historian subagent's whole job is reading history; it has no channel
                // back to the user to ask for approval. Only pre-approve when the
                // manager already minted a bounded SessionAccessGrant for this run.
                return decision(
                    PermissionDecisionAction.ALLOW,
                    localized.historianPreapproved,
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
                        localized.subagentBypassed,
                        "settings_high_risk_subagent",
                        policy,
                    )
                }
                return decision(PermissionDecisionAction.ASK, localized.subagentCannotSilent, "subagent", policy)
            }
        }
        if (!policy.needsApproval) {
            return decision(PermissionDecisionAction.ALLOW, localized.readOnly, "policy", policy)
        }
        if (policy.risk == ToolRisk.High && !autoApproveHighRiskTools) {
            return decision(PermissionDecisionAction.ASK, localized.highRisk, "risk", policy)
        }
        if (tool.toolName in autoApprovedToolNames && tool.toolName != ASK_USER_TOOL_NAME && policy.risk != ToolRisk.High) {
            return decision(PermissionDecisionAction.ALLOW, localized.runTrust, "run_trust", policy)
        }
        if (autoApproveTools && autoApproveHighRiskTools && policy.risk == ToolRisk.High) {
            return decision(PermissionDecisionAction.ALLOW, localized.highRiskAuto, "settings_high_risk", policy)
        }
        if (autoApproveTools && policy.autoApprovable) {
            return decision(PermissionDecisionAction.ALLOW, localized.globalAuto, "settings", policy)
        }
        return decision(PermissionDecisionAction.ASK, localized.requiresApproval, "ui", policy)
    }

    /**
     * P2-01 capability gate (parity plan §P2-01 #2/#3).
     *
     * Scope rule: all matching explicit values are merged by strictness
     * (DISABLED > ASK > AUTO); an absent value inherits. Equal policies use
     * the more-specific scope as the trace source.
     *  - DISABLED → hard DENY, regardless of global switches.
     *  - ASK → hard ASK, regardless of global switches.
     *  - AUTO → explicit per-capability auto-approval, but:
     *      * tool-level hard gates (mandatoryApproval / alwaysAsk) still ASK;
     *      * a capability whose risk floor is High can only be let through by
     *        the explicit high-risk auto-approval setting (普通 auto 不放行
     *        mcp.import 类高风险).
     *  - unset → no policy override; the risk floor still applies.
     *
     * The risk floor raises the effective risk of every mapped tool to at
     * least [Capability.riskFloor], so low-level global switches
     * (autoApproveTools) can never auto-approve a high-risk capability tool
     * that happens to look low-risk on its own (e.g. mcp_import_from_skill).
     */
    private data class CapabilityAdjustment(
        val hardDecision: PermissionDecisionAction?,
        val reason: String?,
        val adjusted: ToolInvocationPolicy,
        val scope: CapabilityPermissionScope?,
    )

    private fun adjustForCapability(
        capability: Capability,
        policy: ToolInvocationPolicy,
        state: CapabilityPermissionState,
        context: CapabilityPermissionContext?,
        autoApproveHighRiskTools: Boolean,
        strings: PermissionDecisionStrings,
    ): CapabilityAdjustment {
        val floor = capability.riskFloor
        val effectiveRisk = if (floor.ordinal > policy.risk.ordinal) floor else policy.risk
        val adjusted = policy.copy(
            risk = effectiveRisk,
            // A raised-to-High tool must never be "auto-approvable" for the
            // plain (non-high-risk) auto path.
            autoApprovable = policy.autoApprovable && effectiveRisk != ToolRisk.High,
            // A raised-to-High tool must not fall through the read-only fast path.
            needsApproval = policy.needsApproval || effectiveRisk == ToolRisk.High,
        )
        val selected = state.policyFor(capability, context)
        return when (selected?.policy) {
            CapabilityPolicy.DISABLED -> CapabilityAdjustment(
                PermissionDecisionAction.DENY,
                strings.capabilityDisabled(capability.id, strings.scope(selected.scope)),
                adjusted,
                selected.scope,
            )

            CapabilityPolicy.ASK -> CapabilityAdjustment(
                PermissionDecisionAction.ASK,
                strings.capabilityAsk(capability.id, strings.scope(selected.scope)),
                adjusted,
                selected.scope,
            )

            CapabilityPolicy.AUTO -> {
                if (policy.mandatoryApproval || policy.alwaysAsk) {
                    CapabilityAdjustment(
                        PermissionDecisionAction.ASK,
                        strings.capabilityAutoMandatory(capability.id, strings.scope(selected.scope)),
                        adjusted,
                        selected.scope,
                    )
                } else if (effectiveRisk == ToolRisk.High && !autoApproveHighRiskTools) {
                    CapabilityAdjustment(
                        PermissionDecisionAction.ASK,
                        strings.capabilityAutoHighRisk(capability.id, strings.scope(selected.scope)),
                        adjusted,
                        selected.scope,
                    )
                } else {
                    CapabilityAdjustment(
                        null,
                        null,
                        adjusted.copy(
                            autoApprovable = true,
                            needsApproval = effectiveRisk == ToolRisk.High,
                        ),
                        selected.scope,
                    )
                }
            }

            null -> CapabilityAdjustment(null, null, adjusted, null)
        }
    }

    private fun ToolInvocationPolicy.requiresSubAgentApproval(): Boolean =
        mutates || risk != ToolRisk.Normal || category in setOf("screen", "terminal", "system", "external_file", "office", "cloud")

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

private const val ASK_USER_TOOL_NAME = "ask_user"
