package app.amber.core.settings.secret

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.mcp.McpCommonOptions
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.PreferencesKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SettingsSecretMigrationTest {
    private lateinit var tempDir: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var backend: SecretStoreBackend
    private lateinit var store: SecretStore
    private lateinit var redactor: SecretRedactor
    private lateinit var migrator: SettingsSecretMigrator

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "secret-migration-${System.nanoTime()}").apply { mkdirs() }
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) {
            File(tempDir, "settings.preferences_pb")
        }
        backend = inMemoryBackend()
        store = fakeSecretStore(backend)
        redactor = SecretRedactor(store)
        migrator = SettingsSecretMigrator(dataStore, store, redactor)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private suspend fun providersJson(): String? =
        dataStore.data.first()[PreferencesKeys.PROVIDERS]

    // ---------------- 基础迁移 ----------------

    @Test
    fun `legacy plaintext migrates to refs and masks, rerun is a no-op`() = runBlocking {
        val provider: ProviderSetting = ProviderSetting.OpenAI(apiKey = "sk-legacy-secret-42")
        dataStore.edit { it[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(listOf(provider)) }

        val version = migrator.migrateIfNeeded()
        assertEquals(1, version)

        val persisted = providersJson()!!
        assertFalse("DataStore must not contain plaintext after migration", persisted.contains("sk-legacy-secret-42"))
        assertTrue("DataStore must keep mask", persisted.contains(SecretRedactor.MASK_STRING))

        val refs = redactor.readRefs(dataStore.data.first())
        assertTrue(refs.isNotEmpty())
        assertEquals("sk-legacy-secret-42", store.read(refs.values.single().descriptor()))

        // 中断/重复执行安全：重跑不重写、不报错、版本保持
        val keysBefore = store.listOrphans(emptySet()).size
        val versionAgain = migrator.migrateIfNeeded()
        assertEquals(1, versionAgain)
        assertEquals(keysBefore, store.listOrphans(emptySet()).size)
        assertEquals("sk-legacy-secret-42", store.read(refs.values.single().descriptor()))
    }

    @Test
    fun `mcp headers migrate too`() = runBlocking {
        val server: McpServerConfig = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(headers = listOf("Authorization" to "Bearer legacy-mcp-token")),
        )
        dataStore.edit { it[PreferencesKeys.MCP_SERVERS] = JsonInstant.encodeToString(listOf(server)) }

        assertEquals(1, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()[PreferencesKeys.MCP_SERVERS]!!
        assertFalse(persisted.contains("legacy-mcp-token"))
        val refs = redactor.readRefs(dataStore.data.first())
        assertTrue(refs.values.any { it.scope == "mcp" && it.fieldName == "header:Authorization" })
        assertEquals("Bearer legacy-mcp-token", store.read(refs.values.first { it.scope == "mcp" }.descriptor()))
    }

    // ---------------- 中断后重跑 ----------------

    @Test
    fun `interrupted after secrets written but before DataStore write reruns cleanly`() = runBlocking {
        val provider: ProviderSetting = ProviderSetting.OpenAI(apiKey = "sk-interrupt-1-77")
        dataStore.edit { it[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(listOf(provider)) }
        // 模拟崩溃点：secret 已写入 SecretStore，DataStore 仍是明文
        redactor.redactProviders(listOf(provider), emptyMap(), mutableMapOf())
        assertTrue(providersJson()!!.contains("sk-interrupt-1-77"))

        val version = migrator.migrateIfNeeded()
        assertEquals(1, version)
        assertFalse(providersJson()!!.contains("sk-interrupt-1-77"))
        val refs = redactor.readRefs(dataStore.data.first())
        assertEquals("sk-interrupt-1-77", store.read(refs.values.single().descriptor()))
    }

    @Test
    fun `interrupted after DataStore write but before version mark reruns as no-op`() = runBlocking {
        val provider: ProviderSetting = ProviderSetting.OpenAI(apiKey = "sk-interrupt-2-88")
        dataStore.edit { it[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(listOf(provider)) }

        // 模拟崩溃点：完整迁移后版本标记未持久化
        assertEquals(1, migrator.migrateIfNeeded())
        backend.remove(SecretStore.MIGRATION_VERSION_KEY)
        assertEquals(0, store.migrationVersion())
        val persistedBefore = providersJson()!!

        val version = migrator.migrateIfNeeded()
        assertEquals(1, version)
        assertEquals("DataStore must not be rewritten on rerun", persistedBefore, providersJson()!!)
        val refs = redactor.readRefs(dataStore.data.first())
        assertEquals("sk-interrupt-2-88", store.read(refs.values.single().descriptor()))
    }

    // ---------------- 失败不删旧值 ----------------

    @Test
    fun `store write failure keeps legacy plaintext and does not mark migrated`() = runBlocking {
        val provider: ProviderSetting = ProviderSetting.OpenAI(apiKey = "sk-failure-keep-99")
        dataStore.edit { it[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(listOf(provider)) }
        val failingStore = SecretStore(
            backend = inMemoryBackend(),
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = error("keystore broken")
                override fun decrypt(stored: String): String? = null
            },
        )
        val failingMigrator = SettingsSecretMigrator(dataStore, failingStore, SecretRedactor(failingStore))

        val version = failingMigrator.migrateIfNeeded()
        assertEquals("migration must not be marked complete on failure", 0, version)
        assertTrue("legacy plaintext must be kept on failure", providersJson()!!.contains("sk-failure-keep-99"))
        assertNull("no secret may be written on failure", failingStore.read(SecretDescriptor("provider", provider.id.toString(), "apiKey")))
    }

    // ---------------- orphan 回收 ----------------

    @Test
    fun `deleteOrphans removes only unreferenced secrets after settings change`() = runBlocking {
        val provider1: ProviderSetting = ProviderSetting.OpenAI(id = kotlin.uuid.Uuid.random(), apiKey = "sk-orphan-a-11")
        val provider2: ProviderSetting = ProviderSetting.OpenAI(id = kotlin.uuid.Uuid.random(), apiKey = "sk-orphan-b-22")
        dataStore.edit {
            it[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(listOf(provider1, provider2))
        }
        assertEquals(1, migrator.migrateIfNeeded())

        // 模拟用户删除 provider1：DataStore 与 refs 同步移除
        val refsBefore = redactor.readRefs(dataStore.data.first())
        val removed = refsBefore.keys.first { it.contains(provider1.id.toString()) }
        dataStore.edit { p ->
            p[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(listOf(provider2))
            redactor.writeRefs(p, refsBefore.filterKeys { it != removed })
        }
        val active = redactor.readRefs(dataStore.data.first()).values.map { it.descriptor() }.toSet()
        redactor.deleteOrphans(active)

        assertNull(store.read(SecretDescriptor.fromKey(removed)!!))
        val remaining = refsBefore.values.first { it.ownerId == provider2.id.toString() }
        assertEquals("sk-orphan-b-22", store.read(remaining.descriptor()))
        assertTrue("still-referenced secret must survive the sweep", store.listOrphans(emptySet()).isNotEmpty())
    }
}
