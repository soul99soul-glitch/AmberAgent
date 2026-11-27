package app.amber.feature.macgateway

import app.amber.agent.data.db.entity.ProactiveCardEntity
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProactiveEventProjectionTest {

    private class InMemoryProactiveCardStore {
        private val lock = Any()
        private val rows = LinkedHashMap<Triple<String, String, String>, ProactiveCardEntity>()
        var insertAttempts: Int = 0
        var failOnSignalId: String? = null

        suspend fun insert(entity: ProactiveCardEntity): Long {
            synchronized(lock) {
                insertAttempts += 1
                if (failOnSignalId == entity.signalId) {
                    throw IllegalStateException("simulated room disk failure")
                }
                val key = Triple(entity.gatewayId, entity.deviceId, entity.eventId)
                if (key in rows) return -1L
                rows[key] = entity
                return rows.size.toLong()
            }
        }

        fun all(): List<ProactiveCardEntity> = synchronized(lock) { rows.values.toList() }
    }

    private fun startServer(handler: (String, String, String) -> Pair<Int, String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val body = exchange.requestBody.bufferedReader().readText()
            val (status, payload) = handler(exchange.requestMethod, if (query.isEmpty()) path else "$path?$query", body)
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun mixedPageIsolatesProactiveCardFromDialogueReceiptsWaitingStateTopicMappingAndTimeline() = runBlocking {
        val timeline = CopyOnWriteArrayList<Pair<String, String>>()
        val sink = object : CloudDialogueTimelineSink {
            override suspend fun appendUserMessage(conversationId: String, text: String, eventId: String?) {
                timeline += "user" to text
            }

            override suspend fun appendAssistantReply(conversationId: String, text: String, eventId: String, topicId: String) {
                timeline += "reply:$eventId" to text
            }

            override suspend fun appendTaskResult(
                conversationId: String,
                workItemId: String,
                taskTitle: String?,
                outcome: String?,
                text: String,
                eventId: String,
                topicId: String,
                sourceRefs: List<String>,
                sourcePaths: List<String>,
                outcomeUnknown: Boolean,
            ) {
                timeline += "result:$eventId" to text
            }
        }

        val mixedPage = """
            {
              "schemaVersion": 1,
              "nextCursor": 3,
              "items": [
                {
                  "seq": 1,
                  "id": "ack:in-a:0",
                  "kind": "assistant_reply",
                  "role": "assistant",
                  "text": "开始处理任务 A",
                  "topicId": "topic-a",
                  "conversationId": "conv-a",
                  "causedByInputId": "in-a"
                },
                {
                  "seq": 2,
                  "id": "proactive:wt_release_1",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "Amber v2.0 已发布，包含虚拟屏支持。",
                  "topicId": "proactive:wt_release_1",
                  "causedByInputId": "in-a",
                  "causedByRequestId": "req-a",
                  "proactive": {
                    "signalId": "wt_release_1",
                    "topicKey": "watchtower:github:amber",
                    "category": "WATCHTOWER",
                    "title": "Amber 新版本发布",
                    "urgency": "urgent",
                    "sourceLabel": "网页监控"
                  }
                },
                {
                  "seq": 3,
                  "id": "reply:in-a:1",
                  "kind": "assistant_reply",
                  "role": "assistant",
                  "text": "任务 A 已完成回复。",
                  "topicId": "topic-a",
                  "conversationId": "conv-a",
                  "causedByInputId": "in-a"
                }
              ]
            }
        """.trimIndent()

        var servedMixed = false
        val server = startServer { method, path, _ ->
            when {
                method == "POST" && path == "/v2/dialogue/messages" ->
                    202 to """{"eventId":"in-a","requestId":"req-a","topicId":"topic-a","workItemIds":["work-a"],"state":"received","conversationId":"conv-a"}"""
                method == "GET" && path.startsWith("/v2/dialogue/events") -> {
                    val body = if (!servedMixed) {
                        servedMixed = true
                        mixedPage
                    } else {
                        """{"items":[],"nextCursor":3}"""
                    }
                    200 to body
                }
                method == "GET" && path == "/v2/dialogue/receipts/req-a" ->
                    200 to """{"eventId":"in-a","requestId":"req-a","topicId":"topic-a","workItemIds":["work-a"],"state":"received","conversationId":"conv-a"}"""
                else -> 500 to """{"error":"unexpected $method $path"}"""
            }
        }

        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val cardStore = InMemoryProactiveCardStore()
            val persistedStates = CopyOnWriteArrayList<CloudDialoguePersistedState>()
            var latestUi = CloudDialogueUiState()
            var clock = 10_000L

            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = sink,
                persistState = { persistedStates += it },
                onStateChanged = { latestUi = it },
                persistCoalesceWindowMs = 0L,
                onProactiveCard = { cardStore.insert(it) },
                nowMs = { clock },
            )
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            coordinator.restore(enabled = true, connection = conn, persisted = null)
            coordinator.bindActiveConversation("conv-a")
            coordinator.sendMessage("conv-a", "帮我检查 A", "req-a")

            coordinator.pollOnce()

            // Proactive card was stored once with monotonic receivedAt and NEVER entered timeline or topic mapping.
            val stored = cardStore.all()
            assertEquals(1, stored.size)
            assertEquals("wt_release_1", stored.single().signalId)
            assertEquals("proactive:wt_release_1", stored.single().eventId)
            assertEquals("gw-1", stored.single().gatewayId)
            assertEquals("dev-1", stored.single().deviceId)
            assertEquals(2L, stored.single().seq)
            assertTrue(stored.single().receivedAt > 10_000L)

            // Timeline contains only user message + A's two replies (no proactive card).
            assertEquals(
                listOf("user", "reply:ack:in-a:0", "reply:reply:in-a:1"),
                timeline.map { it.first },
            )

            // Task A remains active/waiting, submission A became CONSUMED only via reply:in-a:1,
            // and topicToConversation does NOT contain proactive topic.
            val taskA = latestUi.tasks.single { it.workItemId == "work-a" }
            assertTrue(taskA.active)
            assertEquals(CloudInputDisposition.CONSUMED, latestUi.submissions.single { it.requestId == "req-a" }.state)
            assertFalse(persistedStates.last().topicToConversation.containsKey("proactive:wt_release_1"))
            assertEquals("conv-a", persistedStates.last().topicToConversation["topic-a"])
            assertEquals(3L, latestUi.cursor)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun duplicatePagesAndRestartReplayStoreProactiveCardOnlyOnceAndDoNotTriggerSoul() = runBlocking {
        val pageJson = """
            {
              "nextCursor": 10,
              "soulRevision": 99,
              "items": [
                {
                  "seq": 10,
                  "id": "proactive:sig_dup_1",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "每日提醒正文",
                  "topicId": "proactive:sig_dup_1",
                  "proactive": {
                    "signalId": "sig_dup_1",
                    "topicKey": "wakeup:wk_1",
                    "category": "CLOCK_CRON",
                    "title": "喝水提醒"
                  }
                }
              ]
            }
        """.trimIndent()
        val server = startServer { method, path, _ ->
            if (method == "GET" && path.startsWith("/v2/dialogue/events")) 200 to pageJson
            else 500 to """{"error":"unexpected"}"""
        }

        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val cardStore = InMemoryProactiveCardStore()
            val soulCalls = AtomicInteger(0)
            var persisted: CloudDialoguePersistedState? = null
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")

            fun newCoordinator() = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { persisted = it },
                onSoulRevision = { _, _ -> soulCalls.incrementAndGet() },
                persistCoalesceWindowMs = 0L,
                onProactiveCard = { cardStore.insert(it) },
            )

            val first = newCoordinator()
            first.restore(enabled = true, connection = conn, persisted = null)
            first.pollOnce()
            // Duplicate page on the same coordinator instance is skipped via seenEventIds.
            first.pollOnce()

            assertEquals(1, cardStore.all().size)
            assertEquals(1, cardStore.insertAttempts)
            assertEquals(0, soulCalls.get())

            // Simulate process restart losing persisted cursor (replaying from cursor 0):
            // composite PK in Room (IGNORE) guarantees only 1 stored card.
            val restarted = newCoordinator()
            restarted.restore(enabled = true, connection = conn, persisted = null)
            restarted.pollOnce()

            assertEquals(1, cardStore.all().size)
            assertEquals(10L, persisted?.cursor)
            assertEquals(0, soulCalls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun malformedProactiveMetaSkipsBadCardAndContinuesRestOfPage() = runBlocking {
        val pageWithBadMeta = """
            {
              "nextCursor": 13,
              "items": [
                {
                  "seq": 11,
                  "id": "proactive:bad_missing_fields",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "SECRET_BODY_SHOULD_NOT_BE_LOGGED",
                  "proactive": {
                    "signalId": "bad_only_id"
                  }
                },
                {
                  "seq": 12,
                  "id": "proactive:bad_type",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "ANOTHER_SECRET",
                  "proactive": "not-an-object"
                },
                {
                  "seq": 13,
                  "id": "proactive:sig_good_1",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "正常提醒内容",
                  "proactive": {
                    "signalId": "sig_good_1",
                    "topicKey": "watchtower:ok",
                    "category": "WATCHTOWER",
                    "title": "正常提醒"
                  }
                }
              ]
            }
        """.trimIndent()
        val server = startServer { method, path, _ ->
            if (method == "GET" && path.startsWith("/v2/dialogue/events")) 200 to pageWithBadMeta
            else 500 to """{"error":"unexpected"}"""
        }

        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val cardStore = InMemoryProactiveCardStore()
            val diagnostics = CopyOnWriteArrayList<String>()
            var latestUi = CloudDialogueUiState()
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = {},
                onStateChanged = { latestUi = it },
                persistCoalesceWindowMs = 0L,
                onProactiveCard = { cardStore.insert(it) },
                onDiagnostic = { diagnostics += it },
            )
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            coordinator.restore(enabled = true, connection = conn, persisted = null)

            coordinator.pollOnce()

            assertEquals(1, cardStore.all().size)
            assertEquals("sig_good_1", cardStore.all().single().signalId)
            assertEquals(13L, latestUi.cursor)
            assertEquals(2, diagnostics.size)
            assertTrue(diagnostics.none { it.contains("SECRET_BODY") || it.contains("ANOTHER_SECRET") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun staleGenerationResponseAfterRePairDoesNotStoreCardOrAdvanceCursorOrTriggerUnauthorized() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val pageJson = """
            {
              "nextCursor": 25,
              "soulRevision": 7,
              "items": [
                {
                  "seq": 25,
                  "id": "proactive:sig_old_gw",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "来自旧网关的迟到卡片",
                  "proactive": {
                    "signalId": "sig_old_gw",
                    "topicKey": "watchtower:old",
                    "category": "WATCHTOWER",
                    "title": "旧网关提醒"
                  }
                }
              ]
            }
        """.trimIndent()
        val server = startServer { method, path, _ ->
            if (method == "GET" && path.startsWith("/v2/dialogue/events")) {
                requestStarted.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
                200 to pageJson
            } else {
                500 to """{"error":"unexpected"}"""
            }
        }

        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val cardStore = InMemoryProactiveCardStore()
            val persistedStates = CopyOnWriteArrayList<CloudDialoguePersistedState>()
            val soulCalls = AtomicInteger(0)
            val generation = AtomicLong(1L)
            var latestUi = CloudDialogueUiState()

            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { persistedStates += it },
                onStateChanged = { latestUi = it },
                onSoulRevision = { _, _ -> soulCalls.incrementAndGet() },
                persistCoalesceWindowMs = 0L,
                onProactiveCard = { cardStore.insert(it) },
                connectionGenerationProvider = { generation.get() },
            )
            val oldConn = MacGatewayConnection("gw-old", "Old", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            val newConn = MacGatewayConnection("gw-new", "New", listOf("127.0.0.1"), server.address.port, "fp", "dev-2")
            coordinator.restore(enabled = true, connection = oldConn, persisted = null)

            val inFlight = async(Dispatchers.IO) { coordinator.pollOnce() }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

            // Simulate re-pairing to a new gateway while old poll is still in flight.
            generation.incrementAndGet()
            coordinator.restore(enabled = true, connection = newConn, persisted = null)
            allowResponse.countDown()
            inFlight.await()

            assertTrue(cardStore.all().isEmpty())
            assertEquals(0L, latestUi.cursor)
            assertTrue(persistedStates.isEmpty())
            assertEquals(0, soulCalls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun changingPairingWhileObtainingClientCannotBindOldClientToNewGeneration() = runBlocking {
        val requests = AtomicInteger()
        val server = startServer { _, _, _ ->
            requests.incrementAndGet()
            401 to "{}"
        }
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "old", scheme = "http")
            val acquiring = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val rejected = AtomicInteger()
            val generation = AtomicLong(1)
            val coordinator = CloudDialogueCoordinator(
                clientProvider = {
                    acquiring.complete(Unit)
                    release.await()
                    client
                },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { error("stale poll wrote a cursor") },
                onUnauthorized = { rejected.incrementAndGet() },
                connectionGenerationProvider = generation::get,
            )
            val first = MacGatewayConnection("A", "A", listOf("127.0.0.1"), server.address.port, "fp", "device")
            coordinator.restore(true, first, null)
            val poll = async { coordinator.pollOnce() }
            acquiring.await()
            generation.incrementAndGet()
            coordinator.restore(true, first.copy(gatewayId = "B"), null)
            release.complete(Unit)
            poll.await()
            assertEquals(0, requests.get())
            assertEquals(0, rejected.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun missingCardWriterKeepsCursorForReplay() = runBlocking {
        val server = startServer { _, _, _ ->
            200 to """{"items":[{"seq":1,"id":"proactive:x","kind":"proactive_message","proactive":{"signalId":"x","topicKey":"topic","category":"test","title":"Card"}}],"nextCursor":1}"""
        }
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "token", scheme = "http")
            var latestUi = CloudDialogueUiState()
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client }, timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { error("cursor advanced without storing the card") },
                onStateChanged = { latestUi = it },
            )
            coordinator.restore(true, MacGatewayConnection("A", "A", listOf("127.0.0.1"), server.address.port, "fp", "D"), null)
            val failure = runCatching { coordinator.pollOnce(reportFailure = true) }.exceptionOrNull()
            assertEquals("Proactive card storage is unavailable", failure?.message)
            assertEquals(0L, latestUi.cursor)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun proactiveCardPersistFailureDoesNotAdvanceCursorPastFailedCard() = runBlocking {
        val pageJson = """
            {
              "nextCursor": 31,
              "items": [
                {
                  "seq": 30,
                  "id": "proactive:sig_fail_1",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "首次落库失败的卡片",
                  "proactive": {
                    "signalId": "sig_fail_1",
                    "topicKey": "watchtower:fail",
                    "category": "WATCHTOWER",
                    "title": "失败重试卡片"
                  }
                },
                {
                  "seq": 31,
                  "id": "proactive:sig_after_1",
                  "kind": "proactive_message",
                  "role": "assistant",
                  "text": "后续卡片",
                  "proactive": {
                    "signalId": "sig_after_1",
                    "topicKey": "watchtower:after",
                    "category": "WATCHTOWER",
                    "title": "后续卡片"
                  }
                }
              ]
            }
        """.trimIndent()
        val server = startServer { method, path, _ ->
            if (method == "GET" && path.startsWith("/v2/dialogue/events")) 200 to pageJson
            else 500 to """{"error":"unexpected"}"""
        }

        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val cardStore = InMemoryProactiveCardStore().apply {
                failOnSignalId = "sig_fail_1"
            }
            val persistedStates = CopyOnWriteArrayList<CloudDialoguePersistedState>()
            var latestUi = CloudDialogueUiState()
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = CloudDialogueTimelineSink.NoOp,
                persistState = { persistedStates += it },
                onStateChanged = { latestUi = it },
                persistCoalesceWindowMs = 0L,
                onProactiveCard = { cardStore.insert(it) },
            )
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            coordinator.restore(enabled = true, connection = conn, persisted = null)

            val thrown = runCatching { coordinator.pollOnce(reportFailure = true) }.exceptionOrNull()
            assertNotNull(thrown)
            assertEquals(0L, latestUi.cursor)
            assertTrue(cardStore.all().isEmpty())
            assertTrue(persistedStates.isEmpty())

            // Next poll succeeds once DB recovers: both cards are now persisted and cursor advances to 31.
            cardStore.failOnSignalId = null
            coordinator.pollOnce(reportFailure = true)

            assertEquals(listOf("sig_fail_1", "sig_after_1"), cardStore.all().map { it.signalId })
            assertEquals(31L, latestUi.cursor)
            assertEquals(31L, persistedStates.last().cursor)
            assertTrue(cardStore.all()[1].receivedAt > cardStore.all()[0].receivedAt)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun proactiveEventMetaDefaultsDecodeWithoutOptionalFields() {
        val raw = """
            {
              "seq": 5,
              "id": "proactive:sig_min",
              "kind": "proactive_message",
              "text": "body",
              "proactive": {
                "signalId": "sig_min",
                "topicKey": "topic:1",
                "category": "WATCHTOWER",
                "title": "Title"
              }
            }
        """.trimIndent()
        val event = Json { ignoreUnknownKeys = true }.decodeFromString(CloudDialogueEvent.serializer(), raw)
        val meta = event.proactive
        assertNotNull(meta)
        assertEquals("sig_min", meta!!.signalId)
        assertEquals("normal", meta.urgency)
        assertFalse(meta.silent)
        assertEquals("", meta.sourceLabel)
        assertEquals(listOf("act", "snooze", "mute"), meta.actions)
        assertNull(meta.checkPrompt)
        assertNull(meta.wakeupId)
        assertNull(meta.originSignalId)
    }
}
