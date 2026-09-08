package app.amber.feature.novel.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local mailbox for the non-serializable runtime objects of one
 * workspace turn (the serializable identity travels in [NovelTurnInput]).
 * Keyed by the runner run id.
 *
 * The payload carries the CALLER's [NovelWorkspaceRuntime] instance on
 * purpose: pendingProposals is per-instance state and the UI reads the
 * instance it registered with, so the handler must run the turn on that
 * same instance. The launcher registers the payload before launch and
 * removes it when collection ends; the handler resolves it from
 * `RunScope.runId`.
 */
class NovelTurnPayloads {

    private enum class HandlerClaim {
        PENDING,
        STARTED,
        NO_HANDLER,
    }

    class Payload(
        val runtime: NovelWorkspaceRuntime,
        val request: NovelWorkspaceRuntime.TurnRequest,
        /** Live turn events; UNLIMITED so pre-subscription emissions are buffered. */
        val events: Channel<NovelWorkspaceRuntime.TurnEvent>,
        /** Completes only after the agent handler's finally block has settled. */
        val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        private val handlerClaim = AtomicReference(HandlerClaim.PENDING)

        /** A handler may touch the workspace only if it wins this claim. */
        fun claimHandler(): Boolean = handlerClaim.compareAndSet(
            HandlerClaim.PENDING,
            HandlerClaim.STARTED,
        )

        /** A terminal runner outcome arrived before any handler could start. */
        fun claimNoHandler(): Boolean = handlerClaim.compareAndSet(
            HandlerClaim.PENDING,
            HandlerClaim.NO_HANDLER,
        )

        /** Keeps collector-side `first { terminal }` distinct from a real cancellation. */
        val terminalDelivered = AtomicBoolean(false)
    }

    private val payloads = ConcurrentHashMap<String, Payload>()

    fun register(runId: String, payload: Payload) {
        payloads[runId] = payload
    }

    fun resolve(runId: String): Payload? = payloads[runId]

    fun remove(runId: String) {
        payloads.remove(runId)
    }
}
