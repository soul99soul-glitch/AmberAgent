package app.amber.feature.novel.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        val value = strictJson.decodeFromString(NovelContinuityAuditV1.serializer(), cleaned)
        require(value.schemaVersion == 1) { "schemaVersion must be 1" }
        if (value.consistent) {
            require(value.issues.isEmpty()) { "consistent audit must have empty issues" }
        } else {
            require(value.issues.isNotEmpty()) { "inconsistent audit must have issues" }
            value.issues.forEach { issue ->
                require(issue.id.isNotBlank() && issue.summary.isNotBlank())
                require(issue.references.size >= 2) {
                    "continuity issue needs at least 2 references"
                }
            }
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
