package app.amber.core.settings.ssh

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.amber.core.agent.utils.JsonInstant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.fakeSecretStore
import app.amber.core.settings.secret.inMemoryBackend
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshProfilesState

class SshProfileStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `multiple profiles round trip with independent credentials and first default`() = runBlocking {
        val dataStore = createStore()
        val secretStore = fakeSecretStore()
        val store = SshProfileStore(dataStore, secretStore)
        val first = profile("first")
        val second = profile("second", authMethod = SshAuthMethod.PRIVATE_KEY)

        store.save(first, password = "first-password")
        store.save(second, privateKey = "second-private-key", passphrase = "second-passphrase")

        val state = store.snapshot()
        assertEquals(listOf("first", "second"), state.profiles.map { it.id })
        assertEquals("first", state.defaultProfileId)
        assertTrue(state.profiles.all { it.credentialRevision.isNotBlank() })
        val storedFirst = state.profiles.first { it.id == first.id }
        val storedSecond = state.profiles.first { it.id == second.id }
        assertEquals("first-password", store.readCredentials(storedFirst).password)
        assertNull(store.readCredentials(storedFirst).privateKey)
        assertEquals("second-private-key", store.readCredentials(storedSecond).privateKey)
        assertEquals("second-passphrase", store.readCredentials(storedSecond).passphrase)

        val persisted = dataStore.data.first()[stringPreferencesKey("ssh_profiles")]!!
        assertFalse("profile metadata must not contain credentials", persisted.contains("first-password"))
        assertFalse("profile metadata must not contain credentials", persisted.contains("second-private-key"))

