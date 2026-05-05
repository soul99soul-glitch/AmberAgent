package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
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
import me.rerere.rikkahub.data.agent.tools.FeishuOfficeTools
import me.rerere.rikkahub.data.agent.tools.ICloudDriveTools
import me.rerere.rikkahub.data.agent.tools.ScreenAutomationTools
import me.rerere.rikkahub.data.agent.tools.SystemAccessTools
import me.rerere.rikkahub.data.agent.tools.TerminalTools
import me.rerere.rikkahub.data.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.agent.tools.WorkspaceArtifactTools
import me.rerere.rikkahub.data.agent.tools.WorkspaceTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.agent.webview.WebViewOperationStore
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

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
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val workspaceTools: WorkspaceTools,
    private val terminalTools: TerminalTools,
    private val screenAutomationTools: ScreenAutomationTools,
    private val systemAccessTools: SystemAccessTools,
    private val workspaceArtifactTools: WorkspaceArtifactTools,
    private val permissionBroker: AgentPermissionBroker,
    private val webViewOperationStore: WebViewOperationStore,
    private val iCloudDriveTools: ICloudDriveTools,
    private val feishuOfficeTools: FeishuOfficeTools,
    private val settingsStore: SettingsStore,
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

    val timeTool by lazy {
        Tool(
            name = "get_time_info",
            description = """
                Get the current local date and time info from the device.
                Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                val now = ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val payload = buildJsonObject {
                    put("year", date.year)
                    put("month", date.monthValue)
                    put("day", date.dayOfMonth)
                    put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", weekday.value)
                    put("date", date.toString())
                    put("time", time.toString())
                    put("datetime", now.withNano(0).toString())
                    put("timezone", now.zone.id)
                    put("utc_offset", now.offset.id)
                    put("timestamp_ms", now.toInstant().toEpochMilli())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val clipboardTool by lazy {
        Tool(
            name = "clipboard_tool",
            description = """
                Read or write plain text from the device clipboard.
                Use action: read or write. For write, provide text.
                Do NOT write to the clipboard unless the user has explicitly requested it.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
                                    add("read")
                                    add("write")
                                }
                            )
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write to the clipboard (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = {
                val params = it.jsonObject
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "read" -> {
                        val payload = buildJsonObject {
                            put("text", context.readClipboardText())
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        context.writeClipboardText(text)
                        val payload = buildJsonObject {
                            put("success", true)
                            put("text", text)
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
            }
        )
    }

    val webViewTool by lazy {
        Tool(
            name = "webview_open",
            description = """
                Open a URL in AmberAgent's live operation preview WebView.
                Prefer this early when the user asks to open, browse, view, inspect, or visually verify a webpage, or when search results should be shown in the live preview.
                After opening, call webview_read when you need the current page title, readable text, or links.
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
                webViewOperationStore.open(url)
                val payload = buildJsonObject {
                    put("url", url)
                    put("runtime", "webview")
                    put("status", "opened")
                    params["reason"]?.jsonPrimitive?.contentOrNull?.takeIf { reason -> reason.isNotBlank() }?.let { reason ->
                        put("reason", reason)
                    }
                    put("note", "Tap the operation preview to expand the WebView panel.")
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
                If the page is still loading, the tool returns status=loading instead of pretending content is available.
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
                    }
                )
            },
            execute = { input ->
                val state = webViewOperationStore.state.value
                if (!state.hasPage) {
                    val payload = buildJsonObject {
                        put("status", "no_page")
                        put("error", "No WebView page is open. Call webview_open first.")
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                } else {
                    val maxChars = input.jsonObject["max_chars"]?.jsonPrimitive?.contentOrNull
                        ?.toIntOrNull()
                        ?.coerceIn(500, 40_000)
                        ?: 8_000
                    val maxLinks = input.jsonObject["max_links"]?.jsonPrimitive?.contentOrNull
                        ?.toIntOrNull()
                        ?.coerceIn(0, 40)
                        ?: 20
                    val payload = buildJsonObject {
                        put("status", if (state.isLoading) "loading" else "ready")
                        put("url", state.url)
                        put("title", state.title)
                        put("loading_progress", state.loadingProgress)
                        put("text", state.readableText.take(maxChars))
                        put(
                            "links",
                            kotlinx.serialization.json.buildJsonArray {
                                state.links.take(maxLinks).forEach { link ->
                                    add(
                                        buildJsonObject {
                                            put("title", link.title)
                                            put("url", link.url)
                                        }
                                    )
                                }
                            }
                        )
                        if (state.thumbnailPath.isNotBlank()) {
                            put("thumbnail_path", state.thumbnailPath)
                        }
                        put("updated_at_ms", state.updatedAtEpochMillis)
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
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
                val state = webViewOperationStore.state.value
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
                    put("status", if (state.isLoading) "loading" else if (state.hasPage) "ready" else "no_page")
                    put("url", state.url)
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
                val state = webViewOperationStore.state.value
                val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 80) ?: 40
                val links = state.links.filter { link ->
                    query.isBlank() ||
                        link.title.contains(query, ignoreCase = true) ||
                        link.url.contains(query, ignoreCase = true)
                }
                val payload = buildJsonObject {
                    put("status", if (state.isLoading) "loading" else if (state.hasPage) "ready" else "no_page")
                    put("url", state.url)
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
                webViewOperationStore.open(url)
                val payload = buildJsonObject {
                    put("status", "opened")
                    put("url", url)
                    put("runtime", "webview")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
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

    val askUserTool by lazy {
        Tool(
            name = "ask_user",
            description = """
                Ask the user one or more questions when you need clarification, additional information, or confirmation.
                Each question can optionally provide a list of suggested options for the user to choose from.
                The user may select an option or provide their own free-text answer for each question.
                The answers will be returned as a JSON object mapping question IDs to the user's responses.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("description", "List of questions to ask the user")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Unique identifier for this question")
                                    })
                                    put("question", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The question text to display to the user")
                                    })
                                    put("options", buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional list of suggested options for the user to choose from"
                                        )
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            kotlinx.serialization.json.buildJsonArray {
                                                add("text")
                                                add("single")
                                                add("multi")
                                            }
                                        )
                                        put(
                                            "description",
                                            "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                        )
                                    })
                                })
                                put("required", kotlinx.serialization.json.buildJsonArray {
                                    add("id")
                                    add("question")
                                })
                            })
                        })
                    },
                    required = listOf("questions")
                )
            },
            needsApproval = true,
            execute = {
                error("ask_user tool should be handled by HITL flow")
            }
        )
    }

    fun createToolsListTool(registry: ToolRegistry) = Tool(
        name = "tools_list",
        description = "List AmberAgent tools currently available in this run, including category, approval policy, permission needs, and optional schema.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional category filter: workspace, cloud, terminal, web, webview, screen, system, memory, skill, mcp, utility.")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional name or description filter")
                    })
                    put("include_disabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Stage 1 lists enabled tools only; when true, the response explains this limitation.")
                    })
                    put("include_schema", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include tool input schema. Defaults to false.")
                    })
                }
            )
        },
        execute = { input ->
            val categoryFilter = input.jsonObject["category"]?.jsonPrimitive?.contentOrNull
            val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val includeSchema = input.jsonObject["include_schema"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val includeDisabled = input.jsonObject["include_disabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val toolDefinitions = registry.tools().associateBy { it.name }
            val tools = registry.metadata
                .filter { metadata -> categoryFilter == null || metadata.category == categoryFilter }
                .filter { metadata ->
                    val tool = toolDefinitions[metadata.name]
                    query.isBlank() ||
                        metadata.name.contains(query, ignoreCase = true) ||
                        tool?.description.orEmpty().contains(query, ignoreCase = true)
                }
            val payload = buildJsonObject {
                put("enabled_count", tools.size)
                put("include_disabled_supported", false)
                if (includeDisabled) {
                    put("note", "Disabled tool enumeration is not available in stage1 because tools are generated from the current agent configuration.")
                }
                put(
                    "tools",
                    buildJsonArray {
                        tools.forEach { metadata ->
                            val tool = toolDefinitions[metadata.name]
                            val capabilities = permissionBroker.capabilities.filter { capability ->
                                metadata.name in capability.toolNames
                            }
                            add(
                                buildJsonObject {
                                    put("name", metadata.name)
                                    put("category", metadata.category)
                                    put("description", tool?.description.orEmpty().take(240))
                                    put("enabled", true)
                                    put("mutates", metadata.mutates)
                                    put("needs_approval", metadata.needsApproval)
                                    put("allows_auto_approval", metadata.autoApprovable)
                                    put("output_budget_chars", metadata.outputBudgetChars)
                                    put("risk", capabilities.maxByOrNull { it.risk.ordinal }?.risk?.name ?: if (metadata.needsApproval) "Sensitive" else "Normal")
                                    put("required_permissions", buildJsonArray {
                                        capabilities.forEach { capability ->
                                            add(
                                                buildJsonObject {
                                                    put("capability_id", capability.id)
                                                    put("title", capability.title)
                                                    put("status", permissionBroker.getStatus(capability).name.lowercase())
                                                }
                                            )
                                        }
                                    })
                                    if (includeSchema && tool != null) {
                                        put("schema", tool.parameters()?.toString().orEmpty())
                                    }
                                }
                            )
                        }
                    }
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private val permissionsStatusTool by lazy {
        Tool(
            name = "permissions_status",
            description = "List AmberAgent Android permission capability status and how to grant missing permissions.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("capability_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional capability id to inspect")
                        })
                        put("include_how_to_grant", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Include user-facing grant guidance. Defaults to true.")
                        })
                    }
                )
            },
            execute = { input ->
                val id = input.jsonObject["capability_id"]?.jsonPrimitive?.contentOrNull
                val includeHowToGrant = input.jsonObject["include_how_to_grant"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val capabilities = permissionBroker.capabilities.filter { id == null || it.id == id }
                val payload = buildJsonObject {
                    put(
                        "capabilities",
                        buildJsonArray {
                            capabilities.forEach { capability ->
                                add(
                                    buildJsonObject {
                                        put("capability_id", capability.id)
                                        put("title", capability.title)
                                        put("description", capability.description)
                                        put("risk", capability.risk.name.lowercase())
                                        put("status", permissionBroker.getStatus(capability).name.lowercase())
                                        put("runtime_permissions", buildJsonArray {
                                            permissionBroker.runtimePermissionsFor(capability).forEach { permission -> add(permission) }
                                        })
                                        capability.specialAccess?.let { put("special_access", it.name) }
                                        put("tools", buildJsonArray { capability.toolNames.forEach { tool -> add(tool) } })
                                        if (includeHowToGrant) {
                                            put("how_to_grant", "Open AmberAgent Settings > Agent 设置 > 系统权限, then grant ${capability.title}. Special access items open Android system settings.")
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    private val runPlanUpdateTool by lazy {
        Tool(
            name = "run_plan_update",
            description = "Update the current long-task step summary for Agent UI, preview surfaces, and live status. Use concise user-visible step names.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("steps", buildJsonObject {
                            put("type", "array")
                            put("description", "Ordered task steps")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("current_step_index", buildJsonObject {
                            put("type", "integer")
                            put("description", "0-based current step index")
                        })
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("description", "planning, running, waiting, completed, failed, or cancelled")
                        })
                    },
                    required = listOf("steps", "status")
                )
            },
            execute = { input ->
                val payload = buildJsonObject {
                    put("status", input.jsonObject["status"] ?: JsonPrimitive("running"))
                    put("current_step_index", input.jsonObject["current_step_index"] ?: JsonPrimitive(0))
                    put("steps", input.jsonObject["steps"] ?: buildJsonArray {})
                    put("note", "Plan state was accepted by the tool layer. UI live rendering is stage1 and uses the normal tool timeline.")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
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
            tools.add(webViewReadTool)
            tools.add(webViewFindTextTool)
            tools.add(webViewLinksTool)
            tools.add(webViewOpenLinkTool)
        }
        if (options.contains(LocalToolOption.ICloudDrive)) {
            tools.addAll(iCloudDriveTools.getTools())
        }
        tools.add(permissionsStatusTool)
        tools.add(runPlanUpdateTool)
        return tools
    }

}
