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
import me.rerere.rikkahub.data.agent.office.FeishuOfficeContextBundle
import me.rerere.rikkahub.data.agent.office.FeishuOfficeDashboardSummary
import me.rerere.rikkahub.data.agent.office.FeishuOfficeReportResult
import me.rerere.rikkahub.data.agent.office.FeishuOfficeScreenSnapshot

class FeishuOfficeTools(
    private val manager: FeishuOfficeEnhancementManager,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        statusTool,
        dashboardTool,
        captureContextTool,
        makeReportTool,
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

    private val dashboardTool = Tool(
        name = "officepro_dashboard",
        description = "Build a read-only 小米办公 Pro work dashboard with capability gaps, recent signals, Feishu MCP hints, and suggested next actions.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            trackOfficeTool("officepro_dashboard", "飞书办公驾驶舱", buildJsonObject { }) {
                val state = manager.state.value
                val bundle = manager.captureContext(
                    workspacePaths = emptyList(),
                    includeCurrentScreen = state.enabled && state.includeCurrentScreenByDefault,
                    includeNotifications = state.enabled && state.includeNotificationsByDefault,
                    includeUsage = state.enabled && state.includeUsageByDefault,
                    includeMcpHints = state.includeMcpHintsByDefault,
                )
                val summary = FeishuOfficeEnhancementPlanner.buildDashboardSummary(bundle)
                textJson {
                    putState(bundle.state)
                    putSummary(summary)
                    putBundle(bundle, includeScreenTree = false)
                }
            }
        },
    )

    private val captureContextTool = Tool(
        name = "officepro_capture_context",
        description = "Capture a bounded 小米办公 Pro work context from notifications, usage, current screen, Feishu MCP hints, and optional /workspace docs. Does not write files.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("template", enumProp("Workflow template.", FeishuOfficeAnalysisTemplate.entries.map { it.wireName }))
                    put("workspace_paths", stringArrayProp("Optional /workspace document paths already shared/exported to AmberAgent."))
                    put("include_current_screen", booleanProp("Read current Accessibility screen if available. Defaults to the setting value."))
                    put("include_notifications", booleanProp("Include 小米办公 Pro active notifications. Defaults to the setting value."))
                    put("include_usage", booleanProp("Include recent usage signals. Defaults to the setting value."))
                    put("max_chars", integerProp("Maximum digest characters. Defaults to 12000; hard limit 30000."))
                }
            )
        },
        execute = { input ->
            trackOfficeTool("officepro_capture_context", "捕获飞书办公上下文", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val template = FeishuOfficeAnalysisTemplate.fromWireName(input.string("template"))
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: state.includeCurrentScreenByDefault,
                    includeNotifications = input.boolean("include_notifications") ?: state.includeNotificationsByDefault,
                    includeUsage = input.boolean("include_usage") ?: state.includeUsageByDefault,
                    includeMcpHints = state.includeMcpHintsByDefault,
                )
                val digest = FeishuOfficeEnhancementPlanner.buildContextDigest(
                    template = template,
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: 12_000,
                )
                textJson {
                    putState(bundle.state)
                    put("template", template.wireName)
                    putBundle(bundle, includeScreenTree = false)
                    put("digest", digest)
                }
            }
        },
    )

    private val makeReportTool = Tool(
        name = "officepro_make_report",
        description = "Create a Markdown report draft under /workspace from captured 小米办公 Pro context and optional exported documents. Requires approval because it writes a file.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("template", enumProp("Workflow template.", FeishuOfficeAnalysisTemplate.entries.map { it.wireName }))
                    put("workspace_paths", stringArrayProp("Optional /workspace document paths already shared/exported to AmberAgent."))
                    put("title", stringProp("Optional report title."))
                    put("output_path", stringProp("Optional /workspace output path. Defaults to officepro/officepro-<template>-yyyyMMdd-HHmmss.md."))
                    put("include_current_screen", booleanProp("Read current Accessibility screen if available. Defaults to the setting value."))
                }
            )
        },
        needsApproval = true,
        execute = { input ->
            trackOfficeTool("officepro_make_report", "生成飞书办公报告", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val template = FeishuOfficeAnalysisTemplate.fromWireName(input.string("template"))
                val result = manager.makeReport(
                    template = template,
                    workspacePaths = input.stringList("workspace_paths"),
                    title = input.string("title"),
                    outputPath = input.string("output_path"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: state.includeCurrentScreenByDefault,
                )
                textJson {
                    putState(manager.state.value)
                    putReport(result)
                }
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
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: state.includeCurrentScreenByDefault,
                    includeNotifications = state.includeNotificationsByDefault,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = state.includeMcpHintsByDefault,
                )
                val digest = FeishuOfficeEnhancementPlanner.buildContextDigest(
                    template = template,
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: 12_000,
                )
                textJson {
                    putState(state)
                    put("template", template.wireName)
                    put("digest", digest)
                    put("workspace_paths_read", buildJsonArray { bundle.workspaceSnippets.forEach { add(it.path) } })
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putState(state: FeishuOfficeEnhancementState) {
        put("enabled", state.enabled)
        put("target_package", state.targetPackage)
        put("label", state.label.orEmpty())
        put("include_notifications_by_default", state.includeNotificationsByDefault)
        put("include_usage_by_default", state.includeUsageByDefault)
        put("include_current_screen_by_default", state.includeCurrentScreenByDefault)
        put("include_mcp_hints_by_default", state.includeMcpHintsByDefault)
        put("default_output_dir", state.defaultOutputDir)
        put("max_workspace_docs", state.maxWorkspaceDocs)
        put("max_report_chars", state.maxReportChars)
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSummary(summary: FeishuOfficeDashboardSummary) {
        put("dashboard", buildJsonObject {
            put("capability", summary.capability.wireName)
            put("missing_permissions", buildJsonArray { summary.missingPermissions.forEach { add(it) } })
            put("notification_count", summary.notificationCount)
            put("recent_title", summary.recentTitle.orEmpty())
            put("suggested_actions", buildJsonArray { summary.suggestedActions.forEach { add(it) } })
            put("updated_at_ms", summary.updatedAtMs)
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putBundle(
        bundle: FeishuOfficeContextBundle,
        includeScreenTree: Boolean,
    ) {
        put("captured_at_ms", bundle.capturedAtMs)
        put("screen_error", bundle.screenError.orEmpty())
        bundle.screen?.let { screen -> putScreen(screen, includeUiTree = includeScreenTree) }
        put("notifications", buildJsonArray {
            bundle.notifications.forEach { item ->
                add(buildJsonObject {
                    put("posted_at_ms", item.postedAtMs)
                    put("title", item.title)
                    put("text", item.text)
                })
            }
        })
        put("usage_stats", buildJsonArray {
            bundle.usageStats.forEach { item ->
                add(buildJsonObject {
                    put("package_name", item.packageName)
                    put("label", item.label)
                    put("last_time_used_ms", item.lastTimeUsedMs)
                    put("total_time_foreground_ms", item.totalTimeForegroundMs)
                })
            }
        })
        put("workspace_snippets", buildJsonArray {
            bundle.workspaceSnippets.forEach { item ->
                add(buildJsonObject {
                    put("path", item.path)
                    put("content", item.content)
                    put("total_chars", item.totalChars)
                    put("truncated", item.truncated)
                })
            }
        })
        put("mcp_hints", buildJsonArray { bundle.mcpHints.forEach { add(it) } })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putReport(result: FeishuOfficeReportResult) {
        put("report", buildJsonObject {
            put("path", result.path)
            put("title", result.title)
            put("template", result.template.wireName)
            put("truncated", result.truncated)
            put("written_at_ms", result.writtenAtMs)
            put("total_chars", result.totalChars)
        })
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

    private fun stringArrayProp(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
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
