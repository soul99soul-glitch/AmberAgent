package app.amber.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.TokenUsage
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.PartGroup
import app.amber.ai.provider.providers.groupPartsByToolBoundary
import app.amber.ai.registry.ModelRegistry
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.STREAM_TOOL_INDEX_METADATA_KEY
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.hasExplicitReasoningContentField
import app.amber.ai.ui.reasoningContentPresentMetadata
import app.amber.ai.util.KeyRoulette
import app.amber.ai.util.configureReferHeaders
import app.amber.ai.util.encodeBase64
import app.amber.ai.util.json
import app.amber.ai.util.mergeCustomBody
import app.amber.ai.util.parseErrorDetail
import app.amber.ai.util.stringSafe
import app.amber.ai.util.toHeaders
import app.amber.common.http.await
import app.amber.common.http.jsonArrayOrNull
import app.amber.common.http.jsonObjectOrNull
import app.amber.common.http.jsonPrimitiveOrNull
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

private const val TAG = "ChatCompletionsAPI"

internal fun normalizeOpenAIStreamDataLines(data: String): List<String> {
    // OkHttp EventSource 已按 SSE 语义把同一 event 的多行 data 用 \n 合并成一个字符串。
    // 标准语义下整个 data 就是一个 payload; 服务端把单个 JSON 拆成多行 data 是合法的,
    // 无脑按行拆会把它拆碎。因此: 多行时先尝试整体作为一个 JSON payload, 失败才退回
    // 按行拆分 (兼容嵌套 data: 前缀的 wrapper 和单 event 多 JSON 行的异常 provider)。
    val lines = data.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.withoutNestedSseDataPrefix() }
        .filter { it.isNotBlank() && it != "[DONE]" }
        .toList()
    if (lines.size <= 1) return lines
    val joined = lines.joinToString("\n")
    return if (runCatching { json.parseToJsonElement(joined) }.isSuccess) {
        listOf(joined)
    } else {
        lines
    }
}

