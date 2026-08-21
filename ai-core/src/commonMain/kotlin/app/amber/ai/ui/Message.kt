package app.amber.ai.ui


import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.MessageRole
import app.amber.ai.core.TokenUsage
import app.amber.ai.provider.Model
import app.amber.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val STREAM_TOOL_INDEX_METADATA_KEY = "stream_tool_index"
const val RESPONSES_ITEM_ID_METADATA_KEY = "responses_item_id"
const val THOUGHT_SIGNATURE_METADATA_KEY = "thoughtSignature"
const val LOCAL_GENERATION_ERROR_METADATA_KEY = "amber_local_kind"
const val LOCAL_GENERATION_ERROR_METADATA_VALUE = "generation_error"
const val LOCAL_OUTPUT_LIMIT_NOTICE_METADATA_VALUE = "output_limit_notice"

fun localGenerationErrorTextPart(text: String): UIMessagePart.Text =
    UIMessagePart.Text(
        text = text,
        metadata = buildJsonObject {
            put(LOCAL_GENERATION_ERROR_METADATA_KEY, LOCAL_GENERATION_ERROR_METADATA_VALUE)
        }
    )

fun localOutputLimitNoticeTextPart(text: String): UIMessagePart.Text =
    UIMessagePart.Text(
        text = text,
        metadata = buildJsonObject {
            put(LOCAL_GENERATION_ERROR_METADATA_KEY, LOCAL_OUTPUT_LIMIT_NOTICE_METADATA_VALUE)
        }
    )

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
//
// @Immutable: every constructor property is a val and either a primitive,
// a value type (Uuid, LocalDateTime, TokenUsage), or an immutable List of
// data classes. The annotation lets Compose skip recomposition of any
// Composable taking UIMessage as a parameter when the caller passes the
// same reference (e.g. an unchanged historical MessageNode in
// LazyColumn). UIMessagePart is intentionally NOT @Immutable — its
// `var metadata` would violate the contract; see UIMessagePart.
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val finishedAt: LocalDateTime? = null,
    val modelId: Uuid? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null
) {
    private fun appendChunk(chunk: MessageChunk): UIMessage {
        val choice = chunk.choices.getOrNull(0)
        val message = choice?.delta ?: choice?.message
        return message?.let { delta ->
            // Handle Parts
            var newParts = delta.parts.fold(parts) { acc, deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> {
                        // Skip empty text deltas
                        if (deltaPart.text.isEmpty()) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            if (lastPart is UIMessagePart.Text) {
                                // Append to the last Text part
                                acc.dropLast(1) + lastPart.copy(text = lastPart.text + deltaPart.text)
                            } else {
                                // Create new Text part
                                acc + deltaPart
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val lastPart = acc.lastOrNull()
                        if (lastPart is UIMessagePart.Image) {
                            // Append to the last Image part (for streaming base64)
                            acc.dropLast(1) + lastPart.copy(
                                url = lastPart.url + deltaPart.url,
                                metadata = deltaPart.metadata ?: lastPart.metadata
                            )
                        } else {
                            // Create new Image part
                            acc + UIMessagePart.Image(
                                url = "data:image/png;base64,${deltaPart.url}",
                                metadata = deltaPart.metadata,
                            )
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        // Skip empty reasoning deltas
                        if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            if (lastPart is UIMessagePart.Reasoning) {
                                // Append to the last Reasoning part
                                acc.dropLast(1) + UIMessagePart.Reasoning(
                                    reasoning = lastPart.reasoning + deltaPart.reasoning,
                                    createdAt = lastPart.createdAt,
                                    finishedAt = null,
                                ).also {
                                    it.metadata = deltaPart.metadata ?: lastPart.metadata
                                }
                            } else {
                                // Create new Reasoning part
                                acc + deltaPart
                            }
                        }
                    }

                    is UIMessagePart.Tool -> {
                        val streamIndex = deltaPart.streamToolIndex()
                        if (deltaPart.toolCallId.isBlank()) {
                            // Argument-fragment delta (no id/name). Route it to the call
                            // currently being streamed: the MOST RECENT tool that can still
                            // take args (skip ones whose args are already complete). Gateways
                            // such as MiMo reuse stream index 0 across sequential calls, so
                            // matching the first index-0 tool would corrupt a finished one.
                            val targetTool = (streamIndex?.let { index ->
                                acc.lastOrNull {
                                    it is UIMessagePart.Tool && it.streamToolIndex() == index &&
                                        it.canAcceptArgsDelta(deltaPart)
                                }
                            } ?: acc.lastOrNull {
                                it is UIMessagePart.Tool && it.canAcceptArgsDelta(deltaPart)
                            }) as? UIMessagePart.Tool
                            if (targetTool != null) {
                                acc.map { part ->
                                    if (part === targetTool) part.merge(deltaPart) else part
                                }
                            } else {
                                acc + deltaPart.copy()
                            }
                        } else {
                            // First delta of a tool (id/name). Match by id, or fall back to
                            // stream index only when it is the same call (canMergeDelta),
                            // never folding a distinct parallel call in by index alone.
                            val existsPart = ((acc.find {
                                it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                            } as? UIMessagePart.Tool)
                                ?: deltaPart.responsesItemId()?.let { itemId ->
                                    acc.find {
                                        it is UIMessagePart.Tool && it.responsesItemId() == itemId
                                    } as? UIMessagePart.Tool
                                }
                                ?: streamIndex?.let { index ->
                                    acc.lastOrNull {
                                        it is UIMessagePart.Tool && it.streamToolIndex() == index
                                    } as? UIMessagePart.Tool
                                })?.takeIf { it.canMergeDelta(deltaPart) }
                            if (existsPart == null) {
                                acc + deltaPart.copy()
                            } else {
                                acc.map { part ->
                                    if (part === existsPart) part.merge(deltaPart) else part
                                }
                            }
                        }
                    }

                    // 无流式合并语义的多模态 part 原样追加,而不是丢弃。
                    // 与 MessageStreamAccumulator.append 保持同一处置:那边曾经
                    // 也是 `else -> println(...)`,是一条静默丢数据的路径。
                    is UIMessagePart.Video -> acc + deltaPart
                    is UIMessagePart.Audio -> acc + deltaPart
                    is UIMessagePart.Document -> acc + deltaPart
                    is UIMessagePart.MiniApp -> acc + deltaPart
                }
            }
            // Handle Reasoning End
            if (
                parts.filterIsInstance<UIMessagePart.Reasoning>().isNotEmpty() &&
                delta.parts.none { it.isReasoningContentDelta() } &&
                delta.parts.any { it.isReasoningCloseDelta() }
            ) {
                newParts = newParts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else part
                }
            }
            // Handle annotations
            val newAnnotations = delta.annotations.ifEmpty {
                annotations
            }
            copy(
                parts = newParts,
                annotations = newAnnotations,
            )
        } ?: this
    }

    fun summaryAsText(): String {
        return "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
    }

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            else -> true
        }
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    fun hasBase64Part(): Boolean = parts.any {
        it is UIMessagePart.Image && it.url.startsWith("data:")
    }

    operator fun plus(chunk: MessageChunk): UIMessage {
        return this.appendChunk(chunk)
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

private fun UIMessagePart.isReasoningContentDelta(): Boolean =
    this is UIMessagePart.Reasoning && reasoning.isNotEmpty()

private fun UIMessagePart.isReasoningCloseDelta(): Boolean = when (this) {
    is UIMessagePart.Reasoning -> finishedAt != null
    is UIMessagePart.Text -> text.isNotEmpty()
    is UIMessagePart.Image -> url.isNotEmpty()
    is UIMessagePart.Tool -> true
    else -> false
}

/**
 * 处理MessageChunk合并
 *
 * @receiver 已有消息列表
 * @param chunk 消息chunk
 * @param model 模型, 可以不传，如果传了，会把模型id写入到消息，标记是哪个模型输出的消息
 * @return 新消息列表
 */
fun List<UIMessage>.handleMessageChunk(chunk: MessageChunk, model: Model? = null): List<UIMessage> {
    require(this.isNotEmpty()) {
        "messages must not be empty"
    }
    val choice = chunk.choices.getOrNull(0) ?: return this
    val message = choice.delta ?: choice.message ?: return this
    if (this.last().role != message.role) {
        return this + (UIMessage(modelId = model?.id, role = message.role, parts = emptyList()) + chunk)
    } else {
        val last = this.last() + chunk
        return this.dropLast(1) + last
    }
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

fun List<UIMessage>.limitContext(size: Int): List<UIMessage> {
    if (size <= 0 || this.size <= size) return this

    val startIndex = this.size - size
    var adjustedStartIndex = startIndex

    // 循环往前查找，直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含已执行的tool（有output），往前查找对应的tool call
        if (currentMessage.getTools().any { it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getTools().any { !it.isExecuted }) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含未执行的tool call，往前查找对应的用户消息
        if (currentMessage.getTools().any { !it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    return this.subList(adjustedStartIndex, this.size)
}

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
            -> false
    }
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        // Default so old persisted JSON without this field still deserializes. Render
        // layer falls back to the URL's last path segment when fileName is blank.
        // (mime intentionally not stored — every audio encoder downstream hard-codes
        // `audio/mp3` today, so plumbing a per-file mime would be a half-wired
        // feature. Add it when an encoder actually starts honouring it.)
        val fileName: String = "",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("mini_app")
    data class MiniApp(
        val appId: String,
        val title: String,
        val description: String,
        val iconEmoji: String? = null,
        val category: String? = null,
        val permissions: List<String> = emptyList(),
        val htmlHash: String? = null,
        val version: Int = 1,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        val streamIndex: Int? = null,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = approvalState is ToolApprovalState.Pending

        /** Whether generation can resume and handle this tool immediately */
        val canResumeExecution: Boolean get() = !isExecuted && approvalState.canResumeToolExecution()

        /** Parse input string as JsonElement */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = toolCallId,
                toolName = mergeToolNames(toolName, other.toolName),
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                streamIndex = other.streamIndex ?: streamIndex,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }
}

/** Maximum number of characters of the raw (unparsed) tool input surfaced in [ToolInputParse.Invalid]. */
private const val TOOL_INPUT_RAW_PREFIX_LIMIT = 200

/**
 * Outcome of [UIMessagePart.Tool.parseInputStrict]. A sealed class rather than
 * [kotlin.Result] on purpose — inline value classes like `Result` don't bridge
 * across the KMP → Swift boundary, and this type's only reason to exist is to be
 * consumed from Swift execution gates.
 */
sealed class ToolInputParse {
    data class Valid(val args: JsonObject) : ToolInputParse()
    data class Invalid(val message: String, val rawPrefix: String) : ToolInputParse()
}

/**
 * Strictly parse this tool call's `input` into a JSON object, refusing anything
 * that is not exactly one well-formed JSON object.
 *
 * [inputAsJson] already exists for this and is deliberately permissive: it is a
 * rendering helper, and coercing unparsable input to `{}` is harmless when the
 * only consequence is a blank line in the UI. It is not harmless before dispatch.
 * A gateway that double-writes a call (`{"a":1}{"b":2}`), truncates one mid
 * argument, or the model itself emitting bare non-JSON text all produce *some*
 * string that `inputAsJson()` would silently fold into an empty object — and an
 * empty-or-partial object is not a parse failure to the tool, it is a *different,
 * plausible-looking* set of arguments the tool will happily execute. That is the
 * failure mode this guards against: bad arguments don't fail loudly, they succeed
 * with the wrong meaning, and the model reasons from the resulting output as if it
 * were true. Refusing to execute and handing the model a structured error back is
 * strictly cheaper than that — the cost is one visible retry, not an invisible
 * wrong answer.
 *
 * `json` is configured with `isLenient = true` (needed elsewhere to tolerate
 * relaxed provider output), which means a bare word like `not-json` parses
 * successfully as a string primitive rather than throwing — so the "is this
 * actually a JSON *object*" check below carries real weight and isn't redundant
 * with the try/catch.
 */
fun UIMessagePart.Tool.parseInputStrict(): ToolInputParse {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ToolInputParse.Valid(JsonObject(emptyMap()))
    val rawPrefix = input.take(TOOL_INPUT_RAW_PREFIX_LIMIT)
    val element = try {
        json.parseToJsonElement(trimmed)
    } catch (e: Exception) {
        return ToolInputParse.Invalid(
            message = "arguments were not valid JSON: ${e.message ?: e::class.simpleName}",
            rawPrefix = rawPrefix,
        )
    }
    return if (element is JsonObject) {
        ToolInputParse.Valid(element)
    } else {
        ToolInputParse.Invalid(
            message = "arguments parsed but were not a JSON object (was ${element::class.simpleName})",
            rawPrefix = rawPrefix,
        )
    }
}

fun UIMessagePart.Tool.streamToolIndex(): Int? =
    streamIndex ?: metadata?.get(STREAM_TOOL_INDEX_METADATA_KEY)?.jsonPrimitive?.intOrNull

fun UIMessagePart.Tool.responsesItemId(): String? =
    metadata?.get(RESPONSES_ITEM_ID_METADATA_KEY)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

fun thoughtSignatureMetadata(signature: String?): JsonObject? {
    val value = signature?.trim().orEmpty()
    if (value.isEmpty()) return null
    return buildJsonObject { put(THOUGHT_SIGNATURE_METADATA_KEY, value) }
}

fun UIMessagePart.Tool.thoughtSignature(): String? =
    metadata?.get(THOUGHT_SIGNATURE_METADATA_KEY)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * Build a tool part whose metadata is a real Kotlin [JsonObject].
 * Swift `[String: JsonElement]` is exported as NSDictionary and crashes
 * `JsonObject.get` on Kotlin/Native (Gemini 3.x thoughtSignature path).
 */
fun geminiToolPart(
    toolCallId: String,
    toolName: String,
    input: String,
    output: List<UIMessagePart> = emptyList(),
    streamIndex: Int? = null,
    thoughtSignature: String? = null,
): UIMessagePart.Tool = UIMessagePart.Tool(
    toolCallId = toolCallId,
    toolName = toolName,
    input = input,
    output = output,
    streamIndex = streamIndex,
    metadata = thoughtSignatureMetadata(thoughtSignature),
)

/**
 * Combine the tool name of an existing streaming part with that of a [delta].
 *
 * The function name is a tool's identity, not an accumulating buffer, yet
 * OpenAI-compatible gateways disagree on how they stream it:
 *  - standard: the name appears once, then later deltas carry only arguments;
 *  - re-send: the full name repeats on every chunk ("search" + "search");
 *  - progressive: the name grows across chunks ("sea" + "search");
 *  - fragmented: the name arrives in disjoint pieces ("sea" + "rch").
 *
 * Blind concatenation (the previous behaviour) turned a re-send into
 * "searchsearch", corrupting the echoed tool name and — once it rode along in
 * the continuation request — the gateway-side call. Combine so every variant
 * collapses to the single intended name.
 */
private fun mergeToolNames(existing: String, delta: String): String = when {
    delta.isEmpty() -> existing
    existing.isEmpty() -> delta
    existing == delta -> existing            // full re-send → no duplication
    delta.startsWith(existing) -> delta      // progressive re-send ("sea" → "search")
    existing.startsWith(delta) -> existing   // stale/shorter re-send of the same name
    else -> existing + delta                 // genuine fragmentation ("sea" + "rch")
}

/**
 * Whether a streaming [delta] may be merged into this tool part. Two deltas
 * belong to the same tool call only if their non-blank ids agree and their
 * non-blank names agree.
 *
 * Some OpenAI-compatible gateways (observed with MiMo) emit each parallel tool
 * call in its own chunk while reusing stream index 0. Without this guard a
 * second call (distinct id + name) gets merged into the first purely by index,
 * concatenating names ("subagent_dispatch" + "wm_stations") and arguments
 * ("{...}" + "{}") into one malformed tool. The doubled arguments then trip the
 * gateway's prefill JSON parser on the continuation turn
 * ("unexpected content after document"), surfacing as an HTTP 500.
 */
fun UIMessagePart.Tool.canMergeDelta(delta: UIMessagePart.Tool): Boolean {
    val sameResponsesItem = responsesItemId()?.let { it == delta.responsesItemId() } == true
    if (toolCallId.isNotBlank() && delta.toolCallId.isNotBlank() &&
        toolCallId != delta.toolCallId && !sameResponsesItem
    ) {
        return false
    }
    // Names must not contradict. A re-sent or progressively-growing name shares a
    // prefix with what we already have ("sea" → "search"); only treat them as
    // different calls when neither name is a prefix of the other. Genuinely
    // distinct parallel calls are already separated by the id check above (they
    // always carry distinct non-blank ids), so this only guards the id-less path.
    if (toolName.isNotBlank() && delta.toolName.isNotBlank() &&
        toolName != delta.toolName &&
        !toolName.startsWith(delta.toolName) && !delta.toolName.startsWith(toolName)
    ) {
        return false
    }
    return true
}

/**
 * Whether [input] already holds a balanced, complete JSON value — i.e. no more
 * argument fragments are expected. Used to stop a *new* tool call's fragments
 * from being appended onto a finished call that merely shares a stream index.
 * Returns true once the first balanced object/array closes, so a string that is
 * already `{...}{...}` also reads as "complete" (no further extension allowed).
 */
fun UIMessagePart.Tool.argsAreComplete(): Boolean {
    val s = input.trim()
    if (s.length < 2 || (s.first() != '{' && s.first() != '[')) return false
    var depth = 0
    var inString = false
    var escaped = false
    for (c in s) {
        if (escaped) {
            escaped = false
            continue
        }
        when {
            inString && c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            !inString && (c == '{' || c == '[') -> depth++
            !inString && (c == '}' || c == ']') -> {
                depth--
                if (depth == 0) return true
            }
        }
    }
    return false
}

/**
 * Whether an argument-fragment [delta] may extend this tool. It must be the same
 * call ([canMergeDelta]) and this call's args must not already be complete — a
 * non-empty fragment after a complete JSON document is exactly the corruption
 * this guards against ("unexpected content after document").
 */
fun UIMessagePart.Tool.canAcceptArgsDelta(delta: UIMessagePart.Tool): Boolean =
    canMergeDelta(delta) && !(delta.input.isNotBlank() && argsAreComplete())

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

fun UIMessage.finishPendingTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()
}

@Serializable
data class MessageChunk(
    val id: String,
    val model: String,
    val choices: List<UIMessageChoice>,
    val usage: TokenUsage? = null,
)

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
