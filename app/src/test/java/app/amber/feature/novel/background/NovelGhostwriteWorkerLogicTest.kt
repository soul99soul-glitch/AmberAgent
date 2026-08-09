package app.amber.feature.novel.background

import androidx.work.ExistingWorkPolicy
import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelStateSnapshotId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelGhostwriteWorkerLogicTest {
    @Test
    fun `unique work ownership is stable per durable job`() {
        val firstJobId = NovelGhostwriteJobId.generate()
        val secondJobId = NovelGhostwriteJobId.generate()

        val firstName = NovelGhostwriteBatchScheduler.uniqueWorkName(firstJobId)

        assertEquals(firstName, NovelGhostwriteBatchScheduler.uniqueWorkName(firstJobId))
        assertNotEquals(firstName, NovelGhostwriteBatchScheduler.uniqueWorkName(secondJobId))
    }

    @Test
    fun `work request persists the scheduled execution epoch`() {
        val job = newJob(target = 3)

        val input = NovelGhostwriteBatchScheduler.workInputData(job)

        assertEquals(ExistingWorkPolicy.KEEP, NovelGhostwriteBatchScheduler.startPolicy(job))
        assertEquals(job.id.toString(), input.getString(NovelGhostwriteWorker.KEY_JOB_ID))
        assertEquals(
            job.executionEpoch,
            input.getLong(NovelGhostwriteWorker.KEY_SCHEDULED_EXECUTION_EPOCH, -1),
        )
    }

    @Test
    fun `resume appends after unwinding work and fences stale queued requests`() {
        val pending = newJob(target = 3)
        val oldWorkId = "old-work"
        val running = NovelGhostwriteJobReducer.claimLease(
            job = pending,
            expectedLedgerRevision = pending.ledgerRevision,
            expectedExecutionEpoch = pending.executionEpoch,
            ownerWorkID = oldWorkId,
            leaseUntil = Instant.parse("2026-08-09T01:00:00Z"),
            now = Instant.parse("2026-08-09T00:01:00Z"),
        )
        val paused = NovelGhostwriteJobReducer.pause(
            job = running,
            expectedLedgerRevision = running.ledgerRevision,
            expectedExecutionEpoch = running.executionEpoch,
            reasonCode = "user_paused",
            now = Instant.parse("2026-08-09T00:02:00Z"),
        )
        val resumeInput = NovelGhostwriteBatchScheduler.workInputData(paused)

        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            NovelGhostwriteBatchScheduler.resumePolicy(paused),
        )
        assertEquals(
            paused.executionEpoch,
            resumeInput.getLong(NovelGhostwriteWorker.KEY_SCHEDULED_EXECUTION_EPOCH, -1),
        )
        assertFalse(
            NovelGhostwriteWorker.isEligibleWork(
                job = paused,
                workId = oldWorkId,
                scheduledExecutionEpoch = pending.executionEpoch,
            ),
        )
        assertTrue(
            NovelGhostwriteWorker.isEligibleWork(
                job = paused,
                workId = "first-resume-work",
                scheduledExecutionEpoch = paused.executionEpoch,
            ),
        )
        val firstResumeRunning = NovelGhostwriteJobReducer.claimLease(
            job = paused,
            expectedLedgerRevision = paused.ledgerRevision,
            expectedExecutionEpoch = paused.executionEpoch,
            ownerWorkID = "first-resume-work",
            leaseUntil = Instant.parse("2026-08-09T02:00:00Z"),
            now = Instant.parse("2026-08-09T00:03:00Z"),
        )
        assertFalse(
            NovelGhostwriteWorker.isEligibleWork(
                job = firstResumeRunning,
                workId = "second-resume-work",
                scheduledExecutionEpoch = paused.executionEpoch,
            ),
        )
    }

    @Test
    fun `old request cannot enter a newer execution epoch or another owner lease`() {
        val scheduledEpoch = 4L
        val oldWorkId = "old-work"
        val newWorkId = "new-work"
        val base = newJob(target = 3)

        assertTrue(
            NovelGhostwriteWorker.isEligibleWork(
                job = base.copy(executionEpoch = scheduledEpoch),
                workId = oldWorkId,
                scheduledExecutionEpoch = scheduledEpoch,
            ),
        )
        assertTrue(
            NovelGhostwriteWorker.isEligibleWork(
                job = base.copy(
                    executionEpoch = scheduledEpoch + 1,
                    leaseOwnerWorkID = oldWorkId,
                    leaseUntil = Instant.parse("2026-08-09T01:00:00Z"),
                ),
                workId = oldWorkId,
                scheduledExecutionEpoch = scheduledEpoch,
            ),
        )
        assertFalse(
            NovelGhostwriteWorker.isEligibleWork(
                job = base.copy(
                    executionEpoch = scheduledEpoch + 1,
                    leaseOwnerWorkID = newWorkId,
                    leaseUntil = Instant.parse("2026-08-09T01:00:00Z"),
                ),
                workId = oldWorkId,
                scheduledExecutionEpoch = scheduledEpoch,
            ),
        )
        assertFalse(
            NovelGhostwriteWorker.isEligibleWork(
                job = base.copy(
                    executionEpoch = scheduledEpoch,
                    leaseOwnerWorkID = newWorkId,
                    leaseUntil = Instant.parse("2026-08-09T00:00:01Z"),
                ),
                workId = oldWorkId,
                scheduledExecutionEpoch = scheduledEpoch,
            ),
        )
        assertFalse(
            NovelGhostwriteWorker.isEligibleWork(
                job = base.copy(executionEpoch = scheduledEpoch + 2),
                workId = oldWorkId,
                scheduledExecutionEpoch = scheduledEpoch,
            ),
        )
    }

    @Test
    fun `notification route exists only for an exact prepared run`() {
        val withoutRun = newJob(target = 3)
        val runId = NovelRunId.generate()
        val withRun = withoutRun.copy(currentCursor = withoutRun.currentCursor.copy(runID = runId))

        assertNull(NovelGhostwriteWorker.notificationRouteIdentity(withoutRun))
        assertEquals(
            NotificationRouteIdentity(withoutRun.projectID.toString(), runId.toString()),
            NovelGhostwriteWorker.notificationRouteIdentity(withRun),
        )
    }

    @Test
    fun `durable ledger is authoritative for every executor result`() {
        val workId = "current-work"
        val scheduledEpoch = 4L
        val base = newJob(target = 3)
        val ownedRunning = base.copy(
            status = NovelGhostwriteJobStatus.Running,
            executionEpoch = scheduledEpoch + 1,
            leaseOwnerWorkID = workId,
            leaseUntil = Instant.parse("2026-08-09T01:00:00Z"),
        )
        val results = listOf(
            BatchRunResult.Completed,
            BatchRunResult.Paused,
            BatchRunResult.Retry,
            BatchRunResult.Failed,
        )

        assertEquals(
            BatchResultDisposition.Retry,
            NovelGhostwriteWorker.reconcileBatchResult(
                BatchRunResult.Retry,
                ownedRunning,
                workId,
                scheduledEpoch,
            ),
        )
        listOf(BatchRunResult.Completed, BatchRunResult.Paused, BatchRunResult.Failed).forEach { result ->
            assertEquals(
                BatchResultDisposition.PauseCurrent,
                NovelGhostwriteWorker.reconcileBatchResult(result, ownedRunning, workId, scheduledEpoch),
            )
        }
        results.forEach { result ->
            assertEquals(
                BatchResultDisposition.Success,
                NovelGhostwriteWorker.reconcileBatchResult(
                    result,
                    ownedRunning.copy(
                        status = NovelGhostwriteJobStatus.Paused,
                        leaseOwnerWorkID = null,
                        leaseUntil = null,
                    ),
                    workId,
                    scheduledEpoch,
                ),
            )
            TERMINAL_JOB_STATUSES.forEach { terminalStatus ->
                assertEquals(
                    BatchResultDisposition.Success,
                    NovelGhostwriteWorker.reconcileBatchResult(
                        result,
                        ownedRunning.copy(
                            status = terminalStatus,
                            leaseOwnerWorkID = null,
                            leaseUntil = null,
                        ),
                        workId,
                        scheduledEpoch,
                    ),
                )
            }
        }
        results.forEach { result ->
            assertEquals(
                BatchResultDisposition.Success,
                NovelGhostwriteWorker.reconcileBatchResult(
                    result,
                    ownedRunning.copy(
                        executionEpoch = scheduledEpoch + 2,
                        leaseOwnerWorkID = "new-work",
                    ),
                    workId,
                    scheduledEpoch,
                ),
            )
            assertEquals(
                BatchResultDisposition.Success,
                NovelGhostwriteWorker.reconcileBatchResult(
                    result,
                    ownedRunning.copy(leaseOwnerWorkID = "new-work"),
                    workId,
                    scheduledEpoch,
                ),
            )
        }
        assertEquals(
            BatchResultDisposition.Failure,
            NovelGhostwriteWorker.reconcileBatchResult(
                BatchRunResult.Retry,
                base,
                workId,
                scheduledEpoch,
            ),
        )
        assertEquals(WorkResolution.Success, NovelGhostwriteWorker.failClosedResolution(true))
        assertEquals(WorkResolution.Failure, NovelGhostwriteWorker.failClosedResolution(false))
    }

    @Test
    fun `notification reports exact chapter target phase and completed count`() {
        val job = newJob(target = 50)

        val snapshot = NovelGhostwriteWorker.notificationSnapshot(job)

        assertEquals("小说代笔 第 1/50 章", snapshot.title)
        assertEquals("等待章节计划 · 已完成 0/50", snapshot.content)
        assertEquals(0, snapshot.completed)
        assertEquals(50, snapshot.target)
    }

    @Test
    fun `notification id is stable and nonzero`() {
        val jobId = NovelGhostwriteJobId.generate()

        val first = NovelGhostwriteWorker.notificationId(jobId)

        assertEquals(first, NovelGhostwriteWorker.notificationId(jobId))
        assertTrue(first > 0)
    }

    private fun newJob(target: Int) = NovelGhostwriteJobReducer.create(
        id = NovelGhostwriteJobId.generate(),
        projectID = NovelProjectId.generate(),
        branchID = NovelBranchId.generate(),
        targetChapterCount = target,
        initialCursor = NovelGhostwriteChapterCursorV1(
            chapterIndex = 1,
            baseCheckpointID = NovelCheckpointId.generate(),
            baseHeadRevision = 0,
            baseStateSnapshotID = NovelStateSnapshotId.generate(),
            baseConfigRevision = 1,
        ),
        now = Instant.parse("2026-08-09T00:00:00Z"),
    )

    companion object {
        private val TERMINAL_JOB_STATUSES = setOf(
            NovelGhostwriteJobStatus.Completed,
            NovelGhostwriteJobStatus.Failed,
            NovelGhostwriteJobStatus.Cancelled,
        )
    }
}
