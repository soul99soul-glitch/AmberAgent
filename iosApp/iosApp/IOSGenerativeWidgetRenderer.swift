import Foundation
import CoreFoundation

/// Preview-only renderer for static SVG generation from widget specs.
/// Interactive vchart/slides/full_html paths use bundled sandbox WebViews in
/// `IOSGenerativeWidgetCard`; this renderer mirrors Android's timeline preview.
struct IOSGenerativeWidgetRenderer: IOSGenerativeWidgetRendering {
    func render(renderer: String, specJson: String?) -> String? {
        if IOSGuizangHtmlDeckValidator.isRenderer(renderer) {
            guard let specJson, let spec = jsonObject(specJson) else {
                return renderErrorSVG("full_html: no spec")
            }
            let title = spec.string("title") ?? spec.string("source") ?? "Full HTML Deck"
            return renderFullHTMLPreview(title: title)
        }
        if renderer.lowercased() == "slides" {
            guard let specJson, let spec = jsonValue(specJson) else {
                return renderErrorSVG("slides: invalid spec shape")
            }
            guard let slides = extractSlidesArray(spec) else {
                return renderErrorSVG("slides: invalid spec shape")
            }
            return renderSlidesPreview(slides) ?? renderErrorSVG("slides: rendering failed")
        }
        guard let specJson, let spec = jsonObject(specJson) else {
            return renderErrorSVG("\(renderer): no spec")
        }
        switch renderer.lowercased() {
        case "chart", "vchart":
            return renderChart(spec) ?? renderErrorSVG("chart: no renderable data")
        case "diagram":
            return renderDiagram(spec) ?? renderErrorSVG("diagram: no renderable data")
        default:
            return nil
        }
    }

    private func renderErrorSVG(_ message: String) -> String {
        let escaped = escapeXML(message)
        return """
        <svg width="400" height="80" xmlns="http://www.w3.org/2000/svg">
            <rect width="100%" height="100%" fill="#fef2f2" rx="8"/>
            <text x="200" y="44" text-anchor="middle" font-size="13" fill="#dc2626">\(escaped)</text>
        </svg>
        """
    }

