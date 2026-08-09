package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelChapterPlanRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class NovelQuickStartSuggestionV1(
    val title: String,
    val content: String,
)

/**
 * QuickStart structured suggestions.
 *
 * [characters] is a list (v3 prompt contract: one entry per person).
 * Decoder still accepts a legacy single object for backward compatibility.
 */
@Serializable
data class NovelQuickStartSuggestionsV1(
    val schemaVersion: Int,
    val overview: String,
    val world: NovelQuickStartSuggestionV1,
    val characters: List<NovelQuickStartSuggestionV1>,
    val masterOutline: NovelQuickStartSuggestionV1,
    val writingRequirements: NovelQuickStartSuggestionV1,
)

@Serializable
data class NovelStateEventV1(
    val id: String,
    val kind: String,
    val summary: String,
    val entityReferences: List<String> = emptyList(),
    val evidence: String = "",
)

@Serializable
data class NovelSettingProposalV1(
    val id: String,
    val title: String,
    val content: String,
    val evidence: String = "",
)

@Serializable
data class NovelStateDeltaV1(
    val schemaVersion: Int,
    val stateSummary: String,
    val events: List<NovelStateEventV1> = emptyList(),
    val characterChanges: List<JsonObject> = emptyList(),
    val relationshipChanges: List<JsonObject> = emptyList(),
    val foreshadowingChanges: List<JsonObject> = emptyList(),
    val unresolvedEntityNames: List<String> = emptyList(),
    val branchOutlinePatch: String? = null,
    val settingProposals: List<NovelSettingProposalV1> = emptyList(),
)

@Serializable
data class NovelPolishDriftV1Json(
    val schemaVersion: Int,
    val compatible: Boolean,
    val differences: List<JsonObject> = emptyList(),
)

@Serializable
enum class NovelContinuityIssueCategoryV1 {
    @kotlinx.serialization.SerialName("duplicatedPlot")
    DuplicatedPlot,

    @kotlinx.serialization.SerialName("contradiction")
    Contradiction,

    @kotlinx.serialization.SerialName("identityDrift")
    IdentityDrift,

    @kotlinx.serialization.SerialName("chronology")
    Chronology,

    @kotlinx.serialization.SerialName("statusConflict")
    StatusConflict,

    @kotlinx.serialization.SerialName("other")
    Other,
}

@Serializable
enum class NovelContinuityIssueSeverityV1 {
    @kotlinx.serialization.SerialName("blocking")
    Blocking,

    @kotlinx.serialization.SerialName("major")
    Major,

    @kotlinx.serialization.SerialName("minor")
    Minor,
}

@Serializable
data class NovelContinuityReferenceV1(
    val chapterOrdinal: Int,
    val chapterTitle: String,
    val evidence: String,
)

@Serializable
data class NovelContinuityIssueV1(
    val id: String,
    val category: NovelContinuityIssueCategoryV1,
    val severity: NovelContinuityIssueSeverityV1,
    val summary: String,
    val references: List<NovelContinuityReferenceV1> = emptyList(),
)

@Serializable
data class NovelContinuityAuditV1(
    val schemaVersion: Int,
    val consistent: Boolean,
    val issues: List<NovelContinuityIssueV1> = emptyList(),
)

@Serializable
data class NovelDiscussionArchiveDecisionV1(
    val topic: String,
    val decision: String,
    val relatedMaterialID: String? = null,
)

/**
 * iOS NovelDiscussionArchiveV1 — distill-only structured output (not persisted as-is).
 * User still confirms summary + decisions before ArchiveDiscussion intent.
 */
@Serializable
data class NovelDiscussionArchiveV1(
    val schemaVersion: Int,
    val decisions: List<NovelDiscussionArchiveDecisionV1>,
    val summary: String,
)

/**
 * Whole-chapter candidate review against the branch's confirmed chapter plan.
 *
 * The wire name intentionally remains V1 to match iOS. Schema v2 adds
 * [obviousRepetition]; valid persisted/model v1 payloads decode it as an empty list.
 */
