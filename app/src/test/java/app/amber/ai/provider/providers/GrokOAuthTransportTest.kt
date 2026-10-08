package app.amber.ai.provider.providers

import android.app.Application
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.grok.GROK_CLI_PROXY_BASE_URL
import app.amber.ai.provider.providers.grok.GROK_OAUTH_TOKEN_ENDPOINT
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.provider.providers.grok.GrokOAuthTokens
import app.amber.ai.provider.providers.openai.ChatCompletionsAPI
import app.amber.ai.ui.UIMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/** Managed Grok OAuth owns its chat endpoint and refreshes rejected cached credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GrokOAuthTransportTest {
    @Test
    fun `managed Grok complete refreshes rejected unexpired cached bearer and retries`() = runBlocking {
        verifyRejectedBearerRecovery(stream = false)
    }

    @Test
    fun `managed Grok stream refreshes initial rejected bearer before emitting and retries`() = runBlocking {
        verifyRejectedBearerRecovery(stream = true)
    }

    private suspend fun verifyRejectedBearerRecovery(stream: Boolean) {
        val context = TestContext()
        val providerId = Uuid.random()
        GrokAuthStore(context).save(providerId, GrokOAuthTokens("old-access", "refresh-token", System.currentTimeMillis() + 3_600_000L))
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            when {
                request.url.toString() == GROK_OAUTH_TOKEN_ENDPOINT -> reply(request, 200,
                    """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")
                request.header("Authorization") == "Bearer old-access" -> reply(request, 401,
                    """{"error":{"message":"OAuth access token rejected","type":"invalid_token"}}""")
                else -> reply(request, 200, if (stream) STREAM_OK else COMPLETE_OK,
                    if (stream) "text/event-stream" else "application/json")
            }
        }.build()
        val provider = OpenAIProvider(client, context)
        val setting = ProviderSetting.OpenAI(id = providerId, authMode = OpenAIAuthMode.GROK_OAUTH)
        val params = TextGenerationParams(model = Model(modelId = "grok-4.5"))
        val outcome = runCatching {
            withTimeout(5_000) {
                if (stream) provider.stream(setting, listOf(UIMessage.user("hello")), params).toList()
                else provider.complete(setting, listOf(UIMessage.user("hello")), params)
            }
        }
        assertNull("An initial Grok OAuth 401 with a refreshable token should recover; calls=" + requests.map { it.url }, outcome.exceptionOrNull())
        assertEquals(1, requests.count { it.url.toString() == GROK_OAUTH_TOKEN_ENDPOINT })
        assertEquals(listOf("Bearer old-access", "Bearer new-access"), requests.filter { it.url.toString() != GROK_OAUTH_TOKEN_ENDPOINT }.map { it.header("Authorization") })
    }

    @Test
    fun `managed Grok complete pins chat path despite incompatible persisted configuration`() = runBlocking {
        val context = TestContext()
        val providerId = Uuid.random()
        GrokAuthStore(context).save(providerId, GrokOAuthTokens("access", "refresh", System.currentTimeMillis() + 3_600_000L))
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            reply(chain.request(), 200, COMPLETE_OK)
        }.build()
        OpenAIProvider(client, context).complete(
            ProviderSetting.OpenAI(id = providerId, name = "xAI", authMode = OpenAIAuthMode.GROK_OAUTH,
                baseUrl = "https://api.x.ai/v1", chatCompletionsPath = "/responses", useResponseApi = true),
            listOf(UIMessage.user("hello")), TextGenerationParams(model = Model(modelId = "grok-4.5")),
        )
        assertEquals("Managed Grok OAuth pins its transport contract independently of editable API-key fields",
            "$GROK_CLI_PROXY_BASE_URL/chat/completions", requests.single().url.toString())
    }

    @Test
    fun `Grok chat retries a rejected bearer only once for complete and stream`() = runBlocking {
        for (stream in listOf(false, true)) {
            val requests = CopyOnWriteArrayList<Request>()
            val refreshes = CopyOnWriteArrayList<Boolean>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                reply(chain.request(), 401, "{}")
            }.build()
            val api = ChatCompletionsAPI(client, bearerResolver = { _, forceRefresh ->
                refreshes += forceRefresh
                if (forceRefresh) "new-access" else "old-access"
            })
            val setting = ProviderSetting.OpenAI(authMode = OpenAIAuthMode.GROK_OAUTH)
            val params = TextGenerationParams(model = Model(modelId = "grok-4.5"))
            val outcome = runCatching {
                withTimeout(5_000) {
                    if (stream) api.streamText(setting, listOf(UIMessage.user("hello")), params).toList()
                    else api.generateText(setting, listOf(UIMessage.user("hello")), params)
                }
            }
            assertNotNull(outcome.exceptionOrNull())
            assertEquals(listOf(false, true), refreshes.toList())
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun `API key chat does not invoke OAuth refresh on 401 for complete and stream`() = runBlocking {
        for (stream in listOf(false, true)) {
            val requests = CopyOnWriteArrayList<Request>()
            val refreshes = CopyOnWriteArrayList<Boolean>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                reply(chain.request(), 401, "{}")
            }.build()
            val api = ChatCompletionsAPI(client, bearerResolver = { _, forceRefresh ->
                refreshes += forceRefresh
                "api-key"
            })
            val setting = ProviderSetting.OpenAI(authMode = OpenAIAuthMode.API_KEY)
            val params = TextGenerationParams(model = Model(modelId = "grok-4.5"))
            val outcome = runCatching {
                withTimeout(5_000) {
                    if (stream) api.streamText(setting, listOf(UIMessage.user("hello")), params).toList()
                    else api.generateText(setting, listOf(UIMessage.user("hello")), params)
                }
            }
            assertNotNull(outcome.exceptionOrNull())
            assertEquals(listOf(false), refreshes.toList())
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `cancelling Grok stream during refresh prevents request replay`() = runBlocking {
        withTimeout(5_000) {
            val requests = CopyOnWriteArrayList<Request>()
            val enteredRefresh = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                reply(chain.request(), 401, "{}")
            }.build()
            val api = ChatCompletionsAPI(client, bearerResolver = { _, forceRefresh ->
                if (forceRefresh) {
                    enteredRefresh.complete(Unit)
                    awaitCancellation()
                }
                "old-access"
            })
            val deferred = async {
                api.streamText(ProviderSetting.OpenAI(authMode = OpenAIAuthMode.GROK_OAUTH),
                    listOf(UIMessage.user("hello")), TextGenerationParams(model = Model(modelId = "grok-4.5"))).toList()
            }
            enteredRefresh.await()
            deferred.cancel()
            deferred.join()
            assertTrue(deferred.isCancelled)
            assertEquals(1, requests.size)
        }
    }

    private fun reply(request: Request, code: Int, body: String, contentType: String = "application/json") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Unauthorized")
            .header("Content-Type", contentType).body(body.toResponseBody(contentType.toMediaType())).build()

    companion object {
        private const val COMPLETE_OK = """{"id":"r1","model":"grok-4.5","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        private const val STREAM_OK = "data: {\"id\":\"r1\",\"model\":\"grok-4.5\",\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        @JvmStatic @BeforeClass fun installKeyStore() = TestAndroidKeyStore.install()
        @JvmStatic @AfterClass fun restoreKeyStore() = TestAndroidKeyStore.restore()
    }
}
