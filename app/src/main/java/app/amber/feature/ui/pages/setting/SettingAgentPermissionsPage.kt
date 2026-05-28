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
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Zap
import app.amber.agent.R
import me.rerere.rikkahub.Screen
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.CustomColors
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAgentPermissionsPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showHighRiskAutoApproveDialog by remember { mutableStateOf(false) }

    if (showHighRiskAutoApproveDialog) {
        AlertDialog(
            onDismissRequest = { showHighRiskAutoApproveDialog = false },
            icon = { Icon(HugeIcons.Alert01, null) },
            title = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_confirm_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        showHighRiskAutoApproveDialog = false
                        vm.updateSettings(
                            settings.copy(
                                agentRuntime = settings.agentRuntime.copy(
                                    autoApproveHighRiskToolCalls = true
                                )
                            )
                        )
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_agent_permissions_access_section)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingSystemAccess) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_system_access_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_system_access)) },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_agent_permissions_approval_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Zap, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_auto_approve_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_auto_approve)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.autoApproveAllToolCalls,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                autoApproveAllToolCalls = checked
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Alert01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.autoApproveHighRiskToolCalls,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showHighRiskAutoApproveDialog = true
                                    } else {
                                        vm.updateSettings(
                                            settings.copy(
                                                agentRuntime = settings.agentRuntime.copy(
                                                    autoApproveHighRiskToolCalls = false
                                                )
                                            )
                                        )
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
