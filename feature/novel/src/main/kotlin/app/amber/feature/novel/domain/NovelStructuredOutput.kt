package app.amber.feature.novel.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class NovelQuickStartSuggestionV1(
    val title: String,
    val content: String,
)

@Serializable
data class NovelQuickStartSuggestionsV1(
    val schemaVersion: Int,
    val overview: String,
    val world: NovelQuickStartSuggestionV1,
    val characters: NovelQuickStartSuggestionV1,
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

object NovelStructuredOutputDecoder {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun decodeQuickStartSuggestions(text: String): NovelQuickStartSuggestionsV1 {
        val cleaned = stripFence(text)
        val value = json.decodeFromString(NovelQuickStartSuggestionsV1.serializer(), cleaned)
        require(value.schemaVersion == 1)
        require(value.overview.isNotBlank())
        require(value.world.title.isNotBlank() && value.world.content.isNotBlank())
        require(value.characters.title.isNotBlank() && value.characters.content.isNotBlank())
        require(value.masterOutline.title.isNotBlank() && value.masterOutline.content.isNotBlank())
        require(value.writingRequirements.title.isNotBlank() && value.writingRequirements.content.isNotBlank())
        return value
    }

    fun decodeStateDelta(text: String): NovelStateDeltaV1 {
        val cleaned = stripFence(text)
        val value = json.decodeFromString(NovelStateDeltaV1.serializer(), cleaned)
        require(value.schemaVersion == 1)
        require(value.stateSummary.isNotBlank())
        return value
    }

    fun decodePolishDrift(text: String): NovelPolishDriftV1Json {
        val cleaned = stripFence(text)
        val value = json.decodeFromString(NovelPolishDriftV1Json.serializer(), cleaned)
        require(value.schemaVersion == 1)
        if (value.compatible) require(value.differences.isEmpty())
        else require(value.differences.isNotEmpty())
        return value
    }

    private fun stripFence(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        // Ensure root object
        json.parseToJsonElement(t).jsonObject
        return t
    }
}
