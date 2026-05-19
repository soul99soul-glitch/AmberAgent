package me.rerere.rikkahub.data.agent.board.hotlist.deepread

object DeepReadSanitizer {
    fun sanitize(parsed: DeepReadOutput, sources: List<DeepReadSource>): DeepReadOutput {
        val sourceUrls = sources.map { it.url }.toSet()
        val sourceImages = sources.flatMap { it.images }.filter { it.startsWith("http") }.distinct()
        val safeReferences = parsed.references.filter { it.url in sourceUrls }
        val safeExtended = parsed.extendedReading.filter { it.url in sourceUrls }
        val fallbackReading = sources.map { ReadingLink(it.title, it.url, it.source) }
            .distinctBy { it.url }
            .take(6)

        return parsed.copy(
            heroImageUrl = parsed.heroImageUrl
                ?.takeIf { it in sourceImages }
                ?: sourceImages.firstOrNull(),
            analysis = parsed.analysis.copy(
                quotes = parsed.analysis.quotes.filter { quote ->
                    sources.any { source -> source.content.containsQuote(quote.text) }
                }
            ),
            references = (safeReferences + fallbackReading).distinctBy { it.url }.take(10),
            extendedReading = safeExtended.ifEmpty { fallbackReading },
        )
    }

    private fun String.containsQuote(quote: String): Boolean {
        val normalizedQuote = quote.normalizeQuoteText()
        if (normalizedQuote.length < 12) return false
        return normalizeQuoteText().contains(normalizedQuote)
    }

    private fun String.normalizeQuoteText(): String =
        lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[\"'“”‘’「」『』（）()，,。.:：;；!！?？]"), "")
}
