package app.amber.core.ai.transformers

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAppOutputTransformerTest {
    @Test
    fun detectsCompletedMiniAppReview() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("做个小应用")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "review-start",
                        toolName = "subagent_start",
                        input = """{"custom_subagent":{"name":"MiniAppReviewer"},"task":{"objective":"Review/debug this AmberAgent MiniApp draft before final JSON output."}}""",
                        output = listOf(UIMessagePart.Text("""{"status":"running","run_id":"run-1","subagent_name":"MiniAppReviewer"}""")),
                    ),
                    UIMessagePart.Tool(
                        toolCallId = "review-wait",
                        toolName = "subagent_wait",
                        input = """{"run_id":"run-1"}""",
                        output = listOf(UIMessagePart.Text("""{"status":"completed","run_id":"run-1","task_objective":"Review/debug this AmberAgent MiniApp draft before final JSON output.","result":"PASS"}""")),
                    ),
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("""{"title":"x","description":"x","html":"<!DOCTYPE html><html></html>"}""")),
            ),
        )

        assertTrue(MiniAppOutputTransformer.hasCompletedMiniAppReview(messages, startUserIndex = 0, endAssistantIndex = 2))
    }

    @Test
    fun runningSubAgentDoesNotCountAsReview() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("做个小应用")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "review-start",
                        toolName = "subagent_start",
                        input = """{"custom_subagent":{"name":"MiniAppReviewer"},"task":{"objective":"Review/debug this AmberAgent MiniApp draft before final JSON output."}}""",
                        output = listOf(UIMessagePart.Text("""{"status":"running","run_id":"run-1"}""")),
                    ),
                    UIMessagePart.Tool(
                        toolCallId = "review-wait",
                        toolName = "subagent_wait",
                        input = """{"run_id":"run-1"}""",
                        output = listOf(UIMessagePart.Text("""{"status":"running","run_id":"run-1"}""")),
                    ),
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("""{"title":"x","description":"x","html":"<!DOCTYPE html><html></html>"}""")),
            ),
        )

        assertFalse(MiniAppOutputTransformer.hasCompletedMiniAppReview(messages, startUserIndex = 0, endAssistantIndex = 2))
    }

    @Test
    fun unrelatedSubAgentDoesNotCountAsReview() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("做个小应用")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "other-start",
                        toolName = "subagent_start",
                        input = """{"subagent_id":"writer","task":{"objective":"Write a poem"}}""",
                        output = listOf(UIMessagePart.Text("""{"status":"running","run_id":"run-1","subagent_id":"writer"}""")),
                    ),
                    UIMessagePart.Tool(
                        toolCallId = "other-wait",
                        toolName = "subagent_wait",
                        input = """{"run_id":"run-1"}""",
                        output = listOf(UIMessagePart.Text("""{"status":"completed","summary":"pass"}""")),
                    )
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("""{"title":"x","description":"x","html":"<!DOCTYPE html><html></html>"}""")),
            ),
        )

        assertFalse(MiniAppOutputTransformer.hasCompletedMiniAppReview(messages, startUserIndex = 0, endAssistantIndex = 2))
    }

    @Test
    fun completedMiniAppReviewTextWithoutMatchingStartDoesNotCount() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("做个小应用")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "review-wait",
                        toolName = "subagent_wait",
                        input = """{"run_id":"run-1"}""",
                        output = listOf(
                            UIMessagePart.Text(
                                """{"status":"completed","run_id":"run-1","task_objective":"Review/debug this AmberAgent MiniApp draft before final JSON output.","result":"PASS runnable"}"""
                            )
                        ),
                    )
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("""{"title":"x","description":"x","html":"<!DOCTYPE html><html></html>"}""")),
            ),
        )

        assertFalse(MiniAppOutputTransformer.hasCompletedMiniAppReview(messages, startUserIndex = 0, endAssistantIndex = 2))
    }
}
