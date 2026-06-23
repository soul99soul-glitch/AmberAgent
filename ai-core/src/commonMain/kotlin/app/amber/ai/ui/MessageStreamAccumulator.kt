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
        // Process usage FIRST, before the empty-choices early return.
        // OpenAI's final usage-only chunk has empty choices WITH populated usage,
        // so merging here ensures token usage is never silently lost.
        chunk.usage?.let { usage ->
            active.usage = active.usage.merge(usage)
        }

        val choice = chunk.choices.getOrNull(0) ?: return
        val finalMessage = choice.message
        if (choice.delta == null && finalMessage != null) {
            replaceActive(finalMessage)
            return  // usage already merged above
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

            delta.parts.forEach { deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> appendText(deltaPart)
                    is UIMessagePart.Image -> appendImage(deltaPart)
                    is UIMessagePart.Reasoning -> appendReasoning(deltaPart)
                    is UIMessagePart.Tool -> appendTool(deltaPart)
                    // Multi-modal parts that have no streaming-merge semantics are appended
                    // verbatim as static parts so they are NOT silently dropped. Previously
                    // this was `else -> println(...)` which discarded Video/Audio/Document/
                    // MiniApp parts on every chunk — a silent data-loss path on the main
                    // generation streaming route (GenerationHandler / OpenAIProvider).
                    // Enumerating all remaining sealed subtypes (no `else`) makes this
                    // exhaustive: adding a new UIMessagePart subclass becomes a compile
                    // error here, forcing an explicit accumulation decision.
                    is UIMessagePart.Video -> parts += MutablePart.Static(deltaPart)
                    is UIMessagePart.Audio -> parts += MutablePart.Static(deltaPart)
                    is UIMessagePart.Document -> parts += MutablePart.Static(deltaPart)
                    is UIMessagePart.MiniApp -> parts += MutablePart.Static(deltaPart)
                }
            }

            if (hadReasoning && !deltaHasReasoningContent && deltaClosesReasoning) {
                for (i in parts.indices) {
                    val part = parts[i]
                    if (part is MutablePart.Reasoning && part.finishedAt == null) {
                        parts[i] = part.copy(finishedAt = Clock.System.now())
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
            val tool = if (deltaPart.toolCallId.isBlank()) {
                // Argument-fragment delta (no id/name). Route it to the call currently
                // being streamed: the MOST RECENT part that can still take args. Prefer a
                // matching stream index, but skip parts whose args are already complete.
                // Some gateways (MiMo) reuse stream index 0 across sequential calls, so
                // matching the *first* index-0 tool would append a new call's fragments
                // onto a finished one → "{...}{...}" → gateway rejects it (400/500).
                val streamIndex = deltaPart.streamToolIndex()
                val targetTool = (streamIndex?.let { index ->
                    parts.lastOrNull {
                        it is MutablePart.Tool && it.tool.streamToolIndex() == index &&
                            it.tool.canAcceptArgsDelta(deltaPart)
                    }
                } ?: parts.lastOrNull {
                    it is MutablePart.Tool && it.tool.canAcceptArgsDelta(deltaPart)
                }) as? MutablePart.Tool
                targetTool?.tool?.merge(deltaPart)?.let { merged ->
                    val idx = parts.indexOf(targetTool)
                    if (idx >= 0) parts[idx] = MutablePart.Tool(merged)
                    return
                }
                deltaPart.copy()
            } else {
                // First delta of a tool (carries id/name). Match an existing part by id,
                // or fall back to stream index — but only when it is the same call
                // (canMergeDelta), never folding a distinct parallel call in by index.
                val existing = ((parts.find {
                    it is MutablePart.Tool && it.tool.toolCallId == deltaPart.toolCallId
                } as? MutablePart.Tool)
                    ?: deltaPart.streamToolIndex()?.let { index ->
                        parts.lastOrNull { it is MutablePart.Tool && it.tool.streamToolIndex() == index } as? MutablePart.Tool
                    })?.takeIf { it.tool.canMergeDelta(deltaPart) }
                existing?.let {
                    val merged = it.tool.merge(deltaPart)
                    val idx = parts.indexOf(existing)
                    if (idx >= 0) parts[idx] = MutablePart.Tool(merged)
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
