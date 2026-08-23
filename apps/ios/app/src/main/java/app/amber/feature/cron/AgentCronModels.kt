package app.amber.feature.cron

import kotlinx.serialization.Serializable

@Serializable
data class AgentCronTask(
    val id: String,
    val title: String,
    val prompt: String,
    val cronExpression: String,
    val timezoneId: String,
    val conversationId: String,
    val enabled: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val nextRunAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastStatus: AgentCronTaskStatus = AgentCronTaskStatus.Idle,
    val lastError: String? = null,
    val runCount: Int = 0,
)

@Serializable
enum class AgentCronTaskStatus {
    Idle,
    Queued,
    Running,
    Succeeded,
    Failed,
}

@Serializable
data class AgentCronTaskStore(
    val tasks: List<AgentCronTask> = emptyList(),
)
