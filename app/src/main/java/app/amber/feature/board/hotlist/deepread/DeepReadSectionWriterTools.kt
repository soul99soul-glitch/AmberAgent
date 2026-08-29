package app.amber.feature.board.hotlist.deepread

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.board.hotlist.HotListRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val OVERVIEW_SUMMARY_STORAGE_MAX_CHARS = 1_200
private const val MAX_DIAGRAM_NODES = 6
private const val MAX_LINEAR_DIAGRAM_EDGES = 5
private const val MAX_RELATION_DIAGRAM_EDGES = 6
private const val DIAGRAM_TITLE_MAX_CHARS = 64
private const val DIAGRAM_NODE_LABEL_MAX_CHARS = 34
private const val DIAGRAM_NODE_NOTE_MAX_CHARS = 96
private const val DIAGRAM_NODE_GROUP_MAX_CHARS = 40
private const val DIAGRAM_EDGE_LABEL_MAX_CHARS = 42
private const val OVERVIEW_SUMMARY_MIN_CHARS = 80

private data class WriterFeedback(
    val accepted: Map<String, Int> = emptyMap(),
    val dropped: Map<String, Int> = emptyMap(),
    val dropReasons: List<String> = emptyList(),
)

class DeepReadSectionWriterTools(
    private val repository: HotListRepository,
    private val topicId: String,
    private val topicTitle: String,
    private val imageCandidates: List<DeepReadImageCandidate> = emptyList(),
    private val allowTitleFallback: Boolean = true,
    private val ttlDays: Int = HotListRepository.DEFAULT_TTL_DAYS,
) {
    private val _writeCount = AtomicInteger(0)
    private val _requiredWriteCount = AtomicInteger(0)
    private val writeMutex = Mutex()
    val writeCount: Int get() = _writeCount.get()
    val requiredWriteCount: Int get() = _requiredWriteCount.get()

    fun tools(
        stages: Set<DeepReadGenerationStage>? = null,
        locale: Locale = Locale.CHINESE,
    ): List<Tool> = buildList {
        if (stages == null || DeepReadGenerationStage.OVERVIEW in stages) add(overviewTool(locale))
        if (stages == null || DeepReadGenerationStage.NARRATIVE in stages) add(narrativeTool(locale))
        if (stages == null || DeepReadGenerationStage.ANALYSIS in stages) add(analysisTool(locale))
        if (stages == null || DeepReadGenerationStage.EXTENDED_READING in stages) add(extendedReadingTool(locale))
        add(visualsTool(locale))
        add(diagramTool(locale))
        add(finishTool())
    }

    suspend fun markPhase(phase: DeepReadGenerationPhase): DeepReadOutput =
        update { current ->
            current.copy(generationPhase = phase)
        }

    suspend fun markRunning(stages: Collection<DeepReadGenerationStage>): DeepReadOutput =
        update { current ->
            stages.fold(
                current.copy(
                    generationPhase = DeepReadGenerationPhase.WRITING,
                    verificationState = DeepReadSectionState(),
                )
            ) { output, stage ->
                if (output.statusOf(stage) == DeepReadSectionStatus.READY) {
                    output
                } else {
                    output.withSectionStatus(stage, DeepReadSectionStatus.RUNNING)
                }
            }
        }

    suspend fun markFailed(stage: DeepReadGenerationStage, message: String): DeepReadOutput =
        update { current ->
            if (current.statusOf(stage) == DeepReadSectionStatus.READY) {
                current
            } else {
                current.withSectionStatus(stage, DeepReadSectionStatus.FAILED, message.safeTake(220))
            }
        }

    /**
     * Links-only fallback: merges real source links into references / extended_reading but
     * does NOT synthesize placeholder body text and does NOT mark the section READY.
     *
     * Previously this produced占位正文 ("围绕xxx，当前来源已提供可继续阅读的基础事实…") and
     * stamped READY+BASIC, which disguised a failed section as a thin success — directly
     * contradicting the playbook's ban on占位话术. Body fallback is abolished (spec A1):
     * failed sections now surface as FAILED. Real source links are still worth keeping
     * (they are genuine, not伪装), so we preserve them here. The caller (RunManager)
     * then calls markFailed(stage, ...) — this function never changes section status.
     */
    suspend fun writeFallbackSection(
        stage: DeepReadGenerationStage,
        assistantText: String,
        sources: List<DeepReadSource>,
        allowReadyRewrite: Boolean = false,
    ): DeepReadOutput =
        update { current ->
            if (!allowReadyRewrite && current.statusOf(stage) == DeepReadSectionStatus.READY) return@update current
            val links = sources.toReadingLinks()
            // Merge real source links for every stage — they are genuine, and the failing
            // section should not discard them. EXTENDED_READING additionally feeds extendedReading.
            val withExtended = if (stage == DeepReadGenerationStage.EXTENDED_READING) {
                mergeReadingLinks(current.extendedReading, links, limit = 10)
            } else {
                current.extendedReading
            }
            current.copy(
                extendedReading = withExtended,
                references = mergeReadingLinks(current.references, links, limit = 12),
            )
        }

    suspend fun currentOutput(): DeepReadOutput =
        repository.getFreshDeepRead(
            topicId = topicId,
            title = topicTitle.takeIf { allowTitleFallback },
        )
            ?.withInferredSectionStates()
            ?: DeepReadOutput()

    private fun overviewTool(locale: Locale) = Tool(
        name = "deep_read_write_overview",
        description = "Internal Deep Read writer. Write the source-backed overview section after using search_web/scrape_web. UI only renders content written through this tool.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("topic_type", stringProp("event/opinion/product/person."))
                    put("summary", stringProp(if (locale.isChineseLocale()) {
                        "Chinese editorial overview, around 120-250 Chinese characters; keep sentences complete."
                    } else {
                        "English editorial overview, around 120-250 characters; keep sentences complete."
                    }))
                    put("key_entities", stringArrayProp("Key people, companies, products, places, or institutions."))
                    put("references", readingLinksProp("Sources used in this section.", locale))
                },
                required = listOf("summary"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val summary = obj.string("summary")?.cleanText(OVERVIEW_SUMMARY_STORAGE_MAX_CHARS)
            val output = update { current ->
                val references = obj.readingLinks("references")
                val next = current.copy(
                    topicType = obj.string("topic_type")?.safeTake(32) ?: current.topicType,
                    summary = summary ?: current.summary,
                    keyEntities = mergeStrings(current.keyEntities, obj.stringList("key_entities"), limit = 12),
                    references = mergeReadingLinks(current.references, references, limit = 12),
                )
                if (!next.hasOverviewContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.OVERVIEW, DeepReadSectionQuality.STANDARD)
            }
            if (output.statusOf(DeepReadGenerationStage.OVERVIEW) == DeepReadSectionStatus.READY) {
                ok(
                    section = "overview",
                    output = output,
                    feedback = WriterFeedback(
                        accepted = mapOf(
                            "summary_chars" to output.summary.trim().length,
                            "key_entities" to output.keyEntities.size,
                            "references" to output.references.size,
                        )
                    ),
                )
            } else {
                val summaryChars = summary.orEmpty().trim().length
                val required = if (summary.isNullOrBlank()) {
                    "summary missing"
                } else {
                    "summary too short: $summaryChars/$OVERVIEW_SUMMARY_MIN_CHARS"
                }
                missing(
                    section = "overview",
                    required = required,
                    feedback = WriterFeedback(
                        dropped = mapOf("summary" to 1),
                        dropReasons = listOf(required),
                    ),
                )
            }
        },
    )

    private fun narrativeTool(locale: Locale) = Tool(
        name = "deep_read_write_narrative",
        description = "Internal Deep Read writer. Write timeline/story narrative data. Use timeline for events, core_points for opinion/product/person topics.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("timeline", timelineProp(locale))
                    put("core_points", corePointsProp(locale))
                    put("references", readingLinksProp("Sources used in this section.", locale))
                },
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val timeline = obj.timeline()
            val corePoints = obj.corePoints()
            val references = obj.readingLinks("references")
            val rawTimelineCount = obj.objectList("timeline").size
            val rawCorePointCount = obj.objectList("core_points").size
            val output = update { current ->
                val next = current.copy(
                    timeline = timeline.takeIf { it.isNotEmpty() } ?: current.timeline,
                    corePoints = corePoints.takeIf { it.isNotEmpty() } ?: current.corePoints,
                    references = mergeReadingLinks(current.references, references, limit = 12),
                )
                if (!next.hasNarrativeContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.NARRATIVE, DeepReadSectionQuality.STANDARD)
            }
            val feedback = WriterFeedback(
                accepted = mapOf(
                    "timeline" to timeline.size,
                    "core_points" to corePoints.size,
                    "references" to references.size,
                ),
                dropped = mapOf(
                    "timeline" to (rawTimelineCount - timeline.size).coerceAtLeast(0),
                    "core_points" to (rawCorePointCount - corePoints.size).coerceAtLeast(0),
                ).filterValues { it > 0 },
                dropReasons = buildList {
                    if (rawTimelineCount > timeline.size) add("timeline dropped: missing event or truncated limit=8")
                    if (rawCorePointCount > corePoints.size) add("core_points dropped: missing point or truncated limit=8")
                },
            )
            if (output.statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.READY) {
                ok("narrative", output, feedback)
            } else {
                missing("narrative", "timeline or core_points", feedback)
            }
        },
    )

    private fun analysisTool(locale: Locale) = Tool(
        name = "deep_read_write_analysis",
        description = "Internal Deep Read writer. Write the deep analysis section after reasoning over source-backed evidence.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("core_dispute", stringProp("Central tension or core dispute."))
                    put("perspectives", perspectivesProp(locale))
                    put("implications", stringProp(if (locale.isChineseLocale()) "Impact analysis in Chinese." else "Impact analysis in English."))
                    put("quotes", quotesProp())
                    put("references", readingLinksProp("Sources used in this section.", locale))
                },
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val next = current.copy(
                    analysis = DeepAnalysis(
                        coreDispute = obj.string("core_dispute")?.cleanText(1_000) ?: current.analysis.coreDispute,
                        perspectives = obj.perspectives().takeIf { it.isNotEmpty() } ?: current.analysis.perspectives,
                        implications = obj.string("implications")?.cleanText(1_600) ?: current.analysis.implications,
                        quotes = obj.quotes().takeIf { it.isNotEmpty() } ?: current.analysis.quotes,
                    ),
                    references = mergeReadingLinks(current.references, obj.readingLinks("references"), limit = 12),
                )
                if (!next.hasAnalysisContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.ANALYSIS, DeepReadSectionQuality.STANDARD)
            }
            if (output.statusOf(DeepReadGenerationStage.ANALYSIS) == DeepReadSectionStatus.READY) {
                ok("analysis", output)
            } else {
                missing("analysis", "core_dispute, perspectives, implications, or quotes")
            }
        },
    )

    private fun extendedReadingTool(locale: Locale) = Tool(
        name = "deep_read_write_extended_reading",
        description = "Internal Deep Read writer. Write source-backed extended reading links and optional real image assets.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("links", readingLinksProp("Recommended source links.", locale))
                    put("image_assets", imageAssetsProp(locale))
                },
                required = listOf("links"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val links = obj.readingLinks("links")
                    .ifEmpty { obj.readingLinks("extended_reading") }
                val next = current.copy(
                    extendedReading = mergeReadingLinks(current.extendedReading, links, limit = 10),
                    references = mergeReadingLinks(current.references, links, limit = 12),
                    imageAssets = mergeImageAssets(
                        current.imageAssets,
                        obj.imageAssets().mapNotNull { it.withCandidateEvidence() },
                        limit = 8,
                    ),
                )
                if (!next.hasExtendedReadingContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.EXTENDED_READING, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.EXTENDED_READING, DeepReadSectionQuality.STANDARD)
            }
            if (output.statusOf(DeepReadGenerationStage.EXTENDED_READING) == DeepReadSectionStatus.READY) {
                ok("extended_reading", output)
            } else {
                missing("extended_reading", "links")
            }
        },
    )

    private fun visualsTool(locale: Locale) = Tool(
        name = "deep_read_write_visuals",
        description = "Internal Deep Read visual selector. Select hero/inline images only from the pre-fetched candidate pool; never submit arbitrary URLs.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("hero_image_url", stringProp("Optional candidate image URL. Must have hero confidence in the candidate pool."))
                    put("hero_caption", stringProp(if (locale.isChineseLocale()) "Chinese caption for selected hero image." else "English caption for selected hero image."))
                    put("hero_reason", stringProp("Why this candidate matches the title and is not a logo/icon."))
                    put("image_assets", imageAssetsProp(locale))
                },
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val heroUrl = obj.url("hero_image_url")?.takeIf { isHeroCandidate(it) }
                val incomingAssets = obj.imageAssets()
                    .mapNotNull { asset -> asset.withCandidateEvidence() }
                val heroCandidate = heroUrl?.let { candidateForUrl(it) }
                val heroReason = obj.string("hero_reason")?.cleanText(240)
                    ?: heroCandidate?.selectionReason(topicTitle)
                val heroAsset = heroCandidate?.toImageAsset(
                    caption = obj.string("hero_caption")?.cleanText(180),
                    reason = heroReason,
                )
                val next = current.copy(
                    heroImageUrl = heroUrl ?: current.heroImageUrl,
                    heroCaption = obj.string("hero_caption")?.cleanText(180)?.takeIf { heroUrl != null } ?: current.heroCaption,
                    heroImageConfidence = if (heroUrl != null) IMAGE_CONFIDENCE_HERO else current.heroImageConfidence,
                    imageAssets = mergeImageAssets(current.imageAssets, listOfNotNull(heroAsset) + incomingAssets, limit = 8),
                    visualDiagnostics = buildVisualDiagnostics(
                        previous = current.visualDiagnostics,
                        heroCandidate = heroCandidate,
                        heroReason = heroReason,
                        inlineAssets = incomingAssets,
                    ),
                )
                if (heroUrl == null && incomingAssets.isEmpty()) return@update current
                markVisibleWrite()
                next
            }
            ok("visuals", output)
        },
    )

    private fun diagramTool(locale: Locale) = Tool(
        name = "deep_read_write_diagram",
        description = "Internal Deep Read diagram writer. Submit only a compact structured diagram spec; raw SVG/HTML/JS/external resources are forbidden. Use 3-6 short nodes; flow/causal diagrams may keep a few key cross-node relations but must avoid dense webs.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", stringProp("causal_chain/process_flow/stakeholder_map/system_structure/comparison_matrix."))
                    put("title", stringProp(if (locale.isChineseLocale()) "Short Chinese title." else "Short English title."))
                    put("reason", stringProp("Why this topic benefits from a diagram."))
                    put("nodes", diagramNodesProp(locale))
                    put("edges", diagramEdgesProp(locale))
                    put("caption", stringProp(if (locale.isChineseLocale()) "Optional Chinese caption." else "Optional English caption."))
                },
                required = listOf("type", "title", "nodes"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val diagram = obj.diagram()
            val rawNodeCount = obj.objectList("nodes").size
            val rawEdgeCount = obj.objectList("edges").size
            val output = update { current ->
                if (diagram == null) return@update current
                markVisibleWrite()
                current.copy(diagram = diagram)
            }
            val feedback = WriterFeedback(
                accepted = mapOf(
                    "nodes" to diagram?.nodes.orEmpty().size,
                    "edges" to diagram?.edges.orEmpty().size,
                ),
                dropped = mapOf(
                    "nodes" to (rawNodeCount - diagram?.nodes.orEmpty().size).coerceAtLeast(0),
                    "edges" to (rawEdgeCount - diagram?.edges.orEmpty().size).coerceAtLeast(0),
                ).filterValues { it > 0 },
                dropReasons = buildList {
                    if (diagram == null) add(obj.diagramDropReason())
                    if (rawNodeCount > diagram?.nodes.orEmpty().size) {
                        add("nodes dropped: missing id/label, duplicate id, or truncated limit=$MAX_DIAGRAM_NODES")
                    }
                    if (rawEdgeCount > diagram?.edges.orEmpty().size) {
                        add("edges dropped: unknown node, self edge, duplicate edge, or truncated limit")
                    }
                }.filter { it.isNotBlank() },
            )
            if (diagram != null) {
                ok("diagram", output, feedback)
            } else {
                missing("diagram", obj.diagramDropReason(), feedback)
            }
        },
    )

    private fun finishTool() = Tool(
        name = "deep_read_finish",
        description = "Internal Deep Read writer. Call after every section writer reports ready. Returns missing sections if any remain.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        allowsAutoApproval = true,
        execute = {
            val output = update { current ->
                current.copy(
                    generationPhase = if (current.sectionsReady()) {
                        DeepReadGenerationPhase.COMPLETE
                    } else {
                        current.generationPhase
                    },
                    generationComplete = current.sectionsReady(),
                )
            }
            val missing = DeepReadGenerationStage.entries.filter { output.statusOf(it) != DeepReadSectionStatus.READY }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", if (missing.isEmpty()) "complete" else "missing_sections")
                        put("missing", buildJsonArray { missing.forEach { add(JsonPrimitive(it.name.lowercase())) } })
                    }.toString()
                )
            )
        },
    )

    private fun isHeroCandidate(url: String): Boolean =
        candidateForUrl(url)?.confidence == IMAGE_CONFIDENCE_HERO

    private fun markRequiredWrite() {
        _writeCount.incrementAndGet()
        _requiredWriteCount.incrementAndGet()
    }

    private fun markVisibleWrite() {
        _writeCount.incrementAndGet()
    }

    private fun candidateForUrl(url: String): DeepReadImageCandidate? =
        imageCandidates.firstOrNull { it.imageUrl == url }

    private fun DeepReadImageAsset.withCandidateEvidence(): DeepReadImageAsset? {
        val candidate = candidateForUrl(url) ?: return null
        if (candidate.confidence == IMAGE_CONFIDENCE_REJECT) return null
        return copy(
            source = source ?: candidate.sourceService,
            confidence = candidate.confidence,
            score = candidate.score,
            qualityHint = qualityHint ?: candidate.confidence,
            selectionReason = selectionReason ?: candidate.selectionReason(topicTitle),
        )
    }

    private fun DeepReadImageCandidate.toImageAsset(
        caption: String? = null,
        reason: String? = null,
    ): DeepReadImageAsset =
        DeepReadImageAsset(
            url = imageUrl,
            caption = caption,
            source = sourceService,
            qualityHint = confidence,
            confidence = confidence,
            score = score,
            selectionReason = reason ?: selectionReason(topicTitle),
        )

    private fun buildVisualDiagnostics(
        previous: DeepReadVisualDiagnostics?,
        heroCandidate: DeepReadImageCandidate?,
        heroReason: String?,
        inlineAssets: List<DeepReadImageAsset>,
    ): DeepReadVisualDiagnostics =
        DeepReadVisualDiagnostics(
            candidateCount = imageCandidates.size.coerceAtLeast(previous?.candidateCount ?: 0),
            heroSelection = heroCandidate?.let {
                DeepReadImageSelection(
                    imageUrl = it.imageUrl,
                    confidence = it.confidence,
                    score = it.score,
                    reason = heroReason ?: it.selectionReason(topicTitle),
                    riskFlags = it.riskFlags,
                )
            } ?: previous?.heroSelection,
            inlineSelections = (previous?.inlineSelections.orEmpty() + inlineAssets.mapNotNull { asset ->
                candidateForUrl(asset.url)?.let { candidate ->
                    DeepReadImageSelection(
                        imageUrl = candidate.imageUrl,
                        confidence = candidate.confidence,
                        score = candidate.score,
                        reason = asset.selectionReason ?: candidate.selectionReason(topicTitle),
                        riskFlags = candidate.riskFlags,
                    )
                }
            }).distinctBy { it.imageUrl }.take(6),
            rejectedImages = (previous?.rejectedImages.orEmpty() + imageCandidates
                .filter { it.confidence == IMAGE_CONFIDENCE_REJECT }
                .sortedByDescending { it.score }
                .take(6)
                .map {
                    DeepReadImageSelection(
                        imageUrl = it.imageUrl,
                        confidence = it.confidence,
                        score = it.score,
                        reason = it.selectionReason(topicTitle),
                        riskFlags = it.riskFlags,
                    )
                }).distinctBy { it.imageUrl }.take(6),
        )

    private suspend fun update(transform: (DeepReadOutput) -> DeepReadOutput): DeepReadOutput = writeMutex.withLock {
        val current = currentOutput()
        val next = transform(current)
        repository.saveDeepRead(topicId, topicTitle, next, ttlDays = ttlDays)
        next
    }

    private fun ok(
        section: String,
        output: DeepReadOutput,
        feedback: WriterFeedback = WriterFeedback(),
    ): List<UIMessagePart> =
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "ok")
                    put("section", section)
                    put("generation_complete", output.isComplete())
                    appendWriterFeedback(feedback)
                }.toString()
            )
        )

    private fun missing(
        section: String,
        required: String,
        feedback: WriterFeedback = WriterFeedback(),
    ): List<UIMessagePart> =
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "missing_required_content")
                    put("section", section)
                    put("required", required)
                    appendWriterFeedback(feedback)
                }.toString()
            )
        )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.appendWriterFeedback(feedback: WriterFeedback) {
    if (feedback.accepted.isNotEmpty()) {
        put("accepted", buildJsonObject {
            feedback.accepted.forEach { (key, value) -> put(key, value) }
        })
    }
    if (feedback.dropped.isNotEmpty()) {
        put("dropped", buildJsonObject {
            feedback.dropped.forEach { (key, value) -> put(key, value) }
        })
    }
    if (feedback.dropReasons.isNotEmpty()) {
        put("drop_reasons", buildJsonArray {
            feedback.dropReasons.forEach { add(JsonPrimitive(it)) }
        })
    }
}

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun stringArrayProp(description: String) = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

