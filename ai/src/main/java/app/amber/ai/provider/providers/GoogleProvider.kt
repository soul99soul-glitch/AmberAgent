package app.amber.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ImageModelGateway
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.vertex.ServiceAccountTokenProvider
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStatus
import app.amber.ai.ui.ImageAspectRatio
import app.amber.ai.ui.ImageGenerationItem
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.util.KeyRoulette
import app.amber.ai.util.configureReferHeaders
import app.amber.ai.util.json
import app.amber.ai.util.mergeCustomBody
import app.amber.ai.util.stringSafe
import app.amber.ai.util.toHeaders
import app.amber.common.http.await
import app.amber.common.http.jsonArrayOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils

private const val TAG = "GoogleProvider"

class GoogleProvider(
    private val client: OkHttpClient,
    context: Context? = null,
) : TextModelGateway<ProviderSetting.Google>, ImageModelGateway<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val contentAdapter = GeminiGenerateContentAdapter()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }
    // Same shape as OpenAIProvider holds its codex oauthClient: lazily construct from
    // the injected Context (the DI module registers its own singleton too; they share
    // the underlying SharedPreferences-backed store so token state is consistent).
    private val geminiOAuthClient: app.amber.ai.provider.providers.google.GoogleGeminiOAuthClient? =
        context?.let {
            app.amber.ai.provider.providers.google.GoogleGeminiOAuthClient(
                client,
                app.amber.ai.provider.providers.google.GoogleGeminiAuthStore(it),
            )
        }

    private fun isCodeAssistOAuthMode(providerSetting: ProviderSetting.Google): Boolean =
        providerSetting.authMode == app.amber.ai.provider.GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH

    /** Network-free auth state used by provider_config_status. */
    fun oauthAuthStatus(providerSetting: ProviderSetting.Google): GoogleGeminiAuthStatus? {
        if (!isCodeAssistOAuthMode(providerSetting)) return null
        return geminiOAuthClient?.authStatus(providerSetting.id)
            ?: GoogleGeminiAuthStatus.clientUnavailable()
    }

    /** Resolve the (accessToken, projectId) pair needed to send a v1internal request to
     *  cloudcode-pa. Lazily onboards the user on first chat (writes projectId into the
     *  stored tokens) so the user doesn't need a separate "click to onboard" step. */
    private suspend fun resolveCodeAssistSession(
        providerSetting: ProviderSetting.Google,
    ): Pair<String, String> {
        val client = geminiOAuthClient
            ?: error("Gemini OAuth 客户端未初始化（GoogleProvider 缺少 Context 注入）。")
        val session = client.requireUsableSession(providerSetting.id)
        val projectId = session.projectId
            ?: error("cloudcode-pa onboarding 没有返回 cloudaicompanionProject，请检查 Google 账号权限。")
        return session.accessToken to projectId
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        val endpoint = when {
            !providerSetting.vertexAI -> providerSetting.baseUrl.trimEnd('/') + "/" + path
            providerSetting.useServiceAccount -> listOf(
                "https://aiplatform.googleapis.com/v1/projects",
                providerSetting.projectId,
                "locations",
                providerSetting.location,
                path,
            ).joinToString("/")
            else -> "https://aiplatform.googleapis.com/v1/$path"
        }
        return endpoint.toHttpUrl()
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        val builder = request.newBuilder()
        if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            return builder.header("Authorization", "Bearer $accessToken").build()
        }
        val apiKey = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        if (providerSetting.vertexAI) {
            builder.url(request.url.newBuilder().addQueryParameter("key", apiKey).build())
        } else {
            builder.header("x-goog-api-key", apiKey)
        }
        return builder.build()
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            // OAuth path has no public listModels — cloudcode-pa.googleapis.com exposes
            // streamGenerateContent / generateContent / loadCodeAssist / onboardUser /
            // countTokens / retrieveUserQuota but no model enumeration. Return the
            // hardcoded fallback set so the "fetch models" button has something to
            // refresh into without 404-ing. See [defaultGeminiOAuthModelList] docs.
            if (isCodeAssistOAuthMode(providerSetting)) {
                geminiOAuthClient
                    ?.requireUsableSession(providerSetting.id)
                    ?: error("Gemini OAuth 客户端未初始化（GoogleProvider 缺少 Context 注入）。")
                return@withContext app.amber.ai.provider.providers.google.defaultGeminiOAuthModelList()
            }
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: error("empty body")
                Log.d(TAG, "listModels: $body")
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型
                    val supportedGenerationMethods =
                        modelObject["supportedGenerationMethods"]?.jsonArrayOrNull
                            ?.map { method -> method.jsonPrimitive.content }
                            ?: return@mapNotNull null
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNull null
                    }

                    val name = modelObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val displayName = modelObject["displayName"]?.jsonPrimitive?.contentOrNull ?: name.substringAfter("/")

                    Model(
                        modelId = name.substringAfter("/"),
                        displayName = displayName,
                        type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                    )
                }
            } else {
                // 401/403（key 错误）等失败必须抛出让设置页显示"鉴权失败"，
                // 而不是静默返回空列表；同时读关 body 让连接归还连接池
                val errorBody = response.body?.string().orEmpty()
                throw Exception("List models failed: HTTP ${response.code} ${errorBody.take(300)}")
            }
        }

    override suspend fun complete(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val isOAuth = isCodeAssistOAuthMode(providerSetting)
        val requestBody = contentAdapter.encodeRequest(messages, params, codeAssistTransport = isOAuth)
        val request = if (isOAuth) {
            val (accessToken, projectId) = resolveCodeAssistSession(providerSetting)
            geminiOAuthClient!!
                .generateContent(accessToken, params.model.modelId, projectId, requestBody)
                .newBuilder()
                .apply {
                    params.customHeaders.forEach { (k, v) -> addHeader(k, v) }
                }
                .build()
        } else {
            val url = buildUrl(
                providerSetting = providerSetting,
                path = if (providerSetting.vertexAI) {
                    "publishers/google/models/${params.model.modelId}:generateContent"
                } else {
                    "models/${params.model.modelId}:generateContent"
                }
            )
            transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .headers(params.customHeaders.toHeaders())
                    .post(
                        json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                    )
                    .configureReferHeaders(providerSetting.baseUrl)
                    .build()
            )
        }

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyJson = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        contentAdapter.decodeCompletion(bodyJson, params.model.modelId)
    }

    override suspend fun stream(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        // OAuth path: cloudcode-pa.googleapis.com/v1internal:streamGenerateContent. The
        // standard Gemini request body is wrapped in {model, project, request}; the
        // server's SSE chunks come back wrapped in {"response": {...standard chunk...}}.
        // Auth is `Authorization: Bearer <access_token>` instead of `x-goog-api-key`.
        val isOAuth = isCodeAssistOAuthMode(providerSetting)
        val requestBody = contentAdapter.encodeRequest(messages, params, codeAssistTransport = isOAuth)
        val request = if (isOAuth) {
            val (accessToken, projectId) = resolveCodeAssistSession(providerSetting)
            geminiOAuthClient!!
                .streamGenerateContent(accessToken, params.model.modelId, projectId, requestBody)
                .newBuilder()
                .apply {
                    // CRITICAL: use addHeader, not headers(). The latter REPLACES the
                    // entire header set including the Authorization Bearer we just put
                    // in streamGenerateContent — server then returns
                    // "Request is missing required authentication credential".
                    params.customHeaders.forEach { (k, v) -> addHeader(k, v) }
                }
                .build()
        } else {
            val url = buildUrl(
                providerSetting = providerSetting,
                path = if (providerSetting.vertexAI) {
                    "publishers/google/models/${params.model.modelId}:streamGenerateContent"
                } else {
                    "models/${params.model.modelId}:streamGenerateContent"
                }
            ).newBuilder().addQueryParameter("alt", "sse").build()
            transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .headers(params.customHeaders.toHeaders())
                    .post(
                        json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                    )
                    .configureReferHeaders(providerSetting.baseUrl)
                    .build()
            )
        }

        Log.i(TAG, "streamText: model=${params.model.modelId}")

        val terminationGuard = StreamTerminationGuard(StreamProtocol.GOOGLE)

        val streamDecoder = contentAdapter.streamDecoder(params.model.modelId)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: type=$type chars=${data.length}")

                try {
                    when (val signal = streamDecoder.decode(data)) {
                        is GeminiStreamSignal.Emit -> {
                            signal.finishReasons.forEach(terminationGuard::observeFinishReason)
                            if (trySendBlocking(signal.chunk).isFailure) eventSource.cancel()
                        }

                        is GeminiStreamSignal.Failure -> {
                            eventSource.cancel()
                            close(signal.cause)
                        }

                        GeminiStreamSignal.Ignore -> Unit
                    }
                } catch (e: Exception) {
                    // malformed event 不能只打日志吞掉: 否则表现为 silent empty assistant
                    // 或流卡死, 用户看不到任何错误
                    Log.w(TAG, "onEvent: failed to parse event chars=${data.length}", e)
                    eventSource.cancel()
                    close(e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                t?.printStackTrace()
                Log.w(TAG, "onFailure: ${t?.message}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            Log.d(TAG, "onFailure body: $bodyElement")
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "onClosed")
                close(terminationGuard.cleanEofCause())
            }
        }

        val eventSource = EventSources.createFactory(client)
                .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "awaitClose: cancel eventSource")
            eventSource.cancel()
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting.Google,
        params: ImageGenerationParams
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(!isCodeAssistOAuthMode(providerSetting)) {
            "Gemini Code Assist OAuth image generation is not supported by this transport."
        }

        val requestBody = buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject {
                    put("prompt", params.prompt)
                })
            }
            putJsonObject("parameters") {
                put("sampleCount", params.numOfImages)
                put(
                    "aspectRatio", when (params.aspectRatio) {
                        ImageAspectRatio.SQUARE -> "1:1"
                        ImageAspectRatio.LANDSCAPE -> "16:9"
                        ImageAspectRatio.PORTRAIT -> "9:16"
                    }
                )
            }
        }.mergeCustomBody(params.customBody)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:predict"
            } else {
                "models/${params.model.modelId}:predict"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val predictions = bodyJson["predictions"]?.jsonArray ?: error("No predictions in response")

        val items = predictions.mapNotNull { prediction ->
            val predictionObj = prediction.jsonObject
            val bytesBase64Encoded = predictionObj["bytesBase64Encoded"]?.jsonPrimitive?.contentOrNull

            if (bytesBase64Encoded != null) {
                ImageGenerationItem(
                    data = bytesBase64Encoded,
                    mimeType = "image/png"
                )
            } else null
        }

        ImageGenerationResult(items = items)
    }
}
