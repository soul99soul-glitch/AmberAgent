package me.rerere.ai.provider.providers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import me.rerere.ai.ui.UIMessage
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
        require(providerSetting.authMode == OpenAIAuthMode.API_KEY) {
            "Codex OAuth does not support balance lookup. Use an OpenAI API Key provider for balance."
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
    ): Flow<MessageChunk> = if (providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH || providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting.codexOAuthSettingIfNeeded(),
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH || providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting.codexOAuthSettingIfNeeded(),
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting.authMode == OpenAIAuthMode.API_KEY) {
            "Codex OAuth does not support embeddings. Use an OpenAI API Key provider for embeddings."
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
        require(providerSetting.authMode == OpenAIAuthMode.API_KEY) {
            "Codex OAuth does not support image generation. Use an OpenAI API Key provider for images."
        }

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

        ImageGenerationResult(items = items)
    }

    private suspend fun resolveBearerToken(
        providerSetting: ProviderSetting.OpenAI,
        forceRefresh: Boolean,
    ): String {
        return when (providerSetting.authMode) {
            OpenAIAuthMode.API_KEY -> keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
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

    private fun defaultCodexOAuthModels(): List<Model> {
        return CODEX_OAUTH_FALLBACK_MODEL_IDS.map { id ->
            Model(
                modelId = id,
                displayName = id,
                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id),
                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id),
                abilities = ModelRegistry.MODEL_ABILITIES.getData(id),
                contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id),
            )
        }
    }

    private companion object {
        val CODEX_OAUTH_FALLBACK_MODEL_IDS = listOf(
            "gpt-5.4",
            "gpt-5.3-codex",
            "gpt-5.3-codex-spark",
            "gpt-5.2",
            "gpt-5.1",
            "gpt-5.1-codex",
            "gpt-5.1-codex-max",
        )
    }
}

fun Model.isCodexOAuthReviewModel(): Boolean {
    return modelId.contains("review", ignoreCase = true) ||
        displayName.contains("review", ignoreCase = true)
}