private fun readingLinksProp(description: String, locale: Locale) = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("title", stringProp(if (locale.isChineseLocale()) "Chinese title." else "English title."))
            put("url", stringProp("Source URL."))
            put("source", stringProp("Source name/domain."))
        })
        put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("title"), kotlinx.serialization.json.JsonPrimitive("url"))))
    })
}

private fun timelineProp(locale: Locale) = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("date", stringProp("Date or time label."))
            put("event", stringProp(if (locale.isChineseLocale()) "Event narrative in Chinese." else "Event narrative in English."))
            put("is_highlight", buildJsonObject { put("type", "boolean") })
            put("image_url", stringProp("Optional real source image URL."))
            put("image_caption", stringProp(if (locale.isChineseLocale()) "Optional Chinese image caption." else "Optional English image caption."))
        })
    })
}

private fun corePointsProp(locale: Locale) = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("point", stringProp(if (locale.isChineseLocale()) "Point title in Chinese." else "Point title in English."))
            put("supporting", stringProp(if (locale.isChineseLocale()) "Supporting detail in Chinese." else "Supporting detail in English."))
            put("image_url", stringProp("Optional real source image URL."))
            put("image_caption", stringProp(if (locale.isChineseLocale()) "Optional Chinese image caption." else "Optional English image caption."))
        })
    })
}

