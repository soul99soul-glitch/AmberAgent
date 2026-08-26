package app.amber.core.settings.prefs

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.mcp.McpCommonOptions
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.infra.AppScope
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.Settings
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretReference
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SettingsSecretMigrator
import app.amber.core.settings.secret.fakeSecretStore
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import android.os.Looper

/**
 * P1-01: 真实设置保存/读取链路 round-trip（Robolectric）。
 * - 保存边界（ProviderPrefs.writeTo / SettingsAggregator.writeSettings）redaction：
 *   DataStore 里只有掩码 + reference，无明文。
 * - 读取边界（readFrom）rehydration：业务/UI 拿到的是完整运行时值。
 * - orphan 回收：删除配置后只删确认无引用的 secret。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecretPrefsChainRoundTripTest {
    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var secretStore: SecretStore
    private lateinit var secretRedactor: SecretRedactor
    private lateinit var scope: AppScope

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "secret-prefs-chain-${System.nanoTime()}").apply { mkdirs() }
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) {
            File(tempDir, "settings.preferences_pb")
        }
        secretStore = fakeSecretStore()
        secretRedactor = SecretRedactor(secretStore)
        scope = AppScope()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * AppScope 的收集协程在 Dispatchers.Main 上调度；Robolectric PAUSED looper
     * 不会自动执行排队任务，等待 flow 时显式 idle 主 looper（Robolectric 官方提示）。
     */
    private suspend fun <T> StateFlow<T>.awaitUntil(
        timeoutMs: Long = 5_000,
        predicate: (T) -> Boolean,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val current = value
            if (predicate(current)) return current
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("awaitUntil timed out waiting for settings flow")
            }
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }

    // ---------------- SettingsAggregator（生产保存路径） ----------------

    private fun buildAggregator(): SettingsAggregator = SettingsAggregator(
        dataStore = dataStore,
        uiPrefs = UIPrefs(dataStore, scope),
        searchPrefs = SearchPrefs(dataStore, scope, secretStore),
        agentPrefs = AgentPrefs(dataStore, scope),
        providerPrefs = ProviderPrefs(dataStore, scope, secretStore),
        chatPrefs = ChatPrefs(dataStore, scope, secretStore),
        extensionPrefs = ExtensionPrefs(dataStore, scope, secretStore),
        scope = scope,
        secretRedactor = secretRedactor,
    )

    @Test
    fun `SettingsAggregator save keeps DataStore plaintext-free and rehydrates for business`() = runBlocking {
        val aggregator = buildAggregator()
        aggregator.settingsFlow.awaitUntil { !it.init }
        val provider = ProviderSetting.OpenAI(apiKey = "sk-agg-chain-44")
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(headers = listOf("Authorization" to "Bearer agg-mcp-token")),
        )

        aggregator.update(
            Settings(
                providers = listOf(provider),
                mcpServers = listOf(server),
            )
        )

        val providersPersisted = dataStore.data.first()[PreferencesKeys.PROVIDERS]!!
        assertFalse("DataStore providers must not contain plaintext", providersPersisted.contains("sk-agg-chain-44"))
        val mcpPersisted = dataStore.data.first()[PreferencesKeys.MCP_SERVERS]!!
        assertFalse("DataStore MCP must not contain plaintext", mcpPersisted.contains("agg-mcp-token"))

        // 业务使用边界：settingsFlow 读取时按需 rehydrate
        val rehydrated = aggregator.settingsFlow.awaitUntil {
            !it.init &&
                it.providers.firstOrNull()?.let { p -> (p as ProviderSetting.OpenAI).apiKey } == "sk-agg-chain-44"
        }
        assertEquals("sk-agg-chain-44", (rehydrated.providers.first() as ProviderSetting.OpenAI).apiKey)
        assertEquals(
            "Bearer agg-mcp-token",
            rehydrated.mcpServers.first().commonOptions.headers.single().second,
        )
    }

    @Test
    fun `clearing a provider key reclaims its orphan secret through the real save path`() = runBlocking {
        val aggregator = buildAggregator()
        aggregator.settingsFlow.awaitUntil { !it.init }
        val provider = ProviderSetting.OpenAI(apiKey = "sk-orphan-chain-55")

        aggregator.update(Settings(providers = listOf(provider)))
        val descriptor = SecretDescriptor("provider", provider.id.toString(), "apiKey")
        assertEquals("sk-orphan-chain-55", secretStore.read(descriptor))

        // 用户清空 key → 保存 → secret 无引用 → 回收
        aggregator.update(Settings(providers = listOf(provider.copy(apiKey = ""))))
        assertNull("orphan secret must be reclaimed after ref removal", secretStore.read(descriptor))
    }

    @Test
    fun `direct settings update preserves legacy refs and secrets while migration is pending`() = runBlocking {
        val oldId = kotlin.uuid.Uuid.random()
        val descriptor = SecretDescriptor("assistant", oldId.toString(), "customHeader:Authorization")
        val reference = SecretReference(
            scope = descriptor.scope,
            ownerId = descriptor.ownerId,
            fieldName = descriptor.fieldName,
            mask = SecretRedactor.MASK_STRING,
        )
        secretStore.update(descriptor, "Bearer legacy-secret")
        dataStore.edit {
            it[PreferencesKeys.AMBER_PROFILE] = "{broken"
            it[PreferencesKeys.LEGACY_ASSISTANTS] = "[]"
            secretRedactor.writeRefs(it, mapOf(descriptor.key to reference))
        }

        val migrator = SettingsSecretMigrator(dataStore, secretStore, secretRedactor)
        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val aggregator = buildAggregator()
        aggregator.settingsFlow.awaitUntil { !it.init }
        aggregator.update(Settings(systemPrompt = "updated while retrying"))

        val persisted = dataStore.data.first()
        assertEquals("{broken", persisted[PreferencesKeys.AMBER_PROFILE])
        assertEquals("[]", persisted[PreferencesKeys.LEGACY_ASSISTANTS])
        assertEquals(reference, secretRedactor.readRefs(persisted)[descriptor.key])
        assertEquals("Bearer legacy-secret", secretStore.read(descriptor))
        assertEquals("updated while retrying", persisted[PreferencesKeys.AMBER_SYSTEM_PROMPT])
    }
}
