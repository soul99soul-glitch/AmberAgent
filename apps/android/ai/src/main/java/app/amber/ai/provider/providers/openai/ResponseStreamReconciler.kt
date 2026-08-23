package app.amber.ai.provider.providers.openai

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isStreamArgsReplace
import app.amber.ai.ui.withStreamArgsReplace
import app.amber.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.Uuid

/**
 * ResponseAPI 流式 chunk 的调和器, 每个流一个实例。解决两类问题:
 *
 * 1. id 标准化: ResponseAPI 各事件构造的 UIMessage 都用 fresh Uuid.random(),
 *    ChatService merge by-id 找不到会 APPEND orphan node。所有 ASSISTANT chunk
 *    的 id 统一为流级 sharedId。
 *
 * 2. final message 调和: done/completed/incomplete 终止事件带 message=完整内容,
 *    delta=null。直接放行会触发 MessageStreamAccumulator.replaceActive 重置已
 *    累积 parts (内容"闪一下消失"); 旧实现无条件转空 delta, 又会丢掉只出现在
 *    final message 里的 refusal / annotations / 完整 tool args / final-only text。
 *    本类跟踪流内已累积的 text / tool args / reasoning, 把 final message 与累积
 *    做 diff: 一致部分只保留 usage / annotations / finishReason; 累积中缺失的
 *    部分降级为增量 delta 合并。
 *
 * 注意 id 体系: 流式 tool delta 以 item id (fc_...) 为 toolCallId, 而
 * parseResponseOutput 的 final tool 用 call_id (call_...)。匹配优先用
 * output_item.added 时记录的 call_id 元数据, 失败再按出现顺序配对。
 */
internal class ResponseStreamReconciler {
    private val streamAssistantId = Uuid.random()

    // item_id -> 已累积的可见文本 (output_text + refusal deltas 同属一个 message item)
    private val itemTexts = LinkedHashMap<String, StringBuilder>()

    // 流式 toolCallId (item id) -> 累积状态, 按到达顺序
    private val streamedTools = LinkedHashMap<String, StreamedTool>()

    private var hasStreamedReasoning = false

    private class StreamedTool(val id: String) {
        val args = StringBuilder()
        var name = ""
        var callId: String? = null
    }

    fun reconcile(chunk: MessageChunk): MessageChunk = chunk.copy(
        choices = chunk.choices.map { choice -> reconcileChoice(chunk.id, choice) }
    )

    private fun reconcileChoice(chunkId: String, choice: UIMessageChoice): UIMessageChoice {
        val delta = choice.delta
        val message = choice.message
        return when {
            delta != null && delta.role == MessageRole.ASSISTANT -> {
                trackDelta(chunkId, delta)
                choice.copy(delta = delta.copy(id = streamAssistantId))
            }

            delta == null && message != null && message.role == MessageRole.ASSISTANT ->
                choice.copy(
                    delta = buildReconciledDelta(chunkId, message),
                    message = null,
                )

            else -> choice
        }
    }

    private fun trackDelta(chunkId: String, delta: UIMessage) {
        delta.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                    itemTexts.getOrPut(chunkId) { StringBuilder() }.append(part.text)
                }

                is UIMessagePart.Tool -> trackTool(part)

