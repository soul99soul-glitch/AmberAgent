package me.rerere.rikkahub.data.agent.subagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS = 2
const val DEFAULT_SUB_AGENT_TIMEOUT_MS = 5 * 60_000L
const val DEFAULT_SUB_AGENT_MAX_TURNS = 4
const val DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS = 12_000

@Serializable
data class SubAgentRuntimeSetting(
    val enabled: Boolean = false,
    val allowDynamicSubAgents: Boolean = true,
    val maxConcurrentRuns: Int = DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS,
    val timeoutMs: Long = DEFAULT_SUB_AGENT_TIMEOUT_MS,
    val maxTurns: Int = DEFAULT_SUB_AGENT_MAX_TURNS,
    val outputBudgetChars: Int = DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
)

@Serializable
data class SubAgentDefinition(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolAllowlist: Set<String>,
    val maxTurns: Int = DEFAULT_SUB_AGENT_MAX_TURNS,
    val timeoutMs: Long = DEFAULT_SUB_AGENT_TIMEOUT_MS,
    val outputBudgetChars: Int = DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
    val dynamic: Boolean = false,
)

@Serializable
data class SubAgentTaskSpec(
    val objective: String,
    @SerialName("output_format")
    val outputFormat: String,
    @SerialName("tools_and_sources")
    val toolsAndSources: String,
    val boundaries: String,
    val context: String = "",
)

@Serializable
enum class SubAgentRunStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("approval_required")
    APPROVAL_REQUIRED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("timed_out")
    TIMED_OUT,

    @SerialName("interrupted")
    INTERRUPTED,
}

val SubAgentRunStatus.running: Boolean
    get() = this == SubAgentRunStatus.RUNNING

@Serializable
data class SubAgentResult(
    val status: SubAgentRunStatus,
    val summary: String = "",
    val findings: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    @SerialName("recommended_next_steps")
    val recommendedNextSteps: List<String> = emptyList(),
    val error: String = "",
)

@Serializable
data class SubAgentRun(
    @SerialName("run_id")
    val runId: String,
    @SerialName("parent_conversation_id")
    val parentConversationId: Uuid,
    val definition: SubAgentDefinition,
    val task: SubAgentTaskSpec,
    val status: SubAgentRunStatus,
    val result: SubAgentResult? = null,
    @SerialName("transcript_path")
    val transcriptPath: String,
    @SerialName("started_at_ms")
    val startedAtMs: Long,
    @SerialName("updated_at_ms")
    val updatedAtMs: Long = startedAtMs,
)

data class SubAgentValidationResult(
    val definition: SubAgentDefinition,
    val warnings: List<String> = emptyList(),
)
