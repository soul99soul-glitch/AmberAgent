package app.amber.feature.novelworkspace

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

data class NovelWorkspaceChapterVersion(
    val contentHash: String,
    val createdAt: Instant?,
    val message: String?,
    val isCurrent: Boolean,
)

data class NovelWorkspaceChapterHistorySnapshot(
    val headId: String?,
    val currentContentHash: String,
    val versions: List<NovelWorkspaceChapterVersion>,
)

/** Raw chapter versions retained locally; ledger ancestry determines which belong to a chapter. */
object NovelWorkspaceChapterHistory {
    private const val DIRECTORY_NAME = "history"
    private val hashPattern = Regex("[0-9a-f]{64}")

    internal fun isChapterPath(path: String): Boolean {
        val parts = path.split('/')
        return parts.size >= 4 && parts[0] == NovelWorkspacePaths.BRANCHES_DIR &&
            parts[2] == "chapters" && parts.last().endsWith(".md")
    }

    /** Save before the main file changes, so a history failure cannot overwrite the original. */
    internal fun capture(projectDirectory: File, content: String): String = NovelWorkspaceRestoreBoundary.write {
        val hash = sha256Hex(content)
        val destination = file(projectDirectory, hash)
        if (destination.isFile && destination.readText(Charsets.UTF_8) == content) return@write hash
        val directory = destination.parentFile
        if (!directory.exists() && !directory.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create chapter history directory: $directory")
        }
        val temp = File.createTempFile("novel-history-", ".tmp", directory)
        try {
            temp.writeText(content, Charsets.UTF_8)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            NovelWorkspaceLedger.atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
        return@write hash
    }

    /** Older projects can retain their current text, but missing historical text is never invented. */
    fun snapshot(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
    ): NovelWorkspaceChapterHistorySnapshot? = NovelWorkspaceRestoreBoundary.write {
        NovelWorkspacePaths.validate(chapterPath)
        require(isChapterPath(chapterPath) && chapterPath.startsWith("branches/$branchSlug/chapters/"))
        val store = NovelWorkspaceStore(projectDirectory)
        val current = store.read(chapterPath) ?: return@write null
        val currentHash = capture(projectDirectory, current)
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val headId = ledger.heads[branchId] ?: ledger.head.takeIf { ledger.heads.isEmpty() }
        val commits = ledger.commits.associateBy { it.id }
        val changes = ledger.ancestry(headId).asReversed().filter { commit ->
            commit.files[chapterPath] != commits[commit.parentId]?.files?.get(chapterPath)
        }
        val currentCommit = changes.firstOrNull { it.files[chapterPath] == currentHash }
        val versions = mutableListOf(
            NovelWorkspaceChapterVersion(
                currentHash,
                currentCommit?.createdAt,
                currentCommit?.message,
                isCurrent = true,
            ),
        )
        val seen = mutableSetOf(currentHash)
        for (commit in changes) {
            val hash = commit.files[chapterPath] ?: continue
            if (!seen.add(hash)) continue
            if (!hashPattern.matches(hash) || !file(projectDirectory, hash).isFile) continue
            versions.add(NovelWorkspaceChapterVersion(hash, commit.createdAt, commit.message, false))
        }
        return@write NovelWorkspaceChapterHistorySnapshot(headId, currentHash, versions)
    }

    /** A selected version is usable only while its content still matches its address. */
    fun read(projectDirectory: File, contentHash: String): String? {
        if (!hashPattern.matches(contentHash)) return null
        val source = file(projectDirectory, contentHash)
        if (!source.isFile) return null
        val content = source.readText(Charsets.UTF_8)
        return content.takeIf { sha256Hex(it) == contentHash }
    }

    private fun file(projectDirectory: File, contentHash: String): File =
        File(File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), DIRECTORY_NAME), "$contentHash.md")
}
