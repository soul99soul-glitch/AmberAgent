package app.amber.feature.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import app.amber.feature.webmount.primitives.NetworkLog
import app.amber.feature.webmount.primitives.WebViewPool

/**
 * The two deps every WebMount primitive needs: a pool to acquire a
 * session and the activity store for progress events. Domain-specific
 * deps (profileRegistry, cookieProvider, manager, userSiteRegistry,
 * profileBridge, oauthStore) stay outside this wrapper — only the
 * factories that use them take them as explicit parameters, matching
 * the SystemAccessTools split convention.
 */
internal class WebMountDeps(
    val pool: WebViewPool,
    val activityStore: AgentToolActivityStore,
)

/**
 * Records start / complete / fail for a WebMount tool invocation. Mirrors
 * the prior class member `track(...)` byte-for-byte — runtime is
 * "WebMount", workspace is "/webmount". Re-throws after recording failure.
 */
internal suspend fun WebMountDeps.track(
    toolName: String,
    title: String,
    input: JsonElement,
    block: suspend () -> List<UIMessagePart>,
): List<UIMessagePart> {
    val toolCallId = activityStore.startTool(
        toolName = toolName,
        title = title,
        inputPreview = input.toString(),
        runtime = "WebMount",
        workspace = "/webmount",
    )
    return try {
        val result = block()
        activityStore.complete(toolCallId, result.previewText())
        result
    } catch (error: Throwable) {
        activityStore.fail(toolCallId, error)
        throw error
    }
}

private fun List<UIMessagePart>.previewText(): String =
    joinToString("\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> part.toString()
        }
    }.takeLast(1_600)

// --- Schema DSL shared across the WebMount tool factories.
// (Same convention as SystemAccessShared.kt — these stay scoped to the
// WebMount package instead of consolidating with the agent/tools/ DSL.)

internal fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun booleanProp(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun integerProp(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun redactWebMountUrl(url: String?): String? =
    url?.takeIf { it.isNotBlank() }?.let(NetworkLog::redactedUrl)
