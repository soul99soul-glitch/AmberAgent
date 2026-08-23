package app.amber.core.ai.tools

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.infra.AppScope
import app.amber.core.model.MainAgentToolProfile
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.AssistantPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.agent.utils.JsonInstant
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.PermissionDecisionAction
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentValidator
import app.amber.feature.tools.Capability
import app.amber.feature.tools.ToolEffectClass
import app.amber.feature.tools.ToolProfileFilter
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.ToolRisk
import app.amber.feature.tools.ToolSearchIndex
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
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
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/**
 * P0–P2 agent provider-config tools contract tests（对齐 iOS §6 / §10-11）。
 *
 * 覆盖：
 *  - status：has_api_key 只读引用/掩码状态、悬空 chat 模型 issue、result 永不含 key 明文
 *  - apply：批准后经 SettingsAggregator 落 SecretStore；拒绝不写；非法 scheme 不部分提交；
 *           placeholder 拒绝；空串清除走审批
 *  - refresh_models：merge 保留已有模型；401 明确 key 无效且不清 models（fake fetcher）
 *  - set_model_slot：唯一匹配写入；多匹配返回 candidates；类型不符拒绝
 *  - 工具集隔离：SubAgent allowlist（built-in + dynamic default）不含四工具；
 *    注册元数据（effect class / risk / capability / 审批）固定
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderConfigToolsTest {

    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var secretStore: SecretStore
    private lateinit var secretBackend: SecretStoreBackend
    private lateinit var providerManager: ProviderManager
    private val mainDispatcher = UnconfinedTestDispatcher()

    private val openAiProvider = ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-0000-4000-8000-000000000001"),
        name = "TestOpenAI",
        enabled = true,
        models = listOf(
            Model(id = Uuid.parse("1eeea727-0000-4000-8000-000000000011"), modelId = "gpt-4o", displayName = "GPT-4o"),
            Model(id = Uuid.parse("1eeea727-0000-4000-8000-000000000012"), modelId = "gpt-image-2", displayName = "gpt-image-2", type = ModelType.IMAGE),
        ),
        apiKey = "",
        baseUrl = "https://api.testopenai.example.com/v1",
        brand = OpenAIBrand.GENERIC,
    )

    private val googleOAuthProvider = ProviderSetting.Google(
        id = Uuid.parse("1eeea727-0000-4000-8000-000000000021"),
        name = "TestGeminiOAuth",
        enabled = true,
        authMode = GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH,
        models = listOf(
            Model(
                id = Uuid.parse("1eeea727-0000-4000-8000-000000000022"),
                modelId = "gemini-3-pro-preview",
                displayName = "Gemini 3 Pro Preview",
            ),
        ),
    )

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "provider-config-tools-${System.nanoTime()}").apply { mkdirs() }
        val backend = object : SecretStoreBackend {
            private val map = mutableMapOf<String, String>()
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }

            override fun remove(key: String) {
                map.remove(key)
            }

            override fun keys(): Set<String> = map.keys.toSet()
        }
        secretBackend = backend
        secretStore = SecretStore(
            backend = backend,
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
        val appScope = AppScope()
        val dataStore = PreferenceDataStoreFactory.create {
            File(testRoot, "settings.preferences_pb")
        }
        settingsStore = SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope, secretStore),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope, secretStore),
            chatPrefs = ChatPrefs(dataStore, appScope),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secretStore),
            assistantPrefs = AssistantPrefs(dataStore, appScope, secretStore),
            scope = appScope,
            secretRedactor = SecretRedactor(secretStore),
        )
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        providerManager = ProviderManager(OkHttpClient(), context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seed(settings: app.amber.core.settings.Settings) {
        settingsStore.update {
            it.copy(
                providers = settings.providers,
                chatModelId = settings.chatModelId,
                titleModelId = settings.titleModelId,
                ocrModelId = settings.ocrModelId,
                compressModelId = settings.compressModelId,
                suggestionModelId = settings.suggestionModelId,
                imageGenerationModelId = settings.imageGenerationModelId,
            )
        }
        // 等 DataStore 回读流把写后值刷新到 settingsFlow.value（eager 写 + 回读均一致）
        withTimeout(5_000) {
            settingsStore.settingsFlow.first { flow ->
                flow.providers.map { p -> p.id } == settings.providers.map { p -> p.id } &&
                    flow.chatModelId == settings.chatModelId &&
                    flow.imageGenerationModelId == settings.imageGenerationModelId
            }
        }
    }

    private fun tools(modelFetcher: ProviderModelFetcher = ProviderModelFetcher { emptyList() }): List<Tool> =
        createProviderConfigTools(settingsStore, secretStore, providerManager, modelFetcher)

    private fun tool(name: String, modelFetcher: ProviderModelFetcher = ProviderModelFetcher { emptyList() }): Tool =
        tools(modelFetcher).first { it.name == name }

    private suspend fun runTool(tool: Tool, input: String): JsonObject {
        val parts = tool.execute(JsonInstant.parseToJsonElement(input))
        val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        return JsonInstant.parseToJsonElement(text).jsonObject
    }

    private fun apiKeyDescriptor(providerId: Uuid) = SecretDescriptor("provider", providerId.toString(), "apiKey")

    // ------------------------------------------------------------------
    // provider_config_status
    // ------------------------------------------------------------------

    @Test
    fun `status reports has_api_key from secret ref without leaking key value`() = runBlocking {
        val key = "sk-real-secret-9876543210"
        seed(
            app.amber.core.settings.Settings.dummy().copy(
                init = false,
                providers = listOf(openAiProvider.copy(apiKey = key)),
            )
        )
        assertTrue(secretStore.has(apiKeyDescriptor(openAiProvider.id)))
        val result = runTool(tool(TOOL_PROVIDER_CONFIG_STATUS), """{}""")
        val providerJson = result["providers"]!!.jsonArray.first()
        assertEquals(true, providerJson.jsonObject["has_api_key"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        assertEquals("api.testopenai.example.com", providerJson.jsonObject["base_url_host"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, providerJson.jsonObject["chat_model_count"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(1, providerJson.jsonObject["image_model_count"]?.jsonPrimitive?.contentOrNull?.toInt())
        // iOS §6.2 契约：brand 与 auth_mode 必须输出（brand 仅 OpenAI-compatible 有概念）
        assertEquals("generic", providerJson.jsonObject["brand"]?.jsonPrimitive?.contentOrNull)
        assertEquals("api_key", providerJson.jsonObject["auth_mode"]?.jsonPrimitive?.contentOrNull)
        // 红线：result 永不含 key 明文 / apiKey 字段值
        val raw = result.toString()
        assertFalse(raw.contains(key))
        assertFalse(raw.contains("sk-real-secret"))
    }

    @Test
    fun `status reports no key and dangling chat model issues`() = runBlocking {
        val danglingChatId = Uuid.random()
        seed(
            app.amber.core.settings.Settings.dummy().copy(
                init = false,
                providers = listOf(openAiProvider.copy(apiKey = "")),
                chatModelId = danglingChatId,
            )
        )
        val result = runTool(tool(TOOL_PROVIDER_CONFIG_STATUS), """{"provider_name_contains":"TestOpenAI"}""")
        val providerJson = result["providers"]!!.jsonArray.first().jsonObject
        assertEquals(false, providerJson["has_api_key"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        val issues = result["issues"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() }
        assertTrue(issues.any { it.contains("has no API key") })
        assertTrue(issues.any { it.contains("chat model id does not resolve to any configured CHAT model") })
        val chatSlot = result["slots"]!!.jsonObject["chat"]!!.jsonObject
        assertEquals(danglingChatId.toString(), chatSlot["model_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, chatSlot["resolved"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
    }

    @Test
    fun `status has_api_key false when stored ciphertext cannot be decrypted`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val descriptor = apiKeyDescriptor(openAiProvider.id)
        // 模拟 Keystore 失效：密文条目存在但解密失败（密文损坏 / 密钥丢失）
        secretBackend.put(descriptor.key, "corrupted-ciphertext")
        assertTrue(secretStore.has(descriptor))
        val brokenStore = SecretStore(
            backend = secretBackend,
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = null
            },
        )
        val result = runTool(
            createProviderConfigTools(settingsStore, brokenStore, providerManager, ProviderModelFetcher { emptyList() })
                .first { it.name == TOOL_PROVIDER_CONFIG_STATUS },
            """{}""",
        )
        val providerJson = result["providers"]!!.jsonArray.first().jsonObject
        assertEquals(false, providerJson["has_api_key"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        // "has no API key" issue 随之正确出现
        val issues = result["issues"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() }
        assertTrue(issues.any { it.contains("has no API key") })
    }

    @Test
    fun `status does not treat selected Gemini OAuth mode as a usable session`() = runBlocking {
        seed(
            app.amber.core.settings.Settings.dummy().copy(
                init = false,
                providers = listOf(googleOAuthProvider),
            )
        )
        val result = runTool(tool(TOOL_PROVIDER_CONFIG_STATUS), "{\"provider_name_contains\":\"TestGeminiOAuth\"}")
        val providerJson = result["providers"]!!.jsonArray.first().jsonObject
        assertEquals(false, providerJson["has_api_key"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        assertEquals(false, providerJson["auth_usable"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        assertEquals("not_signed_in", providerJson["auth_status"]?.jsonPrimitive?.contentOrNull)
        val issues = result["issues"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() }
        assertTrue(issues.any { it.contains("OAuth auth is not_signed_in") })
    }

    @Test
    fun `refresh Gemini OAuth refuses before model catalog fetch when session is absent`() = runBlocking {
        seed(
            app.amber.core.settings.Settings.dummy().copy(
                init = false,
                providers = listOf(googleOAuthProvider),
            )
        )
        val refresh = createProviderConfigTools(settingsStore, secretStore, providerManager)
            .first { it.name == TOOL_PROVIDER_REFRESH_MODELS }
        val result = runTool(
            refresh,
            "{\"provider_name\":\"TestGeminiOAuth\",\"mode\":\"replace_chat\"}",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            result.toString(),
            false,
            result["models_modified"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
        )
        assertTrue(result["error"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("尚未登录"))
        assertEquals(
            listOf("gemini-3-pro-preview"),
            settingsStore.settingsFlow.value.providers.single().models.map { it.modelId },
        )
    }

    @Test
    fun `status result json never contains apiKey field name`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val result = runTool(tool(TOOL_PROVIDER_CONFIG_STATUS), """{"include_models":true}""")
        val raw = result.toString()
        assertFalse(raw.contains("apiKey"))
        assertFalse(raw.contains("sk-"))
        assertFalse(raw.contains("Authorization"))
    }

    @Test
    fun `status reports brand only for openai-compatible and auth_mode for every provider`() = runBlocking {
        val deepseek = openAiProvider.copy(
            id = Uuid.parse("1eeea727-0000-4000-8000-000000000002"),
            name = "TestDeepSeek",
            brand = OpenAIBrand.DEEPSEEK,
        )
        val google = ProviderSetting.Google(
            id = Uuid.parse("1eeea727-0000-4000-8000-000000000003"),
            name = "TestGoogle",
            enabled = true,
            apiKey = "",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        )
        val claude = ProviderSetting.Claude(
            id = Uuid.parse("1eeea727-0000-4000-8000-000000000004"),
            name = "TestClaude",
            enabled = true,
            apiKey = "",
            baseUrl = "https://api.anthropic.com/v1",
        )
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(deepseek, google, claude)))
        val result = runTool(tool(TOOL_PROVIDER_CONFIG_STATUS), """{}""")
        val providers = result["providers"]!!.jsonArray.map { it.jsonObject }
        val deepseekJson = providers.first { it["name"]?.jsonPrimitive?.contentOrNull == "TestDeepSeek" }
        assertEquals("deepseek", deepseekJson["brand"]?.jsonPrimitive?.contentOrNull)
        assertEquals("api_key", deepseekJson["auth_mode"]?.jsonPrimitive?.contentOrNull)
        val googleJson = providers.first { it["name"]?.jsonPrimitive?.contentOrNull == "TestGoogle" }
        assertEquals(JsonNull, googleJson["brand"])
        assertEquals("api_key", googleJson["auth_mode"]?.jsonPrimitive?.contentOrNull)
        val claudeJson = providers.first { it["name"]?.jsonPrimitive?.contentOrNull == "TestClaude" }
        assertEquals(JsonNull, claudeJson["brand"])
        assertEquals("api_key", claudeJson["auth_mode"]?.jsonPrimitive?.contentOrNull)
    }

    // ------------------------------------------------------------------
    // provider_config_apply
    // ------------------------------------------------------------------

    @Test
    fun `apply writes key through SettingsAggregator into SecretStore`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val key = "sk-valid-key-abcdef123456"
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_id":"${openAiProvider.id}","api_key":"$key","enabled":true}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("updated", result["api_key_status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, result["has_api_key"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        assertTrue(result["changed_fields"]!!.jsonArray.any { it.jsonPrimitive.contentOrNull == "api_key" })
        // 写路径 = SettingsAggregator.update：明文进 SecretStore，DataStore 只留掩码 + reference
        assertTrue(secretStore.has(apiKeyDescriptor(openAiProvider.id)))
        val rehydrated = settingsStore.settingsFlow.value.providers.first().apiKeyValueForTest()
        assertEquals(key, rehydrated)
        // result 不含明文
        assertFalse(result.toString().contains(key))
    }

    @Test
    fun `apply denied by user writes nothing`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val key = "sk-valid-key-abcdef123456"
        val dispatcher = AgentToolDispatcher(
            json = Json { ignoreUnknownKeys = true },
            permissionDecisionResolver = PermissionDecisionResolver(),
        )
        val call = UIMessagePart.Tool(
            toolCallId = "call_apply_1",
            toolName = TOOL_PROVIDER_CONFIG_APPLY,
            input = """{"provider_id":"${openAiProvider.id}","api_key":"$key"}""",
            approvalState = ToolApprovalState.Denied("user said no"),
        )
        dispatcher.execute(
            tool = call,
            toolDef = tool(TOOL_PROVIDER_CONFIG_APPLY),
            autoApproveTools = false,
        )!!
        assertFalse(secretStore.has(apiKeyDescriptor(openAiProvider.id)))
        val provider = settingsStore.settingsFlow.value.providers.first()
        assertEquals("", provider.apiKeyValueForTest())
    }

    @Test
    fun `apply rejects non-https base_url without partial commit`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val key = "sk-valid-key-abcdef123456"
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_id":"${openAiProvider.id}","base_url":"http://evil.example.com/v1","api_key":"$key"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["error"]!!.jsonPrimitive.contentOrNull.orEmpty().contains("https"))
        // 不部分提交：base_url 与 api_key 都未写入
        val provider = settingsStore.settingsFlow.value.providers.first() as ProviderSetting.OpenAI
        assertEquals(openAiProvider.baseUrl, provider.baseUrl)
        assertEquals("", provider.apiKey)
        assertFalse(secretStore.has(apiKeyDescriptor(openAiProvider.id)))
    }

    @Test
    fun `apply rejects base_url with embedded userinfo`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_id":"${openAiProvider.id}","base_url":"https://user:secret@evil.example.com/v1"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        val provider = settingsStore.settingsFlow.value.providers.first() as ProviderSetting.OpenAI
        assertEquals(openAiProvider.baseUrl, provider.baseUrl)
    }

    @Test
    fun `apply rejects placeholder api keys`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        listOf("sk-xxx", "sk-xxxx", "your_key", "your_api_key", "xxx", "sk-123").forEach { placeholder ->
            val result = runTool(
                tool(TOOL_PROVIDER_CONFIG_APPLY),
                """{"provider_id":"${openAiProvider.id}","api_key":"$placeholder"}""",
            )
            assertEquals("placeholder $placeholder must be rejected", "failed", result["status"]?.jsonPrimitive?.contentOrNull)
            assertFalse(secretStore.has(apiKeyDescriptor(openAiProvider.id)))
        }
    }

    @Test
    fun `apply empty api_key clears the stored key`() = runBlocking {
        seed(
            app.amber.core.settings.Settings.dummy().copy(
                init = false,
                providers = listOf(openAiProvider.copy(apiKey = "sk-valid-key-abcdef123456")),
            )
        )
        assertTrue(secretStore.has(apiKeyDescriptor(openAiProvider.id)))
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_id":"${openAiProvider.id}","api_key":""}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("cleared", result["api_key_status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, result["has_api_key"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        assertFalse(secretStore.has(apiKeyDescriptor(openAiProvider.id)))
        assertEquals("", settingsStore.settingsFlow.value.providers.first().apiKeyValueForTest())
    }

    @Test
    fun `apply renames provider and reports changed fields in iOS order`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_id":"${openAiProvider.id}","name":"Renamed","base_url":"https://new.example.com/v1","use_response_api":true,"enabled":false}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        val changed = result["changed_fields"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertEquals(listOf("enabled", "name", "base_url", "use_response_api"), changed)
        val provider = settingsStore.settingsFlow.value.providers.first() as ProviderSetting.OpenAI
        assertEquals("Renamed", provider.name)
        assertEquals("https://new.example.com/v1", provider.baseUrl)
        assertTrue(provider.useResponseApi)
        assertFalse(provider.enabled)
    }

    @Test
    fun `apply create_if_missing creates openai-compatible provider`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val key = "sk-new-provider-key-123456"
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_name":"BrandNew","base_url":"https://new.example.com/v1","api_key":"$key","create_if_missing":true}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        val created = settingsStore.settingsFlow.value.providers.first { it.name == "BrandNew" }
        assertTrue(created is ProviderSetting.OpenAI)
        assertEquals(OpenAIBrand.GENERIC, (created as ProviderSetting.OpenAI).brand)
        assertEquals("https://new.example.com/v1", created.baseUrl)
        assertEquals(key, created.apiKey)
        assertTrue(secretStore.has(apiKeyDescriptor(created.id)))
        assertFalse(result.toString().contains(key))
    }

    @Test
    fun `apply ambiguous name with create_if_missing returns candidates and writes nothing`() = runBlocking {
        val dupA = openAiProvider.copy(name = "Dup", id = Uuid.random())
        val dupB = openAiProvider.copy(name = "Dup", id = Uuid.random())
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(dupA, dupB)))
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_name":"Dup","create_if_missing":true,""" +
                """"base_url":"https://new.example.com/v1","api_key":"sk-new-key-123456"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["error"]!!.jsonPrimitive.contentOrNull.orEmpty().contains("ambiguous"))
        assertEquals(2, result["candidates"]!!.jsonArray.size)
        // 未写入：provider 集合不变，也没有新建 provider
        assertEquals(2, settingsStore.settingsFlow.value.providers.size)
        assertFalse(secretBackend.keys().any { it.contains("provider/") })
    }

    @Test
    fun `apply name resolution prefers case-insensitive exact match over create_if_missing`() = runBlocking {
        // 大小写不敏感精确匹配优先：provider_name="openai" 精确命中 "OpenAI"，
        // 即使 create_if_missing=true 也绝不落入创建分支（也不会新建 provider）。
        val target = openAiProvider.copy(name = "OpenAI", id = Uuid.random())
        val other = openAiProvider.copy(name = "OpenAI2", id = Uuid.random())
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(target, other)))
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_name":"openai","create_if_missing":true,""" +
                """"base_url":"https://new.example.com/v1","api_key":"sk-new-key-123456"}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(target.id.toString(), result["provider_id"]?.jsonPrimitive?.contentOrNull)
        // 更新落在既有 "OpenAI" 上：未新建 "openai"
        assertEquals(2, settingsStore.settingsFlow.value.providers.size)
        assertFalse(settingsStore.settingsFlow.value.providers.any { it.name == "openai" })
        val updated = settingsStore.settingsFlow.value.providers.first { it.id == target.id } as ProviderSetting.OpenAI
        assertEquals("https://new.example.com/v1", updated.baseUrl)
        assertTrue(secretStore.has(apiKeyDescriptor(target.id)))
    }

    @Test
    fun `apply falls back to a unique substring match when no exact match exists`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        // "openai" 与 "TestOpenAI" 非精确同名，但子串唯一命中 → 仍允许写入（保持可用性）
        val result = runTool(
            tool(TOOL_PROVIDER_CONFIG_APPLY),
            """{"provider_name":"openai","enabled":false}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(openAiProvider.id.toString(), result["provider_id"]?.jsonPrimitive?.contentOrNull)
        assertFalse(settingsStore.settingsFlow.value.providers.first().enabled)
    }

    // ------------------------------------------------------------------
    // provider_refresh_models
    // ------------------------------------------------------------------

    @Test
    fun `refresh merge keeps existing models and appends new ones`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val fetched = listOf(
            Model(modelId = "gpt-4o", displayName = "GPT-4o"),
            Model(modelId = "gpt-5", displayName = "GPT-5"),
            Model(modelId = "o3", displayName = "o3"),
        )
        val result = runTool(
            tool(TOOL_PROVIDER_REFRESH_MODELS, ProviderModelFetcher { fetched }),
            """{"provider_id":"${openAiProvider.id}","mode":"merge"}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, result["added"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(2, result["kept"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(4, result["total_models"]?.jsonPrimitive?.contentOrNull?.toInt())
        val provider = settingsStore.settingsFlow.value.providers.first() as ProviderSetting.OpenAI
        val ids = provider.models.map { it.modelId }.toSet()
        assertEquals(setOf("gpt-4o", "gpt-image-2", "gpt-5", "o3"), ids)
        // IMAGE 模型仍保留
        assertTrue(provider.models.any { it.type == ModelType.IMAGE })
    }

    @Test
    fun `refresh 401 reports invalid key and never clears existing models`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val failing = ProviderModelFetcher {
            throw IllegalStateException("Failed to get models: 401 Unauthorized")
        }
        val result = runTool(
            tool(TOOL_PROVIDER_REFRESH_MODELS, failing),
            """{"provider_id":"${openAiProvider.id}"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["error"]!!.jsonPrimitive.contentOrNull.orEmpty().contains("API key is invalid"))
        assertEquals(false, result["models_modified"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        val provider = settingsStore.settingsFlow.value.providers.first() as ProviderSetting.OpenAI
        assertEquals(openAiProvider.models.map { it.id }, provider.models.map { it.id })
    }

    @Test
    fun `refresh replace_chat replaces only chat models`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val fetched = listOf(Model(modelId = "gpt-5", displayName = "GPT-5"))
        val result = runTool(
            tool(TOOL_PROVIDER_REFRESH_MODELS, ProviderModelFetcher { fetched }),
            """{"provider_id":"${openAiProvider.id}","mode":"replace_chat"}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        val provider = settingsStore.settingsFlow.value.providers.first() as ProviderSetting.OpenAI
        assertEquals(listOf("gpt-5"), provider.models.filter { it.type == ModelType.CHAT }.map { it.modelId })
        assertTrue(provider.models.any { it.modelId == "gpt-image-2" && it.type == ModelType.IMAGE })
    }

    @Test
    fun `refresh reports provider-not-found when provider is deleted before the write`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        // 模拟并发删除：fetch 期间（resolve 之后、写事务之前）provider 被移除
        val deletingFetcher = ProviderModelFetcher {
            settingsStore.update { current ->
                current.copy(providers = current.providers.filterNot { it.id == openAiProvider.id })
            }
            listOf(Model(modelId = "gpt-5", displayName = "GPT-5"))
        }
        val result = runTool(
            tool(TOOL_PROVIDER_REFRESH_MODELS, deletingFetcher),
            """{"provider_id":"${openAiProvider.id}"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["error"]!!.jsonPrimitive.contentOrNull.orEmpty().contains("provider not found"))
        assertEquals(false, result["written"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
    }

    // ------------------------------------------------------------------
    // settings_set_model_slot
    // ------------------------------------------------------------------

    @Test
    fun `set_model_slot unique model_ref writes the slot`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val result = runTool(
            tool(TOOL_SETTINGS_SET_MODEL_SLOT),
            """{"slot":"chat","model_ref":"gpt-4o"}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("chat", result["slot"]?.jsonPrimitive?.contentOrNull)
        assertEquals("TestOpenAI", result["provider_name"]?.jsonPrimitive?.contentOrNull)
        val settings = settingsStore.settingsFlow.value
        assertEquals(openAiProvider.models.first().id, settings.chatModelId)
        assertEquals(openAiProvider.models.first().id.toString(), result["model_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `set_model_slot model_id writes the slot`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val modelId = openAiProvider.models.first().id
        val result = runTool(
            tool(TOOL_SETTINGS_SET_MODEL_SLOT),
            """{"slot":"title","model_id":"$modelId"}""",
        )
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(modelId, settingsStore.settingsFlow.value.titleModelId)
    }

    @Test
    fun `set_model_slot ambiguous model_ref returns candidates and writes nothing`() = runBlocking {
        val other = ProviderSetting.OpenAI(
            id = Uuid.parse("1eeea727-0000-4000-8000-000000000002"),
            name = "OtherOpenAI",
            enabled = true,
            models = listOf(Model(modelId = "gpt-4o", displayName = "GPT-4o (Other)")),
            apiKey = "",
            baseUrl = "https://api.other.example.com/v1",
            brand = OpenAIBrand.GENERIC,
        )
        seed(
            app.amber.core.settings.Settings.dummy().copy(
                init = false,
                providers = listOf(openAiProvider, other),
                chatModelId = Uuid.parse("1eeea727-0000-4000-8000-000000000099"),
            )
        )
        val result = runTool(
            tool(TOOL_SETTINGS_SET_MODEL_SLOT),
            """{"slot":"chat","model_ref":"gpt-4o"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["candidates"]!!.jsonArray.size >= 2)
        // 未写入
        assertFalse(
            settingsStore.settingsFlow.value.providers.flatMap { it.models }
                .any { it.id == settingsStore.settingsFlow.value.chatModelId && it.modelId == "gpt-4o" }
        )
    }

    @Test
    fun `set_model_slot rejects type mismatch`() = runBlocking {
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(openAiProvider)))
        val imageModel = openAiProvider.models.first { it.type == ModelType.IMAGE }
        val result = runTool(
            tool(TOOL_SETTINGS_SET_MODEL_SLOT),
            """{"slot":"chat","model_ref":"gpt-image-2"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["error"]!!.jsonPrimitive.contentOrNull.orEmpty().contains("CHAT model"))
        assertTrue(settingsStore.settingsFlow.value.chatModelId != imageModel.id)
    }

    @Test
    fun `set_model_slot ignores disabled providers for model_ref`() = runBlocking {
        val disabled = openAiProvider.copy(enabled = false)
        seed(app.amber.core.settings.Settings.dummy().copy(init = false, providers = listOf(disabled)))
        val result = runTool(
            tool(TOOL_SETTINGS_SET_MODEL_SLOT),
            """{"slot":"chat","model_ref":"gpt-4o"}""",
        )
        assertEquals("failed", result["status"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["error"]!!.jsonPrimitive.contentOrNull.orEmpty().contains("no enabled provider model matches"))
    }

    // ------------------------------------------------------------------
    // 工具集隔离 / 注册元数据 / tool_search 发现
    // ------------------------------------------------------------------

    @Test
    fun `subagent tool sets never include provider config tools`() {
        val providerToolNames = PROVIDER_CONFIG_TOOL_NAMES
        SubAgentDefinitions.builtIns.forEach { definition ->
            assertTrue(
                "${definition.id} allowlist must not include provider config tools",
                providerToolNames.none { it in definition.toolAllowlist },
            )
        }
        assertTrue(
            "dynamic subagent default tools must not include provider config tools",
            SubAgentValidator.defaultDynamicReadOnlyTools.none { it in providerToolNames },
        )
    }

    @Test
    fun `minimal tool profile filters out provider config write tools`() {
        // ChatService.createRunTools 现在把四把 provider 配置工具也送进
        // ToolProfileFilter：MINIMAL profile 下 effectiveRegistry 不得含
        // provider_config_apply 等写工具。
        val filtered = ToolProfileFilter.filter(tools(), MainAgentToolProfile.MINIMAL).tools
        assertTrue(filtered.none { it.name in PROVIDER_CONFIG_TOOL_NAMES })
        assertNull(ToolRegistry.from(filtered).metadataFor(TOOL_PROVIDER_CONFIG_APPLY))
    }

    @Test
    fun `registry metadata pins effect class risk and capability`() = runBlocking {
        val registry = ToolRegistry.from(tools())
        val apply = registry.metadataFor(TOOL_PROVIDER_CONFIG_APPLY)!!
        assertEquals(ToolEffectClass.NON_IDEMPOTENT_WRITE, apply.effectClass)
        assertEquals(ToolRisk.High, apply.risk)
        assertTrue(apply.mutates)
        assertTrue(apply.needsApproval)
        assertFalse(apply.autoApprovable)
        assertEquals(Capability.PROVIDER_CONFIG, apply.capability)

        val refresh = registry.metadataFor(TOOL_PROVIDER_REFRESH_MODELS)!!
        assertEquals(ToolEffectClass.IDEMPOTENT_WRITE, refresh.effectClass)
        assertEquals(ToolRisk.High, refresh.risk)
        assertTrue(refresh.mutates)
        assertTrue(refresh.needsApproval)
        assertEquals(Capability.PROVIDER_CONFIG, refresh.capability)

        val setSlot = registry.metadataFor(TOOL_SETTINGS_SET_MODEL_SLOT)!!
        assertEquals(ToolEffectClass.IDEMPOTENT_WRITE, setSlot.effectClass)
        assertEquals(ToolRisk.High, setSlot.risk)
        assertTrue(setSlot.needsApproval)
        assertEquals(Capability.PROVIDER_CONFIG, setSlot.capability)

        val status = registry.metadataFor(TOOL_PROVIDER_CONFIG_STATUS)!!
        assertEquals(ToolEffectClass.READ_ONLY, status.effectClass)
        assertEquals(ToolRisk.Normal, status.risk)
        assertFalse(status.mutates)
        assertFalse(status.needsApproval)
        assertTrue(status.autoApprovable)
        assertNull(status.capability)
    }

    @Test
    fun `apply resolves to ask approval even with plain auto-approve on`() = runBlocking {
        val resolver = PermissionDecisionResolver()
        val applyTool = tool(TOOL_PROVIDER_CONFIG_APPLY)
        val call = UIMessagePart.Tool(
            toolCallId = "call_apply_ask",
            toolName = TOOL_PROVIDER_CONFIG_APPLY,
            input = """{"provider_id":"${openAiProvider.id}","api_key":"sk-valid-key-abcdef123456"}""",
            approvalState = ToolApprovalState.Auto,
        )
        val decision = resolver.resolve(
            toolDef = applyTool,
            tool = call,
            autoApproveTools = true,
            autoApproveHighRiskTools = false,
        )
        assertEquals(PermissionDecisionAction.ASK, decision.action)
    }

    @Test
    fun `tool search discovers provider config tools by chinese aliases`() = runBlocking {
        val registry = ToolRegistry.from(tools())
        val index = ToolSearchIndex(registry)
        val payload = index.searchPayload("配置提供商", null, 10)
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertTrue(expanded.contains(TOOL_PROVIDER_CONFIG_STATUS))
        assertTrue(expanded.contains(TOOL_PROVIDER_CONFIG_APPLY))
        val keyPayload = index.searchPayload("API Key", null, 10)
        val keyExpanded = keyPayload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertTrue(keyExpanded.contains(TOOL_PROVIDER_CONFIG_APPLY))
        val slotPayload = index.searchPayload("默认模型", null, 10)
        assertTrue(slotPayload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }.contains(TOOL_SETTINGS_SET_MODEL_SLOT))
        val refreshPayload = index.searchPayload("刷新模型列表", null, 10)
        assertTrue(refreshPayload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }.contains(TOOL_PROVIDER_REFRESH_MODELS))
    }
}

/** 测试专用：读取 rehydrate 后的 apiKey（仅断言用，不进入任何 tool result）。 */
private fun ProviderSetting.apiKeyValueForTest(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}
