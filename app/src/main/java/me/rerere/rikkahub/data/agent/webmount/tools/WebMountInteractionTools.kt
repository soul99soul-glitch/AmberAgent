package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.boolean
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.tools.string

internal fun createWaitTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_wait",
    description = """
        Block until a condition holds on the current page, or a timeout elapses. Two kinds:
        `selector` (default) waits for at least one element matching the given selector to appear
        (and be visible unless visible_only=false); `ready_state` waits until document.readyState
        reaches the requested state ('interactive' or 'complete'). Useful after wm_open wait="none"
        or after clicking a link that triggers an SPA navigation.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("until", stringProp("'selector' (default) | 'ready_state'."))
                put("value", stringProp("selector: CSS or 'text=...'/'xpath=...'; ready_state: target state."))
                put("timeout_ms", integerProp("Max wait in ms. Default 10000, clamped to [200, 60000]."))
                put("visible_only", booleanProp("selector: require visible match (default true)."))
            },
            required = listOf("session_id", "value"),
        )
    },
    execute = { input ->
        deps.track("wm_wait", "WebMount 等待", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val until = input.string("until") ?: "selector"
            require(until in setOf("selector", "ready_state")) { "until must be 'selector' or 'ready_state'" }
            val value = input.requiredString("value")
            val timeout = (input.long("timeout_ms") ?: 10_000L).coerceIn(200L, 60_000L)
            val args = buildJsonObject {
                put("until", until)
                put("value", value)
                put("timeout_ms", timeout)
                input.boolean("visible_only")?.let { put("visible_only", it) }
            }
            // Allow the bridge a small grace window beyond its own JS-side timeout
            // so it surfaces the explicit "wait timed out" error rather than our
            // generic bridge-timeout one.
            val payload = handle.callBridge("wait", args, timeoutMs = timeout + 3_000L)
            val merged = buildJsonObject {
                put("session_id", sessionId)
                put("ok", true)
                put("result", payload)
            }
            listOf(UIMessagePart.Text(merged.toString()))
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
                put("selector", stringProp("Legacy CSS / text=... / xpath=... selector for the target element."))
                put("visible_only", booleanProp("Require the element to be visible (default true)."))
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.track("wm_click", "WebMount 点击", input) {
            val sessionId = input.requiredString("session_id")
            val target = input.string("target")
            val selector = input.string("selector")
            require(target != null || selector != null) { "wm_click requires target or selector" }
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val args = buildJsonObject {
                target?.let { put("target", it) }
                selector?.let { put("selector", it) }
                input.boolean("visible_only")?.let { put("visible_only", it) }
            }
            val payload = handle.callBridge("click", args, timeoutMs = 5_000L)
            val merged = buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }
            listOf(UIMessagePart.Text(merged.toString()))
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
        deps.track("wm_type", "WebMount 输入", input) {
            val sessionId = input.requiredString("session_id")
            val target = input.string("target")
            val selector = input.string("selector")
            require(target != null || selector != null) { "wm_type requires target or selector" }
            val text = input.requiredString("text")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val args = buildJsonObject {
                target?.let { put("target", it) }
                selector?.let { put("selector", it) }
                put("text", text)
                input.boolean("clear")?.let { put("clear", it) }
                input.boolean("press_enter")?.let { put("press_enter", it) }
            }
            val payload = handle.callBridge("type", args, timeoutMs = 5_000L)
            val merged = buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }
            listOf(UIMessagePart.Text(merged.toString()))
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
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val timeout = (input.long("timeout_ms") ?: 5_000L).coerceIn(200L, 30_000L)
            val args = buildJsonObject {
                put("expression", expression)
            }
            val payload = handle.callBridge("eval", args, timeoutMs = timeout)
            val merged = buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }
            listOf(UIMessagePart.Text(merged.toString()))
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
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
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
            }
            val payload = handle.callBridge("scroll", args, timeoutMs = 5_000L)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }.toString()))
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
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
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
                put("modifiers", mods)
            }
            val payload = handle.callBridge("keys", args, timeoutMs = 5_000L)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }.toString()))
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
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val args = buildJsonObject {
                target?.let { put("target", it) }
                selector?.let { put("selector", it) }
                put("value", value)
            }
            val payload = handle.callBridge("select", args, timeoutMs = 5_000L)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }.toString()))
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
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val args = buildJsonObject {
                put("text", text)
                input.boolean("case_sensitive")?.let { put("case_sensitive", it) }
                input.long("max")?.let { put("max", it) }
            }
            val payload = handle.callBridge("find", args, timeoutMs = 10_000L)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("result", payload)
            }.toString()))
        }
    },
)

private fun JsonElement.safeEvalPreview(): JsonObject =
    buildJsonObject {
        put("session_id", string("session_id"))
        put("expression_chars", string("expression")?.length ?: 0)
    }
