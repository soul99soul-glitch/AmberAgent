package app.amber.feature.deepread.api

import app.amber.core.agent.runtime.AgentArtifact
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepReadArtifact(
    val summary: String = "",
    @SerialName("topic_type") val topicType: String = "event",
    @SerialName("section_count") val sectionCount: Int = 0,
    @SerialName("generation_complete") val generationComplete: Boolean = false,
) : AgentArtifact
