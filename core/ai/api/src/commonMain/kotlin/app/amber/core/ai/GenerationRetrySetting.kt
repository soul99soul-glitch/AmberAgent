package app.amber.core.ai

import kotlinx.serialization.Serializable

@Serializable
data class GenerationRetrySetting(
    val enabled: Boolean = true,
    val maxRetries: Int = 5,
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 16_000L,
    val jitterRatio: Float = 0.15f,
)
