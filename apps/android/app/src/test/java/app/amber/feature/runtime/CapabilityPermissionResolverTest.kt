package app.amber.feature.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-01 capability permission resolution (parity plan §P2-01 #2/#3):
 * most-restrictive merge, risk floor, plain auto-approve must not pass
 * mcp.import, disabling one capability must not affect others.
 */
class CapabilityPermissionResolverTest {

    private val resolver = PermissionDecisionResolver()

    private fun tool(
        name: String,
        needsApproval: Boolean = false,
        allowsAutoApproval: Boolean = true,
    ): Tool = Tool(
        name = name,
        description = "",
        needsApproval = needsApproval,
        allowsAutoApproval = allowsAutoApproval,
        execute = { _: JsonElement -> emptyList() },
    )

    private fun toolCall(name: String, input: String = "{}"): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = name,
            input = input,
            approvalState = ToolApprovalState.Auto,
        )

    private fun state(vararg pairs: Pair<Capability, CapabilityPolicy>): CapabilityPermissionState =
        CapabilityPermissionState(policies = pairs.toMap())

    private fun resolve(
        tool: Tool,
        call: UIMessagePart.Tool,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean = false,
        capabilityPermissions: CapabilityPermissionState?,
    ): PermissionDecisionAction = resolver.resolve(
        toolDef = tool,
        tool = call,
        autoApproveTools = autoApproveTools,
        autoApproveHighRiskTools = autoApproveHighRiskTools,
        capabilityPermissions = capabilityPermissions,
    ).action

    // ---- flag off: pre-P2-01 behavior is fully preserved ----

    @Test
    fun flagOffKeepsLegacyGlobalSwitchBehavior() {
        // Today mcp_import_from_skill is auto-approvable under the plain
        // global switch; with the capability layer off nothing may change.
        val decision = resolve(
            tool("mcp_import_from_skill", needsApproval = true),
            toolCall("mcp_import_from_skill"),
            autoApproveTools = true,
            capabilityPermissions = null,
        )
        assertEquals(PermissionDecisionAction.ALLOW, decision)
    }

    @Test
    fun plainAutoApprovalDoesNotApproveDirectMcpCall() {
        val decision = resolve(
            tool("mcp_call_tool", needsApproval = true),
            toolCall("mcp_call_tool"),
            autoApproveTools = true,
            capabilityPermissions = null,
        )
        assertEquals(PermissionDecisionAction.ASK, decision)
    }

    @Test
    fun expandedMcpToolUsesMcpCapabilityRiskFloor() {
        assertEquals(Capability.MCP_TOOL, app.amber.feature.tools.capabilityForTool("mcp__server__tool"))
        val decision = resolve(
            tool("mcp__server__tool", needsApproval = true),
            toolCall("mcp__server__tool"),
            autoApproveTools = true,
            capabilityPermissions = state(),
        )
        assertEquals(PermissionDecisionAction.ASK, decision)
    }

    // ---- risk floor: 普通 auto-approve 不会放行 mcp.import ----

    @Test
    fun plainAutoApprovalDoesNotApproveMcpImport() {
        // Flag on, no explicit policy: the High risk floor of mcp.import
        // raises the tool's effective risk so the plain global switch cannot
        // let it through.
        val decision = resolve(
            tool("mcp_import_from_skill", needsApproval = true),
            toolCall("mcp_import_from_skill"),
            autoApproveTools = true,
            capabilityPermissions = state(),
        )
        assertEquals(PermissionDecisionAction.ASK, decision)
    }

    @Test
    fun mcpImportStillRunsUnderExplicitHighRiskAuto() {
        val decision = resolve(
            tool("mcp_import_from_skill", needsApproval = true),
            toolCall("mcp_import_from_skill"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            capabilityPermissions = state(),
        )
        assertEquals(PermissionDecisionAction.ALLOW, decision)
    }

    // ---- capability AUTO is bounded by the risk floor ----

    @Test
    fun capabilityAutoStillNeedsHighRiskAutoForHighFloorCapability() {
        val withAuto = state(Capability.MCP_IMPORT to CapabilityPolicy.AUTO)
        // User set mcp.import = auto, but a High-floor capability is only
        // let through by the explicit high-risk auto setting.
        assertEquals(
            PermissionDecisionAction.ASK,
            resolve(
                tool("mcp_import_from_skill", needsApproval = true),
                toolCall("mcp_import_from_skill"),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
                capabilityPermissions = withAuto,
            ),
        )
        assertEquals(
            PermissionDecisionAction.ALLOW,
            resolve(
                tool("mcp_import_from_skill", needsApproval = true),
                toolCall("mcp_import_from_skill"),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
                capabilityPermissions = withAuto,
            ),
        )
    }

    @Test
    fun riskFloorCannotBeBypassedByLowLevelSwitches() {
        // filesystem.write floor is High: even with capability=auto and the
        // normal global switch on, file_write stays blocked without the
        // explicit high-risk auto setting.
        val withAuto = state(Capability.FILESYSTEM_WRITE to CapabilityPolicy.AUTO)
        assertEquals(
            PermissionDecisionAction.ASK,
            resolve(
                tool("file_write"),
                toolCall("file_write"),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
                capabilityPermissions = withAuto,
            ),
        )
        assertEquals(
            PermissionDecisionAction.ALLOW,
            resolve(
                tool("file_write"),
                toolCall("file_write"),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
                capabilityPermissions = withAuto,
            ),
        )
    }

    @Test
    fun capabilityAutoApprovesSensitiveFloorWithNormalAuto() {
        // network.connect floor is Sensitive: capability auto + the normal
        // global switch is enough (no high-risk switch needed).
        val withAuto = state(Capability.NETWORK_CONNECT to CapabilityPolicy.AUTO)
        assertEquals(
            PermissionDecisionAction.ALLOW,
            resolve(
                tool("http_request"),
                toolCall("http_request", input = """{"method":"GET","url":"https://example.com"}"""),
                autoApproveTools = true,
                capabilityPermissions = withAuto,
            ),
        )
    }

    // ---- most-restrictive merge ----

    @Test
    fun disabledCapabilityDeniesEvenWithBothAutoSwitches() {
        val withDisabled = state(Capability.NETWORK_CONNECT to CapabilityPolicy.DISABLED)
        assertEquals(
            PermissionDecisionAction.DENY,
            resolve(
                tool("http_request"),
                toolCall("http_request", input = """{"method":"GET","url":"https://example.com"}"""),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
                capabilityPermissions = withDisabled,
            ),
        )
    }

    @Test
    fun askPolicyBeatsAutoSwitches() {
        val withAsk = state(Capability.FILESYSTEM_READ to CapabilityPolicy.ASK)
        assertEquals(
            PermissionDecisionAction.ASK,
            resolve(
                tool("file_read"),
                toolCall("file_read"),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
                capabilityPermissions = withAsk,
            ),
        )
    }

    @Test
    fun disablingOneCapabilityDoesNotAffectOthers() {
        // workspace.delete disabled; filesystem.read and mcp.import keep their
        // own behavior.
        val withDeleteDisabled = state(Capability.WORKSPACE_DELETE to CapabilityPolicy.DISABLED)
        assertEquals(
            PermissionDecisionAction.DENY,
            resolve(
                tool("external_file_delete"),
                toolCall("external_file_delete"),
                autoApproveTools = true,
                autoApproveHighRiskTools = true,
                capabilityPermissions = withDeleteDisabled,
            ),
        )
        // filesystem.read untouched: read-only tool still auto-approvable.
        assertEquals(
            PermissionDecisionAction.ALLOW,
            resolve(
                tool("file_read"),
                toolCall("file_read"),
                autoApproveTools = true,
                capabilityPermissions = withDeleteDisabled,
            ),
        )
        // mcp.import untouched: floor still gates it.
        assertEquals(
            PermissionDecisionAction.ASK,
            resolve(
                tool("mcp_import_from_skill", needsApproval = true),
                toolCall("mcp_import_from_skill"),
                autoApproveTools = true,
                capabilityPermissions = withDeleteDisabled,
            ),
        )
    }

    @Test
    fun strictestScopeCannotBeOverriddenByPermissiveScope() {
        val capability = Capability.MCP_IMPORT
        val state = CapabilityPermissionState(
            policies = mapOf(capability to CapabilityPolicy.DISABLED),
            scopedPolicies = mapOf(
                CapabilityPermissionScopeKey(CapabilityPermissionScope.ASSISTANT, "assistant-1") to
                    mapOf(capability to CapabilityPolicy.ASK),
                CapabilityPermissionScopeKey(CapabilityPermissionScope.WORKSPACE, "workspace-1") to
                    mapOf(capability to CapabilityPolicy.AUTO),
                CapabilityPermissionScopeKey(CapabilityPermissionScope.CONVERSATION, "conversation-1") to
                    mapOf(capability to CapabilityPolicy.DISABLED),
                CapabilityPermissionScopeKey(CapabilityPermissionScope.SESSION, "run-1") to
                    mapOf(capability to CapabilityPolicy.AUTO),
            ),
        )

        val decision = resolver.resolve(
            toolDef = tool("mcp_import_from_skill", needsApproval = true),
            tool = toolCall("mcp_import_from_skill"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            capabilityPermissions = state,
            permissionContext = CapabilityPermissionContext(
                assistantId = "assistant-1",
                workspaceId = "workspace-1",
                conversationId = "conversation-1",
                sessionId = "run-1",
            ),
        )

        assertEquals(PermissionDecisionAction.DENY, decision.action)
        // Global and conversation are both DISABLED; the more-specific
        // matching scope is retained as the trace source.
        assertEquals(CapabilityPermissionScope.CONVERSATION, decision.trace.capabilityPolicyScope)
    }

    @Test
    fun conversationAskTightensGlobalAutoPolicy() {
        val capability = Capability.MCP_IMPORT
        val state = CapabilityPermissionState(
            policies = mapOf(capability to CapabilityPolicy.AUTO),
            scopedPolicies = mapOf(
                CapabilityPermissionScopeKey(CapabilityPermissionScope.CONVERSATION, "conversation-1") to
                    mapOf(capability to CapabilityPolicy.ASK),
            ),
        )

        val decision = resolver.resolve(
            toolDef = tool("mcp_import_from_skill", needsApproval = true),
            tool = toolCall("mcp_import_from_skill"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            capabilityPermissions = state,
            permissionContext = CapabilityPermissionContext(
                assistantId = "assistant-1",
                conversationId = "conversation-1",
                sessionId = null,
            ),
        )

        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals(CapabilityPermissionScope.CONVERSATION, decision.trace.capabilityPolicyScope)
    }

    @Test
    fun missingConversationOverrideInheritsGlobalPolicy() {
        val capability = Capability.MCP_IMPORT
        val state = CapabilityPermissionState(
            policies = mapOf(capability to CapabilityPolicy.DISABLED),
        )

        val decision = resolver.resolve(
            toolDef = tool("mcp_import_from_skill", needsApproval = true),
            tool = toolCall("mcp_import_from_skill"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            capabilityPermissions = state,
            permissionContext = CapabilityPermissionContext(
                assistantId = "assistant-1",
                conversationId = "conversation-1",
            ),
        )

        assertEquals(PermissionDecisionAction.DENY, decision.action)
        assertEquals(CapabilityPermissionScope.GLOBAL, decision.trace.capabilityPolicyScope)
    }

    @Test
    fun blankContextIdCannotSelectScopedOverride() {
        val capability = Capability.MCP_IMPORT
        val state = CapabilityPermissionState(
            policies = mapOf(capability to CapabilityPolicy.DISABLED),
            scopedPolicies = mapOf(
                CapabilityPermissionScopeKey(CapabilityPermissionScope.ASSISTANT, "assistant-1") to
                    mapOf(capability to CapabilityPolicy.AUTO),
            ),
        )

        val decision = resolver.resolve(
            toolDef = tool("mcp_import_from_skill", needsApproval = true),
            tool = toolCall("mcp_import_from_skill"),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
            capabilityPermissions = state,
            permissionContext = CapabilityPermissionContext(assistantId = ""),
        )

        assertEquals(PermissionDecisionAction.DENY, decision.action)
        assertEquals(CapabilityPermissionScope.GLOBAL, decision.trace.capabilityPolicyScope)
    }
}