private fun perspectivesProp(locale: Locale) = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("holder", stringProp("Person, organization, side, or market group."))
            put("viewpoint", stringProp(if (locale.isChineseLocale()) "Viewpoint in Chinese." else "Viewpoint in English."))
        })
    })
}

private fun quotesProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("text", stringProp("Short quotation or paraphrased quoted claim."))
            put("attribution", stringProp("Speaker or source."))
        })
    })
}

private fun imageAssetsProp(locale: Locale) = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("url", stringProp("Real source/search image URL."))
            put("caption", stringProp(if (locale.isChineseLocale()) "Chinese caption." else "English caption."))
            put("source", stringProp("Source name/domain."))
            put("quality_hint", stringProp("hero/inline/context/chart/etc."))
            put("selection_reason", stringProp("Why this candidate is useful for the article."))
        })
    })
}

private fun diagramNodesProp(locale: Locale) = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("id", stringProp("Stable short id, e.g. n1."))
            put("label", stringProp(if (locale.isChineseLocale()) "Short Chinese node label, one phrase." else "Short English node label, one phrase."))
            put("note", stringProp(if (locale.isChineseLocale()) "Optional Chinese detail, one compact sentence." else "Optional English detail, one compact sentence."))
            put("group", stringProp("Optional group/lane label."))
        })
        put("required", JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("label"))))
    })
}

