package app.amber.feature.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy

/**
 * P2-01 capability policy management + recent approval history
 * (parity plan §P2-01 #4/#5). Read-only history; policies are edited inline.
 */
class SettingCapabilityPermissionsVM(
    private val store: CapabilityPermissionStore,
) : ViewModel() {
    val policies: StateFlow<Map<Capability, CapabilityPolicy>> = store.policyFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val approvalHistory: StateFlow<List<ApprovalHistoryEntry>> = store.approvalHistoryFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** policy == null removes the override (back to risk-floor behavior). */
    fun setPolicy(capability: Capability, policy: CapabilityPolicy?) {
        viewModelScope.launch {
            store.setPolicy(capability, policy)
        }
    }
}
