package app.amber.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: Boolean = false,
    val allowsAutoApproval: Boolean = true,
    // When true, this tool bypasses ordinary auto-approval, prior in-run trust,
    // and category fast-paths. Only the explicit "auto approve high-risk tools"
    // setting may run it unattended. Used for tools whose blast radius deserves
    // a stronger gate by default (e.g. wm_eval — arbitrary JS in a logged-in
    // WebView). See PermissionDecisionResolver for enforcement.
    val mandatoryApproval: Boolean = false,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

fun createSearchWebToolDeclaration(): Tool = Tool(
    name = "search_web",
    description = """
        Search the web through AmberAgent iOS search execution.
        Use this when the user asks for latest news, current facts, or needs verification.
        Provide focused keywords in `query`; for news/current events, set `topic` and `time_range` when useful.
    """.trimIndent(),
    parameters = { searchWebParameters() },
    execute = { emptyList() }
)

fun createScrapeWebToolDeclaration(): Tool = Tool(
    name = "scrape_web",
    description = """
        Fetch a public http/https URL and extract readable page text through AmberAgent iOS search execution.
        Use this when search snippets are not enough or when the user asks about a specific page.
        iOS blocks local/private URLs and returns an honest error when content cannot be safely fetched.
    """.trimIndent(),
    parameters = { scrapeWebParameters() },
    execute = { emptyList() }
)

fun createAskUserToolDeclaration(): Tool = Tool(
    name = "ask_user",
    description = """
        Pause the current discussion and ask the user one focused question.
        Use only when the answer materially changes the advice. Provide concise options when useful;
        use an empty options array when the user should answer freely. Never call this with another tool.
    """.trimIndent().replace("\n", " "),
    parameters = { askUserParameters() },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { emptyList() }
)

fun createMemoryToolDeclaration(): Tool = Tool(
    name = "memory_tool",
    description = """
        Read and update AmberAgent iOS memories. Use `action`:
        - `list`: list saved memories visible under the enabled memory scopes.
        - `create`: add a memory with `content`, optional `scope` (`core`, `short_term`, `long_term`), `kind`, `pinned`, `expiresAt`, and `confidence`.
        - `edit`: update an existing memory by `id` with new `content`, and optional `scope`, `kind`, or `pinned`.
        - `delete`: remove a memory by `id`.
        Do not store sensitive personal data. Prefer concise durable preferences, project continuity notes, and explicit user-approved facts.
    """.trimIndent(),
    parameters = { memoryToolParameters() },
    execute = { emptyList() }
)

fun createWorkspaceFileReadToolDeclaration(): Tool = workspaceTool(
    name = "workspace_file_read",
    description = """
        Read text preview from an AmberAgent iOS Workspace file previously imported by the user.
        Use `file_id` from Workspace UI/tool output or a `/workspace/...` path. This cannot read arbitrary device files.
    """.trimIndent(),
    parameters = workspaceFileReadParameters()
)

fun createWorkspaceFileWriteToolDeclaration(): Tool = workspaceTool(
    name = "workspace_file_write",
    description = """
        Write a UTF-8 text or Markdown file under AmberAgent iOS `/workspace`.
        Use a relative path or `/workspace/...`; traversal and absolute device paths are rejected. Requires foreground approval.
    """.trimIndent(),
    parameters = workspaceFileWriteParameters()
)

fun createWorkspaceFileEditToolDeclaration(): Tool = workspaceTool(
    name = "workspace_file_edit",
    description = "Replace text inside an existing AmberAgent iOS Workspace file. Requires foreground approval.",
    parameters = workspaceFileEditParameters()
)

fun createWorkspaceFileListToolDeclaration(): Tool = workspaceTool(
    name = "workspace_file_list",
    description = "List files currently stored in AmberAgent iOS Workspace, optionally under a path prefix.",
    parameters = workspaceFileListParameters()
)

fun createWorkspaceFileSearchToolDeclaration(): Tool = workspaceTool(
    name = "workspace_file_search",
    description = "Search text previews of AmberAgent iOS Workspace files.",
    parameters = workspaceFileSearchParameters()
)

fun createWorkspaceFileMoveToolDeclaration(): Tool = workspaceTool(
    name = "workspace_file_move",
    description = "Move or rename an AmberAgent iOS Workspace file. Requires foreground approval.",
    parameters = workspaceFileMoveParameters()
)

fun createWorkspaceArtifactReadToolDeclaration(): Tool = workspaceTool(
    name = "workspace_artifact_read",
    description = "Read a saved AmberAgent iOS Workspace artifact by `artifact_id`.",
    parameters = workspaceArtifactReadParameters()
)

fun createWorkspaceArtifactDeleteToolDeclaration(): Tool = workspaceTool(
    name = "workspace_artifact_delete",
    description = "Delete a saved AmberAgent iOS Workspace artifact by `artifact_id`. Requires explicit foreground approval.",
    parameters = workspaceArtifactReadParameters()
)

