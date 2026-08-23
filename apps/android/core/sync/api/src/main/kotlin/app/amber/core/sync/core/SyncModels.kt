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

/**
 * P7-02：备份加密方式。
 *
 * - [PASSPHRASE]：用户输入自定义口令派生加密密钥，恢复必须提供同一口令。
 * - [DEVICE_BOUND]：密钥来自本机 Keystore 保护的随机秘密，只有创建设备
 *   能恢复；归档内不记录口令或任何可逆提示（只记录 KDF 参数与 salt/iv）。
 *
 * 历史 v1 格式（[LEGACY_ARCHIVE_VERSION]）没有该字段，解码默认
 * [PASSPHRASE]，由读取分支按 `passphraseProtected` 决定是否使用
 * 历史固定回退口令（仅受控兼容，永不用于新备份）。
 */
@Serializable
enum class SyncEncryptionMode {
    PASSPHRASE,
    DEVICE_BOUND,
}

@Serializable
data class SyncManifest(
    /** 文件头格式版本（formatVersion）。v2 = 当前新格式；v1 仅用于读取迁移。 */
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
     * P7-02：加密方式（v2 起）。[PASSPHRASE] 恢复需用户口令；[DEVICE_BOUND]
     * 恢复自动使用本机 Keystore 保护的设备秘密。文件头只记录 KDF 参数与
     * salt/iv/cipher，从不记录口令或可逆提示。
     */
    val encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
    /**
     * 仅 v1（[LEGACY_ARCHIVE_VERSION]）读取迁移使用：`false` 表示该旧备份
     * 用历史固定回退口令（[NO_PASSPHRASE_FALLBACK]）加密 —— 受控兼容分支，
     * 新备份永远不再生成这种格式。v2 备份恒为 `true`。
     *
     * `true`（默认）：归档用用户口令加密，恢复需要同一口令。
     *
     * 旧归档在该字段出现前创建时默认 `true`（v1 全部要求真实口令，向后兼容）。
     */
    val passphraseProtected: Boolean = true,
)

/**
 * 历史固定回退口令 —— 仅用于读取 v1 旧备份的受控兼容分支
 * （`archiveVersion == 1 && !passphraseProtected`）。红线：不得用于新备份，
 * 新备份一律使用自定义口令或设备绑定加密。
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

    /** P7-02：v1 旧格式（只读迁移，恢复后可引导重存为新格式）。 */
    val legacyFormat: Boolean get() = manifest.archiveVersion == LEGACY_ARCHIVE_VERSION
}

/**
 * P7-02：解密成功后才可见的负载预览 —— 恢复写入前展示
 * （会话数 / 消息数 / 附件数 / 估算空间）。
 */
@Serializable
data class SyncPayloadPreview(
    val datasets: List<SyncDatasetSummary> = emptyList(),
) {
    val conversationCount: Int
        get() = datasets.firstOrNull { it.id == "table:conversationentity" }?.recordCount ?: 0

    val messageNodeCount: Int
        get() = datasets.firstOrNull { it.id == "table:message_node" }?.recordCount ?: 0

    val attachmentCount: Int
        get() = datasets.firstOrNull { it.id == "table:managed_files" }?.recordCount ?: 0

    val fileCount: Int
        get() = datasets.firstOrNull { it.id == "files" }?.recordCount ?: 0

    val estimatedBytes: Long
        get() = datasets.sumOf { it.byteCount }

    /** 负载是否包含 secrets（FULL 模式）。 */
    val includesSecrets: Boolean
        get() = datasets.any { it.id == "secrets" && it.recordCount > 0 }
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
    /** P7-02：口令加密或设备绑定加密。PASSPHRASE 模式口令必填。 */
    val encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
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

const val CURRENT_ARCHIVE_VERSION = 2
/** v1 旧格式：只读迁移，不再生成。 */
const val LEGACY_ARCHIVE_VERSION = 1
const val SYNC_ARCHIVE_MIME = "application/vnd.amberagent.backup+zip"
const val SYNC_ARCHIVE_EXTENSION = "amberbackup"

const val SYNC_MANIFEST_ENTRY = "manifest.json"
const val SYNC_PAYLOAD_ENTRY = "payload.enc"
