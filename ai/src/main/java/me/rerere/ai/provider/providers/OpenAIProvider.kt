package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OPENAI_CODEX_BACKEND_BASE_URL
import me.rerere.ai.provider.providers.openai.OPENAI_CODEX_CLIENT_VERSION
import me.rerere.ai.provider.providers.openai.OpenAICodexAuthStore
import me.rerere.ai.provider.providers.openai.OpenAICodexOAuthClient
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.addOpenAICodexBackendHeaders
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.MessageStreamAccumulator
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "OpenAIProvider"

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val oauthClient = context?.let { OpenAICodexOAuthClient(client, OpenAICodexAuthStore(it)) }

    private val chatCompletionsAPI = ChatCompletionsAPI(
        client = client,
        keyRoulette = keyRoulette,
        bearerResolver = ::resolveBearerToken
    )
    private val responseAPI = ResponseAPI(
        client = client,
        keyRoulette = keyRoulette,
        bearerResolver = ::resolveBearerToken
    )


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            if (providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH) {
                return@withContext listCodexModels(providerSetting)
            }

            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        // Only CODEX_OAUTH has no usable API key — its access token can't hit the
        // billing endpoints. Coding Plan modes (Zhipu / Kimi / MiMo) authenticate
        // identically to API_KEY (Bearer with user-pasted key), so let them through;
        // whether the brand's coding-plan host actually exposes /credits is the brand's
        // call to make at HTTP level, not ours to short-circuit here. Previously this
        // check required `== API_KEY` and emitted a "Codex OAuth does not support"
        // string even for Kimi/Zhipu/MiMo, confusing users who'd never picked Codex.
        require(providerSetting.authMode != OpenAIAuthMode.CODEX_OAUTH) {
            "Codex OAuth does not support balance lookup. Use an API Key provider for balance."
        }
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = flow {
        var emitted = false
        try {
            streamTextOnce(providerSetting, messages, params).collect {
                emitted = true
                emit(it)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (emitted) throw error
            val retryParams = params.withoutParamsRejectedByOpenAI(error) ?: throw error
            Log.w(TAG, "streamText rejected request params; retrying with compatible body", error)
            streamTextOnce(providerSetting, messages, retryParams).collect { emit(it) }
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = try {
        generateTextOnce(providerSetting, messages, params)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        handleGenerateTextRetry(providerSetting, messages, params, error)
    }

    private suspend fun streamTextOnce(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = when {
        providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH || providerSetting.useResponseApi -> responseAPI.streamText(
            providerSetting = providerSetting.codexOAuthSettingIfNeeded(),
            messages = messages,
            params = if (providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH) {
                params.withCodexResponsesCompatibility()
            } else {
                params
            },
        )

        else -> chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
        )
    }

    private suspend fun generateTextOnce(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = when {
        providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH -> generateCodexTextWithStreaming(
            providerSetting = providerSetting.codexOAuthSettingIfNeeded(),
            messages = messages,
            params = params.withCodexResponsesCompatibility(),
        )

        providerSetting.useResponseApi -> responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
        )

        else -> chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
        )
    }

    private suspend fun handleGenerateTextRetry(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        error: Throwable,
    ): MessageChunk {
        params.withoutParamsRejectedByOpenAI(error)?.let { retryParams ->
            Log.w(TAG, "generateText rejected request params; retrying with compatible body", error)
            return try {
                generateTextOnce(providerSetting, messages, retryParams)
            } catch (retryError: Throwable) {
                if (retryError is CancellationException) throw retryError
                if (providerSetting.shouldRetryGenerateTextWithStreaming(retryError)) {
                    Log.w(TAG, "generateText requires streaming; retrying with stream aggregation", retryError)
                    generateResponseTextWithStreaming(providerSetting.codexOAuthSettingIfNeeded(), messages, retryParams)
                } else {
                    throw retryError
                }
            }
        }
        if (providerSetting.shouldRetryGenerateTextWithStreaming(error)) {
            Log.w(TAG, "generateText requires streaming; retrying with stream aggregation", error)
            return generateResponseTextWithStreaming(
                providerSetting.codexOAuthSettingIfNeeded(),
                messages,
                params.withCodexResponsesCompatibility(),
            )
        }
        throw error
    }

    private fun TextGenerationParams.withoutParamsRejectedByOpenAI(error: Throwable): TextGenerationParams? {
        var retryParams = this
        if (error.isUnsupportedOpenAISamplingError()) {
            retryParams = retryParams.withoutSamplingParams()
        }
        if (error.isUnsupportedOpenAIOutputLimitError()) {
            retryParams = retryParams.withoutOutputLimitParams()
        }
        return retryParams.takeIf { it != this }
    }

    private fun TextGenerationParams.withCodexResponsesCompatibility(): TextGenerationParams =
        withoutSamplingParams().withoutOutputLimitParams()

    private fun TextGenerationParams.withoutOutputLimitParams(): TextGenerationParams =
        copy(
            maxTokens = null,
            customBody = customBody.filterNot { it.key.isOpenAIOutputLimitKey() },
        )

    private fun String.isOpenAIOutputLimitKey(): Boolean {
        val normalized = lowercase()
        return normalized == "max_output_tokens" ||
            normalized == "max_tokens" ||
            normalized == "max_completion_tokens"
    }

    private fun TextGenerationParams.withoutSamplingParams(): TextGenerationParams =
        copy(
            temperature = null,
            topP = null,
            customBody = customBody.filterNot { it.key.isOpenAISamplingKey() },
        )

    private fun String.isOpenAISamplingKey(): Boolean =
        equals("temperature", ignoreCase = true) || equals("top_p", ignoreCase = true)

    private fun Throwable.isUnsupportedOpenAISamplingError(): Boolean {
        val message = generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
            .lowercase()
        return message.isOpenAIUnsupportedParameterError() &&
            ("temperature" in message || "top_p" in message)
    }

    private fun Throwable.isUnsupportedOpenAIOutputLimitError(): Boolean {
        val message = generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
            .lowercase()
        return message.isOpenAIUnsupportedParameterError() &&
            ("max_output_tokens" in message || "max_tokens" in message || "max_completion_tokens" in message)
    }

    private fun String.isOpenAIUnsupportedParameterError(): Boolean =
        "unsupported parameter" in this ||
            "unknown parameter" in this ||
            "unrecognized request argument" in this ||
            "invalid parameter" in this ||
            "not support" in this

    private fun ProviderSetting.OpenAI.shouldRetryGenerateTextWithStreaming(error: Throwable): Boolean {
        if (authMode != OpenAIAuthMode.CODEX_OAUTH && !useResponseApi) return false
        return error.isOpenAIStreamRequiredError()
    }

    private fun Throwable.isOpenAIStreamRequiredError(): Boolean {
        val message = generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")
            .lowercase()
        return "stream must be set to true" in message ||
            ("stream" in message && "true" in message && "400" in message)
    }

    private suspend fun generateCodexTextWithStreaming(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = generateResponseTextWithStreaming(providerSetting, messages, params)

    private suspend fun generateResponseTextWithStreaming(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val accumulator = MessageStreamAccumulator(
            initialMessages = listOf(UIMessage.assistant("")),
            model = params.model,
        )
        var chunkId = ""
        var modelId = params.model.modelId
        var finishReason: String? = null
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = if (providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH) {
                params.withCodexResponsesCompatibility()
            } else {
                params
            },
        ).collect { chunk ->
            if (chunk.id.isNotBlank()) chunkId = chunk.id
            if (chunk.model.isNotBlank()) modelId = chunk.model
            finishReason = chunk.choices.firstOrNull()?.finishReason ?: finishReason
            accumulator.append(chunk)
        }
        val message = accumulator.snapshot().lastOrNull() ?: UIMessage.assistant("")
        return MessageChunk(
            id = chunkId,
            model = modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = message,
                    finishReason = finishReason,
                )
            ),
            usage = message.usage,
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting.authMode == OpenAIAuthMode.API_KEY) {
            unsupportedAuthModeMessage(providerSetting.authMode, capability = "embeddings")
        }
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        when (providerSetting.authMode) {
            OpenAIAuthMode.API_KEY -> generateImageViaImagesEndpoint(providerSetting, params)
            // Codex OAuth tokens cannot hit /v1/images/generations directly, but the
            // Codex backend exposes image generation as a built-in tool on its
            // Responses API. Route the call there so ChatGPT Plus / Pro subscribers
            // can produce images through their existing OAuth login without
            // separately paying for an API key. Mirrors openai-oauth / ima2-gen.
            OpenAIAuthMode.CODEX_OAUTH -> generateImageViaCodexResponses(providerSetting, params)
            else -> error(unsupportedAuthModeMessage(providerSetting.authMode, capability = "image generation"))
        }
    }

    private suspend fun generateImageViaImagesEndpoint(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
    ): ImageGenerationResult {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                put(
                    "size", when (params.aspectRatio) {
                        ImageAspectRatio.SQUARE -> "1024x1024"
                        ImageAspectRatio.LANDSCAPE -> "1536x1024"
                        ImageAspectRatio.PORTRAIT -> "1024x1536"
                    }
                )
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")

        val items = data.map { imageJson ->
            val imageObj = imageJson.jsonObject
            val b64Json = imageObj["b64_json"]?.jsonPrimitive?.contentOrNull
                ?: error("No b64_json in response")

            ImageGenerationItem(
                data = b64Json,
                mimeType = "image/png"
            )
        }

        return ImageGenerationResult(items = items)
    }

    /**
     * Codex Responses-API image generation. The backend does not accept `n`>1
     * inside the `image_generation` tool — one image per request — so we call
     * the endpoint sequentially when the caller asks for multiple variants.
     *
     * The `model` field in the request body is a Codex *routing* hint, not the
     * actual image model. We pin it to a known-accepted Codex chat model
     * ([CODEX_IMAGE_ROUTING_MODEL]); the backend then dispatches to its
     * underlying image model via the `image_generation` tool. The
     * `params.model.modelId` the caller passes (e.g. our synthesized
     * "codex-oauth-image" placeholder) is ignored on the wire.
     */
    private suspend fun generateImageViaCodexResponses(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
    ): ImageGenerationResult {
        val count = params.numOfImages.coerceIn(1, 4)
        val items = mutableListOf<ImageGenerationItem>()
        repeat(count) {
            val item = generateOneCodexImage(providerSetting, params) ?: return@repeat
            items.add(item)
        }
        if (items.isEmpty()) {
            error("Codex image generation returned no images")
        }
        return ImageGenerationResult(items = items)
    }

    private suspend fun generateOneCodexImage(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
    ): ImageGenerationItem? {
        val sizeStr = when (params.aspectRatio) {
            ImageAspectRatio.SQUARE -> "1024x1024"
            ImageAspectRatio.LANDSCAPE -> "1536x1024"
            ImageAspectRatio.PORTRAIT -> "1024x1536"
        }
        val requestBody = buildJsonObject {
            put("model", CODEX_IMAGE_ROUTING_MODEL)
            // Proxies (and the Codex backend) reject Responses requests without
            // these fields, so set them explicitly even though they are empty
            // / boolean defaults — see openai-oauth/core/transport.ts.
            put("instructions", "")
            put("store", false)
            put("stream", true)
            put("tool_choice", "required")
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "image_generation")
                    put("size", sizeStr)
                    put("quality", "high")
                    put("moderation", "low")
                })
            }
            putJsonArray("input") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", params.prompt)
                        })
                    }
                })
            }
        }.mergeCustomBody(params.customBody)

        // [Review fix] Build a fresh request after any token refresh so the
        // ChatGPT-Account-Id header reflects the post-refresh tokens. The
        // initial request gets the current snapshot; on 401 we rebuild
        // from scratch with whatever oauthClient has after forceRefresh.
        // Also use `.header(name, ...)` (replaces) instead of `.addHeader`
        // for Accept because `addOpenAICodexBackendHeaders` already sets
        // Accept: application/json — without replacing, OkHttp would emit
        // both Accept headers and the server may pick the wrong one.
        fun buildRequest(token: String): Request {
            val tokens = oauthClient?.getCached(providerSetting.id)
            return Request.Builder()
                .url("$OPENAI_CODEX_BACKEND_BASE_URL/responses")
                .headers(params.customHeaders.toHeaders())
                .addHeader("Authorization", "Bearer $token")
                .addOpenAICodexBackendHeaders(tokens)
                .header("Accept", "text/event-stream")
                .header("OpenAI-Beta", "responses=experimental")
                .header("Content-Type", "application/json")
                .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
        }

        var response = client.newCall(buildRequest(resolveBearerToken(providerSetting, false))).await()
        if (response.code == 401) {
            response.close()
            val retryToken = resolveBearerToken(providerSetting, forceRefresh = true)
            response = client.newCall(buildRequest(retryToken)).await()
        }
        if (!response.isSuccessful) {
            error("Codex image generation failed: ${response.code} ${response.body?.string()}")
        }

        response.use { activeResponse ->
            val source = activeResponse.body.source()
            var eventType: String? = null
            val dataLines = mutableListOf<String>()
            var captured: ImageGenerationItem? = null

            fun drainEvent() {
                if (dataLines.isEmpty()) return
                val data = dataLines.joinToString("\n").also { dataLines.clear() }
                if (data == "[DONE]") return
                val eventJson = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                if (eventType == "response.output_item.done") {
                    val item = eventJson["item"]?.jsonObject ?: return
                    if (item["type"]?.jsonPrimitive?.contentOrNull == "image_generation_call") {
                        val result = item["result"]?.jsonPrimitive?.contentOrNull
                        if (!result.isNullOrBlank() && captured == null) {
                            captured = ImageGenerationItem(data = result, mimeType = "image/png")
                        }
                    }
                }
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        drainEvent()
                        if (eventType == "response.completed") break
                        eventType = null
                    }
                    line.startsWith("event:") -> {
                        eventType = line.removePrefix("event:").trim()
                    }
                    line.startsWith("data:") -> {
                        dataLines += line.removePrefix("data:").trimStart()
                    }
                }
            }
            drainEvent()
            return captured
        }
    }

    private suspend fun resolveBearerToken(
        providerSetting: ProviderSetting.OpenAI,
        forceRefresh: Boolean,
    ): String {
        return when (providerSetting.authMode) {
            // Coding Plan modes (Zhipu / Kimi / MiMo) authenticate exactly like API_KEY:
            // user pastes a key, we send it as Bearer. The only difference vs vanilla API_KEY
            // is the pinned base URL (handled at the settings layer when the user picks the
            // mode). No special token negotiation here.
            OpenAIAuthMode.API_KEY,
            OpenAIAuthMode.ZHIPU_CODING_PLAN,
            OpenAIAuthMode.KIMI_CODING_PLAN,
            OpenAIAuthMode.MIMO_CODING_PLAN ->
                keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            OpenAIAuthMode.CODEX_OAUTH -> oauthClient
                ?.getValidAccessToken(providerSetting.id, forceRefresh)
                ?: error("Codex OAuth requires an Android context-backed auth store.")
        }
    }

    private fun ProviderSetting.OpenAI.codexOAuthSettingIfNeeded(): ProviderSetting.OpenAI {
        return if (authMode == OpenAIAuthMode.CODEX_OAUTH) {
            copy(
                baseUrl = OPENAI_CODEX_BACKEND_BASE_URL,
                useResponseApi = true,
            )
        } else {
            this
        }
    }

    private suspend fun listCodexModels(providerSetting: ProviderSetting.OpenAI): List<Model> {
        // Wrap the entire fetch in runCatching so token errors / network errors / OAuth client
        // not yet ready don't propagate as exceptions to the caller. The caller (ModelList in
        // SettingProviderDetailPage) only handles the HTTP-failure return path; an uncaught
        // exception leaves modelList = emptyList and the "可用模型" sheet shows blank with
        // no recovery path. Falling back to the bundled defaults guarantees the user can
        // always pick something.
        return runCatching { fetchCodexModelsOrThrow(providerSetting) }
            .getOrElse { defaultCodexOAuthModels() }
    }

    private suspend fun fetchCodexModelsOrThrow(providerSetting: ProviderSetting.OpenAI): List<Model> {
        val token = resolveBearerToken(providerSetting, forceRefresh = false)
        val tokens = oauthClient?.getCached(providerSetting.id)
        val request = Request.Builder()
            .url("$OPENAI_CODEX_BACKEND_BASE_URL/models?client_version=$OPENAI_CODEX_CLIENT_VERSION")
            .addOpenAICodexBackendHeaders(tokens)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        var response = client.newCall(request).await()
        if (response.code == 401) {
            response.close()
            val retryToken = resolveBearerToken(providerSetting, forceRefresh = true)
            response = request.newBuilder()
                .header("Authorization", "Bearer $retryToken")
                .build()
                .let { client.newCall(it).await() }
        }
        if (!response.isSuccessful) {
            return defaultCodexOAuthModels()
        }

        val bodyStr = response.body?.string() ?: ""
        val root = runCatching { json.parseToJsonElement(bodyStr) }.getOrNull()
            ?: return defaultCodexOAuthModels()
        val data = root.findModelArray() ?: return defaultCodexOAuthModels()
        val models = data.mapNotNull { modelJson ->
            modelJson.toCodexModel()
        }.filterNot { it.isCodexOAuthReviewModel() }
        return models.ifEmpty { defaultCodexOAuthModels() }
    }

    private fun JsonElement.findModelArray(): JsonArray? {
        return runCatching { jsonObject["data"]?.jsonArray }.getOrNull()
            ?: runCatching { jsonObject["models"]?.jsonArray }.getOrNull()
            ?: runCatching { jsonArray }.getOrNull()
    }

    private fun JsonElement.toCodexModel(): Model? {
        val id = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
            ?: runCatching {
                val obj = jsonObject
                obj["id"]?.jsonPrimitive?.contentOrNull
                    ?: obj["slug"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            ?: return null
        return Model(
            modelId = id,
            displayName = id,
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(id),
            contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id),
        )
    }

    private fun defaultCodexOAuthModels(): List<Model> = defaultCodexOAuthModelList()
}

