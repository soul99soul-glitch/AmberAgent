package app.amber.core.sync

import android.content.Context
import app.amber.agent.data.db.AppDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private val databaseEntryNames = setOf(
    "amber_agent.db",
    "amber_agent-wal",
    "amber_agent-shm",
)

internal data class BackupArchiveInspection(
    val hasDatabasePayload: Boolean,
    val hasMainDatabase: Boolean,
)

internal fun inspectBackupArchive(backupFile: File): BackupArchiveInspection {
    var hasDatabasePayload = false
    var hasMainDatabase = false
    ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
        while (true) {
            val entry = zipIn.nextEntry ?: break
            requireSafeZipEntryName(entry.name)
            if (!entry.isDirectory && entry.name in databaseEntryNames) {
                hasDatabasePayload = true
                hasMainDatabase = hasMainDatabase || entry.name == "amber_agent.db"
            }
            zipIn.closeEntry()
        }
    }
    return BackupArchiveInspection(
        hasDatabasePayload = hasDatabasePayload,
        hasMainDatabase = hasMainDatabase,
    )
}

internal fun requireSafeZipEntryName(entryName: String) {
    require(entryName.isNotBlank()) { "Invalid backup entry path" }
    require('\\' !in entryName) { "Invalid backup entry path: $entryName" }
    require(!entryName.startsWith('/')) { "Invalid backup entry path: $entryName" }
    require(!entryName.contains("//")) { "Invalid backup entry path: $entryName" }
    require(!Regex("^[A-Za-z]:").containsMatchIn(entryName)) {
        "Invalid backup entry path: $entryName"
    }

    val segments = entryName.split('/').filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) { "Invalid backup entry path: $entryName" }
    require(segments.none { it == "." || it == ".." }) {
        "Invalid backup entry path: $entryName"
    }
}

internal fun resolveArchiveChild(parent: File, relativePath: String): File {
    requireSafeZipEntryName(relativePath)
    val parentCanonical = parent.canonicalFile
    val target = File(parentCanonical, relativePath).canonicalFile
    val parentPath = parentCanonical.path
    require(target.path == parentPath || target.path.startsWith(parentPath + File.separator)) {
        "Backup entry escapes restore directory: $relativePath"
    }
    return target
}

internal fun copyZipEntryToFile(
    zipIn: ZipInputStream,
    targetFile: File,
    maxBytes: Long = MAX_BACKUP_ENTRY_BYTES,
) {
    targetFile.parentFile?.mkdirs()
    FileOutputStream(targetFile).use { outputStream ->
        zipIn.copyToBackupEntryWithinLimit(outputStream, maxBytes)
    }
}

internal fun readZipEntryTextWithinLimit(zipIn: ZipInputStream, maxBytes: Long = MAX_BACKUP_SETTINGS_BYTES): String {
    val output = java.io.ByteArrayOutputStream()
    zipIn.copyToBackupEntryWithinLimit(output, maxBytes)
    return output.toByteArray().toString(Charsets.UTF_8)
}

internal fun ZipInputStream.copyToBackupEntryWithinLimit(
    output: java.io.OutputStream,
    limit: Long = MAX_BACKUP_ENTRY_BYTES,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Backup archive entry exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
}

private const val MAX_BACKUP_SETTINGS_BYTES = 8L * 1024 * 1024
private const val MAX_BACKUP_ENTRY_BYTES = 512L * 1024 * 1024

internal fun databaseTempFile(tempDir: File, entryName: String): File? = when (entryName) {
    "amber_agent.db" -> File(tempDir, "amber_agent")
    "amber_agent-wal" -> File(tempDir, "amber_agent-wal")
    "amber_agent-shm" -> File(tempDir, "amber_agent-shm")
    else -> null
}

internal fun replaceDatabaseFilesFromTemp(
    context: Context,
    appDatabase: AppDatabase,
    tempDir: File,
) {
    val mainTemp = File(tempDir, "amber_agent")
    require(mainTemp.isFile) { "Backup archive is missing amber_agent.db" }

    val dbFile = context.getDatabasePath("amber_agent")
    val targets = listOf(
        File(tempDir, "amber_agent") to dbFile,
        File(tempDir, "amber_agent-wal") to File(dbFile.parentFile, "amber_agent-wal"),
        File(tempDir, "amber_agent-shm") to File(dbFile.parentFile, "amber_agent-shm"),
    )

    appDatabase.close()
    dbFile.parentFile?.mkdirs()
    targets.forEach { (_, target) ->
        if (target.exists()) {
            check(target.delete()) { "Failed to remove old database file: ${target.name}" }
        }
    }
    targets.forEach { (source, target) ->
        if (source.exists()) {
            source.copyTo(target, overwrite = true)
        }
    }
}
