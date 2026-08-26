package app.amber.core.settings.prefs

import android.util.Log
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.LegacyAssistantProfile
import app.amber.core.settings.Settings
import app.amber.core.settings.selectLegacyAssistantProfile
import app.amber.search.SearchServiceOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

private const val TAG = "PrefsDecode"

/**
 * Decode a persisted settings entry, dropping it (so the caller's elvis default
 * applies) instead of crashing the settings flow when the stored JSON is
 * corrupted. A single bad entry must not halt the whole process.
 */
internal inline fun <reified T> String.decodeJsonOrNull(): T? =
    runCatching { JsonInstant.decodeFromString<T>(this) }
        .onFailure {
            Log.e(TAG, "Dropping corrupted settings entry (${T::class.simpleName}): ${it.message}")
        }
        .getOrNull()

internal fun String.parseUuidOrNull(): Uuid? =
    runCatching { Uuid.parse(this) }
        .onFailure { Log.e(TAG, "Dropping corrupted settings uuid: ${it.message}") }
        .getOrNull()

internal data class DecodedSearchServices(
    val services: List<SearchServiceOptions>,
    val legacyServiceIds: Set<Uuid>,
    val legacyIndices: Set<Int>,
) {
    val removedLegacy: Boolean get() = legacyIndices.isNotEmpty()

    fun adjustedSelected(originalIndex: Int): Int {
        if (services.isEmpty() || originalIndex in legacyIndices) return 0
        val shifted = originalIndex - legacyIndices.count { it < originalIndex }
        return shifted.coerceIn(0, services.lastIndex)
    }
}

/**
 * Decode after removing the retired `amber_agent` subtype at the raw JSON boundary.
 * Its unknown discriminator must not invalidate the user's other configured services,
 * and its API key must never materialize as another service's credential.
 */
internal fun String.decodeSearchServicesDroppingLegacy(): DecodedSearchServices? =
    runCatching {
        val array = JsonInstant.parseToJsonElement(this) as JsonArray
        val legacyIds = mutableSetOf<Uuid>()
        val legacyIndices = mutableSetOf<Int>()
        val retained = array.filterIndexed { index, element ->
            val objectValue = element as? JsonObject
            val isLegacy = (objectValue?.get("type") as? JsonPrimitive)?.content == "amber_agent"
            if (isLegacy) {
                legacyIndices += index
                (objectValue?.get("id") as? JsonPrimitive)?.content?.let { rawId ->
                    runCatching { Uuid.parse(rawId) }.getOrNull()?.let(legacyIds::add)
                }
            }
            !isLegacy
        }
        DecodedSearchServices(
            services = JsonInstant.decodeFromJsonElement(JsonArray(retained)),
            legacyServiceIds = legacyIds,
            legacyIndices = legacyIndices,
        )
    }.onFailure {
        Log.e(TAG, "Dropping corrupted search service list: ${it.message}")
    }.getOrNull()

