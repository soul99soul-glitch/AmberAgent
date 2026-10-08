package app.amber.core.jev

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.context.PreparedContextEditor
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 压缩保留（TOOL_RESULT_RETENTION 用途）。
 *
 * 较早的可清除工具结果移出保留窗口时会被 [PreparedContextEditor] 清空。模型已对
 * 某条结果作出反应、它仍在保留窗口内时，后台问 Jev 一次"原文是否仍需保留"；
 * 移出窗口时按固定结果决定是否跳过清空。请求准备路径从不等待网络：未判定完
 * 即按原行为清空并固定，同一结果在后续请求中不会在"清空/原文"之间翻转。
 * 只有 active 判定为保留的结果才跳过清空；shadow/失败/超时一律照旧清空。
 */
class JevToolResultRetention(private val runtime: JevRuntime) {

    private data class Key(val conversationId: String, val toolCallId: String, val outputHash: Int)

    private class Candidate(val key: Key, val messageIndex: Int, val tool: UIMessagePart.Tool)

    /** 固定后的判定：true = 跳过清空。 */
    private val decisions = LinkedHashMap<Key, Boolean>()
    private val inFlight = HashSet<Key>()

    /** 返回应保留原文（跳过清空）的 toolCallId；同时为保留窗口内的新候选发起后台判断。 */
    fun retainedToolCallIds(
        messages: List<UIMessage>,
        conversationId: String,
        keepRecentMessages: Int,
        runKey: String?,
    ): Set<String> {
        val purpose = JevPurpose.TOOL_RESULT_RETENTION
        runtime.configFor(purpose) ?: return emptySet()
        val boundary = messages.size - keepRecentMessages.coerceAtLeast(0)
        val retained = HashSet<String>()
        val undecided = ArrayList<Candidate>()
        synchronized(this) {
            candidates(messages, conversationId).forEach { candidate ->
                val decision = decisions[candidate.key]
                if (candidate.messageIndex < boundary) {
                    // 已固定的保留在切到 shadow 后仍生效：翻回清空会让 prompt 前缀缓存失效一次。
                    if (decision == true) retained += candidate.key.toolCallId
                    // 移出窗口时仍未判定：固定为原行为，迟到的结果不再改变它。
                    if (decision == null) decisions[candidate.key] = false
                } else if (decision == null && candidate.key !in inFlight) {
                    undecided += candidate
                }
            }
            val batch = undecided.take(MAX_CANDIDATES_PER_REQUEST)
            batch.forEach { inFlight += it.key }
            if (batch.isNotEmpty()) {
                runtime.launchInBackground { evaluate(batch, messages, backgroundRunKey(runKey, JevPurpose.TOOL_RESULT_RETENTION)) }
            }
            trimDecisions(conversationId)
        }
        return retained
    }

    private fun candidates(messages: List<UIMessage>, conversationId: String): List<Candidate> {
        val lastAssistant = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        val result = ArrayList<Candidate>()
        messages.forEachIndexed { index, message ->
            // 模型已对该结果作出反应（其后已有 assistant 消息）才值得判断。
            if (index >= lastAssistant) return@forEachIndexed
            message.parts.forEach { part ->
                if (part is UIMessagePart.Tool && PreparedContextEditor.wouldClearToolResult(part, message)) {
                    val key = Key(conversationId, part.toolCallId, outputText(part).hashCode())
                    result += Candidate(key, index, part)
                }
            }
        }
        return result.distinctBy { it.key }
    }

