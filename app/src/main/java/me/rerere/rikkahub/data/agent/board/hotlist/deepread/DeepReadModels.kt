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
    @SerialName("image_assets")
    val imageAssets: List<DeepReadImageAsset> = emptyList(),
    val references: List<ReadingLink> = emptyList(),
)

@Serializable
data class TimelineEvent(
    val date: String,
    val event: String,
    @SerialName("is_highlight")
    val isHighlight: Boolean = false,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("image_caption")
    val imageCaption: String? = null,
)

@Serializable
data class CorePoint(
    val point: String,
    val supporting: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("image_caption")
    val imageCaption: String? = null,
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

@Serializable
data class DeepReadImageAsset(
    val url: String,
    val caption: String? = null,
    val source: String? = null,
    @SerialName("related_entities")
    val relatedEntities: List<String> = emptyList(),
    @SerialName("related_timeline_index")
    val relatedTimelineIndex: Int? = null,
    @SerialName("quality_hint")
    val qualityHint: String? = null,
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

fun DeepReadOutput.hasEnoughChinese(): Boolean {
    val text = visibleTextForLanguageCheck()
    val cjk = text.count { it in '\u4e00'..'\u9fff' }
    val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    if (cjk >= 80) return true
    if (cjk < 12 && latin > 30) return false
    val denominator = (cjk + latin).coerceAtLeast(1)
    return cjk.toDouble() / denominator >= 0.35
}

fun DeepReadOutput.verifiedImageUrls(): Set<String> =
    imageAssets
        .map { it.url }
        .filter { it.startsWith("http") }
        .toSet()

private fun DeepReadOutput.visibleTextForLanguageCheck(): String =
    buildString {
        append(summary)
        append(' ')
        keyEntities.forEach { append(it).append(' ') }
        timeline.orEmpty().forEach { append(it.date).append(' ').append(it.event).append(' ') }
        corePoints.orEmpty().forEach { append(it.point).append(' ').append(it.supporting).append(' ') }
        append(analysis.coreDispute).append(' ')
        analysis.perspectives.forEach { append(it.holder).append(' ').append(it.viewpoint).append(' ') }
        append(analysis.implications).append(' ')
        analysis.quotes.forEach { append(it.text).append(' ').append(it.attribution).append(' ') }
        extendedReading.forEach { append(it.title).append(' ') }
        append(heroCaption).append(' ')
        imageAssets.forEach { append(it.caption).append(' ') }
        references.forEach { append(it.title).append(' ') }
    }