private fun diagramEdgesProp(locale: Locale) = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("from", stringProp("Source node id."))
            put("to", stringProp("Target node id."))
            put("label", stringProp(if (locale.isChineseLocale()) "Optional short Chinese edge label." else "Optional short English edge label."))
        })
        put("required", JsonArray(listOf(JsonPrimitive("from"), JsonPrimitive("to"))))
    })
}

private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

private fun JsonElement.objectOrEmpty(): JsonObject =
    runCatching { jsonObject }.getOrDefault(JsonObject(emptyMap()))

private fun JsonObject.string(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.array(name: String): List<JsonElement> =
    runCatching { get(name)?.jsonArray?.toList().orEmpty() }.getOrDefault(emptyList())

private fun JsonObject.stringList(name: String): List<String> =
    array(name).mapNotNull { it.jsonPrimitive.contentOrNull?.cleanText(80) }.filter { it.isNotBlank() }

private fun JsonObject.url(name: String): String? =
    string(name)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

private fun JsonObject.objectList(name: String): List<JsonObject> =
    array(name).mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }

private fun JsonObject.readingLinks(name: String): List<ReadingLink> =
    objectList(name).mapNotNull { obj ->
        val url = obj.url("url") ?: return@mapNotNull null
        val title = obj.string("title")?.cleanText(160) ?: url.substringAfter("://").substringBefore('/')
        ReadingLink(
            title = title,
            url = url,
            source = obj.string("source")?.cleanText(80),
        )
    }

