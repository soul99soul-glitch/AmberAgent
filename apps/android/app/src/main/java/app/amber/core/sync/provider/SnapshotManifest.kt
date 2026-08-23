package app.amber.core.sync.provider

import app.amber.core.sync.core.CURRENT_ARCHIVE_VERSION
import app.amber.core.sync.core.SyncCipherInfo
import app.amber.core.sync.core.SyncKdfInfo
import app.amber.core.sync.core.SyncManifest
import app.amber.core.sync.core.SyncMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * P7-01 统一快照 manifest（sidecar JSON，随快照数据体一起落盘/上传）。
 *
 * 字段：snapshotId、createdAt、appVersion、schemaVersion、deviceLabel、
 * encrypted、kdfVersion、size、content digest、包含领域与排除的秘密类型。
 *
 * WebDAV / 本地文件夹侧以 `<snapshotId>.snapshot.json` 形式保存，列表与
 * preview 只读 sidecar，不下载数据体。Google Drive 侧不新增文件 —— 由
 * [GoogleDriveSyncProvider] 从现有 appProperties 映射（协议不改写）。
 */
@Serializable
data class SyncSnapshotManifest(
    val snapshotId: String,
    val createdAt: Long,
    val appVersionName: String = "",
    val appVersionCode: Long = 0L,
    /** 本 manifest JSON 的 schema 版本，见 [CURRENT_SNAPSHOT_SCHEMA_VERSION]。 */
    val schemaVersion: Int = CURRENT_SNAPSHOT_SCHEMA_VERSION,
    val deviceId: String = "",
    val deviceLabel: String = "",
    /** 底层归档格式版本（对齐 [SyncManifest.archiveVersion] / CURRENT_ARCHIVE_VERSION）。 */
    val archiveVersion: Int = CURRENT_ARCHIVE_VERSION,
    val mode: SyncMode = SyncMode.STANDARD,
    val encrypted: Boolean = true,
    /** KDF 标识，对齐 [SyncManifest.kdf.name]（当前仅 PBKDF2WithHmacSHA256）。 */
    val kdfVersion: String = "PBKDF2WithHmacSHA256",
    val sizeBytes: Long = 0L,
    /** 归档数据体的 SHA-256（content digest）。空串 = 该 Provider 不提供外层 digest。 */
    val contentSha256: String = "",
    /** 归档包含的数据领域（settings / secrets / tables / files）。 */
    val includedDomains: Set<String> = emptySet(),
    /** 归档明确排除的秘密类型（如 STANDARD 模式的 OAuth 令牌）。 */
    val excludedSecretTypes: Set<String> = emptySet(),
)

/** 当前支持的 sidecar schema 版本。 */
const val CURRENT_SNAPSHOT_SCHEMA_VERSION = 1

/** sidecar 文件名后缀。 */
const val SNAPSHOT_SIDECAR_SUFFIX = ".snapshot.json"

/** 归档文件名后缀（与数据体同名同源）。 */
const val SNAPSHOT_ARCHIVE_SUFFIX = ".amberbackup"

/** 由归档的 [SyncManifest] + 数据体事实构造统一快照 manifest。 */
fun snapshotManifestFromArchive(
    snapshotId: String,
    archive: SyncManifest,
    sizeBytes: Long,
    contentSha256: String,
): SyncSnapshotManifest {
    val domains = domainsForMode(archive.mode)
    return SyncSnapshotManifest(
        snapshotId = snapshotId,
        createdAt = archive.createdAt,
        appVersionName = archive.appVersionName,
        appVersionCode = archive.appVersionCode,
        deviceId = archive.deviceId,
        deviceLabel = archive.deviceLabel,
        archiveVersion = archive.archiveVersion,
        mode = archive.mode,
        encrypted = archive.encrypted,
        kdfVersion = archive.kdf.name,
        sizeBytes = sizeBytes,
        contentSha256 = contentSha256,
        includedDomains = domains.included,
        excludedSecretTypes = domains.excludedSecrets,
    )
}

/** 快照包含的领域与排除的秘密类型。 */
data class SnapshotDomains(
    val included: Set<String>,
    val excludedSecrets: Set<String>,
)

/**
 * 领域从归档模式推导：FULL 含 secrets（OAuth 令牌），STANDARD 明确排除。
 * 归档内实际条目由 SyncPayloadManifest 统计（解密后可见），sidecar 只记录
 * 模式级声明 —— 不把明文 secret 类型之外的细节放进可枚举元数据。
 */
fun domainsForMode(mode: SyncMode): SnapshotDomains = when (mode) {
    SyncMode.FULL -> SnapshotDomains(
        included = setOf("settings", "secrets", "tables", "files"),
        excludedSecrets = emptySet(),
    )
    SyncMode.STANDARD -> SnapshotDomains(
        included = setOf("settings", "tables", "files"),
        excludedSecrets = setOf("oauth"),
    )
}

fun snapshotArchiveName(snapshotId: String): String = "$snapshotId$SNAPSHOT_ARCHIVE_SUFFIX"

fun snapshotSidecarName(snapshotId: String): String = "$snapshotId$SNAPSHOT_SIDECAR_SUFFIX"

fun encodeSnapshotManifest(json: Json, manifest: SyncSnapshotManifest): String =
    json.encodeToString(manifest)

fun decodeSnapshotManifest(json: Json, raw: String): SyncSnapshotManifest =
    json.decodeFromString(raw)

/**
 * 仅用于 UI 展示的归档 manifest 映射（快照元数据 → 归档 manifest 形态，
 * 让现有 SyncPreview 组件可复用）。kdf/cipher 字段不完整 —— 不可用于解密，
 * 解密始终走数据体归档内的真实 manifest。
 */
fun SyncSnapshotManifest.toArchiveManifest(): SyncManifest = SyncManifest(
    archiveVersion = archiveVersion,
    appVersionName = appVersionName,
    appVersionCode = appVersionCode,
    createdAt = createdAt,
    deviceId = deviceId,
    deviceLabel = deviceLabel,
    mode = mode,
    encrypted = encrypted,
    kdf = SyncKdfInfo(iterations = 0, saltBase64 = ""),
    cipher = SyncCipherInfo(ivBase64 = ""),
    payloadSha256 = contentSha256,
)
