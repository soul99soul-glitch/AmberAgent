package me.rerere.rikkahub.data.agent.board.agent

import me.rerere.rikkahub.data.agent.board.TodayBoardDensity
import me.rerere.rikkahub.data.agent.board.aggregator.ScoredSignal

/**
 * Validates [BoardAgentOutput] against the signals the agent was given.
 *
 * Responsibilities:
 *  - Drop items whose `source_ref` doesn't match any input signal (hallucination guard).
 *  - Normalize urgency / category to the known vocabulary.
 *  - Enforce density caps per category, keeping the highest-urgency items first.
 *  - Cap summary length.
 *
 * Returns a filtered [BoardAgentOutput] plus a list of warnings describing what was
 * rejected. Warnings are purely informational — they're surfaced in logs, not to the
 * user, so the board stays calm even when the model misbehaves.
 */
object BoardOutputValidator {
    private val VALID_URGENCY = setOf("high", "medium", "low")
    private val VALID_CATEGORY = setOf("action", "attention", "info")
    private const val SUMMARY_MAX = 240

    data class ValidationResult(
        val output: BoardAgentOutput,
        val warnings: List<String>,
    )

    fun validate(
        raw: BoardAgentOutput,
        signalsByRef: Map<String, ScoredSignal>,
        density: TodayBoardDensity,
    ): ValidationResult {
        val caps = BoardPrompt.densityCaps(density)
        val warnings = mutableListOf<String>()

        val normalized = raw.items.mapNotNull { item ->
            val signal = signalsByRef[item.source_ref]
            if (signal == null) {
                warnings += "drop: source_ref not in input (${item.source_ref.take(40)})"
                return@mapNotNull null
            }
            val urgency = item.urgency.lowercase().trim().let {
                if (it in VALID_URGENCY) it else "medium".also {
                    warnings += "coerce urgency '${item.urgency}' -> medium"
                }
            }
            val category = item.category.lowercase().trim().let {
                if (it in VALID_CATEGORY) it else "attention".also {
                    warnings += "coerce category '${item.category}' -> attention"
                }
            }
            val title = item.title.trim()
            if (title.isBlank()) {
                warnings += "drop: blank title for ref ${item.source_ref.take(40)}"
                return@mapNotNull null
            }
            // Prefer the agent's signal_time but fall back to the signal's own time when
            // the agent forgot to echo it.
            val signalTime = if (item.signal_time > 0L) item.signal_time else signal.signal.signalTime
            item.copy(
                urgency = urgency,
                category = category,
                title = title,
                signal_time = signalTime,
            )
        }

        // Enforce caps per category. Sort by urgency (high first) then by signal_time
        // descending so recent high-urgency items win.
        val urgencyRank = mapOf("high" to 0, "medium" to 1, "low" to 2)
        val byCategory = normalized.groupBy { it.category }
        val capped = buildList {
            addAll(applyCap(byCategory["action"].orEmpty(), caps.action, urgencyRank, warnings, "action"))
            addAll(applyCap(byCategory["attention"].orEmpty(), caps.attention, urgencyRank, warnings, "attention"))
            addAll(applyCap(byCategory["info"].orEmpty(), caps.info, urgencyRank, warnings, "info"))
        }

        // Dedup by source_ref across categories. If the model emits the same signal as
        // both `action` and `attention`, keep the highest-priority slot (action wins).
        // Without this, two BoardItemEntity rows would be persisted with different
        // category-derived ids, cluttering the board.
        val categoryRank = mapOf("action" to 0, "attention" to 1, "info" to 2)
        val deduped = capped
            .sortedWith(
                compareBy<BoardAgentItem> { categoryRank[it.category] ?: Int.MAX_VALUE }
                    .thenBy { urgencyRank[it.urgency] ?: Int.MAX_VALUE }
            )
            .distinctBy { it.source_ref }
        if (deduped.size < capped.size) {
            warnings += "dedup: ${capped.size - deduped.size} cross-category duplicate(s) removed"
        }

        val summary = raw.summary.trim().take(SUMMARY_MAX)

        return ValidationResult(
            output = BoardAgentOutput(summary = summary, items = deduped),
            warnings = warnings,
        )
    }

    private fun applyCap(
        items: List<BoardAgentItem>,
        cap: Int,
        urgencyRank: Map<String, Int>,
        warnings: MutableList<String>,
        label: String,
    ): List<BoardAgentItem> {
        if (items.size <= cap) return items
        warnings += "cap $label: ${items.size} -> $cap"
        return items.sortedWith(
            compareBy<BoardAgentItem> { urgencyRank[it.urgency] ?: Int.MAX_VALUE }
                .thenByDescending { it.signal_time }
        ).take(cap)
    }
}
