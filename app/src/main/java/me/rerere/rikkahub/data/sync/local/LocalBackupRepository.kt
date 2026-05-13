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
            context.contentResolver.openOutputStream(uri)?.use { output ->
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

    suspend fun restoreFromUri(uri: Uri, request: SyncRestoreRequest): SyncPreview = withContext(Dispatchers.IO) {
        val file = copyUriToTempFile(uri)
        try {
            syncArchiveManager.restoreArchive(file, request)
        } finally {
            file.delete()
        }
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val dir = File(context.cacheDir, "sync-local").apply { mkdirs() }
        val file = File.createTempFile("amber-import-", ".$SYNC_ARCHIVE_EXTENSION", dir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            } ?: error("无法读取备份文件")
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    companion object {
        fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
            val stamp = Instant.ofEpochMilli(now)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            return "AmberAgent-$stamp.$SYNC_ARCHIVE_EXTENSION"
        }
    }
}
