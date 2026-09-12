package app.amber.feature.novel.workspace

import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val scope: CoroutineScope,
) {

    class NovelTurnHandle(
        val runId: AgentRunId,
        val events: Flow<NovelWorkspaceRuntime.TurnEvent>,
        private val agentRunner: AgentRunner,
        private val completion: kotlinx.coroutines.CompletableDeferred<Unit>,
        private val settleNoHandler: (AgentRunSnapshot) -> Unit,
    ) {
        /** Terminal settle of the underlying run (cancellation cleanup done). */
        suspend fun awaitTerminal(): RunStatus {
            val terminal = agentRunner.observe(runId).first { it.status.isTerminal }
            settleNoHandler(terminal)
            completion.await()
            return terminal.status
        }
    }

    fun launch(
        request: NovelWorkspaceRuntime.TurnRequest,
        runtime: NovelWorkspaceRuntime,
    ): NovelTurnHandle {
        val turnRunId = AgentRunId.new()
        val events = Channel<NovelWorkspaceRuntime.TurnEvent>(Channel.UNLIMITED)
        val payload = NovelTurnPayloads.Payload(runtime, request, events)
        payloads.register(turnRunId.value, payload)
        val launched = agentRunner.launch(
            NovelTurnDescriptor.ID,
            request.toInput(),
            requestedRunId = turnRunId,
        )
        if (launched.isFailure) {
            payload.claimNoHandler()
            payloads.remove(turnRunId.value)
            payload.completion.complete(Unit)
            events.trySend(
                NovelWorkspaceRuntime.TurnEvent.Failed(
                    launched.exceptionOrNull()?.message ?: "novel turn agent 未注册",
                ),
            )
            events.close()
        }
        val settleNoHandler: (AgentRunSnapshot) -> Unit = { terminal ->
            if (payload.claimNoHandler()) {
                events.trySend(
                    NovelWorkspaceRuntime.TurnEvent.Failed(
                        terminal.error?.message
                            ?: "小说回合未进入执行器（${terminal.status.wireName}）",
                    ),
                )
                events.close()
                payload.completion.complete(Unit)
            }
        }
        if (launched.isSuccess) {
            val terminalObserver = scope.launch {
                settleNoHandler(agentRunner.observe(turnRunId).first { it.status.isTerminal })
            }
            payload.completion.invokeOnCompletion { terminalObserver.cancel() }
        }
        val eventFlow = events.receiveAsFlow().onEach { event ->
            if (event.isTerminal()) payload.terminalDelivered.set(true)
        }.onCompletion { cause ->
            if (cause is CancellationException && !payload.terminalDelivered.get()) {
                // Claim before cancelling: a handler racing this path either owns
                // the payload and finishes its rollback, or sees NO_HANDLER and
                // never touches the workspace.
                val noHandler = payload.claimNoHandler()
                agentRunner.cancel(turnRunId)
                withContext(NonCancellable) {
                    if (noHandler) {
                        events.close()
                        payload.completion.complete(Unit)
                    } else {
                        payload.completion.await()
                    }
                }
            } else {
                // A `first { terminal }` collector cancels its upstream after the
                // event has been delivered. It must still wait for the handler's
                // final protocol/rollback work, but must not turn the run CANCELLED.
                withContext(NonCancellable) { payload.completion.await() }
            }
            payloads.remove(turnRunId.value)
        }
        return NovelTurnHandle(
            runId = turnRunId,
            events = eventFlow,
            agentRunner = agentRunner,
            completion = payload.completion,
            settleNoHandler = settleNoHandler,
        )
    }

    private fun NovelWorkspaceRuntime.TurnEvent.isTerminal(): Boolean =
        this is NovelWorkspaceRuntime.TurnEvent.Completed ||
            this is NovelWorkspaceRuntime.TurnEvent.Failed

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
