package app.amber.core.jev

import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.feature.modelcouncil.CouncilPoolRanker
import app.amber.feature.modelcouncil.ModelCouncilRuntimeSetting
import app.amber.feature.modelcouncil.ModelCouncilValidator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * 议会席位模型调度（MODEL_ROUTING 用途）：按任务适配度对合法池重排。
 * 仅在默认席次路径生效（Manager 侧已门控）；能力未知字段不臆造，价格未知
 * 不参与判断。失败/超时/shadow/低置信一律返回 null 保持原轮转。
 */
class JevCouncilPoolRanker(private val runtime: JevRuntime) : CouncilPoolRanker {

    override suspend fun rank(
        input: JsonObject,
        settings: Settings,
        councilSetting: ModelCouncilRuntimeSetting,
    ): List<Uuid>? {
        if (runtime.configFor(JevPurpose.MODEL_ROUTING) == null) return null
        val pool = ModelCouncilValidator.defaultPoolModelIds(settings, councilSetting)
        if (pool.size < 2) return null
        val task = input["task"]?.jsonObject ?: input
        val objective = task.stringField("objective").orEmpty()
        if (objective.isBlank()) return null
        val context = task.stringField("context").orEmpty()

        val candidates = pool.mapNotNull { modelId ->
            val model = settings.findModelById(modelId) ?: return@mapNotNull null
            modelId to model
        }
        if (candidates.size < 2) return null
        val threshold = runtime.policy.modelRoutingMinSuitability
        val scores = LinkedHashMap<String, Double>(candidates.size)
        var mode: JevMode? = null
        var model: String? = null
        var latencyMs = 0L
        val ranked = ArrayList<Pair<Uuid, Double>>(candidates.size)
        candidates.chunked(JevLimits.MAX_QUESTIONS_PER_REQUEST).forEachIndexed { chunkIndex, chunk ->
            val state: JsonElement = buildJsonObject {
                put("task", "Pick models as council seats to analyze the user's objective from different angles.")
                put("objective", objective.take(1_000))
                if (context.isNotBlank()) put("context", context.take(1_000))
                put("models", buildJsonArray {
                    chunk.forEach { (modelId, model) ->
                        add(buildJsonObject {
                            put("id", modelId.toString())
                            put("name", model.displayName)
                            model.contextWindowTokens?.let { put("context_window_tokens", it) }
                            if (model.abilities.isNotEmpty()) {
                                put("abilities", model.abilities.joinToString(",") { it.name.lowercase() })
                            }
                        })
                    }
                })
            }
            val questions = chunk.associate { (modelId, _) ->
                modelId.toString() to JevQuestion.Noul(
                    instructions = "Is the model with id $modelId in state.models a suitable council seat for state.objective given its capabilities?",
                )
            }
            val anchor = buildString {
                append("route|")
                append(objective.take(300))
                append('|')
                append(context.take(200))
                append('|')
                append(chunk.joinToString(",") { it.first.toString() })
                append('|')
                append(chunkIndex)
            }
            val outcome = runtime.decide(
                purpose = JevPurpose.MODEL_ROUTING,
                runKey = null,
                state = state,
                questions = questions,
                requiredScopes = setOf(JevDataScope.TASK_TEXT, JevDataScope.TOOL_METADATA),
                cacheAnchor = anchor,
            ) ?: return null
            val evaluated = outcome.evaluated ?: return null
            if (outcome.stale) return null
            mode = outcome.mode
            evaluated.model?.let { model = it }
            latencyMs += evaluated.latencyMs
            chunk.forEach { (modelId, _) ->
                (evaluated.answers[modelId.toString()] as? JevAnswer.Noul)?.let {
                    scores[modelId.toString()] = it.probability
                }
            }
            // shadow：判分已收集供校准记录，不应用排序。
            if (!outcome.applicable) return@forEachIndexed
            chunk.forEach { (modelId, _) ->
                scores[modelId.toString()]?.takeIf { it >= threshold }?.let { ranked += modelId to it }
            }
        }
        runtime.calibration.append(
            JevCalibrationRecord(
                timestamp = System.currentTimeMillis(),
                purpose = JevPurpose.MODEL_ROUTING,
                mode = mode ?: return null,
                model = model,
                latencyMs = latencyMs,
                threshold = threshold,
                scores = scores,
                incumbentTop1 = candidates.first().first.toString(),
                jevTop1 = scores.entries.filter { it.value >= threshold }.maxByOrNull { it.value }?.key,
            ),
        )
        if (ranked.size < 2) return null
        return ranked.sortedByDescending { it.second }.map { it.first }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
