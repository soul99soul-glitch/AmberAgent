package app.amber.feature.novel.persistence

import app.amber.feature.novel.domain.NovelDocumentValidator
import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.model.NovelLoadedProject
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.NovelSwiftWireContract
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    suspend fun deleteProject(id: NovelProjectId, expectedRevision: Long)
    suspend fun restorePrevious(id: NovelProjectId): NovelLoadedProject
}

class NovelFileProjectRepository(
    private val rootDirectory: File,
) : NovelProjectPersisting {
    private val mutex = Mutex()

    private val projectsDir get() = File(rootDirectory, "projects")
    private val recoveryDir get() = File(rootDirectory, "recovery")
    private val lifecycleDir get() = File(rootDirectory, "lifecycle")
    private val indexFile get() = File(rootDirectory, "index.json")

    override suspend fun listProjects(): List<NovelProjectSummary> = mutex.withLock {
        ensureDirectories()
        val summaries = scanProjectSummaries()
        writeIndexBestEffort(summaries)
        return@withLock summaries.sortedWith(
            compareByDescending<NovelProjectSummary> { it.updatedAt }
                .thenBy { it.name.lowercase() },
        )
    }

    override suspend fun loadProject(id: NovelProjectId): NovelLoadedProject = mutex.withLock {
        ensureDirectories()
        val primary = primaryFile(id)
        val previous = previousFile(id)
        if (!primary.exists() && !previous.exists()) {
            throw NovelError.ProjectNotFound(id)
        }
        return@withLock try {
            val document = readValidatedProject(primary, id)
            NovelLoadedProject(document, NovelProjectLoadAccess.ReadWrite)
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

    override suspend fun createProject(document: NovelProjectDocumentV1): NovelLoadedProject =
        mutex.withLock {
            ensureDirectories()
            NovelDocumentValidator.validate(document)
            val projectId = document.project.id
            val destination = primaryFile(projectId)
            if (destination.exists()) {
                throw NovelError.ProjectAlreadyExists(projectId)
            }
            val staged = stage(document)
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

    override suspend fun commitProject(
        document: NovelProjectDocumentV1,
        expectedRevision: Long,
    ): NovelLoadedProject = mutex.withLock {
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
            throw NovelError.InvalidDocument(listOf("A commit must advance project revision exactly once."))
        }
        NovelDocumentValidator.validateTransition(loaded.document, document)
        val destination = primaryFile(projectId)
        val previous = previousFile(projectId)
        val staged = stage(document)
        try {
            if (destination.exists()) {
                // Keep last good primary as previous before replacing.
                Files.copy(
                    destination.toPath(),
                    previous.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
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

    override suspend fun deleteProject(id: NovelProjectId, expectedRevision: Long) = mutex.withLock {
        ensureDirectories()
        val loaded = loadProjectUnlocked(id)
        if (loaded.document.project.revision != expectedRevision) {
            throw NovelError.StaleProjectRevision(expectedRevision, loaded.document.project.revision)
        }
        // Delete previous first so a crash cannot resurrect the project from previous
        // after primary is already gone.
        previousFile(id).delete()
        primaryFile(id).delete()
        recoveryDir.listFiles()
            ?.filter { it.name.startsWith(id.rawValue.lowercase()) || it.name.startsWith(id.rawValue) }
            ?.forEach { it.delete() }
        refreshIndexBestEffort()
    }

    override suspend fun restorePrevious(id: NovelProjectId): NovelLoadedProject = mutex.withLock {
        ensureDirectories()
        val previous = previousFile(id)
        if (!previous.exists()) {
            throw NovelError.ProjectNotFound(id)
        }
        val document = readValidatedProject(previous, id)
        val destination = primaryFile(id)
        Files.copy(previous.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val installed = readValidatedProject(destination, id)
        refreshIndexBestEffort()
        NovelLoadedProject(installed, NovelProjectLoadAccess.ReadWrite)
    }

    private fun loadProjectUnlocked(id: NovelProjectId): NovelLoadedProject {
        val primary = primaryFile(id)
        val previous = previousFile(id)
        if (!primary.exists() && !previous.exists()) {
            throw NovelError.ProjectNotFound(id)
        }
        return try {
            NovelLoadedProject(readValidatedProject(primary, id), NovelProjectLoadAccess.ReadWrite)
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

    private fun stage(document: NovelProjectDocumentV1): File {
        val bytes = NovelSwiftCompatibleJson.encodeProjectDocument(document)
        if (bytes.size > NovelSwiftWireContract.MAX_PROJECT_BYTES) {
            throw NovelError.PackageTooLarge(NovelSwiftWireContract.MAX_PROJECT_BYTES)
        }
        val temp = File.createTempFile("novel-", ".tmp", projectsDir)
        RandomAccessFile(temp, "rw").use { raf ->
            raf.write(bytes)
            raf.fd.sync()
        }
        // Re-read validate before install. Compare via canonical bytes so wire-normalized
        // values (Date millis, key sort) are the source of truth.
        val rereadBytes = temp.readBytes()
        val reread = NovelSwiftCompatibleJson.decodeProjectDocument(rereadBytes)
        NovelDocumentValidator.validate(reread)
        val restaged = NovelSwiftCompatibleJson.encodeProjectDocument(reread)
        if (!restaged.contentEquals(bytes)) {
            temp.delete()
            throw NovelError.RepositoryFailure("Staged document failed re-read equality check.")
        }
        return temp
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
        val bytes = file.readBytes()
        if (bytes.size > NovelSwiftWireContract.MAX_PROJECT_BYTES) {
            throw NovelError.CorruptedProject(projectId, "project exceeds max size")
        }
        val document = try {
            NovelSwiftCompatibleJson.decodeProjectDocument(bytes)
        } catch (error: Exception) {
            throw NovelError.CorruptedProject(projectId, error.message ?: "decode failed")
        }
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
