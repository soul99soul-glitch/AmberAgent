package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import me.rerere.rikkahub.data.agent.webmount.primitives.SessionHandle
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewPool

/**
 * Browser Primitives tool catalog (M1.3).
 *
 * Each `wm_*` tool is a thin shim over [WebViewPool]: parse the agent's
 * JSON input, call into the pool / session, format the result as JSON text.
 *
 * M1.3.1 ships: `wm_open`, `wm_state`. Subsequent sub-milestones (M1.3.2+)
 * extend [getTools] with `wm_extract`, `wm_wait`, `wm_click`, `wm_type`,
 * `wm_eval`, and the rest of the catalog.
 *
 * **Default OFF**: the LocalTools aggregator only includes these when the
 * user enables `LocalToolOption.WebMount` per assistant.
 */
class WebMountPrimitiveTools(
    private val pool: WebViewPool,
    private val activityStore: AgentToolActivityStore,
) {

    fun getTools(): List<Tool> = listOf(
        openTool,
        stateTool,
        extractTool,
        waitTool,
        clickTool,
        typeTool,
        evalTool,
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
                    // Issue load but don't wait.
                    handle.webView.post { handle.webView.loadUrl(url) }
                    buildJsonObject {
                        put("session_id", handle.sessionId)
                        put("status", SessionHandle.LoadStatus.LOADING.wireName)
                        put("url", url)
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
            scroll position, and a tail of console messages. Useful for polling after `wm_open wait="none"`
            or for checking whether a page has finished loading.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session id returned by wm_open."))
                    put("include_console", booleanProp("Include recent console messages (default true)."))
                    put("console_tail", integerProp("How many console entries to include. Default 16, max 64."))
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
                val merged = buildJsonObject {
                    put("session_id", sessionId)
                    put("status", ls.status.wireName)
                    put("load_progress", ls.progress)
                    put("requested_url", ls.requestedUrl)
                    put("committed_url", ls.committedUrl)
                    put("error", ls.error)
                    put("updated_at_ms", ls.updatedAtMs)
                    put("page", bridgePayload)
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
            Evaluate arbitrary JavaScript in the WebMount session and return the result. High-risk
            because the script runs with the user's logged-in cookies and full DOM access — always needs
            explicit human approval. Use the more specific primitives (wm_click, wm_type, wm_extract)
            when they suffice. The expression's return value is serialized to JSON; non-JSON-friendly
            values fall back to a string coercion.
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
