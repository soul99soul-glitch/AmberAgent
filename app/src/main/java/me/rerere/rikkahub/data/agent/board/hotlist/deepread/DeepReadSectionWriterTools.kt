package me.rerere.rikkahub.data.agent.board.hotlist.deepread

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
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val OVERVIEW_SUMMARY_STORAGE_MAX_CHARS = 1_200
private const val MAX_DIAGRAM_NODES = 6
private const val MAX_LINEAR_DIAGRAM_EDGES = 5
private const val MAX_RELATION_DIAGRAM_EDGES = 6
private const val DIAGRAM_TITLE_MAX_CHARS = 64
private const val DIAGRAM_NODE_LABEL_MAX_CHARS = 34
private const val DIAGRAM_NODE_NOTE_MAX_CHARS = 96
private const val DIAGRAM_NODE_GROUP_MAX_CHARS = 40
private const val DIAGRAM_EDGE_LABEL_MAX_CHARS = 42

class DeepReadSectionWriterTools(
    private val repository: HotListRepository,
    private val topicId: String,
    private val topicTitle: String,
    private val imageCandidates: List<DeepReadImageCandidate> = emptyList(),
    private val isEvidenceUrlAllowed: (String) -> Boolean = { true },
    private val evidenceContains: (String, String) -> Boolean = { _, _ -> true },
    private val allowTitleFallback: Boolean = true,
) {
    private val _writeCount = AtomicInteger(0)
    private val _requiredWriteCount = AtomicInteger(0)
    private val _verificationCount = AtomicInteger(0)
    private val _verificationAttemptCount = AtomicInteger(0)
    private val lastVerificationFailure = AtomicReference<String?>(null)
    private val verifiedWriteCount = AtomicInteger(-1)
    private val writeMutex = Mutex()
    val writeCount: Int get() = _writeCount.get()
    val requiredWriteCount: Int get() = _requiredWriteCount.get()
    val verificationCount: Int get() = _verificationCount.get()
    val verificationAttemptCount: Int get() = _verificationAttemptCount.get()
    val lastVerificationFailureReason: String? get() = lastVerificationFailure.get()
    val hasFreshVerification: Boolean
        get() = verificationCount > 0 && verifiedWriteCount.get() == writeCount

    fun tools(stages: Set<DeepReadGenerationStage>? = null): List<Tool> = buildList {
        if (stages == null || DeepReadGenerationStage.OVERVIEW in stages) add(overviewTool())
        if (stages == null || DeepReadGenerationStage.NARRATIVE in stages) add(narrativeTool())
        if (stages == null || DeepReadGenerationStage.ANALYSIS in stages) add(analysisTool())
        if (stages == null || DeepReadGenerationStage.EXTENDED_READING in stages) add(extendedReadingTool())
        add(visualsTool())
        add(diagramTool())
        add(verificationTool())
        add(finishTool())
    }

    fun tools(): List<Tool> = tools(stages = null)

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

    suspend fun markVerificationFailed(message: String): DeepReadOutput =
        update { current ->
            current.copy(
                generationPhase = DeepReadGenerationPhase.IDLE,
                verificationState = DeepReadSectionState(
                    status = DeepReadSectionStatus.FAILED,
                    errorMessage = message.safeTake(220),
                ),
                generationComplete = false,
            )
        }

    suspend fun markVerificationRunning(): DeepReadOutput =
        update { current ->
            current.copy(
                generationPhase = DeepReadGenerationPhase.VERIFYING,
                verificationState = DeepReadSectionState(DeepReadSectionStatus.RUNNING),
                generationComplete = false,
            )
        }

    suspend fun writeFallbackSection(
        stage: DeepReadGenerationStage,
        assistantText: String,
        sources: List<DeepReadSource>,
        allowReadyRewrite: Boolean = false,
    ): DeepReadOutput =
        update { current ->
            if (!allowReadyRewrite && current.statusOf(stage) == DeepReadSectionStatus.READY) return@update current
            val links = sources.toReadingLinks()
            val fallbackText = assistantText.fallbackBody(stage, sources, topicTitle)
            val next = when (stage) {
                DeepReadGenerationStage.OVERVIEW -> current.copy(
                    summary = fallbackText.cleanText(OVERVIEW_SUMMARY_STORAGE_MAX_CHARS).takeIf { it.isNotBlank() }
                        ?: current.summary,
                    references = mergeReadingLinks(current.references, links, limit = 12),
                )

                DeepReadGenerationStage.NARRATIVE -> {
                    val timeline = sources.toFallbackTimeline().takeIf { it.isNotEmpty() }
                    current.copy(
                        timeline = timeline ?: current.timeline,
                        corePoints = fallbackText.toFallbackCorePoints().takeIf { it.isNotEmpty() } ?: current.corePoints,
                        references = mergeReadingLinks(current.references, links, limit = 12),
                    )
                }

                DeepReadGenerationStage.ANALYSIS -> current.copy(
                    analysis = current.analysis.copy(
                        implications = fallbackText.cleanText(1_600).takeIf { it.isNotBlank() }
                            ?: current.analysis.implications,
                    ),
                    references = mergeReadingLinks(current.references, links, limit = 12),
                )

                DeepReadGenerationStage.EXTENDED_READING -> current.copy(
                    extendedReading = mergeReadingLinks(current.extendedReading, links, limit = 10),
                    references = mergeReadingLinks(current.references, links, limit = 12),
                )
            }
            if (next.statusReadyFor(stage)) {
                markRequiredWrite()
                next
                    .withSectionStatus(stage, DeepReadSectionStatus.READY)
                    .withSectionQuality(stage, DeepReadSectionQuality.BASIC)
            } else {
                current
            }
        }

    suspend fun currentOutput(): DeepReadOutput =
        repository.getFreshDeepRead(
            topicId = topicId,
            title = topicTitle.takeIf { allowTitleFallback },
        )
            ?.withInferredSectionStates()
            ?: DeepReadOutput()

    private fun overviewTool() = Tool(
        name = "deep_read_write_overview",
        description = "Internal Deep Read writer. Write the verified overview section after using search_web/scrape_web. UI only renders content written through this tool.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("topic_type", stringProp("event/opinion/product/person."))
                    put("summary", stringProp("Chinese editorial overview, around 120-250 Chinese characters; keep sentences complete."))
                    put("key_entities", stringArrayProp("Key people, companies, products, places, or institutions."))
                    put("references", readingLinksProp("Sources used in this section."))
                },
                required = listOf("summary"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val references = obj.readingLinks("references")
                val next = current.copy(
                    topicType = obj.string("topic_type")?.safeTake(32) ?: current.topicType,
                    summary = obj.string("summary")?.cleanText(OVERVIEW_SUMMARY_STORAGE_MAX_CHARS) ?: current.summary,
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
                ok("overview", output)
            } else {
                missing("overview", "summary")
            }
        },
    )

    private fun narrativeTool() = Tool(
        name = "deep_read_write_narrative",
        description = "Internal Deep Read writer. Write timeline/story narrative data. Use timeline for events, core_points for opinion/product/person topics.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("timeline", timelineProp())
                    put("core_points", corePointsProp())
                    put("references", readingLinksProp("Sources used in this section."))
                },
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val next = current.copy(
                    timeline = obj.timeline().takeIf { it.isNotEmpty() } ?: current.timeline,
                    corePoints = obj.corePoints().takeIf { it.isNotEmpty() } ?: current.corePoints,
                    references = mergeReadingLinks(current.references, obj.readingLinks("references"), limit = 12),
                )
                if (!next.hasNarrativeContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.NARRATIVE, DeepReadSectionQuality.STANDARD)
            }
            if (output.statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.READY) {
                ok("narrative", output)
            } else {
                missing("narrative", "timeline or core_points")
            }
        },
    )

    private fun analysisTool() = Tool(
        name = "deep_read_write_analysis",
        description = "Internal Deep Read writer. Write the deep analysis section after reasoning over verified sources.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("core_dispute", stringProp("Central tension or core dispute."))
                    put("perspectives", perspectivesProp())
                    put("implications", stringProp("Impact analysis in Chinese."))
                    put("quotes", quotesProp())
                    put("references", readingLinksProp("Sources used in this section."))
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

    private fun extendedReadingTool() = Tool(
        name = "deep_read_write_extended_reading",
        description = "Internal Deep Read writer. Write source-backed extended reading links and optional real image assets.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("links", readingLinksProp("Recommended source links."))
                    put("image_assets", imageAssetsProp())
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

    private fun visualsTool() = Tool(
        name = "deep_read_write_visuals",
        description = "Internal Deep Read visual selector. Select hero/inline images only from the pre-fetched candidate pool; never submit arbitrary URLs.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("hero_image_url", stringProp("Optional candidate image URL. Must have hero confidence in the candidate pool."))
                    put("hero_caption", stringProp("Chinese caption for selected hero image."))
                    put("hero_reason", stringProp("Why this candidate matches the title and is not a logo/icon."))
                    put("image_assets", imageAssetsProp())
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

    private fun diagramTool() = Tool(
        name = "deep_read_write_diagram",
        description = "Internal Deep Read diagram writer. Submit only a compact structured diagram spec; raw SVG/HTML/JS/external resources are forbidden. Use 3-6 short nodes and keep flow/causal diagrams to the main chain.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", stringProp("causal_chain/process_flow/stakeholder_map/system_structure/comparison_matrix."))
                    put("title", stringProp("Short Chinese title."))
                    put("reason", stringProp("Why this topic benefits from a diagram."))
                    put("nodes", diagramNodesProp())
                    put("edges", diagramEdgesProp())
                    put("caption", stringProp("Optional Chinese caption."))
                },
                required = listOf("type", "title", "nodes"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val diagram = obj.diagram()
            val output = update { current ->
                if (diagram == null) return@update current
                markVisibleWrite()
                current.copy(diagram = diagram)
            }
            if (diagram != null) ok("diagram", output) else missing("diagram", "type, title, nodes")
        },
    )

    private fun verificationTool() = Tool(
        name = "deep_read_verify_claims",
        description = "Internal Deep Read verifier. Call before deep_read_finish after checking the most important claims against search_web/scrape_web evidence.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("overall", stringProp("passed/has_uncertain/has_refuted."))
                    put("corrections_applied", buildJsonObject { put("type", "boolean") })
                    put("checked_claims", verifiedClaimsProp())
                },
                required = listOf("overall", "checked_claims"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            _verificationAttemptCount.incrementAndGet()
            val visibleText = currentOutput().verificationVisibleText()
            val gate = input.objectOrEmpty().verificationGate(
                visibleText = visibleText,
                isEvidenceUrlAllowed = isEvidenceUrlAllowed,
                evidenceContains = evidenceContains,
            )
            if (gate.accepted) {
                _verificationCount.incrementAndGet()
                verifiedWriteCount.set(writeCount)
                lastVerificationFailure.set(null)
                update { current ->
                    current.copy(
                        verificationState = DeepReadSectionState(DeepReadSectionStatus.READY),
                        generationComplete = false,
                    )
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "ok")
                            put("section", "verification")
                        }.toString()
                    )
                )
            } else {
                lastVerificationFailure.set(gate.reason)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "verification_rejected")
                            put("section", "verification")
                            put("reason", gate.reason)
                        }.toString()
                    )
                )
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
                    generationPhase = if (current.sectionsReady() && hasFreshVerification) {
                        DeepReadGenerationPhase.COMPLETE
                    } else {
                        current.generationPhase
                    },
                    generationComplete = current.sectionsReady() && hasFreshVerification,
                    verificationState = if (hasFreshVerification) {
                        DeepReadSectionState(DeepReadSectionStatus.READY)
                    } else {
                        current.verificationState
                    },
                )
            }
            val missing = DeepReadGenerationStage.entries.filter { output.statusOf(it) != DeepReadSectionStatus.READY }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", if (missing.isEmpty() && hasFreshVerification) "complete" else "missing_sections")
                        put("missing", buildJsonArray { missing.forEach { add(JsonPrimitive(it.name.lowercase())) } })
                        put("verification_ready", hasFreshVerification)
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
        repository.saveDeepRead(topicId, topicTitle, next)
        next
    }

    private fun ok(section: String, output: DeepReadOutput): List<UIMessagePart> =
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "ok")
                    put("section", section)
                    put("generation_complete", output.isComplete())
                }.toString()
            )
        )

    private fun missing(section: String, required: String): List<UIMessagePart> =
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "missing_required_content")
                    put("section", section)
                    put("required", required)
                }.toString()
            )
        )
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

