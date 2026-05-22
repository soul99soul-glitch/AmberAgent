package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
import me.rerere.rikkahub.data.agent.modelcouncil.DEFAULT_MODEL_COUNCIL_WAIT_TIMEOUT_MS
import me.rerere.rikkahub.data.agent.modelcouncil.ExternalCliToolRegistry
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilManager
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRolePresets
import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinitions
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ModelCouncilTools(
    private val manager: ModelCouncilManager,
    private val workspaceManager: WorkspaceManager,
) {
    fun tools(): List<Tool> = listOf(
        statusTool(),
        startTool(),
        readTool(),
        waitTool(),
        cancelTool(),
        makeReportTool(),
    )

    private fun statusTool() = Tool(
        name = "model_council_status",
        description = "Show Model Council experimental mode status, configured seats, limits, role presets (split into core and lens layers), and active runs.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "ok")
                        put("runtime", manager.runtimeSummary())
                        put("core_seats", buildJsonArray {
                            ModelCouncilRolePresets.coreSeats.forEach { preset ->
                                add(
                                    buildJsonObject {
                                        put("id", preset.id)
                                        put("name", preset.name)
                                        put("prompt", preset.prompt)
                                    }
                                )
                            }
                        })
                        put("lens_presets", buildJsonArray {
                            ModelCouncilRolePresets.lensPresets.forEach { preset ->
                                add(
                                    buildJsonObject {
                                        put("id", preset.id)
                                        put("name", preset.name)
                                        put("prompt", preset.prompt)
                                    }
                                )
                            }
                        })
                        put("notes", "Default mode auto-injects core seats (supporter/opponent/judge) unless you pass explicit `seats`. For flexible topic-specific councils, use seat_strategy=agent_planned and planned_seats with name/role/system_prompt plus optional model_ref. Only when the user explicitly asks for a terminal CLI participant, set allow_external_cli=true and add runner_type=external_cli plus one of the supported external_tool ids: ${ExternalCliToolRegistry.supportedToolIds.joinToString()}. External CLI starts a local terminal process and requires human approval. Members run as pure-text participants with no AmberAgent tools or memories.")
                    }.toString()
                )
            )
        },
        systemPrompt = { _, messages ->
            val latestUserText = messages.lastOrNull { it.role == MessageRole.USER }?.toText()
            buildString {
                appendLine("=== Model Council ===")
                appendLine("@council is available when the user wants a multi-model council discussion. Prefer seat_strategy=agent_planned when the task benefits from topic-specific perspectives; provide 3-5 planned_seats with name, role, system_prompt, and optional model_ref. Use external_cli only when the user explicitly asks for a terminal CLI participant: set allow_external_cli=true, then add a planned seat with runner_type=external_cli, external_tool one of ${ExternalCliToolRegistry.supportedToolIds.joinToString()}, and optional external_runtime/external_model. Then call model_council_start, model_council_wait/read, and synthesize the verdict.")
                appendLine("Use mode=\"debate\" whenever the user asks for 2-5 rounds, iterative deliberation, or seats responding to each other. mode=\"compare\" is a one-shot independent comparison and always runs exactly one round.")
                append(buildModelCouncilMentionOverrideDirective(latestUserText))
            }
        },
    )

    private fun startTool() = Tool(
        name = "model_council_start",
        description = "Start a Model Council run. Use mode=compare for one-shot independent answers. Use mode=debate for 2-5 rounds or when seats should read/respond to previous rounds. Default mode uses core seats plus optional extra_lens. For flexible councils, set seat_strategy=agent_planned and pass planned_seats; each planned seat has name/role/system_prompt and may include model_ref. External CLI seats require allow_external_cli=true plus runner_type=external_cli and a supported external_tool, and will require human approval because they start a local terminal process. Members run as pure-text participants with no AmberAgent tools.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject {
                        put("type", "object")
                        put("description", "Task spec. Prefer this object form for council calls.")
                        put("properties", buildJsonObject {
                            put("mode", enumProp("Required for multi-round behavior. compare = one-shot independent answers and always one round. debate = seats see previous rounds and may run 2-5 rounds.", listOf("compare", "debate")))
                            put("objective", stringProp("Required. Question or decision to review."))
                            put("context", stringProp("Bounded context snapshot supplied by the supervisor."))
                            put("output_format", stringProp("Desired final verdict shape."))
                            put("evaluation_criteria", stringProp("Criteria for comparing answers."))
                            put("rounds", integerProp("Debate rounds, up to the Model Council max_rounds setting. Only mode=debate honors rounds > 1; compare always uses one round."))
                            put("allow_external_cli", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Must be true to use any external_cli seat. Set it only when the user explicitly asked to include a local terminal CLI participant.")
                            })
                            put("seat_strategy", enumProp("Optional. Use agent_planned when you want the supervisor to design topic-specific seats instead of fixed supporter/opponent/judge.", listOf("default", "agent_planned")))
                            put("planned_seats", plannedSeatsSchema())
                            put("extra_lens", extraLensSchema())
                            put("seats", explicitSeatsSchema())
                        })
                        put("required", buildJsonArray { add("objective") })
                    })
                    put("mode", enumProp("Optional shorthand mode when task is omitted. Use debate for requested rounds > 1 or cross-seat discussion; compare always uses one round.", listOf("compare", "debate")))
                    put("objective", stringProp("Question or decision to review. Required when task is omitted."))
                    put("context", stringProp("Bounded context snapshot supplied by the supervisor."))
                    put("output_format", stringProp("Desired output shape."))
                    put("evaluation_criteria", stringProp("Criteria for comparing answers."))
                    put("rounds", integerProp("Debate rounds, up to the Model Council max_rounds setting. Only mode=debate honors rounds > 1; compare always uses one round."))
                    put("allow_external_cli", buildJsonObject {
                        put("type", "boolean")
                                put("description", "Must be true to use any external_cli seat. Set it only when the user explicitly asked to include a local terminal CLI participant.")
                    })
                    put("seat_strategy", enumProp("Optional. Use agent_planned when you want the supervisor to design topic-specific seats instead of fixed supporter/opponent/judge.", listOf("default", "agent_planned")))
                    put("planned_seats", plannedSeatsSchema())
                    put("extra_lens", extraLensSchema())
                    put("seats", explicitSeatsSchema())
                }
            )
        },
        execute = { input ->
            listOf(UIMessagePart.Text(manager.start(input.jsonObject).toString()))
        },
    )

    private fun readTool() = Tool(
        name = "model_council_read",
        description = "Read a Model Council run status, turns, result, and transcript path by run_id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Model Council run id."))
                },
                required = listOf("run_id"),
            )
        },
        execute = { input ->
            listOf(UIMessagePart.Text(manager.read(input.requiredString("run_id")).toString()))
        },
    )

    private fun waitTool() = Tool(
        name = "model_council_wait",
        description = "Wait for a Model Council run to finish. If it returns status=running and wait_status=still_running, the run is still healthy; call model_council_read or wait again instead of treating it as a failure.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Model Council run id."))
                    put("wait_timeout_ms", integerProp("Maximum wait time. Default 180000; capped by the Model Council total timeout."))
                },
                required = listOf("run_id"),
            )
        },
        execute = { input ->
            val waitMs = input.int("wait_timeout_ms")?.toLong() ?: DEFAULT_MODEL_COUNCIL_WAIT_TIMEOUT_MS
            listOf(UIMessagePart.Text(manager.wait(input.requiredString("run_id"), waitMs).toString()))
        },
    )

    private fun cancelTool() = Tool(
        name = "model_council_cancel",
        description = "Cancel a running Model Council run by run_id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Model Council run id."))
                },
                required = listOf("run_id"),
            )
        },
        execute = { input ->
            listOf(UIMessagePart.Text(manager.cancel(input.requiredString("run_id")).toString()))
        },
    )

    private fun makeReportTool() = Tool(
        name = "model_council_make_report",
        description = "Write a Model Council result as Markdown under /workspace/model-council. Requires approval because it writes a file.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Completed Model Council run id."))
                    put("title", stringProp("Optional report title."))
                    put("output_path", stringProp("Optional workspace-relative output path. Defaults to model-council/model-council-<timestamp>.md."))
                },
                required = listOf("run_id"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val title = input.string("title") ?: "Model Council Report"
            val outputPath = input.string("output_path") ?: defaultReportPath()
            val markdown = manager.reportMarkdown(input.requiredString("run_id"), title)
            val entry = workspaceManager.writeText(outputPath, markdown)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "ok")
                        put("path", entry.path)
                        put("size_bytes", entry.sizeBytes ?: markdown.length.toLong())
                        put("title", title)
                    }.toString()
                )
            )
        },
    )

    private fun defaultReportPath(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "model-council/model-council-$stamp.md"
    }

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

    private fun plannedSeatsSchema() = buildJsonObject {
        put("type", "array")
        put("description", "Topic-specific seats for seat_strategy=agent_planned. Use 3-5 seats for most councils. Each item should include name, role, system_prompt, optional runner_type/model_ref/external_tool/external_runtime/external_model, optional seat_id/output_budget_chars/reasoning_level/temperature. Omit model_ref to auto-rotate through the configured council model pool. Omit reasoning_level and temperature unless the user explicitly asks for a specific depth or sampling behavior.")
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("seat_id", stringProp("Optional stable id."))
                put("name", stringProp("Human-readable seat name, e.g. 营养策略 / 工程实现 / 反方审查."))
                put("role", stringProp("Short role key, e.g. nutrition, engineering, critic."))
                put("system_prompt", stringProp("How this seat should reason. Keep it focused and role-specific."))
                put("runner_type", enumProp("Optional. provider_model uses a configured chat model. external_cli runs a whitelisted local CLI participant.", listOf("provider_model", "external_cli")))
                put("model_ref", stringProp("Optional model reference. Match by display name, provider name, model id fragment, or UUID. If missing/unmatched, AmberAgent auto-assigns from the default council model pool."))
                put("external_tool", enumProp("For runner_type=external_cli.", ExternalCliToolRegistry.supportedToolIds))
                put("external_runtime", enumProp("Optional terminal runtime for external_cli. Defaults to the Agent terminal runtime setting.", listOf("builtin_alpine", "android_shell", "termux_external")))
                put("external_model", stringProp("Optional CLI model argument, passed as --model when the selected CLI supports it. Example: gemini-2.5-pro."))
                put("output_budget_chars", integerProp("Optional per-seat output budget."))
                put("reasoning_level", enumProp("Optional provider-model reasoning depth. Omit for AmberAgent's safe default. Use off/auto/low/medium/high/xhigh/max only when the user asks for depth/cost tuning or a model needs a provider default.", listOf("off", "auto", "low", "medium", "high", "xhigh", "max")))
                put("temperature", numberProp("Optional provider-model sampling temperature, 0..2. Omit unless the user explicitly requests more diversity or a specific temperature. If a provider rejects it, AmberAgent retries that seat once without temperature."))
            })
            put("required", buildJsonArray {
                add("name")
                add("role")
                add("system_prompt")
            })
        })
    }

    private fun extraLensSchema() = buildJsonObject {
        put("type", "array")
        put("description", "Default-mode domain lenses to include alongside the core seats. Prefer planned_seats for custom role design.")
        put("items", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                add("product")
                add("marketing")
                add("pr")
                add("engineering")
                add("ux")
                add("risk")
            })
        })
    }

    private fun explicitSeatsSchema() = buildJsonObject {
        put("type", "array")
        put("description", "(Advanced) Fully explicit seat list. When provided, the 3 core seats are NOT auto-injected — you take full responsibility for the lineup. Provider-model seats need seat_id/name/role/model_id and may include system_prompt/output_budget_chars/reasoning_level/temperature; external CLI seats use runner_type=external_cli and a supported external_tool. Omit reasoning_level and temperature unless explicitly requested.")
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("seat_id", stringProp("Optional stable id."))
                put("name", stringProp("Human-readable seat name."))
                put("role", stringProp("Role id or short role key."))
                put("model_id", stringProp("Required for provider_model explicit seats. UUID of a configured CHAT model."))
                put("runner_type", enumProp("Optional. provider_model uses a configured chat model. external_cli runs a whitelisted local CLI participant.", listOf("provider_model", "external_cli")))
                put("system_prompt", stringProp("Optional role prompt."))
                put("external_tool", enumProp("For runner_type=external_cli.", ExternalCliToolRegistry.supportedToolIds))
                put("external_runtime", enumProp("Optional terminal runtime for external_cli.", listOf("builtin_alpine", "android_shell", "termux_external")))
                put("external_model", stringProp("Optional CLI model argument, passed as --model when the selected CLI supports it. Example: gemini-2.5-pro."))
                put("output_budget_chars", integerProp("Optional per-seat output budget."))
                put("reasoning_level", enumProp("Optional provider-model reasoning depth: off/auto/low/medium/high/xhigh/max.", listOf("off", "auto", "low", "medium", "high", "xhigh", "max")))
                put("temperature", numberProp("Optional provider-model sampling temperature, 0..2. Omit unless explicitly requested."))
            })
            put("required", buildJsonArray {
                add("role")
            })
        })
    }

    private fun enumProp(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }

    private fun JsonElement.requiredString(name: String): String =
        string(name).also { require(!it.isNullOrBlank()) { "$name is required" } }!!

    private fun JsonElement.string(name: String): String? =
        jsonObject[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonElement.int(name: String): Int? =
        jsonObject[name]?.jsonPrimitive?.intOrNull
}

internal fun buildModelCouncilMentionOverrideDirective(latestUserText: String?): String {
    val mentioned = latestUserText
        ?.let { SubAgentDefinitions.extractMentions(it, setOf("council")) }
        .orEmpty()
    return buildMentionOverrideDirective(mentioned)
}
