package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agent.cron.AgentCronManager
import me.rerere.rikkahub.data.agent.cron.AgentCronTask
import me.rerere.rikkahub.data.agent.task.AgentTaskScheduler
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingAgentExtensionsPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_agent_extensions_page_title)) },
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
                    title = { Text(stringResource(R.string.setting_agent_extensions_page_title)) },
                ) {
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
                        onClick = { navController.navigate(Screen.SettingCronTasks) },
                        leadingContent = { Icon(HugeIcons.Clock02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_cron_tasks_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_cron_tasks)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingAgentRuntimeTasks) },
                        leadingContent = { Icon(HugeIcons.Clock02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_runtime_tasks_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_runtime_tasks)) },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingAgentRuntimeTasksPage(
    taskScheduler: AgentTaskScheduler = koinInject(),
    vm: SettingVM = koinViewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val tasks by taskScheduler.tasksFlow.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val debug = settings.agentRuntime.harnessDebug
    val speculative = settings.agentRuntime.speculativeToolExecution

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_agent_runtime_tasks_page_title)) },
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
                    title = { Text(stringResource(R.string.setting_agent_runtime_tasks_page_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_agent_runtime_tasks_scope_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_agent_runtime_tasks_scope_desc)) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_agent_runtime_debug_permission_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_agent_runtime_debug_permission_desc)) },
                        trailingContent = {
                            Switch(
                                checked = debug.showPermissionReasons,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                harnessDebug = debug.copy(showPermissionReasons = checked)
                                            )
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_agent_runtime_debug_parallel_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_agent_runtime_debug_parallel_desc)) },
                        trailingContent = {
                            Switch(
                                checked = debug.showParallelBatches,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                harnessDebug = debug.copy(showParallelBatches = checked)
                                            )
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_agent_runtime_debug_snapshot_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_agent_runtime_debug_snapshot_desc)) },
                        trailingContent = {
                            Switch(
                                checked = debug.showCapabilitySnapshotSummary,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                harnessDebug = debug.copy(showCapabilitySnapshotSummary = checked)
                                            )
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_agent_runtime_speculative_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_agent_runtime_speculative_desc)) },
                        trailingContent = {
                            Switch(
                                checked = speculative.enabled,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                speculativeToolExecution = speculative.copy(enabled = checked)
                                            )
                                        )
                                    )
                                },
                            )
                        },
                    )
                }
            }
            if (tasks.isEmpty()) {
                item {
                    CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                        item(
                            leadingContent = { Icon(HugeIcons.Clock02, null) },
                            headlineContent = { Text(stringResource(R.string.setting_agent_runtime_tasks_empty_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_agent_runtime_tasks_empty_desc)) },
                        )
                    }
                }
            } else {
                items(tasks, key = { it.taskId }) { task ->
                    CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                        item(
                            leadingContent = { Icon(HugeIcons.Clock02, null) },
                            headlineContent = { Text(task.title) },
                            supportingContent = { AgentTaskSummary(task) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentTaskSummary(task: AgentTaskSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${task.type} · ${task.status.name.lowercase()} · ${task.queueState.name.lowercase()} · ${task.recoveryState.name.lowercase()}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (task.retryPolicy.retryable || task.outputRef?.exists == true) {
            Text(
                text = buildString {
                    if (task.retryPolicy.retryable) append(stringResource(R.string.setting_agent_runtime_tasks_retryable))
                    if (task.retryPolicy.retryable && task.outputRef?.exists == true) append(" · ")
                    if (task.outputRef?.exists == true) append(stringResource(R.string.setting_agent_runtime_tasks_output_readable))
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        task.sourceConversationId?.let {
            Text(
                text = stringResource(R.string.setting_agent_runtime_tasks_source_value, it),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        task.outputPath?.let {
            Text(
                text = stringResource(R.string.setting_agent_runtime_tasks_output_value, it),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.setting_agent_runtime_tasks_updated_value, formatEpochMillis(task.updatedAtMs)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.setting_agent_runtime_tasks_cancel_value,
                if (task.cancelCapability) "yes" else "no",
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        task.error?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = stringResource(R.string.setting_agent_runtime_tasks_error_value, error),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SettingCronTasksPage(
    cronManager: AgentCronManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val tasks by produceState<List<AgentCronTask>>(initialValue = emptyList(), cronManager) {
        value = cronManager.listTasksSnapshot()
        cronManager.tasksFlow.collect { value = it.ifEmpty { cronManager.listTasksSnapshot() } }
    }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_cron_tasks_page_title)) },
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
                    title = { Text(stringResource(R.string.setting_cron_tasks_page_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_cron_tasks_scope_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_cron_tasks_scope_desc)) },
                    )
                }
            }
            if (tasks.isEmpty()) {
                item {
                    CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                        item(
                            leadingContent = { Icon(HugeIcons.Clock02, null) },
                            headlineContent = { Text(stringResource(R.string.setting_cron_tasks_empty_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_cron_tasks_empty_desc)) },
                        )
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                        item(
                            leadingContent = { Icon(HugeIcons.Clock02, null) },
                            headlineContent = { Text(task.title) },
                            supportingContent = { CronTaskSummary(task) },
                            trailingContent = {
                                Switch(
                                    checked = task.enabled,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            cronManager.updateTask(task.id, enabled = checked)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CronTaskSummary(task: AgentCronTask) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = task.prompt,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.setting_cron_tasks_schedule_value,
                task.cronExpression,
                task.timezoneId,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.setting_cron_tasks_next_run_value,
                formatEpochMillis(task.nextRunAtMs),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.setting_cron_tasks_status_value,
                task.lastStatus.name.lowercase(),
                task.runCount,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        task.lastError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = stringResource(R.string.setting_cron_tasks_error_value, error),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatEpochMillis(value: Long?): String {
    if (value == null) return "-"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
}
