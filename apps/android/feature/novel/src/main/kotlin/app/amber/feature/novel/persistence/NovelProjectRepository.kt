package app.amber.feature.novel.persistence

import app.amber.feature.novel.domain.NovelDocumentValidator
import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.domain.NovelLegacyForkMigration
import app.amber.feature.novel.model.NovelLoadedProject
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.novel.serialization.NovelSectionEncodeCache
import app.amber.feature.novel.serialization.NovelPackageCodec
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.NovelSwiftWireContract
import app.amber.feature.novel.serialization.encodeProjectDocumentCached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic file repository for novel projects.
 *
 * Layout (Android):
 * ```
 * root/
 *   index.json
 *   projects/<id>.json
 *   projects/<id>.previous.json
 *   recovery/<project-id>-<run-id>.json
 *   lifecycle/<project-id>-<operation-id>.json
 * ```
 */
interface NovelProjectPersisting {
    suspend fun listProjects(): List<NovelProjectSummary>
    suspend fun loadProject(id: NovelProjectId): NovelLoadedProject
    suspend fun createProject(document: NovelProjectDocumentV1): NovelLoadedProject
    suspend fun commitProject(
        document: NovelProjectDocumentV1,
        expectedRevision: Long,
    ): NovelLoadedProject

    suspend fun replaceProject(
        document: NovelProjectDocumentV1,
        expectedRevision: Long,
    ): NovelLoadedProject

    suspend fun deleteProject(id: NovelProjectId, expectedRevision: Long)
    suspend fun restorePrevious(
        id: NovelProjectId,
        expectedDocumentSHA256: String,
    ): NovelLoadedProject
}

