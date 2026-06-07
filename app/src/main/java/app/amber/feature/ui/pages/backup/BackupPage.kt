package app.amber.feature.ui.pages.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import app.amber.core.sync.core.CURRENT_ARCHIVE_VERSION
import app.amber.core.sync.core.SYNC_ARCHIVE_MIME
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncSettings
import app.amber.core.sync.google.GoogleDriveFile
import app.amber.core.sync.local.LocalBackupRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.UiState
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
    if (syncSettings.lastBackupVersionName.isBlank()) return "暂无成功备份"
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
    return "最近成功：" + parts.joinToString(separator = " · ")
}

@Composable
private fun BackupStatusContent(
    syncSettings: SyncSettings,
    activity: BackupActivity?,
) {
    if (activity == null) {
        // Graphite §3: backup status is timestamp + version + device — machine facts → MONO (meta), muted ink.
        Text(
            formatBackupStatus(syncSettings),
            style = LocalAmberType.current.meta,
            color = LocalAmberTokens.current.ink3,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(activity.title, style = LocalAmberType.current.body)
        if (activity.detail.isNotBlank()) {
            Text(
                activity.detail,
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        }
        val progress = activity.progress
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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
    val backupActivity by vm.backupActivity.collectAsState()
    val pendingGoogleAuthorization by vm.pendingGoogleAuthorization.collectAsState()
    val pendingCloudRestore by vm.pendingCloudRestore.collectAsState()
    val cloudConflict by vm.cloudConflict.collectAsState()
    val cloudSnapshots by vm.cloudSnapshots.collectAsState()
    val cloudSnapshotPickerVisible by vm.cloudSnapshotPickerVisible.collectAsState()
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
    var restoreObservedLoading by remember { mutableStateOf(false) }
    var showRestoreSuccessDialog by remember { mutableStateOf(false) }
    val googleAvailable = vm.googleConfigStatus.available
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
    // operationState alone isn't enough — uploads/downloads and backup previews
    // also flip it to Success. Require this restore attempt to pass through
    // Loading before treating Success as completion.
    LaunchedEffect(operationState, restoreInFlight) {
        if (!restoreInFlight) {
            restoreObservedLoading = false
            return@LaunchedEffect
        }
        when (operationState) {
            is UiState.Loading -> {
                restoreObservedLoading = true
            }
            is UiState.Success -> {
                if (restoreObservedLoading) {
                    restoreInFlight = false
                    restoreObservedLoading = false
                    showRestoreSuccessDialog = true
                }
            }
            is UiState.Error -> {
                // Failure already surfaces its own toast via googleMessage /
                // localMessage; just clear the in-flight flag so subsequent
                // success events don't accidentally fire the hint.
                restoreInFlight = false
                restoreObservedLoading = false
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
            WorkspaceTopBar(
                title = "同步与备份",
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
            CardGroup(title = { SectionLabel("Google Drive") }) {
                item(
                    onClick = if (googleAvailable) {
                        { vm.connectGoogle() }
                    } else {
                        null
                    },
                    leadingContent = { Icon(HugeIcons.Database02, contentDescription = null) },
                    headlineContent = { Text("Google 账号") },
                    supportingContent = {
                        Text(
                            when {
                                !googleAvailable -> vm.googleConfigStatus.reason
                                googleMessage.isNotBlank() -> googleMessage
                                googleSession != null -> "已连接：${googleSession?.label.orEmpty()}"
                                hasGoogleConnection -> "上次连接：${settings.syncSettings.googleAccountEmail}"
                                settings.syncSettings.googleAccountEmail.isNotBlank() ->
                                    "上次连接：${settings.syncSettings.googleAccountEmail}"
                                vm.googleConfigStatus.reason.isNotBlank() -> vm.googleConfigStatus.reason
                                else -> "登录后即可上传和下载同步快照"
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            when {
                                !googleAvailable -> "不可用"
                                hasGoogleConnection -> "已连接"
                                else -> "连接"
                            },
                            color = if (googleAvailable && hasGoogleConnection) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
                item(
                    leadingContent = { Icon(HugeIcons.CloudSavingDone01, contentDescription = null) },
                    headlineContent = { Text("备份状态") },
                    supportingContent = {
                        BackupStatusContent(
                            syncSettings = settings.syncSettings,
                            activity = backupActivity,
                        )
                    },
                    trailingContent = {
                        backupActivity?.let { activity ->
                            val progress = activity.progress
                            if (progress == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                // Graphite §3: progress percent is a machine-fact number → MONO (meta).
                                Text(
                                    "${(progress * 100).toInt()}%",
                                    style = LocalAmberType.current.meta,
                                    color = LocalAmberTokens.current.ink2,
                                )
                            }
                        }
                    },
                )
                item(
                    onClick = if (googleAvailable) {
                        {
                            if (googleSession == null) {
                                pendingGoogleAction = GoogleSyncAction.Upload
                                vm.connectGoogle()
                            } else {
                                vm.uploadGoogle(SyncMode.FULL, passphrase = "")
                            }
                        }
                    } else {
                        null
                    },
                    leadingContent = { Icon(HugeIcons.Upload02, contentDescription = null) },
                    headlineContent = { Text("上传") },
                    supportingContent = {
                        Text(if (googleAvailable) "把当前数据保存到 Google Drive" else "Google Drive 尚未配置")
                    }
                )
                item(
                    onClick = if (googleAvailable) {
                        {
                            if (googleSession == null) {
                                pendingGoogleAction = GoogleSyncAction.Download
                                vm.connectGoogle()
                            } else {
                                vm.downloadGooglePreview()
                            }
                        }
                    } else {
                        null
                    },
                    leadingContent = { Icon(HugeIcons.Download04, contentDescription = null) },
                    headlineContent = { Text("下载") },
                    supportingContent = {
                        Text(if (googleAvailable) "从 Google Drive 恢复到这台设备" else "Google Drive 尚未配置")
                    }
                )
            }

            CardGroup(title = { SectionLabel("本地备份") }) {
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

    if (cloudSnapshotPickerVisible) {
        CloudSnapshotPickerDialog(
            snapshots = cloudSnapshots,
            onDismiss = { vm.dismissCloudSnapshotPicker() },
            onSelect = { vm.downloadGoogleSnapshot(it) },
        )
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            restoreConversations = restoreConversations,
            restoreGenMedia = restoreGenMedia,
            restoreActivity = backupActivity,
            restoring = restoreInFlight,
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
                restoreObservedLoading = false
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
                    Text(
                        "Google Drive 已有一个不同 revision 的同步快照。",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                    // Graphite §3: remote modified time + local revision are machine facts → MONO (meta).
                    Text(
                        "云端修改时间：${conflict.remoteFile.modifiedTime ?: "未知"}",
                        style = LocalAmberType.current.meta,
                        color = LocalAmberTokens.current.ink,
                    )
                    Text(
                        "本机记录 revision：${conflict.localRevision.ifBlank { "无" }}",
                        style = LocalAmberType.current.meta,
                        color = LocalAmberTokens.current.ink,
                    )
                    Text(
                        "为避免静默丢数据，请确认是否用本机快照覆盖云端。",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
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
private fun CloudSnapshotPickerDialog(
    snapshots: List<GoogleDriveFile>,
    onDismiss: () -> Unit,
    onSelect: (GoogleDriveFile) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择云端快照") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                snapshots.forEachIndexed { index, snapshot ->
                    val unsupported = snapshot.archiveVersion?.let { it != CURRENT_ARCHIVE_VERSION } == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !unsupported) { onSelect(snapshot) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Graphite §3: snapshot title (timestamp · version) and detail
                            // (device · size · archive format) are machine facts → MONO (meta).
                            Text(
                                formatCloudSnapshotTitle(snapshot),
                                style = LocalAmberType.current.meta,
                                color = LocalAmberTokens.current.ink,
                            )
                            Text(
                                formatCloudSnapshotDetail(snapshot, unsupported),
                                style = LocalAmberType.current.meta,
                                color = LocalAmberTokens.current.ink3,
                            )
                        }
                        Text(
                            if (unsupported) "不支持" else "选择",
                            color = if (unsupported) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    if (index != snapshots.lastIndex) {
                        Hairline()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun formatCloudSnapshotTitle(snapshot: GoogleDriveFile): String {
    val createdAt = snapshot.backupCreatedAt?.let {
        BackupStatusDateFormat.format(Date(it))
    } ?: snapshot.modifiedTime?.take(16)?.replace('T', ' ') ?: "未知时间"
    val version = snapshot.backupVersionName.ifBlank { "未知版本" }
    return "$createdAt · $version"
}

private fun formatCloudSnapshotDetail(snapshot: GoogleDriveFile, unsupported: Boolean): String {
    val parts = mutableListOf<String>()
    if (snapshot.backupDeviceLabel.isNotBlank()) parts += snapshot.backupDeviceLabel
    formatDriveSize(snapshot.size)?.let { parts += it }
    snapshot.archiveVersion?.let { archiveVersion ->
        parts += if (unsupported) {
            "备份格式 v$archiveVersion 不兼容"
        } else {
            "备份格式 v$archiveVersion"
        }
    }
    if (parts.isEmpty()) parts += snapshot.name
    return parts.joinToString(separator = " · ")
}

private fun formatDriveSize(size: String?): String? {
    val bytes = size?.toLongOrNull() ?: return null
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mib)
    } else {
        "${bytes / 1024} KB"
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: SyncPreview,
    restoreConversations: Boolean,
    restoreGenMedia: Boolean,
    restoreActivity: BackupActivity?,
    restoring: Boolean,
    onRestoreConversationsChange: (Boolean) -> Unit,
    onRestoreGenMediaChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!restoring) onDismiss()
        },
        title = { Text("确认覆盖") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (restoring) {
                    BackupStatusContent(
                        syncSettings = SyncSettings(),
                        activity = restoreActivity ?: BackupActivity(title = "正在恢复备份"),
                    )
                    Hairline()
                }
                // Graphite §3: backup createdAt + version strings are machine facts → MONO (meta).
                Text(
                    "创建时间：${preview.createdAt}",
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                Text(
                    "版本：${preview.manifest.appVersionName} / ${preview.manifest.appVersionCode}",
                    style = LocalAmberType.current.meta,
                    color = LocalAmberTokens.current.ink,
                )
                Hairline()
                Text(
                    "覆盖会替换 Provider 配置、助手、记忆、文件等本机数据。下面两项默认不恢复——勾选才会把备份里的对应内容也覆盖到本机。",
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
                IncludeToggleRow(
                    checked = restoreConversations,
                    title = "恢复对话",
                    description = "勾选后，备份里的对话历史会覆盖本机现有对话。不勾选则保留本机对话。",
                    enabled = !restoring,
                    onCheckedChange = onRestoreConversationsChange,
                )
                IncludeToggleRow(
                    checked = restoreGenMedia,
                    title = "恢复生成的图片",
                    description = "勾选后，备份里的生成图（对话内联图 + 独立画廊）会覆盖本机。不勾选则保留本机的图。",
                    enabled = !restoring,
                    onCheckedChange = onRestoreGenMediaChange,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onRestore,
                enabled = !restoring,
            ) {
                if (restoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (restoring) "恢复中" else "覆盖")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !restoring,
            ) {
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
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,  // handled by selectable() on the row
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = LocalAmberType.current.body)
            Text(
                description,
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        }
    }
}
