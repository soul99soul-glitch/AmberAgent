package app.amber.feature.novel.workspace

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

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

    class Payload(
        val runtime: NovelWorkspaceRuntime,
        val request: NovelWorkspaceRuntime.TurnRequest,
        /** Live turn events; UNLIMITED so pre-subscription emissions are buffered. */
        val events: Channel<NovelWorkspaceRuntime.TurnEvent>,
    )

    private val payloads = ConcurrentHashMap<String, Payload>()

    fun register(runId: String, payload: Payload) {
        payloads[runId] = payload
    }

    fun resolve(runId: String): Payload? = payloads[runId]

    fun remove(runId: String) {
        payloads.remove(runId)
    }
}
