package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.StateFlow

interface Surface<STATE, COMMAND> {
    val supportedAgents: Set<AgentDescriptorId>
    fun stateFor(runId: AgentRunId): StateFlow<STATE>
    suspend fun dispatch(command: COMMAND)
}
