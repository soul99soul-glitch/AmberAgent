package app.amber.core.agent.runtime.impl

import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentEventPayloadCodec
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.RunTransitionResult
import app.amber.core.agent.runtime.ToolLifecycleEvent
import app.amber.core.agent.runtime.TraceSpanRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generic persistence path behind every non-chat run scope (Step 3):
 * registered finals persist with a store-allocated seq, unknown payloads drop
 * without breaking the run, and store failures are contained.
 */
class PersistingEventWriterTest {

    @Serializable
    data class CustomPayload(val value: String) : AgentEventPayload.Final

    private class FakeStore : AgentEventStore {
        val events = mutableListOf<AgentEventRecord>()
        var failOnAppend: Throwable? = null

        override suspend fun appendRun(run: AgentRunRecord) {}
        override suspend fun appendEvent(event: AgentEventRecord) {}
        override suspend fun appendEventAllocatingSeq(event: AgentEventRecord): AgentEventRecord {
            failOnAppend?.let { throw it }
            val next = (events.filter { it.runId == event.runId }.maxOfOrNull { it.seq } ?: 0L) + 1
            return event.copy(seq = next).also(events::add)
        }
        override suspend fun transitionRun(
            runId: AgentRunId,
            expected: Set<RunStatus>,
            to: RunStatus,
            reason: String?,
        ): RunTransitionResult = RunTransitionResult.UnknownRun(to)
        override suspend fun appendSpan(span: TraceSpanRecord) {}
        override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = emptyFlow()
        override suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord> = events
        override suspend fun deleteEventsByType(runId: AgentRunId, type: String) {}
        override suspend fun listUnfinishedRuns(): List<AgentRunRecord> = emptyList()
        override suspend fun markInterrupted(runId: AgentRunId, reason: String) {}
    }

    private fun writer(
        store: FakeStore,
        codecs: Map<String, AgentEventPayloadCodec<*>> = mapOf(
            ToolLifecycleEvent.Started::class.qualifiedName!! to
                AgentEventPayloadCodec(ToolLifecycleEvent.TYPE_STARTED, ToolLifecycleEvent.Started.serializer()),
            CustomPayload::class.qualifiedName!! to
                AgentEventPayloadCodec("CustomPayload", CustomPayload.serializer()),
        ),
    ) = PersistingEventWriter(
        runId = AgentRunId("run_1"),
        parentRunId = AgentRunId("parent_1"),
        agentDescriptorId = "sub_agent_turn",
        store = store,
        json = Json,
        codecs = codecs,
    )

    @Test
    fun registeredFinalPersistsWithCodecTypeAndAllocatedSeq() = runTest {
        val store = FakeStore()
        val w = writer(store)

        w.commit(ToolLifecycleEvent.Started("effect_1", "call_1", "post_message", approvalDigest = "digest"))
        w.commit(CustomPayload("hello"))

        assertEquals(2, store.events.size)
        val started = store.events[0]
        assertEquals("run_1", started.runId)
        assertEquals("parent_1", started.parentRunId)
        assertEquals("ToolStarted", started.type)
        assertEquals(ToolLifecycleEvent.Started::class.qualifiedName, started.payloadType)
        assertEquals("sub_agent_turn", started.agentDescriptorId)
        assertEquals(1L, started.seq)
        assertTrue(started.isFinal)
        assertTrue(started.payload.contains("\"effectId\":\"effect_1\""))
        assertEquals(2L, store.events[1].seq)
        assertEquals("CustomPayload", store.events[1].type)
    }

    @Test
    fun unregisteredFinalIsDroppedSilently() = runTest {
        val store = FakeStore()
        val w = writer(store, codecs = emptyMap())

        w.commit(ToolLifecycleEvent.Started("effect_1", "call_1", "post_message"))

        assertTrue(store.events.isEmpty())
    }

    @Test
    fun storeFailureNeverEscapesIntoTheRun() = runTest {
        val store = FakeStore().apply { failOnAppend = IllegalStateException("db gone") }
        val w = writer(store)

        // Must not throw.
        w.commit(ToolLifecycleEvent.Started("effect_1", "call_1", "post_message"))
        w.flush()
        w.commitError(IllegalStateException("boom"), recoverable = false)

        assertTrue(store.events.isEmpty())
    }
}