@Serializable
data class NovelChapterPlanAcceptanceV1(
    val schemaVersion: Int,
    val accepted: Boolean,
    val missingMustHappen: List<String>,
    val forbiddenViolations: List<String>,
    val obviousRepetition: List<String> = emptyList(),
    val summary: String,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val LEGACY_SCHEMA_VERSION = 1
    }
}

/** Structured next-chapter contract for multi-chapter ghostwrite auto-planning. */
@Serializable
data class NovelChapterPlanProposalV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val outlinePlacement: String,
    val goalAndConflict: String,
    val mustHappen: List<String>,
    val mustNotHappen: List<String>,
    val endingHook: String,
    val visibleFacts: List<String>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

object NovelStructuredOutputDecoder {
    /** Strict for schemas that must not drift (quick-start shape, polish drift). */
    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /**
     * Lenient for model-produced state deltas: providers often add extra keys.
     * Unknown fields are ignored so default-on state-delta (P0-A) is not silent-failing.
     */
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun decodeQuickStartSuggestions(text: String): NovelQuickStartSuggestionsV1 {
        val cleaned = stripFence(text, strictJson)
        val root = strictJson.parseToJsonElement(cleaned).jsonObject
        val schemaVersion = root.getValue("schemaVersion").jsonPrimitive.content.toInt()
        require(schemaVersion == 1) { "schemaVersion must be 1" }
        val overview = root.getValue("overview").jsonPrimitive.content
        require(overview.isNotBlank()) { "overview must be non-blank" }
        val world = decodeQuickStartItem(root.getValue("world").jsonObject, "world")
        val characters = decodeQuickStartCharacters(root.getValue("characters"))
        val masterOutline = decodeQuickStartItem(root.getValue("masterOutline").jsonObject, "masterOutline")
        val writingRequirements = decodeQuickStartItem(
            root.getValue("writingRequirements").jsonObject,
            "writingRequirements",
        )
        return NovelQuickStartSuggestionsV1(
            schemaVersion = schemaVersion,
            overview = overview,
            world = world,
            characters = characters,
            masterOutline = masterOutline,
            writingRequirements = writingRequirements,
        )
    }

    /**
     * v3: JSON array of {title, content} — one person per entry.
     * Legacy: single object (merged multi-person blob) → one-element list.
     */
    private fun decodeQuickStartCharacters(element: kotlinx.serialization.json.JsonElement): List<NovelQuickStartSuggestionV1> {
        val items = when (element) {
            is JsonArray -> element.mapIndexed { index, el ->
                decodeQuickStartItem(el.jsonObject, "characters[$index]")
            }
            is JsonObject -> listOf(decodeQuickStartItem(element, "characters"))
            else -> error("characters must be an array or object")
        }
        require(items.isNotEmpty()) { "characters must contain at least one person" }
        return items
    }

    private fun decodeQuickStartItem(obj: JsonObject, label: String): NovelQuickStartSuggestionV1 {
        val title = obj.getValue("title").jsonPrimitive.content
        val content = obj.getValue("content").jsonPrimitive.content
        require(title.isNotBlank() && content.isNotBlank()) {
            "$label title/content must be non-blank"
        }
        return NovelQuickStartSuggestionV1(title = title, content = content)
    }

    fun decodeStateDelta(text: String): NovelStateDeltaV1 {
        val cleaned = stripFence(text, lenientJson)
        val value = lenientJson.decodeFromString(NovelStateDeltaV1.serializer(), cleaned)
        require(value.schemaVersion == 1)
        require(value.stateSummary.isNotBlank())
        return value
    }

