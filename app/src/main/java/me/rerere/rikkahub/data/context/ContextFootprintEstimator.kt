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
        // 2026-05-15: Reasoning was 0 here on the theory "it's not sent back
        // as input on the next turn." That's WRONG for several real providers
        // (Claude Extended Thinking, Gemini reasoning) and even when the raw
        // reasoning isn't re-sent, the prepareContext path counts it via the
        // planner anyway. Result: UI context ring showed 32K when the model
        // was actually receiving 173K → user surprised when GLM-5.1 stalled
        // at "still in 30K usage". Align both estimators (this one and
        // ConversationContextPlanner.estimatedChars) on the same rule:
        // count reasoning at its raw length, count tool I/O un-capped.
        is UIMessagePart.Reasoning -> reasoning.length
        is UIMessagePart.Tool -> {
            // Tool output cap dropped from 8_000. The provider sees the full
            // payload, the UI ring should reflect what the provider sees.
            val outputChars = if (isExecuted) {
                output.sumOf { it.inputFootprintChars() }
            } else {
                0
            }
            input.length + outputChars
        }
        is UIMessagePart.ToolCall -> arguments.length
        is UIMessagePart.ToolResult -> content.toString().length
        // See ConversationContextPlanner.estimatedChars for the rationale on
        // the 4500-char multimodal stand-in (≈ 1125 tokens at the /4 ratio,
        // mid-range across OpenAI/Claude/Gemini vision token costs).
        is UIMessagePart.Image -> 4_500
        is UIMessagePart.Video -> 4_500
        is UIMessagePart.Audio -> 4_500
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