    private suspend fun evaluate(batch: List<Candidate>, messages: List<UIMessage>, runKey: String?) {
        val purpose = JevPurpose.TOOL_RESULT_RETENTION
        val threshold = runtime.policy.toolResultRetentionKeepProbability
        val labels = batch.mapIndexed { index, candidate -> candidate.key.toolCallId to "r$index" }.toMap()
        val state = buildJsonObject {
            put("task", "An assistant conversation. Older tool results are about to be cleared from the model context to save space; they can be re-fetched by calling the tool again.")
            put("outline", outline(messages, labels))
            put("results", buildJsonArray {
                batch.forEachIndexed { index, candidate ->
                    val text = outputText(candidate.tool).replace('\n', ' ')
                    val sample = if (text.length > 800) text.take(500) + " … " + text.takeLast(300) else text
                    add(buildJsonObject {
                        put("id", "r$index")
                        put("tool", candidate.tool.toolName)
                        put("sample", sample)
                    })
                }
            })
        }
        val questions = batch.indices.associate { index ->
            "r$index" to JevQuestion.Noul(
                instructions = "Does the exact text of result r$index in state.results still need to be kept verbatim for later steps " +
                    "(specific content, numbers, code or paths will be referenced again)? Answer no if its point has already been used, " +
                    "the task has moved on, or it can simply be fetched again when needed.",
            )
        }
        val anchor = "retain|" + batch.joinToString(",") { "${it.key.toolCallId}:${it.key.outputHash}" }.hashCode()
        val outcome = runCatching {
            runtime.decide(
                purpose = purpose,
                runKey = runKey,
                state = state,
                questions = questions,
                requiredScopes = JevPurpose.TOOL_RESULT_RETENTION.requiredScopes,
                cacheAnchor = anchor,
            )
        }.getOrNull()
        val evaluated = outcome?.evaluated
        val scores = batch.indices.mapNotNull { index ->
            (evaluated?.answers?.get("r$index") as? JevAnswer.Noul)?.let { "r$index" to it.probability }
        }.toMap()
        synchronized(this) {
            batch.forEachIndexed { index, candidate ->
                inFlight -= candidate.key
                if (candidate.key !in decisions) {
                    val keep = outcome?.applicable == true && (scores["r$index"] ?: 0.0) >= threshold
                    decisions[candidate.key] = keep
                }
            }
        }
        if (outcome != null && evaluated != null && !outcome.stale) {
            runtime.calibration.append(
                JevCalibrationRecord(
                    timestamp = System.currentTimeMillis(),
                    purpose = purpose,
                    mode = outcome.mode,
                    model = evaluated.model,
                    latencyMs = evaluated.latencyMs,
                    threshold = threshold,
                    scores = scores,
                    incumbentTop1 = null,
                    jevTop1 = null,
                ),
            )
        }
    }

    /** 对话大纲：用户消息、助手文本、工具调用单行摘要；超长时保留开头的任务描述与最近的对话。 */
    private fun outline(messages: List<UIMessage>, labels: Map<String, String>): String {
        val lines = ArrayList<String>()
        messages.forEach { message ->
            message.parts.forEach { part ->
                when {
                    part is UIMessagePart.Text && part.text.isNotBlank() -> {
                        val compact = part.text.replace('\n', ' ')
                        when (message.role) {
                            MessageRole.USER -> lines += "user: " + compact.take(400)
                            MessageRole.ASSISTANT -> lines += "assistant: " + compact.take(200)
                            else -> Unit
                        }
                    }
                    part is UIMessagePart.Tool && part.isExecuted -> {
                        val label = labels[part.toolCallId]?.let { "[$it] " }.orEmpty()
                        lines += "${label}tool ${part.toolName}(${part.input.replace('\n', ' ').take(120)}) -> ${outputText(part).length} chars"
                    }
                }
            }
        }
        val head = lines.take(2)
        val tail = ArrayDeque<String>()
        var used = head.sumOf { it.length }
        for (line in lines.drop(2).asReversed()) {
            if (used + line.length > MAX_OUTLINE_CHARS) break
            tail.addFirst(line)
            used += line.length
        }
        val gap = if (tail.size < lines.size - head.size) listOf("… (earlier turns omitted)") else emptyList()
        return (head + gap + tail).joinToString("\n")
    }

    private fun outputText(tool: UIMessagePart.Tool): String =
        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun trimDecisions(conversationId: String) {
        if (decisions.size <= MAX_DECISIONS) return
        decisions.keys.removeAll { it.conversationId != conversationId }
    }

    companion object {
        const val MAX_CANDIDATES_PER_REQUEST = 8
        /** state 上限按 UTF-8 字节计（48KB）；中文约 3 字节/字，大纲与 8 条样本合计须留在上限内。 */
        private const val MAX_OUTLINE_CHARS = 6_000
        private const val MAX_DECISIONS = 512
    }
}
