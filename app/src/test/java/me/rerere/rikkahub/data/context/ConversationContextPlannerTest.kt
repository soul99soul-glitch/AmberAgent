package me.rerere.rikkahub.data.context

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextPlannerTest {
    @Test
    fun belowThresholdDoesNotCompact() {
        val nodes = List(8) { MessageNode.of(UIMessage.user("short $it")) }

        val plan = ConversationContextPlanner.planCompaction(
            nodes = nodes,
            activeCompacts = emptyList(),
            policy = CompactPolicy(precompactRatio = 0.70f, keepRecentTurns = 2),
            modelContextWindowTokens = 100_000,
        )

        assertFalse(plan.shouldCompact)
        assertEquals("below_threshold", plan.reason)
    }

    @Test
    fun forceThresholdPlansOlderMessagesAndKeepsRecentTurns() {
        val nodes = List(20) { MessageNode.of(UIMessage.user("x".repeat(1_000))) }

        val plan = ConversationContextPlanner.planCompaction(
            nodes = nodes,
            activeCompacts = emptyList(),
            policy = CompactPolicy(precompactRatio = 0.40f, forceRatio = 0.50f, keepRecentTurns = 3),
            modelContextWindowTokens = 1_000,
        )

        assertTrue(plan.shouldCompact)
        assertEquals("force_threshold", plan.reason)
        assertEquals(0, plan.sourceStartIndex)
        assertEquals(13, plan.sourceEndIndex)
        assertEquals(14, plan.sourceMessageIds.size)
    }

    @Test
    fun prepareMessagesInjectsSummaryAndDropsCoveredOriginals() {
        val messages = List(8) { UIMessage.user("message $it") }
        val compact = ConversationCompact(
            id = "summary-1",
            conversationId = "conversation",
            summary = "{\"goals\":[],\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"failed_attempts\":[],\"tool_results\":[],\"entities\":[],\"timeline\":[],\"source_message_ids\":[]}",
            level = 1,
            sourceStartIndex = 0,
            sourceEndIndex = 3,
            sourceMessageIds = messages.take(4).map { it.id.toString() },
            tokenEstimate = 100,
            createdAt = 1,
            updatedAt = 1,
            status = "completed",
        )

        val prepared = ConversationContextPlanner.prepareMessages(
            messages = messages,
            activeCompacts = listOf(compact),
            policy = CompactPolicy(keepRecentTurns = 2),
            contextMessageSize = 0,
        )

        assertEquals(5, prepared.size)
        assertTrue(prepared.first().toText().contains("summary-1"))
        assertEquals(messages.drop(4).map { it.id }, prepared.drop(1).map { it.id })
    }
}
