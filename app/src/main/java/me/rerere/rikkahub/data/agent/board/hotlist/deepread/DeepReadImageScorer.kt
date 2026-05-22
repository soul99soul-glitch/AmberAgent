package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import java.net.URI
import java.util.Locale

object DeepReadImageScorer {
    fun score(topicTitle: String, candidate: DeepReadImageCandidate): DeepReadImageCandidate {
        val risks = candidate.riskFlags.toMutableSet()
        risks += urlRiskFlags(candidate.imageUrl)
        risks += qualityRiskFlags(candidate.quality)
        val localSameHost = sameHost(candidate.imageUrl, candidate.sourceUrl)
        val imageUrlContext = candidate.imageUrl.substringAfterLast('/').substringBefore('?').lowercase(Locale.ROOT)
        if (!localSameHost && candidate.candidateKind == "search_result_image") {
            risks += "image_host_mismatch_without_context"
        }

        val keywords = keywords(topicTitle)
        val context = listOfNotNull(
            candidate.sourceTitle,
            candidate.pageTitle,
            candidate.alt,
            candidate.nearbyText,
            candidate.query,
            imageUrlContext,
        ).joinToString(" ").lowercase(Locale.ROOT)
        val titleContext = listOfNotNull(candidate.sourceTitle, candidate.pageTitle, candidate.alt, candidate.nearbyText)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val imageContext = listOfNotNull(candidate.alt, candidate.nearbyText, imageUrlContext)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val textImageContext = listOfNotNull(candidate.alt, candidate.nearbyText)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        val keywordHits = keywords.count { it.lowercase(Locale.ROOT) in context }
        val titleHits = keywords.count { it.lowercase(Locale.ROOT) in titleContext }
        val imageContextHits = imageEvidenceTerms(topicTitle, keywords, imageContext).size
        val textImageContextHits = imageEvidenceTerms(topicTitle, keywords, textImageContext).size
        val sceneHits = specialSceneHits(topicTitle, context)
        val effectiveKeywordHits = keywordHits + sceneHits
        val lacksDirectSearchImageEvidence = candidate.candidateKind == "search_result_image" &&
            textImageContextHits < 2
        val lacksSourceImageEvidence = candidate.candidateKind in sourceImageKinds &&
            imageContextHits < 2
        var score = 0
        score += when (candidate.candidateKind) {
            "og_image" -> 16
            "twitter_image" -> 14
            "article_image" -> 12
            "hotlist_image" -> 9
            "search_result_image" -> 6
            else -> 4
        }
        score += (keywordHits * 12) + (titleHits * 8)
        score += specialSceneScore(topicTitle, context)
        if (candidate.alt.orEmpty().trim().length >= 6) score += 8
        if (candidate.nearbyText.orEmpty().trim().length >= 12) score += 6
        if (localSameHost) score += 6
        if (candidate.quality.contentType?.startsWith("image/") == true) score += 4
        if ((candidate.quality.byteSize ?: 0L) >= 25_000L) score += 4

        if ("image_host_mismatch_without_context" in risks) score -= 12
        if (risks.any { it in hardRejectRisks }) {
            return candidate.copy(
                score = score.coerceAtLeast(0),
                confidence = IMAGE_CONFIDENCE_REJECT,
                riskFlags = risks.sorted(),
            )
        }

        val confidence = when {
            (lacksDirectSearchImageEvidence || lacksSourceImageEvidence) && score >= 24 && effectiveKeywordHits >= 1 -> IMAGE_CONFIDENCE_INLINE
            lacksDirectSearchImageEvidence || lacksSourceImageEvidence -> IMAGE_CONFIDENCE_REJECT
            score >= 46 && effectiveKeywordHits >= 2 -> IMAGE_CONFIDENCE_HERO
            score >= 24 && effectiveKeywordHits >= 1 -> IMAGE_CONFIDENCE_INLINE
            score >= 10 && localSameHost -> IMAGE_CONFIDENCE_INLINE
            else -> IMAGE_CONFIDENCE_REJECT
        }
        return candidate.copy(
            score = score.coerceAtLeast(0),
            confidence = confidence,
            riskFlags = risks.sorted(),
        )
    }

    fun fallbackCandidate(
        topicTitle: String,
        imageUrl: String,
        sourceUrl: String,
        sourceTitle: String,
        source: String?,
        kind: String = "source_image",
        rank: Int? = null,
    ): DeepReadImageCandidate =
        score(
            topicTitle = topicTitle,
            candidate = DeepReadImageCandidate(
                imageUrl = imageUrl,
                sourceUrl = sourceUrl,
                sourceTitle = sourceTitle,
                pageTitle = sourceTitle,
                candidateKind = kind,
                sourceService = source,
                rank = rank,
            ),
        )