    private func renderFullHTMLPreview(title: String) -> String {
        let safeTitle = String(escapeXML(title).prefix(56))
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
          <text x="52" y="68" font-size="13" letter-spacing="3" fill="rgba(255,255,255,.76)">FULL HTML DECK</text>
          <text x="52" y="116" font-size="28" font-weight="700" fill="#ffffff">\(safeTitle)</text>
          <text x="52" y="154" font-size="14" fill="rgba(255,255,255,.78)">Canvas · Motion · fullscreen deck</text>
          <circle cx="594" cy="64" r="20" fill="rgba(255,255,255,.18)"/>
          <circle cx="594" cy="64" r="8" fill="#fff"/>
        </svg>
        """
    }

    private func renderChart(_ spec: [String: Any]) -> String? {
        let type = spec.string("type")?.lowercased() ?? ""
        let labels = spec.stringArray("x").isEmpty ? spec.stringArray("labels").prefixArray(24) : spec.stringArray("x").prefixArray(24)
        let series = (spec["series"] as? [[String: Any]])?
            .compactMap { item -> ChartSeries? in
                let data = item.numberArray("data").prefixArray(24)
                guard !data.isEmpty else { return nil }
                let name = String((item.string("name") ?? "Value").prefix(32))
                return ChartSeries(name: name.isEmpty ? "Value" : name, data: data)
            }
            .prefixArray(4) ?? []
        guard !labels.isEmpty, !series.isEmpty else { return nil }
        switch type {
        case "line":
            return renderLineChart(labels: labels, series: series)
        case "bar", "column":
            return renderBarChart(labels: labels, series: series)
        case "pie", "donut":
            return renderPieLikeChart(labels: labels, series: series[0])
        default:
            return renderBarChart(labels: labels, series: series)
        }
    }

    private func renderLineChart(labels: [String], series: [ChartSeries]) -> String {
        let width = 680
        let height = 340
        let left = 54.0
        let top = 34.0
        let chartWidth = 572.0
        let chartHeight = 218.0
        let maxValue = max(series.flatMap(\.data).max() ?? 0.0, 1.0)
        let colors = ["#2563eb", "#16a34a", "#ea580c", "#9333ea"]
        let paths = series.enumerated().map { seriesIndex, item in
            let points = item.data.enumerated().map { pointIndex, value in
                let x = left + chartWidth * (Double(pointIndex) / Double(max(labels.count - 1, 1)))
                let y = top + chartHeight - chartHeight * (value / maxValue)
                return "\(round1(x)),\(round1(y))"
            }
            let circles = points.map { point in
                let parts = point.split(separator: ",")
                return "<circle cx=\"\(parts[0])\" cy=\"\(parts[1])\" r=\"4\" fill=\"\(colors[seriesIndex % colors.count])\"/>"
            }.joined(separator: "\n")
            return """
            <polyline points="\(points.joined(separator: " "))" fill="none" stroke="\(colors[seriesIndex % colors.count])" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            \(circles)
            """
        }
        let content = [
            axisSVG(left: left, top: top, chartWidth: chartWidth, chartHeight: chartHeight, labels: labels),
            paths.joined(separator: "\n"),
            legendSVG(labels: series.map(\.name), colors: colors, x: left, y: 290.0),
        ].joined(separator: "\n")
        return chartSVG(width: width, height: height) { content }
    }

    private func renderBarChart(labels: [String], series: [ChartSeries]) -> String {
        let width = 680
        let height = 340
        let left = 54.0
        let top = 34.0
        let chartWidth = 572.0
        let chartHeight = 218.0
        let maxValue = max(series.flatMap(\.data).max() ?? 0.0, 1.0)
        let colors = ["#2563eb", "#16a34a", "#ea580c", "#9333ea"]
        let groupWidth = chartWidth / Double(max(labels.count, 1))
        let barWidth = min(groupWidth * 0.72 / Double(max(series.count, 1)), 28.0)
        return chartSVG(width: width, height: height) {
            var output = axisSVG(left: left, top: top, chartWidth: chartWidth, chartHeight: chartHeight, labels: labels)
            for (seriesIndex, item) in series.enumerated() {
                for (pointIndex, value) in item.data.prefix(labels.count).enumerated() {
                    let barHeight = chartHeight * (value / maxValue)
                    let x = left + Double(pointIndex) * groupWidth + groupWidth * 0.14 + Double(seriesIndex) * barWidth
                    let y = top + chartHeight - barHeight
                    output += "\n<rect x=\"\(round1(x))\" y=\"\(round1(y))\" width=\"\(round1(barWidth))\" height=\"\(round1(barHeight))\" rx=\"4\" fill=\"\(colors[seriesIndex % colors.count])\"/>"
                }
            }
            output += "\n" + legendSVG(labels: series.map(\.name), colors: colors, x: left, y: 290.0)
            return output
        }
    }

    private func renderPieLikeChart(labels: [String], series: ChartSeries) -> String {
        let total = series.data.reduce(0, +)
        guard total > 0 else { return renderBarChart(labels: labels, series: [series]) }
        let colors = ["#2563eb", "#16a34a", "#ea580c", "#9333ea", "#0891b2", "#be123c"]
        return chartSVG(width: 680, height: 340) {
            var output = "<text x=\"34\" y=\"42\" font-size=\"18\" font-weight=\"700\" fill=\"#111827\">占比</text>"
            var x = 34.0
            for (index, value) in series.data.prefix(labels.count).enumerated() {
                let width = max(560.0 * value / total, 4.0)
                output += "\n<rect x=\"\(round1(x))\" y=\"76\" width=\"\(round1(width))\" height=\"56\" rx=\"10\" fill=\"\(colors[index % colors.count])\"/>"
                x += width
            }
            for (index, label) in labels.prefix(series.data.count).enumerated() {
                let y = 176 + index * 24
                let pct = round1(series.data[index] / total * 100)
                output += "\n<circle cx=\"42\" cy=\"\(y)\" r=\"6\" fill=\"\(colors[index % colors.count])\"/>"
                output += "\n<text x=\"58\" y=\"\(y + 5)\" font-size=\"14\" fill=\"#374151\">\(escapeXML(label)) \(pct)%</text>"
            }
            return output
        }
    }

    private func renderDiagram(_ spec: [String: Any]) -> String? {
        switch spec.string("type")?.lowercased() {
        case "timeline":
            return renderTimeline(spec["items"] as? [Any] ?? [])
        case "flow":
            return renderFlow(spec["nodes"] as? [Any] ?? [])
        case "matrix", "risk":
            return renderMatrix(spec["rows"] as? [Any] ?? [])
        default:
            return renderFlow(spec["nodes"] as? [Any] ?? [])
        }
    }

    private func renderTimeline(_ items: [Any]) -> String? {
        let rows = items.compactMap { $0 as? [String: Any] }.prefixArray(8)
        guard !rows.isEmpty else { return nil }
        let height = 76 + rows.count * 76
        return baseSVG(width: 680, height: height) {
            var output = "<line x1=\"52\" y1=\"42\" x2=\"52\" y2=\"\(height - 36)\" stroke=\"#cbd5e1\" stroke-width=\"3\"/>"
            for (index, row) in rows.enumerated() {
                let y = 58 + index * 76
                output += "\n<circle cx=\"52\" cy=\"\(y)\" r=\"11\" fill=\"#2563eb\"/>"
                output += "\n<text x=\"82\" y=\"\(y - 6)\" font-size=\"17\" font-weight=\"700\" fill=\"#111827\">\(escapeXML(row.string("label") ?? ""))</text>"
                output += "\n<text x=\"82\" y=\"\(y + 19)\" font-size=\"14\" fill=\"#475569\">\(escapeXML(row.string("detail") ?? ""))</text>"
            }
            return output
        }
    }

    private func renderFlow(_ nodes: [Any]) -> String? {
        let items = nodes.compactMap { $0 as? [String: Any] }.prefixArray(4)
        guard !items.isEmpty else { return nil }
        let width = 680
        let horizontalPadding = 28.0
        let gap = 22.0
        let count = max(items.count, 1)
        let boxWidth = min(max((Double(width) - horizontalPadding * 2 - gap * Double(count - 1)) / Double(count), 118.0), 172.0)
        return baseSVG(width: width, height: 220) {
            var output = ""
            for (index, node) in items.enumerated() {
                let x = horizontalPadding + Double(index) * (boxWidth + gap)
                let y = 62
                output += "\n<rect x=\"\(round1(x))\" y=\"\(y)\" width=\"\(round1(boxWidth))\" height=\"82\" rx=\"14\" fill=\"#eff6ff\" stroke=\"#bfdbfe\"/>"
                output += "\n<text x=\"\(round1(x + 16))\" y=\"\(y + 35)\" font-size=\"16\" font-weight=\"700\" fill=\"#1e3a8a\">\(String(escapeXML(node.string("label") ?? "").prefix(18)))</text>"
                if let detail = node.string("detail")?.nilIfBlank {
                    output += "\n<text x=\"\(round1(x + 16))\" y=\"\(y + 59)\" font-size=\"12\" fill=\"#475569\">\(String(escapeXML(detail).prefix(24)))</text>"
                }
                if index < items.count - 1 {
                    let ax = x + boxWidth + 8
                    output += "\n<path d=\"M\(round1(ax)) \(y + 41) H\(round1(ax + gap - 16))\" stroke=\"#94a3b8\" stroke-width=\"2\" marker-end=\"url(#arrow)\"/>"
                }
            }
            return output
        }
    }

    private func renderMatrix(_ rows: [Any]) -> String? {
        let items = rows.compactMap { $0 as? [String: Any] }.prefixArray(8)
        guard !items.isEmpty else { return nil }
        let height = 72 + items.count * 52
        return baseSVG(width: 680, height: height) {
            var output = ""
            for (index, row) in items.enumerated() {
                let y = 36 + index * 52
                let tone: String
                switch row.string("tone")?.lowercased() {
                case "risk", "high", "red":
                    tone = "#fee2e2"
                case "ok", "low", "green":
                    tone = "#dcfce7"
                case "warn", "medium", "yellow":
                    tone = "#fef3c7"
                default:
                    tone = "#f1f5f9"
                }
                output += "\n<rect x=\"28\" y=\"\(y)\" width=\"624\" height=\"40\" rx=\"10\" fill=\"\(tone)\"/>"
                output += "\n<text x=\"44\" y=\"\(y + 26)\" font-size=\"15\" font-weight=\"700\" fill=\"#111827\">\(String(escapeXML(row.string("label") ?? "").prefix(32)))</text>"
                output += "\n<text x=\"316\" y=\"\(y + 26)\" font-size=\"14\" fill=\"#334155\">\(String(escapeXML(row.string("value") ?? "").prefix(48)))</text>"
            }
            return output
        }
    }

    private func renderSlidesPreview(_ slides: [Any]) -> String? {
        let items = slides.compactMap { $0 as? [String: Any] }.prefixArray(12)
        guard let first = items.first else { return nil }
        let title = first.string("title")?.nilIfBlank ?? "Slide 1"
        let subtitle = first.string("subtitle") ?? ""
        let bulletItems = stringList(first["content"]).prefixArray(5)
        let height = 220 + bulletItems.count * 28
        return baseSVG(width: 680, height: height) {
            var output = "<rect x=\"16\" y=\"16\" width=\"648\" height=\"\(height - 32)\" rx=\"14\" fill=\"#ffffff\" stroke=\"#e5e7eb\"/>"
            output += "\n<text x=\"40\" y=\"56\" font-size=\"22\" font-weight=\"700\" fill=\"#111827\">\(String(escapeXML(title).prefix(40)))</text>"
            if !subtitle.isEmpty {
                output += "\n<text x=\"40\" y=\"84\" font-size=\"15\" fill=\"#6b7280\">\(String(escapeXML(subtitle).prefix(50)))</text>"
            }
            let startY = subtitle.isEmpty ? 88 : 112
            for (index, item) in bulletItems.enumerated() {
                let y = startY + index * 28
                output += "\n<circle cx=\"50\" cy=\"\(y + 1)\" r=\"4\" fill=\"#3b82f6\"/>"
                output += "\n<text x=\"64\" y=\"\(y + 6)\" font-size=\"14\" fill=\"#374151\">\(String(escapeXML(item).prefix(50)))</text>"
            }
            if items.count > 1 {
                output += "\n<text x=\"40\" y=\"\(height - 28)\" font-size=\"12\" fill=\"#9ca3af\">\(items.count) slides · 点击展开浏览</text>"
            }
            return output
        }
    }

    private func chartSVG(width: Int, height: Int, _ content: () -> String) -> String {
        baseSVG(width: width, height: height) {
            """
            <rect x="20" y="16" width="\(width - 40)" height="\(height - 32)" rx="18" fill="#ffffff" stroke="#e5e7eb"/>
            \(content())
            """
        }
    }

    private func baseSVG(width: Int, height: Int, _ content: () -> String) -> String {
        """
        <svg width="100%" viewBox="0 0 \(width) \(height)" xmlns="http://www.w3.org/2000/svg">
        <defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#94a3b8"/></marker></defs>
        \(content())
        </svg>
        """
    }

    private func axisSVG(left: Double, top: Double, chartWidth: Double, chartHeight: Double, labels: [String]) -> String {
        var output = "<line x1=\"\(round1(left))\" y1=\"\(round1(top + chartHeight))\" x2=\"\(round1(left + chartWidth))\" y2=\"\(round1(top + chartHeight))\" stroke=\"#cbd5e1\"/>"
        output += "\n<line x1=\"\(round1(left))\" y1=\"\(round1(top))\" x2=\"\(round1(left))\" y2=\"\(round1(top + chartHeight))\" stroke=\"#cbd5e1\"/>"
        for (index, label) in labels.enumerated() {
            let x = left + chartWidth * (Double(index) / Double(max(labels.count - 1, 1)))
            output += "\n<text x=\"\(round1(x))\" y=\"\(round1(top + chartHeight + 24))\" text-anchor=\"middle\" font-size=\"12\" fill=\"#64748b\">\(String(escapeXML(label).prefix(10)))</text>"
        }
        return output
    }

    private func legendSVG(labels: [String], colors: [String], x: Double, y: Double) -> String {
        labels.prefix(4).enumerated().map { index, label in
            let itemX = x + Double(index) * 142
            return """
            <circle cx="\(round1(itemX))" cy="\(round1(y))" r="6" fill="\(colors[index % colors.count])"/>
            <text x="\(round1(itemX + 14))" y="\(round1(y + 5))" font-size="13" fill="#334155">\(String(escapeXML(label).prefix(18)))</text>
            """
        }.joined(separator: "\n")
    }

    private func extractSlidesArray(_ spec: Any) -> [Any]? {
        if let array = spec as? [Any] { return array }
        guard let obj = spec as? [String: Any] else { return nil }
        if let slides = obj["slides"] as? [Any] { return slides }
        if let pages = obj["pages"] as? [Any] { return pages }
        if let specArray = obj["spec"] as? [Any] { return specArray }
        if let specObj = obj["spec"] as? [String: Any] { return extractSlidesArray(specObj) }
        return nil
    }

    private func stringList(_ value: Any?) -> [String] {
        if let array = value as? [Any] {
            return array.map { "\($0)".trimmingCharacters(in: .whitespacesAndNewlines) }
        }
        if let value, !(value is NSNull) {
            return ["\(value)".trimmingCharacters(in: .whitespacesAndNewlines)]
        }
        return []
    }

    private func round1(_ value: Double) -> String {
        String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    private struct ChartSeries {
        let name: String
        let data: [Double]
    }
}

private extension Dictionary where Key == String, Value == Any {
    func stringArray(_ key: String) -> [String] {
        (self[key] as? [Any])?.map { "\($0)".trimmingCharacters(in: .whitespacesAndNewlines) } ?? []
    }

    func numberArray(_ key: String) -> [Double] {
        (self[key] as? [Any])?.compactMap { value -> Double? in
            if let number = value as? NSNumber {
                if CFGetTypeID(number) == CFBooleanGetTypeID() { return nil }
                let double = number.doubleValue
                guard double.isFinite else { return nil }
                return Swift.min(Swift.max(double, 0), 1_000_000_000)
            }
            if let string = value as? String, let double = Double(string), double.isFinite {
                return Swift.min(Swift.max(double, 0), 1_000_000_000)
            }
            return nil
        } ?? []
    }
}

private extension Array {
    func prefixArray(_ count: Int) -> [Element] {
        Array(prefix(count))
    }
}
