package app.amber.feature.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.agent.data.db.AppDatabase
import app.amber.core.conversation.exchange.ConversationExchangeCodec
import app.amber.core.conversation.exchange.ConversationExchangeFileHandler
import app.amber.core.storage.StorageBreakdown
import app.amber.core.utils.UiState
import app.amber.core.utils.appLocale
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.search.SearchErrorKind
import app.amber.feature.ui.pages.search.SearchVM
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronRight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Locale

/**
 * 设置 → 聊天记录存储：展示真实 Room 数据库事实，并提供导出、索引重建和附件入口。
 * 清理会话仍由原来的 SettingStoragePage 负责，避免把破坏性操作混进这个概览页。
 */
@Composable
fun SettingChatStoragePage(
    storageVm: StorageVM = koinViewModel(),
    searchVm: SearchVM = koinViewModel(),
    exchangeHandler: ConversationExchangeFileHandler = koinInject(),
    database: AppDatabase = koinInject(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val breakdown by storageVm.breakdown.collectAsState()
    val locale = context.appLocale()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val exchangeScope = rememberCoroutineScope()
    var exchangeBusy by remember { mutableStateOf(false) }
    var exchangeMessage by remember { mutableStateOf<String?>(null) }
    var exchangeError by remember { mutableStateOf(false) }
    var showRebuildDialog by remember { mutableStateOf(false) }

    // DataSourceModule builds Room with the authoritative name "amber_agent";
    // reading it from the open helper keeps this page tied to the live database.
    val databasePath = remember(database, context) {
        database.openHelper.databaseName
            ?.takeIf { it.isNotBlank() }
            ?.let { context.getDatabasePath(it).absolutePath }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ConversationExchangeCodec.MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            // A null result is the system picker cancellation path; it is not an export success.
            exchangeError = false
            exchangeMessage = context.getString(R.string.setting_chat_storage_export_cancelled)
            return@rememberLauncherForActivityResult
        }
        exchangeScope.launch {
            exchangeBusy = true
            exchangeMessage = null
            exchangeError = false
            try {
                val result = exchangeHandler.exportAllToUri(uri)
                exchangeMessage = context.getString(
                    R.string.setting_chat_storage_exported,
                    result.conversationCount,
                    formatStorageBytes(result.byteCount.toLong(), locale),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                exchangeError = true
                exchangeMessage = context.getString(R.string.setting_chat_storage_export_failed, error.message.orEmpty())
            } finally {
                exchangeBusy = false
            }
        }
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text(stringResource(R.string.search_page_rebuild_index)) },
            text = { Text(stringResource(R.string.search_page_rebuild_index_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildDialog = false
                        searchVm.rebuildIndex()
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_page_chat_storage),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StorageFactsCard(
                breakdown = breakdown,
                databasePath = databasePath,
                locale = locale,
                onRefresh = storageVm::refresh,
            )

            SettingCardGroup(title = stringResource(R.string.setting_chat_storage_maintenance)) {
                item(
                    onClick = {
                        if (!exchangeBusy) {
                            createDocumentLauncher.launch(ConversationExchangeCodec.suggestedFileName())
                        }
                    },
                    modifier = Modifier.settingSingleLine(),
                    trailingContent = {
                        if (exchangeBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_chat_storage_export_all)) },
                )
                exchangeMessage?.let { message ->
                    rawItem {
                        Text(
                            text = message,
                            style = LocalAmberType.current.meta,
                            color = if (exchangeError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                LocalAmberTokens.current.ink3
                            },
                        )
                    }
                }
                item(
                    onClick = {
                        if (!searchVm.isRebuilding) {
                            showRebuildDialog = true
                        }
                    },
                    modifier = if (searchVm.isRebuilding) {
                        Modifier.settingTwoLine()
                    } else {
                        Modifier.settingSingleLine()
                    },
                    supportingContent = if (searchVm.isRebuilding) {
                        {
                            val (current, total) = searchVm.rebuildProgress
                            Text(
                                text = if (total > 0) {
                                    stringResource(R.string.search_page_rebuilding, current, total)
                                } else {
                                    stringResource(R.string.search_page_rebuilding_simple)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        null
                    },
                    trailingContent = {
                        if (searchVm.isRebuilding) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_chat_storage_rebuild_index)) },
                )
                if (searchVm.errorKind == SearchErrorKind.INDEX_REBUILD) {
                    rawItem {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.setting_chat_storage_rebuild_failed),
                                style = LocalAmberType.current.secondary,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = searchVm::retryLastOperation) {
                                Text(stringResource(R.string.setting_storage_retry))
                            }
                        }
                    }
                }
                item(
                    onClick = { navController.navigate(Screen.SettingFiles) },
                    modifier = Modifier.settingTwoLine(),
                    supportingContent = {
                        val attachmentState = breakdown as? UiState.Success<StorageBreakdown>
                        val data = attachmentState?.data
                        Text(
                            text = if (data == null) {
                                ""
                            } else {
                                stringResource(
                                    R.string.setting_chat_storage_attachment_summary,
                                    data.attachmentCount,
                                    formatStorageBytes(data.attachmentBytes, locale),
                                )
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Lucide.ChevronRight,
                            contentDescription = null,
                            tint = LocalAmberTokens.current.ink3,
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.setting_files_page_title)) },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StorageFactsCard(
    breakdown: UiState<StorageBreakdown>,
    databasePath: String?,
    locale: Locale,
    onRefresh: () -> Unit,
) {
    CardGroup(
        modifier = Modifier.padding(top = 16.dp),
    ) {
        when (breakdown) {
            is UiState.Loading -> rawItem {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            is UiState.Error -> rawItem {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
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
                    StorageFactRow(
                        label = stringResource(R.string.setting_chat_storage_location),
                        detail = databasePath ?: stringResource(R.string.backup_unavailable),
                    )
                }
                rawItem {
                    StorageFactRow(
                        label = stringResource(R.string.setting_storage_conversation_database),
                        detail = stringResource(R.string.setting_chat_storage_conversation_summary, data.conversationCount, data.messageNodeCount),
                        value = formatStorageBytes(data.databaseBytes, locale),
                    )
                }
                rawItem {
                    StorageFactRow(
                        label = stringResource(R.string.setting_chat_storage_format),
                        value = "SQLite",
                    )
                }
            }

            is UiState.Idle -> Unit
        }
    }
}

@Composable
private fun StorageFactRow(
    label: String,
    detail: String? = null,
    value: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = LocalAmberType.current.body,
                color = LocalAmberTokens.current.ink,
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        }
        value?.let {
            Text(
                text = it,
                style = LocalAmberType.current.meta,
                color = LocalAmberTokens.current.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatStorageBytes(bytes: Long, locale: Locale): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(locale, "%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(locale, "%.1fMB", bytes / (1024.0 * 1024.0))
    else -> String.format(locale, "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
}
