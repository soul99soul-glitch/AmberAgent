package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agent.cron.AgentCronManager
import me.rerere.rikkahub.data.agent.cron.AgentCronTask
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
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
                }
            }
        }
    }
}

@Composable
fun SettingCronTasksPage(
    cronManager: AgentCronManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val tasks by cronManager.tasksFlow.collectAsStateWithLifecycle(emptyList())
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
                    if (tasks.isEmpty()) {
                        item(
                            leadingContent = { Icon(HugeIcons.Clock02, null) },
                            headlineContent = { Text(stringResource(R.string.setting_cron_tasks_empty_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_cron_tasks_empty_desc)) },
                        )
                    } else {
                        tasks.forEach { task ->
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
