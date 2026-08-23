package app.amber.feature.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AgentRunStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("waiting_for_permission")
    WAITING_FOR_PERMISSION,

    @SerialName("waiting_for_user")
    WAITING_FOR_USER,

    @SerialName("failed")
    FAILED,

    @SerialName("completed")
    COMPLETED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
enum class ToolActivityStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("waiting_for_permission")
    WAITING_FOR_PERMISSION,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
data class SandboxActivityUiState(
    val toolCallId: String,
    val toolName: String,
    val title: String,
    val status: ToolActivityStatus,
    val conversationId: String? = null,
    val inputPreview: String = "",
    val outputTail: String = "",
    val runtime: String = "",
    val workspace: String = "",
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val canCancel: Boolean = false,
    val stepIndex: Int? = null,
    val stepTotal: Int? = null,
)
