package app.amber.feature.runtime

import android.util.Log
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.providers.openai.StoredResponseCancelResult

private const val TAG = "StoredResponseStopCancel"

/**
 * P6-01 — server-side cancel for the Stop path (plan §P6-01 #5):
 *
 * Stop must not pretend the run was cancelled. The server cancel is awaited
 * for a decidable outcome first; only when the cancel request itself fails
 * (network / HTTP error) does the caller keep the run in WAITING_EXTERNAL
 * with the cursor intact so recovery can settle it later.
 */
class StoredResponseStopCancel(
    private val gateway: StoredResponseGateway,
    private val resumeStore: ResponseResumeStore,
) {
    /**
     * @return true when the server outcome is decidable (nothing stored, or
     * the cancel was confirmed — cursor cleared). false when the cancel
     * could not be confirmed: the caller must publish WAITING_EXTERNAL and
     * leave the cursor for recovery.
     */
    suspend fun cancelStored(runId: String): Boolean {
        val session = gateway.resolve(runId) ?: return true // nothing stored locally
        val api = session.api ?: run {
            // A cursor exists but the response is no longer resolvable — we
            // cannot decide the server outcome, so do not pretend cancelled.
            Log.w(TAG, "cancelStored: run $runId has a stored response that is no longer resolvable")
            return false
        }
        val providerSetting = session.providerSetting ?: return false
        return when (api.cancel(providerSetting, session.cursor.responseId)) {
            is StoredResponseCancelResult.CancelledDecided -> {
                resumeStore.clear(runId)
                true
            }

            is StoredResponseCancelResult.CancelFailed -> {
                Log.w(TAG, "cancelStored: server cancel failed for run $runId; keeping WAITING_EXTERNAL")
                false
            }
        }
    }
}
