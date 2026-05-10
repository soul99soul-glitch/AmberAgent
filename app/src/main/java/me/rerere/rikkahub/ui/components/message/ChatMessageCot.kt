package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

/** Tool names whose calls represent a single subagent task and should be coalesced by run_id. */
private val SUBAGENT_TASK_TOOLS = setOf(
    "subagent_start",
    "subagent_wait",
    "subagent_read",
    "subagent_cancel",
)

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    /**
     * One subagent task: all of its `subagent_start / subagent_wait / subagent_read / subagent_cancel`
     * tool calls (sharing the same `run_id`), folded into a single rendered card.
     *
     * Note: a subagent_start whose result hasn't arrived yet has no `run_id` available; in that
     * case it briefly renders as a regular ToolStep and gets coalesced once the output appears.
     */
    data class SubAgentTaskStep(
        val runId: String,
        val tools: List<UIMessagePart.Tool>,
    ) : ThinkingStep {
        /** Prefer the start call as the anchor since it carries the subagent_id in its input. */
        val anchor: UIMessagePart.Tool
            get() = tools.firstOrNull { it.toolName == "subagent_start" } ?: tools.first()
    }
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock

    /**
     * Standalone subagent task block — lifted OUT of the surrounding ThinkingBlock so the card
     * survives the ChainOfThought collapse and remains visible (and clickable to reopen the
     * run sheet) even after the message finishes streaming.
     */
    data class SubAgentBlock(val step: ThinkingStep.SubAgentTaskStep) : MessagePartBlock
}

/**
 * Extract the `run_id` this tool call belongs to. For subagent_start, parse it from the result
 * JSON; for the rest, parse it from the call input. Returns null for any other tool, or when
 * the result hasn't arrived yet.
 */
private fun UIMessagePart.Tool.subagentRunId(): String? {
    if (toolName !in SUBAGENT_TASK_TOOLS) return null
    return runCatching {
        when (toolName) {
            "subagent_start" -> {
                val outputText = output.filterIsInstance<UIMessagePart.Text>()
                    .firstOrNull()?.text ?: return null
                JsonInstant.parseToJsonElement(outputText).jsonObject["run_id"]
                    ?.jsonPrimitive?.contentOrNull
            }
            else -> {
                JsonInstant.parseToJsonElement(input).jsonObject["run_id"]
                    ?.jsonPrimitive?.contentOrNull
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Fold consecutive subagent_* tool steps that share a `run_id` into a single SubAgentTaskStep.
 * Order is preserved by inserting the merged step at the position of the first occurrence.
 * Tools whose run_id can't be resolved (e.g. subagent_start with no result yet) stay as plain
 * ToolStep and will coalesce once the result arrives on the next render pass.
 */
private fun coalesceSubAgentSteps(steps: List<ThinkingStep>): List<ThinkingStep> {
    val out = mutableListOf<ThinkingStep>()
    val acc = LinkedHashMap<String, MutableList<UIMessagePart.Tool>>()
    val placeholderIndex = HashMap<String, Int>()

    for (step in steps) {
        if (step is ThinkingStep.ToolStep) {
            val runId = step.tool.subagentRunId()
            if (runId != null) {
                val bucket = acc.getOrPut(runId) {
                    placeholderIndex[runId] = out.size
                    out.add(ThinkingStep.SubAgentTaskStep(runId, emptyList()))
                    mutableListOf()
                }
                bucket.add(step.tool)
                continue
            }
        }
        out.add(step)
    }
    acc.forEach { (runId, tools) ->
        val pos = placeholderIndex.getValue(runId)
        out[pos] = ThinkingStep.SubAgentTaskStep(runId, tools.toList())
    }
    return out
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()
    var pendingText: UIMessagePart.Text? = null
    var pendingTextIndex = -1

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isEmpty()) return
        val coalesced = coalesceSubAgentSteps(currentThinkingSteps)
        // Split off SubAgentTaskStep into its own top-level block so it doesn't get hidden by
        // ChainOfThought's collapse logic. Surrounding reasoning/tool steps remain grouped.
        var pending = mutableListOf<ThinkingStep>()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                result.add(MessagePartBlock.ThinkingBlock(pending.toList()))
                pending = mutableListOf()
            }
        }
        for (step in coalesced) {
            if (step is ThinkingStep.SubAgentTaskStep) {
                flushPending()
                result.add(MessagePartBlock.SubAgentBlock(step))
            } else {
                pending.add(step)
            }
        }
        flushPending()
        currentThinkingSteps = mutableListOf()
    }

    fun flushText() {
        pendingText?.let {
            result.add(MessagePartBlock.ContentBlock(it, pendingTextIndex))
        }
        pendingText = null
        pendingTextIndex = -1
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                if (part.reasoning.isNotBlank()) {
                    flushText()
                    currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
                }
            }

            is UIMessagePart.Tool -> {
                flushText()
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            is UIMessagePart.Text -> {
                flushThinkingSteps()
                val previous = pendingText
                pendingText = if (previous == null) {
                    pendingTextIndex = index
                    part
                } else {
                    previous.copy(
                        text = previous.text + part.text,
                        metadata = part.metadata ?: previous.metadata,
                    )
                }
            }

            else -> {
                flushText()
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushText()
    flushThinkingSteps()
    return result
}
