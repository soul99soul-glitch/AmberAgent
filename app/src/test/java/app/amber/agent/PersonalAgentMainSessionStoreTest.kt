package app.amber.agent

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalAgentMainSessionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PersonalAgentMainSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `localMainConversationId is idempotent and persists to personal_agent prefs`() {
        val store = PersonalAgentMainSessionStore(context)

        val first = store.localMainConversationId()
        val second = store.localMainConversationId()

        assertEquals(first, second)
        assertTrue(runCatching { Uuid.parse(first) }.isSuccess)

        val rawPrefs = context.getSharedPreferences("personal_agent", Context.MODE_PRIVATE)
        assertEquals(first, rawPrefs.getString("local_main_conversation_id", null))

        // A new store instance reads the same durable ID without rotating.
        val recreatedStore = PersonalAgentMainSessionStore(context)
        assertEquals(first, recreatedStore.localMainConversationId())
    }

    @Test
    fun `local and per-gateway cloud main IDs are isolated and peek is read-only`() {
        val store = PersonalAgentMainSessionStore(context)

        assertNull(store.peekCloudMainConversationId("gw-alpha"))
        assertNull(store.peekCloudMainConversationId("gw-beta"))
        assertNull(store.peekCloudMainConversationId("   "))

        val rawPrefs = context.getSharedPreferences("personal_agent", Context.MODE_PRIVATE)
        assertTrue("peekCloudMainConversationId must not write any keys", rawPrefs.all.isEmpty())

        val localId = store.localMainConversationId()
        val alphaId = store.cloudMainConversationId("gw-alpha")
        val betaId = store.cloudMainConversationId("gw-beta")

        assertNotEquals(localId, alphaId)
        assertNotEquals(localId, betaId)
        assertNotEquals(alphaId, betaId)

        assertEquals(alphaId, store.cloudMainConversationId("gw-alpha"))
        assertEquals(betaId, store.cloudMainConversationId("gw-beta"))
        assertEquals(alphaId, store.peekCloudMainConversationId("gw-alpha"))
        assertEquals(betaId, store.peekCloudMainConversationId("gw-beta"))

        assertEquals(alphaId, rawPrefs.getString("cloud_main_conversation_id:gw-alpha", null))
        assertEquals(betaId, rawPrefs.getString("cloud_main_conversation_id:gw-beta", null))
    }

    @Test
    fun `getOrCreate commits synchronously before returning and never uses apply`() {
        val spyPrefs = RecordingSharedPreferences(commitSucceeds = true)
        val expectedId = "33333333-3333-3333-3333-333333333333"
        val store = PersonalAgentMainSessionStore.createForTesting(
            prefs = spyPrefs,
            idGenerator = { expectedId },
        )

        val created = store.localMainConversationId()

        assertEquals(expectedId, created)
        assertEquals(1, spyPrefs.commitCount)
        assertEquals(0, spyPrefs.applyCount)
        assertEquals(expectedId, spyPrefs.getString("local_main_conversation_id", null))

        // Second call hits the persisted value and does not commit again.
        assertEquals(expectedId, store.localMainConversationId())
        assertEquals(1, spyPrefs.commitCount)
    }

    @Test
    fun `getOrCreate throws when synchronous commit fails`() {
        val failingPrefs = RecordingSharedPreferences(commitSucceeds = false)
        val store = PersonalAgentMainSessionStore.createForTesting(
            prefs = failingPrefs,
            idGenerator = { "44444444-4444-4444-4444-444444444444" },
        )

        try {
            store.cloudMainConversationId("gw-failing")
            fail("Expected IllegalStateException when commit() returns false")
        } catch (_: IllegalStateException) {
            // Expected: must not return an unpersisted ID.
        }
        assertEquals(1, failingPrefs.commitCount)
        assertNull(store.peekCloudMainConversationId("gw-failing"))
    }

    @Test
    fun `corrupted non-uuid value is replaced and peek ignores it`() {
        val rawPrefs = context.getSharedPreferences("personal_agent", Context.MODE_PRIVATE)
        rawPrefs.edit()
            .putString("cloud_main_conversation_id:gw-corrupt", "not-a-uuid")
            .commit()

        val store = PersonalAgentMainSessionStore(context)
        assertNull(store.peekCloudMainConversationId("gw-corrupt"))

        val repaired = store.cloudMainConversationId("gw-corrupt")
        assertTrue(runCatching { Uuid.parse(repaired) }.isSuccess)
        assertEquals(repaired, store.peekCloudMainConversationId("gw-corrupt"))
    }
}

private class RecordingSharedPreferences(
    private val commitSucceeds: Boolean,
) : SharedPreferences {
    private val backing = LinkedHashMap<String, Any?>()
    var commitCount: Int = 0
    var applyCount: Int = 0

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(backing)
    override fun getString(key: String?, defValue: String?): String? =
        (backing[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (backing[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (backing[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (backing[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (backing[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (backing[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = backing.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) removed.add(key)
        }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            commitCount += 1
            if (!commitSucceeds) return false
            if (clear) backing.clear()
            removed.forEach { backing.remove(it) }
            staged.forEach { (k, v) ->
                if (v == null) backing.remove(k) else backing[k] = v
            }
            return true
        }

        override fun apply() {
            applyCount += 1
        }
    }
}
