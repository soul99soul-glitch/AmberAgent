package app.amber.ai.provider.providers

import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_CONTROL_METADATA
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_DISABLED
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_EPHEMERAL
import app.amber.ai.core.TokenUsage
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.withStreamToolIndex
import app.amber.ai.util.encodeBase64
import app.amber.ai.util.json
import app.amber.ai.util.mergeCustomBody
import app.amber.ai.util.parseErrorDetail
import app.amber.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** Anthropic Messages wire mapping. It has no HTTP, storage, tool execution, or UI ownership. */
internal class AnthropicMessagesAdapter(
    private val codec: Json = json,
) {
    fun encodeRequest(
        setting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        streaming: Boolean,
    ): JsonObject {
        val systemParts = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            .orEmpty()

        return buildJsonObject {
            put("model", params.model.modelId)
            put("max_tokens", params.maxTokens ?: 64_000)
            put("stream", streaming)
            put("messages", encodeConversation(messages, setting.promptCaching))

            params.temperature
                ?.takeUnless { params.reasoningLevel.isEnabled }
                ?.let { put("temperature", it) }
            params.topP?.let { put("top_p", it) }

            if (systemParts.isNotEmpty()) {
                put("system", encodeSystem(systemParts, setting.promptCaching))
            }
            encodeThinking(params)?.let { thinking ->
                put("thinking", thinking)
                if (params.reasoningLevel !in setOf(ReasoningLevel.OFF, ReasoningLevel.AUTO)) {
                    put("output_config", buildJsonObject {
                        put("effort", params.reasoningLevel.effort)
                    })
                }
            }
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    params.tools.forEachIndexed { index, tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", codec.encodeToJsonElement(tool.parameters()))
                            if (setting.promptCaching && index == params.tools.lastIndex) {
                                put("cache_control", ephemeralCache())
                            }
                        })
                    }
                })
            }
        }.mergeCustomBody(params.customBody)
    }

    fun encodeConversation(messages: List<UIMessage>, promptCaching: Boolean): JsonArray {
        val turns = buildList {
            messages.asSequence()
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addAll(assistantTurns(message))
                    } else {
                        add(userTurn(message))
                    }
                }
        }
        return JsonArray(if (promptCaching) markConversationCache(turns) else turns)
    }

    fun decodeCompletion(payload: JsonObject): MessageChunk {
        val content = payload["content"] as? JsonArray ?: JsonArray(emptyList())
        val message = messageFromBlocks(content)
        return MessageChunk(
            id = payload.string("id").orEmpty(),
            model = payload.string("model").orEmpty(),
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = message,
                    finishReason = payload.string("stop_reason") ?: "unknown",
                ),
            ),
            usage = tokenUsage(payload),
        )
    }

    fun decodeStreamEvent(eventName: String?, eventId: String?, data: String): AnthropicStreamSignal {
        if (data == "[DONE]") return AnthropicStreamSignal.Ignore
        val payload = codec.parseToJsonElement(data).jsonObject
        return when (eventName ?: payload.string("type")) {
            "message_stop" -> AnthropicStreamSignal.Stop
            "error" -> AnthropicStreamSignal.Failure(
                payload["error"]?.parseErrorDetail() ?: payload.parseErrorDetail(),
            )

            "message_start" -> {
                val message = payload["message"] as? JsonObject
                emitStreamChunk(
                    id = message?.string("id") ?: eventId.orEmpty(),
                    model = message?.string("model").orEmpty(),
                    parts = emptyList(),
                    finishReason = null,
                    usage = tokenUsage(payload),
                )
            }

            "content_block_start" -> streamContent(payload, eventId, "content_block")
            "content_block_delta" -> streamContent(payload, eventId, "delta")
            "message_delta" -> emitStreamChunk(
                id = eventId.orEmpty(),
                model = "",
                parts = emptyList(),
                finishReason = (payload["delta"] as? JsonObject)?.string("stop_reason"),
                usage = tokenUsage(payload),
            )

            // Anthropic may add event types over time. Unknown/ping/block-stop events carry
            // no Amber content and are intentionally ignored, as required by the SSE contract.
            else -> AnthropicStreamSignal.Ignore
        }
    }

    private fun streamContent(
        payload: JsonObject,
        eventId: String?,
        field: String,
    ): AnthropicStreamSignal {
        val block = payload[field] as? JsonObject ?: return AnthropicStreamSignal.Ignore
        val index = payload["index"]?.jsonPrimitiveOrNull?.intOrNull
        val part = decodeBlock(block)?.let { decoded ->
            if (decoded is UIMessagePart.Tool && index != null) decoded.withStreamToolIndex(index) else decoded
        } ?: return AnthropicStreamSignal.Ignore
        return emitStreamChunk(
            id = eventId.orEmpty(),
            model = "",
            parts = listOf(part),
            finishReason = null,
            usage = null,
        )
    }

    private fun emitStreamChunk(
        id: String,
        model: String,
        parts: List<UIMessagePart>,
        finishReason: String?,
        usage: TokenUsage?,
    ): AnthropicStreamSignal {
        if (parts.isEmpty() && finishReason == null && usage == null) {
            return AnthropicStreamSignal.Ignore
        }
        return AnthropicStreamSignal.Emit(
            MessageChunk(
                id = id,
                model = model,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                        message = null,
                        finishReason = finishReason,
                    ),
                ),
                usage = usage,
            ),
        )
    }

    private fun encodeSystem(parts: List<UIMessagePart.Text>, promptCaching: Boolean): JsonArray {
        val cacheDisabled = parts.any { part ->
            part.metadata?.string(SYSTEM_PROMPT_CACHE_CONTROL_METADATA) == SYSTEM_PROMPT_CACHE_DISABLED
        }
        val markedIndex = parts.indexOfLast { part ->
            part.metadata?.string(SYSTEM_PROMPT_CACHE_CONTROL_METADATA) == SYSTEM_PROMPT_CACHE_EPHEMERAL
        }
        return buildJsonArray {
            parts.forEachIndexed { index, part ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", part.text)
                    if (promptCaching && !cacheDisabled && index == markedIndex && markedIndex >= 0) {
                        put("cache_control", ephemeralCache())
                    }
                })
            }
        }
    }

    private fun encodeThinking(params: TextGenerationParams): JsonObject? {
        if (!params.model.abilities.contains(ModelAbility.REASONING)) return null
        return buildJsonObject {
            if (params.reasoningLevel == ReasoningLevel.OFF) {
                put("type", "disabled")
            } else {
                put("type", "adaptive")
                put("display", "summarized")
            }
        }
    }

    private fun assistantTurns(message: UIMessage): List<JsonObject> = buildList {
        val pendingContent = mutableListOf<JsonObject>()
        groupPartsByToolBoundary(message.parts).forEach { group ->
            when (group) {
                is PartGroup.Content -> pendingContent += group.parts.mapNotNull(::encodeContent)
                is PartGroup.Tools -> {
                    pendingContent += group.tools.map(::encodeToolUse)
                    add(turn("assistant", pendingContent))
                    pendingContent.clear()
                    add(turn("user", group.tools.map(::encodeToolResult)))
                }
            }
        }
        if (pendingContent.isNotEmpty()) add(turn("assistant", pendingContent))
    }

    private fun userTurn(message: UIMessage): JsonObject = turn(
        role = message.role.name.lowercase(),
        content = message.parts.mapNotNull(::encodeContent),
    )

    private fun turn(role: String, content: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", role)
        put("content", JsonArray(content.toList()))
    }

    private fun encodeContent(part: UIMessagePart): JsonObject? = when (part) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }

        is UIMessagePart.Image -> {
            val encoded = part.encodeBase64(withPrefix = false).getOrThrow()
            buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }
        }

        is UIMessagePart.Reasoning -> {
            val redacted = part.metadata?.string("redacted_thinking_data")
            if (redacted != null) {
                buildJsonObject {
                    put("type", "redacted_thinking")
                    put("data", redacted)
                }
            } else {
                buildJsonObject {
                    put("type", "thinking")
                    put("thinking", part.reasoning)
                    part.metadata?.forEach { (key, value) ->
                        if (key != "redacted_thinking_data") put(key, value)
                    }
                }
            }
        }

        else -> null
    }

    private fun encodeToolUse(tool: UIMessagePart.Tool): JsonObject = buildJsonObject {
        put("type", "tool_use")
        put("id", tool.toolCallId)
        put("name", tool.toolName)
        put("input", tool.inputAsJson())
    }

    private fun encodeToolResult(tool: UIMessagePart.Tool): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", tool.toolCallId)
        put("content", JsonArray(tool.output.mapNotNull(::encodeContent)))
    }

    private fun markConversationCache(turns: List<JsonObject>): List<JsonObject> {
        val realUsers = turns.indices.filter { index ->
            val turn = turns[index]
            turn.string("role") == "user" &&
                turn["content"]?.jsonArray?.none { it.jsonObject.string("type") == "tool_result" } == true
        }
        val target = realUsers.getOrNull(realUsers.lastIndex - 1) ?: return turns
        return turns.mapIndexed { index, turn ->
            if (index != target) return@mapIndexed turn
            val blocks = turn["content"]?.jsonArray ?: return@mapIndexed turn
            val marked = blocks.mapIndexed { blockIndex, block ->
                if (blockIndex == blocks.lastIndex) {
                    JsonObject(block.jsonObject + ("cache_control" to ephemeralCache()))
                } else {
                    block
                }
            }
            JsonObject(turn + ("content" to JsonArray(marked)))
        }
    }

    private fun messageFromBlocks(blocks: JsonArray): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = blocks.mapNotNull { decodeBlock(it.jsonObject) },
    )

    private fun decodeBlock(block: JsonObject): UIMessagePart? = when (block.string("type")) {
        "text", "text_delta" -> block.string("text")
            ?.takeIf(String::isNotEmpty)
            ?.let(UIMessagePart::Text)

        "thinking", "thinking_delta", "signature_delta" -> {
            val thought = block.string("thinking").orEmpty()
            val signature = block.string("signature")
            if (thought.isEmpty() && signature == null) null else {
                UIMessagePart.Reasoning(
                    reasoning = thought,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                ).also { part ->
                    if (signature != null) {
                        part.metadata = buildJsonObject { put("signature", signature) }
                    }
                }
            }
        }

        "redacted_thinking" -> block.string("data")?.let { value ->
            UIMessagePart.Reasoning(
                reasoning = "",
                createdAt = Clock.System.now(),
                finishedAt = null,
            ).also { part ->
                part.metadata = buildJsonObject { put("redacted_thinking_data", value) }
            }
        }

        "tool_use" -> UIMessagePart.Tool(
            toolCallId = block.string("id").orEmpty(),
            toolName = block.string("name").orEmpty(),
            input = (block["input"] as? JsonObject)
                ?.takeUnless(JsonObject::isEmpty)
                ?.let { codec.encodeToString(it) }
                .orEmpty(),
            output = emptyList(),
        )

        "input_json_delta" -> UIMessagePart.Tool(
            toolCallId = "",
            toolName = "",
            input = block.string("partial_json").orEmpty(),
            output = emptyList(),
        )

        else -> null
    }

    private fun tokenUsage(payload: JsonObject): TokenUsage? {
        val usage = payload["usage"] as? JsonObject
            ?: (payload["message"] as? JsonObject)?.get("usage") as? JsonObject
            ?: return null
        val input = usage.int("input_tokens")
        val cacheRead = usage.int("cache_read_input_tokens")
        val cacheWrite = usage.int("cache_creation_input_tokens")
        val output = usage.int("output_tokens")
        val prompt = input + cacheRead + cacheWrite
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = output,
            totalTokens = prompt + output,
            cachedTokens = cacheRead,
        )
    }

    private fun ephemeralCache(): JsonObject = buildJsonObject { put("type", "ephemeral") }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitiveOrNull?.contentOrNull

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitiveOrNull?.intOrNull ?: 0
}

internal sealed interface AnthropicStreamSignal {
    data class Emit(val chunk: MessageChunk) : AnthropicStreamSignal
    data class Failure(val cause: Throwable) : AnthropicStreamSignal
    data object Stop : AnthropicStreamSignal
    data object Ignore : AnthropicStreamSignal
}
