package app.amber.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

fun createWebMountStationsToolDeclaration(): Tool = webMountTool(
    name = "wm_stations",
    description = "List configured iOS WebMount stations, enabled state, auth kind, and redacted cookie summary.",
    parameters = webMountStationsParameters()
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
    parameters = emptyObjectParameters()
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

fun createWebMountBackToolDeclaration(): Tool = webMountTool(
    name = "wm_back",
    description = "Navigate the current iOS WebMount WKWebView session backward.",
    parameters = emptyObjectParameters()
)

fun createWebMountForwardToolDeclaration(): Tool = webMountTool(
    name = "wm_forward",
    description = "Navigate the current iOS WebMount WKWebView session forward.",
    parameters = emptyObjectParameters()
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
 * ChatViewModel dispatches to CouncilRunner.run(objective:) (which drives
 * IosCouncilFactory.startInput + ModelCouncilManager), then resumes the stream
 * with the council's synthesized result as the tool output.
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
            put("description", "optional cap on the number of council seats")
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

private fun emptyObjectParameters(): InputSchema = InputSchema.Obj(properties = buildJsonObject { })

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

private fun webMountOpenParameters(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
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

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
