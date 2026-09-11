package app.amber.feature.novel.workspace

import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentHandler

/**
 * Kernel adapter for one workspace turn: resolves the runtime payload
 * registered by [NovelTurnLauncher] and runs the turn on the caller's
 * [NovelWorkspaceRuntime] instance, forwarding every event into the
 * payload's channel. The compact terminal outcome becomes the run's
 * artifact; the channel is always closed so collectors never hang.
 *
 * Cancellation parity with the old direct collect: cancelling the run
 * cancels the collect inside, the runtime's rollback-on-cancel runs, and
 * the CancellationException propagates so the runner settles CANCELLED.
 */
class NovelTurnAgent(
    private val payloads: NovelTurnPayloads,
) : Agent<NovelTurnInput, NovelTurnArtifact> {

    override val descriptor: AgentDescriptor = NovelTurnDescriptor.value

    override val handler = AgentHandler<NovelTurnInput, NovelTurnArtifact> { _, scope ->
        val payload = payloads.resolve(scope.runId.value)
            ?: error("No novel turn payload registered for run ${scope.runId.value}")
        try {
            var artifact: NovelTurnArtifact? = null
            // Step 5: thread the run scope's identity + protocol event writer
            // into the turn so the kernel's audit trail (tool lifecycle, and
            // request snapshots once the durable path is genuinely on) covers
            // novel runs the same way it covers subagent runs.
            payload.runtime.runTurn(
                payload.request,
                runId = scope.runId.value,
                events = scope.events,
            ).collect { event ->
                payload.events.trySend(event)
                when (event) {
                    is NovelWorkspaceRuntime.TurnEvent.Completed -> {
                        artifact = NovelTurnArtifact(
                            success = true,
                            finalText = event.finalText,
                            proposalId = event.proposal?.id,
                        )
                        // Step 3: the turn's domain trail joins the run's
                        // event stream (novel turns run non-durable, so these
                        // are the only events a novel run produces).
                        scope.events.commit(
                            NovelTurnEventPayload.TurnCompleted(
                                finalTextLength = event.finalText.length,
                                proposalId = event.proposal?.id,
                                proposalEntryCount = event.proposal?.entries?.size ?: 0,
                            ),
                        )
                    }

                    is NovelWorkspaceRuntime.TurnEvent.Failed -> {
                        artifact = NovelTurnArtifact(success = false, error = event.message)
                        scope.events.commit(NovelTurnEventPayload.TurnFailed(event.message))
                    }

                    is NovelWorkspaceRuntime.TurnEvent.ToolActivity ->
                        scope.events.commit(NovelTurnEventPayload.ToolActivity(event.toolName))

                    else -> Unit // Delta / ReasoningDelta: caller-side render hints
                }
            }
            artifact ?: NovelTurnArtifact(
                success = false,
                error = "turn ended without a terminal event",
            )
        } finally {
            payload.events.close()
        }
    }
}
