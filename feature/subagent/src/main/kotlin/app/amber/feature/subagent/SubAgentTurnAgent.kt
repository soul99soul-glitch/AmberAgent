package app.amber.feature.subagent

import android.util.Log
import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val TAG = "SubAgentTurnAgent"

/**
 * Kernel adapter for one sub-agent generation turn: resolves the runtime
 * payload registered by SubAgentManager and delegates to the generation
 * runner. The returned [SubAgentResult] becomes the run's artifact — the
 * manager settles the durable thread from it once the run reaches a
 * terminal or pause state.
 *
 * [reconcileStartedEffects] classifies orphaned STARTED ledger effects of a
 * dead turn (cancel / interrupt / timeout / failure). Thread-graph turns have
 * no run_terminal row, so cold-start recovery never visits them — without
 * this hook a non-idempotent tool effect interrupted mid-execution would stay
 * STARTED forever. Parked turns (APPROVAL_REQUIRED) are resumable and must
 * NOT be reconciled, so only genuinely dead turns trigger it.
 */
class SubAgentTurnAgent(
    private val runner: SubAgentRunner,
    private val payloads: SubAgentTurnPayloads,
    private val reconcileStartedEffects: (suspend (runId: String) -> Unit)? = null,
) : Agent<SubAgentTurnInput, SubAgentResult> {

    override val descriptor: AgentDescriptor = SubAgentTurnDescriptor.value

    override val handler = AgentHandler<SubAgentTurnInput, SubAgentResult> { input, scope ->
        val payload = payloads.resolve(scope.runId.value)
            ?: error("No sub-agent turn payload registered for run ${scope.runId.value}")
        try {
            val result = runner.run(
                settings = payload.settings,
                definition = payload.definition,
                task = payload.task,
                tools = payload.tools,
                liveText = payload.liveText,
                liveParts = payload.liveParts,
                runId = payload.kernelRunId,
                onTerminal = payload.onTerminal,
                consumeSteerMessages = payload.consumeSteerMessages,
                previousAnswer = payload.previousAnswer,
                events = scope.events,
                // P1-7: the parent run's policy carried in the payload (null
                // producer → permissive default), and the payload's optional
                // child narrowing (null = permissive). The runner intersects
                // both, so a child can never widen the parent's sandbox.
                childPolicy = payload.executionPolicy,
                parentPolicy = payload.parentPolicy
                    ?: app.amber.feature.runtime.ExecutionPolicy.permissive(),
            )
            if (result.status == SubAgentRunStatus.FAILED) reconcileDeadTurn(payload.kernelRunId)
            result
        } catch (cancelled: CancellationException) {
            reconcileDeadTurn(payload.kernelRunId)
            throw cancelled
        } catch (error: Throwable) {
            reconcileDeadTurn(payload.kernelRunId)
            throw error
        }
    }

    private suspend fun reconcileDeadTurn(kernelRunId: String?) {
        // A null kernelRunId means the thread-graph flag was off: the turn ran
        // the bare non-durable loop and no ledger effects exist to classify.
        if (kernelRunId == null) return
        val reconcile = reconcileStartedEffects ?: return
        // NonCancellable: on the cancel/timeout path this handler coroutine is
        // already cancelled, and the reconcile hits Room suspend queries —
        // without it the first suspension rethrows CancellationException and
        // the orphaned STARTED effect is never classified.
        runCatching { withContext(NonCancellable) { reconcile(kernelRunId) } }
            .onFailure { Log.w(TAG, "reconcileStartedEffects failed for $kernelRunId", it) }
    }
}
