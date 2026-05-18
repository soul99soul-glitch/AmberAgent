package me.rerere.rikkahub.data.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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

        assertTrue(normalized.startsWith("Summary."))
        val body = normalized.substring(normalized.indexOf('{')).let {
            json.parseToJsonElement(it).jsonObject
        }
        assertEquals(listOf("m1", "m2"), body["source_message_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("single fact", body["facts"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(body["goals"]!!.jsonArray.isEmpty())
        assertTrue(body["tool_results"]!!.jsonArray.isEmpty())
        assertEquals(true, body["extra"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun plainTextSummaryFallsBackToStructuredFacts() {
        val normalized = CompactSummaryNormalizer.normalizeOrNull(
            json = json,
            summary = "用户要求修复上下文压缩，并保留原始历史。",
            sourceMessageIds = listOf("m1"),
        )!!
        val body = json.parseToJsonElement(normalized).jsonObject

        assertEquals("用户要求修复上下文压缩，并保留原始历史。", body["facts"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(listOf("m1"), body["source_message_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
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
