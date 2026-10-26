package app.amber.feature.subagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.amber.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

const val DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS = 2
const val DEFAULT_SUB_AGENT_TIMEOUT_MS = 5 * 60_000L
const val DEFAULT_SUB_AGENT_MAX_TURNS = 4
const val DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS = 12_000
const val EXTENDED_SUB_AGENT_TIMEOUT_MS = 20 * 60_000L
const val EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS = 200_000

@Serializable
data class SubAgentRuntimeSetting(
    val enabled: Boolean = false,
    val mode: SubAgentMode = SubAgentMode.ROSTER,
    val allowDynamicSubAgents: Boolean = true,
    val maxConcurrentRuns: Int = DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS,
    val timeoutMs: Long = DEFAULT_SUB_AGENT_TIMEOUT_MS,
    val maxTurns: Int = DEFAULT_SUB_AGENT_MAX_TURNS,
    val outputBudgetChars: Int = DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
    /** Per built-in id user override: prompt / model / temperature / reasoning / budgets. */
    val overrides: Map<String, SubAgentOverride> = emptyMap(),
    /**
     * User-defined persistent custom roles (vs ephemeral [SubAgentDefinition.dynamic] from main agent).
     *
     * SECURITY: Any UI/import path that writes here MUST run inputs through
     * [SubAgentValidator.validateNarrowDynamicRole], [SubAgentValidator.validateToolAllowlist],
     * and [SubAgentValidator.validateBudgets] first. Otherwise users could persist a custom role
     * with an unbounded tool allowlist or budget, bypassing the runtime caps.
     */
    val customDefinitions: List<SubAgentDefinition> = emptyList(),
)

@Serializable
enum class SubAgentMode {
    @SerialName("roster")
    ROSTER,

    @SerialName("smart_dynamic")
    SMART_DYNAMIC,
}

@Serializable
enum class SubAgentToolProfile {
    @SerialName("none")
    NONE,

    @SerialName("read_only")
    READ_ONLY,

    @SerialName("workspace_read")
    WORKSPACE_READ,

    @SerialName("web_read")
    WEB_READ,

    @SerialName("history_read")
    HISTORY_READ,
}

/**
 * Partial override for a built-in role. Only non-null fields override the built-in default.
 * Stored separately from [SubAgentDefinitions.builtIns] so that future built-in updates
 * (new fields, prompt revisions) don't overwrite user's per-role tweaks.
 */
@Serializable
data class SubAgentOverride(
    val systemPrompt: String? = null,
    val modelId: Uuid? = null,
    val temperature: Float? = null,
    val reasoningLevel: ReasoningLevel? = null,
    val maxTurnsOverride: Int? = null,
    val timeoutMsOverride: Long? = null,
    val outputBudgetOverride: Int? = null,
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
    /** null = inherit current chat model. Resolved via [Settings.findModelById] at run time. */
    val modelId: Uuid? = null,
    /** null = inherit assistant temperature. */
    val temperature: Float? = null,
    /** null = inherit assistant reasoning level. */
    val reasoningLevel: ReasoningLevel? = null,
    /** Free-form "delegate when / don't delegate when" hint for the orchestrator (subagent_list output). */
    val routingHint: String = "",
    /** UI hint: hide the model selector for this role (scenario-bound roles). Runtime override still works. */
    val supportsModelOverride: Boolean = true,
    /**
     * Short phase labels (each ≤4 Chinese chars) the UI cycles through while a run is in flight,
     * to give the user a sense of progression. Purely cosmetic — not derived from the model's
     * actual state. Empty = UI falls back to a generic "运行中..." label.
     */
    val phaseLabels: List<String> = emptyList(),
)

/** Apply a partial user override on top of a built-in definition. */
fun SubAgentDefinition.applyOverride(o: SubAgentOverride?): SubAgentDefinition {
    if (o == null) return this
    return copy(
        systemPrompt = o.systemPrompt?.takeIf { it.isNotBlank() } ?: systemPrompt,
        modelId = o.modelId ?: modelId,
        temperature = o.temperature ?: temperature,
        reasoningLevel = o.reasoningLevel ?: reasoningLevel,
        maxTurns = o.maxTurnsOverride ?: maxTurns,
        timeoutMs = o.timeoutMsOverride ?: timeoutMs,
        outputBudgetChars = o.outputBudgetOverride ?: outputBudgetChars,
    )
}

@Serializable
data class SubAgentTaskSpec(
    val objective: String,
    @SerialName("output_format")
    val outputFormat: String,
    @SerialName("tools_and_sources")
    val toolsAndSources: String,
    val boundaries: String,
    val context: String = "",
    @SerialName("session_grant_id")
    val sessionGrantId: String = "",
    @SerialName("source_session_ids")
    val sourceSessionIds: List<String> = emptyList(),
    @SerialName("history_query")
    val historyQuery: String = "",
    @SerialName("shard_index")
    val shardIndex: Int = 0,
    @SerialName("shard_count")
    val shardCount: Int = 1,
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
    val confidence: String = "",
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
    @SerialName("display_text")
    val displayText: String = "",
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