class NovelFileProjectRepository(
    private val rootDirectory: File,
) : NovelProjectPersisting {
    private val mutex = Mutex()

    /**
     * Per-project in-memory encode cache: fingerprint -> canonical JSON per heavy
     * section, so unchanged append-mostly sections (injection receipts and friends)
     * are not re-encoded on every small commit. Process-lifetime only, no disk.
     */
    private val sectionCaches = mutableMapOf<NovelProjectId, NovelSectionEncodeCache>()

    private val projectsDir get() = File(rootDirectory, "projects")
    private val recoveryDir get() = File(rootDirectory, "recovery")
    private val lifecycleDir get() = File(rootDirectory, "lifecycle")
    private val indexFile get() = File(rootDirectory, "index.json")

    override suspend fun listProjects(): List<NovelProjectSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectories()
            val summaries = scanProjectSummaries()
            writeIndexBestEffort(summaries)
            summaries.sortedWith(
                compareByDescending<NovelProjectSummary> { it.updatedAt }
                    .thenBy { it.name.lowercase() },
            )
        }
    }

    override suspend fun loadProject(id: NovelProjectId): NovelLoadedProject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectories()
                val primary = primaryFile(id)
                val previous = previousFile(id)
                if (!primary.exists() && !previous.exists()) {
                    throw NovelError.ProjectNotFound(id)
                }
                try {
                    val document = readValidatedProject(primary, id)
                    NovelLoadedProject(document, NovelProjectLoadAccess.ReadWrite)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is NovelError.UnsupportedSchema) throw error
                    if (!previous.exists()) {
                        throw NovelError.CorruptedProject(id, error.message ?: "primary unreadable")
                    }
                    val document = readValidatedProject(previous, id)
                    NovelLoadedProject(
                        document = document,
                        access = NovelProjectLoadAccess.DegradedPrevious,
                        primaryFailure = error.message,
                    )
                }
            }
        }

    override suspend fun createProject(document: NovelProjectDocumentV1): NovelLoadedProject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectories()
                NovelDocumentValidator.validate(document)
                val projectId = document.project.id
                val destination = primaryFile(projectId)
                if (destination.exists()) {
                    throw NovelError.ProjectAlreadyExists(projectId)
                }
                val staged = stage(
                    document,
                    sectionCaches.getOrPut(projectId) { NovelSectionEncodeCache() },
                )
                try {
                    atomicMove(staged, destination)
                    val installed = readValidatedProject(destination, projectId)
                    // Installed document is wire-normalized (e.g. Date millis); trust validator + id.
                    if (installed.project.id != document.project.id ||
                        installed.project.revision != document.project.revision
                    ) {
                        throw NovelError.StorageIndeterminate(projectId)
                    }
                    refreshIndexBestEffort()
                    NovelLoadedProject(installed, NovelProjectLoadAccess.ReadWrite)
                } finally {
                    staged.delete()
                }
            }
        }

    override suspend fun commitProject(
        document: NovelProjectDocumentV1,
        expectedRevision: Long,
    ): NovelLoadedProject = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectories()
            val projectId = document.project.id
            val loaded = loadProjectUnlocked(projectId)
            if (loaded.access != NovelProjectLoadAccess.ReadWrite) {
                throw NovelError.DegradedReadOnly(projectId)
            }
            if (loaded.document.project.revision != expectedRevision) {
                throw NovelError.StaleProjectRevision(
                    expected = expectedRevision,
                    actual = loaded.document.project.revision,
                )
            }
            if (document.project.revision != expectedRevision + 1) {
                throw NovelError.InvalidDocument(
                    listOf("A commit must advance project revision exactly once."),
                )
            }
            NovelDocumentValidator.validateTransition(loaded.document, document)
            val destination = primaryFile(projectId)
            val previous = previousFile(projectId)
            val staged = stage(
                document,
                sectionCaches.getOrPut(projectId) { NovelSectionEncodeCache() },
            )
            try {
                if (destination.exists()) {
                    // Keep last good primary as previous before replacing, without ever
                    // exposing a half-written previous sidecar.
                    rotatePrimaryToPrevious(destination, previous, projectId)
                }
                atomicMove(staged, destination)
                val committed = readValidatedProject(destination, projectId)
                if (committed.project.id != document.project.id ||
                    committed.project.revision != document.project.revision
                ) {
                    throw NovelError.StorageIndeterminate(projectId)
                }
                refreshIndexBestEffort()
                NovelLoadedProject(committed, NovelProjectLoadAccess.ReadWrite)
            } finally {
                staged.delete()
            }
        }
    }

    override suspend fun replaceProject(
        document: NovelProjectDocumentV1,
        expectedRevision: Long,
    ): NovelLoadedProject = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectories()
            val projectId = document.project.id
            val loaded = loadProjectUnlocked(projectId)
            if (loaded.access != NovelProjectLoadAccess.ReadWrite) {
                throw NovelError.DegradedReadOnly(projectId)
            }
            if (loaded.document.project.revision != expectedRevision) {
                throw NovelError.StaleProjectRevision(
                    expected = expectedRevision,
                    actual = loaded.document.project.revision,
                )
            }
            val destination = primaryFile(projectId)
            val previous = previousFile(projectId)
            val staged = stage(
                document,
                sectionCaches.getOrPut(projectId) { NovelSectionEncodeCache() },
            )
            try {
                // Install the old primary as previous first. Both moves are replacements,
                // so the primary path is continuously present throughout the operation.
                rotatePrimaryToPrevious(destination, previous, projectId)
                atomicMove(staged, destination)
                val installed = readValidatedProject(destination, projectId)
                if (installed.project.id != document.project.id ||
                    installed.project.revision != document.project.revision
                ) {
                    throw NovelError.StorageIndeterminate(projectId)
                }
                refreshIndexBestEffort()
                NovelLoadedProject(installed, NovelProjectLoadAccess.ReadWrite)
            } finally {
                staged.delete()
            }
        }
    }

    override suspend fun deleteProject(id: NovelProjectId, expectedRevision: Long) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectories()
                val loaded = loadProjectUnlocked(id)
                if (loaded.document.project.revision != expectedRevision) {
                    throw NovelError.StaleProjectRevision(expectedRevision, loaded.document.project.revision)
                }
                // Delete previous first so a crash cannot resurrect the project from previous
                // after primary is already gone.
                previousFile(id).delete()
                primaryFile(id).delete()
                sectionCaches.remove(id)
                recoveryDir.listFiles()
                    ?.filter {
                        it.name.startsWith(id.rawValue.lowercase()) ||
                            it.name.startsWith(id.rawValue)
                    }
                    ?.forEach { it.delete() }
                refreshIndexBestEffort()
            }
        }

    override suspend fun restorePrevious(
        id: NovelProjectId,
        expectedDocumentSHA256: String,
    ): NovelLoadedProject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectories()
                val current = loadProjectUnlocked(id)
                if (current.access != NovelProjectLoadAccess.DegradedPrevious) {
                    throw NovelError.InvalidRecovery(
                        "Previous project restore requires a degraded primary.",
                    )
                }
                val previous = previousFile(id)
                if (!previous.exists()) {
                    throw NovelError.ProjectNotFound(id)
                }
                val previousDocument = readValidatedProject(previous, id)
                if (NovelPackageCodec.encode(previousDocument).projectSha256 != expectedDocumentSHA256) {
                    throw NovelError.StorageIndeterminate(id)
                }
                val destination = primaryFile(id)
                // The previous sidecar stays untouched; only a fully staged and validated
                // copy is atomically installed as the new primary.
                val restoredCache = NovelSectionEncodeCache()
                val staged = stage(previousDocument, restoredCache)
                try {
                    atomicMove(staged, destination)
                    val installed = readValidatedProject(destination, id)
                    if (NovelPackageCodec.encode(installed).projectSha256 != expectedDocumentSHA256) {
                        throw NovelError.StorageIndeterminate(id)
                    }
                    sectionCaches[id] = restoredCache
                    refreshIndexBestEffort()
                    NovelLoadedProject(installed, NovelProjectLoadAccess.ReadWrite)
                } finally {
                    staged.delete()
                }
            }
        }

    private fun loadProjectUnlocked(id: NovelProjectId): NovelLoadedProject {
        val primary = primaryFile(id)
        val previous = previousFile(id)
        if (!primary.exists() && !previous.exists()) {
            throw NovelError.ProjectNotFound(id)
        }
        return try {
            NovelLoadedProject(readValidatedProject(primary, id), NovelProjectLoadAccess.ReadWrite)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Match public loadProject: higher schema is not a "recover via previous" case.
            if (error is NovelError.UnsupportedSchema) throw error
            if (!previous.exists()) throw error
            NovelLoadedProject(
                document = readValidatedProject(previous, id),
                access = NovelProjectLoadAccess.DegradedPrevious,
                primaryFailure = error.message,
            )
        }
    }

    private fun ensureDirectories() {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw NovelError.StorageUnavailable("Cannot create novel root: ${rootDirectory.path}")
        }
        listOf(projectsDir, recoveryDir, lifecycleDir).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                throw NovelError.StorageUnavailable("Cannot create ${dir.path}")
            }
        }
    }

    private fun primaryFile(id: NovelProjectId): File =
        File(projectsDir, "${id.rawValue.lowercase()}.json")

    private fun previousFile(id: NovelProjectId): File =
        File(projectsDir, "${id.rawValue.lowercase()}.previous.json")

    private fun stage(document: NovelProjectDocumentV1, cache: NovelSectionEncodeCache): File {
        // Heavy sections whose fingerprint is unchanged reuse their cached canonical
        // JSON, so a small commit does not re-encode megabytes of receipts.
        val bytes = encodeProjectDocumentCached(document, cache).bytes
        if (bytes.size > NovelSwiftWireContract.MAX_PROJECT_BYTES) {
            throw NovelError.PackageTooLarge(NovelSwiftWireContract.MAX_PROJECT_BYTES)
        }
        val temp = File.createTempFile("novel-", ".tmp", projectsDir)
        RandomAccessFile(temp, "rw").use { raf ->
            raf.write(bytes)
            raf.fd.sync()
        }
        // Re-read validate before install. Compare via canonical bytes so wire-normalized
        // values (Date millis, key sort) are the source of truth. Note the actual reach of
        // this check: a cache-hit section is re-emitted from the same cached entry, so its
        // bytes necessarily match; the check is only effective for non-cached fields and
        // value-level instability (fingerprints recompute from decoded values). On-disk
        // correctness of cached sections is instead guaranteed by fingerprint
        // completeness — see the NovelEncodeCache fingerprint functions (M1 fix: every
        // mutating field, including section-kind payloads and force lists, is covered).
        val rereadBytes = temp.readBytes()
        val reread = NovelSwiftCompatibleJson.decodeProjectDocument(rereadBytes)
        NovelDocumentValidator.validate(reread)
        val restaged = encodeProjectDocumentCached(reread, cache).bytes
        if (!restaged.contentEquals(bytes)) {
            temp.delete()
            throw NovelError.RepositoryFailure("Staged document failed re-read equality check.")
        }
        return temp
    }

    private fun rotatePrimaryToPrevious(
        primary: File,
        previous: File,
        projectId: NovelProjectId,
    ) {
        val stagedPrevious = File.createTempFile("novel-previous-", ".tmp", projectsDir)
        try {
            Files.copy(
                primary.toPath(),
                stagedPrevious.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            RandomAccessFile(stagedPrevious, "rw").use { file -> file.fd.sync() }
            readValidatedProject(stagedPrevious, projectId)
            atomicMove(stagedPrevious, previous)
        } finally {
            stagedPrevious.delete()
        }
    }

    private fun atomicMove(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // Fallback for filesystems without atomic move.
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readValidatedProject(file: File, projectId: NovelProjectId): NovelProjectDocumentV1 {
        if (!file.exists()) {
            throw NovelError.ProjectNotFound(projectId)
        }
        if (file.length() > NovelSwiftWireContract.MAX_PROJECT_BYTES.toLong()) {
            throw NovelError.CorruptedProject(projectId, "project exceeds max size")
        }
        val bytes = file.readBytes()
        val decoded = try {
            NovelSwiftCompatibleJson.decodeProjectDocument(bytes)
        } catch (error: Exception) {
            throw NovelError.CorruptedProject(projectId, error.message ?: "decode failed")
        }
        val document = NovelLegacyForkMigration.migrate(decoded)
        if (document.project.id != projectId) {
            throw NovelError.CorruptedProject(projectId, "project id mismatch in file")
        }
        NovelDocumentValidator.validate(document)
        return document
    }

    private fun scanProjectSummaries(): List<NovelProjectSummary> {
        val files = projectsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.endsWith(".previous.json")
        } ?: emptyArray()
        return files.mapNotNull { file ->
            try {
                summaryOf(readValidatedProject(file, projectIdFromPrimaryName(file.name)), degraded = false)
            } catch (primaryError: Exception) {
                val previous = File(
                    projectsDir,
                    file.name.removeSuffix(".json") + ".previous.json",
                )
                if (!previous.exists()) return@mapNotNull null
                try {
                    val id = projectIdFromPrimaryName(file.name)
                    summaryOf(
                        readValidatedProject(previous, id),
                        degraded = true,
                        loadError = primaryError.message,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun projectIdFromPrimaryName(fileName: String): NovelProjectId =
        NovelProjectId.parse(fileName.removeSuffix(".json"))

    private fun summaryOf(
        document: NovelProjectDocumentV1,
        degraded: Boolean,
        loadError: String? = null,
    ): NovelProjectSummary = NovelProjectSummary(
        id = document.project.id,
        name = document.project.name,
        mainBranchID = document.project.mainBranchID,
        updatedAt = document.project.updatedAt,
        revision = document.project.revision,
        isDegraded = degraded,
        loadError = loadError,
    )

    private fun refreshIndexBestEffort() {
        try {
            writeIndexBestEffort(scanProjectSummaries())
        } catch (_: Exception) {
            // Index is reconstructible.
        }
    }

    private fun writeIndexBestEffort(summaries: List<NovelProjectSummary>) {
        try {
            val index = IndexV1(schemaVersion = 1, projects = summaries)
            val bytes = NovelSwiftCompatibleJson.json.encodeToString(IndexV1.serializer(), index)
                .toByteArray(Charsets.UTF_8)
            val temp = File.createTempFile("index-", ".tmp", rootDirectory)
            temp.writeBytes(bytes)
            atomicMove(temp, indexFile)
        } catch (_: Exception) {
            // best effort
        }
    }

    @Serializable
    private data class IndexV1(
        val schemaVersion: Int,
        val projects: List<NovelProjectSummary>,
    )

    companion object {
        fun defaultRoot(filesDir: File): File =
            File(filesDir, "amberagent/novel-creation")
    }
}
