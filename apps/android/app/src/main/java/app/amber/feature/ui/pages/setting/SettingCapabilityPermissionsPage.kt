package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Cancel01
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy
import app.amber.feature.tools.ToolRisk
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-01 capability permissions settings (parity plan §P2-01 #4/#5):
 * per-capability disabled/ask/auto policy + recent approval history.
 * New UI copy is not localized (repo convention).
 */
@Composable
fun SettingCapabilityPermissionsPage(vm: SettingCapabilityPermissionsVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val policies by vm.policies.collectAsStateWithLifecycle()
    val history by vm.approvalHistory.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "Capability 权限",
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "「自动」策略对高风险能力（风险底线 High）仍需同时开启全局「自动批准」与「高风险自动批准」；禁用单个能力不影响其他能力。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { SectionLabel("Capability 策略") },
                ) {
                    Capability.entries.forEach { capability ->
                        item(
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "${capability.id} · 风险底线 ${capability.riskFloor.displayName()}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        policyChip("默认", null, capability, policies[capability], vm::setPolicy)
                                        CapabilityPolicy.entries.forEach { mode ->
                                            policyChip(mode.displayName(), mode, capability, policies[capability], vm::setPolicy)
                                        }
                                    }
                                }
                            },
                            headlineContent = { Text(capability.label) },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { SectionLabel("最近审批") },
                ) {
                    if (history.isEmpty()) {
                        item(
                            headlineContent = { Text("暂无审批记录") },
                            supportingContent = { Text("批准或拒绝工具调用后会显示在这里（仅保存参数摘要，不保存参数明文）。") },
                        )
                    } else {
                        history.forEach { entry ->
                            item(
                                leadingContent = {
                                    Icon(
                                        if (entry.decision == "approved") HugeIcons.CheckmarkCircle02 else HugeIcons.Cancel01,
                                        null,
                                    )
                                },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${entry.capability?.label ?: entry.capabilityId ?: "未映射"} · ${entry.source} · ${formatTime(entry.approvedAtMs)}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "digest ${entry.argsDigest.take(12)}… · effect ${entry.effectId?.take(8) ?: "-"}",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                },
                                headlineContent = { Text("${entry.toolName} · ${if (entry.decision == "approved") "批准" else "拒绝"}") },
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
        selected = current == policy,
        onClick = { onSelect(capability, policy) },
        label = { Text(label) },
    )
}

private fun CapabilityPolicy.displayName(): String = when (this) {
    CapabilityPolicy.DISABLED -> "禁用"
    CapabilityPolicy.ASK -> "询问"
    CapabilityPolicy.AUTO -> "自动"
}

private fun ToolRisk.displayName(): String = when (this) {
    ToolRisk.Normal -> "普通"
    ToolRisk.Sensitive -> "敏感"
    ToolRisk.High -> "高"
}

private val historyTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)

private fun formatTime(ms: Long): String = historyTimeFormat.format(Date(ms))
