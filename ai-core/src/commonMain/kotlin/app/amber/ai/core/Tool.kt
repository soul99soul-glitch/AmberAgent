package app.amber.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

// MARK: - Novel discussion project tools
//
// Novel-session-only write tools. They are declared here but deliberately NOT
// registered in the `iosToolDeclaration` catalog or ToolSearch: the discussion
// agent assembles them only inside the novel discussion transport
// (`NovelLiveModelAdapter.makeParameters`), so they never leak into ordinary
// Chat/subagent tool sets. Execution lives in `IOSNovelProjectToolExecutor`,
// which routes every call through `DefaultNovelCreation.perform` (the single
// reducer transaction path).

fun createNovelRenameProjectToolDeclaration(): Tool = Tool(
    name = "novel_rename_project",
    description = """
        Rename the user's novel project. Use when the user asks to change the project title.
        `title` is the new project name; `reason` is an optional short note for the rename.
        The change is saved directly into the novel project document.
    """.trimIndent(),
    parameters = { novelRenameProjectParameters() },
    execute = { emptyList() }
)

fun createNovelSetPolishPreferenceToolDeclaration(): Tool = Tool(
    name = "novel_set_polish_preference",
    description = """
        Set the novel project's polish preference (style requirements applied when polishing chapters).
        Pass an empty `preference` string to clear the stored preference.
        The change is saved directly into the novel project document.
    """.trimIndent(),
    parameters = { novelSetPolishPreferenceParameters() },
    execute = { emptyList() }
)

fun createNovelUpsertUpcomingArcToolDeclaration(): Tool = Tool(
    name = "novel_upsert_upcoming_arc",
    description = """
        Save the branch's upcoming-arc notes ("往后几章") as a bounded soft direction.
        `beats` is a list of short beat notes: at most 8 beats, each at most 160 characters;
        longer or extra beats are rejected. Replaces any previously saved arc on this branch.
        The change is saved directly into the novel project document.
    """.trimIndent(),
    parameters = { novelUpsertUpcomingArcParameters() },
    execute = { emptyList() }
)

fun createNovelClearUpcomingArcToolDeclaration(): Tool = Tool(
    name = "novel_clear_upcoming_arc",
    description = """
        Clear the branch's upcoming-arc notes ("往后几章") on the user's novel project.
        Takes no arguments. Fails if the branch has no arc notes to clear.
        The change is saved directly into the novel project document.
    """.trimIndent(),
    parameters = { emptyObjectParameters() },
    execute = { emptyList() }
)

fun createNovelReviseMaterialToolDeclaration(): Tool = Tool(
    name = "novel_revise_material",
    description = """
        Create or update a setting material (设定资料) in the user's novel project.
        With `material_id`, update that existing material (its `kind` must match); without it, create a new material.
        `kind` is one of world/character/relationship/masterOutline/writingRequirements/custom;
        `aliases` only applies to character materials; `custom_name` names a new custom card
        (default「自定义」). When the user's intent is ambiguous,
        ask the user first (ask_user) before writing. The change is saved directly into the novel project document.
    """.trimIndent(),
    parameters = { novelReviseMaterialParameters() },
    execute = { emptyList() }
)

fun createNovelProposeChapterPlanToolDeclaration(): Tool = Tool(
    name = "novel_propose_chapter_plan",
    description = """
        Save a DRAFT chapter plan for the current branch of the user's novel project.
        The plan is always stored as a draft and must be manually confirmed by the user in the
        project control panel before ghostwrite can use it; never present it as already confirmed.
        `must_happen`/`must_not_happen`/`visible_facts` may be empty arrays; `goal_and_conflict` is required.
        The change is saved directly into the novel project document.
    """.trimIndent(),
    parameters = { novelProposeChapterPlanParameters() },
    execute = { emptyList() }
)

