package me.rerere.rikkahub.data.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Throwable.toAgentToolFailureJson(): String =
    toAgentToolFailurePayload().toString()

internal fun Throwable.toAgentToolFailurePayload() = buildJsonObject {
    put("status", "failed")
    put("message", sanitizedToolFailureMessage())
    put("recoverable", isRecoverableToolFailure())
}

private fun Throwable.sanitizedToolFailureMessage(): String {
    val raw = message
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(360)
        ?: javaClass.simpleName.ifBlank { "Tool execution failed" }
    return raw
        .replace(Regex("\\bat\\s+[\\w.$]+\\([^)]*\\)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Tool execution failed" }
}

private fun Throwable.isRecoverableToolFailure(): Boolean = when (this) {
    is VirtualMachineError -> false
    is ThreadDeath -> false
    else -> true
}