private fun String.withoutNestedSseDataPrefix(): String {
    var value = trimStart()
    while (value.startsWith("data:")) {
        value = value.removePrefix("data:").trimStart()
    }
    return value
}

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default(),
    private val bearerResolver: suspend (ProviderSetting.OpenAI, Boolean) -> String = { providerSetting, _ ->
        keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
    },
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val token = bearerResolver(providerSetting, false)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addOpenAICompatibleAuthHeader(providerSetting, token)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: model=${params.model.modelId}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(message),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val token = bearerResolver(providerSetting, false)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addOpenAICompatibleAuthHeader(providerSetting, token)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: model=${params.model.modelId}")

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val payloads = normalizeOpenAIStreamDataLines(data)
                if (payloads.isEmpty() && data.contains("[DONE]")) {
                    close()
                    return
                }
                Log.d(TAG, "onEvent: type=$type chars=${data.length}")
                // parse 异常/error payload 必须主动 cancel + close, 不依赖在 OkHttp
                // callback 中抛异常的隐式传播, 否则 flow 可能不可靠关闭或吞错
                runCatching {
                    for (payload in payloads) {
                        val it = json.parseToJsonElement(payload).jsonObject
                        if (it["error"] != null) {
                            val error = it["error"]!!.parseErrorDetail()
                            throw error
                        }
                        val id = it["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val model = it["model"]?.jsonPrimitive?.contentOrNull ?: ""

                        val choices = it["choices"]?.jsonArray ?: JsonArray(emptyList())
                        val choiceList = buildList {
                            if (choices.isNotEmpty()) {
                                val choice = choices[0].jsonObject
                                val message =
                                    choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                                    ?: throw Exception("delta/message is null")
                                val finishReason =
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                        ?: "unknown"
                                add(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = parseMessage(message),
                                        message = null,
                                        finishReason = finishReason,
                                    )
                                )
                            }
                        }
                        val usage = parseTokenUsage(it["usage"] as? JsonObject)

                        val messageChunk = MessageChunk(
                            id = id,
                            model = model,
                            choices = choiceList,
                            usage = usage
                        )
                        // trySend 在 buffer 满时静默丢 chunk = 永久丢 token;
                        // 阻塞 OkHttp 读线程形成天然背压, channel 关闭时立即失败
                        if (trySendBlocking(messageChunk).isFailure) {
                            eventSource.cancel()
                            break
                        }
                    }
                }.onFailure { error ->
                    eventSource.cancel()
                    close(error)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                Log.w(TAG, "onFailure: status=${response?.code} type=${t?.javaClass?.simpleName}", t)

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(
                            normalizeOpenAIStreamDataLines(bodyRaw).firstOrNull() ?: bodyRaw
                        )
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    // body 解析失败时保留原始网络异常, 不要用 parse 异常覆盖根因
                    Log.w(TAG, "onFailure: failed to parse response body chars=${bodyRaw?.length ?: 0}", e)
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
    }


    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val isMiMo = isMiMoProvider(providerSetting, host, params.model.modelId)
        val forceReasoningContentForToolCalls =
            shouldForceReasoningContentForToolCalls(providerSetting, host, params.model, params.reasoningLevel)
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    messages = messages,
                    preserveHistoricalReasoningContent = isMiMo,
                    forceReasoningContentForToolCalls = forceReasoningContentForToolCalls,
                )
            )

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) {
                put(if (isMiMo) "max_completion_tokens" else "max_tokens", params.maxTokens)
            }

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if(host == "openrouter.ai") {
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                when {
                    isMiMo -> {
                        // MiMo thinking mode uses the OpenAI-compatible extra_body field `thinking`.
                        put("thinking", buildJsonObject {
                            put("type", if (level.isEnabled) "enabled" else "disabled")
                        })
                    }

                    host == "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    host == "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                    }

                    host == "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    host == "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    host == "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    host == "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
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
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    host == "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    host == "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    host == "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，completions API 只支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                            })
                        })
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

    private fun isModelAllowTemperature(model: Model): Boolean {
        val modelId = model.modelId.lowercase()
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) &&
            !ModelRegistry.GPT_5.match(model.modelId) &&
            !modelId.startsWith("gpt-5") &&
            !modelId.contains("codex")
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        preserveHistoricalReasoningContent: Boolean,
        forceReasoningContentForToolCalls: Boolean,
    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }
        val lastUserIndex = filteredMessages.indexOfLast { it.role == MessageRole.USER }

        filteredMessages.forEachIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeReasoning = preserveHistoricalReasoningContent || index > lastUserIndex,
                    forceReasoningContentForToolCalls = forceReasoningContentForToolCalls,
                )
            } else {
                addNonAssistantMessage(message)
            }
        }
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

    private fun shouldForceReasoningContentForToolCalls(
        providerSetting: ProviderSetting.OpenAI,
        host: String,
        model: Model,
        reasoningLevel: ReasoningLevel,
    ): Boolean {
        if (!reasoningLevel.isEnabled) return false
        if (!model.abilities.contains(ModelAbility.REASONING)) return false
        val lowerModelId = model.modelId.lowercase()
        return providerSetting.brand == OpenAIBrand.DEEPSEEK ||
            host == "api.deepseek.com" ||
            lowerModelId.contains("deepseek")
    }

    private fun Request.Builder.addOpenAICompatibleAuthHeader(
        providerSetting: ProviderSetting.OpenAI,
        token: String,
    ): Request.Builder {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return if (providerSetting.authMode == OpenAIAuthMode.MIMO_CODING_PLAN ||
            host.startsWith("token-plan-") && host.endsWith("xiaomimimo.com")
        ) {
            addHeader("api-key", token)
        } else {
            addHeader("Authorization", "Bearer $token")
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeReasoning: Boolean,
        forceReasoningContentForToolCalls: Boolean,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart,
                        forceReasoningContentForToolCalls = forceReasoningContentForToolCalls,
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", tool.toolCallId)
                            put(
                                "content",
                                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart,
                forceReasoningContentForToolCalls = forceReasoningContentForToolCalls,
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?,
        forceReasoningContentForToolCalls: Boolean,
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        val shouldEmitReasoningContent = hasReasoning ||
            reasoningPart?.hasExplicitReasoningContentField() == true ||
            (forceReasoningContentForToolCalls && tools.isNotEmpty())
        if (!hasUsableContent && !shouldEmitReasoningContent && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content
            if (shouldEmitReasoningContent) {
                put("reasoning_content", reasoningPart?.reasoning.orEmpty())
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    val encodedImage = part.encodeBase64().getOrThrow()
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
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
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            if (message.role == MessageRole.SYSTEM && message.parts.filterIsInstance<UIMessagePart.Text>().isNotEmpty()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text })
            } else if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    val encodedImage = part.encodeBase64().getOrThrow()
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content?
        val content = jsonObject["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val hasReasoningContent = jsonObject.containsKey("reasoning_content")
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["content"]?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObject?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (hasReasoningContent || !reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning.orEmpty(),
                            createdAt = Clock.System.now(),
                            finishedAt = null,
                            metadata = if (hasReasoningContent) reasoningContentPresentMetadata() else null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallIndex = toolCalls.jsonObject["index"]?.jsonPrimitive?.intOrNull
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList(),
                            metadata = toolCallIndex?.let { index ->
                                buildJsonObject {
                                    put(STREAM_TOOL_INDEX_METADATA_KEY, index)
                                }
                            }
                        )
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        val promptCacheHitTokens = jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
        val promptCacheMissTokens = jsonObject["prompt_cache_miss_tokens"]?.jsonPrimitive?.intOrNull
        val promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull
            ?: listOfNotNull(promptCacheHitTokens, promptCacheMissTokens)
                .takeIf { it.isNotEmpty() }
                ?.sum()
            ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: promptCacheHitTokens
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}
