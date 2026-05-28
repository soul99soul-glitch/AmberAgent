package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
    fun planKeepsHiddenAssistantAsWaitingItemWithoutAddingDuplicatePlaceholder() {
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
        assertTrue(plan.entries[1] is ChatTimelineEntry.PostSendHiddenAssistant)
        assertTrue(plan.entries.none { it is ChatTimelineEntry.PostSendWaitingAssistant })
    }

    @Test
    fun virtualItemCacheReusesStableNodePlans() {
        val conversation = conversationOf(
            message("u1", MessageRole.USER),
            message("a1", MessageRole.ASSISTANT),
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

        assertEquals(2, cache.misses)
        assertEquals(2, cache.hits)
    }

    @Test
    fun virtualItemCacheEvictsMarkdownPlansByContentBudget() {
        val firstMarkdown = longMarkdown("first")
        val secondMarkdown = longMarkdown("second")
        val conversation = conversationOf(
            message(firstMarkdown, MessageRole.ASSISTANT),
            message(secondMarkdown, MessageRole.ASSISTANT),
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

        assertEquals(4, cache.misses)
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
