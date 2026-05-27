package app.amber.core.agent.runtime.impl

import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRegistry
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class InProcessAgentRunner(
    private val registry: AgentRegistry,
    private val eventStore: AgentEventStore,
) : AgentRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = ConcurrentHashMap<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()

    override fun <I : AgentInput> launch(
        descriptorId: AgentDescriptorId,
        input: I,
    ): Result<AgentRunHandle> {
        val registered = registry.resolve(descriptorId)
            ?: return Result.failure(IllegalArgumentException("No agent registered for $descriptorId"))

        val runId = AgentRunId.new()
        val handle = AgentRunHandle(runId, descriptorId)
        val snapshot = MutableStateFlow(
            AgentRunSnapshot(
                runId = runId,
                parentRunId = null,
                descriptorId = descriptorId,
                status = AgentRunStatus.RUNNING,
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
            )
        )
        runs[runId] = snapshot

        scope.launch {
            try {
                @Suppress("UNCHECKED_CAST")
                val agent = registered.factory() as app.amber.core.agent.runtime.Agent<I, *>
                val runScope = LegacyRunScope(runId = runId)
                agent.handler.handle(input, runScope)
                snapshot.value = snapshot.value.copy(
                    status = AgentRunStatus.COMPLETED,
                    finishedAt = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                snapshot.value = snapshot.value.copy(
                    status = AgentRunStatus.FAILED,
                    finishedAt = System.currentTimeMillis(),
                )
            }
        }

        return Result.success(handle)
    }

    override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> {
        return runs[runId] ?: MutableStateFlow(
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
        runs[runId]?.let { snapshot ->
            snapshot.value = snapshot.value.copy(
                status = AgentRunStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> {
        return runs.values
            .map { it.value }
            .filter { it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.AWAITING_PERMISSION }
    }
}
