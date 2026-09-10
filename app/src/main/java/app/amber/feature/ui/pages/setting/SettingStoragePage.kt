package app.amber.feature.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import app.amber.core.conversation.exchange.ConversationExchangeCodec
import app.amber.core.conversation.exchange.ConversationExchangeFileHandler
import app.amber.core.conversation.exchange.ConversationExchangePendingImport
import app.amber.core.storage.CleanupDryRun
import app.amber.core.storage.StorageBreakdown
import app.amber.core.utils.UiState
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

/**
 * P7-03 设置 → 存储：分类占用展示（会话数据库 / 消息正文 / 附件 / 缓存）
 * + 按时间清理会话（dry run → 确认）。附件清理与模型缓存清理分开，不把
 * “清缓存”包装成“清理会话”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingStoragePage(
    vm: StorageVM = koinViewModel(),
    exchangeHandler: ConversationExchangeFileHandler = koinInject(),
) {
    val breakdown by vm.breakdown.collectAsState()
    val dryRun by vm.dryRun.collectAsState()
    val cleanupResult by vm.cleanupResult.collectAsState()
    val days by vm.days.collectAsState()
    val cleaning by vm.cleaning.collectAsState()
    val exchangeScope = rememberCoroutineScope()
    var exchangeBusy by remember { mutableStateOf(false) }
    var exchangeMessage by remember { mutableStateOf<String?>(null) }
    var pendingExchange by remember { mutableStateOf<ConversationExchangePendingImport?>(null) }

    val createExchangeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ConversationExchangeCodec.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exchangeScope.launch {
            exchangeBusy = true
            exchangeMessage = null
            try {
                runCatching { exchangeHandler.exportAllToUri(uri) }
                    .onSuccess { result ->
                        exchangeMessage = "已导出 ${result.conversationCount} 个会话（${formatBytes(result.byteCount.toLong())}）"
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        exchangeMessage = "导出失败：${error.message.orEmpty()}"
                    }
            } finally {
                exchangeBusy = false
            }
        }
    }
    val openExchangeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exchangeScope.launch {
            exchangeBusy = true
            exchangeMessage = null
            try {
                runCatching { exchangeHandler.prepareImportFromUri(uri) }
                    .onSuccess { result ->
                        pendingExchange = result
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        exchangeMessage = "无法读取交换文件：${error.message.orEmpty()}"
                    }
            } finally {
                exchangeBusy = false
            }
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "存储",
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
            StorageUsageCard(breakdown, onRefresh = { vm.refresh() })
            ConversationExchangeCard(
                busy = exchangeBusy,
                message = exchangeMessage,
                onExport = {
                    createExchangeLauncher.launch(ConversationExchangeCodec.suggestedFileName())
                },
                onImport = {
                    openExchangeLauncher.launch(
                        arrayOf(ConversationExchangeCodec.MIME_TYPE, "application/zip"),
                    )
                },
            )
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
                onDismiss = { vm.dismissResult() },
            )
        }

        is UiState.Error -> {
            AlertDialog(
                onDismissRequest = { vm.dismissResult() },
                title = { Text("清理失败") },
                text = {
                    Text(
                        "部分或全部会话未删除，数据保持不变，可重试。\n${result.error.message.orEmpty()}",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                },
                confirmButton = {
                    Button(onClick = { vm.dismissResult() }) { Text("知道了") }
                },
            )
        }

        else -> Unit
    }

    pendingExchange?.let { pending ->
        ConversationExchangeImportDialog(
            pending = pending,
            busy = exchangeBusy,
            onDismiss = { if (!exchangeBusy) pendingExchange = null },
            onConfirm = {
                pendingExchange = null
                exchangeScope.launch {
                    exchangeBusy = true
                    exchangeMessage = null
                    var importSucceeded = false
                    try {
                        runCatching { exchangeHandler.importPrepared(pending) }
                            .onSuccess { result ->
                                importSucceeded = true
                                exchangeMessage = (
                                    "已导入 ${result.importedCount} 个会话" +
                                        if (result.overwrittenCount > 0) {
                                            "，覆盖 ${result.overwrittenCount} 个同 ID 会话"
                                        } else {
                                            ""
                                        }
                                    ) + if (result.attachmentReferenceCount > 0) {
                                        "；保留 ${result.attachmentReferenceCount} 个附件引用（未复制文件）"
                                    } else {
                                        ""
                                    }
                            }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                exchangeMessage = "导入失败：${error.message.orEmpty()}"
                            }
                    } finally {
                        exchangeBusy = false
                        if (importSucceeded) vm.refresh()
                    }
                }
            },
        )
    }
}

@Composable
private fun ConversationExchangeCard(
    busy: Boolean,
    message: String?,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    CardGroup(title = { SectionLabel("会话交换") }) {
        rawItem {
            Text(
                "可与 iOS 交换普通会话；线程关系、禁用/受污染记忆模式暂不支持。本机附件不会随文件传输。",
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        }
        rawItem {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onExport,
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("导出会话")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onImport,
                    enabled = !busy,
                ) {
                    Text("导入会话")
                }
            }
        }
        message?.let { result ->
            rawItem {
                Text(
                    result,
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        }
    }
}

@Composable
private fun ConversationExchangeImportDialog(
    pending: ConversationExchangePendingImport,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = pending.preview
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入会话") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "将导入 ${preview.conversationCount} 个会话，其中新增 ${preview.newConversationCount} 个。",
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
                if (preview.conflicts.isNotEmpty()) {
                    Text(
                        "以下 ${preview.conflicts.size} 个同 ID 会话会被覆盖：",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink,
                    )
                    preview.conflicts.take(5).forEach { conflict ->
                        Text(
                            "· ${conflict.incomingTitle.ifBlank { "无标题" }}" +
                                if (conflict.existingTitle.isBlank()) "" else "（当前：${conflict.existingTitle}）",
                            style = LocalAmberType.current.meta,
                            color = LocalAmberTokens.current.ink3,
                        )
                    }
                    if (preview.conflicts.size > 5) {
                        Text(
                            "还有 ${preview.conflicts.size - 5} 个会话未展开。",
                            style = LocalAmberType.current.meta,
                            color = LocalAmberTokens.current.ink3,
                        )
                    }
                }
                if (preview.attachmentReferenceCount > 0) {
                    Text(
                        "包含 ${preview.attachmentReferenceCount} 个附件引用，文件本身不会随交换文件复制。",
                        style = LocalAmberType.current.meta,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                Text(
                    "导入会停止正在生成的会话并刷新其持久化快照。",
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "导入中" else "确认导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

@Composable
private fun StorageUsageCard(
    breakdown: UiState<StorageBreakdown>,
    onRefresh: () -> Unit,
) {
    CardGroup(title = { SectionLabel("存储占用") }) {
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
                        "统计失败：${breakdown.error.message.orEmpty()}",
                        style = LocalAmberType.current.secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRefresh) { Text("重试") }
                }
            }

            is UiState.Success -> {
                val data = breakdown.data
                rawItem { UsageRow("会话数据库", "${data.conversationCount} 个会话", data.databaseBytes) }
                rawItem { UsageRow("消息正文", "${data.messageNodeCount} 条消息", data.messageBodyBytes) }
                rawItem { UsageRow("附件", "${data.attachmentCount} 个文件", data.attachmentBytes) }
                rawItem { UsageRow("缓存", "", data.cacheBytes) }
            }

            else -> Unit
        }
    }
}

@Composable
private fun UsageRow(label: String, detail: String, bytes: Long) {
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
            formatBytes(bytes),
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
    CardGroup(title = { SectionLabel("清理会话") }) {
        rawItem {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(7, 30, 90).forEach { candidate ->
                    FilterChip(
                        selected = days == candidate,
                        onClick = { onDaysChange(candidate) },
                        label = { Text("${candidate} 天前") },
                        enabled = !cleaning,
                    )
                }
            }
        }
        rawItem {
            Text(
                "清理 ${days} 天前未更新的会话（置顶会话始终保留）。" +
                    "附件与消息会一并删除，模型缓存不受影响。先预览再确认。",
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
                Text(if (cleaning) "清理中" else "预览将清理的内容")
            }
        }
    }
}

@Composable
private fun CleanupDryRunDialog(
    plan: CleanupDryRun,
    days: Int,
    cleaning: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!cleaning) onDismiss() },
        title = { Text("确认清理会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "将删除 ${days} 天前未更新的非置顶会话：",
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
                Text(
                    "会话 ${plan.conversationCount} · 消息 ${plan.messageNodeCount}" +
                        " · 附件 ${plan.attachmentCount}" +
                        if (plan.estimatedBytes > 0L) " · 约 ${formatBytes(plan.estimatedBytes)}" else "",
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                if (plan.targets.isEmpty()) {
                    Text(
                        "没有符合条件的会话。",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                Hairline()
                Text(
                    "此操作不可撤销。失败时可重试，不会重复删除。",
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !cleaning && plan.targets.isNotEmpty()) {
                Text(if (cleaning) "清理中" else "确认清理")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !cleaning) {
                Text("取消")
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
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理完成") },
        text = {
            Text(
                "已删除会话 $conversations 个、消息 $messages 条、附件 $attachments 个，" +
                    "释放约 ${formatBytes(bytes)}。",
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成") }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fMB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
}
