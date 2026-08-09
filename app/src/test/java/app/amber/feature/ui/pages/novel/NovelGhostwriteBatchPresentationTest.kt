package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.NovelGhostwriteBatchProjectSnapshot
import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobPhase
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.persistence.NovelGhostwriteJobLoadAccess
import app.amber.feature.novel.persistence.NovelLoadedGhostwriteJob
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelGhostwriteBatchPresentationTest {
    @Test
    fun target_acceptsOnlyOneThroughFifty() {
        assertNull(parseNovelGhostwriteBatchTarget("0"))
        assertEquals(1, parseNovelGhostwriteBatchTarget(" 1 "))
        assertEquals(50, parseNovelGhostwriteBatchTarget("50"))
        assertNull(parseNovelGhostwriteBatchTarget("51"))
        assertNull(parseNovelGhostwriteBatchTarget("十"))
    }

    @Test
    fun progress_isReceiptCountNotCurrentGenerationAttempt() {
        val pending = newJob(target = 50)

        assertEquals("0/50", pending.batchProgressLabel())
        assertEquals("等待后台启动", pending.batchStatusLabel())
    }

    @Test
    fun everyDurablePhaseHasConcreteUserCopy() {
        NovelGhostwriteJobPhase.entries.forEach { phase ->
            assertTrue(phase.batchPhaseLabel().isNotBlank())
        }
        assertEquals("准备自纠正", NovelGhostwriteJobPhase.CorrectionReady.batchPhaseLabel())
        assertEquals(
            "准备清理已完成计划",
            NovelGhostwriteJobPhase.ClearPlanPrepared.batchPhaseLabel(),
        )
    }

    @Test
    fun qualityCircuitBreakerExplainsWhyLoopCannotContinue() {
        val reason = humanizeNovelGhostwriteBatchReason("quality_circuit_breaker").orEmpty()

        assertTrue(reason.contains("自动循环已停止"))
        assertTrue(reason.contains("避免污染"))
        assertFalse(reason.contains("可继续"))
    }

    @Test
    fun unknownReasonIsBoundedButStillVisibleForDiagnosis() {
        val raw = "future_reason_" + "x".repeat(300)
        val displayed = humanizeNovelGhostwriteBatchReason(raw).orEmpty()

        assertTrue(displayed.startsWith("任务原因：future_reason_"))
        assertTrue(displayed.length <= 125)
    }

    @Test
    fun processRestart_prefersTheDurableActiveBatchBranch() {
        val job = newJob(target = 10)
        val defaultMainBranch = NovelBranchId.generate()
        val snapshot = NovelGhostwriteBatchProjectSnapshot(
            projectId = job.projectID,
            jobs = listOf(NovelLoadedGhostwriteJob(job, NovelGhostwriteJobLoadAccess.ReadWrite)),
        )

        assertEquals(
            job.branchID,
            preferredBranchForActiveBatch(snapshot, defaultMainBranch) { true },
        )
        assertEquals(
            job.branchID,
            preferredBranchForActiveBatch(snapshot, job.branchID) { true },
        )
    }

    private fun newJob(target: Int) = NovelGhostwriteJobReducer.create(
        id = NovelGhostwriteJobId.generate(),
        projectID = NovelProjectId.generate(),
        branchID = NovelBranchId.generate(),
        targetChapterCount = target,
        initialCursor = NovelGhostwriteChapterCursorV1(
            chapterIndex = 1,
            baseProjectRevision = 1,
            baseCheckpointID = NovelCheckpointId.generate(),
            baseHeadRevision = 0,
            baseStateSnapshotID = NovelStateSnapshotId.generate(),
            baseConfigRevision = 1,
        ),
        now = Instant.parse("2026-08-09T00:00:00Z"),
    )
}