private fun readingLinksProp(description: String) = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("title", stringProp("Chinese title."))
            put("url", stringProp("Source URL."))
            put("source", stringProp("Source name/domain."))
        })
        put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("title"), kotlinx.serialization.json.JsonPrimitive("url"))))
    })
}

private fun timelineProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("date", stringProp("Date or time label."))
            put("event", stringProp("Event narrative in Chinese."))
            put("is_highlight", buildJsonObject { put("type", "boolean") })
            put("image_url", stringProp("Optional real source image URL."))
            put("image_caption", stringProp("Optional Chinese image caption."))
        })
    })
}

private fun corePointsProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("point", stringProp("Point title in Chinese."))
            put("supporting", stringProp("Supporting detail in Chinese."))
            put("image_url", stringProp("Optional real source image URL."))
            put("image_caption", stringProp("Optional Chinese image caption."))
        })
    })
}

private fun perspectivesProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("holder", stringProp("Person, organization, side, or market group."))
            put("viewpoint", stringProp("Viewpoint in Chinese."))
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

private fun imageAssetsProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("url", stringProp("Real source/search image URL."))
            put("caption", stringProp("Chinese caption."))
            put("source", stringProp("Source name/domain."))
            put("quality_hint", stringProp("hero/inline/context/chart/etc."))
            put("selection_reason", stringProp("Why this candidate is useful for the article."))
        })
    })
}

