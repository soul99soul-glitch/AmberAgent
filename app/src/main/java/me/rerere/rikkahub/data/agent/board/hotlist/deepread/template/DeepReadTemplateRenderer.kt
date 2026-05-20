package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.verifiedImageUrls

object DeepReadTemplateRenderer {
    fun renderCustom(
        title: String,
        output: DeepReadOutput,
        templateHtml: String,
        fontCss: String = DEFAULT_FONT_CSS,
    ): DeepReadRenderedTemplate {
        DeepReadTemplateRepository.validateCustomTemplate(templateHtml)
        val safeImages = output.safeImageUrls()
        val hero = output.heroImageUrl?.takeIf { it in safeImages }
            ?: output.imageAssets.firstOrNull { it.url in safeImages }?.url
            ?: ""
        val placeholders = mapOf(
            "title" to title.escapeHtml(),
            "summary" to output.summary.escapeHtml(),
            "topic_type" to output.topicType.uppercase().escapeHtml(),
            "source_label" to output.sourceLabel().escapeHtml(),
            "hero_image_url" to hero.escapeHtml(),
            "hero_caption" to (output.heroCaption ?: output.imageAssets.firstOrNull { it.url == hero }?.caption).orEmpty().escapeHtml(),
            "timeline_html" to output.timelineHtml(safeImages),
            "core_points_html" to output.corePointsHtml(safeImages),
            "analysis_html" to output.analysisHtml(),
            "extended_reading_html" to output.extendedReadingHtml(),
            "font_css" to fontCss,
        )
        val html = placeholders.entries.fold(templateHtml) { current, (key, value) ->
            current.replace("{{$key}}", value)
        }
        return DeepReadRenderedTemplate(
            html = html,
            allowedImageUrls = safeImages,
            allowedLinkUrls = output.safeLinkUrls(),
        )
    }

