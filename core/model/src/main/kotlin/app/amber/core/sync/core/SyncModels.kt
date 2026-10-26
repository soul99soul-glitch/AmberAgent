package app.amber.core.sync.core

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
    /**
     * Human-readable summary of the most recent backup activity (upload OR
     * download). Stamped by BackupVM right after success so the UI can show
     * a one-line "上次备份 yyyy-MM-dd HH:mm (1.8.16, OPPO PMA110)" hint
     * without re-fetching the cloud archive.
     *
     * On upload: filled from local BuildConfig + Build.MANUFACTURER/MODEL
     * (these describe the device that produced the archive that's now in
     * the cloud — i.e. the same as the local one).
     * On download/restore: filled from the archive's SyncManifest fields.
     * Blank when the user hasn't uploaded or downloaded anything yet — UI
     * surfaces that as "暂无备份".
     */
    val lastBackupVersionName: String = "",
    val lastBackupVersionCode: Long = 0L,
    val lastBackupDeviceLabel: String = "",
)

@Serializable
data class SyncManifest(
    val archiveVersion: Int = CURRENT_ARCHIVE_VERSION,
    val appVersionName: String,
    val appVersionCode: Long,
    val createdAt: Long,
    val deviceId: String,
    /**
     * Human-readable model string of the device that produced this archive,
     * e.g. "OPPO PMA110", "vivo V2509A". Stamped from Build.MANUFACTURER +
     * Build.MODEL at archive-creation time. Defaults to blank for archives
     * created before this field existed; UI shows "未知设备" in that case.
     */
    val deviceLabel: String = "",
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
const val NO_PASSPHRASE_FALLBACK = "AmberAgent-NoPassphrase-v1"

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
    val googleGeminiOAuth: String? = null,
)

@Serializable
data class SyncPayloadManifest(
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
    /**
     * Only consulted when `scope == EVERYTHING`. When true, the restore
     * leaves the local conversation tables alone (conversation rows,
     * message_node rows, conversation_compact, conversation_context_event)
     * — every OTHER table is wiped + replaced. The CONFIG_ONLY scope
     * implicitly preserves conversations and ignores this flag.
     *
     * User intent: "对话整个覆盖掉了也不太好" — a restore is usually about
     * picking up provider configs / assistants / files from another device,
     * not wiping the locally-typed chat history.
     */
    val preserveConversations: Boolean = true,
    /**
     * Only consulted when `scope == EVERYTHING`. When true, leaves the
     * genmediaentity table (ImgGenPage gallery) and both image file
     * folders (chat_images, images) untouched. CONFIG_ONLY ignores.
     *
     * User said "绘画是一个单独可以选的" — image-generation output is a
     * separate axis from conversations, surfaced as its own toggle.
     */
    val preserveGenMedia: Boolean = true,
)

const val CURRENT_ARCHIVE_VERSION = 1
const val SYNC_ARCHIVE_MIME = "application/vnd.amberagent.backup+zip"
const val SYNC_ARCHIVE_EXTENSION = "amberbackup"

const val SYNC_MANIFEST_ENTRY = "manifest.json"
const val SYNC_PAYLOAD_ENTRY = "payload.enc"
