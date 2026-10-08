package app.amber.agent.data.sync

import android.app.Application
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.sync.core.DeviceBoundBackupKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceBoundBackupKeyRecoveryTest {
    @Test
    fun unreadableRestoredCiphertextDoesNotPreventCreatingANewBackupKey() {
        val backend = Backend()
        backend.put(DeviceBoundBackupKey.DESCRIPTOR.key, "old-device-ciphertext")
        val key = DeviceBoundBackupKey(SecretStore(backend, Cipher()))
        assertNull("restoring an old device archive must still reject", key.current())

        val created = key.getOrCreate()

        assertNotEquals("old-device-ciphertext", created)
        assertEquals(created, key.current())
        assertEquals(created, key.getOrCreate())
    }

    @Test
    fun concurrentExportsUseTheSameDurableDeviceSecret() {
        val backend = Backend(delayFirstRead = true)
        val key = DeviceBoundBackupKey(SecretStore(backend, Cipher()))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map {
                pool.submit<String> {
                    start.await()
                    key.getOrCreate()
                }
            }
            start.countDown()
            val secrets = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, secrets.toSet().size)
            assertEquals(secrets.first(), key.current())
        } finally {
            pool.shutdownNow()
        }
    }

    private class Backend(private val delayFirstRead: Boolean = false) : SecretStoreBackend {
        private val entries = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? {
            val value = entries[key]
            if (delayFirstRead && value == null) Thread.sleep(30)
            return value
        }
        override fun put(key: String, value: String) { entries[key] = value }
        override fun remove(key: String) { entries.remove(key) }
        override fun keys(): Set<String> = entries.keys.toSet()
    }

    private class Cipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "new-device:$plaintext"
        override fun decrypt(stored: String): String? =
            stored.takeIf { it.startsWith("new-device:") }?.removePrefix("new-device:")
    }
}