fun createMemoryToolDeclaration(): Tool = Tool(
    name = "memory_tool",
    description = """
        Read and update AmberAgent iOS memories. Use `action`:
        - `list`: list saved memories visible under the enabled memory scopes.
        - `read`, `search`, `query`: actively find memories, optionally filtered by `scope`/`kind`; call these to recall more than the injected set when the conversation needs continuity.
        - `status`: check whether memory recall is available.
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
        prompts. When the user attached an image and wants a style transfer, remake, edit, or
        based-on-this-image result, set use_attached_image=true so the host pads that attached
        reference into Codex image2; do not rely on a text-only redraw of the attachment. You may
        also pass source_image_url for a known earlier chat image URL. Use SVG or markdown diagrams
        for precise editable charts and diagrams unless the user explicitly asks for an artistic
        rendering.
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

/**
 * P3-b: `exec` tool — runs JavaScript (ES2020) in a sandbox (iOS:
 * JavaScriptCore via IOSJsSandboxEngine; Android: QuickJS via eval_javascript,
 * which stays as its own legacy tool). No DOM, no Node, no fs, no network, no
 * imports. The result is the value of the last expression (JSON-ified);
 * console.log/info/warn/error are captured into `logs`. Inside the sandbox a
 * `tools` object exposes the currently visible tool set: nested calls are
 * SYNCHRONOUS (the JS thread blocks while the host executes; no
 * await/Promise needed, Promise.all concurrency is not supported in v1) and
 * inherit each tool's own approval policy — the exec container itself never
 * asks for extra approval. Approval flags mirror Android `eval_javascript`
 * (Tool defaults: needsApproval=false, allowsAutoApproval=true) — the
 * per-assistant/global opt-in switch is the gate, not a per-call approval
 * card. iOS execution lives in ChatToolRuntime (Swift); `execute` is empty
 * here, same pattern as createSearchWebToolDeclaration.
 */
fun createExecToolDeclaration(): Tool = Tool(
    name = "exec",
    description = """
        Run JavaScript (ES2020) in a sandbox. No DOM, no Node, no fs, no network, no imports.
        Use console.log for output; the last expression's value is returned.
        Inside the sandbox, `tools` exposes the tools visible in this run; tools.* calls are
        synchronous (no await/Promise needed; Promise.all concurrency is not supported in v1),
        and nested calls inherit each tool's own approval policy.
        The global `ALL_TOOLS` lists every callable tool's `name` and `description`; filter it to discover tools instead of guessing names.
        NOT for generating SVG/widgets/HTML.
    """.trimIndent().replace("\n", " "),
    parameters = { execParameters() },
    needsApproval = false,
    allowsAutoApproval = true,
    execute = { emptyList() }
)

private fun execParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("code", buildJsonObject {
            put("type", "string")
            put("description", "Required. The JavaScript source to evaluate (ES2020). Raw code, no markdown fences.")
        })
        put("timeout_ms", buildJsonObject {
            put("type", "integer")
            put("description", "Optional. Maximum evaluation time in milliseconds, clamped to [1000, 30000]; defaults to 10000. A timed-out script is abandoned: its result is discarded and its engine context is never reused.")
        })
        put("max_output_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Optional. Maximum characters for the returned payload, clamped to [1, 100000]; defaults to 10000.")
        })
    },
    required = listOf("code"),
)

/**
 * P3-c: `wait` tool — continues a yielded `exec` cell. When an exec evaluation
 * runs past its `timeout_ms`, the handle is NOT dropped: exec returns
 * "Script running with cell ID {cell_id}" and the evaluation keeps running on
 * its own queue. `wait` blocks on that cell until it reaches a terminal state
 * (Completed | Terminated | Failed | interrupted) or the wait timeout elapses,
 * and returns `{status, output, logs}`. `terminate=true` marks the cell
 * Terminated (abandon semantics: JavaScriptCore cannot force-kill a runaway
 * script, it keeps burning CPU until it ends by itself). Cells and the
 * `store`/`load` KV are scoped per conversation and survive across runs;
 * every wait consumes one ordinary tool-resume budget slot (no separate
 * budget mechanism). Approval flags mirror `exec` — the container never asks
 * for extra approval, the per-assistant/global opt-in switch is the gate.
 * iOS execution lives in ChatToolRuntime (Swift); `execute` is empty here,
 * same pattern as createExecToolDeclaration.
 */
fun createWaitToolDeclaration(): Tool = Tool(
    name = "wait",
    description = """
        Wait for a running exec cell to finish. `cell_id` is returned by exec when a script times out (yield).
        Blocks until the cell completes or the wait timeout elapses; returns {status, output, logs}.
        terminate=true abandons the cell (status=terminated).
    """.trimIndent().replace("\n", " "),
    parameters = { waitParameters() },
    needsApproval = false,
    allowsAutoApproval = true,
    execute = { emptyList() }
)

private fun waitParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("cell_id", buildJsonObject {
            put("type", "string")
            put("description", "Required. The cell ID returned by exec when a script timed out (yield).")
        })
        put("timeout_ms", buildJsonObject {
            put("type", "integer")
            put("description", "Optional. Maximum wait in milliseconds, clamped to [1000, 60000]; defaults to 10000. A wait that elapses while the cell is still running returns its current running status instead of failing.")
        })
        put("terminate", buildJsonObject {
            put("type", "boolean")
            put("description", "Optional. When true, mark the cell terminated (abandon) and return its terminal status immediately instead of waiting.")
        })
    },
    required = listOf("cell_id"),
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

/**
 * P1-c: spawn a child agent thread. The child runs asynchronously with the same
 * tools as this thread and can spawn its own subagents (depth is capped by the
 * harness). The child's conversation is forked from this thread's history
 * (optionally truncated by `fork_turns`), receives the initial `message` as a
 * NEW_TASK, and its final answer is delivered back through the mailbox when it
 * finishes. `task_name` must be lowercase letters, digits and underscores; the
 * canonical agent path is `/root/{task_name}` (or `/root/{parent_task}/{task_name}`
 * for grandchildren). Use `list_agents` to inspect threads and
 * `interrupt_agent` to stop a child without destroying its thread.
 */
fun createSpawnAgentToolDeclaration(): Tool = Tool(
    name = "spawn_agent",
    description = """
        Spawn a child agent thread that runs asynchronously. The spawned agent has
        the same tools as you and can spawn its own subagents. The child receives a
        fork of this thread's history (truncated by fork_turns) plus the initial
        `message` as its NEW_TASK, and delivers its FINAL_ANSWER back to this thread
        when it finishes. task_name must be lowercase letters, digits and underscores;
        the canonical agent path is /root/{task_name} (or
        /root/{parent_task}/{task_name} for grandchildren). Inspect threads with
        list_agents; stop a child with interrupt_agent (the thread stays addressable).
    """.trimIndent(),
    parameters = { spawnAgentParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

/** P1-c: list the agent threads spawned from this thread (and their transitive children). */
fun createListAgentsToolDeclaration(): Tool = Tool(
    name = "list_agents",
    description = """
        List the agent threads spawned from this thread, including transitive
        children: canonical agent path, child thread id, nickname, role assistant,
        thread status (Open/Closed) and the latest run status of each child.
        Optionally filter by a path prefix.
    """.trimIndent(),
    parameters = { listAgentsParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

/** P1-c: interrupt a child agent's active run. The thread itself stays Open and addressable. */
fun createInterruptAgentToolDeclaration(): Tool = Tool(
    name = "interrupt_agent",
    description = """
        Interrupt the active run of a spawned agent thread by its child_thread_id
        or canonical agent path. The thread is preserved (stays Open and
        addressable, e.g. for a later follow-up); only the running turn is
        cancelled. Returns the previous run status; an idle thread returns
        previous_status "idle" without error.
    """.trimIndent(),
    parameters = { interruptAgentParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

/**
 * P1-d: deliver a message to a spawned agent thread without waking it. The
 * message enters the target thread's mailbox but does not trigger a new turn:
 * an idle target's messages stay queued until its next run, and a running
 * target receives them at its next tool-loop boundary. Sending to your own
 * thread or to a thread outside your tree is rejected.
 */
fun createSendMessageToolDeclaration(): Tool = Tool(
    name = "send_message",
    description = """
        Deliver a message to a spawned agent thread (by child_thread_id or
        canonical agent path) without waking it. It does not trigger a new turn:
        an idle target's messages stay in its mailbox until its next run, and a
        running target receives them at its next tool-loop boundary. You cannot
        message your own thread; targets outside your thread tree are rejected.
    """.trimIndent(),
    parameters = { sendMessageParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

/**
 * P1-d: deliver a follow-up task to a spawned agent thread and wake it when
 * idle. Unlike send_message, an idle target starts a new run with the message
 * immediately; a running target queues the message in its mailbox and folds it
 * in at its next tool-loop boundary.
 */
fun createFollowupTaskToolDeclaration(): Tool = Tool(
    name = "followup_task",
    description = """
        Deliver a follow-up task message to a spawned agent thread (by
        child_thread_id or canonical agent path) and wake it. If the target is
        idle (no active run) it starts a new run with the message immediately;
        if it is running, the message is queued in its mailbox and folded in at
        its next tool-loop boundary. You cannot send to your own thread;
        targets outside your thread tree are rejected.
    """.trimIndent(),
    parameters = { followupTaskParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

/**
 * P1-d: suspend this tool call until this thread's mailbox receives any
 * activity (a message from another thread or a child's final answer), until
 * the wait is interrupted by new user input, or until timeout_ms elapses.
 * Returns immediately when the mailbox already has pending activity.
 */
fun createWaitAgentToolDeclaration(): Tool = Tool(
    name = "wait_agent",
    description = """
        Suspend this tool call until this thread's mailbox receives any activity
        (a message from another thread or a child's final answer), until the
        wait is interrupted by new user input, or until timeout_ms elapses.
        Returns immediately when the mailbox already has pending activity.
        timeout_ms is clamped to [5000, 300000] milliseconds and defaults to
        30000.
    """.trimIndent(),
    parameters = { waitAgentParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

/**
 * Cross-session conversation read tools (`session_search` + `session_read`).
 * Unlike Android's current-session `conversation_search`/`conversation_expand`,
 * these search and read ALL persisted conversations on this device (titles and
 * message text) — the agent can follow up on another session's past chat.
 * Read-only: no approval, ledger classification is pure. iOS local execution
 * lives in ChatToolRuntime (Swift); `execute` is empty here, same pattern as
 * createSearchWebToolDeclaration. Declared via iosToolDeclaration, deferred
 * (tool_search exposes them) — Android wiring is a follow-up.
 */
fun createSessionSearchToolDeclaration(): Tool = Tool(
    name = "session_search",
    description = """
        Search across ALL conversations (titles and message text) on this device.
        Use when the user references another session/conversation or past chat.
        Returns matching sessions with snippets; follow up with `session_read` to read one.
    """.trimIndent().replace("\n", " "),
    parameters = { sessionSearchParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

fun createSessionReadToolDeclaration(): Tool = Tool(
    name = "session_read",
    description = """
        Read recent messages of a conversation by id (from session_search results).
        Returns the latest messages as text. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = { sessionReadParameters() },
    needsApproval = false,
    execute = { emptyList() }
)

private fun sessionSearchParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("query", buildJsonObject {
            put("type", "string")
            put("description", "Required. Keywords to search across all conversation titles and message text on this device.")
        })
        put("limit", buildJsonObject {
            put("type", "integer")
            put("description", "Optional. Maximum number of matching sessions to return, clamped to [1, 20]; defaults to 8.")
        })
    },
    required = listOf("query"),
)

