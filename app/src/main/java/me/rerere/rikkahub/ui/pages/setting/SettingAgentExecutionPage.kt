package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AgentOperationPreviewMode
import me.rerere.rikkahub.data.datastore.MAX_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.data.datastore.MIN_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAgentExecutionPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val toolLoopStepOptions = remember { listOf(64, 128, 256, 384, 512) }
    val retryCountOptions = remember { listOf(1, 2, 3, 5) }
    val operationPreviewModeOptions = remember { AgentOperationPreviewMode.entries }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_agent_execution_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
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
                                modifier = Modifier.width(104.dp),
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
