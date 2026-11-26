package app.amber.feature.macgateway

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudPollPolicyTest {

    private class MemoryBackend : SecretStoreBackend {
        private val map = LinkedHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class PrefixCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(stored: String): String? =
            stored.takeIf { it.startsWith("enc:") }?.removePrefix("enc:")
    }

    private suspend fun awaitWithPump(
        testScope: TestScope,
        timeoutMs: Long = 3_000L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testScope.runCurrent()
            if (predicate()) {
                // One extra pump so the coroutine advances to its next suspension point cleanly.
                testScope.runCurrent()
                return
            }
            delay(5L)
        }
        testScope.runCurrent()
        assertTrue("Timed out waiting for expected polling condition", predicate())
    }

    @Test
    fun cloudPollDelayMsCoversAllFourForegroundAndActivityCombinations() {
        // 1. Foreground + (active || pending) -> 1_000ms
        assertEquals(1_000L, cloudPollDelayMs(foreground = true, hasActiveTask = true, hasPendingSubmission = false))
        assertEquals(1_000L, cloudPollDelayMs(foreground = true, hasActiveTask = false, hasPendingSubmission = true))
        assertEquals(1_000L, cloudPollDelayMs(foreground = true, hasActiveTask = true, hasPendingSubmission = true))

        // 2. Foreground idle -> 5_000ms
        assertEquals(5_000L, cloudPollDelayMs(foreground = true, hasActiveTask = false, hasPendingSubmission = false))

        // 3. Background + (active || pending) -> 10_000ms
        assertEquals(10_000L, cloudPollDelayMs(foreground = false, hasActiveTask = true, hasPendingSubmission = false))
        assertEquals(10_000L, cloudPollDelayMs(foreground = false, hasActiveTask = false, hasPendingSubmission = true))
        assertEquals(10_000L, cloudPollDelayMs(foreground = false, hasActiveTask = true, hasPendingSubmission = true))

        // 4. Background idle -> null (stop polling)
        assertNull(cloudPollDelayMs(foreground = false, hasActiveTask = false, hasPendingSubmission = false))
    }

    @Test
    fun repositoryStopsPollingInBackgroundIdleAndWakesOnPushOrForeground() = runBlocking {
        val tempDir = Files.createTempDirectory("macgw-poll-bg").toFile()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appTestScope = TestScope()
        val pollCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/dialogue/events") { exchange ->
            val seq = pollCount.incrementAndGet()
            val body = """{"items":[{"seq":$seq,"id":"tick-$seq","kind":"poll_tick"}],"nextCursor":$seq,"schemaVersion":1}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = ioScope,
                produceFile = { File(tempDir, "mac_gateway.preferences_pb") },
            )
            val connection = MacGatewayConnection(
                gatewayId = "gw-poll",
                name = "Cloud",
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "fp",
                deviceId = "dev-1",
            )
            val secretStore = SecretStore(MemoryBackend(), PrefixCipher())
            GatewayTokenVault(secretStore).write("gw-poll", "tok-1")
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("connection")] =
                    MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
                prefs[booleanPreferencesKey("cloud_dialogue_enabled")] = true
            }

            val foreground = MutableStateFlow(false)
            val repo = MacGatewayRepository(
                context = ContextWrapper(null),
                appScope = appTestScope.backgroundScope,
                secretStore = secretStore,
                dataStore = dataStore,
                foregroundFlow = foreground,
                clientFactory = { conn, tok ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, tok, scheme = "http")
                },
                nowMs = { appTestScope.testScheduler.currentTime },
            )
            awaitWithPump(appTestScope) { repo.state.value.loaded }

            // Background & idle: 0 periodic polls even after 60s.
            appTestScope.advanceTimeBy(60_000L)
            appTestScope.runCurrent()
            assertEquals(0, pollCount.get())

            // Burst of push wakeups coalesces into a single immediate poll, then stops again in background.
            repeat(5) { repo.requestImmediatePoll("push") }
            awaitWithPump(appTestScope) { repo.cloudDialogueState.value.cursor >= 1L }
            assertEquals(1, pollCount.get())

            appTestScope.advanceTimeBy(60_000L)
            appTestScope.runCurrent()
            assertEquals(1, pollCount.get())

            // Returning to foreground triggers an immediate poll, then polls every 5s while idle.
            foreground.value = true
            awaitWithPump(appTestScope) { repo.cloudDialogueState.value.cursor >= 2L }
            assertEquals(2, pollCount.get())

            appTestScope.advanceTimeBy(4_999L)
            appTestScope.runCurrent()
            assertEquals(2, pollCount.get())

            appTestScope.advanceTimeBy(1L)
            awaitWithPump(appTestScope) { repo.cloudDialogueState.value.cursor >= 3L }
            assertEquals(3, pollCount.get())

            // Moving back to background stops polling immediately.
            foreground.value = false
            appTestScope.runCurrent()
            appTestScope.advanceTimeBy(60_000L)
            appTestScope.runCurrent()
            assertEquals(3, pollCount.get())
        } finally {
            server.stop(0)
            ioScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun repositoryPollsEveryTenSecondsInBackgroundWhenActiveTaskExists() = runBlocking {
        val tempDir = Files.createTempDirectory("macgw-poll-bg-active").toFile()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appTestScope = TestScope()
        val pollCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/dialogue/events") { exchange ->
            val seq = 1 + pollCount.incrementAndGet()
            val body = """{"items":[{"seq":$seq,"id":"tick-$seq","kind":"poll_tick"}],"nextCursor":$seq,"schemaVersion":1}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = ioScope,
                produceFile = { File(tempDir, "mac_gateway.preferences_pb") },
            )
            val connection = MacGatewayConnection(
                gatewayId = "gw-active",
                name = "Cloud",
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "fp",
                deviceId = "dev-1",
            )
            val secretStore = SecretStore(MemoryBackend(), PrefixCipher())
            GatewayTokenVault(secretStore).write("gw-active", "tok-1")
            val persistedWithActiveTask = CloudDialoguePersistedState(
                gatewayId = "gw-active",
                deviceId = "dev-1",
                cursor = 1L,
                tasks = listOf(
                    CloudTaskSession(
                        workItemId = "w-bg-1",
                        topicId = "t-1",
                        conversationId = "conv-1",
                        title = "Background task",
                        active = true,
                    ),
                ),
            )
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("connection")] =
                    MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
                prefs[booleanPreferencesKey("cloud_dialogue_enabled")] = true
                prefs[stringPreferencesKey("cloud_dialogue_state")] =
                    MacGatewayJson.encodeToString(CloudDialoguePersistedState.serializer(), persistedWithActiveTask)
            }

            val foreground = MutableStateFlow(false)
            val repo = MacGatewayRepository(
                context = ContextWrapper(null),
                appScope = appTestScope.backgroundScope,
                secretStore = secretStore,
                dataStore = dataStore,
                foregroundFlow = foreground,
                clientFactory = { conn, tok ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, tok, scheme = "http")
                },
                nowMs = { appTestScope.testScheduler.currentTime },
            )
            awaitWithPump(appTestScope) { repo.cloudDialogueState.value.cursor >= 2L }
            assertEquals(1, pollCount.get())

            // Must wait full 10_000ms before the next background active poll.
            appTestScope.advanceTimeBy(9_999L)
            appTestScope.runCurrent()
            assertEquals(1, pollCount.get())

            appTestScope.advanceTimeBy(1L)
            awaitWithPump(appTestScope) { repo.cloudDialogueState.value.cursor >= 3L }
            assertEquals(2, pollCount.get())
        } finally {
            server.stop(0)
            ioScope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
