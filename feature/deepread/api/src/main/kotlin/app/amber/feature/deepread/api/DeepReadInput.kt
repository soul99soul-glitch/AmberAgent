package app.amber.feature.deepread.api

import app.amber.core.agent.runtime.AgentInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepReadInput(
    val url: String,
    @SerialName("topic_id") val topicId: String,
    @SerialName("topic_title") val title: String,
    val stages: List<String> = emptyList(),
    val force: Boolean = false,
) : AgentInput
