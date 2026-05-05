package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.office.FeishuOfficeAnalysisTemplate
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementManager
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementPlanner
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementState
import me.rerere.rikkahub.data.agent.office.FeishuOfficeScreenSnapshot
import me.rerere.rikkahub.data.agent.office.FeishuOfficeWorkspaceSnippet
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager

class FeishuOfficeTools(
    private val manager: FeishuOfficeEnhancementManager,
    private val workspaceManager: WorkspaceManager,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        statusTool,
        openTool,
        readScreenTool,
        searchTool,
        contextDigestTool,
    )

    private val statusTool = Tool(
        name = "officepro_status",
        description = "Show experimental 小米办公 Pro / 飞书办公 enhancement status, target package, permissions, and capability level.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val candidates = manager.detectPackages().take(8)
            textJson {
                putState(manager.state.value)
                put("candidates", buildJsonArray {
                    candidates.forEach { candidate ->
                        add(buildJsonObject {
                            put("package_name", candidate.packageName)
                            put("label", candidate.label)
                            put("installed", candidate.installed)
                            put("launchable", candidate.launchable)
                        })
                    }
                })
                put("notes", "v1 is read-first and semi-automatic. It does not read 小米办公 Pro private storage and does not write comments or send messages.")
            }
        },
    )

    private val openTool = Tool(
        name = "officepro_open",
        description = "Open the configured 小米办公 Pro app, or a user-provided URL/deep link inside that app. Requires approval because it switches apps.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", stringProp("Optional URL or deep link to open with the configured office app."))
                }
            )
        },
        needsApproval = true,
        execute = { input ->
            trackOfficeTool("officepro_open", "打开小米办公 Pro", input) {
                ensureEnabled()
                val ok = manager.openTargetApp(input.string("url"))
                textJson {
                    putState(manager.state.value)
                    put("success", ok)
                }
            }
        },
    )

    private val readScreenTool = Tool(
        name = "officepro_read_screen",
        description = "Read the current Accessibility UI tree and extract visible 小米办公 Pro title/text snippets. Requires Accessibility to be enabled.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("max_nodes", integerProp("Maximum Accessibility nodes to read. Defaults to 160."))
                    put("include_ui_tree", booleanProp("Include raw UI tree. Defaults to true."))
                }
            )
        },
        execute = { input ->
            trackOfficeTool("officepro_read_screen", "读取办公屏幕", input) {
                ensureEnabled()
                val screen = manager.readScreen(input.int("max_nodes") ?: 160)
                textJson {
                    putState(manager.state.value)
                    putScreen(screen, includeUiTree = input.boolean("include_ui_tree") ?: true)
                }
            }
        },
    )

    private val searchTool = Tool(
        name = "officepro_search",
        description = "Open 小米办公 Pro and try to enter a search query via Accessibility. The user must confirm the target document before any reading or action.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Search keyword or document title."))
                },
                required = listOf("query"),
            )
        },
        needsApproval = true,
        execute = { input ->
            trackOfficeTool("officepro_search", "搜索小米办公 Pro", input) {
                ensureEnabled()
                val result = manager.openAndSearch(input.requiredString("query"))
                textJson {
                    putState(manager.state.value)
                    put("status", result.status)
                    put("message", result.message)
                    put("search_box_found", result.searchBoxFound)
                    put("text_injected", result.textInjected)
                }
            }
        },
    )

    private val contextDigestTool = Tool(
        name = "officepro_context_digest",
        description = "Build a bounded product-market work context from 小米办公 Pro notifications, usage, current screen, and optional /workspace docs. It returns an analysis prompt and evidence; it does not call a model by itself.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("template", enumProp("Workflow template.", FeishuOfficeAnalysisTemplate.entries.map { it.wireName }))
                    put("workspace_paths", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional /workspace document paths already shared/exported to AmberAgent.")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("include_current_screen", booleanProp("Read current Accessibility screen if available. Defaults to true."))
                    put("max_chars", integerProp("Maximum digest characters. Defaults to 12000; hard limit 30000."))
                }
            )
        },
        execute = { input ->
            trackOfficeTool("officepro_context_digest", "生成飞书办公上下文", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val template = FeishuOfficeAnalysisTemplate.fromWireName(input.string("template"))
                val screen = if (input.boolean("include_current_screen") ?: true) {
                    runCatching { manager.readScreen(maxNodes = 180) }.getOrNull()
                } else {
                    null
                }
                val workspaceSnippets = readWorkspaceSnippets(input.stringList("workspace_paths"))
                val digest = FeishuOfficeEnhancementPlanner.buildContextDigest(
                    template = template,
                    state = manager.state.value,
                    notifications = manager.notificationSummaries(),
                    usageStats = manager.usageSummaries(),
                    screen = screen,
                    workspaceSnippets = workspaceSnippets,
                    maxChars = input.int("max_chars") ?: 12_000,
                )
                textJson {
                    putState(state)
                    put("template", template.wireName)
                    put("digest", digest)
                    put("workspace_paths_read", buildJsonArray { workspaceSnippets.forEach { add(it.path) } })
                }
            }
        },
    )

    private suspend fun trackOfficeTool(
        toolName: String,
        title: String,
        input: JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.safePreview().toString(),
            runtime = "小米办公 Pro / Android Accessibility",
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

    private fun ensureEnabled() {
        require(manager.state.value.enabled) {
            "飞书办公增强模式未启用。请先在 设置 > 实验性功能 > 飞书办公增强模式 打开。"
        }
    }

    private suspend fun readWorkspaceSnippets(paths: List<String>): List<FeishuOfficeWorkspaceSnippet> =
        paths.take(8).mapNotNull { path ->
            runCatching {
                val content = workspaceManager.readText(path)
                val maxChars = 5_000
                FeishuOfficeWorkspaceSnippet(
                    path = path,
                    content = content.take(maxChars),
                    totalChars = content.length,
                    truncated = content.length > maxChars,
                )
            }.getOrNull()
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putState(state: FeishuOfficeEnhancementState) {
        put("enabled", state.enabled)
        put("target_package", state.targetPackage)
        put("label", state.label.orEmpty())
        put("installed", state.installed)
        put("launchable", state.launchable)
        put("accessibility_ready", state.accessibilityReady)
        put("notification_ready", state.notificationReady)
        put("usage_ready", state.usageReady)
        put("capability", state.capability.wireName)
        put("default_template", state.defaultTemplate.wireName)
        put("last_known_title", state.lastKnownTitle.orEmpty())
        put("last_error", state.lastError.orEmpty())
        put("updated_at_ms", state.updatedAtMs)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putScreen(
        screen: FeishuOfficeScreenSnapshot,
        includeUiTree: Boolean,
    ) {
        put("title_guess", screen.titleGuess.orEmpty())
        put("visible_text", screen.visibleText)
        if (includeUiTree) {
            put("ui_tree", screen.uiTree.take(20_000))
        }
    }

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

    private fun enumProp(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }

    private fun JsonElement.stringList(name: String): List<String> =
        jsonObject[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()

    private fun JsonElement.safePreview(): JsonElement =
        buildJsonObject {
            jsonObject.forEach { (key, value) ->
                put(key, if (key.contains("query", ignoreCase = true)) JsonPrimitive("<redacted>") else value)
            }
        }

    private fun List<UIMessagePart>.previewText(): String =
        filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.take(2_000)
}