private fun JsonObject.timeline(): List<TimelineEvent> =
    objectList("timeline").mapNotNull { obj ->
        val event = obj.string("event")?.cleanText(600) ?: return@mapNotNull null
        TimelineEvent(
            date = obj.string("date")?.cleanText(80) ?: "",
            event = event,
            isHighlight = obj.boolean("is_highlight") ?: false,
            imageUrl = obj.url("image_url"),
            imageCaption = obj.string("image_caption")?.cleanText(180),
        )
    }.take(8)

private fun JsonObject.corePoints(): List<CorePoint> =
    objectList("core_points").mapNotNull { obj ->
        val point = obj.string("point")?.cleanText(280) ?: return@mapNotNull null
        CorePoint(
            point = point,
            supporting = obj.string("supporting")?.cleanText(700),
            imageUrl = obj.url("image_url"),
            imageCaption = obj.string("image_caption")?.cleanText(180),
        )
    }.take(8)

private fun JsonObject.perspectives(): List<Perspective> =
    objectList("perspectives").mapNotNull { obj ->
        val viewpoint = obj.string("viewpoint")?.cleanText(700) ?: return@mapNotNull null
        Perspective(
            viewpoint = viewpoint,
            holder = obj.string("holder")?.cleanText(120),
        )
    }.take(8)

