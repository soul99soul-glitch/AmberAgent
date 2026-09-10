package app.amber.feature.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.primitives.NetworkLog
import app.amber.feature.webmount.primitives.WebMountLease
import app.amber.feature.webmount.primitives.WebMountLeaseResult
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.webmount.primitives.WebViewPool
import app.amber.feature.webmount.primitives.SessionHandle
import kotlinx.coroutines.CancellationException

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
    val owner: WebMountSessionOwner,
)

internal const val WEBMOUNT_CONVERSATION_ID = "_webmount_conversation_id"
internal const val WEBMOUNT_RUN_ID = "_webmount_run_id"

/** Scope the tool instance to the foreground conversation/run without global context. */
internal fun JsonElement.withWebMountScope(
    conversationId: String?,
    runId: String?,
): JsonElement {
    val objectInput = this as? JsonObject ?: return this
    return JsonObject(objectInput.toMutableMap().apply {
        // These keys are host-owned metadata. Remove model-supplied values
        // before writing the actual scope, including when the host has no
        // scope (debug catalogs must fail closed rather than inherit a fake run).
        remove(WEBMOUNT_CONVERSATION_ID)
        remove(WEBMOUNT_RUN_ID)
        conversationId?.let { put(WEBMOUNT_CONVERSATION_ID, kotlinx.serialization.json.JsonPrimitive(it)) }
        runId?.let { put(WEBMOUNT_RUN_ID, kotlinx.serialization.json.JsonPrimitive(it)) }
    })
}

private fun JsonElement.withoutWebMountScope(): JsonElement {
    val objectInput = this as? JsonObject ?: return this
    if (!objectInput.containsKey(WEBMOUNT_CONVERSATION_ID) &&
        !objectInput.containsKey(WEBMOUNT_RUN_ID)
    ) return this
    return JsonObject(objectInput.toMutableMap().apply {
        remove(WEBMOUNT_CONVERSATION_ID)
        remove(WEBMOUNT_RUN_ID)
    })
}

