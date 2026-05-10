package me.rerere.rikkahub.data.agent.modelcouncil

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import kotlin.uuid.Uuid

object ModelCouncilValidator {
    fun parseTask(
        input: JsonObject,
        settings: Settings,
        councilSetting: ModelCouncilRuntimeSetting,
    ): ModelCouncilTaskSpec {
        require(councilSetting.enabled) { "Model Council experimental mode is disabled." }
        val task = input["task"]?.jsonObject ?: input
        val mode = when (task.stringOrBlank("mode").lowercase()) {
            "", "compare" -> ModelCouncilMode.COMPARE
            "debate" -> ModelCouncilMode.DEBATE
            else -> error("mode must be compare or debate")
        }
        val seats = parseSeats(task, councilSetting)
        validateSeats(settings, councilSetting, seats)
        val rounds = (task["rounds"]?.jsonPrimitive?.intOrNull ?: councilSetting.defaultRounds)
            .coerceIn(1, councilSetting.maxRounds.coerceAtLeast(1))
        return ModelCouncilTaskSpec(
            mode = mode,
            objective = task.string("objective"),
            context = task.stringOrBlank("context").take(40_000),
            outputFormat = task.stringOrBlank("output_format").ifBlank {
                "Return consensus, conflicts, strongest evidence, risks, and final recommendation."
            },
            evaluationCriteria = task.stringOrBlank("evaluation_criteria").take(8_000),
            rounds = if (mode == ModelCouncilMode.COMPARE) 1 else rounds,
            seats = seats,
        )
    }

    fun validateSeats(
        settings: Settings,
        councilSetting: ModelCouncilRuntimeSetting,
        seats: List<ModelCouncilSeat>,
    ) {
        require(seats.size in 2..councilSetting.maxSeats.coerceAtLeast(2)) {
            "Model Council requires 2..${councilSetting.maxSeats} seats."
        }
        seats.forEach { seat ->
            require(seat.seatId.isNotBlank()) { "seat_id is required" }
            require(seat.name.isNotBlank()) { "seat name is required" }
            require(seat.role.isNotBlank()) { "seat role is required" }
            val model = settings.findModelById(seat.modelId)
                ?: error("Model not found for seat ${seat.name}: ${seat.modelId}")
            require(model.type == ModelType.CHAT) {
                "Model Council only supports CHAT models: ${model.displayName.ifBlank { model.modelId }}"
            }
        }
    }

    fun resolveSynthesisModelId(
        settings: Settings,
        councilSetting: ModelCouncilRuntimeSetting,
    ): Uuid {
        val modelId = councilSetting.synthesisModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId) ?: error("Synthesis model is not configured.")
        require(model.type == ModelType.CHAT) { "Synthesis model must be a CHAT model." }
        return model.id
    }

    private fun parseSeats(
        task: JsonObject,
        setting: ModelCouncilRuntimeSetting,
    ): List<ModelCouncilSeat> {
        // Path 1: caller passed an explicit `seats` array — full manual control,
        // no auto-injection. Power-user / debug path.
        val explicit = task["seats"]?.jsonArray?.mapIndexed { index, element ->
            val seat = element.jsonObject
            val role = seat.string("role")
            val preset = ModelCouncilRolePresets.byName(role)
            ModelCouncilSeat(
                seatId = seat.stringOrBlank("seat_id").ifBlank { "seat-${index + 1}" },
                name = seat.stringOrBlank("name").ifBlank { preset?.name ?: role },
                role = role,
                modelId = Uuid.parse(seat.string("model_id")),
                systemPrompt = seat.stringOrBlank("system_prompt").ifBlank { preset?.prompt.orEmpty() },
                outputBudgetChars = (seat["output_budget_chars"]?.jsonPrimitive?.intOrNull
                    ?: setting.outputBudgetChars).coerceIn(1_000, setting.outputBudgetChars.coerceAtLeast(1_000)),
            )
        }?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit

        // Path 2 (default): start from user's defaultSeats, force-inject the 3 core seats
        // (supporter/opponent/judge) if missing, then add extra_lens picked by the orchestrator.
        // Composition: core (always 3) + user defaults (lens picks) + extra_lens — deduplicated by id.
        require(setting.defaultSeats.isNotEmpty()) {
            "Model Council needs at least one default seat configured (Settings → Model Council) so the auto-injected core seats (supporter / opponent / judge) have a model to run on. Either add 1+ default seats or pass an explicit `seats` array in the tool call."
        }
        val baseSeatModelId = setting.defaultSeats.firstOrNull()?.modelId
        val coreInjected = ModelCouncilRolePresets.coreSeats.map { preset ->
            // Match by id (canonical) — falls back to creating a new seat using the first
            // available default-seat's model when the user hasn't pre-configured this core seat.
            setting.defaultSeats.firstOrNull { ModelCouncilRolePresets.byName(it.role)?.id == preset.id }
                ?: baseSeatModelId?.let { modelId ->
                    ModelCouncilSeat(
                        seatId = "core-${preset.id}",
                        name = preset.name,
                        role = preset.id,
                        modelId = modelId,
                        systemPrompt = preset.prompt,
                        outputBudgetChars = setting.outputBudgetChars,
                    )
                }
        }.filterNotNull()

        // User's existing non-core defaults (lens choices baked into settings)
        val userLensSeats = setting.defaultSeats.filter { seat ->
            val canonicalId = ModelCouncilRolePresets.byName(seat.role)?.id ?: seat.role
            !ModelCouncilRolePresets.isCore(canonicalId)
        }

        // Per-task extra_lens from the orchestrator
        val extraLensIds = task["extra_lens"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()
        val extraLensSeats = extraLensIds.mapNotNull { lensId ->
            val preset = ModelCouncilRolePresets.byName(lensId) ?: return@mapNotNull null
            if (ModelCouncilRolePresets.isCore(preset.id)) return@mapNotNull null  // core handled above
            val modelId = baseSeatModelId ?: return@mapNotNull null  // need a model to seat them
            ModelCouncilSeat(
                seatId = "lens-${preset.id}",
                name = preset.name,
                role = preset.id,
                modelId = modelId,
                systemPrompt = preset.prompt,
                outputBudgetChars = setting.outputBudgetChars,
            )
        }

        // Dedupe by canonical role id (preserves first-seen order)
        val seen = HashSet<String>()
        val merged = (coreInjected + userLensSeats + extraLensSeats).filter { seat ->
            val canonicalId = ModelCouncilRolePresets.byName(seat.role)?.id ?: seat.role
            seen.add(canonicalId)
        }

        // Hard cap by maxSeats; truncate excess lenses (core is at the front so it survives).
        return merged.take(setting.maxSeats.coerceAtLeast(2))
    }

    private fun JsonObject.string(name: String): String =
        stringOrBlank(name).also { require(it.isNotBlank()) { "$name is required" } }

    private fun JsonObject.stringOrBlank(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
