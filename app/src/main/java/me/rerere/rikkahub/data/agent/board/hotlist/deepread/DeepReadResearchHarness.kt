package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

private const val STAGE_EVIDENCE_MIN = 4
private const val STAGE_EVIDENCE_MAX = 6
private const val DEFAULT_STAGE_EVIDENCE_TARGET = 5
private const val CLAIM_SUMMARY_LIMIT = 180
private const val EVIDENCE_EXCERPT_LIMIT = 780
private const val PLANNING_EXCERPT_LIMIT = 360

class DeepReadResearchHarness {
    fun buildEvidencePack(
        topicTitle: String,
        sources: List<DeepReadSource>,
    ): DeepReadEvidencePack {
        val cards = sources
            .dedupeSources()
            .mapIndexed { index, source -> source.toEvidenceCard(index + 1) }
            .sortedWith(
                compareByDescending<DeepReadEvidenceCard> { DeepReadEvidenceTag.OFFICIAL in it.topicTags }
                    .thenByDescending { it.source.content.length }
                    .thenBy { it.sourceId }
            )
        val stageCards = distributeStageCards(cards)
        return DeepReadEvidencePack(
            topicTitle = topicTitle,
            cards = cards,
            stageCards = stageCards,
        )
    }

    fun fallbackPlan(
        topicTitle: String,
        pack: DeepReadEvidencePack,
    ): DeepReadArticlePlan {
        val stageSourceIds = DeepReadGenerationStage.entries.associate { stage ->
            stage.stageKey() to pack.stageCards[stage].orEmpty().map { it.sourceId }
        }
        val requiredSourceIds = stageSourceIds.values
            .flatten()
            .distinct()
            .take(pack.requiredSourceTarget())
        val stakeholders = pack.cards
            .filter { DeepReadEvidenceTag.STAKEHOLDER in it.topicTags || DeepReadEvidenceTag.OFFICIAL in it.topicTags }
            .map { it.source.source ?: it.titleDomainFallback() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        return DeepReadArticlePlan(
            overviewAngle = "从已核查来源解释「$topicTitle」发生了什么、为什么值得读，以及哪些结论仍需保守表达。",
            narrativeSlots = listOf(
                "背景和直接触发因素",
                "关键进展或时间线",
                "当前状态与后续观察点",
            ),
            analysisQuestions = listOf(
                "核心矛盾是什么，各方到底在争什么？",
                "这件事会影响哪些用户、公司、行业或公共议题？",
                "有哪些反方证据、不确定点或互相矛盾的说法需要降格表达？",
            ),
            stakeholders = stakeholders.ifEmpty { pack.cards.mapNotNull { it.source.source }.distinct().take(6) },
            riskOrUncertainty = listOf(
                "来源之间未互相印证的事实不得写成定论。",
                "没有来源支撑的价格、时间、人物表态、因果关系需要跳过或标注为不确定。",
            ),
            requiredSourceIds = requiredSourceIds,
            stageSourceIds = stageSourceIds,
            coverageChecks = DeepReadCoverageItem.entries.map { it.label },
        )
    }

    fun buildPlanningPrompt(
        topicTitle: String,
        pack: DeepReadEvidencePack,
        playbookMarkdown: String,
    ): String = buildString {
        appendLine("你是 AmberAgent DeepRead 的结构规划器。")
        appendLine("只输出合法 JSON，不要 Markdown、不要代码围栏、不要解释。")
        appendLine("话题：$topicTitle")
        appendLine()
        appendLine("## 本地 Deep Read Playbook（只读摘录）")
        appendLine(playbookMarkdown.take(4_000))
        appendLine()
        appendLine("## 可用证据")
        pack.cards.take(15).forEach { card ->
            appendLine("- ${card.sourceId} | ${card.topicTags.joinToString { it.key }} | ${card.title}")
            appendLine("  url: ${card.source.url}")
            appendLine("  claim_summary: ${card.claimSummary.take(PLANNING_EXCERPT_LIMIT)}")
        }
        appendLine()
        appendLine("## 输出 JSON Schema")
        appendLine(
            """
            {
              "overview_angle": "一两句话说明文章角度",
              "narrative_slots": ["必须覆盖的叙事槽位"],
              "analysis_questions": ["必须回答的分析问题"],
              "stakeholders": ["相关方"],
              "risk_or_uncertainty": ["风险、不确定点或反方证据"],
              "required_source_ids": ["s1"],
              "stage_source_ids": {
                "overview": ["s1","s2","s3","s4"],
                "narrative": ["s2","s5","s6","s7"],
                "analysis": ["s4","s8","s9","s10"],
                "extended_reading": ["s1","s5","s9","s11"]
              },
              "coverage_checks": ["背景","时间线/核心点","利益相关方","争议","影响","反方证据","来源链接"]
            }
            """.trimIndent()
        )
        appendLine()
        appendLine("要求：")
        appendLine("- required_source_ids 尽量覆盖 10-15 条来源；来源不足时覆盖所有可用来源。")
        appendLine("- 每个 stage_source_ids 只放 4-6 条最相关来源。")
        appendLine("- analysis_questions 必须覆盖核心矛盾、影响链条、反方证据或不确定点。")
        appendLine("- 不要创造不存在的 source_id。")
    }

    fun parsePlan(raw: String): DeepReadArticlePlan? {
        val candidates = jsonCandidates(raw)
        return candidates.firstNotNullOfOrNull { candidate ->
            val obj = runCatching { Json.parseToJsonElement(candidate).jsonObject }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            obj.toArticlePlan()
        }
    }

    fun normalizePlan(
        parsed: DeepReadArticlePlan?,
        topicTitle: String,
        pack: DeepReadEvidencePack,
    ): DeepReadArticlePlan {
        val fallback = fallbackPlan(topicTitle, pack)
        val plan = parsed ?: fallback
        val allIds = pack.cards.map { it.sourceId }.toSet()
        val normalizedStageSources = DeepReadGenerationStage.entries.associate { stage ->
            val fallbackIds = fallback.sourceIdsFor(stage)
            val parsedIds = plan.sourceIdsFor(stage)
                .filter { it in allIds }
                .distinct()
                .take(STAGE_EVIDENCE_MAX)
            stage.stageKey() to parsedIds.withFallbackIds(fallbackIds, allIds)
        }
        val unionIds = normalizedStageSources.values.flatten().distinct()
        val requiredIds = (plan.requiredSourceIds + unionIds + fallback.requiredSourceIds)
            .filter { it in allIds }
            .distinct()
            .take(pack.requiredSourceTarget())
        return plan.copy(
            overviewAngle = plan.overviewAngle.ifBlank { fallback.overviewAngle },
            narrativeSlots = plan.narrativeSlots.ifEmpty { fallback.narrativeSlots }.take(6),
            analysisQuestions = plan.analysisQuestions.ifEmpty { fallback.analysisQuestions }.take(8),
            stakeholders = plan.stakeholders.ifEmpty { fallback.stakeholders }.take(8),
            riskOrUncertainty = plan.riskOrUncertainty.ifEmpty { fallback.riskOrUncertainty }.take(8),
            requiredSourceIds = requiredIds.ifEmpty { fallback.requiredSourceIds },
            stageSourceIds = normalizedStageSources,
            coverageChecks = plan.coverageChecks.ifEmpty { fallback.coverageChecks }.take(10),
        )
    }

    fun verifyCoverage(
        output: DeepReadOutput,
        plan: DeepReadArticlePlan,
        pack: DeepReadEvidencePack,
    ): DeepReadCoverageReport {
        val visible = output.visibleArticleText()
        val usedUrls = (output.references + output.extendedReading)
            .map { it.url.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .toSet()
        val usedSourceIds = pack.cards
            .filter { it.source.url in usedUrls }
            .map { it.sourceId }
            .toSet()
        val usedTags = pack.cards
            .filter { it.sourceId in usedSourceIds }
            .flatMap { it.topicTags }
            .toSet()
        val missing = DeepReadCoverageItem.entries.filterNot { item ->
            when (item) {
                DeepReadCoverageItem.BACKGROUND ->
                    DeepReadEvidenceTag.BACKGROUND in usedTags || visible.hasAny(BACKGROUND_WORDS)
                DeepReadCoverageItem.TIMELINE_OR_CORE ->
                    output.timeline.orEmpty().count { it.event.trim().length >= 20 } >= 2 ||
                        output.corePoints.orEmpty().count { it.supporting.orEmpty().trim().length >= 30 } >= 2
                DeepReadCoverageItem.STAKEHOLDERS ->
                    output.keyEntities.size >= 2 ||
                        output.analysis.perspectives.count { it.viewpoint.trim().length >= 24 } >= 2 ||
                        DeepReadEvidenceTag.STAKEHOLDER in usedTags
                DeepReadCoverageItem.CONTROVERSY ->
                    !output.analysis.coreDispute.isNullOrBlank() ||
                        visible.hasAny(CONTROVERSY_WORDS) ||
                        DeepReadEvidenceTag.CONTROVERSY in usedTags
                DeepReadCoverageItem.IMPACT ->
                    output.analysis.implications.orEmpty().trim().length >= 40 ||
                        visible.hasAny(IMPACT_WORDS) ||
                        DeepReadEvidenceTag.IMPACT in usedTags
                DeepReadCoverageItem.COUNTER_EVIDENCE ->
                    visible.hasAny(COUNTER_EVIDENCE_WORDS) ||
                        DeepReadEvidenceTag.COUNTER_EVIDENCE in usedTags
                DeepReadCoverageItem.SOURCE_LINKS ->
                    usedSourceIds.size >= pack.requiredSourceTarget().coerceAtMost(plan.requiredSourceIds.size.coerceAtLeast(1))
            }
        }
        val missingRequiredSourceIds = plan.requiredSourceIds.filter { it !in usedSourceIds }
        val targetStage = missing.firstOrNull()?.targetStage
            ?: if (missingRequiredSourceIds.isNotEmpty()) DeepReadGenerationStage.EXTENDED_READING else null
        return DeepReadCoverageReport(
            missingItems = missing,
            coveredSourceIds = usedSourceIds.sorted(),
            missingRequiredSourceIds = missingRequiredSourceIds,
            targetStage = targetStage,
        )
    }

    private fun distributeStageCards(
        cards: List<DeepReadEvidenceCard>,
    ): Map<DeepReadGenerationStage, List<DeepReadEvidenceCard>> {
        if (cards.isEmpty()) return emptyMap()
        val used = linkedSetOf<String>()
        return DeepReadGenerationStage.entries.associateWith { stage ->
            val selected = cards.pickForStage(stage, used)
            used += selected.map { it.sourceId }
            selected
        }
    }

    private fun List<DeepReadEvidenceCard>.pickForStage(
        stage: DeepReadGenerationStage,
        alreadyUsed: Set<String>,
    ): List<DeepReadEvidenceCard> {
        val target = when {
            size <= STAGE_EVIDENCE_MIN -> size
            size <= STAGE_EVIDENCE_MAX -> size
            else -> DEFAULT_STAGE_EVIDENCE_TARGET
        }
        return sortedByDescending { card -> card.stageScore(stage, alreadyUsed) }
            .take(target)
            .sortedBy { it.sourceId.drop(1).toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun DeepReadEvidenceCard.stageScore(
        stage: DeepReadGenerationStage,
        alreadyUsed: Set<String>,
    ): Int {
        val preferred = stage.preferredTags()
        var score = 0
        score += topicTags.count { it in preferred } * 20
        if (sourceId !in alreadyUsed) score += 16
        if (DeepReadEvidenceTag.OFFICIAL in topicTags) score += 8
        if (DeepReadEvidenceTag.VISUAL in topicTags && stage == DeepReadGenerationStage.EXTENDED_READING) score += 10
        if (source.publishedAt?.isNotBlank() == true) score += 4
        score += (source.content.length / 300).coerceAtMost(8)
        return score
    }

    private fun DeepReadSource.toEvidenceCard(index: Int): DeepReadEvidenceCard {
        val tags = inferTags()
        val body = evidenceText.takeIf { it.isNotBlank() } ?: content
        return DeepReadEvidenceCard(
            sourceId = "s$index",
            source = this,
            title = title.ifBlank { url },
            claimSummary = buildClaimSummary(body),
            evidenceExcerpt = body.cleanInline().take(EVIDENCE_EXCERPT_LIMIT),
            topicTags = tags,
            credibilityHint = credibilityHint(tags),
            freshnessHint = publishedAt?.takeIf { it.isNotBlank() }?.let { "dated:$it" } ?: "undated",
        )
    }

    private fun DeepReadSource.inferTags(): Set<DeepReadEvidenceTag> {
        val text = "${title}\n${source.orEmpty()}\n$content".lowercase()
        val tags = linkedSetOf<DeepReadEvidenceTag>()
        if (text.hasAny(BACKGROUND_WORDS)) tags += DeepReadEvidenceTag.BACKGROUND
        if (text.hasAny(TIMELINE_WORDS) || !publishedAt.isNullOrBlank()) tags += DeepReadEvidenceTag.TIMELINE
        if (text.hasAny(STAKEHOLDER_WORDS)) tags += DeepReadEvidenceTag.STAKEHOLDER
        if (text.hasAny(CONTROVERSY_WORDS)) tags += DeepReadEvidenceTag.CONTROVERSY
        if (text.hasAny(IMPACT_WORDS)) tags += DeepReadEvidenceTag.IMPACT
        if (text.hasAny(COUNTER_EVIDENCE_WORDS)) tags += DeepReadEvidenceTag.COUNTER_EVIDENCE
        if (text.hasAny(OFFICIAL_WORDS) || url.looksOfficial()) tags += DeepReadEvidenceTag.OFFICIAL
        if (images.isNotEmpty() || imageCandidates.any { it.confidence != IMAGE_CONFIDENCE_REJECT }) {
            tags += DeepReadEvidenceTag.VISUAL
        }
        if (tags.isEmpty()) tags += DeepReadEvidenceTag.BACKGROUND
        return tags
    }

    private fun List<DeepReadSource>.dedupeSources(): List<DeepReadSource> =
        distinctBy { source ->
            source.url.trim().lowercase().ifBlank {
                source.title.trim().lowercase().replace(Regex("\\s+"), " ")
            }
        }

    private fun DeepReadSource.buildClaimSummary(body: String): String {
        val firstSentence = body.cleanInline()
            .split(Regex("(?<=[。！？.!?])\\s+|\\n+"))
            .firstOrNull { it.length >= 18 }
            .orEmpty()
        return listOf(title.cleanInline(), firstSentence)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" — ")
            .take(CLAIM_SUMMARY_LIMIT)
    }

    private fun DeepReadSource.credibilityHint(tags: Set<DeepReadEvidenceTag>): String = when {
        DeepReadEvidenceTag.OFFICIAL in tags -> "official_or_primary"
        content.length >= 800 -> "reported_with_body"
        evidenceText.isNotBlank() -> "scraped_excerpt"
        else -> "search_or_seed_summary"
    }

    private fun DeepReadEvidenceCard.titleDomainFallback(): String =
        source.source ?: runCatching { URI(source.url).host?.removePrefix("www.") }.getOrNull() ?: title

    private fun List<String>.withFallbackIds(
        fallbackIds: List<String>,
        allIds: Set<String>,
    ): List<String> {
        val merged = (this + fallbackIds)
            .filter { it in allIds }
            .distinct()
        val minimum = STAGE_EVIDENCE_MIN.coerceAtMost(allIds.size)
        return if (merged.size >= minimum) {
            merged.take(STAGE_EVIDENCE_MAX)
        } else {
            (merged + allIds).distinct().take(STAGE_EVIDENCE_MAX)
        }
    }

    private fun JsonObject.toArticlePlan(): DeepReadArticlePlan =
        DeepReadArticlePlan(
            overviewAngle = string("overview_angle") ?: string("overviewAngle").orEmpty(),
            narrativeSlots = stringList("narrative_slots").ifEmpty { stringList("narrativeSlots") },
            analysisQuestions = stringList("analysis_questions").ifEmpty { stringList("analysisQuestions") },
            stakeholders = stringList("stakeholders"),
            riskOrUncertainty = stringList("risk_or_uncertainty").ifEmpty { stringList("riskOrUncertainty") },
            requiredSourceIds = stringList("required_source_ids").ifEmpty { stringList("requiredSourceIds") },
            stageSourceIds = stageSourceIds(),
            coverageChecks = stringList("coverage_checks").ifEmpty { stringList("coverageChecks") },
        )

    private fun JsonObject.string(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringList(name: String): List<String> =
        runCatching { get(name)?.jsonArray.orEmpty() }.getOrDefault(emptyList())
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { value -> value.isNotBlank() } }
            .take(20)

    private fun JsonObject.stageSourceIds(): Map<String, List<String>> {
        val sourceObj = runCatching {
            get("stage_source_ids")?.jsonObject ?: get("stageSourceIds")?.jsonObject
        }.getOrNull() ?: return emptyMap()
        return sourceObj.mapValues { (_, value) ->
            runCatching { value.jsonArray }.getOrDefault(JsonArray(emptyList()))
                .mapNotNull { element -> element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }
                .take(STAGE_EVIDENCE_MAX)
        }
    }

    private fun jsonCandidates(text: String): List<String> = buildList {
        val trimmed = text.trim()
        if (trimmed.isNotBlank()) add(trimmed)
        CODE_FENCE.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::add)
        }
        addAll(extractBalancedObjects(text))
    }.map { it.cleanupJsonCandidate() }.distinct()

    private fun extractBalancedObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && inString) {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"') {
                inString = !inString
                return@forEachIndexed
            }
            if (inString) return@forEachIndexed
            when (char) {
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        objects += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun String.cleanupJsonCandidate(): String =
        trim()
            .removePrefix("json")
            .trim()
            .removeSurrounding("```")
            .trim()
            .replace(Regex(",\\s*([}\\]])"), "$1")

    companion object {
        private val CODE_FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    }
}

data class DeepReadEvidencePack(
    val topicTitle: String,
    val cards: List<DeepReadEvidenceCard>,
    val stageCards: Map<DeepReadGenerationStage, List<DeepReadEvidenceCard>>,
) {
    fun cardsFor(
        stage: DeepReadGenerationStage,
        plan: DeepReadArticlePlan,
        forceIncludeSourceIds: List<String> = emptyList(),
    ): List<DeepReadEvidenceCard> {
        val byId = cards.associateBy { it.sourceId }
        val forced = forceIncludeSourceIds.mapNotNull { byId[it] }
        val planned = plan.sourceIdsFor(stage).mapNotNull { byId[it] }
        return (forced + planned.ifEmpty { stageCards[stage].orEmpty() })
            .distinctBy { it.sourceId }
            .take(STAGE_EVIDENCE_MAX)
    }

    fun requiredSourceTarget(): Int = when {
        cards.size >= 12 -> 10
        cards.size >= 8 -> 8
        else -> cards.size
    }
}

data class DeepReadEvidenceCard(
    val sourceId: String,
    val source: DeepReadSource,
    val title: String,
    val claimSummary: String,
    val evidenceExcerpt: String,
    val topicTags: Set<DeepReadEvidenceTag>,
    val credibilityHint: String,
    val freshnessHint: String,
)

enum class DeepReadEvidenceTag(val key: String, val label: String) {
    BACKGROUND("background", "背景"),
    TIMELINE("timeline", "时间线"),
    STAKEHOLDER("stakeholder", "利益相关方"),
    CONTROVERSY("controversy", "争议"),
    IMPACT("impact", "影响"),
    COUNTER_EVIDENCE("counter_evidence", "反方证据"),
    OFFICIAL("official", "官方/一手来源"),
    VISUAL("visual", "图片/视觉证据"),
}

@Serializable
data class DeepReadArticlePlan(
    @SerialName("overview_angle")
    val overviewAngle: String = "",
    @SerialName("narrative_slots")
    val narrativeSlots: List<String> = emptyList(),
    @SerialName("analysis_questions")
    val analysisQuestions: List<String> = emptyList(),
    val stakeholders: List<String> = emptyList(),
    @SerialName("risk_or_uncertainty")
    val riskOrUncertainty: List<String> = emptyList(),
    @SerialName("required_source_ids")
    val requiredSourceIds: List<String> = emptyList(),
    @SerialName("stage_source_ids")
    val stageSourceIds: Map<String, List<String>> = emptyMap(),
    @SerialName("coverage_checks")
    val coverageChecks: List<String> = emptyList(),
) {
    fun sourceIdsFor(stage: DeepReadGenerationStage): List<String> {
        val keys = listOf(stage.stageKey(), stage.name.lowercase(), stage.name)
        return keys.firstNotNullOfOrNull { key -> stageSourceIds[key]?.takeIf { it.isNotEmpty() } }
            .orEmpty()
    }
}

data class DeepReadCoverageReport(
    val missingItems: List<DeepReadCoverageItem>,
    val coveredSourceIds: List<String>,
    val missingRequiredSourceIds: List<String>,
    val targetStage: DeepReadGenerationStage?,
) {
    val needsSupplement: Boolean =
        missingItems.isNotEmpty() || missingRequiredSourceIds.isNotEmpty()

    fun promptSummary(): String = buildString {
        if (missingItems.isNotEmpty()) {
            append("缺少：")
            append(missingItems.joinToString("、") { it.label })
        }
        if (missingRequiredSourceIds.isNotEmpty()) {
            if (isNotEmpty()) append("；")
            append("未使用来源：")
            append(missingRequiredSourceIds.take(8).joinToString("、"))
        }
    }
}

enum class DeepReadCoverageItem(
    val label: String,
    val targetStage: DeepReadGenerationStage,
) {
    BACKGROUND("背景", DeepReadGenerationStage.NARRATIVE),
    TIMELINE_OR_CORE("时间线/核心点", DeepReadGenerationStage.NARRATIVE),
    STAKEHOLDERS("利益相关方", DeepReadGenerationStage.ANALYSIS),
    CONTROVERSY("争议", DeepReadGenerationStage.ANALYSIS),
    IMPACT("影响", DeepReadGenerationStage.ANALYSIS),
    COUNTER_EVIDENCE("反方证据", DeepReadGenerationStage.ANALYSIS),
    SOURCE_LINKS("来源链接", DeepReadGenerationStage.EXTENDED_READING),
}

fun DeepReadGenerationStage.stageKey(): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> "overview"
    DeepReadGenerationStage.NARRATIVE -> "narrative"
    DeepReadGenerationStage.ANALYSIS -> "analysis"
    DeepReadGenerationStage.EXTENDED_READING -> "extended_reading"
}