    /**
     * Strict manual-sync entry point. The ordinary state-delta decoder remains lenient
     * for collection compatibility; sync rebuilds must fail closed against one canonical chunk.
     */
    fun decodeManualSyncStateDelta(
        text: String,
        evidenceSource: String,
    ): NovelStateDeltaV1 {
        require(evidenceSource.isNotBlank()) { "evidenceSource must be non-blank" }
        val cleaned = stripFence(text, strictJson)
        val root = strictJson.parseToJsonElement(cleaned).jsonObject
        requireNoDuplicateObjectKeys(cleaned)
        requireExactKeys(
            root,
            setOf(
                "schemaVersion",
                "stateSummary",
                "events",
                "characterChanges",
                "relationshipChanges",
                "foreshadowingChanges",
                "unresolvedEntityNames",
                "branchOutlinePatch",
                "settingProposals",
            ),
        )
        requireIntegerField(root, "schemaVersion")
        requireStringField(root, "stateSummary")
        requireEventArrayField(root, "events")
        requireArrayField(root, "characterChanges")
        requireArrayField(root, "relationshipChanges")
        requireArrayField(root, "foreshadowingChanges")
        requireStringArrayField(root, "unresolvedEntityNames")
        requireNullableStringField(root, "branchOutlinePatch")
        requireSettingProposalArrayField(root, "settingProposals")

        val value = strictJson.decodeFromString(NovelStateDeltaV1.serializer(), cleaned)
        require(value.schemaVersion == 1) { "schemaVersion must be 1" }
        require(value.stateSummary.isNotBlank()) { "stateSummary must be non-blank" }
        value.branchOutlinePatch?.let { patch ->
            require(patch.isNotBlank()) { "branchOutlinePatch must be null or non-blank" }
        }
        require(value.characterChanges.isEmpty()) {
            "manual sync does not persist characterChanges; encode the change as an event"
        }
        require(value.relationshipChanges.isEmpty()) {
            "manual sync does not persist relationshipChanges; encode the change as an event"
        }
        require(value.foreshadowingChanges.isEmpty()) {
            "manual sync does not persist foreshadowingChanges; encode the change as an event"
        }
        requireNonBlankItems(value.unresolvedEntityNames, "unresolvedEntityNames")

        val normalizedSource = normalizeEvidence(evidenceSource)
        value.events.forEachIndexed { index, event ->
            require(event.id.isNotBlank()) { "events[$index].id must be non-blank" }
            require(event.kind.isNotBlank()) { "events[$index].kind must be non-blank" }
            require(event.summary.isNotBlank()) { "events[$index].summary must be non-blank" }
            requireNonBlankItems(event.entityReferences, "events[$index].entityReferences")
            require(event.evidence.isNotBlank()) { "events[$index].evidence must be non-blank" }
            require(isEvidenceAnchored(event.evidence, normalizedSource)) {
                "events[$index].evidence is not anchored in evidenceSource"
            }
        }
        value.settingProposals.forEachIndexed { index, proposal ->
            require(proposal.id.isNotBlank()) { "settingProposals[$index].id must be non-blank" }
            require(proposal.title.isNotBlank()) { "settingProposals[$index].title must be non-blank" }
            require(proposal.content.isNotBlank()) { "settingProposals[$index].content must be non-blank" }
            require(proposal.evidence.isNotBlank()) {
                "settingProposals[$index].evidence must be non-blank"
            }
            require(isEvidenceAnchored(proposal.evidence, normalizedSource)) {
                "settingProposals[$index].evidence is not anchored in evidenceSource"
            }
        }
        return value
    }

    fun decodePolishDrift(text: String): NovelPolishDriftV1Json {
        val cleaned = stripFence(text, strictJson)
        val value = strictJson.decodeFromString(NovelPolishDriftV1Json.serializer(), cleaned)
        require(value.schemaVersion == 1)
        if (value.compatible) require(value.differences.isEmpty())
        else require(value.differences.isNotEmpty())
        return value
    }

