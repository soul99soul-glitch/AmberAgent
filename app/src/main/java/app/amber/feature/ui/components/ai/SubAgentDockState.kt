package app.amber.feature.ui.components.ai

import app.amber.core.infra.AppScope
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentRun
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.subagent.SUB_AGENT_DOCK_AUTO_HIDE_NEVER
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import java.io.File
import kotlin.uuid.Uuid

/**
 * Identifies one visible subagent generation. A thread id can be reused by a followup, so a
 * task id alone is deliberately not enough to identify a dismiss action or a retained terminal
 * row.
 */
data class SubAgentDockRunKey(
    val taskId: String,
    val createdAtMs: Long,
)

/**
 * The dock's display status. This preserves task-store QUEUED separately instead of relabeling
 * it as a running subagent. Every other value maps one-to-one to a real runtime status.
 */
enum class SubAgentDockStatus {
    QUEUED,
    RUNNING,
    APPROVAL_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED,
    ;

    val keepsDockVisible: Boolean
        get() = this == QUEUED || this == RUNNING || this == APPROVAL_REQUIRED

    val canDismiss: Boolean
        get() = !keepsDockVisible
}

/** Safe, presentation-only task data. It intentionally excludes objective, summary, errors and transcript data. */
data class SubAgentDockTask(
    val key: SubAgentDockRunKey,
    val title: String,
    val sourceConversationId: Uuid?,
    val status: SubAgentDockStatus,
    /** Start of this visible generation, projected from the task snapshot's createdAtMs. */
    val startedAtMs: Long,
    /** Null while the current generation has not reached a terminal state. */
    val finishedAtMs: Long? = null,
)

data class SubAgentDockUiState(
    val tasks: List<SubAgentDockTask> = emptyList(),
    /** User-facing visibility gate; tasks remain observed while this is false. */
    val enabled: Boolean = true,
)

/**
 * Process-wide observer for the compact subagent dock.
 *
 * This stays in [AppScope], independently of ChatVM and individual conversation composition.
 * It observes only task metadata and exact live lifecycle state; it never reads task objectives,
 * summaries, errors, or transcripts into this UI state.
 */
