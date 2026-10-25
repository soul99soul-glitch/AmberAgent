package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.SearchCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Megaphone
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Sparkles
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.core.settings.AgentOperationPreviewMode
import app.amber.core.settings.MAX_AGENT_TOOL_LOOP_STEPS
import app.amber.core.settings.MIN_AGENT_TOOL_LOOP_STEPS
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.CustomColors
import app.amber.core.jev.PerAppSuccessPathClearResult
import app.amber.core.jev.PerAppSuccessPathStore
import app.amber.core.jev.clearPerAppSuccessPaths
import app.amber.core.jev.showRetryError
import app.amber.core.utils.plus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingAgentExecutionPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    // 384 保留：已有用户可能正停在该值，删掉会让 Select 找不到选项。
    val toolLoopStepOptions = remember { listOf(64, 128, 256, 384, 512, 1024, 2048, 8192) }
    val retryCountOptions = remember { listOf(1, 2, 3, 5) }
    val operationPreviewModeOptions = remember { AgentOperationPreviewMode.entries }
    val liveRefreshOptions = remember { listOf(1_000L, 1_500L, 2_000L, 3_000L) }
    val liveMaxNodeOptions = remember { listOf(80, 120, 180, 240) }
    // 手机自控白名单是 screen_run_goal 唯一可进入的应用集合，用户必须能看见并撤销。
    val allowedScreenPackages = settings.agentRuntime.screenAutomation.allowedPackages
    var showScreenScopeDialog by remember { mutableStateOf(false) }
    var showClearExperienceDialog by remember { mutableStateOf(false) }
    var showClearExperienceError by remember { mutableStateOf(false) }
    var clearingExperience by remember { mutableStateOf(false) }
    val successPathStore = koinInject<PerAppSuccessPathStore>()
    val scope = rememberCoroutineScope()
    var experienceHasPaths by remember { mutableStateOf(false) }
    var experienceTick by remember { mutableStateOf(0) }
    fun requestClearExperience() {
        if (clearingExperience) return
        clearingExperience = true
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { clearPerAppSuccessPaths(successPathStore) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PerAppSuccessPathClearResult.Failed("persist_failed", error)
            }
            withContext(Dispatchers.Main) {
                clearingExperience = false
                showClearExperienceDialog = false
                experienceTick++
                showClearExperienceError = result.showRetryError()
            }
        }
    }
    LaunchedEffect(experienceTick) {
        experienceHasPaths = withContext(Dispatchers.IO) { successPathStore.hasPaths() }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_agent_execution_page_title),
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
                    title = stringResource(R.string.setting_agent_execution_environment_section),
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingSandbox) },
                        leadingContent = { SettingTileIcon(Lucide.CodeXml) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_sandbox_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_sandbox)) },
                        trailingContent = {
                            Icon(
                                Lucide.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = workspaceColors().muted,
                            )
                        },
                    )
                }
            }
            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_agent_execution_display_section),
                ) {
                    item(
                        leadingContent = { SettingTileIcon(Lucide.SearchCheck) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_operation_preview)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_page_agent_operation_preview_desc))
                                SettingSegmentedChoice(
                                    options = operationPreviewModeOptions,
                                    selected = settings.agentRuntime.operationPreviewMode,
                                    onSelected = { mode ->
                                        vm.updateSettings { current ->
                                            current.copy(
                                                agentRuntime = current.agentRuntime.copy(
                                                    operationPreviewMode = mode
                                                )
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { mode ->
                                        Text(
                                            when (mode) {
                                                AgentOperationPreviewMode.ALWAYS ->
                                                    stringResource(R.string.setting_page_agent_operation_preview_always)

                                                AgentOperationPreviewMode.AUTO ->
                                                    stringResource(R.string.setting_page_agent_operation_preview_auto)

                                                AgentOperationPreviewMode.HIDDEN ->
                                                    stringResource(R.string.setting_page_agent_operation_preview_hidden)
                                            },
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.Sparkles) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generative_ui_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generative_ui)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.generativeUi.enabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                generativeUi = current.agentRuntime.generativeUi.copy(enabled = checked)
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.CodeXml) },
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
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                maxToolLoopSteps = steps
                                            )
                                        )
                                    }
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_tool_loop_steps_value, it)
                                },
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        modifier = Modifier.heightIn(min = 56.dp),
                        onClick = { showScreenScopeDialog = true },
                        leadingContent = { SettingTileIcon(Lucide.Smartphone) },
                        supportingContent = {
                            Text(
                                if (allowedScreenPackages.isEmpty()) {
                                    stringResource(R.string.setting_agent_screen_scope_empty)
                                } else {
                                    allowedScreenPackages.joinToString("、").take(120)
                                },
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_agent_screen_scope_title)) },
                        trailingContent = {
                            Icon(
                                imageVector = Lucide.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    item(
                        modifier = Modifier.heightIn(min = 56.dp),
                        leadingContent = { SettingTileIcon(Lucide.Eraser) },
                        headlineContent = { Text(stringResource(R.string.setting_agent_screen_experience_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_agent_screen_experience_desc))
                        },
                        trailingContent = {
                            TextButton(
                                modifier = Modifier.heightIn(min = 48.dp),
                                enabled = experienceHasPaths,
                                onClick = { showClearExperienceDialog = true },
                            ) {
                                Text(stringResource(R.string.setting_agent_screen_experience_clear))
                            }
                        },
                    )
                }
            }

            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_agent_execution_live_status_section),
                ) {
                    item(
                        leadingContent = { SettingTileIcon(Lucide.Megaphone) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_status_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_status)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.enableLiveStatusNotification,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                enableLiveStatusNotification = checked
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.SearchCheck) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_status_privacy_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_status_privacy)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.hideSensitiveLiveStatus,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                hideSensitiveLiveStatus = checked
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.MessageCircle) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_task_bubble_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_task_bubble)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.agentTaskBubbleEnabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                agentTaskBubbleEnabled = checked
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.RefreshCw) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_auto_return_to_host_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_auto_return_to_host)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.autoReturnToHostOnScreenGoalComplete,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                autoReturnToHostOnScreenGoalComplete = checked
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                }
            }

            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_agent_execution_live_mode_section),
                ) {
                    item(
                        leadingContent = { SettingTileIcon(Lucide.Sparkles) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_mode_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_mode)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.liveMode.enabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                liveMode = current.agentRuntime.liveMode.copy(enabled = checked)
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.RefreshCw) },
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
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                liveMode = current.agentRuntime.liveMode.copy(refreshIntervalMs = interval)
                                            )
                                        )
                                    }
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_live_mode_refresh_interval_value, it / 1_000f)
                                },
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.CodeXml) },
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
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                liveMode = current.agentRuntime.liveMode.copy(maxNodes = maxNodes)
                                            )
                                        )
                                    }
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_live_mode_max_nodes_value, it)
                                },
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.LiveCompanion) },
                        leadingContent = { SettingTileIcon(Lucide.Eye) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_live_mode_open_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_live_mode_open)) },
                        trailingContent = {
                            Icon(
                                Lucide.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = workspaceColors().muted,
                            )
                        },
                    )
                }
            }

            item {
                SettingCardGroup(
                    title = stringResource(R.string.setting_agent_execution_stability_section),
                ) {
                    item(
                        leadingContent = { SettingTileIcon(Lucide.CodeXml) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generation_retry_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generation_retry)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.generationRetry.enabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                generationRetry = current.agentRuntime.generationRetry.copy(
                                                    enabled = checked
                                                )
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.CodeXml) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generation_retry_count_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generation_retry_count)) },
                        trailingContent = {
                            Select(
                                options = retryCountOptions,
                                selectedOption = settings.agentRuntime.generationRetry.maxRetries.coerceIn(1, 5),
                                onOptionSelected = { maxRetries ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                generationRetry = current.agentRuntime.generationRetry.copy(
                                                    maxRetries = maxRetries
                                                )
                                            )
                                        )
                                    }
                                },
                                optionToString = {
                                    stringResource(R.string.setting_page_agent_generation_retry_count_value, it)
                                },
                                // V3 ValueChip 内容自适应,
                            )
                        },
                    )
                    item(
                        leadingContent = { SettingTileIcon(Lucide.Megaphone) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_generation_keepalive_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_generation_keepalive)) },
                        trailingContent = {
                            Switch(
                                checked = settings.agentRuntime.keepGenerationAliveInBackground,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { current ->
                                        current.copy(
                                            agentRuntime = current.agentRuntime.copy(
                                                keepGenerationAliveInBackground = checked
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
    if (showScreenScopeDialog) {
        ScreenScopeDialog(
            packages = allowedScreenPackages,
            onRemove = { packageName ->
                vm.updateSettings { current ->
                    val scope = current.agentRuntime.screenAutomation
                    current.copy(
                        agentRuntime = current.agentRuntime.copy(
                            screenAutomation = scope.copy(
                                allowedPackages = scope.allowedPackages - packageName,
                            ),
                        ),
                    )
                }
            },
            onDismiss = { showScreenScopeDialog = false },
        )
    }
    if (showClearExperienceDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!clearingExperience) showClearExperienceDialog = false
            },
            title = { Text(stringResource(R.string.setting_agent_screen_experience_clear_confirm_title)) },
            text = { Text(stringResource(R.string.setting_agent_screen_experience_clear_confirm)) },
            confirmButton = {
                TextButton(
                    enabled = !clearingExperience,
                    onClick = { requestClearExperience() },
                ) {
                    Text(stringResource(R.string.setting_agent_screen_experience_clear))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearingExperience,
                    onClick = { showClearExperienceDialog = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showClearExperienceError) {
        AlertDialog(
            onDismissRequest = { showClearExperienceError = false },
            title = { Text(stringResource(R.string.setting_agent_screen_experience_clear_failed_title)) },
            text = { Text(stringResource(R.string.setting_agent_screen_experience_clear_failed)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearExperienceError = false
                        requestClearExperience()
                    },
                ) {
                    Text(stringResource(R.string.retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearExperienceError = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    }
}

/**
 * 手机自控白名单的查看与撤销。白名单由 screen_add_app 写入；这里只做展示与撤销，
 * 撤销立即落盘，下一次 screen_run_goal 会拒绝该应用。
 */
@Composable
private fun ScreenScopeDialog(
    packages: List<String>,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_agent_screen_scope_title)) },
        text = {
            if (packages.isEmpty()) {
                Text(stringResource(R.string.setting_agent_screen_scope_empty))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    packages.forEach { packageName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = packageName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { onRemove(packageName) }) {
                                Text(stringResource(R.string.setting_agent_screen_scope_remove))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
