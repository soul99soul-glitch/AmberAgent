package app.amber.core.sync.provider

import app.amber.core.sync.core.CURRENT_ARCHIVE_VERSION
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-01 验收：恢复前 preview 兼容性检查（schemaVersion / archiveVersion 比对）。
 */
class SnapshotCompatibilityTest {

    private fun manifest(
        schemaVersion: Int = CURRENT_SNAPSHOT_SCHEMA_VERSION,
        archiveVersion: Int = CURRENT_ARCHIVE_VERSION,
    ) = SyncSnapshotManifest(
        snapshotId = "snap-1",
        createdAt = 1L,
        schemaVersion = schemaVersion,
        archiveVersion = archiveVersion,
    )

    @Test
    fun `current schema and archive version are compatible`() {
        assertTrue(checkSnapshotCompatibility(manifest()) is SnapshotCompatibility.Compatible)
    }

    @Test
    fun `older schema version is compatible`() {
        assertTrue(checkSnapshotCompatibility(manifest(schemaVersion = 0)) is SnapshotCompatibility.Compatible)
    }

    @Test
    fun `newer schema version is rejected`() {
        val result = checkSnapshotCompatibility(manifest(schemaVersion = CURRENT_SNAPSHOT_SCHEMA_VERSION + 1))
        assertTrue(result is SnapshotCompatibility.Incompatible)
        assertTrue((result as SnapshotCompatibility.Incompatible).reason.contains("schema"))
    }

    @Test
    fun `mismatched archive version is rejected`() {
        val result = checkSnapshotCompatibility(manifest(archiveVersion = CURRENT_ARCHIVE_VERSION + 1))
        assertTrue(result is SnapshotCompatibility.Incompatible)
        assertTrue((result as SnapshotCompatibility.Incompatible).reason.contains("备份格式"))
    }

    @Test
    fun `both mismatches report schema first`() {
        val result = checkSnapshotCompatibility(
            manifest(
                schemaVersion = CURRENT_SNAPSHOT_SCHEMA_VERSION + 2,
                archiveVersion = CURRENT_ARCHIVE_VERSION + 2,
            )
        )
        assertTrue(result is SnapshotCompatibility.Incompatible)
        assertTrue(
            (result as SnapshotCompatibility.Incompatible).reason.startsWith("快照元数据 schema"),
        )
    }
}
