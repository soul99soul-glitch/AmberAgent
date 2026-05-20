package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.verifiedImageUrls

object DeepReadTemplateRenderer {
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
        return DeepReadRenderedTemplate(html = body, allowedImageUrls = safeImages)
    }

    private fun DeepReadOutput.safeImageUrls(): Set<String> = verifiedImageUrls()

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
        article{padding-bottom:42px;}
        .hero{margin:0 0 20px 0;position:relative;background:#f0f0ec;}
        .hero img{display:block;width:100%;height:282px;object-fit:cover;}
        .hero:after{content:"";display:block;height:70px;background:#fafaf8;clip-path:polygon(0 30%,100% 0,100% 100%,0 100%);margin-top:-50px;position:relative;}
        figcaption{font-size:10px;color:#6b7280;line-height:1.55;margin:6px 26px 0;text-align:right;}
        .headline,section{padding:0 26px;}
        .kicker,.section,.date,.holder,small{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:#6b7280;font-size:10px;}
        h1{font-weight:500;font-size:36px;line-height:1.15;margin:14px 0 18px;}
        h2{font-weight:500;font-size:20px;line-height:1.36;margin:0 0 7px;}
        p{font-size:16px;line-height:1.72;margin:0 0 14px;}
        .summary{font-size:16px;line-height:1.72;}
        section{margin-top:34px;}
        .timeline{display:grid;grid-template-columns:36px 1fr;gap:12px;padding:15px 0;border-top:1px solid #ddd;}
        .num{font-family:var(--deep-read-sans);color:#ef4444;letter-spacing:.12em;font-size:12px;padding-top:4px;}
        .inline{margin:12px 0 4px;background:#f0f0ec;}
        .inline img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
        .inline figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        blockquote{font-size:20px;line-height:1.52;margin:0 0 20px;padding-left:14px;border-left:3px solid #ef4444;}
        .reading{display:grid;grid-template-columns:36px 1fr;gap:12px;border-top:1px solid #ddd;padding:15px 0;}
        .reading span{font-family:var(--deep-read-sans);color:#ef4444;font-size:12px;letter-spacing:.12em;}
        .reading p{margin-bottom:4px;}
    """
}

data class DeepReadRenderedTemplate(
    val html: String,
    val allowedImageUrls: Set<String>,
)
