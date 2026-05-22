package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadGenerationStage
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadImageAsset
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionState
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionStatus
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.IMAGE_CONFIDENCE_INLINE
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.displayHeroCaption
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.displayHeroImageUrl
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.isComplete
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.statusOf
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.withInferredSectionStates
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.withSectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun isCompleteRequiresAllSectionsReadyFinishFlagAndVerification() {
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
        assertFalse(complete.isComplete())

        val verifiedComplete = complete.copy(
            verificationState = DeepReadSectionState(DeepReadSectionStatus.READY),
        )
        assertTrue(verifiedComplete.isComplete())
        assertTrue(verifiedComplete.generationComplete)

        val verifiedButUnfinished = verifiedComplete.copy(generationComplete = false)
        assertFalse(verifiedButUnfinished.isComplete())
    }

    @Test
    fun legacyCompleteCacheWithoutVerificationIsNotComplete() {
        val complete = DeepReadGenerationStage.entries.fold(DeepReadOutput(generationComplete = true)) { output, stage ->
            output.withSectionStatus(stage, DeepReadSectionStatus.READY)
        }.copy(generationComplete = true)

        assertFalse(complete.isComplete())
    }

    @Test
    fun withInferredSectionStatesRecoversSectionsButRequiresVerification() {
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
        assertFalse(inferred.isComplete())
        assertFalse(inferred.generationComplete)
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
