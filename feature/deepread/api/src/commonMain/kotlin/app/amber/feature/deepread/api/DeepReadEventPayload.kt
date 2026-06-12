package app.amber.feature.deepread.api

import app.amber.core.agent.runtime.AgentEventPayload
import kotlinx.serialization.Serializable

sealed interface DeepReadEventPayload {

    @Serializable
    data class SectionStarted(
        val stage: String,
        val heading: String,
    ) : DeepReadEventPayload, AgentEventPayload.Final

    @Serializable
    data class SectionCompleted(
        val stage: String,
        val heading: String,
        val contentPreview: String,
        val quality: String,
    ) : DeepReadEventPayload, AgentEventPayload.Final

    @Serializable
    data class VerificationCompleted(
        val passed: Boolean,
        val issues: List<String>,
    ) : DeepReadEventPayload, AgentEventPayload.Final

    @Serializable
    data class GenerationPhaseChanged(
        val phase: String,
    ) : DeepReadEventPayload, AgentEventPayload.Final

    @Serializable
    data class StreamChunk(
        val stage: String,
        val delta: String,
    ) : DeepReadEventPayload, AgentEventPayload.Transient
}
