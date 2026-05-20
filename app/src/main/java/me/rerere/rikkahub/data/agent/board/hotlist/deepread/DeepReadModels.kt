package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepReadOutput(
    @SerialName("topic_type")
    val topicType: String = "event",
    @SerialName("generation_complete")
    val generationComplete: Boolean = false,
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
    @SerialName("section_states")
    val sectionStates: Map<DeepReadGenerationStage, DeepReadSectionState> = emptyMap(),
)

@Serializable
enum class DeepReadSectionStatus { PENDING, RUNNING, READY, FAILED }

@Serializable
data class DeepReadSectionState(
    val status: DeepReadSectionStatus = DeepReadSectionStatus.PENDING,
    @SerialName("error_message")
    val errorMessage: String? = null,
)

fun DeepReadOutput.statusOf(stage: DeepReadGenerationStage): DeepReadSectionStatus =
    sectionStates[stage]?.status ?: DeepReadSectionStatus.PENDING

fun DeepReadOutput.errorOf(stage: DeepReadGenerationStage): String? =
    sectionStates[stage]?.errorMessage

fun DeepReadOutput.withSectionStatus(
    stage: DeepReadGenerationStage,
    status: DeepReadSectionStatus,
    errorMessage: String? = null,
): DeepReadOutput {
    val nextStates = sectionStates.toMutableMap()
    nextStates[stage] = DeepReadSectionState(status, errorMessage)
    val merged = copy(sectionStates = nextStates)
    return merged.copy(generationComplete = merged.isComplete())
}

fun DeepReadOutput.isComplete(): Boolean =
    DeepReadGenerationStage.entries.all { sectionStates[it]?.status == DeepReadSectionStatus.READY }

fun DeepReadOutput.hasAnyReadySection(): Boolean =
    sectionStates.values.any { it.status == DeepReadSectionStatus.READY }

fun DeepReadOutput.withInferredSectionStates(): DeepReadOutput {
    if (sectionStates.isNotEmpty()) return this
    val inferred = mutableMapOf<DeepReadGenerationStage, DeepReadSectionState>()
    val legacyComplete = generationComplete
    val overviewReady = summary.trim().isNotBlank()
    val narrativeReady = (timeline.orEmpty().count { it.event.isNotBlank() } >= 1) ||
        (corePoints.orEmpty().count { it.point.isNotBlank() } >= 1)
    val analysisReady = !analysis.coreDispute.isNullOrBlank() ||
        !analysis.implications.isNullOrBlank() ||
        analysis.perspectives.any { it.viewpoint.isNotBlank() } ||
        analysis.quotes.any { it.text.isNotBlank() }
    val readingReady = extendedReading.isNotEmpty() || references.isNotEmpty()
    if (overviewReady || legacyComplete) {
        inferred[DeepReadGenerationStage.OVERVIEW] = DeepReadSectionState(DeepReadSectionStatus.READY)
    }
    if (narrativeReady || legacyComplete) {
        inferred[DeepReadGenerationStage.NARRATIVE] = DeepReadSectionState(DeepReadSectionStatus.READY)
    }
    if (analysisReady || legacyComplete) {
        inferred[DeepReadGenerationStage.ANALYSIS] = DeepReadSectionState(DeepReadSectionStatus.READY)
    }
    if (readingReady || legacyComplete) {
        inferred[DeepReadGenerationStage.EXTENDED_READING] = DeepReadSectionState(DeepReadSectionStatus.READY)
    }
    val merged = copy(sectionStates = inferred)
    return merged.copy(generationComplete = merged.isComplete())
}

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
    if (summary.trim().length < 80) return false
    if (lowInformationFallbackPhrases.any { it in visibleTextForLanguageCheck() }) return false
    val hasTimeline = timeline.orEmpty().count { it.event.trim().length >= 20 } >= 2
    val hasCorePoints = corePoints.orEmpty().count {
        it.point.trim().length >= 10 && it.supporting.orEmpty().trim().length >= 30
    } >= 2
    val hasAnalysis = listOfNotNull(
        analysis.coreDispute,
        analysis.implications,
    ).any { it.trim().length >= 40 } ||
        analysis.perspectives.count { it.viewpoint.trim().length >= 30 } >= 2
    return hasTimeline && hasCorePoints && hasAnalysis
}

fun DeepReadOutput.hasEnoughChinese(): Boolean {
    val text = articleTextForQualityCheck()
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

private fun DeepReadOutput.visibleTextForLanguageCheck(): String = articleTextForQualityCheck()

private fun DeepReadOutput.articleTextForQualityCheck(): String =
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
    }

private val lowInformationFallbackPhrases = listOf(
    "当前可抓取信息仍偏薄",
    "可用信息不足以形成稳定脉络",
    "抓取摘要不足",
    "来源提供了相关背景线索",
    "来源链接统一收在扩展阅读",
    "避免把来源列表误写成深度分析",
    "后续深读应围绕",
    "模型输出格式不稳定",
    "目前来源未覆盖",
    "更多材料",
    "链接见扩展阅读",
    "来源见扩展阅读",
)
