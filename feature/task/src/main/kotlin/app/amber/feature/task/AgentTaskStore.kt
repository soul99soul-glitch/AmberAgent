package app.amber.feature.task

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class AgentTaskStore(
    context: Context,
    private val json: Json,
) {
    private val appFilesDir = context.filesDir
    private val taskDir = File(context.filesDir, "amberagent/tasks").also { it.mkdirs() }
    private val recoveryManager = AgentTaskRecoveryManager()
    private val mutex = Mutex()
    private val tasks = ConcurrentHashMap<String, AgentTaskSnapshot>()
    private val cancelCallbacks = ConcurrentHashMap<String, suspend () -> Boolean>()
    private val retryCallbacks = ConcurrentHashMap<String, suspend () -> Boolean>()
    private val _tasksFlow = MutableStateFlow<List<AgentTaskSnapshot>>(emptyList())
    val tasksFlow: StateFlow<List<AgentTaskSnapshot>> = _tasksFlow.asStateFlow()

    init {
        loadSnapshots()
        publish()
    }

    suspend fun register(
        snapshot: AgentTaskSnapshot,
        cancel: (suspend () -> Boolean)? = null,
        retry: (suspend () -> Boolean)? = null,
    ): AgentTaskSnapshot = upsert(snapshot, cancel, retry)

    suspend fun upsert(
        snapshot: AgentTaskSnapshot,
        cancel: (suspend () -> Boolean)? = null,
        retry: (suspend () -> Boolean)? = null,
    ): AgentTaskSnapshot = mutex.withLock {
        // Install the durable snapshot before publishing it to in-memory consumers. A
        // failed write must leave both the previous task and its callbacks untouched.
        persist(snapshot)
        tasks[snapshot.taskId] = snapshot
        if (cancel != null) {
            cancelCallbacks[snapshot.taskId] = cancel
        } else if (!snapshot.cancelCapability) {
            cancelCallbacks.remove(snapshot.taskId)
        }
        if (retry != null) {
            retryCallbacks[snapshot.taskId] = retry
        } else if (!snapshot.retryPolicy.retryable) {
            retryCallbacks.remove(snapshot.taskId)
        }
        publish()
        snapshot
    }

    /**
     * A null [error]/[lastErrorCode] argument preserves the current value for callers that
     * only update other fields. The clear flags mean "write exactly what was passed":
     * a non-null argument overwrites the stored value and a null argument clears it, so a
     * failure reporter can record an error and clear flags can reset both fields.
     */
    suspend fun update(
        taskId: String,
        status: AgentTaskStatus? = null,
        queueState: AgentTaskQueueState? = null,
        summary: String? = null,
        error: String? = null,
        lastErrorCode: String? = null,
        clearError: Boolean = false,
        clearLastErrorCode: Boolean = false,
        outputPath: String? = null,
        outputOffset: Long? = null,
        cancelCapability: Boolean? = null,
        recoveryState: AgentTaskRecoveryState? = null,
        retryPolicy: AgentTaskRetryPolicy? = null,
        outputRef: AgentTaskOutputRef? = null,
        lastHeartbeatMs: Long? = null,
        expectedSpec: JsonObject? = null,
    ): AgentTaskSnapshot? = mutex.withLock {
        val current = tasks[taskId] ?: return@withLock null
        if (expectedSpec != null && expectedSpec != current.spec) return@withLock null
        val next = current.copy(
            status = status ?: current.status,
            queueState = queueState ?: status?.toQueueState(current.type) ?: current.queueState,
            summary = summary ?: current.summary,
            error = if (clearError) error else error ?: current.error,
            lastErrorCode = if (clearError || clearLastErrorCode) lastErrorCode else lastErrorCode ?: current.lastErrorCode,
            outputPath = outputPath ?: current.outputPath,
            outputOffset = outputOffset ?: current.outputOffset,
            cancelCapability = cancelCapability ?: current.cancelCapability,
            recoveryState = recoveryState ?: status?.toRecoveryState(current.type, current.retryPolicy) ?: current.recoveryState,
            retryPolicy = retryPolicy ?: current.retryPolicy,
            outputRef = outputRef ?: current.outputRef,
            lastHeartbeatMs = lastHeartbeatMs ?: current.lastHeartbeatMs,
            updatedAtMs = System.currentTimeMillis(),
        )
        // See upsert: persistence is the commit point, publication follows it.
        persist(next)
        tasks[taskId] = next
        publish()
        next
    }

    suspend fun remove(taskId: String): Boolean = mutex.withLock {
        val existed = tasks.containsKey(taskId)
        deleteSnapshotFile(taskId)
        val removed = tasks.remove(taskId) != null
        cancelCallbacks.remove(taskId)
        retryCallbacks.remove(taskId)
        publish()
        removed || existed
    }

    fun list(type: String? = null, status: AgentTaskStatus? = null): List<AgentTaskSnapshot> =
        tasks.values
            .filter { type == null || it.type == type }
            .filter { status == null || it.status == status }
            .sortedWith(compareByDescending<AgentTaskSnapshot> { it.status.running }.thenByDescending { it.updatedAtMs })

    fun read(taskId: String): AgentTaskSnapshot? = tasks[taskId]

    suspend fun cancel(taskId: String): AgentTaskSnapshot = withContext(Dispatchers.IO) {
        val current = tasks[taskId] ?: error("Unknown agent task: $taskId")
        if (!current.status.running) return@withContext current
        val callback = cancelCallbacks[taskId]
        if (!current.cancelCapability || callback == null) {
            return@withContext update(
                taskId = taskId,
                error = "Task cannot be cancelled from AmberAgent.",
            ) ?: current
        }
        val ok = runCatching { callback() }.getOrDefault(false)
        if (ok) {
            tasks[taskId]?.takeUnless { it.status.running }
                ?: update(taskId = taskId, status = AgentTaskStatus.CANCELLED, summary = "Cancellation requested.")
        } else {
            update(taskId = taskId, error = "Cancellation request failed.")
        } ?: current
    }

    suspend fun retry(taskId: String): AgentTaskSnapshot = withContext(Dispatchers.IO) {
        val current = tasks[taskId] ?: error("Unknown agent task: $taskId")
        if (!current.retryPolicy.retryable) {
            return@withContext update(
                taskId = taskId,
                error = "Task is not retryable.",
                lastErrorCode = "retry_not_allowed",
            ) ?: current
        }
        if (current.retryPolicy.retryCount >= current.retryPolicy.maxRetries) {
            return@withContext update(
                taskId = taskId,
                error = "Task retry limit reached.",
                lastErrorCode = "retry_limit_reached",
            ) ?: current
        }
        val callback = retryCallbacks[taskId]
        if (callback == null) {
            return@withContext update(
                taskId = taskId,
                recoveryState = AgentTaskRecoveryState.RETRYABLE,
                error = "Task retry is available in metadata, but no live retry adapter is registered.",
                lastErrorCode = "retry_adapter_missing",
            ) ?: current
        }
        val retryPolicy = current.retryPolicy.copy(retryCount = current.retryPolicy.retryCount + 1)
        val ok = runCatching { callback() }.getOrDefault(false)
        if (ok) {
            update(
                taskId = taskId,
                status = AgentTaskStatus.QUEUED,
                queueState = AgentTaskQueueState.QUEUED,
                recoveryState = AgentTaskRecoveryState.ACTIVE,
                retryPolicy = retryPolicy,
                summary = "Retry requested.",
                clearError = true,
                clearLastErrorCode = true,
            )
        } else {
            update(
                taskId = taskId,
                retryPolicy = retryPolicy,
                error = "Retry request failed.",
                lastErrorCode = "retry_failed",
            )
        } ?: current
    }

    suspend fun cleanup(taskId: String, deletePrivateOutput: Boolean = false): Boolean = mutex.withLock {
        val current = tasks[taskId] ?: return@withLock false
        if (deletePrivateOutput) {
            privateOutputFile(current)?.let { output ->
                if (output.exists() && !output.delete() && output.exists()) {
                    throw IOException("Failed to delete task output: ${output.absolutePath}")
                }
            }
        }
        deleteSnapshotFile(taskId)
        tasks.remove(taskId)
        cancelCallbacks.remove(taskId)
        retryCallbacks.remove(taskId)
        publish()
        true
    }

    suspend fun reconcileOnStartup(): List<AgentTaskSnapshot> = mutex.withLock {
        val now = System.currentTimeMillis()
        val recovered = tasks.values.map { recoveryManager.recoverOnStartup(it, now) }
        // Persist all recovered snapshots before exposing any of them to observers.
        recovered.forEach(::persist)
        recovered.forEach { snapshot ->
            tasks[snapshot.taskId] = snapshot
        }
        publish()
        recovered.sortedByDescending { it.updatedAtMs }
    }

    private fun loadSnapshots() {
        taskDir.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
            val snapshot = runCatching {
                json.decodeFromString(AgentTaskSnapshot.serializer(), file.readText())
            }.getOrNull() ?: return@forEach
            val restored = recoveryManager.recoverOnStartup(snapshot, System.currentTimeMillis())
            if (restored != snapshot) persist(restored)
            tasks[restored.taskId] = restored
        }
    }

    private fun privateOutputFile(snapshot: AgentTaskSnapshot): File? {
        val path = snapshot.outputRef?.path ?: snapshot.outputPath ?: return null
        val file = File(path)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val root = runCatching { appFilesDir.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun deleteSnapshotFile(taskId: String) {
        val file = File(taskDir, "$taskId.json")
        if (file.exists() && !file.delete() && file.exists()) {
            throw IOException("Failed to delete agent task snapshot: ${file.absolutePath}")
        }
    }

    private fun persist(snapshot: AgentTaskSnapshot) {
        val destination = File(taskDir, "${snapshot.taskId}.json")
        val parent = destination.parentFile
            ?: throw IOException("Cannot resolve task snapshot parent: ${destination.path}")
        if (!parent.isDirectory) {
            throw IOException("Task snapshot parent is not a directory: ${parent.path}")
        }
        val encoded = json.encodeToString(AgentTaskSnapshot.serializer(), snapshot)
        val temp = File.createTempFile("task-snapshot-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temp.delete()
        }
    }

    private fun publish() {
        _tasksFlow.value = list()
    }
}

fun AgentTaskStatus.toQueueState(type: String): AgentTaskQueueState = when {
    type == "cron" && this == AgentTaskStatus.QUEUED -> AgentTaskQueueState.SCHEDULED
    this == AgentTaskStatus.QUEUED -> AgentTaskQueueState.QUEUED
    this == AgentTaskStatus.RUNNING -> AgentTaskQueueState.ACTIVE
    else -> AgentTaskQueueState.TERMINAL
}

fun AgentTaskStatus.toRecoveryState(type: String, retryPolicy: AgentTaskRetryPolicy): AgentTaskRecoveryState = when {
    type == "cron" && this == AgentTaskStatus.QUEUED -> AgentTaskRecoveryState.SCHEDULED
    this.running -> AgentTaskRecoveryState.ACTIVE
    retryPolicy.retryable && this in setOf(AgentTaskStatus.FAILED, AgentTaskStatus.INTERRUPTED, AgentTaskStatus.TIMED_OUT) -> AgentTaskRecoveryState.RETRYABLE
    this == AgentTaskStatus.COMPLETED -> AgentTaskRecoveryState.OUTPUT_ONLY
    else -> AgentTaskRecoveryState.CLEANUP_ONLY
}
