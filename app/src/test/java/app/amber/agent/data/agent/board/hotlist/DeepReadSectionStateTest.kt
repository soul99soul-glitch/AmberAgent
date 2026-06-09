package app.amber.feature.board.hotlist

import kotlinx.serialization.json.Json
import app.amber.feature.board.hotlist.deepread.CorePoint
import app.amber.feature.board.hotlist.deepread.DeepAnalysis
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadImageAsset
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionState
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.IMAGE_CONFIDENCE_INLINE
import app.amber.feature.board.hotlist.deepread.ReadingLink
import app.amber.feature.board.hotlist.deepread.TimelineEvent
import app.amber.feature.board.hotlist.deepread.displayHeroCaption
import app.amber.feature.board.hotlist.deepread.displayHeroImageUrl
import app.amber.feature.board.hotlist.deepread.isComplete
import app.amber.feature.board.hotlist.deepread.isDeliverableDraft
import app.amber.feature.board.hotlist.deepread.sectionFailureMessage
import app.amber.feature.board.hotlist.deepread.statusOf
import app.amber.feature.board.hotlist.deepread.verificationWarningMessage
import app.amber.feature.board.hotlist.deepread.withInferredSectionStates
import app.amber.feature.board.hotlist.deepread.withSectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadSectionStateTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun withSectionStatusUpdatesIndividualStageAndKeepsCompleteFlagInSync() {
        val output = DeepReadOutput()
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)

        assertEquals(DeepReadSectionStatus.READY, output.statusOf(DeepReadGenerationStage.OVERVIEW))
        assertEquals(DeepReadSectionStatus.PENDING, output.statusOf(DeepReadGenerationStage.NARRATIVE))
        assertFalse(output.isComplete())
        assertFalse(output.generationComplete)
    }

    @Test
    fun isCompleteRequiresAllSectionsReadyAndFinishFlag() {
        val partial = DeepReadOutput()
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.READY)
        assertFalse(partial.isComplete())

        val readyButUnfinished = partial
            .withSectionStatus(DeepReadGenerationStage.EXTENDED_READING, DeepReadSectionStatus.READY)
        assertFalse(readyButUnfinished.isComplete())
        assertFalse(readyButUnfinished.generationComplete)

        val complete = readyButUnfinished.copy(generationComplete = true)
        assertTrue(complete.isComplete())
        assertTrue(complete.generationComplete)

        val unfinished = complete.copy(generationComplete = false)
        assertFalse(unfinished.isComplete())
    }

    @Test
    fun legacyCompleteCacheNoLongerRequiresVerification() {
        val complete = DeepReadGenerationStage.entries.fold(DeepReadOutput(generationComplete = true)) { output, stage ->
            output.withSectionStatus(stage, DeepReadSectionStatus.READY)
        }.copy(generationComplete = true)

        assertTrue(complete.isComplete())
    }

    @Test
    fun deliverableDraftRequiresSectionsReadyButNotVerification() {
        val failedVerification = DeepReadGenerationStage.entries.fold(DeepReadOutput()) { output, stage ->
            output.withSectionStatus(stage, DeepReadSectionStatus.READY)
        }.copy(
            verificationState = DeepReadSectionState(
                status = DeepReadSectionStatus.FAILED,
                errorMessage = "最终验真未完成",
            )
        )

        assertTrue(failedVerification.isDeliverableDraft())
        assertFalse(failedVerification.isComplete())
        assertNull(failedVerification.verificationWarningMessage())
        assertNull(failedVerification.sectionFailureMessage())
    }

    @Test
    fun sectionFailureMessageStaysSeparateFromVerificationWarning() {
        val sectionFailed = DeepReadOutput()
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.FAILED, "时间线失败")
            .copy(
                verificationState = DeepReadSectionState(
                    status = DeepReadSectionStatus.FAILED,
                    errorMessage = "验真失败",
                )
            )

        assertFalse(sectionFailed.isDeliverableDraft())
        assertEquals("时间线失败", sectionFailed.sectionFailureMessage())
        assertNull(sectionFailed.verificationWarningMessage())
    }

    @Test
    fun withInferredSectionStatesRecoversCompleteLegacyCache() {
        val legacy = DeepReadOutput(
            summary = "这是一段足够长的中文摘要，足以判断 overview 已经完成。",
            timeline = listOf(TimelineEvent("date", "event")),
            corePoints = listOf(CorePoint("point", "supporting")),
            analysis = DeepAnalysis(implications = "影响分析"),
            extendedReading = listOf(ReadingLink("标题", "https://example.com", "example")),
            generationComplete = true,
        )

        val inferred = legacy.withInferredSectionStates()

        DeepReadGenerationStage.entries.forEach { stage ->
            assertEquals(DeepReadSectionStatus.READY, inferred.statusOf(stage))
        }
        assertTrue(inferred.isComplete())
        assertTrue(inferred.generationComplete)
    }

    @Test
    fun withInferredSectionStatesMarksOnlyOverviewReadyForOverviewOnlyCache() {
        val overviewOnly = DeepReadOutput(
            summary = "只有概览生成出来，后续段落还没写。",
        )

        val inferred = overviewOnly.withInferredSectionStates()

        assertEquals(DeepReadSectionStatus.READY, inferred.statusOf(DeepReadGenerationStage.OVERVIEW))
        assertEquals(DeepReadSectionStatus.PENDING, inferred.statusOf(DeepReadGenerationStage.NARRATIVE))
        assertEquals(DeepReadSectionStatus.PENDING, inferred.statusOf(DeepReadGenerationStage.ANALYSIS))
        assertEquals(DeepReadSectionStatus.PENDING, inferred.statusOf(DeepReadGenerationStage.EXTENDED_READING))
        assertFalse(inferred.isComplete())
    }

    @Test
    fun sectionStatesRoundTripsThroughJson() {
        val original = DeepReadOutput(summary = "中文摘要")
            .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
            .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.FAILED, "时间轴失败")

        val encoded = json.encodeToString(DeepReadOutput.serializer(), original)
        val decoded = json.decodeFromString(DeepReadOutput.serializer(), encoded)

        assertEquals(DeepReadSectionStatus.READY, decoded.statusOf(DeepReadGenerationStage.OVERVIEW))
        assertEquals(DeepReadSectionStatus.FAILED, decoded.statusOf(DeepReadGenerationStage.NARRATIVE))
        assertEquals("时间轴失败", decoded.sectionStates[DeepReadGenerationStage.NARRATIVE]?.errorMessage)
    }

    @Test
    fun displayHeroFallsBackToVerifiedInlineImageWhenExplicitHeroMissing() {
        val imageUrl = "https://example.com/car.jpg"
        val output = DeepReadOutput(
            imageAssets = listOf(
                DeepReadImageAsset(
                    url = imageUrl,
                    caption = "发布会现场图",
                    confidence = IMAGE_CONFIDENCE_INLINE,
                )
            )
        )

        assertEquals(imageUrl, output.displayHeroImageUrl())
        assertEquals("发布会现场图", output.displayHeroCaption())
    }
}