class SubAgentDockState(
    private val agentTaskStore: AgentTaskStore,
    private val subAgentManager: SubAgentManager,
    private val appScope: AppScope,
    private val settingsStore: SettingsAggregator,
) {
    private val tracker = SubAgentDockTracker()
    private val collectors = mutableMapOf<String, RunCollector>()
    private val liveRuns = mutableMapOf<String, SubAgentRun?>()
    /** Approval survives in ThreadGraph while AgentTaskStore recovers its legacy RUNNING row. */
    private val persistedStatuses = mutableMapOf<SubAgentDockRunKey, SubAgentRunStatus>()
    private val persistedStatusJobs = mutableMapOf<SubAgentDockRunKey, Job>()
    private val persistedStatusResolved = mutableSetOf<SubAgentDockRunKey>()
    // StateFlow conflates. Capture the disk-loaded baseline before collecting task updates so a
    // generation registered after recovery still qualifies if it reaches terminal state first.
    private val startupRunKeys = mutableSetOf<SubAgentDockRunKey>()
    private val processRunKeys = mutableSetOf<SubAgentDockRunKey>()
    private var taskSnapshots: List<AgentTaskSnapshot> = emptyList()
    private var dockEnabled: Boolean = settingsStore.settingsFlow.value.agentRuntime.subAgent.dockEnabled
    private var autoHideAfterMs: Long = settingsStore.settingsFlow.value.agentRuntime.subAgent.dockAutoHideAfterMs
        .coerceAtLeast(SUB_AGENT_DOCK_AUTO_HIDE_NEVER)
    private var detailsOpenKey: SubAgentDockRunKey? = null
    private var autoHideJob: Job? = null

    private val _uiState = MutableStateFlow(SubAgentDockUiState())
    val uiState: StateFlow<SubAgentDockUiState> = _uiState.asStateFlow()

    init {
        appScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                val subAgent = settings.agentRuntime.subAgent
                dockEnabled = subAgent.dockEnabled
                autoHideAfterMs = subAgent.dockAutoHideAfterMs.coerceAtLeast(SUB_AGENT_DOCK_AUTO_HIDE_NEVER)
                publishAndReschedule()
            }
        }
        appScope.launch {
            val startupBaseline = agentTaskStore.awaitReady()
                .filter { it.type == SUBAGENT_TASK_TYPE }
            startupRunKeys += startupBaseline.map { it.toDockRunKey() }
            processRunKeys += startupBaseline
                .filter { it.status.keepsDockObserved }
                .map { it.toDockRunKey() }
            agentTaskStore.tasksFlow.collect { snapshots ->
                taskSnapshots = snapshots.filter { it.type == SUBAGENT_TASK_TYPE }
                markProcessRunKeys()
                reconcileRunCollectors()
                reconcilePersistedStatuses()
                publishAndReschedule()
            }
        }
    }

    /**
     * UI-only dismissal. This never writes to [AgentTaskStore], cancels no work, and is ignored
     * for a running or approval-waiting generation.
     */
    fun dismiss(key: SubAgentDockRunKey) {
        tracker.dismiss(key)
        publishAndReschedule()
    }

    /**
     * Hides every currently tracked generation from the dock without cancelling or mutating any
     * task. A followup with a new [SubAgentDockRunKey] is admitted normally.
     */
    fun dismissAll() {
        tracker.dismissAll()
        publishAndReschedule()
    }

    /**
     * Keeps an opened details sheet out of terminal auto-hide. Passing null releases the guard;
     * an already-expired row is then hidden on the next synchronous publish.
     */
    fun keepDetailsOpen(key: SubAgentDockRunKey?) {
        detailsOpenKey = key
        publishAndReschedule()
    }

    /**
     * Cold details stream for the explicitly opened task sheet. The compact dock never collects
     * this path, so live parts and bounded transcript reads stay out of the global metadata rail.
     */
    fun detailsFlow(key: SubAgentDockRunKey, runRoot: File): Flow<SubAgentDockDetails> = flow {
        agentTaskStore.awaitReady()
        emitAll(
            subAgentDockDetailsFlow(
                agentTaskStore = agentTaskStore,
                subAgentManager = subAgentManager,
                key = key,
                runRoot = runRoot,
            )
        )
    }

    private fun reconcileRunCollectors() {
        val activeSnapshots = taskSnapshots.filter { it.status.keepsDockObserved }
        val activeKeys = activeSnapshots.associate { snapshot ->
            snapshot.taskId to snapshot.toDockRunKey()
        }

        collectors.entries.toList().forEach { (taskId, collector) ->
            if (activeKeys[taskId] != collector.key) {
                collector.job?.cancel()
                collectors.remove(taskId)
                // A fresh generation may reuse the task id. Its flow must not borrow the prior
                // generation's live status while the new collector is being established.
                val replacement = activeKeys[taskId]
                if (replacement != null && replacement.createdAtMs != collector.key.createdAtMs) {
                    liveRuns.remove(taskId)
                }
            }
        }

        val snapshotTaskIds = taskSnapshots.mapTo(mutableSetOf()) { it.taskId }
        liveRuns.keys.retainAll(snapshotTaskIds)

        activeSnapshots.forEach { snapshot ->
            val key = snapshot.toDockRunKey()
            if (collectors[snapshot.taskId]?.key == key) return@forEach

            val collector = RunCollector(key)
            collectors[snapshot.taskId] = collector
            collector.job = appScope.launch {
                subAgentManager.runStateFlow(snapshot.taskId).collect { run ->
                    // A followup can replace a task id while an older StateFlow collector is
                    // still unwinding. Only the collector owning this generation may publish.
                    if (collectors[snapshot.taskId]?.key != key) return@collect
                    liveRuns[snapshot.taskId] = run
                    publishAndReschedule()
                }
            }
        }
    }

    /**
     * AgentTaskStore has no APPROVAL_REQUIRED value and restores an approval wait as
     * INTERRUPTED. Read the existing thread-graph status only for that exact generation so a
     * previous thread result cannot resurrect a replaced Dock row.
     */
    private fun reconcilePersistedStatuses() {
        val currentKeys = taskSnapshots.mapTo(mutableSetOf()) { it.toDockRunKey() }
        persistedStatuses.keys.retainAll(currentKeys)
        persistedStatusResolved.retainAll(currentKeys)
        persistedStatusJobs.entries.toList().forEach { (key, job) ->
            if (key !in currentKeys) {
                job.cancel()
                persistedStatusJobs.remove(key)
            }
        }

        taskSnapshots
            .filter { it.status == AgentTaskStatus.INTERRUPTED }
            .forEach { snapshot ->
                val key = snapshot.toDockRunKey()
                if (key in persistedStatusResolved || key in persistedStatusJobs) return@forEach
                persistedStatusJobs[key] = appScope.launch {
                    val state = try {
                        subAgentManager.persistedState(key.taskId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                    if (
                        state?.status == SubAgentRunStatus.APPROVAL_REQUIRED &&
                            state.updatedAtMs >= key.createdAtMs &&
                            taskSnapshots.any { it.toDockRunKey() == key }
                    ) {
                        persistedStatuses[key] = SubAgentRunStatus.APPROVAL_REQUIRED
                    }
                    persistedStatusResolved += key
                    persistedStatusJobs.remove(key)
                    publishAndReschedule()
                }
            }
    }

    private fun publishAndReschedule() {
        tracker.dismissExpired(
            nowMs = System.currentTimeMillis(),
            autoHideAfterMs = autoHideAfterMs,
            protectedKey = detailsOpenKey,
        )
        _uiState.value = tracker.reduce(
            snapshots = taskSnapshots,
            liveRuns = liveRuns,
            processRunKeys = processRunKeys,
            persistedStatuses = persistedStatuses,
        ).copy(enabled = dockEnabled)
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = null
        val deadline = tracker.nextAutoDismissAt(
            nowMs = System.currentTimeMillis(),
            autoHideAfterMs = autoHideAfterMs,
            protectedKey = detailsOpenKey,
        ) ?: return
        autoHideJob = appScope.launch {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(1L))
            tracker.dismissExpired(
                nowMs = System.currentTimeMillis(),
                autoHideAfterMs = autoHideAfterMs,
                protectedKey = detailsOpenKey,
            )
            publishAndReschedule()
        }
    }

    private fun markProcessRunKeys() {
        val currentKeys = taskSnapshots.mapTo(mutableSetOf()) { it.toDockRunKey() }
        processRunKeys.retainAll(currentKeys)
        taskSnapshots.forEach { snapshot ->
            val key = snapshot.toDockRunKey()
            if (snapshot.status.keepsDockObserved || key !in startupRunKeys) {
                processRunKeys += key
            }
        }
    }

    private class RunCollector(
        val key: SubAgentDockRunKey,
        var job: Job? = null,
    )

    private companion object {
        const val SUBAGENT_TASK_TYPE = "subagent"
    }
}

