package me.rerere.rikkahub.ui.pages.backup

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
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.core.SyncExportRequest
import me.rerere.rikkahub.data.sync.core.SyncMode
import me.rerere.rikkahub.data.sync.core.SyncPreview
import me.rerere.rikkahub.data.sync.core.SyncRestoreRequest
import me.rerere.rikkahub.data.sync.google.GoogleDriveAuthSession
import me.rerere.rikkahub.data.sync.google.GoogleDriveAuthRequiredException
import me.rerere.rikkahub.data.sync.google.GoogleDriveAuthorizationOutcome
import me.rerere.rikkahub.data.sync.google.GoogleDriveFile
import me.rerere.rikkahub.data.sync.google.GoogleDriveSyncRepository
import me.rerere.rikkahub.data.sync.google.GoogleOAuthConfigGate
import me.rerere.rikkahub.data.sync.google.GoogleOAuthConfigStatus
import me.rerere.rikkahub.data.sync.local.LocalBackupRepository
import me.rerere.rikkahub.utils.UiState
import java.io.File
import kotlin.uuid.Uuid

class BackupVM(
    private val settingsStore: SettingsStore,
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
    val pendingGoogleAuthorization = MutableStateFlow<PendingIntent?>(null)
    val pendingCloudRestore = MutableStateFlow(false)
    val cloudConflict = MutableStateFlow<GoogleCloudConflict?>(null)
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
        val session = googleSession.value
        if (session == null) {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再上传云端快照。"
            return
        }
        val request = SyncExportRequest(mode = mode, passphrase = passphrase)
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                val activeSession = refreshGoogleSessionForOperation() ?: session
                val remote = googleDriveSyncRepository.findLatest(activeSession)
                val localRevision = settings.value.syncSettings.lastRemoteRevision
                if (!overwrite && remote != null && remote.revisionKey != localRevision) {
                    pendingCloudUploadRequest = request
                    cloudConflict.value = GoogleCloudConflict(
                        remoteFile = remote,
                        localRevision = localRevision,
                    )
                    operationState.value = UiState.Idle
                    return@launch
                }
                googleDriveSyncRepository.upload(
                    session = activeSession,
                    request = request,
                    existingFileId = remote?.id,
                    expectedRemoteRevision = remote?.revisionKey,
                )
            }.onSuccess { result ->
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            googleEnabled = true,
                            googleAccountEmail = googleSession.value?.accountEmail ?: session.accountEmail,
                            googleAccountId = googleSession.value?.accountId ?: session.accountId,
                            googleDisplayName = googleSession.value?.displayName ?: session.displayName,
                            mode = mode,
                            lastUploadAt = System.currentTimeMillis(),
                            lastRemoteRevision = result.file.revisionKey,
                            lastError = "",
                        )
                    )
                }
                googleMessage.value = "已上传到 Google Drive：${session.label}"
                operationState.value = UiState.Success(result.preview)
            }.onFailure { error ->
                operationState.value = UiState.Error(error)
                googleMessage.value = "云端上传失败：${error.message.orEmpty()}"
                recordGoogleDriveError(error)
            }
        }
    }

    fun confirmOverwriteCloud() {
        val request = pendingCloudUploadRequest ?: return
        pendingCloudUploadRequest = null
        cloudConflict.value = null
        uploadGoogle(request.mode, request.passphrase, overwrite = true)
    }

    fun dismissCloudConflict() {
        pendingCloudUploadRequest = null
        cloudConflict.value = null
        operationState.value = UiState.Idle
    }

    fun downloadGooglePreview() {
        val session = googleSession.value
        if (session == null) {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再下载云端快照。"
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                googleDriveSyncRepository.downloadLatest(refreshGoogleSessionForOperation() ?: session)
            }.onSuccess { result ->
                pendingCloudRestoreFile?.delete()
                pendingCloudRestoreFile = result.archiveFile
                pendingCloudRestoreRevision = result.file.revisionKey
                pendingCloudRestore.value = true
                pendingImportPreview.value = result.preview
                googleMessage.value = "已读取云端快照，确认后可恢复。"
                operationState.value = UiState.Success(result.preview)
            }.onFailure { error ->
                operationState.value = UiState.Error(error)
                googleMessage.value = "云端下载失败：${error.message.orEmpty()}"
                recordGoogleDriveError(error)
            }
        }
    }

    fun restoreGoogle(passphrase: String) {
        val archiveFile = pendingCloudRestoreFile
        if (archiveFile == null) {
            operationState.value = UiState.Error(IllegalStateException("没有待恢复的云端快照"))
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                googleDriveSyncRepository.restore(
                    archiveFile = archiveFile,
                    request = SyncRestoreRequest(passphrase = passphrase),
                )
            }.onSuccess { preview ->
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
                        )
                    )
                }
                pendingCloudRestoreRevision = ""
                googleMessage.value = "已从 Google Drive 恢复快照。"
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                operationState.value = UiState.Error(error)
                googleMessage.value = "云端恢复失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    fun exportLocal(uri: Uri, mode: SyncMode, passphrase: String) {
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                localBackupRepository.exportToUri(
                    uri = uri,
                    request = SyncExportRequest(mode = mode, passphrase = passphrase)
                )
            }.onSuccess { preview ->
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            mode = mode,
                            lastLocalExportAt = System.currentTimeMillis(),
                            lastError = "",
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

    fun restoreLocal(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                localBackupRepository.restoreFromUri(
                    uri = uri,
                    request = SyncRestoreRequest(passphrase = passphrase)
                )
            }.onSuccess { preview ->
                pendingImportPreview.value = null
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            lastDownloadAt = System.currentTimeMillis(),
                            lastError = "",
                        )
                    )
                }
                localMessage.value = "已恢复本地备份。"
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
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
    }

    private suspend fun restoreGoogleSessionIfPossible() {
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
}

data class GoogleCloudConflict(
    val remoteFile: GoogleDriveFile,
    val localRevision: String,
)
