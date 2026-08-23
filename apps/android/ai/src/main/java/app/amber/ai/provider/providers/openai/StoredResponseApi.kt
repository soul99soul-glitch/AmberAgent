package app.amber.ai.provider.providers.openai

import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.ui.MessageChunk
import kotlinx.coroutines.flow.Flow

/**
 * P6-01 — server-side operations on a stored OpenAI Response
 * (store=true required; official api.openai.com only, see
 * [ProviderSetting.OpenAI.supportsResponsesResume]).
 *
 * Implemented by [ResponseAPI]; the app layer resolves the provider setting
 * and calls these for the recovery worker and the Stop path.
 */
interface StoredResponseApi {

    /**
     * GET /responses/{responseId} — non-streaming status query used by
     * cold-start recovery to decide how to continue a run.
     */
    suspend fun fetchStatus(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
    ): StoredResponseStatus

    /**
     * GET /responses/{responseId} with `Accept: text/event-stream` — replays
     * the stored event stream. Events with sequence <= [cursor].sequence are
     * skipped (dedup); the cursor is write-ahead persisted for the rest.
     */
    suspend fun streamStored(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
        cursor: ResponseCursor?,
        store: ResponseResumeStore,
        runId: String,
    ): Flow<MessageChunk>

    /**
     * POST /responses/{responseId}/cancel — awaited for a decidable outcome.
     * Any 2xx (cancelled or already settled) is decided; anything else is
     * [StoredResponseCancelResult.CancelFailed] and the caller must not
     * pretend the run was cancelled.
     */
    suspend fun cancel(
        providerSetting: ProviderSetting.OpenAI,
        responseId: String,
    ): StoredResponseCancelResult
}

/** Server-side status of a stored response. */
enum class StoredResponseState {
    COMPLETED,
    CANCELLED,
    FAILED,
    IN_PROGRESS,
}

data class StoredResponseStatus(
    val state: StoredResponseState,
    val responseId: String,
)

sealed interface StoredResponseCancelResult {
    /** The server confirmed the response is settled (cancelled or already done). */
    data object CancelledDecided : StoredResponseCancelResult

    /** The cancel request failed (network / HTTP error) — outcome undecidable. */
    data object CancelFailed : StoredResponseCancelResult
}
