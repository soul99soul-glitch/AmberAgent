package app.amber.feature.subagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-02 pure logic tests for the persisted thread graph (cold-start
 * reconciliation, cancellation, delivery state machine, interrupt retention,
 * cascade target listing). Backed by an in-memory fake [ThreadGraphStore] so
 * no Android/Room stack is needed.
 */
class ThreadGraphManagerTest {

    private val json = Json
    private val definition = SubAgentDefinition(
        id = "explorer",
        name = "Explorer",
        description = "test role",
        systemPrompt = "You explore.",
        toolAllowlist = emptySet(),
    )
    private val task = SubAgentTaskSpec(
        objective = "Find the answer",
        outputFormat = "summary",
        toolsAndSources = "read only",
        boundaries = "no writes",
    )

    private fun node(
        threadId: String,
        status: SubAgentRunStatus,
        rootRunId: String = "parent_run_1",
        conversationId: String = "conv_1",
        parentThreadId: String? = null,
    ) = ThreadNodeRecord(
        threadId = threadId,
        parentThreadId = parentThreadId,
        rootRunId = rootRunId,
        conversationId = conversationId,
        status = status.name,
        task = buildJsonObject {
            put("definition", json.encodeToJsonElement(SubAgentDefinition.serializer(), definition))
            put("task", json.encodeToJsonElement(SubAgentTaskSpec.serializer(), task))
        }.toString(),
        startedAtMs = 1_000,
        updatedAtMs = 1_000,
    )

    private fun manager(store: FakeThreadGraphStore) = ThreadGraphManager(store, json)

    // ── cold-start recovery (wait / completed / cancelled) ────────────────