fun createImageGenToolDeclaration(): Tool = Tool(
    name = "generate_image",
    description = """
        Generate raster images using AmberAgent iOS image generation. Use for photos, paintings,
        illustrations, posters, concept art, wallpapers, product mockups, and other visual results
        where pixels, lighting, texture, composition, or style matter. Prefer detailed English
        prompts. Use SVG or markdown diagrams for precise editable charts and diagrams unless the
        user explicitly asks for an artistic rendering.
    """.trimIndent().replace("\n", " "),
    parameters = { imageGenParameters() },
    execute = { emptyList() }
)

fun createWebMountStationsToolDeclaration(): Tool = webMountTool(
    name = "wm_stations",
    description = "List configured iOS WebMount stations, enabled state, auth kind, and redacted cookie summary.",
    parameters = webMountStationsParameters()
)

fun createWebMountTabListToolDeclaration(): Tool = webMountTool(
    name = "wm_tab_list",
    description = "List up to three foreground iOS WebMount sessions with redacted URLs, titles, status, and navigation state.",
    parameters = emptyObjectParameters()
)

fun createWebMountTabNewToolDeclaration(): Tool = webMountTool(
    name = "wm_tab_new",
    description = "Create a new foreground iOS WebMount session. iOS keeps at most three sessions and evicts least-recently-used sessions.",
    parameters = webMountTabNewParameters()
)

fun createWebMountTabCloseToolDeclaration(): Tool = webMountTool(
    name = "wm_tab_close",
    description = "Close a foreground iOS WebMount session by session_id. If omitted, closes the current foreground session.",
    parameters = webMountTabCloseParameters()
)

fun createWebMountOpenToolDeclaration(): Tool = webMountTool(
    name = "wm_open",
    description = """
        Open an allowlisted URL or station in the local iOS WKWebView WebMount session.
        Use `site_id` from wm_stations when possible. URLs outside the WebMount allowlist are rejected.
    """.trimIndent(),
    parameters = webMountOpenParameters()
)

fun createWebMountStateToolDeclaration(): Tool = webMountTool(
    name = "wm_state",
    description = "Read current iOS WebMount WKWebView status, title, redacted URL, and basic page state.",
    parameters = webMountSessionParameters()
)

fun createWebMountObserveToolDeclaration(): Tool = webMountTool(
    name = "wm_observe",
    description = "Observe the current iOS WebMount page: state, visible text, link summary, interactive elements, and DOM visual candidates. Does not expose cookies, tokens, or headers.",
    parameters = webMountSessionParameters()
)

fun createWebMountExtractToolDeclaration(): Tool = webMountTool(
    name = "wm_extract",
    description = "Extract readable text, links, or interactive element summaries from the current iOS WebMount page.",
    parameters = webMountExtractParameters()
)

fun createWebMountGetToolDeclaration(): Tool = webMountTool(
    name = "wm_get",
    description = "Read one element's text, value, HTML, or attribute from the current iOS WebMount page.",
    parameters = webMountGetParameters()
)

fun createWebMountVisualSnapshotToolDeclaration(): Tool = webMountTool(
    name = "wm_visual_snapshot",
    description = "Return visible DOM visual candidates such as image, iframe, canvas, video, SVG, and text block rectangles. No external vision model is called.",
    parameters = webMountSessionParameters()
)

fun createWebMountScreenshotToolDeclaration(): Tool = webMountTool(
    name = "wm_screenshot",
    description = "Capture only the current iOS WebMount viewport to a local artifact. Returns artifact metadata, not base64 image data.",
    parameters = webMountSessionParameters(),
    needsApproval = true
)

fun createWebMountBackToolDeclaration(): Tool = webMountTool(
    name = "wm_back",
    description = "Navigate the current iOS WebMount WKWebView session backward.",
    parameters = webMountSessionParameters()
)

fun createWebMountForwardToolDeclaration(): Tool = webMountTool(
    name = "wm_forward",
    description = "Navigate the current iOS WebMount WKWebView session forward.",
    parameters = webMountSessionParameters()
)

fun createWebMountClearSessionToolDeclaration(): Tool = webMountTool(
    name = "wm_clear_session",
    description = """
        Clear cookies and website data for one iOS WebMount station.
        This requires an explicit foreground user action.
    """.trimIndent(),
    parameters = webMountClearSessionParameters(),
    needsApproval = true
)

fun createWebMountSiteAddToolDeclaration(): Tool = webMountTool(
    name = "wm_site_add",
    description = "Add and enable a local iOS WebMount station after foreground approval. The URL allowlist is synced; no login or OAuth is performed.",
    parameters = webMountSiteAddParameters(),
    needsApproval = true
)

fun createWebMountSiteRemoveToolDeclaration(): Tool = webMountTool(
    name = "wm_site_remove",
    description = "Remove a local iOS WebMount station after foreground approval. This does not clear cookies or website data.",
    parameters = webMountSiteRemoveParameters(),
    needsApproval = true
)

fun createWebMountClickToolDeclaration(): Tool = webMountTool(
    name = "wm_click",
    description = "Click a visible element on the current iOS WebMount page by selector or target ref.",
    parameters = webMountTargetParameters()
)

