package app.amber.core.context

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic needle baseline for `must_not_lose` (A2.2).
 *
 * This pins the *serialization* guarantee: 12 classes of verbatim facts that the model marks as
 * must-not-lose survive normalize -> parse -> injection even when the handoff omitted them. It is
 * not a model-quality benchmark; it cannot prove the summarizer noticed the fact in the first place.
 */
class CompactMustNotLoseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val needles = linkedMapOf(
        "exact_error" to "java.lang.IllegalStateException: Screen control was ended by screen_stop",
        "command_exit" to "./gradlew :app:testFullDebugUnitTest exited 1",
        "path_commit_hash" to "app/src/main/java/app/amber/core/jev/JevScreenGoalRunner.kt @ a2a159aa1 sha256:9f2c1d",
        "approval_wording" to "用户批准原文：允许本次屏幕操作",
        "key_numbers" to "max_steps=30, budget=180000ms, keepRecentTurns=12",
        "url_endpoint" to "https://api.example.com/v1/chat/completions",
        "model_api_name" to "deepseek-v3.2 via chat.completions",
        "dates_deadlines" to "2026-10-31 23:59 前完成",
        "user_constraint" to "不得修改 iOS 仓库",
        "accepted_decision" to "决定：保持 content-hash snapshot id",
        "unresolved_task" to "TODO: Gateway 配对未验证",
        "security_boundary" to "高风险动作只能手机拒绝，批准须回 Mac",
    )

    private fun normalize(
        summary: String,
        mustNotLose: List<String> = emptyList(),
    ): String {
        val payload = buildString {
            append("""{"schema_version":2,"timeline_summary":"$summary","handoff_markdown":"## Goal\n$summary"""")
            if (mustNotLose.isNotEmpty()) {
                append(""","must_not_lose":[""")
                append(mustNotLose.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" })
                append("]")
            }
            append("}")
        }
        return CompactSummaryNormalizer.normalizeOrNull(
            json = json,
            summary = payload,
            sourceMessageIds = listOf("m1", "m2"),
            coveredCompactIds = emptyList(),
            createdAt = 1_000L,
        )!!
    }

    @Test
    fun everyNeedleClassSurvivesCompactionEvenWhenTheHandoffOmittedIt() {
        assertEquals("baseline must cover exactly 12 classes", 12, needles.size)
        needles.forEach { (category, needle) ->
            val normalized = normalize(summary = "Short summary without the fact.", mustNotLose = listOf(needle))
            val payload = CompactSummaryPayloads.parse(normalized)
            assertTrue("$category: must_not_lose lost the needle", payload?.mustNotLose?.contains(needle) == true)
            val injected = CompactSummaryPayloads.injectionText(
                id = "c1",
                summary = normalized,
                sourceMessageIds = listOf("m1", "m2"),
            )
            assertTrue("$category: next model never sees the needle", injected.contains(needle))
        }
    }

    @Test
    fun needleAlreadyPresentInHandoffIsNotDuplicated() {
        val needle = needles.getValue("exact_error")
        val normalized = normalize(summary = "Failure: $needle", mustNotLose = listOf(needle))
        val payload = CompactSummaryPayloads.parse(normalized)!!
        assertEquals(1, Regex(Regex.escape(needle)).findAll(payload.handoffMarkdown).count())
        assertFalse(payload.handoffMarkdown.contains("## Must Not Lose"))
    }

    @Test
    fun needlesAreTrimmedDedupedCappedAndSearchable() {
        val items = (1..13).map { " fact-$it " } + listOf("FACT-1", "", "   ")
        val normalized = normalize(summary = "Summary.", mustNotLose = items)
        val payload = CompactSummaryPayloads.parse(normalized)!!
        assertEquals(12, payload.mustNotLose.size)
        assertEquals("fact-1", payload.mustNotLose.first())
        assertEquals(1, payload.mustNotLose.count { it.equals("fact-1", ignoreCase = true) })
        assertFalse(payload.mustNotLose.any { it.isBlank() })
        val searchable = CompactSummaryPayloads.searchableText(normalized)
        assertTrue(searchable.contains("fact-12"))
    }

    @Test
    fun remappingCoveredCompactIdsPreservesMustNotLose() {
        val needle = needles.getValue("security_boundary")
        val normalized = normalize(summary = "Summary.", mustNotLose = listOf(needle))
        val remapped = CompactSummaryPayloads.remapCoveredCompactIds(
            summary = normalized,
            idMapping = mapOf("old-compact" to "new-compact"),
        )
        val payload = CompactSummaryPayloads.parse(remapped)!!
        assertTrue(payload.mustNotLose.contains(needle))
    }
}
