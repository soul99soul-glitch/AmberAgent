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
    fun planMapsHistoryMessagesPendingAndStableTail() {
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

        // reverseLayout 发射序（视觉底部 → 顶部）：
        // [TimelineTail, Pending(1), Pending(0), TailCompactMarkers, Msg(1), Msg(0), HistoryLoading]
        assertTrue(plan.entries.first() is ChatTimelineEntry.TimelineTail)
        assertTrue(plan.entries[1] is ChatTimelineEntry.Pending)
        assertTrue(plan.entries[2] is ChatTimelineEntry.Pending)
        assertTrue(plan.entries[3] is ChatTimelineEntry.TailCompactMarkers)
        assertTrue(plan.entries[4] is ChatTimelineEntry.Message)
        assertTrue(plan.entries[5] is ChatTimelineEntry.Message)
        assertTrue(plan.entries.last() is ChatTimelineEntry.HistoryLoading)
        // 跳转语义：目标 = 消息视觉底部切片（reverseLayout 下 scrollToItem 钉在
        // 视口底缘，消息向上展开进入视口）；倒序发射下 newer 消息 lazy index 更小
        assertEquals(5, plan.lazyIndexForMessage(0))
        assertEquals(4, plan.lazyIndexForMessage(1))
        assertEquals(listOf(null, null, null, null, 1, 0, null), plan.lazyItemMessageIndexes)
    }

    @Test
    fun planKeepsSameTailEntryShapeWhenLoadingEnds() {
        val conversation = conversationOf(
            message("u1", MessageRole.USER),
            message("a1", MessageRole.ASSISTANT),
        )
        val cache = ChatVirtualItemCache()

        val loadingPlan = buildChatTimelinePlan(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = true,
            hasHistoryLoadingItem = false,
            pendingMessageCount = 0,
            postSendState = emptyPostSendState(),
            virtualItemCache = cache,
        )
        val completedPlan = buildChatTimelinePlan(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = false,
            hasHistoryLoadingItem = false,
            pendingMessageCount = 0,
            postSendState = emptyPostSendState(),
            virtualItemCache = cache,
        )

        assertEquals(loadingPlan.entries.size, completedPlan.entries.size)
        assertTrue(loadingPlan.entries.first() is ChatTimelineEntry.TimelineTail)
        assertTrue(completedPlan.entries.first() is ChatTimelineEntry.TimelineTail)
        assertEquals(loadingPlan.lazyItemMessageIndexes, completedPlan.lazyItemMessageIndexes)
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

        assertTrue(plan.entries[0] is ChatTimelineEntry.TimelineTail)
        assertTrue(plan.entries[1] is ChatTimelineEntry.TailCompactMarkers)
        assertTrue(plan.entries[2] is ChatTimelineEntry.Message)
        assertTrue(plan.entries[3] is ChatTimelineEntry.Message)
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

    private fun emptyPostSendState() = PostSendTimelineState(
        sentUserMessageId = null,
        sentUserMessageIndex = null,
        assistantMessageIndex = null,
        hiddenAssistantMessageIndex = null,
        waitingForAssistantContent = false,
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
