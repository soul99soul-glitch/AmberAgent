package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import app.amber.core.settings.Settings
import me.rerere.rikkahub.data.model.Assistant

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val forceImageToText: Boolean = false,
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface TailSafeOutputMessageTransformer : OutputMessageTransformer {
    suspend fun visualTransformTail(
        ctx: TransformerContext,
        message: UIMessage,
    ): UIMessage
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    forceImageToText: Boolean = false,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings, processingStatus, forceImageToText)
    return transformers.fold(this) { acc, transformer ->
        val output = transformer.transform(ctx, acc)
        if (output !== acc) {
            validateTransformerInvariants(
                before = acc,
                after = output,
                transformerName = transformer.javaClass.simpleName
            )
        }
        output
    }
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            val output = transformer.visualTransform(ctx, acc)
            if (output !== acc) {
                validateTransformerInvariants(
                    before = acc,
                    after = output,
                    transformerName = transformer.javaClass.simpleName
                )
            }
            output
        } else {
            acc
        }
    }
}

suspend fun List<UIMessage>.visualTransformsStreamingTail(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val tailIndex = indexOfLast { it.role == MessageRole.ASSISTANT }
    if (tailIndex < 0) return this
    val ctx = TransformerContext(context, model, assistant, settings)
    var tail = this[tailIndex]
    var changed = false
    transformers.forEach { transformer ->
        if (transformer is TailSafeOutputMessageTransformer) {
            val next = transformer.visualTransformTail(ctx, tail)
            if (next !== tail) {
                tail = next
                changed = true
            }
        }
    }
    if (!changed) return this
    val output = toMutableList().also { it[tailIndex] = tail }
    if (BuildConfig.DEBUG) {
        validateTransformerInvariants(
            before = this,
            after = output,
            transformerName = "StreamingTailVisualTransform",
        )
    }
    return output
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.onGenerationFinish(ctx, acc).also { output ->
                validateTransformerInvariants(
                    before = acc,
                    after = output,
                    transformerName = transformer.javaClass.simpleName
                )
            }
        } else {
            acc
        }
    }
}

internal fun validateTransformerInvariants(
    before: List<UIMessage>,
    after: List<UIMessage>,
    transformerName: String,
) {
    if (before.any { it.role == MessageRole.SYSTEM }) {
        require(after.any { it.role == MessageRole.SYSTEM }) {
            "$transformerName removed the system message"
        }
    }
    val beforeTools = before.toolSignatures()
    val afterTools = after.toolSignatures()
    require(beforeTools == afterTools) {
        "$transformerName modified tool call/result ordering"
    }
}

private fun List<UIMessage>.toolSignatures(): List<ToolSignature> =
    flatMap { message ->
        message.parts.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Tool -> ToolSignature(
                    id = part.toolCallId,
                    name = part.toolName,
                    input = part.input,
                    executed = part.isExecuted,
                )

                is UIMessagePart.ToolCall -> ToolSignature(
                    id = part.toolCallId,
                    name = part.toolName,
                    input = part.arguments,
                    executed = false,
                )

                is UIMessagePart.ToolResult -> ToolSignature(
                    id = part.toolCallId,
                    name = part.toolName,
                    input = part.arguments.toString(),
                    executed = true,
                )

                else -> null
            }
        }
    }

private data class ToolSignature(
    val id: String,
    val name: String,
    val input: String,
    val executed: Boolean,
)
