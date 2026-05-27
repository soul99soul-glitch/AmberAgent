package me.rerere.rikkahub.data.ai.generative

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Preview-only renderer for static SVG generation from widget specs.
 * Does NOT replace the interactive VChart/slides sandbox — those use the actual
 * spec JSON injected into a sandboxed WebView. This renderer produces inline SVG
 * that displays as a fallback preview in the chat timeline.
 */
object GenerativeWidgetRenderer {
    fun render(renderer: String?, spec: JsonElement?): String? {
        if (renderer?.lowercase() == GuizangHtmlDeckValidator.RENDERER) {
            val specObject = spec as? JsonObject ?: return renderErrorSvg("guizang_html: no spec")
            val title = specObject.string("title")
                ?: specObject.string("source")
                ?: "Guizang Live Deck"
            return renderGuizangPreview(title)
        }
        if (renderer?.lowercase() == "slides") {
            val specArray = when (spec) {
                is JsonArray -> spec
                is JsonObject -> spec["slides"]?.jsonArrayOrNull()
                    ?: spec["spec"]?.jsonArrayOrNull()
                    ?: spec["pages"]?.jsonArrayOrNull()
                else -> null
            }
            if (specArray != null) {
                return runCatching { renderSlidesPreview(specArray) }.getOrNull()
                    ?: renderErrorSvg("slides: rendering failed")
            }
            return renderErrorSvg("slides: invalid spec shape")
        }
        val specObject = spec as? JsonObject ?: return renderErrorSvg("${renderer}: no spec")
        return runCatching {
            when (renderer?.lowercase()) {
                "chart", "vchart" -> renderChart(specObject) ?: renderErrorSvg("chart: no renderable data")
                "diagram" -> renderDiagram(specObject) ?: renderErrorSvg("diagram: no renderable data")
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Returns a minimal error SVG so the parser never drops the widget silently.
     */
    private fun renderErrorSvg(message: String): String {
        val escaped = message.replace("&", "&amp;").replace("<", "&lt;")
        return """
            <svg width="400" height="80" xmlns="http://www.w3.org/2000/svg">
                <rect width="100%" height="100%" fill="#fef2f2" rx="8"/>
                <text x="200" y="44" text-anchor="middle" font-size="13" fill="#dc2626">$escaped</text>
            </svg>
        """.trimIndent()
    }

    private fun renderGuizangPreview(title: String): String {
        val safeTitle = escape(title).take(56)
        return """
            <svg width="100%" viewBox="0 0 680 220" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stop-color="#111827"/>
                  <stop offset="0.52" stop-color="#1F5EFF"/>
                  <stop offset="1" stop-color="#f8fafc"/>
                </linearGradient>
              </defs>
              <rect width="680" height="220" rx="16" fill="url(#g)"/>
              <rect x="26" y="24" width="628" height="172" rx="12" fill="rgba(255,255,255,.13)" stroke="rgba(255,255,255,.35)"/>
              <text x="52" y="68" font-size="13" letter-spacing="3" fill="rgba(255,255,255,.76)">GUIZANG LIVE HTML</text>
              <text x="52" y="116" font-size="28" font-weight="700" fill="#ffffff">$safeTitle</text>
              <text x="52" y="154" font-size="14" fill="rgba(255,255,255,.78)">WebGL · Motion One · fullscreen deck</text>
              <circle cx="594" cy="64" r="20" fill="rgba(255,255,255,.18)"/>
              <circle cx="594" cy="64" r="8" fill="#fff"/>
            </svg>
        """.trimIndent()
    }

    private fun renderChart(spec: JsonObject): String? {
        val type = spec.string("type")?.lowercase().orEmpty()
        val labels = spec.stringArray("x").ifEmpty { spec.stringArray("labels") }.take(24)
        val series = spec["series"]?.jsonArrayOrNull()
            ?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val data = obj.numberArray("data").take(24)
                if (data.isEmpty()) return@mapNotNull null
                ChartSeries(
                    name = obj.string("name")?.take(32).orEmpty().ifBlank { "Value" },
                    data = data,
                )
            }
            ?.take(4)
            .orEmpty()
        if (labels.isEmpty() || series.isEmpty()) return null
        return when (type) {
            "line" -> renderLineChart(labels, series)
            "bar", "column" -> renderBarChart(labels, series)
            "pie", "donut" -> renderPieLikeChart(labels, series.first())
            else -> renderBarChart(labels, series)
        }
    }

    private fun renderLineChart(labels: List<String>, series: List<ChartSeries>): String {
        val width = 680
        val height = 340
        val left = 54.0
        val top = 34.0
        val chartWidth = 572.0
        val chartHeight = 218.0
        val max = series.flatMap { it.data }.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val colors = listOf("#2563eb", "#16a34a", "#ea580c", "#9333ea")
        val paths = series.mapIndexed { index, item ->
            val points = item.data.mapIndexed { pointIndex, value ->
                val x = left + chartWidth * (pointIndex.toDouble() / (labels.size - 1).coerceAtLeast(1))
                val y = top + chartHeight - chartHeight * (value / max)
                "${x.round1()},${y.round1()}"
            }
            """
            <polyline points="${points.joinToString(" ")}" fill="none" stroke="${colors[index % colors.size]}" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            ${points.joinToString("\n") { point -> """<circle cx="${point.substringBefore(",")}" cy="${point.substringAfter(",")}" r="4" fill="${colors[index % colors.size]}"/>""" }}
            """.trimIndent()
        }
        return chartSvg(width, height) {
            appendLine(axisSvg(left, top, chartWidth, chartHeight, labels))
            paths.forEach { appendLine(it) }
            appendLine(legendSvg(series.map { it.name }, colors, left, 290.0))
        }
    }

    private fun renderBarChart(labels: List<String>, series: List<ChartSeries>): String {
        val width = 680
        val height = 340
        val left = 54.0
        val top = 34.0
        val chartWidth = 572.0
        val chartHeight = 218.0
        val max = series.flatMap { it.data }.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val colors = listOf("#2563eb", "#16a34a", "#ea580c", "#9333ea")
        val groupWidth = chartWidth / labels.size.coerceAtLeast(1)
        val barWidth = (groupWidth * 0.72 / series.size.coerceAtLeast(1)).coerceAtMost(28.0)
        return chartSvg(width, height) {
            appendLine(axisSvg(left, top, chartWidth, chartHeight, labels))
            series.forEachIndexed { seriesIndex, item ->
                item.data.take(labels.size).forEachIndexed { pointIndex, value ->
                    val barHeight = chartHeight * (value / max)
                    val x = left + pointIndex * groupWidth + groupWidth * 0.14 + seriesIndex * barWidth
                    val y = top + chartHeight - barHeight
                    appendLine(
                        """<rect x="${x.round1()}" y="${y.round1()}" width="${barWidth.round1()}" height="${barHeight.round1()}" rx="4" fill="${colors[seriesIndex % colors.size]}"/>"""
                    )
                }
            }
            appendLine(legendSvg(series.map { it.name }, colors, left, 290.0))
        }
    }

    private fun renderPieLikeChart(labels: List<String>, series: ChartSeries): String {
        val total = series.data.sum().takeIf { it > 0.0 } ?: return renderBarChart(labels, listOf(series))
        val colors = listOf("#2563eb", "#16a34a", "#ea580c", "#9333ea", "#0891b2", "#be123c")
        return chartSvg(680, 340) {
            appendLine("""<text x="34" y="42" font-size="18" font-weight="700" fill="#111827">占比</text>""")
            var x = 34.0
            series.data.take(labels.size).forEachIndexed { index, value ->
                val width = (560.0 * value / total).coerceAtLeast(4.0)
                appendLine("""<rect x="${x.round1()}" y="76" width="${width.round1()}" height="56" rx="10" fill="${colors[index % colors.size]}"/>""")
                x += width
            }
            labels.take(series.data.size).forEachIndexed { index, label ->
                val y = 176 + index * 24
                appendLine("""<circle cx="42" cy="$y" r="6" fill="${colors[index % colors.size]}"/>""")
                appendLine("""<text x="58" y="${y + 5}" font-size="14" fill="#374151">${escape(label)} ${(series.data[index] / total * 100).round1()}%</text>""")
            }
        }
    }

    private fun renderDiagram(spec: JsonObject): String? {
        return when (spec.string("type")?.lowercase()) {
            "timeline" -> renderTimeline(spec["items"]?.jsonArrayOrNull().orEmpty())
            "flow" -> renderFlow(spec["nodes"]?.jsonArrayOrNull().orEmpty())
            "matrix", "risk" -> renderMatrix(spec["rows"]?.jsonArrayOrNull().orEmpty())
            else -> renderFlow(spec["nodes"]?.jsonArrayOrNull().orEmpty())
        }
    }

    private fun renderTimeline(items: List<JsonElement>): String? {
        val rows = items.mapNotNull { it as? JsonObject }.take(8)
        if (rows.isEmpty()) return null
        val height = 76 + rows.size * 76
        return baseSvg(680, height) {
            appendLine("""<line x1="52" y1="42" x2="52" y2="${height - 36}" stroke="#cbd5e1" stroke-width="3"/>""")
            rows.forEachIndexed { index, row ->
                val y = 58 + index * 76
                appendLine("""<circle cx="52" cy="$y" r="11" fill="#2563eb"/>""")
                appendLine("""<text x="82" y="${y - 6}" font-size="17" font-weight="700" fill="#111827">${escape(row.string("label").orEmpty())}</text>""")
                appendLine("""<text x="82" y="${y + 19}" font-size="14" fill="#475569">${escape(row.string("detail").orEmpty())}</text>""")
            }
        }
    }

    private fun renderFlow(nodes: List<JsonElement>): String? {
        val items = nodes.mapNotNull { it as? JsonObject }.take(4)
        if (items.isEmpty()) return null
        val width = 680
        val horizontalPadding = 28.0
        val gap = 22.0
        val count = items.size.coerceAtLeast(1)
        val boxWidth = ((width - horizontalPadding * 2 - gap * (count - 1)) / count).coerceIn(118.0, 172.0)
        return baseSvg(width, 220) {
            items.forEachIndexed { index, node ->
                val x = horizontalPadding + index * (boxWidth + gap)
                val y = 62
                appendLine("""<rect x="${x.round1()}" y="$y" width="${boxWidth.round1()}" height="82" rx="14" fill="#eff6ff" stroke="#bfdbfe"/>""")
                appendLine("""<text x="${(x + 16).round1()}" y="${y + 35}" font-size="16" font-weight="700" fill="#1e3a8a">${escape(node.string("label").orEmpty()).take(18)}</text>""")
                node.string("detail")?.takeIf { it.isNotBlank() }?.let {
                    appendLine("""<text x="${(x + 16).round1()}" y="${y + 59}" font-size="12" fill="#475569">${escape(it).take(24)}</text>""")
                }
                if (index < items.lastIndex) {
                    val ax = x + boxWidth + 8
                    appendLine("""<path d="M${ax.round1()} ${y + 41} H${(ax + gap - 16).round1()}" stroke="#94a3b8" stroke-width="2" marker-end="url(#arrow)"/>""")
                }
            }
        }
    }

    private fun renderMatrix(rows: List<JsonElement>): String? {
        val items = rows.mapNotNull { it as? JsonObject }.take(8)
        if (items.isEmpty()) return null
        val height = 72 + items.size * 52
        return baseSvg(680, height) {
            items.forEachIndexed { index, row ->
                val y = 36 + index * 52
                val tone = when (row.string("tone")?.lowercase()) {
                    "risk", "high", "red" -> "#fee2e2"
                    "ok", "low", "green" -> "#dcfce7"
                    "warn", "medium", "yellow" -> "#fef3c7"
                    else -> "#f1f5f9"
                }
                appendLine("""<rect x="28" y="$y" width="624" height="40" rx="10" fill="$tone"/>""")
                appendLine("""<text x="44" y="${y + 26}" font-size="15" font-weight="700" fill="#111827">${escape(row.string("label").orEmpty()).take(32)}</text>""")
                appendLine("""<text x="316" y="${y + 26}" font-size="14" fill="#334155">${escape(row.string("value").orEmpty()).take(48)}</text>""")
            }
        }
    }

    private fun renderSlidesPreview(slides: JsonArray): String? {
        val items = slides.mapNotNull { it as? JsonObject }.take(12)
        if (items.isEmpty()) return null
        val first = items.first()
        val title = first.string("title").orEmpty().ifBlank { "Slide 1" }
        val subtitle = first.string("subtitle").orEmpty()
        val bulletItems = first["content"]?.jsonArrayOrNull()
            ?.mapNotNull { runCatching { it.jsonPrimitive.content.trim() }.getOrNull() }
            ?.take(5)
            .orEmpty()
        val height = 220 + bulletItems.size * 28
        return baseSvg(680, height) {
            appendLine("""<rect x="16" y="16" width="648" height="${height - 32}" rx="14" fill="#ffffff" stroke="#e5e7eb"/>""")
            appendLine("""<text x="40" y="56" font-size="22" font-weight="700" fill="#111827">${escape(title).take(40)}</text>""")
            if (subtitle.isNotBlank()) {
                appendLine("""<text x="40" y="84" font-size="15" fill="#6b7280">${escape(subtitle).take(50)}</text>""")
            }
            val startY = if (subtitle.isNotBlank()) 112 else 88
            bulletItems.forEachIndexed { index, item ->
                val y = startY + index * 28
                appendLine("""<circle cx="50" cy="${y + 1}" r="4" fill="#3b82f6"/>""")
                appendLine("""<text x="64" y="${y + 6}" font-size="14" fill="#374151">${escape(item).take(50)}</text>""")
            }
            if (items.size > 1) {
                appendLine("""<text x="40" y="${height - 28}" font-size="12" fill="#9ca3af">${items.size} slides · 点击展开浏览</text>""")
            }
        }
    }

    private fun chartSvg(width: Int, height: Int, content: StringBuilder.() -> Unit): String =
        baseSvg(width, height) {
            appendLine("""<rect x="20" y="16" width="${width - 40}" height="${height - 32}" rx="18" fill="#ffffff" stroke="#e5e7eb"/>""")
            content()
        }

    private fun baseSvg(width: Int, height: Int, content: StringBuilder.() -> Unit): String =
        buildString {
            appendLine("""<svg width="100%" viewBox="0 0 $width $height" xmlns="http://www.w3.org/2000/svg">""")
            appendLine("""<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#94a3b8"/></marker></defs>""")
            content()
            appendLine("</svg>")
        }

    private fun axisSvg(
        left: Double,
        top: Double,
        chartWidth: Double,
        chartHeight: Double,
        labels: List<String>,
    ): String = buildString {
        appendLine("""<line x1="${left.round1()}" y1="${(top + chartHeight).round1()}" x2="${(left + chartWidth).round1()}" y2="${(top + chartHeight).round1()}" stroke="#cbd5e1"/>""")
        appendLine("""<line x1="${left.round1()}" y1="${top.round1()}" x2="${left.round1()}" y2="${(top + chartHeight).round1()}" stroke="#cbd5e1"/>""")
        labels.forEachIndexed { index, label ->
            val x = left + chartWidth * (index.toDouble() / (labels.size - 1).coerceAtLeast(1))
            appendLine("""<text x="${x.round1()}" y="${(top + chartHeight + 24).round1()}" text-anchor="middle" font-size="12" fill="#64748b">${escape(label).take(10)}</text>""")
        }
    }

    private fun legendSvg(labels: List<String>, colors: List<String>, x: Double, y: Double): String = buildString {
        labels.take(4).forEachIndexed { index, label ->
            val itemX = x + index * 142
            appendLine("""<circle cx="${itemX.round1()}" cy="${y.round1()}" r="6" fill="${colors[index % colors.size]}"/>""")
            appendLine("""<text x="${(itemX + 14).round1()}" y="${(y + 5).round1()}" font-size="13" fill="#334155">${escape(label).take(18)}</text>""")
        }
    }

    private data class ChartSeries(
        val name: String,
        val data: List<Double>,
    )
}

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content?.trim() }.getOrNull()

private fun JsonObject.stringArray(key: String): List<String> =
    this[key]?.jsonArrayOrNull()
        ?.mapNotNull { runCatching { it.jsonPrimitive.content.trim() }.getOrNull() }
        .orEmpty()

private fun JsonObject.numberArray(key: String): List<Double> =
    this[key]?.jsonArrayOrNull()
        ?.mapNotNull { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
        ?.mapNotNull { value ->
            value
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, MAX_CHART_VALUE)
        }
        .orEmpty()

private fun Double.round1(): String = "%.1f".format(java.util.Locale.US, this)

private fun escape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private const val MAX_CHART_VALUE = 1_000_000_000.0
