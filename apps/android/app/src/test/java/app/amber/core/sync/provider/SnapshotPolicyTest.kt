package app.amber.core.sync.provider

import app.amber.core.sync.core.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P7-01 验收：覆盖 / 创建副本策略（planUpload 纯函数）。
 */
class SnapshotPolicyTest {

    private fun snapshot(id: String, deviceId: String = "device-1") = SyncSnapshot(
        providerId = "webdav",
        snapshotId = id,
        name = "$id.amberbackup",
        manifest = SyncSnapshotManifest(
            snapshotId = id,
            createdAt = 1L,
            deviceId = deviceId,
            mode = SyncMode.FULL,
        ),
    )

    @Test
    fun `overwrite supersedes same-device snapshot`() {
        val plan = planUpload(
            existing = listOf(snapshot("snap-old", deviceId = "device-1"), snapshot("snap-other", deviceId = "device-2")),
            deviceId = "device-1",
            policy = UploadConflictPolicy.OVERWRITE,
            now = 1000L,
        )
        assertEquals("snap-1000-", plan.snapshotId.take(10))
        assertEquals("snap-old", plan.supersededSnapshotId)
    }

    @Test
    fun `overwrite with no same-device snapshot does not supersede`() {
        val plan = planUpload(
            existing = listOf(snapshot("snap-other", deviceId = "device-2")),
            deviceId = "device-1",
            policy = UploadConflictPolicy.OVERWRITE,
            now = 1000L,
        )
        assertNull(plan.supersededSnapshotId)
    }

    @Test
    fun `create copy never supersedes and keeps remote snapshots`() {
        val plan = planUpload(
            existing = listOf(snapshot("snap-old", deviceId = "device-1")),
            deviceId = "device-1",
            policy = UploadConflictPolicy.CREATE_COPY,
            now = 1000L,
        )
        assertNull(plan.supersededSnapshotId)
        assertEquals("snap-1000-", plan.snapshotId.take(10))
    }

    @Test
    fun `blank device id is not treated as a same-device match`() {
        val plan = planUpload(
            existing = listOf(snapshot("snap-blank", deviceId = "")),
            deviceId = "device-1",
            policy = UploadConflictPolicy.OVERWRITE,
            now = 1000L,
        )
        assertNull(plan.supersededSnapshotId)
    }

    @Test
    fun `snapshot ids are unique per call`() {
        val first = newSnapshotId(1000L)
        val second = newSnapshotId(1000L)
        assertNotNull(first)
        assertNotNull(second)
        // 同毫秒不同随机段 → 不碰撞。
        assertEquals("snap-1000-", first.take(10))
        assertEquals("snap-1000-", second.take(10))
    }
}
