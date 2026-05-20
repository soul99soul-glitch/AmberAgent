package me.rerere.rikkahub.data.agent.board.hotlist.deepread

object DeepReadSanitizer {
    fun sanitize(parsed: DeepReadOutput, sources: List<DeepReadSource>, topicTitle: String): DeepReadOutput {
        val sourceUrls = sources.map { it.url }.toSet()
        val sourceImages = sources
            .flatMap { source -> source.images.map { image -> SourceImage(image, source.source) } }
            .filter { it.url.isUsableDeepReadImageUrl() }
            .distinctBy { it.url }
            .take(12)
        val sourceImageUrls = sourceImages.map { it.url }.toSet()
        val safeReferences = parsed.references.filter { it.url in sourceUrls }
            .mapIndexed { index, link -> link.withChineseSafeTitle(topicTitle, index) }
        val safeExtended = parsed.extendedReading.filter { it.url in sourceUrls }
            .mapIndexed { index, link -> link.withChineseSafeTitle(topicTitle, index) }
        val fallbackReading = sources.mapIndexed { index, source ->
            ReadingLink(source.chineseSafeTitle(topicTitle, index), source.url, source.source)
        }
            .distinctBy { it.url }
            .take(6)
        val safeAssets = sanitizeImageAssets(
            parsed = parsed,
            sourceImages = sourceImages,
            topicTitle = topicTitle,
        )
        val safeAssetUrls = safeAssets.map { it.url }.toSet()

        return parsed.copy(
            heroImageUrl = parsed.heroImageUrl
                ?.takeIf { it in sourceImageUrls }
                ?: safeAssets.firstOrNull()?.url,
            timeline = parsed.timeline?.mapIndexed { index, event ->
                val url = event.imageUrl?.takeIf { it in safeAssetUrls }
                event.copy(
                    imageUrl = url,
                    imageCaption = event.imageCaption?.takeIf { url != null && it.hasCjk() },
                )
            },
            corePoints = parsed.corePoints?.map { point ->
                val url = point.imageUrl?.takeIf { it in safeAssetUrls }
                point.copy(
                    imageUrl = url,
                    imageCaption = point.imageCaption?.takeIf { url != null && it.hasCjk() },
                )
            },
            imageAssets = safeAssets,
            analysis = parsed.analysis.copy(
                quotes = parsed.analysis.quotes.filter { quote ->
                    sources.any { source -> source.content.containsQuote(quote.text) }
                }
            ),
            references = (safeReferences + fallbackReading).distinctBy { it.url }.take(10),
            extendedReading = safeExtended.ifEmpty { fallbackReading },
        )
    }

    private fun sanitizeImageAssets(
        parsed: DeepReadOutput,
        sourceImages: List<SourceImage>,
        topicTitle: String,
    ): List<DeepReadImageAsset> {
        val sourceByUrl = sourceImages.associateBy { it.url }
        val parsedAssets = parsed.imageAssets
            .filter { it.url in sourceByUrl.keys }
            .mapIndexed { index, asset ->
                asset.copy(
                    caption = asset.caption?.takeIf { it.hasCjk() }
                        ?: fallbackImageCaption(topicTitle, index),
                    source = asset.source ?: sourceByUrl[asset.url]?.source,
                    relatedTimelineIndex = asset.relatedTimelineIndex?.takeIf { idx ->
                        idx >= 0 && idx < parsed.timeline.orEmpty().size
                    },
                )
            }
        val fallbackAssets = sourceImages.mapIndexed { index, image ->
            DeepReadImageAsset(
                url = image.url,
                caption = fallbackImageCaption(topicTitle, index),
                source = image.source,
                qualityHint = if (index == 0) "hero" else "context",
            )
        }
        return (parsedAssets + fallbackAssets)
            .distinctBy { it.url }
            .take(6)
    }

    private fun fallbackImageCaption(topicTitle: String, index: Int): String =
        if (index == 0) "与「$topicTitle」相关的来源图片" else "来源图片 ${index + 1}"

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

    private fun String.isUsableDeepReadImageUrl(): Boolean {
        val lower = lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains("avatar") || lower.contains("logo") || lower.contains("icon")) return false
        if (lower.contains("pixel") || lower.contains("tracking") || lower.contains("spacer")) return false
        return true
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

    private data class SourceImage(
        val url: String,
        val source: String?,
    )
}
