package app.amber.feature.novel.persistence

import app.amber.feature.novel.domain.NovelGhostwriteJobError
import app.amber.feature.novel.domain.NovelGhostwriteJobValidator
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.serialization.NovelSwiftDateSerializer
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

enum class NovelGhostwriteJobLoadAccess {
    ReadWrite,
    DegradedPrevious,
}

data class NovelLoadedGhostwriteJob(
    val job: NovelGhostwriteJobV1,
    val access: NovelGhostwriteJobLoadAccess,
    val primaryFailure: String? = null,
)

data class NovelGhostwriteJobScanFailure(
    val token: String,
    val fileName: String,
    val detail: String,
)

data class NovelGhostwriteJobScanResult(
    val jobs: List<NovelLoadedGhostwriteJob>,
    val failures: List<NovelGhostwriteJobScanFailure>,
)

@Serializable
data class NovelGhostwriteJobQuarantineRecord(
    val token: String,
    val originalFileNames: List<String>,
    val quarantinedFileNames: List<String>,
    val details: List<String>,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val quarantinedAt: Instant,
)

/**
 * Durable CAS ledger for background ghostwrite jobs in the app's single WorkManager process.
 *
 * Files are independent of [app.amber.feature.novel.model.NovelProjectDocumentV1]:
 * `root/lifecycle/ghostwrite/<job-id>.json` plus one last-known-good `.previous.json`.
 * [mutex] serializes callers in this process; this store does not claim a cross-process file lock.
 */
