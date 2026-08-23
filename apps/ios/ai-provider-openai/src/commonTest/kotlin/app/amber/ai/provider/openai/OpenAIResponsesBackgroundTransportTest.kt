package app.amber.ai.provider.openai

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAIResponsesBackgroundTransportTest {
    private val setting = ProviderSetting.OpenAI(
        apiKey = "sk-test",
        baseUrl = "https://api.openai.com/v1",
        useResponseApi = true,
    )

    private val params = TextGenerationParams(
        model = Model(modelId = "gpt-5", displayName = "GPT-5"),
    )

    @Test
    fun backgroundRequestForcesStoredStreamingMode() {
        val body = OpenAIResponsesBackgroundTransport.backgroundRequestBodyForTesting(
            providerSetting = setting,
            messages = listOf(userMessage("hello")),
            params = params,
        )

        assertTrue(body.getValue("background").jsonPrimitive.boolean)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        assertTrue(body.getValue("store").jsonPrimitive.boolean)
    }

    @Test
    fun resumeUrlUsesResponseEndpointAndSequenceCheckpoint() {
        assertEquals(
            "https://api.openai.com/v1/responses/resp_123?stream=true&starting_after=17",
            OpenAIResponsesBackgroundTransport.resumeUrl(setting, "resp_123", 17),
        )
        assertEquals(
            "https://api.openai.com/v1/responses/resp_123/cancel",
            OpenAIResponsesBackgroundTransport.cancelUrl(setting, "resp_123"),
        )
    }

    @Test
    fun responseIdCannotEscapeResponsePath() {
        listOf("resp/123", "resp?x=1", "resp#fragment", "resp 123", "").forEach { id ->
            assertFailsWith<IllegalArgumentException> {
                OpenAIResponsesBackgroundTransport.resumeUrl(setting, id, 0)
            }
        }
    }

    @Test
    fun onlyOfficialResponseApiCanUseBackgroundTransport() {
        assertFailsWith<IllegalArgumentException> {
            OpenAIResponsesBackgroundTransport.requireOfficialResponsesProvider(
                setting.copy(useResponseApi = false),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIResponsesBackgroundTransport.requireOfficialResponsesProvider(
                setting.copy(baseUrl = "https://proxy.example/v1"),
            )
        }
    }

    @Test
    fun httpStreamFailurePreservesStatusAsTypedFailure() {
        val failure = openAISseHttpFailure(
            statusCode = 401,
            body = "{\"error\":\"invalid_api_key\"}",
        )

        assertEquals(401, failure.statusCode)
        assertTrue(failure.message.orEmpty().contains("HTTP 401"))
        assertTrue(failure.message.orEmpty().contains("invalid_api_key"))
    }

    @Test
    fun chunkIsDeliveredBeforeItsCheckpointAndCreatedStillCheckpoints() {
        val events = mutableListOf<String>()
        val processor = OpenAIResponsesBackgroundEventProcessor(
            provider = OpenAIKmpProvider(),
            onChunk = { chunk -> events += "chunk:${chunk.choices.firstOrNull()?.delta?.text()}" },
            onCheckpoint = { responseId, sequence -> events += "checkpoint:$responseId:$sequence" },
            onComplete = { events += "complete" },
        )

        processor.consume(
            """
            {"type":"response.created","sequence_number":0,"response":{"id":"resp_123"}}
            """.trimIndent(),
        )
        processor.consume(
            """
            {"type":"response.output_text.delta","sequence_number":1,"item_id":"item_1","delta":"你好"}
            """.trimIndent(),
        )
        processor.consume(
            """
            {"type":"response.completed","sequence_number":2,"response":{"id":"resp_123","status":"completed","output":[]}}
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "checkpoint:resp_123:0",
                "chunk:你好",
                "checkpoint:resp_123:1",
                "checkpoint:resp_123:2",
                "complete",
            ),
            events,
        )
    }

    @Test
    fun failedTerminalCheckpointsBeforeSurfacingError() {
        val events = mutableListOf<String>()
        val processor = OpenAIResponsesBackgroundEventProcessor(
            provider = OpenAIKmpProvider(),
            onChunk = { events += "chunk" },
            onCheckpoint = { responseId, sequence -> events += "checkpoint:$responseId:$sequence" },
            onComplete = { events += "complete" },
        )

        processor.consume(
            """{"type":"response.created","sequence_number":0,"response":{"id":"resp_123"}}""",
        )
        val error = assertFailsWith<OpenAIResponsesBackgroundTerminalFailure> {
            processor.consume(
                """{"type":"response.failed","sequence_number":1,"response":{"id":"resp_123","status":"failed","error":{"message":"upstream unavailable"}}}""",
            )
        }

        assertTrue(error.message.orEmpty().contains("upstream unavailable"))
        assertEquals(listOf("checkpoint:resp_123:0", "checkpoint:resp_123:1"), events)
        assertFalse(events.contains("complete"))
    }

    @Test
    fun resumedStreamUsesPersistedResponseIdBeforeCreatedEvent() {
        val checkpoints = mutableListOf<String>()
        val processor = OpenAIResponsesBackgroundEventProcessor(
            provider = OpenAIKmpProvider(),
            onChunk = {},
            onCheckpoint = { responseId, sequence -> checkpoints += "$responseId:$sequence" },
            onComplete = {},
            initialResponseId = "resp_123",
        )

        processor.consume(
            """{"type":"response.output_text.delta","sequence_number":18,"item_id":"item_1","delta":"继续"}""",
        )

        assertEquals(listOf("resp_123:18"), checkpoints)
    }

    @Test
    fun resumedDoneMarkerDoesNotCompleteWithoutExplicitTerminalEvent() {
        val completions = mutableListOf<String>()
        val processor = OpenAIResponsesBackgroundEventProcessor(
            provider = OpenAIKmpProvider(),
            onChunk = {},
            onCheckpoint = { _, _ -> },
            onComplete = { completions += "complete" },
            initialResponseId = "resp_123",
        )

        processor.consume("[DONE]")

        assertTrue(completions.isEmpty())
        assertFailsWith<IllegalStateException> { processor.finish() }
    }

    @Test
    fun resumedCompletedEventStillCompletesAfterDoneMarker() {
        val completions = mutableListOf<String>()
        val processor = OpenAIResponsesBackgroundEventProcessor(
            provider = OpenAIKmpProvider(),
            onChunk = {},
            onCheckpoint = { _, _ -> },
            onComplete = { completions += "complete" },
            initialResponseId = "resp_123",
        )

        processor.consume(
            """{"type":"response.completed","sequence_number":18,"response":{"id":"resp_123","status":"completed","output":[]}}""",
        )
        processor.consume("[DONE]")

        assertEquals(listOf("complete"), completions)
    }

    @Test
    fun closedStreamWithoutTerminalIsAnError() {
        val processor = OpenAIResponsesBackgroundEventProcessor(
            provider = OpenAIKmpProvider(),
            onChunk = {},
            onCheckpoint = { _, _ -> },
            onComplete = {},
        )

        processor.consume(
            """{"type":"response.created","sequence_number":0,"response":{"id":"resp_123"}}""",
        )
        assertFailsWith<IllegalStateException> { processor.finish() }
    }

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun UIMessage.text(): String = parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}
