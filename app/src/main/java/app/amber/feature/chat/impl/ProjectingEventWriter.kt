package app.amber.feature.chat.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.agent.runtime.AgentRunId
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "ProjectingEventWriter"

class ProjectingEventWriter(
    private val runId: AgentRunId,
    private val conversationId: Uuid,
    private val projector: ChatEventProjector,
) : AgentEventWriter {

    private val seq = AtomicLong(0L)
    private val _transientFlow = MutableSharedFlow<AgentEventPayload.Transient>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val transientFlow: SharedFlow<AgentEventPayload.Transient> = _transientFlow

    override fun emit(transient: AgentEventPayload.Transient) {
        _transientFlow.tryEmit(transient)
    }

    override suspend fun commit(final: AgentEventPayload.Final) {
        val seqNum = seq.incrementAndGet()
        when (final) {
            is ChatEventPayload -> {
                try {
                    projector.commitEvent(runId, final, seqNum)
                    if (final is ChatEventPayload.AssistantMessageFinalized) {
                        projector.projectFinalized(conversationId, final)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to commit event $final", e)
                }
            }
            else -> {
                Log.d(TAG, "Skipping non-chat Final event: $final")
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
