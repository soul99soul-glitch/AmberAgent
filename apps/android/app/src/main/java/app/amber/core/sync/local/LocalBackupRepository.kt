package app.amber.core.sync.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.core.sync.core.SYNC_ARCHIVE_EXTENSION
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.core.SyncRestoreVerification
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalBackupRepository(
    private val context: Context,
    private val syncArchiveManager: SyncArchiveManager,
) {
    suspend fun exportToUri(uri: Uri, request: SyncExportRequest): SyncPreview = withContext(Dispatchers.IO) {
        val archiveFile = syncArchiveManager.createArchiveFile(request)
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                archiveFile.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: error("无法打开导出文件")
            syncArchiveManager.inspectArchive(archiveFile, suggestedFileName())
        } finally {
            archiveFile.delete()
        }
    }

    suspend fun inspectUri(uri: Uri): SyncPreview = withContext(Dispatchers.IO) {
        val file = copyUriToTempFile(uri)
        try {
            syncArchiveManager.inspectArchive(file)
        } finally {
            file.delete()
        }
    }

    /**
     * P7-02 恢复第一步：验证头部 + 认证标签并解密（不写入）。验证结果持有
     * 解密后的临时负载；调用方确认后 [SyncArchiveManager.applyRestore]，
     * 取消时 [SyncArchiveManager.discardVerification]（不残留）。
     */
    suspend fun verifyUri(uri: Uri, request: SyncRestoreRequest): SyncRestoreVerification =
        withContext(Dispatchers.IO) {
            val file = copyUriToTempFile(uri)
            try {
                syncArchiveManager.verifyArchive(file, request)
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }

    suspend fun restoreFromUri(uri: Uri, request: SyncRestoreRequest): SyncPreview = withContext(Dispatchers.IO) {
        val file = copyUriToTempFile(uri)
        try {
            syncArchiveManager.restoreArchive(file, request)
        } finally {
            file.delete()
        }
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val dir = File(context.cacheDir, COPY_DIR_NAME).apply { mkdirs() }
        val file = File.createTempFile(COPY_TEMP_PREFIX, ".$SYNC_ARCHIVE_EXTENSION", dir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().buffered().use { output ->
                    input.copyToWithinLimit(output, MAX_IMPORT_ARCHIVE_BYTES)
                }
            } ?: error("无法读取备份文件")
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /**
     * 判断文件是否为本仓库导入路径（[inspectUri] / [verifyUri] / [restoreFromUri]）
     * 创建的 cacheDir 临时副本 —— App 自建、可安全删除；用户原始选择的文件
     * 不在此目录，绝不会被误删。
     */
    fun isOwnedTempCopy(file: File): Boolean =
        file.parentFile?.name == COPY_DIR_NAME && file.name.startsWith(COPY_TEMP_PREFIX)

    companion object {
        private const val MAX_IMPORT_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val COPY_DIR_NAME = "sync-local"
        private const val COPY_TEMP_PREFIX = "amber-import-"

        fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
            val stamp = Instant.ofEpochMilli(now)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            return "AmberAgent-$stamp.$SYNC_ARCHIVE_EXTENSION"
        }
    }
}

private fun java.io.InputStream.copyToWithinLimit(output: java.io.OutputStream, limit: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Backup archive exceeds ${limit} bytes" }
        output.write(buffer, 0, read)
        total += read
    }
}
