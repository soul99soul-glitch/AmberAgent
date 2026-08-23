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

/**
 * 会话记忆模式（P2-a polluted 三态）。harness 在外部上下文（web 搜索/MCP 等）
 * 成功进入会话时置 POLLUTED；该状态只影响「会话作为记忆抽取源」的资格，召回
 * 注入不受影响。POLLUTED 只能由用户手动复位回 ENABLED，任何旧快照回写不得
 * 自动降级。DISABLED 为预留态（当前无写者）。
 *
 * Wire names are STABLE — persisted in conversation JSON. Old JSON without the
 * field decodes to [ENABLED] via the default parameter.
 */
@Serializable
enum class ConversationMemoryMode(val wireName: String) {
    @SerialName("enabled")
    ENABLED("enabled"),

    @SerialName("disabled")
    DISABLED("disabled"),

    @SerialName("polluted")
    POLLUTED("polluted");

    companion object {
        fun fromWireName(value: String?): ConversationMemoryMode =
            entries.firstOrNull { it.wireName == value } ?: ENABLED
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
