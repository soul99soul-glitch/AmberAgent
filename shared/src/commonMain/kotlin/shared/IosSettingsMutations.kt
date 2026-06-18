package shared

import app.amber.core.settings.Settings
import app.amber.feature.modelcouncil.ModelCouncilSeat
import app.amber.feature.modelcouncil.ModelCouncilSeatRunner

/**
 * Swift-facing typed mutations over the real KMP [Settings] data class.
 *
 * Rationale: KMP data-class `copy()` is not exposed to ObjC/Swift by the
 * framework generator, and the relevant fields (providers / ttsProviders /
 * searchServices / agentRuntime.modelCouncil.defaultSeats) involve sealed
 * classes that are awkward to reconstruct from Swift. Keeping the `.copy()`
 * logic here (native Kotlin) lets the iOS side call one typed function per
 * mutation and get back a new snapshot, which it then persists via
 * [IosSettingsJsonBridge].
 *
 * These are pure functions: they take a snapshot and return a new snapshot;
 * they never touch UserDefaults. The iOS store owns durability.
 *
 * NOTE: SubAgent overrides (`Settings.subAgent.overrides`) are NOT wired
 * here. Empirically, accessing `settings.subAgent` from the `:shared`
 * module fails to compile ("Unresolved reference 'subAgent'") even though
 * `settings.agentRuntime` (same Settings.kt, adjacent field) resolves. The
 * `:core:types` Settings field `subAgent: SubAgentRuntimeSetting` pulls its
 * type from `:feature:subagent:api`, and the `:shared` dependency graph
 * (`:feature:subagent` main module -> `:core:types` -> `:feature:subagent:api`)
 * produces metadata where that field is not visible to `:shared`. This is a
 * module-visibility problem worth a dedicated KMP cleanup, not something to
 * force-clone in Swift. Until then, the SubAgentRoleView edit markers stay
 * 待接 (honestly preserved).
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object IosSettingsMutations {

    // ---- Council seats (agentRuntime.modelCouncil.defaultSeats) ----

    /**
     * Append a council seat to `agentRuntime.modelCouncil.defaultSeats`.
     * [modelId] must already be a valid Uuid string (parsed here). Returns a
     * new [Settings]; caller persists via restoreSnapshot.
     */
    fun addCouncilSeat(
        settings: Settings,
        seatId: String,
        name: String,
        role: String,
        modelId: String,
        runnerType: String,
        systemPrompt: String = "",
        outputBudgetChars: Int = DEFAULT_OUTPUT_BUDGET_CHARS,
    ): Settings {
        val parsedModelId = kotlin.uuid.Uuid.parse(modelId)
        val seat = ModelCouncilSeat(
            seatId = seatId,
            name = name,
            role = role,
            modelId = parsedModelId,
            runnerType = parseRunnerType(runnerType),
            systemPrompt = systemPrompt,
            outputBudgetChars = outputBudgetChars,
        )
        val council = settings.agentRuntime.modelCouncil
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                modelCouncil = council.copy(defaultSeats = council.defaultSeats + seat)
            )
        )
    }

    /** Remove a council seat by [seatId]; no-op if not found. */
    fun removeCouncilSeat(settings: Settings, seatId: String): Settings {
        val council = settings.agentRuntime.modelCouncil
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                modelCouncil = council.copy(
                    defaultSeats = council.defaultSeats.filterNot { it.seatId == seatId }
                )
            )
        )
    }

    // ---- helpers ----

    private fun parseRunnerType(raw: String): ModelCouncilSeatRunner {
        // Accept the legacy iOS shorthand ("provider"/"external") and the
        // serial name ("provider_model"/"external_cli"); default to PROVIDER_MODEL.
        return when (raw.lowercase()) {
            "external", "external_cli" -> ModelCouncilSeatRunner.EXTERNAL_CLI
            else -> ModelCouncilSeatRunner.PROVIDER_MODEL
        }
    }

    private const val DEFAULT_OUTPUT_BUDGET_CHARS = 4096
}