    @Test
    fun restorePayloadReconcilesStaleRunningThreadToInterrupted() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("thread_1", SubAgentRunStatus.RUNNING))

        val payload = manager.restorePayload("thread_1")!!

        // A RUNNING node with no live run is a process-death victim: the read
        // reconciles it to INTERRUPTED (cold-start recovery of waiting runs).
        assertEquals("interrupted", payload["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("thread_1", payload["run_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(SubAgentRunStatus.INTERRUPTED.name, store.nodes["thread_1"]!!.status)
    }

    @Test
    fun restorePayloadReturnsCompletedResultWithFinalAnswer() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("thread_1", SubAgentRunStatus.COMPLETED))
        store.upsertResult(
            ThreadResultRecord(
                threadId = "thread_1",
                finalAnswer = "The answer is 42.",
                artifactsJson = "{}",
                terminalReason = "completed",
                finishedAtMs = 2_000,
            )
        )

        val payload = manager.restorePayload("thread_1")!!

        // Completed thread: final answer readable after restart (child final
        // answer back to the parent run).
        assertEquals("completed", payload["status"]?.jsonPrimitive?.contentOrNull)
        val result = payload["result"]?.jsonPrimitive?.contentOrNull
        assertNotNull(result)
        assertTrue(result!!.contains("The answer is 42."))
        assertEquals("Explorer", payload["subagent_name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun cancelPersistedMarksStaleThreadCancelled() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("thread_1", SubAgentRunStatus.RUNNING))

        val payload = manager.cancelPersisted("thread_1")!!

        // Cold-start cancellation: a stale running thread becomes CANCELLED
        // with a terminal result.
        assertEquals("cancelled", payload["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(SubAgentRunStatus.CANCELLED.name, store.nodes["thread_1"]!!.status)
        assertEquals(
            "cancelled",
            store.results["thread_1"]!!.terminalReason,
        )
        // A second cancel is idempotent.
        assertEquals("cancelled", manager.cancelPersisted("thread_1")!!["status"]?.jsonPrimitive?.contentOrNull)
    }

    // ── followup / send_message delivery states ───────────────────────────

    @Test
    fun followupMessageMovesQueuedDeliveredPersisted() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("thread_1", SubAgentRunStatus.COMPLETED))

        // followup_task: enqueued in QUEUED state.
        val followup = manager.enqueueFollowup("thread_1", task)
        assertEquals(ThreadDeliveryState.QUEUED.name, followup.deliveryState)

        // The followup generation starts → queued messages are delivered.
        val drained = manager.drainQueued("thread_1")
        assertEquals(1, drained.size)
        assertEquals(ThreadDeliveryState.DELIVERED.name, store.messages[followup.messageId]!!.deliveryState)

        // The turn's result lands → delivered messages become persisted.
        manager.finishNode(
            runId = "thread_1",
            status = SubAgentRunStatus.COMPLETED,
            result = SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "done"),
            displayText = "done",
        )
        assertEquals(ThreadDeliveryState.PERSISTED.name, store.messages[followup.messageId]!!.deliveryState)
    }

    @Test
    fun drainQueuedClaimsEachMessageOnlyOnce() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("thread_1", SubAgentRunStatus.COMPLETED))
        manager.enqueueMessage("thread_1", "claim me")

        val first = manager.drainQueued("thread_1")
        val second = manager.drainQueued("thread_1")

        assertEquals(1, first.size)
        assertEquals(ThreadDeliveryState.DELIVERED.name, first.single().deliveryState)
        assertTrue(second.isEmpty())
    }

    @Test
    fun sendMessageQueuedWhenIdleAndPersistedAfterResult() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("thread_1", SubAgentRunStatus.COMPLETED))

        // send_message to an idle thread: stays queued until the thread runs.
        val message = manager.enqueueMessage("thread_1", "keep going")
        assertEquals(ThreadDeliveryState.QUEUED.name, message.deliveryState)
        assertTrue(message.payloadDigest.isNotBlank())

        // Delivered when the thread next runs (followup), persisted with the result.
        manager.markDelivered(message.messageId)
        assertEquals(ThreadDeliveryState.DELIVERED.name, store.messages[message.messageId]!!.deliveryState)
        manager.finishNode(
            runId = "thread_1",
            status = SubAgentRunStatus.COMPLETED,
            result = SubAgentResult(status = SubAgentRunStatus.COMPLETED),
            displayText = "",
        )
        assertEquals(ThreadDeliveryState.PERSISTED.name, store.messages[message.messageId]!!.deliveryState)
    }

    // ── interrupt keeps the thread ────────────────────────────────────────

    @Test
    fun finishNodeInterruptedKeepsThreadAndTerminalReason() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("thread_1", SubAgentRunStatus.RUNNING))

        // interrupt: the thread is preserved as INTERRUPTED with a result —
        // never deleted, never COMPLETED/CANCELLED.
        manager.finishNode(
            runId = "thread_1",
            status = SubAgentRunStatus.INTERRUPTED,
            result = SubAgentResult(status = SubAgentRunStatus.INTERRUPTED),
            displayText = "partial progress",
        )

        val state = manager.getState("thread_1")!!
        assertEquals(SubAgentRunStatus.INTERRUPTED, state.status)
        assertEquals("partial progress", state.finalAnswer)
        assertEquals("interrupted", store.results["thread_1"]!!.terminalReason)
    }

    // ── cascade target listing ────────────────────────────────────────────

    @Test
    fun listByRootRunFindsCascadeTargets() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("child_a", SubAgentRunStatus.RUNNING, rootRunId = "parent_run_1"))
        store.upsertNode(node("child_b", SubAgentRunStatus.RUNNING, rootRunId = "parent_run_1"))
        store.upsertNode(node("other", SubAgentRunStatus.RUNNING, rootRunId = "parent_run_2"))

        val targets = manager.listByRootRun("parent_run_1")
        assertEquals(listOf("child_a", "child_b"), targets.map { it.threadId })
    }

    @Test
    fun unknownThreadReturnsNull() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        assertNull(manager.restorePayload("ghost"))
        assertNull(manager.cancelPersisted("ghost"))
        assertNull(manager.getState("ghost"))
    }

    @Test
    fun parentChainAllowsGrandchildAndRejectsGreatGrandchild() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("child", SubAgentRunStatus.COMPLETED))
        store.upsertNode(
            node(
                threadId = "grandchild",
                status = SubAgentRunStatus.COMPLETED,
                parentThreadId = "child",
            )
        )

        assertEquals(
            "parent_run_1",
            manager.prepareStart("child", "fallback").rootRunId,
        )

        try {
            manager.prepareStart("grandchild", "fallback")
            throw AssertionError("A third-generation child must be rejected")
        } catch (error: ThreadGraphManager.ThreadGraphDepthLimitException) {
            assertEquals("grandchild", error.parentThreadId)
            assertEquals(2, error.parentDepth)
        }
    }

    @Test
    fun prepareStartUsesPersistedParentAndRoot() = runBlocking {
        val store = FakeThreadGraphStore()
        val manager = manager(store)
        store.upsertNode(node("child", SubAgentRunStatus.COMPLETED, rootRunId = "root_run"))

        val context = manager.prepareStart("child", "fallback_root")

        assertEquals("root_run", context.rootRunId)
        assertEquals("child", context.parentThreadId)
    }
}

