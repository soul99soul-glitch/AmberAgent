package app.amber.core.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 regression: `handoff_markdown` is capped at 24k chars, so appending the missing
 * `must_not_lose` facts *after* the cap silently truncated them down to the section title.
 * `injectionText` also only checked for the heading, so the model never saw the facts.
 */
class CompactHandoffBudgetTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun normalize(summary: String, mustNotLose: List<String>): String {
        val payload = buildString {
            append("""{"schema_version":2,"timeline_summary":""")
            append(JsonPrimitive("Summary."))
            append(""","handoff_markdown":""")
            append(JsonPrimitive(summary))
            append(""","must_not_lose":[""")
            append(mustNotLose.joinToString(",") { JsonPrimitive(it).toString() })
            append("]}")
        }
        return CompactSummaryNormalizer.normalizeOrNull(
            json = json,
            summary = payload,
            sourceMessageIds = listOf("m1"),
            coveredCompactIds = emptyList(),
            createdAt = 1_000L,
        )!!
    }

    @Test
    fun mustNotLoseFactsSurviveWhenHandoffWouldExceedTheCharCap() {
        // Handoff already fills the 24k cap; appending facts must not be dropped by truncation.
        val hugeHandoff = buildString {
            appendLine("## Goal")
            var i = 0
            while (length < 25_000) {
                appendLine("- progress detail line ${i++} padded out to make the handoff long")
            }
        }
        val needles = listOf(
            "max_steps=30, budget=180000ms",
            "用户批准原文：允许本次屏幕操作",
            "app/src/main/java/app/amber/core/jev/JevScreenGoalRunner.kt @ a2a159aa1",
        )
        val normalized = normalize(hugeHandoff, needles)
        val payload = CompactSummaryPayloads.parse(normalized)!!

        assertTrue("handoff must stay within the 24k cap", payload.handoffMarkdown.length <= 24_000)
        needles.forEach { needle ->
            assertTrue("handoff lost must-not-lose fact: $needle", payload.handoffMarkdown.contains(needle))
        }

        val injected = CompactSummaryPayloads.injectionText(
            id = "c1",
            summary = normalized,
            sourceMessageIds = listOf("m1"),
        )
        needles.forEach { needle ->
            assertTrue("next model never sees: $needle", injected.contains(needle))
        }
    }

    @Test
    fun injectedHandoffNeverEndsWithABareMustNotLoseHeading() {
        val hugeHandoff = "## Goal\n" + "- filler line\n".repeat(2_000)
        val needle = "决定：保持 content-hash snapshot id"
        val injected = CompactSummaryPayloads.injectionText(
            id = "c1",
            summary = normalize(hugeHandoff, listOf(needle)),
            sourceMessageIds = listOf("m1"),
        )
        assertTrue(injected.contains(needle))
    }

    @Test
    fun mustNotLoseFactsThatCannotFitAreStillSurfacedFromThePayload() {
        // Even a pathological handoff must not lose the facts: injection re-appends them.
        val hugeHandoff = "x".repeat(30_000)
        val needle = "TODO: Gateway 配对未验证"
        val normalized = normalize(hugeHandoff, listOf(needle))
        val payload = CompactSummaryPayloads.parse(normalized)!!
        assertEquals(listOf(needle), payload.mustNotLose)

        val injected = CompactSummaryPayloads.injectionText(
            id = "c1",
            summary = normalized,
            sourceMessageIds = listOf("m1"),
        )
        assertTrue(injected.contains(needle))
        assertFalse(injected.endsWith("Must not lose:"))
    }
}