                is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty()) {
                    hasStreamedReasoning = true
                }

                else -> {}
            }
        }
    }

    private fun trackTool(part: UIMessagePart.Tool) {
        // ResponseAPI 的 tool delta 总带 item_id; blank id 不建状态, 避免误配
        if (part.toolCallId.isBlank()) return
        val state = streamedTools.getOrPut(part.toolCallId) { StreamedTool(part.toolCallId) }
        if (part.isStreamArgsReplace()) {
            // 与 Tool.merge 的 replace 语义保持一致: 非空才整体替换
            if (part.input.isNotBlank()) {
                state.args.setLength(0)
                state.args.append(part.input)
            }
            if (part.toolName.isNotBlank()) state.name = part.toolName
        } else {
            state.args.append(part.input)
            state.name += part.toolName
        }
        part.metadata?.get(OPENAI_TOOL_CALL_ID_METADATA_KEY)?.jsonPrimitiveOrNull?.contentOrNull
            ?.let { state.callId = it }
    }

    private fun buildReconciledDelta(chunkId: String, message: UIMessage): UIMessage {
        val parts = mutableListOf<UIMessagePart>()

        reconcileText(chunkId, message)?.let { parts += it }

        // reasoning 正常经 reasoning delta 事件累积; 只有完全没流过 reasoning 时
        // 才接收 final-only 的 summary, 否则会重复
        if (!hasStreamedReasoning) {
            message.parts.filterIsInstance<UIMessagePart.Reasoning>()
                .filter { it.reasoning.isNotBlank() }
                .forEach { parts += it }
        }

        parts += reconcileTools(message)

        return UIMessage(
            id = streamAssistantId,
            role = MessageRole.ASSISTANT,
            parts = parts,
            // accumulator 对 annotations 做 append + dedupe, 直接透传即可
            annotations = message.annotations,
        )
    }

    private fun reconcileText(chunkId: String, message: UIMessage): UIMessagePart.Text? {
        val finalText = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        if (finalText.isEmpty()) return null

        // item 级终止事件 (output_text.done / output_item.done) 按 item_id 取累积;
        // response 级 (completed / incomplete) 的 chunk.id 是 response id, 不在
        // itemTexts 里, 回退到全部累积文本的聚合比较
        val accumulated = itemTexts[chunkId]?.toString()
            ?: itemTexts.values.joinToString("") { it.toString() }

        val suffix = when {
            accumulated.isEmpty() -> finalText
            finalText == accumulated -> ""
            // final 比累积多出尾部 (refusal / 漏发的 delta): 只补差量
            finalText.startsWith(accumulated) -> finalText.removePrefix(accumulated)
            // final 是累积的子串 (item 级事件重发已流过的内容): 跳过
            accumulated.contains(finalText) -> ""
            // 冲突 (provider 对 final 做了归一化等): 保守保留流式累积, 避免重复
            else -> ""
        }
        if (suffix.isEmpty()) return null

        // 记账, 让后续 completed 的聚合比较看到这段文本
        itemTexts.getOrPut(chunkId) { StringBuilder() }.append(suffix)
        return UIMessagePart.Text(suffix)
    }

    private fun reconcileTools(message: UIMessage): List<UIMessagePart> {
        val finalTools = message.parts.filterIsInstance<UIMessagePart.Tool>()
        if (finalTools.isEmpty()) return emptyList()

        val streamedList = streamedTools.values.toList()
        val matches = arrayOfNulls<StreamedTool>(finalTools.size)
        val matched = mutableSetOf<StreamedTool>()

        // pass 1: 按 call_id / item id 精确匹配
        finalTools.forEachIndexed { index, finalTool ->
            val state = streamedList.firstOrNull {
                it.callId == finalTool.toolCallId || it.id == finalTool.toolCallId
            }
            if (state != null && matched.add(state)) matches[index] = state
        }
        // pass 2: 剩余的按出现顺序配对 (fc_/call_ 两套 id 对不上时的兜底)
        val unmatched = streamedList.filterNot { it in matched }.toMutableList()
        finalTools.forEachIndexed { index, _ ->
            if (matches[index] == null && unmatched.isNotEmpty()) {
                matches[index] = unmatched.removeAt(0)
            }
        }

        val parts = mutableListOf<UIMessagePart>()
        finalTools.forEachIndexed { index, finalTool ->
            val state = matches[index]
            if (state == null) {
                // 流中完全没出现过的 tool: 整体作为增量新 part
                parts += finalTool.copy()
                return@forEachIndexed
            }
            val argsDiffer = finalTool.input.isNotBlank() && finalTool.input != state.args.toString()
            val nameMissing = state.name.isBlank() && finalTool.toolName.isNotBlank()
            if (argsDiffer || nameMissing) {
                // 以流式 id 定位累积 part, replace 语义覆盖参数
                parts += UIMessagePart.Tool(
                    toolCallId = state.id,
                    toolName = finalTool.toolName,
                    input = finalTool.input,
                    output = emptyList(),
                ).withStreamArgsReplace()
            }
        }
        return parts
    }
}
