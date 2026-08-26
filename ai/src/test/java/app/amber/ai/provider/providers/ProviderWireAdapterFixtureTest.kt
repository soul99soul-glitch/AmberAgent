package app.amber.ai.provider.providers

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.hasStreamFallbackId
import app.amber.ai.ui.streamToolIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderWireAdapterFixtureTest {
    @Test
    fun `anthropic official event sequence preserves tool index usage and terminal state`() {
        val adapter = AnthropicMessagesAdapter()

        val start = expect<AnthropicStreamSignal.Emit>(
            adapter.decodeStreamEvent(
                eventName = "message_start",
                eventId = null,
                data = """{"type":"message_start","message":{"id":"msg_1","model":"claude-test","usage":{"input_tokens":2,"cache_read_input_tokens":3,"cache_creation_input_tokens":1,"output_tokens":0}}}""",
            ),
        )
        assertEquals("msg_1", start.chunk.id)
        assertEquals(6, start.chunk.usage?.promptTokens)

        val toolStart = expect<AnthropicStreamSignal.Emit>(
            adapter.decodeStreamEvent(
                eventName = "content_block_start",
                eventId = "evt_1",
                data = """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool_1","name":"lookup","input":{}}}""",
            ),
        )
        val tool = expect<UIMessagePart.Tool>(toolStart.chunk.choices.single().delta!!.parts.single())
        assertEquals("tool_1", tool.toolCallId)
        assertEquals(2, tool.streamToolIndex())

        val arguments = expect<AnthropicStreamSignal.Emit>(
            adapter.decodeStreamEvent(
                eventName = "content_block_delta",
                eventId = "evt_2",
                data = """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"amber\"}"}}""",
            ),
        )
        assertEquals(2, (arguments.chunk.choices.single().delta!!.parts.single() as UIMessagePart.Tool).streamToolIndex())

        val terminal = expect<AnthropicStreamSignal.Emit>(
            adapter.decodeStreamEvent(
                eventName = "message_delta",
                eventId = null,
                data = """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
            ),
        )
        assertEquals("tool_use", terminal.chunk.choices.single().finishReason)
        assertEquals(7, terminal.chunk.usage?.completionTokens)
        expect<AnthropicStreamSignal.Ignore>(
            adapter.decodeStreamEvent("ping", null, """{"type":"ping"}"""),
        )
        expect<AnthropicStreamSignal.Failure>(
            adapter.decodeStreamEvent(
                "error",
                null,
                """{"type":"error","error":{"message":"overloaded"}}""",
            ),
        )
    }

    @Test
    fun `gemini official wrapped chunk preserves provider tool id citations usage and errors`() {
        val decoder = GeminiGenerateContentAdapter().streamDecoder("gemini-test")
        val signal = expect<GeminiStreamSignal.Emit>(
            decoder.decode(
                """
                {
                  "response": {
                    "candidates": [{
                      "index": 4,
                      "content": {"role":"model","parts":[
                        {"text":"checking","thought":true},
                        {"text":"result"},
                        {"functionCall":{"id":"call_7","name":"lookup","args":{"q":"amber"}},"thoughtSignature":"sig"}
                      ]},
                      "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com","title":"Example"}}]},
                      "finishReason":"STOP"
                    }],
                    "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":3,"thoughtsTokenCount":2,"totalTokenCount":9}
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("STOP"), signal.finishReasons)
        assertEquals(5, signal.chunk.usage?.completionTokens)
        assertEquals(4, signal.chunk.choices.single().index)
        val message = signal.chunk.choices.single().delta!!
        expect<UIMessagePart.Reasoning>(message.parts[0])
        assertEquals("result", (message.parts[1] as UIMessagePart.Text).text)
        val tool = expect<UIMessagePart.Tool>(message.parts[2])
        assertEquals("call_7", tool.toolCallId)
        assertEquals(0, tool.streamToolIndex())
        assertTrue(message.annotations.single() is UIMessageAnnotation.UrlCitation)

        expect<GeminiStreamSignal.Failure>(
            decoder.decode("""{"error":{"message":"capacity exhausted"}}"""),
        )
    }

    @Test
    fun `gemini reasoning request preserves thought flag and signature`() {
        val contents = GeminiGenerateContentAdapter().encodeContents(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(
                            reasoning = "private chain",
                            metadata = buildJsonObject { put("thoughtSignature", "sig-9") },
                        ),
                        UIMessagePart.Text("answer"),
                    ),
                ),
            ),
        )

        val reasoning = contents.single().jsonObject.getValue("parts").jsonArray.first().jsonObject
        assertEquals("private chain", reasoning.getValue("text").jsonPrimitive.content)
        assertEquals(true, reasoning.getValue("thought").jsonPrimitive.content.toBoolean())
        assertEquals("sig-9", reasoning.getValue("thoughtSignature").jsonPrimitive.content)
    }

    @Test
    fun `gemini completion uses provider candidate index and rejects error envelope`() {
        val adapter = GeminiGenerateContentAdapter()
        val completion = adapter.decodeCompletion(
            Json.parseToJsonElement(
                """{"candidates":[{"index":7,"content":{"role":"model","parts":[{"text":"done"}]},"finishReason":"STOP"}]}""",
            ).jsonObject,
            modelId = "gemini-test",
        )
        assertEquals(7, completion.choices.single().index)

        val error = runCatching {
            adapter.decodeCompletion(
                Json.parseToJsonElement("""{"error":{"message":"quota exceeded"}}""").jsonObject,
                modelId = "gemini-test",
            )
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("quota exceeded") == true)
    }

    @Test
    fun `gemini late provider tool id replaces only marked fallback id`() {
        val adapter = GeminiGenerateContentAdapter()
        val fallback = expect<UIMessagePart.Tool>(
            adapter.decodePart(
                Json.parseToJsonElement(
                    """{"functionCall":{"name":"lookup","args":{"q":"amber"}}}""",
                ).jsonObject,
                nextFallbackToolId = { "generated-1" },
            ),
        )
        val providerId = expect<UIMessagePart.Tool>(
            adapter.decodePart(
                Json.parseToJsonElement(
                    """{"functionCall":{"id":"call-real","name":"","args":{}}}""",
                ).jsonObject,
            ),
        )

        assertTrue(fallback.hasStreamFallbackId())
        val merged = fallback.merge(providerId)
        assertEquals("call-real", merged.toolCallId)
        assertTrue(!merged.hasStreamFallbackId())
    }

    private inline fun <reified T> expect(value: Any): T {
        assertTrue("Expected ${T::class.simpleName}, got ${value::class.simpleName}", value is T)
        return value as T
    }
}
