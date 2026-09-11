package app.amber.core.agent.store

import android.app.Application
import androidx.room.Room
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.RunTransitionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Room-level verification of the Run Protocol mechanics: CAS transitions,
 * terminal write-once, database-allocated event sequence and fail-closed
 * reads. Uses the real generated DAO against an in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomAgentEventStoreProtocolTest {

    private lateinit var database: AgentRuntimeDatabase
    private lateinit var dao: AgentRuntimeDao
    private lateinit var store: RoomAgentEventStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AgentRuntimeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.agentRuntimeDao()
        store = RoomAgentEventStore(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun runRecord(runId: String, status: RunStatus = RunStatus.RUNNING) = AgentRunRecord(
        runId = runId,
        parentRunId = null,
        agentDescriptorId = "chat_turn",
        agentVersion = "test",
        conversationId = "conv-1",
        messageNodeId = null,
        producesMessageId = null,
        assistantId = null,
        status = status,
        inputDigest = "",
        inputSnapshotRef = null,
        inputSchemaVersion = 1,
        startedAt = 1L,
        finishedAt = null,
        interruptedReason = null,
    )

    private fun event(runId: String, seq: Long = 0L, eventId: String = "$runId-e$seq") = AgentEventRecord(
        eventId = eventId,
        runId = runId,
        parentRunId = null,
        seq = seq,
        type = "test_event",
        payloadType = "TestPayload",
        payload = "{}",
        payloadSchemaVersion = 1,
        agentDescriptorId = "chat_turn",
        agentVersion = "test",
        isFinal = true,
        ts = 1L,
    )

    @Test
    fun `legal transition lands and terminal sets finishedAt`() = runTest {
        store.appendRun(runRecord("r1"))

        val paused = store.transitionRun(AgentRunId("r1"), setOf(RunStatus.RUNNING), RunStatus.WAITING_USER)
        assertEquals(RunTransitionResult.Applied(RunStatus.RUNNING, RunStatus.WAITING_USER), paused)
        assertNull(dao.getRun("r1")!!.finishedAt)

        val done = store.transitionRun(AgentRunId("r1"), setOf(RunStatus.WAITING_USER), RunStatus.CANCELLED)
        assertEquals(RunTransitionResult.Applied(RunStatus.WAITING_USER, RunStatus.CANCELLED), done)
        assertNotNull(dao.getRun("r1")!!.finishedAt)
    }

    @Test
    fun `stale expectation is rejected and changes nothing`() = runTest {
        store.appendRun(runRecord("r2"))
        store.transitionRun(AgentRunId("r2"), setOf(RunStatus.RUNNING), RunStatus.WAITING_USER)

        // A callback from before the pause tries to complete the run.
        val stale = store.transitionRun(AgentRunId("r2"), setOf(RunStatus.RUNNING), RunStatus.COMPLETED)
        assertTrue(stale is RunTransitionResult.Rejected)
        assertEquals(RunStatus.WAITING_USER, (stale as RunTransitionResult.Rejected).current)
        assertEquals("waiting_user", dao.getRun("r2")!!.status)
    }

    @Test
    fun `illegal transition is rejected without touching the row`() = runTest {
        store.appendRun(runRecord("r3"))

        val result = store.transitionRun(AgentRunId("r3"), emptySet(), RunStatus.STEP_LIMIT)
        // RUNNING -> STEP_LIMIT is legal; CREATED -> COMPLETED is not.
        assertEquals(RunTransitionResult.Applied(RunStatus.RUNNING, RunStatus.STEP_LIMIT), result)

        val illegal = store.transitionRun(AgentRunId("r3"), emptySet(), RunStatus.COMPLETED)
        assertEquals(
            RunTransitionResult.Rejected(RunStatus.STEP_LIMIT, RunStatus.COMPLETED, illegal = true),
            illegal,
        )
        assertEquals("step_limit", dao.getRun("r3")!!.status)
    }

    @Test
    fun `terminal is write-once including recovery interrupt`() = runTest {
        store.appendRun(runRecord("r4"))
        store.transitionRun(AgentRunId("r4"), setOf(RunStatus.RUNNING), RunStatus.COMPLETED)

        store.markInterrupted(AgentRunId("r4"), "process death")
        assertEquals("completed", dao.getRun("r4")!!.status)

        val failed = store.transitionRun(AgentRunId("r4"), emptySet(), RunStatus.FAILED)
        assertTrue(failed is RunTransitionResult.Rejected)
        assertEquals("completed", dao.getRun("r4")!!.status)
    }

    @Test
    fun `appendRun is create-only and cannot resurrect a finished run`() = runTest {
        store.appendRun(runRecord("r5"))
        store.transitionRun(AgentRunId("r5"), setOf(RunStatus.RUNNING), RunStatus.COMPLETED)

        // A zombie writer re-asserts the original running record.
        store.appendRun(runRecord("r5"))

        assertEquals("completed", dao.getRun("r5")!!.status)
    }

    @Test
    fun `allocated seqs are monotonic and continue past explicit writes`() = runTest {
        store.appendRun(runRecord("r6"))
        store.appendEvent(event("r6", seq = 7, eventId = "r6-manual"))

        val first = store.appendEventAllocatingSeq(event("r6", eventId = "r6-auto-1"))
        val second = store.appendEventAllocatingSeq(event("r6", eventId = "r6-auto-2"))

        assertEquals(8L, first.seq)
        assertEquals(9L, second.seq)
        assertEquals(listOf(7L, 8L, 9L), dao.listEvents("r6").map { it.seq })
    }

    @Test
    fun `explicit appendEvent stays idempotent on runId and seq`() = runTest {
        store.appendRun(runRecord("r7"))
        store.appendEvent(event("r7", seq = 1))
        store.appendEvent(event("r7", seq = 1))

        assertEquals(1, dao.listEvents("r7").size)
    }

    @Test
    fun `markInterrupted settles a live run`() = runTest {
        store.appendRun(runRecord("r8"))

        store.markInterrupted(AgentRunId("r8"), "process death")

        val row = dao.getRun("r8")!!
        assertEquals("interrupted", row.status)
        assertEquals("process death", row.interruptedReason)
        assertNotNull(row.finishedAt)
        assertTrue(dao.listUnfinished().none { it.runId == "r8" })
    }

    @Test
    fun `legacy awaiting_permission rows parse as waiting_user and stay recoverable`() = runTest {
        dao.insertRun(runRecord("r9").let {
            AgentRunEntity(
                runId = it.runId,
                parentRunId = it.parentRunId,
                agentDescriptorId = it.agentDescriptorId,
                agentVersion = it.agentVersion,
                conversationId = it.conversationId,
                messageNodeId = it.messageNodeId,
                producesMessageId = it.producesMessageId,
                assistantId = it.assistantId,
                status = "awaiting_permission",
                inputDigest = it.inputDigest,
                inputSnapshotRef = it.inputSnapshotRef,
                inputSchemaVersion = it.inputSchemaVersion,
                startedAt = it.startedAt,
                finishedAt = it.finishedAt,
                interruptedReason = it.interruptedReason,
            )
        })

        val snapshot = store.observeRun(AgentRunId("r9")).first()
        assertEquals(RunStatus.WAITING_USER, snapshot.status)
        assertTrue(dao.listUnfinished().any { it.runId == "r9" })

        val resumed = store.transitionRun(AgentRunId("r9"), setOf(RunStatus.WAITING_USER), RunStatus.RUNNING)
        assertEquals(RunTransitionResult.Applied(RunStatus.WAITING_USER, RunStatus.RUNNING), resumed)
    }

    @Test
    fun `unparseable persisted state is fail-closed`() = runTest {
        dao.insertRun(
            AgentRunEntity(
                runId = "r10",
                parentRunId = null,
                agentDescriptorId = "chat_turn",
                agentVersion = "test",
                conversationId = null,
                messageNodeId = null,
                producesMessageId = null,
                assistantId = null,
                status = "banana",
                inputDigest = "",
                inputSnapshotRef = null,
                inputSchemaVersion = 1,
                startedAt = 1L,
                finishedAt = null,
                interruptedReason = null,
            ),
        )

        // No fabricated snapshot is emitted for the unknown state: the
        // observe flow stays silent (mapNotNull drops the row).
        val observed = withTimeoutOrNull(500) { store.observeRun(AgentRunId("r10")).first() }
        assertNull(observed)

        // And no transition may overwrite it.
        val result = store.transitionRun(AgentRunId("r10"), emptySet(), RunStatus.CANCELLED)
        assertEquals(RunTransitionResult.Rejected(current = null, to = RunStatus.CANCELLED, illegal = false), result)
        assertEquals("banana", dao.getRun("r10")!!.status)
    }

    @Test
    fun `unknown run reports UnknownRun`() = runTest {
        val result = store.transitionRun(AgentRunId("missing"), emptySet(), RunStatus.CANCELLED)
        assertEquals(RunTransitionResult.UnknownRun(RunStatus.CANCELLED), result)
    }
}