fun createWebMountTapToolDeclaration(): Tool = webMountTool(
    name = "wm_tap",
    description = "Tap a coordinate or target on the current iOS WebMount page; prefer wm_click when a selector/ref exists.",
    parameters = webMountTargetParameters(includeCoordinates = true)
)

fun createWebMountTypeToolDeclaration(): Tool = webMountTool(
    name = "wm_type",
    description = "Type text into an input element on the current iOS WebMount page.",
    parameters = webMountTextInteractionParameters()
)

fun createWebMountKeysToolDeclaration(): Tool = webMountTool(
    name = "wm_keys",
    description = "Send a short key sequence to the current iOS WebMount page or focused field.",
    parameters = webMountTextInteractionParameters()
)

fun createWebMountScrollToolDeclaration(): Tool = webMountTool(
    name = "wm_scroll",
    description = "Scroll the current iOS WebMount page or an element into view.",
    parameters = webMountScrollParameters()
)

fun createWebMountSelectToolDeclaration(): Tool = webMountTool(
    name = "wm_select",
    description = "Select an option value in a select element on the current iOS WebMount page.",
    parameters = webMountTextInteractionParameters()
)

fun createWebMountFindToolDeclaration(): Tool = webMountTool(
    name = "wm_find",
    description = "Find whether a selector or text appears on the current iOS WebMount page.",
    parameters = webMountFindParameters()
)

fun createWebMountWaitToolDeclaration(): Tool = webMountTool(
    name = "wm_wait",
    description = "Wait briefly for the current iOS WebMount page to settle before the next action.",
    parameters = webMountWaitParameters()
)

fun createSelectedFileReadToolDeclaration(): Tool = Tool(
    name = "file_read_selected",
    description = "Read the text preview of the file the user explicitly selected in AmberAgent iOS.",
    parameters = { emptyObjectParameters() },
    execute = { emptyList() }
)

fun createIshHandoffToolDeclaration(): Tool = Tool(
    name = "ish_handoff",
    description = """
        Prepare a command or shell script for manual execution in the external iSH app on iOS.
        This is a foreground handoff only: AmberAgent writes a script copy, copies a paste-ready
        iSH command to the clipboard, and returns handoff metadata. It does not run iSH in the
        background and cannot read stdout, stderr, exit code, or files from the iSH sandbox.
        Use this only when the user explicitly wants to run something in iSH and can paste it
        into iSH themselves.
    """.trimIndent().replace("\n", " "),
    parameters = { ishHandoffParameters() },
    needsApproval = true,
    mandatoryApproval = true,
    allowsAutoApproval = false,
    execute = { emptyList() }
)

fun createIosIshExecuteToolDeclaration(): Tool = Tool(
    name = "ios_ish_execute",
    description = """
        Execute a POSIX shell command or script inside AmberAgent iOS's embedded experimental iSH runtime.
        This is not the external iSH app: Amber owns the runtime process and returns stdout, stderr,
        exit_code, timeout, and status in the tool result. Available only in the iOS ExperimentalGPL
        build after explicit foreground approval. Use for short, bounded proof commands; do not start
        long-running interactive programs unless the user explicitly asks.
    """.trimIndent().replace("\n", " "),
    parameters = { iosIshExecuteParameters() },
    needsApproval = true,
    mandatoryApproval = true,
    allowsAutoApproval = false,
    execute = { emptyList() }
)

fun createPermissionsStatusToolDeclaration(): Tool = Tool(
    name = "permissions_status",
    description = "Return AmberAgent iOS capability and permission status for tools available on this device.",
    parameters = { emptyObjectParameters() },
    execute = { emptyList() }
)

fun createToolsListToolDeclaration(): Tool = Tool(
    name = "tools_list",
    description = "List the tools currently exposed to this iOS sub-agent run and their intended use.",
    parameters = { emptyObjectParameters() },
    execute = { emptyList() }
)

fun createSubAgentReportToolDeclaration(): Tool = Tool(
    name = "subagent_report",
    description = """
        Finish a sub-agent run by reporting a compact structured result for the supervisor.
        Include summary, findings, evidence, risks, recommended_next_steps, and confidence when available.
    """.trimIndent(),
    parameters = { subAgentReportParameters() },
    execute = { emptyList() }
)

