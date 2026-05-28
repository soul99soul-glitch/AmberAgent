package app.amber.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveUtilsTest {
    @Test
    fun inspectBackupArchiveRejectsUploadZipSlip() {
        val zip = createZip(
            "settings.json" to "{}",
            "upload/../../databases/rikka_hub.db" to "owned",
        )

        val error = runCatching { inspectBackupArchive(zip) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun inspectBackupArchiveDetectsDatabasePayload() {
        val zip = createZip(
            "settings.json" to "{}",
            "rikka_hub.db" to "db",
            "rikka_hub-wal" to "wal",
        )

        val inspection = inspectBackupArchive(zip)

        assertTrue(inspection.hasDatabasePayload)
        assertTrue(inspection.hasMainDatabase)
    }

    @Test
    fun resolveArchiveChildKeepsPathsInsideParent() {
        val parent = Files.createTempDirectory("backup-root").toFile()

        val child = resolveArchiveChild(parent, "nested/file.md")

        assertEquals(
            File(parent, "nested/file.md").canonicalPath,
            child.canonicalPath,
        )
    }

    @Test
    fun resolveArchiveChildRejectsParentTraversal() {
        val parent = Files.createTempDirectory("backup-root").toFile()

        val error = runCatching { resolveArchiveChild(parent, "../outside.md") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun createZip(vararg entries: Pair<String, String>): File {
        val file = Files.createTempFile("backup", ".zip").toFile()
        ZipOutputStream(file.outputStream()).use { zipOut ->
            entries.forEach { (name, content) ->
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(content.toByteArray())
                zipOut.closeEntry()
            }
        }
        return file
    }
}
