package app.amber.core.jev

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 长工具结果语义投影（CONTEXT_SELECTION 用途）。
 *
 * 只作用于"已完成、超过 [PROJECT_AFTER_CHARS] 字符的纯文本工具输出"：
 * 按段落/围栏块切分、程序先标 must-keep，剩余块经 Jev 判相关性，
 * 低相关且无保留信号的块替换为省略标记（可用 conversation_expand 恢复）。
 * 该投影只修改发往模型的请求副本；持久化会话与压缩摘要源始终是原文。
 * shadow/失败/超时/缺题一律原样返回。
 */
class JevToolOutputProjector(private val runtime: JevRuntime) {

    suspend fun projectMessages(
        messages: List<UIMessage>,
        runKey: String?,
    ): List<UIMessage> {
        if (runtime.configFor(JevPurpose.CONTEXT_SELECTION) == null) return messages
        val taskText = messages.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty()
        if (taskText.isBlank()) return messages
        var changed = false
        val projected = messages.map { message ->
            val parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    val next = projectTool(part, taskText, runKey)
                    if (next != part) {
                        changed = true
                        next
                    } else {
                        part
                    }
                } else {
                    part
                }
            }
            if (parts != message.parts) message.copy(parts = parts) else message
        }
        return if (changed) projected else messages
    }

    private suspend fun projectTool(tool: UIMessagePart.Tool, taskText: String, runKey: String?): UIMessagePart.Tool {
        // 与 PreparedContextEditor 相同的硬守卫：未执行/待审批/含多模态/工具级失败信号不筛。
        if (!tool.isExecuted) return tool
        if (tool.approvalState is ToolApprovalState.Pending) return tool
        val textParts = tool.output.filterIsInstance<UIMessagePart.Text>()
        if (textParts.isEmpty() || textParts.size != tool.output.size) return tool
        val fullText = textParts.joinToString("\n") { it.text }
        if (fullText.length <= PROJECT_AFTER_CHARS) return tool
        if (hasToolFailureSignal(fullText)) return tool

        val blocks = splitBlocks(fullText)
        if (blocks.size < MIN_BLOCKS) return tool
        val candidates = bundleBlocks(blocks)
        val keep = scoreBlocks(tool, candidates, taskText, runKey) ?: return tool
        if (keep.size >= candidates.size) return tool

        val rebuilt = buildString {
            candidates.forEachIndexed { index, bundle ->
                if (index in keep) {
                    append(bundle.text).append("\n\n")
                } else {
                    append(omissionMarker(tool.toolName, index, candidates.size)).append("\n\n")
                }
            }
        }.trimEnd()
        return tool.copy(output = listOf(UIMessagePart.Text(rebuilt)))
    }

    private suspend fun scoreBlocks(
        tool: UIMessagePart.Tool,
        bundles: List<BlockBundle>,
        taskText: String,
        runKey: String?,
    ): Set<Int>? {
        val state: JsonElement = buildJsonObject {
            put("task", "The user's current task the assistant is working on.")
            put("task_text", taskText.take(2_000))
            put("tool", tool.toolName)
            put("note", "Judge each block independently: does it contain information relevant to the task, or any result, error, constraint or identifier the assistant must respect?")
            put("blocks", buildJsonArray {
                bundles.forEach { bundle ->
                    add(buildJsonObject {
                        put("i", bundle.index.toString())
                        put("text", bundle.text.take(judgeSnippetChars(bundles.size)))
                    })
                }
            })
        }
        val questions = bundles.associate { bundle ->
            bundle.index.toString() to JevQuestion.Noul(
                instructions = "Is the block with i=${bundle.index} in state.blocks relevant to state.task_text or must it be kept for correctness?",
            )
        }
        val anchor = buildString {
            append("ctx|")
            append(tool.toolCallId)
            append('|')
            append(taskText.hashCode())
            append('|')
            append(bundles.joinToString(",") { it.text.hashCode().toString() }.hashCode())
        }
        val outcome = runtime.decide(
            purpose = JevPurpose.CONTEXT_SELECTION,
            runKey = runKey,
            state = state,
            questions = questions,
            requiredScopes = setOf(JevDataScope.TOOL_OUTPUT, JevDataScope.TASK_TEXT),
            cacheAnchor = anchor,
        ) ?: return null
        val evaluated = outcome.evaluated
        if (!outcome.applicable || evaluated == null) return null
        val snippet = judgeSnippetChars(bundles.size)
        val keep = HashSet<Int>()
        bundles.forEach { bundle ->
            val answer = evaluated.answers[bundle.index.toString()] as? JevAnswer.Noul
            // 判题只看前 snippet 字符；超过 2 倍的包大半内容不可见，强制保留。
            val tooBlindToJudge = bundle.text.length > snippet * 2
            if (bundle.mustKeep || tooBlindToJudge || answer == null || answer.probability >= KEEP_PROBABILITY) {
                keep += bundle.index
            }
        }
        return keep
    }

    /** 判题前缀预算：state 上限按包数均摊，1k~8k 之间。 */
    internal fun judgeSnippetChars(bundleCount: Int): Int =
        (JevLimits.MAX_STATE_BYTES / bundleCount.coerceAtLeast(1)).coerceIn(1_000, 8_000)


    companion object {
        /** 与 ToolResultCompactor 的截断阈值一致：仅处理显著长于既有裁剪线的结果。 */
        const val PROJECT_AFTER_CHARS = 8_000
        const val MIN_BLOCKS = 2
        const val KEEP_PROBABILITY = 0.35
        const val BUNDLE_TARGET_CHARS = 600

        private val TOOL_FAILURE_MARKERS = listOf(
            "\"status\":\"failed\"", "\"status\":\"denied\"", "\"status\":\"policy_denied\"",
            "\"approval_required\"", "\"error\"",
        )

        /** 工具级失败信号（与 PreparedContextEditor.looksFailedOrDenied 同款）：失败输出整体不筛。 */
        internal fun hasToolFailureSignal(text: String): Boolean {
            val lower = text.lowercase()
            return TOOL_FAILURE_MARKERS.any { lower.contains(it) }
        }

        private val MUST_KEEP_MARKERS = listOf(
            "error", "failed", "exception", "traceback", "denied", "approval",
            "pending", "waiting", "permission", "policy", "next_page", "page_token",
            "cursor", "todo", "credential", "unresolved", "required",
        )

        /** 程序级保留信号：错误/审批/未知状态/分页 token/未决事项，命中即整块保留。 */
        internal fun isMustKeepBlock(block: String): Boolean {
            val lower = block.lowercase()
            return MUST_KEEP_MARKERS.any { lower.contains(it) }
        }

        /** 按空行分段；围栏代码块（```）内部不拆，保持 JSON/代码/表格结构完整。 */
        internal fun splitBlocks(text: String): List<String> {
            val blocks = mutableListOf<String>()
            val current = StringBuilder()
            var inFence = false
            for (line in text.lineSequence()) {
                if (line.trim().startsWith("```")) {
                    inFence = !inFence
                    current.append(line).append('\n')
                    continue
                }
                if (!inFence && line.isBlank() && current.isNotBlank()) {
                    blocks += current.toString().trimEnd('\n')
                    current.clear()
                } else {
                    current.append(line).append('\n')
                }
            }
            if (current.isNotBlank()) blocks += current.toString().trimEnd('\n')
            return blocks
        }

        internal class BlockBundle(val index: Int, val text: String, val mustKeep: Boolean)

        /**
         * 相邻块合并到约 target 字符；超题数时加倍 target 重打包直到 ≤32 题，
         * 避免最长输出（本功能主要目标）静默落入 TOO_MANY_QUESTIONS 回退。
         * must-keep 信号按合并后的整包生效。
         */
        internal fun bundleBlocks(blocks: List<String>): List<BlockBundle> {
            var target = if (blocks.size > JevLimits.MAX_QUESTIONS_PER_REQUEST) {
                blocks.sumOf { it.length } / JevLimits.MAX_QUESTIONS_PER_REQUEST
            } else {
                BUNDLE_TARGET_CHARS
            }.coerceAtLeast(200)
            var bundles = build(blocks, target)
            while (bundles.size > JevLimits.MAX_QUESTIONS_PER_REQUEST) {
                target *= 2
                bundles = build(blocks, target)
            }
            return bundles
        }

        private fun build(blocks: List<String>, target: Int): List<BlockBundle> {
            val bundles = mutableListOf<BlockBundle>()
            val buffer = StringBuilder()
            fun flush() {
                if (buffer.isNotBlank()) {
                    val text = buffer.toString().trimEnd('\n')
                    bundles += BlockBundle(bundles.size, text, isMustKeepBlock(text))
                    buffer.clear()
                }
            }
            blocks.forEach { block ->
                if (buffer.isNotEmpty() && buffer.length + block.length > target) flush()
                buffer.append(block).append("\n\n")
            }
            flush()
            return bundles
        }

        internal fun omissionMarker(toolName: String, index: Int, total: Int): String =
            "[omitted by context filter: block ${index + 1}/$total of $toolName output was judged irrelevant to the current task. " +
                "Call conversation_expand if the original text is needed.]"
    }
}
