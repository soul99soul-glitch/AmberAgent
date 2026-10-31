package app.amber.core.settings.ssh

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.amber.core.settings.secret.fakeSecretStore
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshKey
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshRouteException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshKeyAndRouteStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `SshKeyStore saves reads and redacts private key material`() = runBlocking {
        val dataStore = createStore()
        val secretStore = fakeSecretStore()
        val keyStore = SshKeyStore(dataStore, secretStore)

        val saved = keyStore.save(
            key = SshKey(
                id = "key-1",
                name = "Work Ed25519",
                algorithm = "ssh-ed25519",
                publicKeyOpenSsh = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI... amber@Work",
                fingerprintSha256 = "SHA256:testfingerprint123",
                hasPassphrase = true,
            ),
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nsecret-material\n-----END OPENSSH PRIVATE KEY-----",
            passphrase = "my-key-passphrase",
        )

        val material = keyStore.readMaterial(saved)
        assertTrue(material.privateKey.orEmpty().contains("secret-material"))
        assertEquals("my-key-passphrase", material.passphrase)
        assertFalse(material.toString().contains("secret-material"))
        assertFalse(material.toString().contains("my-key-passphrase"))
        assertTrue(material.toString().contains("<redacted>"))
    }

    @Test
    fun `SshKeyStore and SshProfileStore reject all 5 redaction placeholders`() = runBlocking {
        val dataStore = createStore()
        val secretStore = fakeSecretStore()
        val keyStore = SshKeyStore(dataStore, secretStore)
        val profileStore = SshProfileStore(dataStore, secretStore, keyStore)

        val placeholders = listOf("<redacted>", "[redacted]", "***", "••••", "(empty)")
        for (placeholder in placeholders) {
            val keyErr = runCatching {
                keyStore.save(
                    key = SshKey(
                        id = "k",
                        name = "k",
                        algorithm = "ssh-ed25519",
                        publicKeyOpenSsh = "ssh-ed25519 AAAA",
                        fingerprintSha256 = "SHA256:abc",
                    ),
                    privateKey = placeholder,
                )
            }.exceptionOrNull()
            assertTrue("Expected placeholder '$placeholder' to be rejected by SshKeyStore", keyErr is IllegalArgumentException)

            val passErr = runCatching {
                profileStore.save(
                    profile = profile("p1", authMethod = SshAuthMethod.PASSWORD),
                    password = placeholder,
                )
            }.exceptionOrNull()
            assertTrue("Expected placeholder '$placeholder' to be rejected by SshProfileStore", passErr is IllegalArgumentException)
        }
    }

    @Test
    fun `profile with keyId resolves credentials from SshKeyStore and blocks key deletion while in use`() = runBlocking {
        val dataStore = createStore()
        val secretStore = fakeSecretStore()
        val keyStore = SshKeyStore(dataStore, secretStore)
        val profileStore = SshProfileStore(dataStore, secretStore, keyStore)

        val key = keyStore.save(
            key = SshKey(
                id = "shared-key",
                name = "Shared Key",
                algorithm = "ssh-ed25519",
                publicKeyOpenSsh = "ssh-ed25519 AAAA shared",
                fingerprintSha256 = "SHA256:shared",
            ),
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nshared-private-key\n-----END OPENSSH PRIVATE KEY-----",
            passphrase = "shared-passphrase",
        )

        profileStore.save(
            profile = profile("bastion", authMethod = SshAuthMethod.PRIVATE_KEY, keyId = key.id),
        )
        val savedProfile = profileStore.resolve("bastion")
        val creds = profileStore.credentialsFor(savedProfile)
        assertTrue(creds.privateKey.orEmpty().contains("shared-private-key"))
        assertEquals("shared-passphrase", creds.passphrase)

        // Deleting key while referenced by bastion must fail with KeyInUse
        val deleteErr = runCatching {
            keyStore.delete(key.id)
        }.exceptionOrNull() as? SshRouteException
        assertEquals("key_in_use", deleteErr?.error?.code)

        // After deleting profile, key can be deleted
        profileStore.delete("bastion")
        keyStore.delete(key.id)
        assertTrue(keyStore.snapshot().items.isEmpty())
    }

    @Test
    fun `jump chain validation blocks cycles over-3-hops and deleting jump host in use`() = runBlocking {
        val dataStore = createStore()
        val secretStore = fakeSecretStore()
        val keyStore = SshKeyStore(dataStore, secretStore)
        val store = SshProfileStore(dataStore, secretStore, keyStore)

        store.save(profile("j1", host = "1.1.1.1"), password = "p1")
        store.save(profile("j2", host = "1.1.1.2", jumpProfileId = "j1"), password = "p2")
        store.save(profile("j3", host = "1.1.1.3", jumpProfileId = "j2"), password = "p3")
        store.save(profile("target", host = "10.0.0.1", jumpProfileId = "j3"), password = "p4")

        val route = store.resolveRoute("target")
        assertEquals(listOf("j1", "j2", "j3", "target"), route.map { it.id })

        // Saving j0 and pointing j1 -> j0 would make target have 4 jumps (5 hops total) -> rejected!
        store.save(profile("j0", host = "1.1.1.0"), password = "p0")
        val tooLongErr = runCatching {
            val j1Current = store.resolve("j1")
            store.save(j1Current.copy(jumpProfileId = "j0"))
        }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_chain_too_long", tooLongErr?.error?.code)

        // Pointing j1 -> target would create a cycle -> rejected!
        val cycleErr = runCatching {
            val j1Current = store.resolve("j1")
            store.save(j1Current.copy(jumpProfileId = "target"))
        }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_cycle", cycleErr?.error?.code)

        // Deleting j2 while j3 references it -> rejected with in_use_as_jump!
        val inUseErr = runCatching {
            store.delete("j2")
        }.exceptionOrNull() as? SshRouteException
        assertEquals("in_use_as_jump", inUseErr?.error?.code)
    }

    @Test
    fun `changing jumpProfileId clears acceptedHostKeyFingerprint`() = runBlocking {
        val dataStore = createStore()
        val secretStore = fakeSecretStore()
        val store = SshProfileStore(dataStore, secretStore)

        store.save(profile("j1", host = "1.1.1.1"), password = "p1")
        store.save(profile("j2", host = "1.1.1.2"), password = "p2")
        store.save(profile("app", host = "10.0.0.5", jumpProfileId = "j1"), password = "p3")

        val savedApp = store.resolve("app")
        store.acceptHostKey(savedApp, "aa".repeat(32))
        val trustedApp = store.resolve("app")
        assertEquals("aa".repeat(32), trustedApp.acceptedHostKeyFingerprint)
        assertEquals("j1", trustedApp.acceptedHostKeyViaJump)

        // Switching jumpProfileId from j1 to j2 clears acceptedHostKeyFingerprint
        store.save(trustedApp.copy(jumpProfileId = "j2"))
        val movedApp = store.resolve("app")
        assertNull(movedApp.acceptedHostKeyFingerprint)
        assertNull(movedApp.acceptedHostKeyViaJump)
    }

    private fun profile(
        id: String,
        host: String = "example.com",
        authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
        keyId: String? = null,
        jumpProfileId: String? = null,
    ) = SshProfile(
        id = id,
        name = id,
        host = host,
        port = 22,
        username = "amber",
        authMethod = authMethod,
        keyId = keyId,
        jumpProfileId = jumpProfileId,
        createdAtMs = 1L,
        updatedAtMs = 1L,
    )

    private fun createStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        tempFolder.newFile("ssh-key-route-${System.nanoTime()}.preferences_pb")
    }
}
