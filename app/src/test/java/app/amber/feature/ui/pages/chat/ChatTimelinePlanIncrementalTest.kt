package app.amber.feature.ui.pages.chat

import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ChatTimelinePlanIncrementalTest {
    @Test
    fun changedProtectedNodesOrPostSendStateRebuildsThePrefix() {
        val nodes = listOf(
            UIMessage.user("user").toMessageNode(),
            UIMessage.assistant((0 until 12).joinToString("\n\n") {
                "## Section $it\n\n" + "Historical content ".repeat(6)
            }).toMessageNode(),
            UIMessage.assistant("tail").toMessageNode(),
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = nodes)
        val reuse = ChatTimelinePlanReuseCache()
        val virtualItems = ChatVirtualItemCache()
        val basePostSend = PostSendTimelineState(null, null, null, null, false)
        fun cached(postSend: PostSendTimelineState, protectedIds: Set<Uuid>) = reuse.build(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = true,
            hasHistoryLoadingItem = false,
            pendingMessageCount = 0,
            postSendState = postSend,
            virtualItemCache = virtualItems,
            protectedStreamingNodeIds = protectedIds,
        )
        fun full(postSend: PostSendTimelineState, protectedIds: Set<Uuid>) = buildChatTimelinePlan(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = true,
            hasHistoryLoadingItem = false,
            pendingMessageCount = 0,
            postSendState = postSend,
            virtualItemCache = virtualItems,
            protectedStreamingNodeIds = protectedIds,
        )

        val initial = cached(basePostSend, emptySet())
        val protectedIds = setOf(nodes[1].id)
        val protected = cached(basePostSend, protectedIds)
        assertEquals(full(basePostSend, protectedIds), protected)
        assertNotSame(
            initial.entries.first { it.messageIndex == 0 },
            protected.entries.first { it.messageIndex == 0 },
        )

        val changedPostSend = basePostSend.copy(waitingForAssistantContent = true)
        val changedPost = cached(changedPostSend, protectedIds)
        assertEquals(full(changedPostSend, protectedIds), changedPost)
        assertNotSame(
            protected.entries.first { it.messageIndex == 0 },
            changedPost.entries.first { it.messageIndex == 0 },
        )
    }

    @Test
    fun tailOnlyReuseMatchesFullPlanAndEqualLengthPrefixReplacementRebuilds() {
        val markdown = (0 until 12).joinToString("\n\n") { "## Section $it\n\nHistorical content" }
        val nodes = listOf(
            UIMessage.user("user").toMessageNode(),
            UIMessage.assistant(markdown).toMessageNode(),
            UIMessage.assistant("tail").toMessageNode(),
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = nodes)
        val reuse = ChatTimelinePlanReuseCache()
        val virtualItems = ChatVirtualItemCache()
        val postSend = PostSendTimelineState(null, null, null, null, false)
        fun cached(current: Conversation) = reuse.build(
            conversation = current,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = true,
            hasHistoryLoadingItem = true,
            pendingMessageCount = 1,
            postSendState = postSend,
            virtualItemCache = virtualItems,
        )
        fun full(current: Conversation) = buildChatTimelinePlan(
            conversation = current,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = true,
            hasHistoryLoadingItem = true,
            pendingMessageCount = 1,
            postSendState = postSend,
            virtualItemCache = virtualItems,
        )

        val initial = cached(conversation)
        val tailMessage = nodes.last().currentMessage
        val streamedTail = nodes.last().copy(messages = listOf(
            tailMessage.copy(parts = listOf(UIMessagePart.Text("tail streamed")))
        ))
        val streamed = conversation.copy(messageNodes = nodes.dropLast(1) + streamedTail)
        val incremental = cached(streamed)
        assertEquals(full(streamed), incremental)
        assertSame(
            initial.entries.first { it.messageIndex == 1 },
            incremental.entries.first { it.messageIndex == 1 },
        )

        val replacedPrefix = streamed.copy(messageNodes = listOf(nodes[0].copy()) + streamed.messageNodes.drop(1))
        val rebuilt = cached(replacedPrefix)
        assertEquals(full(replacedPrefix), rebuilt)
        assertNotSame(
            incremental.entries.first { it.messageIndex == 0 },
            rebuilt.entries.first { it.messageIndex == 0 },
        )

        val replacedTail = replacedPrefix.copy(
            messageNodes = replacedPrefix.messageNodes.dropLast(1) + UIMessage.assistant("new tail").toMessageNode(),
        )
        val replacedTailPlan = cached(replacedTail)
        assertEquals(full(replacedTail), replacedTailPlan)
        assertSame(
            rebuilt.entries.first { it.messageIndex == 0 },
            replacedTailPlan.entries.first { it.messageIndex == 0 },
        )
    }
}