    fun decodeContinuityAudit(text: String): NovelContinuityAuditV1 {
        val cleaned = stripFence(text, strictJson)
        val root = strictJson.parseToJsonElement(cleaned).jsonObject
        requireNoDuplicateObjectKeys(cleaned)
        requireExactKeys(root, setOf("schemaVersion", "consistent", "issues"))
        requireIntegerField(root, "schemaVersion")
        requireBooleanField(root, "consistent")
        requireArrayField(root, "issues")
        val value = strictJson.decodeFromString(NovelContinuityAuditV1.serializer(), cleaned)
        require(value.schemaVersion == 1) { "schemaVersion must be 1" }
        val issueIds = mutableSetOf<String>()
        value.issues.forEach { issue ->
            require(issue.id.isNotBlank()) { "continuity issue id must be non-blank" }
            require(issueIds.add(issue.id)) { "continuity issue id must be unique" }
            require(issue.summary.isNotBlank()) { "continuity issue summary must be non-blank" }
            require(issue.references.size >= 2) {
                "continuity issue needs at least 2 references"
            }
            issue.references.forEach { reference ->
                require(reference.chapterOrdinal >= 1) { "chapterOrdinal must be at least 1" }
                require(reference.chapterTitle.isNotBlank()) {
                    "continuity reference chapterTitle must be non-blank"
                }
                require(reference.evidence.isNotBlank()) {
                    "continuity reference evidence must be non-blank"
                }
            }
        }
        if (value.consistent) {
            require(value.issues.isEmpty()) { "consistent audit must have empty issues" }
        } else {
            require(value.issues.isNotEmpty()) { "inconsistent audit must have issues" }
        }
        return value
    }

    fun decodeDiscussionArchive(text: String): NovelDiscussionArchiveV1 {
        val cleaned = stripFence(text, strictJson)
        val value = strictJson.decodeFromString(NovelDiscussionArchiveV1.serializer(), cleaned)
        require(value.schemaVersion == 1) { "schemaVersion must be 1" }
        require(value.decisions.isNotEmpty()) { "A discussion archive must contain at least one decision." }
        val summary = value.summary.trim()
        require(summary.isNotEmpty()) { "A discussion archive summary is required." }
        require(summary.length <= 300) { "A discussion archive summary must not exceed 300 characters." }
        value.decisions.forEachIndexed { index, decision ->
            require(decision.topic.trim().isNotEmpty()) {
                "decisions[$index].topic must be non-blank"
            }
            require(decision.decision.trim().isNotEmpty()) {
                "decisions[$index].decision must be non-blank"
            }
            decision.relatedMaterialID?.let { related ->
                if (related.isBlank()) return@let
                // UUID shape only — existence against project materials is checked by the runtime.
                require(
                    runCatching {
                        java.util.UUID.fromString(related.trim())
                    }.isSuccess,
                ) {
                    "decisions[$index].relatedMaterialID must be a UUID"
                }
            }
        }
        return value.copy(
            summary = summary,
            decisions = value.decisions.map {
                it.copy(
                    topic = it.topic.trim(),
                    decision = it.decision.trim(),
                    relatedMaterialID = it.relatedMaterialID?.trim()?.takeIf { id -> id.isNotEmpty() },
                )
            },
        )
    }

