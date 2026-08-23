package app.amber.core.agent.store

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.amber.core.agent.runtime.AgentRunEvent
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DurableRunStoreTest {

    private fun newPath(name: String): String {
        val path = Files.createTempFile("durable-run-$name", ".db")
        Files.delete(path)
        return path.toAbsolutePath().toString()
    }

    private fun open(path: String): AgentRuntimeDatabase =
        Room.databaseBuilder<AgentRuntimeDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    private fun run(
        id: String,
        descriptor: String = "chat_turn",
        parentRunId: String? = null,
        startedAt: Long = 1_000,
    ) = AgentRunRecord(
        runId = id,
        parentRunId = parentRunId,
        agentDescriptorId = descriptor,
        agentVersion = "1.0.0",
        conversationId = "conversation",
        messageNodeId = "message-node",
        producesMessageId = "assistant-message",
        assistantId = "assistant",
        status = AgentRunStatus.RUNNING,
        inputDigest = "digest-$id",
        inputSnapshotRef = null,
        inputSchemaVersion = 1,
        startedAt = startedAt,
        finishedAt = null,
        interruptedReason = null,
    )

    private fun event(id: String) = AgentRunEvent(
        eventId = id,
        type = "tool_result",
        payloadType = "application/json",
        payload = "{\"id\":\"$id\"}",
        payloadSchemaVersion = 1,
        isFinal = true,
        ts = 2_000,
    )

    @Test
    fun persistedStatusDecodeKeepsSharedLifecycleClosed() {
        assertEquals(AgentRunStatus.RECOVERY_PENDING, AgentRunStatus.fromWireName("recovery_pending"))
        assertEquals(AgentRunStatus.CANCELLED, AgentRunStatus.fromWireName("cancelled"))
        assertEquals(AgentRunStatus.FAILED, AgentRunStatus.fromWireName("truncated"))
        assertEquals(AgentRunStatus.FAILED, AgentRunStatus.fromWireName("guard_stopped"))
        assertEquals(AgentRunStatus.FAILED, AgentRunStatus.fromWireName("unknown_future_status"))
        assertFalse(AgentRunStatus.FAILED.canTransitionTo(AgentRunStatus.RUNNING))
    }

    @Test
    fun startRunIsIdempotentAndDoesNotReplaceExistingIdentity() = runTest {
        val db = open(newPath("start"))
        val store = RoomAgentEventStore(db.agentRuntimeDao())
        val original = run(id = "run-1", descriptor = "chat_turn")

        assertTrue(store.startRun(original))
        assertTrue(
            store.transitionRun(
                runId = AgentRunId("run-1"),
                expectedStatus = AgentRunStatus.RUNNING,
                status = AgentRunStatus.COMPLETED,
                inputSnapshotRef = null,
                detail = null,
                at = 2_000,
            ),
        )
        assertFalse(store.startRun(original.copy(agentDescriptorId = "other", inputDigest = "changed")))

        val persisted = store.getRun(AgentRunId("run-1"))!!
        assertEquals(AgentRunStatus.COMPLETED, persisted.status)
        assertEquals("chat_turn", persisted.agentDescriptorId)
        assertEquals("digest-run-1", persisted.inputDigest)
        assertEquals(2_000L, persisted.finishedAt)
        db.close()
    }

    @Test
    fun recoverableRunsAreDescriptorScopedAndStablySorted() = runTest {
        val db = open(newPath("recoverable"))
        val store = RoomAgentEventStore(db.agentRuntimeDao())
        listOf(
            run(id = "run-b", startedAt = 100),
            run(id = "run-a", startedAt = 100),
            run(id = "run-c", startedAt = 50),
            run(id = "other", descriptor = "novel", startedAt = 10),
            run(id = "terminal", startedAt = 1),
        ).forEach { assertTrue(store.startRun(it)) }
        assertTrue(
            store.transitionRun(
                runId = AgentRunId("run-b"),
                expectedStatus = AgentRunStatus.RUNNING,
                status = AgentRunStatus.RECOVERY_PENDING,
                inputSnapshotRef = "snapshot-b",
                detail = "background",
                at = 200,
            ),
        )
        assertTrue(
            store.transitionRun(
                runId = AgentRunId("terminal"),
                expectedStatus = AgentRunStatus.RUNNING,
                status = AgentRunStatus.COMPLETED,
                inputSnapshotRef = null,
                detail = null,
                at = 300,
            ),
        )

        val chatRuns = store.listRecoverableRuns(listOf("chat_turn"))
        assertEquals(listOf("run-c", "run-a", "run-b"), chatRuns.map { it.runId })
        assertEquals(AgentRunStatus.RECOVERY_PENDING, chatRuns.last().status)
        assertEquals(listOf("other"), store.listRecoverableRuns(listOf("novel")).map { it.runId })
        db.close()
    }

    @Test
    fun transitionRunUsesCompareAndSetAndOwnsFinishedAt() = runTest {
        val db = open(newPath("transition"))
        val store = RoomAgentEventStore(db.agentRuntimeDao())
        assertTrue(store.startRun(run(id = "run-1")))

        assertTrue(
            store.transitionRun(
                runId = AgentRunId("run-1"),
                expectedStatus = AgentRunStatus.RUNNING,
                status = AgentRunStatus.RECOVERY_PENDING,
                inputSnapshotRef = "snapshot-1",
                detail = "scene_background",
                at = 2_000,
            ),
        )
        val pending = store.getRun(AgentRunId("run-1"))!!
        assertEquals(AgentRunStatus.RECOVERY_PENDING, pending.status)
        assertEquals("snapshot-1", pending.inputSnapshotRef)
        assertNull("non-terminal transitions must clear finishedAt", pending.finishedAt)

        assertFalse(
            "a stale writer must not settle the run",
            store.transitionRun(
                runId = AgentRunId("run-1"),
                expectedStatus = AgentRunStatus.RUNNING,
                status = AgentRunStatus.COMPLETED,
                inputSnapshotRef = null,
                detail = null,
                at = 3_000,
            ),
        )
        assertFalse(
            "same-state rewrites are not lifecycle transitions",
            store.transitionRun(
                runId = AgentRunId("run-1"),
                expectedStatus = AgentRunStatus.RECOVERY_PENDING,
                status = AgentRunStatus.RECOVERY_PENDING,
                inputSnapshotRef = "replacement",
                detail = null,
                at = 3_500,
            ),
        )
        assertTrue(
            store.transitionRun(
                runId = AgentRunId("run-1"),
                expectedStatus = AgentRunStatus.RECOVERY_PENDING,
                status = AgentRunStatus.RUNNING,
                inputSnapshotRef = "snapshot-1",
                detail = null,
                at = 3_750,
            ),
        )
        assertTrue(
            store.transitionRun(
                runId = AgentRunId("run-1"),
                expectedStatus = AgentRunStatus.RUNNING,
                status = AgentRunStatus.COMPLETED,
                inputSnapshotRef = "snapshot-1",
                detail = null,
                at = 4_000,
            ),
        )

        val completed = store.getRun(AgentRunId("run-1"))!!
        assertEquals(AgentRunStatus.COMPLETED, completed.status)
        assertEquals(4_000L, completed.finishedAt)
        assertFalse(
            "terminal runs cannot be reopened",
            store.transitionRun(
                runId = AgentRunId("run-1"),
                expectedStatus = AgentRunStatus.COMPLETED,
                status = AgentRunStatus.RUNNING,
                inputSnapshotRef = null,
                detail = null,
                at = 5_000,
            ),
        )
        db.close()
    }

    @Test
    fun concurrentEventAppendDerivesSequenceAndRunIdentityAndRejectsOrphans() = runTest {
        val path = newPath("events")
        val firstDb = open(path)
        val first = RoomAgentEventStore(firstDb.agentRuntimeDao())
        assertTrue(first.startRun(run(id = "run-1", descriptor = "chat_turn", parentRunId = "parent-1")))
        val secondDb = open(path)
        val second = RoomAgentEventStore(secondDb.agentRuntimeDao())

        val inserted = (1..40).map { index ->
            async {
                val writer = if (index % 2 == 0) first else second
                writer.appendRunEvent(AgentRunId("run-1"), event("event-$index"))
            }
        }.awaitAll()
        assertTrue("all unique concurrent events must be inserted", inserted.all { it })

        val persisted = first.listRunEvents(AgentRunId("run-1"))
        assertEquals((1L..40L).toList(), persisted.map { it.seq })
        assertTrue(persisted.all { it.parentRunId == "parent-1" })
        assertTrue(persisted.all { it.agentDescriptorId == "chat_turn" })
        assertTrue(persisted.all { it.agentVersion == "1.0.0" })
        assertFalse(first.appendRunEvent(AgentRunId("missing"), event("orphan")))
        assertFalse(first.appendRunEvent(AgentRunId("run-1"), event("event-1")))
        assertEquals(40, first.listRunEvents(AgentRunId("run-1")).size)

        secondDb.close()
        firstDb.close()
    }
}
