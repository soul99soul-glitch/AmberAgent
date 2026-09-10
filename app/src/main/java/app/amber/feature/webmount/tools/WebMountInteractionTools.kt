package app.amber.feature.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.boolean
import app.amber.core.agent.utils.long
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string
import app.amber.feature.webmount.primitives.SessionHandle
import app.amber.feature.webmount.primitives.WebMountLeaseInvalidatedException
import kotlinx.coroutines.CoroutineStart
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

internal fun createWaitTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_wait",
    description = """
        Block until a condition holds on the current page, or a timeout elapses. Kinds:
        `selector` (default) waits for at least one element matching the given selector to appear
        (and be visible unless visible_only=false); `ready_state` waits until document.readyState
        reaches the requested state ('interactive' or 'complete'); `semantic_idle` / `dom_stable` /
        `network_idle` wait for a semantic page settle with a hard timeout. Useful after wm_open
        wait="none" or after clicking a link that triggers an SPA navigation.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("until", stringProp("'selector' (default) | 'ready_state' | 'semantic_idle' | 'dom_stable' | 'network_idle'."))
                put("value", stringProp("selector: CSS or 'text=...'/'xpath=...'; ready_state: target state."))
                put("timeout_ms", integerProp("Max wait in ms. Default 10000, clamped to [200, 60000]."))
                put("stable_ms", integerProp("semantic waits: required stable duration. Default 700, cap 3000."))
                put("visible_only", booleanProp("selector: require visible match (default true)."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_wait", "WebMount 等待", input) {
            val sessionId = input.requiredString("session_id")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val until = input.string("until") ?: "selector"
                require(until in setOf("selector", "ready_state", "semantic_idle", "dom_stable", "network_idle")) {
                    "until must be 'selector', 'ready_state', 'semantic_idle', 'dom_stable', or 'network_idle'"
                }
                val value = if (until in setOf("selector", "ready_state")) input.requiredString("value") else null
                val timeout = (input.long("timeout_ms") ?: 10_000L).coerceIn(200L, 60_000L)
                val args = buildJsonObject {
                    put("until", until)
                    value?.let { put("value", it) }
                    put("timeout_ms", timeout)
                    input.long("stable_ms")?.let { put("stable_ms", it.coerceIn(250L, 3_000L)) }
                    input.boolean("visible_only")?.let { put("visible_only", it) }
                }
                // Allow the bridge a small grace window beyond its own JS-side timeout
                // so it surfaces the explicit "wait timed out" error rather than our
                // generic bridge-timeout one.
                val payload = handle.callBridge(
                    "wait",
                    args,
                    timeoutMs = timeout + 3_000L,
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                )
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("ok", true)
                    put("wait_kind", until)
                    put("network_coverage", handle.bridgeInjectionCoverage)
                    put("result", payload)
                    putWebMountWindowState(handle)
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createClickTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_click",
    description = """
        Click an element in a WebMount session. Prefer `target` with a ref returned by wm_extract;
        selector remains available for legacy CSS / text=... / xpath=... calls. The bridge focuses
        the element, dispatches mousedown/mouseup, then calls .click() so default actions fire.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("target", stringProp("Node ref returned by wm_extract, or a selector fallback."))
                put("snapshot_id", stringProp("Snapshot id returned with the target ref; required for ref-based actions."))
                put("selector", stringProp("Legacy CSS / text=... / xpath=... selector for the target element."))
                put("visible_only", booleanProp("Require the element to be visible (default true)."))
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    mandatoryApproval = true,
    execute = { input ->
        deps.track("wm_click", "WebMount 点击", input) {
            val sessionId = input.requiredString("session_id")
            val target = input.string("target")
            val selector = input.string("selector")
            require(target != null || selector != null) { "wm_click requires target or selector" }
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val args = buildJsonObject {
                    target?.let { put("target", it) }
                    input.string("snapshot_id")?.let { put("snapshot_id", it) }
                    selector?.let { put("selector", it) }
                    input.boolean("visible_only")?.let { put("visible_only", it) }
                }
                val merged = runVerifiedAction(
                    sessionId = sessionId,
                    handle = handle,
                    leaseIsActive = {
                        deps.owner.isLeaseActive(
                            lease.leaseId,
                            input.webMountConversationId(),
                            input.webMountRunId(),
                        )
                    },
                    requestedSnapshotId = input.string("snapshot_id"),
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                ) {
                    handle.callBridge(
                        "click",
                        args,
                        timeoutMs = 5_000L,
                        dispatchWithLease = deps.dispatchWithLease(input, lease),
                    )
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createTapTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_tap",
    description = """
        Explicit coordinate fallback for visually identified WebMount targets. Taps viewport x/y
        coordinates only when DOM refs/selectors are unavailable (for example canvas or cross-origin
        iframe regions). Prefer wm_click with refs whenever possible.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("x", integerProp("Viewport x coordinate."))
                put("y", integerProp("Viewport y coordinate."))
                put("snapshot_id", stringProp("Snapshot id used to validate an optional visual/coordinate target."))
            },
            required = listOf("session_id", "x", "y"),
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.track("wm_tap", "WebMount 坐标点击", input) {
            val sessionId = input.requiredString("session_id")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val x = input.long("x") ?: error("x is required")
                val y = input.long("y") ?: error("y is required")
                val merged = runVerifiedAction(
                    sessionId = sessionId,
                    handle = handle,
                    leaseIsActive = {
                        deps.owner.isLeaseActive(
                            lease.leaseId,
                            input.webMountConversationId(),
                            input.webMountRunId(),
                        )
                    },
                    requestedSnapshotId = input.string("snapshot_id"),
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                ) {
                    handle.callBridge(
                        "tap",
                        buildJsonObject {
                            put("x", x)
                            put("y", y)
                            input.string("snapshot_id")?.let { put("snapshot_id", it) }
                        },
                        timeoutMs = 5_000L,
                        dispatchWithLease = deps.dispatchWithLease(input, lease),
                    )
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createTypeTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_type",
    description = """
        Type text into an editable element (input/textarea/[contenteditable]) in a WebMount session.
        Prefer `target` with a ref returned by wm_extract; selector remains available for legacy calls.
        Fires `input` and `change` events so frontend frameworks observe the new value. Pass
        `press_enter=true` to dispatch a synthetic Enter after typing (useful for search boxes that
        submit on Enter). `clear=true` empties the field first.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("target", stringProp("Node ref returned by wm_extract, or a selector fallback."))
                put("snapshot_id", stringProp("Snapshot id returned with the target ref; required for ref-based actions."))
                put("selector", stringProp("Legacy selector for the editable element."))
                put("text", stringProp("UTF-8 text to type."))
                put("clear", booleanProp("Empty the field before typing (default false)."))
                put("press_enter", booleanProp("Dispatch Enter keydown after typing (default false)."))
            },
            required = listOf("session_id", "text"),
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.track("wm_type", "WebMount 输入", input.safeTypePreview()) {
            val sessionId = input.requiredString("session_id")
            val target = input.string("target")
            val selector = input.string("selector")
            require(target != null || selector != null) { "wm_type requires target or selector" }
            val text = input.requiredString("text")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val args = buildJsonObject {
                    target?.let { put("target", it) }
                    input.string("snapshot_id")?.let { put("snapshot_id", it) }
                    selector?.let { put("selector", it) }
                    put("text", text)
                    input.boolean("clear")?.let { put("clear", it) }
                    input.boolean("press_enter")?.let { put("press_enter", it) }
                }
                val merged = runVerifiedAction(
                    sessionId = sessionId,
                    handle = handle,
                    leaseIsActive = {
                        deps.owner.isLeaseActive(
                            lease.leaseId,
                            input.webMountConversationId(),
                            input.webMountRunId(),
                        )
                    },
                    includePageDetails = false,
                    requestedSnapshotId = input.string("snapshot_id"),
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                ) {
                    handle.callBridge(
                        "type",
                        args,
                        timeoutMs = 5_000L,
                        dispatchWithLease = deps.dispatchWithLease(input, lease),
                    )
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createEvalTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_eval",
    description = """
        ⚠️ HIGH RISK. Evaluate arbitrary JavaScript in the WebMount session and return the
        result. The script runs INSIDE the page's origin with full DOM access — it can read
        any data the user has on that site (cookies, sessionStorage, localStorage), perform
        same-origin fetches with credentials, and mutate the page. Ordinary auto-approval and
        in-run trust cannot bypass its approval gate; only explicit high-risk auto-approval
        can run it unattended. Prefer the specific primitives (wm_click / wm_type / wm_extract /
        wm_find) when they suffice. The expression's return value is JSON-serialized;
        non-serializable values fall back to String() coercion.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("expression", stringProp("JS expression to evaluate. Wrapped in `return (...)` if it's an expression; statements are also accepted."))
                put("timeout_ms", integerProp("Max time the JS engine may take. Default 5000, clamped to [200, 30000]."))
            },
            required = listOf("session_id", "expression"),
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    mandatoryApproval = true,
    execute = { input ->
        deps.track("wm_eval", "WebMount JS 执行", input.safeEvalPreview()) {
            val sessionId = input.requiredString("session_id")
            val expression = input.requiredString("expression")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val timeout = (input.long("timeout_ms") ?: 5_000L).coerceIn(200L, 30_000L)
                val args = buildJsonObject {
                    put("expression", expression)
                }
                val merged = runVerifiedAction(
                    sessionId = sessionId,
                    handle = handle,
                    leaseIsActive = {
                        deps.owner.isLeaseActive(
                            lease.leaseId,
                            input.webMountConversationId(),
                            input.webMountRunId(),
                        )
                    },
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                ) {
                    handle.callBridge(
                        "eval",
                        args,
                        timeoutMs = timeout,
                        dispatchWithLease = deps.dispatchWithLease(input, lease),
                    )
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createScrollTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_scroll",
    description = """
        Scroll a WebMount session. Three mutually exclusive modes (in priority order):
        (1) `target`/`selector` scrolls the matched element into view; (2) `to` accepts "top" |
        "bottom", or absolute coordinates via `to_x` + `to_y`; (3) `by_x` + `by_y` scrolls
        relative to the current position. Reports the post-scroll {x, y}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
                put("target", stringProp("Node ref returned by wm_extract, or a selector fallback."))
                put("selector", stringProp("Legacy selector to scroll into view (CSS / text= / xpath=)."))
                put("snapshot_id", stringProp("Snapshot id returned with the target ref; required for ref-based scrolling."))
                put("to", stringProp("'top' | 'bottom' for shorthand absolute scrolls."))
                put("to_x", integerProp("Absolute x (used with to_y)."))
                put("to_y", integerProp("Absolute y."))
                put("by_x", integerProp("Relative horizontal delta."))
                put("by_y", integerProp("Relative vertical delta."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_scroll", "WebMount 滚动", input) {
            val sessionId = input.requiredString("session_id")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val args = buildJsonObject {
                    val selector = input.string("selector")
                    val target = input.string("target")
                    val to = input.string("to")
                    val toX = input.long("to_x")
                    val toY = input.long("to_y")
                    val byX = input.long("by_x")
                    val byY = input.long("by_y")
                    when {
                        target != null -> put("target", target)
                        selector != null -> put("selector", selector)
                        to != null -> put("to", to)
                        toX != null && toY != null -> put("to", buildJsonObject {
                            put("x", toX)
                            put("y", toY)
                        })
                        byX != null || byY != null ->
                            put("by", buildJsonArray {
                                add(JsonPrimitive(byX ?: 0L))
                                add(JsonPrimitive(byY ?: 0L))
                            })
                        else -> error("wm_scroll requires selector / to / to_x+to_y / by_x+by_y")
                    }
                    input.string("snapshot_id")?.let { put("snapshot_id", it) }
                }
                val merged = runVerifiedAction(
                    sessionId = sessionId,
                    handle = handle,
                    leaseIsActive = {
                        deps.owner.isLeaseActive(
                            lease.leaseId,
                            input.webMountConversationId(),
                            input.webMountRunId(),
                        )
                    },
                    requestedSnapshotId = input.string("snapshot_id"),
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                ) {
                    handle.callBridge(
                        "scroll",
                        args,
                        timeoutMs = 5_000L,
                        dispatchWithLease = deps.dispatchWithLease(input, lease),
                    )
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createKeysTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_keys",
    description = """
        Dispatch a synthetic keyboard event in a WebMount session. Useful for Enter / Escape / Tab /
        arrow keys after wm_type when the field doesn't auto-submit. Modifiers can be combined
        (ctrl, shift, alt, meta). If `target` or `selector` is provided, focus moves to that element first;
        otherwise the event targets the currently-focused element.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
                put("key", stringProp("Key name: 'Enter', 'Escape', 'Tab', 'ArrowDown', 'a', etc."))
                put("target", stringProp("Optional node ref returned by wm_extract, or selector fallback."))
                put("selector", stringProp("Optional legacy selector to focus before sending the key."))
                put("snapshot_id", stringProp("Snapshot id returned with the target ref; required for ref-based key events."))
                put("ctrl", booleanProp("Hold Ctrl while pressing (default false)."))
                put("shift", booleanProp("Hold Shift (default false)."))
                put("alt", booleanProp("Hold Alt (default false)."))
                put("meta", booleanProp("Hold Meta / Cmd (default false)."))
            },
            required = listOf("session_id", "key"),
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.track("wm_keys", "WebMount 键盘", input) {
            val sessionId = input.requiredString("session_id")
            val key = input.requiredString("key")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val mods = buildJsonObject {
                    input.boolean("ctrl")?.let { put("ctrl", it) }
                    input.boolean("shift")?.let { put("shift", it) }
                    input.boolean("alt")?.let { put("alt", it) }
                    input.boolean("meta")?.let { put("meta", it) }
                }
                val args = buildJsonObject {
                    put("key", key)
                    input.string("target")?.let { put("target", it) }
                    input.string("selector")?.let { put("selector", it) }
                    input.string("snapshot_id")?.let { put("snapshot_id", it) }
                    put("modifiers", mods)
                }
                val merged = runVerifiedAction(
                    sessionId = sessionId,
                    handle = handle,
                    leaseIsActive = {
                        deps.owner.isLeaseActive(
                            lease.leaseId,
                            input.webMountConversationId(),
                            input.webMountRunId(),
                        )
                    },
                    requestedSnapshotId = input.string("snapshot_id"),
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                ) {
                    handle.callBridge(
                        "keys",
                        args,
                        timeoutMs = 5_000L,
                        dispatchWithLease = deps.dispatchWithLease(input, lease),
                    )
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createSelectTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_select",
    description = """
        Choose an option in a <select> dropdown. Matches `value` against both option.value and the
        visible option text. Prefer `target` with a ref returned by wm_extract; selector remains
        available for legacy calls. Fires input + change events so frontend frameworks observe the new selection.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
                put("target", stringProp("Node ref returned by wm_extract, or selector fallback."))
                put("selector", stringProp("Legacy selector for the <select> element."))
                put("snapshot_id", stringProp("Snapshot id returned with the target ref; required for ref-based selection."))
                put("value", stringProp("Option value or visible text to choose."))
            },
            required = listOf("session_id", "value"),
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.track("wm_select", "WebMount 选择", input) {
            val sessionId = input.requiredString("session_id")
            val target = input.string("target")
            val selector = input.string("selector")
            require(target != null || selector != null) { "wm_select requires target or selector" }
            val value = input.requiredString("value")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val args = buildJsonObject {
                    target?.let { put("target", it) }
                    input.string("snapshot_id")?.let { put("snapshot_id", it) }
                    selector?.let { put("selector", it) }
                    put("value", value)
                }
                val merged = runVerifiedAction(
                    sessionId = sessionId,
                    handle = handle,
                    leaseIsActive = {
                        deps.owner.isLeaseActive(
                            lease.leaseId,
                            input.webMountConversationId(),
                            input.webMountRunId(),
                        )
                    },
                    requestedSnapshotId = input.string("snapshot_id"),
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                ) {
                    handle.callBridge(
                        "select",
                        args,
                        timeoutMs = 5_000L,
                        dispatchWithLease = deps.dispatchWithLease(input, lease),
                    )
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        }
    },
)

internal fun createFindTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_find",
    description = """
        Search the page text for a substring and return up to N visible matches with their CSS path,
        bounding rect, and a short text preview. Use this to locate elements the agent then clicks
        via wm_click (e.g. find "Login" → click that path). Case-insensitive by default.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
                put("text", stringProp("Text substring to search for."))
                put("case_sensitive", booleanProp("Default false."))
                put("max", integerProp("Max matches to return. Default 20, cap 100."))
            },
            required = listOf("session_id", "text"),
        )
    },
    execute = { input ->
        deps.track("wm_find", "WebMount 查找", input) {
            val sessionId = input.requiredString("session_id")
            val text = input.requiredString("text")
            deps.withAgentSession(input, sessionId) { lease ->
                val handle = lease.handle
                val args = buildJsonObject {
                    put("text", text)
                    input.boolean("case_sensitive")?.let { put("case_sensitive", it) }
                    input.long("max")?.let { put("max", it) }
                }
                val payload = handle.callBridge(
                    "find",
                    args,
                    timeoutMs = 10_000L,
                    dispatchWithLease = deps.dispatchWithLease(input, lease),
                )
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                    putWebMountWindowState(handle)
                }.toString()))
            }
        }
    },
)

private fun JsonElement.safeEvalPreview(): JsonObject =
    buildJsonObject {
        put("session_id", string("session_id"))
        put("expression_chars", string("expression")?.length ?: 0)
    }

private fun JsonElement.safeTypePreview(): JsonObject =
    buildJsonObject {
        put("session_id", string("session_id"))
        put("has_target", string("target") != null)
        put("has_selector", string("selector") != null)
        put("text_chars", string("text")?.length ?: 0)
        put("clear", boolean("clear") ?: false)
        put("press_enter", boolean("press_enter") ?: false)
    }

/**
 * Execute one page action and return the durable receipt consumed by the
 * agent. A bridge error envelope means no dispatch occurred; a host/bridge
 * exception is deliberately `unknown` because the page may have applied the
 * action before the reply was lost. No retry is performed here.
 */
internal suspend fun runVerifiedAction(
    sessionId: String,
    handle: SessionHandle,
    includePageDetails: Boolean = true,
    requestedSnapshotId: String? = null,
    leaseIsActive: (() -> Boolean)? = null,
    dispatchWithLease: ((() -> Unit) -> Boolean)? = null,
    action: suspend () -> JsonElement,
): JsonObject {
    val actionId = UUID.randomUUID().toString()
    // A dialog blocks the synchronous JavaScript click/type handler. Surface
    // the handoff as soon as Android observes it so the short AGENT lease can
    // be released without cancelling the dialog before HUMAN takes over.
    handle.jsDialogs.value.firstOrNull()?.let { dialog ->
        return buildJsonObject {
            put("session_id", sessionId)
            put("action_id", actionId)
            put("dispatched", false)
            put("status", "unknown")
            put("snapshot_id_before", requestedSnapshotId)
            put("snapshot_id_after", requestedSnapshotId)
            put("page_changed", false)
            put("goal_verified", false)
            put("may_have_applied", false)
            put("dialog_id", dialog.dialogId)
            put("error", "human confirmation required")
            put("result", JsonNull)
            put("auto_retry_after_event", false)
            putWebMountWindowState(handle)
        }
    }
    val before = callBridgeForReceipt(
        handle,
        "semantic_state",
        timeoutMs = 2_000L,
        dispatchWithLease = dispatchWithLease,
    )
    val beforeNetwork = handle.networkLog.totalEvents
    val beforeSnapshotId = before.stringField("snapshot_id") ?: requestedSnapshotId
    if (leaseIsActive?.invoke() == false) {
        return buildJsonObject {
            put("session_id", sessionId)
            put("action_id", actionId)
            put("dispatched", false)
            put("status", "failed")
            put("snapshot_id_before", beforeSnapshotId)
            put("snapshot_id_after", beforeSnapshotId)
            put("page_changed", false)
            put("goal_verified", false)
            put("error", "run_mismatch")
            put("result", JsonNull)
            put("auto_retry_after_event", false)
            putWebMountWindowState(handle)
        }
    }
    val attempt = try {
        executeActionOrHumanHandoff(handle, action)
    } catch (_: WebMountLeaseInvalidatedException) {
        return buildJsonObject {
            put("session_id", sessionId)
            put("action_id", actionId)
            put("dispatched", false)
            put("status", "failed")
            put("snapshot_id_before", beforeSnapshotId)
            put("snapshot_id_after", beforeSnapshotId)
            put("page_changed", false)
            put("goal_verified", false)
            put("error", "run_mismatch")
            put("result", JsonNull)
            put("auto_retry_after_event", false)
            putWebMountWindowState(handle)
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        return buildJsonObject {
            put("session_id", sessionId)
            put("action_id", actionId)
            put("dispatched", true)
            put("status", "unknown")
            put("snapshot_id_before", beforeSnapshotId)
            put("snapshot_id_after", null as String?)
            put("page_changed", false)
            put("goal_verified", false)
            put("may_have_applied", true)
            put("error", error.message ?: error.toString())
            put("result", JsonNull)
            put("auto_retry_after_event", false)
            putWebMountWindowState(handle)
        }
    }
    if (attempt is ActionAttempt.HumanHandoff) {
        return buildJsonObject {
            put("session_id", sessionId)
            put("action_id", actionId)
            put("dispatched", attempt.dispatched)
            put("status", "unknown")
            put("snapshot_id_before", beforeSnapshotId)
            put("snapshot_id_after", beforeSnapshotId)
            put("page_changed", false)
            put("goal_verified", false)
            if (attempt.dispatched) put("may_have_applied", true)
            put("dialog_id", attempt.dialog.dialogId)
            put("error", "human confirmation required")
            put("result", JsonNull)
            put("auto_retry_after_event", false)
            putWebMountWindowState(handle)
        }
    }
    val result = (attempt as ActionAttempt.Completed).result

    val resultObject = result as? JsonObject
    val bridgeSucceeded = resultObject?.get("ok")?.let { (it as? JsonPrimitive)?.booleanOrNull } != false
    if (!bridgeSucceeded) {
        val errorCode = runCatching {
            resultObject?.get("error")?.let { errorValue ->
                (errorValue as? JsonObject)?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
        }.getOrNull()
        return buildJsonObject {
            put("session_id", sessionId)
            put("action_id", actionId)
            put("dispatched", false)
            put("status", "failed")
            put("snapshot_id_before", beforeSnapshotId)
            put("snapshot_id_after", beforeSnapshotId)
            put("page_changed", false)
            put("goal_verified", false)
            if (errorCode == "stale_target") put("stale_target", true)
            put("result", result)
            put("auto_retry_after_event", false)
            putWebMountWindowState(handle)
        }
    }

    WebMountPageSnapshotCache.invalidate(sessionId)
    val settle = callBridgeForReceipt(handle, "wait", timeoutMs = 2_500L, dispatchWithLease = dispatchWithLease) {
        buildJsonObject {
            put("until", "semantic_idle")
            put("timeout_ms", 1_500)
            put("stable_ms", 350)
        }
    }
    val after = callBridgeForReceipt(
        handle,
        "semantic_state",
        timeoutMs = 2_000L,
        dispatchWithLease = dispatchWithLease,
    )
    val beforeFingerprint = before.stringField("semantic_fingerprint")
    val afterFingerprint = after.stringField("semantic_fingerprint")
    val afterSnapshotId = after.stringField("snapshot_id")
    val pageChanged = beforeFingerprint != null && afterFingerprint != null && beforeFingerprint != afterFingerprint
    // A changed DOM or network log only proves that the page reacted. It does
    // not prove the requested business outcome. The bridge may opt in with an
    // explicit `goal_verified=true` evidence field; otherwise the receipt stays
    // dispatched even when the after-state is readable.
    val goalVerified = resultObject?.get("goal_verified")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
    val status = when {
        goalVerified -> "verified"
        after == null -> "unknown"
        else -> "dispatched"
    }
    return buildJsonObject {
        put("session_id", sessionId)
        put("action_id", actionId)
        put("dispatched", true)
        put("status", status)
        put("snapshot_id_before", beforeSnapshotId)
        put("snapshot_id_after", afterSnapshotId)
        put("page_changed", pageChanged)
        put("goal_verified", goalVerified)
        if (after == null) put("may_have_applied", true)
        put("result", result)
        putWebMountWindowState(handle)
        put("action_verification", buildJsonObject {
            put("network_delta", (handle.networkLog.totalEvents - beforeNetwork).coerceAtLeast(0L))
            put("dom_changed", pageChanged)
            beforeFingerprint?.let { put("before_fingerprint", it) }
            afterFingerprint?.let { put("after_fingerprint", it) }
            if (includePageDetails) {
                settle?.let { put("settle", it) }
                after?.let { put("after_page", it) }
            }
            put("auto_retry_after_event", false)
            put("network_coverage", handle.bridgeInjectionCoverage)
        })
    }
}

private sealed interface ActionAttempt {
    data class Completed(val result: JsonElement) : ActionAttempt
    data class HumanHandoff(
        val dialog: SessionHandle.JsDialogInfo,
        val dispatched: Boolean,
    ) : ActionAttempt
}

private suspend fun executeActionOrHumanHandoff(
    handle: SessionHandle,
    action: suspend () -> JsonElement,
): ActionAttempt = coroutineScope {
    val actionJob = async(start = CoroutineStart.UNDISPATCHED) { action() }
    val dialogJob = async(start = CoroutineStart.UNDISPATCHED) {
        handle.jsDialogs.first { it.isNotEmpty() }.first()
    }
    try {
        select {
            actionJob.onAwait { ActionAttempt.Completed(it) }
            dialogJob.onAwait { ActionAttempt.HumanHandoff(it, dispatched = true) }
        }
    } finally {
        dialogJob.cancel()
        if (!actionJob.isCompleted) actionJob.cancel()
    }
}

private suspend fun callBridgeForReceipt(
    handle: SessionHandle,
    method: String,
    timeoutMs: Long,
    dispatchWithLease: ((() -> Unit) -> Boolean)? = null,
    args: () -> JsonObject = { buildJsonObject {} },
): JsonElement? = try {
    handle.callBridge(
        method,
        args(),
        timeoutMs = timeoutMs,
        dispatchWithLease = dispatchWithLease,
    )
} catch (error: CancellationException) {
    throw error
} catch (_: Throwable) {
    null
}

private fun JsonElement?.stringField(name: String): String? =
    runCatching { this?.jsonObject?.get(name) as? JsonPrimitive }.getOrNull()?.contentOrNull
