package app.amber.feature.board.hotlist

import app.amber.feature.board.hotlist.deepread.CorePoint
import app.amber.feature.board.hotlist.deepread.DeepAnalysis
import app.amber.feature.board.hotlist.deepread.DeepReadCoverageItem
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadResearchHarness
import app.amber.feature.board.hotlist.deepread.DeepReadSource
import app.amber.feature.board.hotlist.deepread.Perspective
import app.amber.feature.board.hotlist.deepread.ReadingLink
import app.amber.feature.board.hotlist.deepread.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadResearchHarnessTest {
    private val harness = DeepReadResearchHarness()

    @Test
    fun evidencePackKeepsStagePromptsSmallButPlanCoverageBroad() {
        val pack = harness.buildEvidencePack("模型发布争议", mockSources(12))
        val plan = harness.normalizePlan(
            parsed = null,
            topicTitle = "模型发布争议",
            pack = pack,
        )

        DeepReadGenerationStage.entries.forEach { stage ->
            val cards = pack.cardsFor(stage, plan)
            assertTrue("stage ${stage.name} should get at least 4 cards", cards.size >= 4)
            assertTrue("stage ${stage.name} should get at most 6 cards", cards.size <= 6)
        }
        val globalSourceCount = plan.stageSourceIds.values.flatten().distinct().size
        assertTrue("whole article should still cover broad evidence", globalSourceCount >= 8)
        assertTrue(plan.requiredSourceIds.size >= 8)
    }

    @Test
    fun planningParserToleratesHumanTextAroundJson() {
        val pack = harness.buildEvidencePack("模型发布争议", mockSources(8))
        val raw = """
            先给一个规划：
            {
              "overview_angle": "解释发布背后的产品与争议双线",
              "narrative_slots": ["官方发布", "外界反应"],
              "analysis_questions": ["核心矛盾是什么？", "反方证据是什么？"],
              "required_source_ids": ["s1", "s2", "s3", "s4", "s5",],
              "stage_source_ids": {
                "overview": ["s1", "s2", "s3", "s4"],
                "analysis": ["s4", "s5", "s6", "s7"]
              }
            }
        """.trimIndent()

        val plan = harness.normalizePlan(
            parsed = harness.parsePlan(raw),
            topicTitle = "模型发布争议",
            pack = pack,
        )

        assertEquals("解释发布背后的产品与争议双线", plan.overviewAngle)
        assertTrue(plan.sourceIdsFor(DeepReadGenerationStage.OVERVIEW).size in 4..6)
        assertTrue(plan.sourceIdsFor(DeepReadGenerationStage.NARRATIVE).size in 4..6)
    }

    @Test
    fun coverageVerifierTargetsOnlyMissingAnalysisCoverage() {
        val sources = mockSources(12)
        val pack = harness.buildEvidencePack("模型发布争议", sources)
        val plan = harness.normalizePlan(null, "模型发布争议", pack)
        val output = DeepReadOutput(
            summary = "这是一段已核查的概览，说明事件背景和当前进展。",
            keyEntities = listOf("公司A", "产品B"),
            timeline = listOf(
                TimelineEvent("2026-05-01", "官方宣布新模型发布，并给出了可用入口和基本定位。"),
                TimelineEvent("2026-05-02", "媒体继续跟进发布时间线，补充了前后背景和当前状态。"),
            ),
            corePoints = listOf(
                CorePoint("核心点一", "这条内容解释背景和时间线，仍停留在公开信息整理。"),
                CorePoint("核心点二", "这条内容继续补充已确认事实，仍然只停留在公开信息整理。"),
            ),
            references = listOf(sources.first().toReadingLink()),
            extendedReading = listOf(sources.first().toReadingLink()),
        )

        val report = harness.verifyCoverage(output, plan, pack)

        assertTrue(report.needsSupplement)
        assertEquals(DeepReadGenerationStage.ANALYSIS, report.targetStage)
        assertTrue(DeepReadCoverageItem.CONTROVERSY in report.missingItems)
        assertTrue(DeepReadCoverageItem.IMPACT in report.missingItems)
        assertTrue(DeepReadCoverageItem.COUNTER_EVIDENCE in report.missingItems)
    }

    @Test
    fun supplementEvidencePackForceIncludesMissingRequiredSourceIds() {
        val pack = harness.buildEvidencePack("模型发布争议", mockSources(12))
        val plan = harness.normalizePlan(null, "模型发布争议", pack)
        val forcedId = pack.cards.last().sourceId

        val cards = pack.cardsFor(
            stage = DeepReadGenerationStage.EXTENDED_READING,
            plan = plan,
            forceIncludeSourceIds = listOf(forcedId),
        )

        assertTrue(cards.any { it.sourceId == forcedId })
        assertTrue(cards.size <= 6)
    }

    @Test
    fun coverageVerifierAcceptsBroadReferencedDraft() {
        val sources = mockSources(12)
        val pack = harness.buildEvidencePack("模型发布争议", sources)
        val plan = harness.normalizePlan(null, "模型发布争议", pack)
        val links = plan.requiredSourceIds.mapNotNull { id ->
            pack.cards.firstOrNull { it.sourceId == id }?.source?.toReadingLink()
        }
        val output = DeepReadOutput(
            summary = "这是一段已核查的概览，说明事件背景、官方发布和当前进展。",
            keyEntities = listOf("公司A", "产品B", "开发者"),
            timeline = listOf(
                TimelineEvent("2026-05-01", "官方宣布新模型发布，并给出了可用入口和基本定位。"),
                TimelineEvent("2026-05-02", "媒体继续跟进发布时间线，补充了前后背景和当前状态。"),
            ),
            corePoints = listOf(
                CorePoint("核心点一", "这条内容解释背景、时间线和利益相关方为什么会关注。"),
                CorePoint("核心点二", "这条内容综合多个来源说明当前核心变化和后续观察点。"),
            ),
            analysis = DeepAnalysis(
                coreDispute = "核心争议在于官方发布的性能说法、外界质疑和未证实反方证据之间如何降格表达。",
                perspectives = listOf(
                    Perspective("官方认为新模型改善了成本和可用入口。", "官方"),
                    Perspective("开发者担忧价格、迁移成本和生态影响。", "开发者"),
                ),
                implications = "影响链条包括用户成本、开发者集成节奏、行业竞争格局和后续监管观察。",
            ),
            references = links,
            extendedReading = links,
        )

        val report = harness.verifyCoverage(output, plan, pack)

        assertFalse(report.promptSummary(), report.needsSupplement)
    }

    private fun mockSources(count: Int): List<DeepReadSource> =
        (1..count).map { index ->
            val tagText = when (index % 6) {
                0 -> "官方 statement announcement 发布会 声明，给出价格、可用入口和版本号。"
                1 -> "背景 context 来龙去脉，解释起因、前因后果和历史回顾。"
                2 -> "时间线 latest timeline 最新进展，宣布发布后多个节点持续发生。"
                3 -> "利益相关方 stakeholder 用户 开发者 投资者 专家回应。"
                4 -> "争议 controversy dispute 质疑 批评 担忧 诉讼 风险。"
                else -> "影响 impact implication 市场 价格 成本 生态；不过存在未证实、否认、反方证据。"
            }
            DeepReadSource(
                title = "来源 $index 模型发布观察",
                url = "https://example.com/source-$index",
                source = "example-$index",
                content = "$tagText 这是一段足够长的来源正文，用于模拟深度阅读资料收集。".repeat(8),
                publishedAt = "2026-05-${index.toString().padStart(2, '0')}",
                images = if (index % 4 == 0) listOf("https://example.com/image-$index.jpg") else emptyList(),
                evidenceText = "$tagText 这是一段证据摘录，包含可被验真的关键事实。",
            )
        }

    private fun DeepReadSource.toReadingLink(): ReadingLink =
        ReadingLink(
            title = title,
            url = url,
            source = source,
        )
}