private fun DeepReadGenerationStage.preferredTags(): Set<DeepReadEvidenceTag> = when (this) {
    DeepReadGenerationStage.OVERVIEW -> setOf(
        DeepReadEvidenceTag.OFFICIAL,
        DeepReadEvidenceTag.BACKGROUND,
        DeepReadEvidenceTag.IMPACT,
        DeepReadEvidenceTag.TIMELINE,
    )
    DeepReadGenerationStage.NARRATIVE -> setOf(
        DeepReadEvidenceTag.TIMELINE,
        DeepReadEvidenceTag.BACKGROUND,
        DeepReadEvidenceTag.OFFICIAL,
    )
    DeepReadGenerationStage.ANALYSIS -> setOf(
        DeepReadEvidenceTag.CONTROVERSY,
        DeepReadEvidenceTag.IMPACT,
        DeepReadEvidenceTag.STAKEHOLDER,
        DeepReadEvidenceTag.COUNTER_EVIDENCE,
        DeepReadEvidenceTag.OFFICIAL,
    )
    DeepReadGenerationStage.EXTENDED_READING -> setOf(
        DeepReadEvidenceTag.VISUAL,
        DeepReadEvidenceTag.OFFICIAL,
        DeepReadEvidenceTag.BACKGROUND,
        DeepReadEvidenceTag.IMPACT,
        DeepReadEvidenceTag.COUNTER_EVIDENCE,
    )
}

