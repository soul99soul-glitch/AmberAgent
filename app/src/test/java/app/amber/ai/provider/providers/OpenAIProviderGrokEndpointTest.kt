package app.amber.ai.provider.providers

import android.app.Application
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.provider.providers.grok.GrokOAuthTokens
import app.amber.ai.ui.UIMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OpenAIProviderGrokEndpointTest {
    @Test
    fun `grok oauth chat pins the cli proxy when persisted endpoint is stale`() = runBlocking {
        val context = TestContext()
        val providerId = Uuid.random()
        GrokAuthStore(context).save(
            providerId,
            GrokOAuthTokens(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresAtMillis = System.currentTimeMillis() + 60 * 60 * 1000L,
            ),
        )
        val capturedUrl = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                capturedUrl.set(request.url.toString())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"id":"response-1","model":"grok-4.5","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val provider = OpenAIProvider(client, context)
        val setting = ProviderSetting.OpenAI(
            id = providerId,
            baseUrl = "https://api.x.ai/v1",
            authMode = OpenAIAuthMode.GROK_OAUTH,
            useResponseApi = false,
        )

        provider.complete(
            providerSetting = setting,
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "grok-4.5")),
        )

        assertEquals(
            "https://cli-chat-proxy.grok.com/v1/chat/completions",
            capturedUrl.get(),
        )
    }

    @Test
    fun `grok oauth stream pins the cli proxy when persisted endpoint is stale`() = runBlocking {
        val context = TestContext()
        val providerId = Uuid.random()
        GrokAuthStore(context).save(
            providerId,
            GrokOAuthTokens(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresAtMillis = System.currentTimeMillis() + 60 * 60 * 1000L,
            ),
        )
        val capturedUrl = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                capturedUrl.set(request.url.toString())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(
                        """data: {"id":"response-1","model":"grok-4.5","choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

data: [DONE]

""".toResponseBody("text/event-stream".toMediaType()),
                    )
                    .build()
            }
            .build()
        val provider = OpenAIProvider(client, context)
        val setting = ProviderSetting.OpenAI(
            id = providerId,
            baseUrl = "https://api.x.ai/v1",
            authMode = OpenAIAuthMode.GROK_OAUTH,
            useResponseApi = false,
        )

        withTimeout(2_000) {
            provider.stream(
                providerSetting = setting,
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "grok-4.5")),
            ).take(1).toList()
        }

        assertEquals(
            "https://cli-chat-proxy.grok.com/v1/chat/completions",
            capturedUrl.get(),
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun installTestKeyStore() = TestAndroidKeyStore.install()

        @JvmStatic
        @AfterClass
        fun restoreTestKeyStore() = TestAndroidKeyStore.restore()
    }
}
