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

class DeepReadSectionWriterTools(
    private val repository: HotListRepository,
    private val topicId: String,
    private val topicTitle: String,
    private val allowTitleFallback: Boolean = true,
) {
    private val _writeCount = AtomicInteger(0)
    private val _verificationCount = AtomicInteger(0)
    private val verifiedWriteCount = AtomicInteger(-1)
    private val writeMutex = Mutex()
    val writeCount: Int get() = _writeCount.get()
    val verificationCount: Int get() = _verificationCount.get()
    val hasFreshVerification: Boolean
        get() = verificationCount > 0 && verifiedWriteCount.get() == writeCount

    fun tools(stages: Set<DeepReadGenerationStage>? = null): List<Tool> = buildList {
        if (stages == null || DeepReadGenerationStage.OVERVIEW in stages) add(overviewTool())
        if (stages == null || DeepReadGenerationStage.NARRATIVE in stages) add(narrativeTool())
        if (stages == null || DeepReadGenerationStage.ANALYSIS in stages) add(analysisTool())
        if (stages == null || DeepReadGenerationStage.EXTENDED_READING in stages) add(extendedReadingTool())
        add(verificationTool())
        add(finishTool())
    }

    fun tools(): List<Tool> = tools(stages = null)

    suspend fun markRunning(stages: Collection<DeepReadGenerationStage>): DeepReadOutput =
        update { current ->
            stages.fold(current) { output, stage ->
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
            val states = current.sectionStates.toMutableMap()
            states[DeepReadGenerationStage.EXTENDED_READING] = DeepReadSectionState(
                status = DeepReadSectionStatus.FAILED,
                errorMessage = message.safeTake(220),
            )
            current.copy(sectionStates = states, generationComplete = false)
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
                    put("summary", stringProp("Chinese editorial overview, 250-700 Chinese characters."))
                    put("key_entities", stringArrayProp("Key people, companies, products, places, or institutions."))
                    put("hero_image_url", stringProp("Optional real source image URL. Use only real source/search image, never generated news scene."))
                    put("hero_caption", stringProp("Optional Chinese caption for the hero image."))
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
                    summary = obj.string("summary")?.cleanText(1_500) ?: current.summary,
                    keyEntities = mergeStrings(current.keyEntities, obj.stringList("key_entities"), limit = 12),
                    heroImageUrl = obj.url("hero_image_url") ?: current.heroImageUrl,
                    heroCaption = obj.string("hero_caption")?.cleanText(180) ?: current.heroCaption,
                    references = mergeReadingLinks(current.references, references, limit = 12),
                )
                if (!next.hasOverviewContent()) return@update current
                _writeCount.incrementAndGet()
                next.withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
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
                _writeCount.incrementAndGet()
                next.withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.READY)
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
                _writeCount.incrementAndGet()
                next.withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.READY)
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
                    imageAssets = mergeImageAssets(current.imageAssets, obj.imageAssets(), limit = 8),
                )
                if (!next.hasExtendedReadingContent()) return@update current
                _writeCount.incrementAndGet()
                next.withSectionStatus(DeepReadGenerationStage.EXTENDED_READING, DeepReadSectionStatus.READY)
            }
            if (output.statusOf(DeepReadGenerationStage.EXTENDED_READING) == DeepReadSectionStatus.READY) {
                ok("extended_reading", output)
            } else {
                missing("extended_reading", "links")
            }
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
            val gate = input.objectOrEmpty().verificationGate()
            if (gate.accepted) {
                _verificationCount.incrementAndGet()
                verifiedWriteCount.set(writeCount)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "ok")
                            put("section", "verification")
                        }.toString()
                    )
                )
            } else {
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
                current.copy(generationComplete = current.sectionsReady() && hasFreshVerification)
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
            put("quality_hint", stringProp("hero/context/chart/etc."))
        })
    })
}

private fun verifiedClaimsProp() = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("claim", stringProp("Core claim checked."))
            put("status", stringProp("verified/uncertain/refuted."))
            put("note", stringProp("Chinese verification note."))
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
        )
    }.take(8)

private fun JsonObject.verificationGate(): VerificationGate {
    val overall = string("overall")?.lowercase()
    if (overall !in setOf("passed", "has_uncertain", "has_refuted")) {
        return VerificationGate(false, "overall must be passed, has_uncertain, or has_refuted")
    }
    if (overall == "has_refuted") {
        return VerificationGate(false, "refuted claims must be corrected and re-verified before finish")
    }

    val claims = objectList("checked_claims").mapNotNull { claim ->
        val text = claim.string("claim")?.cleanText(240).orEmpty()
        val status = claim.string("status")?.lowercase().orEmpty()
        val note = claim.string("note")?.cleanText(240).orEmpty()
        val evidenceUrls = claim.array("evidence_urls")
            .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull?.trim() }.getOrNull() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        if (text.length < 8 || status !in setOf("verified", "uncertain", "refuted") || note.length < 4) {
            null
        } else {
            VerifiedClaimGate(status = status, evidenceUrls = evidenceUrls)
        }
    }
    if (claims.size < 2) {
        return VerificationGate(false, "checked_claims must include at least two concrete checked claims")
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
        .filter { it.url.startsWith("http") }
        .distinctBy { it.url.trim().trimEnd('/') }
        .take(limit)

private fun mergeImageAssets(
    existing: List<DeepReadImageAsset>,
    incoming: List<DeepReadImageAsset>,
    limit: Int,
): List<DeepReadImageAsset> =
    (existing + incoming)
        .filter { it.url.startsWith("http") }
        .distinctBy { it.url.trim().trimEnd('/') }
        .take(limit)

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
    extendedReading.any { it.url.startsWith("http") }
