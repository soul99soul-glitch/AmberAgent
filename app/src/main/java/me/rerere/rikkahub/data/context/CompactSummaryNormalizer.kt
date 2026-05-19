package me.rerere.rikkahub.data.context

import kotlinx.serialization.json.Json

internal object CompactSummaryNormalizer {
    fun normalizeOrNull(
        json: Json,
        summary: String,
        sourceMessageIds: List<String>,
        coveredCompactIds: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis(),
    ): String? =
        CompactSummaryPayloads.normalizeModelOutput(
            parser = json,
            summary = summary,
            sourceMessageIds = sourceMessageIds,
            coveredCompactIds = coveredCompactIds,
            createdAt = createdAt,
        )

    fun fallbackPlainTextSummaryJson(
        summary: String,
        sourceMessageIds: List<String>,
        coveredCompactIds: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis(),
        sourceContent: String = "",
        carriedHandoffMarkdown: String = "",
    ): String =
        CompactSummaryPayloads.fallbackPayload(
            summary = summary,
            sourceMessageIds = sourceMessageIds,
            coveredCompactIds = coveredCompactIds,
            createdAt = createdAt,
            sourceContent = sourceContent,
            carriedHandoffMarkdown = carriedHandoffMarkdown,
        )
}
