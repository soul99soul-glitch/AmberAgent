package app.amber.feature.ui.pages.backup

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.NO_PASSPHRASE_FALLBACK
import app.amber.core.sync.core.RestoreScope
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.google.GoogleDriveAuthSession
import app.amber.core.sync.google.GoogleDriveAuthRequiredException
import app.amber.core.sync.google.GoogleDriveAuthorizationOutcome
import app.amber.core.sync.google.GoogleDriveFile
import app.amber.core.sync.google.GoogleDriveSyncRepository
import app.amber.core.sync.google.GoogleOAuthConfigGate
import app.amber.core.sync.google.GoogleOAuthConfigStatus
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.utils.UiState
import java.io.File
import kotlin.uuid.Uuid

class BackupVM(
    private val settingsStore: SettingsAggregator,
    private val localBackupRepository: LocalBackupRepository,
    private val googleDriveSyncRepository: GoogleDriveSyncRepository,
    googleOAuthConfigGate: GoogleOAuthConfigGate,
) : ViewModel() {
    companion object {
        private const val TAG = "BackupVM"
    }

    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val operationState = MutableStateFlow<UiState<SyncPreview>>(UiState.Idle)
    val pendingImportPreview = MutableStateFlow<SyncPreview?>(null)
    val googleSession = MutableStateFlow<GoogleDriveAuthSession?>(null)
    val googleMessage = MutableStateFlow("")
    val localMessage = MutableStateFlow("")
    val backupActivity = MutableStateFlow<BackupActivity?>(null)
    val pendingGoogleAuthorization = MutableStateFlow<PendingIntent?>(null)
    val pendingCloudRestore = MutableStateFlow(false)
    val cloudConflict = MutableStateFlow<GoogleCloudConflict?>(null)
    val cloudSnapshots = MutableStateFlow<List<GoogleDriveFile>>(emptyList())
    val cloudSnapshotPickerVisible = MutableStateFlow(false)
    // Build-time Google services config is static for this process; keep the
    // status as a value so Compose does not imply live revalidation support.
    val googleConfigStatus: GoogleOAuthConfigStatus = googleOAuthConfigGate.status()

    private var pendingCloudRestoreFile: File? = null
    private var pendingCloudRestoreRevision: String = ""
    private var pendingCloudUploadRequest: SyncExportRequest? = null
    private var googleAuthorizationInFlight = false

    init {
        viewModelScope.launch {
            settingsStore.update { current ->
                if (current.init || current.syncSettings.deviceId.isNotBlank()) {
                    current
                } else {
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            deviceId = Uuid.random().toString()
                        )
                    )
                }
            }
            restoreGoogleSessionIfPossible()
        }
    }

    fun updateMode(mode: SyncMode) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(syncSettings = current.syncSettings.copy(mode = mode))
            }
        }
    }

    fun connectGoogle() {
        if (googleUnavailable()) return
        if (googleAuthorizationInFlight) return
        googleAuthorizationInFlight = true
        viewModelScope.launch {
            operationState.value = UiState.Loading
            try {
                runCatching {
                    googleDriveSyncRepository.authorizeDrive()
                }.onSuccess { outcome ->
                    when (outcome) {
                        is GoogleDriveAuthorizationOutcome.Authorized -> {
                            applyGoogleSession(outcome.session)
                            operationState.value = UiState.Idle
                        }

                        is GoogleDriveAuthorizationOutcome.ResolutionRequired -> {
                            pendingGoogleAuthorization.value = outcome.pendingIntent
                            operationState.value = UiState.Idle
                        }
                    }
                }.onFailure { error ->
                    operationState.value = UiState.Error(error)
                    googleMessage.value = "Google 授权失败：${error.message.orEmpty()}"
                    recordError(error)
                }
            } finally {
                if (pendingGoogleAuthorization.value == null) {
                    googleAuthorizationInFlight = false
                }
            }
        }
    }

    fun consumePendingGoogleAuthorization() {
        pendingGoogleAuthorization.value = null
    }

    fun completeGoogleAuthorization(intent: Intent?) {
        viewModelScope.launch {
            operationState.value = UiState.Loading
            try {
                runCatching {
                    googleDriveSyncRepository.completeAuthorization(intent)
                }.onSuccess { session ->
                    applyGoogleSession(session)
                    operationState.value = UiState.Idle
                }.onFailure { error ->
                    operationState.value = UiState.Error(error)
                    googleMessage.value = "Google 授权失败：${error.message.orEmpty()}"
                    recordError(error)
                }
            } finally {
                googleAuthorizationInFlight = false
            }
        }
    }

    fun handleGoogleAuthorizationResult(resultCode: Int, intent: Intent?) {
        Log.i(TAG, "Google authorization result: resultCode=$resultCode hasData=${intent != null}")
        if (intent != null) {
            completeGoogleAuthorization(intent)
        } else {
            cancelGoogleAuthorization(resultCode)
        }
    }

    fun cancelGoogleAuthorization(resultCode: Int? = null) {
        googleAuthorizationInFlight = false
        googleMessage.value = if (resultCode == 0) {
            "Google 授权未完成或被系统取消"
        } else {
            "Google 授权已取消"
        }
        operationState.value = UiState.Idle
    }

    fun uploadGoogle(mode: SyncMode, passphrase: String, overwrite: Boolean = false) {
        if (googleUnavailable()) return
        val session = googleSession.value
        if (session == null) {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再上传云端快照。"
            return
        }
        // [Review #6 fix] Reject the literal NO_PASSPHRASE_FALLBACK string —
        // letting it through would silently stamp passphraseProtected=false
        // on restore and skip the password prompt, which contradicts the
        // user's intent of "I set a password".
        if (passphrase == NO_PASSPHRASE_FALLBACK) {
            operationState.value = UiState.Error(IllegalArgumentException("这个口令是内部保留值，请换一个口令"))
            return
        }
        // Blank passphrase from the upload sheet means "no password" — sub in
        // the documented fallback string. The engine stamps the manifest's
        // passphraseProtected flag based on this substitution.
        val resolvedPassphrase = passphrase.ifBlank { NO_PASSPHRASE_FALLBACK }
        val request = SyncExportRequest(mode = mode, passphrase = resolvedPassphrase)
        startGoogleUpload(session, request, overwrite)
    }

    private fun startGoogleUpload(
        session: GoogleDriveAuthSession,
        request: SyncExportRequest,
        overwrite: Boolean,
    ) {
        if (googleUnavailable()) return
        val uploadTitle = if (overwrite) "正在覆盖云端快照" else "正在上传云端快照"
        var lastUploadPercent: Int? = null
        var emittedUnknownProgress = false
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                title = if (overwrite) "准备覆盖云端快照" else "准备上传云端快照",
                detail = "正在连接 Google Drive",
            )
            runCatching {
                val activeSession = refreshGoogleSessionForOperation() ?: session
                backupActivity.value = BackupActivity(
                    title = uploadTitle,
                    detail = "正在生成加密备份文件",
                )
                googleDriveSyncRepository.upload(
                    session = activeSession,
                    request = request,
                    onProgress = { uploadedBytes, totalBytes ->
                        if (totalBytes > 0L) {
                            val percent = ((uploadedBytes.toDouble() / totalBytes.toDouble()) * 100)
                                .toInt()
                                .coerceIn(0, 100)
                            if (percent != lastUploadPercent) {
                                lastUploadPercent = percent
                                backupActivity.value = BackupActivity(
                                    title = uploadTitle,
                                    detail = "$percent%",
                                    progress = percent / 100f,
                                )
                            }
                        } else {
                            if (!emittedUnknownProgress) {
                                emittedUnknownProgress = true
                                backupActivity.value = BackupActivity(
                                    title = uploadTitle,
                                    detail = "正在上传...",
                                )
                            }
                        }
                    },
                )
            }.onSuccess { result ->
                val manifest = result.preview.manifest
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            googleEnabled = true,
                            googleAccountEmail = googleSession.value?.accountEmail ?: session.accountEmail,
                            googleAccountId = googleSession.value?.accountId ?: session.accountId,
                            googleDisplayName = googleSession.value?.displayName ?: session.displayName,
                            mode = request.mode,
                            lastUploadAt = System.currentTimeMillis(),
                            lastRemoteRevision = result.file.revisionKey,
                            lastError = "",
                            lastBackupVersionName = manifest.appVersionName,
                            lastBackupVersionCode = manifest.appVersionCode,
                            lastBackupDeviceLabel = manifest.deviceLabel,
                        )
                    )
                }
                googleMessage.value = ""
                backupActivity.value = null
                operationState.value = UiState.Success(result.preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                googleMessage.value = "云端上传失败：${error.message.orEmpty()}"
                recordGoogleDriveError(error)
            }
        }
    }

    fun confirmOverwriteCloud() {
        val request = pendingCloudUploadRequest ?: return
        if (googleUnavailable()) return
        val session = googleSession.value ?: run {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再覆盖云端快照。"
            return
        }
        pendingCloudUploadRequest = null
        cloudConflict.value = null
        startGoogleUpload(session, request, overwrite = true)
    }

    fun dismissCloudConflict() {
        pendingCloudUploadRequest = null
        cloudConflict.value = null
        backupActivity.value = null
        operationState.value = UiState.Idle
    }

    fun downloadGooglePreview() {
        if (googleUnavailable()) return
        val session = googleSession.value
        if (session == null) {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再下载云端快照。"
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                title = "正在读取云端快照",
                detail = "正在获取 Google Drive 列表",
            )
            runCatching {
                googleDriveSyncRepository.listSnapshots(refreshGoogleSessionForOperation() ?: session)
            }.onSuccess { snapshots ->
                backupActivity.value = null
                if (snapshots.isEmpty()) {
                    operationState.value = UiState.Error(IllegalStateException("Google Drive 云端还没有同步快照"))
                    googleMessage.value = "Google Drive 云端还没有同步快照"
                } else {
                    cloudSnapshots.value = snapshots
                    cloudSnapshotPickerVisible.value = true
                    googleMessage.value = ""
                    operationState.value = UiState.Idle
                }
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                googleMessage.value = "云端列表读取失败：${error.message.orEmpty()}"
                recordGoogleDriveError(error)
            }
        }
    }

    fun downloadGoogleSnapshot(file: GoogleDriveFile) {
        if (googleUnavailable()) return
        val session = googleSession.value
        if (session == null) {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再下载云端快照。"
            return
        }
        viewModelScope.launch {
            cloudSnapshotPickerVisible.value = false
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                title = "正在下载云端快照",
                detail = file.name,
            )
            runCatching {
                googleDriveSyncRepository.download(
                    session = refreshGoogleSessionForOperation() ?: session,
                    file = file,
                )
            }.onSuccess { result ->
                backupActivity.value = null
                pendingCloudRestoreFile?.delete()
                pendingCloudRestoreFile = result.archiveFile
                pendingCloudRestoreRevision = result.file.revisionKey
                pendingCloudRestore.value = true
                pendingImportPreview.value = result.preview
                googleMessage.value = ""
                operationState.value = UiState.Success(result.preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                googleMessage.value = "云端下载失败：${error.message.orEmpty()}"
                recordGoogleDriveError(error)
            }
        }
    }

    fun dismissCloudSnapshotPicker() {
        cloudSnapshotPickerVisible.value = false
    }

    fun restoreGoogle(
        passphrase: String,
        scope: RestoreScope = RestoreScope.EVERYTHING,
        preserveConversations: Boolean = true,
        preserveGenMedia: Boolean = true,
    ) {
        val archiveFile = pendingCloudRestoreFile
        if (archiveFile == null) {
            operationState.value = UiState.Error(IllegalStateException("没有待恢复的云端快照"))
            return
        }
        // Blank means the simplified UI skipped passphrase entry, or the
        // archive was uploaded without one — either way, sub in the
        // documented fallback so the engine's non-blank require() holds.
        val resolvedPassphrase = passphrase.ifBlank { NO_PASSPHRASE_FALLBACK }
        operationState.value = UiState.Loading
        backupActivity.value = BackupActivity(
            title = "正在恢复云端备份",
            detail = "覆盖本机数据",
        )
        viewModelScope.launch {
            runCatching {
                googleDriveSyncRepository.restore(
                    archiveFile = archiveFile,
                    request = SyncRestoreRequest(
                        passphrase = resolvedPassphrase,
                        scope = scope,
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    ),
                )
            }.onSuccess { preview ->
                val manifest = preview.manifest
                pendingCloudRestoreFile?.delete()
                pendingCloudRestoreFile = null
                pendingCloudRestore.value = false
                pendingImportPreview.value = null
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            googleEnabled = true,
                            lastDownloadAt = System.currentTimeMillis(),
                            lastRemoteRevision = pendingCloudRestoreRevision,
                            lastError = "",
                            lastBackupVersionName = manifest.appVersionName,
                            lastBackupVersionCode = manifest.appVersionCode,
                            lastBackupDeviceLabel = manifest.deviceLabel,
                        )
                    )
                }
                pendingCloudRestoreRevision = ""
                googleMessage.value = ""
                backupActivity.value = null
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                googleMessage.value = "云端恢复失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    fun exportLocal(uri: Uri, mode: SyncMode, passphrase: String) {
        if (passphrase == NO_PASSPHRASE_FALLBACK) {
            operationState.value = UiState.Error(IllegalArgumentException("这个口令是内部保留值，请换一个口令"))
            return
        }
        val resolvedPassphrase = passphrase.ifBlank { NO_PASSPHRASE_FALLBACK }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                localBackupRepository.exportToUri(
                    uri = uri,
                    request = SyncExportRequest(mode = mode, passphrase = resolvedPassphrase)
                )
            }.onSuccess { preview ->
                val manifest = preview.manifest
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            mode = mode,
                            lastLocalExportAt = System.currentTimeMillis(),
                            lastError = "",
                            lastBackupVersionName = manifest.appVersionName,
                            lastBackupVersionCode = manifest.appVersionCode,
                            lastBackupDeviceLabel = manifest.deviceLabel,
                        )
                    )
                }
                localMessage.value = "已导出本地备份。"
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                operationState.value = UiState.Error(error)
                localMessage.value = "本地导出失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    fun inspectImport(uri: Uri) {
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                localBackupRepository.inspectUri(uri)
            }.onSuccess { preview ->
                pendingImportPreview.value = preview
                localMessage.value = "已读取本地备份，确认后可恢复。"
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                operationState.value = UiState.Error(error)
                localMessage.value = "本地导入失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    fun restoreLocal(
        uri: Uri,
        passphrase: String,
        scope: RestoreScope = RestoreScope.EVERYTHING,
        preserveConversations: Boolean = true,
        preserveGenMedia: Boolean = true,
    ) {
        val resolvedPassphrase = passphrase.ifBlank { NO_PASSPHRASE_FALLBACK }
        operationState.value = UiState.Loading
        backupActivity.value = BackupActivity(
            title = "正在恢复本地备份",
            detail = "覆盖本机数据",
        )
        viewModelScope.launch {
            runCatching {
                localBackupRepository.restoreFromUri(
                    uri = uri,
                    request = SyncRestoreRequest(
                        passphrase = resolvedPassphrase,
                        scope = scope,
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    )
                )
            }.onSuccess { preview ->
                val manifest = preview.manifest
                pendingImportPreview.value = null
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            lastDownloadAt = System.currentTimeMillis(),
                            lastError = "",
                            lastBackupVersionName = manifest.appVersionName,
                            lastBackupVersionCode = manifest.appVersionCode,
                            lastBackupDeviceLabel = manifest.deviceLabel,
                        )
                    )
                }
                localMessage.value = "已恢复本地备份，建议重启应用以确保所有数据生效。"
                backupActivity.value = null
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                localMessage.value = "本地恢复失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    fun clearOperationState() {
        operationState.value = UiState.Idle
    }

    fun clearPendingImport() {
        pendingImportPreview.value = null
        pendingCloudRestoreFile?.delete()
        pendingCloudRestoreFile = null
        pendingCloudRestore.value = false
        pendingCloudRestoreRevision = ""
        cloudSnapshotPickerVisible.value = false
    }

    private suspend fun restoreGoogleSessionIfPossible() {
        if (!googleConfigStatus.available) {
            googleMessage.value = googleConfigStatus.reason
            return
        }
        val syncSettings = settingsStore.settingsFlow.value.syncSettings
        if (!syncSettings.googleEnabled || syncSettings.googleAccountEmail.isBlank()) return
        if (googleAuthorizationInFlight || googleSession.value != null) return
        googleMessage.value = "正在恢复 Google 连接..."
        runCatching {
            googleDriveSyncRepository.restoreAuthorizedSession()
        }.onSuccess { outcome ->
            when (outcome) {
                is GoogleDriveAuthorizationOutcome.Authorized -> {
                    applyGoogleSession(outcome.session)
                }

                is GoogleDriveAuthorizationOutcome.ResolutionRequired -> {
                    googleMessage.value = "上次连接：${syncSettings.googleAccountEmail}，需要点一下重新确认授权。"
                }
            }
        }.onFailure { error ->
            googleMessage.value = "Google 连接恢复失败：${error.message.orEmpty()}"
            recordError(error)
        }
    }

    private suspend fun refreshGoogleSessionForOperation(): GoogleDriveAuthSession? {
        if (!googleConfigStatus.available) return null
        val syncSettings = settingsStore.settingsFlow.value.syncSettings
        if (!syncSettings.googleEnabled && googleSession.value == null) return null
        return runCatching {
            googleDriveSyncRepository.restoreAuthorizedSession()
        }.mapCatching { outcome ->
            when (outcome) {
                is GoogleDriveAuthorizationOutcome.Authorized -> {
                    applyGoogleSession(outcome.session)
                    outcome.session
                }

                is GoogleDriveAuthorizationOutcome.ResolutionRequired -> {
                    googleSession.value
                }
            }
        }.getOrElse {
            googleSession.value
        }
    }

    private suspend fun applyGoogleSession(session: GoogleDriveAuthSession) {
        googleSession.value = session
        googleMessage.value = "已连接：${session.label}"
        settingsStore.update { current ->
            current.copy(
                syncSettings = current.syncSettings.copy(
                    googleEnabled = true,
                    googleAccountEmail = session.accountEmail,
                    googleAccountId = session.accountId,
                    googleDisplayName = session.displayName,
                    lastError = "",
                )
            )
        }
    }

    private suspend fun recordError(error: Throwable) {
        settingsStore.update { current ->
            current.copy(
                syncSettings = current.syncSettings.copy(
                    lastError = error.message.orEmpty()
                )
            )
        }
    }

    private suspend fun recordGoogleDriveError(error: Throwable) {
        if (error is GoogleDriveAuthRequiredException) {
            runCatching {
                googleDriveSyncRepository.clearCachedToken(error.accessToken)
            }
            googleSession.value = null
            googleMessage.value = "Google 授权已过期，请点 Google 账号刷新连接。"
            settingsStore.update { current ->
                current.copy(
                    syncSettings = current.syncSettings.copy(
                        lastError = error.message.orEmpty()
                    )
                )
            }
            return
        }
        recordError(error)
    }

    private fun googleUnavailable(): Boolean {
        if (googleConfigStatus.available) return false
        val error = IllegalStateException(googleConfigStatus.reason)
        googleMessage.value = googleConfigStatus.reason
        operationState.value = UiState.Error(error)
        return true
    }
}

data class GoogleCloudConflict(
    val remoteFile: GoogleDriveFile,
    val localRevision: String,
)

data class BackupActivity(
    val title: String,
    val detail: String = "",
    val progress: Float? = null,
)
