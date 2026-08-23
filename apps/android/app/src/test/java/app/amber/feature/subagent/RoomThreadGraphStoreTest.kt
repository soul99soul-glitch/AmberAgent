package app.amber.feature.subagent

import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.RoomThreadGraphStore
import kotlinx.coroutines.runBlocking
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
}
