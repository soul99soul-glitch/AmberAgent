package me.rerere.rikkahub.data.agent.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

class WorkspaceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("amberagent_workspace", Context.MODE_PRIVATE)
    private val mirrorMutex = Mutex()
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    val mirrorDir get() = context.filesDir.resolve("amberagent/workspace-mirror")

    fun setWorkspace(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        _state.value = loadState()
    }

    fun clearWorkspace() {
        _state.value.treeUri?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
        _state.value = loadState()
    }

    suspend fun list(relativePath: String = "."): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val dir = requireDocument(relativePath)
        require(dir.isDirectory) { "Not a directory: $relativePath" }
        dir.listFiles()
            .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
            .map { file ->
                WorkspaceEntry(
                    path = joinPath(normalizePath(relativePath), file.name.orEmpty()),
                    name = file.name.orEmpty(),
                    directory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) null else file.length(),
                    mimeType = file.type
                )
            }
    }

    suspend fun readText(relativePath: String): String = withContext(Dispatchers.IO) {
        val file = requireDocument(relativePath)
        require(file.isFile) { "Not a file: $relativePath" }
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Unable to read file: $relativePath")
    }

    suspend fun readBytes(relativePath: String): ByteArray = withContext(Dispatchers.IO) {
        val file = requireDocument(relativePath)
        require(file.isFile) { "Not a file: $relativePath" }
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: error("Unable to read file: $relativePath")
    }

    suspend fun writeText(relativePath: String, content: String, append: Boolean = false): WorkspaceEntry =
        withContext(Dispatchers.IO) {
            val normalized = requireFilePath(relativePath)
            val file = requireOrCreateFile(normalized, textMimeTypeForPath(normalized))
            val mode = if (append) "wa" else "wt"
            context.contentResolver.openOutputStream(file.uri, mode)?.bufferedWriter()?.use {
                it.write(content)
            } ?: error("Unable to write file: $relativePath")
            WorkspaceEntry(
                path = normalized,
                name = file.name.orEmpty(),
                directory = false,
                sizeBytes = file.length(),
                mimeType = file.type
            )
        }

    suspend fun writeBytes(
        relativePath: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
        append: Boolean = false,
    ): WorkspaceEntry = withContext(Dispatchers.IO) {
        val normalized = requireFilePath(relativePath)
        val file = requireOrCreateFile(relativePath, mimeType)
        val mode = if (append) "wa" else "w"
        context.contentResolver.openOutputStream(file.uri, mode)?.use {
            it.write(bytes)
        } ?: error("Unable to write file: $relativePath")
        WorkspaceEntry(
            path = normalized,
            name = file.name.orEmpty(),
            directory = false,
            sizeBytes = file.length(),
            mimeType = file.type
        )
    }

    suspend fun editText(relativePath: String, oldText: String, newText: String, replaceAll: Boolean): EditResult =
        withContext(Dispatchers.IO) {
            require(oldText.isNotEmpty()) { "old_text must not be empty" }
            val current = readText(relativePath)
            val count = if (replaceAll) {
                current.split(oldText).size - 1
            } else if (current.contains(oldText)) {
                1
            } else {
                0
            }
            require(count > 0) { "Text not found in $relativePath" }
            val updated = if (replaceAll) current.replace(oldText, newText) else current.replaceFirst(oldText, newText)
            writeText(relativePath, updated)
            EditResult(path = normalizePath(relativePath), replaceCount = count)
        }

    suspend fun move(sourcePath: String, targetPath: String): WorkspaceEntry = withContext(Dispatchers.IO) {
        val sourceNormalized = normalizePath(sourcePath)
        val targetNormalized = normalizePath(targetPath)
        require(sourceNormalized != targetNormalized) { "Source and target are the same path: $sourceNormalized" }
        require(!targetNormalized.startsWith("$sourceNormalized/")) {
            "Moving a path into itself is not allowed: $sourceNormalized -> $targetNormalized"
        }
        require(findDocument(targetNormalized) == null) { "Target path already exists: $targetNormalized" }
        val source = requireDocument(sourcePath)
        val targetParent = requireOrCreateParent(targetPath)
        val targetName = targetNormalized.substringAfterLast("/")
        val sourceParent = sourceNormalized.substringBeforeLast("/", ".")
        val targetParentPath = targetNormalized.substringBeforeLast("/", ".")
        val renamed = if (sourceParent == targetParentPath) {
            val moved = source.renameTo(targetName)
            require(moved) { "Unable to rename $sourcePath to $targetPath" }
            targetParent.findFile(targetName) ?: source
        } else {
            val copied = copyDocument(source, targetParent, targetName)
            require(source.delete()) { "Copied but failed to delete original path: $sourcePath" }
            copied
        }
        WorkspaceEntry(
            path = targetNormalized,
            name = renamed.name.orEmpty(),
            directory = renamed.isDirectory,
            sizeBytes = if (renamed.isDirectory) null else renamed.length(),
            mimeType = renamed.type
        )
    }

    suspend fun search(query: String, relativePath: String = ".", maxResults: Int = 50): List<SearchResult> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "query is required" }
            val start = requireDocument(relativePath)
            val results = mutableListOf<SearchResult>()
            fun visit(file: DocumentFile, path: String) {
                if (results.size >= maxResults) return
                if (file.isDirectory) {
                    file.listFiles().forEach { child ->
                        visit(child, joinPath(path, child.name.orEmpty()))
                    }
                    return
                }
                val text = runCatching {
                    context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: return
                val line = text.lineSequence().withIndex().firstOrNull { it.value.contains(query, ignoreCase = true) }
                    ?: return
                results.add(
                    SearchResult(
                        path = path,
                        lineNumber = line.index + 1,
                        preview = line.value.take(240)
                    )
                )
            }
            visit(start, normalizePath(relativePath))
            results
        }

    suspend fun syncToMirror(): String = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            syncToMirrorLocked()
        }
    }

    suspend fun refreshMirrorFromWorkspace(): String = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            refreshMirrorFromWorkspaceLocked()
        }
    }

    suspend fun ensureMirrorWorkspace(): String = withContext(Dispatchers.IO) {
        val root = ensureMirrorRoot()
        require(root.mkdirs() || root.isDirectory) { "Unable to create workspace mirror: ${root.absolutePath}" }
        "Using POSIX mirror workspace at ${root.absolutePath}. SAF sync was not required for this terminal job."
    }

    /**
     * Stages a content URI (typically a file shared from the system Share menu) into the
     * POSIX mirror under `uploads/`, so the Agent's terminal tools can find it at
     * `/workspace/uploads/<displayName>` instead of the previously dead-end
     * `filesDir/upload/<uuid>` path.
     *
     * Filename is preserved so the user-visible name matches what `ls` reports. Collisions
     * append a `(1)`, `(2)` suffix before the extension. The mirror dir is created on
     * demand — works even when the SAF workspace has never been authorized; in that case
     * the file lives only in the private mirror, but the Agent still sees it via
     * `/workspace/uploads/`. (When SAF is configured, the next `withMirrorSync` round-trip
     * surfaces it back to the user-visible workspace too.)
     */
    suspend fun copyUriToUploads(sourceUri: Uri, displayName: String): Uri = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            val uploadsDir = mirrorDir.resolve("uploads").also { it.mkdirs() }
            val target = pickUniqueUploadFile(uploadsDir, displayName.ifBlank { "file" })
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open input stream for $sourceUri")
            Uri.fromFile(target)
        }
    }

    /**
     * Delete a single file under the workspace mirror by its relative path. Holds the
     * same `mirrorMutex` as `withMirrorSync` and `copyUriToUploads`, so a delete
     * triggered from the file sheet cannot race with an in-flight terminal-job sync
     * (which would otherwise either re-copy the file from SAF or leave a partial
     * write). Refuses paths that escape the mirror via `..`. Returns true only when an
     * actual file was removed.
     */
    suspend fun deleteWorkspaceFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        if (relativePath.isBlank()) return@withContext false
        mirrorMutex.withLock {
            val rootCanonical = mirrorDir.canonicalFile
            val target = mirrorDir.resolve(relativePath).canonicalFile
            if (!target.path.startsWith(rootCanonical.path + File.separator)) {
                return@withLock false
            }
            if (!target.exists() || !target.isFile) return@withLock false
            runCatching { target.delete() }.getOrDefault(false)
        }
    }

    private fun pickUniqueUploadFile(dir: File, name: String): File {
        val candidate = dir.resolve(name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val attempt = dir.resolve("$base ($index)$ext")
            if (!attempt.exists()) return attempt
            index++
        }
    }

    suspend fun syncFromMirror(): String = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            syncFromMirrorLocked()
        }
    }

    suspend fun <T> withMirrorSync(block: suspend (File) -> T): WorkspaceMirrorResult<T> = withContext(Dispatchers.IO) {
        mirrorMutex.withLock {
            val syncIn = syncToMirrorLocked()
            val value = block(mirrorDir)
            val syncOut = syncFromMirrorLocked()
            WorkspaceMirrorResult(
                value = value,
                syncNote = "$syncIn\n$syncOut"
            )
        }
    }

    private fun syncToMirrorLocked(): String {
        val root = requireRoot()
        val stats = MirrorSyncStats()
        resetMirror()
        root.listFiles().forEach { child ->
            copyDocumentToMirror(child, mirrorDir, stats)
        }
        return "Synced SAF workspace to POSIX mirror at ${mirrorDir.absolutePath}. " +
            stats.summary()
    }

    private fun refreshMirrorFromWorkspaceLocked(): String {
        val root = requireRoot()
        val stats = MirrorSyncStats()
        ensureMirrorRoot().mkdirs()
        root.listFiles().forEach { child ->
            copyDocumentToMirror(child, mirrorDir, stats)
        }
        return "Refreshed POSIX mirror from SAF workspace at ${mirrorDir.absolutePath}. " +
            "Existing mirror-only files were preserved. " + stats.summary()
    }

    private fun syncFromMirrorLocked(): String {
        val root = requireRoot()
        val stats = MirrorSyncStats()
        ensureMirrorRoot().mkdirs()
        mirrorDir.listFiles().orEmpty().forEach { child ->
            copyMirrorToDocument(child, root, stats)
        }
        return "Synced POSIX mirror back to SAF workspace. Policy: mirror files overwrite matching SAF files; " +
            "files deleted from mirror are not deleted from SAF in stage1. " + stats.summary()
    }

    private fun requireRoot(): DocumentFile {
        val uri = _state.value.treeUri ?: error("No AmberAgent workspace selected")
        return DocumentFile.fromTreeUri(context, uri) ?: error("Unable to open AmberAgent workspace")
    }

    private fun requireDocument(relativePath: String): DocumentFile {
        val normalized = normalizePath(relativePath)
        return findDocument(normalized) ?: error("Path not found: $normalized")
    }

    private fun findDocument(relativePath: String): DocumentFile? {
        val normalized = normalizePath(relativePath)
        if (normalized == ".") return requireRoot()
        return normalized.split("/")
            .filter { it.isNotBlank() }
            .fold(requireRoot() as DocumentFile?) { parent, segment ->
                parent?.findFile(segment)
            }
    }

    private fun requireOrCreateFile(
        relativePath: String,
        mimeType: String = "application/octet-stream",
    ): DocumentFile {
        val normalized = requireFilePath(relativePath)
        val parent = requireOrCreateParent(relativePath)
        val name = normalized.substringAfterLast("/")
        parent.findFile(name)?.let { return it }
        val created = parent.createFile(mimeType, name) ?: error("Unable to create $relativePath")
        if (created.name == name) return created

        val renamed = runCatching { created.renameTo(name) }.getOrDefault(false)
        if (renamed) {
            parent.findFile(name)?.let { return it }
        }
        error("Workspace provider created '${created.name.orEmpty()}' instead of '$name'")
    }

    private fun requireOrCreateParent(relativePath: String): DocumentFile {
        val normalized = normalizePath(relativePath)
        val parentPath = normalized.substringBeforeLast("/", ".")
        if (parentPath == "." || parentPath == normalized) return requireRoot()
        return parentPath.split("/")
            .filter { it.isNotBlank() }
            .fold(requireRoot()) { parent, segment ->
                parent.findFile(segment) ?: parent.createDirectory(segment)
                ?: error("Unable to create directory $segment")
            }
    }

    private fun copyDocument(source: DocumentFile, targetParent: DocumentFile, targetName: String): DocumentFile {
        if (source.isDirectory) {
            val targetDir = targetParent.createDirectory(targetName)
                ?: error("Unable to create directory $targetName")
            source.listFiles().forEach { child ->
                copyDocument(child, targetDir, child.name.orEmpty())
            }
            return targetDir
        }
        val target = targetParent.createFile(source.type ?: "application/octet-stream", targetName)
            ?: error("Unable to create file $targetName")
        val input = context.contentResolver.openInputStream(source.uri)
            ?: error("Unable to read ${source.name}")
        val output = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: error("Unable to write $targetName")
        input.use { sourceStream ->
            output.use { targetStream ->
                sourceStream.copyTo(targetStream)
            }
        }
        return target
    }

    private fun requireFilePath(path: String): String {
        val normalized = normalizePath(path)
        require(normalized != ".") { "A file path under /workspace is required: $path" }
        return normalized
    }

    private fun loadState(): WorkspaceState {
        val uri = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        val name = uri?.let { DocumentFile.fromTreeUri(context, it)?.name }
        return WorkspaceState(treeUri = uri, displayName = name)
    }

    private fun normalizePath(path: String): String = WorkspacePaths.normalize(path)

    private fun textMimeTypeForPath(path: String): String =
        when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "yaml", "yml" -> "application/yaml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "csv" -> "text/csv"
            "js", "mjs", "cjs" -> "text/javascript"
            "sh" -> "application/x-sh"
            else -> "text/plain"
        }

    private fun joinPath(parent: String, child: String): String = WorkspacePaths.join(parent, child)

    private fun resetMirror() {
        val root = ensureMirrorRoot()
        if (root.exists()) {
            require(root.deleteRecursively()) { "Unable to clear workspace mirror: ${root.absolutePath}" }
        }
        require(root.mkdirs() || root.isDirectory) { "Unable to create workspace mirror: ${root.absolutePath}" }
    }

    private fun ensureMirrorRoot(): File {
        val amberRoot = context.filesDir.resolve("amberagent").canonicalFile
        val root = mirrorDir.canonicalFile
        require(root.path.startsWith(amberRoot.path + File.separator)) {
            "Workspace mirror must stay under ${amberRoot.absolutePath}"
        }
        return root
    }

    private fun copyDocumentToMirror(source: DocumentFile, targetParent: File, stats: MirrorSyncStats) {
        val targetName = safeMirrorName(source.name ?: return stats.skip())
        val target = safeMirrorChild(targetParent, targetName)
        if (source.isDirectory) {
            if (target.exists() && !target.isDirectory) target.deleteRecursively()
            require(target.mkdirs() || target.isDirectory) { "Unable to create mirror directory: ${target.path}" }
            stats.directories++
            source.listFiles().forEach { child -> copyDocumentToMirror(child, target, stats) }
            return
        }
        if (!source.isFile) {
            stats.skip()
            return
        }
        if (target.exists() && target.isDirectory) {
            require(target.deleteRecursively()) { "Unable to replace mirror directory: ${target.path}" }
        }
        target.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(source.uri) ?: return stats.skip()
        input.use { sourceStream ->
            target.outputStream().use { targetStream ->
                stats.bytes += sourceStream.copyTo(targetStream)
            }
        }
        stats.files++
    }

    private fun copyMirrorToDocument(source: File, targetParent: DocumentFile, stats: MirrorSyncStats) {
        if (Files.isSymbolicLink(source.toPath())) {
            stats.skip()
            return
        }
        requireInsideMirror(source)
        val targetName = safeMirrorName(source.name)
        if (source.isDirectory) {
            val targetDir = ensureDocumentDirectory(targetParent, targetName)
            stats.directories++
            source.listFiles().orEmpty().forEach { child -> copyMirrorToDocument(child, targetDir, stats) }
            return
        }
        if (!source.isFile) {
            stats.skip()
            return
        }
        val target = ensureDocumentFile(targetParent, targetName)
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            source.inputStream().use { input ->
                stats.bytes += input.copyTo(output)
            }
        } ?: error("Unable to write workspace file: $targetName")
        stats.files++
    }

    private fun ensureDocumentDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing?.isDirectory == true) return existing
        existing?.delete()
        return parent.createDirectory(name) ?: error("Unable to create workspace directory: $name")
    }

    private fun ensureDocumentFile(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing?.isFile == true) return existing
        existing?.delete()
        return parent.createFile("application/octet-stream", name)
            ?: error("Unable to create workspace file: $name")
    }

    private fun safeMirrorChild(parent: File, name: String): File {
        val parentCanonical = parent.canonicalFile
        val child = File(parentCanonical, safeMirrorName(name)).canonicalFile
        require(child.path.startsWith(parentCanonical.path + File.separator)) {
            "Mirror path escaped parent: $name"
        }
        return child
    }

    private fun safeMirrorName(name: String): String {
        require(name.isNotBlank()) { "Workspace file name is blank" }
        require(name != "." && name != "..") { "Unsafe workspace file name: $name" }
        require(!name.contains("/") && !name.contains("\\")) { "Unsafe workspace file name: $name" }
        return name
    }

    private fun requireInsideMirror(file: File) {
        val root = ensureMirrorRoot()
        val filePath = file.canonicalPath
        require(filePath == root.canonicalPath || filePath.startsWith(root.canonicalPath + File.separator)) {
            "Mirror entry escaped workspace mirror: ${file.path}"
        }
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
    }
}

data class WorkspaceState(
    val treeUri: Uri?,
    val displayName: String?,
) {
    val configured: Boolean get() = treeUri != null
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val mimeType: String?,
)

data class EditResult(
    val path: String,
    val replaceCount: Int,
)

data class SearchResult(
    val path: String,
    val lineNumber: Int,
    val preview: String,
)

data class WorkspaceMirrorResult<T>(
    val value: T,
    val syncNote: String,
)

private class MirrorSyncStats {
    var files: Int = 0
    var directories: Int = 0
    var bytes: Long = 0
    var skipped: Int = 0

    fun skip() {
        skipped++
    }

    fun summary(): String =
        "files=$files, directories=$directories, bytes=$bytes, skipped=$skipped."
}
