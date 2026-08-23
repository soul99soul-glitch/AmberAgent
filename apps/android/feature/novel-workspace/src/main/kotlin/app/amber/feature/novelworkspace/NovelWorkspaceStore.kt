package app.amber.feature.novelworkspace

import java.io.File
import java.io.RandomAccessFile

/**
 * Filesystem access to one project's book (the agent-visible markdown tree).
 *
 * Layout: the book lives at [rootDirectory] itself (manifest.yaml at top level, as iOS
 * exports it); everything host-private hides under `.amber/` so collectors skip it.
 * Only `.md`/`.yaml` files participate in the tree, mirroring iOS collect rules.
 */
class NovelWorkspaceStore(val rootDirectory: File) {

    val ledgerDirectory: File get() = File(rootDirectory, NovelWorkspaceLedger.DIRECTORY_NAME)
    val checkoutDirectory: File get() = File(ledgerDirectory, "checkout")

    fun exists(): Boolean = File(rootDirectory, NovelWorkspacePaths.MANIFEST).exists()

    /** Sorted tree-relative paths under [prefix] (whole tree when null). */
    fun list(prefix: String? = null): List<String> {
        if (!rootDirectory.exists()) return emptyList()
        val normalized = prefix?.trim('/')?.ifEmpty { null }
        if (normalized != null) NovelWorkspacePaths.validate(normalized)
        val results = mutableListOf<String>()
        collect(rootDirectory, "", results)
        return results
            .filter { normalized == null || it.startsWith("$normalized/") || it == normalized }
            .sorted()
    }

    fun exists(path: String): Boolean {
        NovelWorkspacePaths.validate(path)
        return resolve(path).isFile
    }

    fun read(path: String): String? {
        NovelWorkspacePaths.validate(path)
        val file = resolve(path)
        if (!file.isFile) return null
        return file.readText(Charsets.UTF_8)
    }

    /** Atomic write (temp + fsync + rename); parent directories are created. */
    fun write(path: String, content: String) {
        NovelWorkspacePaths.validate(path)
        val file = resolve(path)
        val parent = file.parentFile
            ?: throw NovelWorkspaceIoError("Cannot resolve parent of $path")
        if (!parent.exists() && !parent.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create directory: $parent")
        }
        val temp = File.createTempFile("novel-workspace-", ".tmp", parent)
        try {
            temp.writeText(content, Charsets.UTF_8)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, file)
        } finally {
            temp.delete()
        }
    }

    fun delete(path: String): Boolean {
        NovelWorkspacePaths.validate(path)
        return resolve(path).delete()
    }

    /**
     * path → sha256(content) for every book file, manifest excluded —
     * the exact input of [NovelWorkspaceLedger.treeSHA256].
     */
    fun fileTree(): Map<String, String> {
        val tree = mutableMapOf<String, String>()
        for (path in list()) {
            if (path == NovelWorkspacePaths.MANIFEST) continue
            val content = read(path) ?: continue
            tree[path] = sha256Hex(content)
        }
        return tree
    }

    /** Paths whose content no longer matches the recorded hash, plus recorded-but-missing files. */
    fun verify(files: Map<String, String>): List<String> = files.keys.sorted().filter { path ->
        val content = read(path)
        content == null || sha256Hex(content) != files.getValue(path)
    }

    /** Refresh the author-view copy under `.amber/checkout/` from the book. */
    fun materializeCheckout() {
        val target = checkoutDirectory
        if (target.exists()) target.deleteRecursively()
        if (!target.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create checkout directory: $target")
        }
        for (path in list()) {
            val content = read(path) ?: continue
            val destination = File(target, path)
            destination.parentFile?.mkdirs()
            destination.writeText(content, Charsets.UTF_8)
        }
    }

    private fun collect(directory: File, relative: String, results: MutableList<String>) {
        val children = directory.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            if (child.isDirectory) {
                collect(child, childRelative, results)
            } else if (child.extension == "md" || child.extension == "yaml") {
                results.add(childRelative)
            }
        }
    }

    private fun resolve(path: String): File {
        val file = File(rootDirectory, path)
        val rootPath = rootDirectory.absoluteFile.normalize().path
        val filePath = file.absoluteFile.normalize().path
        if (!filePath.startsWith("$rootPath${File.separator}")) {
            throw NovelWorkspaceIoError("Workspace path escapes the tree: $path")
        }
        return file
    }
}
