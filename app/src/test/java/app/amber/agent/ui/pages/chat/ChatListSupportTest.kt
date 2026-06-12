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
    fun `generation end keeps bottom-follow without settle guards`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()
        val policy = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatStreamingFollowPolicy.kt").readText()

        assertTrue(source.contains("→ keep FollowingBottom after generation end"))
        assertFalse(source.contains("settleAfterGenerationEnd"))
        assertFalse(source.contains("TL_loading_end"))
        assertFalse(source.contains("postSettle"))
        assertFalse(source.contains("generationEndSettle"))
        assertFalse(policy.contains("TimelineFollowEndSettlePolicy"))
    }

    @Test
    fun `bottom follow events are not gated by active generation after completion`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        assertTrue(source.contains("val willFollow = bottomFollowAllowed()"))
        assertTrue(source.contains("val stillFollowing = bottomFollowAllowed()"))
        assertFalse(source.contains("val willFollow = activeGenerationState &&"))
        assertFalse(source.contains("val stillFollowing = activeGenerationState &&"))
    }

    @Test
    fun `tail assistant keeps visual callbacks after loading ends`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        assertTrue(source.contains("onStreamingVisibleFrame = if (isTailAssistantMessage)"))
        assertTrue(source.contains("onStreamingVisualActiveChange = if (isTailAssistantMessage)"))
        assertFalse(source.contains("onStreamingVisibleFrame = if (isLoadingMessage)"))
    }

    @Test
    fun `markdown reports streaming visual active while draining after stream`() {
        val markdown = repoFile("src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt").readText()
        val message = repoFile("src/main/java/app/amber/feature/ui/components/message/ChatMessage.kt").readText()

        assertTrue(markdown.contains("onStreamingVisualActiveChange"))
        assertTrue(markdown.contains("active = streaming || displayDrainingAfterStream"))
        assertTrue(markdown.contains("updatedOnStreamingVisibleFrame?.invoke()"))
        assertTrue(message.contains("onStreamingVisualActiveChange = onStreamingVisualActiveChange"))
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
        val plan = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListSupport.kt").readText()

        assertTrue(source.contains("retainedTailIndicatorMessageId"))
        assertTrue(source.contains("tailIndicatorReserveVisible"))
        assertTrue(source.contains("pinTailIndicator"))
        assertTrue(source.contains("key = TimelineTailKey"))
        assertTrue(source.contains("contentType = \"timeline-tail\""))
        assertTrue(source.contains("TimelineTailWorkingIndicator("))
        assertTrue(source.contains("visible = tailIndicatorDotVisible && !pinTailIndicator"))
        assertTrue(source.contains("visible = pinTailIndicator && !captureProgress"))
        assertTrue(plan.contains("add(ChatTimelineEntry.TimelineTail)"))
        assertFalse(plan.contains("add(ChatTimelineEntry.Loading)"))
        assertFalse(plan.contains("add(ChatTimelineEntry.ScrollBottom)"))
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
