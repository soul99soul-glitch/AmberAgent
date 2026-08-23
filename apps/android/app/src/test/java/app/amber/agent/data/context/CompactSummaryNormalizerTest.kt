package app.amber.core.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactSummaryNormalizerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun blankSummaryReturnsNullInsteadOfCompletedSkeleton() {
        assertNull(
            CompactSummaryNormalizer.normalizeOrNull(
                json = json,
                summary = "   ",
                sourceMessageIds = listOf("m1", "m2"),
            )
        )
    }

    @Test
    fun structuredSummaryPreservesPreambleAndNormalizesSchema() {
        val normalized = CompactSummaryNormalizer.normalizeOrNull(
            json = json,
            summary = """
                Summary.
                ```json
                {"facts":"single fact","source_message_ids":["wrong"],"extra":true}
                ```
            """.trimIndent(),
            sourceMessageIds = listOf("m1", "m2"),
        )!!

        val body = json.parseToJsonElement(normalized).jsonObject
        assertEquals("2", body["schema_version"]!!.jsonPrimitive.content)
        assertTrue(body["timeline_summary"]!!.jsonPrimitive.content.contains("Summary"))
        assertEquals(listOf("m1", "m2"), body["source_message_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("single fact", body["facts"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(body["goals"]!!.jsonArray.isEmpty())
        assertTrue(body["tool_results"]!!.jsonArray.isEmpty())
        assertEquals("true", body["extra"]!!.jsonPrimitive.content)
        assertTrue(body["handoff_markdown"]!!.jsonPrimitive.content.contains("## Current State"))
    }

    @Test
    fun plainTextSummaryFallsBackToStructuredFacts() {
        val normalized = CompactSummaryNormalizer.normalizeOrNull(
            json = json,
            summary = "用户要求修复上下文压缩，并保留原始历史。",
            sourceMessageIds = listOf("m1"),
        )!!
        val body = json.parseToJsonElement(normalized).jsonObject

        assertEquals("用户要求修复上下文压缩，并保留原始历史。", body["timeline_summary"]!!.jsonPrimitive.content)
        assertTrue(body["handoff_markdown"]!!.jsonPrimitive.content.contains("用户要求修复上下文压缩"))
        assertEquals(listOf("m1"), body["source_message_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun fallbackUsesSourceContentInsteadOfEnglishEmptySummary() {
        val fallback = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
            summary = "",
            sourceMessageIds = listOf("m1", "m2"),
            sourceContent = """
                message_id: m1
                role: user
                text: 我想测试上下文压缩，把很多工具输出压成摘要。

                message_id: m2
                role: assistant
                text: 好的，我会生成大量内容并观察压缩效果。
            """.trimIndent(),
        )
        val body = json.parseToJsonElement(fallback).jsonObject

        assertTrue(body["timeline_summary"]!!.jsonPrimitive.content.contains("我想测试上下文压缩"))
        assertFalse(body["timeline_summary"]!!.jsonPrimitive.content.contains("no readable summary"))
        assertTrue(body["handoff_markdown"]!!.jsonPrimitive.content.contains("我想测试上下文压缩"))
    }

    @Test
    fun fallbackDoesNotClaimCoveredCompactsUnlessItCarriesTheirHandoff() {
        val fallback = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
            summary = "",
            sourceMessageIds = listOf("m2"),
            coveredCompactIds = listOf("old-compact"),
            sourceContent = "text: 当前新片段需要兜底摘要。",
        )
        val payload = CompactSummaryPayloads.parse(fallback)!!

        assertTrue(payload.coveredCompactIds.isEmpty())
        assertFalse(payload.handoffMarkdown.contains("old-compact"))
    }

    @Test
    fun fallbackCarriesCoveredCompactsWhenPreviousHandoffIsIncluded() {
        val fallback = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
            summary = "",
            sourceMessageIds = listOf("m2"),
            coveredCompactIds = listOf("old-compact"),
            sourceContent = "text: 当前新片段需要兜底摘要。",
            carriedHandoffMarkdown = "[Conversation compact handoff: old-compact]\n## Goal\n- Keep prior context.",
        )
        val payload = CompactSummaryPayloads.parse(fallback)!!

        assertEquals(listOf("old-compact"), payload.coveredCompactIds)
        assertTrue(payload.handoffMarkdown.contains("old-compact"))
        assertTrue(payload.handoffMarkdown.contains("Keep prior context"))
    }

    @Test
    fun v2PayloadKeepsTimelineAndHandoffButUsesActualCoverage() {
        val normalized = CompactSummaryNormalizer.normalizeOrNull(
            json = json,
            summary = """
                {
                  "schema_version": 2,
                  "timeline_summary": "用户要求实现上下文压缩 UI。旧消息需要置灰。压缩完成后要保留分割线。摘要需要能给人读。后续模型要靠 handoff 继续。",
                  "handoff_markdown": "## Goal\n- Implement context compaction.\n\n## Constraints\n- Preserve user settings.",
                  "covered_compact_ids": ["wrong"],
                  "source_message_ids": ["wrong"]
                }
            """.trimIndent(),
            sourceMessageIds = listOf("m1", "m2"),
            coveredCompactIds = listOf("c1"),
            createdAt = 123L,
        )!!
        val payload = CompactSummaryPayloads.parse(normalized)!!

        assertEquals(listOf("c1"), payload.coveredCompactIds)
        assertEquals(listOf("m1", "m2"), payload.sourceMessageIds)
        assertEquals(123L, payload.createdAt)
        assertTrue(payload.timelineSummary.contains("压缩完成后"))
        assertTrue(payload.handoffMarkdown.contains("## Goal"))
    }

    @Test
    fun remapCoveredCompactIdsKeepsForkedCumulativeHandoffConsistent() {
        val summary = """
            {
              "schema_version": 2,
              "timeline_summary": "用户已经完成一次压缩。摘要需要留在时间线底部。fork 后不能丢失摘要。模型续跑要使用 handoff。后续继续验证 UI。",
              "handoff_markdown": "## Goal\n- Keep forked compact handoff usable.\n- Previous compact id old-a must be remapped.",
              "covered_compact_ids": ["old-a", "missing", "old-b"],
              "source_message_ids": ["m1"],
              "created_at": 123
            }
        """.trimIndent()

        val remapped = CompactSummaryPayloads.remapCoveredCompactIds(
            summary = summary,
            idMapping = mapOf("old-a" to "new-a", "old-b" to "new-b"),
        )
        val body = json.parseToJsonElement(remapped).jsonObject

        assertEquals(
            listOf("new-a", "new-b"),
            body["covered_compact_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(body["handoff_markdown"]!!.jsonPrimitive.content.contains("forked compact"))
        assertTrue(body["timeline_summary"]!!.jsonPrimitive.content.contains("时间线底部"))
        assertTrue(body["handoff_markdown"]!!.jsonPrimitive.content.contains("new-a"))
        assertFalse(body["handoff_markdown"]!!.jsonPrimitive.content.contains("old-a"))
    }

    @Test
    fun malformedV2FieldTypesDoNotCrashParsing() {
        val payload = CompactSummaryPayloads.parse(
            """
                {
                  "schema_version": {},
                  "timeline_summary": "用户要求继续验证压缩。摘要需要显示在时间线。旧消息需要置灰。后续对话要接上 handoff。",
                  "handoff_markdown": "## Goal\n- Continue safely.",
                  "covered_compact_ids": ["c1"],
                  "source_message_ids": ["m1"],
                  "created_at": {}
                }
            """.trimIndent()
        )!!

        assertEquals(CompactSummaryPayloads.SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(0L, payload.createdAt)
        assertEquals(listOf("c1"), payload.coveredCompactIds)
    }

    @Test
    fun contextStatusActionUsesEffectiveTokens() {
        val policy = CompactPolicy(precompactRatio = 0.70f, forceRatio = 0.85f)

        assertEquals("below_threshold", effectiveContextNextAction(policy, effectiveTokens = 600, contextWindowTokens = 1_000))
        assertEquals("precompact_threshold", effectiveContextNextAction(policy, effectiveTokens = 750, contextWindowTokens = 1_000))
        assertEquals("force_threshold", effectiveContextNextAction(policy, effectiveTokens = 900, contextWindowTokens = 1_000))
        assertEquals("disabled", effectiveContextNextAction(policy.copy(enabled = false), effectiveTokens = 900, contextWindowTokens = 1_000))
    }
}
