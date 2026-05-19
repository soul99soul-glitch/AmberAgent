package me.rerere.rikkahub.data.agent.miniapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val MINI_APP_MAX_HTML_BYTES = 512 * 1024
internal const val MINI_APP_VERSION_KEEP_LIMIT = 20

val MiniAppV2Permissions = setOf(
    "storage",
    "toast",
    "theme",
    "network",
    "externalImages",
    "search",
    "clipboard.copy",
    "host.updateBoardSummary",
)
val MiniAppV1Permissions = MiniAppV2Permissions
val MiniAppCategories = setOf("tool", "game", "info", "custom")

@Serializable
data class MiniAppGeneratedOutput(
    val title: String,
    val description: String,
    val icon: String? = null,
    val category: String = "tool",
    val permissions: List<String> = emptyList(),
    val html: String,
)

@Serializable
data class MiniAppCardRef(
    val appId: String,
    val title: String,
    val description: String,
    val iconEmoji: String? = null,
    val category: String? = null,
    val permissions: List<String> = emptyList(),
    val htmlHash: String? = null,
    val version: Int = 1,
)

@Serializable
data class MiniAppBridgeRequest(
    val id: Int,
    val token: String,
    val method: String,
    val params: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class MiniAppBridgeResponse(
    val id: Int,
    val ok: Boolean,
    val data: kotlinx.serialization.json.JsonElement? = null,
    val error: String? = null,
)

enum class MiniAppPermission(val value: String) {
    @SerialName("storage")
    Storage("storage"),

    @SerialName("toast")
    Toast("toast"),

    @SerialName("theme")
    Theme("theme"),

    @SerialName("network")
    Network("network"),

    @SerialName("externalImages")
    ExternalImages("externalImages"),

    @SerialName("search")
    Search("search"),

    @SerialName("clipboard.copy")
    ClipboardCopy("clipboard.copy"),

    @SerialName("host.updateBoardSummary")
    BoardSummaryUpdate("host.updateBoardSummary"),
}

enum class MiniAppGrantDecision {
    ALLOW,
    DENY,
}
