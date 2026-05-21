package me.rerere.ai.ui

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import kotlin.time.Clock
import kotlin.time.Instant

class MessageStreamAccumulator(
    initialMessages: List<UIMessage>,
    private val model: Model? = null,
) {
    init {
        require(initialMessages.isNotEmpty()) {
            "messages must not be empty"
        }
    }

    private val prefix = initialMessages.dropLast(1).toMutableList()
    private var active = MutableMessage.from(initialMessages.last())

    fun append(chunk: MessageChunk) {
        val choice = chunk.choices.getOrNull(0) ?: return
        val finalMessage = choice.message
        if (choice.delta == null && finalMessage != null) {
            replaceActive(finalMessage)
            chunk.usage?.let { usage ->
                active.usage = active.usage.merge(usage)
            }
            return
        }
        val delta = choice.delta ?: choice.message ?: return

        if (active.role != delta.role) {
            prefix += active.snapshot()
            active = MutableMessage(
                source = UIMessage(
                    modelId = model?.id,
                    role = delta.role,
                    parts = emptyList()
                )
            )
        }

        active.append(delta)
        chunk.usage?.let { usage ->
            active.usage = active.usage.merge(usage)
        }
    }

    fun snapshot(): List<UIMessage> = prefix + active.snapshot()

    private fun replaceActive(message: UIMessage) {
        val replacement = message.copy(modelId = message.modelId ?: model?.id)
        if (active.role != replacement.role) {
            prefix += active.snapshot()
        }
        active = MutableMessage.from(replacement)
    }

    private class MutableMessage(
        private val source: UIMessage,
    ) {
        val role: MessageRole = source.role
        private val parts = source.parts.map { it.toMutablePart() }.toMutableList()
        private var annotations = source.annotations
        var usage: TokenUsage? = source.usage

        fun append(delta: UIMessage) {
            val hadReasoning = parts.any { it is MutablePart.Reasoning }
            val deltaHasReasoning = delta.parts.any { it is UIMessagePart.Reasoning }

            delta.parts.forEach { deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> appendText(deltaPart)
                    is UIMessagePart.Image -> appendImage(deltaPart)
                    is UIMessagePart.Reasoning -> appendReasoning(deltaPart)
                    is UIMessagePart.Tool -> appendTool(deltaPart)
                    else -> println("delta part append not supported: $deltaPart")
                }
            }

            if (hadReasoning && !deltaHasReasoning) {
                parts.replaceAll { part ->
                    if (part is MutablePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else {
                        part
                    }
                }
            }

            if (delta.annotations.isNotEmpty()) {
                annotations = delta.annotations
            }
        }

        fun snapshot(): UIMessage = source.copy(
            parts = parts.map { it.snapshot() }.coalesceStreamParts(),
            annotations = annotations,
            usage = usage,
        )

        private fun appendText(deltaPart: UIMessagePart.Text) {
            if (deltaPart.text.isEmpty()) return

            val lastPart = parts.lastOrNull()
            if (lastPart is MutablePart.Text) {
                lastPart.text.append(deltaPart.text)
                lastPart.metadata = deltaPart.metadata ?: lastPart.metadata
            } else {
                parts += MutablePart.Text(
                    text = StringBuilder(deltaPart.text),
                    metadata = deltaPart.metadata
                )
            }
        }

        private fun appendImage(deltaPart: UIMessagePart.Image) {
            val lastPart = parts.lastOrNull()
            if (lastPart is MutablePart.Image) {
                lastPart.url.append(deltaPart.url)
                lastPart.metadata = deltaPart.metadata ?: lastPart.metadata
            } else {
                parts += MutablePart.Image(
                    url = StringBuilder("data:image/png;base64,${deltaPart.url}"),
                    metadata = deltaPart.metadata
                )
            }
        }

        private fun appendReasoning(deltaPart: UIMessagePart.Reasoning) {
            if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) return

            val lastPart = parts.lastOrNull()
            if (lastPart is MutablePart.Reasoning) {
                lastPart.reasoning.append(deltaPart.reasoning)
                lastPart.finishedAt = null
                lastPart.metadata = deltaPart.metadata ?: lastPart.metadata
            } else {
                parts += MutablePart.Reasoning(
                    reasoning = StringBuilder(deltaPart.reasoning),
                    createdAt = deltaPart.createdAt,
                    finishedAt = deltaPart.finishedAt,
                    metadata = deltaPart.metadata
                )
            }
        }

        private fun appendTool(deltaPart: UIMessagePart.Tool) {
            val tool = if (deltaPart.toolCallId.isBlank()) {
                val lastTool = parts.lastOrNull { it is MutablePart.Tool } as? MutablePart.Tool
                lastTool?.tool?.merge(deltaPart)?.let { merged ->
                    parts.replaceAll { part ->
                        if (part === lastTool) MutablePart.Tool(merged) else part
                    }
                    return
                }
                deltaPart.copy()
            } else {
                val existing = parts.find {
                    it is MutablePart.Tool && it.tool.toolCallId == deltaPart.toolCallId
                } as? MutablePart.Tool
                existing?.let {
                    val merged = it.tool.merge(deltaPart)
                    parts.replaceAll { part ->
                        if (part === existing) MutablePart.Tool(merged) else part
                    }
                    return
                }
                deltaPart.copy()
            }
            parts += MutablePart.Tool(tool)
        }

        companion object {
            fun from(message: UIMessage): MutableMessage = MutableMessage(message)
        }
    }

}

