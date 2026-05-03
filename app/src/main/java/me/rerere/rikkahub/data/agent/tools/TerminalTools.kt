package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntime

class TerminalTools(
    private val terminalRuntime: TerminalRuntime,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        terminalExecuteTool,
        terminalSessionStartTool,
        terminalSessionExecTool,
        terminalSessionReadTool,
        terminalSessionStopTool,
    )

    private val terminalExecuteTool = Tool(
        name = "terminal_execute",
        description = "Run a one-shot command in the AmberAgent Alpine/proot runtime workspace.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", stringProp("Command to run."))
                    put("timeout_ms", integerProp("Timeout in milliseconds. Defaults to 60000."))
                },
                required = listOf("command")
            )
        },
        needsApproval = true,
        execute = { input ->
            val result = terminalRuntime.execute(
                command = input.requiredString("command"),
                timeoutMillis = input.long("timeout_ms") ?: 60_000L
            )
            textJson {
                put("runtime", result.runtime)
                put("workspace", result.workspace)
                put("exit_code", result.exitCode)
                put("output", result.output)
                put("sync_note", result.syncNote)
            }
        }
    )

    private val terminalSessionStartTool = Tool(
        name = "terminal_session_start",
        description = "Start a persistent terminal session in the AmberAgent runtime workspace. Stage 0 sessions still use Android shell until Alpine PTY support is wired.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            trackSessionTool("terminal_session_start", "启动终端会话", input) {
                val session = terminalRuntime.startSession()
                textJson {
                    put("session_id", session.id)
                    put("runtime", session.runtime)
                    put("workspace", session.workspace)
                }
            }
        }
    )

    private val terminalSessionExecTool = Tool(
        name = "terminal_session_exec",
        description = "Send a command to a persistent terminal session.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session ID from terminal_session_start."))
                    put("command", stringProp("Command to send to the session."))
                },
                required = listOf("session_id", "command")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackSessionTool("terminal_session_exec", "执行会话命令", input) {
                val result = terminalRuntime.execSession(
                    id = input.requiredString("session_id"),
                    command = input.requiredString("command")
                )
                textJson {
                    put("session_id", result.id)
                    put("output", result.output)
                    put("running", result.running)
                }
            }
        }
    )

    private val terminalSessionReadTool = Tool(
        name = "terminal_session_read",
        description = "Read currently buffered output from a persistent terminal session.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session ID from terminal_session_start."))
                },
                required = listOf("session_id")
            )
        },
        execute = { input ->
            trackSessionTool("terminal_session_read", "读取会话输出", input) {
                val result = terminalRuntime.readSession(input.requiredString("session_id"))
                textJson {
                    put("session_id", result.id)
                    put("output", result.output)
                    put("running", result.running)
                }
            }
        }
    )

    private val terminalSessionStopTool = Tool(
        name = "terminal_session_stop",
        description = "Stop a persistent terminal session.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Session ID from terminal_session_start."))
                },
                required = listOf("session_id")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackSessionTool("terminal_session_stop", "停止终端会话", input) {
                val result = terminalRuntime.stopSession(input.requiredString("session_id"))
                textJson {
                    put("session_id", result.id)
                    put("output", result.output)
                    put("running", result.running)
                }
            }
        }
    )

    private suspend fun trackSessionTool(
        toolName: String,
        title: String,
        input: kotlinx.serialization.json.JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.toString(),
            runtime = "android-shell-stage0",
            workspace = "/workspace",
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, result.previewText())
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            throw error
        }
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)
}
