package app.amber.core.memory.recall

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.core.settings.Settings
import app.amber.core.jev.MemorySemanticReranker
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.prompt.MemoryPromptBuilder
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.time.MemoryFreshness
import app.amber.core.memory.time.MemoryTimeAnchorParser
import kotlin.math.max
import java.util.Locale

class MemoryRecallStore(
    private val memoryRepository: MemoryRepository,
    private val semanticReranker: MemorySemanticReranker? = null,
) {
    suspend fun buildPrompt(
        settings: Settings,
        messages: List<UIMessage>,
        locale: Locale = Locale.ENGLISH,
        runKey: String? = null,
    ): String {
        val selections = recallSelections(settings, messages, runKey)
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
            locale = locale,
        )
    }

    suspend fun recall(settings: Settings, messages: List<UIMessage>, runKey: String? = null): List<MemoryRecord> {
        return recallSelections(settings, messages, runKey).map { it.record }
    }

    private suspend fun recallSelections(
        settings: Settings,
        messages: List<UIMessage>,
        runKey: String? = null,
    ): List<MemoryRecallSelection> {
        val scopes = buildSet {
            if (settings.agentRuntime.enableCoreMemory) add(MemoryScope.CORE)
            if (settings.agentRuntime.enableShortTermMemory) add(MemoryScope.SHORT_TERM)
            if (settings.agentRuntime.enableLongTermMemory) add(MemoryScope.LONG_TERM)
        }
        if (scopes.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val records = memoryRepository.getActiveRecords(scopes, now)
        if (records.isEmpty()) return emptyList()
        val reranker = semanticReranker ?: return rankRecords(settings, messages, records, now)

        val scored = scoreAll(settings, messages, records, now)
        val baseline = budgeted(scored.baselineEligible(), settings)

        // 候选独立于词面 >0 过滤：词面/强保留池 + 新近补充——语义召回的价值
        // 恰恰在零词面命中的候选上，不能先过滤再指望重排。
        val lexicalPool = scored.selections.take(SEMANTIC_CANDIDATE_LEXICAL_POOL)
        val recentPool = scored.selections
            .sortedByDescending { it.record.updatedAt }
            .take(SEMANTIC_CANDIDATE_RECENT_POOL)
            .filterNot { candidate -> lexicalPool.any { it.record.id == candidate.record.id } }
        val candidates = (lexicalPool + recentPool).distinctBy { it.record.id }
        if (candidates.isEmpty()) return baseline

        val taskText = messages.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty()
        val semantic = reranker.rerank(candidates.map { it.record }, taskText, runKey)
        val rankedIds = semantic.rankedIds?.takeIf { semantic.applied } ?: return baseline

        // 置顶等强保留语义继续满足：Jev 未选中的置顶记忆仍然保留在前部。
        val selectionById = scored.selections.associateBy { it.record.id }
        val pinnedFirst = candidates.filter { it.record.pinned }.mapNotNull { selectionById[it.record.id] }
        val ordered = rankedIds.mapNotNull { selectionById[it] }
        return budgeted(pinnedFirst.distinctBy { it.record.id } + ordered, settings)
    }

    companion object {
        internal const val USER_ALWAYS_ELIGIBLE_CONFIDENCE = 0.70f
        private const val TIME_DECAY_MULTIPLIER = 0.35
        internal const val SEMANTIC_CANDIDATE_LEXICAL_POOL = 24
        internal const val SEMANTIC_CANDIDATE_RECENT_POOL = 16

        internal fun rankRecords(
            settings: Settings,
            messages: List<UIMessage>,
            records: List<MemoryRecord>,
            now: Long = System.currentTimeMillis(),
        ): List<MemoryRecallSelection> {
            val scored = scoreAll(settings, messages, records, now)
            return budgeted(scored.baselineEligible(), settings)
        }

        /**
         * 全量打分排序（不做 >0 过滤与预算截断），一次计算供基线与语义候选共用。
         * [baselineEligible] 恢复原 rankRecords 的词面过滤语义。
         */
        internal fun scoreAll(
            settings: Settings,
            messages: List<UIMessage>,
            records: List<MemoryRecord>,
            now: Long = System.currentTimeMillis(),
        ): ScoredMemories {
            val queryText = messages.takeLast(16).joinToString("\n") { it.toText() }
            val currentText = messages.lastOrNull()?.toText().orEmpty()
            val terms = tokenize("$currentText\n$queryText")
            val selections = records
                .map { record -> MemoryRecallSelection(record, score(record, terms, currentText, now)) }
                .sortedWith(
                    compareByDescending<MemoryRecallSelection> { it.record.pinned }
                        .thenByDescending { it.score.value }
                        .thenByDescending { it.record.updatedAt }
                )
            return ScoredMemories(selections = selections, hasQueryTerms = terms.isNotEmpty())
        }

        internal fun budgeted(
            selections: List<MemoryRecallSelection>,
            settings: Settings,
        ): List<MemoryRecallSelection> {
            val maxItems = settings.agentRuntime.memoryRecall.maxItems.coerceIn(1, 40)
            val maxChars = settings.agentRuntime.memoryRecall.maxPromptChars.coerceIn(256, 12_000)
            return selections.asSequence().takeBudget(maxItems, maxChars).toList()
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
                // Synthesized topic docs score near raw facts but are never
                // always-eligible — they surface only on genuine term match.
                MemoryKind.TOPIC -> 30.0
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
            val freshness = when (MemoryTimeAnchorParser.classifyFreshness(record.content, now)) {
                MemoryFreshness.CURRENT -> MemoryRecallFreshness.CURRENT
                MemoryFreshness.TIME_DECAYED -> MemoryRecallFreshness.TIME_DECAYED
            }
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

        private fun Sequence<MemoryRecallSelection>.takeBudget(
            maxItems: Int,
            maxChars: Int,
        ): List<MemoryRecallSelection> {
            val selected = mutableListOf<MemoryRecallSelection>()
            var used = 0
            for (selection in this) {
                val cost = selection.record.content.length + 32
                // Skip records that exceed the remaining prompt budget; this
                // also covers an oversized first record without mutating it.
                if (used + cost > maxChars) continue
                selected += selection
                used += cost
                if (selected.size >= maxItems) break
            }
            return selected
        }

    }
}

internal data class MemoryRecallSelection(
    val record: MemoryRecord,
    val score: MemoryRecallScore,
)

/** scoreAll 的产物：全量排序 + 是否存在查询词，供基线过滤与语义候选共用一次计算。 */
internal class ScoredMemories(
    val selections: List<MemoryRecallSelection>,
    val hasQueryTerms: Boolean,
) {
    /** 原词面过滤语义：有查询词时丢弃零相关且非 always-eligible 的记录。 */
    fun baselineEligible(): List<MemoryRecallSelection> =
        if (!hasQueryTerms) selections else selections.filter { it.score.value > 0.0 }
}

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
