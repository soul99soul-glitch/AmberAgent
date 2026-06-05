package app.amber.core.memory.recall

import app.amber.ai.ui.UIMessage
import app.amber.core.settings.Settings
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.prompt.MemoryPromptBuilder
import app.amber.core.memory.store.MemoryRepository
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max

class MemoryRecallStore(
    private val memoryRepository: MemoryRepository,
) {
    suspend fun buildPrompt(settings: Settings, messages: List<UIMessage>): String {
        val selections = recallSelections(settings, messages)
        val records = selections.map { it.record }
        memoryRepository.touchMemories(selections.map { it.record.id })
        return MemoryPromptBuilder.buildMemoryContext(
            records = records,
            debug = settings.agentRuntime.memoryRecall.debug,
            debugDetails = if (settings.agentRuntime.memoryRecall.debug) {
                selections.associate { selection ->
                    selection.record.id to selection.score.toDebugText()
                }
            } else {
                emptyMap()
            },
        )
    }

    suspend fun recall(settings: Settings, messages: List<UIMessage>): List<MemoryRecord> {
        return recallSelections(settings, messages).map { it.record }
    }

    private suspend fun recallSelections(settings: Settings, messages: List<UIMessage>): List<MemoryRecallSelection> {
        val scopes = buildSet {
            if (settings.agentRuntime.enableCoreMemory) add(MemoryScope.CORE)
            if (settings.agentRuntime.enableShortTermMemory) add(MemoryScope.SHORT_TERM)
            if (settings.agentRuntime.enableLongTermMemory) add(MemoryScope.LONG_TERM)
        }
        if (scopes.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        return rankRecords(
            settings = settings,
            messages = messages,
            records = memoryRepository.getActiveRecords(scopes, now),
            now = now,
        )
    }

    companion object {
        internal const val USER_ALWAYS_ELIGIBLE_CONFIDENCE = 0.70f
        private const val TIME_DECAY_MULTIPLIER = 0.35

        internal fun rankRecords(
            settings: Settings,
            messages: List<UIMessage>,
            records: List<MemoryRecord>,
            now: Long = System.currentTimeMillis(),
        ): List<MemoryRecallSelection> {
            val queryText = messages.takeLast(16).joinToString("\n") { it.toText() }
            val currentText = messages.lastOrNull()?.toText().orEmpty()
            val terms = tokenize("$currentText\n$queryText")
            val maxItems = settings.agentRuntime.memoryRecall.maxItems.coerceIn(1, 40)
            val maxChars = settings.agentRuntime.memoryRecall.maxPromptChars.coerceIn(256, 12_000)

            return records
                .asSequence()
                .map { record -> MemoryRecallSelection(record, score(record, terms, currentText, now)) }
                .filter { (_, score) -> score.value > 0 || terms.isEmpty() }
                .sortedWith(
                    compareByDescending<MemoryRecallSelection> { it.record.pinned }
                        .thenByDescending { it.score.value }
                        .thenByDescending { it.record.updatedAt }
                )
                .takeBudget(maxItems, maxChars)
        }

        internal fun score(
            record: MemoryRecord,
            terms: Set<String>,
            currentText: String,
            now: Long,
        ): MemoryRecallScore {
            val reasons = mutableListOf<String>()
            val content = record.content.lowercase()
            var relevance = 0.0
            if (currentText.isNotBlank() && content.contains(currentText.lowercase().take(60))) {
                relevance += 30.0
                reasons += "current-exact"
            }
            terms.forEach { term ->
                if (term.length >= 2 && content.contains(term)) {
                    relevance += max(2.0, term.length.coerceAtMost(12).toDouble())
                    if ("term-match" !in reasons) reasons += "term-match"
                }
            }

            val alwaysEligibleReasons = alwaysEligibleReasons(record)
            if (relevance <= 0.0 && alwaysEligibleReasons.isEmpty()) {
                return MemoryRecallScore(
                    value = 0.0,
                    reasons = listOf("no-match"),
                    freshness = MemoryRecallFreshness.CURRENT,
                )
            }
            reasons += alwaysEligibleReasons

            var score = 0.0
            if (record.pinned) score += 100.0
            score += when (record.kind) {
                MemoryKind.FEEDBACK -> 52.0
                MemoryKind.USER -> 44.0
                MemoryKind.PROJECT -> 24.0
                MemoryKind.ROUTINE -> 18.0
                MemoryKind.REFERENCE -> 12.0
                MemoryKind.NOTE -> 6.0
            }
            score += when (record.scope) {
                MemoryScope.CORE -> 26.0
                MemoryScope.LONG_TERM -> 18.0
                MemoryScope.SHORT_TERM -> 14.0
            }
            score += relevance
            record.lastUsedAt?.let { lastUsed ->
                val ageDays = ((now - lastUsed).coerceAtLeast(0L) / 86_400_000.0)
                score += (8.0 / (1.0 + ageDays)).coerceAtMost(8.0)
            }
            val updateAgeDays = ((now - record.updatedAt).coerceAtLeast(0L) / 86_400_000.0)
            score += (10.0 / (1.0 + updateAgeDays)).coerceAtMost(10.0)
            val freshness = classifyFreshness(record.content, now)
            if (freshness == MemoryRecallFreshness.TIME_DECAYED) {
                score *= TIME_DECAY_MULTIPLIER
                reasons += "time-decayed"
            }
            score *= record.confidence.coerceIn(0.1f, 1f)
            return MemoryRecallScore(
                value = score,
                reasons = reasons.distinct(),
                freshness = freshness,
            )
        }

        internal fun tokenize(text: String): Set<String> {
            val normalized = text.lowercase()
            val wordTerms = Regex("[\\p{L}\\p{N}_-]{2,}")
                .findAll(normalized)
                .map { it.value }
                .filterNot { it.length > 48 }
                .toSet()
            val compact = normalized.filter { it.isLetterOrDigit() }
            val cjkHints = if (compact.length >= 4) {
                compact.windowed(size = 4, step = 2, partialWindows = false).take(80).toSet()
            } else {
                emptySet()
            }
            return (wordTerms + cjkHints).take(160).toSet()
        }

        private fun alwaysEligibleReasons(record: MemoryRecord): List<String> = buildList {
            if (record.pinned) add("pinned")
            if (record.scope == MemoryScope.CORE) add("core")
            if (record.kind == MemoryKind.FEEDBACK) add("feedback")
            if (
                record.scope == MemoryScope.LONG_TERM &&
                record.kind == MemoryKind.USER &&
                record.confidence >= USER_ALWAYS_ELIGIBLE_CONFIDENCE
            ) {
                add("durable-user")
            }
        }

        private fun classifyFreshness(content: String, now: Long): MemoryRecallFreshness {
            val lower = content.lowercase()
            if (!hasFutureIntent(lower) || hasHistoricalIntent(lower)) {
                return MemoryRecallFreshness.CURRENT
            }
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val currentMonth = YearMonth.from(today)
            val hasExpiredAnchor = absoluteDateRegex.findAll(content).any { match ->
                val year = match.groupValues[1].toIntOrNull() ?: return@any false
                val month = match.groupValues[2].toIntOrNull() ?: return@any false
                val day = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                isExpiredAnchor(year, month, day, today, currentMonth)
            } || chineseDateRegex.findAll(content).any { match ->
                val year = match.groupValues[1].toIntOrNull() ?: return@any false
                val month = match.groupValues[2].toIntOrNull() ?: return@any false
                val day = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                isExpiredAnchor(year, month, day, today, currentMonth)
            }
            return if (hasExpiredAnchor) MemoryRecallFreshness.TIME_DECAYED else MemoryRecallFreshness.CURRENT
        }

        private fun isExpiredAnchor(
            year: Int,
            month: Int,
            day: Int?,
            today: LocalDate,
            currentMonth: YearMonth,
        ): Boolean = runCatching {
            if (day != null) {
                LocalDate.of(year, month, day).isBefore(today)
            } else {
                YearMonth.of(year, month).isBefore(currentMonth)
            }
        }.getOrDefault(false)

        private fun hasFutureIntent(lower: String): Boolean {
            val hints = listOf(
                "将", "计划", "要去", "准备", "行程", "旅行", "出差",
                "will", "going to", "plan", "trip", "travel", "visit",
            )
            return hints.any { it in lower }
        }

        private fun hasHistoricalIntent(lower: String): Boolean {
            val hints = listOf(
                "去过", "已去", "已经去", "回来", "结束",
                "visited", "went to", "traveled to", "have been", "has been",
            )
            return hints.any { it in lower }
        }

        private fun Sequence<MemoryRecallSelection>.takeBudget(
            maxItems: Int,
            maxChars: Int,
        ): List<MemoryRecallSelection> {
            val selected = mutableListOf<MemoryRecallSelection>()
            var used = 0
            for (selection in this) {
                val cost = selection.record.content.length + 32
                if (selected.isNotEmpty() && used + cost > maxChars) continue
                selected += selection
                used += cost
                if (selected.size >= maxItems) break
            }
            return selected
        }

        private val absoluteDateRegex = Regex("""\b((?:19|20)\d{2})-(\d{1,2})(?:-(\d{1,2}))?\b""")
        private val chineseDateRegex = Regex("""((?:19|20)\d{2})年(\d{1,2})月(?:(\d{1,2})日)?""")
    }
}

internal data class MemoryRecallSelection(
    val record: MemoryRecord,
    val score: MemoryRecallScore,
)

internal data class MemoryRecallScore(
    val value: Double,
    val reasons: List<String>,
    val freshness: MemoryRecallFreshness,
)

internal enum class MemoryRecallFreshness(val wireName: String) {
    CURRENT("current"),
    TIME_DECAYED("time-decayed"),
}

internal fun MemoryRecallScore.toDebugText(): String =
    "score=${"%.1f".format(value)}, reasons=${reasons.joinToString("|")}, freshness=${freshness.wireName}"
