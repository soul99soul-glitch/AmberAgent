package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ChatListSupportTest {
    @Test
    fun `latest render token is empty for empty conversation`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        )

        assertEquals("0:empty", conversation.latestRenderToken())
    }

    @Test
    fun `latest render token uses current message from last node`() {
        val first = UIMessage.user("first")
        val unselected = UIMessage.assistant("unselected")
        val selected = UIMessage.assistant("selected")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode.of(first),
                MessageNode(
                    messages = listOf(unselected, selected),
                    selectIndex = 1,
                ),
            ),
        )

        assertEquals(
            "${conversation.messageNodes.size}:${selected.id}:${selected.parts.size}:text:8:selected",
            conversation.latestRenderToken(),
        )
    }

    @Test
    fun `latest render token keeps compact text and tool part format`() {
        val textMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("12345678901234567890")),
        )
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )

        assertEquals(
            "1:${textMessage.id}:1:text:20:5678901234567890",
            Conversation(
                assistantId = Uuid.random(),
                messageNodes = listOf(MessageNode.of(textMessage)),
            ).latestRenderToken(),
        )
        assertEquals(
            "1:${toolMessage.id}:1:tool:call-1:search:true:approved:1:text:4:done",
            Conversation(
                assistantId = Uuid.random(),
                messageNodes = listOf(MessageNode.of(toolMessage)),
            ).latestRenderToken(),
        )
    }

    @Test
    fun `generation end settle only runs on active generation falling edge`() {
        assertTrue(
            TimelineFollowEndSettlePolicy.effectPlan(
                wasActiveGeneration = true,
                activeGeneration = false,
                autoScrollEnabled = true,
            ).runEndSettleBeforeIdle
        )
        assertFalse(
            TimelineFollowEndSettlePolicy.effectPlan(
                wasActiveGeneration = false,
                activeGeneration = false,
                autoScrollEnabled = true,
            ).runEndSettleBeforeIdle
        )
        assertFalse(
            TimelineFollowEndSettlePolicy.effectPlan(
                wasActiveGeneration = true,
                activeGeneration = true,
                autoScrollEnabled = true,
            ).enterIdleAfterEndSettle
        )
    }

    @Test
    fun `generation end settle keeps idle after settle ordering`() {
        val plan = TimelineFollowEndSettlePolicy.effectPlan(
            wasActiveGeneration = true,
            activeGeneration = false,
            autoScrollEnabled = true,
        )

        assertTrue(plan.runEndSettleBeforeIdle)
        assertTrue(plan.enterIdleAfterEndSettle)
    }

    @Test
    fun `generation end settle gate respects follow mode finger and scroll state`() {
        assertTrue(
            TimelineFollowEndSettlePolicy.canSettleNow(
                followMode = TimelineFollowMode.FollowingBottom,
                userScrollInTimeline = false,
                scrollInProgress = false,
            )
        )
        assertFalse(
            TimelineFollowEndSettlePolicy.canSettleNow(
                followMode = TimelineFollowMode.PausedForUser,
                userScrollInTimeline = false,
                scrollInProgress = false,
            )
        )
        assertFalse(
            TimelineFollowEndSettlePolicy.canSettleNow(
                followMode = TimelineFollowMode.FollowingBottom,
                userScrollInTimeline = true,
                scrollInProgress = false,
            )
        )
        assertFalse(
            TimelineFollowEndSettlePolicy.canSettleNow(
                followMode = TimelineFollowMode.FollowingBottom,
                userScrollInTimeline = false,
                scrollInProgress = true,
            )
        )
    }

    @Test
    fun `generation end settle can still attempt while scroll is settling`() {
        assertTrue(
            TimelineFollowEndSettlePolicy.canAttemptSettle(
                followMode = TimelineFollowMode.FollowingBottom,
                userScrollInTimeline = false,
            )
        )
        assertFalse(
            TimelineFollowEndSettlePolicy.canAttemptSettle(
                followMode = TimelineFollowMode.PausedForUser,
                userScrollInTimeline = false,
            )
        )
        assertFalse(
            TimelineFollowEndSettlePolicy.canAttemptSettle(
                followMode = TimelineFollowMode.FollowingBottom,
                userScrollInTimeline = true,
            )
        )
    }

    @Test
    fun `generation end settle accepts bottom buffer but not missing anchor`() {
        assertTrue(TimelineFollowEndSettlePolicy.isCloseEnoughToBottom(distancePx = 0, bottomBufferPx = 24))
        assertTrue(TimelineFollowEndSettlePolicy.isCloseEnoughToBottom(distancePx = 24, bottomBufferPx = 24))
        assertFalse(TimelineFollowEndSettlePolicy.isCloseEnoughToBottom(distancePx = 25, bottomBufferPx = 24))
        assertFalse(TimelineFollowEndSettlePolicy.isCloseEnoughToBottom(distancePx = null, bottomBufferPx = 24))
    }

    @Test
    fun `chat streaming follow path keeps stable pointer key and chunk emits only`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        assertTrue(source.contains(".pointerInput(conversation.id)"))
        assertFalse(source.contains(".pointerInput(activeGeneration, settings.displaySetting.enableAutoScroll, conversation.id)"))
        assertTrue(source.contains("requestStreamingBottomFollow(\"chunk\")"))
        assertFalse(source.contains("requestTimelineBottom(\"chunk\")"))
        assertTrue(source.contains("streamingVisibleEvents.conflate()"))
    }

    @Test
    fun `streaming bottom follow events use drop oldest buffer`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatStreamingFollowPolicy.kt").readText()

        assertTrue(source.contains("extraBufferCapacity = 1"))
        assertTrue(source.contains("onBufferOverflow = BufferOverflow.DROP_OLDEST"))
        assertTrue(createStreamingBottomFollowEvents().tryEmit("chunk"))
        assertTrue(createStreamingBottomFollowEvents().tryEmit("visible"))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
