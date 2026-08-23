package app.amber.feature.miniapp

import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.tools.Capability as ToolCapability
import app.amber.feature.tools.CapabilityPolicy

/** P3-03 decision for a MiniApp "写入并发送" (mode=send) request. */
sealed interface MiniAppSendDecision {
    /** Explicit per-capability AUTO + global high-risk auto-approval: no dialog. */
    data object AllowAuto : MiniAppSendDecision

    /** Requires the per-call user confirmation dialog (the explicit approval). */
    data object RequireConfirm : MiniAppSendDecision

    /** Capability policy hard-blocks auto-send. */
    data class Denied(val code: String, val message: String) : MiniAppSendDecision
}

/** Gate consulted by the bridge before a MiniApp-triggered send. */
interface MiniAppSendGate {
    suspend fun decide(): MiniAppSendDecision
}

/**
 * P3-03 gate for MiniApp-triggered sends. Auto-send is a separate capability
 * (`miniapp.send`, risk floor High — see `Capability.MINIAPP_SEND` mapping):
 *
 * - `capability_permissions` flag off → fall back to the dialog (explicit
 *   per-call user approval, same as before);
 * - policy DISABLED → hard deny with a structured code;
 * - policy ASK / unset → dialog (explicit approval);
 * - policy AUTO → allowed without a dialog only when the explicit high-risk
 *   auto-approval setting is on — a plain auto-approve can never bypass a
 *   High-risk floor (P2-01 rule).
 */
class CapabilityMiniAppSendGate(
    private val capabilityFlags: CapabilityFlags,
    private val permissionStore: CapabilityPermissionStore,
    private val highRiskAutoApproved: () -> Boolean,
) : MiniAppSendGate {
    override suspend fun decide(): MiniAppSendDecision {
        val capabilityPermissionsOn = capabilityFlags.isEnabled(Capability.CapabilityPermissions)
        if (!capabilityPermissionsOn) return MiniAppSendDecision.RequireConfirm
        return when (permissionStore.policies()[ToolCapability.MINIAPP_SEND]) {
            CapabilityPolicy.DISABLED ->
                MiniAppSendDecision.Denied("miniapp.send_disabled", "「写入并发送」能力已被策略禁用")
            CapabilityPolicy.AUTO ->
                if (highRiskAutoApproved()) {
                    MiniAppSendDecision.AllowAuto
                } else {
                    MiniAppSendDecision.RequireConfirm
                }
            CapabilityPolicy.ASK, null -> MiniAppSendDecision.RequireConfirm
        }
    }
}
