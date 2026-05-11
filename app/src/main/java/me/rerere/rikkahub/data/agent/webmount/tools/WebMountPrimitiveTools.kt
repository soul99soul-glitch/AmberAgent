package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.tools.boolean
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.tools.string
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.primitives.SessionHandle
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewPool
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewScreenshot

/**
 * Browser Primitives tool catalog.
 *
 * Each `wm_*` tool is a thin shim over [WebViewPool]: parse the agent's
 * JSON input, call into the pool / session, format the result as JSON text.
 *
 * Current catalog (set by [getTools]):
 *  - Navigation/state: `wm_open`, `wm_state`, `wm_stations`,
 *    `wm_back`, `wm_forward`, `wm_scroll`
 *  - Reading: `wm_extract`, `wm_wait`, `wm_find`, `wm_screenshot`
 *  - Interaction: `wm_click`, `wm_type`, `wm_keys`, `wm_select`
 *  - Tabs: `wm_tab_list`, `wm_tab_new`, `wm_tab_close`
 *  - Escape hatch (separate toggle): `wm_eval`
 *
 * **Default OFF**: the LocalTools aggregator only includes these when the
 * user enables `LocalToolOption.WebMount` per assistant. `wm_eval` requires
 * an additional `LocalToolOption.WebMountEval` opt-in (Phase 2 M2.0.2)
 * and is flagged `Tool.mandatoryApproval = true` (M2.0.1) so the per-call
 * human approval prompt cannot be bypassed by any auto-approve setting.
 */
