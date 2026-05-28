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
import me.rerere.rikkahub.data.agent.task.AgentTaskOutputRef
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.data.agent.task.AgentTaskQueueState
import me.rerere.rikkahub.data.agent.task.AgentTaskStatus
import me.rerere.rikkahub.data.agent.task.AgentTaskStore
import app.amber.feature.office.FeishuOfficeAnalysisTemplate
import app.amber.feature.office.FeishuOfficeEnhancementManager
import app.amber.feature.office.FeishuOfficeEnhancementPlanner
import app.amber.feature.office.FeishuOfficeEnhancementState
import app.amber.feature.office.FeishuOfficeContextBundle
import app.amber.feature.office.FeishuOfficeDashboardSummary
import app.amber.feature.office.FeishuDocumentWarroomTemplate
import app.amber.feature.office.FeishuOfficeReportResult
import app.amber.feature.office.FeishuOfficeScreenSnapshot
import app.amber.feature.office.FeishuWorkDraft
import app.amber.feature.office.FeishuWorkProject
import app.amber.feature.office.FeishuWorkReport

class FeishuOfficeTools(
    private val manager: FeishuOfficeEnhancementManager,
    private val activityStore: AgentToolActivityStore,
    private val agentTaskStore: AgentTaskStore,
) {
    fun getTools(): List<Tool> = listOf(
        statusTool,
        dashboardTool,
        dailyRadarTool,
        projectBriefingTool,
        documentWarroomTool,
        openItemsRadarTool,
        meetingClosureTool,
        createTaskDraftTool,
        createBaseRecordDraftTool,
        replyDraftTool,
        projectListTool,
        projectUpdateTool,
        projectContextTool,
        projectReportTool,
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
        needsApproval = true,
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

    private val dailyRadarTool = Tool(
        name = "officepro_daily_radar",
        description = "Generate a read-first daily Feishu radar from 小米办公 Pro notifications, usage signals, optional current screen, workspace docs, and Feishu MCP hints. Returns a Markdown draft and suggested /workspace path; it does not write files.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("workspace_paths", stringArrayProp("Optional /workspace documents to include."))
                    put("include_current_screen", booleanProp("Read current 小米办公 Pro screen if available. Defaults to Work Dashboard setting."))
                    put("include_mcp_sources", booleanProp("Include Feishu MCP source hints. Defaults to Work Dashboard setting."))
                    put("max_chars", integerProp("Maximum report characters. Defaults to Work Dashboard setting."))
                }
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_daily_radar", "今日飞书雷达", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: dashboard.includeCurrentScreen,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = input.boolean("include_mcp_sources") ?: dashboard.includeMcpSources,
                )
                val report = FeishuOfficeEnhancementPlanner.buildDailyRadarReport(
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putBundle(bundle, includeScreenTree = false)
                    putWorkReport(report)
                }
            }
        },
    )

    private val projectBriefingTool = Tool(
        name = "officepro_project_briefing",
        description = "Generate a 10-minute product-market briefing for Q 代, MiClaw, Lhasa, AI 办公, or a custom project. Returns a Markdown draft and suggested /workspace path; it does not write files.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("project", stringProp("Project name, for example Q 代, MiClaw, Lhasa, AI 办公."))
                    put("workspace_paths", stringArrayProp("Optional /workspace documents to include."))
                    put("include_current_screen", booleanProp("Read current 小米办公 Pro screen if available. Defaults to Work Dashboard setting."))
                    put("include_mcp_sources", booleanProp("Include Feishu MCP source hints. Defaults to Work Dashboard setting."))
                    put("max_chars", integerProp("Maximum report characters. Defaults to Work Dashboard setting."))
                },
                required = listOf("project"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_project_briefing", "项目 Briefing", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: dashboard.includeCurrentScreen,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = input.boolean("include_mcp_sources") ?: dashboard.includeMcpSources,
                )
                val report = FeishuOfficeEnhancementPlanner.buildProjectBriefingReport(
                    project = input.requiredString("project"),
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putBundle(bundle, includeScreenTree = false)
                    putWorkReport(report)
                }
            }
        },
    )

    private val documentWarroomTool = Tool(
        name = "officepro_document_warroom",
        description = "Build a document war room draft from visible 小米办公 Pro screen, workspace docs, Feishu MCP hints, and optional Model Council guidance. Returns structured Markdown; it does not write files.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("template", enumProp("Document analysis template.", FeishuOfficeAnalysisTemplate.entries.map { it.wireName }))
                    put("warroom_template", enumProp("V5 document war room template. Defaults from template for compatibility.", FeishuDocumentWarroomTemplate.entries.map { it.wireName }))
                    put("workspace_paths", stringArrayProp("Optional /workspace documents to include."))
                    put("include_current_screen", booleanProp("Read current 小米办公 Pro screen if available. Defaults to Work Dashboard setting."))
                    put("include_mcp_sources", booleanProp("Include Feishu MCP source hints. Defaults to Work Dashboard setting."))
                    put("include_model_council", booleanProp("Add Model Council follow-up guidance. Defaults to Work Dashboard setting."))
                    put("max_chars", integerProp("Maximum report characters. Defaults to Work Dashboard setting."))
                }
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_document_warroom", "文档作战室", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val template = FeishuOfficeAnalysisTemplate.fromWireName(input.string("template"))
                val warroomTemplate = input.string("warroom_template")
                    ?.let { FeishuDocumentWarroomTemplate.fromWireName(it) }
                    ?: FeishuDocumentWarroomTemplate.fromAnalysisTemplate(template)
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: dashboard.includeCurrentScreen,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = input.boolean("include_mcp_sources") ?: dashboard.includeMcpSources,
                )
                val report = FeishuOfficeEnhancementPlanner.buildDocumentWarroomReport(
                    template = template,
                    bundle = bundle,
                    includeModelCouncil = input.boolean("include_model_council") ?: dashboard.includeModelCouncil,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                    warroomTemplate = warroomTemplate,
                )
                textJson {
                    putState(bundle.state)
                    put("template", template.wireName)
                    put("warroom_template", warroomTemplate.wireName)
                    putBundle(bundle, includeScreenTree = false)
                    putWorkReport(report)
                }
            }
        },
    )

    private val openItemsRadarTool = Tool(
        name = "officepro_open_items_radar",
        description = "Generate an open-items radar for Feishu/OfficePro work: unresolved issues, TBDs, risks, owner/deadline gaps, and draft landing actions. It does not write Feishu.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("project", stringProp("Optional project name or keyword."))
                    put("workspace_paths", stringArrayProp("Optional /workspace documents to include."))
                    put("include_current_screen", booleanProp("Read current 小米办公 Pro screen if available. Defaults to Work Dashboard setting."))
                    put("include_mcp_sources", booleanProp("Include Feishu MCP source hints. Defaults to Work Dashboard setting."))
                    put("max_chars", integerProp("Maximum report characters. Defaults to Work Dashboard setting."))
                }
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_open_items_radar", "遗留问题雷达", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: dashboard.includeCurrentScreen,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = input.boolean("include_mcp_sources") ?: dashboard.includeMcpSources,
                )
                val report = FeishuOfficeEnhancementPlanner.buildOpenItemsRadarReport(
                    project = input.string("project"),
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putBundle(bundle, includeScreenTree = false)
                    putWorkReport(report)
                }
            }
        },
    )

    private val meetingClosureTool = Tool(
        name = "officepro_meeting_closure",
        description = "Generate a meeting closure draft from OfficePro signals, workspace docs, and Feishu MCP hints: decisions, owners, deadlines, risks, and follow-up wording. It does not write Feishu.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("meeting_keyword", stringProp("Meeting title, keyword, or topic."))
                    put("date", stringProp("Optional date or time range label."))
                    put("workspace_paths", stringArrayProp("Optional meeting notes, minutes, docs, or exported files from /workspace."))
                    put("include_current_screen", booleanProp("Read current 小米办公 Pro screen if available. Defaults to Work Dashboard setting."))
                    put("include_mcp_sources", booleanProp("Include Feishu MCP source hints. Defaults to Work Dashboard setting."))
                    put("max_chars", integerProp("Maximum report characters. Defaults to Work Dashboard setting."))
                }
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_meeting_closure", "会议闭环", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: dashboard.includeCurrentScreen,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = input.boolean("include_mcp_sources") ?: dashboard.includeMcpSources,
                )
                val report = FeishuOfficeEnhancementPlanner.buildMeetingClosureReport(
                    meetingKeyword = input.string("meeting_keyword"),
                    dateLabel = input.string("date"),
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putBundle(bundle, includeScreenTree = false)
                    putWorkReport(report)
                }
            }
        },
    )

    private val createTaskDraftTool = Tool(
        name = "officepro_create_task_draft",
        description = "Create a Feishu task draft JSON/Markdown from current OfficePro context. It does not call Feishu MCP or create a real task.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", stringProp("Task title."))
                    put("owner", stringProp("Optional owner or assignee."))
                    put("due", stringProp("Optional due date or deadline."))
                    put("project", stringProp("Optional project name."))
                    put("source_ref", stringProp("Optional source document, meeting, or chat reference."))
                    put("details", stringProp("Optional task details or acceptance criteria."))
                    put("workspace_paths", stringArrayProp("Optional /workspace sources to include."))
                    put("max_chars", integerProp("Maximum draft characters. Defaults to Work Dashboard setting."))
                },
                required = listOf("title"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_create_task_draft", "飞书任务草稿", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = false,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = false,
                    includeMcpHints = dashboard.includeMcpSources,
                )
                val draft = FeishuOfficeEnhancementPlanner.buildTaskDraft(
                    title = input.requiredString("title"),
                    owner = input.string("owner"),
                    due = input.string("due"),
                    project = input.string("project"),
                    sourceRef = input.string("source_ref"),
                    details = input.string("details"),
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putWorkDraft(draft)
                }
            }
        },
    )

    private val createBaseRecordDraftTool = Tool(
        name = "officepro_create_base_record_draft",
        description = "Create a Feishu Base record draft JSON/Markdown from current OfficePro context. It does not call Feishu MCP or write a real Base record.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("project", stringProp("Optional project name."))
                    put("base_name", stringProp("Optional target Base name."))
                    put("table_name", stringProp("Optional target table name."))
                    put("record_type", stringProp("Record type, for example open_item, risk, selling_point, competitor."))
                    put("fields_json", stringProp("Draft fields as JSON text."))
                    put("workspace_paths", stringArrayProp("Optional /workspace sources to include."))
                    put("max_chars", integerProp("Maximum draft characters. Defaults to Work Dashboard setting."))
                }
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_create_base_record_draft", "飞书 Base 记录草稿", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = false,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = false,
                    includeMcpHints = dashboard.includeMcpSources,
                )
                val draft = FeishuOfficeEnhancementPlanner.buildBaseRecordDraft(
                    project = input.string("project"),
                    baseName = input.string("base_name"),
                    tableName = input.string("table_name"),
                    recordType = input.string("record_type"),
                    fieldsJson = input.string("fields_json"),
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putWorkDraft(draft)
                }
            }
        },
    )

    private val replyDraftTool = Tool(
        name = "officepro_reply_draft",
        description = "Create a Feishu comment/chat reply draft from OfficePro context. It does not send, comment, or write back.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("objective", stringProp("Reply objective or message intent."))
                    put("audience", stringProp("Optional audience, person, group, or reviewer."))
                    put("tone", stringProp("Optional tone, for example concise, firm, friendly, boss-review-ready."))
                    put("source_ref", stringProp("Optional source document, meeting, or chat reference."))
                    put("workspace_paths", stringArrayProp("Optional /workspace sources to include."))
                    put("include_current_screen", booleanProp("Read current 小米办公 Pro screen if available. Defaults to Work Dashboard setting."))
                    put("max_chars", integerProp("Maximum draft characters. Defaults to Work Dashboard setting."))
                },
                required = listOf("objective"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_reply_draft", "飞书回复草稿", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: dashboard.includeCurrentScreen,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = false,
                    includeMcpHints = dashboard.includeMcpSources,
                )
                val draft = FeishuOfficeEnhancementPlanner.buildReplyDraft(
                    audience = input.string("audience"),
                    tone = input.string("tone"),
                    objective = input.requiredString("objective"),
                    sourceRef = input.string("source_ref"),
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putBundle(bundle, includeScreenTree = false)
                    putWorkDraft(draft)
                }
            }
        },
    )

    private val projectListTool = Tool(
        name = "officepro_project_list",
        description = "List local Feishu WorkOS project knowledge packs, including Q 代, MiClaw, Lhasa, AI 办公, and user-defined projects.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            textJson {
                putState(manager.state.value)
                putProjects(manager.state.value.workDashboard.projects)
            }
        },
    )

    private val projectUpdateTool = Tool(
        name = "officepro_project_update",
        description = "Update a local Feishu WorkOS project knowledge pack. This mutates AmberAgent local settings only; it does not write Feishu.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", stringProp("Optional stable project id."))
                    put("name", stringProp("Project name, for example Q 代, MiClaw, Lhasa."))
                    put("keywords", stringArrayProp("Project keywords."))
                    put("current_goal", stringProp("Current project goal."))
                    put("core_selling_points", stringArrayProp("Core selling points to remember."))
                    put("risks", stringArrayProp("Known risks."))
                    put("open_questions", stringArrayProp("Open questions or TBDs."))
                    put("key_decisions", stringArrayProp("Decisions already made."))
                    put("recent_changes", stringArrayProp("Recent changes."))
                    put("source_refs", stringArrayProp("Source document, meeting, or report references."))
                },
                required = listOf("name"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_project_update", "更新项目知识包", input.safePreview()) {
                ensureEnabled()
                val dashboard = manager.state.value.workDashboard
                val existing = FeishuOfficeEnhancementPlanner.resolveProject(dashboard, input.string("id").orEmpty().ifBlank { input.string("name") })
                    .takeIf { project -> dashboard.projects.any { it.id == project.id } }
                val project = FeishuOfficeEnhancementPlanner.mergeProject(
                    existing = existing,
                    id = input.string("id"),
                    name = input.requiredString("name"),
                    keywords = input.stringList("keywords"),
                    currentGoal = input.string("current_goal"),
                    coreSellingPoints = input.stringList("core_selling_points"),
                    risks = input.stringList("risks"),
                    openQuestions = input.stringList("open_questions"),
                    keyDecisions = input.stringList("key_decisions"),
                    recentChanges = input.stringList("recent_changes"),
                    sourceRefs = input.stringList("source_refs"),
                    nowMs = System.currentTimeMillis(),
                )
                manager.saveProject(project)
                textJson {
                    putState(manager.state.value)
                    putProject(project)
                    put("markdown_index", FeishuOfficeEnhancementPlanner.buildProjectKnowledgeMarkdown(project))
                    put("note", "Project knowledge was updated locally in AmberAgent settings. It was not written to Feishu.")
                }
            }
        },
    )

    private val projectContextTool = Tool(
        name = "officepro_project_context",
        description = "Build a local project context report from a project knowledge pack plus optional OfficePro/workspace/MCP signals. Returns Markdown; it does not write files.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("project", stringProp("Project id, name, or keyword."))
                    put("workspace_paths", stringArrayProp("Optional /workspace documents to include."))
                    put("include_current_screen", booleanProp("Read current 小米办公 Pro screen if available. Defaults to Work Dashboard setting."))
                    put("include_mcp_sources", booleanProp("Include Feishu MCP source hints. Defaults to Work Dashboard setting."))
                    put("max_chars", integerProp("Maximum report characters. Defaults to Work Dashboard setting."))
                },
                required = listOf("project"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackOfficeTool("officepro_project_context", "项目上下文", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val project = FeishuOfficeEnhancementPlanner.resolveProject(dashboard, input.requiredString("project"))
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = input.boolean("include_current_screen") ?: dashboard.includeCurrentScreen,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = input.boolean("include_mcp_sources") ?: dashboard.includeMcpSources,
                )
                val report = FeishuOfficeEnhancementPlanner.buildProjectContextReport(
                    project = project,
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putProject(project)
                    putBundle(bundle, includeScreenTree = false)
                    putWorkReport(report)
                }
            }
        },
    )

    private val projectReportTool = Tool(
        name = "officepro_project_report",
        description = "Generate a Markdown report draft for a local Feishu WorkOS project knowledge pack. It returns a suggested /workspace path and does not write files.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("project", stringProp("Project id, name, or keyword."))
                    put("workspace_paths", stringArrayProp("Optional /workspace documents to include."))
                    put("max_chars", integerProp("Maximum report characters. Defaults to Work Dashboard setting."))
                },
                required = listOf("project"),
            )
        },
        execute = { input ->
            trackOfficeTool("officepro_project_report", "项目报告草稿", input.safePreview()) {
                ensureEnabled()
                val state = manager.state.value
                val dashboard = state.workDashboard
                val project = FeishuOfficeEnhancementPlanner.resolveProject(dashboard, input.requiredString("project"))
                val bundle = manager.captureContext(
                    workspacePaths = input.stringList("workspace_paths"),
                    includeCurrentScreen = false,
                    includeNotifications = dashboard.includeNotifications,
                    includeUsage = state.includeUsageByDefault,
                    includeMcpHints = dashboard.includeMcpSources,
                )
                val report = FeishuOfficeEnhancementPlanner.buildProjectContextReport(
                    project = project,
                    bundle = bundle,
                    maxChars = input.int("max_chars") ?: dashboard.maxReportChars,
                )
                textJson {
                    putState(bundle.state)
                    putProject(project)
                    putWorkReport(report)
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
        needsApproval = true,
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
        needsApproval = true,
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
        agentTaskStore.register(
            AgentTaskSnapshot(
                taskId = toolCallId,
                type = "officepro",
                title = title,
                queueState = AgentTaskQueueState.ACTIVE,
                status = AgentTaskStatus.RUNNING,
                sourceToolName = toolName,
                createdAtMs = System.currentTimeMillis(),
                cancelCapability = false,
                summary = input.safePreview().toString(),
            )
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, result.previewText())
            agentTaskStore.update(
                taskId = toolCallId,
                status = AgentTaskStatus.COMPLETED,
                summary = result.previewText().take(4_000),
                outputRef = result.firstPathOrNull()?.let {
                    AgentTaskOutputRef(type = "report", path = it, exists = java.io.File(it).exists())
                },
            )
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            agentTaskStore.update(
                taskId = toolCallId,
                status = AgentTaskStatus.FAILED,
                error = error.message ?: error::class.java.simpleName,
            )
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
        put("work_dashboard_enabled", state.workDashboard.enabled)
        put("work_dashboard_projects", buildJsonArray { state.workDashboard.defaultProjectKeywords.forEach { add(it) } })
        put("work_dashboard_include_model_council", state.workDashboard.includeModelCouncil)
        put("work_dashboard_max_source_docs", state.workDashboard.maxSourceDocs)
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putProjects(projects: List<FeishuWorkProject>) {
        put("projects", buildJsonArray {
            projects.forEach { project ->
                add(buildJsonObject { putProjectFields(project) })
            }
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putProject(project: FeishuWorkProject) {
        put("project", buildJsonObject { putProjectFields(project) })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putProjectFields(project: FeishuWorkProject) {
        put("id", project.id)
        put("name", project.name)
        put("keywords", buildJsonArray { project.keywords.forEach { add(it) } })
        put("current_goal", project.currentGoal)
        put("core_selling_points", buildJsonArray { project.coreSellingPoints.forEach { add(it) } })
        put("risks", buildJsonArray { project.risks.forEach { add(it) } })
        put("open_questions", buildJsonArray { project.openQuestions.forEach { add(it) } })
        put("key_decisions", buildJsonArray { project.keyDecisions.forEach { add(it) } })
        put("recent_changes", buildJsonArray { project.recentChanges.forEach { add(it) } })
        put("source_refs", buildJsonArray { project.sourceRefs.forEach { add(it) } })
        put("updated_at_ms", project.updatedAtMs)
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putWorkReport(report: FeishuWorkReport) {
        put("work_report", buildJsonObject {
            put("title", report.title)
            put("skill_id", report.skillId)
            put("project", report.project.orEmpty())
            put("output_path", report.outputPath)
            put("truncated", report.truncated)
            put("markdown", report.markdown)
            put("sources", buildJsonArray {
                report.sources.forEach { source ->
                    add(buildJsonObject {
                        put("type", source.type.wireName)
                        put("title", source.title)
                        put("source_ref", source.sourceRef)
                        put("snippet", source.snippet)
                        put("captured_at_ms", source.capturedAtMs)
                        put("truncated", source.truncated)
                    })
                }
            })
        })
        put(
            "write_note",
            "This OfficePro report tool does not write files. To persist the report, ask for approval and write markdown to output_path under /workspace.",
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putWorkDraft(draft: FeishuWorkDraft) {
        put("work_draft", buildJsonObject {
            put("type", draft.type.wireName)
            put("title", draft.title)
            put("target", draft.target)
            put("requires_approval", draft.requiresApproval)
            put("approval_note", draft.approvalNote)
            put("markdown", draft.markdown)
            put("payload_json", draft.payloadJson)
            put("sources", buildJsonArray {
                draft.sources.forEach { source ->
                    add(buildJsonObject {
                        put("type", source.type.wireName)
                        put("title", source.title)
                        put("source_ref", source.sourceRef)
                        put("snippet", source.snippet)
                        put("captured_at_ms", source.capturedAtMs)
                        put("truncated", source.truncated)
                    })
                }
            })
        })
        put(
            "write_note",
            "This V6 tool only creates a draft. Calling Feishu MCP to create tasks, Base records, comments, or replies requires a separate approval step.",
        )
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
                val sensitivePreview = key.contains("query", ignoreCase = true) ||
                    key.contains("details", ignoreCase = true) ||
                    key.contains("fields_json", ignoreCase = true) ||
                    key.contains("objective", ignoreCase = true)
                put(key, if (sensitivePreview) JsonPrimitive("<redacted>") else value)
            }
        }

    private fun List<UIMessagePart>.previewText(): String =
        filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.take(2_000)

    private fun List<UIMessagePart>.firstPathOrNull(): String? =
        filterIsInstance<UIMessagePart.Text>()
            .asSequence()
            .flatMap { it.text.lineSequence() }
            .map { it.trim() }
            .firstOrNull { it.startsWith("/workspace/") || it.startsWith("/data/") }
}
