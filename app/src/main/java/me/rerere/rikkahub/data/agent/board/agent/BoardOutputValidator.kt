package me.rerere.rikkahub.data.agent.board.agent

import me.rerere.rikkahub.data.agent.board.aggregator.ScoredSignal

object BoardOutputValidator {
    private val VALID_URGENCY = setOf("high", "medium", "low")
    private const val SUMMARY_MAX = 240

    data class ValidationResult(
        val output: BoardAgentOutput,
        val warnings: List<String>,
    )

    fun validate(
        raw: BoardAgentOutput,
        signals: List<ScoredSignal>,
    ): ValidationResult {
        val warnings = mutableListOf<String>()
        val signalsByKey = signals.associateBy { boardSignalKey(it.signal.sourceType, it.signal.sourceRef) }
        val signalsByRef = signals.groupBy { it.signal.sourceRef }
        val normalized = raw.items.mapNotNull { item ->
            val exactSignal = signalsByKey[boardSignalKey(item.source_type, item.source_ref)]
            val signal = exactSignal ?: signalsByRef[item.source_ref]?.singleOrNull()
            if (signal == null) {
                val reason = if ((signalsByRef[item.source_ref]?.size ?: 0) > 1) {
                    "ambiguous source_ref/source_type"
                } else {
                    "source_ref not in input"
                }
                warnings += "drop: $reason (${item.source_type}:${item.source_ref.take(40)})"
                return@mapNotNull null
            }
            val urgency = item.urgency.lowercase().trim().let {
                if (it in VALID_URGENCY) it else "medium".also {
                    warnings += "coerce urgency '${item.urgency}' -> medium"
                }
            }
            val title = item.title.trim()
            if (title.isBlank()) {
                warnings += "drop: blank title for ref ${item.source_ref.take(40)}"
                return@mapNotNull null
            }
            val sourceType = if (item.source_type == signal.signal.sourceType) {
                item.source_type
            } else {
                warnings += "coerce source_type '${item.source_type}' -> ${signal.signal.sourceType}"
                signal.signal.sourceType
            }
            item.copy(
                source_type = sourceType,
                urgency = urgency,
                category = "todo",
                title = title,
                signal_time = signal.signal.signalTime,
            )
        }

        val urgencyRank = mapOf("high" to 0, "medium" to 1, "low" to 2)
        val deduped = normalized
            .sortedWith(
                compareBy<BoardAgentItem> { urgencyRank[it.urgency] ?: Int.MAX_VALUE }
                    .thenByDescending { it.signal_time }
            )
            .distinctBy { boardSignalKey(it.source_type, it.source_ref) }
        if (deduped.size < normalized.size) {
            warnings += "dedup: ${normalized.size - deduped.size} duplicate source item(s) removed"
        }

        val capped = deduped.take(BoardPrompt.MAX_TODO_ITEMS)
        if (deduped.size > capped.size) {
            warnings += "cap todo: ${deduped.size} -> ${BoardPrompt.MAX_TODO_ITEMS}"
        }

        return ValidationResult(
            output = BoardAgentOutput(
                summary = raw.summary.trim().take(SUMMARY_MAX),
                items = capped,
            ),
            warnings = warnings,
        )
    }
}

internal fun boardSignalKey(sourceType: String, sourceRef: String): String = "$sourceType\u0000$sourceRef"
