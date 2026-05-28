package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import app.amber.core.settings.AgentOperationPreviewMode
import app.amber.core.settings.MAX_AGENT_TOOL_LOOP_STEPS
import app.amber.core.settings.MIN_AGENT_TOOL_LOOP_STEPS
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.theme.CustomColors
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAgentExecutionPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val toolLoopStepOptions = remember { listOf(64, 128, 256, 384, 512) }
    val retryCountOptions = remember { listOf(1, 2, 3, 5) }
    val operationPreviewModeOptions = remember { AgentOperationPreviewMode.entries }
    val liveRefreshOptions = remember { listOf(1_000L, 1_500L, 2_000L, 3_000L) }
    val liveMaxNodeOptions = remember { listOf(80, 120, 180, 240) }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_agent_execution_page_title),
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
                    title = { Text(stringResource(R.string.setting_agent_execution_display_section)) },
                ) {
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
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Sparkles, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generative_ui_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generative_ui)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.generativeUi.enabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                generativeUi = settings.agentRuntime.generativeUi.copy(enabled = checked)
                                            )
                                        )
                                    )
                                }
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
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_agent_execution_live_status_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_status_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_status)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.enableLiveStatusNotification,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                enableLiveStatusNotification = checked
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.LookTop, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_status_privacy_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_status_privacy)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.hideSensitiveLiveStatus,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                hideSensitiveLiveStatus = checked
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_agent_execution_live_mode_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Sparkles, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_mode_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_mode)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.liveMode.enabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                liveMode = settings.agentRuntime.liveMode.copy(enabled = checked)
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Refresh01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_mode_auto_refresh_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_mode_auto_refresh)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.liveMode.autoRefresh,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                liveMode = settings.agentRuntime.liveMode.copy(autoRefresh = checked)
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Refresh01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_mode_refresh_interval_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_mode_refresh_interval)) },
                        trailingContent = {
                            Select(
                                options = liveRefreshOptions,
                                selectedOption = settings.agentRuntime.liveMode.refreshIntervalMs.coerceIn(
                                    liveRefreshOptions.first(),
                                    liveRefreshOptions.last(),
                                ),
                                onOptionSelected = { interval ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                liveMode = settings.agentRuntime.liveMode.copy(refreshIntervalMs = interval)
                                            )
                                        )
                                    )
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_live_mode_refresh_interval_value, it / 1_000f)
                                },
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_mode_max_nodes_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_mode_max_nodes)) },
                        trailingContent = {
                            Select(
                                options = liveMaxNodeOptions,
                                selectedOption = settings.agentRuntime.liveMode.maxNodes.coerceIn(
                                    liveMaxNodeOptions.first(),
                                    liveMaxNodeOptions.last(),
                                ),
                                onOptionSelected = { maxNodes ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                liveMode = settings.agentRuntime.liveMode.copy(maxNodes = maxNodes)
                                            )
                                        )
                                    )
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_live_mode_max_nodes_value, it)
                                },
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.VolumeHigh, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_mode_voice_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_mode_voice)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.liveMode.voiceInputEnabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                liveMode = settings.agentRuntime.liveMode.copy(voiceInputEnabled = checked)
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_agent_execution_stability_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Code, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generation_retry_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generation_retry)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.generationRetry.enabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                generationRetry = settings.agentRuntime.generationRetry.copy(
                                                    enabled = checked
                                                )
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Code, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generation_retry_count_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generation_retry_count)) },
                        trailingContent = {
                            Select(
                                options = retryCountOptions,
                                selectedOption = settings.agentRuntime.generationRetry.maxRetries.coerceIn(1, 5),
                                onOptionSelected = { maxRetries ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                generationRetry = settings.agentRuntime.generationRetry.copy(
                                                    maxRetries = maxRetries
                                                )
                                            )
                                        )
                                    )
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_generation_retry_count_value, it)
                                },
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generation_keepalive_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generation_keepalive)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.keepGenerationAliveInBackground,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                keepGenerationAliveInBackground = checked
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}
