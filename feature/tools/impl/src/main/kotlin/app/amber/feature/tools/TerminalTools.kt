package app.amber.feature.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.terminal.TerminalJobSnapshot
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind

class TerminalTools(
    private val terminalRuntime: TerminalRuntime,
    private val activityStore: AgentToolActivityStore,
    private val sshProfileStore: app.amber.core.settings.ssh.SshProfileStore,
) {
    fun getTools(): List<Tool> = listOf(
        terminalExecuteTool,
        terminalSshProfilesTool,
        terminalJobStartTool,
        terminalJobReadTool,
        terminalJobWaitTool,
        terminalJobStopTool,
        terminalInstallPackagesTool,
        terminalWorkspaceFlushTool,
        terminalSessionStartTool,
        terminalSessionExecTool,
        terminalSessionReadTool,
        terminalSessionStopTool,
    )

    private val terminalExecuteTool = Tool(
        name = "terminal_execute",
        description = "Run a one-shot command in Alpine or over SSH. For SSH pass runtime=remote_ssh and ssh_profile_id from terminal_ssh_profiles. Configure credentials and accept host keys in Settings > Agent Runtime > SSH profiles first.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", stringProp("Command to run."))
                    put("timeout_ms", integerProp("Timeout in milliseconds. Defaults to 60000."))
                    put("runtime", stringProp("Runtime: builtin_alpine, android_shell, termux_external, remote_ssh. Defaults to builtin_alpine."))
                    put("ssh_profile_id", stringProp("Saved SSH profile ID; implies remote_ssh when runtime is omitted. Never pass passwords or keys in tool inputs."))
                    put("sync_workspace", booleanProp("For detached long commands, refresh the SAF workspace before start and flush changes back after completion."))
                },
                required = listOf("command")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val result = terminalRuntime.execute(
                command = input.requiredString("command"),
                timeoutMillis = input.long("timeout_ms") ?: 60_000L,
                syncWorkspace = input.boolean("sync_workspace") ?: false,
                runtime = input.runtime(),
                sshProfileId = input.string("ssh_profile_id"),
            )
            textJson {
                put("runtime", result.runtime)
                put("workspace", result.workspace)
                result.exitCode?.let { put("exit_code", it) }
                put("output", result.output)
                put("sync_note", result.syncNote)
                result.jobId?.let { put("job_id", it) }
                result.status?.let { put("status", it) }
                put("running", result.running)
                put("output_log_path", result.outputLogPath)
                result.sshProfileId?.let { put("ssh_profile_id", it) }
            }
        }
    )

    private val terminalSshProfilesTool = Tool(
        name = "terminal_ssh_profiles",
        description = "List saved SSH profiles, their IDs, default selection and host-key trust readiness. Returns no credentials. Add/edit profiles and confirm fingerprints in Settings > Agent Runtime > SSH profiles.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val state = sshProfileStore.snapshot()
            textJson {
                state.defaultProfileId?.let { put("default_profile_id", it) }
                put("profiles", buildJsonArray {
                    state.profiles.forEach { profile ->
                        add(buildJsonObject {
                            put("id", profile.id)
                            put("name", profile.name)
                            put("host", profile.host)
                            put("port", profile.port)
                            put("username", profile.username)
                            put("auth_method", profile.authMethod.wireName)
                            put("host_key_accepted", profile.acceptedHostKeyFingerprint != null)
                        })
                    }
                })
            }
        },
    )

    private val terminalJobStartTool = Tool(
        name = "terminal_job_start",
        description = "Start a terminal command as a background job, locally or over SSH. For SSH pass runtime=remote_ssh and ssh_profile_id from terminal_ssh_profiles. Use terminal_job_read/wait/stop to follow it. Closing SSH does not prove that the remote process stopped.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", stringProp("Command to run."))
                    put("timeout_ms", integerProp("Job timeout in milliseconds. Defaults to 900000."))
                    put("runtime", stringProp("Runtime: builtin_alpine, android_shell, termux_external, remote_ssh. Defaults to settings."))
                    put("ssh_profile_id", stringProp("Saved SSH profile ID; implies remote_ssh when runtime is omitted. Omit for the default SSH profile. Credentials must be configured in Settings, never in a command."))
                    put("sync_workspace", booleanProp("Refresh /workspace from SAF before start and flush changes back after completion. Use this when the command reads or writes user workspace files."))
                    put("flush_workspace", booleanProp("Flush /workspace changes back to SAF after completion without refreshing before start."))
                },
                required = listOf("command")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val snapshot = terminalRuntime.startJob(
                command = input.requiredString("command"),
                timeoutMillis = input.long("timeout_ms") ?: 15 * 60_000L,
                runtime = input.runtime(),
                sshProfileId = input.string("ssh_profile_id"),
                syncWorkspace = input.boolean("sync_workspace") ?: false,
                flushWorkspace = input.boolean("flush_workspace") ?: false,
            )
            snapshot.toTextJson()
        }
    )

    private val terminalJobReadTool = Tool(
        name = "terminal_job_read",
        description = "Read current status and output tail from a terminal job.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("job_id", stringProp("Job ID returned by terminal_job_start or terminal_install_packages."))
                },
                required = listOf("job_id")
            )
        },
        execute = { input ->
            terminalRuntime.readJob(input.requiredString("job_id")).toTextJson()
        }
    )

    private val terminalJobWaitTool = Tool(
        name = "terminal_job_wait",
        description = "Wait for a terminal job to finish, or return its current status after the wait timeout.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("job_id", stringProp("Job ID returned by terminal_job_start or terminal_install_packages."))
                    put("timeout_ms", integerProp("How long to wait in milliseconds. Defaults to 60000."))
                },
                required = listOf("job_id")
            )
        },
        execute = { input ->
            terminalRuntime.waitJob(
                id = input.requiredString("job_id"),
                timeoutMillis = input.long("timeout_ms") ?: 60_000L,
            ).toTextJson()
        }
    )

    private val terminalJobStopTool = Tool(
        name = "terminal_job_stop",
        description = "Stop a running terminal job.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("job_id", stringProp("Job ID returned by terminal_job_start or terminal_install_packages."))
                },
                required = listOf("job_id")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            terminalRuntime.stopJob(input.requiredString("job_id")).toTextJson()
        }
    )

    private val terminalInstallPackagesTool = Tool(
        name = "terminal_install_packages",
        description = "Install terminal dependencies such as ffmpeg or yt-dlp in the selected runtime. Returns a background job ID and verification output.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("packages", arrayProp("Package names to install, for example [\"ffmpeg\", \"yt-dlp\"]."))
                    put("timeout_ms", integerProp("Install timeout in milliseconds. Defaults to 900000."))
                    put("runtime", stringProp("Runtime: builtin_alpine or termux_external. Defaults to settings."))
                },
                required = listOf("packages")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            terminalRuntime.installPackages(
                packages = input.jsonObject["packages"]?.jsonArrayStrings().orEmpty(),
                timeoutMillis = input.long("timeout_ms") ?: 0L,
                runtime = input.runtime(),
            ).toTextJson()
        }
    )

    private val terminalWorkspaceFlushTool = Tool(
        name = "terminal_workspace_flush",
        description = "Flush the app-private /workspace mirror back to the user-authorized SAF workspace.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = {
            val result = terminalRuntime.flushWorkspace()
            textJson {
                put("status", "completed")
                put("message", result)
            }
        }
    )

    private val terminalSessionStartTool = Tool(
        name = "terminal_session_start",
        description = "Start a persistent Alpine/proot terminal session in the AmberAgent runtime workspace. This is non-PTY; tmux/TUI support needs later PTY wiring.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        allowsAutoApproval = false,
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
        allowsAutoApproval = false,
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
        allowsAutoApproval = false,
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
            runtime = "builtin_alpine",
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

    private fun kotlinx.serialization.json.JsonElement.runtime(): TerminalRuntimeKind? =
        string("runtime")?.let { value ->
            requireNotNull(TerminalRuntimeKind.fromWire(value)) { "Unsupported terminal runtime: $value" }
        }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun arrayProp(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArrayStrings(): List<String> =
        jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

    private fun TerminalJobSnapshot.toTextJson(): List<UIMessagePart> = textJson {
        put("job_id", jobId)
        put("runtime", runtime.wireName)
        put("workspace", workspace)
        put("status", status.wireName)
        exitCode?.let { put("exit_code", it) }
        put("running", running)
        put("output_tail", outputTail)
        put("output_log_path", outputLogPath)
        put("started_at_ms", startedAtMs)
        put("updated_at_ms", updatedAtMs)
        error?.let { put("error", it) }
        sshProfileId?.let { put("ssh_profile_id", it) }
    }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)
}
