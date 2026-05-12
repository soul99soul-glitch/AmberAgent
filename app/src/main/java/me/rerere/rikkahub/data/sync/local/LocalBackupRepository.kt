package me.rerere.rikkahub.data.sync.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.core.SYNC_ARCHIVE_EXTENSION
import me.rerere.rikkahub.data.sync.core.SyncArchiveManager
import me.rerere.rikkahub.data.sync.core.SyncExportRequest
import me.rerere.rikkahub.data.sync.core.SyncPreview
import me.rerere.rikkahub.data.sync.core.SyncRestoreRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalBackupRepository(
    private val context: Context,
    private val syncArchiveManager: SyncArchiveManager,
) {
    suspend fun exportToUri(uri: Uri, request: SyncExportRequest): SyncPreview = withContext(Dispatchers.IO) {
        val bytes = syncArchiveManager.createArchive(request)
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
        } ?: error("无法打开导出文件")
        syncArchiveManager.inspectArchive(bytes, suggestedFileName())
    }

    suspend fun inspectUri(uri: Uri): SyncPreview = withContext(Dispatchers.IO) {
        val bytes = readUri(uri)
        syncArchiveManager.inspectArchive(bytes)
    }

    suspend fun restoreFromUri(uri: Uri, request: SyncRestoreRequest): SyncPreview = withContext(Dispatchers.IO) {
        val bytes = readUri(uri)
        syncArchiveManager.restoreArchive(bytes, request)
    }

    private fun readUri(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
            ?: error("无法读取备份文件")

    companion object {
        fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
            val stamp = Instant.ofEpochMilli(now)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            return "AmberAgent-$stamp.$SYNC_ARCHIVE_EXTENSION"
        }
    }
}