/** Decode a complete Settings backup across retired search and multi-assistant shapes. */
fun Json.decodeSettingsDroppingLegacySearchService(encoded: String): Settings {
    val root = parseToJsonElement(encoded).jsonObject
    val sanitized = root.toMutableMap().apply {
        val legacyAssistants = this["assistants"]?.let { raw ->
            // A present assistants container is authoritative. Malformed legacy
            // data must reject the backup instead of silently defaulting it away.
            decodeFromJsonElement<List<LegacyAssistantProfile>>(raw)
        }.orEmpty()
        val selectedId = this["assistantId"]?.jsonPrimitive?.content
            ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
        val legacyProfile = this["amberProfile"]?.let { raw ->
            // A present profile is authoritative. Let malformed nested data fail the
            // whole backup decode instead of silently falling back to another shape.
            decodeFromJsonElement<LegacyAssistantProfile>(raw)
        } ?: if (legacyAssistants.isNotEmpty() || selectedId != null) {
            selectLegacyAssistantProfile(selectedId, legacyAssistants)
        } else {
            null
        }
        legacyProfile?.let { profile ->
            putLegacyAmberFields(profile)
        }
        remove("amberProfile")
        remove("assistantId")
        remove("assistants")
        remove("assistantTags")

        val rawServices = this["searchServices"] as? JsonArray
        val decoded = rawServices?.toString()?.decodeSearchServicesDroppingLegacy()
        if (rawServices != null && decoded?.removedLegacy == true) {
            val retainedServices = rawServices.filterIndexed { index, _ ->
                index !in decoded.legacyIndices
            }
            val selected = this["searchServiceSelected"]?.jsonPrimitive?.intOrNull ?: 0
            val legacyIds = decoded.legacyServiceIds.mapTo(mutableSetOf()) { it.toString() }
            val rawEnabled = this["searchEnabledServiceIds"] as? JsonArray
            val retainedEnabled = rawEnabled?.filter { element ->
                element.jsonPrimitive.content !in legacyIds
            }
            val legacyWasEnabled = rawEnabled?.any { element ->
                element.jsonPrimitive.content in legacyIds
            } == true

            this["searchServices"] = JsonArray(retainedServices)
            this["searchServiceSelected"] = JsonPrimitive(decoded.adjustedSelected(selected))
            if (retainedEnabled != null) {
                this["searchEnabledServiceIds"] = JsonArray(retainedEnabled)
            }
            if (selected in decoded.legacyIndices || (legacyWasEnabled && retainedEnabled.isNullOrEmpty())) {
                this["enableWebSearch"] = JsonPrimitive(false)
            }
        }
    }
    return decodeFromJsonElement(JsonObject(sanitized))
}

private fun MutableMap<String, kotlinx.serialization.json.JsonElement>.putLegacyAmberFields(
    profile: LegacyAssistantProfile,
) {
    this["systemPrompt"] = JsonInstant.encodeToJsonElement(profile.systemPrompt)
    this["temperature"] = JsonInstant.encodeToJsonElement(profile.temperature)
    this["topP"] = JsonInstant.encodeToJsonElement(profile.topP)
    this["contextMessageSize"] = JsonInstant.encodeToJsonElement(profile.contextMessageSize)
    this["streamOutput"] = JsonInstant.encodeToJsonElement(profile.streamOutput)
    this["messageTemplate"] = JsonInstant.encodeToJsonElement(profile.messageTemplate)
    this["presetMessages"] = JsonInstant.encodeToJsonElement(profile.presetMessages)
    this["regexes"] = JsonInstant.encodeToJsonElement(profile.regexes)
    this["reasoningLevel"] = JsonInstant.encodeToJsonElement(profile.reasoningLevel)
    this["maxTokens"] = JsonInstant.encodeToJsonElement(profile.maxTokens)
    this["customHeaders"] = JsonInstant.encodeToJsonElement(profile.customHeaders)
    this["customBodies"] = JsonInstant.encodeToJsonElement(profile.customBodies)
    this["enabledSkills"] = JsonInstant.encodeToJsonElement(profile.enabledSkills)
    this["enabledMcpServerIds"] = JsonInstant.encodeToJsonElement(profile.mcpServers)
    this["enabledModeInjectionIds"] = JsonInstant.encodeToJsonElement(profile.modeInjectionIds)
    this["enabledLorebookIds"] = JsonInstant.encodeToJsonElement(profile.lorebookIds)
    this["rememberedReasoningLevelsByModelId"] =
        JsonInstant.encodeToJsonElement(profile.rememberedReasoningLevelsByModelId)
    profile.chatModelId?.let { this["chatModelId"] = JsonInstant.encodeToJsonElement(it) }
    // Backup decoding preserves the legacy root selection. Runtime image-model
    // resolution still validates the selected model's IMAGE type.
    profile.legacyImageGenerationModelId?.let {
        this["imageGenerationModelId"] = JsonInstant.encodeToJsonElement(it)
    }
    remove("quickMessageIds")
    remove("enableMemory")
    remove("useGlobalMemory")
    remove("enableRecentChatsReference")
    remove("enableTimeReminder")
    remove("localTools")
    remove("toolProfile")
}
