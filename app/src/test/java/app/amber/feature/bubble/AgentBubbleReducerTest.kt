package app.amber.feature.bubble

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBubbleReducerTest {

    private val conversationA = "11111111-1111-1111-1111-111111111111"
    private val conversationB = "22222222-2222-2222-2222-222222222222"

    private fun runningScreenTool(
        toolName: String = "screen_click",
        status: ToolActivityStatus = ToolActivityStatus.RUNNING,
        conversationId: String? = conversationA,
    ) = SandboxActivityUiState(
        toolCallId = "call_1",
        toolName = toolName,
        title = "点击屏幕",
        status = status,
        conversationId = conversationId,
    )

    private fun assistantMessage(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun pendingToolPart(toolName: String = "screen_click", callId: String = "call_p") =
        UIMessagePart.Tool(
            toolCallId = callId,
            toolName = toolName,
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )

    private fun textPart(text: String) = UIMessagePart.Text(text = text)

    // ── pickConversation ──

    @Test
    fun `pick keeps previous conversation while still active`() {
        val prev = AgentBubbleUiState(conversationId = conversationB)
        val picked = AgentBubbleReducer.pickConversation(
            activeIds = setOf(conversationA, conversationB),
            activityConversationId = conversationA,
            previous = prev,
        )
        assertEquals(conversationB, picked)
    }

    @Test
    fun `pick falls back to activity conversation then first active`() {
        val prev = AgentBubbleUiState(conversationId = "gone")
        assertEquals(
            conversationA,
            AgentBubbleReducer.pickConversation(setOf(conversationA, conversationB), conversationA, prev),
        )
        assertEquals(
            conversationA,
            AgentBubbleReducer.pickConversation(setOf(conversationA, conversationB), null, prev),
        )
    }

    @Test
    fun `pick returns null when nothing active`() {
        assertNull(AgentBubbleReducer.pickConversation(emptySet(), conversationA, AgentBubbleUiState()))
    }

    // ── reduce：运行中 ──

    @Test
    fun `running state with no messages yields empty preview`() {
        val state = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = conversationA,
            activity = null,
            messages = null,
            nowMillis = 1_000L,
        )
        assertEquals(AgentBubblePhase.RUNNING, state.phase)
        assertEquals(conversationA, state.conversationId)
        assertFalse(state.waitingApproval)
        assertEquals("", state.replyPreview)
        assertFalse(state.stepRunning)
    }

    @Test
    fun `pending approval on last tool surfaces approval`() {
        val state = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = conversationA,
            activity = null,
            messages = listOf(
                assistantMessage(
                    textPart("开始操作"),
                    pendingToolPart(toolName = "screen_click", callId = "call_p"),
                ),
            ),
            nowMillis = 1_000L,
        )
        assertTrue(state.waitingApproval)
        assertFalse(state.waitingAskUser)
        assertEquals("call_p", state.approvalToolCallId)
        assertEquals("screen_click", state.approvalToolTitle)
    }

    @Test
    fun `ask_user pending is marked as ask user`() {
        val state = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = conversationA,
            activity = null,
            messages = listOf(
                assistantMessage(
                    textPart("需要确认"),
                    pendingToolPart(toolName = "ask_user", callId = "call_q"),
                ),
            ),
            nowMillis = 1_000L,
        )
        assertTrue(state.waitingApproval)
        assertTrue(state.waitingAskUser)
    }

    @Test
    fun `running screen tool sets step title and touch passthrough`() {
        val state = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = conversationA,
            activity = runningScreenTool(),
            messages = listOf(assistantMessage(textPart("好的，我来点击"))),
            nowMillis = 1_000L,
        )
        assertTrue(state.stepRunning)
        assertEquals("点击屏幕", state.stepTitle)
        assertEquals("好的，我来点击", state.replyPreview)
        assertTrue(AgentBubbleReducer.isTouchPassthrough(state))
    }

    @Test
    fun `non screen or finished tool does not request passthrough`() {
        val nonScreen = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = conversationA,
            activity = runningScreenTool(toolName = "terminal_execute"),
            messages = null,
            nowMillis = 1_000L,
        )
        assertFalse(nonScreen.stepRunning)
        assertFalse(AgentBubbleReducer.isTouchPassthrough(nonScreen))

        val doneTool = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = conversationA,
            activity = runningScreenTool(status = ToolActivityStatus.SUCCEEDED),
            messages = null,
            nowMillis = 1_000L,
        )
        assertFalse(doneTool.stepRunning)
        assertEquals("", doneTool.stepTitle)
    }

    // ── reduce：结束过渡与停留 ──

    @Test
    fun `run end transitions running to finished keeping final text`() {
        val running = AgentBubbleUiState(
            phase = AgentBubblePhase.RUNNING,
            conversationId = conversationA,
            waitingApproval = true,
            approvalToolCallId = "call_p",
            replyPreview = "旧预览",
        )
        val finished = AgentBubbleReducer.reduce(
            previous = running,
            pickedConversationId = null,
            activity = runningScreenTool(),
            messages = null,
            finalText = "任务完成，已打开设置",
            failed = false,
            nowMillis = 5_000L,
        )
        assertEquals(AgentBubblePhase.FINISHED, finished.phase)
        assertEquals(5_000L, finished.finishedAtMillis)
        assertEquals("任务完成，已打开设置", finished.replyPreview)
        assertFalse(finished.waitingApproval)
        assertNull(finished.approvalToolCallId)
        assertFalse(finished.stepRunning)
        assertFalse(AgentBubbleReducer.isTouchPassthrough(finished))
    }

    @Test
    fun `finished lingers then goes idle after 12s`() {
        var state = AgentBubbleUiState(
            phase = AgentBubblePhase.FINISHED,
            conversationId = conversationA,
            replyPreview = "完成",
            finishedAtMillis = 1_000L,
        )
        state = AgentBubbleReducer.reduce(
            previous = state,
            pickedConversationId = null,
            activity = null,
            messages = null,
            nowMillis = 1_000L + AgentBubbleReducer.FINISHED_LINGER_MS - 1,
        )
        assertEquals(AgentBubblePhase.FINISHED, state.phase)

        state = AgentBubbleReducer.reduce(
            previous = state,
            pickedConversationId = null,
            activity = null,
            messages = null,
            nowMillis = 1_000L + AgentBubbleReducer.FINISHED_LINGER_MS,
        )
        assertEquals(AgentBubblePhase.IDLE, state.phase)
        assertFalse(state.isActive)
    }

    @Test
    fun `user stop cancels straight to idle without finished linger`() {
        val running = AgentBubbleUiState(
            phase = AgentBubblePhase.RUNNING,
            conversationId = conversationA,
            replyPreview = "半截预览",
        )
        val state = AgentBubbleReducer.reduce(
            previous = running,
            pickedConversationId = null,
            activity = null,
            messages = null,
            cancelled = true,
            nowMillis = 5_000L,
        )
        assertEquals(AgentBubblePhase.IDLE, state.phase)
        assertFalse(state.isActive)
    }

    @Test
    fun `finished without prior running goes idle`() {
        val state = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = null,
            activity = null,
            messages = null,
            nowMillis = 1_000L,
        )
        assertEquals(AgentBubblePhase.IDLE, state.phase)
    }

    // ── isVisible ──

    @Test
    fun `visibility requires setting service foreground and active state`() {
        val running = AgentBubbleUiState(phase = AgentBubblePhase.RUNNING, conversationId = conversationA)
        val idle = AgentBubbleUiState()
        assertTrue(AgentBubbleReducer.isVisible(running, enabled = true, serviceAvailable = true, foregroundIsAmber = false))
        assertFalse(AgentBubbleReducer.isVisible(running, enabled = false, serviceAvailable = true, foregroundIsAmber = false))
        assertFalse(AgentBubbleReducer.isVisible(running, enabled = true, serviceAvailable = false, foregroundIsAmber = false))
        assertFalse(AgentBubbleReducer.isVisible(running, enabled = true, serviceAvailable = true, foregroundIsAmber = true))
        assertFalse(AgentBubbleReducer.isVisible(idle, enabled = true, serviceAvailable = true, foregroundIsAmber = false))
    }

    // ── lastAssistantText ──

    @Test
    fun `last assistant text picks last non blank text part`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(textPart("帮我操作"))),
            assistantMessage(textPart("第一段"), textPart("  "), textPart("最终回复")),
        )
        assertEquals("最终回复", AgentBubbleReducer.lastAssistantText(messages))
        assertNull(AgentBubbleReducer.lastAssistantText(emptyList()))
    }
}
