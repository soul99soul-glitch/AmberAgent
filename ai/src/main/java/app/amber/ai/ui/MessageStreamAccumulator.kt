package app.amber.ai.ui

import kotlinx.serialization.json.JsonObject
import app.amber.ai.core.MessageRole
import app.amber.ai.core.TokenUsage
import app.amber.ai.core.merge
import app.amber.ai.provider.Model
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
        // Usage often arrives as a final empty-choices chunk (stream_options.include_usage).
        val choice = chunk.choices.getOrNull(0)
        if (choice == null) {
            chunk.usage?.let { usage ->
                active.usage = active.usage.merge(usage)
            }
            return
        }
        val finalMessage = choice.message
        if (choice.delta == null && finalMessage != null) {
            replaceActive(finalMessage)
            chunk.usage?.let { usage ->
                active.usage = active.usage.merge(usage)
            }
            return
        }
        val delta = choice.delta ?: choice.message ?: run {
            // 结束 chunk 可能只有 finishReason + usage（如 Gemini MAX_TOKENS
            // 无 content）——提前 return 前不能把 usage 丢掉
            chunk.usage?.let { usage ->
                active.usage = active.usage.merge(usage)
            }
            return
        }

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
            val deltaHasReasoningContent = delta.parts.any { it.isReasoningContentDelta() }
            val deltaClosesReasoning = delta.parts.any { it.isReasoningCloseDelta() }

            val imagesBeforeDelta = parts.filterIsInstance<MutablePart.Image>()

            delta.parts.forEach { deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> appendText(deltaPart)
                    is UIMessagePart.Image -> appendImage(deltaPart, imagesBeforeDelta)
                    is UIMessagePart.Reasoning -> appendReasoning(deltaPart)
                    is UIMessagePart.Tool -> appendTool(deltaPart)
                    else -> println("delta part append not supported: $deltaPart")
                }
            }

            if (hadReasoning && !deltaHasReasoningContent && deltaClosesReasoning) {
                parts.replaceAll { part ->
                    if (part is MutablePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else {
                        part
                    }
                }
            }

            if (delta.annotations.isNotEmpty()) {
                // append + dedupe: grounding/citation 可能增量到达或重发全量, 整体替换会丢失先前条目
                annotations = (annotations + delta.annotations).distinct()
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

        private fun appendImage(
            deltaPart: UIMessagePart.Image,
            imagesBeforeDelta: List<MutablePart.Image>,
        ) {
            val imageParts = parts.filterIsInstance<MutablePart.Image>()
            val identity = deltaPart.streamIdentity()
            val target = if (identity != null) {
                imageParts.lastOrNull { it.streamIdentity() == identity }
            } else {
                // 无 identity 的图只允许并入"本 delta 之前"就存在的图
                // （承接上一 chunk 的流式分片）；同一 delta 里刚创建的
                // 无 identity 图是另一张完整图，拼接会把多图响应毁掉
                imagesBeforeDelta.lastOrNull { it.streamIdentity() == null }
            }
            if (target != null) {
                target.url.append(deltaPart.streamImageData())
                target.metadata = deltaPart.metadata ?: target.metadata
            } else {
                val image = deltaPart.asStreamImage()
                parts += MutablePart.Image(
                    url = StringBuilder(image.url),
                    metadata = image.metadata,
                )
            }
        }

        private fun appendReasoning(deltaPart: UIMessagePart.Reasoning) {
            if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) return

            val lastPart = parts.lastOrNull()
            if (lastPart is MutablePart.Reasoning) {
                lastPart.reasoning.append(deltaPart.reasoning)
                if (deltaPart.reasoning.isNotEmpty()) {
                    lastPart.finishedAt = deltaPart.finishedAt
                } else if (deltaPart.finishedAt != null) {
                    lastPart.finishedAt = deltaPart.finishedAt
                }
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
            val toolParts = parts.filterIsInstance<MutablePart.Tool>()
            val target = deltaPart.findToolMergeTarget(toolParts.map { it.tool })
            if (target != null) {
                val wrapper = toolParts.first { it.tool === target }
                val merged = target.merge(deltaPart)
                parts.replaceAll { part ->
                    if (part === wrapper) MutablePart.Tool(merged) else part
                }
                return
            }
            parts += MutablePart.Tool(deltaPart.withoutStreamArgsReplace())
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
        fun snapshotImage(): UIMessagePart.Image = UIMessagePart.Image(
            url = url.toString(),
            metadata = metadata,
        )

        fun streamIdentity(): String? = snapshotImage().streamIdentity()

        override fun snapshot(): UIMessagePart = snapshotImage()
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

private fun UIMessagePart.isReasoningContentDelta(): Boolean =
    this is UIMessagePart.Reasoning && reasoning.isNotEmpty()

private fun UIMessagePart.isReasoningCloseDelta(): Boolean = when (this) {
    is UIMessagePart.Reasoning -> finishedAt != null
    is UIMessagePart.Text -> text.isNotEmpty()
    is UIMessagePart.Image -> url.isNotEmpty()
    is UIMessagePart.Tool -> true
    else -> false
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
