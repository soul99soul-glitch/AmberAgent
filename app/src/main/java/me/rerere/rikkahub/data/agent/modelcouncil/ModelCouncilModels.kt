package me.rerere.rikkahub.data.agent.modelcouncil

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val DEFAULT_MODEL_COUNCIL_MAX_SEATS = 4
const val DEFAULT_MODEL_COUNCIL_DEFAULT_ROUNDS = 2
const val DEFAULT_MODEL_COUNCIL_MAX_ROUNDS = 3
const val DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS = 180_000L
const val DEFAULT_MODEL_COUNCIL_TOTAL_TIMEOUT_MS = 8 * 60_000L
const val DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS = 12_000
const val DEFAULT_MODEL_COUNCIL_WAIT_TIMEOUT_MS = 180_000L

@Serializable
data class ModelCouncilRuntimeSetting(
    val enabled: Boolean = false,
    val defaultSeats: List<ModelCouncilSeat> = emptyList(),
    val synthesisModelId: Uuid? = null,
    val maxSeats: Int = DEFAULT_MODEL_COUNCIL_MAX_SEATS,
    val defaultRounds: Int = DEFAULT_MODEL_COUNCIL_DEFAULT_ROUNDS,
    val maxRounds: Int = DEFAULT_MODEL_COUNCIL_MAX_ROUNDS,
    val seatTimeoutMs: Long = DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS,
    val totalTimeoutMs: Long = DEFAULT_MODEL_COUNCIL_TOTAL_TIMEOUT_MS,
    val outputBudgetChars: Int = DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS,
    val showSeatOutputs: Boolean = false,
)

@Serializable
data class ModelCouncilSeat(
    @SerialName("seat_id")
    val seatId: String,
    val name: String,
    val role: String,
    @SerialName("model_id")
    val modelId: Uuid,
    @SerialName("system_prompt")
    val systemPrompt: String = "",
    @SerialName("output_budget_chars")
    val outputBudgetChars: Int = DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS,
)

@Serializable
enum class ModelCouncilMode {
    @SerialName("compare")
    COMPARE,

    @SerialName("debate")
    DEBATE,
}

@Serializable
data class ModelCouncilTaskSpec(
    val mode: ModelCouncilMode,
    val objective: String,
    val context: String = "",
    @SerialName("output_format")
    val outputFormat: String,
    @SerialName("evaluation_criteria")
    val evaluationCriteria: String = "",
    val rounds: Int = DEFAULT_MODEL_COUNCIL_DEFAULT_ROUNDS,
    val seats: List<ModelCouncilSeat>,
)

@Serializable
enum class ModelCouncilRunStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("partial_failed")
    PARTIAL_FAILED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("timed_out")
    TIMED_OUT,

    @SerialName("interrupted")
    INTERRUPTED,
}

val ModelCouncilRunStatus.running: Boolean
    get() = this == ModelCouncilRunStatus.RUNNING

@Serializable
data class ModelCouncilTurn(
    val round: Int,
    @SerialName("seat_id")
    val seatId: String,
    @SerialName("seat_name")
    val seatName: String,
    val role: String,
    @SerialName("model_id")
    val modelId: Uuid,
    val status: ModelCouncilRunStatus,
    val content: String = "",
    val error: String = "",
)

@Serializable
data class ModelCouncilResult(
    val consensus: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    @SerialName("strongest_evidence")
    val strongestEvidence: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    @SerialName("final_recommendation")
    val finalRecommendation: String = "",
    @SerialName("per_seat_summaries")
    val perSeatSummaries: List<String> = emptyList(),
    val error: String = "",
)

@Serializable
data class ModelCouncilRun(
    @SerialName("run_id")
    val runId: String,
    val status: ModelCouncilRunStatus,
    val mode: ModelCouncilMode,
    val seats: List<ModelCouncilSeat>,
    val task: ModelCouncilTaskSpec,
    val turns: List<ModelCouncilTurn> = emptyList(),
    val result: ModelCouncilResult? = null,
    @SerialName("transcript_path")
    val transcriptPath: String,
    @SerialName("started_at_ms")
    val startedAtMs: Long,
    @SerialName("updated_at_ms")
    val updatedAtMs: Long = startedAtMs,
)

data class ModelCouncilRolePreset(
    val id: String,
    val name: String,
    val prompt: String,
)

object ModelCouncilRolePresets {
    val presets = listOf(
        ModelCouncilRolePreset(
            id = "supporter",
            name = "支持者",
            prompt = "你从支持者立场评审方案，重点证明可行性、价值和最佳落地路径，同时承认必要前提。",
        ),
        ModelCouncilRolePreset(
            id = "opponent",
            name = "反对者",
            prompt = "你从反对者立场评审方案，重点寻找风险、反例、代价、失败模式和隐藏假设。",
        ),
        ModelCouncilRolePreset(
            id = "pmm",
            name = "产品市场",
            prompt = "你从产品市场视角评审，重点关注用户价值、叙事表达、卖点、竞品差异和传播风险。",
        ),
        ModelCouncilRolePreset(
            id = "engineering",
            name = "工程实现",
            prompt = "你从工程实现视角评审，重点关注架构复杂度、实现成本、测试、维护和可回滚性。",
        ),
        ModelCouncilRolePreset(
            id = "risk",
            name = "风险审查",
            prompt = "你从风险审查视角评审，重点关注隐私、安全、权限、数据损坏、误操作和合规风险。",
        ),
        ModelCouncilRolePreset(
            id = "judge",
            name = "裁判",
            prompt = "你作为裁判综合各方证据，明确哪些结论可信、哪些仍需验证，并给出最终建议。",
        ),
    )

    fun byName(name: String): ModelCouncilRolePreset? =
        presets.firstOrNull { it.name == name || it.id == name }
}
