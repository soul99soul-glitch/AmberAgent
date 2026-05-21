package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/** Tool names whose calls represent a single subagent task and should be coalesced by run_id. */
private val SUBAGENT_TASK_TOOLS = setOf(
    "subagent_start",
    "subagent_wait",
    "subagent_read",
    "subagent_cancel",
)

/** Tool names whose calls represent a single Model Council run and should be coalesced by run_id. */
private val COUNCIL_TASK_TOOLS = setOf(
    "model_council_start",
    "model_council_wait",
    "model_council_read",
    "model_council_cancel",
    // make_report writes a file; not strictly part of the run, but it's the same run_id and the
    // user mentally groups it with the council card. Coalescing is fine.
    "model_council_make_report",
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

    /**
     * One Model Council run: all of its `model_council_*` tool calls sharing one `run_id` folded
     * into a single rendered card. Same identity/coalescing pattern as [SubAgentTaskStep].
     */
    data class CouncilTaskStep(
        val runId: String,
        val tools: List<UIMessagePart.Tool>,
    ) : ThinkingStep {
        val anchor: UIMessagePart.Tool
            get() = tools.firstOrNull { it.toolName == "model_council_start" } ?: tools.first()
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

    /** Same as [SubAgentBlock] but for Model Council runs. */
    data class CouncilBlock(val step: ThinkingStep.CouncilTaskStep) : MessagePartBlock
}

/**
 * Extract the `run_id` this tool call belongs to. For subagent_start, parse it from the result
 * JSON; for the rest, parse it from the call input. Returns null for any other tool, or when
 * the result hasn't arrived yet.
 */
private fun UIMessagePart.Tool.subagentRunId(): String? {
    if (toolName !in SUBAGENT_TASK_TOOLS) return null
    return extractRunId(startToolName = "subagent_start")
}

/** Same idea as [subagentRunId] but for `model_council_*` tool family. */
private fun UIMessagePart.Tool.councilRunId(): String? {
    if (toolName !in COUNCIL_TASK_TOOLS) return null
    return extractRunId(startToolName = "model_council_start")
}

/**
 * Shared run-id extraction logic for both subagent and council tool families. The "start" call
 * gets its run_id from output (server-assigned); every other call carries it in its input.
 */
private fun UIMessagePart.Tool.extractRunId(startToolName: String): String? = runCatching {
    if (toolName == startToolName) {
        MessageRenderCache.toolOutputJson(output).jsonObject["run_id"]
            ?.jsonPrimitive?.contentOrNull
    } else {
        MessageRenderCache.toolInputJson(input).jsonObject["run_id"]
            ?.jsonPrimitive?.contentOrNull
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Fold consecutive subagent_* tool steps that share a `run_id` into a single SubAgentTaskStep.
 * Order is preserved by inserting the merged step at the position of the first occurrence.
 * Tools whose run_id can't be resolved (e.g. subagent_start with no result yet) stay as plain
 * ToolStep and will coalesce once the result arrives on the next render pass.
 */
private fun coalesceSubAgentSteps(steps: List<ThinkingStep>): List<ThinkingStep> =
    coalesceTaskSteps(
        steps = steps,
        runIdOf = { it.subagentRunId() },
        wrap = { runId, tools -> ThinkingStep.SubAgentTaskStep(runId, tools) },
    )

/** Same shape as [coalesceSubAgentSteps] but for `model_council_*` tools. */
private fun coalesceCouncilSteps(steps: List<ThinkingStep>): List<ThinkingStep> =
    coalesceTaskSteps(
        steps = steps,
        runIdOf = { it.councilRunId() },
        wrap = { runId, tools -> ThinkingStep.CouncilTaskStep(runId, tools) },
    )

/**
 * Generic coalesce: walks [steps], pulls each ToolStep through [runIdOf]; matching tools
 * (same run_id) get folded into one wrapped step at the position of the first occurrence.
 * Tools that don't match (runIdOf returns null) pass through unchanged.
 */
private inline fun coalesceTaskSteps(
    steps: List<ThinkingStep>,
    runIdOf: (UIMessagePart.Tool) -> String?,
    wrap: (String, List<UIMessagePart.Tool>) -> ThinkingStep,
): List<ThinkingStep> {
    val out = mutableListOf<ThinkingStep>()
    val acc = LinkedHashMap<String, MutableList<UIMessagePart.Tool>>()
    val placeholderIndex = HashMap<String, Int>()

    for (step in steps) {
        if (step is ThinkingStep.ToolStep) {
            val runId = runIdOf(step.tool)
            if (runId != null) {
                val bucket = acc.getOrPut(runId) {
                    placeholderIndex[runId] = out.size
                    out.add(wrap(runId, emptyList()))
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
        out[pos] = wrap(runId, tools.toList())
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
    var pendingTextBuilder: StringBuilder? = null
    var pendingTextIndex = -1

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isEmpty()) return
        // Run both coalescers — order matters only because they walk the same list; either
        // order would produce the same final fold (the two tool families don't overlap).
        val coalesced = coalesceCouncilSteps(coalesceSubAgentSteps(currentThinkingSteps))
        // Split off both task-step types into their own top-level blocks so they don't get
        // hidden by ChainOfThought's collapse logic.
        var pending = mutableListOf<ThinkingStep>()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                result.add(MessagePartBlock.ThinkingBlock(pending.toList()))
                pending = mutableListOf()
            }
        }
        for (step in coalesced) {
            when (step) {
                is ThinkingStep.SubAgentTaskStep -> {
                    flushPending()
                    result.add(MessagePartBlock.SubAgentBlock(step))
                }
                is ThinkingStep.CouncilTaskStep -> {
                    flushPending()
                    result.add(MessagePartBlock.CouncilBlock(step))
                }
                else -> pending.add(step)
            }
        }
        flushPending()
        currentThinkingSteps = mutableListOf()
    }

    fun flushText() {
        pendingText?.let { textPart ->
            val mergedText = pendingTextBuilder?.toString() ?: textPart.text
            result.add(MessagePartBlock.ContentBlock(textPart.copy(text = mergedText), pendingTextIndex))
        }
        pendingText = null
        pendingTextBuilder = null
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
                    pendingTextBuilder = StringBuilder(part.text)
                    part
                } else {
                    pendingTextBuilder?.append(part.text)
                    previous.copy(
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
    return result.mergeDuplicateRunBlocks()
}

/**
 * Coalesce SubAgent/Council blocks that share a `runId` into a single block, preserving the
 * position of the first occurrence and concatenating their tool calls. Without this pass a
 * subagent run whose calls are split by an intervening Text part would emit two
 * `SubAgentBlock`s (one per `flushThinkingSteps` invocation) — both keyed on the same
 * `subagent-${runId}`, which crashes LazyColumn with `IllegalArgumentException: Key …
 * was already used`.
 */
private fun List<MessagePartBlock>.mergeDuplicateRunBlocks(): List<MessagePartBlock> {
    val subAgentIndex = HashMap<String, Int>()
    val councilIndex = HashMap<String, Int>()
    val merged = mutableListOf<MessagePartBlock>()
    forEach { block ->
        when (block) {
            is MessagePartBlock.SubAgentBlock -> {
                val runId = block.step.runId
                val existing = subAgentIndex[runId]
                if (existing == null) {
                    subAgentIndex[runId] = merged.size
                    merged.add(block)
                } else {
                    val prior = merged[existing] as MessagePartBlock.SubAgentBlock
                    merged[existing] = MessagePartBlock.SubAgentBlock(
                        step = ThinkingStep.SubAgentTaskStep(
                            runId = runId,
                            tools = prior.step.tools + block.step.tools,
                        )
                    )
                }
            }

            is MessagePartBlock.CouncilBlock -> {
                val runId = block.step.runId
                val existing = councilIndex[runId]
                if (existing == null) {
                    councilIndex[runId] = merged.size
                    merged.add(block)
                } else {
                    val prior = merged[existing] as MessagePartBlock.CouncilBlock
                    merged[existing] = MessagePartBlock.CouncilBlock(
                        step = ThinkingStep.CouncilTaskStep(
                            runId = runId,
                            tools = prior.step.tools + block.step.tools,
                        )
                    )
                }
            }

            else -> merged.add(block)
        }
    }
    return merged
}
