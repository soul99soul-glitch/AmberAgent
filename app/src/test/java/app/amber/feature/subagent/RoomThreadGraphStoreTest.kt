package app.amber.feature.subagent

import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.RoomThreadGraphStore
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * P4-02 Room persistence tests: thread nodes / messages / results live in
 * SQLite tables (schema v13), so a fresh store instance over the same
 * database reads them back — the "cold-start restart" contract.
 */
class RoomThreadGraphStoreTest : DurableRuntimeTestBase() {

    private fun node(threadId: String, status: String = "RUNNING") = ThreadNodeRecord(
        threadId = threadId,
        parentThreadId = null,
        rootRunId = "parent_run_1",
        conversationId = "conv_1",
        status = status,
        task = """{"definition":{},"task":{}}""",
        startedAtMs = 1_000,
        updatedAtMs = 1_000,
    )

    @Test
    fun nodeAndResultSurviveStoreRecreation() = runBlocking {
        val first = RoomThreadGraphStore(database.threadGraphDao())
        first.upsertNode(node("thread_1", status = "COMPLETED"))
        first.upsertResult(
            ThreadResultRecord(
                threadId = "thread_1",
                finalAnswer = "durable answer",
                artifactsJson = """{"findings":["a"]}""",
                terminalReason = "completed",
                finishedAtMs = 2_000,
            )
        )
        first.enqueueMessage(
            ThreadMessageRecord(
                messageId = "msg_1",
                threadId = "thread_1",
                sender = "parent",
                recipient = "thread:thread_1",
                kind = "message",
                payload = "hello",
                payloadDigest = "abc",
                deliveryState = ThreadDeliveryState.QUEUED.name,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
            )
        )

        // "Restart": a brand-new store instance over the same database.
        val second = RoomThreadGraphStore(database.threadGraphDao())
        val node = second.getNode("thread_1")
        assertNotNull(node)
        assertEquals("COMPLETED", node!!.status)
        assertEquals("parent_run_1", node.rootRunId)

        val result = second.getResult("thread_1")
        assertNotNull(result)
        assertEquals("durable answer", result!!.finalAnswer)
        assertEquals("completed", result.terminalReason)
        assertEquals(1, second.listNodesByRootRun("parent_run_1").size)
    }

    @Test
    fun messageDeliveryStatesArePersisted() = runBlocking {
        val store = RoomThreadGraphStore(database.threadGraphDao())
        store.upsertNode(node("thread_1"))
        val record = store.enqueueMessage(
            ThreadMessageRecord(
                messageId = "msg_1",
                threadId = "thread_1",
                sender = "parent",
                recipient = "thread:thread_1",
                kind = "followup",
                payload = "continue",
                payloadDigest = "digest",
                deliveryState = ThreadDeliveryState.QUEUED.name,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
            )
        )

        assertEquals(1, store.listQueuedMessages("thread_1").size)

        store.markMessageDelivered("msg_1")
        assertEquals(
            ThreadDeliveryState.DELIVERED.name,
            store.listMessages("thread_1").single().deliveryState,
        )

        store.markDeliveredMessagesPersisted("thread_1")
        assertEquals(
            ThreadDeliveryState.PERSISTED.name,
            store.listMessages("thread_1").single().deliveryState,
        )
        assertEquals(0, store.listQueuedMessages("thread_1").size)
    }

    @Test
    fun claimQueuedMessagesIsSingleConsumer() = runBlocking {
        val store = RoomThreadGraphStore(database.threadGraphDao())
        store.upsertNode(node("thread_1"))
        store.enqueueMessage(
            ThreadMessageRecord(
                messageId = "msg_claim",
                threadId = "thread_1",
                sender = "parent",
                recipient = "thread:thread_1",
                kind = "message",
                payload = "claim once",
                payloadDigest = "digest",
                deliveryState = ThreadDeliveryState.QUEUED.name,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
            )
        )

        val first = store.claimQueuedMessages("thread_1")
        val second = store.claimQueuedMessages("thread_1")

        assertEquals(1, first.size)
        assertEquals(ThreadDeliveryState.DELIVERED.name, first.single().deliveryState)
        assertEquals(0, second.size)
        assertEquals(ThreadDeliveryState.DELIVERED.name, store.getMessage("msg_claim")!!.deliveryState)
    }

    @Test
    fun concurrentDrainClaimsEachQueuedMessageOnce() = runBlocking {
        val store = RoomThreadGraphStore(database.threadGraphDao())
        store.upsertNode(node("thread_concurrent"))
        repeat(24) { index ->
            store.enqueueMessage(
                ThreadMessageRecord(
                    messageId = "msg_concurrent_$index",
                    threadId = "thread_concurrent",
                    sender = "parent",
                    recipient = "thread:thread_concurrent",
                    kind = "message",
                    payload = "message $index",
                    payloadDigest = "digest-$index",
                    deliveryState = ThreadDeliveryState.QUEUED.name,
                    createdAtMs = 1_000L + index,
                    updatedAtMs = 1_000L + index,
                )
            )
        }

        val claims = coroutineScope {
            (0 until 12).map {
                async(Dispatchers.IO) { store.claimQueuedMessages("thread_concurrent") }
            }.awaitAll().flatten()
        }

        assertEquals(24, claims.size)
        assertEquals(24, claims.map { it.messageId }.toSet().size)
        assertEquals(
            24,
            store.listMessages("thread_concurrent")
                .count { it.deliveryState == ThreadDeliveryState.DELIVERED.name },
        )
    }

    @Test
    fun queuedMessageSurvivesStoreRecreationUntilAThreadClaimsIt() = runBlocking {
        val first = RoomThreadGraphStore(database.threadGraphDao())
        first.upsertNode(node("thread_restart"))
        first.enqueueMessage(
            ThreadMessageRecord(
                messageId = "msg_restart",
                threadId = "thread_restart",
                sender = "parent",
                recipient = "thread:thread_restart",
                kind = "message",
                payload = "survive restart",
                payloadDigest = "digest",
                deliveryState = ThreadDeliveryState.QUEUED.name,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
            )
        )

        // A process restart between enqueue and generation must leave the
        // message queued; the next owner can claim it exactly once.
        val restarted = RoomThreadGraphStore(database.threadGraphDao())
        assertEquals("survive restart", restarted.listQueuedMessages("thread_restart").single().payload)
        val claimed = restarted.claimQueuedMessages("thread_restart")
        assertEquals("msg_restart", claimed.single().messageId)
        assertEquals(ThreadDeliveryState.DELIVERED.name, restarted.getMessage("msg_restart")!!.deliveryState)
    }

    @Test
    fun captureWriteContextPreservesCallerEpochOrCapturesCurrentGateEpoch() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val store = RoomThreadGraphStore(database.threadGraphDao(), gate)

        val captured = store.captureWriteContext()
        assertEquals(gate.currentEpoch(), captured[SyncRestoreWriteEpoch]?.value)

        val callerEpoch = SyncRestoreWriteEpoch(41L)
        val inherited = withContext(callerEpoch) {
            store.captureWriteContext()
        }
        assertEquals(41L, inherited[SyncRestoreWriteEpoch]?.value)
    }
}
