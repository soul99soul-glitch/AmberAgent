package app.amber.core.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart

/**
 * Factory for the `run_plan_update` agent tool — lets a long-running task
 * push its current "step N of M" summary into the live status surface.
 *
 * Extracted from `LocalTools.runPlanUpdateTool` in M1.4 demo. No state, no
 * deps; tool layer accepts the payload back as JSON for UI live rendering
 * (stage1 — uses the normal tool timeline).
 */
fun createRunPlanUpdateTool(): Tool = Tool(
    name = "run_plan_update",
    description = "Update the current long-task step summary for Agent UI, preview surfaces, and live status. Use concise user-visible step names.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("steps", buildJsonObject {
                    put("type", "array")
                    put("description", "Ordered task steps")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("current_step_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "0-based current step index")
                })
                put("status", buildJsonObject {
                    put("type", "string")
                    put("description", "planning, running, waiting, completed, failed, or cancelled")
                })
            },
            required = listOf("steps", "status")
        )
    },
    execute = { input ->
        val payload = buildJsonObject {
            put("status", input.jsonObject["status"] ?: JsonPrimitive("running"))
            put("current_step_index", input.jsonObject["current_step_index"] ?: JsonPrimitive(0))
            put("steps", input.jsonObject["steps"] ?: buildJsonArray {})
            put("note", "Plan state was accepted by the tool layer. UI live rendering is stage1 and uses the normal tool timeline.")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