    fun decodeChapterPlanAcceptance(text: String): NovelChapterPlanAcceptanceV1 {
        val cleaned = stripFence(text, strictJson)
        val root = strictJson.parseToJsonElement(cleaned).jsonObject
        requireNoDuplicateObjectKeys(cleaned)
        val schemaVersion = requireIntegerField(root, "schemaVersion")
        val requiredKeys = when (schemaVersion) {
            NovelChapterPlanAcceptanceV1.LEGACY_SCHEMA_VERSION -> setOf(
                "schemaVersion",
                "accepted",
                "missingMustHappen",
                "forbiddenViolations",
                "summary",
            )
            NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION -> setOf(
                "schemaVersion",
                "accepted",
                "missingMustHappen",
                "forbiddenViolations",
                "obviousRepetition",
                "summary",
            )
            else -> error(
                "schemaVersion must be ${NovelChapterPlanAcceptanceV1.LEGACY_SCHEMA_VERSION} " +
                    "or ${NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION}",
            )
        }
        requireExactKeys(root, requiredKeys)
        requireBooleanField(root, "accepted")
        requireStringArrayField(root, "missingMustHappen")
        requireStringArrayField(root, "forbiddenViolations")
        if (schemaVersion == NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION) {
            requireStringArrayField(root, "obviousRepetition")
        }
        requireStringField(root, "summary")
        val value = strictJson.decodeFromString(NovelChapterPlanAcceptanceV1.serializer(), cleaned)
        require(value.summary.isNotBlank()) { "summary must be non-blank" }
        requireNonBlankItems(value.missingMustHappen, "missingMustHappen")
        requireNonBlankItems(value.forbiddenViolations, "forbiddenViolations")
        requireNonBlankItems(value.obviousRepetition, "obviousRepetition")
        val hasContractViolation =
            value.missingMustHappen.isNotEmpty() || value.forbiddenViolations.isNotEmpty()
        if (value.accepted) {
            require(!hasContractViolation) {
                "accepted result must not list chapter-plan violations"
            }
        } else {
            require(hasContractViolation) {
                "rejected result must list at least one chapter-plan violation"
            }
        }
        return value
    }

    fun decodeChapterPlanProposal(text: String): NovelChapterPlanProposalV1 {
        val cleaned = stripFence(text, strictJson)
        val root = strictJson.parseToJsonElement(cleaned).jsonObject
        requireNoDuplicateObjectKeys(cleaned)
        requireExactKeys(
            root,
            setOf(
                "schemaVersion",
                "outlinePlacement",
                "goalAndConflict",
                "mustHappen",
                "mustNotHappen",
                "endingHook",
                "visibleFacts",
            ),
        )
        requireIntegerField(root, "schemaVersion")
        requireStringField(root, "outlinePlacement")
        requireStringField(root, "goalAndConflict")
        requireStringArrayField(root, "mustHappen")
        requireStringArrayField(root, "mustNotHappen")
        requireStringField(root, "endingHook")
        requireStringArrayField(root, "visibleFacts")

        val value = strictJson.decodeFromString(NovelChapterPlanProposalV1.serializer(), cleaned)
        require(value.schemaVersion == NovelChapterPlanProposalV1.CURRENT_SCHEMA_VERSION) {
            "schemaVersion must be ${NovelChapterPlanProposalV1.CURRENT_SCHEMA_VERSION}"
        }
        require(value.goalAndConflict.isNotBlank()) { "goalAndConflict must be non-blank" }
        require(value.goalAndConflict.length <= 8_000) { "goalAndConflict is too long" }
        require(value.outlinePlacement.length <= 500) { "outlinePlacement is too long" }
        require(value.endingHook.length <= 4_000) { "endingHook is too long" }
        requireNonBlankItems(value.mustHappen, "mustHappen")
        requireNonBlankItems(value.mustNotHappen, "mustNotHappen")
        requireNonBlankItems(value.visibleFacts, "visibleFacts")
        val mustHappen = NovelChapterPlanRecord.normalizedLines(value.mustHappen)
        val mustNotHappen = NovelChapterPlanRecord.normalizedLines(value.mustNotHappen)
        val visibleFacts = NovelChapterPlanRecord.normalizedLines(value.visibleFacts)
        require(mustHappen.isNotEmpty()) { "mustHappen must contain at least one item" }
        require(mustHappen.size <= 32 && mustNotHappen.size <= 32 && visibleFacts.size <= 32) {
            "chapter plan has too many checklist items"
        }
        return value
    }

    private fun requireExactKeys(root: JsonObject, requiredKeys: Set<String>) {
        require(root.keys == requiredKeys) {
            val missing = requiredKeys - root.keys
            val unknown = root.keys - requiredKeys
            "JSON fields do not match the contract; missing=$missing unknown=$unknown"
        }
    }

