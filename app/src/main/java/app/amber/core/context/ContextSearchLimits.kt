package app.amber.core.context

/**
 * A compact summary is stale when every original message it covered is gone from the current
 * transcript. Callers must still show it (the summary is the only surviving evidence), but the
 * model needs the marker so it can prefer fresh transcript hits or ask the user.
 */
object ContextSearchStaleness {
    fun isStale(sourceMessageIds: List<String>, existingMessageIds: Set<String>?): Boolean {
        if (existingMessageIds == null || sourceMessageIds.isEmpty()) return false
        return sourceMessageIds.none { it in existingMessageIds }
    }
}

/**
 * Caps the total preview characters returned by one history search so a single call cannot flood
 * the model context. Results keep their ranking order; the caller reports `truncated`.
 */
object ContextSearchBudget {
    const val DEFAULT_MAX_CHARS = 6_000

    data class Applied(
        val results: List<ContextSearchResult>,
        val usedChars: Int,
        val truncated: Boolean,
        val hasMore: Boolean = truncated,
    )

    fun deduplicateAndFilter(
        results: List<ContextSearchResult>,
        role: String? = null,
        limit: Int? = null,
    ): ContextSearchPage {
        val normalizedRole = role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" }
        val deduped = results.distinctBy { "${it.source}:${it.id}" }
        val filtered = if (normalizedRole == null) {
            deduped
        } else {
            deduped.filter { item ->
                val itemRole = item.role?.trim()?.lowercase()
                when {
                    itemRole != null -> itemRole == normalizedRole ||
                        (itemRole == "compact_summary" && normalizedRole == "summary")
                    item.source == "compact_summary" ->
                        normalizedRole == "compact_summary" || normalizedRole == "summary"
                    else -> false
                }
            }
        }
        if (limit == null) {
            return ContextSearchPage(
                results = filtered,
                hasMore = false,
                totalMatches = filtered.size,
            )
        }
        val effectiveLimit = limit.coerceIn(1, 20)
        val sliced = filtered.take(effectiveLimit)
        val hasMore = filtered.size > sliced.size
        return ContextSearchPage(
            results = sliced,
            hasMore = hasMore,
            totalMatches = filtered.size,
        )
    }

    fun apply(
        results: List<ContextSearchResult>,
        maxChars: Int = DEFAULT_MAX_CHARS,
        limitTruncated: Boolean = false,
    ): Applied {
        val budget = maxChars.coerceAtLeast(200)
        var used = 0
        val kept = mutableListOf<ContextSearchResult>()
        for (result in results) {
            val cost = result.preview.length.coerceAtLeast(1)
            if (used + cost > budget) break
            kept += result
            used += cost
        }
        val truncated = limitTruncated || kept.size < results.size
        return Applied(
            results = kept,
            usedChars = used,
            truncated = truncated,
            hasMore = truncated,
        )
    }
}

/**
 * Caps the total characters returned by one `conversation_expand` call. A compact summary can carry
 * dozens of source messages, so without a cap a single expand floods the model with the whole
 * original transcript. Messages keep transcript order; the caller reports `truncated`.
 */
object ContextExpandBudget {
    const val DEFAULT_MAX_CHARS = 12_000

    data class Applied(
        val messages: List<app.amber.ai.ui.UIMessage>,
        val usedChars: Int,
        val truncated: Boolean,
    )

    fun apply(
        messages: List<app.amber.ai.ui.UIMessage>,
        textOf: (app.amber.ai.ui.UIMessage) -> String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): Applied {
        val budget = maxChars.coerceAtLeast(200)
        var used = 0
        val kept = mutableListOf<app.amber.ai.ui.UIMessage>()
        for (message in messages) {
            val cost = textOf(message).length.coerceAtLeast(1)
            if (used + cost > budget) break
            kept += message
            used += cost
        }
        return Applied(messages = kept, usedChars = used, truncated = kept.size < messages.size)
    }
}
