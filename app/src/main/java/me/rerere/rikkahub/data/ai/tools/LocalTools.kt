package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.tools.AgentCronTools
import me.rerere.rikkahub.data.agent.tools.FeishuOfficeTools
import me.rerere.rikkahub.data.agent.tools.ICloudDriveTools
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.tools.WebMountPrimitiveTools
import me.rerere.rikkahub.data.agent.tools.ExternalFileTools
import me.rerere.rikkahub.data.agent.tools.ScreenAutomationTools
import me.rerere.rikkahub.data.agent.tools.SystemAccessTools
import me.rerere.rikkahub.data.agent.tools.TerminalTools
import me.rerere.rikkahub.data.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.agent.tools.WorkspaceArtifactTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceTools
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.getCurrentImageGenerationModel
import me.rerere.rikkahub.data.repository.ImageGenerationRepository
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.rikkahub.data.agent.webview.WebViewLoadStatus
import me.rerere.rikkahub.data.agent.webview.WebViewOperationState
import me.rerere.rikkahub.data.agent.webview.WebViewOperationStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("workspace_files")
    data object WorkspaceFiles : LocalToolOption()

    @Serializable
    @SerialName("terminal")
    data object Terminal : LocalToolOption()

    @Serializable
    @SerialName("screen_automation")
    data object ScreenAutomation : LocalToolOption()

    @Serializable
    @SerialName("system_access")
    data object SystemAccess : LocalToolOption()

    @Serializable
    @SerialName("webview")
    data object WebView : LocalToolOption()

    @Serializable
    @SerialName("icloud_drive")
    data object ICloudDrive : LocalToolOption()

    @Serializable
    @SerialName("webmount")
    data object WebMount : LocalToolOption()

    /**
     * Secondary toggle that enables [WebMountPrimitiveTools.evalTool] (`wm_eval`)
     * in addition to the safe primitives gated by [WebMount]. Default OFF.
     *
     * `wm_eval` runs arbitrary JavaScript inside a logged-in WebView origin —
     * it can read cookies / sessionStorage / localStorage, perform same-origin
     * fetches with credentials, and mutate the page. The framework routes it
     * through Tool.mandatoryApproval, so ordinary auto-approval cannot run it;
     * only the explicit high-risk auto-approval setting may bypass the prompt.
     * The conservative default is to keep the tool entirely out of the agent's
     * catalog unless the user opts in here.
     */
    @Serializable
    @SerialName("webmount_eval")
    data object WebMountEval : LocalToolOption()
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val workspaceTools: WorkspaceTools,
    private val terminalTools: TerminalTools,
    private val screenAutomationTools: ScreenAutomationTools,
    private val systemAccessTools: SystemAccessTools,
    private val workspaceArtifactTools: WorkspaceArtifactTools,
    private val externalFileTools: ExternalFileTools,
    private val permissionBroker: AgentPermissionBroker,
    private val webViewOperationStore: WebViewOperationStore,
    private val iCloudDriveTools: ICloudDriveTools,
    private val feishuOfficeTools: FeishuOfficeTools,
    private val agentCronTools: AgentCronTools,
    private val webMountPrimitiveTools: WebMountPrimitiveTools,
    private val webMountManager: WebMountManager,
    private val userSiteRegistry: me.rerere.rikkahub.data.agent.webmount.usersites.UserSiteRegistry,
    private val settingsStore: SettingsAggregator,
    private val imageGenerationRepository: ImageGenerationRepository,
) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = """
                Execute JavaScript code using QuickJS engine (ES2020).
                The result is the value of the last expression in the code.
                For calculations with decimals, use toFixed() to control precision.
                Console output (log/info/warn/error) is captured and returned in 'logs' field.
                No DOM or Node.js APIs available.
                Do not use this tool to create SVG, HTML, charts, diagrams, or generative UI widgets; write those directly in the assistant response.
                Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "The JavaScript code to execute")
                        })
                    },
                    required = listOf("code")
                )
            },
            execute = {
                val logs = arrayListOf<String>()
                val context = QuickJSContext.create()
                context.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) {
                        logs.add("[LOG] $info")
                    }

                    override fun info(info: String?) {
                        logs.add("[INFO] $info")
                    }

                    override fun warn(info: String?) {
                        logs.add("[WARN] $info")
                    }

                    override fun error(info: String?) {
                        logs.add("[ERROR] $info")
                    }
                })
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val result = context.evaluate(code)
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val timeTool by lazy { createTimeTool() }

    val clipboardTool by lazy {
        createClipboardTool(context)
    }

    val webViewTool by lazy {
        Tool(
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
                val loadId = webViewOperationStore.open(url)
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
    }

    val webViewSearchOpenTool by lazy {
        Tool(
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
                val loadId = webViewOperationStore.open(url)
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
    }

    val webViewReadTool by lazy {
        Tool(
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
    }

    val webViewWaitForLoadTool by lazy {
        Tool(
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
    }

    val webViewFindTextTool by lazy {
        Tool(
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
                val state = webViewOperationStore.refreshStalled()
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
    }

    val webViewLinksTool by lazy {
        Tool(
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
                val state = webViewOperationStore.refreshStalled()
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
    }

    val webViewOpenLinkTool by lazy {
        Tool(
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
                val state = webViewOperationStore.state.value
                val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: input.jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        ?.let { index -> state.links.getOrNull(index)?.url }
                    ?: error("url or valid index is required")
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "Only http and https URLs can be opened in WebView"
                }
                val loadId = webViewOperationStore.open(url)
                val payload = buildJsonObject {
                    put("status", "opened")
                    put("url", url)
                    put("load_id", loadId)
                    put("runtime", "webview")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    private suspend fun awaitWebViewState(
        timeoutMs: Long,
        minTextChars: Int,
        acceptPartial: Boolean,
        targetUrl: String?,
    ): WebViewOperationState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var state = webViewOperationStore.refreshStalled()
        if (!state.hasPage || timeoutMs <= 0L) return state
        while (true) {
            state = webViewOperationStore.refreshStalled()
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
    ) = buildJsonObject {
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

    val ttsTool by lazy {
        Tool(
            name = "text_to_speech",
            description = """
                Speak text aloud to the user using the device's text-to-speech engine.
                Use this when the user asks you to read something aloud, or when audio output is appropriate.
                The tool returns immediately; audio plays in the background on the device.
                Provide natural, readable text without markdown formatting.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "The text to speak aloud")
                        })
                    },
                    required = listOf("text")
                )
            },
            execute = {
                val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("text is required")
                eventBus.emit(AppEvent.Speak(text))
                val payload = buildJsonObject {
                    put("success", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val askUserTool by lazy { createAskUserTool() }

    fun toolsListTool(registry: ToolRegistry): Tool =
        createToolsListTool(registry, permissionBroker)

    fun createToolPolicyExplainTool(registry: ToolRegistry) = Tool(
        name = "tool_policy_explain",
        description = "Explain how AmberAgent would evaluate one tool invocation without executing it.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tool_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Tool name to evaluate.")
                    })
                    put("input", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional JSON string input for dynamic policy evaluation.")
                    })
                },
                required = listOf("tool_name")
            )
        },
        execute = { input ->
            val toolName = input.jsonObject["tool_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rawInput = input.jsonObject["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val toolInput = runCatching {
                JsonInstant.parseToJsonElement(rawInput.ifBlank { "{}" })
            }.getOrNull()
            val policy = registry.evaluateInvocation(toolName, toolInput)
            val payload = buildJsonObject {
                put("status", if (policy == null) "not_found" else "ok")
                put("tool_name", toolName)
                policy?.let {
                    put("category", it.category)
                    put("risk", it.risk.name.lowercase())
                    put("mutates", it.mutates)
                    put("needs_approval", it.needsApproval)
                    put("allows_auto_approval", it.autoApprovable)
                    put("concurrency_safe", it.concurrencySafe)
                    it.parallelGroup?.let { group -> put("parallel_group", group) }
                    it.requiresForegroundAppPackage?.let { pkg -> put("requires_foreground_app_package", pkg) }
                    put("speculative_eligible", it.speculativeEligible)
                    it.speculativeBlockReason?.let { reason -> put("speculative_block_reason", reason) }
                    put("output_budget_chars", it.outputBudgetChars)
                    put("hard_blocked", it.hardBlocked)
                    it.reason?.let { reason -> put("reason", reason) }
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private val permissionsStatusTool by lazy { createPermissionsStatusTool(permissionBroker) }

    private val runPlanUpdateTool by lazy { createRunPlanUpdateTool() }

    /**
     * Build a fresh `generate_image` tool bound to [conversationId]. Each
     * conversation gets its own tool instance because the execute lambda needs
     * to know where on disk to write the resulting images (per-conversation
     * subdir). The tool is only included when the current assistant — or the
     * global Settings — has an image-generation model configured.
     */
    private fun buildImageGenTool(conversationId: Uuid): Tool = Tool(
        name = "generate_image",
        description = """
            Generate photographic, painted, illustrated, or otherwise textured
            raster imagery from a text prompt using the user's configured
            image-generation model. Best fits: landscapes, portraits, photo-
            realistic scenes, paintings (oil / watercolor / ink), concept art,
            illustrations, posters, book / album covers, wallpapers, character
            art, food photography, product mockups — anything where the value
            of the result depends on visual depth, lighting, texture, or
            aesthetic richness that vector code cannot fake. For purely
            structural visualizations — flowcharts, architecture diagrams,
            org charts, sequence / class / state diagrams, mind maps,
            schematics, simple line-art logos, math / data plots — prefer
            emitting an SVG show-widget block instead, since precision and
            editability matter more than visual richness there. (You CAN
            still call this tool for those if the user explicitly asks for
            an artistic / painted / textured rendering of structural content
            — they want art, not a clean diagram.) Prefer detailed English
            prompts that specify subject, style, composition, lighting, and
            mood — image models follow them more reliably. Generated images
            are bound to this conversation; deleting the conversation removes
            them. The user sees them inline and can save / share via
            long-press.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Detailed description of the desired image. Include subject, style, composition, lighting, mood."
                        )
                    })
                    put("aspect_ratio", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray { add("1:1"); add("16:9"); add("9:16") }
                        )
                        put("description", "Image aspect ratio. Default 1:1 (square).")
                    })
                    put("count", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 4)
                        put("description", "Number of variants to generate, 1-4. Default 1. Only request multiple variants when the user explicitly asks for choices.")
                    })
                },
                required = listOf("prompt")
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(prompt.isNotEmpty()) { "prompt is required and must not be blank" }
            val aspectRatio = when (obj["aspect_ratio"]?.jsonPrimitive?.contentOrNull) {
                "16:9" -> ImageAspectRatio.LANDSCAPE
                "9:16" -> ImageAspectRatio.PORTRAIT
                "1:1", null -> ImageAspectRatio.SQUARE
                else -> ImageAspectRatio.SQUARE
            }
            val count = (obj["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, 4)

            val settings = settingsStore.settingsFlow.value
            val model = settings.getCurrentImageGenerationModel()
                ?: error("No image generation model configured. Please ask the user to set one in the Assistant settings page.")

            val files = imageGenerationRepository.generateForConversation(
                modelId = model.id,
                prompt = prompt,
                aspectRatio = aspectRatio,
                numOfImages = count,
                conversationId = conversationId,
            ).getOrThrow()

            // Build output: Image parts first (so the timeline renders cards
            // up top) followed by a single Text summary so the main chat model
            // has a structured handle on the result for its follow-up reply.
            val parts = mutableListOf<UIMessagePart>()
            files.forEach { saved ->
                parts.add(
                    UIMessagePart.Image(
                        url = "file://${saved.file.absolutePath}",
                        metadata = buildJsonObject {
                            put("source", "generate_image")
                            put("prompt", prompt)
                            put("aspect_ratio", aspectRatio.name)
                            put("model", saved.modelDisplayName)
                        }
                    )
                )
            }
            val summary = buildJsonObject {
                put("status", "ok")
                put("count", files.size)
                put("model", model.displayName)
                put("prompt", prompt)
                put("aspect_ratio", aspectRatio.name)
            }
            parts.add(UIMessagePart.Text(summary.toString()))
            parts
        }
    )

    fun getTools(options: List<LocalToolOption>, conversationId: Uuid? = null): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.WorkspaceFiles)) {
            tools.addAll(workspaceTools.getTools())
            tools.addAll(workspaceArtifactTools.getTools())
            tools.addAll(externalFileTools.getTools())
        }
        if (options.contains(LocalToolOption.Terminal)) {
            tools.addAll(terminalTools.getTools())
        }
        if (options.contains(LocalToolOption.ScreenAutomation)) {
            tools.addAll(screenAutomationTools.getTools())
        }
        if (options.contains(LocalToolOption.SystemAccess)) {
            tools.addAll(systemAccessTools.getTools())
        }
        if (options.contains(LocalToolOption.SystemAccess) ||
            settingsStore.settingsFlow.value.agentRuntime.feishuOfficeEnhancement.enabled
        ) {
            tools.addAll(feishuOfficeTools.getTools())
        }
        if (options.contains(LocalToolOption.WebView)) {
            tools.add(webViewTool)
            tools.add(webViewSearchOpenTool)
            tools.add(webViewWaitForLoadTool)
            tools.add(webViewReadTool)
            tools.add(webViewFindTextTool)
            tools.add(webViewLinksTool)
            tools.add(webViewOpenLinkTool)
        }
        if (options.contains(LocalToolOption.ICloudDrive)) {
            tools.addAll(iCloudDriveTools.getTools())
        }
        // Phase 2 post-review UX fix: WebMount is now gated by a SINGLE
        // global toggle on the WebMount Stations setting page (matches the
        // iCloud / Feishu Office Enhancement experimental pattern). When
        // the global toggle is ON, every assistant gets the WebMount tools
        // automatically — no per-assistant config needed. Per-assistant
        // `LocalToolOption.WebMount` is preserved as a manual override
        // (someone can still opt one assistant in even when the global is
        // off), but it's no longer the primary discovery path.
        val webMountActive = webMountManager.globalEnabled || options.contains(LocalToolOption.WebMount)
        if (webMountActive) {
            // `wm_eval` is gated by the separate global WebMountEval toggle.
            // Per-assistant `LocalToolOption.WebMountEval` is kept as a manual
            // override (one assistant can have eval even when the global is off).
            val includeEval = webMountManager.evalEnabled || options.contains(LocalToolOption.WebMountEval)
            tools.addAll(webMountPrimitiveTools.getTools(includeEval = includeEval))
            // Plan v2: adapter tools are gated by the user's site list.
            // If the user deleted a site (e.g. removed Bilibili), its adapter's
            // tools (`bilibili_*`) drop out of the agent catalog automatically.
            // The 7 seed sites are present by default after first launch, so
            // existing behaviour is preserved.
            val activeAdapterIds = userSiteRegistry.activeNativeAdapterIds()
            val gatedAdapterTools = webMountManager.allToolsByAdapter().asSequence()
                .filter { (adapterId, _) -> adapterId in activeAdapterIds }
                .flatMap { it.value.asSequence() }
                .toList()
            tools.addAll(gatedAdapterTools)
        }
        tools.add(permissionsStatusTool)
        tools.addAll(agentCronTools.getTools())
        tools.add(runPlanUpdateTool)

        // generate_image auto-appears whenever the current assistant — or the
        // global setting — resolves to a real image-gen model. The tool needs
        // a concrete conversationId to scope its file output, so we skip it
        // for the debug catalog path (conversationId == null).
        if (conversationId != null && settingsStore.settingsFlow.value.getCurrentImageGenerationModel() != null) {
            tools.add(buildImageGenTool(conversationId))
        }

        return tools
    }

}
