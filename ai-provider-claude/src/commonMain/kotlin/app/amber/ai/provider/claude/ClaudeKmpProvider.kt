package app.amber.ai.provider.claude

import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_CONTROL_METADATA
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_DISABLED
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_EPHEMERAL
import app.amber.ai.core.TokenUsage
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.Provider
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.PartGroup
import app.amber.ai.provider.providers.groupPartsByToolBoundary
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.parseErrorDetail
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
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
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock

private const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * KMP Anthropic/Claude chat provider. Implements [Provider] for
 * [ProviderSetting.Claude], supporting text streaming / generation and model
 * listing over the `/messages` (SSE) and `/models` endpoints.
 *
 * Mirrors the JSON shaping/parsing logic of the Android-only `ClaudeProvider`
 * (`:ai` module) but is engine-agnostic (no `java.net`, no OkHttp, no
 * Android-only utils) so it compiles and runs on iOS via the Ktor Darwin engine.
 *
 * Differences vs the Android original (intentional, KMP baseline):
 *  - No multi-key rotation (KeyRoulette): uses `providerSetting.apiKey` directly,
 *    matching the OpenAI KMP provider convention.
 *  - No image content blocks yet: `UIMessagePart.Image` -> null (skipped), same
 *    baseline as the OpenAI KMP provider. Can be added later when a KMP base64
 *    helper lands.
 *  - Host-specific referer headers (aihubmix/openrouter) are preserved.
 */
class ClaudeKmpProvider : Provider<ProviderSetting.Claude> {
    private val json = Json { ignoreUnknownKeys = true }

