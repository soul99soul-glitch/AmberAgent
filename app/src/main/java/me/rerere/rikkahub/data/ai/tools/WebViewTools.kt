package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.webview.WebViewLoadStatus
import app.amber.feature.webview.WebViewOperationState
import app.amber.feature.webview.WebViewOperationStore
import java.net.URLEncoder
import java.util.Locale

/**
 * Factories for the seven AmberAgent WebView preview tools.
 *
 * The whole cluster shares a single [WebViewOperationStore] that owns the
 * currently-open WebView session (its load status, readable text, extracted
 * links, last error, etc.). Each tool either commands the store (open/follow
 * link) or queries it (read/wait/find/list links).
 *
 * Top-level factories — each takes the store explicitly so the tools can be
 * built outside [LocalTools] without dragging its full ctor in. LocalTools
 * keeps thin `by lazy { createWebView*Tool(store) }` delegators.
 *
 * Private helpers (await-and-poll loop + state classifiers + payload shape)
 * live at file scope so multiple factories in this file can share them
 * without exposing them to the wider tools package.
 *
 * Extracted from `LocalTools.webView*Tool` in M1.4 continuation.
 */

fun createWebViewOpenTool(store: WebViewOperationStore): Tool = Tool(
    name = "webview_open",
    description = """
        Open a URL in AmberAgent's live operation preview WebView.
        Prefer this early when the user asks to open, browse, view, inspect, or visually verify a webpage, or when search results should be shown in the live preview.
        After opening, call webview_wait_for_load or webview_read(wait_timeout_ms=...) when you need the current page title, readable text, or links.
        Use search_web or scrape_web when you need search results or deeper page extraction.
        Do not try to open the Android System WebView package as an app; the preview WebView is embedded inside AmberAgent.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http or https URL to open in the AmberAgent WebView preview")
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "Short reason for opening the page")
                })
            },
            required = listOf("url")
        )
    },
    execute = {
        val params = it.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: error("url is required")
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Only http and https URLs can be opened in WebView"
        }
        val loadId = store.open(url)
        val payload = buildJsonObject {
            put("url", url)
            put("load_id", loadId)
            put("runtime", "webview")
            put("status", "opened")
            params["reason"]?.jsonPrimitive?.contentOrNull?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                put("reason", reason)
            }
            put("note", "The live preview is loading. Use webview_wait_for_load or webview_read with wait_timeout_ms before relying on page text.")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun createWebViewSearchOpenTool(store: WebViewOperationStore): Tool = Tool(
    name = "webview_search_open",
    description = """
        Open a visible search results page in AmberAgent's live WebView preview.
        Use this as a fallback when search_web reports weak/empty ordinary sources, when visual verification is needed, or when the user explicitly wants Google/DuckDuckGo/Bing results opened.
        After opening, call webview_wait_for_load and webview_links or webview_read to inspect visible results.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query")
                })
                put("engine", buildJsonObject {
                    put("type", "string")
                    put("description", "Search engine to open")
                    put(
                        "enum",
                        buildJsonArray {
                            add("google")
                            add("duckduckgo")
                            add("bing")
                        }
                    )
                })
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "Short reason for opening a visible search page")
                })
            },
            required = listOf("query")
        )
    },
    execute = { input ->
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: error("query is required")
        val engine = input.jsonObject["engine"]?.jsonPrimitive?.contentOrNull
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in setOf("google", "duckduckgo", "bing") }
            ?: "google"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = when (engine) {
            "duckduckgo" -> "https://duckduckgo.com/?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
        val loadId = store.open(url)
        val payload = buildJsonObject {
            put("status", "opened")
            put("runtime", "webview")
            put("engine", engine)
            put("query", query)
            put("url", url)
            put("load_id", loadId)
            input.jsonObject["reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                put("reason", it)
            }
            put("note", "The visible search page is loading. Use webview_wait_for_load and webview_links/webview_read to inspect results.")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun createWebViewReadTool(store: WebViewOperationStore): Tool = Tool(
    name = "webview_read",
    description = """
        Read the currently opened AmberAgent WebView page.
        Use after webview_open when you need the page title, readable visible text, current URL, or page links.
        By default this waits briefly for ready or partial content, then returns status=ready/interactive/loading/stalled/failed/no_page.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum readable text characters to return. Defaults to 8000.")
                })
                put("max_links", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum links to return. Defaults to 20.")
                })
                put("wait_timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Wait up to this many milliseconds for ready or partial content. Defaults to 6000. Use 0 for immediate read.")
                })
                put("accept_partial", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether interactive or stalled pages with partial content are acceptable. Defaults to true.")
                })
            }
        )
    },
    execute = { input ->
        val maxChars = input.jsonObject["max_chars"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()
            ?.coerceIn(500, 40_000)
            ?: 8_000
        val maxLinks = input.jsonObject["max_links"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()
            ?.coerceIn(0, 40)
            ?: 20
        val waitTimeoutMs = input.jsonObject["wait_timeout_ms"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull()
            ?.coerceIn(0L, 30_000L)
            ?: 6_000L
        val acceptPartial = input.jsonObject["accept_partial"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: true
        val state = awaitWebViewState(
            store = store,
            timeoutMs = waitTimeoutMs,
            minTextChars = 80,
            acceptPartial = acceptPartial,
            targetUrl = null,
        )
        listOf(
            UIMessagePart.Text(
                state.toWebViewPayload(
                    maxChars = maxChars,
                    maxLinks = maxLinks,
                    acceptPartial = acceptPartial,
                ).toString()
            )
        )
    }
)

fun createWebViewWaitForLoadTool(store: WebViewOperationStore): Tool = Tool(
    name = "webview_wait_for_load",
    description = """
        Wait for the currently opened AmberAgent WebView page to become ready or partially readable.
        Use this right after webview_open before reading JS-heavy pages, search result pages, or pages that need visual rendering.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum wait time in milliseconds. Defaults to 10000.")
                })
                put("min_text_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minimum readable text characters before considering the page useful. Defaults to 80.")
                })
                put("accept_partial", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether interactive or stalled pages with partial content are acceptable. Defaults to true.")
                })
                put("target_url", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional URL that must match the currently loading page.")
                })
            }
        )
    },
    execute = { input ->
        val timeoutMs = input.jsonObject["timeout_ms"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull()
            ?.coerceIn(500L, 60_000L)
            ?: 10_000L
        val minTextChars = input.jsonObject["min_text_chars"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()
            ?.coerceIn(0, 40_000)
            ?: 80
        val acceptPartial = input.jsonObject["accept_partial"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: true
        val targetUrl = input.jsonObject["target_url"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val state = awaitWebViewState(
            store = store,
            timeoutMs = timeoutMs,
            minTextChars = minTextChars,
            acceptPartial = acceptPartial,
            targetUrl = targetUrl,
        )
        listOf(
            UIMessagePart.Text(
                state.toWebViewPayload(
                    maxChars = 2_000,
                    maxLinks = 10,
                    acceptPartial = acceptPartial,
                    targetUrl = targetUrl,
                ).toString()
            )
        )
    }
)

fun createWebViewFindTextTool(store: WebViewOperationStore): Tool = Tool(
    name = "webview_find_text",
    description = "Find text in the currently opened AmberAgent WebView readable text. Call webview_open first.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to find in the current page")
                })
                put("case_sensitive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether matching should be case-sensitive. Defaults to false.")
                })
            },
            required = listOf("query")
        )
    },
    execute = { input ->
        val state = store.refreshStalled()
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull ?: error("query is required")
        val caseSensitive = input.jsonObject["case_sensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val text = state.readableText
        val matches = buildJsonArray {
            if (query.isNotBlank()) {
                var start = 0
                var matchCount = 0
                while (start < text.length && matchCount < 20) {
                    val index = text.indexOf(query, start, ignoreCase = !caseSensitive)
                    if (index < 0) break
                    val snippetStart = (index - 120).coerceAtLeast(0)
                    val snippetEnd = (index + query.length + 120).coerceAtMost(text.length)
                    add(
                        buildJsonObject {
                            put("offset", index)
                            put("snippet", text.substring(snippetStart, snippetEnd))
                        }
                    )
                    matchCount++
                    start = index + query.length.coerceAtLeast(1)
                }
            }
        }
        val payload = buildJsonObject {
            put("status", if (state.hasPage) state.statusValue else "no_page")
            put("load_id", state.loadId)
            put("url", state.displayUrl)
            put("title", state.title)
            put("query", query)
            put("matches", matches)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun createWebViewLinksTool(store: WebViewOperationStore): Tool = Tool(
    name = "webview_links",
    description = "List links extracted from the current AmberAgent WebView page. Call webview_open first.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional title or URL filter")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum links to return. Defaults to 40.")
                })
            }
        )
    },
    execute = { input ->
        val state = store.refreshStalled()
        val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 80) ?: 40
        val links = state.links.filter { link ->
            query.isBlank() ||
                link.title.contains(query, ignoreCase = true) ||
                link.url.contains(query, ignoreCase = true)
        }
        val payload = buildJsonObject {
            put("status", if (state.hasPage) state.statusValue else "no_page")
            put("load_id", state.loadId)
            put("url", state.displayUrl)
            put(
                "links",
                buildJsonArray {
                    links.take(limit).forEachIndexed { index, link ->
                        add(
                            buildJsonObject {
                                put("index", index)
                                put("title", link.title)
                                put("url", link.url)
                            }
                        )
                    }
                }
            )
            put("total_matches", links.size)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun createWebViewOpenLinkTool(store: WebViewOperationStore): Tool = Tool(
    name = "webview_open_link",
    description = "Open a link from the current WebView link list by index, or open a provided URL in the AmberAgent WebView.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Index from webview_links")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Explicit http/https URL to open")
                })
            }
        )
    },
    execute = { input ->
        val state = store.state.value
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: input.jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.let { index -> state.links.getOrNull(index)?.url }
            ?: error("url or valid index is required")
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Only http and https URLs can be opened in WebView"
        }
        val loadId = store.open(url)
        val payload = buildJsonObject {
            put("status", "opened")
            put("url", url)
            put("load_id", loadId)
            put("runtime", "webview")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private suspend fun awaitWebViewState(
    store: WebViewOperationStore,
    timeoutMs: Long,
    minTextChars: Int,
    acceptPartial: Boolean,
    targetUrl: String?,
): WebViewOperationState {
    val deadline = System.currentTimeMillis() + timeoutMs
    var state = store.refreshStalled()
    if (!state.hasPage || timeoutMs <= 0L) return state
    while (true) {
        state = store.refreshStalled()
        if (state.isUsefulWebViewState(minTextChars, acceptPartial, targetUrl)) {
            return state
        }
        if (System.currentTimeMillis() >= deadline) {
            return state
        }
        delay(250L)
    }
}

private fun WebViewOperationState.isUsefulWebViewState(
    minTextChars: Int,
    acceptPartial: Boolean,
    targetUrl: String?,
): Boolean {
    if (!hasPage) return true
    if (!matchesTargetUrl(targetUrl)) return false
    if (status == WebViewLoadStatus.FAILED) return true
    val enoughText = readableText.length >= minTextChars
    val hasUsefulContent = enoughText || (minTextChars == 0 && hasReadableContent)
    return when (status) {
        WebViewLoadStatus.READY -> true
        WebViewLoadStatus.INTERACTIVE -> acceptPartial && hasUsefulContent
        WebViewLoadStatus.STALLED -> acceptPartial && hasReadableContent
        else -> false
    }
}

private fun WebViewOperationState.matchesTargetUrl(targetUrl: String?): Boolean {
    if (targetUrl.isNullOrBlank()) return true
    val target = targetUrl.trim()
    return requestedUrl == target ||
        committedUrl == target ||
        url == target ||
        displayUrl == target ||
        displayUrl.startsWith(target) ||
        target.startsWith(displayUrl) && displayUrl.isNotBlank()
}

private fun WebViewOperationState.toWebViewPayload(
    maxChars: Int,
    maxLinks: Int,
    acceptPartial: Boolean,
    targetUrl: String? = null,
): JsonObject = buildJsonObject {
    val statusString = if (hasPage) statusValue else "no_page"
    val visibleText = readableText.take(maxChars)
    put("status", statusString)
    put("ready", status == WebViewLoadStatus.READY)
    put("partial", acceptPartial && status in setOf(WebViewLoadStatus.INTERACTIVE, WebViewLoadStatus.STALLED) && hasReadableContent)
    put("stalled", status == WebViewLoadStatus.STALLED)
    put("failed", status == WebViewLoadStatus.FAILED)
    put("load_id", loadId)
    put("requested_url", requestedUrl)
    put("url", displayUrl)
    put("committed_url", committedUrl)
    put("title", title)
    put("loading_progress", loadingProgress)
    put("text", visibleText)
    put("text_chars", readableText.length)
    put("truncated", readableText.length > visibleText.length)
    put(
        "links",
        buildJsonArray {
            links.take(maxLinks).forEach { link ->
                add(
                    buildJsonObject {
                        put("title", link.title)
                        put("url", link.url)
                    }
                )
            }
        }
    )
    put("total_links", links.size)
    if (thumbnailPath.isNotBlank()) {
        put("thumbnail_path", thumbnailPath)
    }
    if (lastError.isNotBlank()) {
        put("error", lastError)
    }
    if (targetUrl != null) {
        put("target_url", targetUrl)
        put("target_matched", matchesTargetUrl(targetUrl))
    }
    if (!hasPage) {
        put("error", "No WebView page is open. Call webview_open first.")
    }
    put("opened_at_ms", openedAtEpochMillis)
    put("last_progress_at_ms", lastProgressAtEpochMillis)
    put("last_extract_at_ms", lastExtractAtEpochMillis)
    put("updated_at_ms", updatedAtEpochMillis)
}