private fun JsonElement.webMountScopeValue(name: String): String? =
    (this as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonElement.webMountConversationId(): String? = webMountScopeValue(WEBMOUNT_CONVERSATION_ID)

internal fun JsonElement.webMountRunId(): String? = webMountScopeValue(WEBMOUNT_RUN_ID)

/**
 * New-session tools must reject an unscoped invocation before allocating a
 * WebView. Existing-session tools use [WebMountDeps.withAgentSession], which
 * returns the same structured failure after the session id is known.
 */
internal fun JsonElement.agentScopeFailure(sessionId: String? = null): List<UIMessagePart>? {
    val code = when {
        webMountConversationId() == null -> "missing_conversation"
        webMountRunId() == null -> "missing_run"
        else -> return null
    }
    return listOf(UIMessagePart.Text(buildJsonObject {
        sessionId?.let { put("session_id", it) }
        put("ok", false)
        put("error", code)
        put("owner_conflict", false)
    }.toString()))
}

/**
 * Surface the live primary window and transient overlays with every state or
 * action receipt. A popup/JS dialog is a human hand-off in the first version;
 * the agent must resume by observing the same session after the user resolves
 * or returns from it.
 */
internal fun SessionHandle.webMountWindowState(): JsonObject {
    val popupItems = popups.value
    val dialogItems = jsDialogs.value
    val activePopup = popupItems.lastOrNull()
    val requiresHuman = popupItems.isNotEmpty() || dialogItems.isNotEmpty()
    val resumeCondition = dialogItems.firstOrNull()?.let { "resolve_js_dialog:${it.dialogId}" }
        ?: activePopup?.let { "close_or_return_popup:${it.popupId}" }
        ?: "continue"
    return buildJsonObject {
        put("active_window", buildJsonObject {
            put("kind", if (activePopup == null) "primary" else "popup")
            put("id", activePopup?.popupId ?: sessionId)
            put("session_id", sessionId)
            put("url", redactWebMountUrl(activePopup?.url ?: loadState.value.currentUrl))
            put("title", activePopup?.title ?: loadState.value.title)
            activePopup?.let { put("is_dialog_window", it.isDialogWindow) }
        })
        put("popups", buildJsonArray {
            popupItems.forEach { popup ->
                add(buildJsonObject {
                    put("popup_id", popup.popupId)
                    put("url", redactWebMountUrl(popup.url))
                    put("title", popup.title)
                    put("is_dialog_window", popup.isDialogWindow)
                    put("created_at_ms", popup.createdAtMs)
                })
            }
        })
        put("dialogs", buildJsonArray {
            dialogItems.forEach { dialog ->
                add(buildJsonObject {
                    put("dialog_id", dialog.dialogId)
                    put("type", dialog.type.name.lowercase())
                    put("url", redactWebMountUrl(dialog.url))
                    put("message", dialog.message.take(500))
                    dialog.defaultValue?.let { put("default_value", it.take(200)) }
                })
            }
        })
        put("requires_human", requiresHuman)
        put("resume_condition", resumeCondition)
    }
}

internal fun JsonObjectBuilder.putWebMountWindowState(handle: SessionHandle) {
    handle.webMountWindowState().forEach { (key, value) -> put(key, value) }
}

/**
 * Acquire the AGENT lease before touching a pooled WebView. A rejected claim
 * is returned as a structured tool result so owner conflicts remain visible
 * to the model and never fall through to a direct pool lookup.
 */
internal suspend fun WebMountDeps.withAgentSession(
    input: JsonElement,
    sessionId: String,
    allowReopen: Boolean = false,
    block: suspend (WebMountLease) -> List<UIMessagePart>,
): List<UIMessagePart> {
    val conversationId = input.webMountConversationId()
    val runId = input.webMountRunId()
    val result = owner.acquire(
        sessionId = sessionId,
        actor = WebMountOwner.AGENT,
        conversationId = conversationId,
        runId = runId,
        allowReopen = allowReopen,
    )
    val lease = when (result) {
        is WebMountLeaseResult.Granted -> result.lease
        is WebMountLeaseResult.Rejected -> {
            return listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("ok", false)
                put("error", result.failure.code)
                put("owner_conflict", result.failure.code == "owner_conflict")
                result.metadata?.owner?.let { put("current_owner", it.name.lowercase()) }
                result.metadata?.conversationId?.let { put("bound_conversation_id", it) }
                result.metadata?.runId?.let { put("bound_run_id", it) }
            }.toString()))
        }
    }
    // endRun/lease expiry can race the suspended pool acquire. Do not enter
    // a tool block after the owner has withdrawn this exact run lease.
    if (!owner.isLeaseActive(lease.leaseId, conversationId, runId)) {
        owner.release(lease.leaseId, "lease no longer active")
        return listOf(UIMessagePart.Text(buildJsonObject {
            put("session_id", sessionId)
            put("ok", false)
            put("error", "run_mismatch")
            put("owner_conflict", false)
        }.toString()))
    }
    var preserveDialogs = false
    return try {
        val result = block(lease)
        // A tool may deliberately return while a JS dialog is still pending
        // so the user can take over the same WebView. Preserve that dialog
        // only for an explicit handoff receipt; ordinary cancellation and
        // failures still use the owner's default cancellation path.
        preserveDialogs = result.requestsHumanHandoff() && lease.handle.jsDialogs.value.isNotEmpty()
        result
    } catch (error: CancellationException) {
        throw error
    } finally {
        // The flag is assigned above before leaving the try block. Keep it
        // outside the expression so exceptions/cancellation cannot be
        // mistaken for a human handoff.
        owner.release(lease.leaseId, "tool_finished", cancelDialogs = !preserveDialogs)
    }
}

/**
 * Bind a WebView dispatch to the exact agent lease that entered this tool.
 * SessionHandle invokes this callback on Main immediately before the browser
 * side effect, while the owner keeps its CAS lock through the dispatch.
 */
internal fun WebMountDeps.dispatchWithLease(
    input: JsonElement,
    lease: WebMountLease,
): ((() -> Unit) -> Boolean) = { action ->
    owner.dispatchIfActive(
        leaseId = lease.leaseId,
        conversationId = input.webMountConversationId(),
        runId = input.webMountRunId(),
        action = action,
    )
}

private fun List<UIMessagePart>.requestsHumanHandoff(): Boolean = any { part ->
    val text = (part as? UIMessagePart.Text)?.text ?: return@any false
    runCatching {
        Json.parseToJsonElement(text).jsonObject["requires_human"]
            ?.jsonPrimitive
            ?.booleanOrNull == true
    }.getOrDefault(false)
}

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
        inputPreview = input.withoutWebMountScope().toString(),
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