private fun DeepReadOutput.visibleArticleText(): String = buildString {
    append(summary).append('\n')
    keyEntities.forEach { append(it).append('\n') }
    timeline.orEmpty().forEach { append(it.date).append(' ').append(it.event).append('\n') }
    corePoints.orEmpty().forEach { append(it.point).append(' ').append(it.supporting.orEmpty()).append('\n') }
    append(analysis.coreDispute.orEmpty()).append('\n')
    analysis.perspectives.forEach { append(it.holder.orEmpty()).append(' ').append(it.viewpoint).append('\n') }
    append(analysis.implications.orEmpty()).append('\n')
    analysis.quotes.forEach { append(it.attribution.orEmpty()).append(' ').append(it.text).append('\n') }
}

private fun String.cleanInline(): String =
    replace(Regex("\\s+"), " ").trim()

private fun String.hasAny(words: Set<String>): Boolean {
    val lower = lowercase()
    return words.any { lower.contains(it.lowercase()) }
}

private fun String.looksOfficial(): Boolean {
    val host = runCatching { URI(this).host?.removePrefix("www.")?.lowercase() }.getOrNull() ?: return false
    return host.endsWith(".gov") ||
        host.endsWith(".gov.cn") ||
        host.contains("official") ||
        host.contains("blog.google") ||
        host.contains("openai.com") ||
        host.contains("deepmind.google")
}

