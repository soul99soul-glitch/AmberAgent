package app.amber.feature.chat.impl

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.TraceSpanRecord
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatStreamCheckpointRecorderTest {

    private class FakeEventStore : AgentEventStore {
        val runs = mutableListOf<AgentRunRecord>()
        val events = mutableListOf<AgentEventRecord>()
        val interruptions = mutableListOf<Pair<AgentRunId, String>>()
        var failAppendEvent = false

        override suspend fun appendRun(run: AgentRunRecord) {
            runs += run
        }

        override suspend fun appendEvent(event: AgentEventRecord) {
            if (failAppendEvent) error("boom")
            // mirrors the (runId, seq) idempotency of the Room store
            if (events.none { it.runId == event.runId && it.seq == event.seq }) {
                events += event
            }
        }

        override suspend fun appendSpan(span: TraceSpanRecord) {}

        override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = emptyFlow()

        override suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord> =
            events.filter { it.runId == runId.value }.sortedBy { it.seq }

        override suspend fun deleteEventsByType(runId: AgentRunId, type: String) {
            events.removeAll { it.runId == runId.value && it.type == type }
        }

        override suspend fun listUnfinishedRuns(): List<AgentRunRecord> =
            runs.groupBy { it.runId }
                .map { (_, versions) -> versions.last() }
                .filter { it.status == "running" }

        override suspend fun markInterrupted(runId: AgentRunId, reason: String) {
            interruptions += runId to reason
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun recorder(
        store: FakeEventStore,
        clock: () -> Long,
        conversationId: String = Uuid.random().toString(),
    ) = ChatStreamCheckpointRecorder(
        eventStore = store,
        conversationId = conversationId,
        json = json,
        now = clock,
    )

    private fun assistantMessage(text: String, id: Uuid = Uuid.random()) = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `onRunStarted persists a running run with the conversation id`() = runTest {
        val store = FakeEventStore()
        val conversationId = Uuid.random().toString()
        recorder(store, clock = { 0L }, conversationId = conversationId).onRunStarted()

        val run = store.runs.single()
        assertEquals("running", run.status)
        assertEquals(conversationId, run.conversationId)
        assertEquals(ChatStreamCheckpointRecorder.DESCRIPTOR_ID, run.agentDescriptorId)
    }

    @Test
    fun `checkpoints are coalesced and carry increasing seq`() = runTest {
        val store = FakeEventStore()
        var now = 0L
        val r = recorder(store, clock = { now })
        r.onRunStarted()

        val tailId = Uuid.random()
        // first chunk arms the window — no checkpoint yet
        r.onChunk(listOf(assistantMessage("a", tailId)), tailId)
        assertEquals(0, store.events.size)

        now = 1_100
        r.onChunk(listOf(assistantMessage("ab", tailId)), tailId)
        assertEquals(1, store.events.size)

        // within the next window and below char threshold: coalesced away
        now = 1_500
        r.onChunk(listOf(assistantMessage("abc", tailId)), tailId)
        assertEquals(1, store.events.size)

        now = 2_600
        r.onChunk(listOf(assistantMessage("abcd", tailId)), tailId)
        assertEquals(2, store.events.size)

        assertEquals(listOf(1L, 2L), store.events.map { it.seq })
        assertEquals(ChatEventPayload.StreamCheckpoint.TYPE, store.events[0].type)
        assertEquals("${r.runId.value}_1", store.events[0].eventId)
        // second checkpoint points back at the first event
        val second = json.decodeFromString(
            ChatEventPayload.StreamCheckpoint.serializer(),
            store.events[1].payload,
        )
        assertEquals("${r.runId.value}_1", second.lastEventId)
    }

    @Test
    fun `checkpoint payload round-trips tail id hash and tool states`() = runTest {
        val store = FakeEventStore()
        var now = 0L
        val conversationId = Uuid.random().toString()
        val r = recorder(store, clock = { now }, conversationId = conversationId)
        r.onRunStarted()

        val tailId = Uuid.random()
        val tail = UIMessage(
            id = tailId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("answer"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "search",
                    input = "{}",
                ),
            ),
        )
        r.onChunk(listOf(tail), tailId)
        now = 1_100
        r.onChunk(listOf(tail.copy(parts = tail.parts + UIMessagePart.Text("!"))), tailId)

        val payload = json.decodeFromString(
            ChatEventPayload.StreamCheckpoint.serializer(),
            store.events.single().payload,
        )
        assertEquals(conversationId, payload.conversationId)
        assertEquals(tailId.toString(), payload.messageId)
        assertTrue(payload.partsHash.isNotBlank())
        assertEquals("call_1", payload.toolStates.single().toolCallId)
    }

    @Test
    fun `completed run is finalized and its checkpoints pruned`() = runTest {
        val store = FakeEventStore()
        var now = 0L
        val r = recorder(store, clock = { now })
        r.onRunStarted()
        val tailId = Uuid.random()
        r.onChunk(listOf(assistantMessage("a", tailId)), tailId)
        now = 1_100
        r.onChunk(listOf(assistantMessage("ab", tailId)), tailId)
        assertEquals(1, store.events.size)

        r.onRunFinished(null)

        assertEquals("completed", store.runs.last().status)
        assertTrue(store.events.isEmpty())
        assertTrue(store.listUnfinishedRuns().isEmpty())
    }

    @Test
    fun `cancellation marks the run interrupted`() = runTest {
        val store = FakeEventStore()
        val r = recorder(store, clock = { 0L })
        r.onRunStarted()

        r.onRunFinished(CancellationException("user cancelled"))

        assertEquals(r.runId to "cancelled", store.interruptions.single())
    }

    @Test
    fun `failure finalizes the run as failed with reason`() = runTest {
        val store = FakeEventStore()
        val r = recorder(store, clock = { 0L })
        r.onRunStarted()

        r.onRunFinished(RuntimeException("provider 500"))

        val run = store.runs.last()
        assertEquals("failed", run.status)
        assertEquals("provider 500", run.interruptedReason)
    }

    @Test
    fun `onRunFinished is idempotent`() = runTest {
        val store = FakeEventStore()
        val r = recorder(store, clock = { 0L })
        r.onRunStarted()

        r.onRunFinished(null)
        r.onRunFinished(RuntimeException("late failure"))

        assertEquals("completed", store.runs.last().status)
    }

    @Test
    fun `store failures never propagate into the generation loop`() = runTest {
        val store = FakeEventStore().apply { failAppendEvent = true }
        var now = 0L
        val r = recorder(store, clock = { now })
        r.onRunStarted()
        val tailId = Uuid.random()
        r.onChunk(listOf(assistantMessage("a", tailId)), tailId)
        now = 1_100
        // would emit, append throws — must be swallowed
        r.onChunk(listOf(assistantMessage("ab", tailId)), tailId)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun `unfinished run stays running for replay to pick up`() = runTest {
        val store = FakeEventStore()
        var now = 0L
        val r = recorder(store, clock = { now })
        r.onRunStarted()
        val tailId = Uuid.random()
        r.onChunk(listOf(assistantMessage("a", tailId)), tailId)
        now = 1_100
        r.onChunk(listOf(assistantMessage("ab", tailId)), tailId)

        // process death: no onRunFinished
        assertEquals(1, store.listUnfinishedRuns().size)
        val latest = ChatEventProjector.latestStreamCheckpoint(
            store.listEvents(r.runId),
            json,
        )
        assertEquals(tailId.toString(), latest?.messageId)
    }
}
