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
