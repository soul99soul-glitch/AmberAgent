package app.amber.ai.provider.providers

import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.TokenUsage
import app.amber.ai.provider.BuiltInTools
import app.amber.ai.provider.Modality
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.registry.ModelRegistry
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.STREAM_TOOL_FALLBACK_ID_METADATA_KEY
import app.amber.ai.ui.withStreamToolIndex
import app.amber.ai.util.encodeBase64
import app.amber.ai.util.json
import app.amber.ai.util.mergeCustomBody
import app.amber.ai.util.parseErrorDetail
import app.amber.ai.util.removeElements
import app.amber.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val GEMINI_CALL_ID_METADATA = "gemini_function_call_id"

/** Google generateContent wire mapping, isolated from OAuth, Vertex, HTTP, and image predict. */
internal class GeminiGenerateContentAdapter(
    private val codec: Json = json,
) {
    fun encodeRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        codeAssistTransport: Boolean,
    ): JsonObject = buildJsonObject {
        val systemText = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n\n", transform = UIMessagePart.Text::text)
            .orEmpty()
        if (systemText.isNotEmpty() && Modality.IMAGE !in params.model.outputModalities) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemText) })
                })
            })
        }

        put("generationConfig", generationConfig(params, codeAssistTransport))
        put("contents", encodeContents(messages))
        encodeTools(params)?.let { put("tools", it) }
        put("safetySettings", safetySettings())
    }.mergeCustomBody(params.customBody)

    fun encodeContents(messages: List<UIMessage>): JsonArray = JsonArray(buildList {
        messages.asSequence()
            .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAll(assistantTurns(message))
                } else {
                    add(contentTurn(roleForRequest(message.role), message.parts.mapNotNull(::encodePart)))
                }
            }
    })

    fun decodeCompletion(payload: JsonObject, modelId: String): MessageChunk {
        val response = unwrap(payload)
        response["error"]?.let { throw it.parseErrorDetail() }
        blockedReason(response)?.let { error("Google blocked the prompt: $it") }
        val candidates = response["candidates"]?.jsonArray
            ?.takeIf(JsonArray::isNotEmpty)
            ?: error("Google returned no response candidates")
        val choices = candidates.mapIndexed { index, element ->
            val candidate = element.jsonObject
            val finish = finishReason(candidate)
            val content = candidate["content"] as? JsonObject
                ?: error("Google returned no content for candidate with finishReason=${finish ?: "unknown"}")
            UIMessageChoice(
                index = candidate.indexOr(index),
                delta = null,
                message = decodeCandidate(candidate, content) { Uuid.random().toString() },
                finishReason = finish,
            )
        }
        return MessageChunk(
            id = Uuid.random().toString(),
            model = modelId,
            choices = choices,
            usage = usage(response["usageMetadata"] as? JsonObject),
        )
    }

    fun streamDecoder(modelId: String): GeminiStreamDecoder = GeminiStreamDecoder(this, modelId)

    fun unwrap(payload: JsonObject): JsonObject = payload["response"] as? JsonObject ?: payload

    fun finishReason(candidate: JsonObject): String? = candidate.string("finishReason")

    fun usage(metadata: JsonObject?): TokenUsage? {
        if (metadata == null) return null
        val prompt = metadata.int("promptTokenCount")
        val thoughts = metadata.int("thoughtsTokenCount")
        val cached = metadata.int("cachedContentTokenCount")
        val candidates = metadata.int("candidatesTokenCount")
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = candidates + thoughts,
            totalTokens = metadata.int("totalTokenCount"),
            cachedTokens = cached,
        )
    }

    internal fun decodeCandidate(
        candidate: JsonObject,
        content: JsonObject,
        nextFallbackToolId: () -> String,
    ): UIMessage {
        val role = when (content.string("role") ?: "model") {
            "model" -> MessageRole.ASSISTANT
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            else -> error("Unknown Google content role: ${content.string("role")}")
        }
        val parts = content["parts"]?.jsonArray
            ?.map { decodePart(it.jsonObject, nextFallbackToolId) }
            .orEmpty()
        return UIMessage(
            role = role,
            parts = parts,
            annotations = citations(candidate["groundingMetadata"] as? JsonObject),
        )
    }

    internal fun decodePart(
        part: JsonObject,
        nextFallbackToolId: () -> String = { Uuid.random().toString() },
    ): UIMessagePart = when {
        "text" in part -> {
            val text = part.string("text").orEmpty()
            if (part["thought"]?.jsonPrimitiveOrNull?.booleanOrNull == true) {
                UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = part.thoughtSignatureMetadata(),
                )
            } else {
                UIMessagePart.Text(text)
            }
        }

        "functionCall" in part -> {
            val call = part.getValue("functionCall").jsonObject
            val providerCallId = call.string("id")
            UIMessagePart.Tool(
                toolCallId = providerCallId ?: nextFallbackToolId(),
                toolName = call.string("name").orEmpty(),
                input = call["args"]?.let { codec.encodeToString(it) } ?: "{}",
                output = emptyList(),
                metadata = buildJsonObject {
                    part.string("thoughtSignature")?.let { put("thoughtSignature", it) }
                    providerCallId?.let { put(GEMINI_CALL_ID_METADATA, it) }
                    if (providerCallId == null) put(STREAM_TOOL_FALLBACK_ID_METADATA_KEY, true)
                },
            )
        }

        "inlineData" in part -> decodeInlineData(part)
        else -> error("Unsupported Google response part: ${part.keys.sorted()}")
    }

    private fun generationConfig(
        params: TextGenerationParams,
        codeAssistTransport: Boolean,
    ): JsonObject = buildJsonObject {
        params.temperature?.let { put("temperature", it) }
        params.topP?.let { put("topP", it) }
        params.maxTokens?.let { put("maxOutputTokens", it) }
        if (Modality.IMAGE in params.model.outputModalities) {
            put("responseModalities", buildJsonArray {
                add(JsonPrimitive("TEXT"))
                add(JsonPrimitive("IMAGE"))
            })
        }
        if (params.model.abilities.contains(ModelAbility.REASONING)) {
            put("thinkingConfig", thinkingConfig(params, codeAssistTransport))
        }
    }

    private fun thinkingConfig(
        params: TextGenerationParams,
        codeAssistTransport: Boolean,
    ): JsonObject = buildJsonObject {
        put("includeThoughts", true)
        val gemini3 = ModelRegistry.GEMINI_3_SERIES.match(params.model.modelId)
        val gemini25Pro = params.model.modelId.contains(
            Regex("2\\.5.*pro", RegexOption.IGNORE_CASE),
        )
        when (params.reasoningLevel) {
            ReasoningLevel.AUTO -> Unit
            ReasoningLevel.OFF -> when {
                gemini3 && !codeAssistTransport -> put("thinkingLevel", "minimal")
                !gemini3 && !gemini25Pro -> {
                    put("thinkingBudget", 0)
                    put("includeThoughts", false)
                }
            }

            else -> if (gemini3) {
                put(
                    "thinkingLevel",
                    when (params.reasoningLevel) {
                        ReasoningLevel.LOW -> "low"
                        ReasoningLevel.MEDIUM -> "medium"
                        else -> "high"
                    },
                )
            } else {
                put("thinkingBudget", params.reasoningLevel.budgetTokens)
            }
        }
    }

    private fun encodeTools(params: TextGenerationParams): JsonArray? {
        val functionTools = params.tools.takeIf {
            it.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        }
        if (functionTools == null && params.model.tools.isEmpty()) return null
        return buildJsonArray {
            if (functionTools != null) {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        functionTools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    codec.encodeToJsonElement(tool.parameters()).removeElements(
                                        listOf(
                                            "const",
                                            "exclusiveMaximum",
                                            "exclusiveMinimum",
                                            "format",
                                            "additionalProperties",
                                            "enum",
                                        ),
                                    ),
                                )
                            })
                        }
                    })
                })
            }
            params.model.tools.forEach { tool ->
                when (tool) {
                    BuiltInTools.Search -> add(buildJsonObject {
                        put("googleSearch", buildJsonObject {})
                    })

                    BuiltInTools.UrlContext -> add(buildJsonObject {
                        put("urlContext", buildJsonObject {})
                    })

                    else -> Unit
                }
            }
        }
    }

    private fun safetySettings(): JsonArray = buildJsonArray {
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
            "HARM_CATEGORY_CIVIC_INTEGRITY",
        ).forEach { category ->
            add(buildJsonObject {
                put("category", category)
                put("threshold", "OFF")
            })
        }
    }

    private fun assistantTurns(message: UIMessage): List<JsonObject> = buildList {
        val pending = mutableListOf<JsonObject>()
        groupPartsByToolBoundary(message.parts).forEach { group ->
            when (group) {
                is PartGroup.Content -> pending += group.parts.mapNotNull(::encodePart)
                is PartGroup.Tools -> {
                    pending += group.tools.map(::encodeFunctionCall)
                    add(contentTurn("model", pending))
                    pending.clear()
                    add(contentTurn("user", group.tools.map(::encodeFunctionResponse)))
                }
            }
        }
        if (pending.isNotEmpty()) add(contentTurn("model", pending))
    }

    private fun contentTurn(role: String, parts: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", role)
        put("parts", JsonArray(parts.toList()))
    }

    private fun roleForRequest(role: MessageRole): String = when (role) {
        MessageRole.ASSISTANT -> "model"
        MessageRole.SYSTEM -> "system"
        MessageRole.USER, MessageRole.TOOL -> "user"
    }

    private fun encodePart(part: UIMessagePart): JsonObject? = when (part) {
        is UIMessagePart.Text -> buildJsonObject { put("text", part.text) }
        is UIMessagePart.Reasoning -> buildJsonObject {
            put("text", part.reasoning)
            put("thought", true)
            part.metadata?.string("thoughtSignature")?.let { put("thoughtSignature", it) }
        }

        is UIMessagePart.Image -> {
            val encoded = part.encodeBase64(withPrefix = false).getOrThrow()
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", encoded.mimeType)
                    put("data", encoded.base64)
                })
                part.metadata?.string("thoughtSignature")?.let { put("thoughtSignature", it) }
            }
        }

        is UIMessagePart.Video -> part.encodeBase64(withPrefix = false).getOrNull()?.let { encoded ->
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", part.mime.takeIf { it.startsWith("video/") } ?: "video/mp4")
                    put("data", encoded)
                })
            }
        }

        is UIMessagePart.Audio -> part.encodeBase64(withPrefix = false).getOrNull()?.let { encoded ->
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", part.mime.takeIf { it.startsWith("audio/") } ?: "audio/mpeg")
                    put("data", encoded)
                })
            }
        }

        else -> null
    }

    private fun encodeFunctionCall(tool: UIMessagePart.Tool): JsonObject = buildJsonObject {
        put("functionCall", buildJsonObject {
            tool.metadata?.string(GEMINI_CALL_ID_METADATA)?.let { put("id", it) }
            put("name", tool.toolName)
            put("args", tool.inputAsJson())
        })
        tool.metadata?.string("thoughtSignature")?.let { put("thoughtSignature", it) }
    }

    private fun encodeFunctionResponse(tool: UIMessagePart.Tool): JsonObject = buildJsonObject {
        put("functionResponse", buildJsonObject {
            tool.metadata?.string(GEMINI_CALL_ID_METADATA)?.let { put("id", it) }
            put("name", tool.toolName)
            put("response", buildJsonObject {
                put(
                    "result",
                    tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
                )
            })
        })
    }

    private fun decodeInlineData(part: JsonObject): UIMessagePart {
        val data = part.getValue("inlineData").jsonObject
        val mime = data.string("mimeType") ?: "image/png"
        require(mime.startsWith("image/")) { "Only image mime type is supported" }
        if (part["thought"]?.jsonPrimitiveOrNull?.booleanOrNull == true) {
            return UIMessagePart.Reasoning(
                reasoning = "[Draft Image]\n",
                createdAt = Clock.System.now(),
                finishedAt = null,
                metadata = part.thoughtSignatureMetadata(),
            )
        }
        return UIMessagePart.Image(
            url = "data:$mime;base64,${data.string("data").orEmpty()}",
            metadata = buildJsonObject {
                part.string("thoughtSignature")?.let { put("thoughtSignature", it) }
            },
        )
    }

    private fun citations(metadata: JsonObject?): List<UIMessageAnnotation> =
        metadata?.get("groundingChunks")
            ?.jsonArray
            ?.mapNotNull { chunk ->
                val web = chunk.jsonObject["web"] as? JsonObject ?: return@mapNotNull null
                val uri = web.string("uri") ?: return@mapNotNull null
                val title = web.string("title") ?: return@mapNotNull null
                UIMessageAnnotation.UrlCitation(title = title, url = uri)
            }
            .orEmpty()

    private fun blockedReason(payload: JsonObject): String? =
        (payload["promptFeedback"] as? JsonObject)?.string("blockReason")

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitiveOrNull?.contentOrNull

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitiveOrNull?.intOrNull ?: 0

    private fun JsonObject.indexOr(fallback: Int): Int =
        this["index"]?.jsonPrimitiveOrNull?.intOrNull ?: fallback

    private fun JsonObject.thoughtSignatureMetadata(): JsonObject? =
        string("thoughtSignature")?.let { signature ->
            buildJsonObject { put("thoughtSignature", signature) }
        }
}

