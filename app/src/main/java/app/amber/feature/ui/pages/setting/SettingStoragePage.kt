package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.core.storage.CleanupDryRun
import app.amber.core.storage.StorageBreakdown
import app.amber.core.utils.UiState
import app.amber.core.utils.appLocale
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

/**
 * P7-03 设置 → 存储：分类占用展示（会话数据库 / 消息正文 / 附件 / 缓存）
 * + 按时间清理会话（dry run → 确认）。附件清理与模型缓存清理分开，不把
 * “清缓存”包装成“清理会话”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingStoragePage(vm: StorageVM = koinViewModel()) {
    val breakdown by vm.breakdown.collectAsState()
    val dryRun by vm.dryRun.collectAsState()
    val cleanupResult by vm.cleanupResult.collectAsState()
    val days by vm.days.collectAsState()
    val cleaning by vm.cleaning.collectAsState()
    val appLocale = LocalContext.current.appLocale()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_storage_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StorageUsageCard(breakdown, appLocale, onRefresh = { vm.refresh() })
            CleanupCard(
                days = days,
                cleaning = cleaning,
                onDaysChange = { vm.selectDays(it) },
                onPreview = { vm.previewCleanup() },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    (dryRun as? UiState.Success)?.data?.let { plan ->
        CleanupDryRunDialog(
            plan = plan,
            days = days,
            cleaning = cleaning,
            locale = appLocale,
            onDismiss = { if (!cleaning) vm.dismissDryRun() },
            onConfirm = { vm.confirmCleanup() },
        )
    }

    when (val result = cleanupResult) {
        is UiState.Success -> {
            CleanupResultDialog(
                conversations = result.data.conversationCount,
                messages = result.data.messageNodeCount,
                attachments = result.data.attachmentCount,
                bytes = result.data.deletedBytes,
                locale = appLocale,
                onDismiss = { vm.dismissResult() },
            )
        }

        is UiState.Error -> {
            AlertDialog(
                onDismissRequest = { vm.dismissResult() },
                title = { Text(stringResource(R.string.setting_storage_cleanup_failed_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.setting_storage_cleanup_failed_message,
                            result.error.message.orEmpty(),
                        ),
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                },
                confirmButton = {
                    Button(onClick = { vm.dismissResult() }) {
                        Text(stringResource(R.string.setting_storage_got_it))
                    }
                },
            )
        }

        else -> Unit
    }
}

@Composable
private fun StorageUsageCard(
    breakdown: UiState<StorageBreakdown>,
    locale: Locale,
    onRefresh: () -> Unit,
) {
    CardGroup(title = { SectionLabel(stringResource(R.string.setting_storage_usage_title)) }) {
        when (breakdown) {
            is UiState.Loading -> rawItem {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }
            }

            is UiState.Error -> rawItem {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.setting_storage_usage_failed,
                            breakdown.error.message.orEmpty(),
                        ),
                        style = LocalAmberType.current.secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.setting_storage_retry))
                    }
                }
            }

            is UiState.Success -> {
                val data = breakdown.data
                rawItem {
                    UsageRow(
                        stringResource(R.string.setting_storage_conversation_database),
                        stringResource(R.string.setting_storage_conversation_count, data.conversationCount),
                        data.databaseBytes,
                        locale,
                    )
                }
                rawItem {
                    UsageRow(
                        stringResource(R.string.setting_storage_message_body),
                        stringResource(R.string.setting_storage_message_count, data.messageNodeCount),
                        data.messageBodyBytes,
                        locale,
                    )
                }
                rawItem {
                    UsageRow(
                        stringResource(R.string.setting_storage_attachments),
                        stringResource(R.string.setting_storage_attachment_count, data.attachmentCount),
                        data.attachmentBytes,
                        locale,
                    )
                }
                rawItem {
                    UsageRow(stringResource(R.string.setting_storage_cache), "", data.cacheBytes, locale)
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun UsageRow(label: String, detail: String, bytes: Long, locale: Locale) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, style = LocalAmberType.current.body)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        }
        Text(
            formatBytes(bytes, locale),
            style = LocalAmberType.current.meta,
            color = LocalAmberTokens.current.ink,
        )
    }
}

@Composable
private fun CleanupCard(
    days: Int,
    cleaning: Boolean,
    onDaysChange: (Int) -> Unit,
    onPreview: () -> Unit,
) {
    CardGroup(title = { SectionLabel(stringResource(R.string.setting_storage_cleanup_title)) }) {
        rawItem {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { candidate ->
                    FilterChip(
                        selected = days == candidate,
                        onClick = { onDaysChange(candidate) },
                        label = {
                            Text(stringResource(R.string.setting_storage_days_ago, candidate))
                        },
                        enabled = !cleaning,
                    )
                }
            }
        }
        rawItem {
            Text(
                stringResource(R.string.setting_storage_cleanup_desc, days),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        }
        rawItem {
            Button(onClick = onPreview, enabled = !cleaning) {
                if (cleaning) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (cleaning) {
                            R.string.setting_storage_cleaning
                        } else {
                            R.string.setting_storage_preview_cleanup
                        },
                    )
                )
            }
        }
    }
}

@Composable
private fun CleanupDryRunDialog(
    plan: CleanupDryRun,
    days: Int,
    cleaning: Boolean,
    locale: Locale,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!cleaning) onDismiss() },
        title = { Text(stringResource(R.string.setting_storage_confirm_cleanup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setting_storage_confirm_cleanup_message, days),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
                Text(
                    stringResource(
                        R.string.setting_storage_cleanup_counts,
                        plan.conversationCount,
                        plan.messageNodeCount,
                        plan.attachmentCount,
                        if (plan.estimatedBytes > 0L) {
                            stringResource(
                                R.string.setting_storage_estimated_size,
                                formatBytes(plan.estimatedBytes, locale),
                            )
                        } else {
                            ""
                        },
                    ),
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                if (plan.targets.isEmpty()) {
                    Text(
                        stringResource(R.string.setting_storage_no_matching_conversations),
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                Hairline()
                Text(
                    stringResource(R.string.setting_storage_cleanup_irreversible),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !cleaning && plan.targets.isNotEmpty()) {
                Text(
                    stringResource(
                        if (cleaning) {
                            R.string.setting_storage_cleaning
                        } else {
                            R.string.setting_storage_confirm_cleanup
                        },
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !cleaning) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun CleanupResultDialog(
    conversations: Int,
    messages: Int,
    attachments: Int,
    bytes: Long,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_storage_cleanup_complete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.setting_storage_cleanup_complete_message,
                    conversations,
                    messages,
                    attachments,
                    formatBytes(bytes, locale),
                ),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.setting_storage_done))
            }
        },
    )
}

private fun formatBytes(bytes: Long, locale: Locale): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(locale, "%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(locale, "%.1fMB", bytes / (1024.0 * 1024.0))
    else -> String.format(locale, "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
}
