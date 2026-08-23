package app.amber.core.sync.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncManifest
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.core.SYNC_ARCHIVE_EXTENSION
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GoogleDriveSyncRepository(
    private val context: Context,
    private val driveClient: GoogleDriveAppDataClient,
    private val archiveManager: SyncArchiveManager,
) {
    private val authorizationClient = Identity.getAuthorizationClient(context)

    suspend fun authorizeDrive(): GoogleDriveAuthorizationOutcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        val result = runCatching {
            authorizationClient.authorize(request).awaitTask()
        }.getOrElse { error ->
            throw error.toDriveAuthorizationException()
        }
        return result.toAuthorizationOutcome()
    }

    suspend fun restoreAuthorizedSession(): GoogleDriveAuthorizationOutcome = authorizeDrive()

    fun completeAuthorization(intent: Intent?): GoogleDriveAuthSession {
        requireNotNull(intent) { "Google 授权结果为空" }
        val result = runCatching {
            authorizationClient.getAuthorizationResultFromIntent(intent)
        }.getOrElse { error ->
            throw error.toDriveAuthorizationException()
        }
        return result.toSession()
    }

    suspend fun findLatest(session: GoogleDriveAuthSession): GoogleDriveFile? = withContext(Dispatchers.IO) {
        driveClient.findLatest(session.accessToken)
    }

    suspend fun listSnapshots(session: GoogleDriveAuthSession): List<GoogleDriveFile> = withContext(Dispatchers.IO) {
        driveClient.listSnapshots(session.accessToken, pageSize = CLOUD_SNAPSHOT_LIST_PAGE_SIZE)
    }

    suspend fun upload(
        session: GoogleDriveAuthSession,
        request: SyncExportRequest,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): GoogleDriveUploadResult = withContext(Dispatchers.IO) {
        val archiveFile = archiveManager.createArchiveFile(request)
        try {
            val preview = archiveManager.inspectArchive(archiveFile)
            val file = driveClient.upload(
                accessToken = session.accessToken,
                archiveFile = archiveFile,
                fileName = snapshotFileName(preview.manifest),
                appProperties = preview.manifest.toDriveAppProperties(),
                existingFileId = null,
                onProgress = onProgress,
            )
            pruneOldSnapshots(session.accessToken)
            GoogleDriveUploadResult(
                file = file,
                preview = preview.copy(fileName = file.name),
            )
        } finally {
            archiveFile.delete()
        }
    }

    suspend fun downloadLatest(session: GoogleDriveAuthSession): GoogleDriveDownloadResult = withContext(Dispatchers.IO) {
        val file = driveClient.findLatest(session.accessToken)
            ?: error("Google Drive 云端还没有同步快照")
        val archiveFile = createTempArchiveFile("google-download")
        try {
            driveClient.downloadToFile(session.accessToken, file.id, archiveFile)
            GoogleDriveDownloadResult(
                file = file,
                archiveFile = archiveFile,
                preview = archiveManager.inspectArchive(archiveFile, file.name),
            )
        } catch (error: Throwable) {
            archiveFile.delete()
            throw error
        }
    }

    suspend fun download(
        session: GoogleDriveAuthSession,
        file: GoogleDriveFile,
    ): GoogleDriveDownloadResult = withContext(Dispatchers.IO) {
        val archiveFile = createTempArchiveFile("google-download")
        try {
            driveClient.downloadToFile(session.accessToken, file.id, archiveFile)
            GoogleDriveDownloadResult(
                file = file,
                archiveFile = archiveFile,
                preview = archiveManager.inspectArchive(archiveFile, file.name),
            )
        } catch (error: Throwable) {
            archiveFile.delete()
            throw error
        }
    }

    suspend fun restore(archiveFile: File, request: SyncRestoreRequest): SyncPreview = withContext(Dispatchers.IO) {
        archiveManager.restoreArchive(archiveFile, request)
    }

    suspend fun clearCachedToken(accessToken: String) {
        if (accessToken.isBlank()) return
        withContext(Dispatchers.IO) {
            authorizationClient.clearToken(
                ClearTokenRequest.builder()
                    .setToken(accessToken)
                    .build()
            ).awaitTask()
        }
    }

    private fun createTempArchiveFile(prefix: String): File {
        val dir = File(context.cacheDir, "sync-google").apply { mkdirs() }
        return File.createTempFile("amber-$prefix-", ".$SYNC_ARCHIVE_EXTENSION", dir)
    }

    private suspend fun pruneOldSnapshots(accessToken: String) {
        val snapshots = driveClient.listSnapshots(accessToken, pageSize = CLOUD_SNAPSHOT_LIST_PAGE_SIZE)
            .sortedByDescending { it.snapshotSortKey() }
        snapshots.drop(CLOUD_SNAPSHOT_LIMIT).forEach { snapshot ->
            driveClient.delete(accessToken, snapshot.id)
        }
    }

    private fun snapshotFileName(manifest: SyncManifest): String =
        "${GoogleDriveAppDataClient.SYNC_FILE_PREFIX}${manifest.createdAt}.$SYNC_ARCHIVE_EXTENSION"

    private fun SyncManifest.toDriveAppProperties(): Map<String, String> = mapOf(
        "archiveVersion" to archiveVersion.toString(),
        "appVersionName" to appVersionName,
        "appVersionCode" to appVersionCode.toString(),
        "createdAt" to createdAt.toString(),
        "deviceId" to deviceId,
        "deviceLabel" to deviceLabel,
        "mode" to mode.name,
    ).filterValues { it.isNotBlank() }

    private fun GoogleDriveFile.snapshotSortKey(): String =
        backupCreatedAt?.toString()?.padStart(20, '0') ?: modifiedTime.orEmpty()

    private fun AuthorizationResult.toAuthorizationOutcome(): GoogleDriveAuthorizationOutcome {
        if (hasResolution()) {
            return GoogleDriveAuthorizationOutcome.ResolutionRequired(
                pendingIntent = requireNotNull(pendingIntent) { "Google 授权缺少确认窗口" }
            )
        }
        return GoogleDriveAuthorizationOutcome.Authorized(toSession())
    }

    private fun AuthorizationResult.toSession(): GoogleDriveAuthSession {
        val account = toGoogleSignInAccount()
        val token = accessToken.orEmpty()
        require(token.isNotBlank()) { "Google Drive access token 为空，请重新授权" }
        return GoogleDriveAuthSession(
            accessToken = token,
            accountEmail = account?.email.orEmpty(),
            accountId = account?.id.orEmpty(),
            displayName = account?.displayName.orEmpty(),
            grantedScopes = grantedScopes.orEmpty(),
        )
    }

    private fun Throwable.toDriveAuthorizationException(): Throwable {
        if (!isUnregisteredOAuthClientError()) return this
        return IllegalStateException(
            "当前 APK 的 Google Drive OAuth client 未注册或签名不匹配。" +
                "请检查 Google API Console 中 ${context.packageName} 的 Android OAuth client 和 SHA-1。",
            this,
        )
    }

    private fun Throwable.isUnregisteredOAuthClientError(): Boolean {
        val statusText = when (this) {
            is ApiException -> listOfNotNull(
                status.statusMessage,
                status.toString(),
                message,
            ).joinToString(" ")
            else -> message.orEmpty()
        }
        return statusText.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true)
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            continuation.resume(value)
        }
        addOnFailureListener { error ->
            continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val CLOUD_SNAPSHOT_LIMIT = 5
        private const val CLOUD_SNAPSHOT_LIST_PAGE_SIZE = 20
    }
}

sealed interface GoogleDriveAuthorizationOutcome {
    data class Authorized(val session: GoogleDriveAuthSession) : GoogleDriveAuthorizationOutcome
    data class ResolutionRequired(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationOutcome
}

data class GoogleDriveAuthSession(
    val accessToken: String,
    val accountEmail: String,
    val accountId: String,
    val displayName: String,
    val grantedScopes: List<String>,
) {
    val label: String
        get() = accountEmail.ifBlank { displayName.ifBlank { "Google 账号" } }
}

data class GoogleDriveUploadResult(
    val file: GoogleDriveFile,
    val preview: SyncPreview,
)

data class GoogleDriveDownloadResult(
    val file: GoogleDriveFile,
    val archiveFile: File,
    val preview: SyncPreview,
)