private fun diagramNodesProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("id", stringProp("Stable short id, e.g. n1."))
            put("label", stringProp("Short Chinese node label, one phrase."))
            put("note", stringProp("Optional Chinese detail, one compact sentence."))
            put("group", stringProp("Optional group/lane label."))
        })
        put("required", JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("label"))))
    })
}

private fun diagramEdgesProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("from", stringProp("Source node id."))
            put("to", stringProp("Target node id."))
            put("label", stringProp("Optional short Chinese edge label."))
        })
        put("required", JsonArray(listOf(JsonPrimitive("from"), JsonPrimitive("to"))))
    })
}

private fun verifiedClaimsProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("claim", stringProp("Core claim checked."))
            put("visible_excerpt", stringProp("Exact visible excerpt from the current Deep Read draft that this claim verifies."))
            put("status", stringProp("verified/uncertain/refuted."))
            put("note", stringProp("Chinese verification note."))
            put("evidence_excerpt", stringProp("Exact excerpt from one evidence URL that supports or refutes this claim."))
            put("evidence_urls", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            })
        })
    })
}

private data class VerificationGate(
    val accepted: Boolean,
    val reason: String,
)

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

private fun List<DeepReadDiagramEdge>.normalizedDiagramEdges(
    type: String,
    nodeIds: List<String>,
): List<DeepReadDiagramEdge> {
    val nodeIndex = nodeIds.withIndex().associate { it.value to it.index }
    val unique = distinctBy { "${it.from}->${it.to}" }
    if (type == "process_flow" || type == "causal_chain") {
        return unique
            .filter { edge ->
                val fromIndex = nodeIndex[edge.from] ?: return@filter false
                val toIndex = nodeIndex[edge.to] ?: return@filter false
                toIndex - fromIndex == 1
            }
            .take(MAX_LINEAR_DIAGRAM_EDGES)
    }
    return unique.take(MAX_RELATION_DIAGRAM_EDGES)
}