/**
 * Pure lifecycle reducer behind [SubAgentDockState]. Keeping it side-effect-free gives the UI a
 * small test seam while the production state holder owns the global Flow subscriptions.
 */
internal class SubAgentDockTracker {
    private val tracked = linkedMapOf<SubAgentDockRunKey, SubAgentDockTask>()
    private val dismissed = mutableSetOf<SubAgentDockRunKey>()

    fun reduce(
        snapshots: List<AgentTaskSnapshot>,
        liveRuns: Map<String, SubAgentRun?>,
        /** Current-process runs admitted by the state holder's pre-collection baseline. */
        processRunKeys: Set<SubAgentDockRunKey> = emptySet(),
        /** Persisted lifecycle overrides keyed to one exact task generation. */
        persistedStatuses: Map<SubAgentDockRunKey, SubAgentRunStatus> = emptyMap(),
    ): SubAgentDockUiState {
        val subagentSnapshots = snapshots.filter { it.type == SUBAGENT_TASK_TYPE }
        val snapshotTaskIds = subagentSnapshots.mapTo(mutableSetOf()) { it.taskId }

        // A task explicitly removed from the store should not survive as a stale dock card.
        tracked.keys.retainAll { it.taskId in snapshotTaskIds }
        dismissed.retainAll { it.taskId in snapshotTaskIds }

        subagentSnapshots.forEach { snapshot ->
            val key = snapshot.toDockRunKey()
            val live = liveRuns[snapshot.taskId]
                ?.takeIf { it.matches(snapshot) }
            // The durable task row is authoritative once terminal. A delayed old in-memory
            // RUNNING state must never resurrect a completed/interrupted task card.
            val persisted = persistedStatuses[key]
            val status = when {
                snapshot.status.keepsDockObserved ->
                    live?.status?.toDockStatus() ?: snapshot.status.toDockStatus()
                snapshot.status == AgentTaskStatus.INTERRUPTED &&
                    persisted == SubAgentRunStatus.APPROVAL_REQUIRED ->
                    SubAgentDockStatus.APPROVAL_REQUIRED
                else -> snapshot.status.toDockStatus()
            }

            // A task-store row is replaced in place for a followup. Even if a very short
            // generation reaches terminal before this collector sees its active emission, do
            // not leave the older generation's terminal card behind under the reused id.
            tracked.keys
                .filter { it.taskId == snapshot.taskId && it != key }
                .toList()
                .forEach {
                    tracked.remove(it)
                    dismissed.remove(it)
                }

            if (status.keepsDockVisible) {
                tracked[key] = SubAgentDockTask(
                    key = key,
                    title = snapshot.title,
                    sourceConversationId = snapshot.sourceConversationId.toUuidOrNull(),
                    status = status,
                    startedAtMs = snapshot.createdAtMs,
                    // An active status has no real finish timestamp.
                    finishedAtMs = null,
                )
            } else {
                val previous = tracked[key]
                if (previous == null && key !in processRunKeys) return@forEach
                if (previous == null) {
                    tracked[key] = SubAgentDockTask(
                        key = key,
                        title = snapshot.title,
                        sourceConversationId = snapshot.sourceConversationId.toUuidOrNull(),
                        status = status,
                        startedAtMs = snapshot.createdAtMs,
                        finishedAtMs = live
                            ?.takeIf { !it.status.keepsDockVisible }
                            ?.updatedAtMs
                            ?: snapshot.updatedAtMs,
                    )
                    return@forEach
                }
                tracked[key] = previous.copy(
                    title = snapshot.title.ifBlank { previous.title },
                    sourceConversationId = snapshot.sourceConversationId.toUuidOrNull(),
                    status = status,
                    // Freeze the first terminal timestamp. Later task-summary writes must never
                    // make an already completed duration continue to grow.
                    // A stale RUNNING snapshot is never a finish time.
                    finishedAtMs = previous.finishedAtMs
                        ?: live
                            ?.takeIf { !it.status.keepsDockVisible }
                            ?.updatedAtMs
                        ?: snapshot.updatedAtMs,
                )
            }
        }

        return SubAgentDockUiState(
            tasks = tracked.values
                .asSequence()
                .filter { it.key !in dismissed }
                .sortedWith(
                    compareByDescending<SubAgentDockTask> { it.status.keepsDockVisible }
                        .thenByDescending { it.startedAtMs },
                )
                .toList(),
        )
    }

