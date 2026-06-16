package app.amber.feature.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AgentTaskStatus {
    @SerialName("queued")
    QUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("timed_out")
    TIMED_OUT,

    @SerialName("interrupted")
    INTERRUPTED,
}

val AgentTaskStatus.running: Boolean
    get() = this == AgentTaskStatus.QUEUED || this == AgentTaskStatus.RUNNING

@Serializable
enum class AgentTaskQueueState {
    @SerialName("scheduled")
    SCHEDULED,

    @SerialName("queued")
    QUEUED,

    @SerialName("active")
    ACTIVE,

    @SerialName("terminal")
    TERMINAL,
}

@Serializable
enum class AgentTaskRecoveryState {
    @SerialName("active")
    ACTIVE,

    @SerialName("scheduled")
    SCHEDULED,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("restorable")
    RESTORABLE,

    @SerialName("retryable")
    RETRYABLE,

    @SerialName("output_only")
    OUTPUT_ONLY,

    @SerialName("cleanup_only")
    CLEANUP_ONLY,
}

@Serializable
data class AgentTaskRetryPolicy(
    val retryable: Boolean = false,
    @SerialName("requires_approval")
    val requiresApproval: Boolean = true,
    @SerialName("max_retries")
    val maxRetries: Int = 0,
    @SerialName("retry_count")
    val retryCount: Int = 0,
    val reason: String? = null,
)

@Serializable
data class AgentTaskOutputRef(
    val type: String = "file",
    val path: String,
    @SerialName("tail_offset")
    val tailOffset: Long = 0L,
    val exists: Boolean = false,
)

@Serializable
data class AgentTaskSnapshot(
    @SerialName("schema_version")
    val schemaVersion: Int = 2,
    @SerialName("task_id")
    val taskId: String,
    val type: String,
    val title: String,
    val spec: JsonObject? = null,
    val runtime: String? = null,
    @SerialName("queue_state")
    val queueState: AgentTaskQueueState = AgentTaskQueueState.ACTIVE,
    @SerialName("recovery_state")
    val recoveryState: AgentTaskRecoveryState = AgentTaskRecoveryState.ACTIVE,
    @SerialName("retry_policy")
    val retryPolicy: AgentTaskRetryPolicy = AgentTaskRetryPolicy(),
    @SerialName("output_ref")
    val outputRef: AgentTaskOutputRef? = null,
    @SerialName("source_tool_name")
    val sourceToolName: String? = null,
    @SerialName("source_conversation_id")
    val sourceConversationId: String? = null,
    val status: AgentTaskStatus,
    @SerialName("output_path")
    val outputPath: String? = null,
    @SerialName("output_offset")
    val outputOffset: Long = 0L,
    @SerialName("created_at_ms")
    val createdAtMs: Long,
    @SerialName("updated_at_ms")
    val updatedAtMs: Long = createdAtMs,
    @SerialName("last_heartbeat_ms")
    val lastHeartbeatMs: Long? = null,
    val notified: Boolean = false,
    @SerialName("cancel_capability")
    val cancelCapability: Boolean = false,
    @SerialName("permission_trace_id")
    val permissionTraceId: String? = null,
    val summary: String? = null,
    @SerialName("last_error_code")
    val lastErrorCode: String? = null,
    val error: String? = null,
)