class WebMountPrimitiveTools(
    private val pool: WebViewPool,
    private val activityStore: AgentToolActivityStore,
    private val manager: WebMountManager,
) {

    /**
     * Phase 2 M2.0.2: `wm_eval` is now gated by a separate
     * [me.rerere.rikkahub.data.ai.tools.LocalToolOption.WebMountEval] toggle
     * because it can execute arbitrary JS in a logged-in WebView — a strictly
     * stronger capability than the rest of the primitives. Pass
     * `includeEval = true` only when that secondary toggle is on.
     */
    fun getTools(includeEval: Boolean = false): List<Tool> = listOfNotNull(
        openTool,
        stateTool,
        extractTool,
        waitTool,
        clickTool,
        typeTool,
        if (includeEval) evalTool else null,
        scrollTool,
        backTool,
        forwardTool,
        keysTool,
        selectTool,
        findTool,
        tabListTool,
        tabNewTool,
        tabCloseTool,
        screenshotTool,
        stationsTool,
    )

    // -------------------------------------------------------------- wm_open

    private val openTool = Tool(
        name = "wm_open",
        description = """
            Open a URL in a pooled headless WebView. Re-uses an existing session if `session_id` is provided
            and still alive, otherwise allocates a new one. Returns the session id, the load status, the
            current URL, and the latest title. Use `wait="load"` (default) to block until onPageFinished;
            `wait="none"` returns immediately after issuing the navigation. The headless WebView reuses the
            app-wide cookie jar, so any sites the user has logged into through other in-app WebViews are
            already authenticated here.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", stringProp("Absolute http(s) URL to navigate to."))
                    put("session_id", stringProp("Reuse an existing session. Omit to allocate a new one."))
                    put("wait", stringProp("'load' (default) waits for onPageFinished; 'none' returns immediately."))
                    put("timeout_ms", integerProp("Load timeout in ms. Default 30000, clamped to [1000, 60000]."))
                },
                required = listOf("url"),
            )
        },
        execute = { input ->
            track("wm_open", "WebMount 打开", input.safeUrlPreview()) {
                val url = input.requiredString("url")
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "wm_open only supports http(s) URLs"
                }
                val wait = input.string("wait")?.lowercase() ?: "load"
                require(wait in setOf("load", "none")) { "wait must be one of: load, none" }
                val timeoutMs = (input.long("timeout_ms") ?: SessionHandle.DEFAULT_LOAD_TIMEOUT_MS)
                    .coerceIn(1_000L, 60_000L)
                val sessionId = input.string("session_id")
                val handle = if (sessionId != null) pool.acquire(sessionId) else pool.acquireNew()
                val payload: JsonObject = if (wait == "none") {
                    // Issue load but don't wait. Still routes through SessionHandle
                    // so _loadState is flipped to LOADING synchronously — without
                    // this, a follow-up wm_state would briefly see stale "ready"
                    // state from the prior navigation.
                    val state = handle.loadUrlNoWait(url)
                    buildJsonObject {
                        put("session_id", handle.sessionId)
                        put("status", state.status.wireName)
                        put("url", state.currentUrl ?: url)
                        put("requested_url", url)
                        put("waited", false)
                    }
                } else {
                    val state = handle.loadUrl(url, timeoutMs)
                    buildJsonObject {
                        put("session_id", handle.sessionId)
                        put("status", state.status.wireName)
                        put("url", state.currentUrl ?: url)
                        put("title", state.title)
                        put("requested_url", url)
                        put("load_progress", state.progress)
                        put("error", state.error)
                        put("waited", true)
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    // ------------------------------------------------------------- wm_state

    private val stateTool = Tool(
        name = "wm_state",
        description = """
            Snapshot the live state of a WebMount session: URL, title, document.readyState, viewport,
            scroll position, console message tail, and (since M1.4.4) the per-session network event log.
            Pass `network_since` = the highest `seq` you've already seen to receive only newer entries;
            use this to poll for XHR/fetch traffic after wm_click or page navigation. Useful for adapter
            authors mapping page actions to backend endpoints.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("include_console", booleanProp("Include recent console messages (default true)."))
                    put("console_tail", integerProp("How many console entries to include. Default 16, max 64."))
                    put("network_since", integerProp("Return network events with seq > this. Default 0 = include everything in the ring."))
                    put("network_max", integerProp("Cap on network events returned. Default 50, hard cap 200."))
                },
                required = listOf("session_id"),
            )
        },
        execute = { input ->
            track("wm_state", "WebMount 状态", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId)
                    ?: error("session not found: $sessionId")
                val includeConsole = input.boolean("include_console") ?: true
                val consoleTail = (input.long("console_tail") ?: 16L).coerceIn(0L, 64L).toInt()
                val networkSince = (input.long("network_since") ?: 0L).coerceAtLeast(0L)
                val networkMax = (input.long("network_max") ?: 50L).coerceIn(0L, 200L).toInt()
                val args = buildJsonObject {
                    put("include_console", includeConsole)
                    put("console_tail", consoleTail)
                }
                val bridgePayload: JsonElement = runCatching { handle.callBridge("state", args) }
                    .getOrElse { error ->
                        // Bridge failure is recoverable — surface partial state from Kotlin side.
                        val ls = handle.loadState.value
                        return@getOrElse buildJsonObject {
                            put("url", ls.currentUrl)
                            put("title", ls.title)
                            put("ready_state", "unknown")
                            put("bridge_error", error.message ?: error.toString())
                        }
                    }
                val ls = handle.loadState.value
                val networkSnap = handle.networkLog.snapshot(networkSince, networkMax)
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("status", ls.status.wireName)
                    put("load_progress", ls.progress)
                    put("requested_url", ls.requestedUrl)
                    put("committed_url", ls.committedUrl)
                    put("error", ls.error)
                    put("updated_at_ms", ls.updatedAtMs)
                    put("page", bridgePayload)
                    put("network", networkSnap)
                    put("network_total_events", handle.networkLog.totalEvents)
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        },
    )

    // ------------------------------------------------------------ wm_extract

    private val extractTool = Tool(
        name = "wm_extract",
        description = """
            Extract structured information from the current page of a WebMount session. Four modes:
            `readable` (default) returns innerText + outbound links (compatible with the legacy webview_read
            payload); `interactive` returns only clickable elements (a, button, input, [role=button|link|tab|menuitem]);
            `snapshot` returns a flattened tree of semantically interesting nodes with role / accessible name / rect /
            visibility — useful for the agent to find the next element to click; `html` returns the outerHTML of one
            selector. All modes cap output size to keep the agent's context manageable.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("mode", stringProp("'readable' (default) | 'interactive' | 'snapshot' | 'html'."))
                    put("max_chars", integerProp("readable/html: max characters to return."))
                    put("max_links", integerProp("readable: max links to return (default 20)."))
                    put("max_nodes", integerProp("interactive/snapshot: max nodes to return."))
                    put("visible_only", booleanProp("interactive/snapshot: skip hidden nodes (default true)."))
                    put("root_selector", stringProp("snapshot: limit the walk to a subtree. Default body."))
                    put("selector", stringProp("html: which element to serialize."))
                },
                required = listOf("session_id"),
            )
        },
        execute = { input ->
            track("wm_extract", "WebMount 提取", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    input.string("mode")?.let { put("mode", it) }
                    input.long("max_chars")?.let { put("max_chars", it) }
                    input.long("max_links")?.let { put("max_links", it) }
                    input.long("max_nodes")?.let { put("max_nodes", it) }
                    input.boolean("visible_only")?.let { put("visible_only", it) }
                    input.string("root_selector")?.let { put("root_selector", it) }
                    input.string("selector")?.let { put("selector", it) }
                }
                val payload = handle.callBridge("extract", args, timeoutMs = 15_000L)
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }
                listOf(UIMessagePart.Text(merged.toString()))
            }
        },
    )

    // --------------------------------------------------------------- wm_wait

    private val waitTool = Tool(
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
            track("wm_wait", "WebMount 等待", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
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

    // ------------------------------------------------------------- wm_click

    private val clickTool = Tool(
        name = "wm_click",
        description = """
            Click an element in a WebMount session. The element is resolved via the same selector grammar
            as wm_extract / wm_wait: plain CSS, `text=substring`, `xpath=expr`. The bridge focuses the
            element, dispatches mousedown/mouseup, then calls .click() so default actions (form submit,
            anchor navigation) fire. Hidden elements are scrolled into view once; if they remain hidden
            after that, the call fails rather than clicking the wrong thing.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("selector", stringProp("CSS / text=... / xpath=... selector for the target element."))
                    put("visible_only", booleanProp("Require the element to be visible (default true)."))
                },
                required = listOf("session_id", "selector"),
            )
        },
        needsApproval = true,
        execute = { input ->
            track("wm_click", "WebMount 点击", input) {
                val sessionId = input.requiredString("session_id")
                val selector = input.requiredString("selector")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    put("selector", selector)
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

    // -------------------------------------------------------------- wm_type

    private val typeTool = Tool(
        name = "wm_type",
        description = """
            Type text into an editable element (input/textarea/[contenteditable]) in a WebMount session.
            Fires `input` and `change` events so frontend frameworks observe the new value. Pass
            `press_enter=true` to dispatch a synthetic Enter after typing (useful for search boxes that
            submit on Enter). `clear=true` empties the field first.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("selector", stringProp("Selector for the editable element."))
                    put("text", stringProp("UTF-8 text to type."))
                    put("clear", booleanProp("Empty the field before typing (default false)."))
                    put("press_enter", booleanProp("Dispatch Enter keydown after typing (default false)."))
                },
                required = listOf("session_id", "selector", "text"),
            )
        },
        needsApproval = true,
        execute = { input ->
            track("wm_type", "WebMount 输入", input) {
                val sessionId = input.requiredString("session_id")
                val selector = input.requiredString("selector")
                val text = input.requiredString("text")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    put("selector", selector)
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

    // -------------------------------------------------------------- wm_eval

    private val evalTool = Tool(
        name = "wm_eval",
        description = """
            ⚠️ HIGH RISK. Evaluate arbitrary JavaScript in the WebMount session and return the
            result. The script runs INSIDE the page's origin with full DOM access — it can read
            any data the user has on that site (cookies, sessionStorage, localStorage), perform
            same-origin fetches with credentials, and mutate the page. This tool ALWAYS requires
            per-call human approval and cannot be bypassed by any auto-approve setting or prior
            in-run trust. Prefer the specific primitives (wm_click / wm_type / wm_extract /
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
            track("wm_eval", "WebMount JS 执行", input.safeEvalPreview()) {
                val sessionId = input.requiredString("session_id")
                val expression = input.requiredString("expression")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
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

    // ------------------------------------------------------------ wm_scroll

    private val scrollTool = Tool(
        name = "wm_scroll",
        description = """
            Scroll a WebMount session. Three mutually exclusive modes (in priority order):
            (1) `selector` scrolls the matched element into view; (2) `to` accepts "top" | "bottom",
            or absolute coordinates via `to_x` + `to_y`; (3) `by_x` + `by_y` scrolls relative to the
            current position. Reports the post-scroll {x, y}.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("selector", stringProp("Selector to scroll into view (CSS / text= / xpath=)."))
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
            track("wm_scroll", "WebMount 滚动", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    val selector = input.string("selector")
                    val to = input.string("to")
                    val toX = input.long("to_x")
                    val toY = input.long("to_y")
                    val byX = input.long("by_x")
                    val byY = input.long("by_y")
                    when {
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

    // ----------------------------------------------- wm_back / wm_forward

    private val backTool = Tool(
        name = "wm_back",
        description = "Step the WebMount session's history one page backwards (window.history.back).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                },
                required = listOf("session_id"),
            )
        },
        execute = { input ->
            track("wm_back", "WebMount 后退", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val payload = handle.callBridge("back", buildJsonObject {}, timeoutMs = 3_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    private val forwardTool = Tool(
        name = "wm_forward",
        description = "Step the WebMount session's history one page forwards (window.history.forward).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                },
                required = listOf("session_id"),
            )
        },
        execute = { input ->
            track("wm_forward", "WebMount 前进", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val payload = handle.callBridge("forward", buildJsonObject {}, timeoutMs = 3_000L)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("result", payload)
                }.toString()))
            }
        },
    )

    // -------------------------------------------------------------- wm_keys

    private val keysTool = Tool(
        name = "wm_keys",
        description = """
            Dispatch a synthetic keyboard event in a WebMount session. Useful for Enter / Escape / Tab /
            arrow keys after wm_type when the field doesn't auto-submit. Modifiers can be combined
            (ctrl, shift, alt, meta). If `selector` is provided, focus moves to that element first;
            otherwise the event targets the currently-focused element.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("key", stringProp("Key name: 'Enter', 'Escape', 'Tab', 'ArrowDown', 'a', etc."))
                    put("selector", stringProp("Optional selector to focus before sending the key."))
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
            track("wm_keys", "WebMount 键盘", input) {
                val sessionId = input.requiredString("session_id")
                val key = input.requiredString("key")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val mods = buildJsonObject {
                    input.boolean("ctrl")?.let { put("ctrl", it) }
                    input.boolean("shift")?.let { put("shift", it) }
                    input.boolean("alt")?.let { put("alt", it) }
                    input.boolean("meta")?.let { put("meta", it) }
                }
                val args = buildJsonObject {
                    put("key", key)
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

    // ------------------------------------------------------------ wm_select

    private val selectTool = Tool(
        name = "wm_select",
        description = """
            Choose an option in a <select> dropdown. Matches `value` against both option.value and the
            visible option text — so the agent can use whichever it sees in wm_extract. Fires
            input + change events so frontend frameworks observe the new selection.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("selector", stringProp("Selector for the <select> element."))
                    put("value", stringProp("Option value or visible text to choose."))
                },
                required = listOf("session_id", "selector", "value"),
            )
        },
        needsApproval = true,
        execute = { input ->
            track("wm_select", "WebMount 选择", input) {
                val sessionId = input.requiredString("session_id")
                val selector = input.requiredString("selector")
                val value = input.requiredString("value")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val args = buildJsonObject {
                    put("selector", selector)
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

    // -------------------------------------------------------------- wm_find

    private val findTool = Tool(
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
            track("wm_find", "WebMount 查找", input) {
                val sessionId = input.requiredString("session_id")
                val text = input.requiredString("text")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
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

    // ----------------------------------------------------------- wm_tab_*

    private val tabListTool = Tool(
        name = "wm_tab_list",
        description = """
            List every live WebMount session in the pool with {session_id, url, title, status}.
            Use this before wm_tab_close to see which sessions are open and what they hold.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {})
        },
        execute = { _ ->
            track("wm_tab_list", "WebMount 列出会话", buildJsonObject {}) {
                val sessions = pool.listSessions().map { handle ->
                    val ls = handle.loadState.value
                    buildJsonObject {
                        put("session_id", handle.sessionId)
                        put("url", ls.currentUrl ?: ls.requestedUrl)
                        put("title", ls.title)
                        put("status", ls.status.wireName)
                        put("destroyed", handle.destroyed)
                    }
                }
                val payload = buildJsonObject {
                    put("count", sessions.size)
                    put("sessions", buildJsonArray { sessions.forEach { add(it) } })
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    private val tabNewTool = Tool(
        name = "wm_tab_new",
        description = """
            Allocate a brand-new WebMount session without navigating. Returns the freshly-issued
            session_id. The session is empty (about:blank) — the agent typically follows with
            wm_open to load the first URL. Useful when the agent wants to work on two pages in
            parallel; tools on different sessions run concurrently (per-session parallel groups).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {})
        },
        execute = { _ ->
            track("wm_tab_new", "WebMount 新建会话", buildJsonObject {}) {
                val handle = pool.acquireNew()
                val payload = buildJsonObject {
                    put("session_id", handle.sessionId)
                    put("status", handle.loadState.value.status.wireName)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    private val tabCloseTool = Tool(
        name = "wm_tab_close",
        description = """
            Destroy a WebMount session and release its WebView. After this, the session_id is no longer
            valid — subsequent calls referencing it fail. Use to free memory when the agent is done
            with a long-running session; the pool also LRU-evicts automatically at its capacity.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id to close."))
                    put("reason", stringProp("Optional reason recorded in logs."))
                },
                required = listOf("session_id"),
            )
        },
        // Closing its own session is not a security action — auto-approvable to
        // avoid training users to click "Approve" reflexively on a confirmation
        // that doesn't carry real risk. Reverses the M1.4 review over-correction.
        execute = { input ->
            track("wm_tab_close", "WebMount 关闭会话", input) {
                val sessionId = input.requiredString("session_id")
                val reason = input.string("reason") ?: "agent requested"
                pool.release(sessionId, reason)
                val payload = buildJsonObject {
                    put("session_id", sessionId)
                    put("closed", true)
                    put("reason", reason)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    // ---------------------------------------------------------- wm_screenshot

    private val screenshotTool = Tool(
        name = "wm_screenshot",
        description = """
            Capture the current page of a WebMount session and return its base64-encoded image in
            the tool result text (a `data:` URI). `full_page=false` (default) captures the visible
            viewport; `full_page=true` scrolls + stitches up to ~16384px tall. Uses native Android
            Bitmap rendering with a software-rendering fallback for hardware-accelerated WebViews.
            Heads up: full-page PNGs are large; for full-page captures prefer format="jpeg" with
            quality=75-85 to keep the agent's context manageable.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id."))
                    put("full_page", booleanProp("Stitch scrolled slices (default false)."))
                    put("format", stringProp("'png' (default) | 'jpeg'."))
                    put("quality", integerProp("JPEG quality 1-100, default 85. PNG ignores this."))
                },
                required = listOf("session_id"),
            )
        },
        execute = { input ->
            track("wm_screenshot", "WebMount 截图", input) {
                val sessionId = input.requiredString("session_id")
                val handle = pool.peek(sessionId) ?: error("session not found: $sessionId")
                val fullPage = input.boolean("full_page") ?: false
                val format = when (input.string("format")?.lowercase()) {
                    "jpeg", "jpg" -> WebViewScreenshot.Format.JPEG
                    else -> WebViewScreenshot.Format.PNG
                }
                val quality = (input.long("quality") ?: 85L).coerceIn(1L, 100L).toInt()
                val result = WebViewScreenshot.capture(handle, fullPage, format, quality)
                when (result) {
                    is WebViewScreenshot.Result.Success -> {
                        // Inline the base64 in the Text payload — every provider's
                        // tool-result serializer drops UIMessagePart.Image, so the
                        // model never sees standalone Image parts on a tool return.
                        // The wm_screenshot Tool's `outputBudgetChars` is bumped
                        // (see ToolRegistry.outputBudgetChars) so the base64 fits.
                        val mime = if (result.format == "jpeg") "image/jpeg" else "image/png"
                        val payload = buildJsonObject {
                            put("session_id", sessionId)
                            put("ok", true)
                            put("width", result.width)
                            put("height", result.height)
                            put("format", result.format)
                            put("size_bytes", result.sizeBytes)
                            put("full_page", fullPage)
                            put("image_data_url", "data:$mime;base64,${result.base64}")
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                    is WebViewScreenshot.Result.Failed -> {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("session_id", sessionId)
                            put("ok", false)
                            put("error", result.message)
                        }.toString()))
                    }
                }
            }
        },
    )

    // -------------------------------------------------------- wm_stations

    /**
     * Phase 2 M2.0.4 — `wm_stations` introspection.
     *
     * Returns a flat snapshot of every WebMount station the user has
     * configured (HN / Reddit / 飞书 / GitHub / Bilibili / 知乎 / 掘金 today;
     * future additions automatic). The agent uses this to decide whether to
     * prefer adapter tools (already authenticated) or fall back to generic
     * `wm_*` primitives.
     *
     * The output schema reserves `applicable_profile` / `has_profile` /
     * `login_indicator` fields — populated by M2.1 (Site Profile mechanism)
     * once that ships. Today they are always null/false so callers can
     * already key on them without breaking when M2.1 lands.
     */
    private val stationsTool = Tool(
        name = "wm_stations",
        description = """
            List every WebMount station registered in this app along with its current status
            (enabled, authenticated, read_only/read_write/login_required/...), display name,
            and the auth methods it supports. Read-only and side-effect-free; safe to call
            before deciding whether to use an adapter tool (e.g. github_repo_search) versus
            a generic primitive (wm_open + wm_extract). Includes `applicable_profile_id` and
            `has_profile` slots reserved for Phase 2 M2.1 (currently always null/false).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("include_disabled", booleanProp("Include stations the user has not enabled. Default true."))
                    put("status_filter", stringProp("Optional wire status to filter by: not_configured / login_required / probing / read_only / read_write / degraded / error."))
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            track("wm_stations", "WebMount 站点列表", input) {
                val includeDisabled = input.boolean("include_disabled") ?: true
                val rawFilter = input.string("status_filter")?.lowercase()?.takeIf { it.isNotBlank() }
                // M2.0 review W-5 fix: validate status_filter against the
                // enum's wireName set so a typo like "online" doesn't silently
                // produce zero results — surface a warning the agent can act on.
                val validStatuses = me.rerere.rikkahub.data.agent.webmount.core.WebMountStatus.entries
                    .map { it.wireName }
                    .toSet()
                val statusFilter = rawFilter?.takeIf { it in validStatuses }
                val unknownFilter = rawFilter != null && statusFilter == null
                val states = manager.states.value.values
                    .asSequence()
                    .filter { includeDisabled || it.enabled }
                    .filter { statusFilter == null || it.status.wireName == statusFilter }
                    .toList()
                val payload = buildJsonObject {
                    put("count", states.size)
                    if (unknownFilter) {
                        put(
                            "warning",
                            "Unknown status_filter '$rawFilter'. Valid values: " +
                                validStatuses.sorted().joinToString(", ") +
                                ". Returning unfiltered results."
                        )
                    }
                    put(
                        "stations",
                        buildJsonArray {
                            states.forEach { s ->
                                add(buildJsonObject {
                                    put("id", s.id)
                                    put("display_name", s.displayName)
                                    put("enabled", s.enabled)
                                    put("status", s.status.wireName)
                                    put("capability", s.capability.wireName)
                                    put("auth_methods", buildJsonArray {
                                        s.authMethods.forEach { add(JsonPrimitive(it.wireName)) }
                                    })
                                    s.message?.let { put("message", it) }
                                    if (s.updatedAtMillis > 0) put("updated_at_ms", s.updatedAtMillis)
                                    // Phase 2 M2.1 reservations — agents can already key on
                                    // these without breaking when profiles land.
                                    put("has_profile", false)
                                    put("applicable_profile_id", JsonNull)
                                })
                            }
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        },
    )

    // ------------------------------------------------------------- helpers

    private suspend fun track(
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

    private fun JsonElement.safeUrlPreview(): JsonObject =
        buildJsonObject {
            put("url", string("url").orEmpty())
            put("session_id", string("session_id"))
            put("wait", string("wait"))
        }

    private fun JsonElement.safeEvalPreview(): JsonObject =
        buildJsonObject {
            put("session_id", string("session_id"))
            put("expression_chars", string("expression")?.length ?: 0)
        }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
}
