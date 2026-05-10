package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinitions
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
        description = "List built-in and user-defined subagents with routing rules. The roster is also injected into your system context — you only need to call this for the most up-to-date snapshot of runtime limits and any user-saved custom roles.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val payload = buildJsonObject {
                put("status", "ok")
                put("limits", subAgentManager.runtimeSummary())
                put("dynamic_subagents", "supported_with_validator: narrow name, invocation description, boundary prompt, report format, tool allowlist, and budget caps are required")
                put("built_ins", subAgentManager.listBuiltIns().joinToString("\n\n") { agent ->
                    val routing = agent.routingHint.takeIf { it.isNotBlank() }?.let { "\nrouting:\n$it" }.orEmpty()
                    "@${agent.id} (${agent.name})\ndescription: ${agent.description}\ntools: ${agent.toolAllowlist.sorted().joinToString(", ")}$routing"
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
        // Injects two pieces into the model's system context whenever subagent tools are mounted:
        //   1) Roster — every role's description + routing hint, so the orchestrator decides without
        //      having to call subagent_list first.
        //   2) @-mention escalation — if the latest user message contains @role-id, force a
        //      subagent_start delegation for that role.
        systemPrompt = { _, messages ->
            val roster = buildString {
                appendLine("=== Available Subagents ===")
                appendLine("You can delegate bounded subtasks to specialist subagents. Each subagent runs depth-1, with its own tool allowlist (and possibly its own model). Use them when a task is complex, clearly bounded, and benefits from isolation, a different model, or parallel viewpoints. Simple linear tasks must stay in the main agent.")
                appendLine()
                appendLine("To delegate: call subagent_start(subagent_id, task={objective, output_format, tools_and_sources, boundaries, context}). Run multiple in parallel by issuing back-to-back subagent_start calls before any subagent_wait.")
                appendLine()
                subAgentManager.listBuiltIns().forEach { agent ->
                    appendLine("@${agent.id} (${agent.name})")
                    appendLine("- ${agent.description}")
                    if (agent.routingHint.isNotBlank()) {
                        agent.routingHint.lineSequence().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) appendLine("  $trimmed")
                        }
                    }
                    appendLine()
                }
            }

            // Full id set = built-ins + user-saved custom roles. Otherwise users get their custom
            // roles advertised in the roster but @-mentions for them silently no-op.
            val rosterIds = subAgentManager.listBuiltIns().map { it.id }.toSet()
            val mentioned = messages
                .lastOrNull { it.role == MessageRole.USER }
                ?.toText()
                ?.let { SubAgentDefinitions.extractMentions(it, rosterIds) }
                .orEmpty()

            val mentionDirective = if (mentioned.isEmpty()) "" else buildString {
                appendLine()
                appendLine("=== USER MENTION OVERRIDE ===")
                if (mentioned.size == 1) {
                    appendLine("The user explicitly invoked @${mentioned[0]}. You MUST call subagent_start with subagent_id=\"${mentioned[0]}\" for this turn, filling the task fields from the user's message. After it reports, you may package or follow up on its result.")
                } else {
                    appendLine("The user explicitly invoked: ${mentioned.joinToString { "@$it" }}. You MUST start each of these via subagent_start (in parallel where the subtasks are independent). After they report, synthesize their results.")
                }
                // Self-loop guard: the system prompt is rebuilt on every tool-call iteration within
                // the same user turn, so without this the directive nags the model after it already
                // dispatched.
                appendLine("(If you have already started the requested subagent(s) in this turn, do NOT start them again — proceed to wait/read their results.)")
            }

            roster + mentionDirective
        }
    )

    private fun startTool() = Tool(
        name = "subagent_start",
        description = "Start a built-in or dynamic subagent as an isolated task. Requires task.objective, output_format, tools_and_sources, and boundaries.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("subagent_id", stringProp("Built-in subagent id. Available: explorer, historian, oracle, designer, writer, fixer. The roster (with routing rules) is also injected into your system context — pick from there. For OfficePro / terminal scenarios, call the underlying tools directly instead of dispatching a subagent."))
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
        description = "Wait for a subagent run to complete, up to wait_timeout_ms. Use the maximum timeout (60000) on the FIRST call; if it returns running, immediately call wait again with the same arguments — do NOT spend any reasoning between waits, and do NOT narrate \"still running, let me wait again\". The wait blocks efficiently in the background; thinking between waits just burns tokens and clutters the timeline.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Subagent run id"))
                    put("wait_timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum wait time in ms. Default 10000; CAPPED at 60000. Pass 60000 for the longest single wait. If the subagent is still running after the wait, call this tool again with the same run_id — do not reason between calls.")
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
