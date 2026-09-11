package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.reasoningContentPresentMetadata
import app.amber.feature.subagent.SubAgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `blank reasoning markers do not split adjacent visible text`() {
        val blocks = listOf(
            UIMessagePart.Text("Amber"),
            UIMessagePart.Reasoning(
                reasoning = "",
                metadata = reasoningContentPresentMetadata(),
            ),
            UIMessagePart.Text("Agent"),
        ).groupMessageParts()

        assertEquals(1, blocks.size)
        val block = blocks.single() as MessagePartBlock.ContentBlock
        assertEquals("AmberAgent", (block.part as UIMessagePart.Text).text)
    }

    @Test
    fun `followup send and interrupt share the original subagent card`() {
        val blocks = listOf(
            tool(
                name = "subagent_start",
                input = "{\"subagent_id\":\"explorer\"}",
                output = "{\"status\":\"completed\",\"run_id\":\"thread-1\"}",
            ),
            tool(
                name = "subagent_followup",
                input = "{\"thread_id\":\"thread-1\",\"task\":{\"objective\":\"continue\"}}",
                output = "{\"status\":\"running\",\"run_id\":\"thread-1\"}",
            ),
            tool(
                name = "subagent_send_message",
                input = "{\"thread_id\":\"thread-1\",\"message\":\"steer\"}",
                output = "{\"status\":\"ok\",\"thread_id\":\"thread-1\",\"delivery_state\":\"delivered\"}",
            ),
            tool(
                name = "subagent_interrupt",
                input = "{\"thread_id\":\"thread-1\"}",
                output = "{\"status\":\"interrupted\",\"run_id\":\"thread-1\"}",
            ),
        ).groupMessageParts()

        val block = blocks.single() as MessagePartBlock.SubAgentBlock
        assertEquals("thread-1", block.step.runId)
        assertEquals(
            listOf(
                "subagent_start",
                "subagent_followup",
                "subagent_send_message",
                "subagent_interrupt",
            ),
            block.step.tools.map { it.toolName },
        )
    }

    @Test
    fun `queued message keeps prior result but card reports pending receipt`() {
        val tools = listOf(
            tool(
                name = "subagent_start",
                input = "{\"subagent_id\":\"explorer\"}",
                output = "{\"status\":\"completed\",\"run_id\":\"thread-1\"}",
            ),
            tool(
                name = "subagent_send_message",
                input = "{\"thread_id\":\"thread-1\",\"message\":\"later\"}",
                output = "{\"status\":\"ok\",\"thread_id\":\"thread-1\",\"delivery_state\":\"queued\"}",
            ),
        )

        val state = deriveSubAgentCardState(tools)
        assertEquals(SubAgentRunStatus.COMPLETED, state.status)
        assertTrue(state.isQueued)
        assertTrue(state.isPendingReceipt)
        assertEquals("补充已排队", state.statusVerb(SubAgentRunStatus.COMPLETED))
    }

    @Test
    fun `followup starts a distinct current turn`() {
        val state = deriveSubAgentCardState(
            listOf(
                tool(
                    name = "subagent_start",
                    input = "{\"subagent_id\":\"explorer\"}",
                    output = "{\"status\":\"completed\",\"run_id\":\"thread-1\"}",
                ),
                tool(
                    name = "subagent_followup",
                    input = "{\"thread_id\":\"thread-1\",\"task\":{\"objective\":\"continue\"}}",
                    output = "{\"status\":\"running\",\"run_id\":\"thread-1\"}",
                ),
            ),
        )

        assertEquals(2, state.turn)
        assertEquals(SubAgentRunStatus.RUNNING, state.status)
        assertEquals("第2轮 正在工作", state.statusVerb(state.status))
    }

    private fun tool(name: String, input: String, output: String): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = name,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output)),
        )
}
