package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ResponseAPI"

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default(),
    private val bearerResolver: suspend (ProviderSetting.OpenAI, Boolean) -> String = { providerSetting, _ ->
        keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
    },
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${bearerResolver(providerSetting, false)}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        var response = client.newCall(request).await()
        if (response.code == 401) {
            response.close()
            val retryRequest = request.newBuilder()
                .header("Authorization", "Bearer ${bearerResolver(providerSetting, true)}")
                .build()
            response = client.newCall(retryRequest).await()
        }
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        Log.i(TAG, "generateText: $bodyStr")
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val output = parseResponseOutput(bodyJson)

        return output
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        // V3 fix: ResponseAPI 在 delta / done / completed 各事件构造的 UIMessage 都用 fresh
        // Uuid.random(). MessageStreamAccumulator.replaceActive 会把 active 整个换掉 (parts
        // 全替换为 message.parts), 导致两个问题:
        //   1) 新 id → ChatService merge by-id 找不到 → APPEND orphan node → 用户看到双
        //      message + 多行 action button
        //   2) replaceActive 把 acc 累积的 parts 重置 → 内容"闪一下消失", 只剩 action row
        // 解法 (2 步):
        //   - id 标准化: 所有 emit chunk 的 ASSISTANT id 都用流 scope sharedId
        //   - 阻止 replaceActive: 把 done/completed 的 message=...,delta=null 转成空 delta,
        //     accumulator 走 append 路径 (no-op 因为 parts 是空), 保留已累积内容
        val streamAssistantId = kotlin.uuid.Uuid.random()
        fun MessageChunk.normalizeAssistantId(): MessageChunk = copy(
            choices = choices.map { choice ->
                val delta = choice.delta
                val msg = choice.message
                when {
                    delta != null && delta.role == MessageRole.ASSISTANT ->
                        choice.copy(delta = delta.copy(id = streamAssistantId))
                    // ASSISTANT "done" / "completed" event (delta=null, message=完整文本):
                    // 转成空 delta (parts=emptyList), 阻止 replaceActive 重置 active.
                    // delta 累积已含完整文本, message 是冗余 finish marker.
                    delta == null && msg != null && msg.role == MessageRole.ASSISTANT ->
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
            }
        )
        if (providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH) {
            streamCodexText(providerSetting, messages, params).collect { chunk ->
                trySend(chunk.normalizeAssistantId())
            }
            close()
            return@callbackFlow
        }

        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${bearerResolver(providerSetting, false)}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                Log.d(TAG, "onEvent: $id/$type $data")
                val json = json.parseToJsonElement(data).jsonObject
                val chunk = parseResponseDelta(json)
                if (chunk != null) {
                    trySend(chunk.normalizeAssistantId())
                }
                if (type == "response.completed") {
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        println(bodyElement)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
    }

    private fun streamCodexText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${bearerResolver(providerSetting, false)}")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamCodexText: ${json.encodeToString(requestBody)}")

        var response = client.newCall(request).await()
        if (response.code == 401) {
            response.close()
            val retryRequest = request.newBuilder()
                .header("Authorization", "Bearer ${bearerResolver(providerSetting, true)}")
                .build()
            response = client.newCall(retryRequest).await()
        }
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body.stringSafe()}")
        }

        response.use { activeResponse ->
            val source = activeResponse.body.source()
            var eventType: String? = null
            val dataLines = mutableListOf<String>()

            fun drainEvent(): MessageChunk? {
                if (dataLines.isEmpty()) return null
                val data = dataLines.joinToString("\n")
                dataLines.clear()
                if (data == "[DONE]") return null
                val eventJson = Json.parseToJsonElement(data).jsonObjectOrNull ?: return null
                return parseResponseDelta(eventJson)
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        drainEvent()?.let { emit(it) }
                        if (eventType == "response.completed") {
                            return@use
                        }
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
            drainEvent()?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            if (!params.model.tools.contains(BuiltInTools.ImageGeneration)) {
                put("store", false)
            }

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text })
            }

            // messages
            put("input", buildMessages(messages))

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING) && params.reasoningLevel != ReasoningLevel.OFF) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    openAIResponsesReasoningEffort(level)?.let { effort ->
                        put("effort", effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put(
                                "parameters",
                                json.encodeToJsonElement(
                                    tool.parameters()
                                )
                            )
                        })
                    }
                }
            }
            // built-in tools
            if (params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
            .withoutSamplingParamsIfNeeded(params.model)
            .withForcedStream(stream)
    }

    private fun JsonObject.withoutSamplingParamsIfNeeded(model: Model): JsonObject {
        if (isModelAllowTemperature(model)) return this
        return JsonObject(toMutableMap().apply {
            remove("temperature")
            remove("top_p")
        })
    }

    private fun JsonObject.withForcedStream(stream: Boolean): JsonObject =
        JsonObject(toMutableMap().apply {
            put("stream", JsonPrimitive(stream))
        })

    internal fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message)
                } else {
                    addUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    part.metadata?.get("reasoning_id")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put("id", it)
                                    }
                                    put("summary", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", part.reasoning)
                                        })
                                    })
                                    part.metadata?.get("encrypted_content")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put(
                                            "encrypted_content",
                                            part.metadata?.get("encrypted_content")?.jsonPrimitive?.contentOrNull ?: ""
                                        )
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                val callId = part.metadata?.get("openai_image_call_id")?.jsonPrimitive?.contentOrNull
                                if (callId != null) {
                                    if (contentBuffer.isNotEmpty()) {
                                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                        contentBuffer.clear()
                                    }
                                    add(buildJsonObject {
                                        put("type", "image_generation_call")
                                        put("id", callId)
                                    })
                                } else {
                                    contentBuffer.add(part)
                                }
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 输出 function_call + function_call_output
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
                                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    val encodedImage = part.encodeBase64().getOrThrow()
                                    put("type", if (role == MessageRole.USER) "input_image" else "output_image")
                                    put("image_url", encodedImage.base64)
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_text.done" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = null,
                            message = UIMessage.assistant(
                                jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            finishReason = null
                        )
                    )
                )
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                                            ?: "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObjectOrNull ?: return null
                val type = item["type"]?.jsonPrimitiveOrNull?.content ?: return null
                val id = item["id"]?.jsonPrimitiveOrNull?.content ?: return null
                if (type == "function_call") {
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
                                        UIMessagePart.Tool(
                                            toolCallId = id,
                                            toolName = item["name"]?.jsonPrimitiveOrNull?.content ?: "",
                                            input = item["arguments"]?.jsonPrimitiveOrNull?.content
                                                ?: "",
                                            output = emptyList()
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitiveOrNull?.content
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
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val callId = item["id"]?.jsonPrimitiveOrNull?.content ?: return null
                    return MessageChunk(
                        id = callId,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                            url = "",
                                            metadata = buildJsonObject {
                                                put("openai_image_call_id", callId)
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObjectOrNull ?: return null
                val type = item["type"]?.jsonPrimitiveOrNull?.content ?: return null
                val id = item["id"]?.jsonPrimitiveOrNull?.content ?: return null
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitiveOrNull?.content
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
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "message") {
                    return parseDoneMessageItem(item, id)
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitiveOrNull?.content ?: return null
                    return MessageChunk(
                        id = item["id"]?.jsonPrimitiveOrNull?.content ?: return null,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                                    url = result,
                                                    metadata = buildJsonObject {
                                                put("openai_image_call_id", item["id"]?.jsonPrimitiveOrNull?.content ?: "")
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                val toolCallId =
                    jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
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
                                        output = emptyList()
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.completed" -> {
                val response = jsonObject["response"]?.jsonObjectOrNull
                if (response != null) {
                    return parseResponseOutput(response)
                }
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(response?.get("usage")?.jsonObject)
                )
            }
        }

        return null
    }

    private fun parseDoneMessageItem(item: JsonObject, id: String): MessageChunk? {
        val text = item.extractMessageOutputText()
        if (text.isEmpty()) return null
        return MessageChunk(
            id = id,
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage.assistant(text),
                    finishReason = null
                )
            )
        )
    }

    private fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        val outputs = jsonObject["output"]?.jsonArrayOrNull.orEmpty()
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObjectOrNull ?: return@forEach
            val type = output["type"]?.jsonPrimitiveOrNull?.content ?: return@forEach
            when (type) {
                "reasoning" -> {
                    val summary = output["summary"]?.jsonArrayOrNull.orEmpty()
                    summary.mapNotNull { it.jsonObjectOrNull }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitiveOrNull?.content ?: return@forEach
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitiveOrNull?.content ?: return@forEach
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitiveOrNull?.content ?: return@forEach
                    val name = output["name"]?.jsonPrimitiveOrNull?.content ?: return@forEach
                    val arguments =
                        output["arguments"]?.jsonPrimitiveOrNull?.content ?: ""
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    output.extractMessageOutputText()
                        .takeIf { it.isNotEmpty() }
                        ?.let { text -> parts.add(UIMessagePart.Text(text = text)) }
                    output["content"]?.jsonArrayOrNull.orEmpty()
                        .mapNotNull { it.jsonObjectOrNull }
                        .mapNotNull { it["refusal"]?.jsonPrimitiveOrNull?.contentOrNull }
                        .filter { it.isNotBlank() }
                        .forEach { refusal -> parts.add(UIMessagePart.Text(text = refusal)) }
                }

                "output_text" -> {
                    output["text"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { text -> parts.add(UIMessagePart.Text(text = text)) }
                }

                "refusal" -> {
                    output["refusal"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { refusal -> parts.add(UIMessagePart.Text(text = refusal)) }
                }

                else -> {
                    // Responses can add tool/result/refusal/internal items over time.
                    // Unknown output items must not discard visible text collected from
                    // message/output_text siblings.
                    return@forEach
                }
            }
        }

        return MessageChunk(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts,
                    ),
                    finishReason = jsonObject.responseFinishReason(),
                    delta = null
                )
            ),
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObjectOrNull)
        )
    }

    private fun JsonObject.extractMessageOutputText(): String =
        this["content"]?.jsonArrayOrNull
            ?.mapNotNull { part ->
                val partObject = part.jsonObjectOrNull ?: return@mapNotNull null
                when (partObject["type"]?.jsonPrimitiveOrNull?.contentOrNull) {
                    "output_text" -> partObject["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    "text" -> partObject["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    else -> null
                }
            }
            ?.joinToString("")
            .orEmpty()

    private fun JsonObject.responseFinishReason(): String? {
        val status = this["status"]?.jsonPrimitive?.contentOrNull
        val incompleteReason = this["incomplete_details"]?.jsonObjectOrNull
            ?.get("reason")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
        return incompleteReason ?: status?.takeIf { it != "completed" }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

private fun isModelAllowTemperature(model: Model): Boolean {
    val modelId = model.modelId.lowercase()
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) &&
        !ModelRegistry.GPT_5.match(model.modelId) &&
        !modelId.startsWith("gpt-5") &&
        !modelId.contains("codex")
}

private fun openAIResponsesReasoningEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.AUTO -> null
    ReasoningLevel.OFF -> "low"
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH,
    ReasoningLevel.XHIGH,
    ReasoningLevel.MAX -> "high"
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}
