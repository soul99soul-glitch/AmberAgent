package app.amber.core.agent.runtime.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRegistry
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AgentRunner"

class InProcessAgentRunner(
    private val registry: AgentRegistry,
    private val eventStore: AgentEventStore,
    private val runScopeFactory: (AgentRunId, AgentInput) -> app.amber.core.agent.runtime.RunScope = { id, _ ->
        LegacyRunScope(runId = id)
    },
    /**
     * P1-e: 账本写失败的用户可见错误回调（iOS 已走 publishUserVisibleError；
     * 默认空实现保持旧调用方零改动，注册点接线到 Android 的 ChatService.addError）。
     * 账本说谎会让并发计数/恢复语义失真，写失败必须被用户看到，不能只 Log.w 吞掉。
     */
    private val onLedgerError: (AgentRunId, Throwable) -> Unit = { _, _ -> },
) : AgentRunner {

    private val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val snapshots = ConcurrentHashMap<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()
    private val jobs = ConcurrentHashMap<AgentRunId, Job>()

    override fun <I : AgentInput> launch(
        descriptorId: AgentDescriptorId,
        input: I,
    ): Result<AgentRunHandle> {
        val registered = registry.resolve(descriptorId)
            ?: return Result.failure(IllegalArgumentException("No agent registered for $descriptorId"))

        val runId = AgentRunId.new()
        val now = System.currentTimeMillis()
        val handle = AgentRunHandle(runId, descriptorId)

        val snapshot = MutableStateFlow(
            AgentRunSnapshot(
                runId = runId,
                parentRunId = null,
                descriptorId = descriptorId,
                status = AgentRunStatus.RUNNING,
                startedAt = now,
                finishedAt = null,
            )
        )
        snapshots[runId] = snapshot

        val job = runnerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val record = AgentRunRecord(
                runId = runId.value,
                parentRunId = null,
                agentDescriptorId = descriptorId.value,
                agentVersion = registered.descriptor.version,
                conversationId = null,
                messageNodeId = null,
                producesMessageId = null,
                assistantId = null,
                status = AgentRunStatus.RUNNING,
                inputDigest = input.hashCode().toString(),
                inputSnapshotRef = null,
                inputSchemaVersion = 1,
                startedAt = now,
                finishedAt = null,
                interruptedReason = null,
            )
            val startError = try {
                if (withContext(NonCancellable) { eventStore.startRun(record) }) {
                    null
                } else {
                    IllegalStateException("Run ${runId.value} could not be persisted")
                }
            } catch (error: Exception) {
                error
            }
            if (startError != null) {
                runCatching { Log.w(TAG, "Failed to persist run record", startError) }
                onLedgerError(runId, startError)
                snapshot.value = snapshot.value.copy(
                    status = AgentRunStatus.FAILED,
                    finishedAt = System.currentTimeMillis(),
                )
                return@launch
            }

            try {
                // Keep launch asynchronous after the durable owner exists.
                yield()
                coroutineContext.ensureActive()
                @Suppress("UNCHECKED_CAST")
                val agent = registered.factory() as app.amber.core.agent.runtime.Agent<I, *>
                val runScope = runScopeFactory(runId, input)
                agent.handler.handle(input, runScope)

                val finishedAt = System.currentTimeMillis()
                settleRun(
                    runId = runId,
                    snapshot = snapshot,
                    record = record,
                    status = AgentRunStatus.COMPLETED,
                    detail = null,
                    at = finishedAt,
                )
                runCatching { Log.i(TAG, "Run $runId completed (${finishedAt - now}ms)") }
            } catch (e: CancellationException) {
                val finishedAt = System.currentTimeMillis()
                settleRun(
                    runId = runId,
                    snapshot = snapshot,
                    record = record,
                    status = AgentRunStatus.CANCELLED,
                    detail = "cancelled",
                    at = finishedAt,
                )
                throw e
            } catch (e: Exception) {
                val finishedAt = System.currentTimeMillis()
                settleRun(
                    runId = runId,
                    snapshot = snapshot,
                    record = record,
                    status = AgentRunStatus.FAILED,
                    detail = e.message?.take(500),
                    at = finishedAt,
                )
                runCatching { Log.e(TAG, "Run $runId failed", e) }
            }
        }
        jobs[runId] = job

        return Result.success(handle)
    }

    override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> {
        return snapshots[runId] ?: MutableStateFlow(
            AgentRunSnapshot(
                runId = runId,
                parentRunId = null,
                descriptorId = AgentDescriptorId("unknown"),
                status = AgentRunStatus.INTERRUPTED,
                startedAt = 0,
                finishedAt = null,
            )
        )
    }

    override fun cancel(runId: AgentRunId) {
        jobs[runId]?.cancel()
    }

    override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> {
        return snapshots.values
            .map { it.value }
            .filter { it.status.isRecoverable }
    }

    /** Publish terminal state only after the durable CAS owns it. */
    private suspend fun settleRun(
        runId: AgentRunId,
        snapshot: MutableStateFlow<AgentRunSnapshot>,
        record: AgentRunRecord,
        status: AgentRunStatus,
        detail: String?,
        at: Long,
    ) = withContext(NonCancellable) {
        val expectedStatuses = when (status) {
            AgentRunStatus.COMPLETED -> listOf(AgentRunStatus.RUNNING)
            else -> listOf(
                AgentRunStatus.RUNNING,
                AgentRunStatus.AWAITING_PERMISSION,
                AgentRunStatus.RECOVERY_PENDING,
            ).filter { it.canTransitionTo(status) }
        }
        var transitionError: Throwable? = null
        for (expectedStatus in expectedStatuses) {
            try {
                if (eventStore.transitionRun(
                        runId = runId,
                        expectedStatus = expectedStatus,
                        status = status,
                        inputSnapshotRef = record.inputSnapshotRef,
                        detail = detail,
                        at = at,
                    )
                ) {
                    snapshot.value = snapshot.value.copy(status = status, finishedAt = at)
                    return@withContext
                }
            } catch (error: Exception) {
                transitionError = error
                break
            }
        }

        val persistedResult = runCatching { eventStore.getRun(runId) }
        val persisted = persistedResult.getOrNull()
        if (persisted?.status?.isTerminal == true) {
            snapshot.value.copy(status = persisted.status, finishedAt = persisted.finishedAt)
                .also { snapshot.value = it }
            return@withContext
        }

        val failure = transitionError
            ?: IllegalStateException("Run ${runId.value} could not settle as ${status.wireName}")
        runCatching { Log.w(TAG, "Failed to settle run as ${status.wireName}", failure) }
        persistedResult.exceptionOrNull()?.let {
            runCatching { Log.w(TAG, "Failed to reconcile run ${runId.value}", it) }
        }
        onLedgerError(runId, failure)
        snapshot.value = snapshot.value.copy(
            status = AgentRunStatus.RECOVERY_PENDING,
            finishedAt = null,
        )
    }
}
