package app.amber.feature.board.hotlist.deepread

/**
 * Renders a persisted [DeepReadOutput] as a Markdown document for share/export.
 * Source of truth is the cached structured output — nothing is recomposed from
 * UI state, so partial runs export exactly the sections that are READY.
 */
object DeepReadMarkdownExporter {

    fun toMarkdown(title: String, output: DeepReadOutput): String = buildString {
        appendLine("# ${title.trim()}")
        appendLine()

        if (output.summary.isNotBlank()) {
            appendLine(output.summary.trim())
            appendLine()
        }
        if (output.keyEntities.isNotEmpty()) {
            appendLine("**Key entities**: ${output.keyEntities.joinToString(", ")}")
            appendLine()
        }

        val timeline = output.timeline.orEmpty().filter { it.event.isNotBlank() }
        if (timeline.isNotEmpty()) {
            appendLine("## Timeline")
            appendLine()
            timeline.forEach { event ->
                append("- ")
                if (event.date.isNotBlank()) append("**${event.date.trim()}** — ")
                appendLine(event.event.trim())
            }
            appendLine()
        }

        val corePoints = output.corePoints.orEmpty().filter { it.point.isNotBlank() }
        if (corePoints.isNotEmpty()) {
            appendLine("## Key points")
            appendLine()
            corePoints.forEach { point ->
                appendLine("- ${point.point.trim()}")
                point.supporting?.takeIf { it.isNotBlank() }?.let {
                    appendLine("  ${it.trim()}")
                }
            }
            appendLine()
        }

        val analysis = output.analysis
        val hasAnalysis = !analysis.coreDispute.isNullOrBlank() ||
            !analysis.implications.isNullOrBlank() ||
            analysis.perspectives.any { it.viewpoint.isNotBlank() } ||
            analysis.quotes.any { it.text.isNotBlank() }
        if (hasAnalysis) {
            appendLine("## Analysis")
            appendLine()
            analysis.coreDispute?.takeIf { it.isNotBlank() }?.let {
                appendLine(it.trim())
                appendLine()
            }
            val perspectives = analysis.perspectives.filter { it.viewpoint.isNotBlank() }
            if (perspectives.isNotEmpty()) {
                appendLine("### Perspectives")
                appendLine()
                perspectives.forEach { perspective ->
                    append("- ")
                    perspective.holder?.takeIf { it.isNotBlank() }?.let { append("**${it.trim()}**: ") }
                    appendLine(perspective.viewpoint.trim())
                }
                appendLine()
            }
            analysis.implications?.takeIf { it.isNotBlank() }?.let {
                appendLine("### Implications")
                appendLine()
                appendLine(it.trim())
                appendLine()
            }
            val quotes = analysis.quotes.filter { it.text.isNotBlank() }
            if (quotes.isNotEmpty()) {
                appendLine("### Quotes")
                appendLine()
                quotes.forEach { quote ->
                    append("> ${quote.text.trim()}")
                    quote.attribution?.takeIf { it.isNotBlank() }?.let { append(" — ${it.trim()}") }
                    appendLine()
                }
                appendLine()
            }
        }

        val links = (output.extendedReading + output.references)
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.url }
        if (links.isNotEmpty()) {
            appendLine("## Further reading")
            appendLine()
            links.forEach { link ->
                append("- [${link.title.trim()}](${link.url.trim()})")
                link.source?.takeIf { it.isNotBlank() }?.let { append(" — ${it.trim()}") }
                appendLine()
            }
        }
    }.trimEnd() + "\n"
}
