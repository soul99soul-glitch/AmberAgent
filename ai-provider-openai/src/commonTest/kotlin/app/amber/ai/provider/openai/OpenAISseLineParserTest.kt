package app.amber.ai.provider.openai

import app.amber.ai.ui.UIMessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAISseLineParserTest {
    @Test
    fun emitsStandaloneJsonDataLineWithoutWaitingForBlankSeparator() {
        val parser = OpenAISseLineParser()

        assertEquals(
            SseEvent.Event(
                id = null,
                type = null,
                data = "{\"choices\":[{\"delta\":{\"content\":\"first\"}}]}",
            ),
            parser.consume("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}")
        )
        assertEquals(
            SseEvent.Event(
                id = null,
                type = null,
                data = "{\"choices\":[{\"delta\":{\"content\":\"second\"}}]}",
            ),
            parser.consume("data: {\"choices\":[{\"delta\":{\"content\":\"second\"}}]}")
        )
        assertNull(parser.consume(""))
    }

    @Test
    fun preservesStandardMultilineDataUntilBlankSeparator() {
        val parser = OpenAISseLineParser()

        assertNull(parser.consume("event: custom"))
        assertNull(parser.consume("data: {"))
        assertNull(parser.consume("data:   \"value\": \"text\""))
        assertNull(parser.consume("data: }"))
        assertEquals(
            SseEvent.Event(
                id = null,
                type = "custom",
                data = "{\n  \"value\": \"text\"\n}",
            ),
            parser.consume("")
        )
    }

    @Test
    fun doneMarkerIsEmittedImmediately() {
        val parser = OpenAISseLineParser()

        assertEquals(
            SseEvent.Event(id = null, type = null, data = "[DONE]"),
            parser.consume("data: [DONE]")
        )
        assertNull(parser.finish())
    }

    @Test
    fun rawNdjsonFrameIsEmittedImmediately() {
        val parser = OpenAISseLineParser()
        val payload = "{\"choices\":[{\"delta\":{\"content\":\"token\"}}]}"

        assertEquals(SseEvent.Event(id = null, type = null, data = payload), parser.consume(payload))
        assertNull(parser.finish())
    }

    @Test
    fun chatCompletionBatchPreservesEveryPayloadInOrder() {
        val chunks = OpenAIKmpProvider().parseChatCompletionStreamData(
            """data: {"id":"1","choices":[{"delta":{"content":"first"}}]}
data: {"id":"2","choices":[{"delta":{"content":"second"}}]}"""
        )

        assertEquals(listOf("first", "second"), chunks.map(::textDelta))
    }

    @Test
    fun responsesBatchPreservesEveryPayloadInOrder() {
        val chunks = OpenAIKmpProvider().parseResponsesStreamData(
            """data: {"type":"response.output_text.delta","item_id":"1","delta":"first"}
data: {"type":"response.output_text.delta","item_id":"1","delta":"second"}"""
        )

        assertEquals(listOf("first", "second"), chunks.map(::textDelta))
    }

    private fun textDelta(chunk: app.amber.ai.ui.MessageChunk): String =
        (chunk.choices.single().delta!!.parts.single() as UIMessagePart.Text).text
}
