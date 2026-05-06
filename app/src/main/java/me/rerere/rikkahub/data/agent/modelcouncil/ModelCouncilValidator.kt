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
        val inputSeats = task["seats"]?.jsonArray?.mapIndexed { index, element ->
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
        }
        return inputSeats?.takeIf { it.isNotEmpty() } ?: setting.defaultSeats
    }

    private fun JsonObject.string(name: String): String =
        stringOrBlank(name).also { require(it.isNotBlank()) { "$name is required" } }

    private fun JsonObject.stringOrBlank(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
