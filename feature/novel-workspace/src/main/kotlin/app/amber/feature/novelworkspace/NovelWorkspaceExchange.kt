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
    /** Same aggregate public-text budget as a v1 project payload, after inflation. */
    private const val MAX_WORKSPACE_BYTES = 100L * 1024 * 1024

    fun exportZip(projectDirectory: File, output: OutputStream) {
        val store = NovelWorkspaceStore(projectDirectory)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        ZipOutputStream(output).use { zip ->
            for (path in store.list()) {
                val content = store.read(path) ?: continue
                zip.putNextEntry(ZipEntry(path))
                zip.write(exportContent(path, content, store, ledger).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun exportContent(
        path: String,
        content: String,
        store: NovelWorkspaceStore,
        ledger: NovelWorkspaceLedgerStore,
    ): String {
        val segments = path.split('/')
        if (segments.size != 3 || segments[0] != NovelWorkspacePaths.BRANCHES_DIR ||
            segments[2] != "branch.md"
        ) return content
        val slug = segments[1]
        val id = NovelWorkspaceLedger.branchId(store, ledger, slug) ?: return content
        if (ledger.headOf(id) == null) return content
        val status = if (NovelWorkspaceLedger.isPlotStale(store, ledger, slug)) "needsSync" else "synchronized"
        if (NovelWorkspaceMarkdown.parseFile(content).fields["syncStatus"] == status) return content
        return NovelWorkspaceMarkdown.withFields(content, mapOf("syncStatus" to status))
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
        var totalBytes = 0L
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
                    val bytes = readEntry(zip, MAX_WORKSPACE_BYTES - totalBytes)
                    totalBytes += bytes.size
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

    private fun readEntry(input: InputStream, remainingBudget: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > remainingBudget) {
                throw NovelWorkspaceFormatError("Workspace text exceeds the 100 MB import limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Import from a zip stream into a fresh project directory (always a new project). */
    fun importZip(
        input: InputStream,
        projectDirectory: File,
    ): NovelWorkspaceInstaller.Result {
        val epoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val files = readZipFiles(input)
        return NovelWorkspaceRestoreBoundary.write(epoch) {
            NovelWorkspaceInstaller.install(files, projectDirectory)
        }
    }

    private fun isWorkspaceFile(path: String): Boolean {
        val segments = path.split('/')
        if (segments.any { it.startsWith(".") }) return false
        return path.endsWith(".md") || path.endsWith(".yaml")
    }
}
