package app.amber.feature.ui.pages.backup

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.RestoreScope
import app.amber.core.sync.core.SYNC_ARCHIVE_EXTENSION
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.core.SyncRestoreVerification
import app.amber.core.sync.google.GoogleDriveAuthSession
import app.amber.core.sync.google.GoogleDriveAuthRequiredException
import app.amber.core.sync.google.GoogleDriveAuthorizationOutcome
import app.amber.core.sync.google.GoogleDriveFile
import app.amber.core.sync.google.GoogleDriveSyncRepository
import app.amber.core.sync.google.GoogleOAuthConfigGate
import app.amber.core.sync.google.GoogleOAuthConfigStatus
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.sync.provider.GoogleDriveSyncProvider
import app.amber.core.sync.provider.LocalFolderSyncProvider
import app.amber.core.sync.provider.PersistedFolderStore
import app.amber.core.sync.provider.SyncProviderUploadRequest
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.SyncSnapshotManifest
import app.amber.core.sync.provider.UploadConflictPolicy
import app.amber.core.sync.provider.WebDavSyncProvider
import app.amber.core.sync.provider.toArchiveManifest
import app.amber.core.utils.UiState
import java.io.File
import kotlin.uuid.Uuid

class BackupVM(
    private val settingsStore: SettingsAggregator,
    private val localBackupRepository: LocalBackupRepository,
    private val googleDriveSyncRepository: GoogleDriveSyncRepository,
    googleOAuthConfigGate: GoogleOAuthConfigGate,
    private val archiveManager: SyncArchiveManager,
    private val webDavSyncProvider: WebDavSyncProvider,
    private val localFolderSyncProvider: LocalFolderSyncProvider,
    private val persistedFolderStore: PersistedFolderStore,
    private val capabilityFlags: CapabilityFlags,
) : ViewModel() {
    companion object {
        private const val TAG = "BackupVM"
    }

    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    // P7-01：sync_provider_v2 开关 —— off 时现有 Google Drive / 本地导入导出行为完全不变。
    val providerV2Enabled: StateFlow<Boolean> = capabilityFlags.flow
        .map { Capability.SyncProviderV2 in it.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    // P7-01 provider 状态（WebDAV / 本地文件夹）。
    val webDavSnapshots = MutableStateFlow<List<SyncSnapshot>>(emptyList())
    val webDavMessage = MutableStateFlow("")
    val localFolderSnapshots = MutableStateFlow<List<SyncSnapshot>>(emptyList())
    val folderMessage = MutableStateFlow("")
    val folderInfo = MutableStateFlow<PersistedFolderStore.Folder?>(null)
    val pendingDeleteConfirm = MutableStateFlow<SyncSnapshot?>(null)
    val pendingUploadConflict = MutableStateFlow<UploadConflictChoice?>(null)
    /** 已下载待恢复的快照来源 providerId（null = 非 provider 路径）。 */
    val pendingProviderRestore = MutableStateFlow<String?>(null)

    // Build-time Google services config is static for this process; keep the
    // status as a value so Compose does not imply live revalidation support.
    val googleConfigStatus: GoogleOAuthConfigStatus = googleOAuthConfigGate.status()

    // P7-02：导出加密方式选择（口令加密 / 设备绑定加密）与两阶段恢复。
    val pendingExportDialog = MutableStateFlow<ExportRequestSeed?>(null)
    val pendingVerifiedRestore = MutableStateFlow<SyncRestoreVerification?>(null)
    /** 恢复源标记：provider / google / local，决定 verify 走哪个文件。 */
    val pendingRestoreSource = MutableStateFlow<RestoreSource?>(null)
    /** verify 成功后确认写入时使用的请求参数（scope + preserve toggles）。 */
    private var pendingRestoreRequest: SyncRestoreRequest? = null

    private var pendingCloudRestoreFile: File? = null
    private var pendingCloudRestoreRevision: String = ""
    private var pendingCloudUploadRequest: SyncExportRequest? = null
    private var pendingLocalRestoreUri: Uri? = null
    private var googleAuthorizationInFlight = false
    /** P7-02：授权完成后自动继续的 Google 上传（口令与加密方式保留到会话就绪）。 */
    private var pendingGoogleUpload: PendingGoogleUpload? = null

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
            folderInfo.value = persistedFolderStore.read()
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

    /**
     * P7-02：导出前先选择加密方式（口令加密或设备绑定加密），UI 确认后
     * 通过 [confirmExport] 分发。
     */
    fun requestExport(source: ExportSource, conflictPolicy: UploadConflictPolicy? = null) {
        if (source == ExportSource.Google && googleUnavailable()) return
        pendingExportDialog.value = ExportRequestSeed(source, conflictPolicy)
    }

    fun requestExport(seed: ExportRequestSeed) {
        if (seed.source == ExportSource.Google && googleUnavailable()) return
        pendingExportDialog.value = seed
    }

    fun dismissExportDialog() {
        pendingExportDialog.value = null
    }

    fun confirmExport(passphrase: String, encryptionMode: SyncEncryptionMode) {
        val seed = pendingExportDialog.value ?: return
        pendingExportDialog.value = null
        when (seed.source) {
            ExportSource.Google -> {
                val session = googleSession.value
                if (session == null) {
                    // 授权成功后由 applyGoogleSession 自动继续上传。
                    pendingGoogleUpload = PendingGoogleUpload(passphrase, encryptionMode)
                    connectGoogle()
                    googleMessage.value = "请先完成 Google Drive 授权，再上传云端快照。"
                    return
                }
                uploadGoogle(SyncMode.FULL, passphrase, encryptionMode = encryptionMode)
            }

            ExportSource.WebDav -> uploadWebDav(seed.conflictPolicy, passphrase, encryptionMode)
            ExportSource.LocalFolder -> uploadLocalFolder(seed.conflictPolicy, passphrase, encryptionMode)
            ExportSource.LocalFile -> {
                val uri = seed.uri
                if (uri != null) {
                    exportLocal(uri, SyncMode.FULL, passphrase, encryptionMode)
                }
            }
        }
    }

    fun uploadGoogle(
        mode: SyncMode,
        passphrase: String,
        overwrite: Boolean = false,
        encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
    ) {
        if (googleUnavailable()) return
        val session = googleSession.value
        if (session == null) {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再上传云端快照。"
            return
        }
        // P7-02：口令模式必须输入自定义口令；设备绑定模式由引擎使用本机密钥。
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        val request = SyncExportRequest(mode = mode, passphrase = passphrase, encryptionMode = encryptionMode)
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

    /**
     * P7-02 恢复第一步（Google 源）：验证头部 + 认证标签并解密，**不写入**。
     * 成功后 [pendingVerifiedRestore] 持有验证结果，UI 展示解密后的恢复
     * preview，用户确认后调用 [applyVerifiedRestore]。
     */
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
        runRestoreVerify(passphrase, scope, preserveConversations, preserveGenMedia) { request ->
            archiveManager.verifyArchive(archiveFile, request)
        }
    }

    fun exportLocal(uri: Uri, mode: SyncMode, passphrase: String, encryptionMode: SyncEncryptionMode) {
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            localMessage.value = ""
            backupActivity.value = BackupActivity(
                title = "正在导出本地备份",
                detail = "正在生成加密备份文件",
            )
            runCatching {
                localBackupRepository.exportToUri(
                    uri = uri,
                    request = SyncExportRequest(mode = mode, passphrase = passphrase, encryptionMode = encryptionMode)
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
                backupActivity.value = null
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                localMessage.value = "本地导出失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    fun inspectImport(uri: Uri) {
        pendingLocalRestoreUri = uri
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                title = "正在读取本地备份",
                detail = "正在解析备份文件",
            )
            runCatching {
                localBackupRepository.inspectUri(uri)
            }.onSuccess { preview ->
                backupActivity.value = null
                pendingImportPreview.value = preview
                localMessage.value = "已读取本地备份，确认后可恢复。"
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                pendingLocalRestoreUri = null
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                localMessage.value = "本地导入失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    /**
     * P7-02 恢复第一步（本地文件源）：验证头部 + 认证标签并解密，**不写入**。
     */
    fun restorePendingLocal(
        passphrase: String,
        scope: RestoreScope = RestoreScope.EVERYTHING,
        preserveConversations: Boolean = true,
        preserveGenMedia: Boolean = true,
    ) {
        val uri = pendingLocalRestoreUri
        if (uri == null) {
            val error = IllegalStateException("没有待恢复的本地备份")
            backupActivity.value = null
            operationState.value = UiState.Error(error)
            localMessage.value = error.message.orEmpty()
            return
        }
        runRestoreVerify(passphrase, scope, preserveConversations, preserveGenMedia) { request ->
            localBackupRepository.verifyUri(uri, request)
        }
    }

    /**
     * P7-02 恢复第二步：解密已验证通过、UI 展示恢复 preview 并确认后写入。
     * 失败可重试（验证结果仍在，直接重试 apply；或重新验证）。
     */
    fun applyVerifiedRestore(
        scope: RestoreScope = RestoreScope.EVERYTHING,
        preserveConversations: Boolean = true,
        preserveGenMedia: Boolean = true,
    ) {
        val verification = pendingVerifiedRestore.value
        if (verification == null) {
            operationState.value = UiState.Error(IllegalStateException("没有已验证的备份，请重新选择备份文件"))
            return
        }
        operationState.value = UiState.Loading
        backupActivity.value = BackupActivity(
            title = "正在恢复备份",
            detail = "覆盖本机数据",
        )
        viewModelScope.launch {
            runCatching {
                archiveManager.applyRestore(
                    verification,
                    SyncRestoreRequest(
                        passphrase = "",
                        scope = scope,
                        preserveConversations = preserveConversations,
                        preserveGenMedia = preserveGenMedia,
                    ),
                )
            }.onSuccess { preview ->
                val manifest = preview.manifest
                onRestoreApplied(manifest, verification.archiveFile)
                backupActivity.value = null
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                localMessage.value = "恢复失败：${error.message.orEmpty()}"
                googleMessage.value = "恢复失败：${error.message.orEmpty()}"
                webDavMessage.value = "恢复失败：${error.message.orEmpty()}"
                folderMessage.value = "恢复失败：${error.message.orEmpty()}"
                recordError(error)
            }
        }
    }

    /** 恢复写入成功后统一收尾（清理待恢复文件 + 更新 lastDownloadAt 等）。 */
    private suspend fun onRestoreApplied(manifest: app.amber.core.sync.core.SyncManifest, archiveFile: File) {
        pendingImportPreview.value = null
        pendingVerifiedRestore.value = null
        pendingRestoreRequest = null
        archiveFile.delete()
        pendingCloudRestoreFile?.takeIf { it != archiveFile }?.delete()
        pendingCloudRestoreFile = null
        pendingCloudRestore.value = false
        pendingProviderRestore.value = null
        pendingLocalRestoreUri = null
        val wasGoogle = pendingRestoreSource.value == RestoreSource.Google
        pendingRestoreSource.value = null
        settingsStore.update { current ->
            current.copy(
                syncSettings = current.syncSettings.copy(
                    googleEnabled = wasGoogle || current.syncSettings.googleEnabled,
                    lastDownloadAt = System.currentTimeMillis(),
                    lastRemoteRevision = if (wasGoogle) pendingCloudRestoreRevision else current.syncSettings.lastRemoteRevision,
                    lastError = "",
                    lastBackupVersionName = manifest.appVersionName,
                    lastBackupVersionCode = manifest.appVersionCode,
                    lastBackupDeviceLabel = manifest.deviceLabel,
                )
            )
        }
        pendingCloudRestoreRevision = ""
        localMessage.value = "已恢复备份，建议重启应用以确保所有数据生效。"
    }

    /** P7-02：取消恢复 —— 解密后未写入，不残留任何临时文件。 */
    fun dismissVerifiedRestore() {
        pendingVerifiedRestore.value?.let { verification ->
            archiveManager.discardVerification(verification)
            val archiveFile = verification.archiveFile
            // 只清理 App 自己创建的 cacheDir 临时副本：本地导入拷贝（可经
            // LocalBackupRepository 识别）与 provider/Google 下载副本
            // （pendingCloudRestoreFile 只会指向 App 下载缓存）——用户原始
            // 选择的文件绝不删除。
            if (localBackupRepository.isOwnedTempCopy(archiveFile) || archiveFile == pendingCloudRestoreFile) {
                archiveFile.delete()
            }
            pendingCloudRestoreFile?.takeIf { it != archiveFile }?.delete()
            pendingCloudRestoreFile = null
        }
        pendingVerifiedRestore.value = null
        pendingRestoreRequest = null
    }

    private fun runRestoreVerify(
        passphrase: String,
        scope: RestoreScope,
        preserveConversations: Boolean,
        preserveGenMedia: Boolean,
        block: suspend (SyncRestoreRequest) -> SyncRestoreVerification,
    ) {
        operationState.value = UiState.Loading
        backupActivity.value = BackupActivity(
            title = "正在验证备份",
            detail = "正在校验口令与加密头",
        )
        pendingRestoreSource.value = when {
            pendingProviderRestore.value != null -> RestoreSource.Provider
            pendingCloudRestore.value -> RestoreSource.Google
            else -> RestoreSource.Local
        }
        pendingRestoreRequest = SyncRestoreRequest(
            passphrase = passphrase,
            scope = scope,
            preserveConversations = preserveConversations,
            preserveGenMedia = preserveGenMedia,
        )
        viewModelScope.launch {
            runCatching { block(pendingRestoreRequest!!) }
                .onSuccess { verification ->
                    backupActivity.value = null
                    pendingVerifiedRestore.value = verification
                    pendingImportPreview.value = null
                    operationState.value = UiState.Success(verification.preview)
                }
                .onFailure { error ->
                    backupActivity.value = null
                    pendingRestoreSource.value = null
                    pendingRestoreRequest = null
                    operationState.value = UiState.Error(error)
                    localMessage.value = "恢复验证失败：${error.message.orEmpty()}"
                    googleMessage.value = "恢复验证失败：${error.message.orEmpty()}"
                    webDavMessage.value = "恢复验证失败：${error.message.orEmpty()}"
                    folderMessage.value = "恢复验证失败：${error.message.orEmpty()}"
                    recordError(error)
                }
        }
    }

    // ---------------- P7-01：WebDAV / 本地文件夹 provider ----------------

    fun saveWebDavConfig(url: String, username: String, password: String, path: String) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    webDavConfig = current.webDavConfig.copy(
                        url = url.trim(),
                        username = username,
                        password = password,
                        path = path.trim().ifBlank { "amber_agent_backups" },
                    )
                )
            }
            webDavMessage.value = "已保存 WebDAV 配置（凭据加密存储）。"
        }
    }

    fun refreshWebDavSnapshots() {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { webDavSyncProvider.listSnapshots() }
                .onSuccess { snapshots ->
                    webDavSnapshots.value = snapshots
                    webDavMessage.value = if (snapshots.isEmpty()) "WebDAV 上没有快照" else "共 ${snapshots.size} 个快照"
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    webDavMessage.value = "WebDAV 列表读取失败：${error.message.orEmpty()}"
                }
        }
    }

    fun uploadWebDav(
        policy: UploadConflictPolicy?,
        passphrase: String = "",
        encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
    ) {
        if (!providerV2Enabled.value) return
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        val resolved = resolveUploadPolicy(policy, webDavSnapshots.value)
        if (resolved == null) {
            webDavSnapshots.value.firstOrNull { it.manifest.deviceId == currentDeviceId() }?.let { snapshot ->
                pendingUploadConflict.value = UploadConflictChoice(snapshot, webDavSyncProvider.id)
            }
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(title = "正在上传 WebDAV 快照", detail = "正在生成加密备份文件")
            runCatching {
                webDavSyncProvider.uploadSnapshot(
                    SyncProviderUploadRequest(
                        mode = SyncMode.FULL,
                        passphrase = passphrase,
                        encryptionMode = encryptionMode,
                        conflictPolicy = resolved,
                    )
                )
            }.onSuccess { snapshot ->
                backupActivity.value = null
                webDavSnapshots.value = listOf(snapshot) + webDavSnapshots.value
                webDavMessage.value = "已上传快照 ${snapshot.name}"
                operationState.value = UiState.Success(pendingImportPreview.value ?: SyncPreview(
                    manifest = snapshot.manifest.toArchiveManifest(),
                    fileName = snapshot.name,
                    sizeBytes = snapshot.sizeBytes,
                ))
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                webDavMessage.value = "WebDAV 上传失败：${error.message.orEmpty()}"
            }
        }
    }

    fun downloadWebDavSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            cloudSnapshotPickerVisible.value = false
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(title = "正在下载 WebDAV 快照", detail = snapshot.name)
            runCatching { webDavSyncProvider.downloadSnapshot(snapshot.snapshotId) }
                .onSuccess { file ->
                    backupActivity.value = null
                    pendingCloudRestoreFile?.delete()
                    pendingCloudRestoreFile = file
                    pendingCloudRestore.value = true
                    pendingProviderRestore.value = webDavSyncProvider.id
                    pendingImportPreview.value = archiveManager.inspectArchive(file, snapshot.name)
                    operationState.value = UiState.Success(pendingImportPreview.value!!)
                }
                .onFailure { error ->
                    backupActivity.value = null
                    operationState.value = UiState.Error(error)
                    webDavMessage.value = "WebDAV 下载失败：${error.message.orEmpty()}"
                }
        }
    }

    fun deleteWebDavSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { webDavSyncProvider.deleteSnapshot(snapshot.snapshotId) }
                .onSuccess {
                    pendingDeleteConfirm.value = null
                    webDavSnapshots.value = webDavSnapshots.value.filterNot { it.snapshotId == snapshot.snapshotId }
                    webDavMessage.value = "已删除远端快照"
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    webDavMessage.value = "删除失败：${error.message.orEmpty()}"
                }
        }
    }

    fun deleteGoogleSnapshot(file: GoogleDriveFile) {
        if (googleUnavailable()) return
        val session = googleSession.value
        if (session == null) {
            connectGoogle()
            googleMessage.value = "请先完成 Google Drive 授权，再删除云端快照。"
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                GoogleDriveSyncProvider(googleDriveSyncRepository, session).deleteSnapshot(file.id)
            }.onSuccess {
                pendingDeleteConfirm.value = null
                cloudSnapshots.value = cloudSnapshots.value.filterNot { it.id == file.id }
                googleMessage.value = "已删除云端快照"
                operationState.value = UiState.Idle
            }.onFailure { error ->
                operationState.value = UiState.Error(error)
                googleMessage.value = "删除失败：${error.message.orEmpty()}"
                recordGoogleDriveError(error)
            }
        }
    }

    /** 请求删除 Google Drive 快照（复用统一的二次确认对话框）。 */
    fun requestDeleteGoogle(file: GoogleDriveFile) {
        pendingDeleteConfirm.value = SyncSnapshot(
            providerId = "google_drive",
            snapshotId = file.id,
            name = file.name,
            manifest = SyncSnapshotManifest(
                snapshotId = file.id,
                createdAt = file.backupCreatedAt ?: 0L,
                appVersionName = file.backupVersionName,
                appVersionCode = file.backupVersionCode ?: 0L,
                deviceLabel = file.backupDeviceLabel,
                sizeBytes = file.size?.toLongOrNull() ?: 0L,
            ),
        )
    }

    fun saveLocalFolder(uri: Uri, displayName: String) {
        if (!providerV2Enabled.value) return
        persistedFolderStore.save(uri.toString(), displayName)
        folderInfo.value = persistedFolderStore.read()
        folderMessage.value = "已选择文件夹：${displayName.ifBlank { uri.toString() }}"
        refreshLocalFolderSnapshots()
    }

    fun refreshLocalFolderSnapshots() {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { localFolderSyncProvider.listSnapshots() }
                .onSuccess { snapshots ->
                    localFolderSnapshots.value = snapshots
                    folderMessage.value = if (snapshots.isEmpty()) "文件夹里没有快照" else "共 ${snapshots.size} 个快照"
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    folderMessage.value = "快照列表读取失败：${error.message.orEmpty()}"
                }
        }
    }

    fun uploadLocalFolder(
        policy: UploadConflictPolicy?,
        passphrase: String = "",
        encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
    ) {
        if (!providerV2Enabled.value) return
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        val resolved = resolveUploadPolicy(policy, localFolderSnapshots.value)
        if (resolved == null) {
            localFolderSnapshots.value.firstOrNull { it.manifest.deviceId == currentDeviceId() }?.let { snapshot ->
                pendingUploadConflict.value = UploadConflictChoice(snapshot, localFolderSyncProvider.id)
            }
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(title = "正在上传本地文件夹快照", detail = "正在生成加密备份文件")
            runCatching {
                localFolderSyncProvider.uploadSnapshot(
                    SyncProviderUploadRequest(
                        mode = SyncMode.FULL,
                        passphrase = passphrase,
                        encryptionMode = encryptionMode,
                        conflictPolicy = resolved,
                    )
                )
            }.onSuccess { snapshot ->
                backupActivity.value = null
                localFolderSnapshots.value = listOf(snapshot) + localFolderSnapshots.value
                folderMessage.value = "已上传快照 ${snapshot.name}"
                operationState.value = UiState.Success(SyncPreview(
                    manifest = snapshot.manifest.toArchiveManifest(),
                    fileName = snapshot.name,
                    sizeBytes = snapshot.sizeBytes,
                ))
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                folderMessage.value = "上传失败：${error.message.orEmpty()}"
            }
        }
    }

    fun downloadLocalFolderSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(title = "正在下载本地文件夹快照", detail = snapshot.name)
            runCatching { localFolderSyncProvider.downloadSnapshot(snapshot.snapshotId) }
                .onSuccess { file ->
                    backupActivity.value = null
                    pendingCloudRestoreFile?.delete()
                    pendingCloudRestoreFile = file
                    pendingCloudRestore.value = true
                    pendingProviderRestore.value = localFolderSyncProvider.id
                    pendingImportPreview.value = archiveManager.inspectArchive(file, snapshot.name)
                    operationState.value = UiState.Success(pendingImportPreview.value!!)
                }
                .onFailure { error ->
                    backupActivity.value = null
                    operationState.value = UiState.Error(error)
                    folderMessage.value = "下载失败：${error.message.orEmpty()}"
                }
        }
    }

    fun deleteLocalFolderSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { localFolderSyncProvider.deleteSnapshot(snapshot.snapshotId) }
                .onSuccess {
                    pendingDeleteConfirm.value = null
                    localFolderSnapshots.value =
                        localFolderSnapshots.value.filterNot { it.snapshotId == snapshot.snapshotId }
                    folderMessage.value = "已删除文件夹快照"
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    folderMessage.value = "删除失败：${error.message.orEmpty()}"
                }
        }
    }

    fun requestDelete(snapshot: SyncSnapshot) {
        pendingDeleteConfirm.value = snapshot
    }

    fun confirmPendingDelete() {
        val snapshot = pendingDeleteConfirm.value ?: return
        when (snapshot.providerId) {
            webDavSyncProvider.id -> deleteWebDavSnapshot(snapshot)
            localFolderSyncProvider.id -> deleteLocalFolderSnapshot(snapshot)
            else -> {
                // Google Drive 的快照走 file 路径（snapshotId == file.id）。
                cloudSnapshots.value.firstOrNull { it.id == snapshot.snapshotId }?.let { file ->
                    deleteGoogleSnapshot(file)
                }
            }
        }
    }

    fun dismissPendingDelete() {
        pendingDeleteConfirm.value = null
    }

    fun requestUploadConflict(snapshot: SyncSnapshot) {
        pendingUploadConflict.value = UploadConflictChoice(
            snapshot = snapshot,
            providerId = snapshot.providerId,
        )
    }

    fun resolveUploadConflict(policy: UploadConflictPolicy) {
        val choice = pendingUploadConflict.value ?: return
        pendingUploadConflict.value = null
        when (choice.providerId) {
            webDavSyncProvider.id -> uploadWebDav(policy)
            localFolderSyncProvider.id -> uploadLocalFolder(policy)
        }
    }

    fun dismissUploadConflict() {
        pendingUploadConflict.value = null
    }

    /**
     * P7-02 恢复第一步（provider 源）：验证头部 + 认证标签并解密，**不写入**。
     */
    fun restorePendingProvider(
        passphrase: String,
        scope: RestoreScope = RestoreScope.EVERYTHING,
        preserveConversations: Boolean = true,
        preserveGenMedia: Boolean = true,
    ) {
        val archiveFile = pendingCloudRestoreFile
        if (archiveFile == null) {
            operationState.value = UiState.Error(IllegalStateException("没有待恢复的快照"))
            return
        }
        runRestoreVerify(passphrase, scope, preserveConversations, preserveGenMedia) { request ->
            archiveManager.verifyArchive(archiveFile, request)
        }
    }

    /**
     * 上传冲突策略解析：显式策略直接返回；null 时若远端已有本机同 deviceId
     * 快照则返回 null（调用方弹选择对话框），否则默认创建副本。
     */
    private fun resolveUploadPolicy(
        policy: UploadConflictPolicy?,
        existing: List<SyncSnapshot>,
    ): UploadConflictPolicy? {
        if (policy != null) return policy
        val hasSameDevice = existing.any {
            it.manifest.deviceId.isNotBlank() && it.manifest.deviceId == currentDeviceId()
        }
        return if (hasSameDevice) null else UploadConflictPolicy.CREATE_COPY
    }

    private fun currentDeviceId(): String = settingsStore.settingsFlow.value.syncSettings.deviceId

    fun clearOperationState() {
        operationState.value = UiState.Idle
    }

    fun clearPendingImport() {
        dismissVerifiedRestore()
        pendingImportPreview.value = null
        pendingCloudRestoreFile?.delete()
        pendingCloudRestoreFile = null
        pendingCloudRestore.value = false
        pendingCloudRestoreRevision = ""
        pendingLocalRestoreUri = null
        pendingProviderRestore.value = null
        pendingRestoreSource.value = null
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
        // P7-02：授权完成前已选好的导出（口令 + 加密方式）在此自动继续。
        pendingGoogleUpload?.let { pending ->
            pendingGoogleUpload = null
            uploadGoogle(SyncMode.FULL, pending.passphrase, encryptionMode = pending.encryptionMode)
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

/** P7-01：上传冲突选择（覆盖本机旧快照 / 创建副本）。 */
data class UploadConflictChoice(
    val snapshot: SyncSnapshot,
    val providerId: String,
)

data class BackupActivity(
    val title: String,
    val detail: String = "",
    val progress: Float? = null,
)

/**
 * P7-02：导出加密方式选择弹窗的请求种子。
 * - [ExportSource.LocalFile] 携带已选保存 URI（文档选择器回调后弹窗）。
 * - provider 上传携带冲突策略（弹窗确认后解析）。
 */
data class ExportRequestSeed(
    val source: ExportSource,
    val conflictPolicy: UploadConflictPolicy? = null,
    val uri: android.net.Uri? = null,
) {
    companion object {
        fun localFile(uri: android.net.Uri) = ExportRequestSeed(ExportSource.LocalFile, uri = uri)
    }
}

enum class ExportSource {
    Google,
    WebDav,
    LocalFolder,
    LocalFile,
}

/** 待恢复快照的来源，决定验证阶段读取哪个文件/URI。 */
enum class RestoreSource {
    Provider,
    Google,
    Local,
}

private data class PendingGoogleUpload(
    val passphrase: String,
    val encryptionMode: SyncEncryptionMode,
)

/** P7-02：恢复是否需要用户输入口令（v2 口令模式与 v1 受口令保护的旧格式需要）。 */
fun restoreNeedsPassphrase(preview: SyncPreview): Boolean = when {
    preview.legacyFormat && !preview.manifest.passphraseProtected -> false
    preview.manifest.encryptionMode == SyncEncryptionMode.DEVICE_BOUND -> false
    else -> true
}