private sealed interface MutablePart {
    fun snapshot(): UIMessagePart

    data class Text(
        val text: StringBuilder,
        var metadata: JsonObject?,
    ) : MutablePart {
        override fun snapshot(): UIMessagePart = UIMessagePart.Text(
            text = text.toString(),
            metadata = metadata,
        )
    }

    data class Image(
        val url: StringBuilder,
        var metadata: JsonObject?,
    ) : MutablePart {
        override fun snapshot(): UIMessagePart = UIMessagePart.Image(
            url = url.toString(),
            metadata = metadata,
        )
    }

    data class Reasoning(
        val reasoning: StringBuilder,
        val createdAt: Instant,
        var finishedAt: Instant?,
        var metadata: JsonObject?,
    ) : MutablePart {
        override fun snapshot(): UIMessagePart = UIMessagePart.Reasoning(
            reasoning = reasoning.toString(),
            createdAt = createdAt,
            finishedAt = finishedAt,
            metadata = metadata,
        )
    }

    data class Tool(
        val tool: UIMessagePart.Tool,
    ) : MutablePart {
        override fun snapshot(): UIMessagePart = tool
    }

    data class Static(
        val part: UIMessagePart,
    ) : MutablePart {
        override fun snapshot(): UIMessagePart = part
    }
}

private fun UIMessagePart.toMutablePart(): MutablePart {
    return when (this) {
        is UIMessagePart.Text -> MutablePart.Text(
            text = StringBuilder(text),
            metadata = metadata,
        )

        is UIMessagePart.Image -> MutablePart.Image(
            url = StringBuilder(url),
            metadata = metadata,
        )

        is UIMessagePart.Reasoning -> MutablePart.Reasoning(
            reasoning = StringBuilder(reasoning),
            createdAt = createdAt,
            finishedAt = finishedAt,
            metadata = metadata,
        )

        is UIMessagePart.Tool -> MutablePart.Tool(this)
        else -> MutablePart.Static(this)
    }
}

private fun List<UIMessagePart>.coalesceStreamParts(): List<UIMessagePart> {
    val result = mutableListOf<UIMessagePart>()
    var pendingText: UIMessagePart.Text? = null
    var pendingExplicitEmptyReasoning: UIMessagePart.Reasoning? = null

    fun flushText() {
        pendingText?.let { result += it }
        pendingText = null
    }

    fun flushExplicitEmptyReasoning() {
        val marker = pendingExplicitEmptyReasoning ?: return
        if (result.none { it is UIMessagePart.Reasoning && it.hasExplicitReasoningContentField() }) {
            result += marker
        }
        pendingExplicitEmptyReasoning = null
    }

    for (part in this) {
        when (part) {
            is UIMessagePart.Text -> {
                if (part.text.isEmpty()) continue
                val previous = pendingText
                pendingText = if (previous == null) {
                    part
                } else {
                    previous.copy(
                        text = previous.text + part.text,
                        metadata = part.metadata ?: previous.metadata,
                    )
                }
            }

            is UIMessagePart.Reasoning -> {
                if (part.reasoning.isBlank()) {
                    if (part.hasExplicitReasoningContentField()) {
                        pendingExplicitEmptyReasoning = pendingExplicitEmptyReasoning ?: part
                    }
                } else {
                    flushText()
                    pendingExplicitEmptyReasoning = null
                    result += part
                }
            }

            else -> {
                flushText()
                flushExplicitEmptyReasoning()
                result += part
            }
        }
    }

    flushText()
    flushExplicitEmptyReasoning()
    return result
}
