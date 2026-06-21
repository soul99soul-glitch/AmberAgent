package app.amber.ai.ui

import app.amber.ai.core.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageStreamAccumulatorToolTest {

    private fun baseMessages(): List<UIMessage> = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("use a tool"))),
    )

    private fun chunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "test",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            ),
        ),
    )

    @Test
    fun toolArgumentDeltaMergesByStreamIndexWhenCallIdIsBlank() {
        val acc = MessageStreamAccumulator(baseMessages(), model = null)

        acc.append(chunk(tool(id = "call_1", name = "workspace_file_list", input = "", streamIndex = 0)))
        acc.append(chunk(tool(id = "", name = "", input = """{"path":"/"}""", streamIndex = 0)))

        val tools = acc.snapshot().last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("call_1", tools.single().toolCallId)
        assertEquals("workspace_file_list", tools.single().toolName)
        assertEquals("""{"path":"/"}""", tools.single().input)
        assertEquals(0, tools.single().streamToolIndex())
    }

    @Test
    fun parallelToolArgumentDeltasStaySeparatedByStreamIndex() {
        val acc = MessageStreamAccumulator(baseMessages(), model = null)

        acc.append(
            chunk(
                tool(id = "call_a", name = "workspace_file_read", input = "", streamIndex = 0),
                tool(id = "call_b", name = "workspace_file_search", input = "", streamIndex = 1),
            )
        )
        acc.append(chunk(tool(id = "", name = "", input = """{"query":"b"}""", streamIndex = 1)))
        acc.append(chunk(tool(id = "", name = "", input = """{"file_id":"a"}""", streamIndex = 0)))

        val tools = acc.snapshot().last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("""{"file_id":"a"}""", tools.first { it.toolCallId == "call_a" }.input)
        assertEquals("""{"query":"b"}""", tools.first { it.toolCallId == "call_b" }.input)
    }

    @Test
    fun streamToolIndexPrefersFieldAndKeepsMetadataFallback() {
        val fieldTool = tool(id = "field", name = "x", input = "{}", streamIndex = 2).copy(
            metadata = buildJsonObject { put(STREAM_TOOL_INDEX_METADATA_KEY, 9) }
        )
        val legacyTool = tool(id = "legacy", name = "x", input = "{}", streamIndex = null).copy(
            metadata = buildJsonObject { put(STREAM_TOOL_INDEX_METADATA_KEY, 4) }
        )

        assertEquals(2, fieldTool.streamToolIndex())
        assertEquals(4, legacyTool.streamToolIndex())
    }

    private fun tool(
        id: String,
        name: String,
        input: String,
        streamIndex: Int?,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
        output = emptyList(),
        streamIndex = streamIndex,
    )
}
