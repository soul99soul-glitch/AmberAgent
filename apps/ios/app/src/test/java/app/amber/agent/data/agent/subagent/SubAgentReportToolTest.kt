package app.amber.feature.subagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SubAgentReportToolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun reportToolCapturesSupervisorPayloadWithoutUsingVisibleText() = runBlocking {
        val capture = SubAgentReportCapture()

        val output = capture.tool().execute(
            json.parseToJsonElement(
                """
                {
                  "summary": "Reviewed the target files and found one blocking issue.",
                  "findings": ["Tool search must keep the report tool visible inside subagent runs."],
                  "evidence": ["subagent_report"],
                  "risks": ["Subagent could finish without a structured report."],
                  "recommended_next_steps": ["Treat missing report as fallback, not as a crash."],
                  "confidence": "high"
                }
                """.trimIndent()
            )
        )

        val ack = json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("ok", ack["status"]?.jsonPrimitive?.contentOrNull)

        val result = capture.latest
        assertNotNull(result)
        assertEquals(SubAgentRunStatus.COMPLETED, result!!.status)
        assertEquals("Reviewed the target files and found one blocking issue.", result.summary)
        assertEquals(listOf("Tool search must keep the report tool visible inside subagent runs."), result.findings)
        assertEquals(listOf("subagent_report"), result.evidence)
        assertEquals(listOf("Subagent could finish without a structured report."), result.risks)
        assertEquals(listOf("Treat missing report as fallback, not as a crash."), result.recommendedNextSteps)
        assertEquals("high", result.confidence)
    }

    @Test
    fun reportToolCapturesOptionalErrorField() = runBlocking {
        val capture = SubAgentReportCapture()

        capture.tool().execute(
            json.parseToJsonElement(
                """{"summary":"Could not finish","error":"blocked by missing file"}"""
            )
        )

        assertEquals("blocked by missing file", capture.latest?.error)
        assertEquals(SubAgentRunStatus.FAILED, capture.latest?.status)
    }

    @Test
    fun fallbackUsesVisibleTextOnlyWhenReportWasNotCaptured() {
        val capture = SubAgentReportCapture()

        val result = capture.resultOrFallback("Human-readable body that should stay visible in the panel.")

        assertEquals(SubAgentRunStatus.COMPLETED, result.status)
        assertEquals("Human-readable body that should stay visible in the panel.", result.summary)
        assertEquals(emptyList<String>(), result.findings)
        assertEquals(
            listOf("Subagent finished without calling subagent_report; summary was derived from visible text."),
            result.risks,
        )
    }

    @Test
    fun structuredReportWinsOverVisibleTextForSupervisor() = runBlocking {
        val capture = SubAgentReportCapture()
        capture.tool().execute(
            json.parseToJsonElement(
                """{"summary":"Short structured result","findings":["A"],"evidence":["B"]}"""
            )
        )

        val result = capture.resultOrFallback("Very long Markdown body intended for the human sheet.")

        assertEquals("Short structured result", result.summary)
        assertEquals(listOf("A"), result.findings)
        assertEquals(listOf("B"), result.evidence)
    }

    @Test
    fun fallbackSummaryIsCappedForSupervisorContext() {
        val capture = SubAgentReportCapture()
        val result = capture.resultOrFallback("x".repeat(5_000))

        assertEquals(4_000, result.summary.length)
    }
}
