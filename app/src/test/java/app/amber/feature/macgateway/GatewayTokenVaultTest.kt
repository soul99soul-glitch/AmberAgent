package app.amber.feature.macgateway

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            repo1.awaitReady()

            // Legacy key removed from DataStore, encrypted value stored and readable from SecretStore.
            val prefsAfter = dataStore.data.first()
            assertNull("Legacy DataStore token key must be removed after migration", prefsAfter[stringPreferencesKey("token")])
            assertEquals(legacySecret, GatewayTokenVault(secretStore).read("gw-1"))
            assertEquals("gw-1", repo1.state.value.connection?.gatewayId)

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
            repo2.awaitReady()
            repo2.refresh()
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
                name = "Mac",
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
            repo.awaitReady()
            assertEquals("gw-forget", repo.state.value.connection?.gatewayId)
            val generationBefore = repo.connectionGeneration.value

            repo.unpair()

            assertNull("SecretStore entry must be deleted on forget", vault.read("gw-forget"))
            assertTrue("DataStore must be cleared on forget", dataStore.data.first().asMap().isEmpty())
            assertNull(repo.state.value.connection)
            assertEquals(generationBefore + 1L, repo.connectionGeneration.value)
        } finally {
            server.stop(0)
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
