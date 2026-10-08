package app.amber.feature.miniapp

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.core.settings.MiniAppSetting
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.utils.JsonInstant
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.feature.miniapp.bridge.MiniAppBridge
import app.amber.feature.miniapp.bridge.MiniAppTheme
import app.amber.feature.workspace.WorkspaceManager
import app.amber.core.infra.AppScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.tools.Capability as ToolCapability
import app.amber.feature.tools.CapabilityPolicy
import app.amber.feature.ui.pages.miniapp.createRunnerSendGate
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P4 W10: real-bridge system capability regressions. Drives the production
 * `MiniAppBridge.postMessage` with a real in-memory Room repository and a
 * Robolectric WebView, capturing the JS payload `sendResponse` would evaluate
 * — the actual production dispatch path, not a mirrored helper.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [34], application = Application::class)
class MiniAppSystemCapabilityBridgeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: MiniAppRepository
    private lateinit var webView: WebView
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var providerCatalog: ProviderCatalog
    private lateinit var testRoot: java.io.File

    /** Scripted confirmation answers; captures dialog text for assertions. */
    private class ScriptedConfirmation(var answer: Boolean) : MiniAppUserConfirmation {
        val prompts = mutableListOf<String>()
        override suspend fun confirm(title: String, message: String): Boolean {
            prompts += "$title|$message"
            return answer
        }
    }

    private class RecordingHandler(
        override val supportedMethods: Set<String>,
    ) : MiniAppSystemCapabilityHandler {
        val dispatched = mutableListOf<String>()
        override suspend fun dispatch(method: String, params: JsonObject): kotlinx.serialization.json.JsonElement {
            dispatched += method
            return kotlinx.serialization.json.JsonPrimitive("native-ok")
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        testRoot = java.nio.file.Files.createTempDirectory("miniapp-bridge-test").toFile()
        val secretStore = SecretStore(
            backend = object : SecretStoreBackend {
                override fun get(key: String): String? = null
                override fun put(key: String, value: String) = Unit
                override fun remove(key: String) = Unit
                override fun keys(): Set<String> = emptySet()
            },
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
        val appScope = AppScope()
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(testRoot, "settings.preferences_pb")
        }
        settingsStore = SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope, secretStore),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope, secretStore),
            chatPrefs = ChatPrefs(dataStore, appScope, secretStore),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secretStore),
            scope = appScope,
            secretRedactor = SecretRedactor(secretStore),
        )
        val httpClient = okhttp3.OkHttpClient()
        providerCatalog = ProviderCatalog(
            openAIProvider = OpenAIProvider(httpClient, context),
            googleProvider = GoogleProvider(httpClient, context),
            claudeProvider = ClaudeProvider(httpClient, context),
        )
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MiniAppRepository(
            context = context,
            database = db,
            dao = db.miniAppDao(),
            grantDao = db.miniAppGrantDao(),
            versionDao = db.miniAppVersionDao(),
            auditLogDao = db.miniAppAuditLogDao(),
            sharedDataDao = db.miniAppSharedDataDao(),
            json = JsonInstant,
        )
        webView = WebView(context)
    }

    @After
    fun tearDown() {
        db.close()
        testRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun runnerGateUsesUpdatedHighRiskApprovalWithoutRecreation() = runBlocking {
        val flagsData = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(testRoot, "runner-flags.preferences_pb")
        }
        val permissionsData = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(testRoot, "runner-permissions.preferences_pb")
        }
        val flags = CapabilityFlags(flagsData)
        val policies = CapabilityPermissionStore(permissionsData)
        flags.setEnabled(Capability.CapabilityPermissions, true)
        policies.setPolicy(ToolCapability.MINIAPP_SEND, CapabilityPolicy.AUTO)
        withTimeout(5_000) { settingsStore.settingsFlow.filterNot { it.init }.first() }
        assertTrue(flags.isEnabled(Capability.CapabilityPermissions))
        assertEquals(CapabilityPolicy.AUTO, policies.policies()[ToolCapability.MINIAPP_SEND])
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(autoApproveHighRiskToolCalls = true)) }
        // Same factory as the real AndroidView bridge and same gate object for both requests.
        val gate = createRunnerSendGate(settingsStore, flags, policies)
        assertEquals(MiniAppSendDecision.AllowAuto, gate.decide())
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(autoApproveHighRiskToolCalls = false)) }
        assertEquals(MiniAppSendDecision.RequireConfirm, gate.decide())
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(autoApproveHighRiskToolCalls = true)) }
        assertEquals(MiniAppSendDecision.AllowAuto, gate.decide())
    }

    private fun appEntity(permissions: List<String>, version: Int = 1): MiniAppEntity = MiniAppEntity(
        id = "app-1",
        title = "系统能力验收",
        description = "desc",
        htmlContent = "<html><body>ok</body></html>",
        permissionsJson = Json.encodeToString(permissions),
        version = version,
        htmlHash = "hash-$version",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun bridgeOf(
        app: MiniAppEntity,
        handler: MiniAppSystemCapabilityHandler?,
        confirmation: MiniAppUserConfirmation,
        setting: MiniAppSetting = MiniAppSetting(),
        settingProvider: (() -> MiniAppSetting)? = null,
        httpClient: MiniAppHttpClient = MiniAppHttpClient(),
        sendMessage: suspend (String, List<app.amber.ai.ui.UIMessagePart>) -> Boolean = { _, _ -> true },
        sendDecision: MiniAppSendDecision = MiniAppSendDecision.AllowAuto,
    ): MiniAppBridge {
        val apps = mutableMapOf(app.id to app)
        return MiniAppBridge(
            context = context,
            webViewProvider = { webView },
            appId = app.id,
            sessionToken = "token-1",
            appProvider = { apps.getValue(app.id) },
            sandbox = MiniAppSandbox(
                appId = app.id,
                declaredPermissions = Json.decodeFromString<List<String>>(app.permissionsJson).toSet(),
                setting = setting,
                settingProvider = settingProvider,
                grantDecision = { permission -> runBlocking { repository.grantDecision(app.id, permission) } },
            ),
            repository = repository,
            storage = MiniAppStorage(context),
            httpClient = httpClient,
            searchBridge = MiniAppSearchBridge(settingsStore),
            aiBridge = MiniAppAiBridge(context, settingsStore, providerCatalog),
            confirmation = confirmation,
            systemBridge = MiniAppSystemBridge(context),
            toast = {},
            clipboardCopy = {},
            updateBoardSummary = {},
            launchApp = {},
            themeProvider = { MiniAppTheme(dark = false, background = "", foreground = "", primary = "") },
            conversationWriter = MiniAppConversationWriter(
                ConversationDraftStore(db.conversationDraftDao(), db.conversationDao()),
                sendMessage = sendMessage,
            ),
            workspaceWriter = MiniAppWorkspaceWriter(
                ArtifactRepository(
                    dao = db.artifactDao(),
                    workspaceManager = WorkspaceManager(context),
                    messageNodeDao = db.messageNodeDao(),
                    conversationDao = db.conversationDao(),
                ),
            ),
            sendGate = object : MiniAppSendGate {
                override suspend fun decide(): MiniAppSendDecision = sendDecision
            },
            systemCapabilityHandler = handler,
        )
    }

    /** Posts through the real postMessage path and awaits the JS response payload. */
    private fun MiniAppBridge.request(method: String, params: String = "{}"): MiniAppBridgeResponse {
        val requestId = nextRequestId++
        val raw = """
            {"id":$requestId,"token":"token-1","method":"$method","params":$params}
        """.trimIndent()
        val previousScript = shadowOf(webView).lastEvaluatedJavascript
        postMessage(raw)
        // sendResponse posts to the main looper; drive the paused Robolectric
        // main looper until this request's payload was evaluated. The bridge
        // scope serializes execution between suspension points.
        val mainShadow = shadowOf(android.os.Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        var script: String
        while (true) {
            mainShadow.idle()
            script = shadowOf(webView).lastEvaluatedJavascript ?: ""
            if (script != (previousScript ?: "") && script.contains("\"id\":$requestId")) break
            if (System.nanoTime() > deadline) {
                throw AssertionError("bridge response was not evaluated in time for $method (request $requestId)")
            }
            Thread.sleep(10)
        }
        val jsonStart = script.indexOf('(') + 1
        val jsonEnd = script.lastIndexOf(')')
        return Json { ignoreUnknownKeys = true }.decodeFromString(
            MiniAppBridgeResponse.serializer(),
            script.substring(jsonStart, jsonEnd),
        )
    }

    private var nextRequestId = 1

    @Test
    fun conversationImageIsSnapshottedThroughGuardedClientBeforeDraftOrSend() = runBlocking {
        val miniApp = appEntity(listOf("host.sendToConversation"))
        repository.upsert(miniApp)
        val id = "image-target"
        db.conversationDao().insert(app.amber.agent.data.db.entity.ConversationEntity(
            id, "assistant", "chat", "[]", 1, 1, "[]", false))
        val fetched = mutableListOf<String>()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val guard = MiniAppUrlGuard { host -> listOf(java.net.InetAddress.getByName(
            if (host == "private.test") "192.168.1.2" else "93.184.216.34")) }
        val client = okhttp3.OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            fetched += request.url.toString()
            val builder = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
            if (request.url.host == "redirect.test") {
                builder.code(302).message("Found").header("Location", "https://private.test/image.png")
                    .body(ByteArray(0).toResponseBody(null)).build()
            } else {
                builder.code(200).message("OK").header("Content-Type", "image/png")
                    .body(bytes.toResponseBody("image/png".toMediaType())).build()
            }
        }.build()
        var sent: List<app.amber.ai.ui.UIMessagePart>? = null
        val bridge = bridgeOf(miniApp, null, ScriptedConfirmation(true),
            setting = MiniAppSetting(enabled = true, hostWriteEnabled = true),
            httpClient = MiniAppHttpClient(guard, client), sendMessage = { _, parts -> sent = parts; true })
        fun params(url: String, mode: String) = """{"conversationId":"$id","text":"image","mode":"$mode","attachments":[{"kind":"image","url":"$url"}]}"""
        try {
            val expected = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
            val draftResponse = bridge.request("host.sendToConversation", params("HTTPS://public.test/image.png?signature=whole", "draft"))
            assertTrue("${draftResponse.errorCode}: ${draftResponse.error}", draftResponse.ok)
            val draft = ConversationDraftStore(db.conversationDraftDao(), db.conversationDao()).load(id)!!
            assertEquals(expected, (draft.attachments.single() as app.amber.ai.ui.UIMessagePart.Image).url)
            val sendResponse = bridge.request("host.sendToConversation", params("https://public.test/image.png", "send"))
            assertTrue("${sendResponse.errorCode}: ${sendResponse.error}", sendResponse.ok)
            assertEquals(expected, (sent!!.filterIsInstance<app.amber.ai.ui.UIMessagePart.Image>().single()).url)
            val beforeDenied = fetched.size
            assertFalse(bridge.request("host.sendToConversation", params("https://private.test/image.png", "draft")).ok)
            assertEquals(beforeDenied, fetched.size)
            assertFalse(bridge.request("host.sendToConversation", params("https://redirect.test/image.png", "send")).ok)
            assertEquals("https://redirect.test/image.png", fetched.last())
            assertFalse(fetched.any { it.contains("private.test") })
        } finally { bridge.close() }
    }

    @Test
    fun deniedSendDoesNotFetchAnAttachmentBeforeItsApproval() = runBlocking {
        val miniApp = appEntity(listOf("host.sendToConversation"))
        repository.upsert(miniApp)
        var fetches = 0
        val guard = MiniAppUrlGuard { listOf(java.net.InetAddress.getByName("93.184.216.34")) }
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            fetches++
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(byteArrayOf(1).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val bridge = bridgeOf(miniApp, null, ScriptedConfirmation(false),
            setting = MiniAppSetting(hostWriteEnabled = true), httpClient = MiniAppHttpClient(guard, client),
            sendDecision = MiniAppSendDecision.RequireConfirm)
        try {
            val result = bridge.request("host.sendToConversation", """{"conversationId":"target","mode":"send","text":"image","attachments":[{"kind":"image","url":"https://public.test/image.png"}]}""")
            assertFalse(result.ok)
            assertEquals("user_denied", result.errorCode)
            assertEquals(0, fetches)
        } finally { bridge.close() }
    }

    @Test
    fun appInfoReportsDurableAppGrantsAndHonestBridgeVersion() = runBlocking {
        val app = appEntity(listOf("haptics", "toast"))
        repository.upsert(app)
        repository.setGrant(app.id, "haptics", MiniAppGrantDecision.ALLOW)
        val bridge = bridgeOf(app, handler = RecordingHandler(setOf("haptics.impact")), confirmation = ScriptedConfirmation(true))

        val response = bridge.request("app.info")
        assertTrue(response.error, response.ok)
        val data = response.data!!.jsonObject
        assertEquals("android", data["platform"]!!.jsonPrimitive.content)
        assertEquals("0.2", data["bridgeVersion"]!!.jsonPrimitive.content)
        assertEquals("app-1", data["appId"]!!.jsonPrimitive.content)
        assertEquals(1, data["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("haptics", "toast"), data["permissions"]!!.jsonArray.map { it.jsonPrimitive.content })
        val grants = data["grants"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, grants.size)
        assertEquals("haptics", grants[0]["permission"]!!.jsonPrimitive.content)
        assertEquals("ALLOW", grants[0]["decision"]!!.jsonPrimitive.content)
        assertEquals(db.miniAppGrantDao().get(app.id, "haptics")!!.updatedAt,
            grants[0]["updatedAt"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun capabilitiesReportsHandlerIntersectionWithoutConfirmation() = runBlocking {
        val app = appEntity(listOf("haptics", "share"))
        repository.upsert(app)
        val confirmation = ScriptedConfirmation(true)
        val bridge = bridgeOf(
            app,
            handler = RecordingHandler(setOf("haptics.impact", "haptics.selection", "qrcode.generate")),
            confirmation = confirmation,
        )

        val response = bridge.request("app.capabilities")
        assertTrue(response.error, response.ok)
        val data = response.data!!.jsonObject
        // Discovery never triggers a permission dialog.
        assertTrue(confirmation.prompts.isEmpty())
        val methods = data["methods"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("haptics.impact" in methods)
        assertTrue("qrcode.generate" in methods)
        assertFalse("share" in methods) // handler does not support share
        assertFalse("speech.speak" in methods)
        assertTrue(data["systemCapabilitiesEnabled"]!!.jsonPrimitive.content.toBoolean())
        val permissions = data["permissions"]!!.jsonArray.map { it.jsonObject }
        val haptics = permissions.first { it["permission"]!!.jsonPrimitive.content == "haptics" }
        assertTrue(haptics["declared"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(haptics["enabled"]!!.jsonPrimitive.content.toBoolean())
        // No durable decision yet → JSON null, and still no dialog.
        assertTrue(haptics["decision"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun undecidedPermissionPromptsConfirmsThenPersistsAllow() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        val confirmation = ScriptedConfirmation(true)
        val handler = RecordingHandler(setOf("haptics.impact"))
        val bridge = bridgeOf(app, handler, confirmation)

        val response = bridge.request("haptics.impact")
        assertTrue(response.error, response.ok)
        assertEquals(1, confirmation.prompts.size)
        assertTrue(confirmation.prompts[0].contains("haptics"))
        assertEquals(listOf("haptics.impact"), handler.dispatched)
        assertEquals(MiniAppGrantDecision.ALLOW, repository.grantDecision(app.id, "haptics"))

        // Second call reuses the durable grant — no new dialog.
        val second = bridge.request("haptics.impact")
        assertTrue(second.error, second.ok)
        assertEquals(1, confirmation.prompts.size)
    }

    @Test
    fun userDenialPersistsDenyAndLaterCallsFailWithoutDialog() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        val confirmation = ScriptedConfirmation(false)
        val handler = RecordingHandler(setOf("haptics.impact"))
        val bridge = bridgeOf(app, handler, confirmation)

        val response = bridge.request("haptics.impact")
        assertFalse(response.ok)
        assertEquals("user_denied", response.errorCode)
        assertTrue(handler.dispatched.isEmpty())
        assertEquals(MiniAppGrantDecision.DENY, repository.grantDecision(app.id, "haptics"))

        val retry = bridge.request("haptics.impact")
        assertFalse(retry.ok)
        assertEquals("permission_denied", retry.errorCode)
        assertEquals(1, confirmation.prompts.size)
    }

    @Test
    fun appChangedWhileConfirmationOpenRejectsAndWritesNoGrant() = runBlocking {
        val app = appEntity(listOf("haptics"), version = 1)
        repository.upsert(app)
        val racingConfirmation = object : MiniAppUserConfirmation {
            override suspend fun confirm(title: String, message: String): Boolean {
                repository.saveNewVersion(app, "<html><body>v2</body></html>")
                return true
            }
        }
        val handler = RecordingHandler(setOf("haptics.impact"))
        val bridge = bridgeOf(app, handler, racingConfirmation)

        val response = bridge.request("haptics.impact")
        assertFalse(response.ok)
        assertEquals("miniapp_changed", response.errorCode)
        assertTrue(handler.dispatched.isEmpty())
        assertEquals(null, repository.grantDecision(app.id, "haptics"))
    }

    @Test
    fun undeclaredPermissionAndDisabledSwitchAreDistinctFailures() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        val bridge = bridgeOf(
            app,
            handler = RecordingHandler(setOf("haptics.impact", "share")),
            confirmation = ScriptedConfirmation(true),
        )
        // Declared haptics but not share.
        val undeclared = bridge.request("share")
        assertFalse(undeclared.ok)
        assertEquals("permission_denied", undeclared.errorCode)

        val disabled = bridgeOf(
            app,
            handler = RecordingHandler(setOf("haptics.impact")),
            confirmation = ScriptedConfirmation(true),
            setting = MiniAppSetting(systemCapabilitiesEnabled = false),
        )
        val blocked = disabled.request("haptics.impact")
        assertFalse(blocked.ok)
        assertEquals("permission_denied", blocked.errorCode)
    }

    @Test
    fun qrcodeSkipsGrantButNotSystemSwitchAndIsAudited() = runBlocking {
        val app = appEntity(emptyList())
        repository.upsert(app)
        val handler = RecordingHandler(setOf("qrcode.generate"))
        val confirmation = ScriptedConfirmation(true)
        val bridge = bridgeOf(app, handler, confirmation)

        val response = bridge.request("qrcode.generate")
        assertTrue(response.error, response.ok)
        assertEquals(listOf("qrcode.generate"), handler.dispatched)
        // No per-app permission dialog for QR.
        assertTrue(confirmation.prompts.isEmpty())
        assertEquals(null, repository.grantDecision(app.id, "qrcode.generate"))

        val disabled = bridgeOf(
            app,
            handler = RecordingHandler(setOf("qrcode.generate")),
            confirmation = ScriptedConfirmation(true),
            setting = MiniAppSetting(systemCapabilitiesEnabled = false),
        )
        assertFalse(disabled.request("qrcode.generate").ok)
    }

    @Test
    fun openURLValidatesTargetAndConfirmsEveryCall() = runBlocking {
        val app = appEntity(listOf("openURL"))
        repository.upsert(app)
        repository.setGrant(app.id, "openURL", MiniAppGrantDecision.ALLOW)
        val handler = RecordingHandler(setOf("openURL"))
        val confirmation = ScriptedConfirmation(true)
        val bridge = bridgeOf(app, handler, confirmation)

        val ok = bridge.request("openURL", params = """{"url":"https://example.com/docs"}""")
        assertTrue(ok.error, ok.ok)
        assertEquals(listOf("openURL"), handler.dispatched)
        // Per-call confirmation even though the grant is already ALLOW.
        assertEquals(1, confirmation.prompts.size)
        assertTrue(confirmation.prompts[0].contains("https://example.com/docs"))

        // Second call confirms again.
        assertTrue(bridge.request("openURL", params = """{"url":"https://example.com/docs"}""").ok)
        assertEquals(2, confirmation.prompts.size)

        // Invalid target fails before confirmation and never reaches native.
        val invalid = bridge.request("openURL", params = """{"url":"https://127.0.0.1/x"}""")
        assertFalse(invalid.ok)
        assertEquals(2, confirmation.prompts.size)
        assertEquals(2, handler.dispatched.size)
    }

    @Test
    fun unsupportedMethodAndMissingHandlerFailWithoutDialogs() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        val confirmation = ScriptedConfirmation(true)

        val noHandler = bridgeOf(app, handler = null, confirmation = confirmation)
        val unavailable = noHandler.request("haptics.impact")
        assertFalse(unavailable.ok)
        assertEquals("system_unavailable", unavailable.errorCode)

        val partial = bridgeOf(app, handler = RecordingHandler(setOf("haptics.impact")), confirmation = confirmation)
        val unsupported = partial.request("speech.speak", params = """{"text":"hi"}""")
        assertFalse(unsupported.ok)
        assertEquals("method_unsupported", unsupported.errorCode)
        assertTrue(confirmation.prompts.isEmpty())
    }

    @Test
    fun invalidUrlFailsBeforePermissionDialogAndWritesNoGrant() = runBlocking {
        val app = appEntity(listOf("share", "openURL"))
        repository.upsert(app)
        val confirmation = ScriptedConfirmation(true)
        val handler = RecordingHandler(setOf("share", "openURL"))
        val bridge = bridgeOf(app, handler, confirmation)

        val shareInvalid = bridge.request("share", params = """{"text":"hi","url":"https://192.168.0.1/x"}""")
        assertFalse(shareInvalid.ok)
        assertEquals("invalid_url", shareInvalid.errorCode)
        // The invalid request never prompts nor persists a durable grant.
        assertTrue(confirmation.prompts.isEmpty())
        assertEquals(null, repository.grantDecision(app.id, "share"))
        assertTrue(handler.dispatched.isEmpty())

        val openInvalid = bridge.request("openURL", params = """{"url":"https://127.0.0.1/x"}""")
        assertFalse(openInvalid.ok)
        assertEquals("invalid_url", openInvalid.errorCode)
        assertEquals(null, repository.grantDecision(app.id, "openURL"))
        assertTrue(handler.dispatched.isEmpty())
    }

    @Test
    fun closedBridgeRepliesRunnerClosedToLateRequests() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        val bridge = bridgeOf(
            app,
            handler = RecordingHandler(setOf("haptics.impact")),
            confirmation = ScriptedConfirmation(true),
        )
        bridge.close()

        val late = bridge.request("haptics.impact")
        assertFalse(late.ok)
        assertEquals("runner_closed", late.errorCode)
    }

    @Test
    fun appInfoReportsUnknownAfterDurableDelete() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        val bridge = bridgeOf(
            app,
            handler = RecordingHandler(setOf("haptics.impact")),
            confirmation = ScriptedConfirmation(true),
        )
        repository.delete(app.id)

        val response = bridge.request("app.info")
        assertTrue(response.error, response.ok)
        val data = response.data!!.jsonObject
        // Deleted MiniApp reports Unknown instead of the runner snapshot.
        assertEquals("Unknown MiniApp", data["title"]!!.jsonPrimitive.content)
        assertEquals(0, data["version"]!!.jsonPrimitive.content.toInt())
    }
    @Test
    fun masterSwitchBlocksGrantedSystemCallsAndGrantlessQr() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        repository.setGrant(app.id, "haptics", MiniAppGrantDecision.ALLOW)
        val handler = RecordingHandler(setOf("haptics.impact", "qrcode.generate"))
        val confirmation = ScriptedConfirmation(true)
        val bridge = bridgeOf(app, handler, confirmation, MiniAppSetting(enabled = false))
        listOf("haptics.impact", "qrcode.generate").forEach { method ->
            assertEquals("permission_denied", bridge.request(method).errorCode)
        }
        assertTrue(handler.dispatched.isEmpty())
        assertTrue(confirmation.prompts.isEmpty())
        val capabilities = bridge.request("app.capabilities").data!!.jsonObject
        assertFalse(capabilities["systemCapabilitiesEnabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun masterSwitchChangedDuringGrantConfirmationWritesNoGrant() = runBlocking {
        val app = appEntity(listOf("haptics"))
        repository.upsert(app)
        var setting = MiniAppSetting()
        val confirmation = object : MiniAppUserConfirmation {
            override suspend fun confirm(title: String, message: String): Boolean {
                setting = setting.copy(enabled = false)
                return true
            }
        }
        val handler = RecordingHandler(setOf("haptics.impact"))
        val bridge = bridgeOf(app, handler, confirmation, settingProvider = { setting })
        assertEquals("miniapp_changed", bridge.request("haptics.impact").errorCode)
        assertEquals(null, repository.grantDecision(app.id, "haptics"))
        assertTrue(handler.dispatched.isEmpty())
    }

    @Test
    fun openUrlRechecksAppGrantAndSwitchAfterPerCallConfirmation() = runBlocking {
        val changes = listOf("version", "hash", "declaration", "delete", "deny", "master", "system")
        for (change in changes) {
            val app = appEntity(listOf("openURL"))
            repository.upsert(app)
            repository.setGrant(app.id, "openURL", MiniAppGrantDecision.ALLOW)
            var setting = MiniAppSetting()
            val confirmation = object : MiniAppUserConfirmation {
                override suspend fun confirm(title: String, message: String): Boolean {
                    when (change) {
                        "version" -> repository.upsert(app.copy(version = 2))
                        "hash" -> repository.upsert(app.copy(htmlHash = "changed"))
                        "declaration" -> repository.upsert(app.copy(permissionsJson = "[]"))
                        "delete" -> repository.delete(app.id)
                        "deny" -> repository.setGrant(app.id, "openURL", MiniAppGrantDecision.DENY)
                        "master" -> setting = setting.copy(enabled = false)
                        "system" -> setting = setting.copy(systemCapabilitiesEnabled = false)
                    }
                    return true
                }
            }
            val handler = RecordingHandler(setOf("openURL"))
            val bridge = bridgeOf(app, handler, confirmation, settingProvider = { setting })
            val response = bridge.request("openURL", params = """{"url":"https://example.com/docs"}""")
            assertEquals(change, if (change == "deny") "permission_denied" else "miniapp_changed", response.errorCode)
            assertTrue(change, handler.dispatched.isEmpty())
            bridge.close()
        }
    }

}