private fun sessionReadParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("conversation_id", buildJsonObject {
            put("type", "string")
            put("description", "Required. The conversation id (UUID) of a session, taken from session_search results.")
        })
        put("max_messages", buildJsonObject {
            put("type", "integer")
            put("description", "Optional. Maximum number of latest messages to return, clamped to [1, 50]; defaults to 20.")
        })
    },
    required = listOf("conversation_id"),
)

private fun spawnAgentParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("task_name", buildJsonObject {
            put("type", "string")
            put("pattern", "^[a-z0-9_]+$")
            put("description", "Required. Lowercase letters, digits and underscores only; used for the canonical agent path (e.g. research_notes).")
        })
        put("message", buildJsonObject {
            put("type", "string")
            put("description", "Required. The initial task message the spawned agent receives as its NEW_TASK.")
        })
        put("fork_turns", buildJsonObject {
            put("type", "string")
            put("description", "How much of this thread's history the child inherits: \"none\" (empty), \"all\" (full copy), or a positive-integer string N (last N user turns). Defaults to \"all\".")
        })
        put("role_assistant_id", buildJsonObject {
            put("type", "string")
            put("description", "Optional assistant id the child conversation should use.")
        })
    },
    required = listOf("task_name", "message"),
)

private fun listAgentsParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("path_prefix", buildJsonObject {
            put("type", "string")
            put("description", "Optional agent path prefix to filter the listed threads (e.g. /root/research).")
        })
    },
)

