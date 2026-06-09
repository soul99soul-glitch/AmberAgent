package app.amber.feature.modelcouncil

import kotlinx.coroutines.CancellationException
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import kotlin.uuid.Uuid

/**
 * In-process [ModelCouncilTextRunner] — streams seat output via the configured
 * [ProviderManager] for a provider+model combination from settings.
 *
 * Extracted from ModelCouncilManager.kt in Phase 1 god-class slimming
 * (companion split alongside [ExternalCliModelCouncilRunner]).
 */
class ProviderModelCouncilTextRunner(
    private val providerManager: ProviderManager,
) : ModelCouncilTextRunner {
    override suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
        reasoningLevel: ReasoningLevel?,
        temperature: Float?,
        onChunk: (String) -> Unit,
    ): ModelCouncilTextResult {
        val model = settings.findModelById(modelId) ?: error("Model not found: $modelId")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found for model: ${model.displayName}")
        val providerImpl = providerManager.getProviderByType(provider)
        val messages = buildList {
            add(UIMessage.system(systemPrompt))
            add(UIMessage.user(userPrompt))
        }
        suspend fun streamWith(candidateTemperature: Float?, reasoningLevel: ReasoningLevel): String {
            val params = TextGenerationParams(
                model = model,
                tools = emptyList(),
                reasoningLevel = reasoningLevel,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
                temperature = candidateTemperature,
            )
            // Streaming path: per-chunk MessageChunk.choices.first.delta is the delta; we
            // accumulate and emit the running text. Provider chunks can arrive faster than
            // Compose can render; coalesce live updates to a frame-scale cadence while still
            // forcing the final text through.
            val accumulated = StringBuilder()
            var lastEmitNanos = 0L
            var lastEmittedText = ""
            fun emitLive(force: Boolean) {
                val now = System.nanoTime()
                if (!force && now - lastEmitNanos < MODEL_COUNCIL_LIVE_EMIT_INTERVAL_NANOS) return
                val text = accumulated.toString().take(outputBudgetChars)
                if (text != lastEmittedText) {
                    lastEmittedText = text
                    lastEmitNanos = now
                    onChunk(text)
                }
            }
            providerImpl.streamText(
                providerSetting = provider,
                messages = messages,
                params = params,
            ).collect { chunk ->
                val delta = chunk.choices.firstOrNull()?.delta?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
                if (delta.isNotEmpty()) {
                    accumulated.append(delta)
                    emitLive(force = false)
                }
            }
            emitLive(force = true)
            return accumulated.toString().take(outputBudgetChars)
        }
        val requestedReasoning = reasoningLevel ?: ReasoningLevel.OFF
        val label = listOf(provider.name, model.displayName.ifBlank { model.modelId })
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val warnings = mutableListOf<String>()
        suspend fun tryStream(candidateTemperature: Float?, candidateReasoning: ReasoningLevel): Result<String> =
            runCatching { streamWith(candidateTemperature, candidateReasoning) }.also { result ->
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            }

        val first = tryStream(temperature, requestedReasoning)
        if (first.isSuccess) {
            return ModelCouncilTextResult(first.getOrThrow())
        }
        val firstError = first.exceptionOrNull()!!
        val candidates = buildList {
            if (firstError.isUnsupportedReasoningConfigError() && requestedReasoning != ReasoningLevel.AUTO) {
                add(temperature to ReasoningLevel.AUTO)
            }
            if (temperature != null && firstError.isUnsupportedTemperatureError()) {
                add(null to requestedReasoning)
            }
            if (
                temperature != null &&
                requestedReasoning != ReasoningLevel.AUTO &&
                (
                    firstError.isUnsupportedReasoningConfigError() ||
                        firstError.isUnsupportedTemperatureError()
                    )
            ) {
                add(null to ReasoningLevel.AUTO)
            }
        }.distinct()

        var lastError = firstError
        candidates.forEach { (candidateTemperature, candidateReasoning) ->
            val result = tryStream(candidateTemperature, candidateReasoning)
            if (result.isSuccess) {
                if (candidateReasoning != requestedReasoning) {
                    warnings += "$label rejected reasoningLevel=${requestedReasoning.name.lowercase()}; retried with provider default reasoning."
                }
                if (candidateTemperature != temperature) {
                    warnings += "$label rejected temperature; retried without temperature."
                }
                return ModelCouncilTextResult(
                    text = result.getOrThrow(),
                    warnings = warnings,
                )
            }
            lastError = result.exceptionOrNull() ?: lastError
        }
        throw if (candidates.isNotEmpty()) {
            IllegalStateException(
                "$label rejected council generation parameters; fallback attempts failed: ${lastError.message ?: lastError::class.java.simpleName}",
                lastError,
            )
        } else {
            firstError
        }
    }
}

private const val MODEL_COUNCIL_LIVE_EMIT_INTERVAL_NANOS = 32_000_000L

private fun Throwable.isUnsupportedReasoningConfigError(): Boolean {
    val message = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .joinToString("\n")
        .lowercase()
    if (!listOf("thinking", "reasoning").any { message.contains(it) }) return false
    return listOf(
        "unsupported parameter",
        "unsupported param",
        "not supported",
        "does not support",
        "unsupported value",
        "unknown parameter",
        "unrecognized parameter",
        "不支持",
    ).any { marker -> message.contains(marker) }
}

private fun Throwable.isUnsupportedTemperatureError(): Boolean {
    val message = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .joinToString("\n")
        .lowercase()
    if (!message.contains("temperature")) return false
    return listOf(
        "unsupported parameter",
        "unsupported param",
        "not supported",
        "does not support",
        "unsupported value",
        "unknown parameter",
        "unrecognized parameter",
        "不支持",
    ).any { marker -> message.contains(marker) }
}
