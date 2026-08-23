package app.amber.feature.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.modelcouncil.ExternalCliToolRegistry
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentMode
import app.amber.feature.subagent.SubAgentToolProfile
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
            val rosterText = subAgentManager.listBuiltIns().joinToString("\n\n") { agent ->
                val routing = agent.routingHint.takeIf { it.isNotBlank() }?.let { "\nrouting:\n$it" }.orEmpty()
                "@${agent.id} (${agent.name})\ndescription: ${agent.description}\ntools: ${agent.toolAllowlist.sorted().joinToString(", ")}$routing"
            }
            val payload = buildJsonObject {
                put("status", "ok")
                put("limits", subAgentManager.runtimeSummary())
                put("mode", subAgentManager.runtimeMode().name.lowercase())
                put("dynamic_subagents", dynamicSubagentHelp())
                put("tool_profiles", toolProfileHelp())
                put("roster", rosterText)
                put("built_ins", rosterText)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
        // Injects two pieces into the model's system context whenever subagent tools are mounted:
        //   1) Roster — every role's description + routing hint, so the orchestrator decides without
        //      having to call subagent_list first.
        //   2) @-mention escalation — if the latest user message contains @role-id, force a
        //      subagent_start delegation for that role.
        systemPrompt = { _, messages ->
            val builtIns = subAgentManager.listBuiltIns()
            val roster = buildString {
                appendLine("=== Available Subagents ===")
                if (subAgentManager.runtimeMode() == SubAgentMode.SMART_DYNAMIC) {
                    appendLine("Smart dynamic mode is enabled. Ordinary built-in role ids are hidden and disabled. Design a temporary custom_subagent only when the task is complex, clearly bounded, and benefits from isolated work.")
                    appendLine("To delegate: call subagent_start(custom_subagent={description, system_prompt, optional name, tool_profile, optional tool_allowlist}, task={objective, output_format, tools_and_sources, boundaries, context}). If name is omitted or too generic, smart dynamic mode assigns a stable English display name for this run.")
                    appendLine("Never write textual <tool_call>/<function>/<parameter> blocks in your reply; use native tool calls only.")
                    appendLine("display_title is only the tool chip title; do not use it as the subagent name. Put a wanted role display name in custom_subagent.name.")
                    appendLine("custom_subagent.description must say when to invoke the temporary role, e.g. \"Use when ...\" or \"何时调用：...\".")
                    appendLine("custom_subagent.system_prompt must include explicit boundaries plus report/output instructions, e.g. \"Boundaries: ... Report output as ...\".")
                    appendLine("tool_profile options: ${toolProfileHelp()}. Default is read_only. tool_allowlist can only narrow that profile; it cannot add write, terminal, send, install, delete, or subagent_* tools.")
                    appendLine("Example: custom_subagent={\"description\":\"Use when a bounded code-reading check is useful.\",\"system_prompt\":\"Boundaries: read only, do not edit files, do not ask the user. Report output as findings with evidence and risks.\",\"tool_profile\":\"workspace_read\"}.")
                } else {
                    appendLine("You can delegate bounded subtasks to specialist subagents. Each subagent runs depth-1, with its own tool allowlist (and possibly its own model). Use them when a task is complex, clearly bounded, and benefits from isolation, a different model, or parallel viewpoints. Simple linear tasks must stay in the main agent.")
                    appendLine()
                    appendLine("To delegate: call subagent_start(subagent_id, task={objective, output_format, tools_and_sources, boundaries, context}). Run multiple in parallel by issuing back-to-back subagent_start calls before any subagent_wait. The user can watch the subagent's live Markdown panel; subagent_wait/read returns a compact structured result for you to synthesize.")
                    appendLine("To dynamically create a temporary role: call subagent_start(custom_subagent={name, description, system_prompt, tool_profile}, task={...}) and omit subagent_id entirely. custom_subagent.name may be Chinese or English; if omitted, the app assigns a stable display name. Broad names are rejected outside smart dynamic mode. Do not use placeholder ids like \"custom\" or \"dynamic\". For pure creative/internal tasks, set tool_profile=\"none\".")
                    appendLine("display_title is only the tool chip title; do not use it as the subagent name. Put a wanted role display name in custom_subagent.name.")
                }
                appendLine()
                builtIns.forEach { agent ->
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
                // Model Council uses a separate tool family (model_council_*), but conceptually
                // it's a multi-model variant of @oracle. Surface it next to oracle when enabled so
                // the orchestrator considers it for high-stakes calls. Mirrors the routingHint
                // formatting of other built-in roles for tonal consistency.
                if (subAgentManager.isModelCouncilEnabled()) {
                    appendLine("@council (Model Council)")
                    appendLine("- @oracle 的多模型加强版：可用 agent_planned 自动设计 3-5 个议题相关席位，也可把外部 CLI 作为 external_cli 临时席位。")
                    appendLine("  何时调用：长期影响重大、单一 oracle 不够稳的关键决策 • 想要多视角对比 • 高风险/不可逆操作前的合议。")
                    appendLine("  何时不要：常规深思（直接 @oracle）• 时间紧 • 答案已经很清楚 • 简单任务。")
                    appendLine("  调用方式：优先 model_council_start(seat_strategy=\"agent_planned\", planned_seats=[name/role/system_prompt/model_ref? 或 runner_type=external_cli/external_tool=${ExternalCliToolRegistry.supportedToolIds.first()}]) → model_council_wait → model_council_read。")
                    appendLine()
                }
            }

            // Full id set = built-ins + user-saved custom roles. @council belongs to the
            // ModelCouncilTools prompt so it still works when subagents are disabled.
            val rosterIds = buildSet {
                addAll(builtIns.map { it.id })
            }
            val mentioned = messages
                .lastOrNull { it.role == MessageRole.USER }
                ?.toText()
                ?.let { SubAgentDefinitions.extractMentions(it, rosterIds) }
                .orEmpty()

            val mentionDirective = buildMentionOverrideDirective(mentioned)

            roster + mentionDirective
        }
    )

    private fun startTool() = Tool(
        name = "subagent_start",
        description = "Start a built-in or dynamic subagent as an isolated task. Pass exactly one of subagent_id or custom_subagent. In smart dynamic mode, use custom_subagent. Minimal dynamic roles can provide only task.objective; the app will fill safe defaults, but best results come from adding description, boundaries, and report/output instructions.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("subagent_id", stringProp("Roster subagent id. Omit this when using custom_subagent; passing both is invalid. In smart dynamic mode ordinary built-ins are disabled; use custom_subagent instead. Do not pass placeholder ids like \"custom\" or \"dynamic\". For OfficePro / terminal scenarios, call the underlying tools directly instead of dispatching a subagent."))
                    put("custom_subagent", buildJsonObject {
                        put("type", "object")
                        put("description", "Narrow dynamic subagent definition. name, description, and system_prompt are recommended, not strict: missing/generic names are replaced, and missing boundaries/report instructions are appended.")
                        put("properties", buildJsonObject {
                            put("name", stringProp("Optional subagent display name. May be Chinese or English. Missing or broad names such as general/helper/万能/通用 are replaced with a stable generated name. Do not use root display_title as the name."))
                            put("description", stringProp("Recommended. Explain when to invoke this temporary role, e.g. \"Use when ...\" or \"何时调用：...\". If omitted or too terse, the app synthesizes a bounded invocation description."))
                            put("system_prompt", stringProp("Recommended. Include explicit boundaries plus report/output instructions, e.g. \"Boundaries: read only... Report output as findings...\". If omitted or too short, the app appends safe defaults."))
                            put("tool_profile", stringProp("Optional. One of: ${toolProfileHelp()}. Default read_only. Use none for pure creative/internal tasks. If no tools are available for the profile, the subagent runs without tools."))
                            put("tool_allowlist", buildJsonObject {
                                put("type", "array")
                                put("description", "Optional tool names. This can only narrow tool_profile; it cannot add write, terminal, send, install, delete, or subagent_* tools. subagent_* tools are always invalid.")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("model_id", stringProp("Optional chat model UUID for this dynamic subagent. Omit to use the current chat model."))
                            put("temperature", numberProp("Optional sampling temperature, 0..2. Omit unless the user asks for sampling changes."))
                            put("reasoning_level", stringProp("Optional reasoning depth: off, auto, low, medium, high, xhigh, or max. Omit for the app default."))
                            put("max_turns", integerProp("Optional max turns. Capped by the SubAgent runtime setting."))
                            put("timeout_ms", integerProp("Optional timeout in milliseconds. Capped by the SubAgent runtime setting."))
                            put("output_budget_chars", integerProp("Optional output budget in characters. Capped by the SubAgent runtime setting."))
                        })
                    })
                    put("task", buildJsonObject {
                        put("type", "object")
                        put("description", "Required task spec.")
                        put("properties", buildJsonObject {
                            put("objective", stringProp("Required. The exact subtask objective."))
                            put("output_format", stringProp("Recommended. How the subagent should report back. Defaults to a concise summary with findings/evidence/risks/next steps."))
                            put("tools_and_sources", stringProp("Recommended. Which tools/sources it may use or should avoid. Defaults to granted tools only."))
                            put("boundaries", stringProp("Recommended. Scope limits, non-goals, and safety constraints. Defaults to staying within the objective, no subagents, report once and stop."))
                            put("context", stringProp("Optional. Minimal context needed for the task; do not paste the whole parent conversation, raw tool results, or large dumps."))
                            put("session_grant_id", stringProp("Optional. Only use when the user granted historical session access."))
                            put("history_query", stringProp("Optional. Query for history-read workflows."))
                            put("source_session_ids", buildJsonObject {
                                put("type", "array")
                                put("description", "Optional session ids for granted history-read workflows.")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("shard_index", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional shard index for parallel history workflows.")
                            })
                            put("shard_count", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional shard count for parallel history workflows.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("objective")
                        })
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
        description = "Read subagent run status and compact structured result by run_id.",
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

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun numberProp(description: String) = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    private fun dynamicSubagentHelp(): String =
        if (subAgentManager.runtimeMode() == SubAgentMode.SMART_DYNAMIC) {
            "smart_dynamic: create temporary custom_subagent definitions; built-in ids are hidden/disabled; English name may be auto-assigned"
        } else {
            "supported_with_validator: pass custom_subagent and omit subagent_id; name is optional, while invocation description, boundary prompt, report format, and tool profile/allowlist are validated"
        }

    private fun toolProfileHelp(): String =
        SubAgentToolProfile.entries.joinToString(", ") { it.name.lowercase() }
}

internal fun buildMentionOverrideDirective(mentioned: List<String>): String {
    if (mentioned.isEmpty()) return ""
    return buildString {
        appendLine()
        appendLine("=== USER MENTION OVERRIDE ===")
        val subAgentMentions = mentioned.filterNot { it == "council" }
        if ("council" in mentioned) {
            appendLine("The user explicitly invoked @council. You MUST call model_council_start for this turn, using the user's message as the council objective. Prefer seat_strategy=\"agent_planned\" with 3-5 planned_seats tailored to the topic; each planned seat should include name, role, system_prompt, and optional model_ref when the user named a model. If the user asks for a terminal CLI participant, set allow_external_cli=true and add a planned seat with runner_type=\"external_cli\", external_tool one of ${ExternalCliToolRegistry.supportedToolIds.joinToString()}, and optional external_runtime/external_model. Then use model_council_wait/read and synthesize the verdict.")
        }
        if (subAgentMentions.isNotEmpty()) {
            if (subAgentMentions.size == 1) {
                appendLine("The user explicitly invoked @${subAgentMentions[0]}. You MUST call subagent_start with subagent_id=\"${subAgentMentions[0]}\" for this turn, filling the task fields from the user's message. After it reports, you may package or follow up on its result.")
            } else {
                appendLine("The user explicitly invoked: ${subAgentMentions.joinToString { "@$it" }}. You MUST start each of these via subagent_start (in parallel where the subtasks are independent). After they report, synthesize their results.")
            }
        }
        // Self-loop guard: the system prompt is rebuilt on every tool-call iteration within
        // the same user turn, so without this the directive nags the model after it already
        // dispatched.
        appendLine("(If you have already started the requested subagent(s) or council run in this turn, do NOT start them again — proceed to wait/read their results.)")
    }
}
