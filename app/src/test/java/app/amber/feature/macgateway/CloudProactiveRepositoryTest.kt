package app.amber.feature.macgateway

import app.amber.agent.data.db.dao.ProactiveCardDao
import app.amber.agent.data.db.entity.ProactiveCardEntity
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProactiveRepositoryTest {

    private class FakeProactiveCardDao : ProactiveCardDao {
        private val state = MutableStateFlow<List<ProactiveCardEntity>>(emptyList())

        override suspend fun insert(entity: ProactiveCardEntity): Long {
            val current = state.value
            val exists = current.any {
                it.gatewayId == entity.gatewayId &&
                    it.deviceId == entity.deviceId &&
                    it.eventId == entity.eventId
            }
            if (exists) return -1L
            state.value = current + entity
            return state.value.size.toLong()
        }

        override fun observeForGateway(gatewayId: String, deviceId: String): Flow<List<ProactiveCardEntity>> =
            state.map { list ->
                list.filter { it.gatewayId == gatewayId && it.deviceId == deviceId }
                    .sortedWith(compareBy<ProactiveCardEntity> { it.receivedAt }.thenBy { it.seq })
            }

        override suspend fun findBySignal(gatewayId: String, deviceId: String, signalId: String): ProactiveCardEntity? =
            state.value
                .filter { it.gatewayId == gatewayId && it.deviceId == deviceId && it.signalId == signalId }
                .maxWithOrNull(compareBy<ProactiveCardEntity> { it.receivedAt }.thenBy { it.seq })

        override suspend fun markNotified(gatewayId: String, deviceId: String, signalId: String, notifiedAt: Long): Int {
            var count = 0
            state.value = state.value.map { row ->
                if (row.gatewayId == gatewayId && row.deviceId == deviceId && row.signalId == signalId && row.notifiedAt == null) {
                    count++
                    row.copy(notifiedAt = notifiedAt)
                } else {
                    row
                }
            }
            return count
        }

        override suspend fun setFeedback(gatewayId: String, deviceId: String, signalId: String, feedbackState: String): Int {
            var count = 0
            state.value = state.value.map { row ->
                if (row.gatewayId == gatewayId && row.deviceId == deviceId && row.signalId == signalId) {
                    count++
                    row.copy(feedbackState = feedbackState)
                } else {
                    row
                }
            }
            return count
        }

        fun snapshot(): List<ProactiveCardEntity> = state.value
    }

    private fun sampleEntity(
        gatewayId: String = "gw-1",
        deviceId: String = "dev-1",
        signalId: String = "sig_local_1",
        title: String = "本地提醒",
        text: String = "这是一条本地卡片正文",
        checkPrompt: String? = null,
        receivedAt: Long = 1_000L,
        seq: Long = 1L,
    ): ProactiveCardEntity {
        val meta = ProactiveEventMeta(
            signalId = signalId,
            topicKey = "watchtower:1",
            category = "WATCHTOWER",
            title = title,
            checkPrompt = checkPrompt,
        )
        return ProactiveCardEntity(
            gatewayId = gatewayId,
            deviceId = deviceId,
            eventId = "proactive:$signalId",
            signalId = signalId,
            seq = seq,
            text = text,
            metaJson = MacGatewayJson.encodeToString(ProactiveEventMeta.serializer(), meta),
            receivedAt = receivedAt,
        )
    }

    @Test
    fun loadSignalPrefersLocalDaoAndRemoteFallbackDoesNotWriteDaoOrTimeline() = runBlocking {
        val remoteCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            remoteCalls.incrementAndGet()
            val (status, body) = when (exchange.requestURI.path) {
                "/v2/proactive/signals/sig_remote_1" ->
                    200 to """{"event":{"seq":88,"id":"proactive:sig_remote_1","kind":"proactive_message","role":"assistant","text":"远端详情","topicId":"proactive:sig_remote_1","proactive":{"signalId":"sig_remote_1","topicKey":"watchtower:r","category":"WATCHTOWER","title":"远端标题"}}}"""
                "/v2/proactive/signals/sig_missing" ->
                    404 to """{"error":"signal_not_found"}"""
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val dao = FakeProactiveCardDao()
            dao.insert(sampleEntity(signalId = "sig_local_1"))
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            val repo = CloudProactiveRepository(
                dao = dao,
                gateway = null,
                clientProviderOverride = { client },
                connectionProviderOverride = { conn },
                generationProviderOverride = { 1L },
                activeConversationIdProviderOverride = { "conv-1" },
                sendCloudMainMessageOverride = { _, _ -> true },
                nowMs = { 5_000L },
            )

            // A notification from a prior device cannot read the current device's card or hit the network.
            assertNull(repo.loadSignal(ProactiveDetailRequest("sig_local_1", "gw-1", "old-device")))
            // 1. Local hit avoids network entirely.
            val local = repo.loadSignal("sig_local_1")
            assertNotNull(local)
            assertEquals("本地提醒", local!!.meta.title)
            assertEquals(0, remoteCalls.get())

            // 2. Remote fallback returns card without mutating DAO.
            val remote = repo.loadSignal("sig_remote_1")
            assertNotNull(remote)
            assertEquals("远端标题", remote!!.meta.title)
            assertEquals("远端详情", remote.text)
            assertEquals(1, remoteCalls.get())
            assertEquals(1, dao.snapshot().size)
            assertNull(dao.findBySignal("gw-1", "dev-1", "sig_remote_1"))

            // 3. 404 returns null cleanly.
            assertNull(repo.loadSignal("sig_missing"))
            assertEquals(2, remoteCalls.get())

            // 4. cards() observes DAO sorted by receivedAt, seq.
            val observed = repo.cards("gw-1", "dev-1").first()
            assertEquals(listOf("sig_local_1"), observed.map { it.signalId })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sendFeedbackUpdatesLocalStateAndTreatsAppliedFalseAsIdempotentSuccess() = runBlocking {
        val feedbackCalls = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            feedbackCalls += body
            val (status, resp) = when {
                body.contains("snooze") ->
                    200 to """{"ok":true,"action":"snooze","applied":false,"wakeupId":"wk_existing"}"""
                body.contains("mute_topic") ->
                    200 to """{"ok":true,"action":"mute_topic","applied":true,"mutedUntilEpochMs":999999}"""
                else -> 500 to """{"error":"unexpected"}"""
            }
            val bytes = resp.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val dao = FakeProactiveCardDao()
            dao.insert(sampleEntity(signalId = "sig_fb_1"))
            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            val gen = AtomicLong(1L)
            val repo = CloudProactiveRepository(
                dao = dao,
                gateway = null,
                clientProviderOverride = { client },
                connectionProviderOverride = { conn },
                generationProviderOverride = { gen.get() },
                activeConversationIdProviderOverride = { "conv-1" },
                sendCloudMainMessageOverride = { _, _ -> true },
            )

            // applied=false still counts as success and updates local feedback_state.
            assertTrue(repo.sendFeedback("gw-1", "sig_fb_1", ProactiveFeedbackAction.SNOOZE, deviceId = "dev-1", snoozeMinutes = 60))
            assertEquals("snooze", dao.findBySignal("gw-1", "dev-1", "sig_fb_1")?.feedbackState)

            assertTrue(repo.sendFeedback("gw-1", "sig_fb_1", ProactiveFeedbackAction.MUTE_TOPIC, deviceId = "dev-1", muteHours = 168))
            assertEquals("mute_topic", dao.findBySignal("gw-1", "dev-1", "sig_fb_1")?.feedbackState)

            // Gateway mismatch is rejected without hitting network.
            assertFalse(repo.sendFeedback("gw-other", "sig_fb_1", ProactiveFeedbackAction.DISMISS, deviceId = "dev-1"))
            assertEquals(2, feedbackCalls.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun actUsesCheckPromptOrFallbackAndReportsRepliedOnlyAfterAcceptedSend() = runBlocking {
        val feedbackActions = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            feedbackActions += body
            val resp = """{"ok":true,"action":"replied","applied":true}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()

        try {
            val dao = FakeProactiveCardDao()
            val longBody = "X".repeat(800)
            val entityWithPrompt = sampleEntity(
                signalId = "sig_prompt",
                title = "PR 提醒",
                text = "正文",
                checkPrompt = "请直接帮我 review PR #42",
            )
            val entityFallback = sampleEntity(
                signalId = "sig_fallback",
                title = "版本发布",
                text = longBody,
                checkPrompt = null,
            )
            dao.insert(entityWithPrompt)
            dao.insert(entityFallback)

            val client = MacGatewayClient(listOf("127.0.0.1"), server.address.port, "fp", "tok", scheme = "http")
            val conn = MacGatewayConnection("gw-1", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "dev-1")
            val sentMessages = CopyOnWriteArrayList<Pair<String, String>>()
            var acceptSend = false

            val repo = CloudProactiveRepository(
                dao = dao,
                gateway = null,
                clientProviderOverride = { client },
                connectionProviderOverride = { conn },
                generationProviderOverride = { 1L },
                activeConversationIdProviderOverride = { "conv-cloud-main" },
                sendCloudMainMessageOverride = { convId, text ->
                    sentMessages += convId to text
                    acceptSend
                },
            )

            val promptCard = ProactiveCard.fromEntityOrNull(entityWithPrompt)!!
            val fallbackCard = ProactiveCard.fromEntityOrNull(entityFallback)!!

            // 1. Send rejected -> returns false and MUST NOT report replied.
            acceptSend = false
            assertFalse(repo.act(promptCard))
            assertTrue(feedbackActions.isEmpty())
            assertNull(dao.findBySignal("gw-1", "dev-1", "sig_prompt")?.feedbackState)

            // 2. Send accepted with checkPrompt -> sends exact checkPrompt and reports replied.
            acceptSend = true
            assertTrue(repo.act(promptCard))
            assertEquals("conv-cloud-main" to "请直接帮我 review PR #42", sentMessages.last())
            assertEquals(1, feedbackActions.size)
            assertTrue(feedbackActions.last().contains("\"replied\""))
            assertEquals("replied", dao.findBySignal("gw-1", "dev-1", "sig_prompt")?.feedbackState)

            // 3. Send accepted without checkPrompt -> uses frozen §4.4 template truncated to 600 chars.
            assertTrue(repo.act(fallbackCard))
            val expectedFallback = "关于这条主动提醒「版本发布」，请帮我处理：\n\n" + "X".repeat(600)
            assertEquals("conv-cloud-main" to expectedFallback, sentMessages.last())
            assertEquals("replied", dao.findBySignal("gw-1", "dev-1", "sig_fallback")?.feedbackState)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun proactiveDeepLinkInboxValidatesSubmitsAndConsumesOnce() {
        val inbox = ProactiveDeepLinkInbox()
        assertNull(inbox.pending.value)

        inbox.submit("../bad")
        assertNull(inbox.pending.value)

        inbox.submit("wt_valid_1")
        assertEquals("wt_valid_1", inbox.pending.value?.signalId)

        // Consuming a different id keeps the pending signal intact.
        inbox.consume(app.amber.feature.macgateway.ProactiveDetailRequest("other_id", null, null))
        assertEquals("wt_valid_1", inbox.pending.value?.signalId)

        inbox.consume(app.amber.feature.macgateway.ProactiveDetailRequest("wt_valid_1", null, null))
        assertNull(inbox.pending.value)
    }
}