private fun interruptAgentParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("target", buildJsonObject {
            put("type", "string")
            put("description", "Required. child_thread_id (uuid) or canonical agent path of the agent thread to interrupt.")
        })
    },
    required = listOf("target"),
)

private fun sendMessageParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("target", buildJsonObject {
            put("type", "string")
            put("description", "Required. child_thread_id (uuid) or canonical agent path of the target thread.")
        })
        put("message", buildJsonObject {
            put("type", "string")
            put("description", "Required. The message text to deliver. It does not trigger a new turn; an idle target's messages stay in its mailbox until its next run.")
        })
    },
    required = listOf("target", "message"),
)

private fun followupTaskParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("target", buildJsonObject {
            put("type", "string")
            put("description", "Required. child_thread_id (uuid) or canonical agent path of the target thread.")
        })
        put("message", buildJsonObject {
            put("type", "string")
            put("description", "Required. The follow-up task message. Wakes an idle target into a new run; a running target receives it at its next tool-loop boundary.")
        })
    },
    required = listOf("target", "message"),
)

private fun waitAgentParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("timeout_ms", buildJsonObject {
            put("type", "integer")
            put("description", "Optional maximum wait in milliseconds, clamped to [5000, 300000]; defaults to 30000. The wait ends earlier on mailbox activity or when interrupted by new input.")
        })
    },
)