    fun dismiss(key: SubAgentDockRunKey) {
        tracked[key]
            ?.takeIf { it.status.canDismiss }
            ?.let { dismissed += key }
    }

    /** Hide all current generations in the UI while leaving their durable/runtime state intact. */
    fun dismissAll() {
        dismissed += tracked.keys
    }

    /** Hide terminal rows whose first real terminal timestamp has passed the configured delay. */
    fun dismissExpired(
        nowMs: Long,
        autoHideAfterMs: Long,
        protectedKey: SubAgentDockRunKey? = null,
    ): Boolean {
        if (autoHideAfterMs <= SUB_AGENT_DOCK_AUTO_HIDE_NEVER) return false
        val before = dismissed.size
        tracked.values
            .asSequence()
            .filter { it.key != protectedKey && it.status.canDismiss }
            .filter { task ->
                val finishedAtMs = task.finishedAtMs ?: return@filter false
                nowMs >= finishedAtMs && nowMs - finishedAtMs >= autoHideAfterMs
            }
            .forEach { dismissed += it.key }
        return dismissed.size != before
    }

    fun nextAutoDismissAt(
        nowMs: Long,
        autoHideAfterMs: Long,
        protectedKey: SubAgentDockRunKey? = null,
    ): Long? {
        if (autoHideAfterMs <= SUB_AGENT_DOCK_AUTO_HIDE_NEVER) return null
        return tracked.values
            .asSequence()
            .filter { it.key != protectedKey && it.status.canDismiss && it.key !in dismissed }
            .mapNotNull { task ->
                task.finishedAtMs?.let { finishedAtMs ->
                    val deadline = if (finishedAtMs > Long.MAX_VALUE - autoHideAfterMs) {
                        Long.MAX_VALUE
                    } else {
                        finishedAtMs + autoHideAfterMs
                    }
                    deadline.coerceAtLeast(nowMs)
                }
            }
            .minOrNull()
    }

