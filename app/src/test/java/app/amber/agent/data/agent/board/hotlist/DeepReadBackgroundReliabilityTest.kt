package app.amber.feature.board.hotlist

import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.DeepReadWorkerRoute
import app.amber.feature.board.hotlist.deepread.canRetryDeepReadWorker
import app.amber.feature.board.hotlist.deepread.deepReadWorkerRoute
import app.amber.feature.board.hotlist.deepread.effectiveDeepReadForce
import app.amber.feature.board.hotlist.deepread.isRetryableDeepReadWorkerError
import app.amber.feature.board.hotlist.deepread.shouldDeferDeepReadMissingStages
import app.amber.feature.board.hotlist.deepread.shouldRetryDeepReadWorkerError
import app.amber.feature.board.hotlist.deepread.statusOf
import app.amber.feature.board.hotlist.deepread.withSectionRetryRunning
import app.amber.feature.board.hotlist.deepread.withSectionStatus
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeepReadBackgroundReliabilityTest {
    @Test
    fun effectiveForceOnlyAppliesToFirstWorkerAttempt() {
        assertTrue(effectiveDeepReadForce(force = true, runAttemptCount = 0))
        assertFalse(effectiveDeepReadForce(force = true, runAttemptCount = 1))
        assertFalse(effectiveDeepReadForce(force = false, runAttemptCount = 0))
    }

    @Test
    fun workerRouteParsesBlankValidAndInvalidStage() {
        assertSame(DeepReadWorkerRoute.All, deepReadWorkerRoute(null))
        assertSame(DeepReadWorkerRoute.All, deepReadWorkerRoute(""))

        assertEquals(
            DeepReadWorkerRoute.Section(DeepReadGenerationStage.ANALYSIS),
            deepReadWorkerRoute("ANALYSIS"),
        )

        val invalid = deepReadWorkerRoute("NO_SUCH_STAGE")
        assertTrue(invalid is DeepReadWorkerRoute.Invalid)
    }

    @Test
    fun workerModeDoesNotDeferMissingStagesBackToAppScope() {
        val partial = DeepReadOutput()
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
        val missing = listOf(DeepReadGenerationStage.NARRATIVE)

        assertTrue(
            shouldDeferDeepReadMissingStages(
                force = false,
                cached = partial,
                missing = missing,
                deferMissingStages = true,
            )
        )
        assertFalse(
            shouldDeferDeepReadMissingStages(
                force = false,
                cached = partial,
                missing = missing,
                deferMissingStages = false,
            )
        )
        assertFalse(
            shouldDeferDeepReadMissingStages(
                force = true,
                cached = partial,
                missing = missing,
                deferMissingStages = true,
            )
        )
    }

    @Test
    fun transientDeepReadWorkerFailuresAreRetryable() {
        assertTrue(isRetryableDeepReadWorkerError(IOException("connection reset")))
        assertTrue(isRetryableDeepReadWorkerError(IllegalStateException("没有抓到足够的来源")))
        assertTrue(isRetryableDeepReadWorkerError(RuntimeException("HTTP 503 temporarily unavailable")))
        assertFalse(isRetryableDeepReadWorkerError(CancellationException("timeout")))
        assertFalse(isRetryableDeepReadWorkerError(IllegalStateException("请先配置今日看板模型或主聊天模型")))
    }

    @Test
    fun retryableDeepReadWorkerFailuresStopAfterAttemptLimit() {
        val retryable = IllegalStateException("没有抓到足够的来源")

        assertTrue(canRetryDeepReadWorker(runAttemptCount = 0))
        assertTrue(canRetryDeepReadWorker(runAttemptCount = 2))
        assertFalse(canRetryDeepReadWorker(runAttemptCount = 3))
        assertTrue(shouldRetryDeepReadWorkerError(retryable, runAttemptCount = 2))
        assertFalse(shouldRetryDeepReadWorkerError(retryable, runAttemptCount = 3))
        assertFalse(shouldRetryDeepReadWorkerError(CancellationException("timeout"), runAttemptCount = 0))
    }

    @Test
    fun sectionRetryMarksOnlyTargetSectionRunningWithoutCollectingWholeArticle() {
        val partial = DeepReadOutput(generationPhase = DeepReadGenerationPhase.IDLE)
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)

        val retrying = partial.withSectionRetryRunning(DeepReadGenerationStage.NARRATIVE)

        assertEquals(DeepReadGenerationPhase.WRITING, retrying.generationPhase)
        assertEquals(DeepReadSectionStatus.READY, retrying.statusOf(DeepReadGenerationStage.OVERVIEW))
        assertEquals(DeepReadSectionStatus.RUNNING, retrying.statusOf(DeepReadGenerationStage.NARRATIVE))
        assertEquals(DeepReadSectionStatus.PENDING, retrying.statusOf(DeepReadGenerationStage.ANALYSIS))
        assertFalse(retrying.generationComplete)
        assertFalse(retrying.generationPhase == DeepReadGenerationPhase.COLLECTING)
    }
}