fun iosToolDeclaration(name: String): Tool? = when (name) {
    "ask_user" -> createAskUserToolDeclaration()
    "exec" -> createExecToolDeclaration()
    "wait" -> createWaitToolDeclaration()
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
    "mcp_describe_tool" -> createMcpDescribeToolDeclaration()
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
    "spawn_agent" -> createSpawnAgentToolDeclaration()
    "list_agents" -> createListAgentsToolDeclaration()
    "interrupt_agent" -> createInterruptAgentToolDeclaration()
    "send_message" -> createSendMessageToolDeclaration()
    "followup_task" -> createFollowupTaskToolDeclaration()
    "wait_agent" -> createWaitAgentToolDeclaration()
    "session_search" -> createSessionSearchToolDeclaration()
    "session_read" -> createSessionReadToolDeclaration()
    else -> null
}

fun iosToolDeclarations(names: List<String>): List<Tool> = names.distinct().mapNotNull(::iosToolDeclaration)

/**
 * [Slice 3] Tool declaration for dispatching a sub-agent task.
 *
 * The model calls this with an `objective` describing the delegated task and an
 * optional `roleId`. The iOS chat runtime dispatches it through
 * `SubAgentRunner.runViaEngine`, backed by `IOSAgentToolEngine`, then resumes the
 * stream with the sub-agent's result text as the tool output.
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
        Provide a clear `objective`; optionally a `role_id` to select a built-in
        sub-agent role (explorer, historian, oracle, designer, writer, fixer), or
        pass `custom_role_prompt` (with optional `custom_role_name` and
        `custom_role_lens`) for a one-off custom role. `tool_scope` narrows the
        sub-agent's tools within the read-only allowlist. `max_turns` (2-8) and
        `output_budget_chars` (4000-24000) budget the run.
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
            put("description", "optional built-in sub-agent role id (explorer, historian, oracle, designer, writer, fixer); omit for the default role")
        })
        put("custom_role_name", buildJsonObject {
            put("type", "string")
            put("description", "optional display name for a one-off custom role; used with custom_role_prompt")
        })
        put("custom_role_lens", buildJsonObject {
            put("type", "string")
            put("description", "optional focus lens or summary for a one-off custom role")
        })
        put("custom_role_prompt", buildJsonObject {
            put("type", "string")
            put("description", "optional system prompt for a one-off custom role; when present a custom role is used instead of role_id")
        })
        put("max_turns", buildJsonObject {
            put("type", "integer")
            put("minimum", 2)
            put("maximum", 8)
            put("description", "optional max engine turns for the sub-agent run; clamped to 2-8 (custom roles default to 4)")
        })
        put("output_budget_chars", buildJsonObject {
            put("type", "integer")
            put("minimum", 4000)
            put("maximum", 24000)
            put("description", "optional output budget in characters; clamped to 4000-24000 (custom roles default to 12000)")
        })
        put("tool_scope", buildJsonObject {
            put("type", "array")
            put("description", "optional list of tool names the sub-agent may use; narrowed within the read-only allowlist")
            put("items", buildJsonObject { put("type", "string") })
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

fun createMcpDescribeToolDeclaration(): Tool = Tool(
    name = "mcp_describe_tool",
    description = """
        Return the full description and input JSON schema of one discovered MCP
        tool. Call this before `mcp_call` when you need the exact argument names
        and types for a tool's `arguments`.
    """.trimIndent(),
    parameters = { mcpDescribeToolParameters() },
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

/**
 * P0-b: one discovered MCP tool flattened into an independent declaration
 * input. `inputSchema` is the raw `tools/list` JSON schema (nullable — some
 * servers omit it). Swift cannot construct [JsonObject] directly (Kotlin/Native
 * exports it as an opaque NSDictionary), so the secondary constructor parses
 * the raw persisted schema JSON text that IOSMcpTool carries.
 */
