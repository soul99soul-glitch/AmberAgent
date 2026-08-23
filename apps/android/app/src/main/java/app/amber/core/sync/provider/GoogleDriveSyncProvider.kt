package app.amber.core.sync.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.core.sync.google.GoogleDriveAuthSession
import app.amber.core.sync.google.GoogleDriveSyncRepository

/**
 * P7-01 Google Drive SyncProvider —— 现有能力适配统一接口。
 *
 * 红线：不重写已工作的 Google Drive 协议。全部调用走 [GoogleDriveSyncRepository]
 * / GoogleDriveAppDataClient 既有实现；元数据映射自 Drive 文件已有的
 * appProperties（createdAt / appVersion / deviceLabel / archiveVersion / mode），
 * 不新增任何 Drive 侧文件或字段。因此 contentSha256 为空串 —— 外层 digest
 * 校验不适用于 Drive（协议未携带该字段），下载完整性仍由归档内层
 * payloadSha256 在恢复时校验。
 *
 * 生产仅使用 [deleteSnapshot]（BackupVM 删除云端快照）；列表/上传/下载
 * 走 [GoogleDriveSyncRepository] 既有能力，不经由此适配层。
 */
class GoogleDriveSyncProvider(
    private val repository: GoogleDriveSyncRepository,
    private val session: GoogleDriveAuthSession,
) {

    /** 删除远端快照。 */
    suspend fun deleteSnapshot(snapshotId: String) = withContext(Dispatchers.IO) {
        val file = repository.listSnapshots(session).firstOrNull { it.id == snapshotId }
            ?: return@withContext
        repository.delete(session, file)
    }
}
