package app.amber.feature.novel

import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.persistence.NovelGhostwriteJobStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelGhostwriteBatchControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun start_existingPendingBindingAttachesWithoutReadingProjectOrCreatingDuplicate() = runTest {
        val store = newStore("attach")
        val job = newJob()
        store.createJob(job)
        val scheduling = RecordingScheduling(store)
        val controller = controller(
            store = store,
            scheduling = scheduling,
            projectSnapshotting = NovelGhostwriteBatchProjectSnapshotting {
                error("Existing binding must attach before reading the project")
            },
        )

        val result = controller.start(job.projectID, job.branchID, targetChapterCount = 50)

        assertEquals(job, (result as NovelGhostwriteBatchCommandResult.Attached).job)
        assertEquals(listOf(job.id), scheduling.started)
        assertEquals(1, store.listJobs().jobs.size)
    }

    @Test
    fun pause_commitsDurableFenceBeforeAwaitingWorkCancellation() = runTest {
        val store = newStore("pause-order")
        val job = newJob()
        store.createJob(job)
        val scheduling = RecordingScheduling(store)
        val controller = controller(store, scheduling)

        val result = controller.pause(job.id)

        val updated = result as NovelGhostwriteBatchCommandResult.Updated
        assertEquals(NovelGhostwriteJobStatus.Paused, updated.job.status)
        assertEquals("user_paused", updated.job.statusReasonCode)
        assertEquals(listOf(job.id), scheduling.cancelledAfterDurableFence)
        assertEquals(NovelGhostwriteJobStatus.Paused, store.loadJob(job.id).job.status)
    }

    @Test
    fun pause_cancelCleanupFailureKeepsDurablePauseAndReturnsWarning() = runTest {
        val store = newStore("pause-warning")
        val job = newJob()
        store.createJob(job)
        val scheduling = RecordingScheduling(store, failCancellation = true)
        val controller = controller(store, scheduling)

        val result = controller.pause(job.id) as NovelGhostwriteBatchCommandResult.Updated

        assertEquals(NovelGhostwriteJobStatus.Paused, result.job.status)
        assertTrue(result.warning.orEmpty().contains("批次状态已保存"))
        assertEquals(NovelGhostwriteJobStatus.Paused, store.loadJob(job.id).job.status)
    }

    @Test
    fun resume_qualityCircuitBreakerIsFailClosed() = runTest {
        val store = newStore("resume-blocked")
        val pending = newJob()
        store.createJob(pending)
        val paused = NovelGhostwriteJobReducer.pause(
            job = pending,
            expectedLedgerRevision = pending.ledgerRevision,
            expectedExecutionEpoch = pending.executionEpoch,
            reasonCode = "quality_circuit_breaker",
            now = BASE_TIME.plusSeconds(1),
        )
        store.commitJob(paused, pending.ledgerRevision, pending.executionEpoch)
        val scheduling = RecordingScheduling(store)
        val controller = controller(store, scheduling)

        val result = controller.resume(pending.id)

        val rejected = result as NovelGhostwriteBatchCommandResult.Rejected
        assertEquals(NovelGhostwriteBatchCommandFailure.InvalidState, rejected.reason)
        assertTrue(rejected.detail.orEmpty().contains("人工检查计划"))
        assertTrue(scheduling.resumed.isEmpty())
    }

    @Test
    fun projectSnapshot_scanFailureBlocksActivityQueriesUntilExplicitQuarantine() = runTest {
        val store = newStore("quarantine")
        val invalid = tempFolder.root
            .resolve("quarantine/lifecycle/ghostwrite/not-a-job.json")
        invalid.parentFile.mkdirs()
        invalid.writeText("{}")
        val controller = controller(store, RecordingScheduling(store))

        val blocked = controller.projectSnapshot(NovelProjectId.generate())
        assertTrue(blocked.isLedgerBlocked)
        assertEquals(1, blocked.scanFailures.size)

        val quarantined = controller.quarantineScanFailure(blocked.scanFailures.single().token)
        assertTrue(quarantined is NovelGhostwriteBatchCommandResult.Quarantined)
        assertFalse(controller.projectSnapshot(blocked.projectId).isLedgerBlocked)
    }

    @Test
    fun pauseReasonPolicy_unknownAndQualityFailuresCannotRestartLoop() {
        assertTrue(NovelGhostwriteBatchController.isResumablePauseReason("user_paused"))
        assertTrue(NovelGhostwriteBatchController.isResumablePauseReason("infra_retry_exhausted"))
        assertFalse(
            NovelGhostwriteBatchController.isResumablePauseReason("quality_circuit_breaker"),
        )
        assertFalse(NovelGhostwriteBatchController.isResumablePauseReason("future_unknown_reason"))
        assertFalse(NovelGhostwriteBatchController.isResumablePauseReason(null))
    }

    private fun controller(
        store: NovelGhostwriteJobStore,
        scheduling: NovelGhostwriteBatchScheduling,
        projectSnapshotting: NovelGhostwriteBatchProjectSnapshotting =
            NovelGhostwriteBatchProjectSnapshotting { null },
    ): NovelGhostwriteBatchController = NovelGhostwriteBatchController(
        projectSnapshotting = projectSnapshotting,
        store = store,
        scheduling = scheduling,
        canScheduleInForeground = { true },
    )

    private fun newStore(name: String): NovelGhostwriteJobStore =
        NovelGhostwriteJobStore(tempFolder.newFolder(name))

    private fun newJob(
        projectId: NovelProjectId = NovelProjectId.generate(),
        branchId: NovelBranchId = NovelBranchId.generate(),
    ): NovelGhostwriteJobV1 = NovelGhostwriteJobReducer.create(
        id = NovelGhostwriteJobId.generate(),
        projectID = projectId,
        branchID = branchId,
        targetChapterCount = 50,
        initialCursor = NovelGhostwriteChapterCursorV1(
            chapterIndex = 1,
            baseProjectRevision = 1,
            baseCheckpointID = NovelCheckpointId.generate(),
            baseHeadRevision = 0,
            baseStateSnapshotID = NovelStateSnapshotId.generate(),
            baseConfigRevision = 1,
        ),
        now = BASE_TIME,
    )

    private class RecordingScheduling(
        private val store: NovelGhostwriteJobStore,
        private val failCancellation: Boolean = false,
    ) : NovelGhostwriteBatchScheduling {
        val started = mutableListOf<NovelGhostwriteJobId>()
        val resumed = mutableListOf<NovelGhostwriteJobId>()
        val cancelledAfterDurableFence = mutableListOf<NovelGhostwriteJobId>()

        override suspend fun start(job: NovelGhostwriteJobV1) {
            started += job.id
        }

        override suspend fun resume(job: NovelGhostwriteJobV1) {
            resumed += job.id
        }

        override suspend fun cancel(jobId: NovelGhostwriteJobId) {
            val durable = store.loadJob(jobId).job
            assertTrue(
                durable.status == NovelGhostwriteJobStatus.Paused ||
                    durable.status == NovelGhostwriteJobStatus.Cancelled,
            )
            cancelledAfterDurableFence += jobId
            if (failCancellation) error("cancel await failed")
        }
    }

    companion object {
        private val BASE_TIME: Instant = Instant.parse("2026-08-09T00:00:00Z")
    }
}