@Serializable
data class McpDiscoveredToolSpec(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null,
) {
    constructor(name: String, description: String?, inputSchemaJson: String?) : this(
        name = name,
        description = description,
        inputSchema = parseMcpSchemaJson(inputSchemaJson),
    )
}

private fun parseMcpSchemaJson(text: String?): JsonObject? =
    text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

/** P0-b: flattened `mcp__{server}__{tool}` name (MCP community / Claude Code
 *  convention). Each part is sanitized (non `[a-zA-Z0-9_-]` → `_`); the whole
 *  name is truncated to 64 chars when overlong — deterministic by construction.
 *  Sanitization is NOT reversible, so execution resolves back to a directory
 *  (see `mcpExpandedToolDeclarations` consumers) instead of parsing strings. */
fun expandedMcpToolName(server: String, tool: String): String {
    val name = "mcp__${sanitizeMcpNamePart(server)}__${sanitizeMcpNamePart(tool)}"
    return if (name.length <= MCP_EXPANDED_MAX_NAME_LENGTH) name else name.take(MCP_EXPANDED_MAX_NAME_LENGTH)
}

/** P0-b: `mcp__` prefix classifies expanded MCP tool calls for routing.
 *  Distinct from the `mcp_call`/`mcp_list`/… management names (single `_`). */
fun isExpandedMcpToolName(name: String): Boolean = name.startsWith(MCP_EXPANDED_PREFIX)

/**
 * P0-b: generate one flattened declaration per discovered tool. Description
 * falls back to "MCP tool {tool} on {server}"; parameters are the normalized
 * input schema; needsApproval/allowsAutoApproval/mandatoryApproval match the
 * `mcp_call` passthrough declaration exactly. Sanitized name collisions within
 * the server keep the first occurrence (never throws). Cross-server collisions
 * (e.g. `srv.1` vs `srv_1`, both sanitizing to `srv_1`) are resolved by the
 * caller: generate per server, then merge keeping the first occurrence.
 */
fun mcpExpandedToolDeclarations(
    serverName: String,
    discovered: List<McpDiscoveredToolSpec>,
): List<Tool> {
    val seenNames = mutableSetOf<String>()
    return discovered.mapNotNull { spec ->
        val name = expandedMcpToolName(serverName, spec.name)
        if (!seenNames.add(name)) return@mapNotNull null
        Tool(
            name = name,
            description = spec.description?.takeIf { it.isNotBlank() }
                ?: "MCP tool ${spec.name} on ${serverName}",
            parameters = { normalizeMcpInputSchema(spec.inputSchema) },
            execute = { emptyList() },
        )
    }
}

/**
 * P0-b: flatten an MCP JSON schema into the amber [InputSchema] shape.
 * Object-like roots (type==object, or no type but `properties` present) keep
 * their `properties` verbatim — `$ref`/`anyOf`/nested schemas pass through
 * untouched (no recursive resolution) — with `required` taken as the string
 * array when present. Non-object roots (array/string/…) are wrapped under an
 * `input` property. null → empty object.
 */
fun normalizeMcpInputSchema(schema: JsonObject?): InputSchema {
    if (schema == null) return InputSchema.Obj(properties = buildJsonObject { })
    val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
    val objectLike = type == "object" || (type == null && schema["properties"] != null)
    if (!objectLike) {
        return InputSchema.Obj(
            properties = buildJsonObject { put("input", schema) },
            required = listOf("input"),
        )
    }
    val properties = schema["properties"] as? JsonObject ?: buildJsonObject { }
    val required = (schema["required"] as? JsonArray)?.let { array ->
        val strings = array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        if (strings.size == array.size) strings else null
    }
    return InputSchema.Obj(properties = properties, required = required)
}

private const val MCP_EXPANDED_PREFIX = "mcp__"
private const val MCP_EXPANDED_MAX_NAME_LENGTH = 64

