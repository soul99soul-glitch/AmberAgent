package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Zap
import app.amber.agent.R
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.agent.Screen
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.CustomColors
import app.amber.core.utils.plus
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAgentPermissionsPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showHighRiskAutoApproveDialog by remember { mutableStateOf(false) }
    // Capability 权限页入口仅在 capability_permissions flag 开启时可见
    // （与入口文案的声明一致；Debug 页负责开关该 flag）。
    val capabilityFlags: CapabilityFlags = koinInject()
    val capabilityPermissionsEnabled by remember(capabilityFlags) {
        capabilityFlags.flow.map { Capability.CapabilityPermissions in it.enabled }
    }.collectAsStateWithLifecycle(initialValue = false)

    if (showHighRiskAutoApproveDialog) {
        AlertDialog(
            onDismissRequest = { showHighRiskAutoApproveDialog = false },
            icon = { Icon(Lucide.TriangleAlert, null) },
            title = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_confirm_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        showHighRiskAutoApproveDialog = false
                        vm.updateSettings { current ->
                            current.copy(
                                agentRuntime = current.agentRuntime.copy(
                                    autoApproveHighRiskToolCalls = true
                                )
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHighRiskAutoApproveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_agent_permissions_page_title),
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
                SettingCardGroup(
                    title = stringResource(R.string.setting_agent_permissions_access_section),
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingSystemAccess) },
                        leadingContent = { SettingTileIcon(Lucide.Settings) },
                        supportingContent = { Text(stringResource(R.string.setting_page_system_access_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_system_access)) },
                    )
                    if (capabilityPermissionsEnabled) {
                        item(
                            onClick = { navController.navigate(Screen.SettingCapabilityPermissions) },
                            leadingContent = { SettingTileIcon(Lucide.Layers) },
                            supportingContent = { Text(stringResource(R.string.setting_agent_permissions_capability_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_agent_permissions_capability_title)) },
                        )
                    }
                }
            }

            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_agent_permissions_approval_section),
                ) {
                    item(
                        leadingContent = { SettingTileIcon(Lucide.Zap) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_auto_approve_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_auto_approve)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.autoApproveAllToolCalls,
                                enabled = !settings.init,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                autoApproveAllToolCalls = checked
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.TriangleAlert) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.autoApproveHighRiskToolCalls,
                                enabled = !settings.init,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showHighRiskAutoApproveDialog = true
                                    } else {
                                        vm.updateSettings { current ->
                                            current.copy(
                                                agentRuntime = current.agentRuntime.copy(
                                                    autoApproveHighRiskToolCalls = false
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}