internal class GeminiStreamDecoder(
    private val adapter: GeminiGenerateContentAdapter,
    private val modelId: String,
) {
    private val generatedIdPrefix = "gemini-call-${Uuid.random().toString().take(8)}"
    private var generatedIdOrdinal = 0

    fun decode(data: String): GeminiStreamSignal {
        val payload = adapter.unwrap(json.parseToJsonElement(data).jsonObject)
        payload["error"]?.let { return GeminiStreamSignal.Failure(it.parseErrorDetail()) }
        val blocked = (payload["promptFeedback"] as? JsonObject)
            ?.get("blockReason")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
        if (blocked != null) {
            return GeminiStreamSignal.Failure(IllegalStateException("Prompt feedback: $blocked"))
        }

        val usage = adapter.usage(payload["usageMetadata"] as? JsonObject)
        val candidates = payload["candidates"]?.jsonArray.orEmpty()
        if (candidates.isEmpty() && usage == null) return GeminiStreamSignal.Ignore

        val reasons = mutableListOf<String?>()
        val choices = candidates.mapIndexed { index, element ->
            val candidate = element.jsonObject
            val finish = adapter.finishReason(candidate)
            reasons += finish
            val content = candidate["content"] as? JsonObject
            val message = content?.let {
                adapter.decodeCandidate(candidate, it) {
                    "$generatedIdPrefix-${generatedIdOrdinal++}"
                }.withIndexedTools()
            }
            UIMessageChoice(
                index = candidate["index"]?.jsonPrimitiveOrNull?.intOrNull ?: index,
                delta = message,
                message = null,
                finishReason = finish,
            )
        }
        return GeminiStreamSignal.Emit(
            chunk = MessageChunk(
                id = Uuid.random().toString(),
                model = modelId,
                choices = choices,
                usage = usage,
            ),
            finishReasons = reasons,
        )
    }

    private fun UIMessage.withIndexedTools(): UIMessage {
        var index = 0
        return copy(parts = parts.map { part ->
            if (part is UIMessagePart.Tool) part.withStreamToolIndex(index++) else part
        })
    }
}

internal sealed interface GeminiStreamSignal {
    data class Emit(
        val chunk: MessageChunk,
        val finishReasons: List<String?>,
    ) : GeminiStreamSignal

    data class Failure(val cause: Throwable) : GeminiStreamSignal
    data object Ignore : GeminiStreamSignal
}