fun iosToolDeclaration(name: String): Tool? = when (name) {
    "ask_user" -> createAskUserToolDeclaration()
    "search_web" -> createSearchWebToolDeclaration()
    "scrape_web" -> createScrapeWebToolDeclaration()
    "memory_tool" -> createMemoryToolDeclaration()
    "workspace_file_read" -> createWorkspaceFileReadToolDeclaration()
    "workspace_file_write" -> createWorkspaceFileWriteToolDeclaration()
    "workspace_file_edit" -> createWorkspaceFileEditToolDeclaration()
    "workspace_file_list" -> createWorkspaceFileListToolDeclaration()
    "workspace_file_search" -> createWorkspaceFileSearchToolDeclaration()
    "workspace_file_move" -> createWorkspaceFileMoveToolDeclaration()
    "workspace_artifact_read" -> createWorkspaceArtifactReadToolDeclaration()
    "workspace_artifact_delete" -> createWorkspaceArtifactDeleteToolDeclaration()
    "generate_image" -> createImageGenToolDeclaration()
    "wm_stations" -> createWebMountStationsToolDeclaration()
    "wm_tab_list" -> createWebMountTabListToolDeclaration()
    "wm_tab_new" -> createWebMountTabNewToolDeclaration()
    "wm_tab_close" -> createWebMountTabCloseToolDeclaration()
    "wm_open" -> createWebMountOpenToolDeclaration()
    "wm_state" -> createWebMountStateToolDeclaration()
    "wm_observe" -> createWebMountObserveToolDeclaration()
    "wm_extract" -> createWebMountExtractToolDeclaration()
    "wm_get" -> createWebMountGetToolDeclaration()
    "wm_visual_snapshot" -> createWebMountVisualSnapshotToolDeclaration()
    "wm_screenshot" -> createWebMountScreenshotToolDeclaration()
    "wm_back" -> createWebMountBackToolDeclaration()
    "wm_forward" -> createWebMountForwardToolDeclaration()
    "wm_clear_session" -> createWebMountClearSessionToolDeclaration()
    "wm_site_add" -> createWebMountSiteAddToolDeclaration()
    "wm_site_remove" -> createWebMountSiteRemoveToolDeclaration()
    "wm_click" -> createWebMountClickToolDeclaration()
    "wm_tap" -> createWebMountTapToolDeclaration()
    "wm_type" -> createWebMountTypeToolDeclaration()
    "wm_keys" -> createWebMountKeysToolDeclaration()
    "wm_scroll" -> createWebMountScrollToolDeclaration()
    "wm_select" -> createWebMountSelectToolDeclaration()
    "wm_find" -> createWebMountFindToolDeclaration()
    "wm_wait" -> createWebMountWaitToolDeclaration()
    "mcp_call" -> createMcpCallToolDeclaration()
    "mcp_list" -> createMcpListToolDeclaration()
    "mcp_test" -> createMcpTestToolDeclaration()
    "mcp_import_from_skill" -> createMcpImportFromSkillToolDeclaration()
    "skills_list" -> createSkillsListToolDeclaration()
    "use_skill" -> createUseSkillToolDeclaration()
    "skill_validate" -> createSkillValidateToolDeclaration()
    "skill_import" -> createSkillImportToolDeclaration()
    "skill_enable" -> createSkillEnableToolDeclaration()
    "skill_disable" -> createSkillDisableToolDeclaration()
    "subagent_dispatch" -> createSubAgentDispatchToolDeclaration()
    "model_council_run" -> createModelCouncilRunToolDeclaration()
    "file_read_selected" -> createSelectedFileReadToolDeclaration()
    "ish_handoff" -> createIshHandoffToolDeclaration()
    "ios_ish_execute" -> createIosIshExecuteToolDeclaration()
    "permissions_status" -> createPermissionsStatusToolDeclaration()
    "tools_list" -> createToolsListToolDeclaration()
    "subagent_report" -> createSubAgentReportToolDeclaration()
    else -> null
}

fun iosToolDeclarations(names: List<String>): List<Tool> = names.distinct().mapNotNull(::iosToolDeclaration)

/**
 * [Slice 3] Tool declaration for dispatching a sub-agent task.
 *
 * The model calls this with an `objective` describing the delegated task and an
 * optional `roleId`. The iOS ChatViewModel detects the call in onComplete and
 * dispatches it to SubAgentRunner.run(objective:) (which drives
 * IosSubAgentFactory.startInput + SubAgentManager), then resumes the stream with
 * the sub-agent's result text as the tool output.
 *
 * NOTE: `execute` returns empty — actual execution lives in the Swift dispatch
 * (same pattern as createSearchWebToolDeclaration, whose real executor is
 * IOSSearchExecutor in Swift).
 */
fun createSubAgentDispatchToolDeclaration(): Tool = Tool(
    name = "subagent_dispatch",
    description = """
        Dispatch a sub-task to a sub-agent that runs in its own isolated context
        with its own system prompt and model. Use when a task should be delegated
        (e.g. research, drafting, code review) rather than done inline. Returns the
        sub-agent's final output as text.
        Provide a clear `objective`; optionally a `role_id` to select a configured
        sub-agent role (e.g. "historian", "researcher").
    """.trimIndent(),
    parameters = { subAgentDispatchParameters() },
    execute = { emptyList() }
)

/**
 * [Slice 3] Tool declaration for running the model council.
 *
 * The model calls this with an `objective` and (optionally) `max_seats`. The iOS
 * ChatViewModel dispatches to the native iOS Council room runner, then resumes
 * the stream with the council's synthesized result as the tool output.
 */
fun createModelCouncilRunToolDeclaration(): Tool = Tool(
    name = "model_council_run",
    description = """
        Convene a multi-seat model council to deliberate on an `objective` and
        return a synthesized answer. Use when a question benefits from multiple
        models/perspectives debating before answering. Returns the council's
        final synthesized output as text.
        Provide a clear `objective`; optionally `max_seats` to cap participants.
    """.trimIndent(),
    parameters = { modelCouncilRunParameters() },
    execute = { emptyList() }
)

