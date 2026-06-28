package app.amber.ai.provider.openai

import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.TokenUsage
import app.amber.ai.provider.BuiltInTools
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.Provider
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.PartGroup
import app.amber.ai.provider.providers.groupPartsByToolBoundary
import app.amber.ai.registry.ModelRegistry
import app.amber.ai.util.parseErrorDetail
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.STREAM_TOOL_INDEX_METADATA_KEY
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import io.ktor.client.HttpClient
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
import kotlin.uuid.Uuid

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
    private val sseClient by lazy { HttpClient { } }
    private val httpClient by lazy { HttpClient { } }

    // The Provider.listModels contract is NOT annotated @Throws, so a thrown error
    // here would NOT bridge to Swift and would abort the process (SIGABRT) on iOS
    // (Kotlin/Native), instead of surfacing as a catchable error. Swallow failures
    // and return an empty list so callers degrade gracefully. This matters for codex
    // providers, whose apiKey is empty (the bearer is the OAuth token, injected only
    // for chat requests), so a `/models` call here always 401s.
    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> {
        return runCatching {
            val url = "${providerSetting.baseUrl}/models"
            val response = httpClient.get(url) {
                header("Authorization", "Bearer ${providerSetting.apiKey}")
            }
            if (!response.status.isSuccess()) return@runCatching emptyList()
            val bodyJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = bodyJson["data"]?.arr() ?: return@runCatching emptyList()
            data.mapNotNull { modelJson ->
                val modelObj = modelJson.obj() ?: return@mapNotNull null
                val id = modelObj.str("id") ?: return@mapNotNull null
                Model(modelId = id, displayName = id)
            }
        }.getOrDefault(emptyList())
    }

    // @Throws is REQUIRED for the Swift-facing suspend boundary: without it, a
    // thrown exception (HTTP error, parse failure) is treated by Kotlin/Native as
    // an UNHANDLED exception and aborts the whole process (SIGABRT) instead of
    // bridging to Swift as a catchable error. The iOS deep-read / sub-agent paths
    // call this via `try await`; the annotation lets their do/catch actually run.
    @Throws(Throwable::class)
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        if (providerSetting.useResponseApi) {
            return responsesGenerateText(providerSetting, messages, params)
        }
        val requestBody = buildChatCompletionRequest(providerSetting, messages, params, stream = false)
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
        if (providerSetting.useResponseApi) {
            return responsesStreamText(providerSetting, messages, params)
        }
        val requestBody = buildChatCompletionRequest(providerSetting, messages, params, stream = true)
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
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false,
    ): JsonObject = buildJsonObject {
        val host = hostOf(providerSetting.baseUrl)
        val isMiMo = isMiMoProvider(providerSetting, host, params.model.modelId)
        put("model", params.model.modelId)
        put("messages", buildMessages(messages))

        if (params.temperature != null) put("temperature", params.temperature)
        if (params.topP != null) put("top_p", params.topP)
        if (params.maxTokens != null) put(if (isMiMo) "max_completion_tokens" else "max_tokens", params.maxTokens)

        put("stream", stream)
        if (stream) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }

        if (params.model.abilities.contains(ModelAbility.REASONING)) {
            val level = params.reasoningLevel
            when {
                isMiMo -> {
                    put("thinking", buildJsonObject {
                        put("type", if (level.isEnabled) "enabled" else "disabled")
                    })
                }

                host == "openrouter.ai" -> {
                    put("reasoning", buildJsonObject {
                        when (level) {
                            ReasoningLevel.OFF -> put("effort", "none")
                            ReasoningLevel.AUTO -> put("enabled", true)
                            else -> put("effort", level.effort)
                        }
                    })
                }

                host == "dashscope.aliyuncs.com" -> {
                    put("enable_thinking", level.isEnabled)
                    if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                }

                host == "ark.cn-beijing.volces.com" -> {
                    put("thinking", buildJsonObject {
                        put("type", if (level.isEnabled) "enabled" else "disabled")
                    })
                }

                host == "api.mistral.ai" -> {
                    // Mistral does not accept an OpenAI-style reasoning field.
                }

                host == "chat.intern-ai.org.cn" -> {
                    put("thinking_mode", level.isEnabled)
                }

                host == "api.siliconflow.cn" -> {
                    if (params.model.modelId in siliconFlowThinkingModels) {
                        put("enable_thinking", level.isEnabled)
                    }
                }

                host == "open.bigmodel.cn" || host == "api.moonshot.cn" -> {
                    put("thinking", buildJsonObject {
                        put("type", if (level.isEnabled) "enabled" else "disabled")
                    })
                }

                host == "api.deepseek.com" -> {
                    put("thinking", buildJsonObject {
                        put("type", if (level.isEnabled) "enabled" else "disabled")
                    })
                    if (level.isEnabled && level != ReasoningLevel.AUTO) {
                        put("reasoning_effort", level.effort)
                    }
                }

                else -> {
                    if (level != ReasoningLevel.AUTO) {
                        put("reasoning_effort", openAIChatCompletionsReasoningEffort(level))
                    }
                }
            }
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
        val uploadable = messages.filter { it.isValidToUpload() }
        val lastUserIndex = uploadable.indexOfLast { it.role == MessageRole.USER }
        uploadable.forEachIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT) {
                // reasoning_content 只在「最后一条用户消息之后的当前轮」回传;历史里的思考不回传。
                // 否则带思考 + tool_calls 的 assistant 消息被重发时,严格网关(mimo / DeepSeek 系)
                // 会把请求体里不该出现的 reasoning_content 判为非法 → HTTP 500。对齐 Android 的 gating。
                addAssistantMessages(message, includeReasoning = index > lastUserIndex)
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(message: UIMessage, includeReasoning: Boolean) {
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
                    // 防御:并行工具调用偶发 toolCallId 为空(流式分片未带 id 时),会让续写请求里
                    // tool_calls 的 id 与后续 tool 结果的 tool_call_id 全空/撞车,服务端据此返回 500。
                    // 为空 id 合成稳定唯一 id;同一个 id 同时用于 tool_call 与其结果,确保严格配对。
                    val resolved = group.tools.mapIndexed { i, tool ->
                        tool to tool.toolCallId.ifBlank { "call_${tool.streamIndex ?: i}_$i" }
                    }
                    buildAssistantMessageJson(contentBuffer, resolved, reasoningPart, includeReasoning)?.let { add(it) }
                    contentBuffer.clear()
                    reasoningPart = null
                    resolved.forEach { (tool, id) ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", id)
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
            buildAssistantMessageJson(contentBuffer, emptyList(), reasoningPart, includeReasoning)?.let { add(it) }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<Pair<UIMessagePart.Tool, String>>,
        reasoningPart: UIMessagePart.Reasoning?,
        includeReasoning: Boolean,
    ): JsonObject? {
        val hasText = contentParts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        val hasReasoning = includeReasoning && !reasoningPart?.reasoning.isNullOrBlank()
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
                    tools.forEach { (tool, id) ->
                        add(buildJsonObject {
                            put("id", id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                // 空/缺失参数补 "{}":arguments 必须是合法 JSON 字符串,空串会被网关判为非法。
                                put("arguments", tool.input.ifBlank { "{}" })
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
            // Images: the iOS composer encodes each attachment as a `data:` URL
            // (or passes an http(s) URL); OpenAI accepts both directly.
            val images = message.parts.filterIsInstance<UIMessagePart.Image>()
                .mapNotNull { it.imageUrlBlock() }
            when {
                message.role == MessageRole.SYSTEM && texts.isNotEmpty() ->
                    put("content", texts.joinToString("\n\n") { it.text })
                images.isNotEmpty() -> putJsonArray("content") {
                    texts.forEach { add(buildJsonObject { put("type", "text"); put("text", it.text) }) }
                    images.forEach { add(it) }
                }
                texts.size == 1 -> put("content", texts.first().text)
                texts.isEmpty() -> put("content", "")
                else -> putJsonArray("content") {
                    texts.forEach { add(buildJsonObject { put("type", "text"); put("text", it.text) }) }
                }
            }
        })
    }

    /** OpenAI image_url block from a `data:` base64 URL or an http(s) URL; null otherwise. */
    private fun UIMessagePart.Image.imageUrlBlock(): JsonObject? {
        if (!(url.startsWith("data:") || url.startsWith("http://") || url.startsWith("https://"))) return null
        return buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", url) })
        }
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

    // ========================================================================
    // OpenAI Responses API (`/responses`) support.
    //
    // Faithful, engine-agnostic port of the Android-only `ResponseAPI`
    // (`:ai` module). Routed when `providerSetting.useResponseApi == true`;
    // the `/chat/completions` path above is untouched. Platform deps removed:
    //  - host parsing uses a pure-Kotlin extractor (no java.net.URL)
    //  - all android.util.Log calls dropped
    //  - image_generation is omitted (no commonMain base64 encoder / image
    //    output type) — function_call / function_call_output tool calling is
    //    fully supported because the agent needs tools.
    // ========================================================================

    @Throws(Throwable::class)
    private suspend fun responsesGenerateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val requestBody = buildResponsesRequestBody(providerSetting, messages, params, stream = false)
        val url = "${providerSetting.baseUrl}/responses"
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${providerSetting.apiKey}")
            params.customHeaders.filter { it.name.isNotBlank() }.forEach { header(it.name, it.value) }
            setBody(json.encodeToString(requestBody))
        }
        if (!response.status.isSuccess()) {
            throw Exception("OpenAI Responses request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        val bodyJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return parseResponseOutput(bodyJson)
    }

    private fun responsesStreamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        val streamAssistantId = Uuid.random()
        val requestBody = buildResponsesRequestBody(providerSetting, messages, params, stream = true)
        val url = "${providerSetting.baseUrl}/responses"
        return sseClient.sseFlow(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${providerSetting.apiKey}")
            params.customHeaders.filter { it.name.isNotBlank() }.forEach { header(it.name, it.value) }
            setBody(json.encodeToString(requestBody))
        }.mapNotNull { event ->
            when (event) {
                is SseEvent.Event -> {
                    val payloads = normalizeOpenAIStreamDataLines(event.data)
                    payloads.mapNotNull { payload ->
                        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }
                            .getOrNull() ?: return@mapNotNull null
                        obj["error"]?.let { throw it.parseErrorDetail() }
                        parseResponseDelta(obj)?.normalizeResponseStreamAssistant(streamAssistantId)
                    }.lastOrNull()
                }

                is SseEvent.Failure -> throw event.throwable ?: Exception("Stream failed")
                is SseEvent.Open, is SseEvent.Closed -> null
            }
        }
    }

    private fun MessageChunk.normalizeResponseStreamAssistant(streamAssistantId: Uuid): MessageChunk = copy(
        choices = choices.map { choice ->
            val delta = choice.delta
            val message = choice.message
            when {
                delta != null && delta.role == MessageRole.ASSISTANT ->
                    choice.copy(delta = delta.copy(id = streamAssistantId))

                delta == null && message != null && message.role == MessageRole.ASSISTANT ->
                    choice.copy(
                        delta = UIMessage(
                            id = streamAssistantId,
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                        ),
                        message = null,
                    )

                else -> choice
            }
        },
    )

    // ---- Responses request building (port of buildRequestBody) ----

    private fun buildResponsesRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): JsonObject {
        val capabilities = resolveResponseProviderCapabilities(hostOf(providerSetting.baseUrl))
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (responsesIsModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            if (systemMessage != null) {
                put(
                    "instructions",
                    systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text },
                )
            }

            // messages
            put("input", buildResponsesMessages(messages))

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING) &&
                params.reasoningLevel != ReasoningLevel.OFF
            ) {
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    openAIResponsesReasoningEffort(params.reasoningLevel)?.let { effort ->
                        put("effort", effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray { add("reasoning.encrypted_content") })
                }
            }

            val toolDefinitions = buildJsonArray {
                if (params.model.abilities.contains(ModelAbility.TOOL)) {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters()?.let { schema ->
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(InputSchema.serializer(), schema),
                                )
                            }
                        })
                    }
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> add(buildJsonObject { put("type", "web_search") })
                        BuiltInTools.UrlContext -> {} // not supported
                        BuiltInTools.ImageGeneration -> {} // omitted: no commonMain image output path
                    }
                }
            }
            if (toolDefinitions.isNotEmpty()) {
                put("tools", toolDefinitions)
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun buildResponsesMessages(messages: List<UIMessage>): JsonArray = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addResponsesAssistantItems(message)
                } else {
                    addResponsesUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addResponsesAssistantItems(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addResponsesContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    part.metadata?.get("reasoning_id")?.responseContentOrNull()?.let {
                                        put("id", it)
                                    }
                                    put("summary", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", part.reasoning)
                                        })
                                    })
                                    part.metadata?.get("encrypted_content")?.responseContentOrNull()
                                        ?.let { put("encrypted_content", it) }
                                })
                            }

                            is UIMessagePart.Text -> contentBuffer.add(part)

                            // Image / image_generation_call replay is omitted on the
                            // Responses port (no commonMain image output path). Treat any
                            // image part as a no-op so visible text is preserved.
                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    if (contentBuffer.isNotEmpty()) {
                        addResponsesContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            put("arguments", tool.input)
                        })
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            put(
                                "output",
                                tool.output.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("\n") { it.text },
                            )
                        })
                    }
                }
            }
        }

        if (contentBuffer.isNotEmpty()) {
            addResponsesContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addResponsesUserItems(message: UIMessage) {
        val contentParts = message.parts.filter {
            it is UIMessagePart.Text || it is UIMessagePart.Image
        }
        if (contentParts.isNotEmpty()) {
            addResponsesContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addResponsesContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return
        val texts = parts.filterIsInstance<UIMessagePart.Text>()
        val images = parts.filterIsInstance<UIMessagePart.Image>()

        add(buildJsonObject {
            put("role", role.name.lowercase())
            if (images.isEmpty() && texts.size == 1) {
                put("content", texts.first().text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> add(buildJsonObject {
                                put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                put("text", part.text)
                            })

                            is UIMessagePart.Image -> {
                                // Android re-encodes to base64 via a platform helper; commonMain has
                                // none. The iOS composer already passes a `data:`/http(s) URL, which
                                // the Responses `input_image` accepts directly, so forward `url` as-is.
                                if (role == MessageRole.USER && part.url.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", part.url)
                                    })
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    // ---- Responses stream/output parsing (port of parseResponseDelta etc.) ----

    private fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
        val chunkType = jsonObject.str("type") ?: return null

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject.str("item_id") ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(jsonObject.str("delta") ?: ""),
                            message = null,
                            finishReason = null,
                        ),
                    ),
                )
            }

            "response.output_text.done" -> {
                return MessageChunk(
                    id = jsonObject.str("item_id") ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = null,
                            message = UIMessage.assistant(jsonObject.str("text") ?: ""),
                            finishReason = null,
                        ),
                    ),
                )
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return MessageChunk(
                    id = jsonObject.str("item_id") ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject.str("delta") ?: "",
                                        finishedAt = null,
                                    ),
                                ),
                            ),
                            message = null,
                            finishReason = null,
                        ),
                    ),
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.obj() ?: return null
                val type = item.str("type") ?: return null
                val id = item.str("id") ?: return null
                when (type) {
                    "function_call" -> return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Tool(
                                            toolCallId = id,
                                            toolName = item.str("name") ?: "",
                                            input = item.str("arguments") ?: "",
                                            output = emptyList(),
                                        ),
                                    ),
                                ),
                                finishReason = null,
                            ),
                        ),
                    )

                    "reasoning" -> {
                        val encryptedContent = item.str("encrypted_content")
                        return MessageChunk(
                            id = id,
                            model = "",
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    message = null,
                                    delta = UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listOf(
                                            UIMessagePart.Reasoning(
                                                reasoning = "",
                                                finishedAt = null,
                                            ).also {
                                                it.metadata = buildJsonObject {
                                                    put("encrypted_content", encryptedContent)
                                                    put("reasoning_id", id)
                                                }
                                            },
                                        ),
                                    ),
                                    finishReason = null,
                                ),
                            ),
                        )
                    }
                    // image_generation_call: omitted (no commonMain image output path).
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.obj() ?: return null
                val type = item.str("type") ?: return null
                val id = item.str("id") ?: return null
                when (type) {
                    "reasoning" -> {
                        val encryptedContent = item.str("encrypted_content")
                        return MessageChunk(
                            id = id,
                            model = "",
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    message = null,
                                    delta = UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listOf(
                                            UIMessagePart.Reasoning(reasoning = "").also {
                                                it.metadata = buildJsonObject {
                                                    put("encrypted_content", encryptedContent)
                                                    put("reasoning_id", id)
                                                }
                                            },
                                        ),
                                    ),
                                    finishReason = null,
                                ),
                            ),
                        )
                    }

                    "message" -> return parseResponsesDoneMessageItem(item, id)
                    // image_generation_call: omitted (no commonMain image output path).
                }
            }

            "response.function_call_arguments.done" -> {
                val toolCallId = jsonObject.str("item_id") ?: return null
                val arguments = jsonObject.str("arguments") ?: return null
                return MessageChunk(
                    id = toolCallId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = arguments,
                                        output = emptyList(),
                                    ),
                                ),
                            ),
                            message = null,
                            finishReason = null,
                        ),
                    ),
                )
            }

            "response.completed" -> {
                val response = jsonObject["response"]?.obj()
                if (response != null) {
                    return parseResponseOutput(response)
                }
                return MessageChunk(
                    id = jsonObject.str("item_id") ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = null,
                )
            }
        }

        return null
    }

    private fun parseResponsesDoneMessageItem(item: JsonObject, id: String): MessageChunk? {
        val text = item.extractResponsesMessageOutputText()
        if (text.isEmpty()) return null
        return MessageChunk(
            id = id,
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage.assistant(text),
                    finishReason = null,
                ),
            ),
        )
    }

    private fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        val outputs = jsonObject["output"]?.arr() ?: emptyList()
        val parts = mutableListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.obj() ?: return@forEach
            when (output.str("type")) {
                "reasoning" -> {
                    (output["summary"]?.arr() ?: emptyList()).mapNotNull { it.obj() }.forEach { part ->
                        if (part.str("type") == "summary_text") {
                            part.str("text")?.let { text ->
                                parts.add(UIMessagePart.Reasoning(reasoning = text))
                            }
                        }
                    }
                }

                "function_call" -> {
                    val callId = output.str("call_id") ?: return@forEach
                    val name = output.str("name") ?: return@forEach
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = output.str("arguments") ?: "",
                            output = emptyList(),
                        ),
                    )
                }

                "message" -> {
                    output.extractResponsesMessageOutputText()
                        .takeIf { it.isNotEmpty() }
                        ?.let { text -> parts.add(UIMessagePart.Text(text = text)) }
                    (output["content"]?.arr() ?: emptyList())
                        .mapNotNull { it.obj() }
                        .mapNotNull { it.str("refusal") }
                        .filter { it.isNotBlank() }
                        .forEach { refusal -> parts.add(UIMessagePart.Text(text = refusal)) }
                }

                "output_text" -> {
                    output.str("text")?.takeIf { it.isNotEmpty() }
                        ?.let { text -> parts.add(UIMessagePart.Text(text = text)) }
                }

                "refusal" -> {
                    output.str("refusal")?.takeIf { it.isNotBlank() }
                        ?.let { refusal -> parts.add(UIMessagePart.Text(text = refusal)) }
                }

                // image_generation_call: omitted (no commonMain image output path).
                else -> return@forEach
            }
        }

        return MessageChunk(
            id = jsonObject.str("id") ?: "",
            model = jsonObject.str("model") ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                    finishReason = jsonObject.responsesFinishReason(),
                    delta = null,
                ),
            ),
            usage = parseResponsesTokenUsage(jsonObject["usage"]?.obj()),
        )
    }

    private fun JsonObject.extractResponsesMessageOutputText(): String =
        (this["content"]?.arr() ?: emptyList())
            .mapNotNull { part ->
                val partObject = part.obj() ?: return@mapNotNull null
                when (partObject.str("type")) {
                    "output_text", "text" -> partObject.str("text")
                    else -> null
                }
            }
            .joinToString("")

    private fun JsonObject.responsesFinishReason(): String? {
        val status = this.str("status")
        val incompleteReason = this["incomplete_details"]?.obj()?.str("reason")
        return incompleteReason ?: status?.takeIf { it != "completed" }
    }

    private fun parseResponsesTokenUsage(obj: JsonObject?): TokenUsage? {
        if (obj == null) return null
        return TokenUsage(
            promptTokens = obj.int("input_tokens") ?: 0,
            completionTokens = obj.int("output_tokens") ?: 0,
            totalTokens = obj.int("total_tokens") ?: 0,
            cachedTokens = obj["input_tokens_details"]?.obj()?.int("cached_tokens") ?: 0,
        )
    }

    /** Pure-Kotlin replacement for the Android `java.net.URL(...).host`. */
    private fun hostOf(baseUrl: String): String {
        val afterScheme = baseUrl.substringAfter("://", baseUrl)
        val authority = afterScheme.substringBefore('/')
        return authority.substringBefore('?').substringBefore('#')
            .substringAfter('@')      // strip userinfo
            .substringBefore(':')     // strip port
            .lowercase()
    }

    private fun responsesIsModelAllowTemperature(model: Model): Boolean {
        val modelId = model.modelId.lowercase()
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) &&
            !ModelRegistry.GPT_5.match(model.modelId) &&
            !modelId.startsWith("gpt-5") &&
            !modelId.contains("codex")
    }

    private fun isMiMoProvider(
        providerSetting: ProviderSetting.OpenAI,
        host: String,
        modelId: String,
    ): Boolean {
        val lowerModelId = modelId.lowercase()
        return providerSetting.brand == OpenAIBrand.MIMO ||
            providerSetting.authMode == OpenAIAuthMode.MIMO_CODING_PLAN ||
            host.endsWith("xiaomimimo.com") ||
            lowerModelId.contains("mimo")
    }

    private fun openAIChatCompletionsReasoningEffort(level: ReasoningLevel): String = when (level) {
        ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.AUTO,
        ReasoningLevel.HIGH,
        ReasoningLevel.XHIGH,
        ReasoningLevel.MAX -> "high"
    }

    private fun openAIResponsesReasoningEffort(level: ReasoningLevel): String? = when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
    }

    private data class ResponseProviderCapabilities(
        val supportsReasoningSummary: Boolean = true,
        val supportEncryptedContent: Boolean = true,
    )

    private fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities =
        when (host) {
            "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
                supportsReasoningSummary = false,
                supportEncryptedContent = false,
            )

            else -> ResponseProviderCapabilities()
        }

    private val siliconFlowThinkingModels = setOf(
        "Pro/moonshotai/Kimi-K2.5",
        "Pro/zai-org/GLM-5",
        "Pro/zai-org/GLM-5.1",
        "Pro/zai-org/GLM-4.7",
        "deepseek-ai/DeepSeek-V3.2",
        "Pro/deepseek-ai/DeepSeek-V3.2",
        "Qwen/Qwen3.5-397B-A17B",
        "Qwen/Qwen3.5-122B-A10B",
        "Qwen/Qwen3.5-35B-A3B",
        "Qwen/Qwen3.5-27B",
        "Qwen/Qwen3.5-9B",
        "Qwen/Qwen3.5-4B",
        "zai-org/GLM-4.6",
        "Qwen/Qwen3-8B",
        "Qwen/Qwen3-14B",
        "Qwen/Qwen3-32B",
        "Qwen/Qwen3-30B-A3B",
        "tencent/Hunyuan-A13B-Instruct",
        "zai-org/GLM-4.5V",
        "deepseek-ai/DeepSeek-V3.1-Terminus",
        "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
        "deepseek-ai/DeepSeek-V4-Flash",
        "Pro/deepseek-ai/DeepSeek-V4-Flash",
        "deepseek-ai/DeepSeek-V4-Pro",
        "Pro/deepseek-ai/DeepSeek-V4-Pro",
    )

    private fun JsonElement?.responseContentOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
