package me.rerere.rikkahub.ui.pages.backup

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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudSavingDone01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Upload02
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import me.rerere.rikkahub.data.sync.core.SYNC_ARCHIVE_MIME
import me.rerere.rikkahub.data.sync.core.SyncMode
import me.rerere.rikkahub.data.sync.core.SyncPreview
import me.rerere.rikkahub.data.sync.core.SyncSettings
import me.rerere.rikkahub.data.sync.local.LocalBackupRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.UiState
import org.koin.androidx.compose.koinViewModel

private enum class GoogleSyncAction {
    Upload,
    Download,
}

private val BackupStatusDateFormat by lazy {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
}

/**
 * Render the "上次备份" supporting line for the backup-status card item.
 * Falls back to "暂无备份" when none of upload / download / local-export has
 * happened yet (i.e. `lastBackupVersionName` is still its default blank).
 */
private fun formatBackupStatus(syncSettings: SyncSettings): String {
    if (syncSettings.lastBackupVersionName.isBlank()) return "暂无备份"
    val latestAt = maxOf(
        syncSettings.lastUploadAt,
        syncSettings.lastDownloadAt,
        syncSettings.lastLocalExportAt,
    )
    val parts = mutableListOf<String>()
    if (latestAt > 0L) parts += BackupStatusDateFormat.format(Date(latestAt))
    parts += syncSettings.lastBackupVersionName
    if (syncSettings.lastBackupDeviceLabel.isNotBlank()) {
        parts += syncSettings.lastBackupDeviceLabel
    }
    return parts.joinToString(separator = " · ")
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
    var pendingGoogleAction by remember { mutableStateOf<GoogleSyncAction?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // Restore scope is always EVERYTHING; within it the user opts in to
    // including chat history / generated images. Default OFF for both — the
    // safe path is "leave local chat & gallery alone". User framed it as:
    // "下载的时候有两个选项可以选，不勾选的话恢复就不会恢复这俩".
    // (Engine API still speaks in terms of `preserveX` = the inverse —
    // we translate at the call site.)
    var restoreConversations by remember { mutableStateOf(false) }
    var restoreGenMedia by remember { mutableStateOf(false) }
    // Used to gate the "建议重启应用" dialog: set when the user kicks off
    // a restore, watched by a LaunchedEffect that flips it back on
    // success and surfaces the hint. Avoids showing the hint after
    // unrelated upload/download operations also complete.
    var restoreInFlight by remember { mutableStateOf(false) }
    var showRestoreSuccessDialog by remember { mutableStateOf(false) }
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
                GoogleSyncAction.Upload -> vm.uploadGoogle(SyncMode.FULL, passphrase = "")
                GoogleSyncAction.Download -> vm.downloadGooglePreview()
            }
        }
    }

    LaunchedEffect(operationState, googleSession) {
        if (googleSession == null && operationState is UiState.Error) {
            pendingGoogleAction = null
        }
    }

    // Surface a "建议重启" hint dialog after a successful restore. Watching
    // operationState alone isn't enough — uploads/downloads also flip it to
    // Success. The restoreInFlight flag scopes the effect to actual restore
    // operations the user just kicked off.
    LaunchedEffect(operationState, restoreInFlight) {
        if (!restoreInFlight) return@LaunchedEffect
        when (operationState) {
            is UiState.Success -> {
                restoreInFlight = false
                showRestoreSuccessDialog = true
            }
            is UiState.Error -> {
                // Failure already surfaces its own toast via googleMessage /
                // localMessage; just clear the in-flight flag so subsequent
                // success events don't accidentally fire the hint.
                restoreInFlight = false
            }
            else -> Unit
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SYNC_ARCHIVE_MIME),
    ) { uri ->
        if (uri != null) {
            // Passphrase intentionally blank — BackupVM substitutes the
            // documented fallback. Per-user-request simplification: no
            // password prompt during export.
            vm.exportLocal(uri, SyncMode.FULL, passphrase = "")
        }
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
                        if (operationState is UiState.Loading) {
                            // Visible "something is happening" cue — user reported
                            // not being able to tell when an upload or download
                            // was actually in progress.
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                if (hasGoogleConnection) "已连接" else "连接",
                                color = if (hasGoogleConnection) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                )
                item(
                    leadingContent = { Icon(HugeIcons.CloudSavingDone01, contentDescription = null) },
                    headlineContent = { Text("备份状态") },
                    supportingContent = {
                        Text(formatBackupStatus(settings.syncSettings))
                    },
                )
                item(
                    onClick = {
                        if (googleSession == null) {
                            pendingGoogleAction = GoogleSyncAction.Upload
                            vm.connectGoogle()
                        } else {
                            vm.uploadGoogle(SyncMode.FULL, passphrase = "")
                        }
                    },
                    leadingContent = { Icon(HugeIcons.Upload02, contentDescription = null) },
                    headlineContent = { Text("上传") },
                    supportingContent = {
                        Text("把当前数据保存到 Google Drive")
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
                    onClick = {
                        createDocumentLauncher.launch(LocalBackupRepository.suggestedFileName())
                    },
                    leadingContent = { Icon(HugeIcons.Upload02, contentDescription = null) },
                    headlineContent = { Text("导出") },
                    supportingContent = {
                        Text(
                            localMessage.ifBlank {
                                "把当前数据保存成本地文件"
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

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            restoreConversations = restoreConversations,
            restoreGenMedia = restoreGenMedia,
            onRestoreConversationsChange = { restoreConversations = it },
            onRestoreGenMediaChange = { restoreGenMedia = it },
            onDismiss = {
                vm.clearPendingImport()
                pendingImportUri = null
                // Reset toggles to safe defaults (don't override local data)
                // for the next restore.
                restoreConversations = false
                restoreGenMedia = false
            },
            onRestore = {
                val localUri = pendingImportUri
                restoreInFlight = true
                // UI's "restoreX" maps inversely to engine's "preserveX".
                val preserveConversations = !restoreConversations
                val preserveGenMedia = !restoreGenMedia
                if (pendingCloudRestore) {
                    vm.restoreGoogle(
                        passphrase = "",
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    )
                } else if (localUri != null) {
                    vm.restoreLocal(
                        uri = localUri,
                        passphrase = "",
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    )
                } else {
                    restoreInFlight = false
                }
            },
        )
    }

    if (showRestoreSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreSuccessDialog = false },
            title = { Text("恢复完成") },
            text = {
                Text("数据已经覆盖到这台设备。建议重新打开应用以确保所有界面读取到最新数据。")
            },
            confirmButton = {
                Button(onClick = { showRestoreSuccessDialog = false }) {
                    Text("我知道了")
                }
            },
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
    restoreConversations: Boolean,
    restoreGenMedia: Boolean,
    onRestoreConversationsChange: (Boolean) -> Unit,
    onRestoreGenMediaChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认覆盖") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("创建时间：${preview.createdAt}")
                Text("版本：${preview.manifest.appVersionName} / ${preview.manifest.appVersionCode}")
                HorizontalDivider()
                Text(
                    "覆盖会替换 Provider 配置、助手、记忆、文件等本机数据。下面两项默认不恢复——勾选才会把备份里的对应内容也覆盖到本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IncludeToggleRow(
                    checked = restoreConversations,
                    title = "恢复对话",
                    description = "勾选后，备份里的对话历史会覆盖本机现有对话。不勾选则保留本机对话。",
                    onCheckedChange = onRestoreConversationsChange,
                )
                IncludeToggleRow(
                    checked = restoreGenMedia,
                    title = "恢复生成的图片",
                    description = "勾选后，备份里的生成图（对话内联图 + 独立画廊）会覆盖本机。不勾选则保留本机的图。",
                    onCheckedChange = onRestoreGenMediaChange,
                )
            }
        },
        confirmButton = {
            Button(onClick = onRestore) {
                Text("覆盖")
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
private fun IncludeToggleRow(
    checked: Boolean,
    title: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                role = androidx.compose.ui.semantics.Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,  // handled by selectable() on the row
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
