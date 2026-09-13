package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import app.amber.agent.R
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.core.localization.PermissionDisplayLocalizer
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy
import app.amber.feature.tools.ToolRisk
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-01 capability permissions settings (parity plan §P2-01 #4/#5):
 * per-capability disabled/ask/auto policy + recent approval history.
 * Static capability labels follow the app locale; ids and dynamic audit data
 * remain unchanged.
 */
@Composable
fun SettingCapabilityPermissionsPage(vm: SettingCapabilityPermissionsVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val workspace = workspaceColors()
    val policies by vm.policies.collectAsStateWithLifecycle()
    val history by vm.approvalHistory.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_capability_permissions_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = workspace.row,
                    contentColor = workspace.ink,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.TriangleAlert,
                            contentDescription = null,
                            tint = workspace.muted,
                        )
                        Text(
                            stringResource(R.string.setting_capability_permissions_high_risk_note),
                            style = LocalAmberType.current.secondary,
                        )
                    }
                }
            }

            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_capability_permissions_policy_section),
                ) {
                    Capability.entries.forEach { capability ->
                        item(
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        stringResource(
                                            R.string.setting_capability_permissions_risk_floor,
                                            capability.id,
                                            capability.riskFloor.displayName(),
                                        ),
                                        style = LocalAmberType.current.meta,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        policyChip(
                                            stringResource(R.string.setting_capability_permissions_policy_default),
                                            null,
                                            capability,
                                            policies[capability],
                                            vm::setPolicy,
                                        )
                                        CapabilityPolicy.entries.forEach { mode ->
                                            policyChip(mode.displayName(), mode, capability, policies[capability], vm::setPolicy)
                                        }
                                    }
                                }
                            },
                            headlineContent = {
                                Text(PermissionDisplayLocalizer.capabilityLabel(context, capability))
                            },
                        )
                    }
                }
            }

            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_capability_permissions_recent_approval_section),
                ) {
                    if (history.isEmpty()) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_capability_permissions_no_history))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_capability_permissions_history_desc))
                            },
                        )
                    } else {
                        history.forEach { entry ->
                            item(
                                leadingContent = {
                                    Icon(
                                        if (entry.decision == "approved") Lucide.CircleCheck else Lucide.X,
                                        null,
                                    )
                                },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${entry.capability?.let { PermissionDisplayLocalizer.capabilityLabel(context, it) }
                                                ?: entry.capabilityId
                                                ?: stringResource(R.string.setting_capability_permissions_unmapped)} · ${entry.source} · ${formatTime(entry.approvedAtMs)}",
                                            style = LocalAmberType.current.meta,
                                        )
                                        Text(
                                            stringResource(
                                                R.string.setting_capability_permissions_digest_effect,
                                                entry.argsDigest.take(12),
                                                entry.effectId?.take(8) ?: "-",
                                            ),
                                            style = LocalAmberType.current.meta,
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        stringResource(
                                            R.string.setting_capability_permissions_tool_decision,
                                            entry.toolName,
                                            if (entry.decision == "approved") {
                                                stringResource(R.string.setting_capability_permissions_decision_approved)
                                            } else {
                                                stringResource(R.string.setting_capability_permissions_decision_denied)
                                            },
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun policyChip(
    label: String,
    policy: CapabilityPolicy?,
    capability: Capability,
    current: CapabilityPolicy?,
    onSelect: (Capability, CapabilityPolicy?) -> Unit,
) {
    FilterChip(
        modifier = Modifier.height(32.dp),
        selected = current == policy,
        onClick = { onSelect(capability, policy) },
        label = { Text(label) },
    )
}

@Composable
private fun CapabilityPolicy.displayName(): String = when (this) {
    CapabilityPolicy.DISABLED -> stringResource(R.string.setting_capability_permissions_policy_disabled)
    CapabilityPolicy.ASK -> stringResource(R.string.setting_capability_permissions_policy_ask)
    CapabilityPolicy.AUTO -> stringResource(R.string.setting_capability_permissions_policy_auto)
}

@Composable
private fun ToolRisk.displayName(): String = when (this) {
    ToolRisk.Normal -> stringResource(R.string.setting_capability_permissions_risk_normal)
    ToolRisk.Sensitive -> stringResource(R.string.setting_capability_permissions_risk_sensitive)
    ToolRisk.High -> stringResource(R.string.setting_capability_permissions_risk_high)
}

private val historyTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)

private fun formatTime(ms: Long): String = historyTimeFormat.format(Date(ms))