private fun JsonObject.verificationGate(
    visibleText: String,
    isEvidenceUrlAllowed: (String) -> Boolean,
    evidenceContains: (String, String) -> Boolean,
): VerificationGate {
    val overall = string("overall")?.lowercase()
    if (overall !in setOf("passed", "has_uncertain", "has_refuted")) {
        return VerificationGate(false, "overall must be passed, has_uncertain, or has_refuted")
    }
    if (overall == "has_refuted") {
        return VerificationGate(false, "refuted claims must be corrected and re-verified before finish")
    }

    val claimElements = array("checked_claims")
    if (claimElements.size < 2) {
        return VerificationGate(false, "checked_claims must include at least two concrete checked claims")
    }
    val claims = mutableListOf<VerifiedClaimGate>()
    claimElements.forEachIndexed { index, element ->
        val claim = runCatching { element.jsonObject }.getOrNull()
            ?: return VerificationGate(false, "checked_claims[$index] must be an object")
        val text = claim.string("claim")?.cleanText(240).orEmpty()
        val visibleExcerpt = claim.string("visible_excerpt")?.cleanText(420).orEmpty()
        val status = claim.string("status")?.lowercase().orEmpty()
        val note = claim.string("note")?.cleanText(240).orEmpty()
        val evidenceExcerpt = claim.string("evidence_excerpt")?.cleanText(700).orEmpty()
        val evidenceUrls = claim.array("evidence_urls")
            .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        if (text.length < 8 || status !in setOf("verified", "uncertain", "refuted") || note.length < 4) {
            return VerificationGate(
                false,
                "checked_claims[$index] must include claim, status, and note",
            )
        } else if (evidenceUrls.isNotEmpty() && evidenceExcerpt.length < 8) {
            return VerificationGate(false, "evidence-backed claims must include evidence_excerpt")
        } else {
            val unknownEvidence = evidenceUrls.firstOrNull { !isEvidenceUrlAllowed(it) }
            if (unknownEvidence != null) {
                return VerificationGate(
                    false,
                    "evidence_urls must come from pre-fetched or actually visited sources: $unknownEvidence",
                )
            }
            if (evidenceUrls.isNotEmpty() && evidenceUrls.none { evidenceContains(it, evidenceExcerpt) }) {
                return VerificationGate(false, "evidence_excerpt must appear in at least one evidence URL")
            }
            if (visibleExcerpt.length < 8) {
                return VerificationGate(false, "checked_claims[$index] must include visible_excerpt from the draft")
            }
            if (!visibleText.containsLoose(visibleExcerpt)) {
                return VerificationGate(false, "visible_excerpt must appear in the current draft")
            }
            claims += VerifiedClaimGate(status = status, evidenceUrls = evidenceUrls)
        }
    }
    if (claims.any { it.status == "refuted" }) {
        return VerificationGate(false, "refuted claims require correction and another verification pass")
    }
    if (claims.count { it.evidenceUrls.isNotEmpty() } < 2) {
        return VerificationGate(false, "checked_claims must include at least two evidence-backed claims")
    }
    val hasUncertain = overall == "has_uncertain" || claims.any { it.status == "uncertain" }
    if (hasUncertain && boolean("corrections_applied") != true) {
        return VerificationGate(false, "uncertain claims must be caveated or removed before finish")
    }
    return VerificationGate(true, "accepted")
}

