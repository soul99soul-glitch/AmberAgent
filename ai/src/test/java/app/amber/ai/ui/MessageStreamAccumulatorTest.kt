package app.amber.ai.ui

import app.amber.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStreamAccumulatorTest {
    @Test(timeout = 4_000)
    fun `20k tiny text and reasoning chunks merge without repeated full string copies`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        repeat(20_000) {
            accumulator.append(chunk(UIMessagePart.Text("x")))
        }
        repeat(20_000) {
            accumulator.append(chunk(UIMessagePart.Reasoning("r", finishedAt = null)))
        }

        val assistant = accumulator.snapshot().last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals(20_000, assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text.length)
        assertEquals(20_000, assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning.length)
    }

    @Test
    fun `explicit empty reasoning marker does not split streamed text`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Text("你好")))
        accumulator.append(
            chunk(
                UIMessagePart.Reasoning(
                    reasoning = "",
                    metadata = reasoningContentPresentMetadata()
                )
            )
        )
        accumulator.append(chunk(UIMessagePart.Text("啊")))

        val assistant = accumulator.snapshot().last()
        assertEquals("你好啊", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("", reasoning.reasoning)
        assertTrue(reasoning.hasExplicitReasoningContentField())
    }

    @Test
    fun `reasoning finishes when streamed text starts`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Reasoning("thinking", finishedAt = null)))
        accumulator.append(chunk(UIMessagePart.Text("answer")))

        val assistant = accumulator.snapshot().last()
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertNotNull(reasoning.finishedAt)
        assertEquals("answer", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `empty reasoning marker in text chunk does not keep reasoning timer open`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Reasoning("thinking", finishedAt = null)))
        accumulator.append(
            chunk(
                UIMessagePart.Reasoning(
                    reasoning = "",
                    finishedAt = null,
                    metadata = reasoningContentPresentMetadata()
                ),
                UIMessagePart.Text("answer"),
            )
        )

        val assistant = accumulator.snapshot().last()
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertNotNull(reasoning.finishedAt)
        assertEquals("answer", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `final full message replaces streamed deltas instead of appending again`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Text("{\"summary\":\"半")))
        accumulator.append(chunk(UIMessagePart.Text("截\"")))
        accumulator.append(finalMessage("""{"summary":"完整 JSON"}"""))

        val assistant = accumulator.snapshot().last()
        assertEquals("""{"summary":"完整 JSON"}""", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `chinese text deltas merge in order`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(UIMessagePart.Text("你")))
        accumulator.append(chunk(UIMessagePart.Text("好")))
        accumulator.append(chunk(UIMessagePart.Text("世界")))

        val assistant = accumulator.snapshot().last()
        assertEquals("你好世界", assistant.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `late tool call id merges into stream-indexed tool and adopts the real id`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        // OpenAI 流式时序: 首个 delta 只有 index 没有 id, 真实 id 随后到达
        accumulator.append(chunk(tool(id = "", name = "search", input = "{\"q\"", streamIndex = 0)))
        accumulator.append(chunk(tool(id = "call_abc", name = "", input = ":\"amber\"}", streamIndex = 0)))

        val tools = accumulator.snapshot().last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("call_abc", tools.single().toolCallId)
        assertEquals("search", tools.single().toolName)
        assertEquals("""{"q":"amber"}""", tools.single().input)
    }

    @Test
    fun `parallel tool deltas merge by stream index without cross wiring`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        // Claude 并行 tool_use: input_json_delta 只带 block index, 不带 id
        accumulator.append(chunk(tool(id = "tool_a", name = "search", input = "", streamIndex = 0)))
        accumulator.append(chunk(tool(id = "tool_b", name = "read_file", input = "", streamIndex = 1)))
        accumulator.append(chunk(tool(id = "", name = "", input = """{"q":"amber"}""", streamIndex = 0)))

        val tools = accumulator.snapshot().last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals("""{"q":"amber"}""", tools.first { it.toolCallId == "tool_a" }.input)
        assertEquals("", tools.first { it.toolCallId == "tool_b" }.input)
    }

    @Test
    fun `blank tool delta without index falls back to last tool`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )

        accumulator.append(chunk(tool(id = "call_1", name = "search", input = "{\"q\"")))
        accumulator.append(chunk(tool(id = "", name = "", input = ":\"x\"}")))

        val tools = accumulator.snapshot().last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("""{"q":"x"}""", tools.single().input)
    }

    @Test
    fun `annotations accumulate across chunks and dedupe repeats`() {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.user("go"))
        )
        val citationA = UIMessageAnnotation.UrlCitation(title = "A", url = "https://a.example")
        val citationB = UIMessageAnnotation.UrlCitation(title = "B", url = "https://b.example")

        accumulator.append(annotatedChunk(citationA))
        // 模拟 provider 重发全量 grounding (含已有条目) + 新增条目
        accumulator.append(annotatedChunk(citationA, citationB))

        val assistant = accumulator.snapshot().last()
        assertEquals(listOf(citationA, citationB), assistant.annotations)
    }

    private fun tool(
        id: String,
        name: String,
        input: String,
        streamIndex: Int? = null,
    ): UIMessagePart.Tool {
        val tool = UIMessagePart.Tool(toolCallId = id, toolName = name, input = input)
        return if (streamIndex != null) tool.withStreamToolIndex(streamIndex) else tool
    }

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

    private fun finalMessage(text: String): MessageChunk = MessageChunk(
        id = "final",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = UIMessage.assistant(text),
                finishReason = null,
            )
        )
    )
}
