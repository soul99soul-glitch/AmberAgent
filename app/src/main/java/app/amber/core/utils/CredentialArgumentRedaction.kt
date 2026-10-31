package app.amber.core.utils

import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tools whose arguments carry a live credential that is only ever supposed to
 * be written into encrypted storage. The write-only contract means the value
 * must not survive in the transcript either: a profile password or provider API
 * key sitting in a saved tool call would leak through the conversation list,
 * the export file, sync archives and the context summary for as long as that
 * conversation lives.
 *
 * Only the tools listed here have their credential keys rewritten, so unrelated
 * tools that happen to take a `password` input (an `ask_user` free-text answer,
 * for example) are untouched.
 */
internal val CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS: Map<String, Set<String>> = mapOf(
    "ssh_profile_create" to setOf("password", "private_key", "passphrase"),
    "ssh_profile_update" to setOf("password", "private_key", "passphrase"),
    "ssh_key_import" to setOf("private_key", "passphrase"),
    "provider_config_apply" to setOf("api_key", "token", "secret", "password"),
)

/** Placeholder that replaces a credential wherever the call is stored. */
internal const val CREDENTIAL_REDACTION = "<redacted>"

/**
 * Rewrites the credential arguments of a tool call to [CREDENTIAL_REDACTION]
 * before the call reaches durable storage, context summaries, exports or sync
 * archives. Non-credential arguments keep their exact value so the call still
 * explains itself; a call without credentials is returned unchanged.
 *
 * Deliberately narrow: it runs only for the tools in
 * [CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS] because only those promise a
 * write-only credential, and it is applied at the storage boundary rather than
 * in a generic argument path.
 */
internal fun redactCredentialArguments(toolName: String, input: JsonElement?): JsonElement? {
    if (input == null) return null
    val keys = CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS[toolName] ?: return input
    val root = input as? JsonObject ?: return JsonObject(emptyMap())
    if (root.keys.none { it in keys }) return input
    return buildJsonObject {
        root.forEach { (key, value) ->
            if (key in keys) {
                val strVal = value.jsonPrimitiveOrNull?.contentOrNull
                // An empty string means "clear the stored credential"; keep "" untouched
                // so the transcript still shows it was a clear operation.
                if (strVal != null && strVal.isEmpty()) {
                    put(key, value)
                } else {
                    put(key, JsonPrimitive(CREDENTIAL_REDACTION))
                }
            } else {
                put(key, value)
            }
        }
    }
}

/**
 * True when this call supplies at least one credential value. The approval
 * receipt uses it to say "will set/replace the password" instead of rendering
 * the value.
 */
internal fun toolTakesCredentialArguments(toolName: String, input: JsonElement?): Boolean {
    val keys = CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS[toolName] ?: return false
    val root = input as? JsonObject ?: return false
    return keys.any { key ->
        val value = root[key]?.jsonPrimitive?.contentOrNull
        !value.isNullOrEmpty()
    }
}

/**
 * Which credential fields this call supplies, as human-facing nouns. Empty for
 * calls that carry no credential.
 */
internal fun credentialArgumentRoles(toolName: String, input: JsonElement?): List<String> {
    val keys = CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS[toolName] ?: return emptyList()
    val root = input as? JsonObject ?: return emptyList()
    return keys.mapNotNull { key ->
        val supplied = !root[key]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
        if (supplied) key else null
    }
}

/**
 * Rewrites this call's credential arguments to [CREDENTIAL_REDACTION], leaving
 * every other argument untouched.
 */
internal fun UIMessagePart.Tool.withRedactedCredentialArguments(): UIMessagePart.Tool {
    if (toolName !in CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS) return this
    val parsed = runCatching {
        JsonInstant.parseToJsonElement(input.ifBlank { "{}" })
    }.getOrNull() ?: return copy(input = "{}")
    val redacted = redactCredentialArguments(toolName, parsed) ?: return this
    if (redacted == parsed) return this
    return copy(input = redacted.toString())
}

/**
 * Applies credential redaction to this call only when it is settled. Used by the
 * terminal paths (cancel, abandon, interrupted projection) that build a fresh
 * copy of a call and persist it.
 */
