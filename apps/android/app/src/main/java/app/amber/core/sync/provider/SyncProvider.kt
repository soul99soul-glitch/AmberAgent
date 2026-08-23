package app.amber.core.sync.provider

import app.amber.core.sync.core.SyncMode
import java.io.File

/**
 * P7-01 SyncProvider —— Android/iOS 能力对齐计划 Phase 7 的统一同步抽象。
 *
 * 三个实现：
 * - [WebDavSyncProvider]：完整 WebDAV 实现（PROPFIND/GET/PUT/MOVE/DELETE）。
 * - [GoogleDriveSyncProvider]：现有 Google Drive 协议只做适配，不重写。
 * - [LocalFolderSyncProvider]：SAF document tree + 持久 URI permission。
 *
 * 快照元数据统一为 [SyncSnapshotManifest]（snapshotId、createdAt、appVersion、
 * schemaVersion、deviceLabel、encrypted、kdfVersion、size、content digest、
 * 包含领域与排除的秘密类型），写入时随快照落一个 sidecar JSON。
 */
interface SyncProvider {
    /** 稳定标识：`google_drive` / `webdav` / `local_folder`。 */
    val id: String

    /** 远端快照列表（含元数据，不下载数据体）。 */
    suspend fun listSnapshots(): List<SyncSnapshot>

    /**
     * 读取单个快照的元数据（preview），不下载数据体。
     * 恢复前由 [SnapshotCompatibility.check] 做兼容性检查。
     */
    suspend fun previewSnapshot(snapshotId: String): SyncSnapshotManifest

    /**
     * 上传新快照。实现必须先用临时名写完整份数据，校验成功后
     * 再 publish 为最终名（WebDAV MOVE / SAF rename）。
     */
    suspend fun uploadSnapshot(request: SyncProviderUploadRequest): SyncSnapshot

    /**
     * 下载快照数据体到本地临时文件，并完成校验：
     * 内容 digest（contentSha256）比对 + 归档头部（manifest.json /
     * payload.enc 加密头）检查。校验失败抛错并删除临时文件。
     */
    suspend fun downloadSnapshot(snapshotId: String): File

    /** 删除远端快照（数据体 + sidecar）。 */
    suspend fun deleteSnapshot(snapshotId: String)
}

/** 上传冲突策略：覆盖本机在远端的旧快照，或保留全部创建副本。 */
enum class UploadConflictPolicy {
    OVERWRITE,
    CREATE_COPY,
}

data class SyncProviderUploadRequest(
    val mode: SyncMode,
    val passphrase: String,
    /** P7-02：口令加密或设备绑定加密（PASSPHRASE 模式口令必填）。 */
    val encryptionMode: app.amber.core.sync.core.SyncEncryptionMode =
        app.amber.core.sync.core.SyncEncryptionMode.PASSPHRASE,
    val conflictPolicy: UploadConflictPolicy = UploadConflictPolicy.CREATE_COPY,
)

/** Provider 侧的一个快照条目：统一元数据 + provider 定位信息。 */
data class SyncSnapshot(
    val providerId: String,
    val snapshotId: String,
    /** Provider 侧展示名（远端文件名）。 */
    val name: String,
    val manifest: SyncSnapshotManifest,
    val sizeBytes: Long = manifest.sizeBytes,
)
