package app.amber.feature.subagent

/**
 * P4-02 (capability parity plan): persistent thread graph records.
 *
 * The wire model is intentionally kept free of Room/Android types so the
 * interface can live in this feature module; the durable implementation
 * (Room-backed, app module) maps these to tables. All fields follow the plan
 * model: ThreadNode (threadId / parentThreadId / rootRunId / conversationId /
 * status / task), ThreadMessage (sender / recipient / kind / payload digest /
 * delivery state), ThreadResult (final answer / artifacts / terminal reason).
 */

/** One child thread (a subagent run). [status] is a [SubAgentRunStatus] name. */
data class ThreadNodeRecord(
    val threadId: String,
    val parentThreadId: String?,
    /** The parent run that started this thread graph (cascade cancellation key). */
    val rootRunId: String,
    val conversationId: String,
    val status: String,
    /** JSON: { "definition": <SubAgentDefinition>, "task": <SubAgentTaskSpec> }. */
    val task: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
)

/** Delivery states of a thread message (plan §P4-02 persistence semantics). */
enum class ThreadDeliveryState {
    /** Persisted, not yet handed to the thread. */
    QUEUED,

    /** Handed to the thread's generation (mid-run steer or followup context). */
    DELIVERED,

    /** The thread's result for the consuming turn landed (no silent loss). */
    PERSISTED,
}

/**
 * A message between the parent run and a thread. The payload text is kept
 * alongside its SHA-256 digest; a message is never deleted between dequeue and
 * result persistence, so it cannot silently disappear mid-flight.
 */
data class ThreadMessageRecord(
    val messageId: String,
    val threadId: String,
    val sender: String,
    val recipient: String,
    /** "followup" | "message". */
    val kind: String,
    val payload: String,
    val payloadDigest: String,
    val deliveryState: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** The thread's final answer, structured artifacts and terminal reason. */
data class ThreadResultRecord(
    val threadId: String,
    val finalAnswer: String,
    /** JSON of [ThreadArtifacts]. */
    val artifactsJson: String,
    /** completed | failed | cancelled | timed_out | interrupted. */
    val terminalReason: String,
    val finishedAtMs: Long,
)

/** Structured subagent artifacts persisted with a thread result. */
@kotlinx.serialization.Serializable
data class ThreadArtifacts(
    val findings: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("recommended_next_steps")
    val recommendedNextSteps: List<String> = emptyList(),
    val confidence: String = "",
    val error: String = "",
)

/**
 * Durable storage of the thread graph (P4-02). Implemented in the app module
 * on top of Room (schema v13); disabled by the `thread_graph_v2` capability
 * flag — when the flag is off the legacy in-memory subagent path is used and
 * this store is never written or read.
 */
interface ThreadGraphStore {
    suspend fun upsertNode(node: ThreadNodeRecord)

    suspend fun getNode(threadId: String): ThreadNodeRecord?

    /** All threads started by [rootRunId] — the cascade-cancellation target set. */
    suspend fun listNodesByRootRun(rootRunId: String): List<ThreadNodeRecord>

    /** Persist a new message in QUEUED state. */
    suspend fun enqueueMessage(message: ThreadMessageRecord)

    suspend fun getMessage(messageId: String): ThreadMessageRecord?

    suspend fun listMessages(threadId: String): List<ThreadMessageRecord>

    suspend fun listQueuedMessages(threadId: String): List<ThreadMessageRecord>

    /**
     * Atomically claim all queued messages for the next thread turn. The
     * returned records are already in [ThreadDeliveryState.DELIVERED] state;
     * concurrent drains must not receive the same message twice.
     */
    suspend fun claimQueuedMessages(threadId: String): List<ThreadMessageRecord>

    /** Requeue messages claimed by a turn that was interrupted before a result landed. */
    suspend fun requeueDeliveredMessages(threadId: String): Int

    suspend fun markMessageDelivered(messageId: String)

    /** Promote all DELIVERED messages of a thread to PERSISTED. */
    suspend fun markDeliveredMessagesPersisted(threadId: String): Int

    suspend fun upsertResult(result: ThreadResultRecord)

    suspend fun getResult(threadId: String): ThreadResultRecord?
}
