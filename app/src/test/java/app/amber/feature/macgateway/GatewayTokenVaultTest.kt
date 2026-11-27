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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTokenVaultTest {

    private class InMemorySecretBackend : SecretStoreBackend {
        private val map = LinkedHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class ProgrammableSecretCipher(
        var failEncrypt: Boolean = false,
        var corruptDecrypt: Boolean = false,
    ) : SecretCipher {
        override fun encrypt(plaintext: String): String {
            if (failEncrypt) throw IllegalStateException("Simulated keystore encrypt failure")
            return "enc:${plaintext.reversed()}"
        }

        override fun decrypt(stored: String): String? {
            if (corruptDecrypt) return null
            if (!stored.startsWith("enc:")) return null
            return stored.removePrefix("enc:").reversed()
        }
    }

    @Test
    fun writeReadDeleteUsesExpectedDescriptorAndNeverStoresPlaintext() {
        val backend = InMemorySecretBackend()
        val cipher = ProgrammableSecretCipher()
        val vault = GatewayTokenVault(SecretStore(backend, cipher))

        vault.write("gw-alpha", "tok-super-secret-999")

        val expectedKey = "mac_gateway/gw-alpha/device_token"
        assertTrue(expectedKey in backend.keys())
        val rawStored = backend.get(expectedKey)
        assertNotEquals("tok-super-secret-999", rawStored)
        assertFalse(rawStored.orEmpty().contains("tok-super-secret-999"))
        assertEquals("tok-super-secret-999", vault.read("gw-alpha"))

        vault.delete("gw-alpha")
        assertNull(vault.read("gw-alpha"))
        assertFalse(expectedKey in backend.keys())
    }

    @Test
    fun writeThrowsWithoutLeakingTokenWhenReadBackVerificationFails() {
        val backend = InMemorySecretBackend()
        val cipher = ProgrammableSecretCipher(corruptDecrypt = true)
        val vault = GatewayTokenVault(SecretStore(backend, cipher))
        val secret = "tok-never-leak-me-12345"

        val error = runCatching { vault.write("gw-1", secret) }.exceptionOrNull()
        assertTrue("Expected write verification failure", error is IllegalStateException)
        assertFalse(
            "Exception message must never contain the raw token",
            error?.message.orEmpty().contains(secret),
        )
    }

    @Test
    fun legacyPlaintextTokenMigratesIdempotentlyAndRemovesDataStoreKey() = runBlocking {
        val tempDir = Files.createTempDirectory("macgw-migrate-ok").toFile()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authHeaders = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/status") { exchange ->
            authHeaders += exchange.requestHeaders.getFirst("Authorization")
            val body = """{"gateway":{"id":"gw-1","name":"Mac"},"device":{"id":"dev-1","name":"Android","pushRegistered":false},"serverTime":1.0,"sessions":[]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope1,
                produceFile = { File(tempDir, "mac_gateway.preferences_pb") },
            )
            val connection = MacGatewayConnection(
                gatewayId = "gw-1",
                name = "Mac",
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "fp-1",
                deviceId = "dev-1",
            )
            val legacySecret = "legacy-plaintext-token-abc"
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("connection")] =
                    MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
                prefs[stringPreferencesKey("token")] = legacySecret
            }

            val backend = InMemorySecretBackend()
            val secretStore = SecretStore(backend, ProgrammableSecretCipher())
            val repo1 = MacGatewayRepository(
                context = ContextWrapper(null),
                appScope = scope1,
                secretStore = secretStore,
                dataStore = dataStore,
                foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, tok ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, tok, scheme = "http")
                },
            )
            val macSlot1 = repo1.slot(GatewayEndpointRole.MAC)
            macSlot1.awaitReady()

            // Legacy key removed from DataStore, encrypted value stored and readable from SecretStore.
            val prefsAfter = dataStore.data.first()
            assertNull("Legacy DataStore token key must be removed after migration", prefsAfter[stringPreferencesKey("token")])
            assertNull(prefsAfter[stringPreferencesKey("mac_token")])
            assertEquals(legacySecret, GatewayTokenVault(secretStore).read("gw-1"))
            assertNull(repo1.state.value.connection)
            assertEquals("gw-1", macSlot1.state.value.connection?.gatewayId)

            // Re-creating repository reads the exact same token from SecretStore and authenticates.
            val repo2 = MacGatewayRepository(
                context = ContextWrapper(null),
                appScope = scope2,
                secretStore = secretStore,
                dataStore = dataStore,
                foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, tok ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, tok, scheme = "http")
                },
            )
            repo2.slot(GatewayEndpointRole.MAC).refresh()
            assertEquals("Bearer $legacySecret", authHeaders.lastOrNull())
        } finally {
            server.stop(0)
            scope1.cancel()
            scope2.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cipherFailureDuringMigrationPreservesLegacyDataStoreTokenAndRetriesOnNextLaunch() = runBlocking {
        val tempDir = Files.createTempDirectory("macgw-migrate-fail").toFile()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope1,
                produceFile = { File(tempDir, "mac_gateway.preferences_pb") },
            )
            val connection = MacGatewayConnection(
                gatewayId = "gw-retry",
                name = "Cloud",
                addrs = listOf("127.0.0.1"),
                port = 12345,
                fingerprint = "fp-1",
                deviceId = "dev-1",
            )
            val legacySecret = "legacy-token-keep-on-error"
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("connection")] =
                    MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
                prefs[stringPreferencesKey("token")] = legacySecret
            }

            val backend = InMemorySecretBackend()
            val cipher = ProgrammableSecretCipher(failEncrypt = true)
            val secretStore = SecretStore(backend, cipher)

            val repoFailed = MacGatewayRepository(
                context = ContextWrapper(null),
                appScope = scope1,
                secretStore = secretStore,
                dataStore = dataStore,
                foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, tok ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, tok, scheme = "http")
                },
            )
            repoFailed.awaitReady()

            // Legacy token must be preserved in DataStore and session remains usable via fallback.
            assertEquals(legacySecret, dataStore.data.first()[stringPreferencesKey("token")])
            assertNull(GatewayTokenVault(secretStore).read("gw-retry"))
            assertEquals("gw-retry", repoFailed.state.value.connection?.gatewayId)

            // Once cipher recovers, next startup completes migration cleanly.
            cipher.failEncrypt = false
            val repoRecovered = MacGatewayRepository(
                context = ContextWrapper(null),
                appScope = scope2,
                secretStore = secretStore,
                dataStore = dataStore,
                foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, tok ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, tok, scheme = "http")
                },
            )
            repoRecovered.awaitReady()

            assertNull(dataStore.data.first()[stringPreferencesKey("token")])
            assertEquals(legacySecret, GatewayTokenVault(secretStore).read("gw-retry"))
        } finally {
            scope1.cancel()
            scope2.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun unauthorizedPollFinishesForgetInsideItsOwnPollingJob() = runBlocking {
        val tempDir = Files.createTempDirectory("macgw-401").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/dialogue/events") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                File(tempDir, "mac_gateway.preferences_pb")
            }
            val connection = MacGatewayConnection(
                "gw-401", "Cloud", listOf("127.0.0.1"), server.address.port, "fp", "device",
            )
            val secretStore = SecretStore(InMemorySecretBackend(), ProgrammableSecretCipher())
            val vault = GatewayTokenVault(secretStore)
            vault.write(connection.gatewayId, "old-device-key")
            store.edit {
                it[stringPreferencesKey("connection")] = MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
                it[booleanPreferencesKey("cloud_dialogue_enabled")] = true
                // A stop after the encrypted write can leave a second copy until the next launch.
                it[stringPreferencesKey("token")] = "old-device-key"
            }
            val repo = MacGatewayRepository(
                context = ContextWrapper(null), appScope = scope, secretStore = secretStore,
                dataStore = store, foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, key -> MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, key, scheme = "http") },
            )
            repo.awaitReady()
            assertNull(store.data.first()[stringPreferencesKey("token")])
            repo.requestImmediatePoll("push")
            withTimeout(5_000L) { repo.state.first { it.error is MacGatewayException.Unauthorized } }

            assertNull(vault.read(connection.gatewayId))
            assertNull(repo.state.value.connection)
            assertTrue(store.data.first().asMap().isEmpty())
        } finally {
            server.stop(0)
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun invalidatedKeystoreWithNoLegacyCopyExposesUnpairedState() = runBlocking {
        val tempDir = Files.createTempDirectory("macgw-invalid-key").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "mac_gateway.preferences_pb") }
            val connection = MacGatewayConnection("gw", "Cloud", listOf("127.0.0.1"), 443, "fp", "device")
            store.edit { it[stringPreferencesKey("connection")] = MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection) }
            val cipher = ProgrammableSecretCipher()
            val secretStore = SecretStore(InMemorySecretBackend(), cipher)
            val vault = GatewayTokenVault(secretStore)
            vault.write(connection.gatewayId, "key-before-invalidation")
            cipher.corruptDecrypt = true
            assertNull(vault.read(connection.gatewayId))

            val repo = MacGatewayRepository(
                context = ContextWrapper(null), appScope = scope, secretStore = secretStore,
                dataStore = store, foregroundFlow = MutableStateFlow(false),
            )
            repo.awaitReady()
            assertNull(repo.state.value.connection)
            assertFalse(repo.state.value.isCloudDialogueActive)
            repo.unpair()
            assertTrue(store.data.first().asMap().isEmpty())
            assertFalse(secretStore.has(vault.descriptor(connection.gatewayId)))
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun forgetClearsBothSecretStoreAndDataStoreAndIncrementsConnectionGeneration() = runBlocking {
        val tempDir = Files.createTempDirectory("macgw-forget").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/unpair") { exchange ->
            val body = "{}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(tempDir, "mac_gateway.preferences_pb") },
            )
            val connection = MacGatewayConnection(
                gatewayId = "gw-forget",
                name = "Mac",
                addrs = listOf("127.0.0.1"),
                port = server.address.port,
                fingerprint = "fp-1",
                deviceId = "dev-1",
            )
            val backend = InMemorySecretBackend()
            val secretStore = SecretStore(backend, ProgrammableSecretCipher())
            val vault = GatewayTokenVault(secretStore)
            vault.write("gw-forget", "tok-encrypted-before-forget")
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("connection")] =
                    MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
            }

            val repo = MacGatewayRepository(
                context = ContextWrapper(null),
                appScope = scope,
                secretStore = secretStore,
                dataStore = dataStore,
                foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, tok ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, tok, scheme = "http")
                },
            )
            val macSlot = repo.slot(GatewayEndpointRole.MAC)
            macSlot.awaitReady()
            assertEquals("gw-forget", macSlot.state.value.connection?.gatewayId)
            val generationBefore = macSlot.connectionGeneration.value

            macSlot.unpair()

            assertNull("SecretStore entry must be deleted on forget", vault.read("gw-forget"))
            assertTrue("DataStore must be cleared on forget", dataStore.data.first().asMap().isEmpty())
            assertNull(repo.state.value.connection)
            assertNull(macSlot.state.value.connection)
            assertEquals(generationBefore + 1L, macSlot.connectionGeneration.value)
        } finally {
            server.stop(0)
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun legacyMacAndNewCloudKeepTheirOwnTokensStatusSelectionAndReceipts() = runBlocking {
        val tempDir = Files.createTempDirectory("amber-dual-gateway").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cloudRequests = CopyOnWriteArrayList<Pair<String, String?>>()
        val macRequests = CopyOnWriteArrayList<Pair<String, String?>>()

        fun server(id: String, seen: MutableList<Pair<String, String?>>): HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                mapOf(
                    "/v1/pair" to """{"deviceId":"device-$id","token":"token-$id","gatewayId":"$id","gatewayName":"$id"}""",
                    "/v1/status" to """{"gateway":{"id":"$id","name":"$id"},"device":{"id":"device-$id","name":"Android","pushRegistered":false},"serverTime":1.0,"sessions":[]}""",
                    "/v1/test-push" to "{}",
                    "/v1/amber/decision" to "{}",
                    "/v2/dialogue/messages" to """{"eventId":"reply-$id","requestId":"request-$id","topicId":"topic-$id","workItemIds":[],"state":"received","replayed":false}""",
                    "/v1/unpair" to "{}",
                    "/v2/dialogue/events" to """{"items":[],"nextCursor":0,"schemaVersion":1}""",
                ).forEach { (path, body) ->
                    createContext(path) { exchange ->
                        val token = exchange.requestHeaders.getFirst("Authorization")
                        if (path != "/v1/pair") seen += path to token
                        val bytes = body.toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
                start()
            }

        val cloudServer = server("cloud", cloudRequests)
        val macServer = server("mac", macRequests)
        try {
            val store = PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempDir, "mac_gateway.preferences_pb")
            }
            val secretStore = SecretStore(InMemorySecretBackend(), ProgrammableSecretCipher())
            val legacy = MacGatewayConnection("mac", "Studio Mac", listOf("127.0.0.1"), macServer.address.port, "pin-mac", "device-mac")
            GatewayTokenVault(secretStore).write(legacy.gatewayId, "token-mac")
            store.edit {
                it[stringPreferencesKey("connection")] = MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), legacy)
                it[booleanPreferencesKey("cloud_dialogue_enabled")] = true
                it[stringPreferencesKey("cloud_dialogue_state")] = MacGatewayJson.encodeToString(
                    CloudDialoguePersistedState.serializer(),
                    CloudDialoguePersistedState(gatewayId = "mac", deviceId = "device-mac", cursor = 19),
                )
            }
            fun open(scope: CoroutineScope) = MacGatewayRepository(
                context = ContextWrapper(null), appScope = scope, secretStore = secretStore,
                dataStore = store, foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, token -> MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, token, scheme = "http") },
            )

            val cloud = open(firstScope)
            val mac = cloud.slot(GatewayEndpointRole.MAC)
            mac.awaitReady()
            assertNull(cloud.state.value.connection)
            assertEquals(GatewayEndpointRole.MAC, cloud.selectedDialogueRole.value)
            assertEquals(19L, mac.cloudDialogueState.value.cursor)

            cloud.pair(MacGatewayPairingPayload(1, "cloud", "cloud", listOf("127.0.0.1"), cloudServer.address.port, "pin-cloud", "pair-code"))
            mac.refresh()
            assertEquals("cloud", cloud.state.value.status?.gateway?.id)
            assertEquals("mac", mac.state.value.status?.gateway?.id)
            assertEquals(0L, cloud.cloudDialogueState.value.cursor)
            assertTrue(cloud.setCloudDialogueEnabled(true))
            assertFalse(mac.state.value.cloudDialogueEnabled)

            val receipt = cloud.sendCloudMessage("cloud-conversation", "Hello cloud", "request-cloud")
            assertEquals("reply-cloud", receipt.eventId)
            assertTrue(mac.cloudDialogueState.value.submissions.isEmpty())
            val blocked = runCatching { mac.decide("session", "tool", allow = true, highRisk = true) }.exceptionOrNull()
            assertTrue(blocked is MacGatewayException.Forbidden && blocked.requiresLocalApproval)
            assertFalse(macRequests.any { it.first == "/v1/amber/decision" })
            mac.decide("session", "tool", allow = false, highRisk = true)

            firstScope.cancel()
            val reopened = open(reopenedScope)
            val reopenedMac = reopened.slot(GatewayEndpointRole.MAC)
            reopenedMac.awaitReady()
            assertEquals(GatewayEndpointRole.CLOUD, reopened.selectedDialogueRole.value)
            assertEquals(19L, reopenedMac.cloudDialogueState.value.cursor)
            assertEquals("reply-cloud", reopened.cloudDialogueState.value.submissions.single().eventId)
            assertTrue(reopenedMac.unpair())
            assertNull(reopenedMac.state.value.connection)
            assertEquals(GatewayEndpointRole.CLOUD, reopened.selectedDialogueRole.value)
            assertNull(GatewayTokenVault(secretStore).read("mac"))
            reopened.testPush()

            assertTrue(cloudRequests.all { it.second == "Bearer token-cloud" })
            assertTrue(macRequests.all { it.second == "Bearer token-mac" })
            assertTrue(cloudRequests.any { it.first == "/v1/test-push" })
            assertFalse(macRequests.any { it.first == "/v1/test-push" || it.first == "/v2/dialogue/messages" })
            assertTrue(macRequests.any { it.first == "/v1/unpair" })
            assertTrue(macRequests.any { it.first == "/v1/amber/decision" })
            assertFalse(cloudRequests.any { it.first == "/v1/unpair" || it.first == "/v1/amber/decision" })
        } finally {
            cloudServer.stop(0)
            macServer.stop(0)
            firstScope.cancel()
            reopenedScope.cancel()
            storeScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun macBoardAndScheduleActionsUseOnlyMacPairingAndServerCapabilities() = runBlocking {
        val tempDir = Files.createTempDirectory("amber-mac-schedule").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
        val boardAvailable = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val id = "task-id"
        val board = """{"board":[{"id":"run:1","kind":"run","column":"needsYou","title":"Review","detail":"Waiting","updatedAt":12.0,"approval":{"sessionKey":"amber:run","toolCallId":"tool-1","tool":"request"}}]}"""
        val schedules = """{"schedules":[{"id":"$id","name":"Review","rule":"Daily","enabled":true,"running":false,"paused":true,"lastRunAt":10.0,"autoApprovesHighRisk":true}]}"""
        server.createContext("/") { exchange ->
            val uri = exchange.requestURI.path
            val method = exchange.requestMethod
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += Triple("$method $uri", exchange.requestHeaders.getFirst("Authorization"), body)
            val (code, json) = when ("$method $uri") {
                "GET /v1/status" -> 200 to """{"gateway":{"id":"mac","name":"Mac"},"device":{"id":"device-mac","name":"Android","pushRegistered":false},"serverTime":1.0,"sessions":[]}"""
                "GET /v1/amber/board" -> if (boardAvailable.get()) 200 to board else 404 to """{"error":"not found"}"""
                "GET /v1/amber/schedules" -> 200 to schedules
                else -> 200 to "{}"
            }
            val bytes = json.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                File(tempDir, "mac_tasks.preferences_pb")
            }
            val secretStore = SecretStore(InMemorySecretBackend(), ProgrammableSecretCipher())
            val connection = MacGatewayConnection("mac", "Mac", listOf("127.0.0.1"), server.address.port, "pin", "device-mac", GatewayEndpointRole.MAC)
            GatewayTokenVault(secretStore).write(connection.gatewayId, "test-mac-token")
            store.edit {
                it[stringPreferencesKey("mac_connection")] = MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection)
            }
            val cloud = MacGatewayRepository(
                context = ContextWrapper(null), appScope = scope, secretStore = secretStore,
                dataStore = store, foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, token -> MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, token, scheme = "http") },
            )
            val mac = cloud.slot(GatewayEndpointRole.MAC)
            mac.refresh()
            val generation = mac.state.value.generation
            mac.refreshMacTasks(generation)
            assertTrue(mac.state.value.macTasks.board?.exceptionOrNull() is MacGatewayException.NotFound)
            assertTrue(mac.state.value.macTasks.schedules?.getOrThrow()?.single()?.paused == true)
            assertNull(cloud.state.value.macTasks.schedules)

            boardAvailable.set(true)
            mac.refreshMacTasks(generation)
            val approval = mac.state.value.macTasks.board?.getOrThrow()?.single()?.approval
            assertTrue(approval?.asPendingApproval()?.effectiveRequiresLocalApproval == true)
            val allow = runCatching {
                mac.decide("amber:run", "tool-1", allow = true, expectedGeneration = generation, fromMacBoard = true)
            }.exceptionOrNull()
            assertTrue(allow is MacGatewayException.Forbidden && allow.requiresLocalApproval)
            assertFalse(requests.any { it.first == "POST /v1/amber/decision" })
            mac.decide("amber:run", "tool-1", allow = false, expectedGeneration = generation, fromMacBoard = true)

            val draft = MacScheduleDraft(" Review ", " Check ", MacScheduleDraft.Rule.WEEKLY, weekdays = setOf(6, 2))
            assertTrue(runCatching { cloud.createMacSchedule(draft, generation) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { mac.runMacSchedule(id, generation + 1) }.exceptionOrNull() is MacGatewayException.Conflict)
            mac.createMacSchedule(draft, generation)
            mac.setMacScheduleEnabled(id, enabled = false, expectedGeneration = generation)
            mac.runMacSchedule(id, generation)

            val writes = requests.filter { it.first.startsWith("POST") }
            assertEquals(listOf(
                "POST /v1/amber/decision",
                "POST /v1/amber/schedules",
                "POST /v1/amber/schedules/$id/enabled",
                "POST /v1/amber/schedules/$id/run",
            ), writes.map { it.first })
            assertTrue(requests.all { it.second == "Bearer test-mac-token" })
            val body = MacGatewayJson.parseToJsonElement(writes[1].third).jsonObject
            assertEquals(setOf("name", "prompt", "rule", "hour", "minute", "weekdays"), body.keys)
            assertEquals(listOf("2", "6"), body.getValue("weekdays").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("Review", body.getValue("name").jsonPrimitive.content)
        } finally {
            server.stop(0)
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cloudDetailUsesOnlyTheCurrentCloudPairingAndDeclaredRoutes() = runBlocking {
        val tempDir = Files.createTempDirectory("amber-cloud-detail").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val redirect = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer test-cloud-token"
            val route = "${exchange.requestMethod} $path"
            requests += route to authorized
            val (code, json) = when (route) {
                "GET /v2/configuration" -> if (redirect.get()) {
                    exchange.responseHeaders.set("Location", "/v2/configuration/import-key")
                    302 to "{}"
                } else {
                    200 to """{"search":{"sources":[],"mode":"off"},"credentials":{"modelApiKey":{"configured":true},"exaApiKey":{"configured":false},"braveApiKey":{"configured":false}}}"""
                }
                "POST /v2/work-items" -> 202 to """{"workItem":{"id":"work-1","title":"Test","state":"QUEUED"}}"""
                else -> 404 to "{}"
            }
            exchange.requestBody.close()
            val bytes = json.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                File(tempDir, "cloud_detail.preferences_pb")
            }
            val secretStore = SecretStore(InMemorySecretBackend(), ProgrammableSecretCipher())
            val connection = MacGatewayConnection("cloud", "Cloud", listOf("127.0.0.1"), server.address.port, "pin", "device", GatewayEndpointRole.CLOUD)
            GatewayTokenVault(secretStore).write(connection.gatewayId, "test-cloud-token")
            store.edit { it[stringPreferencesKey("connection")] = MacGatewayJson.encodeToString(MacGatewayConnection.serializer(), connection) }
            val cloud = MacGatewayRepository(
                context = ContextWrapper(null), appScope = scope, secretStore = secretStore,
                dataStore = store, foregroundFlow = MutableStateFlow(false),
                clientFactory = { conn, token ->
                    MacGatewayClient(conn.addrs, conn.port, conn.fingerprint, token, scheme = "http", endpointRole = conn.role)
                },
            )
            cloud.awaitReady()
            val generation = cloud.state.value.generation
            val transport = MacGatewayClient::requestCloudDetail
            val detail = CloudEndpointDetailApi(cloud, connection, generation, transport)
            val stale = CloudEndpointDetailApi(cloud, connection, generation + 1, transport)
            val changedPeer = CloudEndpointDetailApi(cloud, connection.copy(fingerprint = "changed"), generation, transport)
            val mac = MacGatewayClient(connection.addrs, connection.port, "pin", "test-mac-token", "http", GatewayEndpointRole.MAC)

            assertTrue(runCatching { stale.submitTask("request", "Test") }.exceptionOrNull() is MacGatewayException.Conflict)
            assertTrue(runCatching { changedPeer.configuration() }.exceptionOrNull() is MacGatewayException.Conflict)
            assertTrue(runCatching { mac.requestCloudDetail("GET", listOf("v2", "configuration"), null) }
                .exceptionOrNull() is MacGatewayException.Forbidden)
            assertTrue(runCatching { detail.updateRule("..", CloudRuleAction.Confirm) }.exceptionOrNull() is MacGatewayException.Forbidden)
            assertTrue(requests.isEmpty())

            assertTrue(detail.configuration().credentials.modelApiKey.configured)
            assertEquals("work-1", detail.submitTask("request", "Test").id)
            redirect.set(true)
            assertTrue(runCatching { detail.configuration() }.isFailure)
            assertEquals(listOf(
                "GET /v2/configuration" to true, "POST /v2/work-items" to true, "GET /v2/configuration" to true,
            ), requests.toList())
        } finally {
            server.stop(0)
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

}
