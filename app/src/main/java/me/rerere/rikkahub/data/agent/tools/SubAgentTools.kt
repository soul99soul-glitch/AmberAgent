package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.subagent.SubAgentManager
import kotlin.uuid.Uuid

class SubAgentTools(
    private val subAgentManager: SubAgentManager,
    private val parentConversationId: Uuid,
    private val parentToolsProvider: () -> List<Tool>,
) {
    fun tools(): List<Tool> = listOf(
        listTool(),
        startTool(),
        readTool(),
        waitTool(),
        cancelTool(),
    )

    private fun listTool() = Tool(
        name = "subagent_list",
        description = "List built-in subagents and dynamic subagent rules available to this AmberAgent run.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val payload = buildJsonObject {
                put("status", "ok")
                put("limits", subAgentManager.runtimeSummary())
                put("dynamic_subagents", "supported_with_validator: narrow name, invocation description, boundary prompt, report format, tool allowlist, and budget caps are required")
                put("built_ins", subAgentManager.listBuiltIns().joinToString("\n") { agent ->
                    "${agent.id}: ${agent.description}; tools=${agent.toolAllowlist.sorted().joinToString(",")}"
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun startTool() = Tool(
        name = "subagent_start",
        description = "Start a built-in or dynamic subagent as an isolated task. Requires task.objective, output_format, tools_and_sources, and boundaries.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("subagent_id", stringProp("Built-in subagent id, such as researcher, reviewer, officepro-analyst, terminal-operator, or risk-checker."))
                    put("custom_subagent", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional narrow dynamic subagent definition with name, description, system_prompt, tool_allowlist, max_turns, timeout_ms, output_budget_chars.")
                    })
                    put("task", buildJsonObject {
                        put("type", "object")
                        put("description", "Required task spec containing objective, output_format, tools_and_sources, boundaries, and optional context.")
                    })
                },
                required = listOf("task")
            )
        },
        execute = { input ->
            val payload = subAgentManager.start(
                parentConversationId = parentConversationId,
                input = input.jsonObject,
                parentTools = parentToolsProvider(),
            )
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun readTool() = Tool(
        name = "subagent_read",
        description = "Read subagent run status and result summary by run_id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Subagent run id"))
                },
                required = listOf("run_id")
            )
        },
        execute = { input ->
            val payload = subAgentManager.read(input.runId())
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun waitTool() = Tool(
        name = "subagent_wait",
        description = "Wait for a subagent run to complete, up to wait_timeout_ms.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Subagent run id"))
                    put("wait_timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum wait time, default 10000, capped at 60000")
                    })
                },
                required = listOf("run_id")
            )
        },
        execute = { input ->
            val waitMs = input.jsonObject["wait_timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 10_000L
            val payload = subAgentManager.wait(input.runId(), waitMs)
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun cancelTool() = Tool(
        name = "subagent_cancel",
        description = "Cancel a running subagent task by run_id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Subagent run id"))
                },
                required = listOf("run_id")
            )
        },
        execute = { input ->
            val payload = subAgentManager.cancel(input.runId())
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun kotlinx.serialization.json.JsonElement.runId(): String =
        jsonObject["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }
}
