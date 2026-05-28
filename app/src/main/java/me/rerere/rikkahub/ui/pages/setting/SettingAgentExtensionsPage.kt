package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import app.amber.feature.cron.AgentCronManager
import app.amber.feature.cron.AgentCronTask
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskSnapshot
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.WorkspaceTopBar
import me.rerere.rikkahub.ui.components.ui.workspaceColors
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
            WorkspaceTopBar(
                title = stringResource(R.string.setting_agent_extensions_page_title),
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
                        onClick = { navController.navigate(Screen.SettingSlidesFonts) },
                        leadingContent = { Icon(HugeIcons.Package, null) },
                        supportingContent = { Text("下载中文字体包，提高生成式幻灯片的还原度") },
                        headlineContent = { Text("Slides 字体资源") },
                    )
                    // "Agent 运行时任务" entry intentionally hidden from end users — that page
                    // is a developer-facing harness debug view (审批原因 / 并发批次 / 能力快照
                    // / 提前执行只读工具 / 完整生成历史). The page itself is kept and reachable
                    // by route in case we want to surface it again later behind a hidden gesture.
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
            WorkspaceTopBar(
                title = stringResource(R.string.setting_agent_runtime_tasks_page_title),
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
    val navController = LocalNavController.current
    val tasks by produceState<List<AgentCronTask>>(initialValue = emptyList(), cronManager) {
        value = cronManager.listTasksSnapshot()
        cronManager.tasksFlow.collect { value = it.ifEmpty { cronManager.listTasksSnapshot() } }
    }
    val scope = rememberCoroutineScope()
    var selectedTask by remember { mutableStateOf<AgentCronTask?>(null) }
    var deleteTask by remember { mutableStateOf<AgentCronTask?>(null) }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_cron_tasks_page_title),
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
                            onClick = { selectedTask = task },
                            leadingContent = { Icon(HugeIcons.Clock02, null) },
                            headlineContent = { Text(task.title) },
                            supportingContent = { CronTaskSummary(task) },
                            trailingContent = {
                                CronTaskActions(
                                    task = task,
                                    onShowDetails = { selectedTask = task },
                                    onToggle = { checked ->
                                        scope.launch {
                                            cronManager.updateTask(task.id, enabled = checked)
                                        }
                                    },
                                    onRunNow = {
                                        scope.launch {
                                            cronManager.runTaskNow(task.id)
                                        }
                                    },
                                    onDelete = { deleteTask = task },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    selectedTask?.let { task ->
        CronTaskDetailDialog(
            task = task,
            onDismiss = { selectedTask = null },
            onOpenConversation = {
                selectedTask = null
                navController.navigate(Screen.Chat(task.conversationId))
            },
            onRunNow = {
                scope.launch {
                    cronManager.runTaskNow(task.id)
                }
            },
            onDelete = {
                selectedTask = null
                deleteTask = task
            },
        )
    }

    deleteTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTask = null },
            title = { Text("删除定时任务") },
            text = { Text("确定删除「${task.title}」吗？这会取消后续计划运行。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            cronManager.deleteTask(task.id)
                            deleteTask = null
                        }
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTask = null }) {
                    Text("取消")
                }
            },
        )
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

@Composable
private fun CronTaskActions(
    task: AgentCronTask,
    onShowDetails: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row {
        Switch(
            checked = task.enabled,
            onCheckedChange = onToggle,
        )
        IconButton(onClick = { expanded = true }) {
            Icon(HugeIcons.MoreVertical, contentDescription = "更多操作")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("查看详情") },
                onClick = {
                    expanded = false
                    onShowDetails()
                },
            )
            DropdownMenuItem(
                text = { Text("立即运行") },
                leadingIcon = { Icon(HugeIcons.Play, null) },
                onClick = {
                    expanded = false
                    onRunNow()
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(HugeIcons.Delete01, null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun CronTaskDetailDialog(
    task: AgentCronTask,
    onDismiss: () -> Unit,
    onOpenConversation: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.title) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CronDetailLine("任务 ID", task.id)
                CronDetailLine("计划", "${task.cronExpression} · ${task.timezoneId}")
                CronDetailLine("下次运行", formatEpochMillis(task.nextRunAtMs))
                CronDetailLine("最近运行", formatEpochMillis(task.lastRunAtMs))
                CronDetailLine("状态", "${task.lastStatus.name.lowercase()} · 已运行 ${task.runCount} 次")
                CronDetailLine("会话", task.conversationId)
                task.lastError?.takeIf { it.isNotBlank() }?.let {
                    CronDetailLine("错误", it)
                }
                CronDetailLine("Prompt", task.prompt)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenConversation) {
                Text("打开会话")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRunNow) {
                    Text("立即运行")
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

@Composable
private fun CronDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label)
        Text(
            text = value,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatEpochMillis(value: Long?): String {
    if (value == null) return "-"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
}
