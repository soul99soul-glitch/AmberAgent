package me.rerere.rikkahub.ui.pages.backup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.data.sync.core.SYNC_ARCHIVE_EXTENSION
import me.rerere.rikkahub.data.sync.core.SYNC_ARCHIVE_MIME
import me.rerere.rikkahub.data.sync.core.SyncMode
import me.rerere.rikkahub.data.sync.core.SyncPreview
import me.rerere.rikkahub.data.sync.local.LocalBackupRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.UiState
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val settings by vm.settings.collectAsState()
    val operationState by vm.operationState.collectAsState()
    val importPreview by vm.pendingImportPreview.collectAsState()
    val googleSession by vm.googleSession.collectAsState()
    val googleMessage by vm.googleMessage.collectAsState()
    val pendingGoogleAuthorization by vm.pendingGoogleAuthorization.collectAsState()
    val pendingCloudRestore by vm.pendingCloudRestore.collectAsState()
    val cloudConflict by vm.cloudConflict.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var exportMode by remember { mutableStateOf(settings.syncSettings.mode) }
    var pendingExportMode by remember { mutableStateOf<SyncMode?>(null) }
    var pendingCloudUploadMode by remember { mutableStateOf<SyncMode?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showRestorePassphrase by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }

    val googleAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.completeGoogleAuthorization(result.data)
        } else {
            vm.cancelGoogleAuthorization()
        }
    }

    LaunchedEffect(pendingGoogleAuthorization) {
        val pendingIntent = pendingGoogleAuthorization ?: return@LaunchedEffect
        vm.consumePendingGoogleAuthorization()
        googleAuthorizationLauncher.launch(
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        )
    }

    LaunchedEffect(settings.syncSettings.mode) {
        exportMode = settings.syncSettings.mode
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SYNC_ARCHIVE_MIME),
    ) { uri ->
        val mode = pendingExportMode
        if (uri != null && mode != null) {
            vm.exportLocal(uri, mode, exportPassphrase)
        }
        pendingExportMode = null
        exportPassphrase = ""
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            vm.inspectImport(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步与备份") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusHeader(
                state = operationState,
                lastExportAt = settings.syncSettings.lastLocalExportAt,
                lastError = settings.syncSettings.lastError,
            )

            CardGroup(title = { Text("Google 云同步") }) {
                item(
                    onClick = { vm.connectGoogle() },
                    leadingContent = { Icon(HugeIcons.Database02, contentDescription = null) },
                    headlineContent = { Text("Google 账号") },
                    supportingContent = {
                        Text(
                            when {
                                googleSession != null -> "已连接：${googleSession?.label.orEmpty()}"
                                settings.syncSettings.googleAccountEmail.isNotBlank() ->
                                    "上次连接：${settings.syncSettings.googleAccountEmail}"
                                googleMessage.isNotBlank() -> googleMessage
                                vm.googleConfigStatus.reason.isNotBlank() -> vm.googleConfigStatus.reason
                                vm.googleConfigStatus.credentialManagerAvailable ->
                                    "Credential Manager 与 Drive appDataFolder 已就绪。"
                                else -> "使用 Google Drive appDataFolder 保存加密同步快照。"
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            if (googleSession != null) "已连接" else "连接",
                            color = if (googleSession != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
                item(
                    onClick = { pendingCloudUploadMode = exportMode },
                    leadingContent = { Icon(HugeIcons.Upload02, contentDescription = null) },
                    headlineContent = { Text("上传云端快照") },
                    supportingContent = {
                        Text(
                            if (settings.syncSettings.lastUploadAt > 0) {
                                "上次上传：${settings.syncSettings.lastUploadAt}。检测到冲突时必须人工确认，不做自动合并。"
                            } else {
                                "生成加密快照并上传到 Google Drive appDataFolder。"
                            }
                        )
                    },
                    trailingContent = {
                        Text("上传", color = MaterialTheme.colorScheme.primary)
                    }
                )
                item(
                    onClick = { vm.downloadGooglePreview() },
                    leadingContent = { Icon(HugeIcons.Download04, contentDescription = null) },
                    headlineContent = { Text("下载并恢复云端快照") },
                    supportingContent = {
                        Text(
                            if (settings.syncSettings.lastDownloadAt > 0) {
                                "上次恢复：${settings.syncSettings.lastDownloadAt}。恢复前会先展示公开 manifest。"
                            } else {
                                "从 Google Drive appDataFolder 读取最新快照，确认后输入同步口令恢复。"
                            }
                        )
                    },
                    trailingContent = {
                        Text("下载", color = MaterialTheme.colorScheme.primary)
                    }
                )
            }

            CardGroup(title = { Text("同步模式") }) {
                item(
                    leadingContent = { Icon(HugeIcons.Database02, contentDescription = null) },
                    headlineContent = { Text("端到端加密") },
                    supportingContent = { Text("标准同步和完全同步都会先加密再导出或上传。完全同步会包含 API key。") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = exportMode == SyncMode.STANDARD,
                    onClick = {
                        exportMode = SyncMode.STANDARD
                        vm.updateMode(SyncMode.STANDARD)
                    },
                    label = { Text("标准同步") },
                )
                FilterChip(
                    selected = exportMode == SyncMode.FULL,
                    onClick = {
                        exportMode = SyncMode.FULL
                        vm.updateMode(SyncMode.FULL)
                    },
                    label = { Text("完全同步") },
                )
            }

            CardGroup(title = { Text("本地备份") }) {
                item(
                    onClick = { pendingExportMode = exportMode },
                    leadingContent = { Icon(HugeIcons.Upload02, contentDescription = null) },
                    headlineContent = { Text("导出本地备份") },
                    supportingContent = { Text("生成 .$SYNC_ARCHIVE_EXTENSION 加密快照，保存到你选择的位置。") },
                )
                item(
                    onClick = { openDocumentLauncher.launch(arrayOf(SYNC_ARCHIVE_MIME, "application/zip", "*/*")) },
                    leadingContent = { Icon(HugeIcons.FileImport, contentDescription = null) },
                    headlineContent = { Text("导入本地备份") },
                    supportingContent = { Text("先读取公开 manifest 预览，确认后输入同步口令恢复。") },
                )
            }

            CardGroup(title = { Text("数据范围") }) {
                item(
                    leadingContent = { Icon(HugeIcons.Database02, contentDescription = null) },
                    headlineContent = { Text("完整应用数据") },
                    supportingContent = {
                        Text("包含设置、模型、助手、聊天、记忆、看板、飞书监听、上传文件、skills、生成图片和 OAuth 凭据。")
                    },
                )
                item(
                    leadingContent = { Icon(HugeIcons.Alert01, contentDescription = null) },
                    headlineContent = { Text("明确排除") },
                    supportingContent = { Text("不包含缓存、日志、terminal runtime、正在运行的任务和外部 workspace 内容。") },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    pendingExportMode?.let { mode ->
        PassphraseDialog(
            title = "设置同步口令",
            confirmText = "继续导出",
            message = if (mode == SyncMode.FULL) {
                "完全同步会包含 API key、OAuth token 和敏感配置。请使用一个之后能在另一台手机输入的同步口令。"
            } else {
                "标准同步会脱敏 API key，但聊天、记忆和文件仍会端到端加密。"
            },
            onDismiss = {
                pendingExportMode = null
                exportPassphrase = ""
            },
            onConfirm = { passphrase ->
                exportPassphrase = passphrase
                createDocumentLauncher.launch(LocalBackupRepository.suggestedFileName())
            }
        )
    }

    pendingCloudUploadMode?.let { mode ->
        PassphraseDialog(
            title = "输入同步口令",
            confirmText = "上传",
            message = if (mode == SyncMode.FULL) {
                "完全同步会包含 API key、OAuth token 和敏感配置。快照会先加密，再上传到 Google Drive appDataFolder。"
            } else {
                "标准同步会脱敏 API key。快照会先加密，再上传到 Google Drive appDataFolder。"
            },
            onDismiss = { pendingCloudUploadMode = null },
            onConfirm = { passphrase ->
                vm.uploadGoogle(mode, passphrase)
                pendingCloudUploadMode = null
            }
        )
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            onDismiss = {
                vm.clearPendingImport()
                pendingImportUri = null
            },
            onRestore = { showRestorePassphrase = true },
        )
    }

    if (showRestorePassphrase) {
        PassphraseDialog(
            title = "输入同步口令",
            confirmText = "恢复",
            message = "恢复会替换本机应用数据。恢复前请确认这是你想导入的快照。",
            onDismiss = { showRestorePassphrase = false },
            onConfirm = { passphrase ->
                val uri = pendingImportUri
                if (pendingCloudRestore) {
                    vm.restoreGoogle(passphrase)
                } else if (uri != null) {
                    vm.restoreLocal(uri, passphrase)
                }
                showRestorePassphrase = false
                pendingImportUri = null
            }
        )
    }

    cloudConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { vm.dismissCloudConflict() },
            title = { Text("云端快照冲突") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Google Drive 已有一个不同 revision 的同步快照。")
                    Text("云端修改时间：${conflict.remoteFile.modifiedTime ?: "未知"}")
                    Text("本机记录 revision：${conflict.localRevision.ifBlank { "无" }}")
                    Text("为避免静默丢数据，请确认是否用本机快照覆盖云端。")
                }
            },
            confirmButton = {
                Button(onClick = { vm.confirmOverwriteCloud() }) {
                    Text("覆盖云端")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissCloudConflict() }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun StatusHeader(
    state: UiState<SyncPreview>,
    lastExportAt: Long,
    lastError: String,
) {
    CardGroup {
        item(
            leadingContent = {
                if (state is UiState.Loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                } else {
                    Icon(HugeIcons.Download04, contentDescription = null)
                }
            },
            headlineContent = {
                Text(
                    when (state) {
                        is UiState.Loading -> "正在处理同步快照"
                        is UiState.Success -> "最近操作完成"
                        is UiState.Error -> "最近操作失败"
                        UiState.Idle -> "同步快照"
                    }
                )
            },
            supportingContent = {
                Text(
                    when (state) {
                        is UiState.Success -> "快照时间：${state.data.createdAt}"
                        is UiState.Error -> state.error.message ?: "未知错误"
                        is UiState.Loading -> "请保持应用在前台，避免中断导出或恢复。"
                        UiState.Idle -> if (lastExportAt > 0) {
                            "上次本地导出：$lastExportAt"
                        } else {
                            lastError.ifBlank { "尚未创建新的同步快照。" }
                        }
                    }
                )
            },
        )
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: SyncPreview,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认恢复备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模式：${preview.mode}")
                Text("创建时间：${preview.createdAt}")
                Text("版本：${preview.manifest.appVersionName} / ${preview.manifest.appVersionCode}")
                Text("数据清单：已放入加密 payload")
                Text("恢复会替换本机应用数据，并在完成后刷新文件索引和聊天搜索索引。")
            }
        },
        confirmButton = {
            Button(onClick = onRestore) {
                Text("输入口令并恢复")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("同步口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = passphrase.isNotBlank(),
                onClick = { onConfirm(passphrase) },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