private fun JsonObject.quotes(): List<DeepQuote> =
    objectList("quotes").mapNotNull { obj ->
        val text = obj.string("text")?.cleanText(420) ?: return@mapNotNull null
        DeepQuote(
            text = text,
            attribution = obj.string("attribution")?.cleanText(160),
        )
    }.take(6)

private fun JsonObject.imageAssets(): List<DeepReadImageAsset> =
    objectList("image_assets").mapNotNull { obj ->
        val url = obj.url("url") ?: return@mapNotNull null
        DeepReadImageAsset(
            url = url,
            caption = obj.string("caption")?.cleanText(180),
            source = obj.string("source")?.cleanText(80),
            qualityHint = obj.string("quality_hint")?.cleanText(60),
            selectionReason = obj.string("selection_reason")?.cleanText(240),
        )
    }.take(8)

private fun JsonObject.diagram(): DeepReadDiagram? {
    val type = string("type")?.lowercase()?.takeIf {
        it in setOf("causal_chain", "process_flow", "stakeholder_map", "system_structure", "comparison_matrix")
    } ?: return null
    val title = string("title")?.cleanText(DIAGRAM_TITLE_MAX_CHARS) ?: return null
    val nodes = objectList("nodes").mapNotNull { obj ->
        val id = obj.string("id")?.cleanText(32) ?: return@mapNotNull null
        val label = obj.string("label")?.cleanText(DIAGRAM_NODE_LABEL_MAX_CHARS) ?: return@mapNotNull null
        DeepReadDiagramNode(
            id = id,
            label = label,
            note = obj.string("note")?.cleanText(DIAGRAM_NODE_NOTE_MAX_CHARS),
            group = obj.string("group")?.cleanText(DIAGRAM_NODE_GROUP_MAX_CHARS),
        )
    }.distinctBy { it.id }.take(MAX_DIAGRAM_NODES)
    if (nodes.size < 2) return null
    val nodeIds = nodes.map { it.id }.toSet()
    val rawEdges = objectList("edges").mapNotNull { obj ->
        val from = obj.string("from")?.cleanText(32) ?: return@mapNotNull null
        val to = obj.string("to")?.cleanText(32) ?: return@mapNotNull null
        if (from !in nodeIds || to !in nodeIds || from == to) return@mapNotNull null
        DeepReadDiagramEdge(
            from = from,
            to = to,
            label = obj.string("label")?.cleanText(DIAGRAM_EDGE_LABEL_MAX_CHARS),
        )
    }
    val edges = rawEdges.normalizedDiagramEdges(type = type, nodeIds = nodes.map { it.id })
    return DeepReadDiagram(
        type = type,
        title = title,
        reason = string("reason")?.cleanText(220),
        nodes = nodes,
        edges = edges,
        caption = string("caption")?.cleanText(180),
    )
}

