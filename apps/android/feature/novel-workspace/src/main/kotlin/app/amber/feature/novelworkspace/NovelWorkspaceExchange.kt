package app.amber.feature.novelworkspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Cross-platform exchange: a zip of the book only (manifest at zip root).
 * The ledger is host-local and never travels — backups keep the book, not history.
 */
object NovelWorkspaceExchange {

    fun exportZip(projectDirectory: File, output: OutputStream) {
        val store = NovelWorkspaceStore(projectDirectory)
        ZipOutputStream(output).use { zip ->
            for (path in store.list()) {
                val content = store.read(path) ?: continue
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun exportZipBytes(projectDirectory: File): ByteArray {
        val buffer = ByteArrayOutputStream()
        exportZip(projectDirectory, buffer)
        return buffer.toByteArray()
    }

    /** Read a workspace zip; rejects non-UTF-8 payloads and hidden entries. */
    fun readZipFiles(input: InputStream): List<NovelWorkspaceFile> {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val files = mutableListOf<NovelWorkspaceFile>()
        val seen = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val path = entry.name.trim('/')
                if (!entry.isDirectory && path.isNotEmpty() && isWorkspaceFile(path)) {
                    // Duplicate entries are rejected outright, matching iOS's
                    // Dictionary(uniqueKeysWithValues:) behavior for malformed zips.
                    if (!seen.add(path)) {
                        throw NovelWorkspaceFormatError("Workspace zip has duplicate entries: $path")
                    }
                    val bytes = zip.readBytes()
                    val content = try {
                        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    } catch (error: CharacterCodingException) {
                        throw NovelWorkspaceFormatError("Workspace file is not valid UTF-8: $path")
                    }
                    files.add(NovelWorkspaceFile(path, content))
                }
                entry = zip.nextEntry
            }
        }
        return files
    }

    /** Import from a zip stream into a fresh project directory (always a new project). */
    fun importZip(
        input: InputStream,
        projectDirectory: File,
    ): NovelWorkspaceInstaller.Result {
        val files = readZipFiles(input)
        return NovelWorkspaceInstaller.install(files, projectDirectory)
    }

    private fun isWorkspaceFile(path: String): Boolean {
        val segments = path.split('/')
        if (segments.any { it.startsWith(".") }) return false
        return path.endsWith(".md") || path.endsWith(".yaml")
    }
}
