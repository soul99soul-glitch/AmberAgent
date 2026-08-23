package app.amber.ai.provider

/**
 * P6-01 — server-side stored OpenAI Response resumption (parity plan
 * §P6-01). These types bind a stored Response API run to the local runId
 * and carry the resume cursor through the provider call.
 *
 * Invariants (kept by the provider implementation):
 *  - A cursor row exists for a runId  <=>  the server holds a stored
 *    response for that run that has not been fully consumed locally.
 *  - The persisted sequence is written BEFORE an event is emitted to the
 *    consumer (write-ahead), so a reconnect can skip every event with
 *    sequence <= cursor without re-delivering text already shown.
 *  - The cursor is cleared when the terminal event (response.completed /
 *    response.incomplete) is delivered or when the run is settled
 *    server-side by recovery.
 */
data class ResponseCursor(
    val responseId: String,
    val sequence: Long,
    /** ProviderSetting id the response belongs to (recovery re-resolves the setting). */
    val providerId: String,
)

/**
 * Persisted resume cursor store, implemented app-side over Room (P6-01 #3:
 * "落 Room 或现有 run 状态存储，选最小方案").
 */
interface ResponseResumeStore {
    /** Write-ahead checkpoint for one streamed event. */
    suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String)

    suspend fun load(runId: String): ResponseCursor?

    suspend fun clear(runId: String)
}

/**
 * Carried with a generation request to enable stored/resumable streaming.
 * Non-null only when every gate passed (capability flag, user toggle and
 * strict official-endpoint match); null keeps today's store=false behavior.
 */
data class ResponsesResumeRequest(
    val runId: String,
    val store: ResponseResumeStore,
)
