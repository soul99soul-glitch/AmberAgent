package app.amber.feature.runtime

import android.util.Log
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RunOwnershipRegistry"

/** P1-05 — cancellation key: assistantId + conversationId + runId. */
data class RunOwnerKey(
    val assistantId: String,
    val conversationId: String,
    val runId: String,
)

/**
 * P1-05 — in-process ownership map for active generation runs.
 *
 * The cancellation key is assistantId + conversationId + runId (no account
 * layer: the app has a single signed-in session, so the assistant dimension
 * is the current session owner). Registration covers the generation Job;
 * because the provider transport flow is collected inside that Job,
 * cancelling the Job also cancels the transport. Tool effects are owned by
 * runId inside ToolEffectLedger (durable path) and are reconciled after a
 * stop via RunRecoveryService.reconcileStartedEffects.
 *
 * Sub-agent (child thread) runs are CASCADED: when the parent run is
 * cancelled, ChatService calls SubAgentManager.cancelByRootRun(rootRunId,
 * conversationId), which cancels every child thread started by this run
 * (thread_graph_v2 flag; flag-off keeps the legacy detached behavior where
 * child runs live on appScope independent of the parent generation job).
 * Child tool effects stay ledger-tracked under their own runId.
 *
 * In-memory by design: entries only need to live as long as the run's
 * handles. Cold-start recovery uses the persisted RunTerminalStore instead.
 */
class RunOwnershipRegistry {

    private data class Entry(
        val key: RunOwnerKey,
        val job: Job,
        val registeredAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Own [runId] to [job]. Returns false (and keeps the existing owner) when
     * the same runId is already owned by a different key — a run may resume
     * (WAITING_USER approval) but never be stolen by another conversation.
     */
    fun register(assistantId: String, conversationId: String, runId: String, job: Job): Boolean {
        val key = RunOwnerKey(assistantId, conversationId, runId)
        val existing = entries[runId]
        if (existing != null && existing.key != key) {
            Log.w(TAG, "register: runId $runId already owned by ${existing.key}; refusing re-ownership")
            return false
        }
        entries[runId] = Entry(key = key, job = job, registeredAtMs = System.currentTimeMillis())
        return true
    }

    /** Called when the run's generation flow ends (onCompletion). */
    fun unregister(runId: String) {
        entries.remove(runId)
    }

    /**
     * Cancel exactly the handles owned by (runId, conversationId).
     *
     * Returns true when a matching owner was found and cancelled. A runId
     * whose registered owner does not belong to [conversationId], or a runId
     * with no active owner (finished/stale), is left untouched — a stale or
     * mismatched notification deep link must never cancel another
     * conversation's run.
     */
    fun cancel(runId: String, conversationId: String): Boolean {
        val entry = entries[runId] ?: return false
        if (entry.key.conversationId != conversationId) {
            Log.w(TAG, "cancel: runId $runId does not belong to conversation $conversationId; refusing")
            return false
        }
        entries.remove(runId)
        entry.job.cancel()
        return true
    }

    /** Test/observability hook. */
    fun clearForTest() {
        entries.clear()
    }
}
