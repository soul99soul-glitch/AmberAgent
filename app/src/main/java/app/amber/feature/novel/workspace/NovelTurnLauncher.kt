package app.amber.feature.novel.workspace

import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Caller-side half of the novel kernel surface: registers the turn payload,
 * launches the turn as an [AgentRunner] run, and hands back the live event
 * flow. Cancelling collection cancels the run (the runtime's
 * rollback-on-cancel hygiene then runs inside the handler, exactly as a
 * cancelled direct `runTurn` collect did before).
 */
class NovelTurnLauncher(
    private val agentRunner: AgentRunner,
    private val payloads: NovelTurnPayloads,
) {

    class NovelTurnHandle(
        val runId: AgentRunId,
        val events: Flow<NovelWorkspaceRuntime.TurnEvent>,
        private val agentRunner: AgentRunner,
    ) {
        /** Terminal settle of the underlying run (cancellation cleanup done). */
        suspend fun awaitTerminal(): RunStatus =
            agentRunner.observe(runId).first { it.status.isTerminal }.status
    }

    fun launch(
        request: NovelWorkspaceRuntime.TurnRequest,
        runtime: NovelWorkspaceRuntime,
    ): NovelTurnHandle {
        val turnRunId = AgentRunId.new()
        val events = Channel<NovelWorkspaceRuntime.TurnEvent>(Channel.UNLIMITED)
        payloads.register(turnRunId.value, NovelTurnPayloads.Payload(runtime, request, events))
        val launched = agentRunner.launch(
            NovelTurnDescriptor.ID,
            request.toInput(),
            requestedRunId = turnRunId,
        )
        if (launched.isFailure) {
            payloads.remove(turnRunId.value)
            events.trySend(
                NovelWorkspaceRuntime.TurnEvent.Failed(
                    launched.exceptionOrNull()?.message ?: "novel turn agent 未注册",
                ),
            )
            events.close()
        }
        val eventFlow = events.receiveAsFlow().onCompletion { cause ->
            payloads.remove(turnRunId.value)
            // A cancelled collector walked away mid-turn: cancel the run so
            // the handler's cancellation path (canon rollback) runs.
            if (cause is CancellationException) agentRunner.cancel(turnRunId)
        }
        return NovelTurnHandle(turnRunId, eventFlow, agentRunner)
    }

    private fun NovelWorkspaceRuntime.TurnRequest.toInput() = NovelTurnInput(
        projectPath = projectDirectory.absolutePath,
        branchId = branchId,
        branchSlug = branchSlug,
        userText = userText,
        maxSteps = maxSteps,
        autoApproveCanon = autoApproveCanon,
        ownerJobId = ownerJobId,
        ownerExecutionId = ownerExecutionId,
    )
}
