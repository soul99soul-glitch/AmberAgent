package app.amber.feature.modelcouncil

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.feature.terminal.TerminalRuntimeKind
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import java.util.Locale
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
        val seats = parseSeats(task, settings, councilSetting)
        validateSeats(settings, councilSetting, seats)
        val maxRounds = councilSetting.maxRounds.coerceAtLeast(DEFAULT_MODEL_COUNCIL_MAX_ROUNDS)
        val rounds = (task["rounds"]?.jsonPrimitive?.intOrNull ?: councilSetting.defaultRounds)
            .coerceIn(1, maxRounds)
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
        val synthesisModelId = councilSetting.synthesisModelId ?: settings.chatModelId
        val synthesisModel = settings.findModelById(synthesisModelId)
        require(synthesisModel?.type == ModelType.CHAT) {
            "Model Council needs a CHAT synthesis model before it can produce the final verdict."
        }
        require(seats.size in 2..councilSetting.maxSeats.coerceAtLeast(2)) {
            "Model Council requires 2..${councilSetting.maxSeats} seats."
        }
        seats.forEach { seat ->
            require(seat.seatId.isNotBlank()) { "seat_id is required" }
            require(seat.name.isNotBlank()) { "seat name is required" }
            require(seat.role.isNotBlank()) { "seat role is required" }
            seat.temperature?.let { temperature ->
                require(temperature in 0f..2f) { "temperature for seat ${seat.name} must be between 0 and 2." }
            }
            when (seat.runnerType) {
                ModelCouncilSeatRunner.PROVIDER_MODEL -> {
                    val model = settings.findModelById(seat.modelId)
                        ?: error("Model not found for seat ${seat.name}: ${seat.modelId}")
                    require(model.type == ModelType.CHAT) {
                        "Model Council only supports CHAT models: ${model.displayName.ifBlank { model.modelId }}"
                    }
                }

                ModelCouncilSeatRunner.EXTERNAL_CLI -> {
                    require(ExternalCliToolRegistry.isSupported(seat.externalTool)) {
                        "Unsupported external_tool for seat ${seat.name}: ${seat.externalTool}"
                    }
                    require(seat.externalModel.isBlank() || EXTERNAL_CLI_MODEL_ARG_REGEX.matches(seat.externalModel)) {
                        "Unsafe external_model for seat ${seat.name}: ${seat.externalModel}"
                    }
                    if (seat.externalRuntime.isNotBlank()) {
                        require(TerminalRuntimeKind.fromWire(seat.externalRuntime) != null) {
                            "Unsupported external_runtime for seat ${seat.name}: ${seat.externalRuntime}"
                        }
                    }
                }
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
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
    ): List<ModelCouncilSeat> {
        val seatStrategy = task.stringOrBlank("seat_strategy").lowercase(Locale.ROOT)
        val allowExternalCli = task.booleanFlag("allow_external_cli")
        when (seatStrategy) {
            "agent_planned" -> return parseAgentPlannedSeats(task, settings, setting)
                .requireExternalCliAllowed(allowExternalCli)
            "", "default" -> Unit
            else -> error("seat_strategy must be default or agent_planned")
        }

        // Path 1: caller passed an explicit `seats` array — full manual control,
        // no auto-injection. Power-user / debug path.
        val explicit = task["seats"]?.jsonArray?.mapIndexed { index, element ->
            val seat = element.jsonObject
            val role = seat.string("role")
            val preset = ModelCouncilRolePresets.byName(role)
            val runnerType = seat.runnerType()
            ModelCouncilSeat(
                seatId = seat.stringOrBlank("seat_id").ifBlank { "seat-${index + 1}" },
                name = seat.stringOrBlank("name").ifBlank { preset?.name ?: role },
                role = role,
                modelId = if (runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) {
                    seat.stringOrBlank("model_id").takeIf { it.isNotBlank() }?.let(Uuid::parse)
                        ?: settings.placeholderCouncilModelId()
                } else {
                    Uuid.parse(seat.string("model_id"))
                },
                runnerType = runnerType,
                systemPrompt = seat.stringOrBlank("system_prompt").ifBlank { preset?.prompt.orEmpty() },
                outputBudgetChars = (seat["output_budget_chars"]?.jsonPrimitive?.intOrNull
                    ?: setting.outputBudgetChars).coerceIn(1_000, setting.outputBudgetChars.coerceAtLeast(1_000)),
                reasoningLevel = seat.optionalReasoningLevel("seats[$index].reasoning_level"),
                temperature = seat.optionalTemperature("seats[$index].temperature"),
                externalTool = seat.externalToolFor(runnerType),
                externalRuntime = seat.stringOrBlank("external_runtime"),
                externalModel = seat.stringOrBlank("external_model"),
            )
        }?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit.withUniqueSeatIds().requireExternalCliAllowed(allowExternalCli)

        // Path 2 (default): start from user's defaultSeats, force-inject the 3 core seats
        // (supporter/opponent/judge) if missing, then add extra_lens picked by the orchestrator.
        // Composition: core (always 3) + user defaults (lens picks) + extra_lens — deduplicated by id.
        val modelPool = settings.defaultCouncilModelPool(setting)
        require(modelPool.isNotEmpty()) {
            "Model Council default mode needs at least one enabled CHAT model for auto-injected seats."
        }
        val usedModelIds = setting.defaultSeats.mapNotNull { seat ->
            if (seat.runnerType != ModelCouncilSeatRunner.PROVIDER_MODEL) return@mapNotNull null
            settings.findModelById(seat.modelId)
                ?.takeIf { it.type == ModelType.CHAT }
                ?.id
        }.toCollection(LinkedHashSet())
        var autoModelIndex = 0
        fun nextAutoModelId(): Uuid {
            modelPool.firstOrNull { it !in usedModelIds }?.let { modelId ->
                usedModelIds += modelId
                return modelId
            }
            val modelId = modelPool[(autoModelIndex++ % modelPool.size)]
            usedModelIds += modelId
            return modelId
        }
        val coreInjected = ModelCouncilRolePresets.coreSeats.map { preset ->
            // Match by id (canonical) — falls back to creating a new seat using the first
            // available model-pool candidate when the user hasn't pre-configured this core seat.
            setting.defaultSeats.firstOrNull { seat ->
                ModelCouncilRolePresets.byName(seat.role)?.id == preset.id &&
                    seat.isDefaultSeatUsable(settings, allowExternalCli)
            }
                ?: ModelCouncilSeat(
                    seatId = "core-${preset.id}",
                    name = preset.name,
                    role = preset.id,
                    modelId = nextAutoModelId(),
                    systemPrompt = preset.prompt,
                    outputBudgetChars = setting.outputBudgetChars,
                )
        }

        // User's existing non-core defaults (lens choices baked into settings)
        val userLensSeats = setting.defaultSeats.filter { seat ->
            val canonicalId = ModelCouncilRolePresets.byName(seat.role)?.id ?: seat.role
            !ModelCouncilRolePresets.isCore(canonicalId) &&
                seat.isDefaultSeatUsable(settings, allowExternalCli)
        }

        // Per-task extra_lens from the orchestrator
        val extraLensIds = task["extra_lens"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()
        val extraLensSeats = extraLensIds.mapNotNull { lensId ->
            val preset = ModelCouncilRolePresets.byName(lensId) ?: return@mapNotNull null
            if (ModelCouncilRolePresets.isCore(preset.id)) return@mapNotNull null  // core handled above
            ModelCouncilSeat(
                seatId = "lens-${preset.id}",
                name = preset.name,
                role = preset.id,
                modelId = nextAutoModelId(),
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
            .withUniqueSeatIds()
            .requireExternalCliAllowed(allowExternalCli)
    }

    private fun parseAgentPlannedSeats(
        task: JsonObject,
        settings: Settings,
        setting: ModelCouncilRuntimeSetting,
    ): List<ModelCouncilSeat> {
        val planned = task["planned_seats"]?.jsonArray
            ?: error("seat_strategy=agent_planned requires planned_seats.")
        val plannedObjects = planned.map { it.jsonObject }
        val needsProviderModels = plannedObjects.any { it.runnerType() == ModelCouncilSeatRunner.PROVIDER_MODEL }
        val modelPool = if (needsProviderModels) {
            settings.defaultCouncilModelPool(setting).also {
                require(it.isNotEmpty()) {
                    "Model Council needs at least one CHAT model for agent_planned provider-model seats."
                }
            }
        } else {
            emptyList()
        }

        val maxSeats = setting.maxSeats.coerceAtLeast(2)
        val usedModelIds = LinkedHashSet<Uuid>()
        var autoModelIndex = 0
        fun nextAutoModelId(): Uuid {
            modelPool.firstOrNull { it !in usedModelIds }?.let { modelId ->
                usedModelIds += modelId
                return modelId
            }
            val modelId = modelPool[(autoModelIndex++ % modelPool.size)]
            usedModelIds += modelId
            return modelId
        }
        val seats = plannedObjects.take(maxSeats).mapIndexed { index, seat ->
            val name = seat.stringOrBlank("name").ifBlank { "席位 ${index + 1}" }
            val role = seat.stringOrBlank("role").ifBlank { name }
            val modelRef = seat.stringOrBlank("model_ref")
            val runnerType = seat.runnerType()
            val modelId = if (runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) {
                settings.placeholderCouncilModelId()
            } else {
                modelRef.takeIf { it.isNotBlank() }
                    ?.let { ref -> settings.resolveCouncilModelRef(ref) }
                    ?.also { usedModelIds += it }
                    ?: nextAutoModelId()
            }
            ModelCouncilSeat(
                seatId = seat.stringOrBlank("seat_id").ifBlank { role.toSeatId(index) },
                name = name.take(MAX_PLANNED_SEAT_NAME_CHARS),
                role = role.take(MAX_PLANNED_SEAT_ROLE_CHARS),
                modelId = modelId,
                runnerType = runnerType,
                systemPrompt = seat.stringOrBlank("system_prompt")
                    .ifBlank { "Apply this perspective faithfully and stay within the provided context." }
                    .take(MAX_PLANNED_SEAT_PROMPT_CHARS),
                outputBudgetChars = (seat["output_budget_chars"]?.jsonPrimitive?.intOrNull
                    ?: setting.outputBudgetChars).coerceIn(1_000, setting.outputBudgetChars.coerceAtLeast(1_000)),
                reasoningLevel = seat.optionalReasoningLevel("planned_seats[$index].reasoning_level"),
                temperature = seat.optionalTemperature("planned_seats[$index].temperature"),
                externalTool = seat.externalToolFor(runnerType),
                externalRuntime = seat.stringOrBlank("external_runtime"),
                externalModel = seat.stringOrBlank("external_model"),
            )
        }.filter { it.name.isNotBlank() && it.role.isNotBlank() }

        require(seats.size >= 2) {
            "seat_strategy=agent_planned requires at least 2 planned seats."
        }
        return seats.withUniqueSeatIds()
    }

    private fun JsonObject.string(name: String): String =
        stringOrBlank(name).also { require(it.isNotBlank()) { "$name is required" } }

    private fun JsonObject.stringOrBlank(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.booleanFlag(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.optionalTemperature(label: String): Float? {
        val raw = this["temperature"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val value = raw.toFloatOrNull() ?: error("$label is not a number: $raw")
        require(value in 0f..2f) { "$label must be between 0 and 2." }
        return value
    }

    private fun JsonObject.optionalReasoningLevel(label: String): ReasoningLevel? {
        val raw = stringOrBlank("reasoning_level")
            .ifBlank { stringOrBlank("reasoningLevel") }
            .takeIf { it.isNotBlank() }
            ?: return null
        return ReasoningLevel.entries.firstOrNull { level ->
            level.name.equals(raw, ignoreCase = true) ||
                level.effort.equals(raw, ignoreCase = true)
        } ?: error("$label must be one of ${ReasoningLevel.entries.joinToString { it.name.lowercase() }}; got: $raw")
    }

    private fun JsonObject.runnerType(): ModelCouncilSeatRunner {
        val runnerType = stringOrBlank("runner_type").lowercase(Locale.ROOT)
        val externalTool = stringOrBlank("external_tool")
        return when {
            runnerType in setOf("external_cli", "cli") + ExternalCliToolRegistry.supportedToolIds ->
                ModelCouncilSeatRunner.EXTERNAL_CLI
            runnerType in setOf("provider_model", "model", "llm", "") && externalTool.isBlank() ->
                ModelCouncilSeatRunner.PROVIDER_MODEL
            runnerType.isBlank() && externalTool.isNotBlank() -> ModelCouncilSeatRunner.EXTERNAL_CLI
            else -> error("runner_type must be provider_model or external_cli")
        }
    }

    private fun JsonObject.externalToolFor(runnerType: ModelCouncilSeatRunner): String {
        val explicit = stringOrBlank("external_tool")
        if (runnerType != ModelCouncilSeatRunner.EXTERNAL_CLI || explicit.isNotBlank()) return explicit
        val alias = stringOrBlank("runner_type").lowercase(Locale.ROOT)
        return alias.takeIf(ExternalCliToolRegistry::isSupported).orEmpty()
    }

    private fun Settings.defaultCouncilModelPool(setting: ModelCouncilRuntimeSetting): List<Uuid> {
        val allChatCandidates = modelCandidates()
            .filter { it.model.type == ModelType.CHAT }
        val preferred = buildList {
            setting.defaultSeats.forEach { seat ->
                allChatCandidates.firstOrNull { it.model.id == seat.modelId }?.let(::add)
            }
            allChatCandidates.firstOrNull { it.model.id == chatModelId }?.let(::add)
            addAll(allChatCandidates)
        }
        val uniqueByModel = LinkedHashMap<Uuid, ModelCandidate>()
        preferred.forEach { candidate ->
            uniqueByModel.putIfAbsent(candidate.model.id, candidate)
        }
        return uniqueByModel.values
            .toList()
            .roundRobinByProvider()
            .map { it.model.id }
    }

    private fun List<ModelCandidate>.roundRobinByProvider(): List<ModelCandidate> {
        val buckets = LinkedHashMap<Uuid, ArrayDeque<ModelCandidate>>()
        forEach { candidate ->
            buckets.getOrPut(candidate.provider.id) { ArrayDeque() }.addLast(candidate)
        }
        val ordered = mutableListOf<ModelCandidate>()
        while (buckets.isNotEmpty()) {
            val iterator = buckets.iterator()
            while (iterator.hasNext()) {
                val bucket = iterator.next().value
                ordered += bucket.removeFirst()
                if (bucket.isEmpty()) {
                    iterator.remove()
                }
            }
        }
        return ordered
    }

    private fun Settings.placeholderCouncilModelId(): Uuid =
        findModelById(chatModelId)?.id
            ?: modelCandidates().firstOrNull { it.model.type == ModelType.CHAT }?.model?.id
            ?: Uuid.parse(MODEL_COUNCIL_EXTERNAL_MODEL_PLACEHOLDER)

    private fun Settings.resolveCouncilModelRef(ref: String): Uuid? {
        val normalized = ref.normalizeModelRef()
        Uuid.parseOrNull(ref)?.let { uuid ->
            findModelById(uuid)?.takeIf { it.type == ModelType.CHAT }?.let { return it.id }
        }
        val candidates = modelCandidates().filter { it.model.type == ModelType.CHAT }
        fun ModelCandidate.matchesExact(): Boolean {
            val model = model
            val provider = provider
            return listOf(
                model.displayName,
                model.modelId,
                provider.name,
                "${provider.name} ${model.displayName}",
                "${provider.name} ${model.modelId}",
            ).any { it.normalizeModelRef() == normalized }
        }
        fun ModelCandidate.matchesContains(): Boolean {
            val model = model
            val provider = provider
            return listOf(
                model.displayName,
                model.modelId,
                provider.name,
                "${provider.name} ${model.displayName}",
                "${provider.name} ${model.modelId}",
            ).any { value ->
                val candidate = value.normalizeModelRef()
                candidate.contains(normalized) || normalized.contains(candidate)
            }
        }
        return candidates.firstOrNull { it.matchesExact() }?.model?.id
            ?: candidates.firstOrNull { it.matchesContains() }?.model?.id
    }

    private fun Settings.modelCandidates(): List<ModelCandidate> =
        providers.filter { it.enabled }.flatMap { provider ->
            provider.models.map { model -> ModelCandidate(provider = provider, model = model) }
        }

    private fun String.toSeatId(index: Int): String {
        val slug = lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5_-]+"), "-")
            .trim('-')
        return slug.ifBlank { "planned-${index + 1}" }.take(48)
    }

    private fun List<ModelCouncilSeat>.withUniqueSeatIds(): List<ModelCouncilSeat> {
        val counts = HashMap<String, Int>()
        val used = HashSet<String>()
        return mapIndexed { index, seat ->
            val base = seat.seatId.ifBlank { seat.role.toSeatId(index) }
            val seen = counts.getOrDefault(base, 0)
            var nextCount = seen + 1
            var unique = if (seen == 0) base else "${base.take(44)}-$nextCount"
            while (!used.add(unique)) {
                nextCount += 1
                unique = "${base.take(44)}-$nextCount"
            }
            counts[base] = nextCount
            seat.copy(seatId = unique)
        }
    }

    private fun List<ModelCouncilSeat>.requireExternalCliAllowed(allowExternalCli: Boolean): List<ModelCouncilSeat> {
        require(allowExternalCli || none { it.runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI }) {
            "External CLI Model Council seats require allow_external_cli=true because they start a local terminal process."
        }
        return this
    }

    private fun ModelCouncilSeat.isDefaultSeatUsable(settings: Settings, allowExternalCli: Boolean): Boolean =
        when (runnerType) {
            ModelCouncilSeatRunner.PROVIDER_MODEL ->
                settings.findModelById(modelId)?.type == ModelType.CHAT

            ModelCouncilSeatRunner.EXTERNAL_CLI ->
                allowExternalCli
        }

    private fun String.normalizeModelRef(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("[\\s_\\-:.]+"), "")
            .trim()

    private fun Uuid.Companion.parseOrNull(value: String): Uuid? =
        runCatching { parse(value) }.getOrNull()

    private data class ModelCandidate(
        val provider: ProviderSetting,
        val model: Model,
    )

    private val EXTERNAL_CLI_MODEL_ARG_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,119}")

    private const val MAX_PLANNED_SEAT_NAME_CHARS = 40
    private const val MAX_PLANNED_SEAT_ROLE_CHARS = 80
    private const val MAX_PLANNED_SEAT_PROMPT_CHARS = 2_000
}
