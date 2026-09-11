package app.amber.core.ai

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.UIMessageChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-side normalization and capture for [GenerationRoundOutcome]: both
 * consumption points of a round — the streaming collect and the non-streaming
 * complete — feed [lastFinishReason] and the round returns
 * [finishReasonTruncatesOutput] of the last captured value, so these helpers
 * ARE the production truncation-guard seam (ChatGenerationRoundEngine).
 */
class GenerationRoundOutcomeTest {

    private fun chunk(
        finishReason: String?,
        streaming: Boolean = true,
        extraChoiceFinishReasons: List<String?> = emptyList(),
    ): MessageChunk = MessageChunk(
        id = "chunk",
        model = "model",
        choices = (listOf(finishReason) + extraChoiceFinishReasons).mapIndexed { index, reason ->
            UIMessageChoice(
                index = index,
                delta = if (streaming && index == 0) {
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("t")))
                } else {
                    null
                },
                message = if (!streaming && index == 0) {
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("t")))
                } else {
                    null
                },
                finishReason = reason,
            )
        },
    )

    @Test
    fun `output-limit reasons are recognized across provider vocabularies and case`() {
        assertTrue(finishReasonTruncatesOutput("length"))
        assertTrue(finishReasonTruncatesOutput("max_tokens"))
        assertTrue(finishReasonTruncatesOutput("max_output_tokens"))
        assertTrue(finishReasonTruncatesOutput("MAX_TOKENS"))
        assertTrue(finishReasonTruncatesOutput(" Length "))
    }

    @Test
    fun `normal stops and unknown reasons never count as truncation`() {
        assertFalse(finishReasonTruncatesOutput(null))
        assertFalse(finishReasonTruncatesOutput("stop"))
        assertFalse(finishReasonTruncatesOutput("tool_calls"))
        assertFalse(finishReasonTruncatesOutput("STOP"))
        assertFalse(finishReasonTruncatesOutput("end_turn"))
        assertFalse(finishReasonTruncatesOutput("some_future_reason"))
    }

    @Test
    fun `streaming capture takes the last non-null finish_reason across chunks`() {
        var captured: String? = null
        // A final chunk may carry only finishReason (no parts) — still wins.
        listOf(chunk(null), chunk("max_tokens"), chunk(null)).forEach { c ->
            c.lastFinishReason()?.let { captured = it }
        }
        assertEquals("max_tokens", captured)

        // A later normal stop after a truncation overwrites it.
        captured = null
        listOf(chunk("length"), chunk("stop")).forEach { c ->
            c.lastFinishReason()?.let { captured = it }
        }
        assertEquals("stop", captured)
    }

    @Test
    fun `non-streaming capture reads the single complete chunk`() {
        var captured: String? = null
        chunk("max_output_tokens", streaming = false).lastFinishReason()?.let { captured = it }
        assertEquals("max_output_tokens", captured)

        captured = null
        chunk(null, streaming = false).lastFinishReason()?.let { captured = it }
        assertEquals(null, captured)
    }

    @Test
    fun `multi-choice chunks resolve to the last choice carrying a reason`() {
        assertEquals("stop", chunk(null, extraChoiceFinishReasons = listOf("stop", null)).lastFinishReason())
        assertEquals("length", chunk(null, extraChoiceFinishReasons = listOf(null, "length")).lastFinishReason())
    }
}
