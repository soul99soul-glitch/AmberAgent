package app.amber.ai.provider.providers

import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class StreamProtocol {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    CLAUDE,
    GOOGLE,
}

internal class IncompleteStreamException(protocol: StreamProtocol) :
    EOFException("$protocol stream closed before its terminal event")

internal class StreamTerminationGuard(
    private val protocol: StreamProtocol,
) {
    private val terminalSeen = AtomicBoolean(false)

    fun observe(type: String?, data: String) {
        val isTerminal = when (protocol) {
            StreamProtocol.OPENAI_CHAT -> data.lineSequence().any { line ->
                line.trim().removePrefix("data:").trim() == "[DONE]"
            }
            StreamProtocol.OPENAI_RESPONSES ->
                type == "response.completed" || type == "response.incomplete"
            StreamProtocol.CLAUDE -> type == "message_stop"
            StreamProtocol.GOOGLE -> false
        }
        if (isTerminal) terminalSeen.set(true)
    }

    fun observeFinishReason(finishReason: String?) {
        val normalized = finishReason?.trim()?.uppercase()
        val isTerminal = !normalized.isNullOrEmpty() &&
            normalized != "UNSPECIFIED" &&
            normalized != "FINISH_REASON_UNSPECIFIED"
        if (protocol == StreamProtocol.GOOGLE && isTerminal) {
            terminalSeen.set(true)
        }
    }

    fun cleanEofCause(): Throwable? =
        if (terminalSeen.get()) null else IncompleteStreamException(protocol)
}
