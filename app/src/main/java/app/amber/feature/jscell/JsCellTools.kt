package app.amber.feature.jscell

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P4-03 agent-facing tools (parity plan §10 P4-03): create / run / wait /
 * terminate / store / load for persistent JS cells.
 *
 * The tools are only added to the runtime catalog when the `js_cell_runtime`
 * capability flag is on (see ChatService.createRunTools) and delegate all
 * enforcement (limits, whitelist, persistence, recovery) to [JsCellRuntime].
 * [runId] is the generation round that built the tools; it becomes the cell's
 * owner run (null on non-durable paths).
 */
fun createJsCellTools(
    runtime: JsCellRuntime,
    runId: String?,
): List<Tool> = listOf(
    Tool(
        name = "js_cell_create",
        description = "Create a persistent JavaScript cell. Returns a cell_id used by js_cell_run / js_cell_wait / js_cell_store / js_cell_load / js_cell_terminate. Cells are sandboxed (QuickJS), have hard CPU/memory/output/time limits, and can only call whitelisted read-only tools via js_call_tool(name, argsJson).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {}
            )
        },
        execute = { input ->
            payload(runtime.createCell(runId))
        },
    ),
    Tool(
        name = "js_cell_run",
        description = "Execute JavaScript code in a persistent cell (ES2020, QuickJS). Short code completes and returns its result; long-running code returns status=running with the cell_id, and its output is then fetched with js_cell_wait. Console output is returned in 'output'. No DOM, Node, network or filesystem APIs; nested tool calls are limited to the read-only whitelist via js_call_tool(name, argsJson).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("cell_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Cell id from js_cell_create.")
                    })
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "The JavaScript code to execute.")
                    })
                },
                required = listOf("cell_id", "code"),
            )
        },
        execute = { input ->
            val args = input.asObject()
            val cellId = args["cell_id"]?.asString()
            val code = args["code"]?.asString()
            if (cellId == null || code == null) missingArg("cell_id", "code")
            else payload(runtime.runCell(cellId, code))
        },
    ),
    Tool(
        name = "js_cell_wait",
        description = "Wait for a running cell to produce new output or reach a terminal state. Returns new console output since 'cursor' (pass the cursor from the previous response), the final result when completed, or an error when failed/terminated. Never blocks longer than timeout_ms.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("cell_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Cell id from js_cell_create.")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max wait in milliseconds (default 10000).")
                    })
                    put("cursor", buildJsonObject {
                        put("type", "integer")
                        put("description", "Output cursor from the previous js_cell_run/js_cell_wait response.")
                    })
                },
                required = listOf("cell_id"),
            )
        },
        execute = { input ->
            val args = input.asObject()
            val cellId = args["cell_id"]?.asString()
            if (cellId == null) missingArg("cell_id")
            else {
                val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 10_000L
                val cursor = args["cursor"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                payload(runtime.waitCell(cellId, timeoutMs, cursor))
            }
        },
    ),
    Tool(
        name = "js_cell_terminate",
        description = "Stop a cell: cancels pending work, closes its sandbox and marks it terminated. Terminated cells cannot be run again; store content remains readable.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("cell_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Cell id from js_cell_create.")
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional termination reason.")
                    })
                },
                required = listOf("cell_id"),
            )
        },
        execute = { input ->
            val args = input.asObject()
            val cellId = args["cell_id"]?.asString()
            if (cellId == null) missingArg("cell_id")
            else payload(runtime.terminateCell(cellId, args["reason"]?.asString() ?: JsCellTerminationReasons.USER))
        },
    ),
    Tool(
        name = "js_cell_store",
        description = "Persist small serializable state (JSON) for a cell. The store survives process restarts (up to the size limit); it is never a JS stack. Use js_cell_load to read it back.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("cell_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Cell id from js_cell_create.")
                    })
                    put("value", buildJsonObject {
                        put("type", "string")
                        put("description", "JSON string payload to persist.")
                    })
                },
                required = listOf("cell_id", "value"),
            )
        },
        execute = { input ->
            val args = input.asObject()
            val cellId = args["cell_id"]?.asString()
            val value = args["value"]?.asString()
            if (cellId == null || value == null) missingArg("cell_id", "value")
            else payload(runtime.storeCell(cellId, value))
        },
    ),
    Tool(
        name = "js_cell_load",
        description = "Read the persisted store of a cell (JSON string). Works for any persisted cell, including terminated ones.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("cell_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Cell id from js_cell_create.")
                    })
                },
                required = listOf("cell_id"),
            )
        },
        execute = { input ->
            val args = input.asObject()
            val cellId = args["cell_id"]?.asString()
            if (cellId == null) missingArg("cell_id")
            else payload(runtime.loadCell(cellId))
        },
    ),
)

private fun JsonElement.asObject(): kotlinx.serialization.json.JsonObject =
    runCatching { jsonObject }.getOrDefault(kotlinx.serialization.json.buildJsonObject {})

private fun JsonElement.asString(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

private fun missingArg(vararg names: String): List<UIMessagePart> =
    payload(buildJsonObject {
        put("status", "failed")
        put("error", "missing_argument")
        put("arguments", names.joinToString(","))
    })

private fun payload(json: kotlinx.serialization.json.JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(json.toString()))