private fun sanitizeMcpNamePart(value: String): String = value.map { char ->
    if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_') {
        char
    } else {
        '_'
    }
}.joinToString("")

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
    description = """
        Prepare a read-only import preview for a skill folder or SKILL.md file under /workspace.
        The host requires one explicit user approval, then rechecks the previewed base and candidate
        hashes (CAS) before atomically applying the package. New skills are enabled; existing skills
        keep their current enabled state.
    """.trimIndent().replace("\n", " "),
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
    allowsAutoApproval = false,
    mandatoryApproval = true,
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

private fun mcpDescribeToolParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("server", buildJsonObject {
            put("type", "string")
            put("description", "the configured MCP server name")
        })
        put("tool", buildJsonObject {
            put("type", "string")
            put("description", "the discovered tool name on that server")
        })
    },
    required = listOf("server", "tool")
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
                add("read")
                add("search")
                add("query")
                add("status")
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
        put("use_attached_image", buildJsonObject {
            put("type", "boolean")
            put(
                "description",
                "Set true when the latest user-attached chat image should be used as the Codex image2 reference/pad image for style transfer, remakes, or edits. The host injects that attachment; do not paste base64 into this tool call."
            )
        })
        put("source_image_url", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Optional explicit reference image URL (amber-image-generation://, file://, https://, or data:). Prefer use_attached_image=true for the latest user attachment. Values attached/latest mean the same as use_attached_image=true."
            )
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

private fun novelRenameProjectParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("title", buildJsonObject {
            put("type", "string")
            put("description", "The new project name")
        })
        put("reason", buildJsonObject {
            put("type", "string")
            put("description", "Optional short note the user gave for the rename")
        })
    },
    required = listOf("title")
)

private fun novelSetPolishPreferenceParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("preference", buildJsonObject {
            put("type", "string")
            put("description", "The polish preference text; pass an empty string to clear it")
        })
    },
    required = listOf("preference")
)

private fun novelUpsertUpcomingArcParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("beats", buildJsonObject {
            put("type", "array")
            put("description", "Upcoming-arc beat notes; at most 8 beats, each at most 160 characters")
            put("items", buildJsonObject { put("type", "string") })
            put("maxItems", 8)
        })
    },
    required = listOf("beats")
)

private fun novelReviseMaterialParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("material_id", buildJsonObject {
            put("type", "string")
            put("description", "Optional existing material id; when present the material is updated and its kind must match")
        })
        put("kind", buildJsonObject {
            put("type", "string")
            put("description", "Material category")
            put("enum", buildJsonArray {
                add("world")
                add("character")
                add("relationship")
                add("masterOutline")
                add("writingRequirements")
                add("custom")
            })
        })
        put("title", buildJsonObject {
            put("type", "string")
            put("description", "Material title")
        })
        put("content", buildJsonObject {
            put("type", "string")
            put("description", "Material body text")
        })
        put("aliases", buildJsonObject {
            put("type", "array")
            put("description", "Optional aliases for character materials only")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("custom_name", buildJsonObject {
            put("type", "string")
            put("description", "Display name used only when creating a kind=custom material; ignored on update (the existing name is kept)")
        })
    },
    required = listOf("kind", "title", "content")
)

private fun novelProposeChapterPlanParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("outline_placement", buildJsonObject {
            put("type", "string")
            put("description", "Short placement note such as \"第 3 章 · 中段转折\"")
        })
        put("goal_and_conflict", buildJsonObject {
            put("type", "string")
            put("description", "The chapter's goal and conflict (required, non-empty)")
        })
        put("must_happen", buildJsonObject {
            put("type", "array")
            put("description", "Beat items that must happen this chapter; may be empty")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("must_not_happen", buildJsonObject {
            put("type", "array")
            put("description", "Beat items that must not happen this chapter; may be empty")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("ending_hook", buildJsonObject {
            put("type", "string")
            put("description", "The chapter's ending hook; may be an empty string")
        })
        put("visible_facts", buildJsonObject {
            put("type", "array")
            put("description", "Facts the POV is allowed to know in this chapter; may be empty")
            put("items", buildJsonObject { put("type", "string") })
        })
    },
    required = listOf(
        "outline_placement", "goal_and_conflict", "must_happen",
        "must_not_happen", "ending_hook", "visible_facts"
    )
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
