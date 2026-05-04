package me.rerere.rikkahub.data.agent.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.SandboxActivityUiState
import me.rerere.rikkahub.data.agent.ToolActivityStatus
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import java.io.BufferedWriter
import java.io.IOException
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

class TerminalRuntime(
    private val workspaceManager: WorkspaceManager,
    private val alpineRuntimeInstaller: AlpineRuntimeInstaller,
    private val activityStore: AgentToolActivityStore,
) {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    suspend fun execute(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        onOutputLine: ((String) -> Unit)? = null,
    ): TerminalResult =
        withContext(Dispatchers.IO) {
            val sessionId = Uuid.random().toString()
            activityStore.start(
                SandboxActivityUiState(
                    toolCallId = sessionId,
                    toolName = "terminal_execute",
                    title = "执行 Alpine 命令",
                    status = ToolActivityStatus.RUNNING,
                    inputPreview = command,
                    runtime = ALPINE_RUNTIME_NAME,
                    workspace = "/workspace",
                    startedAtEpochMillis = System.currentTimeMillis(),
                    canCancel = true,
                )
            )
            runCatching {
                val mirrorResult = workspaceManager.withMirrorSync { workingDir ->
                    val installStatus = alpineRuntimeInstaller.ensureInstalled()
                    require(installStatus.success) { installStatus.message }
                    workingDir.mkdirs()
                    val processBuilder = ProcessBuilder(
                        "/system/bin/sh",
                        alpineRuntimeInstaller.localBinDir.resolve("init-host").absolutePath,
                        "/bin/sh",
                        "-lc",
                        "cd /workspace && $command"
                    )
                        .directory(workingDir)
                        .redirectErrorStream(true)
                    alpineRuntimeInstaller.environment(
                        workspacePath = workingDir.absolutePath,
                        sessionId = sessionId
                    ).forEach { (key, value) ->
                        processBuilder.environment()[key] = value
                    }
                    val process = processBuilder.start()
                    val output = StringBuffer()
                    val outputReader = thread(name = "amberagent-terminal-output-$sessionId") {
                        try {
                            process.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    output.appendLine(line)
                                    activityStore.appendOutput(sessionId, line)
                                    runCatching { onOutputLine?.invoke(line) }
                                }
                            }
                        } catch (error: IOException) {
                            if (process.isAlive) {
                                val line = "Terminal output stream closed: ${error.message.orEmpty()}"
                                output.appendLine(line)
                                activityStore.appendOutput(sessionId, line)
                            }
                        }
                    }
                    val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                    val exitCode = if (finished) {
                        process.exitValue()
                    } else {
                        process.destroyForcibly()
                        process.waitFor(1, TimeUnit.SECONDS)
                        val timeoutLine = "Command timed out after ${timeoutMillis}ms."
                        output.appendLine(timeoutLine)
                        activityStore.appendOutput(sessionId, timeoutLine)
                        runCatching { onOutputLine?.invoke(timeoutLine) }
                        TIMEOUT_EXIT_CODE
                    }
                    outputReader.join(1_000)
                    activityStore.complete(sessionId, exitCode, output.toString())
                    TerminalResult(
                        exitCode = exitCode,
                        output = output.toString(),
                        runtime = ALPINE_RUNTIME_NAME,
                        workspace = workingDir.absolutePath,
                        syncNote = ""
                    )
                }
                mirrorResult.value.copy(syncNote = mirrorResult.syncNote)
            }.onFailure { error ->
                activityStore.fail(sessionId, error)
            }.getOrThrow()
        }

    suspend fun startSession(): TerminalSessionInfo = withContext(Dispatchers.IO) {
        workspaceManager.syncToMirror()
        val workingDir = workspaceManager.mirrorDir.also { it.mkdirs() }
        val process = ProcessBuilder("/system/bin/sh")
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val id = Uuid.random().toString()
        sessions[id] = TerminalSession(
            id = id,
            process = process,
            writer = BufferedWriter(OutputStreamWriter(process.outputStream)),
            workingDir = workingDir
        )
        TerminalSessionInfo(
            id = id,
            runtime = SESSION_RUNTIME_NAME,
            workspace = workingDir.absolutePath
        )
    }

    suspend fun execSession(id: String, command: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        session.writer.write(command)
        session.writer.newLine()
        session.writer.flush()
        readSession(id)
    }

    suspend fun readSession(id: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        val stream = session.process.inputStream
        val buffer = ByteArray(DEFAULT_READ_BYTES)
        val output = StringBuilder()
        while (stream.available() > 0) {
            val count = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
            if (count <= 0) break
            output.append(String(buffer, 0, count))
        }
        TerminalReadResult(
            id = id,
            output = output.toString(),
            running = session.process.isAlive
        )
    }

    suspend fun stopSession(id: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions.remove(id) ?: error("Unknown terminal session: $id")
        session.process.destroy()
        workspaceManager.syncFromMirror()
        TerminalReadResult(
            id = id,
            output = "session stopped",
            running = false
        )
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val DEFAULT_READ_BYTES = 64 * 1024
        private const val TIMEOUT_EXIT_CODE = 124
        private const val ALPINE_RUNTIME_NAME = "alpine-proot-stage1"
        private const val SESSION_RUNTIME_NAME = "android-shell-stage0"
    }
}

data class TerminalResult(
    val exitCode: Int,
    val output: String,
    val runtime: String,
    val workspace: String,
    val syncNote: String,
)

data class TerminalSessionInfo(
    val id: String,
    val runtime: String,
    val workspace: String,
)

data class TerminalReadResult(
    val id: String,
    val output: String,
    val running: Boolean,
)

private data class TerminalSession(
    val id: String,
    val process: Process,
    val writer: BufferedWriter,
    val workingDir: File,
)