/** In-memory [ThreadGraphStore] fake for the pure-logic tests. */
class FakeThreadGraphStore : ThreadGraphStore {
    val nodes = mutableMapOf<String, ThreadNodeRecord>()
    val messages = mutableMapOf<String, ThreadMessageRecord>()
    val results = mutableMapOf<String, ThreadResultRecord>()

    override suspend fun upsertNode(node: ThreadNodeRecord) {
        nodes[node.threadId] = node
    }

    override suspend fun getNode(threadId: String): ThreadNodeRecord? = nodes[threadId]

    override suspend fun listNodesByRootRun(rootRunId: String): List<ThreadNodeRecord> =
        nodes.values.filter { it.rootRunId == rootRunId }.sortedBy { it.startedAtMs }

    override suspend fun enqueueMessage(message: ThreadMessageRecord) {
        messages[message.messageId] = message
    }

    override suspend fun getMessage(messageId: String): ThreadMessageRecord? = messages[messageId]

    override suspend fun listMessages(threadId: String): List<ThreadMessageRecord> =
        messages.values.filter { it.threadId == threadId }.sortedBy { it.createdAtMs }

    override suspend fun listQueuedMessages(threadId: String): List<ThreadMessageRecord> =
        listMessages(threadId).filter { it.deliveryState == ThreadDeliveryState.QUEUED.name }

    override suspend fun claimQueuedMessages(threadId: String): List<ThreadMessageRecord> {
        val claimed = listQueuedMessages(threadId).map {
            it.copy(deliveryState = ThreadDeliveryState.DELIVERED.name)
        }
        claimed.forEach { messages[it.messageId] = it }
        return claimed
    }

    override suspend fun requeueDeliveredMessages(threadId: String): Int {
        var count = 0
        messages.forEach { (id, message) ->
            if (message.threadId == threadId && message.deliveryState == ThreadDeliveryState.DELIVERED.name) {
                messages[id] = message.copy(deliveryState = ThreadDeliveryState.QUEUED.name)
                count++
            }
        }
        return count
    }

    override suspend fun markMessageDelivered(messageId: String) {
        val message = messages[messageId] ?: return
        if (message.deliveryState != ThreadDeliveryState.QUEUED.name) return
        messages[messageId] = message.copy(deliveryState = ThreadDeliveryState.DELIVERED.name)
    }

    override suspend fun markDeliveredMessagesPersisted(threadId: String): Int {
        var count = 0
        messages.forEach { (id, message) ->
            if (message.threadId == threadId && message.deliveryState == ThreadDeliveryState.DELIVERED.name) {
                messages[id] = message.copy(deliveryState = ThreadDeliveryState.PERSISTED.name)
                count++
            }
        }
        return count
    }

    override suspend fun upsertResult(result: ThreadResultRecord) {
        results[result.threadId] = result
    }

    override suspend fun getResult(threadId: String): ThreadResultRecord? = results[threadId]
}
