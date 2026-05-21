package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CLOSING_TAG_REGEX = Regex("</think>")

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : TailSafeOutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        var changed = false
        val transformed = messages.map { message ->
            val next = transformMessage(message, finishOpenReasoningAt = null)
            if (next !== message) changed = true
            next
        }
        return if (changed) transformed else messages
    }

    override suspend fun visualTransformTail(
        ctx: TransformerContext,
        message: UIMessage,
    ): UIMessage {
        return transformMessage(message, finishOpenReasoningAt = null)
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val now = Clock.System.now()
        var changed = false
        val transformed = messages.map { message ->
            val next = transformMessage(message, finishOpenReasoningAt = now)
            if (next !== message) changed = true
            next
        }
        return if (changed) transformed else messages
    }

    private fun transformMessage(
        message: UIMessage,
        finishOpenReasoningAt: kotlin.time.Instant?,
    ): UIMessage {
        if (message.role != MessageRole.ASSISTANT || !message.hasPart<UIMessagePart.Text>()) {
            return message
        }
        var changed = false
        val parts = message.parts.flatMap { part ->
            if (part is UIMessagePart.Text && THINKING_REGEX.containsMatchIn(part.text)) {
                changed = true
                val stripped = part.text.replace(THINKING_REGEX, "")
                val reasoning = THINKING_REGEX.find(part.text)?.groupValues?.getOrNull(1)?.trim()
                    ?: ""
                val hasClosingTag = CLOSING_TAG_REGEX.containsMatchIn(part.text)
                listOf(
                    UIMessagePart.Reasoning(
                        reasoning = reasoning,
                        createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                        finishedAt = finishOpenReasoningAt ?: if (hasClosingTag) Clock.System.now() else null,
                    ),
                    part.copy(text = stripped),
                )
            } else {
                listOf(part)
            }
        }
        return if (changed) message.copy(parts = parts) else message
    }
}
