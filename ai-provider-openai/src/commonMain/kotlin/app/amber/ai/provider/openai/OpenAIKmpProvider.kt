package app.amber.ai.provider.openai

import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.TokenUsage
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.Provider
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.PartGroup
import app.amber.ai.provider.providers.groupPartsByToolBoundary
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.STREAM_TOOL_INDEX_METADATA_KEY
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * KMP OpenAI-compatible chat provider. Implements [Provider] for
 * [ProviderSetting.OpenAI], supporting text streaming / generation and model
 * listing over the `/chat/completions` and `/models` endpoints.
 *
 * Mirrors the JSON shaping/parsing logic of the Android-only
 * `ChatCompletionsAPI` (`:ai` module) but is engine-agnostic (no `java.net`,
 * no OkHttp, no Android-only utils) so it compiles and runs on iOS via the
 * Ktor Darwin engine. Host-specific sampling/reasoning quirks are intentionally
 * omitted — this is a baseline OpenAI-compatible implementation.
 */
class OpenAIKmpProvider : Provider<ProviderSetting.OpenAI> {
    private val json = Json { ignoreUnknownKeys = true }

    // Engine is resolved per-platform at runtime (Darwin on iOS, JVM default on JVM).
    private val sseClient by lazy { HttpClient { install(SSE) } }
    private val httpClient by lazy { HttpClient { } }

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> {
        val url = "${providerSetting.baseUrl}/models"
        val response = httpClient.get(url) {
            header("Authorization", "Bearer ${providerSetting.apiKey}")
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("OpenAI listModels failed: ${response.status.value} $errorBody")
        }
        val bodyStr = response.bodyAsText()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.arr() ?: return emptyList()
        return data.mapNotNull { modelJson ->
            val modelObj = modelJson.obj() ?: return@mapNotNull null
            val id = modelObj.str("id") ?: return@mapNotNull null
            Model(modelId = id, displayName = id)
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val requestBody = buildChatCompletionRequest(messages, params, stream = false)
        val url = "${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            configureAuth(providerSetting, params)
            setBody(json.encodeToString(requestBody))
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw Exception("OpenAI request failed: ${response.status.value} $errorBody")
        }
        val bodyJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val id = bodyJson.str("id").orEmpty()
        val model = bodyJson.str("model").orEmpty()
        val choice = bodyJson["choices"]?.arr()?.firstOrNull()?.obj()
            ?: error("choices is null")
        val message = choice["message"]?.obj() ?: throw Exception("message is null")
        val finishReason = choice.str("finish_reason") ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"]?.obj())
        return MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(message),
                    finishReason = finishReason,
                )
            ),
            usage = usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        val requestBody = buildChatCompletionRequest(messages, params, stream = true)
        val url = "${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}"
        return sseClient.sseFlow(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            configureAuth(providerSetting, params)
            setBody(json.encodeToString(requestBody))
        }.mapNotNull { event ->
            when (event) {
                is SseEvent.Event -> {
                    val payloads = normalizeOpenAIStreamDataLines(event.data)
                    payloads.mapNotNull { parseStreamChunk(it) }.lastOrNull()
                }

                is SseEvent.Failure -> throw event.throwable ?: Exception("Stream failed")
                is SseEvent.Open, is SseEvent.Closed -> null
            }
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult = error("Image generation is not supported by OpenAIKmpProvider")

    /**
     * Swift-friendly streaming entry point that returns a cancellable [Job].
     *
     * Swift holds the returned [Job] and calls `job.cancel()` when the user stops
     * generation. This properly propagates cancellation through the Kotlin
     * coroutine → Flow → Ktor SSE → HTTP connection, unlike the
     * `Flow.collect(collector:)` bridge which does not propagate Swift Task
     * cancellation to the Kotlin side.
     *
     * [onChunk] is called sequentially from a background dispatcher.
     * [onComplete] is called on normal stream completion.
     * [onError] is called if [streamText] or collection throws a non-cancellation error.
     * Neither [onComplete] nor [onError] is called on cancellation — Swift initiated
     * the cancel and should handle state transition itself.
     */
    fun streamTextCancellable(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        onChunk: (MessageChunk) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit,
    ): Job {
        val scope = CoroutineScope(Dispatchers.Default)
        return scope.launch {
            try {
                val flow = streamText(providerSetting, messages, params)
                flow.collect { chunk ->
                    onChunk(chunk)
                }
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    // ---- request building ----

    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", params.model.modelId)
        put("messages", buildMessages(messages))

        if (params.temperature != null) put("temperature", params.temperature)
        if (params.topP != null) put("top_p", params.topP)
        if (params.maxTokens != null) put("max_tokens", params.maxTokens)

        put("stream", stream)
        if (stream) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }

        if (params.tools.isNotEmpty()) {
            putJsonArray("tools") {
                params.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters()?.let { schema ->
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(InputSchema.serializer(), schema)
                                )
                            }
                        })
                    })
                }
            }
        }
    }.mergeCustomBody(params.customBody)

    private fun buildMessages(messages: List<UIMessage>): JsonArray = buildJsonArray {
        messages.filter { it.isValidToUpload() }.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(message)
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()
                        ?.let { reasoningPart = it }
                    group.parts.filterIsInstance<UIMessagePart.Text>().forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    buildAssistantMessageJson(contentBuffer, group.tools, reasoningPart)?.let { add(it) }
                    contentBuffer.clear()
                    reasoningPart = null
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", tool.toolCallId)
                            put(
                                "content",
                                tool.output.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("\n") { it.text }
                            )
                        })
                    }
                }
            }
        }

        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(contentBuffer, emptyList(), reasoningPart)?.let { add(it) }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?,
    ): JsonObject? {
        val hasText = contentParts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        if (!hasText && !hasReasoning && tools.isEmpty()) return null

        return buildJsonObject {
            put("role", "assistant")

            if (hasReasoning) {
                put("reasoning_content", reasoningPart?.reasoning.orEmpty())
            }

            val texts = contentParts.filterIsInstance<UIMessagePart.Text>()
            when {
                texts.isEmpty() -> put("content", "")
                texts.size == 1 -> put("content", texts.first().text)
                else -> putJsonArray("content") {
                    texts.forEach { add(buildJsonObject { put("type", "text"); put("text", it.text) }) }
                }
            }

            if (tools.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                put("arguments", tool.input)
                            })
                        })
                    }
                }
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            val texts = message.parts.filterIsInstance<UIMessagePart.Text>()
            if (message.role == MessageRole.SYSTEM && texts.isNotEmpty()) {
                put("content", texts.joinToString("\n\n") { it.text })
            } else if (texts.size == 1) {
                put("content", texts.first().text)
            } else if (texts.isEmpty()) {
                put("content", "")
            } else {
                putJsonArray("content") {
                    texts.forEach { add(buildJsonObject { put("type", "text"); put("text", it.text) }) }
                }
            }
        })
    }

    // ---- response parsing ----

    private fun parseStreamChunk(payload: String): MessageChunk? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        if (obj["error"] != null) {
            throw Exception("OpenAI stream error: ${obj["error"]}")
        }
        val id = obj.str("id").orEmpty()
        val model = obj.str("model").orEmpty()
        val choicesArr = obj["choices"]?.arr() ?: emptyList()
        val choiceList = buildList {
            if (choicesArr.isNotEmpty()) {
                val choice = choicesArr.first().obj() ?: return@buildList
                val message = choice["delta"]?.obj() ?: choice["message"]?.obj()
                if (message != null) {
                    val finishReason = choice.str("finish_reason") ?: "unknown"
                    add(UIMessageChoice(0, parseMessage(message), null, finishReason))
                }
            }
        }
        val usage = parseTokenUsage(obj["usage"]?.obj())
        return MessageChunk(id, model, choiceList, usage)
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(jsonObject.str("role")?.uppercase() ?: "ASSISTANT")
        val content = jsonObject.str("content") ?: ""
        val reasoning = jsonObject.str("reasoning_content") ?: jsonObject.str("reasoning")
        val hasReasoningContent = jsonObject.containsKey("reasoning_content")
        val toolCalls = jsonObject["tool_calls"]?.arr() ?: emptyList()

        return UIMessage(
            role = role,
            parts = buildList {
                if (hasReasoningContent || !reasoning.isNullOrEmpty()) {
                    add(UIMessagePart.Reasoning(reasoning = reasoning.orEmpty(), finishedAt = null))
                }
                toolCalls.forEach { tc ->
                    val tcObj = tc.obj() ?: return@forEach
                    val toolCallIndex = tcObj.int("index")
                    val toolCallId = tcObj.str("id")
                    val fn = tcObj["function"]?.obj()
                    val toolName = fn?.str("name")
                    val arguments = fn?.str("arguments")
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList(),
                            streamIndex = toolCallIndex,
                            metadata = toolCallIndex?.let {
                                buildJsonObject { put(STREAM_TOOL_INDEX_METADATA_KEY, it) }
                            },
                        )
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
            },
            annotations = parseAnnotations(jsonObject["annotations"]?.arr() ?: emptyList()),
        )
    }

    private fun parseAnnotations(jsonArray: List<JsonElement>): List<UIMessageAnnotation> =
        jsonArray.mapNotNull { element ->
            val obj = element.obj() ?: return@mapNotNull null
            when (obj.str("type")) {
                "url_citation" -> {
                    val citation = obj["url_citation"]?.obj()
                    UIMessageAnnotation.UrlCitation(
                        title = citation?.str("title") ?: "",
                        url = citation?.str("url") ?: "",
                    )
                }

                else -> null
            }
        }

    private fun parseTokenUsage(obj: JsonObject?): TokenUsage? {
        if (obj == null) return null
        val promptCacheHit = obj.int("prompt_cache_hit_tokens")
        val promptCacheMiss = obj.int("prompt_cache_miss_tokens")
        val promptTokens = obj.int("prompt_tokens")
            ?: listOfNotNull(promptCacheHit, promptCacheMiss).takeIf { it.isNotEmpty() }?.sum()
            ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = obj.int("completion_tokens") ?: 0,
            totalTokens = obj.int("total_tokens") ?: 0,
            cachedTokens = obj["prompt_tokens_details"]?.obj()?.int("cached_tokens")
                ?: promptCacheHit ?: 0,
        )
    }

    private fun normalizeOpenAIStreamDataLines(data: String): List<String> =
        data.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.withoutNestedSseDataPrefix() }
            .filter { it.isNotBlank() && it != "[DONE]" }
            .toList()

    private fun String.withoutNestedSseDataPrefix(): String {
        var value = trimStart()
        while (value.startsWith("data:")) {
            value = value.removePrefix("data:").trimStart()
        }
        return value
    }

    private fun JsonObject.mergeCustomBody(customBody: List<CustomBody>): JsonObject {
        if (customBody.isEmpty()) return this
        return JsonObject(toMutableMap().apply { customBody.forEach { put(it.key, it.value) } })
    }

    private fun HttpRequestBuilder.configureAuth(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
    ) {
        header("Authorization", "Bearer ${providerSetting.apiKey}")
        params.customHeaders.filter { it.name.isNotBlank() }.forEach { header(it.name, it.value) }
    }

    // ---- safe JsonElement accessors (avoid a dependency on :common helpers) ----

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
}
