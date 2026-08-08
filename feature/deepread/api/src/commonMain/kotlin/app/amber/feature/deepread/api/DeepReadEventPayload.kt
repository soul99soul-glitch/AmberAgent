package app.amber.feature.deepread.api

import app.amber.core.agent.runtime.AgentEventPayload
import kotlinx.serialization.Serializable

sealed interface DeepReadEventPayload {

    @Serializable
    data class SectionCompleted(
        val stage: String,
        val heading: String,
        val contentPreview: String,
        val quality: String,
    ) : DeepReadEventPayload, AgentEventPayload.Final

    @Serializable
    data class GenerationPhaseChanged(
        val phase: String,
    ) : DeepReadEventPayload, AgentEventPayload.Final
}
