package app.amber.core.agent.runtime

internal actual fun generateUuidString(): String = java.util.UUID.randomUUID().toString()
