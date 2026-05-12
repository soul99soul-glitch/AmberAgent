package me.rerere.rikkahub.data.sync.core

import kotlinx.serialization.Serializable

@Serializable
enum class SyncMode {
    STANDARD,
    FULL,
}

@Serializable
data class SyncSettings(
    val googleEnabled: Boolean = false,
    val googleAccountEmail: String = "",
    val googleAccountId: String = "",
    val googleDisplayName: String = "",
    val mode: SyncMode = SyncMode.STANDARD,
    val autoSyncEnabled: Boolean = false,
    val deviceId: String = "",
    val lastLocalExportAt: Long = 0L,
    val lastUploadAt: Long = 0L,
    val lastDownloadAt: Long = 0L,
    val lastRemoteRevision: String = "",
    val lastError: String = "",
)

@Serializable
data class SyncManifest(
    val archiveVersion: Int = CURRENT_ARCHIVE_VERSION,
    val appVersionName: String,
    val appVersionCode: Long,
    val createdAt: Long,
    val deviceId: String,
    val mode: SyncMode,
    val remoteRevision: String = "",
    val encrypted: Boolean = true,
    val kdf: SyncKdfInfo,
    val cipher: SyncCipherInfo,
    val payloadSha256: String,
)

@Serializable
data class SyncKdfInfo(
    val name: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    val saltBase64: String,
    val keySizeBits: Int = 256,
)

@Serializable
data class SyncCipherInfo(
    val name: String = "AES/GCM/NoPadding",
    val ivBase64: String,
    val tagSizeBits: Int = 128,
)

@Serializable
data class SyncDatasetSummary(
    val id: String,
    val recordCount: Int = 0,
    val byteCount: Long = 0L,
)

@Serializable
data class SyncPreview(
    val manifest: SyncManifest,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
) {
    val createdAt: Long get() = manifest.createdAt
    val mode: SyncMode get() = manifest.mode
}

@Serializable
data class SyncSecretSnapshot(
    val webMountOauth: String? = null,
    val openAICodexOAuth: String? = null,
)

@Serializable
internal data class SyncPayloadManifest(
    val datasets: List<SyncDatasetSummary> = emptyList(),
)

data class SyncExportRequest(
    val mode: SyncMode,
    val passphrase: String,
)

data class SyncRestoreRequest(
    val passphrase: String,
)

const val CURRENT_ARCHIVE_VERSION = 1
const val SYNC_ARCHIVE_MIME = "application/vnd.amberagent.backup+zip"
const val SYNC_ARCHIVE_EXTENSION = "amberbackup"

internal const val SYNC_MANIFEST_ENTRY = "manifest.json"
internal const val SYNC_PAYLOAD_ENTRY = "payload.enc"