private fun subAgentDispatchParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("objective", buildJsonObject {
            put("type", "string")
            put("description", "the task to delegate to the sub-agent")
        })
        put("role_id", buildJsonObject {
            put("type", "string")
            put("description", "optional sub-agent role id (e.g. historian, researcher); omit for default")
        })
    },
    required = listOf("objective")
)

private fun modelCouncilRunParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("objective", buildJsonObject {
            put("type", "string")
            put("description", "the question/objective for the council to deliberate")
        })
        put("max_seats", buildJsonObject {
            put("type", "integer")
            put("minimum", 2)
            put("maximum", 8)
            put("description", "optional cap on non-host council seats (2-8)")
        })
    },
    required = listOf("objective")
)

/**
 * [Slice 3] Generic MCP tool-call declaration. MCP tools are discovered per
 * server (dynamic), but for the chat dispatch we expose a single generic
 * `mcp_call` tool the model can invoke with a `server`, `tool`, and `arguments`
 * object. The iOS ChatViewModel routes it to IOSMcpManager.callTool.
 *
 * (A richer per-tool declaration set can be generated from discovered tools
 * later; this generic form keeps the dispatch closed-loop for Slice 3.)
 */
fun createMcpCallToolDeclaration(): Tool = Tool(
    name = "mcp_call",
    description = """
        Call a tool on a connected MCP (Model Context Protocol) server. Use when
        the user needs an external capability exposed by a configured MCP server
        (filesystem, database, custom API, etc.). Returns the server's tool
        output as text. Provide the `server` name, the `tool` name, and the
        tool's `arguments` as a JSON object.
    """.trimIndent(),
    parameters = { mcpCallParameters() },
    execute = { emptyList() }
)

fun createMcpListToolDeclaration(): Tool = Tool(
    name = "mcp_list",
    description = "List configured MCP servers, enabled state, connection status, and known tool counts. Pass include_tools=true to see callable MCP tool names.",
    parameters = { mcpListParameters() },
    execute = { emptyList() }
)

fun createMcpTestToolDeclaration(): Tool = Tool(
    name = "mcp_test",
    description = "Test one configured MCP server by id or name and refresh its tool list.",
    parameters = { mcpServerLookupParameters() },
    needsApproval = true,
    execute = { emptyList() }
)

fun createMcpImportFromSkillToolDeclaration(): Tool = Tool(
    name = "mcp_import_from_skill",
    description = "Import standard mcp.json from an installed Skill into the global AmberAgent MCP settings.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("skill_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Installed skill name.")
                })
            },
            required = listOf("skill_name")
        )
    },
    needsApproval = true,
    execute = { emptyList() }
)

fun createSkillsListToolDeclaration(): Tool = Tool(
    name = "skills_list",
    description = "List AmberAgent skills and their load status. Use this first when you are unsure which skills are installed, enabled, disabled, or missing.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { emptyList() }
)

fun createUseSkillToolDeclaration(): Tool = Tool(
    name = "use_skill",
    description = """
        Load and apply a skill to get specialized instructions or capabilities.
        Call this tool when the user's request matches one of the available skills.
    """.trimIndent(),
    parameters = { useSkillParameters() },
    execute = { emptyList() }
)

fun createSkillValidateToolDeclaration(): Tool = Tool(
    name = "skill_validate",
    description = "Validate an installed skill by name or a /workspace skill folder/SKILL.md before import.",
    parameters = { skillValidateParameters() },
    execute = { emptyList() }
)

fun createSkillImportToolDeclaration(): Tool = Tool(
    name = "skill_import",
    description = "Import a skill folder or SKILL.md file from /workspace. Imported skills are enabled by default.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("workspace_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace path to a skill folder or SKILL.md.")
                })
            },
            required = listOf("workspace_path")
        )
    },
    needsApproval = true,
    execute = { emptyList() }
)

fun createSkillEnableToolDeclaration(): Tool = Tool(
    name = "skill_enable",
    description = "Enable an installed skill for the current AmberAgent assistant.",
    parameters = { skillNameParameters() },
    needsApproval = true,
    execute = { emptyList() }
)

fun createSkillDisableToolDeclaration(): Tool = Tool(
    name = "skill_disable",
    description = "Disable an installed skill for the current AmberAgent assistant.",
    parameters = { skillNameParameters() },
    needsApproval = true,
    execute = { emptyList() }
)

private fun mcpCallParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("server", buildJsonObject {
            put("type", "string")
            put("description", "the connected MCP server name")
        })
        put("tool", buildJsonObject {
            put("type", "string")
            put("description", "the tool name to call on the server")
        })
        put("arguments", buildJsonObject {
            put("type", "object")
            put("description", "the tool arguments as a JSON object")
        })
    },
    required = listOf("server", "tool")
)

private fun mcpListParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("include_tools", buildJsonObject {
            put("type", "boolean")
            put("description", "Include enabled/disabled tool names for each server. Defaults to true.")
        })
        put("include_schema", buildJsonObject {
            put("type", "boolean")
            put("description", "Include MCP input schemas. Defaults to false.")
        })
    }
)

