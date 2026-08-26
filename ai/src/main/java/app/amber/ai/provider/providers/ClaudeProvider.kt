package app.amber.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ImageModelGateway
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.util.KeyRoulette
import app.amber.ai.util.configureReferHeaders
import app.amber.ai.util.json
import app.amber.ai.util.parseErrorDetail
import app.amber.ai.util.stringSafe
import app.amber.ai.util.toHeaders
import app.amber.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"

class ClaudeProvider(
    private val client: OkHttpClient,
    context: Context? = null,
) : TextModelGateway<ProviderSetting.Claude>, ImageModelGateway<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val messagesAdapter = AnthropicMessagesAdapter()

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val apiKey = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val catalogRequest = Request.Builder()
                .url(providerSetting.baseUrl.trimEnd('/') + "/models")
                .header("x-api-key", apiKey)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .build()
            val payload = client.newCall(catalogRequest).execute().use { response ->
                val body = response.body.string()
                check(response.isSuccessful) {
                    "Anthropic model catalog failed: HTTP ${response.code} ${body.take(300)}"
                }
                body
            }
            decodeModelCatalog(payload)
        }

    private fun decodeModelCatalog(payload: String): List<Model> =
        json.parseToJsonElement(payload).jsonObject["data"]
            ?.jsonArray
            ?.mapNotNull { item ->
                val fields = item.jsonObject
                val modelId = fields["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Model(
                    modelId = modelId,
                    displayName = fields["display_name"]?.jsonPrimitive?.contentOrNull ?: modelId,
                )
            }
            .orEmpty()

    override suspend fun generateImage(
        providerSetting: ProviderSetting.Claude,
        params: ImageGenerationParams
    ): ImageGenerationResult {
        error("Claude provider does not support image generation")
    }

    override suspend fun complete(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = messagesAdapter.encodeRequest(providerSetting, messages, params, streaming = false)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: model=${params.model.modelId}, messages=${messages.size}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        messagesAdapter.decodeCompletion(bodyJson)
    }

    override suspend fun stream(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = messagesAdapter.encodeRequest(providerSetting, messages, params, streaming = true)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: model=${params.model.modelId}, messages=${messages.size}, stream=true")

        val terminationGuard = StreamTerminationGuard(StreamProtocol.CLAUDE)
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    handleStreamEvent(eventSource, id, type, data)
                } catch (error: OutOfMemoryError) {
                    Log.e(TAG, "Claude stream exhausted app heap; canceling stream")
                    eventSource.cancel()
                    close(error)
                } catch (error: Throwable) {
                    Log.e(TAG, "Claude stream event failed: ${error.message.orEmpty()}")
                    eventSource.cancel()
                    close(error)
                }
            }

            private fun handleStreamEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                terminationGuard.observe(type, data)
                logStreamEvent(type, data)
                when (val signal = messagesAdapter.decodeStreamEvent(type, id, data)) {
                    is AnthropicStreamSignal.Emit -> {
                        // Blocking send provides backpressure instead of dropping token deltas.
                        if (trySendBlocking(signal.chunk).isFailure) eventSource.cancel()
                    }

                    is AnthropicStreamSignal.Failure -> {
                        eventSource.cancel()
                        close(signal.cause)
                    }

                    AnthropicStreamSignal.Stop -> close()
                    AnthropicStreamSignal.Ignore -> Unit
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse response body chars=${bodyRaw?.length ?: 0}", e)
                } finally {
                    // 非 2xx 且 body 为空/非 JSON 时 t 为 null；必须合成异常,
                    // 否则 close(null) 会让 flow 以零 chunk "正常完成", 上层把失败当成功
                    close(exception ?: Exception("HTTP ${response?.code ?: "unknown"}"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close(terminationGuard.cleanEofCause())
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            eventSource.cancel()
        }
    }

    private fun logStreamEvent(type: String?, data: String) {
        if (!Log.isLoggable(TAG, Log.VERBOSE)) return
        Log.v(TAG, "onEvent: type=$type chars=${data.length}")
    }

}
