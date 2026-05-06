package me.rerere.rikkahub.data.agent.task

import kotlinx.coroutines.flow.StateFlow

class AgentTaskScheduler(
    private val taskStore: AgentTaskStore,
) {
    val tasksFlow: StateFlow<List<AgentTaskSnapshot>> = taskStore.tasksFlow

    suspend fun enqueue(
        snapshot: AgentTaskSnapshot,
        cancel: (suspend () -> Boolean)? = null,
        retry: (suspend () -> Boolean)? = null,
    ): AgentTaskSnapshot = taskStore.upsert(
        snapshot = snapshot.copy(
            status = AgentTaskStatus.QUEUED,
            queueState = AgentTaskQueueState.QUEUED,
            recoveryState = AgentTaskRecoveryState.ACTIVE,
        ),
        cancel = cancel,
        retry = retry,
    )

    suspend fun start(
        snapshot: AgentTaskSnapshot,
        cancel: (suspend () -> Boolean)? = null,
        retry: (suspend () -> Boolean)? = null,
    ): AgentTaskSnapshot = taskStore.upsert(
        snapshot = snapshot.copy(
            status = AgentTaskStatus.RUNNING,
            queueState = AgentTaskQueueState.ACTIVE,
            recoveryState = AgentTaskRecoveryState.ACTIVE,
        ),
        cancel = cancel,
        retry = retry,
    )

    suspend fun complete(taskId: String, summary: String? = null): AgentTaskSnapshot? =
        taskStore.update(
            taskId = taskId,
            status = AgentTaskStatus.COMPLETED,
            queueState = AgentTaskQueueState.TERMINAL,
            summary = summary,
            cancelCapability = false,
        )

    suspend fun fail(taskId: String, message: String, code: String = "failed"): AgentTaskSnapshot? =
        taskStore.update(
            taskId = taskId,
            status = AgentTaskStatus.FAILED,
            queueState = AgentTaskQueueState.TERMINAL,
            error = message,
            lastErrorCode = code,
            cancelCapability = false,
        )

    suspend fun cancel(taskId: String): AgentTaskSnapshot = taskStore.cancel(taskId)

    suspend fun retry(taskId: String): AgentTaskSnapshot = taskStore.retry(taskId)

    suspend fun cleanup(taskId: String, deletePrivateOutput: Boolean = false): Boolean =
        taskStore.cleanup(taskId, deletePrivateOutput)

    suspend fun reconcileOnStartup(): List<AgentTaskSnapshot> = taskStore.reconcileOnStartup()

    fun list(type: String? = null, status: AgentTaskStatus? = null): List<AgentTaskSnapshot> =
        taskStore.list(type = type, status = status)

    fun read(taskId: String): AgentTaskSnapshot? = taskStore.read(taskId)

    fun status(): AgentRuntimeStatus {
        val tasks = taskStore.list()
        return AgentRuntimeStatus(
            total = tasks.size,
            queued = tasks.count { it.status == AgentTaskStatus.QUEUED },
            running = tasks.count { it.status == AgentTaskStatus.RUNNING },
            completed = tasks.count { it.status == AgentTaskStatus.COMPLETED },
            failed = tasks.count { it.status == AgentTaskStatus.FAILED },
            cancelled = tasks.count { it.status == AgentTaskStatus.CANCELLED },
            timedOut = tasks.count { it.status == AgentTaskStatus.TIMED_OUT },
            interrupted = tasks.count { it.status == AgentTaskStatus.INTERRUPTED },
            byType = tasks.groupingBy { it.type }.eachCount(),
        )
    }
}

data class AgentRuntimeStatus(
    val total: Int,
    val queued: Int,
    val running: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val timedOut: Int,
    val interrupted: Int,
    val byType: Map<String, Int>,
)

interface AgentTaskAdapter {
    val type: String
    val maxConcurrency: Int
    suspend fun cancel(taskId: String): Boolean
}
