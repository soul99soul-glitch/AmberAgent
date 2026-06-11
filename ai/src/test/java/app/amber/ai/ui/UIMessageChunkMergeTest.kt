package app.amber.ai.ui

import app.amber.ai.core.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 覆盖旧 chunk merge 路径 (UIMessage.plus / appendChunk) 与共享 tool merge 语义。
 * 该路径与 MessageStreamAccumulator 必须保持一致, 共享 findToolMergeTarget。
 */
class UIMessageChunkMergeTest {

    @Test
    fun `text deltas merge in order`() {
        var message = emptyAssistant()
        message += chunk(UIMessagePart.Text("你"))
        message += chunk(UIMessagePart.Text("好"))
        message += chunk(UIMessagePart.Text("世界"))

        assertEquals("你好世界", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `late tool call id merges into stream-indexed tool and adopts the real id`() {
        var message = emptyAssistant()
        message += chunk(tool(id = "", name = "search", input = "{\"q\"", streamIndex = 0))
        message += chunk(tool(id = "call_abc", name = "", input = ":\"amber\"}", streamIndex = 0))

        val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("call_abc", tools.single().toolCallId)
        assertEquals("search", tools.single().toolName)
        assertEquals("""{"q":"amber"}""", tools.single().input)
    }

    @Test
    fun `parallel tool deltas merge by stream index without cross wiring`() {
        var message = emptyAssistant()
        message += chunk(tool(id = "tool_a", name = "search", input = "", streamIndex = 0))
        message += chunk(tool(id = "tool_b", name = "read_file", input = "", streamIndex = 1))
        message += chunk(tool(id = "", name = "", input = """{"q":"amber"}""", streamIndex = 0))

        val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("""{"q":"amber"}""", tools.first { it.toolCallId == "tool_a" }.input)
        assertEquals("", tools.first { it.toolCallId == "tool_b" }.input)
    }

    @Test
    fun `blank tool delta with unmatched index creates new part instead of cross wiring`() {
        var message = emptyAssistant()
        message += chunk(tool(id = "tool_a", name = "search", input = "{}", streamIndex = 0))
        message += chunk(tool(id = "", name = "read", input = "{}", streamIndex = 5))

        val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("{}", tools.first { it.toolCallId == "tool_a" }.input)
    }

    @Test
    fun `annotations append and dedupe instead of replacing`() {
        val citationA = UIMessageAnnotation.UrlCitation(title = "A", url = "https://a.example")
        val citationB = UIMessageAnnotation.UrlCitation(title = "B", url = "https://b.example")

        var message = emptyAssistant()
        message += annotatedChunk(citationA)
        message += annotatedChunk(citationA, citationB)

        assertEquals(listOf(citationA, citationB), message.annotations)
    }

    @Test
    fun `replace tool delta overrides accumulated args instead of appending`() {
        var message = emptyAssistant()
        message += chunk(tool(id = "call_1", name = "search", input = "{\"q\""))
        message += chunk(tool(id = "call_1", name = "", input = ":\"am"))
        // arguments.done 全量参数: replace 语义, 不与累积片段拼接
        message += chunk(
            tool(id = "call_1", name = "", input = """{"q":"amber"}""").withStreamArgsReplace()
        )

        val merged = message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("""{"q":"amber"}""", merged.input)
        assertEquals("search", merged.toolName)
        assertNull(
            "replace 控制标记不应留在合并结果里",
            merged.metadata?.get(STREAM_TOOL_ARGS_REPLACE_METADATA_KEY)
        )
    }

    @Test
    fun `blank replace delta keeps accumulated args`() {
        var message = emptyAssistant()
        message += chunk(tool(id = "call_1", name = "search", input = """{"q":"amber"}"""))
        message += chunk(tool(id = "call_1", name = "", input = "").withStreamArgsReplace())

        val merged = message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("""{"q":"amber"}""", merged.input)
    }

    @Test
    fun `replace delta creating new part strips control metadata`() {
        var message = emptyAssistant()
        message += chunk(tool(id = "call_9", name = "lookup", input = "{}").withStreamArgsReplace())

        val created = message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("{}", created.input)
        assertNull(created.metadata?.get(STREAM_TOOL_ARGS_REPLACE_METADATA_KEY))
    }

    @Test
    fun `withStreamToolIndex preserves existing metadata`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "id",
            toolName = "search",
            input = "{}",
            metadata = buildJsonObject { put("signature", "sig") }
        )

        val indexed = tool.withStreamToolIndex(3)

        assertEquals("sig", indexed.metadata?.get("signature")?.jsonPrimitive?.content)
        assertEquals(
            3,
            indexed.metadata?.get(STREAM_TOOL_INDEX_METADATA_KEY)?.jsonPrimitive?.intOrNull
        )
    }

    private fun emptyAssistant() = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

    private fun tool(
        id: String,
        name: String,
        input: String,
        streamIndex: Int? = null,
    ): UIMessagePart.Tool {
        val tool = UIMessagePart.Tool(toolCallId = id, toolName = name, input = input)
        return if (streamIndex != null) tool.withStreamToolIndex(streamIndex) else tool
    }

    private fun chunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = parts.toList()
                ),
                message = null,
                finishReason = null,
            )
        )
    )

    private fun annotatedChunk(vararg annotations: UIMessageAnnotation): MessageChunk = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("t")),
                    annotations = annotations.toList(),
                ),
                message = null,
                finishReason = null,
            )
        )
    )
}
