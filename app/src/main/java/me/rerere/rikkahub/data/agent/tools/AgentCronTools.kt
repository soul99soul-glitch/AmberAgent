package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.cron.AgentCronManager
import me.rerere.rikkahub.data.agent.cron.AgentCronTask

class AgentCronTools(
    private val manager: AgentCronManager,
) {
    fun getTools(): List<Tool> = listOf(
        listTool,
        createTool,
        updateTool,
        deleteTool,
    )

    private val listTool = Tool(
        name = "cron_task_list",
        description = "List AmberAgent mobile cron tasks scheduled on this Android device.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            textJson {
                put("status", "ok")
                put("tasks", buildJsonArray {
                    manager.listTasksSnapshot().forEach { task -> add(task.toJson()) }
                })
            }
        }
    )

    private val createTool = Tool(
        name = "cron_task_create",
        description = "Create a mobile-side cron task. The task will trigger AmberAgent on this Android device and send the prompt to its own conversation when due. Supports 5-field cron expressions like '0 9 * * *', '*/30 * * * *', and '30 9 * * 1-5'.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", stringProp("Short task name shown in Settings."))
                    put("prompt", stringProp("User instruction AmberAgent should run when the cron fires."))
                    put("cron_expression", stringProp("Five-field cron expression: minute hour day-of-month month day-of-week."))
                    put("timezone", stringProp("IANA timezone id. Defaults to this device timezone."))
                    put("enabled", booleanProp("Whether to schedule immediately. Defaults to true."))
                },
                required = listOf("prompt", "cron_expression")
            )
        },
        execute = { input ->
            val task = manager.createTask(
                title = input.string("title").orEmpty(),
                prompt = input.requiredString("prompt"),
                cronExpression = input.requiredString("cron_expression"),
                timezoneId = input.string("timezone"),
                enabled = input.boolean("enabled") ?: true,
            )
            textJson {
                put("status", "created")
                put("task", task.toJson())
            }
        }
    )

    private val updateTool = Tool(
        name = "cron_task_update",
        description = "Update an existing mobile cron task. Omitted fields are preserved.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", stringProp("Cron task id."))
                    put("title", stringProp("New task title."))
                    put("prompt", stringProp("New prompt to send when the task runs."))
                    put("cron_expression", stringProp("New five-field cron expression."))
                    put("timezone", stringProp("New IANA timezone id."))
                    put("enabled", booleanProp("Enable or pause the task."))
                },
                required = listOf("task_id")
            )
        },
        execute = { input ->
            val task = manager.updateTask(
                id = input.requiredString("task_id"),
                title = input.string("title"),
                prompt = input.string("prompt"),
                cronExpression = input.string("cron_expression"),
                timezoneId = input.string("timezone"),
                enabled = input.boolean("enabled"),
            )
            textJson {
                put("status", "updated")
                put("task", task.toJson())
            }
        }
    )

    private val deleteTool = Tool(
        name = "cron_task_delete",
        description = "Delete a mobile cron task and cancel its scheduled work.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", stringProp("Cron task id."))
                },
                required = listOf("task_id")
            )
        },
        execute = { input ->
            val removed = manager.deleteTask(input.requiredString("task_id"))
            textJson {
                put("status", if (removed) "deleted" else "not_found")
                put("task_id", input.requiredString("task_id"))
            }
        }
    )

    private fun AgentCronTask.toJson() = buildJsonObject {
        put("id", id)
        put("title", title)
        put("prompt", prompt.take(2_000))
        put("cron_expression", cronExpression)
        put("timezone", timezoneId)
        put("conversation_id", conversationId)
        put("enabled", enabled)
        nextRunAtMs?.let { put("next_run_at_ms", it) }
        lastRunAtMs?.let { put("last_run_at_ms", it) }
        put("last_status", lastStatus.name.lowercase())
        lastError?.let { put("last_error", it) }
        put("run_count", runCount)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }
}