private val BACKGROUND_WORDS = setOf(
    "背景", "起因", "前因", "后果", "来龙去脉", "回顾", "context", "background", "why it matters",
)

private val TIMELINE_WORDS = setOf(
    "时间线", "进展", "最新", "宣布", "发布", "发生", "timeline", "latest", "announced", "released",
)

private val STAKEHOLDER_WORDS = setOf(
    "官方", "声明", "回应", "公司", "机构", "监管", "用户", "开发者", "投资者", "专家", "分析师",
    "stakeholder", "statement", "response", "regulator",
)

private val CONTROVERSY_WORDS = setOf(
    "争议", "分歧", "质疑", "批评", "风波", "调查", "诉讼", "风险", "担忧", "dispute", "controversy",
    "critic", "lawsuit", "probe", "concern",
)

private val IMPACT_WORDS = setOf(
    "影响", "意味着", "后续", "市场", "价格", "成本", "生态", "行业", "用户", "implication", "impact",
    "effect", "market", "pricing",
)

private val COUNTER_EVIDENCE_WORDS = setOf(
    "反方", "但", "不过", "尚未", "未证实", "不确定", "否认", "辟谣", "不实", "存疑", "however",
    "uncertain", "unconfirmed", "denied", "refuted", "not true",
)

private val OFFICIAL_WORDS = setOf(
    "官方", "公告", "通报", "声明", "发布会", "press release", "official", "statement", "announcement",
)
