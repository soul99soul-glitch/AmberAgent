package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Developer
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.WavingHand01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.AgentOperationPreviewMode
import me.rerere.rikkahub.data.datastore.MAX_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.data.datastore.MIN_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val toolLoopStepOptions = remember { listOf(64, 128, 256, 384, 512) }
    val operationPreviewModeOptions = remember { AgentOperationPreviewMode.entries }
    var showHighRiskAutoApproveDialog by remember { mutableStateOf(false) }

    if (settings.launchCount > 100 && (settings.launchCount - settings.sponsorAlertDismissedAt) >= 50) {
        AlertDialog(
            onDismissRequest = {
                vm.updateSettings(settings.copy(sponsorAlertDismissedAt = settings.launchCount))
            },
            icon = { Icon(HugeIcons.WavingHand01, null) },
            title = { Text(stringResource(R.string.setting_page_sponsor_alert_title)) },
            text = { Text(stringResource(R.string.setting_page_sponsor_alert_desc)) },
            confirmButton = {
                Button(onClick = {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = settings.launchCount))
                    navController.navigate(Screen.SettingDonate)
                }) {
                    Text(stringResource(R.string.setting_page_sponsor_alert_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = settings.launchCount))
                }) {
                    Text(stringResource(R.string.setting_page_sponsor_alert_dismiss))
                }
            },
        )
    }

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
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    if(settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            }
                        ) {
                            Icon(HugeIcons.Developer, "Developer")
                        }
                    }
                },
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            item("generalSettings") {
                var colorMode by rememberColorMode()
                val selectedColorModeText = when (colorMode) {
                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Sun01, null) },
                        trailingContent = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = {
                                    colorMode = it
                                    navController.navigate(Screen.Setting) {
                                        popUpTo(Screen.Setting) {
                                            inclusive = true
                                        }
                                    }
                                },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                                modifier = Modifier.width(150.dp)
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                        supportingContent = { Text(selectedColorModeText) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingDisplay) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_display_setting_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_display_setting)) },
                    )
                }
            }

            item("agentRuntimeSettings") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_agent_runtime)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentMemory) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_memory_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_memory)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Skills) },
                        leadingContent = { Icon(HugeIcons.Package, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_skills_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_skills)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Extensions) },
                        leadingContent = { Icon(HugeIcons.Package, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { Icon(HugeIcons.McpServer, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSandbox) },
                        leadingContent = { Icon(HugeIcons.Code, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_sandbox_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_sandbox)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSystemAccess) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_system_access_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_system_access)) },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.LookTop, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_operation_preview_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_operation_preview)) },
                        trailingContent = {
                            Select(
                                options = operationPreviewModeOptions,
                                selectedOption = settings.agentRuntime.operationPreviewMode,
                                onOptionSelected = { mode ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                operationPreviewMode = mode
                                            )
                                        )
                                    )
                                },
                                optionToString = { mode ->
                                    when (mode) {
                                        AgentOperationPreviewMode.ALWAYS ->
                                            stringResource(R.string.setting_page_agent_operation_preview_always)

                                        AgentOperationPreviewMode.AUTO ->
                                            stringResource(R.string.setting_page_agent_operation_preview_auto)

                                        AgentOperationPreviewMode.HIDDEN ->
                                            stringResource(R.string.setting_page_agent_operation_preview_hidden)
                                    }
                                },
                                modifier = Modifier.width(116.dp),
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_tool_loop_steps_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_tool_loop_steps)) },
                        trailingContent = {
                            Select(
                                options = toolLoopStepOptions,
                                selectedOption = settings.agentRuntime.maxToolLoopSteps.coerceIn(
                                    MIN_AGENT_TOOL_LOOP_STEPS,
                                    MAX_AGENT_TOOL_LOOP_STEPS,
                                ),
                                onOptionSelected = { steps ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                maxToolLoopSteps = steps
                                            )
                                        )
                                    )
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_tool_loop_steps_value, it)
                                },
                                modifier = Modifier.width(120.dp),
                            )
                        },
                    )
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

            item("modelServices") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_model_and_services)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { Icon(HugeIcons.AiMagic, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { Icon(HugeIcons.Brain02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTTS) },
                        leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                    )
                }
            }

            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_data_settings)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { Icon(HugeIcons.Database02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                        supportingContent = {
                            if (storageState.first == -1) {
                                Text(stringResource(R.string.calculating))
                            } else {
                                Text(
                                    stringResource(
                                        R.string.setting_page_chat_storage_desc,
                                        storageState.first,
                                        storageState.second / 1024 / 1024.0
                                    )
                                )
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.setting_page_config_api_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}