private fun mcpServerLookupParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("server_id", buildJsonObject {
            put("type", "string")
            put("description", "MCP server id.")
        })
        put("name", buildJsonObject {
            put("type", "string")
            put("description", "MCP server name.")
        })
    }
)

private fun useSkillParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("name", buildJsonObject {
            put("type", "string")
            put("description", "The name of the skill to use")
        })
        put("path", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions."
            )
        })
    },
    required = listOf("name")
)

private fun skillValidateParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("name", buildJsonObject {
            put("type", "string")
            put("description", "Installed skill name.")
        })
        put("workspace_path", buildJsonObject {
            put("type", "string")
            put("description", "Workspace skill folder or SKILL.md.")
        })
    }
)

private fun skillNameParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("name", buildJsonObject {
            put("type", "string")
            put("description", "Skill name.")
        })
    },
    required = listOf("name")
)

private fun searchWebParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("query", buildJsonObject {
            put("type", "string")
            put("description", "search keyword")
        })
        put("topic", buildJsonObject {
            put("type", "string")
            put("description", "search topic")
            put("enum", buildJsonArray {
                add("general")
                add("news")
                add("market")
                add("technical")
                add("finance")
            })
        })
        put("time_range", buildJsonObject {
            put("type", "string")
            put("description", "recency window for current/news searches")
            put("enum", buildJsonArray {
                add("day")
                add("week")
                add("month")
                add("year")
                add("any")
            })
        })
        put("max_results", buildJsonObject {
            put("type", "integer")
            put("description", "maximum merged results to return")
        })
    },
    required = listOf("query")
)

private fun scrapeWebParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("url", buildJsonObject {
            put("type", "string")
            put("description", "public http/https URL to fetch and extract")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "maximum extracted characters to return")
        })
        put("service", buildJsonObject {
            put("type", "string")
            put("description", "optional provider hint; iOS MVP uses safe direct fetch and may ignore unsupported services")
        })
    },
    required = listOf("url")
)

private fun memoryToolParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("action", buildJsonObject {
            put("type", "string")
            put("description", "operation to perform")
            put("enum", buildJsonArray {
                add("list")
                add("create")
                add("edit")
                add("delete")
            })
        })
        put("id", buildJsonObject {
            put("type", "integer")
            put("description", "memory id for edit/delete")
        })
        put("scope", buildJsonObject {
            put("type", "string")
            put("description", "memory scope")
            put("enum", buildJsonArray {
                add("core")
                add("short_term")
                add("long_term")
                add("all")
            })
        })
        put("kind", buildJsonObject {
            put("type", "string")
            put("description", "memory kind")
            put("enum", buildJsonArray {
                add("user")
                add("feedback")
                add("project")
                add("reference")
                add("routine")
                add("note")
            })
        })
        put("content", buildJsonObject {
            put("type", "string")
            put("description", "memory content for create/edit")
        })
        put("pinned", buildJsonObject {
            put("type", "boolean")
            put("description", "whether this memory should sort ahead of ordinary memories")
        })
        put("sourceConversationId", buildJsonObject {
            put("type", "string")
            put("description", "optional source conversation id")
        })
        put("sourceMessageIds", buildJsonObject {
            put("type", "array")
            put("description", "optional source message ids")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("expiresAt", buildJsonObject {
            put("type", "integer")
            put("description", "optional expiration epoch milliseconds")
        })
        put("confidence", buildJsonObject {
            put("type", "number")
            put("description", "confidence from 0 to 1")
        })
    },
    required = listOf("action")
)

private fun workspaceFileReadParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("file_id", buildJsonObject {
            put("type", "string")
            put("description", "Workspace file id returned by Workspace, optional when `path` is provided")
        })
        put("path", buildJsonObject {
            put("type", "string")
            put("description", "Workspace path such as /workspace/uploads/example.md, optional when `file_id` is provided")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "maximum text characters to return")
        })
    }
)

private fun workspaceFileWriteParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("path", buildJsonObject {
            put("type", "string")
            put("description", "target path under /workspace, for example /workspace/notes/summary.md")
        })
        put("content", buildJsonObject {
            put("type", "string")
            put("description", "UTF-8 text or Markdown content to write")
        })
        put("overwrite", buildJsonObject {
            put("type", "boolean")
            put("description", "set true to replace an existing Workspace file")
        })
    },
    required = listOf("path", "content")
)

private fun workspaceFileEditParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("file_id", buildJsonObject {
            put("type", "string")
            put("description", "Workspace file id; optional when `path` is provided")
        })
        put("path", buildJsonObject {
            put("type", "string")
            put("description", "Workspace path such as /workspace/notes/summary.md")
        })
        put("find", buildJsonObject {
            put("type", "string")
            put("description", "Text to replace")
        })
        put("replace", buildJsonObject {
            put("type", "string")
            put("description", "Replacement text")
        })
    },
    required = listOf("find", "replace")
)

private fun workspaceFileListParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("path", buildJsonObject {
            put("type", "string")
            put("description", "Optional Workspace path prefix")
        })
        put("limit", buildJsonObject {
            put("type", "integer")
            put("description", "Maximum files to return")
        })
    }
)

