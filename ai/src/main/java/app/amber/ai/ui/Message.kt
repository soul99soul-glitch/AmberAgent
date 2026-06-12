package app.amber.ai.ui

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
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

internal const val STREAM_TOOL_INDEX_METADATA_KEY = "stream_tool_index"
internal const val STREAM_TOOL_ARGS_REPLACE_METADATA_KEY = "stream_tool_args_replace"

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
//
// @Immutable: every constructor property is a val and either a primitive,
// a value type (Uuid, LocalDateTime, TokenUsage), or an immutable List of
// data classes. The annotation lets Compose skip recomposition of any
// Composable taking UIMessage as a parameter when the caller passes the
// same reference (e.g. an unchanged historical MessageNode in
// LazyColumn). UIMessagePart is intentionally NOT @Immutable — its
// `var metadata` would violate the contract; see UIMessagePart.
@Immutable
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
                        val target = deltaPart.findToolMergeTarget(acc.filterIsInstance<UIMessagePart.Tool>())
                        if (target == null) {
                            acc + deltaPart.withoutStreamArgsReplace()
                        } else {
                            acc.map { part ->
                                if (part === target) target.merge(deltaPart) else part
                            }
                        }
                    }

                    else -> {
                        println("delta part append not supported: $deltaPart")
                        acc
                    }
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
            // Handle annotations: append + dedupe, 不整体替换
            // (Google grounding / OpenAI citation 可能分多个 chunk 增量到达或重发全量)
            val newAnnotations = if (delta.annotations.isEmpty()) {
                annotations
            } else {
                (annotations + delta.annotations).distinct()
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
        val mime: String = "video/mp4",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        val fileName: String = "",
        val mime: String = "audio/mpeg",
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
            // replace 语义 (arguments.done / final message 的完整 tool args): 整体替换
            // 而不是流式 append, 否则 delta 累积 + done 全量会重复拼接 input
            val replaceArgs = other.isStreamArgsReplace()
            val incoming = if (replaceArgs) other.withoutStreamArgsReplace() else other
            return Tool(
                // OpenAI 流式 tool delta 的真实 id 可能晚于首个 (blank id) delta 到达,
                // 必须采纳后到的非空 id, 否则下游按 toolCallId 回填执行结果会失败
                toolCallId = toolCallId.ifBlank { incoming.toolCallId },
                toolName = if (replaceArgs) incoming.toolName.ifBlank { toolName } else toolName + incoming.toolName,
                input = when {
                    !replaceArgs -> input + incoming.input
                    // replace 不允许空内容抹掉已累积参数 (异常 provider 兜底)
                    incoming.input.isNotBlank() -> incoming.input
                    else -> input
                },
                output = output + incoming.output,
                approvalState = approvalState,
                metadata = if (incoming.metadata != null) incoming.metadata else metadata,
            )
        }
    }
}

// public: UI 层用它做 tool 卡片的稳定 Compose key (toolCallId 为空时的回退)
fun UIMessagePart.Tool.streamToolIndex(): Int? =
    metadata?.get(STREAM_TOOL_INDEX_METADATA_KEY)?.jsonPrimitive?.intOrNull

/**
 * 给 Tool part 附加流式 stream index 元数据 (合并入已有 metadata)。
 * provider parser 用它标记并行 tool delta 的归属，merge 层按此索引聚合。
 */
internal fun UIMessagePart.Tool.withStreamToolIndex(index: Int): UIMessagePart.Tool {
    val mergedMetadata = buildJsonObject {
        metadata?.forEach { (key, value) -> put(key, value) }
        put(STREAM_TOOL_INDEX_METADATA_KEY, index)
    }
    return copy(metadata = mergedMetadata)
}

/**
 * 标记该 Tool delta 的 input 为 replace 语义 (整体替换累积参数), 用于
 * OpenAI Responses 的 function_call_arguments.done / final message 全量 args。
 * 不带此标记的 delta 保持 append 语义 (arguments.delta 流式片段)。
 */
internal fun UIMessagePart.Tool.withStreamArgsReplace(): UIMessagePart.Tool {
    val mergedMetadata = buildJsonObject {
        metadata?.forEach { (key, value) -> put(key, value) }
        put(STREAM_TOOL_ARGS_REPLACE_METADATA_KEY, true)
    }
    return copy(metadata = mergedMetadata)
}

internal fun UIMessagePart.Tool.isStreamArgsReplace(): Boolean =
    metadata?.get(STREAM_TOOL_ARGS_REPLACE_METADATA_KEY)?.jsonPrimitive?.booleanOrNull == true

/**
 * 剥离 replace 控制标记 (返回防御性 copy)。merge 结果与新建 part 都不应携带
 * 流式控制元数据, 避免随消息持久化。
 */
internal fun UIMessagePart.Tool.withoutStreamArgsReplace(): UIMessagePart.Tool {
    val current = metadata ?: return copy()
    if (STREAM_TOOL_ARGS_REPLACE_METADATA_KEY !in current) return copy()
    val cleaned = JsonObject(current.filterKeys { it != STREAM_TOOL_ARGS_REPLACE_METADATA_KEY })
    return copy(metadata = cleaned.takeIf { it.isNotEmpty() })
}

/**
 * 流式 tool delta 的统一合并目标查找 (所有 merge 路径共享, 语义保持一致):
 *
 * 1. delta 带非空 toolCallId: 先按 id 匹配, 失败再按 streamToolIndex 匹配
 *    (覆盖 "先 index 后 id" 的 OpenAI 流式时序);
 * 2. delta 是 blank id: 只按 streamToolIndex 匹配;
 * 3. delta 既无 id 也无 index: 回退到最后一个 Tool (单 tool 流的兼容分支);
 * 4. blank id 且带 index 但找不到匹配: 返回 null, 新建 part, 避免串到别的 tool。
 */
internal fun UIMessagePart.Tool.findToolMergeTarget(
    tools: List<UIMessagePart.Tool>
): UIMessagePart.Tool? {
    // 已执行 (有 output) 的 tool 已封口, 不能再作为合并目标: 多轮 tool 循环
    // 共用同一条 assistant 消息, 第二轮的 stream index 从 0 重新计数, 不排除
    // 已执行项会把新一轮 delta 串进上一轮同 index/末尾的 tool。
    val candidates = tools.filter { !it.isExecuted }
    val streamIndex = streamToolIndex()
    val byStreamIndex = streamIndex?.let { index ->
        candidates.firstOrNull { it.streamToolIndex() == index }
    }
    return if (toolCallId.isBlank()) {
        byStreamIndex ?: if (streamIndex == null) candidates.lastOrNull() else null
    } else {
        candidates.firstOrNull { it.toolCallId == toolCallId } ?: byStreamIndex
    }
}

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

    /**
     * Marks an assistant message whose generation was cut off by a process
     * death (or other non-graceful stop) and later recovered from a stream
     * checkpoint. `reason` carries the interruption source, with a
     * ":stale_tail" suffix when the persisted content is known to lag the
     * last checkpoint (i.e. the streamed tail was partially lost).
     */
    @Serializable
    @SerialName("generation_interrupted")
    data class GenerationInterrupted(
        val reason: String = "",
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
