package app.amber.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Memory classification enums — extracted from core.memory.model.MemoryModels
 * so :core:model can be physically extracted as a standalone Gradle module
 * without pulling in the full memory subsystem.
 *
 * Wire names are STABLE — persisted in user DataStore as @SerialName strings.
 * Adding new variants is fine; renaming or removing breaks user data.
 */
@Serializable
enum class MemoryScope(val wireName: String) {
    @SerialName("core")
    CORE("core"),

    @SerialName("short_term")
    SHORT_TERM("short_term"),

    @SerialName("long_term")
    LONG_TERM("long_term");

    companion object {
        fun fromWireName(value: String?): MemoryScope =
            entries.firstOrNull { it.wireName == value } ?: LONG_TERM
    }
}

@Serializable
enum class MemoryKind(val wireName: String) {
    @SerialName("user")
    USER("user"),

    @SerialName("feedback")
    FEEDBACK("feedback"),

    @SerialName("project")
    PROJECT("project"),

    @SerialName("reference")
    REFERENCE("reference"),

    @SerialName("routine")
    ROUTINE("routine"),

    @SerialName("note")
    NOTE("note");

    companion object {
        fun fromWireName(value: String?): MemoryKind =
            entries.firstOrNull { it.wireName == value } ?: NOTE
    }
}