/**
 * Routing model name we send in the `model` field of the Codex Responses-API
 * image generation request. The Codex backend uses this as a router hint; the
 * actual image is produced by the `image_generation` tool's underlying model
 * (gpt-image-2 family today). We pin a known-accepted Codex chat model so the
 * request validates server-side regardless of what placeholder modelId the
 * caller picked. Pinned to `gpt-5.4` (the newest non-codex-suffix entry in
 * the chat fallback list above).
 */
internal const val CODEX_IMAGE_ROUTING_MODEL = "gpt-5.4"

/**
 * Synthetic model ID exposed in the Codex OAuth provider's model list with
 * `type = IMAGE`. Users see this as a pickable "image model" in the assistant
 * settings; under the hood [OpenAIProvider.generateImage] detects CODEX_OAUTH
 * auth mode and routes to the Responses API, ignoring this id on the wire.
 */
internal const val CODEX_OAUTH_IMAGE_MODEL_ID = "codex-oauth-image"

/**
 * Bundled fallback model list for OpenAI Codex OAuth. Public so the provider settings UI can
 * write it into `provider.models` whenever a live `listModels` call returns nothing or throws —
 * without this, an early failure in the OAuth login path leaves `provider.models` empty and the
 * "available models" sheet shows blank with no obvious recovery.
 *
 * Includes one synthetic [CODEX_OAUTH_IMAGE_MODEL_ID] entry with `type = IMAGE`
 * so users can pick "Codex Image" in the new assistant `生图模型` field. Generation
 * goes through the Responses API + `image_generation` tool — no API key needed,
 * the call bills against the user's ChatGPT subscription.
 */
