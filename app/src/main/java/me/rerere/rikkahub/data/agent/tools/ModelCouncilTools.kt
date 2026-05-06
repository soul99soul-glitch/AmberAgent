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
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilManager
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRolePresets
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
        description = "Show Model Council experimental mode status, configured seats, limits, role presets, and active runs.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "ok")
                        put("runtime", manager.runtimeSummary())
                        put("role_presets", buildJsonArray {
                            ModelCouncilRolePresets.presets.forEach { preset ->
                                add(
                                    buildJsonObject {
                                        put("id", preset.id)
                                        put("name", preset.name)
                                        put("prompt", preset.prompt)
                                    }
                                )
                            }
                        })
                        put("notes", "Council members run as pure-text model calls with tools and memories disabled.")
                    }.toString()
                )
            )
        },
    )

    private fun startTool() = Tool(
        name = "model_council_start",
        description = "Start a Model Council compare or debate run. Members receive only the explicit task/context and cannot call tools.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject {
                        put("type", "object")
                        put("description", "Task spec: mode(compare|debate), objective, context, output_format, evaluation_criteria, rounds, and optional seats.")
                    })
                    put("mode", enumProp("Optional shorthand mode when task is omitted.", listOf("compare", "debate")))
                    put("objective", stringProp("Question or decision to review. Required when task is omitted."))
                    put("context", stringProp("Bounded context snapshot supplied by the supervisor."))
                    put("output_format", stringProp("Desired output shape."))
                    put("evaluation_criteria", stringProp("Criteria for comparing answers."))
                    put("rounds", integerProp("Debate rounds. Compare always uses one round."))
                    put("seats", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional temporary seats. Each seat needs seat_id/name/role/model_id and may include system_prompt/output_budget_chars.")
                        put("items", buildJsonObject { put("type", "object") })
                    })
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
        description = "Wait for a Model Council run to finish, up to wait_timeout_ms.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", stringProp("Model Council run id."))
                    put("wait_timeout_ms", integerProp("Maximum wait time. Default 10000, capped at 60000."))
                },
                required = listOf("run_id"),
            )
        },
        execute = { input ->
            val waitMs = input.int("wait_timeout_ms")?.toLong() ?: 10_000L
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
