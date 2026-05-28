package app.amber.feature.board.hotlist.deepread

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepReadOutput(
    @SerialName("topic_type")
    val topicType: String = "event",
    @SerialName("generation_complete")
    val generationComplete: Boolean = false,
    @SerialName("generation_phase")
    val generationPhase: DeepReadGenerationPhase = DeepReadGenerationPhase.IDLE,
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
    @SerialName("hero_image_confidence")
    val heroImageConfidence: String? = null,
    @SerialName("image_assets")
    val imageAssets: List<DeepReadImageAsset> = emptyList(),
    val diagram: DeepReadDiagram? = null,
    @SerialName("visual_diagnostics")
    val visualDiagnostics: DeepReadVisualDiagnostics? = null,
    val references: List<ReadingLink> = emptyList(),
    @SerialName("section_states")
    val sectionStates: Map<DeepReadGenerationStage, DeepReadSectionState> = emptyMap(),
    @SerialName("section_qualities")
    val sectionQualities: Map<DeepReadGenerationStage, DeepReadSectionQuality> = emptyMap(),
    @SerialName("verification_state")
    val verificationState: DeepReadSectionState = DeepReadSectionState(),
)

@Serializable
enum class DeepReadSectionStatus { PENDING, RUNNING, READY, FAILED }

@Serializable
enum class DeepReadGenerationPhase { IDLE, COLLECTING, PLANNING, WRITING, VERIFYING, COMPLETE }

@Serializable
enum class DeepReadSectionQuality { BASIC, STANDARD, VERIFIED }

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

fun DeepReadOutput.qualityOf(stage: DeepReadGenerationStage): DeepReadSectionQuality? =
    sectionQualities[stage]

fun DeepReadOutput.withSectionStatus(
    stage: DeepReadGenerationStage,
    status: DeepReadSectionStatus,
    errorMessage: String? = null,
): DeepReadOutput {
    val nextStates = sectionStates.toMutableMap()
    nextStates[stage] = DeepReadSectionState(status, errorMessage)
    val merged = copy(sectionStates = nextStates)
    return merged.copy(generationComplete = generationComplete && merged.isVerifiedComplete())
}

fun DeepReadOutput.withSectionQuality(
    stage: DeepReadGenerationStage,
    quality: DeepReadSectionQuality,
): DeepReadOutput {
    val nextQualities = sectionQualities.toMutableMap()
    nextQualities[stage] = quality
    return copy(sectionQualities = nextQualities)
}

fun DeepReadOutput.isComplete(): Boolean =
    generationComplete && isVerifiedComplete()

fun DeepReadOutput.isVerifiedComplete(): Boolean =
    sectionsReady() && verificationState.status == DeepReadSectionStatus.READY

fun DeepReadOutput.sectionsReady(): Boolean =
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
    return merged.copy(generationComplete = legacyComplete && merged.isVerifiedComplete())
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
    val confidence: String? = null,
    val score: Int? = null,
    @SerialName("selection_reason")
    val selectionReason: String? = null,
)

@Serializable
data class DeepReadImageCandidate(
    @SerialName("image_url")
    val imageUrl: String,
    @SerialName("source_url")
    val sourceUrl: String,
    @SerialName("source_title")
    val sourceTitle: String,
    @SerialName("page_title")
    val pageTitle: String? = null,
    val alt: String? = null,
    @SerialName("nearby_text")
    val nearbyText: String? = null,
    @SerialName("candidate_kind")
    val candidateKind: String,
    @SerialName("source_service")
    val sourceService: String? = null,
    val query: String? = null,
    val rank: Int? = null,
    val quality: DeepReadImageQuality = DeepReadImageQuality(),
    val score: Int = 0,
    val confidence: String = IMAGE_CONFIDENCE_REJECT,
    @SerialName("risk_flags")
    val riskFlags: List<String> = emptyList(),
)

@Serializable
data class DeepReadImageQuality(
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    @SerialName("byte_size")
    val byteSize: Long? = null,
)

@Serializable
data class DeepReadImageSelection(
    @SerialName("image_url")
    val imageUrl: String,
    val confidence: String,
    val score: Int,
    val reason: String,
    @SerialName("risk_flags")
    val riskFlags: List<String> = emptyList(),
)

@Serializable
data class DeepReadVisualDiagnostics(
    @SerialName("candidate_count")
    val candidateCount: Int = 0,
    @SerialName("hero_selection")
    val heroSelection: DeepReadImageSelection? = null,
    @SerialName("inline_selections")
    val inlineSelections: List<DeepReadImageSelection> = emptyList(),
    @SerialName("rejected_images")
    val rejectedImages: List<DeepReadImageSelection> = emptyList(),
)

@Serializable
data class DeepReadDiagram(
    val type: String,
    val title: String,
    val reason: String? = null,
    val nodes: List<DeepReadDiagramNode> = emptyList(),
    val edges: List<DeepReadDiagramEdge> = emptyList(),
    val caption: String? = null,
)

@Serializable
data class DeepReadDiagramNode(
    val id: String,
    val label: String,
    val note: String? = null,
    val group: String? = null,
)

@Serializable
data class DeepReadDiagramEdge(
    val from: String,
    val to: String,
    val label: String? = null,
)

@Serializable
data class DeepReadPlaybookSnapshot(
    val revision: String,
    val markdown: String,
    @SerialName("updated_at")
    val updatedAt: Long,
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
        .filter { it.confidence != IMAGE_CONFIDENCE_REJECT }
        .map { it.url }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .toSet()

fun DeepReadOutput.displayHeroImageUrl(): String? {
    val safeImages = verifiedImageUrls()
    return heroImageUrl?.takeIf { it in safeImages && heroImageConfidence == IMAGE_CONFIDENCE_HERO }
        ?: imageAssets.firstOrNull { asset ->
            asset.url in safeImages && asset.confidence != IMAGE_CONFIDENCE_REJECT
        }?.url
}

fun DeepReadOutput.displayHeroCaption(imageUrl: String? = displayHeroImageUrl()): String? {
    if (imageUrl == null) return null
    return heroCaption?.takeIf { heroImageUrl == imageUrl && it.isNotBlank() }
        ?: imageAssets.firstOrNull { it.url == imageUrl }?.caption?.takeIf { it.isNotBlank() }
}

const val IMAGE_CONFIDENCE_HERO = "hero"
const val IMAGE_CONFIDENCE_INLINE = "inline"
const val IMAGE_CONFIDENCE_REJECT = "reject"

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
