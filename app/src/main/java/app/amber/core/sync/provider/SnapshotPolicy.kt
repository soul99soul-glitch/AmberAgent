package app.amber.core.sync.provider

import java.util.UUID

/**
 * P7-01 覆盖 / 合并 / 创建副本的明确策略（上传侧）。
 *
 * - [UploadConflictPolicy.OVERWRITE]：本机在远端已有同 deviceId 的快照时，
 *   新快照发布成功后删除旧快照（覆盖语义，云端不无限堆积）。
 * - [UploadConflictPolicy.CREATE_COPY]：保留远端全部快照，只新增一份。
 *
 * 恢复侧的覆盖 / 合并由现有恢复对话框承担：覆盖 = EVERYTHING scope，
 * 合并 = preserveConversations / preserveGenMedia 开关（保留本机数据）。
 */
fun planUpload(
    existing: List<SyncSnapshot>,
    deviceId: String,
    policy: UploadConflictPolicy,
    now: Long = System.currentTimeMillis(),
): UploadPlan {
    val sameDevice = existing.firstOrNull { snapshot ->
        snapshot.manifest.deviceId.isNotBlank() && snapshot.manifest.deviceId == deviceId
    }
    return UploadPlan(
        snapshotId = newSnapshotId(now),
        supersededSnapshotId = when (policy) {
            UploadConflictPolicy.OVERWRITE -> sameDevice?.snapshotId
            UploadConflictPolicy.CREATE_COPY -> null
        },
    )
}

/** 新快照 ID：时间戳前缀 + 短随机段，保证并发/同毫秒也不碰撞。 */
fun newSnapshotId(now: Long = System.currentTimeMillis()): String =
    "snap-$now-${UUID.randomUUID().toString().take(8)}"

data class UploadPlan(
    val snapshotId: String,
    /** OVERWRITE 策略下、新快照发布成功后应删除的同 deviceId 旧快照。 */
    val supersededSnapshotId: String? = null,
)
