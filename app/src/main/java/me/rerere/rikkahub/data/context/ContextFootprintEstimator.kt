package me.rerere.rikkahub.data.context

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

/**
 * UI-facing estimate of the input footprint that will be sent to the model on the next turn.
 *
 * This intentionally differs from "everything visible in the timeline": model reasoning and
 * verbose tool previews can be useful UI progress, but they are not sent back as raw prompt
 * context in the same shape. Keep this estimator conservative and aligned with context
 * compaction's capped tool-output policy.
 */
@Suppress("DEPRECATION")
object ContextFootprintEstimator {
    fun estimateConversationInputTokens(conversation: Conversation): Int =
        estimateMessages(conversation.currentMessages)

    fun inputFingerprint(messages: List<UIMessage>): Long {
        var acc = 1125899906842597L
        fun mix(value: Long) {
            acc = (acc * 31) xor value
        }
        messages.forEach { message ->
            mix(message.id.hashCode().toLong())
            mix(message.role.name.hashCode().toLong())
            mix(message.parts.size.toLong())
            message.parts.forEach { part ->
                mix(part.inputFootprintFingerprint())
            }
        }
        return acc
    }

    fun estimateMessages(messages: List<UIMessage>): Int {
        val chars = messages.sumOf { message ->
            message.role.name.length + message.parts.sumOf { it.inputFootprintChars() }
        }
        return (chars / 4).coerceAtLeast(messages.size * 4)
    }

    private fun UIMessagePart.inputFootprintChars(): Int = when (this) {
        is UIMessagePart.Text -> text.length
        is UIMessagePart.Reasoning -> 0
        is UIMessagePart.Tool -> {
            val inputChars = input.length.coerceAtMost(2_000)
            val outputChars = if (isExecuted) {
                ToolResultCompactor.summarize(output, maxChars = 8_000).length
            } else {
                0
            }
            inputChars + outputChars
        }
        is UIMessagePart.ToolCall -> arguments.length.coerceAtMost(2_000)
        is UIMessagePart.ToolResult -> content.toString().takeMiddle(8_000).length
        is UIMessagePart.Image -> 80
        is UIMessagePart.Video -> 80
        is UIMessagePart.Audio -> 80
        is UIMessagePart.Document -> fileName.length + 80
        UIMessagePart.Search -> 20
    }

    private fun UIMessagePart.inputFootprintFingerprint(): Long = when (this) {
        is UIMessagePart.Text -> 1_000L + text.length
        is UIMessagePart.Reasoning -> 2_000L
        is UIMessagePart.Tool -> {
            var acc = 3_000L
            acc = acc * 31 + toolCallId.hashCode()
            acc = acc * 31 + toolName.hashCode()
            acc = acc * 31 + input.length.coerceAtMost(2_000)
            acc = acc * 31 + if (isExecuted) 1 else 0
            output.forEach { acc = acc * 31 + it.inputFootprintFingerprint() }
            acc
        }
        is UIMessagePart.ToolCall -> 4_000L + arguments.length.coerceAtMost(2_000)
        is UIMessagePart.ToolResult -> 5_000L + content.toString().length.coerceAtMost(8_000)
        is UIMessagePart.Image -> 6_000L + url.length
        is UIMessagePart.Video -> 7_000L + url.length
        is UIMessagePart.Audio -> 8_000L + url.length + fileName.length
        is UIMessagePart.Document -> 9_000L + fileName.length + url.length
        UIMessagePart.Search -> 10_000L
    }

}