    fun renderEditorialSlant(
        title: String,
        output: DeepReadOutput,
        fontCss: String = DEFAULT_FONT_CSS,
    ): DeepReadRenderedTemplate {
        val safeImages = output.safeImageUrls()
        val hero = output.heroImageUrl?.takeIf { it in safeImages }
            ?: output.imageAssets.firstOrNull { it.url in safeImages }?.url
        val body = buildString {
            appendLine("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style>")
            appendLine(fontCss)
            appendLine(BASE_CSS)
            appendLine("</style></head><body>")
            appendLine("<article>")
            if (hero != null) {
                appendLine("<figure class=\"hero\"><img src=\"${hero.escapeHtml()}\"/><div class=\"hero-cut\"><div><span class=\"hero-type\">${output.topicType.uppercase().escapeHtml()}</span><span class=\"hero-source\">${output.sourceLabel().escapeHtml()}</span></div><figcaption>${(output.heroCaption ?: output.imageAssets.firstOrNull { it.url == hero }?.caption).orEmpty().escapeHtml()}</figcaption></div></figure>")
            }
            appendLine("<section class=\"headline\">${if (hero == null) "<p class=\"kicker\">${output.topicType.uppercase().escapeHtml()} · DEEP READ</p>" else ""}<h1>${title.escapeHtml()}</h1><p class=\"summary\">${output.summary.escapeHtml()}</p></section>")
            output.timeline.orEmpty().takeIf { it.isNotEmpty() }?.let { events ->
                appendLine("<section><p class=\"section\">时间轴</p>")
                events.forEachIndexed { index, event ->
                    appendLine("<div class=\"timeline\"><div class=\"num\">${(index + 1).toString().padStart(2, '0')}</div><div><p class=\"date\">${event.date.escapeHtml()}</p><p>${event.event.escapeHtml()}</p>")
                    event.imageUrl?.takeIf { it in safeImages }?.let { url ->
                        appendLine("<figure class=\"inline\"><img src=\"${url.escapeHtml()}\"/><figcaption>${event.imageCaption.orEmpty().escapeHtml()}</figcaption></figure>")
                    }
                    appendLine("</div></div>")
                }
                appendLine("</section>")
            }
            output.corePoints.orEmpty().takeIf { it.isNotEmpty() }?.let { points ->
                appendLine("<section><p class=\"section\">关键脉络</p>")
                points.forEach { point ->
                    appendLine("<div class=\"point\"><h2>${point.point.escapeHtml()}</h2><p>${point.supporting.orEmpty().escapeHtml()}</p>")
                    point.imageUrl?.takeIf { it in safeImages }?.let { url ->
                        appendLine("<figure class=\"inline\"><img src=\"${url.escapeHtml()}\"/><figcaption>${point.imageCaption.orEmpty().escapeHtml()}</figcaption></figure>")
                    }
                    appendLine("</div>")
                }
                appendLine("</section>")
            }
            appendLine("<section><p class=\"section\">深度分析</p>")
            output.analysis.coreDispute?.takeIf { it.isNotBlank() }?.let { appendLine("<blockquote>${it.escapeHtml()}</blockquote>") }
            output.analysis.perspectives.take(4).forEach { perspective ->
                appendLine("<div class=\"perspective\"><p class=\"holder\">${perspective.holder.orEmpty().escapeHtml()}</p><p>${perspective.viewpoint.escapeHtml()}</p></div>")
            }
            output.analysis.implications?.takeIf { it.isNotBlank() }?.let { appendLine("<p>${it.escapeHtml()}</p>") }
            appendLine("</section>")
            if (output.extendedReading.isNotEmpty()) {
                appendLine("<section><p class=\"section\">扩展阅读</p>")
                output.extendedReading.take(8).forEachIndexed { index, link ->
                    appendLine("<a class=\"reading\" href=\"${link.url.escapeHtml()}\"><span>${(index + 1).toString().padStart(2, '0')}</span><div><p>${link.title.escapeHtml()}</p><small>${(link.source ?: link.url).escapeHtml()}</small></div></a>")
                }
                appendLine("</section>")
            }
            appendLine("</article></body></html>")
        }
        return DeepReadRenderedTemplate(
            html = body,
            allowedImageUrls = safeImages,
            allowedLinkUrls = output.safeLinkUrls(),
        )
    }

    private fun DeepReadOutput.safeImageUrls(): Set<String> = verifiedImageUrls()

    private fun DeepReadOutput.safeLinkUrls(): Set<String> =
        (extendedReading + references)
            .map { it.url }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .toSet()

    private fun DeepReadOutput.sourceLabel(): String {
        val count = (references.ifEmpty { extendedReading })
            .map { it.source ?: it.url }
            .filter { it.isNotBlank() }
            .distinct()
            .size
        return if (count > 0) "$count SOURCES" else "DEEP READ"
    }

    private fun DeepReadOutput.timelineHtml(safeImages: Set<String>): String =
        timeline.orEmpty().joinToString("\n") { event ->
            buildString {
                append("<div class=\"timeline-item\"><p class=\"timeline-date\">")
                append(event.date.escapeHtml())
                append("</p><p>")
                append(event.event.escapeHtml())
                append("</p>")
                event.imageUrl?.takeIf { it in safeImages }?.let { url ->
                    append("<figure><img src=\"")
                    append(url.escapeHtml())
                    append("\"/><figcaption>")
                    append(event.imageCaption.orEmpty().escapeHtml())
                    append("</figcaption></figure>")
                }
                append("</div>")
            }
        }

    private fun DeepReadOutput.corePointsHtml(safeImages: Set<String>): String =
        corePoints.orEmpty().joinToString("\n") { point ->
            buildString {
                append("<div class=\"core-point\"><h2>")
                append(point.point.escapeHtml())
                append("</h2><p>")
                append(point.supporting.orEmpty().escapeHtml())
                append("</p>")
                point.imageUrl?.takeIf { it in safeImages }?.let { url ->
                    append("<figure><img src=\"")
                    append(url.escapeHtml())
                    append("\"/><figcaption>")
                    append(point.imageCaption.orEmpty().escapeHtml())
                    append("</figcaption></figure>")
                }
                append("</div>")
            }
        }

    private fun DeepReadOutput.analysisHtml(): String = buildString {
        analysis.coreDispute?.takeIf { it.isNotBlank() }?.let {
            append("<blockquote>")
            append(it.escapeHtml())
            append("</blockquote>")
        }
        analysis.perspectives.take(6).forEach { perspective ->
            append("<div class=\"perspective\"><p class=\"holder\">")
            append(perspective.holder.orEmpty().escapeHtml())
            append("</p><p>")
            append(perspective.viewpoint.escapeHtml())
            append("</p></div>")
        }
        analysis.implications?.takeIf { it.isNotBlank() }?.let {
            append("<p>")
            append(it.escapeHtml())
            append("</p>")
        }
    }

    private fun DeepReadOutput.extendedReadingHtml(): String =
        extendedReading.take(10).joinToString("\n") { link ->
            "<a class=\"reading-link\" href=\"${link.url.escapeHtml()}\"><p>${link.title.escapeHtml()}</p><small>${(link.source ?: link.url).escapeHtml()}</small></a>"
        }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private const val DEFAULT_FONT_CSS = """
        :root{
          --deep-read-serif:"Noto Serif SC","Source Han Serif SC","Songti SC",serif;
          --deep-read-sans:"PingFang SC","Source Han Sans SC","Noto Sans SC",system-ui,sans-serif;
        }
    """

    private const val BASE_CSS = """
        html,body{margin:0;padding:0;background:#fafaf8;color:#191919;font-family:var(--deep-read-serif);}
        article{padding-bottom:34px;}
        .hero{margin:0 0 8px 0;position:relative;background:#f0f0ec;min-height:310px;overflow:hidden;}
        .hero img{display:block;width:100%;height:265px;object-fit:cover;}
        .hero-cut{height:106px;background:#fafaf8;clip-path:polygon(0 24%,100% 0,100% 100%,0 100%);margin-top:-54px;position:relative;padding:46px 22px 0;box-sizing:border-box;}
        .hero-cut>div{display:flex;align-items:center;justify-content:space-between;gap:14px;}
        .hero-type{font-family:var(--deep-read-sans);letter-spacing:.24em;color:#991b1b;font-size:10px;}
        .hero-source{font-family:var(--deep-read-sans);letter-spacing:.18em;color:#6b7280;font-size:10px;white-space:nowrap;}
        figcaption{font-size:9px;color:#6b7280;line-height:1.45;margin:10px 0 0;text-align:right;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
        .headline,section{padding:0 22px;}
        .kicker,.section,.date,.holder,small{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:#6b7280;font-size:10px;}
        h1{font-weight:500;font-size:32px;line-height:1.13;margin:12px 0 16px;}
        h2{font-weight:500;font-size:18px;line-height:1.34;margin:0 0 6px;}
        p{font-size:15px;line-height:1.68;margin:0 0 13px;}
        .summary{font-size:15px;line-height:1.68;}
        section{margin-top:28px;}
        .timeline{display:grid;grid-template-columns:32px 1fr;gap:10px;padding:11px 0;border-top:1px solid #ddd;}
        .num{font-family:var(--deep-read-sans);color:#ef4444;letter-spacing:.12em;font-size:12px;padding-top:4px;}
        .inline{margin:12px 0 4px;background:#f0f0ec;}
        .inline img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
        .inline figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        blockquote{font-size:18px;line-height:1.48;margin:0 0 16px;padding-left:12px;border-left:3px solid #ef4444;}
        .reading{display:grid;grid-template-columns:30px 1fr;gap:10px;border-top:1px solid #ddd;padding:10px 0;text-decoration:none;color:inherit;}
        .reading span{font-family:var(--deep-read-sans);color:#ef4444;font-size:12px;letter-spacing:.12em;}
        .reading p{font-size:13px;line-height:1.45;margin-bottom:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        .reading small{letter-spacing:.08em;font-size:9px;}
    """
}

data class DeepReadRenderedTemplate(
    val html: String,
    val allowedImageUrls: Set<String>,
    val allowedLinkUrls: Set<String> = emptySet(),
)
