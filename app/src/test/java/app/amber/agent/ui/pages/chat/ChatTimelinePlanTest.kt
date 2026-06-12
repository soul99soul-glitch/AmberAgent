package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatTimelinePlanTest {
    @Test
    fun planMapsHistoryMessagesPendingLoadingAndBottom() {
        val conversation = conversationOf(
            message("u1", MessageRole.USER),
            message("a1", MessageRole.ASSISTANT),
        )
        val plan = buildChatTimelinePlan(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = true,
            hasHistoryLoadingItem = true,
            pendingMessageCount = 2,
            postSendState = PostSendTimelineState(
                sentUserMessageId = null,
                sentUserMessageIndex = null,
                assistantMessageIndex = null,
                hiddenAssistantMessageIndex = null,
                waitingForAssistantContent = false,
            ),
            virtualItemCache = ChatVirtualItemCache(),
        )

        assertTrue(plan.entries.first() is ChatTimelineEntry.HistoryLoading)
        assertEquals(1, plan.firstLazyIndexForMessage(0))
        assertEquals(2, plan.firstLazyIndexForMessage(1))
        assertTrue(plan.entries[3] is ChatTimelineEntry.Pending)
        assertTrue(plan.entries[4] is ChatTimelineEntry.Pending)
        assertTrue(plan.entries[5] is ChatTimelineEntry.Loading)
        assertTrue(plan.entries.last() is ChatTimelineEntry.ScrollBottom)
        assertEquals(listOf(null, 0, 1, null, null, null, null), plan.lazyItemMessageIndexes)
    }

    @Test
    fun planKeepsProtectedHiddenTailAssistantAsMessageWithoutAddingDuplicatePlaceholder() {
        val conversation = conversationOf(
            message("u1", MessageRole.USER),
            message("", MessageRole.ASSISTANT),
        )
        val plan = buildChatTimelinePlan(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = false,
            hasHistoryLoadingItem = false,
            pendingMessageCount = 0,
            postSendState = PostSendTimelineState(
                sentUserMessageId = "user",
                sentUserMessageIndex = 0,
                assistantMessageIndex = 1,
                hiddenAssistantMessageIndex = 1,
                waitingForAssistantContent = true,
            ),
            virtualItemCache = ChatVirtualItemCache(),
        )

        assertTrue(plan.entries[0] is ChatTimelineEntry.Message)
        assertTrue(plan.entries[1] is ChatTimelineEntry.Message)
        assertTrue(plan.entries.none { it is ChatTimelineEntry.PostSendHiddenAssistant })
        assertTrue(plan.entries.none { it is ChatTimelineEntry.PostSendWaitingAssistant })
    }

    @Test
    fun virtualItemCacheReusesStableNodePlans() {
        val conversation = conversationOf(
            message("u1", MessageRole.USER),
            message(longMarkdown("older"), MessageRole.ASSISTANT),
            message("middle", MessageRole.ASSISTANT),
            message("tail", MessageRole.ASSISTANT),
        )
        val cache = ChatVirtualItemCache()
        repeat(2) {
            buildChatTimelinePlan(
                conversation = conversation,
                assistant = null,
                showAssistantBubble = true,
                timelineLoading = false,
                hasHistoryLoadingItem = false,
                pendingMessageCount = 0,
                postSendState = PostSendTimelineState(
                    sentUserMessageId = null,
                    sentUserMessageIndex = null,
                    assistantMessageIndex = null,
                    hiddenAssistantMessageIndex = null,
                    waitingForAssistantContent = false,
                ),
                virtualItemCache = cache,
            )
        }

        assertEquals(4, cache.misses)
        assertEquals(4, cache.hits)
    }

    @Test
    fun planKeepsTwoTailAssistantMessagesAsSingleItems() {
        val conversation = conversationOf(
            message("u1", MessageRole.USER),
            message(longMarkdown("older"), MessageRole.ASSISTANT),
            message(longMarkdown("middle"), MessageRole.ASSISTANT),
            message(longMarkdown("tail"), MessageRole.ASSISTANT),
        )
        val plan = buildChatTimelinePlan(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = false,
            hasHistoryLoadingItem = false,
            pendingMessageCount = 0,
            postSendState = PostSendTimelineState(
                sentUserMessageId = null,
                sentUserMessageIndex = null,
                assistantMessageIndex = null,
                hiddenAssistantMessageIndex = null,
                waitingForAssistantContent = false,
            ),
            virtualItemCache = ChatVirtualItemCache(),
        )

        assertTrue(plan.entries.any { it is ChatTimelineEntry.VirtualMessage && it.messageIndex == 1 })
        assertTrue(plan.entries.any { it is ChatTimelineEntry.Message && it.messageIndex == 2 })
        assertTrue(plan.entries.any { it is ChatTimelineEntry.Message && it.messageIndex == 3 })
        assertTrue(plan.entries.none { it is ChatTimelineEntry.VirtualMessage && it.messageIndex == 2 })
        assertTrue(plan.entries.none { it is ChatTimelineEntry.VirtualMessage && it.messageIndex == 3 })
    }

    @Test
    fun virtualItemCacheEvictsMarkdownPlansByContentBudget() {
        val firstMarkdown = longMarkdown("first")
        val secondMarkdown = longMarkdown("second")
        val thirdMarkdown = longMarkdown("third")
        val fourthMarkdown = longMarkdown("fourth")
        val conversation = conversationOf(
            message(firstMarkdown, MessageRole.ASSISTANT),
            message(secondMarkdown, MessageRole.ASSISTANT),
            message(thirdMarkdown, MessageRole.ASSISTANT),
            message(fourthMarkdown, MessageRole.ASSISTANT),
        )
        val cache = ChatVirtualItemCache(maxMarkdownChars = firstMarkdown.length + 100)
        repeat(2) {
            buildChatTimelinePlan(
                conversation = conversation,
                assistant = null,
                showAssistantBubble = true,
                timelineLoading = false,
                hasHistoryLoadingItem = false,
                pendingMessageCount = 0,
                postSendState = PostSendTimelineState(
                    sentUserMessageId = null,
                    sentUserMessageIndex = null,
                    assistantMessageIndex = null,
                    hiddenAssistantMessageIndex = null,
                    waitingForAssistantContent = false,
                ),
                virtualItemCache = cache,
            )
        }

        assertEquals(8, cache.misses)
        assertEquals(0, cache.hits)
    }

    private fun conversationOf(vararg messages: UIMessage) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    private fun message(text: String, role: MessageRole) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun longMarkdown(label: String): String = buildString {
        append("# ")
        append(label)
        append("\n\n")
        repeat(180) {
            append("markdown-cache-budget ")
        }
        append("\n\n")
        append("tail")
    }
}
