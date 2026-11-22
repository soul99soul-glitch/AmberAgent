package app.amber.feature.macgateway

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class DialogueContractV1Test {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun cloudDialogueRequestsAlwaysSendVersionHeaderAndStableConversationId() = runBlocking {
        data class CapturedRequest(
            val method: String,
            val path: String,
            val versionHeader: String?,
            val conversationHeader: String?,
            val body: String,
        )

        val captured = CopyOnWriteArrayList<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            captured += CapturedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                versionHeader = exchange.requestHeaders.getFirst("X-Amber-Dialogue-Version"),
                conversationHeader = exchange.requestHeaders.getFirst("X-Amber-Conversation-Id"),
                body = body,
            )
            val payload = when {
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/dialogue/messages" ->
                    """{"eventId":"evt-1","requestId":"req-1","topicId":"t-1","workItemIds":["w-1"],"state":"received","conversationId":"conv-stable-1","schemaVersion":1}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/work-items/w-1/inputs" ->
                    """{"eventId":"evt-2","requestId":"req-2","topicId":"t-1","workItemIds":["w-1"],"state":"received","conversationId":"conv-stable-1","schemaVersion":1}"""
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/work-items/w-1/stop" ->
                    """{"eventId":"evt-3","requestId":"req-3","topicId":"t-1","workItemIds":["w-1"],"state":"consumed","conversationId":"conv-stable-1","schemaVersion":1}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/dialogue/receipts/req-1" ->
                    """{"eventId":"evt-1","requestId":"req-1","topicId":"t-1","workItemIds":["w-1"],"state":"consumed","conversationId":"conv-stable-1","schemaVersion":1}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/dialogue/events" ->
                    """{"items":[],"nextCursor":0,"schemaVersion":1}"""
                else -> """{"error":"unexpected"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "unused",
                token = "tok-1",
                scheme = "http",
            )
            client.messages(requestId = "req-1", text = "hi", conversationId = "conv-stable-1")
            client.inputs(workItemId = "w-1", requestId = "req-2", text = "more", conversationId = "conv-stable-1")
            client.stop(workItemId = "w-1", requestId = "req-3", conversationId = "conv-stable-1")
            client.receipts(requestId = "req-1", conversationId = "conv-stable-1")
            client.events(after = 0L, limit = 50, conversationId = "conv-stable-1")

            assertEquals(5, captured.size)
            captured.forEach { req ->
                assertEquals("${req.method} ${req.path} must send X-Amber-Dialogue-Version: 1", "1", req.versionHeader)
                assertEquals("${req.method} ${req.path} must send X-Amber-Conversation-Id", "conv-stable-1", req.conversationHeader)
            }
            captured.filter { it.method == "POST" }.forEach { req ->
                val obj = json.parseToJsonElement(req.body).jsonObject
                assertEquals("conv-stable-1", obj["conversationId"]?.jsonPrimitive?.content)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun decodesContractV1OptionalFieldsUnknownKindCausalPrecedenceStopTooLateOutcomeUnknownAndSources() = runBlocking {
        data class RecordedTaskResult(
            val conversationId: String,
            val workItemId: String,
            val outcome: String?,
            val text: String,
            val sourceRefs: List<String>,
            val sourcePaths: List<String>,
            val outcomeUnknown: Boolean,
        )

        val recordedResults = mutableListOf<RecordedTaskResult>()
        val sink = object : CloudDialogueTimelineSink {
            override suspend fun appendUserMessage(conversationId: String, text: String, eventId: String?) = Unit
            override suspend fun appendAssistantReply(conversationId: String, text: String, eventId: String, topicId: String) = Unit
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
                recordedResults += RecordedTaskResult(
                    conversationId = conversationId,
                    workItemId = workItemId,
                    outcome = outcome,
                    text = text,
                    sourceRefs = sourceRefs,
                    sourcePaths = sourcePaths,
                    outcomeUnknown = outcomeUnknown,
                )
            }
        }

        val eventPages = ArrayDeque<String>()
        eventPages += """
            {
              "schemaVersion": 1,
              "nextCursor": 6,
              "items": [
                {
                  "seq": 1,
                  "id": "future-1",
                  "kind": "future_v2_unrecognized_event",
                  "role": "system",
                  "text": "should be skipped cleanly",
                  "topicId": "t-1",
                  "conversationId": "conv-1"
                },
                {
                  "seq": 2,
                  "id": "evt-consumed-no-prefix",
                  "kind": "input_consumed",
                  "role": "system",
                  "text": "已接收第一条补充",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1",
                  "causedByInputId": "in-f1",
                  "causedByRequestId": "req-f2",
                  "executorKind": "cloud_worker",
                  "executorId": "worker-1",
                  "parentRunId": "run-parent-1",
                  "epoch": 3
                },
                {
                  "seq": 3,
                  "id": "evt-cancelled-by-reqid",
                  "kind": "input_cancelled",
                  "role": "system",
                  "text": "第二条补充未执行",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1",
                  "causedByRequestId": "req-f2"
                },
                {
                  "seq": 4,
                  "id": "cancelled:in-f3",
                  "kind": "input_cancelled",
                  "role": "system",
                  "text": "兼容旧前缀取消",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1"
                },
                {
                  "seq": 5,
                  "id": "evt-result-unknown",
                  "kind": "task_result",
                  "role": "assistant",
                  "text": "远端进程中断，写入状态待确认",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1",
                  "outcome": "failed",
                  "outcomeUnknown": true,
                  "parentRunId": "run-parent-1",
                  "executorKind": "cloud_worker",
                  "executorId": "worker-1",
                  "epoch": 4,
                  "sourceRefs": ["pr#108", "commit:abc123"],
                  "sourcePaths": ["app/src/main/Foo.kt"]
                },
                {
                  "seq": 6,
                  "id": "evt-stop-late",
                  "kind": "stop_too_late",
                  "role": "system",
                  "text": "任务已完成，停止请求到达过晚",
                  "topicId": "t-1",
                  "workItemId": "w-1",
                  "conversationId": "conv-1",
                  "causedByInputId": "in-stop-1"
                }
              ]
            }
        """.trimIndent()
        eventPages += """{"schemaVersion":1,"nextCursor":6,"items":[]}"""

        val retryRequests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            val (code, payload) = when {
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/dialogue/messages" -> {
                    val reqId = json.parseToJsonElement(body).jsonObject["requestId"]!!.jsonPrimitive.content
                    if (reqId == "req-retry-1" && retryRequests.add(body) && retryRequests.size == 1) {
                        exchange.sendResponseHeaders(503, -1)
                        exchange.close()
                        return@createContext
                    }
                    val eventId = if (reqId == "req-retry-1") "in-retry-1" else "in-msg-1"
                    val workId = if (reqId == "req-retry-1") "w-2" else "w-1"
                    202 to """{"eventId":"$eventId","requestId":"$reqId","topicId":"t-1","workItemIds":["$workId"],"state":"received","outcomeUnknown":${workId == "w-2"},"conversationId":"conv-1"}"""
                }
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/work-items/w-1/inputs" -> {
                    val reqId = json.parseToJsonElement(body).jsonObject["requestId"]!!.jsonPrimitive.content
                    val eventId = when (reqId) {
                        "req-f1" -> "in-f1"
                        "req-f2" -> "in-f2"
                        else -> "in-f3"
                    }
                    202 to """{"eventId":"$eventId","requestId":"$reqId","topicId":"t-1","workItemIds":["w-1"],"state":"received","conversationId":"conv-1"}"""
                }
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/v2/work-items/w-1/stop" ->
                    200 to """{"eventId":"in-stop-1","requestId":"req-stop-1","topicId":"t-1","workItemIds":["w-1"],"state":"received","conversationId":"conv-1"}"""
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/v2/dialogue/events" ->
                    200 to (eventPages.removeFirstOrNull() ?: """{"items":[],"nextCursor":6}""")
                exchange.requestMethod == "GET" && exchange.requestURI.path.startsWith("/v2/dialogue/receipts/") -> {
                    val reqId = exchange.requestURI.path.substringAfterLast('/')
                    200 to """{"eventId":"in-$reqId","requestId":"$reqId","topicId":"t-1","workItemIds":["w-1"],"state":"consumed","outcomeUnknown":false,"conversationId":"conv-1"}"""
                }
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "unused",
                token = "tok-1",
                scheme = "http",
            )
            var latestState = CloudDialogueUiState()
            var persisted: String? = null
            val connection = MacGatewayConnection("gw-1", "Mac", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            fun newCoordinator() = CloudDialogueCoordinator(
                clientProvider = { client },
                timelineSink = sink,
                persistState = { persisted = json.encodeToString(CloudDialoguePersistedState.serializer(), it) },
                onStateChanged = { latestState = it },
            )
            var coordinator = newCoordinator()
            coordinator.restore(enabled = true, connection = connection, persisted = null)
            coordinator.bindActiveConversation("conv-1")
            coordinator.sendMessage("conv-1", "部署检查", "req-msg-1")
            coordinator.sendFollowup("conv-1", "w-1", "补充1", "req-f1")
            coordinator.sendFollowup("conv-1", "w-1", "补充2", "req-f2")
            coordinator.sendFollowup("conv-1", "w-1", "补充3", "req-f3")
            coordinator.stopWorkItem("w-1", "req-stop-1")

            coordinator.pollOnce()

            // Unknown event kind skipped cleanly and cursor advanced past seq 6.
            assertEquals(6L, latestState.cursor)

            // Causal precedence: causedByInputId, causedByRequestId, and legacy prefix all resolved.
            val subByReq = latestState.submissions.associateBy { it.requestId }
            assertEquals(CloudInputDisposition.CONSUMED, subByReq.getValue("req-f1").state)
            assertEquals(CloudInputDisposition.CANCELLED, subByReq.getValue("req-f2").state)
            assertEquals(CloudInputDisposition.CANCELLED, subByReq.getValue("req-f3").state)

            // stop_too_late resolved via causedByInputId without reopening task.
            val stopSub = subByReq.getValue("req-stop-1")
            assertTrue(stopSub.stopTooLate)
            assertEquals(CloudInputDisposition.CANCELLED, stopSub.state)

            val task = latestState.tasks.single { it.workItemId == "w-1" }
            assertFalse(task.active)
            assertTrue(task.outcomeUnknown)
            assertEquals("run-parent-1", task.parentRunId)
            assertEquals("cloud_worker", task.executorKind)
            assertEquals("worker-1", task.executorId)
            assertEquals(4L, task.epoch)
            assertEquals(listOf("pr#108", "commit:abc123"), task.sourceRefs)
            assertEquals(listOf("app/src/main/Foo.kt"), task.sourcePaths)
            assertEquals("任务已完成，停止请求到达过晚", task.stopTooLateNotice)
            assertEquals(1, latestState.outcomeUnknownTasksForConversation("conv-1").size)

            // Timeline sink received sourceRefs/sourcePaths and outcomeUnknown, and Markdown renders them.
            val recorded = recordedResults.single()
            assertTrue(recorded.outcomeUnknown)
            assertEquals(listOf("pr#108", "commit:abc123"), recorded.sourceRefs)
            assertEquals(listOf("app/src/main/Foo.kt"), recorded.sourcePaths)
            val markdown = ConversationStoreCloudDialogueSink.formatTaskResultMarkdown(
                workItemId = "w-1",
                taskTitle = "部署检查",
                outcome = "failed",
                text = "远端进程中断",
                sourceRefs = recorded.sourceRefs,
                sourcePaths = recorded.sourcePaths,
                outcomeUnknown = true,
            )
            assertTrue(markdown.contains("outcome_unknown"))
            assertTrue(markdown.contains("pr#108"))
            assertTrue(markdown.contains("app/src/main/Foo.kt"))

            coordinator.recheckOutcomeUnknownTask("w-1")
            assertTrue(latestState.tasks.single { it.workItemId == "w-1" }.outcomeUnknown)
            assertTrue(runCatching {
                coordinator.retryOutcomeUnknownTask("conv-1", "w-1", "req-retry-1")
            }.isFailure)
            assertEquals(1, latestState.outcomeUnknownTasksForConversation("conv-1").size)

            // A late POST reply to the original follow-up must preserve the event's terminal state.
            assertEquals(CloudInputDisposition.CANCELLED,
                coordinator.sendFollowup("conv-1", "w-1", "补充2", "req-f2").state)

            // Recreate from the stored JSON between attempts, as after process death.
            coordinator = newCoordinator()
            coordinator.restore(true, connection, json.decodeFromString(CloudDialoguePersistedState.serializer(), checkNotNull(persisted)))
            val restored = latestState.outcomeUnknownTasksForConversation("conv-1").single()
            assertEquals(task.sourceRefs, restored.sourceRefs)
            assertEquals(task.sourcePaths, restored.sourcePaths)
            assertEquals("req-retry-1", restored.retryRequestId)
            coordinator.retryOutcomeUnknownTask("conv-1", "w-1", "req-second-tap")
            assertEquals(listOf("w-2"), latestState.outcomeUnknownTasksForConversation("conv-1").map { it.workItemId })
            assertEquals(2, retryRequests.size)
            assertEquals(retryRequests.first(), retryRequests.last())
            assertEquals("部署检查", json.parseToJsonElement(retryRequests.last()).jsonObject["text"]?.jsonPrimitive?.content)

            // Recovery action 2: abandon clears unknown banner without sending network requests.
            coordinator.abandonOutcomeUnknownTask("w-2")
            assertTrue(latestState.tasks.first { it.workItemId == "w-2" }.outcomeUnknownAbandoned)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun remoteL2ChallengeRetriesOnlyAfterForegroundVerification() = runBlocking {
        val proofs = CopyOnWriteArrayList<List<String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/amber/decision") { exchange ->
            val headers = exchange.requestHeaders
            val proof = listOf(headers.getFirst("X-Amber-Trusted-Foreground"), headers.getFirst("X-Amber-Biometric-Verified"))
            proofs.add(proof)
            val verified = proof == listOf("true", "true")
            val bytes = (if (verified) "{}" else """{"error":"verify","requiresLocalApproval":false,"riskLevel":"L2"}""").toByteArray()
            exchange.sendResponseHeaders(if (verified) 200 else 403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "unused", "token", scheme = "http")
            val cancelled = runCatching { client.decide("s", "c", true, verifyForeground = { false }) }
            assertTrue(cancelled.exceptionOrNull() is MacGatewayException.Forbidden)
            client.decide("s", "c", true, verifyForeground = { true })
            assertEquals(listOf(listOf(null, null), listOf(null, null), listOf("true", "true")), proofs.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun approvalRespectsRequiresLocalApprovalAndRemote403Metadata() = runBlocking {
        val rawStatus = """
            {
              "gateway": { "id": "gw-1", "name": "Mac Studio" },
              "device": { "id": "dev-1", "name": "Pixel", "pushRegistered": true },
              "serverTime": 2000.0,
              "sessions": [
                {
                  "key": "sess-1",
                  "agent": "amber",
                  "subject": "Apply patch",
                  "origin": "desktop",
                  "state": "waiting",
                  "conversationId": "conv-mac-1",
                  "pendingApproval": {
                    "toolCallId": "tc-local-1",
                    "tool": "file_edit",
                    "highRisk": false,
                    "riskLevel": "L3",
                    "targetDevice": "Mac Studio",
                    "targetSummary": "Edit deploy.sh",
                    "diffPreview": "- old\n+ new",
                    "requiresLocalApproval": true
                  }
                }
              ]
            }
        """.trimIndent()
        val status = json.decodeFromString(MacGatewayStatus.serializer(), rawStatus)
        val pending = status.sessions.single().pendingApproval
        assertNotNull(pending)
        assertEquals("L3", pending!!.riskLevel)
        assertEquals("Mac Studio", pending.targetDevice)
        assertEquals("Edit deploy.sh", pending.targetSummary)
        assertEquals("- old\n+ new", pending.diffPreview)
        assertEquals(true, pending.requiresLocalApproval)
        assertTrue(pending.effectiveRequiresLocalApproval)

        // Client refuses remote approve when requiresLocalApproval == true.
        val localClient = MacGatewayClient(listOf("127.0.0.1"), port = 1, fingerprint = "fp", token = "t")
        val clientBlocked = runCatching {
            localClient.decide(
                sessionKey = "sess-1",
                toolCallId = "tc-local-1",
                allow = true,
                requiresLocalApproval = true,
                riskLevel = "L3",
            )
        }.exceptionOrNull()
        assertTrue(clientBlocked is MacGatewayException.Forbidden)
        assertTrue((clientBlocked as MacGatewayException.Forbidden).requiresLocalApproval)

        // Remote 403 returns structured Forbidden with requiresLocalApproval & read-only diff metadata.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/amber/decision") { exchange ->
            val body = """
                {
                  "error": "local_approval_required",
                  "requiresLocalApproval": true,
                  "riskLevel": "L3",
                  "targetDevice": "Mac Studio",
                  "targetSummary": "Modify production config",
                  "diffPreview": "- prod=false\n+ prod=true"
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = MacGatewayClient(
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "unused",
                token = "tok-1",
                scheme = "http",
            )
            val err = runCatching {
                client.decide(
                    sessionKey = "sess-1",
                    toolCallId = "tc-remote-l2",
                    allow = true,
                    highRisk = false,
                    requiresLocalApproval = false,
                    riskLevel = "L2",
                )
            }.exceptionOrNull()
            assertTrue("HTTP 403 must decode as MacGatewayException.Forbidden", err is MacGatewayException.Forbidden)
            val forbidden = err as MacGatewayException.Forbidden
            assertEquals("local_approval_required", forbidden.code)
            assertTrue(forbidden.requiresLocalApproval)
            assertEquals("L3", forbidden.riskLevel)
            assertEquals("Mac Studio", forbidden.targetDevice)
            assertEquals("Modify production config", forbidden.targetSummary)
            assertEquals("- prod=false\n+ prod=true", forbidden.diffPreview)
        } finally {
            server.stop(0)
        }
    }
}