    private fun urlRiskFlags(url: String): List<String> {
        val lower = url.lowercase(Locale.ROOT)
        val flags = mutableListOf<String>()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) flags += "non_http_url"
        if (listOf("favicon", "logo", "site-icon", "apple-touch-icon", "brand", "watermark").any { it in lower }) {
            flags += "site_brand_asset"
        }
        if (listOf("avatar", "profile_photo", "userpic").any { it in lower }) flags += "avatar_asset"
        if (listOf("pixel", "tracking", "spacer", "blank.gif", "1x1").any { it in lower }) flags += "tracking_or_spacer"
        if (listOf("sprite", "iconfont", "badge").any { it in lower }) flags += "sprite_or_badge"
        if (lower.substringBefore('?').substringAfterLast('.') in setOf("ico", "svg")) flags += "icon_format"
        return flags
    }

    private fun qualityRiskFlags(quality: DeepReadImageQuality): List<String> {
        val flags = mutableListOf<String>()
        val width = quality.width
        val height = quality.height
        if (quality.contentType != null && !quality.contentType.startsWith("image/")) {
            flags += "not_image_content_type"
        }
        if (width != null && height != null) {
            val smaller = minOf(width, height)
            val larger = maxOf(width, height)
            if (width < 320 || height < 160) flags += "too_small"
            if (smaller <= 160 && larger <= 320) flags += "tiny_asset"
            if (smaller > 0 && larger.toDouble() / smaller > 4.5) flags += "sprite_or_banner_strip"
            if (kotlin.math.abs(width - height) <= 16 && width <= 640) flags += "small_square_brand_asset"
        }
        if ((quality.byteSize ?: Long.MAX_VALUE) in 1L..4096L) flags += "tiny_file"
        return flags
    }

    private fun specialSceneScore(topicTitle: String, context: String): Int {
        val lowerTitle = topicTitle.lowercase(Locale.ROOT)
        var score = 0
        val sceneWords = listOf("ppt", "截图", "发布会", "现场", "演示", "presentation", "slide", "keynote", "launch")
        if (sceneWords.any { it in lowerTitle } && sceneWords.any { it in context }) score += 18
        if ("八败两胜" in topicTitle && "八败两胜" in context) score += 24
        if ("特斯拉" in topicTitle && ("tesla" in context || "特斯拉" in context)) score += 10
        if ("小米" in topicTitle && ("xiaomi" in context || "小米" in context)) score += 10
        return score
    }

    private fun specialSceneHits(topicTitle: String, context: String): Int {
        val lowerTitle = topicTitle.lowercase(Locale.ROOT)
        return sceneTerms.count { term ->
            val lowerTerm = term.lowercase(Locale.ROOT)
            lowerTerm in lowerTitle && lowerTerm in context
        }.coerceAtMost(3)
    }

    private fun imageEvidenceTerms(topicTitle: String, keywords: List<String>, context: String): Set<String> {
        val lowerTitle = topicTitle.lowercase(Locale.ROOT)
        val lowerContext = context.lowercase(Locale.ROOT)
        val matches = mutableSetOf<String>()
        keywords.forEach { keyword ->
            val lowerKeyword = keyword.lowercase(Locale.ROOT)
            if (lowerKeyword in lowerContext) matches += lowerKeyword
        }
        if ("八败两胜" in topicTitle && "八败两胜" in context) matches += "八败两胜"
        if ("特斯拉" in topicTitle && ("tesla" in lowerContext || "特斯拉" in context)) matches += "特斯拉"
        if ("小米" in topicTitle && ("xiaomi" in lowerContext || "小米" in context)) matches += "小米"
        if ("发布会" in topicTitle && listOf("发布会", "launch", "keynote").any { it in lowerContext }) {
            matches += "发布会"
        }
        sceneWords.forEach { word ->
            if (word in lowerTitle && word in lowerContext) matches += word
        }
        return matches
    }

    private fun keywords(text: String): List<String> {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        val lower = compact.lowercase(Locale.ROOT)
        val protectedTerms = sceneTerms
            .filter { it.lowercase(Locale.ROOT) in lower }
        val cjkRuns = Regex("[\\u4e00-\\u9fff]{2,}")
            .findAll(compact)
            .map { it.value }
            .toList()
        val cjkPhrases = cjkRuns.flatMap { run ->
            if (run.length <= 6) {
                listOf(run)
            } else {
                (4 downTo 2).flatMap { size -> run.windowed(size) }
            }
        }.filterUsefulKeyword()
        val latin = Regex("[A-Za-z][A-Za-z0-9_-]{2,}")
            .findAll(compact)
            .map { it.value.lowercase(Locale.ROOT) }
            .filterNot { it in stopWords }
            .toList()
        return (protectedTerms + cjkPhrases + latin)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(18)
    }

    private fun List<String>.filterUsefulKeyword(): List<String> =
        filter { token ->
            token !in stopWords &&
                token.length >= 2 &&
                stopWords.none { stop -> token == stop || (token.length <= 3 && stop in token) }
        }

    private fun sameHost(a: String, b: String): Boolean {
        val hostA = runCatching { URI(a).host?.removePrefix("www.") }.getOrNull()
        val hostB = runCatching { URI(b).host?.removePrefix("www.") }.getOrNull()
        return !hostA.isNullOrBlank() && hostA == hostB
    }

    private val hardRejectRisks = setOf(
        "non_http_url",
        "site_brand_asset",
        "avatar_asset",
        "tracking_or_spacer",
        "sprite_or_badge",
        "icon_format",
        "not_image_content_type",
        "too_small",
        "tiny_asset",
        "small_square_brand_asset",
        "tiny_file",
    )

    private val sourceImageKinds = setOf("og_image", "twitter_image", "article_image", "hotlist_image", "source_image")

    private val stopWords = setOf(
        "关于",
        "最新",
        "今日",
        "昨天",
        "新闻",
        "热搜",
        "发布",
        "报道",
        "the",
        "and",
        "for",
        "with",
        "from",
        "news",
        "latest",
    )

    private val sceneTerms = listOf(
        "八败两胜",
        "小米",
        "特斯拉",
        "发布会",
        "ppt",
        "截图",
        "现场图",
        "现场",
        "演示文稿",
        "演示",
        "presentation",
        "slide",
        "keynote",
        "launch",
    )

    private val sceneWords = listOf("ppt", "截图", "发布会", "现场", "演示", "presentation", "slide", "keynote", "launch")
}