fun defaultCodexOAuthModelList(): List<Model> {
    val chatModels = OPENAI_CODEX_OAUTH_FALLBACK_MODEL_IDS.map { id ->
        Model(
            modelId = id,
            displayName = id,
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(id),
            contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id),
        )
    }
    val imageModel = Model(
        modelId = CODEX_OAUTH_IMAGE_MODEL_ID,
        displayName = "Codex Image (ChatGPT Plus/Pro)",
        type = me.rerere.ai.provider.ModelType.IMAGE,
        inputModalities = listOf(me.rerere.ai.provider.Modality.TEXT),
        outputModalities = listOf(me.rerere.ai.provider.Modality.IMAGE),
    )
    return chatModels + imageModel
}

private val OPENAI_CODEX_OAUTH_FALLBACK_MODEL_IDS = listOf(
    "gpt-5.4",
    "gpt-5.3-codex",
    "gpt-5.3-codex-spark",
    "gpt-5.2",
    "gpt-5.1",
    "gpt-5.1-codex",
    "gpt-5.1-codex-max",
)

fun Model.isCodexOAuthReviewModel(): Boolean {
    return modelId.contains("review", ignoreCase = true) ||
        displayName.contains("review", ignoreCase = true)
}

/**
 * Build the "this auth mode does not support X" error string with the *actual* mode label
 * resolved from the provider's `authMode`, instead of hardcoding "Codex OAuth" for every
 * non-API_KEY case. Previously every such error said "Codex OAuth does not support …"
 * even when the user was on a Kimi / Zhipu / MiMo Coding Plan, which made the failure
 * read like a Codex-specific limitation.
 */
internal fun unsupportedAuthModeMessage(authMode: OpenAIAuthMode, capability: String): String {
    val modeLabel = when (authMode) {
        OpenAIAuthMode.CODEX_OAUTH -> "Codex OAuth"
        OpenAIAuthMode.ZHIPU_CODING_PLAN,
        OpenAIAuthMode.KIMI_CODING_PLAN,
        OpenAIAuthMode.MIMO_CODING_PLAN -> "Coding Plan"
        // Unreachable: every caller of this helper guards on `authMode == API_KEY` first;
        // if we ever get here it's a bug, so emit a recognizable label rather than swallow it.
        OpenAIAuthMode.API_KEY -> "API Key"
    }
    return "$modeLabel does not support $capability. Use an API Key provider for $capability."
}