internal fun UIMessagePart.Tool.withSettledCredentialArgumentsRedacted(): UIMessagePart.Tool =
    if (hasSettledCredentialArguments()) withRedactedCredentialArguments() else this

/**
 * True once this call can no longer be replayed from the stored conversation:
 * it executed, or a human denied/answered it. An `Approved` call that has not
 * run yet is deliberately NOT settled — approval resume replays its arguments,
 * so erasing them early would turn a real credential into a placeholder.
 */
internal fun UIMessagePart.Tool.hasSettledCredentialArguments(): Boolean =
    isExecuted ||
        approvalState is ToolApprovalState.Denied ||
        approvalState is ToolApprovalState.Answered

/**
 * Redacts credentials on settled calls so the value never reaches durable
 * storage.
 *
 * A pending or approved-but-unexecuted call keeps its arguments in memory because
 * approval resume replays them from the active session. Those values are masked
 * in every display surface, and this function erases them as soon as the call
 * settles. Callers that must never persist a credential at all (the mid-approval
 * checkpoint and Room storage boundary) drop the unapproved call's secret instead
 * — see [CREDENTIAL_WITHHELD_ON_RESUME].
 */
internal fun UIMessage.withSettledCredentialsRedacted(): UIMessage {
    if (parts.none { it is UIMessagePart.Tool && it.toolName in CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS }) {
        return this
    }
    var changed = false
    val redactedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && part.hasSettledCredentialArguments()) {
            val redacted = part.withRedactedCredentialArguments()
            if (redacted !== part) {
                changed = true
                redacted
            } else {
                part
            }
        } else {
            part
        }
    }
    return if (changed) copy(parts = redactedParts) else this
}

/**
 * Error a credential-bearing call returns when its arguments were withheld
 * from durable storage before it could run. The model has to issue a fresh call
 * with the credential again; the placeholder must never be written to the
 * store, so the call is refused before it touches storage.
 */
internal const val CREDENTIAL_WITHHELD_ON_RESUME =
    "The credential for this tool call was deliberately not persisted while it awaited " +
        "approval, so it cannot be replayed after the interruption. Nothing was changed. " +
        "Ask the user for the credential again and issue a new tool call."

/**
 * Drops the credential arguments of a call that must not be persisted in
 * plaintext (the mid-approval checkpoint) and marks it unexecutable, so a later
 * resume reports [CREDENTIAL_WITHHELD_ON_RESUME] instead of writing a
 * placeholder into the store.
 */
internal fun UIMessagePart.Tool.withheldCredentialArguments(): UIMessagePart.Tool {
    if (toolName !in CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS) return this
    val parsed = runCatching {
        JsonInstant.parseToJsonElement(input.ifBlank { "{}" })
    }.getOrNull()
    if (parsed is JsonObject && redactCredentialArguments(toolName, parsed) == parsed) return this
    return copy(
        input = JsonObject(emptyMap()).toString(),
        approvalState = ToolApprovalState.Denied(CREDENTIAL_WITHHELD_ON_RESUME),
    )
}

/**
 * Withholds credentials from every call that is still awaiting approval. Used
 * at the mid-approval checkpoint and durable storage boundary so plaintext
 * credentials never land in Room while awaiting user approval.
 */
internal fun List<UIMessage>.withUnapprovedCredentialsWithheld(): List<UIMessage> {
    var changed = false
    val withheld = map { message ->
        if (message.parts.none {
                it is UIMessagePart.Tool &&
                    it.toolName in CREDENTIAL_BEARING_TOOL_CREDENTIAL_KEYS &&
                    !it.hasSettledCredentialArguments()
            }
        ) {
            message
        } else {
            var messageChanged = false
            val parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.hasSettledCredentialArguments()) {
                    val next = part.withheldCredentialArguments()
                    if (next !== part) messageChanged = true
                    next
                } else {
                    part
                }
            }
            if (messageChanged) {
                changed = true
                message.copy(parts = parts)
            } else {
                message
            }
        }
    }
    return if (changed) withheld else this
}

/** Same redaction applied across a message list. */
internal fun List<UIMessage>.withSettledCredentialsRedacted(): List<UIMessage> {
    var changed = false
    val redacted = map { message ->
        val next = message.withSettledCredentialsRedacted()
        if (next !== message) changed = true
        next
    }
    return if (changed) redacted else this
}
