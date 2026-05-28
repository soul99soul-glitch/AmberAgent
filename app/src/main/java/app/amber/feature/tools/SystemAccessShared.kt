package app.amber.feature.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import app.amber.feature.system.AgentPermissionBroker
import kotlin.math.min

/**
 * Two-field deps bundle threaded into every system-access tool factory.
 * Kept as a thin wrapper (no equality, no copy) because every sibling
 * file pulls the same pair — passing two args each would just be noise.
 */
internal class SystemAccessDeps(
    val activityStore: AgentToolActivityStore,
    val permissionBroker: AgentPermissionBroker,
)

/**
 * Gates [block] on permissionBroker.ensureGranted then records start /
 * complete / fail into activityStore so the UI surfaces progress and the
 * activity log audits each system-access invocation. Re-throws whatever
 * [block] throws after recording the failure.
 */
internal suspend fun SystemAccessDeps.trackSystemTool(
    toolName: String,
    title: String,
    capabilityId: String,
    input: JsonElement,
    block: suspend () -> List<UIMessagePart>,
): List<UIMessagePart> {
    permissionBroker.ensureGranted(
        capabilityId = capabilityId,
        toolName = toolName,
        reason = title,
    )
    val toolCallId = activityStore.startTool(
        toolName = toolName,
        title = title,
        inputPreview = input.toString(),
        runtime = "Android system access",
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

// --- Schema DSL shared across the SystemAccess tool factories.
// NOTE: nine sibling tool files in this package keep their own
// private copies of this DSL — promoting these to the package-wide
// helper file (ToolJson.kt) is a separate cleanup, intentionally
// out of scope for the SystemAccess god-class split. ---

internal fun obj(vararg properties: Pair<String, JsonElement>, required: List<String>? = null) =
    InputSchema.Obj(
        properties = buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
        },
        required = required
    )

internal fun accessStringProp(description: String) = buildJsonObject {
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

internal fun enumProp(description: String, values: List<String>) = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach(::add) })
}

// --- JsonElement helpers used across system-access tools ---

internal fun JsonElement.limit(name: String = "limit", default: Int, max: Int): Int =
    min(int(name) ?: default, max).coerceAtLeast(1)

/**
 * Strips PII fields from a tool input element so it's safe to persist
 * into the activity log preview. Phones / sender / email are masked;
 * long free-text fields collapse to their char count.
 */
internal fun JsonElement.safePreview(): JsonElement = buildJsonObject {
    jsonObject.forEach { (key, value) ->
        when (key) {
            "message", "body", "description" -> put("${key}_chars", value.toString().length)
            "phone_number", "phone", "sender" -> put("${key}_masked", maskPhone(value.toString().trim('"')))
            "email" -> put("email_masked", maskEmail(value.toString().trim('"')))
            else -> put(key, value)
        }
    }
}

// --- Masking primitives ---

internal fun maskPhone(value: String): String {
    val digits = value.filter { it.isDigit() }
    if (digits.length <= 4) return value.take(2) + "***"
    val prefix = digits.take(3)
    val suffix = digits.takeLast(4)
    return "$prefix****$suffix"
}

internal fun maskEmail(value: String): String {
    val parts = value.split("@", limit = 2)
    if (parts.size != 2) return value.take(2) + "***"
    val name = parts[0]
    val domain = parts[1]
    val maskedName = when {
        name.isEmpty() -> "***"
        name.length == 1 -> "${name.first()}***"
        else -> "${name.first()}***${name.last()}"
    }
    return "$maskedName@$domain"
}