private data class VerifiedClaimGate(
    val status: String,
    val evidenceUrls: List<String>,
)

private fun DeepReadOutput.verificationVisibleText(): String = buildString {
    appendLine(summary)
    appendLine(keyEntities.joinToString(" "))
    timeline.orEmpty().forEach { event ->
        appendLine(event.date)
        appendLine(event.event)
        appendLine(event.imageCaption.orEmpty())
    }
    corePoints.orEmpty().forEach { point ->
        appendLine(point.point)
        appendLine(point.supporting.orEmpty())
        appendLine(point.imageCaption.orEmpty())
    }
    appendLine(analysis.coreDispute.orEmpty())
    analysis.perspectives.forEach { perspective ->
        appendLine(perspective.holder.orEmpty())
        appendLine(perspective.viewpoint)
    }
    appendLine(analysis.implications.orEmpty())
    analysis.quotes.forEach { quote ->
        appendLine(quote.text)
        appendLine(quote.attribution.orEmpty())
    }
    appendLine(heroCaption.orEmpty())
    imageAssets.forEach { asset -> appendLine(asset.caption.orEmpty()) }
    diagram?.let { diagram ->
        appendLine(diagram.title)
        appendLine(diagram.reason.orEmpty())
        diagram.nodes.forEach { node ->
            appendLine(node.label)
            appendLine(node.note.orEmpty())
            appendLine(node.group.orEmpty())
        }
        diagram.edges.forEach { edge -> appendLine(edge.label.orEmpty()) }
        appendLine(diagram.caption.orEmpty())
    }
    extendedReading.forEach { link -> appendLine("${link.title} ${link.source.orEmpty()} ${link.url}") }
    references.forEach { link -> appendLine("${link.title} ${link.source.orEmpty()} ${link.url}") }
}

private fun String.containsLoose(needle: String): Boolean =
    normalizeForVerification().contains(needle.normalizeForVerification())

private fun String.normalizeForVerification(): String =
    replace(Regex("\\s+"), " ").trim()

private fun String.cleanText(max: Int): String =
    replace(Regex("\\s+"), " ").trim().safeTake(max)

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