private fun workspaceFileSearchParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("query", buildJsonObject {
            put("type", "string")
            put("description", "Text to search for in Workspace file previews")
        })
        put("limit", buildJsonObject {
            put("type", "integer")
            put("description", "Maximum matches to return")
        })
    },
    required = listOf("query")
)

private fun workspaceFileMoveParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("file_id", buildJsonObject {
            put("type", "string")
            put("description", "Workspace file id; optional when `path` is provided")
        })
        put("path", buildJsonObject {
            put("type", "string")
            put("description", "Current Workspace path")
        })
        put("destination_path", buildJsonObject {
            put("type", "string")
            put("description", "Destination Workspace path")
        })
    },
    required = listOf("destination_path")
)

private fun workspaceArtifactReadParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("artifact_id", buildJsonObject {
            put("type", "string")
            put("description", "Workspace artifact id")
        })
        put("id", buildJsonObject {
            put("type", "string")
            put("description", "alias for artifact_id")
        })
    }
)

private fun imageGenParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("prompt", buildJsonObject {
            put("type", "string")
            put("description", "Detailed image prompt. Include subject, style, composition, lighting, and mood.")
        })
        put("aspect_ratio", buildJsonObject {
            put("type", "string")
            put("description", "Image aspect ratio.")
            put("enum", buildJsonArray {
                add("1:1")
                add("16:9")
                add("9:16")
            })
        })
        put("count", buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 4)
            put("description", "Number of variants to generate, 1-4. Default 1.")
        })
        put("style", buildJsonObject {
            put("type", "string")
            put("description", "Optional style hint, for example photo, watercolor, poster, or product mockup.")
        })
    },
    required = listOf("prompt")
)

private fun ishHandoffParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("command", buildJsonObject {
            put("type", "string")
            put("description", "Single shell command to run in iSH. Use either command or script.")
        })
        put("script", buildJsonObject {
            put("type", "string")
            put("description", "Full POSIX /bin/sh script content to paste into iSH. Use either script or command.")
        })
        put("filename", buildJsonObject {
            put("type", "string")
            put("description", "Optional safe .sh filename to use in iSH and AmberAgent handoff storage.")
        })
        put("purpose", buildJsonObject {
            put("type", "string")
            put("description", "Short user-facing reason for this iSH handoff.")
        })
    }
)

private fun iosIshExecuteParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("command", buildJsonObject {
            put("type", "string")
            put("description", "Single POSIX shell command to execute with /bin/sh -lc. Use either command or script, not both.")
        })
        put("script", buildJsonObject {
            put("type", "string")
            put("description", "Full POSIX /bin/sh script content to execute. Use either script or command, not both.")
        })
        put("timeout_seconds", buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 180)
            put("description", "Execution timeout in seconds. Default 60, maximum 180.")
        })
        put("purpose", buildJsonObject {
            put("type", "string")
            put("description", "Short user-facing reason for this embedded iSH execution.")
        })
    }
)

private fun webMountTool(
    name: String,
    description: String,
    parameters: InputSchema,
    needsApproval: Boolean = false
): Tool = Tool(
    name = name,
    description = description,
    parameters = { parameters },
    needsApproval = needsApproval,
    allowsAutoApproval = !needsApproval,
    mandatoryApproval = needsApproval,
    execute = { emptyList() }
)

private fun workspaceTool(
    name: String,
    description: String,
    parameters: InputSchema
): Tool = Tool(
    name = name,
    description = description,
    parameters = { parameters },
    needsApproval = true,
    allowsAutoApproval = false,
    mandatoryApproval = true,
    execute = { emptyList() }
)

private fun emptyObjectParameters(): InputSchema = InputSchema.Obj(properties = buildJsonObject { })

private fun askUserParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("question", buildJsonObject {
            put("type", "string")
            put("description", "The focused question to present inline to the user.")
        })
        put("options", buildJsonObject {
            put("type", "array")
            put("description", "Two to six suggested answers, or an empty array for free text.")
            put("items", buildJsonObject { put("type", "string") })
            put("maxItems", 6)
        })
    },
    required = listOf("question", "options")
)

private fun webMountStationsParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("auth_kind_filter", buildJsonObject {
            put("type", "string")
            put("description", "optional filter: anonymous, cookie, or oauth")
            put("enum", buildJsonArray {
                add("anonymous")
                add("cookie")
                add("oauth")
            })
        })
    }
)

private fun JsonObjectBuilder.putWebMountSessionId() {
    put("session_id", buildJsonObject {
        put("type", "string")
        put("description", "optional WebMount session id from wm_tab_list; omit to use the current foreground session")
    })
}

private fun webMountSessionParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
    }
)

private fun webMountTabNewParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("site_id", buildJsonObject {
            put("type", "string")
            put("description", "optional station id to associate with the new session")
        })
    }
)

private fun webMountTabCloseParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
    }
)