private fun JsonObject.diagramDropReason(): String {
    val type = string("type")?.lowercase()
    if (type !in setOf("causal_chain", "process_flow", "stakeholder_map", "system_structure", "comparison_matrix")) {
        return "invalid type"
    }
    if (string("title")?.cleanText(DIAGRAM_TITLE_MAX_CHARS).isNullOrBlank()) {
        return "title missing"
    }
    val acceptedNodes = objectList("nodes").mapNotNull { obj ->
        val id = obj.string("id")?.cleanText(32) ?: return@mapNotNull null
        val label = obj.string("label")?.cleanText(DIAGRAM_NODE_LABEL_MAX_CHARS) ?: return@mapNotNull null
        id to label
    }.distinctBy { it.first }.take(MAX_DIAGRAM_NODES)
    if (acceptedNodes.size < 2) return "nodes < 2"
    return "type, title, nodes"
}

private fun List<DeepReadDiagramEdge>.normalizedDiagramEdges(
    type: String,
    nodeIds: List<String>,
): List<DeepReadDiagramEdge> {
    val unique = distinctBy { "${it.from}->${it.to}" }
    val limit = if (type == "process_flow" || type == "causal_chain") {
        MAX_LINEAR_DIAGRAM_EDGES
    } else {
        MAX_RELATION_DIAGRAM_EDGES
    }
    return unique
        .filter { it.from in nodeIds && it.to in nodeIds && it.from != it.to }
        .take(limit)
}

private fun String.cleanText(max: Int): String =
    replace(Regex("\\s+"), " ")
        .normalizeQuotes()
        .trim()
        .safeTake(max)
        .repairTruncatedMarkdownLink()

/**
 * If [safeTake] cut a claim string mid-link, the trailing `[text](url` fragment would
 * render as literal garbage. Drop the whole incomplete link by rewinding to the last `[`
 * that opened a still-unclosed link. A `[` with no following `](` is treated as a literal
 * bracket and left alone.
 */
