package app.amber.feature.terminal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.core.infra.AppScope
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskOutputRef
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.task.running
import app.amber.feature.task.toQueueState
import app.amber.feature.workspace.WorkspaceManager
import app.amber.core.settings.prefs.SettingsAggregator
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

private const val WAIT_POLL_MS = 200L

class TerminalRuntime(
    private val context: Context,
    private val appScope: AppScope,
    private val workspaceManager: WorkspaceManager,
    private val alpineRuntimeInstaller: AlpineRuntimeInstaller,
    private val activityStore: AgentToolActivityStore,
    private val settingsStore: SettingsAggregator,
    private val agentTaskStore: AgentTaskStore,
) {
    private val jobs = ConcurrentHashMap<String, TerminalJob>()
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val installRunning = AtomicBoolean(false)

    suspend fun execute(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        syncWorkspace: Boolean = false,
        onOutputLine: ((String) -> Unit)? = null,
    ): TerminalResult {
        val shouldDetach = timeoutMillis > SHORT_EXECUTE_TIMEOUT_MS || command.looksLikeLongRunningCommand()
        val started = startJob(
            command = command,
            timeoutMillis = timeoutMillis,
            runtime = TerminalRuntimeKind.BUILTIN_ALPINE,
            toolName = "terminal_execute",
            title = "执行 Alpine 命令",
            syncWorkspace = !shouldDetach || syncWorkspace,
            flushWorkspace = shouldDetach && syncWorkspace,
            outputCallback = onOutputLine,
        )
        if (shouldDetach) {
            return TerminalResult(
                exitCode = 0,
                output = "Command started as terminal job ${started.jobId}. Use terminal_job_read or terminal_job_wait to follow progress.",
                runtime = started.runtime.wireName,
                workspace = "/workspace",
                syncNote = "",
                jobId = started.jobId,
                status = started.status.wireName,
                running = started.running,
                outputLogPath = started.outputLogPath,
            )
        }

        val final = waitJob(started.jobId, timeoutMillis + WAIT_AFTER_EXECUTE_MS)
        return TerminalResult(
            exitCode = final.exitCode ?: if (final.status == TerminalJobStatus.COMPLETED) 0 else 1,
            output = final.outputTail,
            runtime = final.runtime.wireName,
            workspace = "/workspace",
            syncNote = "",
            jobId = final.jobId,
            status = final.status.wireName,
            running = final.running,
            outputLogPath = final.outputLogPath,
        )
    }

    suspend fun startJob(
        command: String,
        timeoutMillis: Long = DEFAULT_JOB_TIMEOUT_MS,
        runtime: TerminalRuntimeKind? = null,
        toolName: String = "terminal_job_start",
        title: String = "执行终端任务",
        isInstall: Boolean = false,
        syncWorkspace: Boolean = false,
        flushWorkspace: Boolean = false,
        outputCallback: ((String) -> Unit)? = null,
    ): TerminalJobSnapshot = withContext(Dispatchers.IO) {
        val selectedRuntime = runtime ?: settingsStore.settingsFlow.value.agentRuntime.terminalDefaultRuntime
        val maxJobs = settingsStore.settingsFlow.value.agentRuntime.terminalMaxConcurrentJobs.coerceIn(1, MAX_CONCURRENT_JOBS)
        val runningJobs = jobs.values.count { it.status.get().running }
        if (runningJobs >= maxJobs) {
            return@withContext failedJob(
                command = command,
                runtime = selectedRuntime,
                error = "Too many running terminal jobs ($runningJobs/$maxJobs). Stop or wait for an existing job first.",
                toolName = toolName,
                title = title,
            )
        }
        if (isInstall && !installRunning.compareAndSet(false, true)) {
            return@withContext failedJob(
                command = command,
                runtime = selectedRuntime,
                error = "Another terminal_install_packages job is already running.",
                toolName = toolName,
                title = title,
            )
        }

        val job = newJob(
            command = command,
            runtime = selectedRuntime,
            timeoutMillis = timeoutMillis.coerceIn(MIN_JOB_TIMEOUT_MS, MAX_JOB_TIMEOUT_MS),
            toolName = toolName,
            title = title,
            isInstall = isInstall,
            syncWorkspace = syncWorkspace,
            flushWorkspace = flushWorkspace,
            outputCallback = outputCallback,
        )
        jobs[job.id] = job
        agentTaskStore.register(job.toAgentTaskSnapshot(AgentTaskStatus.QUEUED), cancel = {
            stopJob(job.id)
            true
        })
        activityStore.start(job.toActivityState())

        when (selectedRuntime) {
            TerminalRuntimeKind.BUILTIN_ALPINE -> {
                job.worker = appScope.launch(Dispatchers.IO) { runBuiltinAlpineJob(job) }
            }

            TerminalRuntimeKind.ANDROID_SHELL -> {
                job.worker = appScope.launch(Dispatchers.IO) { runAndroidShellJob(job) }
            }

            TerminalRuntimeKind.TERMUX_EXTERNAL -> {
                startTermuxJob(job)
            }
        }

        job.snapshot()
    }

    suspend fun installPackages(
        packages: List<String>,
        timeoutMillis: Long = DEFAULT_INSTALL_TIMEOUT_MS,
        runtime: TerminalRuntimeKind? = null,
    ): TerminalJobSnapshot {
        val selectedRuntime = runtime ?: settingsStore.settingsFlow.value.agentRuntime.terminalDefaultRuntime
        val plan = TerminalInstallPlanner.build(packages, selectedRuntime)
        val effectiveTimeout = if (timeoutMillis > 0) {
            timeoutMillis
        } else {
            settingsStore.settingsFlow.value.agentRuntime.terminalInstallTimeoutMs
        }
        return startJob(
            command = plan.command,
            timeoutMillis = effectiveTimeout,
            runtime = selectedRuntime,
            toolName = "terminal_install_packages",
            title = "安装终端依赖",
            isInstall = true,
            syncWorkspace = false,
            flushWorkspace = false,
        )
    }

    suspend fun readJob(id: String): TerminalJobSnapshot = withContext(Dispatchers.IO) {
        jobs[id]?.snapshot() ?: error("Unknown terminal job: $id")
    }

    suspend fun waitJob(id: String, timeoutMillis: Long = DEFAULT_WAIT_TIMEOUT_MS): TerminalJobSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(0L)
        while (System.currentTimeMillis() < deadline) {
            val snapshot = readJob(id)
            if (!snapshot.running) return snapshot
            delay(WAIT_POLL_MS)
        }
        return readJob(id)
    }

    suspend fun stopJob(id: String, reason: String = "Command cancelled by user."): TerminalJobSnapshot =
        withContext(Dispatchers.IO) {
            val job = jobs[id] ?: error("Unknown terminal job: $id")
            stopJobInternal(job, reason)
            job.snapshot()
        }

    suspend fun cancelRunningJobs(reason: String = "Command cancelled by user."): Int = withContext(Dispatchers.IO) {
        val running = jobs.values.filter { it.status.get().running }
        running.forEach { stopJobInternal(it, reason) }
        running.size
    }

    suspend fun flushWorkspace(): String = withContext(Dispatchers.IO) {
        workspaceManager.syncFromMirror()
    }

    fun handleTermuxResult(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val job = jobs[id] ?: return
        val result = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
        val stdout = result?.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT).orEmpty()
        val stderr = result?.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR).orEmpty()
        val err = result?.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_ERR, 0) ?: 0
        val errMsg = result?.getString(EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG).orEmpty()
        val exitCode = result?.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, if (err == 0) 0 else 1)
            ?: if (err == 0) 0 else 1

        if (stdout.isNotBlank()) appendJobOutput(job, stdout)
        if (stderr.isNotBlank()) appendJobOutput(job, stderr)
        if (errMsg.isNotBlank()) appendJobOutput(job, "Termux error: $errMsg\n")

        val status = if (exitCode == 0 && err == 0) TerminalJobStatus.COMPLETED else TerminalJobStatus.FAILED
        finishJob(job, status, exitCode, errMsg.ifBlank { null })
    }

    suspend fun probeTermuxRuntime(): TermuxRuntimeStatus = withContext(Dispatchers.IO) {
        probeTermuxRuntimeNow()
    }

    private fun probeTermuxRuntimeNow(): TermuxRuntimeStatus {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
        }.isSuccess
        val permissionGranted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.checkSelfPermission(TERMUX_PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
            } else {
                pm.checkPermission(TERMUX_PERMISSION_RUN_COMMAND, context.packageName) == PackageManager.PERMISSION_GRANTED
            }
        }.getOrDefault(false)
        val ready = installed && permissionGranted
        val message = when {
            !installed -> "Termux is not installed."
            !permissionGranted -> "Termux is installed, but AmberAgent does not have RUN_COMMAND permission."
            else -> "Termux is installed and RUN_COMMAND permission is granted. If commands still fail, enable allow-external-apps=true in Termux."
        }
        return TermuxRuntimeStatus(
            installed = installed,
            runCommandPermissionGranted = permissionGranted,
            allowExternalAppsConfigured = null,
            ready = ready,
            message = message,
        )
    }

    suspend fun startSession(): TerminalSessionInfo = withContext(Dispatchers.IO) {
        workspaceManager.ensureMirrorWorkspace()
        val workingDir = workspaceManager.mirrorDir.also { it.mkdirs() }
        val id = Uuid.random().toString()
        val processBuilder = ProcessBuilder(
            "/system/bin/sh",
            alpineRuntimeInstaller.localBinDir.resolve("init-host").absolutePath,
            "/bin/sh",
            "-l",
        )
            .directory(workingDir)
            .redirectErrorStream(true)
        alpineRuntimeInstaller.environment(
            workspacePath = workingDir.absolutePath,
            sessionId = id,
        ).forEach { (key, value) -> processBuilder.environment()[key] = value }
        val installStatus = alpineRuntimeInstaller.ensureInstalled()
        require(installStatus.success) { installStatus.message }
        val process = processBuilder.start()
        sessions[id] = TerminalSession(
            id = id,
            process = process,
            writer = BufferedWriter(OutputStreamWriter(process.outputStream)),
            workingDir = workingDir,
            runtime = TerminalRuntimeKind.BUILTIN_ALPINE,
            lastActivityMs = System.currentTimeMillis(),
        )
        TerminalSessionInfo(
            id = id,
            runtime = TerminalRuntimeKind.BUILTIN_ALPINE.wireName,
            workspace = workingDir.absolutePath,
        )
    }

    suspend fun execSession(id: String, command: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        session.lastActivityMs = System.currentTimeMillis()
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
            running = session.process.isAlive,
        )
    }

    suspend fun stopSession(id: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions.remove(id) ?: error("Unknown terminal session: $id")
        session.process.destroy()
        session.process.waitFor(1, TimeUnit.SECONDS)
        if (session.process.isAlive) {
            session.process.destroyForcibly()
        }
        workspaceManager.syncFromMirror()
        TerminalReadResult(
            id = id,
            output = "session stopped",
            running = false,
        )
    }

    private fun newJob(
        command: String,
        runtime: TerminalRuntimeKind,
        timeoutMillis: Long,
        toolName: String,
        title: String,
        isInstall: Boolean,
        syncWorkspace: Boolean,
        flushWorkspace: Boolean,
        outputCallback: ((String) -> Unit)?,
    ): TerminalJob {
        val id = Uuid.random().toString()
        val logDir = context.filesDir.resolve("amberagent/terminal-jobs").apply { mkdirs() }
        return TerminalJob(
            id = id,
            command = command,
            runtime = runtime,
            timeoutMillis = timeoutMillis,
            output = TerminalOutputBuffer(
                settingsStore.settingsFlow.value.agentRuntime.terminalOutputTailChars.coerceIn(
                    MIN_OUTPUT_TAIL_CHARS,
                    MAX_OUTPUT_TAIL_CHARS,
                )
            ),
            startedAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            toolName = toolName,
            title = title,
            isInstall = isInstall,
            syncWorkspace = syncWorkspace,
            flushWorkspace = flushWorkspace,
            outputCallback = outputCallback,
            log = TerminalJobLog(
                file = logDir.resolve("$id.log"),
                maxBytes = MAX_JOB_LOG_BYTES,
            ),
        )
    }

    private fun failedJob(
        command: String,
        runtime: TerminalRuntimeKind,
        error: String,
        toolName: String,
        title: String,
    ): TerminalJobSnapshot {
        val job = newJob(
            command = command,
            runtime = runtime,
            timeoutMillis = 0L,
            toolName = toolName,
            title = title,
            isInstall = false,
            syncWorkspace = false,
            flushWorkspace = false,
            outputCallback = null,
        )
        jobs[job.id] = job
        appScope.launch(Dispatchers.IO) {
            agentTaskStore.register(job.toAgentTaskSnapshot(AgentTaskStatus.FAILED))
        }
        appendJobOutput(job, "$error\n")
        finishJob(job, TerminalJobStatus.FAILED, null, error)
        return job.snapshot()
    }

    private suspend fun runBuiltinAlpineJob(job: TerminalJob) {
        runProcessJob(job) { workingDir ->
            val installStatus = alpineRuntimeInstaller.ensureInstalled()
            require(installStatus.success) { installStatus.message }
            ProcessBuilder(
                "/system/bin/sh",
                alpineRuntimeInstaller.localBinDir.resolve("init-host").absolutePath,
                "/bin/sh",
                "-lc",
                "export AMBERAGENT_JOB_ID=${job.id.shellQuoted()}; cd /workspace && ${job.command}",
            )
                .directory(workingDir)
                .redirectErrorStream(true)
                .also { builder ->
                    alpineRuntimeInstaller.environment(
                        workspacePath = workingDir.absolutePath,
                        sessionId = job.id,
                    ).forEach { (key, value) -> builder.environment()[key] = value }
                }
        }
    }

    private suspend fun runAndroidShellJob(job: TerminalJob) {
        runProcessJob(job) { workingDir ->
            ProcessBuilder("/system/bin/sh", "-lc", job.command)
                .directory(workingDir)
                .redirectErrorStream(true)
        }
    }

    private suspend fun runProcessJob(
        job: TerminalJob,
        processBuilder: suspend (File) -> ProcessBuilder,
    ) {
        try {
            job.status.set(TerminalJobStatus.RUNNING)
            agentTaskStore.update(job.id, status = AgentTaskStatus.RUNNING)
            val syncIn = if (job.syncWorkspace) {
                appendJobOutput(job, "Preparing /workspace mirror...\n")
                workspaceManager.refreshMirrorFromWorkspace()
            } else {
                workspaceManager.ensureMirrorWorkspace()
            }
            appendJobOutput(job, "$syncIn\n")
            if (job.status.get() == TerminalJobStatus.CANCELLED) {
                finishJob(job, TerminalJobStatus.CANCELLED, job.exitCode, job.error)
                return
            }
            val workingDir = workspaceManager.mirrorDir.also { it.mkdirs() }
            appendJobOutput(job, "Starting ${job.runtime.wireName} command...\n")
            val process = processBuilder(workingDir).start()
            job.process = process
            val reader = thread(name = "amberagent-terminal-output-${job.id}") {
                readProcessOutput(job, process)
            }
            val status = waitForProcess(job, process)
            reader.join(1_000)
            val exitCode = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
            val finalStatus = when {
                job.status.get() == TerminalJobStatus.CANCELLED -> TerminalJobStatus.CANCELLED
                status == TerminalJobStatus.TIMED_OUT -> TerminalJobStatus.TIMED_OUT
                exitCode == 0 -> TerminalJobStatus.COMPLETED
                else -> TerminalJobStatus.FAILED
            }
            if ((job.syncWorkspace || job.flushWorkspace) && !job.isInstall) {
                appendJobOutput(job, "Syncing /workspace changes back to SAF...\n")
                runCatching { workspaceManager.syncFromMirror() }
                    .onFailure { appendJobOutput(job, "Workspace sync failed: ${it.message.orEmpty()}\n") }
            }
            finishJob(job, finalStatus, exitCode, null)
        } catch (error: CancellationException) {
            stopJobInternal(job, "Command cancelled by user.")
            throw error
        } catch (error: Throwable) {
            appendJobOutput(job, "${error.message ?: error::class.java.simpleName}\n")
            finishJob(job, TerminalJobStatus.FAILED, null, error.message ?: error::class.java.simpleName)
        } finally {
            if (job.isInstall) installRunning.set(false)
        }
    }

    private fun readProcessOutput(job: TerminalJob, process: Process) {
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count <= 0) break
                appendJobOutput(job, String(buffer, 0, count))
            }
        } catch (error: Throwable) {
            if (process.isAlive && job.status.get().running) {
                appendJobOutput(job, "Terminal output stream closed: ${error.message.orEmpty()}\n")
            }
        }
    }

    private fun waitForProcess(job: TerminalJob, process: Process): TerminalJobStatus {
        val deadline = System.currentTimeMillis() + job.timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (job.status.get() == TerminalJobStatus.CANCELLED) {
                terminateProcess(job, process)
                return TerminalJobStatus.CANCELLED
            }
            if (process.waitFor(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                return TerminalJobStatus.COMPLETED
            }
        }
        appendJobOutput(job, "Command timed out after ${job.timeoutMillis}ms.\n")
        terminateProcess(job, process)
        return TerminalJobStatus.TIMED_OUT
    }

    private fun startTermuxJob(job: TerminalJob) {
        val status = probeTermuxRuntimeNow()
        if (!status.ready) {
            appendJobOutput(job, "${status.message}\n")
            finishJob(job, TerminalJobStatus.FAILED, null, status.message)
            if (job.isInstall) installRunning.set(false)
            return
        }

        runCatching {
            job.status.set(TerminalJobStatus.RUNNING)
            appScope.launch(Dispatchers.IO) {
                agentTaskStore.update(job.id, status = AgentTaskStatus.RUNNING)
            }
            val resultIntent = Intent(context, TermuxCommandResultReceiver::class.java).apply {
                action = ACTION_TERMUX_RESULT
                putExtra(EXTRA_JOB_ID, job.id)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pendingIntent = PendingIntent.getBroadcast(context, job.id.hashCode(), resultIntent, flags)
            val intent = Intent(ACTION_TERMUX_RUN_COMMAND).apply {
                setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE)
                putExtra(EXTRA_TERMUX_COMMAND_PATH, TERMUX_SHELL_PATH)
                putExtra(EXTRA_TERMUX_ARGUMENTS, arrayOf("-lc", job.command))
                putExtra(EXTRA_TERMUX_WORKDIR, TERMUX_HOME)
                putExtra(EXTRA_TERMUX_BACKGROUND, true)
                putExtra(EXTRA_TERMUX_COMMAND_LABEL, "AmberAgent terminal job")
                putExtra(EXTRA_TERMUX_PENDING_INTENT, pendingIntent)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            job.worker = appScope.launch(Dispatchers.IO) {
                delay(job.timeoutMillis)
                if (job.status.get().running) {
                    appendJobOutput(job, "Termux job timed out after ${job.timeoutMillis}ms. The external Termux process may still be running.\n")
                    finishJob(job, TerminalJobStatus.TIMED_OUT, null, "Termux job timed out.")
                }
            }
        }.onFailure { error ->
            appendJobOutput(job, "${error.message ?: error::class.java.simpleName}\n")
            finishJob(job, TerminalJobStatus.FAILED, null, error.message ?: error::class.java.simpleName)
            if (job.isInstall) installRunning.set(false)
        }
    }

    private fun stopJobInternal(job: TerminalJob, reason: String) {
        if (!job.status.get().running) return
        job.status.set(TerminalJobStatus.CANCELLED)
        appendJobOutput(job, "$reason\n")
        job.process?.let { terminateProcess(job, it) }
        if (job.runtime == TerminalRuntimeKind.TERMUX_EXTERNAL) {
            appendJobOutput(job, "Termux external jobs cannot be force-killed from AmberAgent; check Termux if work continues.\n")
        }
        finishJob(job, TerminalJobStatus.CANCELLED, job.exitCode, reason)
    }

    private fun finishJob(
        job: TerminalJob,
        status: TerminalJobStatus,
        exitCode: Int?,
        error: String?,
    ) {
        while (true) {
            val current = job.status.get()
            val allowCancelledFinalization = current == TerminalJobStatus.CANCELLED &&
                status == TerminalJobStatus.CANCELLED &&
                !job.completionNotified.get()
            if (!current.running && !allowCancelledFinalization) return
            if (job.status.compareAndSet(current, status)) break
        }
        job.exitCode = exitCode
        job.error = error
        job.updatedAtMs = System.currentTimeMillis()
        if (job.isInstall) installRunning.set(false)
        if (!job.completionNotified.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            agentTaskStore.update(
                taskId = job.id,
                status = status.toAgentTaskStatus(),
                summary = job.output.snapshot().take(4_000),
                error = error,
                outputOffset = job.log.file.length(),
                cancelCapability = false,
            )
        }
        when (status) {
            TerminalJobStatus.COMPLETED -> activityStore.complete(job.id, exitCode ?: 0, job.output.snapshot())
            TerminalJobStatus.CANCELLED -> activityStore.cancel(job.id, job.output.snapshot())
            TerminalJobStatus.FAILED,
            TerminalJobStatus.TIMED_OUT -> activityStore.complete(job.id, exitCode ?: 1, job.output.snapshot())
            TerminalJobStatus.QUEUED,
            TerminalJobStatus.RUNNING -> Unit
        }
    }

    private fun appendJobOutput(job: TerminalJob, text: String) {
        job.output.append(text)
        job.updatedAtMs = System.currentTimeMillis()
        runCatching { job.log.append(text) }
        text.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                runCatching { activityStore.appendOutput(job.id, line) }
                runCatching { job.outputCallback?.invoke(line) }
            }
    }

    private fun TerminalJob.toAgentTaskSnapshot(status: AgentTaskStatus) = AgentTaskSnapshot(
        taskId = id,
        type = "terminal",
        title = title,
        queueState = status.toQueueState("terminal"),
        status = status,
        outputPath = log.file.absolutePath,
        outputOffset = log.file.length(),
        outputRef = AgentTaskOutputRef(
            type = "terminal_log",
            path = log.file.absolutePath,
            tailOffset = log.file.length(),
            exists = log.file.exists(),
        ),
        retryPolicy = AgentTaskRetryPolicy(
            retryable = status in setOf(AgentTaskStatus.FAILED, AgentTaskStatus.TIMED_OUT, AgentTaskStatus.INTERRUPTED),
            requiresApproval = true,
            maxRetries = 1,
            reason = "Terminal commands can be retried by launching a fresh process; interrupted processes are not reattached.",
        ),
        runtime = runtime.name.lowercase(),
        sourceToolName = toolName,
        createdAtMs = startedAtMs,
        updatedAtMs = updatedAtMs,
        cancelCapability = status.running,
        summary = command.take(500),
    )

    private fun TerminalJobStatus.toAgentTaskStatus(): AgentTaskStatus = when (this) {
        TerminalJobStatus.QUEUED -> AgentTaskStatus.QUEUED
        TerminalJobStatus.RUNNING -> AgentTaskStatus.RUNNING
        TerminalJobStatus.COMPLETED -> AgentTaskStatus.COMPLETED
        TerminalJobStatus.FAILED -> AgentTaskStatus.FAILED
        TerminalJobStatus.CANCELLED -> AgentTaskStatus.CANCELLED
        TerminalJobStatus.TIMED_OUT -> AgentTaskStatus.TIMED_OUT
    }

    private fun terminateProcess(job: TerminalJob, process: Process) {
        val pid = processPid(process) ?: findJobProcessPid(job.id)
        pid?.let { killProcessTree(it, signal = "TERM") }
        runCatching { process.destroy() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        if (process.isAlive) {
            pid?.let { killProcessTree(it, signal = "KILL") }
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
    }

    private fun processPid(process: Process): Long? =
        processPidFromMethod(process) ?: processPidFromField(process)

    private fun processPidFromMethod(process: Process): Long? = runCatching {
        val value = process.javaClass.getMethod("pid").invoke(process)
        (value as? Number)?.toLong()
    }.getOrNull()

    private fun processPidFromField(process: Process): Long? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        (field.get(process) as? Number)?.toLong()
    }.getOrNull()

    private fun killProcessTree(pid: Long, signal: String) {
        val normalizedSignal = if (signal == "KILL") "9" else "15"
        collectDescendantPids(pid)
            .asReversed()
            .forEach { childPid ->
                runCatching {
                    ProcessBuilder("/system/bin/kill", "-$normalizedSignal", childPid.toString())
                        .start()
                        .waitFor(1, TimeUnit.SECONDS)
                }
            }
        runCatching {
            ProcessBuilder("/system/bin/pkill", "-$normalizedSignal", "-P", pid.toString())
                .start()
                .waitFor(1, TimeUnit.SECONDS)
        }
        runCatching {
            ProcessBuilder("/system/bin/kill", "-$normalizedSignal", pid.toString())
                .start()
                .waitFor(1, TimeUnit.SECONDS)
        }
    }

    private fun findJobProcessPid(jobId: String): Long? =
        processRows()
            .firstOrNull { row -> row.command.contains("AMBERAGENT_JOB_ID") && row.command.contains(jobId) }
            ?.pid

    private fun collectDescendantPids(rootPid: Long): List<Long> {
        val childrenByParent = processRows().groupBy { it.parentPid }
        val result = mutableListOf<Long>()
        fun visit(parentPid: Long) {
            childrenByParent[parentPid].orEmpty().forEach { child ->
                result += child.pid
                visit(child.pid)
            }
        }
        visit(rootPid)
        return result
    }

    private fun processRows(): List<ProcessRow> = runCatching {
        val process = ProcessBuilder("/system/bin/ps", "-ef").start()
        val rows = process.inputStream.bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 8)
                val pid = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val parentPid = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                ProcessRow(
                    pid = pid,
                    parentPid = parentPid,
                    command = parts.getOrNull(7).orEmpty(),
                )
            }.toList()
        }
        process.waitFor(1, TimeUnit.SECONDS)
        rows
    }.getOrDefault(emptyList())

    private fun TerminalJob.toActivityState(): SandboxActivityUiState =
        SandboxActivityUiState(
            toolCallId = id,
            toolName = toolName,
            title = title,
            status = ToolActivityStatus.RUNNING,
            inputPreview = command,
            runtime = runtime.wireName,
            workspace = "/workspace",
            startedAtEpochMillis = startedAtMs,
            canCancel = true,
        )

    private fun TerminalJob.snapshot(): TerminalJobSnapshot =
        TerminalJobSnapshot(
            jobId = id,
            runtime = runtime,
            status = status.get(),
            exitCode = exitCode,
            running = status.get().running,
            outputTail = output.snapshot(),
            outputLogPath = log.file.absolutePath,
            startedAtMs = startedAtMs,
            updatedAtMs = updatedAtMs,
            error = error,
        )

    private fun String.looksLikeLongRunningCommand(): Boolean =
        LONG_RUNNING_COMMAND_REGEX.containsMatchIn(this)

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val DEFAULT_JOB_TIMEOUT_MS = 15 * 60_000L
        private const val DEFAULT_INSTALL_TIMEOUT_MS = 15 * 60_000L
        private const val DEFAULT_WAIT_TIMEOUT_MS = 60_000L
        private const val SHORT_EXECUTE_TIMEOUT_MS = 120_000L
        private const val WAIT_AFTER_EXECUTE_MS = 5_000L
        private const val MIN_JOB_TIMEOUT_MS = 1_000L
        private const val MAX_JOB_TIMEOUT_MS = 60 * 60_000L
        private const val DEFAULT_READ_BYTES = 64 * 1024
        private const val MAX_CONCURRENT_JOBS = 4
        private const val MIN_OUTPUT_TAIL_CHARS = 64 * 1024
        private const val MAX_OUTPUT_TAIL_CHARS = 512 * 1024
        private const val MAX_JOB_LOG_BYTES = 8 * 1024 * 1024

        private const val TERMUX_PACKAGE_NAME = "com.termux"
        private const val TERMUX_PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_SHELL_PATH = "/data/data/com.termux/files/usr/bin/sh"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"

        private const val ACTION_TERMUX_RESULT = "me.rerere.rikkahub.action.TERMUX_COMMAND_RESULT"
        private const val ACTION_TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_TERMUX_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_TERMUX_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_TERMUX_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_TERMUX_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_TERMUX_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_TERMUX_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT = "stdout"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR = "stderr"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE = "exitCode"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERR = "err"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG = "errmsg"

        private val LONG_RUNNING_COMMAND_REGEX = Regex(
            "\\b(?:apk\\s+add|pip3?\\s+install|uv\\s+pip\\s+install|npm\\s+install|pnpm\\s+add|yarn\\s+add)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}

data class TerminalResult(
    val exitCode: Int,
    val output: String,
    val runtime: String,
    val workspace: String,
    val syncNote: String,
    val jobId: String? = null,
    val status: String? = null,
    val running: Boolean = false,
    val outputLogPath: String = "",
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

private data class TerminalJob(
    val id: String,
    val command: String,
    val runtime: TerminalRuntimeKind,
    val timeoutMillis: Long,
    val output: TerminalOutputBuffer,
    val startedAtMs: Long,
    var updatedAtMs: Long,
    val toolName: String,
    val title: String,
    val isInstall: Boolean,
    val syncWorkspace: Boolean,
    val flushWorkspace: Boolean,
    val outputCallback: ((String) -> Unit)?,
    val log: TerminalJobLog,
    val status: AtomicReference<TerminalJobStatus> = AtomicReference(TerminalJobStatus.QUEUED),
    val completionNotified: AtomicBoolean = AtomicBoolean(false),
    @Volatile var process: Process? = null,
    @Volatile var exitCode: Int? = null,
    @Volatile var error: String? = null,
    @Volatile var worker: Job? = null,
)

private data class TerminalSession(
    val id: String,
    val process: Process,
    val writer: BufferedWriter,
    val workingDir: File,
    val runtime: TerminalRuntimeKind,
    @Volatile var lastActivityMs: Long,
)

private data class ProcessRow(
    val pid: Long,
    val parentPid: Long,
    val command: String,
)
