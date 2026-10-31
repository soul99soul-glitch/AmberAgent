package app.amber.agent.data.agent.runtime

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.PermissionDecisionAction
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.tools.ToolEffectClass
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.effectClass
import app.amber.feature.tools.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approval contract for the SSH profile tools, verified through the real
 * tool metadata pipeline (ToolRegistry) and the production
 * [PermissionDecisionResolver] — the same pair the dispatcher uses at runtime.
 *
 * The rule the user asked for: the three writers are High risk, so ordinary
 * auto-approval must never release them, while the explicit high-risk
 * auto-approval switch runs them unattended like any other High-risk tool.
 */
class SshProfileToolApprovalTest {
    private val resolver = PermissionDecisionResolver()

    private val registry = ToolRegistry.from(
        listOf(
            sshTool("ssh_profile_list", needsApproval = false, allowsAutoApproval = true),
            sshTool("ssh_profile_create"),
            sshTool("ssh_profile_update"),
            sshTool("ssh_profile_delete"),
            sshTool("ssh_key_list", needsApproval = false, allowsAutoApproval = true),
            sshTool("ssh_key_generate"),
            sshTool("ssh_key_import"),
            sshTool("ssh_key_delete"),
        ),
    )

    private val listInput = "{}"
    private val createInput = """{"name":"web","host":"h","username":"u","password":"pw"}"""
    private val updateInput = """{"id":"p1","name":"renamed"}"""
    private val deleteInput = """{"id":"p1"}"""
    private val keyGenInput = """{"name":"k1","type":"ed25519"}"""
    private val keyImportInput = """{"name":"k1","private_key":"-----BEGIN-----"}"""
    private val keyDeleteInput = """{"key_id":"k1"}"""

    // ---------------- metadata ----------------

    @Test
    fun `registry marks the writers High risk and non-auto-approvable`() {
        listOf(
            "ssh_profile_create",
            "ssh_profile_update",
            "ssh_profile_delete",
            "ssh_key_generate",
            "ssh_key_import",
            "ssh_key_delete",
        ).forEach { name ->
            val metadata = registry.metadataFor(name)!!
            assertEquals(name, ToolRisk.High, metadata.risk)
            assertTrue(name, metadata.mutates)
            assertTrue(name, metadata.needsApproval)
            assertFalse(name, metadata.autoApprovable)
        }
        listOf("ssh_profile_list", "ssh_key_list").forEach { readName ->
            val list = registry.metadataFor(readName)!!
            assertEquals(ToolRisk.Normal, list.risk)
            assertFalse(list.mutates)
            assertFalse(list.needsApproval)
            assertTrue(list.autoApprovable)
        }
    }

    @Test
    fun `registry classifies the writers as non-idempotent external effects`() {
        listOf(
            "ssh_profile_create",
            "ssh_profile_update",
            "ssh_profile_delete",
            "ssh_key_generate",
            "ssh_key_import",
            "ssh_key_delete",
        ).forEach { name ->
            val effectClass = registry.tools().first { it.name == name }.effectClass()
            assertEquals(name, ToolEffectClass.NON_IDEMPOTENT_WRITE, effectClass)
        }
    }

    // ---------------- ordinary auto-approval must not release a write ----------------

    @Test
    fun `ordinary auto-approval does not release any writer`() {
        listOf(
            "ssh_profile_create" to createInput,
            "ssh_profile_update" to updateInput,
            "ssh_profile_delete" to deleteInput,
            "ssh_key_generate" to keyGenInput,
            "ssh_key_import" to keyImportInput,
            "ssh_key_delete" to keyDeleteInput,
        ).forEach { (name, input) ->
            val decision = resolve(name, input, autoApproveTools = true, highRisk = false)
            assertEquals(name, PermissionDecisionAction.ASK, decision.action)
            assertEquals(name, "risk", decision.source)
        }
    }

    @Test
    fun `neither switch releases a writer when both are off`() {
        listOf(
            "ssh_profile_create" to createInput,
            "ssh_profile_update" to updateInput,
            "ssh_profile_delete" to deleteInput,
        ).forEach { (name, input) ->
            val decision = resolve(name, input, autoApproveTools = false, highRisk = false)
            assertEquals(name, PermissionDecisionAction.ASK, decision.action)
        }
    }

    @Test
    fun `prior in-run trust does not release a writer`() {
        val decision = resolver.resolve(
            toolDef = registry.tools().first { it.name == "ssh_profile_delete" },
            tool = toolCall("ssh_profile_delete", deleteInput),
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
            autoApprovedToolNames = setOf("ssh_profile_delete"),
        )
        assertEquals(PermissionDecisionAction.ASK, decision.action)
        assertEquals("risk", decision.source)
    }

    // ---------------- the explicit high-risk switch does release it ----------------

    @Test
    fun `high-risk auto-approval runs every writer unattended`() {
        listOf(
            "ssh_profile_create" to createInput,
            "ssh_profile_update" to updateInput,
            "ssh_profile_delete" to deleteInput,
        ).forEach { (name, input) ->
            val decision = resolve(name, input, autoApproveTools = false, highRisk = true)
            assertEquals(name, PermissionDecisionAction.ALLOW, decision.action)
            assertEquals(name, "settings_unattended", decision.source)
        }
    }

    @Test
    fun `high-risk auto-approval alone is enough even when the regular switch is off`() {
        val decision = resolve(
            "ssh_profile_delete",
            deleteInput,
            autoApproveTools = false,
            highRisk = true,
        )
        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
    }

    // ---------------- list needs no approval either way ----------------

    @Test
    fun `list runs without approval under every switch combination`() {
        listOf(false to false, true to false, false to true, true to true).forEach { (auto, highRisk) ->
            val decision = resolve("ssh_profile_list", listInput, auto, highRisk)
            assertEquals("auto=$auto highRisk=$highRisk", PermissionDecisionAction.ALLOW, decision.action)
        }
        // With no unattended switch the read is released by its own policy, not
        // by a settings shortcut — a writer never gets that path.
        assertEquals("policy", resolve("ssh_profile_list", listInput, false, false).source)
    }

    @Test
    fun `a user denial is honoured above every auto-approval switch`() {
        val decision = resolver.resolve(
            toolDef = registry.tools().first { it.name == "ssh_profile_delete" },
            tool = toolCall("ssh_profile_delete", deleteInput).copy(
                approvalState = app.amber.ai.ui.ToolApprovalState.Denied("nope"),
            ),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
        )
        assertEquals(PermissionDecisionAction.ALLOW, decision.action)
        // approval_state short-circuits here; the dispatcher then emits the
        // structured denial instead of executing. The decision itself must not
        // invent a second approval gate for an already-decided call.
        assertEquals("approval_state", decision.source)
    }

    // ---------------- helpers ----------------

    private fun resolve(
        name: String,
        input: String,
        autoApproveTools: Boolean,
        highRisk: Boolean,
    ) = resolver.resolve(
        toolDef = registry.tools().first { it.name == name },
        tool = toolCall(name, input),
        autoApproveTools = autoApproveTools,
        autoApproveHighRiskTools = highRisk,
    )

    private fun toolCall(name: String, input: String = "{}") = UIMessagePart.Tool(
        toolCallId = "call_$name",
        toolName = name,
        input = input,
    )

    private fun sshTool(
        name: String,
        needsApproval: Boolean = true,
        allowsAutoApproval: Boolean = false,
    ) = Tool(
        name = name,
        description = "",
        needsApproval = needsApproval,
        allowsAutoApproval = allowsAutoApproval,
        execute = { emptyList() },
    )
}
