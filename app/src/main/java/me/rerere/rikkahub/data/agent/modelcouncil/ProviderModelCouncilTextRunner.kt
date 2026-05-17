package me.rerere.rikkahub.data.agent.modelcouncil

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
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
        suspend fun streamWith(candidateTemperature: Float?): String {
            val params = TextGenerationParams(
                model = model,
                tools = emptyList(),
                reasoningLevel = ReasoningLevel.OFF,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
                temperature = candidateTemperature,
            )
            // Streaming path: per-chunk MessageChunk.choices.first.delta is the delta; we
            // accumulate and emit the running text.
            val accumulated = StringBuilder()
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
                    onChunk(accumulated.toString())
                }
            }
            return accumulated.toString().take(outputBudgetChars)
        }
        return try {
            ModelCouncilTextResult(streamWith(temperature))
        } catch (error: Throwable) {
            if (error is CancellationException || temperature == null || !error.isUnsupportedTemperatureError()) {
                throw error
            }
            val label = listOf(provider.name, model.displayName.ifBlank { model.modelId })
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            ModelCouncilTextResult(
                text = streamWith(null),
                warnings = listOf("$label rejected temperature; retried once without temperature."),
            )
        }
    }
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
