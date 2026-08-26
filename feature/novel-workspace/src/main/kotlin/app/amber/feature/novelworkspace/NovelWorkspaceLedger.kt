package app.amber.feature.novelworkspace

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Thin-git ledger: an append-only commit chain plus branch head pointers.
 *
 * Wire shape mirrors iOS `NovelWorkspaceLedger` (`.amber/commits.json`): the host owns
 * commits/undo/branch pointers; the agent never sees this file.
 */
object NovelWorkspaceLedger {
    const val DIRECTORY_NAME = ".amber"
    const val STORE_FILE_NAME = "commits.json"

    /** Commit message conventions shared with iOS (NovelWorkspaceLedger.commitMessage). */
    object Message {
        const val INITIAL = "初始"
        const val COLLECTION = "收录"
        const val PLOT_POINTER = "剧情指针"
        const val DISCUSSION_ARCHIVE = "讨论归档"
        const val IDENTITY_CLARIFICATION = "人物说明"
        const val POLISH = "润色"
        const val RESTORE = "还原"
        const val GENERIC = "提交"
        /** Author manual edit (host-local label; the ledger never crosses platforms). */
        const val MANUAL_EDIT = "手改"
    }

    private val json = Json {
        // Defaults (null head / empty heads) are omitted to match Swift's encodeIfPresent.
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun treeSHA256(tree: Map<String, String>): String {
        val payload = tree.keys.sorted().joinToString("\n") { key ->
            "$key\t${tree.getValue(key)}"
        }
        return sha256Hex(payload)
    }

    fun makeCommit(
        id: String,
        parentId: String?,
        files: Map<String, String>,
        message: String,
        createdAt: Instant,
    ): NovelWorkspaceCommit = NovelWorkspaceCommit(
        id = id,
        parentId = parentId,
        createdAt = createdAt,
        message = message,
        treeSHA256 = treeSHA256(files),
        files = files,
    )

    /** Append (deduplicated by id) and advance head — mirrors iOS `appending(_:to:)`. */
    fun appending(commit: NovelWorkspaceCommit, to: NovelWorkspaceLedgerStore): NovelWorkspaceLedgerStore {
        val commits = if (to.commits.any { it.id == commit.id }) to.commits else to.commits + commit
        return to.copy(head = commit.id, commits = commits)
    }

    /** Working-tree status against a head commit: dirty paths, plot staleness, unresolved flag. */
    fun status(
        head: NovelWorkspaceCommit?,
        working: Map<String, String>,
        plotStale: Boolean,
        unresolved: Boolean,
    ): NovelWorkspaceStatus {
        val previous = head?.files.orEmpty()
        val dirty = buildSet {
            addAll(working.keys)
            addAll(previous.keys)
        }.filter { path -> working[path] != previous[path] }.sorted().toMutableList()
        if (plotStale && "plot/" !in dirty) {
            dirty.add("plot/")
            dirty.sort()
        }
        return NovelWorkspaceStatus(
            headID = head?.id,
            message = head?.message,
            dirtyPaths = dirty,
            plotStale = plotStale,
            unresolved = unresolved,
        )
    }

    fun load(directory: File): NovelWorkspaceLedgerStore {
        val file = File(File(directory, DIRECTORY_NAME), STORE_FILE_NAME)
        if (!file.exists()) return NovelWorkspaceLedgerStore()
        return try {
            json.decodeFromString(NovelWorkspaceLedgerStore.serializer(), file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            // Quarantine the unreadable ledger instead of silently starting over:
            // the next save would otherwise overwrite the only copy of the history.
            runCatching {
                file.renameTo(File(file.parentFile, "$STORE_FILE_NAME.corrupt-${System.currentTimeMillis()}"))
            }
            NovelWorkspaceLedgerStore()
        }
    }

    fun save(store: NovelWorkspaceLedgerStore, directory: File) {
        val ledgerDir = File(directory, DIRECTORY_NAME)
        if (!ledgerDir.exists() && !ledgerDir.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create ledger directory: $ledgerDir")
        }
        val destination = File(ledgerDir, STORE_FILE_NAME)
        val temp = File.createTempFile("novel-commits-", ".tmp", ledgerDir)
        try {
            temp.writeText(json.encodeToString(NovelWorkspaceLedgerStore.serializer(), store), Charsets.UTF_8)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            atomicMove(temp, destination)
        } finally {
            temp.delete()
        }
    }

    internal fun atomicMove(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Paths whose hash differs from the parent commit (or all paths for a root commit).
     * A commit's [NovelWorkspaceCommit.files] is a full tree snapshot, so "what this
     * commit changed" must be derived by diffing against the parent.
     */
    fun changedPaths(commit: NovelWorkspaceCommit, allCommits: List<NovelWorkspaceCommit>): Set<String> {
        val parentFiles = commit.parentId
            ?.let { pid -> allCommits.firstOrNull { it.id == pid } }
            ?.files
            .orEmpty()
        return commit.files.filter { (path, hash) -> parentFiles[path] != hash }.keys
    }

    /**
     * Cross-platform standard D-C (freshness): plot is stale when the manuscript moved
     * ahead of it — the newest commit that CHANGED chapters/ is newer than the newest
     * commit that CHANGED plot/. Derived from commit history, no extra state to sync.
     */
    fun isPlotStale(store: NovelWorkspaceLedgerStore, branchSlug: String): Boolean {
        val chapterPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters/"
        val plotPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/plot/"
        var lastChapterChange = -1
        var lastPlotChange = -1
        store.commits.forEachIndexed { index, commit ->
            val changed = changedPaths(commit, store.commits)
            if (changed.any { it.startsWith(chapterPrefix) }) lastChapterChange = index
            if (changed.any { it.startsWith(plotPrefix) }) lastPlotChange = index
        }
        return lastChapterChange >= 0 && lastChapterChange > lastPlotChange
    }

    /**
     * Cross-platform standard D-D (unresolved gate): a commit that edits a chapter which
     * is NOT the newest working chapter invalidates everything after it. Returns the
     * ordinal of the earliest affected chapter, or null when the edit is fast-forward-safe.
     * Working order is taken from the current chapters on disk.
     */
    fun firstUnresolvedOrdinalAfterEdit(
        store: NovelWorkspaceStore,
        ledger: NovelWorkspaceLedgerStore,
        branchSlug: String,
        commit: NovelWorkspaceCommit,
    ): Int? {
        val chapterPrefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters/"
        val edited = changedPaths(commit, ledger.commits)
            .filter { it.startsWith(chapterPrefix) }
            .mapNotNull { NovelWorkspacePaths.chapterOrdinalFromPath(it) }
        if (edited.isEmpty()) return null
        val newest = workingChapterOrdinals(store, branchSlug).maxOrNull() ?: return null
        // Editing any chapter older than the newest invalidates the chapters after it.
        val middleEdit = edited.minOrNull()?.takeIf { it < newest } ?: return null
        return middleEdit + 1
    }

    /** Ordinals of the chapters currently present in the branch, ascending. */
    fun workingChapterOrdinals(store: NovelWorkspaceStore, branchSlug: String): List<Int> {
        val prefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters"
        return store.list(prefix)
            .mapNotNull { NovelWorkspacePaths.chapterOrdinalFromPath(it) }
            .sorted()
    }

    /** Resolve branch metadata; legacy imports without branch.md are safe only with one head. */
    fun branchId(
        store: NovelWorkspaceStore,
        ledger: NovelWorkspaceLedgerStore,
        branchSlug: String,
    ): String? {
        val path = NovelWorkspacePaths.branchPrefix(branchSlug) + "/branch.md"
        val metadata = store.read(path)
            ?: return ledger.heads.keys.singleOrNull()
        val metadataId = NovelWorkspaceMarkdown.parseFile(metadata)
            .fields["id"]
            ?.takeIf { it.isNotBlank() }
        return metadataId?.takeIf { it in ledger.heads }
    }

    /** Chapter ordinals owned by the durable branch head, excluding dirty working files. */
    fun committedChapterOrdinals(
        store: NovelWorkspaceStore,
        ledger: NovelWorkspaceLedgerStore,
        branchSlug: String,
    ): List<Int> {
        val branchId = branchId(store, ledger, branchSlug) ?: return emptyList()
        val prefix = NovelWorkspacePaths.branchPrefix(branchSlug) + "/chapters/"
        return ledger.headOf(branchId)
            ?.files
            .orEmpty()
            .keys
            .asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull(NovelWorkspacePaths::chapterOrdinalFromPath)
            .sorted()
            .toList()
    }
}

@Serializable
data class NovelWorkspaceCommit(
    val id: String,
    @SerialName("parentID")
    val parentId: String? = null,
    @Serializable(with = NovelWorkspaceInstantSerializer::class)
    val createdAt: Instant,
    val message: String,
    val treeSHA256: String,
    val files: Map<String, String> = emptyMap(),
)

@Serializable
data class NovelWorkspaceLedgerStore(
    val head: String? = null,
    /** Branch id → commit id. Thin-git pointers; [head] mirrors the main branch. */
    val heads: Map<String, String> = emptyMap(),
    val commits: List<NovelWorkspaceCommit> = emptyList(),
) {
    val headCommit: NovelWorkspaceCommit?
        get() = commits.firstOrNull { it.id == head }

    fun commit(id: String?): NovelWorkspaceCommit? =
        if (id == null) null else commits.firstOrNull { it.id == id }

    fun headOf(branchId: String): NovelWorkspaceCommit? = commit(heads[branchId])

    /** Chain of commits from [commitId] back to the root, oldest first. */
    fun ancestry(commitId: String?): List<NovelWorkspaceCommit> {
        val byId = commits.associateBy { it.id }
        val chain = ArrayDeque<NovelWorkspaceCommit>()
        var current = commitId?.let(byId::get)
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            chain.addFirst(current)
            current = current.parentId?.let(byId::get)
        }
        return chain.toList()
    }
}

data class NovelWorkspaceStatus(
    val headID: String?,
    val message: String?,
    val dirtyPaths: List<String>,
    val plotStale: Boolean,
    val unresolved: Boolean,
)

class NovelWorkspaceIoError(message: String) : IllegalStateException(message)

/** Writes second-precision ISO8601 UTC (matching iOS); reads any Instant.parse shape. */
object NovelWorkspaceInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NovelWorkspaceInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(NovelWorkspaceTime.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return NovelWorkspaceTime.parse(raw)
            ?: throw IllegalStateException("Invalid workspace timestamp: $raw")
    }
}

fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
