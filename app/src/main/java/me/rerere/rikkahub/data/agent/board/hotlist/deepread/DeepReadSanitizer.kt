package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import java.net.URI

object DeepReadSanitizer {
    fun sanitize(parsed: DeepReadOutput, sources: List<DeepReadSource>, topicTitle: String): DeepReadOutput {
        val sourceUrls = sources.map { it.url }.toSet()
        val sourceImages = sources
            .flatMap { source ->
                val candidates = source.imageCandidates.ifEmpty {
                    source.images.mapIndexed { index, image ->
                        DeepReadImageScorer.fallbackCandidate(
                            topicTitle = topicTitle,
                            imageUrl = image,
                            sourceUrl = source.url,
                            sourceTitle = source.title,
                            source = source.source,
                            rank = index + 1,
                        )
                    }
                }
                candidates.map { candidate ->
                    SourceImage(
                        url = candidate.imageUrl,
                        source = candidate.sourceService ?: source.source,
                        confidence = candidate.confidence,
                        score = candidate.score,
                        riskFlags = candidate.riskFlags,
                    )
                }
            }
            .filter { it.confidence != IMAGE_CONFIDENCE_REJECT }
            .filter { it.url.isUsableDeepReadImageUrl() }
            .distinctBy { it.url.imageDedupKey() }
            .sortedWith(compareByDescending<SourceImage> { it.confidence == IMAGE_CONFIDENCE_HERO }
                .thenByDescending { it.score })
            .take(12)
        val heroImageUrls = sourceImages.filter { it.confidence == IMAGE_CONFIDENCE_HERO }.map { it.url }.toSet()
        val safeReferences = parsed.references.filter { it.url in sourceUrls }
            .mapIndexed { index, link -> link.withChineseSafeTitle(topicTitle, index) }
        val safeExtended = parsed.extendedReading.filter { it.url in sourceUrls }
            .mapIndexed { index, link -> link.withChineseSafeTitle(topicTitle, index) }
        val fallbackReading = sources.filter { it.url.isHttpOrHttpsUrl() }.mapIndexed { index, source ->
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
        val usedInlineImages = mutableSetOf<String>()
        val heroUrl = parsed.heroImageUrl
            ?.takeIf { it in heroImageUrls }
            ?: safeAssets.firstOrNull { it.confidence == IMAGE_CONFIDENCE_HERO }?.url

        return parsed.copy(
            heroImageUrl = heroUrl,
            heroImageConfidence = heroUrl?.let { IMAGE_CONFIDENCE_HERO },
            timeline = parsed.timeline?.mapIndexed { index, event ->
                val url = event.imageUrl?.takeIf { it in safeAssetUrls }
                    ?.takeIf { usedInlineImages.add(it.imageDedupKey()) }
                event.copy(
                    imageUrl = url,
                    imageCaption = event.imageCaption?.takeIf { url != null && it.hasCjk() },
                )
            },
            corePoints = parsed.corePoints?.map { point ->
                val url = point.imageUrl?.takeIf { it in safeAssetUrls }
                    ?.takeIf { usedInlineImages.add(it.imageDedupKey()) }
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
            visualDiagnostics = parsed.visualDiagnostics ?: sourceImages.toDiagnostics(heroUrl),
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
                    confidence = sourceByUrl[asset.url]?.confidence,
                    score = sourceByUrl[asset.url]?.score,
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
                qualityHint = image.confidence,
                confidence = image.confidence,
                score = image.score,
            )
        }
        return (parsedAssets + fallbackAssets)
            .distinctBy { it.url.imageDedupKey() }
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
        return "关于「$topicTitle」的相关报道（$sourceName）"
    }

    private fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }

    private fun String.isUsableDeepReadImageUrl(): Boolean {
        val lower = lowercase()
        if (!lower.isHttpOrHttpsUrl()) return false
        if (lower.contains("avatar") || lower.contains("logo") || lower.contains("icon")) return false
        if (lower.contains("pixel") || lower.contains("tracking") || lower.contains("spacer")) return false
        return true
    }

    private fun String.isHttpOrHttpsUrl(): Boolean =
        startsWith("http://") || startsWith("https://")

    private fun String.imageDedupKey(): String {
        val trimmed = trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri == null) return trimmed.substringBefore('#')

        val scheme = uri.scheme?.lowercase().orEmpty()
        val authority = buildString {
            append(uri.host?.lowercase() ?: uri.rawAuthority.orEmpty())
            if (uri.port != -1) append(':').append(uri.port)
        }
        val identityQuery = uri.rawQuery
            ?.split('&')
            .orEmpty()
            .filter { pair ->
                val name = pair.substringBefore('=').lowercase()
                name !in ignoredImageVariantQueryParams
            }
            .sorted()
            .joinToString("&")
        val querySuffix = identityQuery.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return "$scheme://$authority${uri.rawPath.orEmpty()}$querySuffix"
    }

    private val ignoredImageVariantQueryParams = setOf(
        "w",
        "width",
        "h",
        "height",
        "q",
        "quality",
        "format",
        "fmt",
        "fm",
        "auto",
        "fit",
        "crop",
        "resize",
        "dpr",
        "ixlib",
    )

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
        val confidence: String,
        val score: Int,
        val riskFlags: List<String>,
    )

    private fun List<SourceImage>.toDiagnostics(heroUrl: String?): DeepReadVisualDiagnostics =
        DeepReadVisualDiagnostics(
            candidateCount = size,
            heroSelection = firstOrNull { it.url == heroUrl }?.let { image ->
                DeepReadImageSelection(
                    imageUrl = image.url,
                    confidence = image.confidence,
                    score = image.score,
                    reason = "App 复核通过：图片达到 hero 置信度，且未命中 logo/icon 风险。",
                    riskFlags = image.riskFlags,
                )
            },
            inlineSelections = filter { it.url != heroUrl }
                .take(6)
                .map { image ->
                    DeepReadImageSelection(
                        imageUrl = image.url,
                        confidence = image.confidence,
                        score = image.score,
                        reason = if (image.confidence == IMAGE_CONFIDENCE_INLINE) {
                            "App 复核：适合作为正文图，未达到头图阈值。"
                        } else {
                            "App 复核：可作为备用视觉资产。"
                        },
                        riskFlags = image.riskFlags,
                    )
                },
        )
}
