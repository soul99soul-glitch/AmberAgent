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
        // The writer owns event ids, so it stamps the checkpoint's
        // last-covered-event pointer (the most recent prior committed event).
        val resolved = if (
            final is ChatEventPayload.StreamCheckpoint &&
            final.lastEventId.isEmpty() &&
            seqNum > 1
        ) {
            final.copy(lastEventId = "${runId.value}_${seqNum - 1}")
        } else {
            final
        }
        when (resolved) {
            is ChatEventPayload -> {
                try {
                    projector.commitEvent(runId, resolved, seqNum)
                    if (resolved is ChatEventPayload.AssistantMessageFinalized) {
                        projector.projectFinalized(conversationId, resolved)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to commit event $resolved", e)
                }
            }
            else -> {
                Log.d(TAG, "Skipping non-chat Final event: $resolved")
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
