package me.rerere.rikkahub.data.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.uuid.Uuid

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
data class AgentRun(
    val id: Uuid = Uuid.random(),
    val conversationId: Uuid,
    val status: AgentRunStatus = AgentRunStatus.RUNNING,
    val events: List<AgentEvent> = emptyList(),
)

@Serializable
data class AgentEvent(
    val id: Uuid = Uuid.random(),
    val type: AgentEventType,
    val title: String,
    val detail: String = "",
    val createdAtEpochMillis: Long = Instant.now().toEpochMilli(),
)

@Serializable
enum class AgentEventType {
    @SerialName("model")
    MODEL,

    @SerialName("tool_call")
    TOOL_CALL,

    @SerialName("tool_result")
    TOOL_RESULT,

    @SerialName("permission")
    PERMISSION,

    @SerialName("artifact")
    ARTIFACT,

    @SerialName("error")
    ERROR,
}

@Serializable
data class ToolInvocation(
    val id: String,
    val name: String,
    val status: AgentRunStatus,
    val inputPreview: String = "",
    val outputPreview: String = "",
)

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

@Serializable
data class ArtifactRef(
    val path: String,
    val mimeType: String = "application/octet-stream",
    val description: String = "",
)

@Serializable
data class KoogSpikeStatus(
    val dependencyCoordinate: String = "ai.koog:koog-agents:0.8.0",
    val state: State = State.PLANNED,
    val notes: String = "Koog is evaluated behind this contract before replacing the existing AmberAgent tool loop.",
) {
    @Serializable
    enum class State {
        @SerialName("planned")
        PLANNED,

        @SerialName("passed")
        PASSED,

        @SerialName("failed")
        FAILED,
    }
}
