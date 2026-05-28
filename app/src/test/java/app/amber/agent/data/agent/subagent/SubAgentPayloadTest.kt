package app.amber.feature.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun runPayloadOmitsDisplayTextByDefault() {
        val payload = subAgentRunToPayload(run(displayText = "Full human transcript"), json)

        assertEquals("completed", payload["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Full human transcript".length, payload["display_text_chars"]?.jsonPrimitive?.int)
        assertEquals("Review a small change.", payload["task_objective"]?.jsonPrimitive?.contentOrNull)
        assertFalse(payload.containsKey("display_text"))
        assertFalse(payload.containsKey("definition"))
        assertFalse(payload.containsKey("task"))
        assertFalse(payload.containsKey("transcript_path"))
        assertTrue(payload.containsKey("result"))
    }

    @Test
    fun runPayloadCanIncludeDisplayTextForTranscriptEventsOnly() {
        val payload = subAgentRunToPayload(
            run(displayText = "Full human transcript"),
            json,
            includeDisplayText = true,
        )

        assertEquals("Full human transcript", payload["display_text"]?.jsonPrimitive?.contentOrNull)
    }

    private fun run(displayText: String) = SubAgentRun(
        runId = "run-1",
        parentConversationId = Uuid.random(),
        definition = SubAgentDefinition(
            id = "reviewer",
            name = "Reviewer",
            description = "Use when a bounded review is useful.",
            systemPrompt = "Boundaries: read only. Report output as findings with evidence.",
            toolAllowlist = setOf("file_read"),
        ),
        task = SubAgentTaskSpec(
            objective = "Review a small change.",
            outputFormat = "Findings with evidence.",
            toolsAndSources = "Use file_read.",
            boundaries = "Do not edit.",
        ),
        status = SubAgentRunStatus.COMPLETED,
        result = SubAgentResult(
            status = SubAgentRunStatus.COMPLETED,
            summary = "Done",
            confidence = "high",
        ),
        displayText = displayText,
        transcriptPath = "/tmp/run-1.jsonl",
        startedAtMs = 1L,
        updatedAtMs = 2L,
    )
}
