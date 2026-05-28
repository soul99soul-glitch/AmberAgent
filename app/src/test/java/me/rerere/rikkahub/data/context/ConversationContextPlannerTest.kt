package me.rerere.rikkahub.data.context

import me.rerere.ai.ui.UIMessage
import app.amber.core.model.MessageNode
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
    fun forceCompactionSkipsShallowPlanThatWouldNotReleaseEnoughTokens() {
        val nodes = List(4) { MessageNode.of(UIMessage.user("short $it")) } +
            List(24) { MessageNode.of(UIMessage.user("x".repeat(4_000))) }

        val plan = ConversationContextPlanner.planForceCompaction(
            nodes = nodes,
            activeCompacts = emptyList(),
            policy = CompactPolicy(
                precompactRatio = 0.40f,
                forceRatio = 0.85f,
                keepRecentTurns = 12,
                maxSummaryTokens = 500,
            ),
            modelContextWindowTokens = 10_000,
        )

        assertTrue(plan.shouldCompact)
        assertEquals("force_threshold", plan.reason)
        assertTrue("force plan should go beyond the first 4 tiny messages", plan.sourceEndIndex > 3)
    }

    @Test
    fun forceCompactionDepthUsesEffectiveCompactAwarePressure() {
        val nodes = List(100) { MessageNode.of(UIMessage.user("x".repeat(4_000))) }
        val oldCompact = ConversationCompact(
            id = "old-compact",
            conversationId = "conversation",
            summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
                summary = "Old cumulative facts.",
                sourceMessageIds = nodes.take(80).map { it.currentMessage.id.toString() },
            ),
            level = 1,
            sourceStartIndex = 0,
            sourceEndIndex = 79,
            sourceMessageIds = nodes.take(80).map { it.currentMessage.id.toString() },
            tokenEstimate = 500,
            createdAt = 1,
            updatedAt = 1,
            status = "completed",
        )

        val plan = ConversationContextPlanner.planForceCompaction(
            nodes = nodes,
            activeCompacts = listOf(oldCompact),
            policy = CompactPolicy(
                precompactRatio = 0.40f,
                forceRatio = 0.90f,
                keepRecentTurns = 8,
                maxSummaryTokens = 500,
            ),
            modelContextWindowTokens = 20_000,
        )

        assertTrue(plan.shouldCompact)
        assertEquals("force_threshold", plan.reason)
        assertEquals(80, plan.sourceStartIndex)
        assertEquals(83, plan.sourceEndIndex)
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

    @Test
    fun prepareMessagesInjectsLatestCumulativeHandoffOnly() {
        val messages = List(8) { UIMessage.user("message $it") }
        val olderCompact = ConversationCompact(
            id = "summary-old",
            conversationId = "conversation",
            summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
                summary = "Older facts.",
                sourceMessageIds = messages.take(4).map { it.id.toString() },
            ),
            level = 1,
            sourceStartIndex = 0,
            sourceEndIndex = 3,
            sourceMessageIds = messages.take(4).map { it.id.toString() },
            tokenEstimate = 100,
            createdAt = 1,
            updatedAt = 1,
            status = "completed",
        )
        val latestCompact = ConversationCompact(
            id = "summary-latest",
            conversationId = "conversation",
            summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
                summary = "Latest cumulative facts.",
                sourceMessageIds = messages.drop(4).take(2).map { it.id.toString() },
                coveredCompactIds = listOf("summary-old"),
                carriedHandoffMarkdown = CompactSummaryPayloads.injectionText(olderCompact),
            ),
            level = 1,
            sourceStartIndex = 4,
            sourceEndIndex = 5,
            sourceMessageIds = messages.drop(4).take(2).map { it.id.toString() },
            tokenEstimate = 100,
            createdAt = 2,
            updatedAt = 2,
            status = "completed",
        )

        val prepared = ConversationContextPlanner.prepareMessages(
            messages = messages,
            activeCompacts = listOf(olderCompact, latestCompact),
            policy = CompactPolicy(keepRecentTurns = 2),
            contextMessageSize = 0,
        )

        assertEquals(3, prepared.size)
        assertTrue(prepared.first().toText().contains("summary-latest"))
        assertTrue(prepared.first().toText().contains("summary-old"))
        assertEquals(messages.drop(6).map { it.id }, prepared.drop(1).map { it.id })
    }

    @Test
    fun prepareMessagesSkipsSummaryWhenSourceMessageIsMissing() {
        val messages = List(8) { UIMessage.user("message $it") }
        val compact = ConversationCompact(
            id = "stale-summary",
            conversationId = "conversation",
            summary = "{\"facts\":[\"old fact\"]}",
            level = 1,
            sourceStartIndex = 0,
            sourceEndIndex = 3,
            sourceMessageIds = messages.take(3).map { it.id.toString() } + "missing-message-id",
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

        assertEquals(messages.map { it.id }, prepared.map { it.id })
        assertFalse(prepared.any { it.toText().contains("stale-summary") })
    }

    @Test
    fun extraTokenEstimateContributesToCompactionPressure() {
        val nodes = List(4) { MessageNode.of(UIMessage.user("short $it")) }

        val plan = ConversationContextPlanner.planCompaction(
            nodes = nodes,
            activeCompacts = emptyList(),
            policy = CompactPolicy(precompactRatio = 0.50f, forceRatio = 0.80f, keepRecentTurns = 1),
            modelContextWindowTokens = 1_000,
            extraTokenEstimate = 850,
        )

        assertTrue(plan.shouldCompact)
        assertEquals("force_threshold", plan.reason)
    }
}
