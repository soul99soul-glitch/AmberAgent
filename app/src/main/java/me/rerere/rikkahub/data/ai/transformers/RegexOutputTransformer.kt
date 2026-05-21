package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : TailSafeOutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return messages // No regexes, return original messages
        var changed = false
        val transformed = messages.map { message ->
            val next = transformMessage(ctx, message)
            if (next !== message) changed = true
            next
        }
        return if (changed) transformed else messages
    }

    override suspend fun visualTransformTail(
        ctx: TransformerContext,
        message: UIMessage,
    ): UIMessage {
        if (ctx.assistant.regexes.isEmpty()) return message
        return transformMessage(ctx, message)
    }

    private fun transformMessage(
        ctx: TransformerContext,
        message: UIMessage,
    ): UIMessage {
        val assistant = ctx.assistant
        val scope = when (message.role) {
            MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
            else -> return message
        }
        var changed = false
        val parts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val text = part.text.replaceRegexes(assistant, scope, visual = false)
                    if (text != part.text) changed = true
                    if (text == part.text) part else part.copy(text = text)
                }

                is UIMessagePart.Reasoning -> {
                    val reasoning = part.reasoning.replaceRegexes(assistant, scope, visual = false)
                    if (reasoning != part.reasoning) changed = true
                    if (reasoning == part.reasoning) part else part.copy(reasoning = reasoning)
                }

                else -> part
            }
        }
        return if (changed) message.copy(parts = parts) else message
    }
}
