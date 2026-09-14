package app.amber.feature.modelcouncil

import kotlinx.coroutines.CancellationException
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import kotlin.uuid.Uuid

/**
 * In-process [ModelCouncilTextRunner] — streams seat output via the configured
 * [ProviderCatalog] for a provider+model combination from settings.
 *
 * Extracted from ModelCouncilManager.kt in Phase 1 god-class slimming
 * (companion split alongside [ExternalCliModelCouncilRunner]).
 */
class ProviderModelCouncilTextRunner(
    private val providerCatalog: ProviderCatalog,
) : ModelCouncilTextRunner {
    override suspend fun generate(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        outputBudgetChars: Int,
        reasoningLevel: ReasoningLevel?,
        temperature: Float?,
        userImageParts: List<UIMessagePart.Image>,
        onChunk: (String) -> Unit,
    ): ModelCouncilTextResult {
        val model = settings.findModelById(modelId) ?: error("Model not found: $modelId")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found for model: ${model.displayName}")
        val providerImpl = providerCatalog.text(provider)
        val messages = buildList {
            add(UIMessage.system(systemPrompt))
            // User turn carries the synthesized prompt text plus any images the
            // user attached (passed multimodally). Documents are already inlined
            // into userPrompt as text upstream, so only image parts ride here.
            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(userPrompt)) + userImageParts,
                )
            )
        }
        // Keep parameter fallback attempts in one provider session. This runner
        // has no conversation owner in its public contract, so one generated
        // ID per seat invocation is the narrowest stable scope.
        val sessionId = Uuid.random().toString()
        val requestedReasoning = reasoningLevel ?: ReasoningLevel.OFF
        val label = listOf(provider.name, model.displayName.ifBlank { model.modelId })
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val warnings = mutableListOf<String>()
        suspend fun streamWith(candidateTemperature: Float?, candidateReasoning: ReasoningLevel): String {
            val params = TextGenerationParams(
                model = model,
                tools = emptyList(),
                reasoningLevel = candidateReasoning,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
                temperature = candidateTemperature,
                sessionId = sessionId,
            )
            // Streaming path: per-chunk MessageChunk.choices.first.delta is the delta; we
            // accumulate and emit the running text. Provider chunks can arrive faster than
            // Compose can render; coalesce live updates to a frame-scale cadence while still
            // forcing the final text through.
            val accumulated = StringBuilder()
            // Diagnostic counters for the "guest sometimes doesn't stream" issue.
            // Track how many raw chunks arrive, how many were non-empty deltas,
            // how many emissions were throttled/deduped away, and the wall
            // time span of the stream — so a single log line per turn reveals
            // whether the provider sent few chunks (upstream), or we suppressed
            // them (our throttle). VERBOSE-only; guarded by a stable tag.
            var rawChunkCount = 0
            var nonEmptyDeltaCount = 0
            var truncatedByProvider = false
            val throttle = CumulativeTextThrottle(onChunk)
            val streamStartNanos = System.nanoTime()
            val runTag = "council-stream/${model.displayName.ifBlank { model.modelId }}/t=${candidateTemperature}/r=${candidateReasoning}"
            providerImpl.stream(
                providerSetting = provider,
                messages = messages,
                params = params,
            ).collect { chunk ->
                rawChunkCount++
                val finishReason = chunk.choices.lastOrNull { it.finishReason != null }?.finishReason
                if (finishReason?.trim()?.lowercase() in OUTPUT_LIMIT_FINISH_REASONS) {
                    truncatedByProvider = true
                }
                val delta = chunk.choices.firstOrNull()?.delta?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
                if (delta.isNotEmpty()) {
                    nonEmptyDeltaCount++
                    accumulated.append(delta)
                    throttle.offer { accumulated.toString().take(outputBudgetChars) }
                }
            }
            throttle.offer(force = true) { accumulated.toString().take(outputBudgetChars) }
            // A provider-side output-limit cut is a truncation, not a completion —
            // surface it as a warning instead of silently treating the text as whole
            // (mirrors the chat kernel's OUTPUT_LIMIT semantics; here the turn still
            // completes, the warning rides the bubble).
            if (truncatedByProvider) {
                warnings += "$label reply was cut off by the provider output limit."
            }
            val totalChars = accumulated.length
            val elapsedMs = (System.nanoTime() - streamStartNanos) / 1_000_000
            // One diagnostic line per turn. Patterns to watch for:
            //  - rawChunkCount <= 2 with large totalChars  → provider sent the whole
            //    reply in one shot (non-streaming fallback / local model batching);
            //    the "pop in whole" is upstream, not our throttle.
            //  - skippedByThrottle high with rawChunkCount high → our 32ms cadence
            //    is the culprit; lower the interval.
            //  - forwarded high but user still sees a pop → rendering/Compose
            //    side, not this runner.
            android.util.Log.i(
                "CouncilRunner",
                "$runTag done: chunks=$rawChunkCount nonEmpty=$nonEmptyDeltaCount " +
                    "chars=$totalChars elapsedMs=$elapsedMs " +
                    "emit(fwd=${throttle.forwarded} throttled=${throttle.skippedByThrottle} noChange=${throttle.skippedNoChange})",
            )
            return accumulated.toString().take(outputBudgetChars)
        }
        suspend fun tryStream(candidateTemperature: Float?, candidateReasoning: ReasoningLevel): Result<String> =
            runCatching { streamWith(candidateTemperature, candidateReasoning) }.also { result ->
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            }

        val first = tryStream(temperature, requestedReasoning)
        if (first.isSuccess) {
            return ModelCouncilTextResult(first.getOrThrow(), warnings)
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
        // Note the retry path: the first attempt already streamed partial text
        // (if it got that far before failing), and the retry restarts from an
        // empty accumulator. If a user reports "the beginning appeared then the
        // whole thing re-flowed", it's this retry, not a streaming bug.
        android.util.Log.w(
            "CouncilRunner",
            "$label first stream failed (${firstError.message}); trying ${candidates.size} fallback candidate(s)",
            firstError,
        )

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

/** Finish reasons meaning "the provider cut the reply at its output limit". Same set as the chat kernel's. */
private val OUTPUT_LIMIT_FINISH_REASONS = setOf("length", "max_tokens", "max_output_tokens")

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
