package app.amber.feature.miniapp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.tools.Capability as ToolCapability
import app.amber.feature.tools.CapabilityPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P3-03 tests (plan §P3-03 测试 "未授权发送被拒"):
 *
 * - `capability_permissions` flag off → falls back to the confirm dialog.
 * - policy DISABLED → hard deny with structured `miniapp.send_disabled` code.
 * - policy ASK / unset → requires explicit per-call confirmation.
 * - policy AUTO without the explicit high-risk auto-approval → still requires
 *   confirmation (High risk floor cannot be bypassed by plain auto).
 * - policy AUTO + high-risk auto-approval → allowed unattended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityMiniAppSendGateTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createStore() = PreferenceDataStoreFactory.create {
        File(tempFolder.newFolder(), "capability-miniapp-send.preferences_pb")
    }

    private fun gate(
        flags: CapabilityFlags,
        permissionStore: CapabilityPermissionStore,
        highRiskAuto: Boolean,
    ) = CapabilityMiniAppSendGate(
        capabilityFlags = flags,
        permissionStore = permissionStore,
        highRiskAutoApproved = { highRiskAuto },
    )

    @Test
    fun flagOffFallsBackToConfirmDialog() = runBlocking {
        val flags = CapabilityFlags(createStore())
        val store = CapabilityPermissionStore(createStore())
        // capability_permissions flag off: dialog is the explicit approval.
        assertEquals(
            MiniAppSendDecision.RequireConfirm,
            gate(flags, store, highRiskAuto = true).decide(),
        )
    }

    @Test
    fun disabledPolicyHardDeniesSend() = runBlocking {
        val flags = CapabilityFlags(createStore())
        flags.setEnabled(Capability.CapabilityPermissions, true)
        val store = CapabilityPermissionStore(createStore())
        store.setPolicy(ToolCapability.MINIAPP_SEND, CapabilityPolicy.DISABLED)

        val decision = gate(flags, store, highRiskAuto = true).decide()
        assertTrue(decision is MiniAppSendDecision.Denied)
        assertEquals("miniapp.send_disabled", (decision as MiniAppSendDecision.Denied).code)
    }

    @Test
    fun askPolicyRequiresConfirmation() = runBlocking {
        val flags = CapabilityFlags(createStore())
        flags.setEnabled(Capability.CapabilityPermissions, true)
        val store = CapabilityPermissionStore(createStore())
        store.setPolicy(ToolCapability.MINIAPP_SEND, CapabilityPolicy.ASK)

        assertEquals(
            MiniAppSendDecision.RequireConfirm,
            gate(flags, store, highRiskAuto = false).decide(),
        )
    }

    @Test
    fun unsetPolicyRequiresConfirmation() = runBlocking {
        val flags = CapabilityFlags(createStore())
        flags.setEnabled(Capability.CapabilityPermissions, true)
        val store = CapabilityPermissionStore(createStore())

        assertEquals(
            MiniAppSendDecision.RequireConfirm,
            gate(flags, store, highRiskAuto = false).decide(),
        )
    }

    @Test
    fun autoPolicyWithoutHighRiskAutoStillRequiresConfirmation() = runBlocking {
        val flags = CapabilityFlags(createStore())
        flags.setEnabled(Capability.CapabilityPermissions, true)
        val store = CapabilityPermissionStore(createStore())
        store.setPolicy(ToolCapability.MINIAPP_SEND, CapabilityPolicy.AUTO)

        // High risk floor: plain auto must not let a MiniApp send unattended.
        assertEquals(
            MiniAppSendDecision.RequireConfirm,
            gate(flags, store, highRiskAuto = false).decide(),
        )
    }

    @Test
    fun autoPolicyWithHighRiskAutoAllowsSend() = runBlocking {
        val flags = CapabilityFlags(createStore())
        flags.setEnabled(Capability.CapabilityPermissions, true)
        val store = CapabilityPermissionStore(createStore())
        store.setPolicy(ToolCapability.MINIAPP_SEND, CapabilityPolicy.AUTO)

        assertEquals(
            MiniAppSendDecision.AllowAuto,
            gate(flags, store, highRiskAuto = true).decide(),
        )
    }
}
