package app.amber.ai.provider.providers.openai

import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.BuiltInTools
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.PartGroup
import app.amber.ai.provider.providers.groupPartsByToolBoundary
import app.amber.ai.registry.ModelRegistry
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.encodeBase64
import app.amber.ai.util.json
import app.amber.ai.util.mergeCustomBody
import app.amber.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object ResponseRequestEncoder {
    fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
        resume: ResponsesResumeRequest? = null,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        val isSiwc = providerSetting.authMode == OpenAIAuthMode.CHATGPT_SIWC
        val effectiveStream = if (isSiwc) true else stream
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", effectiveStream)
            // P6-01: resume requests store the response server-side so a
            // disconnect / process death can re-attach and fetch missing
            // events. store=false stays the default otherwise (the
            // image_generation built-in tool still forces store=true —
            // the server rejects store=false for it).
            // SIWC Preview strictly requires store=false and stream=true.
            if (isSiwc) {
                put("store", false)
            } else if (resume != null) {
                put("store", true)
                if (effectiveStream) put("background", true)
            } else if (!params.model.tools.contains(BuiltInTools.ImageGeneration)) {
                put("store", false)
            }

            if (!isSiwc && isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (!isSiwc && params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions: Responses has one instructions field, so preserve
            // every system message/part in source order instead of silently dropping
            // compaction, capability, or retry instructions after the first one.
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val systemText = messages.asSequence()
                    .filter { it.role == MessageRole.SYSTEM }
                    .flatMap { message ->
                        message.parts.asSequence().filterIsInstance<UIMessagePart.Text>()
                    }
                    .joinToString("\n\n") { it.text }
                put("instructions", systemText)
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

            val toolDefinitions = buildJsonArray {
                if (params.model.abilities.contains(ModelAbility.TOOL)) {
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
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("type", "web_search")
                            })
                        }

                        BuiltInTools.UrlContext -> {} // not supported

                        BuiltInTools.ImageGeneration -> {
                            if (!isSiwc) {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
            if (toolDefinitions.isNotEmpty()) {
                put("tools", toolDefinitions)
            }
        }.mergeCustomBody(params.customBody)
            .withoutSamplingParamsIfNeeded(params.model)
            .withSiwcPreviewSanitizationIfNeeded(isSiwc)
            .withForcedStream(effectiveStream)
    }

    private fun JsonObject.withSiwcPreviewSanitizationIfNeeded(isSiwc: Boolean): JsonObject {
        if (!isSiwc) return this
        val sanitized = toMutableMap()
        for (field in SIWC_UNSUPPORTED_REQUEST_FIELDS) {
            sanitized.remove(field)
        }
        sanitized["store"] = JsonPrimitive(false)
        sanitized["stream"] = JsonPrimitive(true)

        // Ensure no `{type: "message", role: "system"}` item leaks into `input` (convert to developer).
        val inputArray = sanitized["input"] as? JsonArray
        if (inputArray != null) {
            sanitized["input"] = JsonArray(
                inputArray.map { item ->
                    val obj = item as? JsonObject ?: return@map item
                    val role = obj["role"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (role.equals("system", ignoreCase = true)) {
                        JsonObject(obj.toMutableMap().apply { put("role", JsonPrimitive("developer")) })
                    } else {
                        item
                    }
                }
            )
        }

        // Filter out unsupported hosted tools while retaining custom function tools (and web_search).
        val toolsArray = sanitized["tools"] as? JsonArray
        if (toolsArray != null) {
            val allowedTools = toolsArray.filter { toolElement ->
                val type = (toolElement as? JsonObject)?.get("type")?.jsonPrimitiveOrNull?.contentOrNull
                type != null && type in SIWC_ALLOWED_TOOL_TYPES
            }
            if (allowedTools.isEmpty()) {
                sanitized.remove("tools")
            } else {
                sanitized["tools"] = JsonArray(allowedTools)
            }
        }
        return JsonObject(sanitized)
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

    fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
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

}

private val SIWC_UNSUPPORTED_REQUEST_FIELDS = setOf(
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

private val SIWC_ALLOWED_TOOL_TYPES = setOf(
    "function",
    "custom",
    "web_search",
    "web_search_preview",
)

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
