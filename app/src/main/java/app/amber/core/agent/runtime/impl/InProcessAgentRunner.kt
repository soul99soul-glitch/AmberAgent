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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AgentRunner"

class InProcessAgentRunner(
    private val registry: AgentRegistry,
    private val eventStore: AgentEventStore,
    private val runScopeFactory: (AgentRunId, AgentInput) -> app.amber.core.agent.runtime.RunScope = { id, _ ->
        LegacyRunScope(runId = id)
    },
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

        val job = runnerScope.launch {
            val record = AgentRunRecord(
                runId = runId.value,
                parentRunId = null,
                agentDescriptorId = descriptorId.value,
                agentVersion = registered.descriptor.version,
                conversationId = null,
                messageNodeId = null,
                producesMessageId = null,
                assistantId = null,
                status = "running",
                inputDigest = input.hashCode().toString(),
                inputSnapshotRef = null,
                inputSchemaVersion = 1,
                startedAt = now,
                finishedAt = null,
                interruptedReason = null,
            )
            try {
                eventStore.appendRun(record)
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "Failed to persist run record", e) }
            }

            try {
                @Suppress("UNCHECKED_CAST")
                val agent = registered.factory() as app.amber.core.agent.runtime.Agent<I, *>
                val runScope = runScopeFactory(runId, input)
                agent.handler.handle(input, runScope)

                val finishedAt = System.currentTimeMillis()
                snapshot.value = snapshot.value.copy(
                    status = AgentRunStatus.COMPLETED,
                    finishedAt = finishedAt,
                )
                try {
                    eventStore.appendRun(record.copy(
                        status = "completed",
                        finishedAt = finishedAt,
                    ))
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Failed to update run record", e) }
                }
                runCatching { Log.i(TAG, "Run $runId completed (${finishedAt - now}ms)") }
            } catch (e: CancellationException) {
                val finishedAt = System.currentTimeMillis()
                snapshot.value = snapshot.value.copy(
                    status = AgentRunStatus.CANCELLED,
                    finishedAt = finishedAt,
                )
                try {
                    eventStore.markInterrupted(runId, "cancelled")
                } catch (ex: Exception) {
                    runCatching { Log.w(TAG, "Failed to mark run interrupted", ex) }
                }
                throw e
            } catch (e: Exception) {
                val finishedAt = System.currentTimeMillis()
                snapshot.value = snapshot.value.copy(
                    status = AgentRunStatus.FAILED,
                    finishedAt = finishedAt,
                )
                try {
                    eventStore.appendRun(record.copy(
                        status = "failed",
                        finishedAt = finishedAt,
                        interruptedReason = e.message?.take(500),
                    ))
                } catch (ex: Exception) {
                    runCatching { Log.w(TAG, "Failed to update run record on failure", ex) }
                }
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
        snapshots[runId]?.let { snapshot ->
            snapshot.value = snapshot.value.copy(
                status = AgentRunStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> {
        return snapshots.values
            .map { it.value }
            .filter { it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.AWAITING_PERMISSION }
    }
}
