package app.amber.feature.miniapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val MINI_APP_MAX_HTML_BYTES = 768 * 1024
internal const val MINI_APP_VERSION_KEEP_LIMIT = 30

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
val MiniAppV3Permissions = MiniAppV2Permissions + setOf(
    "host.context",
    "host.sendToConversation",
    "host.createArtifact",
    "ai.generate",
    "sharedStore",
    "eventBus",
    "launch",
    "sensor",
    "location",
    "clipboard.read",
)
val MiniAppV1Permissions = MiniAppV3Permissions
val MiniAppCategories = setOf("tool", "game", "info", "custom")

val MiniAppPermissionAliases = mapOf(
    "fetch" to "network",
    "http" to "network",
    "https" to "network",
    "internet" to "network",
    "联网" to "network",
    "externalimages" to "externalImages",
    "external-images" to "externalImages",
    "external_images" to "externalImages",
    "remoteImages" to "externalImages",
    "gyro" to "sensor",
    "gyroscope" to "sensor",
    "accelerometer" to "sensor",
    "accel" to "sensor",
    "light" to "sensor",
    "ambientLight" to "sensor",
    "ambientlight" to "sensor",
    "ambient-light" to "sensor",
    "ambient_light" to "sensor",
    "illuminance" to "sensor",
)

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

    @SerialName("host.context")
    HostContext("host.context"),

    @SerialName("host.sendToConversation")
    HostSendToConversation("host.sendToConversation"),

    @SerialName("host.createArtifact")
    HostCreateArtifact("host.createArtifact"),

    @SerialName("ai.generate")
    AiGenerate("ai.generate"),

    @SerialName("sharedStore")
    SharedStore("sharedStore"),

    @SerialName("eventBus")
    EventBus("eventBus"),

    @SerialName("launch")
    Launch("launch"),

    @SerialName("sensor")
    Sensor("sensor"),

    @SerialName("location")
    Location("location"),

    @SerialName("clipboard.read")
    ClipboardRead("clipboard.read"),
}

enum class MiniAppGrantDecision {
    ALLOW,
    DENY,
}
