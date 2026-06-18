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

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
