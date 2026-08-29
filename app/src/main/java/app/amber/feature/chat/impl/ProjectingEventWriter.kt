package app.amber.feature.chat.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.agent.runtime.AgentRunId
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.uuid.Uuid

private const val TAG = "ProjectingEventWriter"

class ProjectingEventWriter(
    private val runId: AgentRunId,
    private val conversationId: Uuid,
    private val projector: ChatEventProjector,
    /**
     * Persistence path for non-chat Final events (Step 3): the tool lifecycle
     * events the kernel/dispatcher emit are protocol-level, not chat-domain —
     * they persist through this delegate (a PersistingEventWriter) instead of
     * the chat projector. Null keeps the legacy log-and-drop behavior.
     */
    private val fallback: AgentEventWriter? = null,
) : AgentEventWriter {

    private val _transientFlow = MutableSharedFlow<AgentEventPayload.Transient>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val transientFlow: SharedFlow<AgentEventPayload.Transient> = _transientFlow

    override fun emit(transient: AgentEventPayload.Transient) {
        _transientFlow.tryEmit(transient)
    }

    override suspend fun commit(final: AgentEventPayload.Final) {
        // The projector owns event ids and the store owns seq allocation —
        // a writer-side counter would collide with persisted rows after a
        // process restart under the same runId (idempotent-append IGNORE).
        when (final) {
            is ChatEventPayload -> {
                try {
                    projector.commitEvent(runId, final)
                    if (final is ChatEventPayload.AssistantMessageFinalized) {
                        projector.projectFinalized(conversationId, final)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Identity only, one line: `final` can carry up to an 8KB
                    // conversation preview (RequestSnapshot) — interpolating
                    // the payload would leak it into logcat.
                    val identity = when (final) {
                        is ChatEventPayload.RequestSnapshot ->
                            "RequestSnapshot(stepIndex=${final.stepIndex}, attempt=${final.attempt}, kind=${final.kind})"
                        else -> final::class.simpleName ?: "unknown"
                    }
                    Log.w(TAG, "Failed to commit event $identity", e)
                }
            }
            else -> {
                if (fallback != null) {
                    fallback.commit(final)
                } else {
                    Log.d(TAG, "Skipping non-chat Final event: $final")
                }
            }
        }
    }

    override suspend fun flush() {
        // No buffering; commits are synchronous against the projector
    }

    override suspend fun commitError(throwable: Throwable, recoverable: Boolean) {
        Log.e(TAG, "Run $runId error (recoverable=$recoverable)", throwable)
    }
}
