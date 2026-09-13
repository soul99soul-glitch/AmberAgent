package app.amber.feature.ui.components.ai

import app.amber.core.infra.AppScope
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentRun
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
) {
    private val tracker = SubAgentDockTracker()
    private val collectors = mutableMapOf<String, RunCollector>()
    private val liveRuns = mutableMapOf<String, SubAgentRun?>()
    // StateFlow conflates. Capture the already-loaded task-store baseline synchronously so a
    // generation registered after this singleton exists still qualifies for the dock even when
    // its register → terminal transition completes before the collector's first reduction.
    private val startupBaseline = agentTaskStore.tasksFlow.value
        .filter { it.type == SUBAGENT_TASK_TYPE }
        .map { it.toDockRunKey() to it.status.keepsDockObserved }
    private val startupRunKeys = startupBaseline.mapTo(mutableSetOf()) { it.first }
    private val processRunKeys = startupBaseline
        .filter { it.second }
        .mapTo(mutableSetOf()) { it.first }
    private var taskSnapshots: List<AgentTaskSnapshot> = emptyList()

    private val _uiState = MutableStateFlow(SubAgentDockUiState())
    val uiState: StateFlow<SubAgentDockUiState> = _uiState.asStateFlow()

    init {
        appScope.launch {
            agentTaskStore.tasksFlow.collect { snapshots ->
                taskSnapshots = snapshots.filter { it.type == SUBAGENT_TASK_TYPE }
                markProcessRunKeys()
                reconcileRunCollectors()
                publish()
            }
        }
    }

    /**
     * UI-only dismissal. This never writes to [AgentTaskStore], cancels no work, and is ignored
     * for a running or approval-waiting generation.
     */
    fun dismiss(key: SubAgentDockRunKey) {
        tracker.dismiss(key)
        publish()
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
                    publish()
                }
            }
        }
    }

    private fun publish() {
        _uiState.value = tracker.reduce(
            snapshots = taskSnapshots,
            liveRuns = liveRuns,
            processRunKeys = processRunKeys,
        )
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
            val status = if (snapshot.status.keepsDockObserved) {
                live?.status?.toDockStatus() ?: snapshot.status.toDockStatus()
            } else {
                snapshot.status.toDockStatus()
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
                    // If the manager's matching *terminal* live snapshot arrives after the
                    // durable task row, prefer its exact generation finish time over that
                    // coarse fallback. A stale RUNNING snapshot is never a finish time.
                    finishedAtMs = live
                        ?.takeIf { !it.status.keepsDockVisible }
                        ?.updatedAtMs
                        ?: previous.finishedAtMs
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
