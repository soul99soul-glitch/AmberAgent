package app.amber.feature.terminal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import app.amber.feature.workspace.WorkspaceMirrorLease
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
    private val admissionMutex = Mutex()
    private val sessionMutex = Mutex()
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
            val startedSuccessfully = started.running
            return TerminalResult(
                exitCode = started.exitCode,
                output = if (startedSuccessfully) {
                    "Command started as terminal job ${started.jobId}. Use terminal_job_read or terminal_job_wait to follow progress."
                } else {
                    started.error ?: started.outputTail
                },
                runtime = started.runtime.wireName,
                workspace = started.workspace,
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
            workspace = final.workspace,
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
        val job = admissionMutex.withLock {
            pruneFinishedJobsLocked()
            val maxJobs = settingsStore.settingsFlow.value.agentRuntime.terminalMaxConcurrentJobs
                .coerceIn(1, MAX_CONCURRENT_JOBS)
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
            if ((syncWorkspace || flushWorkspace) && !selectedRuntime.supportsWorkspaceSync) {
                return@withContext failedJob(
                    command = command,
                    runtime = selectedRuntime,
                    error = "${selectedRuntime.wireName} does not use AmberAgent /workspace and cannot sync or flush it.",
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

            newJob(
                command = command,
                runtime = selectedRuntime,
                timeoutMillis = timeoutMillis.coerceIn(MIN_JOB_TIMEOUT_MS, MAX_JOB_TIMEOUT_MS),
                toolName = toolName,
                title = title,
                isInstall = isInstall,
                syncWorkspace = syncWorkspace,
                flushWorkspace = flushWorkspace,
                outputCallback = outputCallback,
            ).also { jobs[it.id] = it }
        }
        agentTaskStore.register(job.toAgentTaskSnapshot(AgentTaskStatus.QUEUED), cancel = {
            stopJob(job.id).status in setOf(TerminalJobStatus.CANCELLED, TerminalJobStatus.INTERRUPTED)
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
            job.snapshot().also { snapshot ->
                if (job.runtime == TerminalRuntimeKind.TERMUX_EXTERNAL && !snapshot.running) {
                    agentTaskStore.update(
                        taskId = job.id,
                        status = snapshot.status.toAgentTaskStatus(),
                        summary = snapshot.outputTail.take(4_000),
                        error = snapshot.error,
                        outputOffset = job.log.file.length(),
                        cancelCapability = false,
                    )
                }
            }
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
        sessionMutex.withLock {
            pruneDeadSessionsLocked()
            val activeSessions = sessions.values.count { it.process.isAlive }
            check(activeSessions < MAX_CONCURRENT_SESSIONS) {
                "Too many active terminal sessions ($activeSessions/$MAX_CONCURRENT_SESSIONS). Stop an existing session first."
            }
            val id = Uuid.random().toString()
            val installStatus = alpineRuntimeInstaller.ensureInstalled()
            require(installStatus.success) { installStatus.message }
            var mirrorLease: WorkspaceMirrorLease? = null
            try {
                mirrorLease = workspaceManager.acquireTerminalMirror(refreshFromWorkspace = false)
                val workingDir = mirrorLease.directory
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
                val process = processBuilder.start()
                val session = TerminalSession(
                    id = id,
                    process = process,
                    writer = BufferedWriter(OutputStreamWriter(process.outputStream)),
                    workingDir = workingDir,
                    runtime = TerminalRuntimeKind.BUILTIN_ALPINE,
                    mirrorLease = mirrorLease,
                    output = TerminalSessionOutput(MAX_SESSION_OUTPUT_CHARS),
                    lastActivityMs = System.currentTimeMillis(),
                )
                sessions[id] = session
                session.reader = appScope.launch(Dispatchers.IO) { readSessionOutput(session) }
                TerminalSessionInfo(
                    id = id,
                    runtime = TerminalRuntimeKind.BUILTIN_ALPINE.wireName,
                    workspace = "/workspace",
                )
            } catch (error: Throwable) {
                mirrorLease?.release(syncBack = false)
                throw error
            }
        }
    }

    suspend fun execSession(id: String, command: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        check(session.process.isAlive) { "Terminal session has exited: $id. Read it to collect final output." }
        session.lastActivityMs = System.currentTimeMillis()
        session.writer.write(command)
        session.writer.newLine()
        session.writer.flush()
        readSession(id)
    }

    suspend fun readSession(id: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        TerminalReadResult(
            id = id,
            output = session.output.drain(),
            running = session.process.isAlive,
        )
    }

    suspend fun stopSession(id: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        withContext(NonCancellable) {
            var terminated = false
            var syncNote = ""
            try {
                runCatching { session.writer.close() }
                session.process.destroy()
                session.process.waitFor(1, TimeUnit.SECONDS)
                if (session.process.isAlive) {
                    session.process.destroyForcibly()
                    session.process.waitFor(1, TimeUnit.SECONDS)
                }
                check(!session.process.isAlive) { "Unable to stop terminal session: $id" }
                terminated = true
            } finally {
                if (terminated) {
                    sessions.remove(id, session)
                    withTimeoutOrNull(1_000L) { session.reader?.join() }
                    syncNote = runCatching {
                        session.mirrorLease.release(syncBack = workspaceManager.state.value.configured)
                    }.getOrElse { "workspace sync failed: ${it.message}" }
                }
            }
            TerminalReadResult(
                id = id,
                output = listOf(session.output.drain(), "session stopped", syncNote)
                    .filter { it.isNotBlank() }
                    .joinToString("; "),
                running = false,
            )
        }
    }

    private suspend fun readSessionOutput(session: TerminalSession) {
        val buffer = ByteArray(DEFAULT_READ_BYTES)
        try {
            while (true) {
                val count = session.process.inputStream.read(buffer)
                if (count <= 0) break
                session.output.append(String(buffer, 0, count))
                session.lastActivityMs = System.currentTimeMillis()
            }
        } catch (error: Throwable) {
            if (session.process.isAlive) {
                session.output.append("Terminal output stream closed: ${error.message.orEmpty()}\n")
            }
        } finally {
            if (session.process.isAlive) {
                runCatching { session.process.waitFor() }
            }
            if (!session.process.isAlive) {
                runCatching { session.writer.close() }
                val syncNote = runCatching {
                    session.mirrorLease.release(syncBack = workspaceManager.state.value.configured)
                }.getOrElse { "workspace sync failed: ${it.message}" }
                if (syncNote.isNotBlank()) session.output.append("$syncNote\n")
            }
        }
    }

    private fun pruneDeadSessionsLocked() {
        val deadSessions = sessions.values
            .filterNot { it.process.isAlive }
            .sortedBy { it.lastActivityMs }
        val removeCount = (deadSessions.size - MAX_RETAINED_SESSIONS + 1).coerceAtLeast(0)
        deadSessions.take(removeCount).forEach { session -> sessions.remove(session.id, session) }
    }

    private fun pruneFinishedJobsLocked() {
        val finishedJobs = jobs.values
            .filterNot { it.status.get().running }
            .sortedBy { it.updatedAtMs }
        val removeCount = (finishedJobs.size - MAX_RETAINED_JOBS + 1).coerceAtLeast(0)
        finishedJobs.take(removeCount).forEach { job -> jobs.remove(job.id, job) }
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
            workspace = when (runtime) {
                TerminalRuntimeKind.BUILTIN_ALPINE -> "/workspace"
                TerminalRuntimeKind.ANDROID_SHELL -> workspaceManager.mirrorDir.absolutePath
                TerminalRuntimeKind.TERMUX_EXTERNAL -> TERMUX_HOME
            },
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
        var mirrorLease: WorkspaceMirrorLease? = null
        var processStatus: TerminalJobStatus? = null
        var processExitCode: Int? = null
        try {
            if (!job.status.compareAndSet(TerminalJobStatus.QUEUED, TerminalJobStatus.RUNNING)) return
            agentTaskStore.update(job.id, status = AgentTaskStatus.RUNNING)
            if (job.syncWorkspace) {
                appendJobOutput(job, "Preparing /workspace mirror...\n")
            }
            mirrorLease = workspaceManager.acquireTerminalMirror(refreshFromWorkspace = job.syncWorkspace)
            appendJobOutput(job, "${mirrorLease.preparationNote}\n")
            if (job.status.get() == TerminalJobStatus.CANCELLED) {
                mirrorLease.release(syncBack = false)
                finishJob(job, TerminalJobStatus.CANCELLED, job.exitCode, job.error)
                return
            }
            val workingDir = mirrorLease.directory
            appendJobOutput(job, "Starting ${job.runtime.wireName} command...\n")
            val process = processBuilder(workingDir).start()
            job.process = process
            val reader = thread(name = "amberagent-terminal-output-${job.id}") {
                readProcessOutput(job, process)
            }
            val status = waitForProcess(job, process)
            reader.join(1_000)
            processExitCode = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
            val finalStatus = when {
                job.status.get() == TerminalJobStatus.CANCELLED -> TerminalJobStatus.CANCELLED
                status == TerminalJobStatus.TIMED_OUT -> TerminalJobStatus.TIMED_OUT
                processExitCode == 0 -> TerminalJobStatus.COMPLETED
                else -> TerminalJobStatus.FAILED
            }
            processStatus = finalStatus
            val syncBack = (job.syncWorkspace || job.flushWorkspace) && !job.isInstall
            if (syncBack) {
                appendJobOutput(job, "Syncing /workspace changes back to SAF...\n")
            }
            val syncNote = mirrorLease.release(syncBack = syncBack)
            if (syncNote.isNotBlank()) appendJobOutput(job, "$syncNote\n")
            val statusAfterSync = if (job.status.get() == TerminalJobStatus.CANCELLED) {
                TerminalJobStatus.CANCELLED
            } else {
                finalStatus
            }
            finishJob(
                job,
                statusAfterSync,
                processExitCode,
                job.error.takeIf { statusAfterSync == TerminalJobStatus.CANCELLED },
            )
        } catch (error: CancellationException) {
            stopJobInternal(job, "Command cancelled by user.")
            mirrorLease?.release(syncBack = false)
            finishJob(job, TerminalJobStatus.CANCELLED, processExitCode, job.error)
            throw error
        } catch (error: Throwable) {
            job.process?.takeIf { it.isAlive }?.let { terminateProcess(job, it) }
            mirrorLease?.release(syncBack = false)
            val message = listOfNotNull(job.error, error.message ?: error::class.java.simpleName)
                .distinct()
                .joinToString("; ")
            val finalStatus = when {
                job.status.get() == TerminalJobStatus.CANCELLED -> TerminalJobStatus.CANCELLED
                processStatus == TerminalJobStatus.TIMED_OUT -> TerminalJobStatus.TIMED_OUT
                else -> TerminalJobStatus.FAILED
            }
            appendJobOutput(job, "$message\n")
            finishJob(
                job = job,
                status = finalStatus,
                exitCode = processExitCode?.takeUnless { finalStatus == TerminalJobStatus.FAILED && it == 0 },
                error = message,
            )
        } finally {
            if (job.process?.isAlive != true) {
                mirrorLease?.release(syncBack = false)
            }
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
            if (!job.status.compareAndSet(TerminalJobStatus.QUEUED, TerminalJobStatus.RUNNING)) {
                return
            }
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
                    finishJob(
                        job,
                        TerminalJobStatus.INTERRUPTED,
                        null,
                        "Termux result timed out in AmberAgent; external process state is unknown.",
                    )
                }
            }
        }.onFailure { error ->
            appendJobOutput(job, "${error.message ?: error::class.java.simpleName}\n")
            finishJob(job, TerminalJobStatus.FAILED, null, error.message ?: error::class.java.simpleName)
            if (job.isInstall) installRunning.set(false)
        }
    }

    private fun stopJobInternal(job: TerminalJob, reason: String) {
        val stoppedStatus = if (job.runtime == TerminalRuntimeKind.TERMUX_EXTERNAL) {
            TerminalJobStatus.INTERRUPTED
        } else {
            TerminalJobStatus.CANCELLED
        }
        var previousStatus: TerminalJobStatus
        while (true) {
            previousStatus = job.status.get()
            if (!previousStatus.running) return
            if (job.status.compareAndSet(previousStatus, stoppedStatus)) break
        }
        job.error = reason
        appendJobOutput(job, "$reason\n")
        job.process?.let { terminateProcess(job, it) }
        if (job.runtime == TerminalRuntimeKind.TERMUX_EXTERNAL) {
            appendJobOutput(job, "Termux external jobs cannot be force-killed from AmberAgent; check Termux if work continues.\n")
        }
        if (job.runtime == TerminalRuntimeKind.TERMUX_EXTERNAL || previousStatus == TerminalJobStatus.QUEUED) {
            finishJob(job, stoppedStatus, job.exitCode, reason)
        }
    }

    private fun finishJob(
        job: TerminalJob,
        status: TerminalJobStatus,
        exitCode: Int?,
        error: String?,
    ) {
        var finalStatus = status
        while (true) {
            val current = job.status.get()
            if (!current.running) {
                if (current in setOf(TerminalJobStatus.CANCELLED, TerminalJobStatus.INTERRUPTED) &&
                    !job.completionNotified.get()
                ) {
                    finalStatus = current
                    break
                }
                return
            }
            if (job.status.compareAndSet(current, status)) break
        }
        val finalError = if (finalStatus in setOf(TerminalJobStatus.CANCELLED, TerminalJobStatus.INTERRUPTED)) {
            error ?: job.error
        } else {
            error
        }
        job.exitCode = exitCode
        job.error = finalError
        job.updatedAtMs = System.currentTimeMillis()
        job.outputCallback = null
        if (job.isInstall) installRunning.set(false)
        if (!job.completionNotified.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            agentTaskStore.update(
                taskId = job.id,
                status = finalStatus.toAgentTaskStatus(),
                summary = job.output.snapshot().take(4_000),
                error = finalError,
                outputOffset = job.log.file.length(),
                cancelCapability = false,
            )
        }
        when (finalStatus) {
            TerminalJobStatus.COMPLETED -> activityStore.complete(job.id, exitCode ?: 0, job.output.snapshot())
            TerminalJobStatus.CANCELLED -> activityStore.cancel(job.id, job.output.snapshot())
            TerminalJobStatus.FAILED -> activityStore.complete(job.id, exitCode ?: 1, job.output.snapshot())
            TerminalJobStatus.TIMED_OUT -> activityStore.complete(
                job.id,
                exitCode ?: 1,
                job.output.snapshot(),
                ToolActivityStatus.TIMED_OUT,
            )
            TerminalJobStatus.INTERRUPTED -> activityStore.complete(
                job.id,
                exitCode ?: 1,
                job.output.snapshot(),
                ToolActivityStatus.INTERRUPTED,
            )
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
        TerminalJobStatus.INTERRUPTED -> AgentTaskStatus.INTERRUPTED
    }

    private fun terminateProcess(job: TerminalJob, process: Process) {
        val pid = processPid(process) ?: findJobProcessPid(job.id)
        pid?.let { killProcessTree(it, signal = "TERM") }
        runCatching { process.destroy() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        if (process.isAlive) {
            pid?.let { killProcessTree(it, signal = "KILL") }
            process.destroyForcibly()
            process.waitFor()
        }
        check(!process.isAlive) { "Terminal process did not stop; workspace lease remains held." }
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
            workspace = workspace,
            startedAtEpochMillis = startedAtMs,
            canCancel = true,
        )

    private fun TerminalJob.snapshot(): TerminalJobSnapshot {
        val currentStatus = status.get()
        return TerminalJobSnapshot(
            jobId = id,
            runtime = runtime,
            workspace = workspace,
            status = currentStatus,
            exitCode = exitCode,
            running = currentStatus.running,
            outputTail = output.snapshot(),
            outputLogPath = log.file.absolutePath,
            startedAtMs = startedAtMs,
            updatedAtMs = updatedAtMs,
            error = error,
        )
    }

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
        private const val MAX_CONCURRENT_SESSIONS = 4
        private const val MAX_RETAINED_JOBS = 64
        private const val MAX_RETAINED_SESSIONS = 8
        private const val MIN_OUTPUT_TAIL_CHARS = 64 * 1024
        private const val MAX_OUTPUT_TAIL_CHARS = 512 * 1024
        private const val MAX_SESSION_OUTPUT_CHARS = 256 * 1024
        private const val MAX_JOB_LOG_BYTES = 8 * 1024 * 1024

        private const val TERMUX_PACKAGE_NAME = "com.termux"
        private const val TERMUX_PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_SHELL_PATH = "/data/data/com.termux/files/usr/bin/sh"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"

        private const val ACTION_TERMUX_RESULT = "app.amber.agent.action.TERMUX_COMMAND_RESULT"
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
    val exitCode: Int?,
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
    val workspace: String,
    val timeoutMillis: Long,
    val output: TerminalOutputBuffer,
    val startedAtMs: Long,
    var updatedAtMs: Long,
    val toolName: String,
    val title: String,
    val isInstall: Boolean,
    val syncWorkspace: Boolean,
    val flushWorkspace: Boolean,
    @Volatile var outputCallback: ((String) -> Unit)?,
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
    val mirrorLease: WorkspaceMirrorLease,
    val output: TerminalSessionOutput,
    @Volatile var reader: Job? = null,
    @Volatile var lastActivityMs: Long,
)

private class TerminalSessionOutput(private val maxChars: Int) {
    private val buffer = StringBuilder()
    private var truncated = false

    @Synchronized
    fun append(text: String) {
        buffer.append(text)
        if (buffer.length > maxChars) {
            buffer.delete(0, buffer.length - maxChars)
            truncated = true
        }
    }

    @Synchronized
    fun drain(): String {
        val output = buildString {
            if (truncated) append("[Session output truncated]\n")
            append(buffer)
        }
        buffer.clear()
        truncated = false
        return output
    }
}

private data class ProcessRow(
    val pid: Long,
    val parentPid: Long,
    val command: String,
)
