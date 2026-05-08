package me.rerere.rikkahub.data.memory.recall

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.model.MemoryKind
import me.rerere.rikkahub.data.memory.model.MemoryRecord
import me.rerere.rikkahub.data.memory.model.MemoryScope
import me.rerere.rikkahub.data.memory.prompt.MemoryPromptBuilder
import me.rerere.rikkahub.data.memory.store.MemoryRepository
import kotlin.math.max

class MemoryRecallStore(
    private val memoryRepository: MemoryRepository,
) {
    suspend fun buildPrompt(settings: Settings, messages: List<UIMessage>): String {
        val records = recall(settings, messages)
        memoryRepository.touchMemories(records.map { it.id })
        return MemoryPromptBuilder.buildMemoryContext(
            records = records,
            debug = settings.agentRuntime.memoryRecall.debug,
        )
    }

    suspend fun recall(settings: Settings, messages: List<UIMessage>): List<MemoryRecord> {
        val scopes = buildSet {
            if (settings.agentRuntime.enableCoreMemory) add(MemoryScope.CORE)
            if (settings.agentRuntime.enableShortTermMemory) add(MemoryScope.SHORT_TERM)
            if (settings.agentRuntime.enableLongTermMemory) add(MemoryScope.LONG_TERM)
        }
        if (scopes.isEmpty()) return emptyList()

        val queryText = messages.takeLast(16).joinToString("\n") { it.toText() }
        val currentText = messages.lastOrNull()?.toText().orEmpty()
        val terms = tokenize("$currentText\n$queryText")
        val now = System.currentTimeMillis()
        val maxItems = settings.agentRuntime.memoryRecall.maxItems.coerceIn(1, 40)
        val maxChars = settings.agentRuntime.memoryRecall.maxPromptChars.coerceIn(256, 12_000)

        return memoryRepository.getActiveRecords(scopes, now)
            .asSequence()
            .map { record -> record to score(record, terms, currentText, now) }
            .filter { (_, score) -> score > 0 || terms.isEmpty() }
            .sortedWith(
                compareByDescending<Pair<MemoryRecord, Double>> { it.first.pinned }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .map { it.first }
            .takeBudget(maxItems, maxChars)
    }

    private fun score(record: MemoryRecord, terms: Set<String>, currentText: String, now: Long): Double {
        val content = record.content.lowercase()
        var relevance = 0.0
        if (currentText.isNotBlank() && content.contains(currentText.lowercase().take(60))) {
            relevance += 30.0
        }
        terms.forEach { term ->
            if (term.length >= 2 && content.contains(term)) {
                relevance += max(2.0, term.length.coerceAtMost(12).toDouble())
            }
        }
        val alwaysEligible = record.pinned ||
            record.scope == MemoryScope.CORE ||
            record.kind == MemoryKind.FEEDBACK
        if (relevance <= 0.0 && !alwaysEligible) return 0.0

        var score = 0.0
        if (record.pinned) score += 100.0
        score += when (record.kind) {
            MemoryKind.FEEDBACK -> 35.0
            MemoryKind.USER -> 26.0
            MemoryKind.PROJECT -> 18.0
            MemoryKind.ROUTINE -> 12.0
            MemoryKind.REFERENCE -> 10.0
            MemoryKind.NOTE -> 6.0
        }
        score += when (record.scope) {
            MemoryScope.CORE -> 24.0
            MemoryScope.SHORT_TERM -> 16.0
            MemoryScope.LONG_TERM -> 8.0
        }
        score += relevance
        record.lastUsedAt?.let { lastUsed ->
            val ageDays = ((now - lastUsed).coerceAtLeast(0L) / 86_400_000.0)
            score += (8.0 / (1.0 + ageDays)).coerceAtMost(8.0)
        }
        val updateAgeDays = ((now - record.updatedAt).coerceAtLeast(0L) / 86_400_000.0)
        score += (10.0 / (1.0 + updateAgeDays)).coerceAtMost(10.0)
        score *= record.confidence.coerceIn(0.1f, 1f)
        return score
    }

    private fun tokenize(text: String): Set<String> {
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

    private fun Sequence<MemoryRecord>.takeBudget(maxItems: Int, maxChars: Int): List<MemoryRecord> {
        val selected = mutableListOf<MemoryRecord>()
        var used = 0
        for (record in this) {
            val cost = record.content.length + 32
            if (selected.isNotEmpty() && used + cost > maxChars) continue
            selected += record
            used += cost
            if (selected.size >= maxItems) break
        }
        return selected
    }
}