    // Engine is resolved per-platform at runtime (Darwin on iOS, JVM default on JVM).
    private val sseClient by lazy { HttpClient { install(SSE) } }
    private val httpClient by lazy { HttpClient { } }

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> {
        val response = httpClient.get("${providerSetting.baseUrl}/models") {
            header("x-api-key", providerSetting.apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
        }
        if (!response.status.isSuccess()) {
            error("Claude listModels failed: ${response.status.value} ${response.bodyAsText()}")
        }
        val bodyJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { modelJson ->
            val modelObj = modelJson.jsonObject
            val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id
            Model(modelId = id, displayName = displayName)
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val requestBody = buildMessageRequest(providerSetting, messages, params)
        val response = httpClient.post("${providerSetting.baseUrl}/messages") {
            params.customHeaders.filter { it.name.isNotBlank() }.forEach {
                header(it.name, it.value)
            }
            contentType(ContentType.Application.Json)
            header("x-api-key", providerSetting.apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            configureReferHeaders(providerSetting.baseUrl) { name, value ->
                header(name, value)
            }
            setBody(json.encodeToString(requestBody))
        }
        if (!response.status.isSuccess()) {
            throw Exception("Claude request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        val bodyJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = parseTokenUsage(bodyJson)
        return MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(content),
                    finishReason = stopReason,
                ),
            ),
            usage = usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        val requestBody = buildMessageRequest(providerSetting, messages, params, stream = true)
        val apiKey = providerSetting.apiKey
        val baseUrl = providerSetting.baseUrl

        return sseClient.sseFlow("$baseUrl/messages") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            params.customHeaders.filter { it.name.isNotBlank() }.forEach {
                header(it.name, it.value)
            }
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            configureReferHeaders(baseUrl) { name, value ->
                header(name, value)
            }
            setBody(json.encodeToString(requestBody))
        }.transform { event ->
            when (event) {
                is SseEvent.Open -> Unit
                is SseEvent.Event -> handleStreamEvent(event.id, event.type, event.data)
                is SseEvent.Closed -> Unit
                is SseEvent.Failure -> throw parseFailureException(event)
            }
        }
    }

    /**
     * Swift-friendly cancellable streaming wrapper, mirroring the OpenAI KMP
     * provider's surface so iOS can consume both providers identically.
     *
     * [onChunk] is called sequentially from a background dispatcher.
     * [onComplete] is called on normal stream completion.
     * [onError] is called if [streamText] or collection throws a non-cancellation error.
     * Neither [onComplete] nor [onError] is called on cancellation — Swift initiated
     * the cancel and should handle state transition itself.
     */
    fun streamTextCancellable(
        providerSetting: ProviderSetting.Claude,
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

    private fun parseFailureException(event: SseEvent.Failure): Throwable {
        val throwable = event.throwable
        // Ktor sseFlow surfaces ClientRequestException/ServerResponseException whose
        // message is shaped "Client request POST url failed with status 4xx: <body>".
        // Try to parse the body portion as a provider error for a friendlier message.
        val message = throwable?.message.orEmpty()
        try {
            val bodyStart = message.indexOf(": ", message.indexOf("status"))
            if (bodyStart >= 0) {
                val bodyRaw = message.substring(bodyStart + 2).trim()
                if (bodyRaw.isNotBlank()) {
                    val bodyElement = Json.parseToJsonElement(bodyRaw)
                    val parsed = bodyElement.parseErrorDetail()
                    if (parsed != null) return parsed
                }
            }
        } catch (_: Throwable) {
            // fall through to the original throwable
        }
        return throwable ?: Exception("SSE connection failed")
    }

    private suspend fun FlowCollector<MessageChunk>.handleStreamEvent(
        id: String?,
        type: String?,
        data: String,
    ) {
        if (data == "[DONE]") return
        val dataJson = json.parseToJsonElement(data).jsonObject
        when (type) {
            "message_stop" -> return
            "error" -> {
                val error = dataJson["error"]?.parseErrorDetail()
                throw error ?: Exception("Stream error event: $data")
            }
        }

        val deltaMessage = parseMessage(buildJsonArray {
            val contentBlockObj = dataJson["content_block"]?.jsonObject
            val deltaObj = dataJson["delta"]?.jsonObject
            if (contentBlockObj != null) {
                add(contentBlockObj)
            }
            if (deltaObj != null) {
                add(deltaObj)
            }
        })
        val tokenUsage = parseTokenUsage(dataJson)
        if (deltaMessage.parts.isEmpty() && tokenUsage == null) return

        val messageChunk = MessageChunk(
            id = id ?: "",
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = deltaMessage,
                    message = null,
                    finishReason = null,
                ),
            ),
            usage = tokenUsage,
        )
        emit(messageChunk)
    }

    private fun configureReferHeaders(
        url: String,
        addHeader: (name: String, value: String) -> Unit,
    ) {
        // Ktor Url parsing (replaces java.net.URL from the Android original).
        val host = runCatching { io.ktor.http.URLBuilder(url).host }.getOrNull() ?: return
        when (host) {
            "aihubmix.com" -> addHeader("APP-Code", "DKHA9468")
            "openrouter.ai" -> {
                addHeader("X-Title", "AmberAgent")
                addHeader("HTTP-Referer", "https://github.com")
            }
        }
    }

    // ---- request building ----
    // Internal (not private) so JVM tests can assert the JSON shape without HTTP.
    internal fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false,
    ): JsonObject {
        fun cacheControlEphemeral() = buildJsonObject { put("type", "ephemeral") }

        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages, providerSetting.promptCaching))
            put("max_tokens", params.maxTokens ?: 64_000)

            if (params.temperature != null && !params.reasoningLevel.isEnabled) {
                put("temperature", params.temperature)
            }
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val systemTextParts = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
            if (systemTextParts.isNotEmpty()) {
                val cacheDisabled = systemTextParts.any { part ->
                    part.metadata?.get(SYSTEM_PROMPT_CACHE_CONTROL_METADATA)?.jsonPrimitive?.contentOrNull == SYSTEM_PROMPT_CACHE_DISABLED
                }
                val explicitCacheIndex = systemTextParts.indexOfLast { part ->
                    part.metadata?.get(SYSTEM_PROMPT_CACHE_CONTROL_METADATA)?.jsonPrimitive?.contentOrNull == SYSTEM_PROMPT_CACHE_EPHEMERAL
                }
                val cacheIndex = explicitCacheIndex.takeIf { it >= 0 }
                put("system", buildJsonArray {
                    systemTextParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                            if (providerSetting.promptCaching && !cacheDisabled && index == cacheIndex) {
                                put("cache_control", cacheControlEphemeral())
                            }
                        })
                    }
                })
            }

            // thinking (Anthropic adaptive mode + output_config.effort)
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                when (params.reasoningLevel) {
                    ReasoningLevel.OFF -> {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    }

                    ReasoningLevel.AUTO -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                    }

                    else -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                        put("output_config", buildJsonObject {
                            put("effort", params.reasoningLevel.effort)
                        })
                    }
                }
            }

            // tools
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEachIndexed { index, tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters()?.let { schema ->
                                put("input_schema", json.encodeToJsonElement(InputSchema.serializer(), schema))
                            }
                            if (providerSetting.promptCaching && index == params.tools.lastIndex) {
                                put("cache_control", cacheControlEphemeral())
                            }
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun buildMessages(messages: List<UIMessage>, promptCaching: Boolean) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantMessage(message)
                } else {
                    addUserMessage(message)
                }
            }
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray)
    }

    /**
     * Insert cache_control on the last content block of the second-to-last
     * non-tool_result user message (matches the Android original).
     */
    private fun insertMessagesCacheControl(messages: JsonArray): JsonArray {
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else return messages

        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(block.jsonObject + mapOf("cache_control" to buildJsonObject {
                            put("type", "ephemeral")
                        }))
                    } else block
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else msg
        })
    }

    private fun JsonArrayBuilder.addAssistantMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull { it.toContentBlock() }.forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock()) }
                        }
                    })
                }
            }
        }

        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.mapNotNull { it.toContentBlock() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toContentBlock(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Reasoning -> buildJsonObject {
            put("type", "thinking")
            put("thinking", reasoning)
            metadata?.forEach { (key, value) -> put(key, value) }
        }

        // Image support deferred until a KMP base64 helper exists (baseline parity
        // with the OpenAI KMP provider, which also omits image content).
        else -> null
    }

    private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

    private fun UIMessagePart.Tool.toToolResultBlock() = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        putJsonArray("content") {
            output.mapNotNull { it.toContentBlock() }.forEach { add(it) }
        }
    }

    // ---- response parsing ----

    private fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()

        content.forEach { contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty()) {
                        parts.add(UIMessagePart.Text(text))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null) {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = thinking,
                            createdAt = Clock.System.now(),
                            finishedAt = null,
                        )
                        if (signature != null) {
                            reasoning.metadata = buildJsonObject {
                                put("signature", signature)
                            }
                        }
                        parts.add(reasoning)
                    }
                }

                "redacted_thinking" -> Unit

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList(),
                        ),
                    )
                }

                "input_json_delta" -> {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = "",
                            toolName = "",
                            input = input ?: "",
                            output = emptyList(),
                        ),
                    )
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
        )
    }

    private fun parseTokenUsage(bodyJson: JsonObject?): TokenUsage? {
        if (bodyJson == null) return null
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }

    private fun JsonObject.mergeCustomBody(customBody: List<CustomBody>): JsonObject {
        if (customBody.isEmpty()) return this
        return JsonObject(toMutableMap().apply { customBody.forEach { put(it.key, it.value) } })
    }
}