class NovelGhostwriteJobStore(
    private val rootDirectory: File,
) {
    private val mutex = Mutex()

    private val jobsDirectory: File
        get() = File(rootDirectory, "lifecycle/ghostwrite")

    private val quarantineDirectory: File
        get() = File(jobsDirectory, "quarantine")

    suspend fun loadJob(id: NovelGhostwriteJobId): NovelLoadedGhostwriteJob = mutex.withLock {
        ensureDirectory()
        loadJobUnlocked(id)
    }

    suspend fun listJobs(): NovelGhostwriteJobScanResult = mutex.withLock {
        ensureDirectory()
        scanJobsUnlocked()
    }

    suspend fun loadActiveForBinding(
        projectID: NovelProjectId,
        branchID: NovelBranchId,
    ): NovelLoadedGhostwriteJob? = mutex.withLock {
        ensureDirectory()
        val scan = scanJobsUnlocked()
        requireCleanScan(scan)
        val active = scan.jobs.filter {
            it.job.projectID == projectID && it.job.branchID == branchID && !it.job.isTerminal
        }
        if (active.size > 1) {
            throw NovelGhostwriteJobError.DuplicateActiveBinding(
                projectID = projectID,
                branchID = branchID,
                jobIDs = active.map { it.job.id },
            )
        }
        active.singleOrNull()
    }

    suspend fun createJob(job: NovelGhostwriteJobV1): NovelLoadedGhostwriteJob = mutex.withLock {
        ensureDirectory()
        NovelGhostwriteJobValidator.validate(job)
        if (job.ledgerRevision != 0L || job.executionEpoch != 0L) {
            throw NovelGhostwriteJobError.InvalidJob(
                listOf("A new ghostwrite job must start at ledger revision and execution epoch zero."),
            )
        }
        val destination = primaryFile(job.id)
        if (destination.exists() || previousFile(job.id).exists()) {
            throw NovelGhostwriteJobError.JobAlreadyExists(job.id)
        }
        val scan = scanJobsUnlocked()
        requireCleanScan(scan)
        scan.jobs.firstOrNull {
            it.job.projectID == job.projectID && it.job.branchID == job.branchID && !it.job.isTerminal
        }?.let { existing ->
            throw NovelGhostwriteJobError.ActiveJobAlreadyExists(existing.job.id)
        }
        installStaged(job, destination)
    }

    suspend fun commitJob(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
    ): NovelLoadedGhostwriteJob = mutex.withLock {
        ensureDirectory()
        val loaded = loadJobUnlocked(job.id)
        if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
            throw NovelGhostwriteJobError.DegradedReadOnly(job.id)
        }
        val stored = loaded.job
        if (stored.ledgerRevision != expectedLedgerRevision) {
            throw NovelGhostwriteJobError.StaleLedgerRevision(
                expected = expectedLedgerRevision,
                actual = stored.ledgerRevision,
            )
        }
        if (stored.executionEpoch != expectedExecutionEpoch) {
            throw NovelGhostwriteJobError.StaleExecutionEpoch(
                expected = expectedExecutionEpoch,
                actual = stored.executionEpoch,
            )
        }
        NovelGhostwriteJobValidator.validateTransition(stored, job)

        val destination = primaryFile(job.id)
        val previous = previousFile(job.id)
        val staged = stage(job)
        try {
            if (destination.exists()) {
                preservePrevious(stored, previous)
            }
            atomicMove(staged, destination)
            readBackInstalled(job, destination)
        } finally {
            staged.delete()
        }
    }

    suspend fun deleteJob(
        id: NovelGhostwriteJobId,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
    ) = mutex.withLock {
        ensureDirectory()
        val loaded = loadJobUnlocked(id)
        if (loaded.job.ledgerRevision != expectedLedgerRevision) {
            throw NovelGhostwriteJobError.StaleLedgerRevision(
                expectedLedgerRevision,
                loaded.job.ledgerRevision,
            )
        }
        if (loaded.job.executionEpoch != expectedExecutionEpoch) {
            throw NovelGhostwriteJobError.StaleExecutionEpoch(
                expectedExecutionEpoch,
                loaded.job.executionEpoch,
            )
        }
        if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) {
            throw NovelGhostwriteJobError.DegradedReadOnly(id)
        }
        // Remove the fallback first so a crash cannot resurrect a deleted job.
        deleteChecked(previousFile(id))
        deleteChecked(primaryFile(id))
    }

    suspend fun restorePrevious(
        id: NovelGhostwriteJobId,
        now: Instant = Instant.now(),
    ): NovelLoadedGhostwriteJob = mutex.withLock {
        ensureDirectory()
        val loaded = loadJobUnlocked(id)
        if (loaded.access != NovelGhostwriteJobLoadAccess.DegradedPrevious) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Previous ledger restoration is only allowed from degraded read-only access.",
            )
        }
        val recovered = loaded.job
        if (recovered.ledgerRevision > Long.MAX_VALUE - RECOVERY_REVISION_GAP ||
            recovered.executionEpoch > Long.MAX_VALUE - RECOVERY_REVISION_GAP
        ) {
            throw NovelGhostwriteJobError.StorageFailure("Ghostwrite recovery counter overflow.")
        }
        val repaired = recovered.copy(
            status = if (recovered.isTerminal) recovered.status else
                app.amber.feature.novel.model.NovelGhostwriteJobStatus.Paused,
            ledgerRevision = recovered.ledgerRevision + RECOVERY_REVISION_GAP,
            executionEpoch = recovered.executionEpoch + RECOVERY_REVISION_GAP,
            leaseOwnerWorkID = null,
            leaseUntil = null,
            statusReasonCode = if (recovered.isTerminal) {
                recovered.statusReasonCode
            } else {
                "storage_recovered_previous"
            },
            updatedAt = maxOf(now, recovered.updatedAt),
        )
        installStaged(repaired, primaryFile(id))
    }

    suspend fun deleteForProject(projectID: NovelProjectId): Int = mutex.withLock {
        ensureDirectory()
        val scan = scanJobsUnlocked()
        requireCleanScan(scan)
        val matches = scan.jobs.filter { it.job.projectID == projectID }
        matches.forEach { loaded ->
            deleteChecked(previousFile(loaded.job.id))
            deleteChecked(primaryFile(loaded.job.id))
        }
        matches.size
    }

    /**
     * Deletes historical ledgers only when the project has no active batch.
     * The check and deletion share the store mutex so a concurrent create cannot
     * slip between an external preflight and the destructive file operation.
     */
    suspend fun deleteTerminalForProjectIfNoActive(projectID: NovelProjectId): Int = mutex.withLock {
        ensureDirectory()
        val scan = scanJobsUnlocked()
        requireCleanScan(scan)
        val matches = scan.jobs.filter { it.job.projectID == projectID }
        matches.firstOrNull { !it.job.isTerminal }?.let { active ->
            throw NovelGhostwriteJobError.ActiveJobAlreadyExists(active.job.id)
        }
        matches.forEach { loaded ->
            deleteChecked(previousFile(loaded.job.id))
            deleteChecked(primaryFile(loaded.job.id))
        }
        matches.size
    }

    suspend fun quarantineScanFailure(
        token: String,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobQuarantineRecord = mutex.withLock {
        ensureDirectory()
        ensureQuarantineDirectory()
        val scan = scanJobsUnlocked()
        val selected = scan.failures.firstOrNull { it.token == token }
            ?: throw NovelGhostwriteJobError.StorageFailure("Unknown ghostwrite scan-failure token.")
        val targets = quarantineTargets(selected)
        if (targets.isEmpty()) {
            throw NovelGhostwriteJobError.StorageFailure("The corrupt ledger files no longer exist.")
        }
        val prefix = "${now.toEpochMilli()}-${selected.token.take(16)}"
        val quarantined = targets.mapIndexed { index, source ->
            File(
                quarantineDirectory,
                "$prefix-$index-${source.name}.quarantined",
            )
        }
        quarantined.forEach { destination ->
            if (destination.exists()) {
                throw NovelGhostwriteJobError.StorageFailure("Quarantine destination already exists.")
            }
        }
        val relatedNames = targets.map { it.name }.toSet()
        val record = NovelGhostwriteJobQuarantineRecord(
            token = selected.token,
            originalFileNames = targets.map { it.name },
            quarantinedFileNames = quarantined.map { it.name },
            details = scan.failures.filter { it.fileName in relatedNames }.map { it.detail },
            quarantinedAt = now,
        )
        // Persist the audit record before moving sources so an interrupted multi-file
        // quarantine is still discoverable and can be cleaned up explicitly.
        installQuarantineRecord(record, "$prefix.record.json")
        targets.zip(quarantined).forEach { (source, destination) ->
            atomicMove(source, destination)
        }
        record
    }

    suspend fun listQuarantineRecords(): List<NovelGhostwriteJobQuarantineRecord> = mutex.withLock {
        ensureDirectory()
        if (!quarantineDirectory.exists()) return@withLock emptyList()
        listFilesChecked(quarantineDirectory)
            .filter { it.isFile && it.name.endsWith(".record.json") }
            .map { readQuarantineRecord(it) }
            .sortedBy { it.quarantinedAt }
    }

    suspend fun cleanupQuarantine(token: String): Int = mutex.withLock {
        ensureDirectory()
        if (!TOKEN_PATTERN.matches(token) || !quarantineDirectory.exists()) return@withLock 0
        val records = listFilesChecked(quarantineDirectory)
            .filter { it.isFile && it.name.endsWith(".record.json") }
            .map { it to readQuarantineRecord(it) }
            .filter { it.second.token == token }
        var deleted = 0
        records.forEach { (recordFile, record) ->
            record.quarantinedFileNames.forEach { name ->
                val data = safeQuarantineChild(name)
                if (data.exists()) {
                    deleteChecked(data)
                    deleted++
                }
            }
            deleteChecked(recordFile)
            deleted++
        }
        deleted
    }

    private fun scanJobsUnlocked(): NovelGhostwriteJobScanResult {
        val failures = mutableListOf<NovelGhostwriteJobScanFailure>()
        val ids = linkedSetOf<NovelGhostwriteJobId>()
        listFilesChecked(jobsDirectory)
            .filter { file ->
                file.isFile && (file.name.endsWith(".json") || file.name.endsWith(".previous.json"))
            }
            .forEach { file ->
                val raw = file.name
                    .removeSuffix(".previous.json")
                    .removeSuffix(".json")
                try {
                    val id = NovelGhostwriteJobId.parse(raw)
                    val expectedName = if (file.name.endsWith(".previous.json")) {
                        "${id.rawValue.lowercase()}.previous.json"
                    } else {
                        "${id.rawValue.lowercase()}.json"
                    }
                    if (file.name != expectedName) {
                        failures += scanFailure(
                            fileName = file.name,
                            detail = "job file name is not canonical lowercase UUID form",
                        )
                    } else {
                        ids += id
                    }
                } catch (error: Exception) {
                    failures += scanFailure(
                        fileName = file.name,
                        detail = error.message ?: "invalid job file name",
                    )
                }
            }
        val jobs = mutableListOf<NovelLoadedGhostwriteJob>()
        ids.forEach { id ->
            try {
                val loaded = loadJobUnlocked(id)
                jobs += loaded
                if (loaded.access == NovelGhostwriteJobLoadAccess.DegradedPrevious) {
                    failures += scanFailure(
                        fileName = primaryFile(id).name,
                        detail = loaded.primaryFailure ?: "primary ledger is unavailable",
                    )
                }
            } catch (error: Exception) {
                failures += scanFailure(
                    fileName = "${id.rawValue.lowercase()}.json",
                    detail = error.message ?: "job load failed",
                )
            }
        }
        val sortedJobs = jobs.sortedWith(
            compareBy<NovelLoadedGhostwriteJob> { it.job.createdAt }
                .thenBy { it.job.id.rawValue },
        )
        return NovelGhostwriteJobScanResult(sortedJobs, failures.distinctBy { it.token })
    }

    private fun requireCleanScan(scan: NovelGhostwriteJobScanResult) {
        if (scan.failures.isNotEmpty()) {
            throw NovelGhostwriteJobError.ScanFailed(
                scan.failures.map { "${it.fileName}: ${it.detail}" },
            )
        }
    }

    private fun loadJobUnlocked(id: NovelGhostwriteJobId): NovelLoadedGhostwriteJob {
        val primary = primaryFile(id)
        val previous = previousFile(id)
        if (!primary.exists() && !previous.exists()) {
            throw NovelGhostwriteJobError.JobNotFound(id)
        }
        return try {
            NovelLoadedGhostwriteJob(
                job = readValidated(primary, id),
                access = NovelGhostwriteJobLoadAccess.ReadWrite,
            )
        } catch (error: Exception) {
            if (error is NovelGhostwriteJobError.UnsupportedSchema) throw error
            if (!previous.exists()) {
                throw NovelGhostwriteJobError.CorruptedJob(
                    id,
                    error.message ?: "primary unreadable",
                )
            }
            NovelLoadedGhostwriteJob(
                job = readValidated(previous, id),
                access = NovelGhostwriteJobLoadAccess.DegradedPrevious,
                primaryFailure = error.message,
            )
        }
    }

    private fun installStaged(
        job: NovelGhostwriteJobV1,
        destination: File,
    ): NovelLoadedGhostwriteJob {
        val staged = stage(job)
        return try {
            atomicMove(staged, destination)
            readBackInstalled(job, destination)
        } finally {
            staged.delete()
        }
    }

    private fun readBackInstalled(
        expected: NovelGhostwriteJobV1,
        destination: File,
    ): NovelLoadedGhostwriteJob {
        val installed = readValidated(destination, expected.id)
        if (installed.id != expected.id ||
            installed.ledgerRevision != expected.ledgerRevision ||
            installed.executionEpoch != expected.executionEpoch
        ) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Installed ghostwrite job identity did not match the staged ledger.",
            )
        }
        return NovelLoadedGhostwriteJob(installed, NovelGhostwriteJobLoadAccess.ReadWrite)
    }

    private fun ensureDirectory() {
        if (!jobsDirectory.exists() && !jobsDirectory.mkdirs()) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Cannot create ghostwrite job directory: ${jobsDirectory.path}",
            )
        }
    }

    private fun ensureQuarantineDirectory() {
        if (!quarantineDirectory.exists() && !quarantineDirectory.mkdirs()) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Cannot create ghostwrite quarantine directory: ${quarantineDirectory.path}",
            )
        }
    }

    private fun listFilesChecked(directory: File): List<File> =
        directory.listFiles()?.toList()
            ?: throw NovelGhostwriteJobError.StorageFailure(
                "Cannot list ghostwrite directory: ${directory.path}",
            )

    private fun primaryFile(id: NovelGhostwriteJobId): File =
        File(jobsDirectory, "${id.rawValue.lowercase()}.json")

    private fun previousFile(id: NovelGhostwriteJobId): File =
        File(jobsDirectory, "${id.rawValue.lowercase()}.previous.json")

    private fun stage(job: NovelGhostwriteJobV1): File {
        val bytes = encode(job)
        if (bytes.size > MAX_JOB_BYTES) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Ghostwrite job exceeds the $MAX_JOB_BYTES byte limit.",
            )
        }
        val temp = try {
            File.createTempFile("ghostwrite-", ".tmp", jobsDirectory)
        } catch (error: Exception) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Cannot stage ghostwrite job: ${error.message}",
            )
        }
        try {
            RandomAccessFile(temp, "rw").use { file ->
                file.write(bytes)
                file.fd.sync()
            }
            val reread = readValidated(temp, job.id)
            if (!encode(reread).contentEquals(bytes)) {
                throw NovelGhostwriteJobError.StorageFailure(
                    "Staged ghostwrite job failed canonical read-back.",
                )
            }
            return temp
        } catch (error: Exception) {
            temp.delete()
            if (error is NovelGhostwriteJobError) throw error
            throw NovelGhostwriteJobError.StorageFailure(
                "Cannot stage ghostwrite job: ${error.message}",
            )
        }
    }

    private fun preservePrevious(job: NovelGhostwriteJobV1, destination: File) {
        val staged = stage(job)
        try {
            atomicMove(staged, destination)
            readValidated(destination, job.id)
        } finally {
            deleteQuietly(staged)
        }
    }

    private fun readValidated(file: File, id: NovelGhostwriteJobId): NovelGhostwriteJobV1 {
        if (!file.exists()) throw NovelGhostwriteJobError.JobNotFound(id)
        val bytes = try {
            file.readBytes()
        } catch (error: Exception) {
            throw NovelGhostwriteJobError.CorruptedJob(id, error.message ?: "read failed")
        }
        if (bytes.size > MAX_JOB_BYTES) {
            throw NovelGhostwriteJobError.CorruptedJob(id, "job exceeds maximum size")
        }
        val job = try {
            NovelSwiftCompatibleJson.json.decodeFromString<NovelGhostwriteJobV1>(
                bytes.toString(Charsets.UTF_8),
            )
        } catch (error: Exception) {
            throw NovelGhostwriteJobError.CorruptedJob(id, error.message ?: "decode failed")
        }
        if (job.id != id) {
            throw NovelGhostwriteJobError.CorruptedJob(id, "job id mismatch in file")
        }
        try {
            NovelGhostwriteJobValidator.validate(job)
        } catch (error: NovelGhostwriteJobError.UnsupportedSchema) {
            throw error
        } catch (error: NovelGhostwriteJobError.InvalidJob) {
            throw NovelGhostwriteJobError.CorruptedJob(id, error.message ?: "invalid job")
        }
        return job
    }

    private fun encode(job: NovelGhostwriteJobV1): ByteArray {
        NovelGhostwriteJobValidator.validate(job)
        val raw = NovelSwiftCompatibleJson.json.encodeToString(job)
        val element = NovelSwiftCompatibleJson.json.parseToJsonElement(raw)
        return NovelSwiftCompatibleJson.canonicalJson(element).toByteArray(Charsets.UTF_8)
    }

    private fun installQuarantineRecord(
        record: NovelGhostwriteJobQuarantineRecord,
        fileName: String,
    ) {
        val raw = NovelSwiftCompatibleJson.json.encodeToString(
            NovelGhostwriteJobQuarantineRecord.serializer(),
            record,
        )
        val element = NovelSwiftCompatibleJson.json.parseToJsonElement(raw)
        val bytes = NovelSwiftCompatibleJson.canonicalJson(element).toByteArray(Charsets.UTF_8)
        val staged = stageBytes(bytes, "ghostwrite-quarantine-")
        try {
            atomicMove(staged, File(quarantineDirectory, fileName))
        } finally {
            deleteQuietly(staged)
        }
    }

    private fun readQuarantineRecord(file: File): NovelGhostwriteJobQuarantineRecord = try {
        NovelSwiftCompatibleJson.json.decodeFromString(file.readText(Charsets.UTF_8))
    } catch (error: Exception) {
        throw NovelGhostwriteJobError.StorageFailure(
            "Cannot read quarantine record ${file.name}: ${error.message}",
        )
    }

    private fun stageBytes(bytes: ByteArray, prefix: String): File {
        val temp = try {
            File.createTempFile(prefix, ".tmp", quarantineDirectory)
        } catch (error: Exception) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Cannot stage quarantine record: ${error.message}",
            )
        }
        try {
            RandomAccessFile(temp, "rw").use { file ->
                file.write(bytes)
                file.fd.sync()
            }
            return temp
        } catch (error: Exception) {
            deleteQuietly(temp)
            throw NovelGhostwriteJobError.StorageFailure(
                "Cannot stage quarantine record: ${error.message}",
            )
        }
    }

    private fun scanFailure(fileName: String, detail: String): NovelGhostwriteJobScanFailure =
        NovelGhostwriteJobScanFailure(
            token = sha256HexOfUtf8(fileName),
            fileName = fileName,
            detail = detail,
        )

    private fun quarantineTargets(failure: NovelGhostwriteJobScanFailure): List<File> {
        val rawID = failure.fileName.removeSuffix(".previous.json").removeSuffix(".json")
        val id = runCatching { NovelGhostwriteJobId.parse(rawID) }.getOrNull()
        val candidates = buildList {
            add(File(jobsDirectory, failure.fileName))
            val canonicalPrimaryName = id?.let { primaryFile(it).name }
            val canonicalPreviousName = id?.let { previousFile(it).name }
            if (id != null &&
                failure.fileName in setOf(canonicalPrimaryName, canonicalPreviousName)
            ) {
                add(primaryFile(id))
                add(previousFile(id))
            }
        }
        return candidates
            .filter { file ->
                file.exists() && file.parentFile?.canonicalFile == jobsDirectory.canonicalFile
            }
            .distinctBy { it.canonicalPath }
    }

    private fun safeQuarantineChild(name: String): File {
        val child = File(quarantineDirectory, name)
        if (child.parentFile?.canonicalFile != quarantineDirectory.canonicalFile ||
            child.name != name
        ) {
            throw NovelGhostwriteJobError.StorageFailure("Invalid quarantine file name.")
        }
        return child
    }

    private fun deleteChecked(file: File) {
        if (file.exists() && !file.delete()) {
            throw NovelGhostwriteJobError.StorageFailure("Cannot delete ghostwrite file: ${file.path}")
        }
    }

    private fun deleteQuietly(file: File) {
        if (file.exists()) file.delete()
    }

    private fun atomicMove(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: Exception) {
            throw NovelGhostwriteJobError.StorageFailure(
                "Cannot atomically install ghostwrite file: ${error.message}",
            )
        }
    }

    companion object {
        const val MAX_JOB_BYTES: Int = 1_048_576
        private const val RECOVERY_REVISION_GAP: Long = 2
        private val TOKEN_PATTERN = Regex("[0-9a-f]{64}")

        fun defaultRoot(filesDir: File): File =
            NovelFileProjectRepository.defaultRoot(filesDir)
    }
}