private fun String.repairTruncatedMarkdownLink(): String {
    val lastOpen = lastIndexOf('[')
    if (lastOpen < 0) return this
    val afterOpen = substring(lastOpen)
    // Complete link form: [text](url) — has both ]( and a closing ).
    val hasCloser = "](" in afterOpen && afterOpen.lastIndexOf(')') > afterOpen.lastIndexOf("](")
    if (hasCloser) return this
    // Only treat as a broken link if ]( is present (i.e. we got past the label into the url).
    return if ("](" in afterOpen) {
        substring(0, lastOpen).trimEnd()
    } else {
        this
    }
}

/**
 * Normalize curly/smart quotes into CJK corner brackets (直角引号):
 * “ ” → 「 」, ‘ ’ → 『 』. ASCII straight quotes are left untouched since they
 * cannot be distinguished into opening/closing without context. This keeps
 * user-visible Deep Read copy on the 直角引号 system the project standardizes on.
 */
private fun String.normalizeQuotes(): String =
    replace("\u201C", "「") // "
        .replace("\u201D", "」") // "
        .replace("\u2018", "『") // '
        .replace("\u2019", "』") // '

/**
 * Like [String.take] but does not split a UTF-16 surrogate pair. A dangling
 * high surrogate produced by a naive `take` corrupts the string for any layer
 * that round-trips through UTF-8 encoding (kotlinx.serialization rejects it,
 * WebView replaces it with U+FFFD), so emoji-containing model output ends up
 * blanking the rendered section.
 */
internal fun String.safeTake(n: Int): String {
    if (n <= 0) return ""
    if (length <= n) return this
    val cut = if (this[n - 1].isHighSurrogate()) n - 1 else n
    return substring(0, cut)
}

private fun mergeStrings(existing: List<String>, incoming: List<String>, limit: Int): List<String> =
    (existing + incoming)
        .map { it.cleanText(80) }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .take(limit)

private fun mergeReadingLinks(
    existing: List<ReadingLink>,
    incoming: List<ReadingLink>,
    limit: Int,
): List<ReadingLink> =
    (existing + incoming)
        .filter { it.url.isHttpOrHttpsUrl() }
        .distinctBy { it.url.trim().trimEnd('/') }
        .take(limit)

private fun mergeImageAssets(
    existing: List<DeepReadImageAsset>,
    incoming: List<DeepReadImageAsset>,
    limit: Int,
): List<DeepReadImageAsset> =
    (existing + incoming)
        .filter { it.url.isHttpOrHttpsUrl() }
        .distinctBy { it.url.trim().trimEnd('/') }
        .take(limit)

private fun List<DeepReadSource>.toReadingLinks(): List<ReadingLink> =
    asSequence()
        .filter { it.url.isHttpOrHttpsUrl() }
        .map { source ->
            ReadingLink(
                title = source.title.cleanText(120).ifBlank { source.source ?: source.url },
                url = source.url,
                source = source.source,
            )
        }
        .distinctBy { it.url.trim().trimEnd('/') }
        .take(8)
        .toList()

private fun DeepReadImageCandidate.selectionReason(topicTitle: String): String =
    when (confidence) {
        IMAGE_CONFIDENCE_HERO -> "候选图与「$topicTitle」的标题实体或事件词匹配，且未命中 logo/icon 风险。"
        IMAGE_CONFIDENCE_INLINE -> "候选图可作为正文上下文图，但标题相关性不足以做头图。"
        else -> riskFlags.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "图片相关性或质量不足。"
    }

private fun DeepReadOutput.hasOverviewContent(): Boolean =
    summary.trim().length >= OVERVIEW_SUMMARY_MIN_CHARS

private fun DeepReadOutput.hasNarrativeContent(): Boolean =
    timeline.orEmpty().any { it.event.trim().length >= 20 } ||
        corePoints.orEmpty().any { it.point.trim().length >= 8 || it.supporting.orEmpty().trim().length >= 20 }

private fun DeepReadOutput.hasAnalysisContent(): Boolean =
    listOfNotNull(analysis.coreDispute, analysis.implications).any { it.trim().length >= 20 } ||
        analysis.perspectives.any { it.viewpoint.trim().length >= 20 } ||
        analysis.quotes.any { it.text.trim().length >= 8 }

private fun DeepReadOutput.hasExtendedReadingContent(): Boolean =
    extendedReading.any { it.url.isHttpOrHttpsUrl() }

private fun String.isHttpOrHttpsUrl(): Boolean =
    startsWith("http://") || startsWith("https://")
