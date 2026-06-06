package app.amber.feature.board.hotlist

import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadProgressSnapshot
import app.amber.feature.board.hotlist.deepread.DeepReadSectionState
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.DeepReadWorkerRoute
import app.amber.feature.board.hotlist.deepread.canRetryDeepReadWorker
import app.amber.feature.board.hotlist.deepread.deepReadWorkerRoute
import app.amber.feature.board.hotlist.deepread.deepReadProgressSnapshot
import app.amber.feature.board.hotlist.deepread.effectiveDeepReadForce
import app.amber.feature.board.hotlist.deepread.isRetryableDeepReadWorkerError
import app.amber.feature.board.hotlist.deepread.shouldDeferDeepReadMissingStages
import app.amber.feature.board.hotlist.deepread.shouldNotifyDeepReadProgress
import app.amber.feature.board.hotlist.deepread.shouldNotifyRunningDeepReadProgress
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

    @Test
    fun progressSnapshotHandlesEmptyAndPhaseFloors() {
        assertEquals(6, null.deepReadProgressSnapshot(running = true).percent)
        assertEquals(0, null.deepReadProgressSnapshot(running = false).percent)
        assertEquals(
            6,
            DeepReadOutput(generationPhase = DeepReadGenerationPhase.IDLE)
                .deepReadProgressSnapshot(running = true)
                .percent,
        )

        assertEquals(
            10,
            DeepReadOutput(generationPhase = DeepReadGenerationPhase.COLLECTING)
                .deepReadProgressSnapshot(running = true)
                .percent,
        )
        assertEquals(
            24,
            DeepReadOutput(generationPhase = DeepReadGenerationPhase.PLANNING)
                .deepReadProgressSnapshot(running = true)
                .percent,
        )
        assertEquals(
            96,
            DeepReadOutput(generationPhase = DeepReadGenerationPhase.VERIFYING)
                .deepReadProgressSnapshot(running = true)
                .percent,
        )
    }

    @Test
    fun progressSnapshotWeightsWritingStages() {
        val analysisRunning = DeepReadOutput(generationPhase = DeepReadGenerationPhase.WRITING)
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.RUNNING)
            .deepReadProgressSnapshot(running = true)

        val extendedRunning = DeepReadOutput(generationPhase = DeepReadGenerationPhase.WRITING)
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.EXTENDED_READING, DeepReadSectionStatus.RUNNING)
            .deepReadProgressSnapshot(running = true)

        assertEquals(69, analysisRunning.percent)
        assertEquals("分段写作：分析", analysisRunning.label)
        assertEquals(86, extendedRunning.percent)
        assertTrue(extendedRunning.percent > analysisRunning.percent)
    }

    @Test
    fun progressSnapshotFinishesAtNinetyFourBeforeCompletion() {
        val allSectionsReady = DeepReadGenerationStage.entries.fold(
            DeepReadOutput(generationPhase = DeepReadGenerationPhase.WRITING)
        ) { output, stage ->
            output.withSectionStatus(stage, DeepReadSectionStatus.READY)
        }
        val complete = allSectionsReady.copy(generationComplete = true)

        assertEquals(94, allSectionsReady.deepReadProgressSnapshot(running = true).percent)
        assertEquals("正在收尾", allSectionsReady.deepReadProgressSnapshot(running = true).label)
        assertEquals(100, complete.deepReadProgressSnapshot(running = false).percent)
    }

    @Test
    fun progressSnapshotSectionRetryKeepsReadyStageContribution() {
        val retrying = DeepReadOutput(generationPhase = DeepReadGenerationPhase.IDLE)
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
            .withSectionRetryRunning(DeepReadGenerationStage.NARRATIVE)
            .deepReadProgressSnapshot(running = true)

        assertEquals(51, retrying.percent)
        assertEquals("分段写作：叙事", retrying.label)
        assertTrue(retrying.percent > 10)
    }

    @Test
    fun progressSnapshotVerificationRunningWinsOverSectionState() {
        val output = DeepReadOutput(
            generationPhase = DeepReadGenerationPhase.WRITING,
            verificationState = DeepReadSectionState(DeepReadSectionStatus.RUNNING),
        )
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)

        assertEquals(96, output.deepReadProgressSnapshot(running = true).percent)
        assertEquals("正在补漏", output.deepReadProgressSnapshot(running = true).label)
    }

    @Test
    fun progressNotificationDeduplicatesEqualSnapshots() {
        val previous = DeepReadProgressSnapshot(24, "正在规划结构")
        val same = DeepReadProgressSnapshot(24, "正在规划结构")
        val changed = DeepReadProgressSnapshot(69, "分段写作：分析")

        assertFalse(shouldNotifyDeepReadProgress(previous, same))
        assertTrue(shouldNotifyDeepReadProgress(previous, changed))
    }

    @Test
    fun runningProgressNotificationSkipsTerminalAndIdleSnapshots() {
        val writing = DeepReadOutput(generationPhase = DeepReadGenerationPhase.WRITING)
            .withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.RUNNING)
        val finishing = DeepReadGenerationStage.entries.fold(
            DeepReadOutput(generationPhase = DeepReadGenerationPhase.WRITING)
        ) { output, stage ->
            output.withSectionStatus(stage, DeepReadSectionStatus.READY)
        }
        val complete = finishing.copy(generationComplete = true)
        val failed = DeepReadOutput(generationPhase = DeepReadGenerationPhase.IDLE)
            .withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.FAILED)

        assertFalse(null.shouldNotifyRunningDeepReadProgress())
        assertTrue(writing.shouldNotifyRunningDeepReadProgress())
        assertTrue(finishing.shouldNotifyRunningDeepReadProgress())
        assertFalse(complete.shouldNotifyRunningDeepReadProgress())
        assertFalse(failed.shouldNotifyRunningDeepReadProgress())
    }
}
