package app.amber.feature.ui.pages.backup

import app.amber.feature.ui.pages.backup.components.BackupDialog

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.DatabaseZap
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.FileInput
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Upload
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import app.amber.core.sync.core.CURRENT_ARCHIVE_VERSION
import app.amber.core.sync.core.NO_PASSPHRASE_FALLBACK
import app.amber.core.sync.core.SYNC_ARCHIVE_MIME
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncSettings
import app.amber.core.sync.google.GoogleDriveFile
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.UploadConflictPolicy
import app.amber.core.sync.provider.checkSnapshotCompatibility
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.OpenableColumns
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

private val BackupStatusDateFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
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
    if (latestAt > 0L) parts += BackupStatusDateFormat.get()!!.format(Date(latestAt))
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
    val context = LocalContext.current
    // P7-01 provider 状态。
    val providerV2Enabled by vm.providerV2Enabled.collectAsState()
    val webDavSnapshots by vm.webDavSnapshots.collectAsState()
    val webDavMessage by vm.webDavMessage.collectAsState()
    val localFolderSnapshots by vm.localFolderSnapshots.collectAsState()
    val folderMessage by vm.folderMessage.collectAsState()
    val folderInfo by vm.folderInfo.collectAsState()
    val pendingDeleteConfirm by vm.pendingDeleteConfirm.collectAsState()
    val pendingUploadConflict by vm.pendingUploadConflict.collectAsState()
    val pendingExportDialog by vm.pendingExportDialog.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingGoogleAction by remember { mutableStateOf<GoogleSyncAction?>(null) }
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
                GoogleSyncAction.Upload -> vm.requestExport(ExportSource.Google)
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
            // P7-02：导出必须选择口令加密或设备绑定加密（二选一），
            // 由 ExportEncryptionDialog 收集后调用 vm.confirmExport。
            vm.requestExport(ExportRequestSeed.localFile(uri))
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.inspectImport(uri)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // P7-01：持久 URI permission —— 授权跨进程重启保留，下次直接复用文件夹。
            val contentResolver = context.contentResolver
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val displayName = runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull().orEmpty()
            vm.saveLocalFolder(uri, displayName)
        }
    }

    // P7-01 WebDAV 配置草稿（本地编辑态，保存时才写入 settings）。
    var webDavUrl by remember { mutableStateOf(settings.webDavConfig.url) }
    var webDavUsername by remember { mutableStateOf(settings.webDavConfig.username) }
    var webDavPassword by remember { mutableStateOf(settings.webDavConfig.password) }
    var webDavPath by remember { mutableStateOf(settings.webDavConfig.path) }

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
                    leadingContent = { Icon(Lucide.DatabaseZap, contentDescription = null) },
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
                    leadingContent = { Icon(Lucide.Cloud, contentDescription = null) },
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
                                vm.requestExport(ExportSource.Google)
                            }
                        }
                    } else {
                        null
                    },
                    leadingContent = { Icon(Lucide.Upload, contentDescription = null) },
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
                    leadingContent = { Icon(Lucide.CloudDownload, contentDescription = null) },
                    headlineContent = { Text("下载") },
                    supportingContent = {
                        Text(if (googleAvailable) "从 Google Drive 恢复到这台设备" else "Google Drive 尚未配置")
                    }
                )
            }

            if (providerV2Enabled) {
                CardGroup(title = { SectionLabel("WebDAV") }) {
                    item(
                        leadingContent = { Icon(Lucide.DatabaseZap, contentDescription = null) },
                        headlineContent = { Text("服务器配置") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = webDavUrl,
                                    onValueChange = { webDavUrl = it },
                                    label = { Text("服务器地址") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = webDavUsername,
                                    onValueChange = { webDavUsername = it },
                                    label = { Text("用户名") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = webDavPassword,
                                    onValueChange = { webDavPassword = it },
                                    label = { Text("密码") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = webDavPath,
                                    onValueChange = { webDavPath = it },
                                    label = { Text("备份目录") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            vm.saveWebDavConfig(webDavUrl, webDavUsername, webDavPassword, webDavPath)
                                        },
                                    ) { Text("保存配置") }
                                    TextButton(onClick = { vm.refreshWebDavSnapshots() }) {
                                        Text("读取快照")
                                    }
                                }
                                if (webDavMessage.isNotBlank()) {
                                    Text(
                                        webDavMessage,
                                        style = LocalAmberType.current.secondary,
                                        color = LocalAmberTokens.current.ink3,
                                    )
                                }
                            }
                        },
                    )
                    item(
                        onClick = { vm.requestExport(ExportSource.WebDav) },
                        leadingContent = { Icon(Lucide.Upload, contentDescription = null) },
                        headlineContent = { Text("上传") },
                        supportingContent = { Text("把当前数据作为新快照保存到 WebDAV") },
                    )
                }
                if (webDavSnapshots.isNotEmpty()) {
                    CardGroup(title = { SectionLabel("WebDAV 快照") }) {
                        webDavSnapshots.forEachIndexed { index, snapshot ->
                            rawItem {
                                ProviderSnapshotRow(
                                    snapshot = snapshot,
                                    onRestore = { vm.downloadWebDavSnapshot(snapshot) },
                                    onDelete = { vm.requestDelete(snapshot) },
                                )
                            }
                            if (index != webDavSnapshots.lastIndex) rawItem { Hairline() }
                        }
                    }
                }

                CardGroup(title = { SectionLabel("本地文件夹") }) {
                    item(
                        onClick = { folderPickerLauncher.launch(null) },
                        leadingContent = { Icon(Lucide.FolderOpen, contentDescription = null) },
                        headlineContent = { Text(if (folderInfo != null) "更换文件夹" else "选择文件夹") },
                        supportingContent = {
                            Text(
                                folderMessage.ifBlank {
                                    folderInfo?.displayName?.takeIf { it.isNotBlank() }
                                        ?: "选择后授权保留，可直接读写文件夹里的快照"
                                }
                            )
                        },
                    )
                    item(
                        onClick = { vm.refreshLocalFolderSnapshots() },
                        leadingContent = { Icon(Lucide.DatabaseZap, contentDescription = null) },
                        headlineContent = { Text("读取快照") },
                        supportingContent = { Text("列出所选文件夹里的同步快照") },
                    )
                    item(
                        onClick = { vm.requestExport(ExportSource.LocalFolder) },
                        leadingContent = { Icon(Lucide.Upload, contentDescription = null) },
                        headlineContent = { Text("上传") },
                        supportingContent = { Text("把当前数据作为新快照保存到文件夹") },
                    )
                }
                if (localFolderSnapshots.isNotEmpty()) {
                    CardGroup(title = { SectionLabel("文件夹快照") }) {
                        localFolderSnapshots.forEachIndexed { index, snapshot ->
                            rawItem {
                                ProviderSnapshotRow(
                                    snapshot = snapshot,
                                    onRestore = { vm.downloadLocalFolderSnapshot(snapshot) },
                                    onDelete = { vm.requestDelete(snapshot) },
                                )
                            }
                            if (index != localFolderSnapshots.lastIndex) rawItem { Hairline() }
                        }
                    }
                }
            }

            CardGroup(title = { SectionLabel("本地备份") }) {
                item(
                    onClick = {
                        createDocumentLauncher.launch(LocalBackupRepository.suggestedFileName())
                    },
                    leadingContent = { Icon(Lucide.Upload, contentDescription = null) },
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
                        openDocumentLauncher.launch(arrayOf(SYNC_ARCHIVE_MIME, "application/zip", "*/*"))
                    },
                    leadingContent = { Icon(Lucide.FileInput, contentDescription = null) },
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
            onDelete = { vm.requestDeleteGoogle(it) },
        )
    }

    // P7-02 恢复两阶段：先输入口令并验证（头部 + 认证标签 + 解密，不写入），
    // 解密成功后展示恢复 preview（复用 ImportPreviewDialog），确认后才写入。
    val verifiedRestore by vm.pendingVerifiedRestore.collectAsState()
    if (importPreview != null && verifiedRestore == null) {
        RestorePassphraseDialog(
            preview = importPreview!!,
            verifying = operationState is UiState.Loading,
            onDismiss = {
                vm.clearPendingImport()
                restoreConversations = false
                restoreGenMedia = false
            },
            onVerify = { passphrase ->
                // UI's "restoreX" maps inversely to engine's "preserveX".
                val preserveConversations = !restoreConversations
                val preserveGenMedia = !restoreGenMedia
                if (vm.pendingProviderRestore.value != null) {
                    vm.restorePendingProvider(
                        passphrase = passphrase,
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    )
                } else if (pendingCloudRestore) {
                    vm.restoreGoogle(
                        passphrase = passphrase,
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    )
                } else {
                    vm.restorePendingLocal(
                        passphrase = passphrase,
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    )
                }
            },
        )
    }

    verifiedRestore?.let { verification ->
        ImportPreviewDialog(
            preview = verification.preview,
            payloadPreview = verification.payloadPreview,
            restoreConversations = restoreConversations,
            restoreGenMedia = restoreGenMedia,
            restoreActivity = backupActivity,
            restoring = restoreInFlight,
            onRestoreConversationsChange = { restoreConversations = it },
            onRestoreGenMediaChange = { restoreGenMedia = it },
            onDismiss = {
                vm.dismissVerifiedRestore()
                // Reset toggles to safe defaults (don't override local data)
                // for the next restore.
                restoreConversations = false
                restoreGenMedia = false
            },
            onRestore = {
                restoreObservedLoading = false
                restoreInFlight = true
                // UI's "restoreX" maps inversely to engine's "preserveX".
                val preserveConversations = !restoreConversations
                val preserveGenMedia = !restoreGenMedia
                vm.applyVerifiedRestore(
                    preserveConversations = preserveConversations,
                    preserveGenMedia = preserveGenMedia,
                )
            },
        )
    }

    pendingExportDialog?.let { seed ->
        ExportEncryptionDialog(
            seed = seed,
            onDismiss = { vm.dismissExportDialog() },
            onConfirm = { passphrase, encryptionMode ->
                vm.confirmExport(passphrase, encryptionMode)
            },
        )
    }

    pendingDeleteConfirm?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { vm.dismissPendingDelete() },
            title = { Text("删除远端快照") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将删除 ${snapshot.name}（${snapshot.manifest.deviceLabel.ifBlank { "未知设备" }}，" +
                            "${snapshot.manifest.appVersionName.ifBlank { "未知版本" }}）。此操作不可撤销。",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { vm.confirmPendingDelete() }) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPendingDelete() }) {
                    Text("取消")
                }
            },
        )
    }

    pendingUploadConflict?.let { choice ->
        AlertDialog(
            onDismissRequest = { vm.dismissUploadConflict() },
            title = { Text("上传冲突") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "远端已有这台设备的快照（${choice.snapshot.manifest.createdAt}）。" +
                            "覆盖会删除旧快照，创建副本会保留全部。",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { vm.resolveUploadConflict(UploadConflictPolicy.OVERWRITE) }) {
                    Text("覆盖旧快照")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolveUploadConflict(UploadConflictPolicy.CREATE_COPY) }) {
                    Text("创建副本")
                }
            },
        )
    }

    if (showRestoreSuccessDialog) {
        // 整量恢复替换了全部表和文件，但内存会话与 Room Flow 不会自动失效——
        // 必须强制重启进程，否则旧内存状态会被重新写回新库造成数据混合。
        // （BackupDialog 仅一个确认按钮，点击后 exitProcess(0)）
        BackupDialog()
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
    onDelete: (GoogleDriveFile) -> Unit,
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
                    // P7-02：v1 旧格式允许只读恢复（迁移）。
                    val unsupported = snapshot.archiveVersion?.let {
                        it != CURRENT_ARCHIVE_VERSION && it != app.amber.core.sync.core.LEGACY_ARCHIVE_VERSION
                    } == true
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
                        TextButton(onClick = { onDelete(snapshot) }) {
                            Icon(
                                Lucide.Trash,
                                contentDescription = "删除",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        BackupStatusDateFormat.get()!!.format(Date(it))
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
    payloadPreview: app.amber.core.sync.core.SyncPayloadPreview? = null,
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
                if (preview.legacyFormat) {
                    // P7-02 迁移引导：旧格式只读恢复，恢复后建议重存为新格式。
                    Text(
                        "该备份为旧格式（v${preview.manifest.archiveVersion}），已兼容恢复。" +
                            "恢复后建议重新导出，以使用新的加密格式。",
                        style = LocalAmberType.current.secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                payloadPreview?.let { payload ->
                    Hairline()
                    Text(
                        "备份内容：会话 ${payload.conversationCount} · 消息 ${payload.messageNodeCount}" +
                            " · 附件 ${payload.attachmentCount} · 文件 ${payload.fileCount}" +
                            if (payload.estimatedBytes > 0L) " · 约 ${formatBytes(payload.estimatedBytes)}" else "",
                        style = LocalAmberType.current.meta,
                        color = LocalAmberTokens.current.ink,
                    )
                    if (payload.includesSecrets) {
                        Text(
                            "该备份包含 OAuth 登录令牌（FULL 模式）。",
                            style = LocalAmberType.current.secondary,
                            color = LocalAmberTokens.current.ink3,
                        )
                    }
                }
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
private fun ProviderSnapshotRow(
    snapshot: SyncSnapshot,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val compatibility = checkSnapshotCompatibility(snapshot.manifest)
    val unsupported = compatibility !is app.amber.core.sync.provider.SnapshotCompatibility.Compatible
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !unsupported) { onRestore() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Graphite §3: snapshot title (timestamp · version) and detail
            // (device · size · domains) are machine facts → MONO (meta).
            Text(
                formatProviderSnapshotTitle(snapshot),
                style = LocalAmberType.current.meta,
                color = LocalAmberTokens.current.ink,
            )
            Text(
                formatProviderSnapshotDetail(snapshot, unsupported, compatibility),
                style = LocalAmberType.current.meta,
                color = LocalAmberTokens.current.ink3,
            )
        }
        TextButton(onClick = onDelete) {
            Icon(
                Lucide.Trash,
                contentDescription = "删除",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (unsupported) "不兼容" else "恢复",
            color = if (unsupported) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

private fun formatProviderSnapshotTitle(snapshot: SyncSnapshot): String {
    val createdAt = snapshot.manifest.createdAt.let {
        if (it > 0L) BackupStatusDateFormat.get()!!.format(Date(it)) else "未知时间"
    }
    val version = snapshot.manifest.appVersionName.ifBlank { "未知版本" }
    return "$createdAt · $version"
}

private fun formatProviderSnapshotDetail(
    snapshot: SyncSnapshot,
    unsupported: Boolean,
    compatibility: app.amber.core.sync.provider.SnapshotCompatibility,
): String {
    val parts = mutableListOf<String>()
    if (snapshot.manifest.deviceLabel.isNotBlank()) parts += snapshot.manifest.deviceLabel
    if (snapshot.sizeBytes > 0L) {
        parts += if (snapshot.sizeBytes >= 1024 * 1024) {
            String.format(Locale.getDefault(), "%.1f MB", snapshot.sizeBytes / (1024.0 * 1024.0))
        } else {
            "${snapshot.sizeBytes / 1024} KB"
        }
    }
    parts += if (unsupported) {
        "格式不兼容：${(compatibility as? app.amber.core.sync.provider.SnapshotCompatibility.Incompatible)?.reason.orEmpty()}"
    } else {
        "加密:${if (snapshot.manifest.encrypted) "是" else "否"} " +
            "领域:${snapshot.manifest.includedDomains.joinToString("/").ifBlank { "无" }}"
    }
    return parts.joinToString(separator = " · ")
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

/**
 * P7-02 恢复第一步：输入口令（或确认设备绑定/旧无口令格式）并触发验证。
 * 验证只做头部 + 认证标签 + 解密，不写入；解密成功后由 ImportPreviewDialog
 * 展示恢复 preview，用户确认后才覆盖。
 */
@Composable
private fun RestorePassphraseDialog(
    preview: SyncPreview,
    verifying: Boolean,
    onDismiss: () -> Unit,
    onVerify: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val needsPassphrase = restoreNeedsPassphrase(preview)
    val mismatch = needsPassphrase && passphrase != confirm
    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
        title = { Text("恢复备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (preview.legacyFormat) {
                    Text(
                        "该备份为旧格式（v${preview.manifest.archiveVersion}），可兼容恢复。",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                if (needsPassphrase) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("备份口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("确认口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mismatch) {
                        Text(
                            "两次输入的口令不一致",
                            style = LocalAmberType.current.secondary,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        if (preview.manifest.encryptionMode == SyncEncryptionMode.DEVICE_BOUND) {
                            "该备份使用设备绑定加密，可直接在本设备恢复（无需口令）。"
                        } else {
                            "该备份未设置口令（历史格式），可直接恢复。"
                        },
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                if (verifying) {
                    BackupStatusContent(
                        syncSettings = SyncSettings(),
                        activity = BackupActivity(title = "正在验证备份", detail = "正在校验口令与加密头"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onVerify(passphrase) },
                enabled = !verifying && (!needsPassphrase || (passphrase.isNotBlank() && !mismatch)),
            ) {
                Text(if (verifying) "验证中" else "验证并预览")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text("取消")
            }
        },
    )
}

/**
 * P7-02 导出加密方式：口令加密（输入 + 确认 + 强度提示）或设备绑定加密，
 * 二选一的明确 UI。新备份不再允许“无口令”导出。
 */
@Composable
private fun ExportEncryptionDialog(
    seed: ExportRequestSeed,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String, mode: SyncEncryptionMode) -> Unit,
) {
    var mode by remember { mutableStateOf(SyncEncryptionMode.PASSPHRASE) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val error = when {
        mode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank() -> "请输入自定义备份口令"
        mode == SyncEncryptionMode.PASSPHRASE && passphrase != confirm -> "两次输入的口令不一致"
        passphrase == NO_PASSPHRASE_FALLBACK -> "这个口令是内部保留值，请换一个口令"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加密备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == SyncEncryptionMode.PASSPHRASE,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { mode = SyncEncryptionMode.PASSPHRASE },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = mode == SyncEncryptionMode.PASSPHRASE,
                        onClick = null,
                    )
                    Text("自定义口令加密（推荐）", style = LocalAmberType.current.body)
                }
                if (mode == SyncEncryptionMode.PASSPHRASE) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("备份口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("确认口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "口令强度：${passphraseStrength(passphrase)}",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == SyncEncryptionMode.DEVICE_BOUND,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { mode = SyncEncryptionMode.DEVICE_BOUND },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = mode == SyncEncryptionMode.DEVICE_BOUND,
                        onClick = null,
                    )
                    Text("本设备绑定加密", style = LocalAmberType.current.body)
                }
                if (mode == SyncEncryptionMode.DEVICE_BOUND) {
                    Text(
                        "使用本机密钥加密，无需口令，但只能在这台设备上恢复。",
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = LocalAmberType.current.secondary,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passphrase, mode) },
                enabled = error == null,
            ) {
                Text("开始导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun passphraseStrength(passphrase: String): String {
    if (passphrase.length < 8) return "弱"
    val classes = listOf(
        Regex("[a-z]"),
        Regex("[A-Z]"),
        Regex("[0-9]"),
        Regex("[^A-Za-z0-9]"),
    ).count { it.containsMatchIn(passphrase) }
    return when {
        passphrase.length >= 12 && classes >= 3 -> "强"
        classes >= 2 -> "中"
        else -> "弱"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fMB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
}