    private fun requireIntegerField(root: JsonObject, field: String): Int {
        val primitive = root[field] as? JsonPrimitive
            ?: error("$field must be an integer")
        require(!primitive.isString) { "$field must be an integer" }
        return primitive.content.toIntOrNull()
            ?: error("$field must be an integer")
    }

    private fun requireBooleanField(root: JsonObject, field: String) {
        val primitive = root[field] as? JsonPrimitive
            ?: error("$field must be a boolean")
        require(!primitive.isString && primitive.content in setOf("true", "false")) {
            "$field must be a boolean"
        }
    }

    private fun requireStringField(root: JsonObject, field: String) {
        val primitive = root[field] as? JsonPrimitive
            ?: error("$field must be a string")
        require(primitive.isString) { "$field must be a string" }
    }

    private fun requireStringArrayField(root: JsonObject, field: String) {
        val array = root[field] as? JsonArray
            ?: error("$field must be an array of strings")
        require(array.all { it is JsonPrimitive && it.isString }) {
            "$field must be an array of strings"
        }
    }

    private fun requireArrayField(root: JsonObject, field: String) {
        require(root[field] is JsonArray) { "$field must be an array" }
    }

    private fun requireNullableStringField(root: JsonObject, field: String) {
        val value = root[field]
        require(
            value is JsonNull ||
                (value is JsonPrimitive && value.isString),
        ) { "$field must be null or a string" }
    }

    private fun requireEventArrayField(root: JsonObject, field: String) {
        requireObjectArrayField(
            root = root,
            field = field,
            requiredKeys = setOf("id", "kind", "summary", "entityReferences", "evidence"),
        ) { item, _ ->
            requireStringField(item, "id")
            requireStringField(item, "kind")
            requireStringField(item, "summary")
            requireStringArrayField(item, "entityReferences")
            requireStringField(item, "evidence")
        }
    }

    private fun requireSettingProposalArrayField(root: JsonObject, field: String) {
        requireObjectArrayField(
            root = root,
            field = field,
            requiredKeys = setOf("id", "title", "content", "evidence"),
        ) { item, _ ->
            requireStringField(item, "id")
            requireStringField(item, "title")
            requireStringField(item, "content")
            requireStringField(item, "evidence")
        }
    }

    private fun requireObjectArrayField(
        root: JsonObject,
        field: String,
        requiredKeys: Set<String>,
        validate: (JsonObject, String) -> Unit,
    ) {
        val array = root[field] as? JsonArray ?: error("$field must be an array of objects")
        array.forEachIndexed { index, element ->
            val path = "$field[$index]"
            val item = element as? JsonObject ?: error("$path must be an object")
            requireExactKeys(item, requiredKeys)
            validate(item, path)
        }
    }

    private fun requireNonBlankItems(values: List<String>, field: String) {
        values.forEachIndexed { index, value ->
            require(value.isNotBlank()) { "$field[$index] must be non-blank" }
        }
    }

