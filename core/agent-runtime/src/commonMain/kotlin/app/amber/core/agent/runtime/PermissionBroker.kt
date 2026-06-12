package app.amber.core.agent.runtime

import kotlin.time.Duration

interface PermissionBroker {
    suspend fun request(intent: PermissionIntent): PermissionDecision
}

data class PermissionIntent(
    val kind: PermissionKind,
    val toolId: ToolId?,
    val payloadDigest: String,
    val reason: String,
    val channel: ApprovalChannel,
)

enum class PermissionKind {
    TOOL_INVOKE,
    FILE_ACCESS,
    DESTRUCTIVE_OP,
    NETWORK_REQUEST,
    EXTERNAL_PROCESS,
}

enum class ApprovalChannel {
    IN_CHAT,
    NOTIFICATION,
    SYSTEM_DIALOG,
}

sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data class Deny(val reason: String) : PermissionDecision
    data class TimedOut(val after: Duration) : PermissionDecision
}
