package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.data.agent.task.AgentTaskScheduler
import me.rerere.rikkahub.data.agent.task.AgentTaskStatus

class AgentTaskTools(
    private val taskScheduler: AgentTaskScheduler,
) {
    fun tools(): List<Tool> = listOf(
        listTool,
        readTool,
        cancelTool,
        retryTool,
        cleanupTool,
        runtimeStatusTool,
    )

    private val listTool = Tool(
        name = "agent_task_list",
        description = "List AmberAgent background tasks in this Android app, including terminal jobs, subagents, model council runs, cron tasks, and report jobs.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", stringProp("Optional task type filter, such as terminal, subagent, model_council, cron, officepro."))
                    put("status", stringProp("Optional status filter: queued, running, completed, failed, cancelled, timed_out, interrupted."))
                }
            )
        },
        execute = { input ->
            val status = input.string("status")?.let { raw ->
                AgentTaskStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
            val type = input.string("type")?.takeIf { it.isNotBlank() }
            textJson {
                put("status", "ok")
                put("tasks", buildJsonArray {
                    taskScheduler.list(type = type, status = status).forEach { add(it.toJson()) }
                })
            }
        }
    )

    private val readTool = Tool(
        name = "agent_task_read",
        description = "Read one AmberAgent background task snapshot by task_id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", stringProp("Agent task id."))
                },
                required = listOf("task_id")
            )
        },
        execute = { input ->
            val taskId = input.requiredString("task_id")
            val task = taskScheduler.read(taskId)
            textJson {
                put("status", if (task == null) "not_found" else "ok")
                put("task_id", taskId)
                task?.let { put("task", it.toJson()) }
            }
        }
    )

    private val cancelTool = Tool(
        name = "agent_task_cancel",
        description = "Cancel a running AmberAgent background task when it exposes a cancel capability.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", stringProp("Agent task id."))
                },
                required = listOf("task_id")
            )
        },
        execute = { input ->
            val task = taskScheduler.cancel(input.requiredString("task_id"))
            textJson {
                put("status", task.status.name.lowercase())
                put("task", task.toJson())
            }
        }
    )

    private val retryTool = Tool(
        name = "agent_task_retry",
        description = "Retry an interrupted or failed AmberAgent background task only when its snapshot marks it retryable. Mutating or sensitive retries still require approval.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", stringProp("Agent task id."))
                },
                required = listOf("task_id")
            )
        },
        execute = { input ->
            val task = taskScheduler.retry(input.requiredString("task_id"))
            textJson {
                put("status", task.status.name.lowercase())
                put("task", task.toJson())
            }
        }
    )

    private val cleanupTool = Tool(
        name = "agent_task_cleanup",
        description = "Remove a completed, failed, interrupted, or cancelled AmberAgent task snapshot. It only deletes app-private output when delete_private_output=true.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", stringProp("Agent task id."))
                    put("delete_private_output", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Also delete app-private logs/transcripts referenced by this task. Workspace files are never deleted by this flag.")
                    })
                },
                required = listOf("task_id")
            )
        },
        execute = { input ->
            val taskId = input.requiredString("task_id")
            val deletePrivateOutput = input.jsonObject["delete_private_output"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val removed = taskScheduler.cleanup(taskId, deletePrivateOutput = deletePrivateOutput)
            textJson {
                put("status", if (removed) "ok" else "not_found")
                put("task_id", taskId)
                put("delete_private_output", deletePrivateOutput)
            }
        }
    )

    private val runtimeStatusTool = Tool(
        name = "agent_runtime_status",
        description = "Read a concise AmberAgent harness runtime status summary, including task counts by status and type.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val status = taskScheduler.status()
            textJson {
                put("status", "ok")
                put("total_tasks", status.total)
                put("queued", status.queued)
                put("running", status.running)
                put("completed", status.completed)
                put("failed", status.failed)
                put("cancelled", status.cancelled)
                put("timed_out", status.timedOut)
                put("interrupted", status.interrupted)
                put("by_type", buildJsonObject {
                    status.byType.forEach { (type, count) -> put(type, count) }
                })
            }
        }
    )

    private fun AgentTaskSnapshot.toJson() = buildJsonObject {
        put("task_id", taskId)
        put("type", type)
        put("title", title)
        put("queue_state", queueState.name.lowercase())
        put("recovery_state", recoveryState.name.lowercase())
        put("retryable", retryPolicy.retryable)
        put("retry_requires_approval", retryPolicy.requiresApproval)
        put("retry_count", retryPolicy.retryCount)
        put("retry_max", retryPolicy.maxRetries)
        retryPolicy.reason?.let { put("retry_reason", it) }
        put("output_exists", outputRef?.exists ?: outputPath?.let { java.io.File(it).exists() } ?: false)
        outputRef?.let { ref ->
            put("output_ref", buildJsonObject {
                put("type", ref.type)
                put("path", ref.path)
                put("tail_offset", ref.tailOffset)
                put("exists", ref.exists)
            })
        }
        lastHeartbeatMs?.let { put("last_heartbeat_ms", it) }
        spec?.let { put("spec", it) }
        runtime?.let { put("runtime", it) }
        sourceToolName?.let { put("source_tool_name", it) }
        sourceConversationId?.let { put("source_conversation_id", it) }
        put("status", status.name.lowercase())
        outputPath?.let { put("output_path", it) }
        put("output_offset", outputOffset)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
        put("notified", notified)
        put("cancel_capability", cancelCapability)
        permissionTraceId?.let { put("permission_trace_id", it) }
        summary?.let { put("summary", it.take(4_000)) }
        lastErrorCode?.let { put("last_error_code", it) }
        error?.let { put("error", it.take(1_000)) }
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }
}
