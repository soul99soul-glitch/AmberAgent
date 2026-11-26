package app.amber.feature.macgateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudDialoguePersistCoalescingTest {

    private fun startMockEventsServer(
        pages: ArrayDeque<String>,
        receiptStatusAndBody: (String) -> Pair<Int, String> = { reqId ->
            200 to """{"eventId":"evt-$reqId","requestId":"$reqId","topicId":"t-1","workItemIds":["w-1"],"state":"received","conversationId":"conv-1"}"""
        },
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (status, payload) = when {
                exchange.requestMethod == "POST" && path == "/v2/dialogue/messages" ->
                    200 to """{"eventId":"evt-msg-1","requestId":"req-msg-1","topicId":"t-1","workItemIds":["w-1"],"state":"received","conversationId":"conv-1"}"""
                exchange.requestMethod == "POST" && path == "/v2/work-items/w-1/stop" ->
                    200 to """{"eventId":"evt-stop-1","requestId":"req-stop-1","topicId":"t-1","workItemIds":["w-1"],"state":"consumed","conversationId":"conv-1"}"""
                exchange.requestMethod == "GET" && path == "/v2/dialogue/events" ->
                    200 to (pages.removeFirstOrNull() ?: """{"items":[],"nextCursor":0}""")
                exchange.requestMethod == "GET" && path.startsWith("/v2/dialogue/receipts/") ->
                    receiptStatusAndBody(path.substringAfterLast('/'))
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun durableBarriersPersistImmediatelyWithoutWaitingCoalesceWindow() = runBlocking {
        val pages = ArrayDeque<String>()
        pages += """
            {
              "nextCursor": 1,
              "items": [
                {
                  "seq": 1,
                  "id": "evt-result-1",
                  "kind": "task_result",
                  "role": "assistant",
                  "text": "All done",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1",
                  "outcome": "completed"
                }
              ]
            }
        """.trimIndent()
        val server = startMockEventsServer(pages)
        val persistScope = TestScope()
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val persisted = CopyOnWriteArrayList<CloudDialoguePersistedState>()
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { persisted += it },
                persistScope = persistScope.backgroundScope,
                persistCoalesceWindowMs = 300L,
            )
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            coordinator.restore(enabled = true, connection = conn, persisted = null)
            coordinator.bindActiveConversation("conv-1")

            // Input acceptance (sendMessage) persists immediately without advancing coalesce timer.
            coordinator.sendMessage("conv-1", "Run check", "req-msg-1")
            val writesAfterSend = persisted.size
            assertTrue("sendMessage must persist immediately", writesAfterSend >= 2)
            assertEquals("evt-msg-1", persisted.last().submissions.single { it.requestId == "req-msg-1" }.eventId)

            // Stop request persists immediately.
            coordinator.stopWorkItem("w-1", "req-stop-1")
            val writesAfterStop = persisted.size
            assertTrue("stopWorkItem must persist immediately", writesAfterStop > writesAfterSend)

            // Terminal settlement (task_result) persists immediately without advancing virtual time.
            coordinator.pollOnce()
            assertTrue("task_result must persist immediately", persisted.size > writesAfterStop)
            assertEquals(1L, persisted.last().cursor)
            assertFalse(persisted.last().tasks.single { it.workItemId == "w-1" }.active)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun nonDurableProgressUpdatesWithin300msCoalesceIntoSingleLatestWinsWrite() = runBlocking {
        val pages = ArrayDeque<String>()
        for (i in 1..4) {
            pages += """
                {
                  "nextCursor": $i,
                  "items": [
                    {
                      "seq": $i,
                      "id": "proc-$i",
                      "kind": "process_event",
                      "role": "system",
                      "text": "step $i",
                      "topicId": "t-1",
                      "workItemId": "w-1",
                      "conversationId": "conv-1"
                    }
                  ]
                }
            """.trimIndent()
        }
        val server = startMockEventsServer(pages)
        val persistScope = TestScope()
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val persisted = CopyOnWriteArrayList<CloudDialoguePersistedState>()
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { persisted += it },
                persistScope = persistScope.backgroundScope,
                persistCoalesceWindowMs = 300L,
            )
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            coordinator.restore(enabled = true, connection = conn, persisted = null)
            coordinator.bindActiveConversation("conv-1")

            // 4 high-frequency process_event updates within 200ms (< 300ms window).
            coordinator.pollOnce() // t=0
            persistScope.runCurrent()
            persistScope.advanceTimeBy(50L)
            persistScope.runCurrent()

            coordinator.pollOnce() // t=50
            persistScope.advanceTimeBy(70L)
            persistScope.runCurrent()

            coordinator.pollOnce() // t=120
            persistScope.advanceTimeBy(80L)
            persistScope.runCurrent()

            coordinator.pollOnce() // t=200
            persistScope.runCurrent()

            // Still inside the 300ms coalesce window: 0 writes so far.
            assertEquals(0, persisted.size)

            // Cross t=300ms: exactly 1 coalesced write carrying the latest state (step 4).
            persistScope.advanceTimeBy(100L)
            persistScope.runCurrent()
            assertEquals(1, persisted.size)
            val snapshot = persisted.single()
            assertEquals(4L, snapshot.cursor)
            val task = snapshot.tasks.single { it.workItemId == "w-1" }
            assertEquals("step 4", task.progressText)
            assertEquals(4, task.processEventCount)

            // No extra write after the window settles.
            persistScope.advanceTimeBy(1_000L)
            persistScope.runCurrent()
            assertEquals(1, persisted.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun durableBarrierFlushesPendingCoalescedStateImmediatelyAndCancelsTimer() = runBlocking {
        val pages = ArrayDeque<String>()
        pages += """
            {
              "nextCursor": 1,
              "items": [
                {
                  "seq": 1,
                  "id": "proc-1",
                  "kind": "process_event",
                  "role": "system",
                  "text": "working...",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1"
                }
              ]
            }
        """.trimIndent()
        pages += """
            {
              "nextCursor": 2,
              "items": [
                {
                  "seq": 2,
                  "id": "res-1",
                  "kind": "task_result",
                  "role": "assistant",
                  "text": "Finished",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1",
                  "outcome": "completed"
                }
              ]
            }
        """.trimIndent()
        val server = startMockEventsServer(pages)
        val persistScope = TestScope()
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val persisted = CopyOnWriteArrayList<CloudDialoguePersistedState>()
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { persisted += it },
                persistScope = persistScope.backgroundScope,
                persistCoalesceWindowMs = 300L,
            )
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            coordinator.restore(enabled = true, connection = conn, persisted = null)
            coordinator.bindActiveConversation("conv-1")

            coordinator.pollOnce() // process_event at t=0
            persistScope.runCurrent()
            persistScope.advanceTimeBy(100L)
            persistScope.runCurrent()
            assertEquals(0, persisted.size)

            coordinator.pollOnce() // durable task_result at t=100ms
            assertEquals(1, persisted.size)
            assertEquals(2L, persisted.single().cursor)
            assertEquals("Finished", persisted.single().tasks.single().resultText)

            // Advancing past original 300ms timer produces no duplicate write.
            persistScope.advanceTimeBy(500L)
            persistScope.runCurrent()
            assertEquals(1, persisted.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun pruningDropsExcessSettledHistoryWhilePreservingAllUnsettledAndOutcomeUnknownRecords() = runBlocking {
        val pages = ArrayDeque<String>()
        pages += """
            {
              "nextCursor": 100,
              "items": [
                {
                  "seq": 100,
                  "id": "evt-trigger-prune",
                  "kind": "task_result",
                  "role": "assistant",
                  "text": "Trigger settled",
                  "topicId": "t-settled-5",
                  "workItemId": "w-settled-5",
                  "conversationId": "conv-1",
                  "outcome": "completed"
                }
              ]
            }
        """.trimIndent()
        // Keep pending receipts unconfirmed during this poll so pruning preserves them as unsettled.
        val server = startMockEventsServer(pages) { _ ->
            404 to """{"error":"receipt_pending"}"""
        }
        val persistScope = TestScope()
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val persisted = CopyOnWriteArrayList<CloudDialoguePersistedState>()
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { persisted += it },
                persistScope = persistScope.backgroundScope,
                persistCoalesceWindowMs = 300L,
                maxSettledTasks = 2,
                maxSettledSubmissions = 2,
                maxTopicMappings = 2,
            )
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")

            val settledTasks = (1..4).map { idx ->
                CloudTaskSession(
                    workItemId = "w-settled-$idx",
                    topicId = "t-settled-$idx",
                    conversationId = "conv-1",
                    title = "Settled $idx",
                    active = false,
                    outcome = "completed",
                    updatedAtMs = idx * 1_000L,
                )
            }
            val activeTask = CloudTaskSession(
                workItemId = "w-active",
                topicId = "t-active",
                conversationId = "conv-1",
                title = "Running",
                active = true,
                updatedAtMs = 10L, // Very old timestamp to prove timestamp alone never prunes active tasks
            )
            val outcomeUnknownTask = CloudTaskSession(
                workItemId = "w-unknown",
                topicId = "t-unknown",
                conversationId = "conv-1",
                title = "Unknown outcome",
                active = false,
                outcome = "failed",
                outcomeUnknown = true,
                outcomeUnknownAbandoned = false,
                updatedAtMs = 20L, // Very old timestamp to prove it is never pruned
            )

            val settledSubs = (1..4).map { idx ->
                CloudDialogueSubmission(
                    requestId = "req-settled-$idx",
                    eventId = "evt-settled-$idx",
                    topicId = "t-settled-$idx",
                    conversationId = "conv-1",
                    kind = CloudSubmissionKind.MESSAGE,
                    text = "Settled msg $idx",
                    workItemIds = listOf("w-settled-$idx"),
                    state = CloudInputDisposition.CONSUMED,
                    receiptSyncedAfterConsumed = true,
                    updatedAtMs = idx * 1_000L,
                )
            }
            // Original MESSAGE submission for outcomeUnknown task (already consumed+synced, but needed for retry!)
            val unknownTaskOriginalSub = CloudDialogueSubmission(
                requestId = "req-unknown-orig",
                eventId = "evt-unknown-orig",
                topicId = "t-unknown",
                conversationId = "conv-1",
                kind = CloudSubmissionKind.MESSAGE,
                text = "Original prompt for unknown task",
                workItemIds = listOf("w-unknown"),
                state = CloudInputDisposition.CONSUMED,
                receiptSyncedAfterConsumed = true,
                updatedAtMs = 5L,
            )
            val receivedSub = CloudDialogueSubmission(
                requestId = "req-received",
                eventId = "evt-received",
                topicId = "t-received",
                conversationId = "conv-1",
                kind = CloudSubmissionKind.FOLLOWUP,
                text = "Still received",
                state = CloudInputDisposition.RECEIVED,
                receiptSyncedAfterConsumed = false,
                updatedAtMs = 6L,
            )
            val unconfirmedReceiptSub = CloudDialogueSubmission(
                requestId = "req-unconfirmed",
                eventId = "evt-unconfirmed",
                topicId = "t-unconfirmed",
                conversationId = "conv-1",
                kind = CloudSubmissionKind.MESSAGE,
                text = "Consumed but receipt not synced",
                state = CloudInputDisposition.CONSUMED,
                receiptSyncedAfterConsumed = false,
                updatedAtMs = 7L,
            )
            val noEventIdSub = CloudDialogueSubmission(
                requestId = "req-no-event",
                eventId = null,
                topicId = null,
                conversationId = "conv-1",
                kind = CloudSubmissionKind.MESSAGE,
                text = "No server receipt yet",
                state = CloudInputDisposition.CONSUMED,
                receiptSyncedAfterConsumed = true,
                updatedAtMs = 8L,
            )
            val outcomeUnknownSub = CloudDialogueSubmission(
                requestId = "req-sub-unknown",
                eventId = "evt-sub-unknown",
                topicId = "t-sub-unknown",
                conversationId = "conv-1",
                kind = CloudSubmissionKind.MESSAGE,
                text = "Submission marked outcomeUnknown",
                state = CloudInputDisposition.CONSUMED,
                receiptSyncedAfterConsumed = true,
                outcomeUnknown = true,
                updatedAtMs = 9L,
            )

            val initialState = CloudDialoguePersistedState(
                gatewayId = "gw-1",
                deviceId = "dev-1",
                cursor = 10L,
                topicToConversation = mapOf(
                    "t-settled-1" to "conv-1",
                    "t-settled-2" to "conv-1",
                    "t-settled-3" to "conv-1",
                    "t-settled-4" to "conv-1",
                    "t-active" to "conv-1",
                    "t-unknown" to "conv-1",
                ),
                tasks = settledTasks + activeTask + outcomeUnknownTask,
                submissions = settledSubs + listOf(
                    unknownTaskOriginalSub,
                    receivedSub,
                    unconfirmedReceiptSub,
                    noEventIdSub,
                    outcomeUnknownSub,
                ),
            )

            coordinator.restore(enabled = true, connection = conn, persisted = initialState)
            coordinator.bindActiveConversation("conv-1")
            coordinator.pollOnce()

            val after = persisted.last()
            val taskIds = after.tasks.map { it.workItemId }.toSet()
            val subIds = after.submissions.map { it.requestId }.toSet()

            // Protected tasks MUST all be retained.
            assertTrue("Active task must never be pruned", "w-active" in taskIds)
            assertTrue("outcomeUnknown task must never be pruned", "w-unknown" in taskIds)

            // Only the 2 newest settled tasks (w-settled-4 and w-settled-5) remain; oldest 3 pruned.
            assertEquals(setOf("w-active", "w-unknown", "w-settled-4", "w-settled-5"), taskIds)

            // Protected submissions MUST all be retained.
            assertTrue("Original submission for outcomeUnknown task must be retained", "req-unknown-orig" in subIds)
            assertTrue("RECEIVED submission must never be pruned", "req-received" in subIds)
            assertTrue("Unconfirmed receipt submission must never be pruned", "req-unconfirmed" in subIds)
            assertTrue("Submission without eventId must never be pruned", "req-no-event" in subIds)
            assertTrue("outcomeUnknown submission must never be pruned", "req-sub-unknown" in subIds)

            // Excess oldest settled submissions (req-settled-1, req-settled-2) were pruned; newest 2 kept.
            assertFalse("Oldest settled submission should be pruned", "req-settled-1" in subIds)
            assertFalse("Second oldest settled submission should be pruned", "req-settled-2" in subIds)
            assertTrue("Newest settled submissions should be kept", "req-settled-3" in subIds)
            assertTrue("Newest settled submissions should be kept", "req-settled-4" in subIds)

            // Referenced protected topics remain mapped.
            assertEquals("conv-1", after.topicToConversation["t-active"])
            assertEquals("conv-1", after.topicToConversation["t-unknown"])
        } finally {
            server.stop(0)
        }
    }
}
