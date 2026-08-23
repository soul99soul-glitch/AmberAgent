package app.amber.feature.chat.impl

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.chat.api.StreamToolState
import app.amber.feature.chat.api.ToolStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class InterruptedRunProjectionTest {

    private val assistantId = Uuid.random()

    private fun conversationOf(vararg messages: UIMessage) = Conversation(
        assistantId = assistantId,
        messageNodes = messages.map { MessageNode(messages = listOf(it)) },
    )

    private fun assistantMessage(parts: List<UIMessagePart>) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts,
    )

    private fun checkpointFor(
        message: UIMessage,
        toolStates: List<ToolStateSnapshot> = message.toolStateSnapshots(),
        partsHash: String = message.streamPartsHash(),
    ) = ChatEventPayload.StreamCheckpoint(
        conversationId = Uuid.random().toString(),
        messageId = message.id.toString(),
        partsHash = partsHash,
        toolStates = toolStates,
    )

    private fun tool(
        approvalState: ToolApprovalState,
        output: List<UIMessagePart> = emptyList(),
        id: String = Uuid.random().toString(),
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "search",
        input = """{"q":"hi"}""",
        output = output,
        approvalState = approvalState,
    )

    private fun Conversation.projectedTail(): UIMessage =
        messageNodes.last().currentMessage

    @Test
    fun `marks tail assistant message interrupted and stamps finishedAt`() {
        val tail = assistantMessage(listOf(UIMessagePart.Text("partial answer")))
        val conversation = conversationOf(UIMessage.user("hi"), tail)

        val projected = InterruptedRunProjection.project(conversation, checkpointFor(tail))

        val message = projected.projectedTail()
        val annotation = message.annotations
            .filterIsInstance<UIMessageAnnotation.GenerationInterrupted>()
            .single()
        assertEquals(InterruptedRunProjection.DEFAULT_REASON, annotation.reason)
        assertNotNull(message.finishedAt)
    }

    @Test
    fun `appends stale suffix when persisted tail lags the checkpoint hash`() {
        val tail = assistantMessage(listOf(UIMessagePart.Text("part")))
        val conversation = conversationOf(UIMessage.user("hi"), tail)
        val checkpoint = checkpointFor(tail, partsHash = "different-hash")

        val projected = InterruptedRunProjection.project(conversation, checkpoint)

        val annotation = projected.projectedTail().annotations
            .filterIsInstance<UIMessageAnnotation.GenerationInterrupted>()
            .single()
        assertEquals(
            InterruptedRunProjection.DEFAULT_REASON + InterruptedRunProjection.STALE_TAIL_SUFFIX,
            annotation.reason,
        )
    }

    @Test
    fun `preserves pending approval tools`() {
        val pending = tool(ToolApprovalState.Pending)
        val tail = assistantMessage(listOf(UIMessagePart.Text("x"), pending))
        val conversation = conversationOf(UIMessage.user("hi"), tail)

        val projected = InterruptedRunProjection.project(conversation, checkpointFor(tail))

        val projectedTool = projected.projectedTail().getTools().single()
        assertTrue(projectedTool.isPending)
        assertFalse(projectedTool.isExecuted)
    }

    @Test
    fun `marks half-streamed tool args as not executable`() {
        val streaming = tool(ToolApprovalState.Auto)
        val tail = assistantMessage(listOf(streaming))
        val conversation = conversationOf(UIMessage.user("hi"), tail)

        val projected = InterruptedRunProjection.project(conversation, checkpointFor(tail))

        val projectedTool = projected.projectedTail().getTools().single()
        // synthetic interrupted output + denial: can never execute truncated args
        assertTrue(projectedTool.isExecuted)
        assertTrue(projectedTool.approvalState is ToolApprovalState.Denied)
        val outputText = (projectedTool.output.single() as UIMessagePart.Text).text
        assertTrue(outputText.contains("\"status\":\"interrupted\""))
    }

    @Test
    fun `restores pending approval lost in the snapshot window`() {
        // Conversation snapshot is older: tool still Auto, but the checkpoint
        // saw it awaiting approval — Pending must be restored, not denied.
        val lostPending = tool(ToolApprovalState.Auto)
        val tail = assistantMessage(listOf(lostPending))
        val conversation = conversationOf(UIMessage.user("hi"), tail)
        val checkpoint = checkpointFor(
            tail,
            toolStates = listOf(
                ToolStateSnapshot(lostPending.toolCallId, "search", StreamToolState.PENDING_APPROVAL)
            ),
        )

        val projected = InterruptedRunProjection.project(conversation, checkpoint)

        val projectedTool = projected.projectedTail().getTools().single()
        assertTrue(projectedTool.isPending)
        assertFalse(projectedTool.isExecuted)
    }

    @Test
    fun `leaves executed and user-decided tools untouched`() {
        val executed = tool(ToolApprovalState.Auto, output = listOf(UIMessagePart.Text("ok")))
        val approved = tool(ToolApprovalState.Approved)
        val tail = assistantMessage(listOf(executed, approved))
        val conversation = conversationOf(UIMessage.user("hi"), tail)

        val projected = InterruptedRunProjection.project(conversation, checkpointFor(tail))

        val tools = projected.projectedTail().getTools()
        assertEquals(listOf(UIMessagePart.Text("ok")), tools[0].output)
        assertEquals(ToolApprovalState.Approved, tools[1].approvalState)
        assertFalse(tools[1].isExecuted)
    }

    @Test
    fun `falls back to last node when checkpointed tail is missing from snapshot`() {
        val persistedTail = assistantMessage(listOf(UIMessagePart.Text("older content")))
        val conversation = conversationOf(UIMessage.user("hi"), persistedTail)
        // checkpoint references a message created after the last snapshot
        val checkpoint = ChatEventPayload.StreamCheckpoint(
            conversationId = Uuid.random().toString(),
            messageId = Uuid.random().toString(),
            partsHash = "whatever",
        )

        val projected = InterruptedRunProjection.project(conversation, checkpoint)

        val annotation = projected.projectedTail().annotations
            .filterIsInstance<UIMessageAnnotation.GenerationInterrupted>()
            .single()
        assertTrue(annotation.reason.endsWith(InterruptedRunProjection.STALE_TAIL_SUFFIX))
    }

    @Test
    fun `does nothing when fallback tail is not an assistant message`() {
        val conversation = conversationOf(UIMessage.user("hi"))
        val checkpoint = ChatEventPayload.StreamCheckpoint(
            conversationId = Uuid.random().toString(),
            messageId = Uuid.random().toString(),
            partsHash = "x",
        )

        val projected = InterruptedRunProjection.project(conversation, checkpoint)

        assertSame(conversation, projected)
    }

    @Test
    fun `projection is idempotent`() {
        val tail = assistantMessage(listOf(UIMessagePart.Text("partial")))
        val conversation = conversationOf(UIMessage.user("hi"), tail)
        val checkpoint = checkpointFor(tail)

        val once = InterruptedRunProjection.project(conversation, checkpoint)
        val twice = InterruptedRunProjection.project(once, checkpoint)

        assertSame(once, twice)
        assertEquals(
            1,
            twice.projectedTail().annotations
                .count { it is UIMessageAnnotation.GenerationInterrupted },
        )
    }

    @Test
    fun `closes dangling reasoning parts`() {
        val tail = assistantMessage(
            listOf(UIMessagePart.Reasoning(reasoning = "thinking...", finishedAt = null))
        )
        val conversation = conversationOf(UIMessage.user("hi"), tail)

        val projected = InterruptedRunProjection.project(conversation, checkpointFor(tail))

        val reasoning = projected.projectedTail().parts
            .filterIsInstance<UIMessagePart.Reasoning>()
            .single()
        assertNotNull(reasoning.finishedAt)
    }
}
