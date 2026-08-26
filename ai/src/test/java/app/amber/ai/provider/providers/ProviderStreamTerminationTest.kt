package app.amber.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderStreamTerminationTest {
    @Test
    fun `claude message delta preserves max tokens without text parts`() {
        val signal = AnthropicMessagesAdapter().decodeStreamEvent(
            eventName = "message_delta",
            eventId = null,
            data = """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
        ) as AnthropicStreamSignal.Emit

        assertEquals("max_tokens", signal.chunk.choices.single().finishReason)
    }

    @Test
    fun `google partial candidate clean EOF without finish reason fails`() {
        val adapter = GeminiGenerateContentAdapter()
        val partialCandidates = Json.parseToJsonElement(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"partial"}]}}]}""",
        ).jsonObject.getValue("candidates").jsonArray
        val guard = StreamTerminationGuard(StreamProtocol.GOOGLE)

        partialCandidates.forEach { candidate ->
            guard.observeFinishReason(adapter.finishReason(candidate.jsonObject))
        }

        assertTrue(guard.cleanEofCause() is IncompleteStreamException)
        assertNull(adapter.finishReason(partialCandidates.single().jsonObject))
    }

    @Test
    fun `google usage only chunk does not mark stream complete`() {
        val signal = GeminiGenerateContentAdapter().streamDecoder("gemini-test").decode(
            """{"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":3,"totalTokenCount":5}}""",
        ) as GeminiStreamSignal.Emit
        val guard = StreamTerminationGuard(StreamProtocol.GOOGLE)

        signal.finishReasons.forEach(guard::observeFinishReason)

        assertEquals(5, signal.chunk.usage?.totalTokens)
        assertTrue(guard.cleanEofCause() is IncompleteStreamException)
    }
}
