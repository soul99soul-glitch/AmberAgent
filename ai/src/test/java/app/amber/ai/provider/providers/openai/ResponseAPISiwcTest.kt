package app.amber.ai.provider.providers.openai

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.provider.BuiltInTools
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.availableAuthModes
import app.amber.ai.provider.fixedBaseUrl
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.HttpException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

class ResponseAPISiwcTest {

    private fun siwcSetting(baseUrl: String = "https://custom.ignored.example/v1") = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "ChatGPT",
        baseUrl = baseUrl,
        apiKey = "",
        useResponseApi = false,
        enableResponsesResume = true,
        authMode = OpenAIAuthMode.CHATGPT_SIWC,
        brand = OpenAIBrand.OPENAI,
    )

    @Test
    fun siwcAuthModeRegistrationPreservesCodexAndPinsPublicApiBaseUrl() {
        assertEquals("https://api.openai.com/v1", OpenAIAuthMode.CHATGPT_SIWC.fixedBaseUrl())
        assertEquals("https://chatgpt.com/backend-api/codex", OpenAIAuthMode.CODEX_OAUTH.fixedBaseUrl())
        val openAiModes = OpenAIBrand.OPENAI.availableAuthModes()
        assertTrue(openAiModes.contains(OpenAIAuthMode.API_KEY))
        assertTrue(openAiModes.contains(OpenAIAuthMode.CHATGPT_SIWC))
        assertTrue(openAiModes.contains(OpenAIAuthMode.CODEX_OAUTH))
        assertFalse(siwcSetting(baseUrl = "https://api.openai.com/v1").supportsResponsesResume())
    }

    @Test
    fun requestEncoderEnforcesSiwcPreviewConstraintsAndFiltersUnsupportedFieldsAndTools() {
        val model = Model(
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            tools = setOf(BuiltInTools.ImageGeneration, BuiltInTools.Search),
        )
        val customTool = Tool(
            name = "lookup_weather",
            description = "Get weather",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { emptyList() },
        )
        val forbiddenKeys = listOf(
            "background",
            "conversation",
            "max_output_tokens",
            "max_tool_calls",
            "metadata",
            "moderation",
            "multi_agent",
            "previous_response_id",
            "prompt",
            "prompt_cache_retention",
            "safety_identifier",
            "temperature",
            "top_logprobs",
            "top_p",
            "truncation",
            "user",
        )
        val customBodies = forbiddenKeys.map { CustomBody(it, JsonPrimitive("should-strip")) } + listOf(
            CustomBody("store", JsonPrimitive(true)),
            CustomBody("stream", JsonPrimitive(false)),
            CustomBody(
                "tools",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", "lookup_weather")
                    })
                    add(buildJsonObject { put("type", "image_generation") })
                    add(buildJsonObject { put("type", "file_search") })
                    add(buildJsonObject { put("type", "code_interpreter") })
                    add(buildJsonObject { put("type", "computer_use_preview") })
                    add(buildJsonObject { put("type", "mcp") })
                    add(buildJsonObject { put("type", "tool_search") })
                    add(buildJsonObject { put("type", "programmatic_tool_calling") })
                    add(buildJsonObject {
                        put("type", "namespace")
                        put("name", "calendar")
                        put("description", "Calendar tools")
                        put("tools", buildJsonArray {
                            add(buildJsonObject { put("type", "custom"); put("name", "agenda") })
                        })
                    })
                }
            ),
            CustomBody(
                "input",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "message")
                        put("role", "system")
                        put("content", "Injected system role")
                    })
                    add(buildJsonObject {
                        put("type", "message")
                        put("role", "user")
                        put("content", "Hello")
                    })
                    add(buildJsonObject {
                        put("type", "additional_tools")
                        put("tools", buildJsonArray {
                            add(buildJsonObject { put("type", "function"); put("name", "local") })
                            add(buildJsonObject { put("type", "file_search") })
                        })
                    })
                }
            ),
        )

        val body = ResponseRequestEncoder.buildRequestBody(
            providerSetting = siwcSetting(baseUrl = "https://api.openai.com/v1"),
            messages = listOf(
                UIMessage.system("Top system instruction"),
                UIMessage.user("Hello"),
            ),
            params = TextGenerationParams(
                model = model,
                temperature = 0.7f,
                topP = 0.9f,
                maxTokens = 2048,
                tools = listOf(customTool),
                customBody = customBodies,
            ),
            stream = false,
            resume = null,
        )

        assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        assertEquals("Top system instruction", body["instructions"]?.jsonPrimitive?.contentOrNull)

        for (forbidden in forbiddenKeys) {
            assertNull("SIWC body must omit unsupported field '$forbidden'", body[forbidden])
        }

        val inputItems = body["input"]?.jsonArray ?: error("input missing")
        assertTrue(
            "SIWC input must never contain role=system message",
            inputItems.none { (it as? JsonObject)?.get("role")?.jsonPrimitive?.contentOrNull == "system" },
        )
        assertEquals("developer", inputItems[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)

        val tools = body["tools"]?.jsonArray ?: error("tools missing")
        assertEquals(listOf("function", "namespace"), tools.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull })
        assertEquals("lookup_weather", tools.first().jsonObject["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("agenda", tools.last().jsonObject.getValue("tools").jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("local", inputItems.last().jsonObject.getValue("tools").jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun responseFailedUsageLimitAndUnavailableSurfaceActionableUsageUrl() {
        val limitEvent = buildJsonObject {
            put("type", "response.failed")
            put("response", buildJsonObject {
                put("error", buildJsonObject {
                    put("code", "subscription_sharing_usage_limit_exceeded")
                    put("message", "Weekly app usage limit reached")
                })
            })
        }
        val limitError = runCatching { ResponseEventDecoder.parseResponseDelta(limitEvent) }.exceptionOrNull()
        assertTrue(limitError is HttpException)
        assertTrue(limitError!!.message!!.contains("subscription_sharing_usage_limit_exceeded"))
        assertTrue(limitError.message!!.contains(OPENAI_CHATGPT_SIWC_USAGE_URL))

        val unavailableEvent = buildJsonObject {
            put("type", "response.failed")
            put("response", buildJsonObject {
                put("error", buildJsonObject {
                    put("code", "subscription_sharing_usage_unavailable")
                    put("message", "Temporary check failure")
                })
            })
        }
        val unavailableError = runCatching { ResponseEventDecoder.parseResponseDelta(unavailableEvent) }.exceptionOrNull()
        assertTrue(unavailableError is HttpException)
        assertTrue(unavailableError!!.message!!.contains("subscription_sharing_usage_unavailable"))
        assertTrue(unavailableError.message!!.contains(OPENAI_CHATGPT_SIWC_USAGE_URL))
    }

    @Test
    fun onlyCompletedPayloadFinishesSiwcButCodexCanReturnIncomplete() = runBlocking {
        val incomplete = """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}}"""
        val completed = """{"type":"response.completed","response":{"id":"r1","status":"completed","output":[]}}"""
        suspend fun collect(body: String, mode: OpenAIAuthMode = OpenAIAuthMode.CHATGPT_SIWC) =
            ResponseAPI(
                OkHttpClient.Builder().addInterceptor { chain ->
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(body.toResponseBody("text/event-stream".toMediaType())).build()
                }.build(),
                bearerResolver = { _, _ -> "test" },
            ).streamText(siwcSetting().copy(authMode = mode), emptyList(), TextGenerationParams(model = Model(modelId = "test"))).toList()

        val partial = "event: response.incomplete\ndata: $incomplete\n\n"
        assertTrue(collect(partial, OpenAIAuthMode.CODEX_OAUTH).isNotEmpty())
        assertTrue(runCatching { collect(partial) }.exceptionOrNull()?.message.orEmpty().contains("max_output_tokens"))
        assertTrue(collect("data: $completed\n\n").isNotEmpty())
        assertTrue(runCatching { collect("event: response.completed\n\n") }.isFailure)
        assertTrue(runCatching { collect("data: [DONE]\n\n") }.isFailure)
    }

    @Test
    fun openAIProviderForcesPublicResponsesEndpointWithStreamTrueAndStoreFalseForSiwc() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val requestBodies = CopyOnWriteArrayList<String>()
        val sse = "event: response.output_text.delta\n" +
            """data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hello from SIWC"}""" + "\n\n" +
            "event: response.completed\n" +
            """data: {"type":"response.completed","response":{"id":"resp_siwc","status":"completed","output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello from SIWC"}]}]}}""" + "\n\n"

        var quotaReached = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req
            val buf = Buffer()
            req.body?.writeTo(buf)
            requestBodies += buf.readUtf8()
            if (quotaReached) {
                val error = """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Weekly app limit"}}"""
                return@addInterceptor Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                    .code(429).message("Too Many Requests").header("x-request-id", "quota-test")
                    .body(error.toResponseBody("application/json".toMediaType())).build()
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()

        val backing = object : SiwcKeyValueStore {
            private val map = mutableMapOf<String, String>()
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun exportPlainValues() = map.toMap()
            override fun replacePlainValues(values: Map<String, String>) { map.clear(); map.putAll(values) }
        }
        val store = OpenAIChatGptSiwcAuthStore(backing)
        val setting = siwcSetting()
        store.save(
            setting.id,
            OpenAIChatGptSiwcTokens(
                clientId = "oaiapp_test",
                extAgentHostId = store.getOrCreateHostId(),
                accessToken = "siwc-live-token",
                refreshToken = "siwc-rt",
                expiresAtMillis = System.currentTimeMillis() + 3600_000L,
                scopes = listOf(OPENAI_CHATGPT_SIWC_DIRECT_USAGE_SCOPE),
            ),
        )
        val siwcClient = OpenAIChatGptSiwcOAuthClient(http, store)
        val provider = OpenAIProvider(client = http, context = null, siwcOAuthClient = siwcClient)

        val completed = withTimeout(10_000) {
            provider.complete(
                providerSetting = setting,
                messages = listOf(UIMessage.system("Be concise"), UIMessage.user("Hi")),
                params = TextGenerationParams(
                    model = Model(modelId = "gpt-5.4"),
                    temperature = 0.8f,
                    maxTokens = 512,
                    customHeaders = listOf(CustomHeader("Authorization", "Bearer previous-mode")),
                ),
            )
        }

        assertEquals(1, requests.size)
        assertEquals("https://api.openai.com/v1/responses", requests.single().url.toString())
        assertEquals(listOf("Bearer siwc-live-token"), requests.single().headers("Authorization"))
        val sentJson = app.amber.ai.util.json.parseToJsonElement(requestBodies.single()).jsonObject
        assertEquals(true, sentJson["stream"]?.jsonPrimitive?.boolean)
        assertEquals(false, sentJson["store"]?.jsonPrimitive?.boolean)
        assertNull(sentJson["temperature"])
        assertNull(sentJson["max_output_tokens"])
        assertEquals("Be concise", sentJson["instructions"]?.jsonPrimitive?.contentOrNull)
        val text = completed.choices.single().message?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("") { it.text }
        assertEquals("Hello from SIWC", text)
        assertFalse(provider.supportsImageEdit(setting))

        quotaReached = true
        val failure = runCatching {
            provider.complete(setting, listOf(UIMessage.user("Hi")), TextGenerationParams(model = Model(modelId = "gpt-5.4")))
        }.exceptionOrNull()
        assertTrue(failure is HttpException)
        for (detail in listOf("429", "quota-test", "subscription_sharing_usage_limit_exceeded", OPENAI_CHATGPT_SIWC_USAGE_URL)) {
            assertTrue(failure?.message.orEmpty().contains(detail))
        }
        assertEquals(2, requests.size)
        assertTrue(store.get(setting.id)!!.isUsable())
    }
}
