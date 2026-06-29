package app.amber.ai.provider.openai

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIKmpProviderResponsesStreamTest {
    @Test
    fun functionCallArgumentsDoneCarriesCallIdAndName() {
        val chunk = OpenAIKmpProvider().parseResponseDelta(
            buildJsonObject {
                put("type", "response.function_call_arguments.done")
                put("item_id", "item_123")
                put("call_id", "call_123")
                put("name", "search_web")
                put("arguments", """{"query":"amber"}""")
            }
        )

        val tool = chunk!!.choices.single().delta!!.parts.single() as UIMessagePart.Tool
        assertEquals("call_123", tool.toolCallId)
        assertEquals("search_web", tool.toolName)
        assertEquals("""{"query":"amber"}""", tool.input)
    }

    @Test
    fun functionCallArgumentsDoneMergesWithAddedItemWhenDoneOmitsCallId() {
        val provider = OpenAIKmpProvider()
        val accumulator = MessageStreamAccumulator(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        )

        accumulator.append(provider.parseResponseDelta(buildJsonObject {
            put("type", "response.output_item.added")
            putJsonObject("item") {
                put("id", "fc_123")
                put("type", "function_call")
                put("call_id", "call_123")
                put("name", "generate_image")
                put("arguments", "")
            }
        })!!)
        accumulator.append(provider.parseResponseDelta(buildJsonObject {
            put("type", "response.function_call_arguments.done")
            put("item_id", "fc_123")
            put("arguments", """{"prompt":"Link from The Legend of Zelda"}""")
        })!!)

        val tools = accumulator.snapshot().single().parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("generate_image", tools.single().toolName)
        assertEquals("""{"prompt":"Link from The Legend of Zelda"}""", tools.single().input)
    }

    @Test
    fun completedDoesNotDuplicateArgumentsAfterAddedAndDone() {
        val provider = OpenAIKmpProvider()
        val accumulator = MessageStreamAccumulator(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        )
        val prompt = """{"prompt":"Link from The Legend of Zelda"}"""

        // response.output_item.added — empty arguments, has call_id
        accumulator.append(provider.parseResponseDelta(buildJsonObject {
            put("type", "response.output_item.added")
            putJsonObject("item") {
                put("id", "fc_123")
                put("type", "function_call")
                put("call_id", "call_123")
                put("name", "generate_image")
                put("arguments", "")
            }
        })!!)
        // response.function_call_arguments.done — item_id only, no call_id/name
        accumulator.append(provider.parseResponseDelta(buildJsonObject {
            put("type", "response.function_call_arguments.done")
            put("item_id", "fc_123")
            put("arguments", prompt)
        })!!)
        // response.completed — final function_call echoes the full arguments again
        accumulator.append(provider.parseResponseDelta(buildJsonObject {
            put("type", "response.completed")
            putJsonObject("response") {
                put("id", "resp_1")
                put("status", "completed")
                putJsonArray("output") {
                    add(buildJsonObject {
                        put("type", "function_call")
                        put("call_id", "call_123")
                        put("name", "generate_image")
                        put("arguments", prompt)
                    })
                }
            }
        })!!)

        val tools = accumulator.snapshot().single().parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("generate_image", tools.single().toolName)
        // arguments must appear exactly once — never "{...}{...}"
        assertEquals(prompt, tools.single().input)
    }
}
