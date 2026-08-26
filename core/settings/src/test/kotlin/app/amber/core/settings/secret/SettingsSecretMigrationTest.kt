package app.amber.core.settings.secret

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.core.ReasoningLevel
import app.amber.core.ai.mcp.McpCommonOptions
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.Settings
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.settings.LegacyAssistantProfile
import app.amber.core.settings.prefs.decodeSettingsDroppingLegacySearchService
import app.amber.search.SearchServiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
    fun `legacy assistant container migrates once to direct Amber keys`() = runBlocking {
        val selected = LegacyAssistantProfile(
            id = kotlin.uuid.Uuid.random(),
            systemPrompt = "Keep selected prompt",
            customHeaders = listOf(CustomHeader("Authorization", "Bearer selected-secret")),
        )
        val other = LegacyAssistantProfile(id = kotlin.uuid.Uuid.random(), systemPrompt = "Discarded prompt")
        dataStore.edit {
            it[PreferencesKeys.LEGACY_SELECTED_ASSISTANT] = selected.id.toString()
            it[PreferencesKeys.LEGACY_ASSISTANTS] = JsonInstant.encodeToString(listOf(other, selected))
            it[PreferencesKeys.LEGACY_ASSISTANT_TAGS] = "[]"
        }

        assertEquals(1, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        val prompt = persisted[PreferencesKeys.AMBER_SYSTEM_PROMPT]
        val headersJson = persisted[PreferencesKeys.AMBER_CUSTOM_HEADERS]!!
        assertEquals("Keep selected prompt", prompt)
        assertFalse(headersJson.contains("selected-secret"))
        assertTrue(headersJson.contains(SecretRedactor.MASK_STRING))
        assertNull(persisted[PreferencesKeys.AMBER_PROFILE])
        assertNull(persisted[PreferencesKeys.LEGACY_SELECTED_ASSISTANT])
        assertNull(persisted[PreferencesKeys.LEGACY_ASSISTANTS])
        assertNull(persisted[PreferencesKeys.LEGACY_ASSISTANT_TAGS])
        val ref = redactor.readRefs(persisted).values.single { it.scope == "assistant" }
        assertEquals(AMBER_AGENT_ID.toString(), ref.ownerId)
        assertEquals("Bearer selected-secret", store.read(ref.descriptor()))

        assertEquals(1, migrator.migrateIfNeeded())
        val rerun = dataStore.data.first()
        assertEquals(prompt, rerun[PreferencesKeys.AMBER_SYSTEM_PROMPT])
        assertNull(rerun[PreferencesKeys.LEGACY_ASSISTANTS])
    }

    @Test
    fun `malformed legacy assistant list is kept for a later recovery`() = runBlocking {
        dataStore.edit {
            it[PreferencesKeys.LEGACY_SELECTED_ASSISTANT] = kotlin.uuid.Uuid.random().toString()
            it[PreferencesKeys.LEGACY_ASSISTANTS] = "{broken"
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertEquals("{broken", persisted[PreferencesKeys.LEGACY_ASSISTANTS])
        assertTrue(persisted[PreferencesKeys.LEGACY_SELECTED_ASSISTANT] != null)
        assertNull(persisted[PreferencesKeys.AMBER_PROFILE])
    }

    @Test
    fun `malformed current Amber profile keeps legacy keys refs and direct settings`() = runBlocking {
        val oldId = kotlin.uuid.Uuid.random()
        val oldDescriptor = SecretDescriptor("assistant", oldId.toString(), "customHeader:Authorization")
        val oldReference = SecretReference(
            scope = oldDescriptor.scope,
            ownerId = oldDescriptor.ownerId,
            fieldName = oldDescriptor.fieldName,
            mask = SecretRedactor.MASK_STRING,
        )
        // A previously completed v1 must not turn a later legacy-profile failure
        // into a successful rescue gate.
        store.markMigrated(SettingsSecretMigrator.MIGRATION_VERSION)
        dataStore.edit {
            it[PreferencesKeys.AMBER_PROFILE] = "{broken"
            it[PreferencesKeys.LEGACY_SELECTED_ASSISTANT] = oldId.toString()
            it[PreferencesKeys.LEGACY_ASSISTANTS] = "[]"
            it[PreferencesKeys.LEGACY_ASSISTANT_TAGS] = "[]"
            it[PreferencesKeys.AMBER_SYSTEM_PROMPT] = "keep direct setting"
            redactor.writeRefs(it, mapOf(oldDescriptor.key to oldReference))
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertEquals("{broken", persisted[PreferencesKeys.AMBER_PROFILE])
        assertEquals(oldId.toString(), persisted[PreferencesKeys.LEGACY_SELECTED_ASSISTANT])
        assertEquals("[]", persisted[PreferencesKeys.LEGACY_ASSISTANTS])
        assertEquals("[]", persisted[PreferencesKeys.LEGACY_ASSISTANT_TAGS])
        assertEquals("keep direct setting", persisted[PreferencesKeys.AMBER_SYSTEM_PROMPT])
        assertEquals(oldReference, redactor.readRefs(persisted)[oldDescriptor.key])
    }

    @Test
    fun `persisted single profile is rekeyed to Amber without losing header secret`() = runBlocking {
        val oldId = kotlin.uuid.Uuid.random()
        val oldDescriptor = SecretDescriptor("assistant", oldId.toString(), "customHeader:Authorization")
        store.update(oldDescriptor, "Bearer preserved")
        val persistedProfile = LegacyAssistantProfile(
            id = oldId,
            customHeaders = listOf(CustomHeader("Authorization", SecretRedactor.MASK_STRING)),
        )
        dataStore.edit {
            it[PreferencesKeys.AMBER_PROFILE] = JsonInstant.encodeToString(persistedProfile)
            redactor.writeRefs(
                it,
                mapOf(
                    oldDescriptor.key to SecretReference(
                        scope = oldDescriptor.scope,
                        ownerId = oldDescriptor.ownerId,
                        fieldName = oldDescriptor.fieldName,
                        mask = SecretRedactor.MASK_STRING,
                    )
                ),
            )
        }

        migrator.migrateIfNeeded()

        val persisted = dataStore.data.first()
        val headers = JsonInstant.decodeFromString<List<CustomHeader>>(
            persisted[PreferencesKeys.AMBER_CUSTOM_HEADERS]!!,
        )
        val profileRef = redactor.readRefs(persisted).values.single { it.scope == "assistant" }
        assertEquals("${SecretRedactor.MASK_STRING}rved", headers.single().value)
        assertEquals(AMBER_AGENT_ID.toString(), profileRef.ownerId)
        assertEquals("Bearer preserved", store.read(profileRef.descriptor()))
        assertNull(store.read(oldDescriptor))
        assertNull(persisted[PreferencesKeys.AMBER_PROFILE])
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

    @Test
    fun `retired amber search is removed without moving its key to another service`() = runBlocking {
        val legacyId = kotlin.uuid.Uuid.random()
        val bing = SearchServiceOptions.BingLocalOptions()
        val legacySecret = SecretDescriptor("search", legacyId.toString(), "apiKey")
        store.update(legacySecret, "retired-key")
        dataStore.edit {
            it[PreferencesKeys.SEARCH_SERVICES] =
                """[{"type":"amber_agent","id":"$legacyId","apiKey":"${SecretRedactor.MASK_STRING}","depth":"deep"},{"type":"bing_local","id":"${bing.id}"}]"""
            it[PreferencesKeys.SEARCH_SELECTED] = 1
            it[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] =
                JsonInstant.encodeToString(listOf(legacyId, bing.id))
            redactor.writeRefs(
                it,
                mapOf(
                    legacySecret.key to SecretReference(
                        scope = "search",
                        ownerId = legacyId.toString(),
                        fieldName = "apiKey",
                        mask = SecretRedactor.MASK_STRING,
                    )
                ),
            )
        }

        assertEquals(1, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        val servicesJson = persisted[PreferencesKeys.SEARCH_SERVICES]!!
        assertFalse(servicesJson.contains("amber_agent"))
        assertFalse(servicesJson.contains("retired-key"))
        assertTrue(servicesJson.contains("bing_local"))
        assertEquals(0, persisted[PreferencesKeys.SEARCH_SELECTED])
        assertEquals(
            listOf(bing.id),
            JsonInstant.decodeFromString<List<kotlin.uuid.Uuid>>(
                persisted[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS]!!
            ),
        )
        assertFalse(redactor.readRefs(persisted).containsKey(legacySecret.key))
        assertNull(store.read(SecretDescriptor("search", legacyId.toString(), "apiKey")))
    }

    @Test
    fun `retired selected search disables web search instead of enabling another service`() = runBlocking {
        val legacyId = kotlin.uuid.Uuid.random()
        val bing = SearchServiceOptions.BingLocalOptions()
        dataStore.edit {
            it[PreferencesKeys.SEARCH_SERVICES] =
                """[{"type":"amber_agent","id":"$legacyId","apiKey":"retired-key"},{"type":"bing_local","id":"${bing.id}"}]"""
            it[PreferencesKeys.SEARCH_SELECTED] = 0
            it[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] = JsonInstant.encodeToString(listOf(legacyId))
            it[PreferencesKeys.ENABLE_WEB_SEARCH] = true
        }

        migrator.migrateIfNeeded()

        val persisted = dataStore.data.first()
        assertEquals(false, persisted[PreferencesKeys.ENABLE_WEB_SEARCH])
        assertEquals(
            emptyList<kotlin.uuid.Uuid>(),
            JsonInstant.decodeFromString<List<kotlin.uuid.Uuid>>(
                persisted[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS]!!
            ),
        )
    }

    @Test
    fun `settings backup decode drops retired search and requires reconfiguration`() {
        val legacyId = kotlin.uuid.Uuid.random()
        val bing = SearchServiceOptions.BingLocalOptions()
        val base = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString(
                Settings(
                    enableWebSearch = true,
                    searchServices = listOf(bing),
                    searchServiceSelected = 0,
                    searchEnabledServiceIds = listOf(bing.id),
                )
            )
        ).jsonObject
        val legacy = JsonObject(
            mapOf(
                "type" to JsonPrimitive("amber_agent"),
                "id" to JsonPrimitive(legacyId.toString()),
                "apiKey" to JsonPrimitive("retired-key"),
            )
        )
        val encodedBing = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString<SearchServiceOptions>(bing)
        )
        val backup = JsonObject(
            base.toMutableMap().apply {
                this["searchServices"] = JsonArray(listOf(legacy, encodedBing))
                this["searchServiceSelected"] = JsonPrimitive(0)
                this["searchEnabledServiceIds"] = JsonArray(listOf(JsonPrimitive(legacyId.toString())))
            }
        ).toString()

        val decoded = JsonInstant.decodeSettingsDroppingLegacySearchService(backup)

        val retained = decoded.searchServices.single() as SearchServiceOptions.BingLocalOptions
        assertEquals(bing.id, retained.id)
        assertEquals(0, decoded.searchServiceSelected)
        assertTrue(decoded.searchEnabledServiceIds.isEmpty())
        assertFalse(decoded.enableWebSearch)
        assertFalse(JsonInstant.encodeToString(decoded).contains("retired-key"))
    }

    @Test
    fun `legacy settings backup selects one Amber profile`() {
        val selected = LegacyAssistantProfile(
            id = kotlin.uuid.Uuid.random(),
            systemPrompt = "Preserve me",
            enabledSkills = setOf("legacy-skill"),
        )
        val other = LegacyAssistantProfile(id = kotlin.uuid.Uuid.random(), systemPrompt = "Do not select")
        val base = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings())).jsonObject
        val backup = JsonObject(
            base.toMutableMap().apply {
                remove("amberProfile")
                this["assistantId"] = JsonPrimitive(selected.id.toString())
                this["assistants"] = JsonInstant.parseToJsonElement(
                    JsonInstant.encodeToString(listOf(other, selected))
                )
                this["assistantTags"] = JsonArray(emptyList())
            }
        ).toString()

        val decoded = JsonInstant.decodeSettingsDroppingLegacySearchService(backup)

        assertEquals("Preserve me", decoded.systemPrompt)
        assertTrue("legacy-skill" in decoded.enabledSkills)
        val newJson = JsonInstant.encodeToString(decoded)
        assertFalse(newJson.contains("\"assistants\""))
        assertFalse(newJson.contains("\"assistantId\""))
    }

    @Test
    fun `malformed legacy assistants container rejects the whole backup decode`() {
        val base = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings())).jsonObject
        val backup = JsonObject(
            base.toMutableMap().apply {
                this["assistants"] = JsonPrimitive("{broken")
            }
        ).toString()

        val failure = runCatching {
            JsonInstant.decodeSettingsDroppingLegacySearchService(backup)
        }.exceptionOrNull()
        assertTrue("malformed assistants must reject the backup", failure != null)
    }

    @Test
    fun `malformed nested Amber profile rejects the whole backup decode`() {
        val base = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings())).jsonObject
        val backup = JsonObject(
            base.toMutableMap().apply {
                this["amberProfile"] = JsonPrimitive("{broken")
            }
        ).toString()

        val failure = runCatching {
            JsonInstant.decodeSettingsDroppingLegacySearchService(backup)
        }.exceptionOrNull()
        assertTrue("malformed nested amberProfile must reject the backup", failure != null)
    }

    @Test
    fun `legacy backup and datastore migration preserve reasoning memory and image model`() = runBlocking {
        val imageModelId = kotlin.uuid.Uuid.random()
        val rememberedModelId = kotlin.uuid.Uuid.random()
        val legacyProfile = LegacyAssistantProfile(
            rememberedReasoningLevelsByModelId = mapOf(
                rememberedModelId.toString() to ReasoningLevel.HIGH,
            ),
            legacyImageGenerationModelId = imageModelId,
        )
        val provider: ProviderSetting = ProviderSetting.OpenAI(
            models = listOf(Model(id = imageModelId, type = ModelType.IMAGE)),
        )
        dataStore.edit {
            it[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(listOf(provider))
            it[PreferencesKeys.AMBER_PROFILE] = JsonInstant.encodeToString(legacyProfile)
        }

        assertEquals(1, migrator.migrateIfNeeded())
        val persisted = dataStore.data.first()
        assertEquals(
            mapOf(rememberedModelId.toString() to ReasoningLevel.HIGH),
            JsonInstant.decodeFromString<Map<String, ReasoningLevel>>(
                persisted[PreferencesKeys.AMBER_REMEMBERED_REASONING_LEVELS]!!,
            ),
        )
        assertEquals(imageModelId.toString(), persisted[PreferencesKeys.IMAGE_GENERATION_MODEL])

        val base = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings())).jsonObject
        val backup = JsonObject(
            base.toMutableMap().apply {
                this["amberProfile"] = JsonInstant.parseToJsonElement(
                    JsonInstant.encodeToString(legacyProfile)
                )
            }
        ).toString()
        val decoded = JsonInstant.decodeSettingsDroppingLegacySearchService(backup)
        assertEquals(
            mapOf(rememberedModelId.toString() to ReasoningLevel.HIGH),
            decoded.rememberedReasoningLevelsByModelId,
        )
        assertEquals(imageModelId, decoded.imageGenerationModelId)
    }

    @Test
    fun `malformed legacy secret refs keep profile refs and stored secret`() = runBlocking {
        val oldId = kotlin.uuid.Uuid.random()
        val oldDescriptor = SecretDescriptor("assistant", oldId.toString(), "customHeader:Authorization")
        store.update(oldDescriptor, "Bearer preserved")
        val profile = LegacyAssistantProfile(
            id = oldId,
            customHeaders = listOf(CustomHeader("Authorization", SecretRedactor.MASK_STRING)),
        )
        dataStore.edit {
            it[PreferencesKeys.AMBER_PROFILE] = JsonInstant.encodeToString(profile)
            it[PreferencesKeys.SECRET_REFS] = "{broken"
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertEquals(JsonInstant.encodeToString(profile), persisted[PreferencesKeys.AMBER_PROFILE])
        assertEquals("{broken", persisted[PreferencesKeys.SECRET_REFS])
        assertTrue(oldDescriptor.key in backend.keys())
    }

    @Test
    fun `malformed refs without legacy profile fail migration without sweeping`() = runBlocking {
        val descriptor = SecretDescriptor("provider", kotlin.uuid.Uuid.random().toString(), "apiKey")
        store.update(descriptor, "still-referenced")
        dataStore.edit {
            it[PreferencesKeys.SECRET_REFS] = "{broken"
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertEquals("{broken", persisted[PreferencesKeys.SECRET_REFS])
        assertEquals("still-referenced", store.read(descriptor))
        assertEquals(0, store.migrationVersion())
    }

    @Test
    fun `unreadable provider settings fail migration without replacing the raw value`() = runBlocking {
        val rawProviders = """[{"type":"removed_provider","id":"${kotlin.uuid.Uuid.random()}"}]"""
        dataStore.edit {
            it[PreferencesKeys.PROVIDERS] = rawProviders
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        assertEquals(rawProviders, dataStore.data.first()[PreferencesKeys.PROVIDERS])
        assertEquals(0, store.migrationVersion())
    }

    @Test
    fun `malformed refs block retired search cleanup without deleting its secret`() = runBlocking {
        val legacyId = kotlin.uuid.Uuid.random()
        val legacySecret = SecretDescriptor("search", legacyId.toString(), "apiKey")
        val rawSearch =
            """[{"type":"amber_agent","id":"$legacyId","apiKey":"${SecretRedactor.MASK_STRING}"}]"""
        store.update(legacySecret, "retired-but-preserved")
        dataStore.edit {
            it[PreferencesKeys.SEARCH_SERVICES] = rawSearch
            it[PreferencesKeys.SECRET_REFS] = "{broken"
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertEquals(rawSearch, persisted[PreferencesKeys.SEARCH_SERVICES])
        assertEquals("{broken", persisted[PreferencesKeys.SECRET_REFS])
        assertEquals("retired-but-preserved", store.read(legacySecret))
        assertEquals(0, store.migrationVersion())
    }

    @Test
    fun `unreadable legacy secret ref keeps profile refs and stored entry`() = runBlocking {
        val oldId = kotlin.uuid.Uuid.random()
        val oldDescriptor = SecretDescriptor("assistant", oldId.toString(), "customHeader:Authorization")
        val oldReference = SecretReference(
            scope = oldDescriptor.scope,
            ownerId = oldDescriptor.ownerId,
            fieldName = oldDescriptor.fieldName,
            mask = SecretRedactor.MASK_STRING,
        )
        val brokenStore = SecretStore(
            backend = backend,
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = null
            },
        )
        brokenStore.update(oldDescriptor, "Bearer unreadable")
        dataStore.edit {
            it[PreferencesKeys.AMBER_PROFILE] = JsonInstant.encodeToString(
                LegacyAssistantProfile(
                    id = oldId,
                    customHeaders = listOf(CustomHeader("Authorization", SecretRedactor.MASK_STRING)),
                )
            )
            redactor.writeRefs(it, mapOf(oldDescriptor.key to oldReference))
        }

        val brokenMigrator = SettingsSecretMigrator(dataStore, brokenStore, SecretRedactor(brokenStore))
        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, brokenMigrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertTrue(persisted[PreferencesKeys.AMBER_PROFILE] != null)
        assertEquals(oldReference, redactor.readRefs(persisted)[oldDescriptor.key])
        assertTrue(oldDescriptor.key in backend.keys())
    }

    @Test
    fun `masked legacy header without a matching ref keeps legacy profile`() = runBlocking {
        val profile = LegacyAssistantProfile(
            id = kotlin.uuid.Uuid.random(),
            customHeaders = listOf(CustomHeader("Authorization", SecretRedactor.MASK_STRING)),
        )
        dataStore.edit {
            it[PreferencesKeys.AMBER_PROFILE] = JsonInstant.encodeToString(profile)
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertEquals(JsonInstant.encodeToString(profile), persisted[PreferencesKeys.AMBER_PROFILE])
        assertNull(persisted[PreferencesKeys.AMBER_CUSTOM_HEADERS])
    }

    @Test
    fun `masked legacy header with a different owner ref keeps legacy profile`() = runBlocking {
        val profileId = kotlin.uuid.Uuid.random()
        val wrongId = kotlin.uuid.Uuid.random()
        val wrongDescriptor = SecretDescriptor("assistant", wrongId.toString(), "customHeader:Authorization")
        val wrongReference = SecretReference(
            scope = wrongDescriptor.scope,
            ownerId = wrongDescriptor.ownerId,
            fieldName = wrongDescriptor.fieldName,
            mask = SecretRedactor.MASK_STRING,
        )
        store.update(wrongDescriptor, "Bearer wrong-owner")
        val profile = LegacyAssistantProfile(
            id = profileId,
            customHeaders = listOf(CustomHeader("Authorization", SecretRedactor.MASK_STRING)),
        )
        dataStore.edit {
            it[PreferencesKeys.AMBER_PROFILE] = JsonInstant.encodeToString(profile)
            redactor.writeRefs(it, mapOf(wrongDescriptor.key to wrongReference))
        }

        assertEquals(SettingsSecretMigrator.MIGRATION_FAILED, migrator.migrateIfNeeded())

        val persisted = dataStore.data.first()
        assertEquals(JsonInstant.encodeToString(profile), persisted[PreferencesKeys.AMBER_PROFILE])
        assertEquals(wrongReference, redactor.readRefs(persisted)[wrongDescriptor.key])
        assertEquals("Bearer wrong-owner", store.read(wrongDescriptor))
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
        assertEquals("migration failure must stay explicit and rerunnable", SettingsSecretMigrator.MIGRATION_FAILED, version)
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
