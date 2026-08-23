package app.amber.core.settings.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {

    @Test
    fun `create read update delete round-trip`() {
        val store = fakeSecretStore()
        val descriptor = SecretDescriptor("provider", "owner-1", "apiKey")

        store.create(descriptor, "sk-value-1")
        assertEquals("sk-value-1", store.read(descriptor))

        store.update(descriptor, "sk-value-2")
        assertEquals("sk-value-2", store.read(descriptor))

        store.delete(descriptor)
        assertNull(store.read(descriptor))
        assertTrue(store.listOrphans(emptySet()).isEmpty())
    }

    @Test
    fun `create throws when key already exists`() {
        val store = fakeSecretStore()
        val descriptor = SecretDescriptor("provider", "owner-1", "apiKey")
        store.create(descriptor, "sk-value-1")
        runCatching { store.create(descriptor, "sk-value-2") }
            .onFailure { return }
        error("create on existing key must throw")
    }

    @Test
    fun `read returns null when cipher cannot decrypt (keystore invalidation)`() {
        val store = SecretStore(
            backend = inMemoryBackend(),
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = null
            },
        )
        val descriptor = SecretDescriptor("provider", "owner-1", "apiKey")
        store.update(descriptor, "sk-value-1")
        assertNull(store.read(descriptor))
    }

    @Test
    fun `listOrphans only lists entries not referenced by active descriptors`() {
        val store = fakeSecretStore()
        val active = SecretDescriptor("provider", "owner-1", "apiKey")
        val orphan = SecretDescriptor("provider", "owner-2", "apiKey")
        store.update(active, "sk-active")
        store.update(orphan, "sk-orphan")

        val orphans = store.listOrphans(setOf(active))
        assertEquals(listOf(orphan.key), orphans.map { it.key })
    }

    @Test
    fun `deleteOrphans only deletes confirmed unreferenced entries`() {
        val store = fakeSecretStore()
        val active = SecretDescriptor("provider", "owner-1", "apiKey")
        val orphan = SecretDescriptor("mcp", "server-2", "header:Authorization")
        store.update(active, "sk-active")
        store.update(orphan, "sk-orphan")

        store.deleteOrphans(setOf(active))

        assertEquals("sk-active", store.read(active))
        assertNull(store.read(orphan))
    }

    @Test
    fun `migration version marker round-trip`() {
        val store = fakeSecretStore()
        assertEquals(0, store.migrationVersion())
        store.markMigrated(1)
        assertEquals(1, store.migrationVersion())
    }

    @Test
    fun `migration marker is not treated as a secret entry`() {
        val store = fakeSecretStore()
        store.markMigrated(1)
        assertTrue(store.listOrphans(emptySet()).isEmpty())
    }

    @Test
    fun `descriptor key contains scope owner and field but never the value`() {
        val descriptor = SecretDescriptor("provider", "owner/1", "apiKey")
        assertEquals("provider/owner_1/apiKey", descriptor.key)
        assertFalse(descriptor.key.contains("sk-"))
    }

    @Test
    fun `descriptor fromKey round-trips`() {
        val descriptor = SecretDescriptor("provider", "owner-1", "apiKey")
        assertEquals(descriptor, SecretDescriptor.fromKey(descriptor.key))
        assertNull(SecretDescriptor.fromKey("only-two-parts"))
    }
}
