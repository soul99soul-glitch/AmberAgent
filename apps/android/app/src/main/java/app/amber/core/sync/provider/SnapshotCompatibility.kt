package app.amber.core.sync.provider

import app.amber.core.sync.core.CURRENT_ARCHIVE_VERSION
import app.amber.core.sync.core.LEGACY_ARCHIVE_VERSION

/** 恢复 / 预览前的兼容性检查结果。 */
sealed interface SnapshotCompatibility {
    data object Compatible : SnapshotCompatibility
    data class Incompatible(val reason: String) : SnapshotCompatibility
}

/**
 * 恢复前兼容性检查：sidecar schema 版本不能高于当前支持版本；
 * 归档格式版本（archiveVersion）必须是当前格式或 v1 旧格式（只读迁移）。
 *
 * schemaVersion 高于当前 → 该快照由更新版本的应用生成，拒绝恢复；
 * archiveVersion 不是 v1/v2 → 归档内部格式未知，拒绝恢复。
 */
fun checkSnapshotCompatibility(manifest: SyncSnapshotManifest): SnapshotCompatibility {
    if (manifest.schemaVersion > CURRENT_SNAPSHOT_SCHEMA_VERSION) {
        return SnapshotCompatibility.Incompatible(
            "快照元数据 schema v${manifest.schemaVersion} 高于当前支持的 v$CURRENT_SNAPSHOT_SCHEMA_VERSION"
        )
    }
    if (
        manifest.archiveVersion != CURRENT_ARCHIVE_VERSION &&
        manifest.archiveVersion != LEGACY_ARCHIVE_VERSION
    ) {
        return SnapshotCompatibility.Incompatible(
            "备份格式 v${manifest.archiveVersion} 与当前格式 v$CURRENT_ARCHIVE_VERSION 不兼容"
        )
    }
    return SnapshotCompatibility.Compatible
}
