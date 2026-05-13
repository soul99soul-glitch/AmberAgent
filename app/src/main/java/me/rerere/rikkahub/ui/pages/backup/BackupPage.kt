package me.rerere.rikkahub.ui.pages.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.data.sync.core.SYNC_ARCHIVE_MIME
import me.rerere.rikkahub.data.sync.core.SyncMode
import me.rerere.rikkahub.data.sync.core.SyncPreview
import me.rerere.rikkahub.data.sync.local.LocalBackupRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.UiState
import org.koin.androidx.compose.koinViewModel

private enum class GoogleSyncAction {
    Upload,
    Download,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val settings by vm.settings.collectAsState()
    val operationState by vm.operationState.collectAsState()
    val importPreview by vm.pendingImportPreview.collectAsState()
    val googleSession by vm.googleSession.collectAsState()
    val googleMessage by vm.googleMessage.collectAsState()
    val localMessage by vm.localMessage.collectAsState()
    val pendingGoogleAuthorization by vm.pendingGoogleAuthorization.collectAsState()
    val pendingCloudRestore by vm.pendingCloudRestore.collectAsState()
    val cloudConflict by vm.cloudConflict.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingCloudUploadMode by remember { mutableStateOf<SyncMode?>(null) }
    var pendingGoogleAction by remember { mutableStateOf<GoogleSyncAction?>(null) }
    var pendingLocalExport by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var localExportPassphrase by remember { mutableStateOf("") }
    var showRestorePassphrase by remember { mutableStateOf(false) }
    val hasGoogleConnection = googleSession != null ||
        (settings.syncSettings.googleEnabled && settings.syncSettings.googleAccountEmail.isNotBlank())

    val googleAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.data == null) {
            pendingGoogleAction = null
        }
        vm.handleGoogleAuthorizationResult(result.resultCode, result.data)
    }

    LaunchedEffect(pendingGoogleAuthorization) {
        val pendingIntent = pendingGoogleAuthorization ?: return@LaunchedEffect
        vm.consumePendingGoogleAuthorization()
        googleAuthorizationLauncher.launch(
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        )
    }

    LaunchedEffect(googleSession, pendingGoogleAction) {
        val action = pendingGoogleAction
        if (googleSession != null && action != null) {
            pendingGoogleAction = null
            when (action) {
                GoogleSyncAction.Upload -> pendingCloudUploadMode = SyncMode.FULL
                GoogleSyncAction.Download -> vm.downloadGooglePreview()
            }
        }
    }

    LaunchedEffect(operationState, googleSession) {
        if (googleSession == null && operationState is UiState.Error) {
            pendingGoogleAction = null
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SYNC_ARCHIVE_MIME),
    ) { uri ->
        if (uri != null) {
            vm.exportLocal(uri, SyncMode.FULL, localExportPassphrase)
        }
        localExportPassphrase = ""
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
            CardGroup(title = { Text("Google Drive") }) {
                item(
                    onClick = { vm.connectGoogle() },
                    leadingContent = { Icon(HugeIcons.Database02, contentDescription = null) },
                    headlineContent = { Text("Google 账号") },
                    supportingContent = {
                        Text(
                            when {
                                operationState is UiState.Loading -> "正在处理..."
                                googleMessage.isNotBlank() -> googleMessage
                                googleSession != null -> "已连接：${googleSession?.label.orEmpty()}"
                                hasGoogleConnection -> "上次连接：${settings.syncSettings.googleAccountEmail}"
                                settings.syncSettings.googleAccountEmail.isNotBlank() ->
                                    "上次连接：${settings.syncSettings.googleAccountEmail}"
                                vm.googleConfigStatus.reason.isNotBlank() -> "Google 同步暂不可用"
                                else -> "登录后即可上传和下载同步快照"
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            if (hasGoogleConnection) "已连接" else "连接",
                            color = if (hasGoogleConnection) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
                item(
                    onClick = {
                        if (googleSession == null) {
                            pendingGoogleAction = GoogleSyncAction.Upload
                            vm.connectGoogle()
                        } else {
                            pendingCloudUploadMode = SyncMode.FULL
                        }
                    },
                    leadingContent = { Icon(HugeIcons.Upload02, contentDescription = null) },
                    headlineContent = { Text("上传") },
                    supportingContent = {
                        Text("把当前数据加密保存到 Google Drive")
                    }
                )
                item(
                    onClick = {
                        if (googleSession == null) {
                            pendingGoogleAction = GoogleSyncAction.Download
                            vm.connectGoogle()
                        } else {
                            vm.downloadGooglePreview()
                        }
                    },
                    leadingContent = { Icon(HugeIcons.Download04, contentDescription = null) },
                    headlineContent = { Text("下载") },
                    supportingContent = {
                        Text("从 Google Drive 恢复到这台设备")
                    }
                )
            }

            CardGroup(title = { Text("本地备份") }) {
                item(
                    onClick = { pendingLocalExport = true },
                    leadingContent = { Icon(HugeIcons.Upload02, contentDescription = null) },
                    headlineContent = { Text("导出") },
                    supportingContent = {
                        Text(
                            localMessage.ifBlank {
                                "把当前数据加密保存成本地文件"
                            }
                        )
                    }
                )
                item(
                    onClick = {
                        vm.clearPendingImport()
                        pendingImportUri = null
                        openDocumentLauncher.launch(arrayOf(SYNC_ARCHIVE_MIME, "application/zip", "*/*"))
                    },
                    leadingContent = { Icon(HugeIcons.FileImport, contentDescription = null) },
                    headlineContent = { Text("导入") },
                    supportingContent = {
                        Text("从本地备份文件恢复到这台设备")
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    pendingCloudUploadMode?.let { mode ->
        PassphraseDialog(
            title = "输入同步口令",
            confirmText = "上传",
            message = "用于加密这次同步快照。另一台设备下载时输入同一个口令即可恢复。",
            onDismiss = { pendingCloudUploadMode = null },
            onConfirm = { passphrase ->
                vm.uploadGoogle(mode, passphrase)
                pendingCloudUploadMode = null
            }
        )
    }

    if (pendingLocalExport) {
        PassphraseDialog(
            title = "设置备份口令",
            confirmText = "导出",
            message = "本地备份会包含几乎所有应用数据，并先加密再保存成本地文件。",
            onDismiss = {
                pendingLocalExport = false
                localExportPassphrase = ""
            },
            onConfirm = { passphrase ->
                localExportPassphrase = passphrase
                pendingLocalExport = false
                createDocumentLauncher.launch(LocalBackupRepository.suggestedFileName())
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
                val localUri = pendingImportUri
                if (pendingCloudRestore) {
                    vm.restoreGoogle(passphrase)
                } else if (localUri != null) {
                    vm.restoreLocal(localUri, passphrase)
                }
                showRestorePassphrase = false
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