private fun webMountOpenParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("site_id", buildJsonObject {
            put("type", "string")
            put("description", "optional station id from wm_stations, e.g. github")
        })
        put("url", buildJsonObject {
            put("type", "string")
            put("description", "optional https URL; must match WebMount allowlist")
        })
        put("timeout_ms", buildJsonObject {
            put("type", "integer")
            put("description", "load timeout in milliseconds, clamped by iOS")
        })
    }
)

private fun webMountExtractParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("mode", buildJsonObject {
            put("type", "string")
            put("description", "readable text, interactive element summary, or snapshot")
            put("enum", buildJsonArray {
                add("readable")
                add("interactive")
                add("snapshot")
            })
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "maximum text characters to return")
        })
        put("max_links", buildJsonObject {
            put("type", "integer")
            put("description", "maximum links to return")
        })
    }
)

private fun webMountGetParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector to read")
        })
        put("target", buildJsonObject {
            put("type", "string")
            put("description", "optional target ref such as css:body")
        })
        put("kind", buildJsonObject {
            put("type", "string")
            put("description", "value to read from the target")
            put("enum", buildJsonArray {
                add("text")
                add("value")
                add("attr")
                add("html")
            })
        })
        put("attr_name", buildJsonObject {
            put("type", "string")
            put("description", "attribute name when kind is attr")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "maximum characters to return")
        })
    }
)

private fun webMountClearSessionParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("site_id", buildJsonObject {
            put("type", "string")
            put("description", "station id from wm_stations")
        })
    },
    required = listOf("site_id")
)

private fun webMountSiteAddParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("display_name", buildJsonObject {
            put("type", "string")
            put("description", "station display name")
        })
        put("name", buildJsonObject {
            put("type", "string")
            put("description", "alias for display_name")
        })
        put("homepage_url", buildJsonObject {
            put("type", "string")
            put("description", "http(s) homepage URL for the station")
        })
        put("url", buildJsonObject {
            put("type", "string")
            put("description", "alias for homepage_url")
        })
        put("needs_login", buildJsonObject {
            put("type", "boolean")
            put("description", "whether the site should be treated as cookie-login based")
        })
        put("login_cookie_name", buildJsonObject {
            put("type", "string")
            put("description", "optional cookie name hint; values are never exposed")
        })
        put("enabled", buildJsonObject {
            put("type", "boolean")
            put("description", "whether to enable the station immediately; defaults to true after approval")
        })
    }
)

private fun webMountSiteRemoveParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("site_id", buildJsonObject {
            put("type", "string")
            put("description", "station id from wm_stations")
        })
    },
    required = listOf("site_id")
)

private fun webMountTargetParameters(includeCoordinates: Boolean = false): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector for the target element")
        })
        put("target", buildJsonObject {
            put("type", "string")
            put("description", "Target ref from wm_extract/wm_find, when available")
        })
        if (includeCoordinates) {
            put("x", buildJsonObject {
                put("type", "number")
                put("description", "X coordinate in viewport pixels")
            })
            put("y", buildJsonObject {
                put("type", "number")
                put("description", "Y coordinate in viewport pixels")
            })
        }
    }
)

private fun webMountTextInteractionParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector for the target element")
        })
        put("target", buildJsonObject {
            put("type", "string")
            put("description", "Target ref from wm_extract/wm_find, when available")
        })
        put("text", buildJsonObject {
            put("type", "string")
            put("description", "Text, keys, or option value to send")
        })
        put("value", buildJsonObject {
            put("type", "string")
            put("description", "Alias for text when selecting or typing a value")
        })
    }
)

private fun webMountScrollParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "Optional CSS selector to scroll")
        })
        put("target", buildJsonObject {
            put("type", "string")
            put("description", "Target ref from wm_extract/wm_find")
        })
        put("to", buildJsonObject {
            put("type", "string")
            put("description", "Named position such as top, bottom, or visible")
        })
        put("by_y", buildJsonObject {
            put("type", "number")
            put("description", "Vertical pixel delta")
        })
    }
)

private fun webMountFindParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector to find")
        })
        put("text", buildJsonObject {
            put("type", "string")
            put("description", "Text to find on the page")
        })
        put("max_results", buildJsonObject {
            put("type", "integer")
            put("description", "Maximum matches to return")
        })
    }
)

private fun webMountWaitParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        putWebMountSessionId()
        put("timeout_ms", buildJsonObject {
            put("type", "integer")
            put("description", "Wait duration in milliseconds, clamped by iOS")
        })
    }
)

private fun subAgentReportParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("summary", buildJsonObject {
            put("type", "string")
            put("description", "Concise final summary")
        })
        put("findings", buildJsonObject {
            put("type", "array")
            put("description", "Key findings")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("evidence", buildJsonObject {
            put("type", "array")
            put("description", "Evidence or source references")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("risks", buildJsonObject {
            put("type", "array")
            put("description", "Risks, uncertainty, or limitations")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("recommended_next_steps", buildJsonObject {
            put("type", "array")
            put("description", "Recommended next steps")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("confidence", buildJsonObject {
            put("type", "number")
            put("description", "Confidence from 0 to 1")
        })
    }
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
