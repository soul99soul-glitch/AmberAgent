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
    /**
     * `true` (default): the archive was encrypted with a user-supplied
     * passphrase; restoring it requires the same passphrase.
     *
     * `false`: the archive was encrypted with a constant fallback
     * passphrase ([NO_PASSPHRASE_FALLBACK]) — still AES-GCM-256 on the
     * wire (the cipher/kdf fields stay valid), but anyone who can read
     * the file can decrypt it. Only the user's Google Drive account
     * ACL gates access. The UI auto-applies the fallback on restore so
     * the user doesn't need to type anything.
     *
     * Old archives created before this field existed default to `true`
     * via the Kotlin serialization default — they always required a real
     * passphrase anyway, so the default is backwards-compatible.
     */
    val passphraseProtected: Boolean = true,
)

/**
 * Fixed passphrase substituted when the user opts out of a real password.
 * Length and entropy don't matter — the threat model is "Google account
 * holder can decrypt, anyone else can't get the file off Google Drive's
 * AppData folder in the first place".
 */
internal const val NO_PASSPHRASE_FALLBACK = "AmberAgent-NoPassphrase-v1"

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

/**
 * Scope of a restore operation.
 *
 * - [CONFIG_ONLY] writes only the settings + secrets (provider list, API
 *   keys, OAuth tokens) back into local state. Local conversations,
 *   messages, memories, files, generated images, board / feishu state —
 *   ALL preserved. Intended for the "I just want my provider configs
 *   back, don't touch my chat history" workflow.
 *
 * - [EVERYTHING] is the historical full-replace: every backed-up table
 *   wipes and replaces its local counterpart; all file directories are
 *   wiped and refilled from the archive.
 */
enum class RestoreScope {
    CONFIG_ONLY,
    EVERYTHING,
}

data class SyncRestoreRequest(
    val passphrase: String,
    val scope: RestoreScope = RestoreScope.EVERYTHING,
)

const val CURRENT_ARCHIVE_VERSION = 1
const val SYNC_ARCHIVE_MIME = "application/vnd.amberagent.backup+zip"
const val SYNC_ARCHIVE_EXTENSION = "amberbackup"

internal const val SYNC_MANIFEST_ENTRY = "manifest.json"
internal const val SYNC_PAYLOAD_ENTRY = "payload.enc"
