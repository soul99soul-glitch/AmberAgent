package me.rerere.rikkahub.data.agent.board.hotlist.deepread

object DeepReadSanitizer {
    fun sanitize(parsed: DeepReadOutput, sources: List<DeepReadSource>, topicTitle: String): DeepReadOutput {
        val sourceUrls = sources.map { it.url }.toSet()
        val sourceImages = sources.flatMap { it.images }.filter { it.startsWith("http") }.distinct()
        val safeReferences = parsed.references.filter { it.url in sourceUrls }
            .mapIndexed { index, link -> link.withChineseSafeTitle(topicTitle, index) }
        val safeExtended = parsed.extendedReading.filter { it.url in sourceUrls }
            .mapIndexed { index, link -> link.withChineseSafeTitle(topicTitle, index) }
        val fallbackReading = sources.mapIndexed { index, source ->
            ReadingLink(source.chineseSafeTitle(topicTitle, index), source.url, source.source)
        }
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

    private fun ReadingLink.withChineseSafeTitle(topicTitle: String, index: Int): ReadingLink =
        if (title.hasCjk()) this else copy(title = fallbackReadingTitle(topicTitle, source, url, index))

    private fun DeepReadSource.chineseSafeTitle(topicTitle: String, index: Int): String =
        title.takeIf { it.hasCjk() } ?: fallbackReadingTitle(topicTitle, source, url, index)

    private fun fallbackReadingTitle(topicTitle: String, source: String?, url: String, index: Int): String {
        val sourceName = source?.takeIf { it.isNotBlank() }
            ?: url.substringAfter("://", url).substringBefore('/').removePrefix("www.")
        return "原文来源 ${index + 1}：$sourceName 关于「$topicTitle」的报道"
    }

    private fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }

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
