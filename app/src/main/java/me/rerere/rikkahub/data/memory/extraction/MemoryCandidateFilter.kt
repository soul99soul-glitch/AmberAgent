package me.rerere.rikkahub.data.memory.extraction

import me.rerere.rikkahub.data.memory.model.MemoryCandidate
import me.rerere.rikkahub.data.memory.model.MemoryCandidateStatus
import me.rerere.rikkahub.data.memory.model.MemoryKind
import me.rerere.rikkahub.data.memory.model.MemoryRecord

class MemoryCandidateFilter {
    fun filter(candidates: List<MemoryCandidate>, existing: List<MemoryRecord>): FilterResult {
        val accepted = mutableListOf<MemoryCandidate>()
        val rejected = mutableListOf<MemoryCandidate>()
        val existingNormalized = existing.map { normalize(it.content) }.toSet()

        candidates.forEach { candidate ->
            val normalized = normalize(candidate.content)
            val sensitive = candidate.sensitive || containsSensitiveHint(candidate.content)
            val tooWeak = candidate.content.trim().length < 12 || candidate.confidence < 0.45f
            val duplicate = normalized in existingNormalized || accepted.any { normalize(it.content) == normalized }
            if (sensitive || tooWeak || duplicate) {
                rejected += candidate.copy(
                    sensitive = sensitive,
                    status = MemoryCandidateStatus.FILTERED,
                    reason = listOfNotNull(
                        candidate.reason.takeIf { it.isNotBlank() },
                        "sensitive".takeIf { sensitive },
                        "low_value".takeIf { tooWeak },
                        "duplicate".takeIf { duplicate },
                    ).joinToString("; "),
                )
            } else {
                accepted += candidate.copy(kind = normalizeKind(candidate.kind))
            }
        }
        return FilterResult(accepted = accepted, rejected = rejected)
    }

    private fun normalizeKind(kind: MemoryKind): MemoryKind =
        if (kind == MemoryKind.NOTE) MemoryKind.PROJECT else kind

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }.take(200)

    private fun containsSensitiveHint(text: String): Boolean {
        val lower = text.lowercase()
        val sensitiveTerms = listOf(
            "身份证", "护照", "银行卡", "密码", "宗教", "政治观点",
            "criminal", "password", "passport", "credit card", "religion", "sexual",
        )
        return sensitiveTerms.any { it in lower }
    }
}

data class FilterResult(
    val accepted: List<MemoryCandidate>,
    val rejected: List<MemoryCandidate>,
)
