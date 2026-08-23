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
        val provider = ClaudeProvider(okhttp3.OkHttpClient())
        val event = Json.parseToJsonElement(
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
        ).jsonObject

        val finishReason = provider.claudeStreamFinishReason(event)

        assertEquals("max_tokens", finishReason)
        assertTrue(
            provider.shouldEmitClaudeStreamChunk(
                hasParts = false,
                hasUsage = false,
                finishReason = finishReason,
            ),
        )
    }

    @Test
    fun `google partial candidate clean EOF without finish reason fails`() {
        val provider = GoogleProvider(okhttp3.OkHttpClient())
        val partialCandidates = Json.parseToJsonElement(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"partial"}]}}]}""",
        ).jsonObject.getValue("candidates").jsonArray
        val guard = StreamTerminationGuard(StreamProtocol.GOOGLE)

        with(provider) { guard.observeGoogleCandidates(partialCandidates) }

        assertTrue(guard.cleanEofCause() is IncompleteStreamException)
        assertNull(provider.googleCandidateFinishReason(partialCandidates.single().jsonObject))
    }
}
