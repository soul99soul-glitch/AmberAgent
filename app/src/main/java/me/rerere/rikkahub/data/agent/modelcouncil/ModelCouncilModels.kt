package me.rerere.rikkahub.data.agent.modelcouncil

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

// Bumped from 4 → 8 to fit the new "3 core seats + up to 5 lens" model.
// Core (supporter / opponent / judge) are structural and always present;
// lenses (product / marketing / pr / engineering / ux / risk) are picked per topic.
const val DEFAULT_MODEL_COUNCIL_MAX_SEATS = 8
const val DEFAULT_MODEL_COUNCIL_DEFAULT_ROUNDS = 2
const val DEFAULT_MODEL_COUNCIL_MAX_ROUNDS = 5
const val DEFAULT_MODEL_COUNCIL_SEAT_TIMEOUT_MS = 180_000L
const val DEFAULT_MODEL_COUNCIL_TOTAL_TIMEOUT_MS = 8 * 60_000L
const val DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS = 12_000
const val DEFAULT_MODEL_COUNCIL_WAIT_TIMEOUT_MS = 180_000L
const val EXTENDED_MODEL_COUNCIL_SEAT_TIMEOUT_MS = 20 * 60_000L
const val EXTENDED_MODEL_COUNCIL_TOTAL_TIMEOUT_MS = 45 * 60_000L
const val EXTENDED_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS = 200_000
const val MODEL_COUNCIL_EXTERNAL_MODEL_PLACEHOLDER = "00000000-0000-0000-0000-000000000000"

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
    @SerialName("runner_type")
    val runnerType: ModelCouncilSeatRunner = ModelCouncilSeatRunner.PROVIDER_MODEL,
    @SerialName("system_prompt")
    val systemPrompt: String = "",
    @SerialName("output_budget_chars")
    val outputBudgetChars: Int = DEFAULT_MODEL_COUNCIL_OUTPUT_BUDGET_CHARS,
    @SerialName("reasoning_level")
    val reasoningLevel: ReasoningLevel? = null,
    val temperature: Float? = null,
    @SerialName("external_tool")
    val externalTool: String = "",
    @SerialName("external_runtime")
    val externalRuntime: String = "",
    @SerialName("external_model")
    val externalModel: String = "",
)

@Serializable
enum class ModelCouncilSeatRunner {
    @SerialName("provider_model")
    PROVIDER_MODEL,

    @SerialName("external_cli")
    EXTERNAL_CLI,
}

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
    @SerialName("model_name")
    val modelName: String = "",
    @SerialName("provider_name")
    val providerName: String = "",
    val status: ModelCouncilRunStatus,
    val content: String = "",
    val error: String = "",
    val warnings: List<String> = emptyList(),
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
    val warnings: List<String> = emptyList(),
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

/**
 * Council role presets, organized in two layers:
 *
 *  - **coreSeats** (always on): the structural backbone of any council debate —
 *    a supporter, an opponent, and a judge. Removing any of these breaks the
 *    "adversarial deliberation + verdict" pattern, so the validator force-injects
 *    them even when the user / orchestrator doesn't list them explicitly.
 *
 *  - **lensPresets** (pick per topic): domain perspectives that vary by question.
 *    A commercial decision may want product/marketing/pr; a technical one wants
 *    engineering/risk; a writing judgment wants none of these. The orchestrator
 *    can pass `extra_lens: ["product", "engineering"]` when starting a council
 *    run, and the user can also pre-pick defaults in settings.
 *
 * `presets` (the legacy union) is preserved so old persisted seats and the
 * Settings UI's preset dropdown keep working without migration.
 */
object ModelCouncilRolePresets {
    val coreSeats = listOf(
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
            id = "judge",
            name = "裁判",
            prompt = "你作为裁判综合各方证据，明确哪些结论可信、哪些仍需验证，并给出最终建议。",
        ),
    )

    val lensPresets = listOf(
        ModelCouncilRolePreset(
            id = "product",
            name = "产品",
            prompt = "你从产品视角评审，关注用户价值、需求定位、功能取舍、产品决策的取舍逻辑。",
        ),
        ModelCouncilRolePreset(
            id = "marketing",
            name = "营销",
            prompt = "你从营销视角评审，关注渠道选择、内容策略、增长抓手、获客成本、传播效率。",
        ),
        ModelCouncilRolePreset(
            id = "pr",
            name = "公关",
            prompt = "你从公关视角评审，关注舆论走向、品牌叙事、危机应对、媒体关系、长期形象。",
        ),
        ModelCouncilRolePreset(
            id = "engineering",
            name = "工程",
            prompt = "你从工程视角评审，关注架构复杂度、实现成本、测试覆盖、维护负担、可回滚性。",
        ),
        ModelCouncilRolePreset(
            id = "ux",
            name = "用户体验",
            prompt = "你从用户体验视角评审，关注流程顺畅、交互细节、情感感受、易用性、视觉一致性。",
        ),
        ModelCouncilRolePreset(
            id = "risk",
            name = "风险",
            prompt = "你从风险视角评审，关注隐私、安全、权限边界、数据损坏、误操作、合规底线。",
        ),
    )

    /** Legacy union — keeps the old "产品市场" preset findable (folded into product) for back-compat. */
    val presets: List<ModelCouncilRolePreset> = coreSeats + lensPresets

    /**
     * Lookup helper. Tolerates a few legacy aliases that older persisted seats may still carry:
     *  - "pmm" / "产品市场" → product
     *  - "工程实现" / "engineering"-suffix variants → engineering
     *  - "风险审查" → risk
     */
    fun byName(name: String): ModelCouncilRolePreset? {
        val normalized = when (name) {
            "pmm", "产品市场" -> "product"
            "工程实现" -> "engineering"
            "风险审查" -> "risk"
            else -> name
        }
        return presets.firstOrNull { it.name == normalized || it.id == normalized }
    }

    /** True iff the given preset id is one of the structural core seats. */
    fun isCore(id: String): Boolean = coreSeats.any { it.id == id }
}
