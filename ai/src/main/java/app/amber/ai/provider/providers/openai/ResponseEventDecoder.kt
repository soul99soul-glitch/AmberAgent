package app.amber.ai.provider.providers.openai

import app.amber.ai.core.MessageRole
import app.amber.ai.core.TokenUsage
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.asStreamImage
import app.amber.ai.ui.withStreamArgsReplace
import app.amber.ai.util.HttpException
import app.amber.ai.util.parseErrorDetail
import app.amber.common.http.jsonArrayOrNull
import app.amber.common.http.jsonObjectOrNull
import app.amber.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

// 流式 function_call 的 toolCallId 用 item id (fc_...), final output 用 call_id
// (call_...); 该元数据保存两者的映射, 供 ResponseStreamReconciler 匹配 final tool
internal const val OPENAI_TOOL_CALL_ID_METADATA_KEY = "openai_call_id"

internal object ResponseEventDecoder {
    fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            // refusal 文本与 output_text 同属 message item, 都按文本增量累积
            "response.output_text.delta", "response.refusal.delta" -> {
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

            "response.output_text.annotation.added" -> {
                val annotation = jsonObject["annotation"]?.jsonObjectOrNull ?: return null
                if (annotation["type"]?.jsonPrimitiveOrNull?.contentOrNull != "url_citation") return null
                val url = annotation["url"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = emptyList(),
                                annotations = listOf(
                                    UIMessageAnnotation.UrlCitation(
                                        title = annotation["title"]?.jsonPrimitiveOrNull?.contentOrNull ?: url,
                                        url = url,
                                    )
                                ),
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
                                        finishedAt = null,
                                        metadata = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull?.let { itemId ->
                                            buildJsonObject { put("reasoning_id", itemId) }
                                        },
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
                    // 流式 toolCallId 用 item id (fc_...), 但 final output 用 call_id
                    // (call_...); 把 call_id 记入 metadata 供 reconciler 匹配 final tool
                    val callId = item["call_id"]?.jsonPrimitiveOrNull?.contentOrNull
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
                                            output = emptyList(),
                                            metadata = callId?.let {
                                                buildJsonObject {
                                                    put(OPENAI_TOOL_CALL_ID_METADATA_KEY, it)
                                                }
                                            }
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
                                            finishedAt = Clock.System.now(),
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

            // arguments.delta 是流式片段: append 语义
            "response.function_call_arguments.delta" -> {
                val toolCallId =
                    jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
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
                                        input = jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: "",
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

            // arguments.done 携带完整参数: replace 语义, 避免与 delta 累积重复拼接
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
                                    ).withStreamArgsReplace()
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.failed" -> {
                val response = jsonObject["response"]?.jsonObjectOrNull
                val error = (response?.get("error") ?: jsonObject["error"])
                    ?.takeUnless { it is JsonNull }
                throw error?.let(::parseOpenAIResponseError) ?: HttpException("Response stream failed")
            }

            "error" -> {
                val error = (jsonObject["error"] ?: jsonObject)
                    .takeUnless { it is JsonNull }
                throw error?.let(::parseOpenAIResponseError) ?: HttpException("Response stream error")
            }

            // incomplete 与 completed 同构: response 对象携带 (可能不完整的) 最终输出,
            // finishReason 由 incomplete_details.reason / status 给出
            "response.completed", "response.incomplete" -> {
                val response = jsonObject["response"]?.jsonObjectOrNull
                if (response != null) {
                    return parseResponseOutput(response)
                }
                // Fallback when nested response object is absent: usage is on the event root.
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(jsonObject["usage"]?.jsonObjectOrNull)
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

    fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        val outputs = jsonObject["output"]?.jsonArrayOrNull.orEmpty()
        val parts = arrayListOf<UIMessagePart>()
        val annotations = arrayListOf<UIMessageAnnotation>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObjectOrNull ?: return@forEach
            val type = output["type"]?.jsonPrimitiveOrNull?.content ?: return@forEach
            when (type) {
                "reasoning" -> {
                    val reasoningId = output["id"]?.jsonPrimitiveOrNull?.content
                    val encryptedContent = output["encrypted_content"]?.jsonPrimitiveOrNull?.content
                    val summary = output["summary"]?.jsonArrayOrNull.orEmpty()
                    if (summary.isEmpty() && encryptedContent != null) {
                        parts.add(UIMessagePart.Reasoning(
                            reasoning = "",
                            metadata = buildJsonObject {
                                put("encrypted_content", encryptedContent)
                                reasoningId?.let { put("reasoning_id", it) }
                            },
                        ))
                    }
                    summary.mapNotNull { it.jsonObjectOrNull }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitiveOrNull?.content ?: return@forEach
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitiveOrNull?.content ?: return@forEach
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now(),
                                        metadata = buildJsonObject {
                                            encryptedContent?.let { put("encrypted_content", it) }
                                            reasoningId?.let { put("reasoning_id", it) }
                                        }.takeIf { it.isNotEmpty() }
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
                    val contentItems = output["content"]?.jsonArrayOrNull.orEmpty()
                        .mapNotNull { it.jsonObjectOrNull }
                    contentItems
                        .mapNotNull { it["refusal"]?.jsonPrimitiveOrNull?.contentOrNull }
                        .filter { it.isNotBlank() }
                        .forEach { refusal -> parts.add(UIMessagePart.Text(text = refusal)) }
                    // output_text content 可携带 url_citation annotations (web_search 等);
                    // 不解析会丢失 final-only 的引用来源
                    contentItems.forEach { contentItem ->
                        contentItem["annotations"]?.jsonArrayOrNull.orEmpty()
                            .mapNotNull { it.jsonObjectOrNull }
                            .filter { it["type"]?.jsonPrimitiveOrNull?.contentOrNull == "url_citation" }
                            .forEach { citation ->
                                val url = citation["url"]?.jsonPrimitiveOrNull?.contentOrNull
                                    ?: return@forEach
                                annotations.add(
                                    UIMessageAnnotation.UrlCitation(
                                        title = citation["title"]?.jsonPrimitiveOrNull?.contentOrNull
                                            ?: url,
                                        url = url,
                                    )
                                )
                            }
                    }
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

                "image_generation_call" -> {
                    // Mirrors the streaming response.output_item.done handler; without
                    // this, the final/non-stream message drops the generated image.
                    output["result"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { result ->
                            parts.add(
                                UIMessagePart.Image(
                                    url = result,
                                    metadata = buildJsonObject {
                                        put(
                                            "openai_image_call_id",
                                            output["id"]?.jsonPrimitiveOrNull?.content ?: ""
                                        )
                                    }
                                ).asStreamImage()
                            )
                        }
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
                        annotations = annotations.distinct(),
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

internal fun parseOpenAIResponseError(errorElement: kotlinx.serialization.json.JsonElement): HttpException {
    val obj = errorElement.jsonObjectOrNull
    val nested = obj?.get("error")?.jsonObjectOrNull ?: obj
    val code = nested?.get("code")?.jsonPrimitiveOrNull?.contentOrNull
    val detailMessage = nested?.get("message")?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
    return when (code) {
        "subscription_sharing_usage_limit_exceeded" -> HttpException(
            buildString {
                append("ChatGPT plan usage limit reached (subscription_sharing_usage_limit_exceeded).")
                if (detailMessage != null) append(" ").append(detailMessage)
                append(" Manage your usage limits at $OPENAI_CHATGPT_SIWC_USAGE_URL")
            }
        )
        "subscription_sharing_usage_unavailable" -> HttpException(
            buildString {
                append("ChatGPT plan usage is temporarily unavailable (subscription_sharing_usage_unavailable).")
                if (detailMessage != null) append(" ").append(detailMessage)
                append(" Your sign-in is preserved; please retry shortly or check $OPENAI_CHATGPT_SIWC_USAGE_URL")
            }
        )
        "subscription_sharing_user_not_eligible" -> HttpException(
            buildString {
                append("This ChatGPT account or workspace is not eligible for plan usage sharing (subscription_sharing_user_not_eligible).")
                if (detailMessage != null) append(" ").append(detailMessage)
                append(" Review your account at $OPENAI_CHATGPT_SIWC_USAGE_URL")
            }
        )
        else -> errorElement.parseErrorDetail()
    }
}

internal fun isSubscriptionSharingErrorCode(errorElement: kotlinx.serialization.json.JsonElement): Boolean {
    val obj = errorElement.jsonObjectOrNull ?: return false
    val nested = obj["error"]?.jsonObjectOrNull ?: obj
    val code = nested["code"]?.jsonPrimitiveOrNull?.contentOrNull ?: return false
    return code.startsWith("subscription_sharing_")
}
