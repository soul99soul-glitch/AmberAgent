package app.amber.ai.provider.providers

import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.util.configureOpenCodeSessionHeader
import app.amber.ai.util.toHeaders
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeSessionHeaderTest {
    private val baseUrl = "https://opencode.ai/zen/go/v1"
    private val model = Model(modelId = "test-model")

    @Test
    fun `all Go text transports send the conversation session header`() = runBlocking {
        for (path in listOf("/chat/completions", "/responses", "/messages")) {
            for (streaming in listOf(false, true)) {
                val requests = mutableListOf<Request>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val payload = when (path) {
                        "/chat/completions" -> if (streaming) "data: [DONE]\n\n" else chatCompletion
                        "/responses" -> if (streaming) {
                            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$responseCompletion}\n\n"
                        } else responseCompletion
                        else -> if (streaming) {
                            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                        } else """{"id":"msg_test","content":[],"stop_reason":"end_turn"}"""
                    }
                    response(request, payload, streaming = streaming)
                }.build()
                try {
                    val params = TextGenerationParams(
                        model = model,
                        sessionId = "conversation-a",
                        customHeaders = listOf(CustomHeader("X-Custom", "preserved")),
                    )
                    val messages = listOf(UIMessage.user("hello"))
                    withTimeout(5_000) {
                        if (path == "/messages") {
                            val gateway = ClaudeProvider(client)
                            val setting = ProviderSetting.Claude(baseUrl = baseUrl, apiKey = "test-key")
                            if (streaming) gateway.stream(setting, messages, params).toList()
                            else gateway.complete(setting, messages, params)
                        } else {
                            val gateway = OpenAIProvider(client)
                            val setting = ProviderSetting.OpenAI(
                                baseUrl = baseUrl,
                                apiKey = "test-key",
                                useResponseApi = path == "/responses",
                            )
                            if (streaming) gateway.stream(setting, messages, params).toList()
                            else gateway.complete(setting, messages, params)
                        }
                    }
                    val request = requests.single()
                    assertEquals("$baseUrl$path", request.url.toString())
                    assertEquals(listOf("conversation-a"), request.headers.values("x-opencode-session"))
                    assertEquals("preserved", request.header("X-Custom"))
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    @Test
    fun `standalone session survives provider parameter retry and differs for another request`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            if (requests.size == 1) {
                response(request, """{"error":{"message":"Unsupported parameter: temperature"}}""", code = 400)
            } else {
                response(request, chatCompletion)
            }
        }.build()
        try {
            val gateway = OpenAIProvider(client)
            val setting = ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "test-key")
            val params = TextGenerationParams(model = model, temperature = 0.5f)
            val messages = listOf(UIMessage.user("hello"))
            gateway.complete(setting, messages, params)
            gateway.complete(setting, messages, TextGenerationParams(model = model))

            assertEquals(3, requests.size)
            assertTrue(params.sessionId.isNotBlank())
            assertEquals(params.sessionId, requests[0].header("x-opencode-session"))
            assertEquals(params.sessionId, requests[1].header("x-opencode-session"))
            assertNotEquals(params.sessionId, requests[2].header("x-opencode-session"))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `explicit session header is case insensitive and blank header gets a session`() {
        val params = TextGenerationParams(model = model, sessionId = "conversation-a")
        val configured = params.copy(customHeaders = listOf(CustomHeader("X-OpenCode-Session", "manual-session")))
        val blank = params.copy(customHeaders = listOf(CustomHeader("x-opencode-session", " ")))

        assertEquals(listOf("manual-session"), request(baseUrl, configured).headers.values("x-opencode-session"))
        assertEquals(listOf("conversation-a"), request(baseUrl, blank).headers.values("x-opencode-session"))
    }

    @Test
    fun `automatic session header is restricted to the OpenCode host`() {
        val params = TextGenerationParams(model = model)
        for (url in listOf("https://api.openai.com/v1", "https://opencode.ai.example.com/v1")) {
            assertNull(request(url, params).header("x-opencode-session"))
        }
    }

    private fun request(url: String, params: TextGenerationParams): Request = Request.Builder()
        .url("$url/messages")
        .headers(params.customHeaders.toHeaders())
        .configureOpenCodeSessionHeader(url, params)
        .build()

    private fun response(request: Request, payload: String, code: Int = 200, streaming: Boolean = false): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Bad Request")
            .body(payload.toResponseBody((if (streaming) "text/event-stream" else "application/json").toMediaType()))
            .build()

    private val chatCompletion = """{"id":"chat_test","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
    private val responseCompletion = """{"id":"resp_test","status":"completed","output":[]}"""
}
