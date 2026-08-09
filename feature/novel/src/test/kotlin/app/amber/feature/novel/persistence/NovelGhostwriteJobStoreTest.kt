package app.amber.feature.novel.persistence

import app.amber.feature.novel.domain.NovelGhostwriteJobError
import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelStateSnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class NovelGhostwriteJobStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun listFilesNull_isAStorageFailureInsteadOfAnEmptyScan() = runBlocking {
        val root = tempFolder.newFolder("ghostwrite-list-failure")
        val jobsPath = File(root, "lifecycle/ghostwrite")
        assertTrue(requireNotNull(jobsPath.parentFile).mkdirs())
        assertTrue(jobsPath.createNewFile())
        expectError<NovelGhostwriteJobError.StorageFailure> {
            runBlocking { NovelGhostwriteJobStore(root).listJobs() }
        }
        Unit
    }

    @Test
    fun storeUsesRevisionAndEpochCas_andReadsBackCanonicalLedger() = runBlocking {
        val root = tempFolder.newFolder("ghostwrite-cas")
        val store = NovelGhostwriteJobStore(root)
        val original = newJob()
        val created = store.createJob(original)
        assertEquals(NovelGhostwriteJobLoadAccess.ReadWrite, created.access)
        assertEquals(original, created.job)

        val claimed = NovelGhostwriteJobReducer.claimLease(
            original,
            expectedLedgerRevision = 0,
            expectedExecutionEpoch = 0,
            ownerWorkID = OWNER,
            leaseUntil = LEASE_UNTIL,
            now = BASE_TIME.plusSeconds(1),
        )
        val committed = store.commitJob(claimed, expectedLedgerRevision = 0, expectedExecutionEpoch = 0)
        assertEquals(1, committed.job.ledgerRevision)
        assertEquals(1, committed.job.executionEpoch)

        expectError<NovelGhostwriteJobError.StaleLedgerRevision> {
            runBlocking {
                store.commitJob(claimed, expectedLedgerRevision = 0, expectedExecutionEpoch = 0)
            }
        }

        val renewed = NovelGhostwriteJobReducer.renewLease(
            claimed,
            claimed.ledgerRevision,
            claimed.executionEpoch,
            OWNER,
            LEASE_UNTIL.plusSeconds(60),
            BASE_TIME.plusSeconds(2),
        )
        expectError<NovelGhostwriteJobError.StaleExecutionEpoch> {
            runBlocking {
                store.commitJob(
                    renewed,
                    expectedLedgerRevision = claimed.ledgerRevision,
                    expectedExecutionEpoch = 0,
                )
            }
        }
        val installed = store.commitJob(
            renewed,
            expectedLedgerRevision = claimed.ledgerRevision,
            expectedExecutionEpoch = claimed.executionEpoch,
        )
        assertEquals(renewed, installed.job)
    }

    @Test
    fun corruptPrimaryFallsBackToPrevious_andRestoreReturnsReadWrite() = runBlocking {
        val root = tempFolder.newFolder("ghostwrite-recovery")
        val store = NovelGhostwriteJobStore(root)
        val original = newJob()
        store.createJob(original)
        val claimed = NovelGhostwriteJobReducer.claimLease(
            original,
            original.ledgerRevision,
            original.executionEpoch,
            OWNER,
            LEASE_UNTIL,
            BASE_TIME.plusSeconds(1),
        )
        store.commitJob(claimed, original.ledgerRevision, original.executionEpoch)

        primaryFile(root, original).writeText("{not-json")
        val recovered = store.loadJob(original.id)
        assertEquals(NovelGhostwriteJobLoadAccess.DegradedPrevious, recovered.access)
        assertEquals(0, recovered.job.ledgerRevision)
        assertNotNull(recovered.primaryFailure)

        val reclaimed = NovelGhostwriteJobReducer.claimLease(
            recovered.job,
            recovered.job.ledgerRevision,
            recovered.job.executionEpoch,
            "worker-2",
            LEASE_UNTIL.plusSeconds(60),
            BASE_TIME.plusSeconds(2),
        )
        expectError<NovelGhostwriteJobError.DegradedReadOnly> {
            runBlocking {
                store.commitJob(
                    reclaimed,
                    recovered.job.ledgerRevision,
                    recovered.job.executionEpoch,
                )
            }
        }

        val restored = store.restorePrevious(original.id, BASE_TIME.plusSeconds(3))
        assertEquals(NovelGhostwriteJobLoadAccess.ReadWrite, restored.access)
        assertEquals(2, restored.job.ledgerRevision)
        assertEquals(2, restored.job.executionEpoch)
        assertEquals(app.amber.feature.novel.model.NovelGhostwriteJobStatus.Paused, restored.job.status)
        assertEquals("storage_recovered_previous", restored.job.statusReasonCode)
        assertNull(restored.job.leaseOwnerWorkID)
        assertEquals(NovelGhostwriteJobLoadAccess.ReadWrite, store.loadJob(original.id).access)
        expectError<NovelGhostwriteJobError.StorageFailure> {
            runBlocking { store.restorePrevious(original.id, BASE_TIME.plusSeconds(4)) }
        }
        Unit
    }

    @Test
    fun quarantineCorruptJob_preservesPrimaryAndPreviousAsOneAuditedSet() = runBlocking {
        val root = tempFolder.newFolder("ghostwrite-quarantine-pair")
        val store = NovelGhostwriteJobStore(root)
        val original = newJob()
        store.createJob(original)
        val claimed = NovelGhostwriteJobReducer.claimLease(
            original,
            original.ledgerRevision,
            original.executionEpoch,
            OWNER,
            LEASE_UNTIL,
            BASE_TIME.plusSeconds(1),
        )
        store.commitJob(claimed, original.ledgerRevision, original.executionEpoch)
        primaryFile(root, original).writeText("{broken")

        val failure = store.listJobs().failures.single()
        val record = store.quarantineScanFailure(failure.token, BASE_TIME.plusSeconds(2))
        assertEquals(2, record.originalFileNames.size)
        assertTrue(record.originalFileNames.any { it.endsWith(".previous.json") })
        assertTrue(record.quarantinedFileNames.all { name ->
            File(root, "lifecycle/ghostwrite/quarantine/$name").isFile
        })
        assertTrue(store.listJobs().jobs.isEmpty())
        assertTrue(store.listJobs().failures.isEmpty())
    }

    @Test
    fun bindingLookupIsUnique_scanFailuresAreVisible_andProjectDeleteCleansLedger() = runBlocking {
        val root = tempFolder.newFolder("ghostwrite-binding")
        val store = NovelGhostwriteJobStore(root)
        val projectID = NovelProjectId.generate()
        val branchID = NovelBranchId.generate()
        val job = newJob(projectID, branchID)
        store.createJob(job)

        assertEquals(job.id, store.loadActiveForBinding(projectID, branchID)?.job?.id)
        assertEquals(listOf(job.id), store.listJobs().jobs.map { it.job.id })
        expectError<NovelGhostwriteJobError.ActiveJobAlreadyExists> {
            runBlocking { store.createJob(newJob(projectID, branchID)) }
        }

        val invalid = File(root, "lifecycle/ghostwrite/not-a-uuid.json")
        invalid.writeText("{}")
        val scan = store.listJobs()
        assertEquals(1, scan.jobs.size)
        assertEquals(listOf("not-a-uuid.json"), scan.failures.map { it.fileName })
        expectError<NovelGhostwriteJobError.ScanFailed> {
            runBlocking { store.loadActiveForBinding(projectID, branchID) }
        }
        val token = scan.failures.single().token
        val quarantine = store.quarantineScanFailure(token, BASE_TIME.plusSeconds(10))
        assertEquals(listOf("not-a-uuid.json"), quarantine.originalFileNames)
        assertTrue(store.listJobs().failures.isEmpty())
        assertEquals(token, store.listQuarantineRecords().single().token)
        assertTrue(store.cleanupQuarantine(token) >= 2)
        assertTrue(store.listQuarantineRecords().isEmpty())

        assertEquals(1, store.deleteForProject(projectID))
        assertTrue(store.listJobs().jobs.isEmpty())
    }

    @Test
    fun nonCanonicalUuidFileName_canBeQuarantinedByItsActualScanToken() = runBlocking {
        val root = tempFolder.newFolder("ghostwrite-uppercase-name")
        val directory = File(root, "lifecycle/ghostwrite")
        assertTrue(directory.mkdirs())
        val id = NovelGhostwriteJobId.generate()
        val upperName = "${id.rawValue}.json"
        File(directory, upperName).writeText("{}")

        val store = NovelGhostwriteJobStore(root)
        val failure = store.listJobs().failures.single()
        assertEquals(upperName, failure.fileName)
        val record = store.quarantineScanFailure(failure.token, BASE_TIME.plusSeconds(20))
        assertEquals(listOf(upperName), record.originalFileNames)
        assertTrue(store.listJobs().failures.isEmpty())
    }

    @Test
    fun terminalProjectCleanup_isAtomicAndRefusesAnyActiveBatch() = runBlocking {
        val root = tempFolder.newFolder("ghostwrite-terminal-cleanup")
        val store = NovelGhostwriteJobStore(root)
        val projectID = NovelProjectId.generate()
        val terminal = newJob(projectID, NovelBranchId.generate())
        store.createJob(terminal)
        val cancelled = NovelGhostwriteJobReducer.cancel(
            terminal,
            terminal.ledgerRevision,
            terminal.executionEpoch,
            now = BASE_TIME.plusSeconds(1),
        )
        store.commitJob(cancelled, terminal.ledgerRevision, terminal.executionEpoch)

        val active = newJob(projectID, NovelBranchId.generate())
        store.createJob(active)
        expectError<NovelGhostwriteJobError.ActiveJobAlreadyExists> {
            runBlocking { store.deleteTerminalForProjectIfNoActive(projectID) }
        }
        assertEquals(setOf(cancelled.id, active.id), store.listJobs().jobs.map { it.job.id }.toSet())

        val stopped = NovelGhostwriteJobReducer.cancel(
            active,
            active.ledgerRevision,
            active.executionEpoch,
            now = BASE_TIME.plusSeconds(2),
        )
        store.commitJob(stopped, active.ledgerRevision, active.executionEpoch)
        assertEquals(2, store.deleteTerminalForProjectIfNoActive(projectID))
        assertTrue(store.listJobs().jobs.isEmpty())
    }

    private fun newJob(
        projectID: NovelProjectId = NovelProjectId.generate(),
        branchID: NovelBranchId = NovelBranchId.generate(),
    ): NovelGhostwriteJobV1 = NovelGhostwriteJobReducer.create(
        id = NovelGhostwriteJobId.generate(),
        projectID = projectID,
        branchID = branchID,
        targetChapterCount = 50,
        initialCursor = NovelGhostwriteChapterCursorV1(
            chapterIndex = 1,
            baseCheckpointID = NovelCheckpointId.generate(),
            baseHeadRevision = 0,
            baseStateSnapshotID = NovelStateSnapshotId.generate(),
            baseConfigRevision = 1,
        ),
        now = BASE_TIME,
    )

    private fun primaryFile(root: File, job: NovelGhostwriteJobV1): File =
        File(root, "lifecycle/ghostwrite/${job.id.rawValue.lowercase()}.json")

    private inline fun <reified T : Throwable> expectError(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            @Suppress("UNCHECKED_CAST")
            return error as T
        }
        fail("Expected ${T::class.java.name}")
        throw AssertionError("unreachable")
    }

    companion object {
        private val BASE_TIME: Instant = Instant.parse("2026-08-09T00:00:00Z")
        private val LEASE_UNTIL: Instant = BASE_TIME.plusSeconds(10_000)
        private const val OWNER = "worker-1"
    }
}