private fun List<DeepReadSource>.toFallbackTimeline(): List<TimelineEvent> =
    asSequence()
        .filter { it.title.isNotBlank() || it.content.isNotBlank() }
        .take(4)
        .mapIndexed { index, source ->
            val date = source.publishedAt?.takeIf { it.isNotBlank() }
                ?: source.source?.takeIf { it.isNotBlank() }
                ?: "来源 ${index + 1}"
            val excerpt = source.content
                .fallbackSentences(limit = 1)
                .firstOrNull()
                .orEmpty()
            TimelineEvent(
                date = date.cleanText(40),
                event = listOf(source.title, excerpt)
                    .filter { it.isNotBlank() }
                    .joinToString("：")
                    .cleanText(260),
            )
        }
        .filter { it.event.isNotBlank() }
        .toList()

private fun String.fallbackBody(
    stage: DeepReadGenerationStage,
    sources: List<DeepReadSource>,
    topicTitle: String,
): String {
    val cleaned = cleanAssistantFallback(stage.fallbackTextMax())
    if (cleaned.isUsefulFallbackText()) return cleaned
    val sourceText = sources.asSequence()
        .mapNotNull { source ->
            source.content
                .fallbackSentences(limit = 2)
                .joinToString(" ")
                .ifBlank { null }
        }
        .firstOrNull()
    if (!sourceText.isNullOrBlank()) return sourceText.cleanText(stage.fallbackTextMax())
    return when (stage) {
        DeepReadGenerationStage.OVERVIEW ->
            "围绕「$topicTitle」，当前来源已经提供了可继续阅读的基础事实，但模型未按约定写入结构化概览。"

        DeepReadGenerationStage.NARRATIVE ->
            "围绕「$topicTitle」，现有来源显示事件已有多个公开节点，后续应优先沿时间线补齐关键进展。"

        DeepReadGenerationStage.ANALYSIS ->
            "围绕「$topicTitle」，核心分析应聚焦已公开事实、各方立场和可能影响，避免把未证实推断写成定论。"

        DeepReadGenerationStage.EXTENDED_READING -> ""
    }
}

private fun String.cleanAssistantFallback(max: Int): String {
    val withoutFences = replace(Regex("(?s)```.*?```"), " ")
    return withoutFences
        .lines()
        .map { it.trim().trimStart('#', '-', '*', ' ') }
        .filterNot { line ->
            line.contains("deep_read_") ||
                (line.contains("调用") && line.contains("工具")) ||
                line.equals("好的", ignoreCase = true)
        }
        .joinToString(" ")
        .cleanText(max)
}

private fun String.isUsefulFallbackText(): Boolean {
    val cjk = count { it in '\u4e00'..'\u9fff' }
    return length >= 24 && cjk >= 12
}

private fun String.fallbackSentences(limit: Int): List<String> =
    split(Regex("[。！？!?]\\s*|\\n+"))
        .map { it.cleanText(220) }
        .filter { it.isNotBlank() }
        .take(limit)

private fun String.toFallbackCorePoints(): List<CorePoint> =
    fallbackSentences(limit = 4)
        .map { sentence ->
            CorePoint(
                point = sentence.safeTake(42),
                supporting = sentence.takeIf { it.length > 42 }?.cleanText(240),
            )
        }

private fun DeepReadGenerationStage.fallbackTextMax(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> OVERVIEW_SUMMARY_STORAGE_MAX_CHARS
    DeepReadGenerationStage.NARRATIVE -> 1_200
    DeepReadGenerationStage.ANALYSIS -> 1_600
    DeepReadGenerationStage.EXTENDED_READING -> 600
}

private fun DeepReadOutput.statusReadyFor(stage: DeepReadGenerationStage): Boolean = when (stage) {
    DeepReadGenerationStage.OVERVIEW -> hasOverviewContent()
    DeepReadGenerationStage.NARRATIVE -> hasNarrativeContent()
    DeepReadGenerationStage.ANALYSIS -> hasAnalysisContent()
    DeepReadGenerationStage.EXTENDED_READING -> hasExtendedReadingContent()
}

private fun DeepReadImageCandidate.selectionReason(topicTitle: String): String =
    when (confidence) {
        IMAGE_CONFIDENCE_HERO -> "候选图与「$topicTitle」的标题实体或事件词匹配，且未命中 logo/icon 风险。"
        IMAGE_CONFIDENCE_INLINE -> "候选图可作为正文上下文图，但标题相关性不足以做头图。"
        else -> riskFlags.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "图片相关性或质量不足。"
    }

private fun DeepReadOutput.hasOverviewContent(): Boolean =
    summary.trim().length >= 40

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
