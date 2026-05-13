package me.rerere.rikkahub.data.sync.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.core.SyncArchiveManager
import me.rerere.rikkahub.data.sync.core.SyncExportRequest
import me.rerere.rikkahub.data.sync.core.SyncPreview
import me.rerere.rikkahub.data.sync.core.SyncRestoreRequest
import me.rerere.rikkahub.data.sync.core.SYNC_ARCHIVE_EXTENSION
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
        val result = authorizationClient.authorize(request).awaitTask()
        return result.toAuthorizationOutcome()
    }

    suspend fun restoreAuthorizedSession(): GoogleDriveAuthorizationOutcome = authorizeDrive()

    fun completeAuthorization(intent: Intent?): GoogleDriveAuthSession {
        requireNotNull(intent) { "Google 授权结果为空" }
        return authorizationClient.getAuthorizationResultFromIntent(intent).toSession()
    }

    suspend fun findLatest(session: GoogleDriveAuthSession): GoogleDriveFile? = withContext(Dispatchers.IO) {
        driveClient.findLatest(session.accessToken)
    }

    suspend fun upload(
        session: GoogleDriveAuthSession,
        request: SyncExportRequest,
        existingFileId: String?,
        expectedRemoteRevision: String?,
    ): GoogleDriveUploadResult = withContext(Dispatchers.IO) {
        val latest = driveClient.findLatest(session.accessToken)
        when {
            existingFileId.isNullOrBlank() && latest != null -> {
                error("Google Drive 云端快照刚刚发生变化，请重新确认后再上传。")
            }

            !existingFileId.isNullOrBlank() && latest?.id != existingFileId -> {
                error("Google Drive 云端快照刚刚发生变化，请重新确认后再上传。")
            }

            !existingFileId.isNullOrBlank() &&
                latest?.id == existingFileId &&
                expectedRemoteRevision != null &&
                latest.revisionKey != expectedRemoteRevision -> {
                error("Google Drive 云端快照刚刚发生变化，请重新确认后再上传。")
            }
        }
        val archiveFile = archiveManager.createArchiveFile(request)
        try {
            val file = driveClient.upload(
                accessToken = session.accessToken,
                archiveFile = archiveFile,
                existingFileId = existingFileId,
            )
            GoogleDriveUploadResult(
                file = file,
                preview = archiveManager.inspectArchive(archiveFile, file.name),
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
