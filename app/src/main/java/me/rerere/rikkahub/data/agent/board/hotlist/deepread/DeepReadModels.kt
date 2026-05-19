package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepReadOutput(
    @SerialName("topic_type")
    val topicType: String = "event",
    val summary: String = "",
    @SerialName("key_entities")
    val keyEntities: List<String> = emptyList(),
    val timeline: List<TimelineEvent>? = null,
    @SerialName("core_points")
    val corePoints: List<CorePoint>? = null,
    val analysis: DeepAnalysis = DeepAnalysis(),
    @SerialName("extended_reading")
    val extendedReading: List<ReadingLink> = emptyList(),
    @SerialName("hero_image_query")
    val heroImageQuery: String? = null,
    @SerialName("hero_image_url")
    val heroImageUrl: String? = null,
    @SerialName("hero_caption")
    val heroCaption: String? = null,
    val references: List<ReadingLink> = emptyList(),
)

@Serializable
data class TimelineEvent(
    val date: String,
    val event: String,
    @SerialName("is_highlight")
    val isHighlight: Boolean = false,
)

@Serializable
data class CorePoint(
    val point: String,
    val supporting: String? = null,
)

@Serializable
data class DeepAnalysis(
    @SerialName("core_dispute")
    val coreDispute: String? = null,
    val perspectives: List<Perspective> = emptyList(),
    val implications: String? = null,
    val quotes: List<DeepQuote> = emptyList(),
)

@Serializable
data class Perspective(
    val viewpoint: String,
    val holder: String? = null,
)

@Serializable
data class DeepQuote(
    val text: String,
    val attribution: String? = null,
)

@Serializable
data class ReadingLink(
    val title: String,
    val url: String,
    val source: String? = null,
)

data class DeepReadState(
    val isLoading: Boolean = false,
    val output: DeepReadOutput? = null,
    val cached: Boolean = false,
    val error: String? = null,
)

fun DeepReadOutput.hasReadableArticle(): Boolean {
    if (summary.trim().length < 10) return false
    return timeline.orEmpty().any { it.event.isNotBlank() } ||
        corePoints.orEmpty().any { it.point.isNotBlank() } ||
        !analysis.coreDispute.isNullOrBlank() ||
        !analysis.implications.isNullOrBlank() ||
        analysis.perspectives.any { it.viewpoint.isNotBlank() } ||
        analysis.quotes.any { it.text.isNotBlank() }
}
