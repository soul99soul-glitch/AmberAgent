package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.verifiedImageUrls

object DeepReadTemplateRenderer {
    fun renderEditorialSlant(title: String, output: DeepReadOutput): DeepReadRenderedTemplate {
        val safeImages = output.safeImageUrls()
        val hero = output.heroImageUrl?.takeIf { it in safeImages }
            ?: output.imageAssets.firstOrNull { it.url in safeImages }?.url
        val body = buildString {
            appendLine("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style>")
            appendLine(BASE_CSS)
            appendLine("</style></head><body>")
            appendLine("<article>")
            if (hero != null) {
                appendLine("<figure class=\"hero\"><img src=\"${hero.escapeHtml()}\"/><figcaption>${(output.heroCaption ?: output.imageAssets.firstOrNull { it.url == hero }?.caption).orEmpty().escapeHtml()}</figcaption></figure>")
            }
            appendLine("<section class=\"headline\"><p class=\"kicker\">${output.topicType.uppercase().escapeHtml()} · DEEP READ</p><h1>${title.escapeHtml()}</h1><p class=\"summary\">${output.summary.escapeHtml()}</p></section>")
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
                    appendLine("<div class=\"reading\"><span>${(index + 1).toString().padStart(2, '0')}</span><div><p>${link.title.escapeHtml()}</p><small>${(link.source ?: link.url).escapeHtml()}</small></div></div>")
                }
                appendLine("</section>")
            }
            appendLine("</article></body></html>")
        }
        DeepReadTemplateValidator.validateOrThrow(body)
        return DeepReadRenderedTemplate(html = body, allowedImageUrls = safeImages)
    }

    private fun DeepReadOutput.safeImageUrls(): Set<String> = verifiedImageUrls()

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private const val BASE_CSS = """
        html,body{margin:0;padding:0;background:#fafaf8;color:#191919;font-family:serif;}
        article{padding-bottom:48px;}
        .hero{margin:0 0 20px 0;position:relative;background:#f0f0ec;}
        .hero img{display:block;width:100%;height:320px;object-fit:cover;}
        .hero:after{content:"";display:block;height:80px;background:#fafaf8;clip-path:polygon(0 28%,100% 0,100% 100%,0 100%);margin-top:-58px;position:relative;}
        figcaption{font-size:12px;color:#6b7280;line-height:1.7;margin:8px 30px 0;text-align:right;}
        .headline,section{padding:0 30px;}
        .kicker,.section,.date,.holder,small{font-family:sans-serif;letter-spacing:.22em;text-transform:uppercase;color:#6b7280;font-size:12px;}
        h1{font-weight:400;font-size:46px;line-height:1.12;margin:18px 0 22px;}
        h2{font-weight:400;font-size:24px;line-height:1.35;margin:0 0 8px;}
        p{font-size:19px;line-height:1.82;margin:0 0 18px;}
        .summary{font-size:20px;line-height:1.75;}
        section{margin-top:46px;}
        .timeline{display:grid;grid-template-columns:42px 1fr;gap:14px;padding:18px 0;border-top:1px solid #ddd;}
        .num{font-family:sans-serif;color:#ef4444;letter-spacing:.12em;font-size:13px;padding-top:5px;}
        .inline{margin:16px 0 6px;background:#f0f0ec;}
        .inline img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
        .inline figcaption{text-align:left;margin:8px 10px 10px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        blockquote{font-size:25px;line-height:1.55;margin:0 0 24px;padding-left:18px;border-left:3px solid #ef4444;}
        .reading{display:grid;grid-template-columns:42px 1fr;gap:14px;border-top:1px solid #ddd;padding:18px 0;}
        .reading span{font-family:sans-serif;color:#ef4444;font-size:13px;letter-spacing:.12em;}
        .reading p{margin-bottom:4px;}
    """
}

data class DeepReadRenderedTemplate(
    val html: String,
    val allowedImageUrls: Set<String>,
)