    private companion object {
        const val SUBAGENT_TASK_TYPE = "subagent"
    }
}

internal fun SubAgentDockTask.elapsedAt(nowMs: Long): Long =
    ((finishedAtMs ?: nowMs) - startedAtMs).coerceAtLeast(0L)

private val AgentTaskStatus.keepsDockObserved: Boolean
    get() = this == AgentTaskStatus.QUEUED || this == AgentTaskStatus.RUNNING

private fun AgentTaskSnapshot.toDockRunKey(): SubAgentDockRunKey =
    SubAgentDockRunKey(taskId = taskId, createdAtMs = createdAtMs)

private fun SubAgentRun.matches(snapshot: AgentTaskSnapshot): Boolean =
    runId == snapshot.taskId &&
        parentConversationId.toString() == snapshot.sourceConversationId &&
        updatedAtMs >= snapshot.createdAtMs

private fun String?.toUuidOrNull(): Uuid? =
    this?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }

private fun AgentTaskStatus.toDockStatus(): SubAgentDockStatus = when (this) {
    AgentTaskStatus.QUEUED -> SubAgentDockStatus.QUEUED
    AgentTaskStatus.RUNNING -> SubAgentDockStatus.RUNNING
    AgentTaskStatus.COMPLETED -> SubAgentDockStatus.COMPLETED
    AgentTaskStatus.FAILED -> SubAgentDockStatus.FAILED
    AgentTaskStatus.CANCELLED -> SubAgentDockStatus.CANCELLED
    AgentTaskStatus.TIMED_OUT -> SubAgentDockStatus.TIMED_OUT
    AgentTaskStatus.INTERRUPTED -> SubAgentDockStatus.INTERRUPTED
}

private fun SubAgentRunStatus.toDockStatus(): SubAgentDockStatus = when (this) {
    SubAgentRunStatus.RUNNING -> SubAgentDockStatus.RUNNING
    SubAgentRunStatus.APPROVAL_REQUIRED -> SubAgentDockStatus.APPROVAL_REQUIRED
    SubAgentRunStatus.COMPLETED -> SubAgentDockStatus.COMPLETED
    SubAgentRunStatus.FAILED -> SubAgentDockStatus.FAILED
    SubAgentRunStatus.CANCELLED -> SubAgentDockStatus.CANCELLED
    SubAgentRunStatus.TIMED_OUT -> SubAgentDockStatus.TIMED_OUT
    SubAgentRunStatus.INTERRUPTED -> SubAgentDockStatus.INTERRUPTED
}

private val SubAgentRunStatus.keepsDockVisible: Boolean
    get() = this == SubAgentRunStatus.RUNNING || this == SubAgentRunStatus.APPROVAL_REQUIRED
