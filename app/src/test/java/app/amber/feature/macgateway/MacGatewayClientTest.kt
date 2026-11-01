package app.amber.feature.macgateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacGatewayClientTest {
    private fun encode(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private val validJson =
        """{"v":1,"id":"gw","name":"Studio","addrs":["studio.local","192.168.1.5"],"port":47821,"fp":"abc=","s":"secret"}"""

    @Test
    fun parsesTheFullLinkAndTheBareValue() {
        val encoded = encode(validJson)
        val fromLink = MacGatewayPairingPayload.parse("amber://gateway/pair?p=$encoded")
        assertEquals(listOf("studio.local", "192.168.1.5"), fromLink?.addrs)
        assertEquals("secret", fromLink?.s)
        assertEquals(fromLink, MacGatewayPairingPayload.parse("  $encoded\n"))
    }

    @Test
    fun rejectsGarbageAndUnknownVersions() {
        assertNull(MacGatewayPairingPayload.parse(""))
        assertNull(MacGatewayPairingPayload.parse("amber://gateway/pair?p=!!!"))
        assertNull(MacGatewayPairingPayload.parse(encode(validJson.replace("\"v\":1", "\"v\":2"))))
        assertNull(MacGatewayPairingPayload.parse(encode(validJson.replace("[\"studio.local\",\"192.168.1.5\"]", "[]"))))
        assertNull(MacGatewayPairingPayload.parse("amber://gateway/pair?p=" + "A".repeat(5000)))
    }

    @Test
    fun reportsUnreachableWhenNoAddressAnswers() {
        val client = MacGatewayClient(listOf("127.0.0.1"), port = 1, fingerprint = "abc=", token = "t")
        val error = runCatching { runBlocking { client.status() } }.exceptionOrNull()
        assertTrue(error is MacGatewayException.Unreachable)
    }

    // ---------------------------------------------------------------------------------------
    // P4 / A4.2: the Mac owns the task list but not its order. The phone must present it stably,
    // because the 10 s refresh would otherwise reshuffle rows and `take(limit)` could hide a task
    // that is waiting for a human decision.
    // ---------------------------------------------------------------------------------------

    private fun session(
        key: String,
        state: String,
        updatedAt: Double = 0.0,
        abnormal: Boolean = false,
    ) = MacGatewayStatus.Session(
        key = key,
        agent = "codex",
        subject = "task $key",
        origin = "cli",
        state = state,
        abnormal = abnormal,
        monitored = false,
        updatedAt = updatedAt,
    )

    @Test
    fun waitingTasksSortAboveRunningAndFinishedOnes() {
        val sorted = MacGatewaySessionOrdering.sort(
            listOf(
                session("done", "completed", updatedAt = 900.0),
                session("stalled", "stalled", updatedAt = 800.0),
                session("run", "running", updatedAt = 700.0),
                session("wait", "waiting", updatedAt = 100.0),
            )
        )
        assertEquals(listOf("wait", "run", "stalled", "done"), sorted.map { it.key })
    }

    @Test
    fun orderingIsStableWhenTheGatewayReturnsTheSameSessionsInAnotherOrder() {
        val a = session("a", "running", updatedAt = 500.0)
        val b = session("b", "running", updatedAt = 500.0)
        val first = MacGatewaySessionOrdering.sort(listOf(a, b)).map { it.key }
        val second = MacGatewaySessionOrdering.sort(listOf(b, a)).map { it.key }
        assertEquals(first, second)
        assertEquals(listOf("a", "b"), first)
    }

    @Test
    fun theVisibleLimitNeverDropsAWaitingTaskInFavourOfAFinishedOne() {
        val sessions = listOf(
            session("done-1", "completed", updatedAt = 999.0),
            session("done-2", "completed", updatedAt = 998.0),
            session("wait", "waiting", updatedAt = 1.0),
        )
        val visible = MacGatewaySessionOrdering.visible(sessions, limit = 1)
        assertEquals(listOf("wait"), visible.map { it.key })
    }

    @Test
    fun abnormalSessionsRankWithStalledOnesAndUnknownStatesStayVisible() {
        val sorted = MacGatewaySessionOrdering.sort(
            listOf(
                session("finished", "completed", updatedAt = 10.0),
                session("weird", "some_future_state", updatedAt = 20.0),
                session("bad", "running", updatedAt = 30.0, abnormal = true),
            )
        )
        assertEquals(listOf("bad", "weird", "finished"), sorted.map { it.key })
    }

    @Test
    fun parsesSessionSummaryAndPendingApprovalAndBlocksHighRiskAllowOnClient() {
        val rawStatus = """
            {
              "gateway": { "id": "gw-1", "name": "Mac Studio" },
              "device": { "id": "dev-1", "name": "Pixel", "pushRegistered": false },
              "serverTime": 1000.0,
              "sessions": [
                {
                  "key": "s-1",
                  "agent": "amber",
                  "subject": "Deploy check",
                  "origin": "desktop",
                  "state": "waiting",
                  "abnormal": false,
                  "monitored": true,
                  "updatedAt": 1000.0,
                  "summary": "Waiting to run shell command",
                  "pendingApproval": {
                    "toolCallId": "tc-99",
                    "tool": "bash",
                    "detail": "rm -rf build",
                    "highRisk": true
                  }
                }
              ]
            }
        """.trimIndent()
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(MacGatewayStatus.serializer(), rawStatus)
        val session = parsed.sessions.single()
        assertEquals("Waiting to run shell command", session.summary)
        assertEquals("tc-99", session.pendingApproval?.toolCallId)
        assertEquals("bash", session.pendingApproval?.tool)
        assertEquals("rm -rf build", session.pendingApproval?.detail)
        assertEquals(true, session.pendingApproval?.highRisk)

        val client = MacGatewayClient(listOf("127.0.0.1"), port = 1, fingerprint = "abc=", token = "t")
        val error = runCatching {
            runBlocking {
                client.decide(sessionKey = "s-1", toolCallId = "tc-99", allow = true, highRisk = true)
            }
        }.exceptionOrNull()
        assertTrue(error is MacGatewayException.Server)
    }

    @Test
    fun cloudDialogueClientCallsMessagesInputsStopReceiptsAndEventsWithBearerAuth() = runBlocking {
        val observedRequests = mutableListOf<Triple<String, String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val pathWithQuery = buildString {
                append(exchange.requestURI.path)
                exchange.requestURI.rawQuery?.let { append("?").append(it) }
            }
            val body = exchange.requestBody.bufferedReader().readText()
            observedRequests += Triple(exchange.requestMethod, pathWithQuery, body)
            if (auth != "Bearer pair-token-1") {
                val err = """{"error":"unauthorized"}""".toByteArray()
                exchange.sendResponseHeaders(401, err.size.toLong())
                exchange.responseBody.use { it.write(err) }
                return@createContext
            }
            val (status, payload) = when {
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/dialogue/messages" ->
                    202 to """{"eventId":"evt-1","requestId":"req-1","topicId":"topic-1","workItemIds":[],"state":"received","replayed":false}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/work-items/work:1/inputs" ->
                    202 to """{"eventId":"evt-2","requestId":"req-2","topicId":"topic-1","workItemIds":["work:1"],"state":"received","replayed":false}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/work-items/work:1/stop" ->
                    200 to """{"eventId":"evt-3","requestId":"req-3","topicId":"topic-1","workItemIds":["work:1"],"state":"consumed","replayed":false}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/dialogue/receipts/req-1" ->
                    200 to """{"eventId":"evt-1","requestId":"req-1","topicId":"topic-1","workItemIds":["work:1"],"state":"consumed","replayed":true}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/dialogue/events" ->
                    200 to """{"items":[{"seq":4,"id":"reply:evt-1:0","kind":"assistant_reply","role":"assistant","text":"Done","topicId":"topic-1","workItemId":null,"outcome":null}],"nextCursor":4}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/work-items/finished/inputs" ->
                    409 to """{"error":"task_terminal"}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/dialogue/receipts/missing" ->
                    404 to """{"error":"receipt_not_found"}"""
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val client = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = port,
                fingerprint = "unused-over-http",
                token = "pair-token-1",
                scheme = "http",
            )
            val msgReceipt = client.messages(requestId = "req-1", text = "Hello cloud")
            assertEquals("req-1", msgReceipt.requestId)
            assertEquals("evt-1", msgReceipt.eventId)
            assertEquals("received", msgReceipt.state)

            val inputReceipt = client.inputs(workItemId = "work:1", requestId = "req-2", text = "Add constraint")
            assertEquals(listOf("work:1"), inputReceipt.workItemIds)
            assertEquals("evt-2", inputReceipt.eventId)

            val stopReceipt = client.stop(workItemId = "work:1", requestId = "req-3")
            assertEquals("consumed", stopReceipt.state)

            val polledReceipt = client.receipts(requestId = "req-1")
            assertEquals("consumed", polledReceipt.state)
            assertEquals(listOf("work:1"), polledReceipt.workItemIds)

            val eventsPage = client.events(after = 0L, limit = 100)
            assertEquals(4L, eventsPage.nextCursor)
            assertEquals(1, eventsPage.items.size)
            assertEquals("assistant_reply", eventsPage.items.single().kind)

            val conflict = runCatching {
                client.inputs(workItemId = "finished", requestId = "req-4", text = "late")
            }.exceptionOrNull()
            assertTrue(conflict is MacGatewayException.Conflict)
            assertEquals("task_terminal", (conflict as MacGatewayException.Conflict).code)

            val notFound = runCatching {
                client.receipts(requestId = "missing")
            }.exceptionOrNull()
            assertTrue(notFound is MacGatewayException.NotFound)

            val badTokenClient = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = port,
                fingerprint = "unused-over-http",
                token = "wrong-token",
                scheme = "http",
            )
            val unauthorized = runCatching {
                badTokenClient.events(after = 0L, limit = 10)
            }.exceptionOrNull()
            assertTrue(unauthorized is MacGatewayException.Unauthorized)

            assertTrue(observedRequests.any { it.first == "GET" && it.second == "/v2/dialogue/events?after=0&limit=100" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cloudDialogueCoordinatorHandlesLongTaskAConcurrentQuestionBFollowupAndStopWithDistinctInputStates() = runBlocking {
        data class RecordedEntry(
            val conversationId: String,
            val role: String,
            val text: String,
            val referenceId: String,
        )

        val timeline = mutableListOf<RecordedEntry>()
        val persistedStates = mutableListOf<CloudDialoguePersistedState>()
        var latestUiState = CloudDialogueUiState()

        val sink = object : CloudDialogueTimelineSink {
            override suspend fun appendUserMessage(
                conversationId: String,
                text: String,
                eventId: String?,
            ) {
                timeline += RecordedEntry(conversationId, "USER", text, eventId.orEmpty())
            }

            override suspend fun appendAssistantReply(
                conversationId: String,
                text: String,
                eventId: String,
                topicId: String,
            ) {
                timeline += RecordedEntry(conversationId, "ASSISTANT:assistant_reply", text, eventId)
            }

            override suspend fun appendTaskResult(
                conversationId: String,
                workItemId: String,
                taskTitle: String?,
                outcome: String?,
                text: String,
                eventId: String,
                topicId: String,
            ) {
                timeline += RecordedEntry(
                    conversationId,
                    "ASSISTANT:task_result:${outcome.orEmpty()}",
                    text,
                    eventId,
                )
            }
        }

        val receiptsByRequestId = java.util.concurrent.ConcurrentHashMap<String, CloudDialogueReceipt>()
        val eventLog = java.util.concurrent.CopyOnWriteArrayList<CloudDialogueEvent>()
        var seqCounter = 0L

        fun appendEvent(
            id: String,
            kind: String,
            role: String = "assistant",
            topicId: String,
            workItemId: String? = null,
            outcome: String? = null,
            text: String,
        ) {
            seqCounter += 1L
            eventLog += CloudDialogueEvent(
                seq = seqCounter,
                id = id,
                kind = kind,
                role = role,
                text = text,
                topicId = topicId,
                workItemId = workItemId,
                outcome = outcome,
            )
        }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val testJson = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val bodyText = exchange.requestBody.bufferedReader().readText()
            val (status, responseJson) = when {
                exchange.requestMethod == "POST" && path == "/v2/dialogue/messages" -> {
                    val reqObj = testJson.parseToJsonElement(bodyText) as kotlinx.serialization.json.JsonObject
                    val reqId = reqObj["requestId"]!!.toString().trim('"')
                    val text = reqObj["text"]!!.toString().trim('"')
                    val eventId = "evt:$reqId"
                    val topicId = "topic:$reqId"
                    val receipt = CloudDialogueReceipt(
                        eventId = eventId,
                        requestId = reqId,
                        topicId = topicId,
                        workItemIds = emptyList(),
                        state = "received",
                        replayed = false,
                    )
                    receiptsByRequestId[reqId] = receipt
                    appendEvent(id = eventId, kind = "user_message", role = "user", topicId = topicId, text = text)
                    202 to testJson.encodeToString(CloudDialogueReceipt.serializer(), receipt)
                }

                exchange.requestMethod == "POST" && path.startsWith("/v2/work-items/") && path.endsWith("/inputs") -> {
                    val workId = java.net.URLDecoder.decode(
                        path.removePrefix("/v2/work-items/").removeSuffix("/inputs"),
                        Charsets.UTF_8,
                    )
                    val reqObj = testJson.parseToJsonElement(bodyText) as kotlinx.serialization.json.JsonObject
                    val reqId = reqObj["requestId"]!!.toString().trim('"')
                    val text = reqObj["text"]!!.toString().trim('"')
                    val eventId = "evt:$reqId"
                    val topicId = "topic:req-a"
                    val receipt = CloudDialogueReceipt(
                        eventId = eventId,
                        requestId = reqId,
                        topicId = topicId,
                        workItemIds = listOf(workId),
                        state = "received",
                        replayed = false,
                    )
                    receiptsByRequestId[reqId] = receipt
                    appendEvent(
                        id = eventId,
                        kind = "followup",
                        role = "user",
                        topicId = topicId,
                        workItemId = workId,
                        text = text,
                    )
                    202 to testJson.encodeToString(CloudDialogueReceipt.serializer(), receipt)
                }

                exchange.requestMethod == "POST" && path.startsWith("/v2/work-items/") && path.endsWith("/stop") -> {
                    val workId = java.net.URLDecoder.decode(
                        path.removePrefix("/v2/work-items/").removeSuffix("/stop"),
                        Charsets.UTF_8,
                    )
                    val reqObj = testJson.parseToJsonElement(bodyText) as kotlinx.serialization.json.JsonObject
                    val reqId = reqObj["requestId"]!!.toString().trim('"')
                    val eventId = "evt:$reqId"
                    val topicId = "topic:req-a"
                    val receipt = CloudDialogueReceipt(
                        eventId = eventId,
                        requestId = reqId,
                        topicId = topicId,
                        workItemIds = listOf(workId),
                        state = "consumed",
                        replayed = false,
                    )
                    receiptsByRequestId[reqId] = receipt
                    appendEvent(
                        id = eventId,
                        kind = "stop_requested",
                        role = "user",
                        topicId = topicId,
                        workItemId = workId,
                        text = "",
                    )
                    200 to testJson.encodeToString(CloudDialogueReceipt.serializer(), receipt)
                }

                exchange.requestMethod == "GET" && path.startsWith("/v2/dialogue/receipts/") -> {
                    val reqId = java.net.URLDecoder.decode(
                        path.removePrefix("/v2/dialogue/receipts/"),
                        Charsets.UTF_8,
                    )
                    val receipt = receiptsByRequestId[reqId]
                    if (receipt == null) {
                        404 to """{"error":"receipt_not_found"}"""
                    } else {
                        200 to testJson.encodeToString(CloudDialogueReceipt.serializer(), receipt)
                    }
                }

                exchange.requestMethod == "GET" && path == "/v2/dialogue/events" -> {
                    val query = exchange.requestURI.rawQuery.orEmpty()
                        .split("&")
                        .mapNotNull { part ->
                            val idx = part.indexOf('=')
                            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
                        }
                        .toMap()
                    val after = query["after"]?.toLongOrNull() ?: 0L
                    val limit = query["limit"]?.toIntOrNull() ?: 100
                    val items = eventLog.filter { it.seq > after }.take(limit)
                    val nextCursor = items.lastOrNull()?.seq ?: after
                    val page = CloudDialogueEventsPage(items = items, nextCursor = nextCursor)
                    200 to testJson.encodeToString(CloudDialogueEventsPage.serializer(), page)
                }

                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = responseJson.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val port = server.address.port
            val client = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = port,
                fingerprint = "unused-over-http",
                token = "pair-token-1",
                scheme = "http",
            )
            val coordinator = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = sink,
                persistState = { persistedStates += it },
                onStateChanged = { latestUiState = it },
            )
            coordinator.restore(
                enabled = true,
                connection = MacGatewayConnection(
                    gatewayId = "gw-1",
                    name = "Studio",
                    addrs = listOf("127.0.0.1"),
                    port = port,
                    fingerprint = "unused-over-http",
                    deviceId = "dev-1",
                ),
                persisted = null,
            )
            coordinator.bindActiveConversation("conv-main")

            // 1. Start long Task A via messages
            val subA = coordinator.sendMessage(
                conversationId = "conv-main",
                text = "LONG_CSV_A: analyze synthetic CSV in background",
                requestId = "req-a",
            )
            assertEquals(CloudInputDisposition.RECEIVED, subA.state)
            assertEquals(
                CloudInputDisposition.RECEIVED,
                latestUiState.submissions.single { it.requestId == "req-a" }.state,
            )

            // Router dispatches Task A -> receipt transitions to consumed with workItemIds = ["work:a"],
            // and emits multiple process_event items + initial router reply.
            receiptsByRequestId["req-a"] = CloudDialogueReceipt(
                eventId = "evt:req-a",
                requestId = "req-a",
                topicId = "topic:req-a",
                workItemIds = listOf("work:a"),
                state = "consumed",
                replayed = true,
            )
            appendEvent(
                id = "proc:work:a:1",
                kind = "process_event",
                topicId = "topic:req-a",
                workItemId = "work:a",
                text = "Started long task A",
            )
            appendEvent(
                id = "proc:work:a:2",
                kind = "process_event",
                topicId = "topic:req-a",
                workItemId = "work:a",
                text = "Scanning CSV rows (step 1/3)",
            )
            appendEvent(
                id = "proc:work:a:3",
                kind = "process_event",
                topicId = "topic:req-a",
                workItemId = "work:a",
                text = "Computing aggregates in background (step 2/3)",
            )

            coordinator.pollOnce()

            // Verify process_event updates single-line progressText in-place and NEVER writes assistant timeline nodes
            val activeTaskA = latestUiState.activeTasksForConversation("conv-main").single()
            assertEquals("work:a", activeTaskA.workItemId)
            assertEquals("Computing aggregates in background (step 2/3)", activeTaskA.progressText)
            assertEquals(3, activeTaskA.processEventCount)
            assertEquals(
                CloudInputDisposition.CONSUMED,
                latestUiState.submissions.single { it.requestId == "req-a" }.state,
            )
            assertTrue(timeline.none { it.role.startsWith("ASSISTANT") })

            // 2. While Task A is still running, ask independent Question B via messages
            coordinator.sendMessage(
                conversationId = "conv-main",
                text = "QUESTION_B: what is 2+2?",
                requestId = "req-b",
            )
            receiptsByRequestId["req-b"] = CloudDialogueReceipt(
                eventId = "evt:req-b",
                requestId = "req-b",
                topicId = "topic:req-b",
                workItemIds = emptyList(),
                state = "consumed",
                replayed = true,
            )
            appendEvent(
                id = "reply:evt:req-b:0",
                kind = "assistant_reply",
                topicId = "topic:req-b",
                text = "ANSWER_B: 2+2=4, independent from Task A.",
            )

            coordinator.pollOnce()

            // Question B's assistant_reply enters the timeline while Task A remains active in background
            assertEquals(1, latestUiState.activeTasksForConversation("conv-main").size)
            val replyB = timeline.single { it.role.startsWith("ASSISTANT") }
            assertEquals("ANSWER_B: 2+2=4, independent from Task A.", replyB.text)
            assertEquals("reply:evt:req-b:0", replyB.referenceId)

            // 3. Send Followup 1 to Task A via inputs -> starts as RECEIVED, then becomes CONSUMED
            coordinator.sendFollowup(
                conversationId = "conv-main",
                workItemId = "work:a",
                text = "ADD_AVERAGE to result.json",
                requestId = "req-f1",
            )
            assertEquals(
                CloudInputDisposition.RECEIVED,
                latestUiState.submissions.single { it.requestId == "req-f1" }.state,
            )

            receiptsByRequestId["req-f1"] = CloudDialogueReceipt(
                eventId = "evt:req-f1",
                requestId = "req-f1",
                topicId = "topic:req-a",
                workItemIds = listOf("work:a"),
                state = "consumed",
                replayed = true,
            )
            appendEvent(
                id = "consumed:evt:req-f1",
                kind = "input_consumed",
                role = "system",
                topicId = "topic:req-a",
                workItemId = "work:a",
                text = "Followup consumed by Task A",
            )
            coordinator.pollOnce()

            assertEquals(
                CloudInputDisposition.CONSUMED,
                latestUiState.submissions.single { it.requestId == "req-f1" }.state,
            )

            // 4. Send Followup 2 to Task A via inputs (stays RECEIVED), then Stop Task A
            coordinator.sendFollowup(
                conversationId = "conv-main",
                workItemId = "work:a",
                text = "UNCONSUMED_ADDITION before stop",
                requestId = "req-f2",
            )
            assertEquals(
                CloudInputDisposition.RECEIVED,
                latestUiState.submissions.single { it.requestId == "req-f2" }.state,
            )

            val stopSub = coordinator.stopWorkItem(
                workItemId = "work:a",
                requestId = "req-stop-a",
            )
            assertEquals(CloudInputDisposition.CONSUMED, stopSub.state)

            // Daemon marks unconsumed Followup 2 as cancelled and emits input_cancelled + task_result(cancelled)
            receiptsByRequestId["req-f2"] = CloudDialogueReceipt(
                eventId = "evt:req-f2",
                requestId = "req-f2",
                topicId = "topic:req-a",
                workItemIds = listOf("work:a"),
                state = "cancelled",
                replayed = true,
            )
            appendEvent(
                id = "cancelled:evt:req-f2",
                kind = "input_cancelled",
                role = "system",
                topicId = "topic:req-a",
                workItemId = "work:a",
                text = "Task stopped; followup was not executed",
            )
            appendEvent(
                id = "result:work:a",
                kind = "task_result",
                role = "assistant",
                topicId = "topic:req-a",
                workItemId = "work:a",
                outcome = "cancelled",
                text = "Task work:a cancelled by user.",
            )

            coordinator.pollOnce()

            // Verify Followup 1 is CONSUMED while Followup 2 is CANCELLED (never confused),
            // Task A is no longer active, and task_result enters the timeline.
            val submissionsById = latestUiState.submissions.associateBy { it.requestId }
            assertEquals(CloudInputDisposition.CONSUMED, submissionsById.getValue("req-f1").state)
            assertEquals(CloudInputDisposition.CANCELLED, submissionsById.getValue("req-f2").state)
            assertTrue(latestUiState.activeTasksForConversation("conv-main").isEmpty())

            val assistantEntries = timeline.filter { it.role.startsWith("ASSISTANT") }
            assertEquals(2, assistantEntries.size)
            assertEquals("ASSISTANT:assistant_reply", assistantEntries[0].role)
            assertEquals("ASSISTANT:task_result:cancelled", assistantEntries[1].role)
            assertEquals("Task work:a cancelled by user.", assistantEntries[1].text)

            // 5. Verify seq cursor persistence, empty poll non-advancement, and event.id deduplication
            val cursorAfterStop = latestUiState.cursor
            assertEquals(seqCounter, cursorAfterStop)
            assertEquals(cursorAfterStop, persistedStates.last().cursor)

            // Polling with no new events keeps cursor unchanged
            coordinator.pollOnce()
            assertEquals(cursorAfterStop, latestUiState.cursor)
            assertEquals(2, timeline.count { it.role.startsWith("ASSISTANT") })

            // Replaying an already-seen event.id at a higher seq advances cursor but does NOT duplicate timeline entry
            appendEvent(
                id = "reply:evt:req-b:0",
                kind = "assistant_reply",
                topicId = "topic:req-b",
                text = "ANSWER_B: 2+2=4, independent from Task A.",
            )
            coordinator.pollOnce()
            assertEquals(cursorAfterStop + 1L, latestUiState.cursor)
            assertEquals(2, timeline.count { it.role.startsWith("ASSISTANT") })
        } finally {
            server.stop(0)
        }
    }
}
