package me.rerere.rikkahub.data.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