    /** kotlinx.serialization keeps the last duplicate key; contract decoders must reject it instead. */
    private fun requireNoDuplicateObjectKeys(text: String) {
        val keyScopes = mutableListOf<MutableSet<String>>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> keyScopes.add(mutableSetOf())
                '}' -> {
                    require(keyScopes.isNotEmpty()) { "malformed JSON object" }
                    keyScopes.removeAt(keyScopes.lastIndex)
                }
                '"' -> {
                    val start = index
                    index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length) { "unterminated JSON string" }
                    var next = index + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        require(keyScopes.isNotEmpty()) { "JSON key outside an object" }
                        val key = strictJson.parseToJsonElement(
                            text.substring(start, index + 1),
                        ).jsonPrimitive.content
                        require(keyScopes.last().add(key)) { "duplicate JSON key: $key" }
                    }
                }
            }
            index++
        }
        require(keyScopes.isEmpty()) { "malformed JSON object" }
    }

    private fun normalizeEvidence(value: String): String {
        val widthAndPunctuation = buildString(value.length) {
            for (character in value) {
                append(
                    when (character) {
                        '\u3000' -> ' '
                        in '\uFF01'..'\uFF5E' -> (character.code - 0xFEE0).toChar()
                        '\u201C', '\u201D', '\u2018', '\u2019',
                        '\u300C', '\u300D', '\u300E', '\u300F', '\'' -> '"'
                        '\u2014', '\u2013', '\u2010', '\u2212' -> '-'
                        else -> character
                    },
                )
            }
        }.replace(Regex("[\\u2026]+|\\.{2,}"), "…")
        return buildString(widthAndPunctuation.length) {
            var needsSpace = false
            for (character in widthAndPunctuation) {
                if (character.isWhitespace()) {
                    needsSpace = isNotEmpty()
                } else {
                    if (needsSpace) append(' ')
                    append(character)
                    needsSpace = false
                }
            }
        }
    }

    private fun isEvidenceAnchored(evidence: String, normalizedSource: String): Boolean {
        val normalizedEvidence = normalizeEvidence(evidence)
        if (normalizedEvidence.isEmpty()) return false
        if (normalizedSource.contains(normalizedEvidence)) return true

        val evidenceCodePoints = normalizedEvidence.codePoints().toArray()
        val sourceCodePoints = normalizedSource.codePoints().toArray()
        val threshold = maxOf(8, (evidenceCodePoints.size * 2 + 4) / 5)
        if (threshold > evidenceCodePoints.size || threshold > sourceCodePoints.size) return false

        var previous = IntArray(evidenceCodePoints.size + 1)
        for (sourceCodePoint in sourceCodePoints) {
            val current = IntArray(evidenceCodePoints.size + 1)
            for (index in evidenceCodePoints.indices) {
                if (sourceCodePoint == evidenceCodePoints[index]) {
                    current[index + 1] = previous[index] + 1
                    if (current[index + 1] >= threshold) return true
                }
            }
            previous = current
        }
        return false
    }

    private fun stripFence(text: String, json: Json): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        // Direct parse when the whole payload is a single object.
        runCatching {
            json.parseToJsonElement(t).jsonObject
            return t
        }
        // iOS-compatible: extract exactly one balanced top-level `{...}` object when the
        // model wraps JSON in prose or a fenced block with a preamble.
        val extracted = extractSingleJsonObject(t)
            ?: error("The model returned malformed JSON.")
        json.parseToJsonElement(extracted).jsonObject
        return extracted
    }

    /**
     * Scan for a single balanced JSON object. Returns null if zero or multiple objects.
     */
    private fun extractSingleJsonObject(text: String): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val candidates = mutableListOf<String>()
        var index = 0
        while (index < bytes.size) {
            if (bytes[index] != '{'.code.toByte()) {
                index++
                continue
            }
            val end = balancedObjectEnd(bytes, index)
            if (end == null) {
                index++
                continue
            }
            val slice = bytes.copyOfRange(index, end + 1).toString(Charsets.UTF_8)
            val ok = runCatching {
                Json.parseToJsonElement(slice) is JsonObject
            }.getOrDefault(false)
            if (ok) {
                candidates += slice
                index = end + 1
            } else {
                index++
            }
        }
        return candidates.singleOrNull()
    }

    private fun balancedObjectEnd(bytes: ByteArray, startIndex: Int): Int? {
        var depth = 0
        var insideString = false
        var index = startIndex
        while (index < bytes.size) {
            val b = bytes[index].toInt() and 0xFF
            if (insideString) {
                when (b) {
                    '\\'.code -> {
                        index += 2
                        continue
                    }
                    '"'.code -> insideString = false
                }
            } else {
                when (b) {
                    '"'.code -> insideString = true
                    '{'.code -> depth++
                    '}'.code -> {
                        depth--
                        if (depth == 0) return index
                        if (depth < 0) return null
                    }
                }
            }
            index++
        }
        return null
    }
}
