package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
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

        val token = conversation.latestRenderToken()

        assertTrue(token.startsWith("${conversation.messageNodes.size}:${selected.id}:${selected.parts.size}:0:"))
        assertTrue(token.contains("text:8:selected:${"selected".hashCode()}"))
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

        val textToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(textMessage)),
        ).latestRenderToken()
        val toolToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(toolMessage)),
        ).latestRenderToken()

        assertTrue(textToken.contains("text:20:5678901234567890:${"12345678901234567890".hashCode()}"))
        assertTrue(toolToken.contains("tool:call-1:search:2:{}:${"{}".hashCode()}:true:approved:1:"))
    }

    @Test
    fun `latest render token changes when non trailing tool input changes`() {
        val first = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{\"q\":\"a\"}",
                ),
                UIMessagePart.Text("tail"),
            ),
        )
        val second = first.copy(
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{\"q\":\"amber\"}",
                ),
                UIMessagePart.Text("tail"),
            ),
        )

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken.contains("tool:call-1:search:9:"))
        assertTrue(secondToken.contains("tool:call-1:search:13:"))
        assertTrue(firstToken != secondToken)
    }

    @Test
    fun `latest render token changes when same shape text content changes`() {
        val first = UIMessage.assistant("A1234567890123456")
        val second = first.copy(parts = listOf(UIMessagePart.Text("B1234567890123456")))

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken != secondToken)
    }

    @Test
    fun `latest render token changes when same count annotations change`() {
        val first = UIMessage.assistant("answer").copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation(title = "old", url = "https://old.example")),
        )
        val second = first.copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation(title = "new", url = "https://new.example")),
        )

        val firstToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(first)),
        ).latestRenderToken()
        val secondToken = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(second)),
        ).latestRenderToken()

        assertTrue(firstToken != secondToken)
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
    fun `generation end settle waits for consecutive stable bottom frames`() {
        assertFalse(TimelineFollowEndSettlePolicy.hasEnoughStableBottomFrames(0))
        assertFalse(TimelineFollowEndSettlePolicy.hasEnoughStableBottomFrames(1))
        assertTrue(TimelineFollowEndSettlePolicy.hasEnoughStableBottomFrames(2))
    }

    @Test
    fun `chat streaming follow path keeps stable pointer key and chunk emits only`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        assertTrue(source.contains(".pointerInput(conversation.id)"))
        assertFalse(source.contains(".pointerInput(activeGeneration, settings.displaySetting.enableAutoScroll, conversation.id)"))
        assertTrue(source.contains("requestStreamingBottomFollow(\"chunk\")"))
        assertFalse(source.contains("processingStatus,\n                pendingUserMessages.size"))
        assertFalse(source.contains("requestTimelineBottom(\"stream-"))
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

    @Test
    fun `streaming tail indicator uses pinned overlay with retained reserve`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        assertTrue(source.contains("retainedTailIndicatorMessageId"))
        assertTrue(source.contains("tailIndicatorReserveVisible"))
        assertTrue(source.contains("pinTailIndicator"))
        assertTrue(source.contains("TimelineTailWorkingIndicator("))
        assertTrue(source.contains("visible = tailIndicatorDotVisible && !pinTailIndicator"))
        assertTrue(source.contains("visible = pinTailIndicator && !captureProgress"))
    }

    @Test
    fun `pinned tail indicator overlay aligns with in-list resting position`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // The overlay's bottom padding must be derived from the same values
        // that position the in-list reserve dot, or the pin handoff teleports.
        assertTrue(source.contains("bottom = timelineBottomPadding + ScrollBottomSpacerHeight +"))
        assertTrue(source.contains("TimelineItemSpacing + TailIndicatorDotBottomPadding"))
        assertFalse(source.contains("AgentWorkingIndicatorOverlayBottomOffset"))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
