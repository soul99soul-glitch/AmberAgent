package app.amber.core.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ContextFootprintEstimatorTest {
    @Test
    fun reasoningCountsAsInputFootprint() {
        val base = ContextFootprintEstimator.estimateMessages(
            listOf(UIMessage.assistant("visible"))
        )
        val withReasoning = ContextFootprintEstimator.estimateMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("visible"),
                        UIMessagePart.Reasoning("x".repeat(20_000)),
                    ),
                )
            )
        )

        assertTrue("reasoning footprint should be counted", withReasoning > base)
    }

    @Test
    fun toolOutputContributesToFootprint() {
        val estimate = ContextFootprintEstimator.estimateMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "tool-1",
                            toolName = "large_tool",
                            input = "i".repeat(10_000),
                            output = listOf(UIMessagePart.Text("o".repeat(100_000))),
                        )
                    ),
                )
            )
        )

        assertTrue("tool footprint should include large output, got $estimate", estimate > 20_000)
    }

    @Test
    fun fingerprintIgnoresUsageButTracksReasoningGrowth() {
        val base = UIMessage.assistant("done")
        val withUsage = base.copy(usage = TokenUsage(promptTokens = 1234, completionTokens = 56))
        val withShortReasoning = base.copy(parts = base.parts + UIMessagePart.Reasoning("thinking"))
        val withLongReasoning = base.copy(parts = base.parts + UIMessagePart.Reasoning("thinking".repeat(1000)))

        assertEquals(
            ContextFootprintEstimator.inputFingerprint(listOf(base)),
            ContextFootprintEstimator.inputFingerprint(listOf(withUsage))
        )
        assertNotEquals(
            ContextFootprintEstimator.inputFingerprint(listOf(withShortReasoning)),
            ContextFootprintEstimator.inputFingerprint(listOf(withLongReasoning))
        )
    }

    @Test
    fun estimateUsesLatestCumulativeHandoffOnly() {
        val messages = List(8) { UIMessage.user("message $it " + "x".repeat(1_000)) }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = messages.map { MessageNode.of(it) },
        )
        val olderCompact = ConversationCompact(
            id = "summary-old",
            conversationId = conversation.id.toString(),
            summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
                summary = "Older compact.",
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
            conversationId = conversation.id.toString(),
            summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
                summary = "Latest cumulative compact.",
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

        val estimate = ContextFootprintEstimator.estimateConversationInputTokens(
            conversation = conversation,
            activeCompacts = listOf(olderCompact, latestCompact),
        )
        val expected = ContextFootprintEstimator.estimateMessages(
            listOf(UIMessage.system(CompactSummaryPayloads.injectionText(latestCompact))) + messages.drop(6)
        )

        assertEquals(expected, estimate)
        assertTrue(estimate < ContextFootprintEstimator.estimateMessages(messages))
    }
}