        val restarted = SshProfileStore(dataStore, secretStore)
        assertEquals(state, restarted.snapshot())
    }

    @Test
    fun `deleting default selects stable first remaining profile and only owned secrets`() = runBlocking {
        val secretStore = fakeSecretStore()
        val store = SshProfileStore(createStore(), secretStore)
        val first = profile("first")
        val second = profile("second")
        val unrelated = SecretDescriptor("provider", "owner", "apiKey")

        store.save(first, password = "first-password")
        store.save(second, password = "second-password")
        secretStore.update(unrelated, "must-survive")

        store.delete("first")

        assertEquals("second", store.snapshot().defaultProfileId)
        assertNull(store.readCredentials(first).password)
        val storedSecond = store.snapshot().profiles.single { it.id == second.id }
        assertEquals("second-password", store.readCredentials(storedSecond).password)
        assertEquals("must-survive", secretStore.read(unrelated))

        store.delete("second")
        assertEquals(emptyList<SshProfile>(), store.snapshot().profiles)
        assertNull(store.snapshot().defaultProfileId)
    }

    @Test
    fun `endpoint change clears trust and stale CAS is rejected`() = runBlocking {
        val store = SshProfileStore(createStore(), fakeSecretStore())
        val original = profile("server")
        val fingerprintA = "aa".repeat(32)
        val fingerprintB = "bb".repeat(32)

        store.save(original)
        val saved = store.snapshot().profiles.single()
        store.acceptHostKey(saved, fingerprintA)
        val trusted = store.snapshot().profiles.single()

        store.save(trusted.copy(host = "new.example.com"))
        val moved = store.snapshot().profiles.single()
        assertNull(moved.acceptedHostKeyFingerprint)
        assertTrue(runCatching { store.acceptHostKey(trusted, fingerprintB) }.isFailure)

        store.acceptHostKey(moved, fingerprintB)
        assertEquals(fingerprintB, store.snapshot().profiles.single().acceptedHostKeyFingerprint)
    }

    @Test
    fun `save cannot override accepted fingerprint and auth switch removes old secret`() = runBlocking {
        val secretStore = fakeSecretStore()
        val store = SshProfileStore(createStore(), secretStore)
        val passwordProfile = profile("server")

        store.save(passwordProfile, password = "old-password")
        val trusted = store.snapshot().profiles.single().let {
            store.acceptHostKey(it, "aa".repeat(32))
            store.snapshot().profiles.single()
        }
        store.save(
            trusted.copy(
                acceptedHostKeyFingerprint = "bb".repeat(32),
                authMethod = SshAuthMethod.PRIVATE_KEY,
            ),
            privateKey = "new-private-key",
            passphrase = "new-passphrase",
        )

        val saved = store.snapshot().profiles.single()
        assertEquals("aa".repeat(32), saved.acceptedHostKeyFingerprint)
        assertNull(store.readCredentials(saved).password)
        assertEquals("new-private-key", store.readCredentials(saved).privateKey)
        assertEquals("new-passphrase", store.readCredentials(saved).passphrase)

        store.save(saved.copy(authMethod = SshAuthMethod.PASSWORD), password = "next-password")
        val passwordSaved = store.snapshot().profiles.single()
        assertEquals("next-password", store.readCredentials(passwordSaved).password)
        assertNull(store.readCredentials(passwordSaved).privateKey)
        assertNull(store.readCredentials(passwordSaved).passphrase)
    }

    @Test
    fun `empty passphrase clears it and credentials string is redacted`() = runBlocking {
        val secretStore = fakeSecretStore()
        val store = SshProfileStore(createStore(), secretStore)
        val profile = profile("server", authMethod = SshAuthMethod.PRIVATE_KEY)

        store.save(profile, privateKey = "private-key", passphrase = "secret-passphrase")
        val saved = store.snapshot().profiles.single()
        store.save(saved, passphrase = "")

        val credentials = store.readCredentials(store.snapshot().profiles.single())
        assertNull(credentials.passphrase)
        assertFalse(credentials.toString().contains("private-key"))
        assertFalse(credentials.toString().contains("secret-passphrase"))
        assertTrue(credentials.toString().contains("<redacted>"))
    }

    @Test
    fun `invalid endpoint and credential input is rejected`() = runBlocking {
        val store = SshProfileStore(createStore(), fakeSecretStore())

        assertTrue(runCatching { store.save(profile("server", host = "bad host")) }.isFailure)
        assertTrue(runCatching { store.save(profile("server", username = "bad user")) }.isFailure)
        assertTrue(runCatching { store.save(profile("server"), password = " ") }.isFailure)
        assertTrue(runCatching { store.save(profile("server"), password = "x".repeat(64 * 1024 + 1)) }.isFailure)
        assertTrue(runCatching { store.acceptHostKey(profile("server"), "zz") }.isFailure)

        assertEquals(emptyList<SshProfile>(), store.snapshot().profiles)
    }

    @Test
    fun `credentialsFor rejects a stale profile snapshot`() = runBlocking {
        val store = SshProfileStore(createStore(), fakeSecretStore())
        val profile = profile("server")
        store.save(profile, password = "password")
        val captured = store.snapshot().profiles.single()

        store.save(captured.copy(username = "new-user"), password = "new-password")

        assertTrue(runCatching { runBlocking { store.credentialsFor(captured) } }.isFailure)
    }

    @Test
    fun `empty revision reads legacy credential keys and upgrades on save`() = runBlocking {
        val dataStore = createStore()
        val secretStore = fakeSecretStore()
        val store = SshProfileStore(dataStore, secretStore)
        val legacy = profile("legacy")
        val legacyPassword = SecretDescriptor("ssh", "legacy", "password")
        secretStore.update(legacyPassword, "legacy-password")
        dataStore.edit {
            it[stringPreferencesKey("ssh_profiles")] = JsonInstant.encodeToString(
                SshProfilesState(profiles = listOf(legacy), defaultProfileId = legacy.id)
            )
        }

        assertEquals("legacy-password", store.readCredentials(legacy).password)
        store.save(legacy)

        val upgraded = store.snapshot().profiles.single()
        assertTrue(upgraded.credentialRevision.isNotBlank())
        assertEquals("legacy-password", store.readCredentials(upgraded).password)
        assertNull(secretStore.read(legacyPassword))
    }

    @Test
    fun `failed credential write leaves the old profile generation usable`() = runBlocking {
        val backend = inMemoryBackend()
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = "enc:$plaintext"
            override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
        }
        val dataStore = createStore()
        val workingStore = SshProfileStore(dataStore, SecretStore(backend, cipher))
        val profile = profile("server")
        workingStore.save(profile, password = "old-password")
        val captured = workingStore.snapshot().profiles.single()

        val failingSecretStore = SecretStore(
            backend = backend,
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = error("keystore unavailable")
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
        val failingStore = SshProfileStore(dataStore, failingSecretStore)
        assertTrue(
            runCatching {
                failingStore.save(captured.copy(name = "renamed"), password = "new-password")
            }.isFailure,
        )

        assertEquals(captured, workingStore.snapshot().profiles.single())
        assertEquals("old-password", workingStore.readCredentials(captured).password)
    }

    @Test
    fun `lost publication acknowledgement never deletes the committed credentials`() = runBlocking {
        val dataStore = createStore()
        val secrets = fakeSecretStore()
        val original = SshProfileStore(dataStore, secrets)
        original.save(profile("server"), password = "old-password")
        val saved = original.resolve()
        val lostAcknowledgement = object : DataStore<Preferences> {
            override val data = dataStore.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                dataStore.updateData(transform)
                throw kotlinx.coroutines.CancellationException("publication acknowledgement lost")
            }
        }

        assertTrue(runCatching {
            SshProfileStore(lostAcknowledgement, secrets).save(
                saved.copy(host = "new.example.com"), password = "new-password",
            )
        }.isFailure)
        val committed = original.resolve()
        assertEquals("new.example.com", committed.host)
        assertEquals("new-password", original.credentialsFor(committed).password)
    }

    @Test
    fun `editing with an unreadable retained secret preserves its stored reference`() = runBlocking {
        val dataStore = createStore()
        val backend = inMemoryBackend()
        val cipher = object : SecretCipher {
            var readable = true
            override fun encrypt(plaintext: String) = "enc:$plaintext"
            override fun decrypt(stored: String): String? = if (readable) stored.removePrefix("enc:") else null
        }
        val store = SshProfileStore(dataStore, SecretStore(backend, cipher))
        store.save(profile("server"), password = "old-password")
        val saved = store.resolve()
        cipher.readable = false
        assertTrue(runCatching { store.save(saved.copy(name = "renamed")) }.isFailure)
        assertEquals(saved, store.resolve())
        cipher.readable = true
        assertEquals("old-password", store.credentialsFor(saved).password)
    }

    private fun profile(
        id: String,
        host: String = "example.com",
        username: String = "amber",
        authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    ) = SshProfile(
        id = id,
        name = id,
        host = host,
        port = 22,
        username = username,
        authMethod = authMethod,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun createStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        tempFolder.newFile("ssh-${System.nanoTime()}.preferences_pb")
    }
}
