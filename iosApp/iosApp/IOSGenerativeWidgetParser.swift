import Foundation

protocol IOSGenerativeWidgetRendering {
    func render(renderer: String, specJson: String?) -> String?
}

enum IOSGenerativeWidgetSegment: Identifiable, Equatable {
    case text(id: String, content: String)
    case widget(IOSGenerativeWidget)
    case loading(id: String)

    var id: String {
        switch self {
        case .text(let id, _), .loading(let id):
            id
        case .widget(let widget):
            widget.id
        }
    }
}

struct IOSGenerativeWidget: Identifiable, Equatable {
    let id: String
    var title: String?
    var widgetCode: String
    var complete: Bool
    var renderer: String = "html"
    var actions: [IOSGenerativeWidgetAction] = []
    var specJson: String?
}

struct IOSGenerativeWidgetAction: Identifiable, Equatable {
    let id: String
    let label: String
    let instruction: String

    func toUserPrompt(widgetTitle: String?) -> String {
        var output = "请基于上一张生成式 UI"
        if let widgetTitle, !widgetTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            output += "「\(widgetTitle)」"
        }
        output += "继续处理：\(instruction)"
        return output
    }
}

enum IOSGenerativeWidgetParser {
    private static let markers: Set<String> = ["show-widget", "widget", "generative-ui"]
    private static let renderableTagPattern = #"<\s*(?:svg|div|section|article|table|ul|ol|p|figure|main|aside|header|footer)\b"#
    private static let placeholderPhrases = [
        "human readable title",
        "<svg or static",
        "svg or static html",
        "static html/css",
        "<svg 或",
        "placeholder",
        "replace me",
        "todo",
    ]
    private static let blockedActionInstructionSnippets = [
        "<system",
        "</system",
        "ignore previous",
        "system prompt",
        "developer message",
        "tool call",
        "http://",
        "https://",
        "打开链接",
        "执行工具",
        "<script",
        "javascript:",
    ]
    private static let incompleteFullHTMLMessage = "可视化预览没有生成完整，已隐藏未完成的 HTML 内容。请重新生成或缩短内容。"

    static func containsWidgetFence(_ content: String) -> Bool {
        findMarker(in: content, from: content.startIndex) != nil
    }

    static func containsFullHtmlDeckPayload(_ content: String) -> Bool {
        parseStandaloneFullHtmlWidget(content, idSeed: "standalone") != nil
    }

    static func hasRenderableWidget(_ content: String) -> Bool {
        parse(content, streaming: false).contains { segment in
            if case .widget = segment { return true }
            return false
        }
    }

    static func parse(
        _ content: String,
        streaming: Bool,
        renderer: IOSGenerativeWidgetRendering = IOSGenerativeWidgetRenderer()
    ) -> [IOSGenerativeWidgetSegment] {
        var segments: [IOSGenerativeWidgetSegment] = []
        var cursor = content.startIndex
        var foundWidgetMarker = false

        while cursor < content.endIndex {
            guard let marker = findMarker(in: content, from: cursor) else { break }
            foundWidgetMarker = true
            appendTextSegment(&segments, String(content[cursor..<marker.range.lowerBound]), idSeed: "text-\(content.distance(from: content.startIndex, to: cursor))")

            let afterMarker = marker.range.upperBound
            guard let jsonStart = firstIndex(of: "{", in: content, from: afterMarker),
                  content.distance(from: afterMarker, to: jsonStart) <= 80 else {
                if streaming {
                    segments.append(.loading(id: "loading-\(content.distance(from: content.startIndex, to: marker.range.lowerBound))"))
                    return segments
                }
                let fragment = String(content[marker.range.lowerBound...])
                if !appendIncompleteFullHtmlMessage(&segments, widgetFragment: fragment) {
                    appendTextSegment(&segments, fragment, idSeed: "fragment-\(content.distance(from: content.startIndex, to: marker.range.lowerBound))")
                }
                return segments
            }

            if let jsonRange = jsonObjectRange(in: content, start: jsonStart) {
                let jsonText = String(content[jsonRange])
                if let widget = parseWidgetJson(jsonText, idSeed: "widget-\(content.distance(from: content.startIndex, to: jsonStart))", renderer: renderer) {
                    segments.append(.widget(widget))
                    cursor = skipTrailingFence(in: content, from: jsonRange.upperBound)
                } else {
                    let fallbackEnd = skipTrailingFence(in: content, from: jsonRange.upperBound)
                    let fragment = String(content[marker.range.lowerBound..<fallbackEnd])
                    if streaming, looksLikeFullHtmlWidgetFragment(fragment) {
                        segments.append(.loading(id: "loading-\(content.distance(from: content.startIndex, to: marker.range.lowerBound))"))
                        return segments
                    }
                    if !appendIncompleteFullHtmlMessage(&segments, widgetFragment: fragment) {
                        appendTextSegment(&segments, fragment, idSeed: "fragment-\(content.distance(from: content.startIndex, to: marker.range.lowerBound))")
                    }
                    cursor = fallbackEnd
                }
                continue
            }

            if streaming {
                let partialText = String(content[jsonStart...])
                if let partial = parsePartialWidget(partialText, idSeed: "partial-\(content.distance(from: content.startIndex, to: jsonStart))") {
                    segments.append(.widget(partial))
                } else {
                    segments.append(.loading(id: "loading-\(content.distance(from: content.startIndex, to: marker.range.lowerBound))"))
                }
                return segments
            }

            let fragment = String(content[marker.range.lowerBound...])
            if !appendIncompleteFullHtmlMessage(&segments, widgetFragment: fragment) {
                appendTextSegment(&segments, fragment, idSeed: "fragment-\(content.distance(from: content.startIndex, to: marker.range.lowerBound))")
            }
            return segments
        }

        let trailing = String(content[cursor...])
        if streaming, !foundWidgetMarker, hasPartialWidgetFenceAtEnd(trailing),
           let match = partialFenceMatch(in: trailing) {
            appendTextSegment(&segments, String(trailing[..<match.lowerBound]), idSeed: "tail")
            segments.append(.loading(id: "loading-tail"))
            return segments
        }
        appendTextSegment(&segments, trailing, idSeed: "tail-\(content.distance(from: content.startIndex, to: cursor))")
        if foundWidgetMarker { return segments }
        if let standalone = parseStandaloneFullHtmlWidget(content, idSeed: "standalone") {
            return [.widget(standalone)]
        }
        return [.text(id: "plain-\(stableID(content))", content: content)]
    }

    private static func appendTextSegment(_ segments: inout [IOSGenerativeWidgetSegment], _ content: String, idSeed: String) {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            segments.append(.text(id: "\(idSeed)-\(stableID(trimmed))", content: trimmed))
        }
    }

    @discardableResult
    private static func appendIncompleteFullHtmlMessage(_ segments: inout [IOSGenerativeWidgetSegment], widgetFragment: String) -> Bool {
        guard looksLikeFullHtmlWidgetFragment(widgetFragment) else { return false }
        appendTextSegment(&segments, incompleteFullHTMLMessage, idSeed: "incomplete-full-html")
        return true
    }

    private static func parseWidgetJson(
        _ jsonText: String,
        idSeed: String,
        renderer rendererImpl: IOSGenerativeWidgetRendering
    ) -> IOSGenerativeWidget? {
        let parsed = jsonObject(jsonText)
        let renderer = normalizeRenderer(parsed?.string("renderer"))
        if let recovered = recoverFullHtmlWidget(parsed: parsed, jsonText: jsonText, idSeed: idSeed) {
            return recovered
        }

        let specValue: Any?
        if renderer == "slides", parsed?["spec"] == nil, parsed?["slides"] != nil || parsed?["pages"] != nil {
            specValue = parsed
        } else {
            specValue = parsed?["spec"] ?? (renderer == "slides" ? (parsed?["slides"] ?? parsed?["pages"]) : nil)
        }
        let specJson = specValue.flatMap(jsonString)
        let renderedCode = renderer.flatMap { rendererImpl.render(renderer: $0, specJson: specJson) }
        let rawWidgetCode = parsed?.string("widget_code") ?? extractJsonStringValue(jsonText, key: "widget_code", allowUnclosed: false)
        let title = parsed?.string("title") ?? extractJsonStringValue(jsonText, key: "title", allowUnclosed: false)
        let normalizedSpecJson: String? = {
            switch renderer {
            case "slides":
                return specJson.flatMap(IOSVChartSpecValidator.normalizeSlidesDeckSpecJson)
            case IOSGuizangHtmlDeckValidator.renderer:
                return specJson
            default:
                return specJson
            }
        }()
        let code: String?
        switch renderer {
        case "vchart", "slides", IOSGuizangHtmlDeckValidator.renderer:
            code = renderedCode ?? rawWidgetCode
        case "html":
            code = rawWidgetCode
        case nil:
            code = rawWidgetCode
        default:
            code = renderedCode ?? rawWidgetCode
        }
        guard let renderableCode = code?.nilIfBlank,
              isRenderableWidgetCode(renderableCode, complete: true) else {
            return nil
        }
        return IOSGenerativeWidget(
            id: "\(idSeed)-\(stableID(jsonText))",
            title: normalizeWidgetTitle(title),
            widgetCode: renderableCode,
            complete: true,
            renderer: renderer ?? "html",
            actions: parseActions(parsed),
            specJson: normalizedSpecJson
        )
    }

    private static func parsePartialWidget(_ jsonText: String, idSeed: String) -> IOSGenerativeWidget? {
        if let code = extractJsonStringValue(jsonText, key: "widget_code", allowUnclosed: true)?.nilIfBlank {
            guard isRenderableWidgetCode(code, complete: false) else { return nil }
            return IOSGenerativeWidget(
                id: "\(idSeed)-widget-code",
                title: normalizeWidgetTitle(extractJsonStringValue(jsonText, key: "title", allowUnclosed: true)),
                widgetCode: code,
                complete: false,
                renderer: normalizeRenderer(extractJsonStringValue(jsonText, key: "renderer", allowUnclosed: true)) ?? "html"
            )
        }

        guard let renderer = extractJsonStringValue(jsonText, key: "renderer", allowUnclosed: true)
            .flatMap(normalizeRenderer),
              ["vchart", "slides", IOSGuizangHtmlDeckValidator.renderer].contains(renderer) else {
            return nil
        }
        let title = normalizeWidgetTitle(extractJsonStringValue(jsonText, key: "title", allowUnclosed: true))
        if renderer == "slides" {
            let partialSlides = extractPartialSlides(jsonText)
            if !partialSlides.isEmpty {
                let count = partialSlides.count
                let countLabel = count >= 24 ? "24+ 页" : "\(count) 页"
                let height = 80 + count * 30
                var svg = """
                <svg width="100%" viewBox="0 0 680 \(height)" xmlns="http://www.w3.org/2000/svg">
                <rect width="100%" height="100%" fill="#f0fdf4" rx="10" stroke="#bbf7d0"/>
                <text x="340" y="36" text-anchor="middle" font-size="15" fill="#166534">生成幻灯片: \(countLabel)</text>
                """
                for (index, slide) in partialSlides.prefix(5).enumerated() {
                    let slideTitle = slide.string("title") ?? "第\(index + 1)页"
                    svg += "\n<text x=\"48\" y=\"\(72 + index * 24)\" font-size=\"13\" fill=\"#065f46\">\(index + 1). \(escapeXML(slideTitle))</text>"
                }
                if count > 5 {
                    svg += "\n<text x=\"340\" y=\"\(72 + 5 * 24 + 12)\" text-anchor=\"middle\" font-size=\"12\" fill=\"#86efac\">还有 \(count - 5) 页...</text>"
                }
                svg += "\n</svg>"
                return IOSGenerativeWidget(
                    id: "\(idSeed)-slides",
                    title: title,
                    widgetCode: svg,
                    complete: false,
                    renderer: renderer
                )
            }
        }
        let label = renderer == "vchart" ? "图表" : "演示"
        let titleText = escapeXML(title ?? "正在生成")
        let placeholder = """
        <svg width="100%" viewBox="0 0 680 100" xmlns="http://www.w3.org/2000/svg"><rect width="100%" height="100%" fill="#f0f9ff" rx="10" stroke="#bae6fd"/><text x="340" y="42" text-anchor="middle" font-size="15" fill="#0369a1">生成\(label)...</text><text x="340" y="68" text-anchor="middle" font-size="13" fill="#7dd3fc">\(titleText)</text></svg>
        """
        return IOSGenerativeWidget(
            id: "\(idSeed)-\(renderer)",
            title: title,
            widgetCode: placeholder,
            complete: false,
            renderer: renderer
        )
    }

    private static func normalizeRenderer(_ rendererRaw: String?) -> String? {
        guard let lower = rendererRaw?.lowercased() else { return nil }
        if IOSGuizangHtmlDeckValidator.isRenderer(lower) { return IOSGuizangHtmlDeckValidator.renderer }
        if ["html", "chart", "diagram", "vchart", "slides"].contains(lower) { return lower }
        return nil
    }

    private static func parseStandaloneFullHtmlWidget(_ content: String, idSeed: String) -> IOSGenerativeWidget? {
        let body = content.trimmingCharacters(in: .whitespacesAndNewlines).singleFenceBodyOrSelf()
        if body.isEmpty { return nil }
        if body.hasPrefix("{"), let range = jsonObjectRange(in: body, start: body.startIndex) {
            let json = String(body[range])
            if let widget = parseWidgetJson(json, idSeed: idSeed, renderer: IOSGenerativeWidgetRenderer()),
               widget.renderer == IOSGuizangHtmlDeckValidator.renderer {
                return widget
            }
            return nil
        }
        if looksLikeStandaloneHtml(body), IOSGuizangHtmlDeckValidator.validateHtml(body).valid {
            return buildFullHtmlWidget(
                parsed: nil,
                title: nil,
                html: body,
                coverHtml: nil,
                complete: true,
                idSeed: idSeed
            )
        }
        return nil
    }

    private static func recoverFullHtmlWidget(
        parsed: [String: Any]?,
        jsonText: String,
        idSeed: String
    ) -> IOSGenerativeWidget? {
        guard let parsed else { return nil }
        let title = parsed.string("title") ?? extractJsonStringValue(jsonText, key: "title", allowUnclosed: false)
        let rawWidgetCode = parsed.string("widget_code") ?? extractJsonStringValue(jsonText, key: "widget_code", allowUnclosed: false)
        guard let html = fullHtmlCandidates(parsed: parsed, jsonText: jsonText, rawWidgetCode: rawWidgetCode)
            .first(where: { IOSGuizangHtmlDeckValidator.validateHtml($0).valid }) else {
            return nil
        }
        let cover = rawWidgetCode.flatMap { isStaticWidgetCover($0) ? $0 : nil }
        return buildFullHtmlWidget(
            parsed: parsed,
            title: title,
            html: html,
            coverHtml: cover,
            complete: true,
            idSeed: idSeed
        )
    }

    private static func fullHtmlCandidates(parsed: [String: Any], jsonText: String, rawWidgetCode: String?) -> [String] {
        var candidates: [String] = []
        if let spec = parsed["spec"] as? [String: Any], let html = spec.string("html") {
            candidates.append(html)
        }
        if let html = parsed.string("html") {
            candidates.append(html)
        }
        if let html = extractJsonStringValue(jsonText, key: "html", allowUnclosed: false) {
            candidates.append(html)
        }
        if let rawWidgetCode, looksLikeStandaloneHtml(rawWidgetCode) {
            candidates.append(rawWidgetCode)
        }
        var seen = Set<String>()
        return candidates
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private static func buildFullHtmlWidget(
        parsed: [String: Any]?,
        title: String?,
        html: String,
        coverHtml: String?,
        complete: Bool,
        idSeed: String
    ) -> IOSGenerativeWidget {
        let normalizedTitle = normalizeWidgetTitle(title)
        let specSource = (parsed?["spec"] as? [String: Any])?.string("source")
            ?? parsed?.string("source")
            ?? "full-html-adapter"
        let allowRemoteImages = (parsed?["spec"] as? [String: Any])?.bool("allowRemoteImages")
            ?? parsed?.bool("allowRemoteImages")
            ?? true
        let allowRemoteFonts = (parsed?["spec"] as? [String: Any])?.bool("allowRemoteFonts")
            ?? parsed?.bool("allowRemoteFonts")
            ?? true
        var spec: [String: Any] = [
            "html": html,
            "source": specSource,
            "allowRemoteImages": allowRemoteImages,
            "allowRemoteFonts": allowRemoteFonts,
        ]
        if let normalizedTitle { spec["title"] = normalizedTitle }
        let specJson = jsonString(spec)
        let preview = coverHtml ?? IOSGenerativeWidgetRenderer().render(
            renderer: IOSGuizangHtmlDeckValidator.renderer,
            specJson: specJson
        ) ?? ""
        return IOSGenerativeWidget(
            id: "\(idSeed)-full-html-\(stableID(html))",
            title: normalizedTitle,
            widgetCode: preview,
            complete: complete,
            renderer: IOSGuizangHtmlDeckValidator.renderer,
            actions: parseActions(parsed),
            specJson: specJson
        )
    }

    private static func isStaticWidgetCover(_ code: String) -> Bool {
        let compact = code.trimmingCharacters(in: .whitespacesAndNewlines)
        return compact.lowercased().hasPrefix("<svg") &&
            compact.count <= 32_000 &&
            isRenderableWidgetCode(compact, complete: true)
    }

    private static func isRenderableWidgetCode(_ code: String?, complete: Bool) -> Bool {
        let compact = code?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if compact.isEmpty { return false }
        let lower = compact.lowercased()
        if placeholderPhrases.contains(where: { lower.contains($0) }) { return false }
        if ["<svg>", "<div>", "<svg></svg>", "<div></div>"].contains(lower) {
            return !complete
        }
        if !compact.containsMatch(pattern: renderableTagPattern) { return false }
        if complete, compact.filter({ $0 == "<" }).count < 2 { return false }
        return true
    }

    private static func parseActions(_ parsed: [String: Any]?) -> [IOSGenerativeWidgetAction] {
        guard let rows = parsed?["actions"] as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard let label = row.string("label")?.compactSpaces().nilIfBlank,
                  let instruction = row.string("instruction")?.compactSpaces().nilIfBlank,
                  (1...20).contains(label.count),
                  (1...240).contains(instruction.count),
                  isSafeActionInstruction(instruction) else {
                return nil
            }
            let id = sanitizeActionID(row.string("id")?.nilIfBlank ?? label)
            return IOSGenerativeWidgetAction(id: id, label: label, instruction: instruction)
        }
        .prefix(3)
        .map { $0 }
    }

    private static func isSafeActionInstruction(_ value: String) -> Bool {
        let lower = value.lowercased()
        return !blockedActionInstructionSnippets.contains { lower.contains($0) }
    }

    private static func sanitizeActionID(_ value: String) -> String {
        var output = String.UnicodeScalarView()
        for scalar in value.unicodeScalars {
            let scalarValue = scalar.value
            let allowed = (0x30...0x39).contains(scalarValue) ||
                (0x41...0x5a).contains(scalarValue) ||
                (0x61...0x7a).contains(scalarValue) ||
                scalarValue == 0x5f ||
                scalarValue == 0x2d ||
                (0x4e00...0x9fa5).contains(scalarValue)
            output.append(allowed ? scalar : "-")
            if output.count >= 48 { break }
        }
        let compact = String(output)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
            .nilIfBlank ?? "action"
        return String(compact.prefix(48))
    }

    private static func normalizeWidgetTitle(_ title: String?) -> String? {
        guard let compact = title?.compactSpaces().nilIfBlank else { return nil }
        if placeholderPhrases.contains(where: { compact.localizedCaseInsensitiveContains($0) }) {
            return nil
        }
        return compact
    }

    private static func looksLikeFullHtmlWidgetFragment(_ widgetFragment: String) -> Bool {
        let lower = String(widgetFragment.prefix(4_096)).lowercased()
        let namesFullHtmlRenderer = lower.contains("\"renderer\"") &&
            (lower.contains("\"\(IOSGuizangHtmlDeckValidator.renderer)\"") || lower.contains("\"guizang_html\""))
        let carriesDeckHtml = lower.contains("\"spec\"") && lower.contains("\"html\"")
        let carriesLegacyDeckHtml = lower.contains("\"widget_code\"") && looksLikeDeckHtmlFragment(lower)
        return namesFullHtmlRenderer || carriesDeckHtml || carriesLegacyDeckHtml
    }

    private static func looksLikeDeckHtmlFragment(_ lower: String) -> Bool {
        lower.contains("<!doctype html") ||
            lower.contains("<html") ||
            lower.contains("<div id=\\\"deck\\\"") ||
            lower.contains("<div id='deck'") ||
            lower.contains("class=\\\"slides\\\"") ||
            lower.contains("class='slides'") ||
            lower.contains("class=\\\"deck\\\"") ||
            lower.contains("class='deck'")
    }

    private static func looksLikeStandaloneHtml(_ value: String) -> Bool {
        let lower = String(value.prefix(2_000)).lowercased()
        return lower.contains("<!doctype html") ||
            lower.contains("<html") ||
            lower.contains("<div id=\"deck\"") ||
            lower.contains("<div id='deck'") ||
            lower.contains("class=\"slides\"") ||
            lower.contains("class='slides'") ||
            lower.contains("class=\"deck\"") ||
            lower.contains("class='deck'") ||
            lower.contains("id=\"card-set\"") ||
            lower.contains("class=\"poster")
    }

    private static func extractPartialSlides(_ jsonText: String) -> [[String: Any]] {
        let possibleKeys = ["\"spec\"", "\"slides\"", "\"pages\""]
        guard let specStart = possibleKeys
            .compactMap({ key in jsonText.range(of: key).map { ($0.lowerBound, key) } })
            .sorted(by: { jsonText.distance(from: jsonText.startIndex, to: $0.0) < jsonText.distance(from: jsonText.startIndex, to: $1.0) })
            .first?.0,
              let arrayStart = firstIndex(of: "[", in: jsonText, from: specStart) else {
            return []
        }
        var slides: [[String: Any]] = []
        var objectStart: String.Index?
        var depth = 0
        var arrayDepth = 0
        var inString = false
        var escaped = false
        var index = arrayStart
        while index < jsonText.endIndex {
            let char = jsonText[index]
            defer { index = jsonText.index(after: index) }
            if escaped {
                escaped = false
                continue
            }
            if char == "\\", inString {
                escaped = true
                continue
            }
            if char == "\"" {
                inString.toggle()
                continue
            }
            if inString { continue }
            switch char {
            case "[":
                arrayDepth += 1
            case "]":
                arrayDepth -= 1
                if arrayDepth == 0 { return slides }
            case "{":
                depth += 1
                if arrayDepth == 1, depth == 1, objectStart == nil {
                    objectStart = index
                }
            case "}":
                depth -= 1
                if arrayDepth == 1, depth == 0, let start = objectStart {
                    let end = jsonText.index(after: index)
                    let candidate = String(jsonText[start..<end])
                    if let obj = jsonObject(candidate) {
                        slides.append(obj)
                    }
                    objectStart = nil
                    if slides.count >= 24 { return slides }
                }
            default:
                break
            }
        }
        return slides
    }

    private struct MarkerMatch {
        let range: Range<String.Index>
    }

    private static func findMarker(in text: String, from start: String.Index) -> MarkerMatch? {
        var lineStart = start
        while lineStart < text.endIndex {
            let nextLineStart: String.Index
            let lineEnd: String.Index
            if let newline = text[lineStart...].firstIndex(of: "\n") {
                lineEnd = newline
                nextLineStart = text.index(after: newline)
            } else {
                return nil
            }
            let line = text[lineStart..<lineEnd].trimmingCharacters(in: CharacterSet(charactersIn: " \t\r"))
            if line.hasPrefix("```") {
                let markerText = line.drop { $0 == "`" }
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .split(whereSeparator: { $0 == " " || $0 == "\t" })
                    .first
                    .map(String.init) ?? ""
                if markers.contains(markerText) {
                    return MarkerMatch(range: lineStart..<nextLineStart)
                }
            }
            lineStart = nextLineStart
        }
        return nil
    }

    private static func hasPartialWidgetFenceAtEnd(_ text: String) -> Bool {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let lastLine = normalized.split(separator: "\n", omittingEmptySubsequences: false).last else {
            return false
        }
        let trimmed = lastLine.trimmingCharacters(in: CharacterSet(charactersIn: " \t\r"))
        guard trimmed.hasPrefix("```") else { return false }
        let marker = trimmed.drop { $0 == "`" }
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0 == " " || $0 == "\t" })
            .first
            .map(String.init) ?? ""
        return markers.contains(marker)
    }

    private static func partialFenceMatch(in text: String) -> Range<String.Index>? {
        var lineStart = text.startIndex
        var last: Range<String.Index>?
        while lineStart < text.endIndex {
            let lineEnd = text[lineStart...].firstIndex(of: "\n") ?? text.endIndex
            let next = lineEnd < text.endIndex ? text.index(after: lineEnd) : text.endIndex
            let line = text[lineStart..<lineEnd].trimmingCharacters(in: CharacterSet(charactersIn: " \t\r"))
            if line.hasPrefix("```") {
                let marker = line.drop { $0 == "`" }
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .split(whereSeparator: { $0 == " " || $0 == "\t" })
                    .first
                    .map(String.init) ?? ""
                if markers.contains(marker) {
                    last = lineStart..<lineEnd
                }
            }
            lineStart = next
        }
        return last
    }

    private static func firstIndex(of needle: Character, in text: String, from start: String.Index) -> String.Index? {
        var index = start
        while index < text.endIndex {
            if text[index] == needle { return index }
            index = text.index(after: index)
        }
        return nil
    }

    static func jsonObjectRange(in text: String, start: String.Index) -> Range<String.Index>? {
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while index < text.endIndex {
            let char = text[index]
            if escaped {
                escaped = false
                index = text.index(after: index)
                continue
            }
            if char == "\\", inString {
                escaped = true
                index = text.index(after: index)
                continue
            }
            if char == "\"" {
                inString.toggle()
                index = text.index(after: index)
                continue
            }
            if !inString {
                if char == "{" {
                    depth += 1
                } else if char == "}" {
                    depth -= 1
                    if depth == 0 {
                        return start..<text.index(after: index)
                    }
                }
            }
            index = text.index(after: index)
        }
        return nil
    }

    private static func skipTrailingFence(in text: String, from start: String.Index) -> String.Index {
        var cursor = start
        while cursor < text.endIndex, text[cursor].isWhitespace {
            cursor = text.index(after: cursor)
        }
        guard text[cursor...].hasPrefix("```") else { return start }
        return text[cursor...].firstIndex(of: "\n").map { text.index(after: $0) } ?? text.endIndex
    }

    static func extractJsonStringValue(_ source: String, key: String, allowUnclosed: Bool) -> String? {
        let needle = "\"\(key)\""
        guard let keyRange = source.range(of: needle) else { return nil }
        var cursor = keyRange.upperBound
        while cursor < source.endIndex, source[cursor].isWhitespace { cursor = source.index(after: cursor) }
        guard cursor < source.endIndex, source[cursor] == ":" else { return nil }
        cursor = source.index(after: cursor)
        while cursor < source.endIndex, source[cursor].isWhitespace { cursor = source.index(after: cursor) }
        guard cursor < source.endIndex, source[cursor] == "\"" else { return nil }
        cursor = source.index(after: cursor)

        var output = ""
        var escaped = false
        while cursor < source.endIndex {
            let char = source[cursor]
            cursor = source.index(after: cursor)
            if escaped {
                switch char {
                case "\"", "\\", "/":
                    output.append(char)
                case "b":
                    output.append("\u{0008}")
                case "f":
                    output.append("\u{000C}")
                case "n":
                    output.append("\n")
                case "r":
                    output.append("\r")
                case "t":
                    output.append("\t")
                case "u":
                    let hexStart = cursor
                    var hexEnd = cursor
                    var digits = ""
                    for _ in 0..<4 where hexEnd < source.endIndex {
                        digits.append(source[hexEnd])
                        hexEnd = source.index(after: hexEnd)
                    }
                    if digits.count == 4, let scalar = UInt32(digits, radix: 16).flatMap(UnicodeScalar.init) {
                        output.unicodeScalars.append(scalar)
                        cursor = hexEnd
                    } else {
                        output.append("u")
                        cursor = hexStart
                    }
                default:
                    output.append(char)
                }
                escaped = false
                continue
            }
            if char == "\\" {
                escaped = true
                continue
            }
            if char == "\"" {
                return output
            }
            output.append(char)
        }
        return allowUnclosed ? output : nil
    }
}

enum IOSVChartSpecValidator {
    private static let maxSpecBytes = 51_200
    private static let maxArrayElements = 1_000
    private static let maxStringLength = 500
    private static let maxSlideStringLength = 800
    private static let maxDepth = 12
    private static let supportedSlideLayouts: Set<String> = [
        "cover", "section", "quote", "split", "metrics", "timeline",
        "cards", "image-grid", "comparison", "closing",
    ]
    private static let blockedPatterns = [
        "javascript:", "<script", "</script", "eval(", "Function(",
        "new Function", "__proto__", "constructor[", "prototype.",
        "setTimeout(", "setInterval(", "atob(", "btoa(", "srcdoc",
        "onerror=", "onload=", "data:text/html",
    ]

    struct ValidationResult: Equatable {
        let valid: Bool
        var reason: String?
    }

    static func validateChartSpec(_ specJson: String) -> ValidationResult {
        if specJson.utf8.count > maxSpecBytes {
            return ValidationResult(valid: false, reason: "spec too large: \(specJson.utf8.count) bytes")
        }
        guard let obj = jsonValue(specJson) as? [String: Any] else {
            return ValidationResult(valid: false, reason: "expected JSON object")
        }
        return validateElement(obj, depth: 0, maxStringLength: maxStringLength)
    }

    static func validateSlidesSpec(_ slidesJson: String) -> ValidationResult {
        if slidesJson.utf8.count > maxSpecBytes {
            return ValidationResult(valid: false, reason: "slides too large: \(slidesJson.utf8.count) bytes")
        }
        guard let normalized = normalizeSlidesDeckSpecJson(slidesJson),
              let deck = jsonValue(normalized) as? [String: Any],
              let slides = deck["slides"] as? [Any] else {
            return ValidationResult(valid: false, reason: "expected slides JSON")
        }
        if slides.isEmpty { return ValidationResult(valid: false, reason: "empty slides") }
        if slides.count > 24 { return ValidationResult(valid: false, reason: "too many slides: \(slides.count)") }
        let deckResult = validateElement(deck, depth: 0, maxStringLength: maxSlideStringLength)
        if !deckResult.valid { return deckResult }
        for element in slides {
            if let obj = element as? [String: Any],
               let layout = obj.string("layout")?.lowercased(),
               !supportedSlideLayouts.contains(layout) {
                return ValidationResult(valid: false, reason: "unsupported slide layout: \(layout)")
            }
            let result = validateElement(element, depth: 0, maxStringLength: maxSlideStringLength)
            if !result.valid { return result }
        }
        return ValidationResult(valid: true)
    }

    static func normalizeSlidesSpecJson(_ slidesJson: String) -> String? {
        slidesArrayOrNull(slidesJson).flatMap(jsonString)
    }

    static func normalizeSlidesDeckSpecJson(_ slidesJson: String) -> String? {
        guard let parsed = jsonValue(slidesJson),
              let deck = normalizeSlidesDeckSpec(parsed) else {
            return nil
        }
        return jsonString(deck)
    }

    static func slidesArrayOrNull(_ slidesJson: String) -> [Any]? {
        jsonValue(slidesJson).flatMap(findSlidesSource)?.slides
    }

    private static func normalizeSlidesDeckSpec(_ parsed: Any) -> [String: Any]? {
        guard let source = findSlidesSource(parsed) else { return nil }
        let topLevel = parsed as? [String: Any]
        var deck: [String: Any] = [
            "schemaVersion": topLevel?.int("schemaVersion") ?? source.source?.int("schemaVersion") ?? 2,
            "style": topLevel?.string("style") ?? source.source?.string("style") ?? "system",
            "slides": source.slides,
        ]
        if let accent = topLevel?.string("accent") ?? source.source?.string("accent") { deck["accent"] = accent }
        if let fontPack = topLevel?.string("fontPack") ?? source.source?.string("fontPack") { deck["fontPack"] = fontPack }
        if let title = topLevel?.string("title") ?? source.source?.string("title") { deck["title"] = title }
        return deck
    }

    private struct SlidesSource {
        let source: [String: Any]?
        let slides: [Any]
    }

    private static func findSlidesSource(_ element: Any) -> SlidesSource? {
        if let array = element as? [Any] {
            return SlidesSource(source: nil, slides: array)
        }
        guard let object = element as? [String: Any] else { return nil }
        if let slides = object["slides"] as? [Any] { return SlidesSource(source: object, slides: slides) }
        if let pages = object["pages"] as? [Any] { return SlidesSource(source: object, slides: pages) }
        if let specArray = object["spec"] as? [Any] { return SlidesSource(source: object, slides: specArray) }
        if let specObject = object["spec"] as? [String: Any] { return findSlidesSource(specObject) }
        return nil
    }

    private static func validateElement(_ element: Any, depth: Int, maxStringLength: Int) -> ValidationResult {
        if depth > maxDepth { return ValidationResult(valid: false, reason: "nesting too deep") }
        if let string = element as? String {
            return validateStringValue(string, maxStringLength: maxStringLength)
        }
        if let array = element as? [Any] {
            if array.count > maxArrayElements {
                return ValidationResult(valid: false, reason: "array too large: \(array.count)")
            }
            for child in array {
                let result = validateElement(child, depth: depth + 1, maxStringLength: maxStringLength)
                if !result.valid { return result }
            }
            return ValidationResult(valid: true)
        }
        if let object = element as? [String: Any] {
            for (key, child) in object {
                let keyResult = validateObjectKey(key)
                if !keyResult.valid { return keyResult }
                let result = validateElement(child, depth: depth + 1, maxStringLength: maxStringLength)
                if !result.valid { return result }
            }
        }
        return ValidationResult(valid: true)
    }

    private static func validateObjectKey(_ key: String) -> ValidationResult {
        if key.count > maxStringLength {
            return ValidationResult(valid: false, reason: "object key too long: \(key.count)")
        }
        let lower = key.lowercased()
        if ["__proto__", "constructor", "prototype"].contains(lower) {
            return ValidationResult(valid: false, reason: "blocked object key: \(key)")
        }
        for pattern in blockedPatterns where lower.contains(pattern.lowercased()) {
            return ValidationResult(valid: false, reason: "blocked object key pattern: \(pattern)")
        }
        return ValidationResult(valid: true)
    }

    private static func validateStringValue(_ value: String, maxStringLength: Int) -> ValidationResult {
        if value.count > maxStringLength {
            return ValidationResult(valid: false, reason: "string value too long: \(value.count)")
        }
        let lower = value.lowercased()
        for pattern in blockedPatterns where lower.contains(pattern.lowercased()) {
            return ValidationResult(valid: false, reason: "blocked pattern: \(pattern)")
        }
        if value.containsMatch(pattern: #"^\s*function\s*\(|\=\>"#) {
            return ValidationResult(valid: false, reason: "function or arrow syntax in string value")
        }
        if value.containsMatch(pattern: #"\.innerHTML\s*=|\bdocument\.\w+"#) {
            return ValidationResult(valid: false, reason: "DOM access in string value")
        }
        return ValidationResult(valid: true)
    }
}

enum IOSGuizangHtmlDeckValidator {
    static let renderer = "full_html"
    static let legacyRenderer = "guizang_html"
    static let maxHTMLBytes = 1_500_000
    static let localMotionURL = "amber-full-html://runtime/motion.min.js"
    static let localLucideURL = "amber-full-html://runtime/lucide.min.js"
    static let remoteResourceScheme = "amber-full-html-resource"
    static let motionAssetPath = "generative-libs/guizang/motion.min.js"
    static let lucideAssetPath = "generative-libs/guizang/lucide.min.js"

    struct DeckSpec: Equatable {
        let html: String
        var source: String?
        var allowRemoteImages = true
        var allowRemoteFonts = true
    }

    struct ValidationResult: Equatable {
        let valid: Bool
        var reason: String?
    }

    enum RuntimeAsset {
        case motion
        case lucide

        var resourceName: String {
            switch self {
            case .motion: "motion.min"
            case .lucide: "lucide.min"
            }
        }
    }

    enum RemoteResourceKind: String, Sendable {
        case image
        case stylesheet
        case font
    }

    static func isRenderer(_ renderer: String?) -> Bool {
        let lower = renderer?.lowercased()
        return lower == Self.renderer || lower == legacyRenderer
    }

    static func normalizeSpecJson(_ specJson: String) -> DeckSpec? {
        guard let obj = jsonObject(specJson),
              let html = obj.string("html")?.nilIfBlank else {
            return nil
        }
        return DeckSpec(
            html: html,
            source: obj.string("source"),
            allowRemoteImages: obj.bool("allowRemoteImages") ?? true,
            allowRemoteFonts: obj.bool("allowRemoteFonts") ?? true
        )
    }

    static func validateSpecJson(_ specJson: String) -> ValidationResult {
        guard let spec = normalizeSpecJson(specJson) else {
            return ValidationResult(valid: false, reason: "expected spec.html")
        }
        return validateDeck(spec)
    }

    static func validateDeck(_ spec: DeckSpec) -> ValidationResult {
        validateHtml(spec.html)
    }

    static func validateHtml(_ html: String) -> ValidationResult {
        if html.utf8.count > maxHTMLBytes {
            return ValidationResult(valid: false, reason: "html too large: max \(maxHTMLBytes / 1_000)KB")
        }
        let runtimeHtml = prepareRuntimeHtml(html)
        guard hasSlideLikeContent(runtimeHtml) else {
            return ValidationResult(valid: false, reason: "expected at least one slide element: <section class=\"slide ...\">")
        }
        for rule in blockedRules where runtimeHtml.containsMatch(pattern: rule.pattern) {
            return ValidationResult(valid: false, reason: rule.reason)
        }
        return validateExternalScripts(runtimeHtml)
    }

    static func prepareRuntimeHtml(_ html: String) -> String {
        normalizeDeckStructure(rewriteRuntimeUrls(html))
    }

    static func prepareRuntimeHtml(_ spec: DeckSpec) -> String {
        let runtimeHtml = prepareRuntimeHtml(spec.html)
        let resourceScopedHtml = rewriteRemoteResourceUrls(
            runtimeHtml,
            allowRemoteImages: spec.allowRemoteImages,
            allowRemoteFonts: spec.allowRemoteFonts
        )
        return injectResourceContentSecurityPolicy(resourceScopedHtml)
    }

    static func rewriteRuntimeUrls(_ html: String) -> String {
        html
            .replacing(pattern: #"https://unpkg\.com/lucide(?:@[^/"'`\s<>]+)?(?:/dist/umd/lucide(?:\.min)?\.js)?"#, with: localLucideURL)
            .replacing(pattern: #"https://cdn\.jsdelivr\.net/npm/lucide(?:@[^/"'`\s<>]+)?(?:/dist/umd/lucide(?:\.min)?\.js)?"#, with: localLucideURL)
            .replacing(pattern: #"https://cdn\.jsdelivr\.net/npm/motion(?:@[^/"'`\s<>]+)?/\+esm"#, with: localMotionURL)
            .replacing(pattern: #"https://unpkg\.com/motion(?:@[^/"'`\s<>]+)?(?:/\+esm)?"#, with: localMotionURL)
            .replacing(pattern: #"(?<=['"])(?:\./)?assets/motion\.min\.js(?=['"])"#, with: localMotionURL)
            .replacing(pattern: #"(?<=['"])(?:\./)?assets/lucide\.min\.js(?=['"])"#, with: localLucideURL)
    }

    static func rewriteRemoteResourceUrls(
        _ html: String,
        allowRemoteImages: Bool,
        allowRemoteFonts: Bool
    ) -> String {
        let rewrittenAttributes = html.replacingMatches(pattern: #"<[a-zA-Z][^>]*>"#) { match, source in
            guard let range = Range(match.range, in: source) else { return "" }
            return rewriteRemoteResourceAttributes(
                in: String(source[range]),
                allowRemoteImages: allowRemoteImages,
                allowRemoteFonts: allowRemoteFonts
            )
        }
        return rewriteCSSRemoteResourceUrls(
            rewrittenAttributes,
            allowRemoteImages: allowRemoteImages,
            allowRemoteFonts: allowRemoteFonts
        )
    }

    static func runtimeAssetForURL(_ url: String) -> RuntimeAsset? {
        let normalized = rewriteRuntimeUrls(url.trimmingCharacters(in: .whitespacesAndNewlines)).lowercased()
        if normalized == localMotionURL.lowercased() { return .motion }
        if normalized == localLucideURL.lowercased() { return .lucide }
        return nil
    }

    static func isAllowedRemoteImage(_ url: String) -> Bool {
        let lower = url.split(separator: "?", maxSplits: 1).first.map(String.init)?.lowercased() ?? ""
        return lower.hasPrefix("https://") && [".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif"].contains {
            lower.hasSuffix($0)
        }
    }

    static func isAllowedRemoteFontOrStylesheet(_ url: String) -> Bool {
        let lower = url.split(separator: "?", maxSplits: 1).first.map(String.init)?.lowercased() ?? ""
        if lower.hasPrefix("https://fonts.googleapis.com/") || lower.hasPrefix("https://fonts.gstatic.com/") {
            return true
        }
        return lower.hasPrefix("https://") && [".css", ".woff", ".woff2", ".ttf", ".otf"].contains {
            lower.hasSuffix($0)
        }
    }

    static func remoteResourceURL(originalURL: String, kind: RemoteResourceKind) -> String? {
        guard let parsed = URL(string: originalURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              parsed.scheme?.lowercased() == "https",
              !parsed.absoluteString.isEmpty else {
            return nil
        }
        let encoded = Data(parsed.absoluteString.utf8)
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "\(remoteResourceScheme)://\(kind.rawValue)/\(encoded)"
    }

    static func remoteResourceOriginalURL(from url: URL) -> (kind: RemoteResourceKind, originalURL: String)? {
        guard url.scheme?.lowercased() == remoteResourceScheme,
              let host = url.host?.lowercased(),
              let kind = RemoteResourceKind(rawValue: host) else {
            return nil
        }
        let encoded = url.pathComponents.dropFirst().joined()
        guard !encoded.isEmpty else { return nil }
        var base64 = encoded
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = (4 - base64.count % 4) % 4
        if padding > 0 {
            base64 += String(repeating: "=", count: padding)
        }
        guard let data = Data(base64Encoded: base64),
              let originalURL = String(data: data, encoding: .utf8),
              URL(string: originalURL)?.scheme?.lowercased() == "https" else {
            return nil
        }
        return (kind, originalURL)
    }

    static func isAllowedRemoteResource(kind: RemoteResourceKind, url: String) -> Bool {
        switch kind {
        case .image:
            isAllowedRemoteImage(url)
        case .stylesheet, .font:
            isAllowedRemoteFontOrStylesheet(url)
        }
    }

    private struct BlockedRule {
        let pattern: String
        let reason: String
    }

    private static let blockedRules: [BlockedRule] = [
        BlockedRule(pattern: #"file://"#, reason: "file:// is not allowed"),
        BlockedRule(pattern: #"content://"#, reason: "content:// is not allowed"),
        BlockedRule(pattern: #"intent://"#, reason: "intent:// is not allowed"),
        BlockedRule(pattern: #"\baddJavascriptInterface\b"#, reason: "native bridge access is not allowed"),
        BlockedRule(pattern: #"\bAmberWidget\b"#, reason: "AmberWidget bridge access is not allowed"),
        BlockedRule(pattern: #"\bwindow\.open\s*\("#, reason: "popups are not allowed"),
        BlockedRule(pattern: #"\btarget\s*=\s*(['"])_blank\1"#, reason: "new windows are not allowed"),
        BlockedRule(pattern: #"<a\b[^>]*\bdownload(?:\s|=|>)"#, reason: "downloads are not allowed"),
        BlockedRule(pattern: #"<iframe\b|<object\b|<embed\b"#, reason: "embedded frames are not allowed"),
        BlockedRule(pattern: #"<form\b|<input\b[^>]*\btype\s*=\s*(['"])file\1"#, reason: "form/file input is not allowed"),
        BlockedRule(pattern: #"\bfetch\s*\("#, reason: "network fetch is not allowed"),
        BlockedRule(pattern: #"\bXMLHttpRequest\b"#, reason: "XMLHttpRequest is not allowed"),
        BlockedRule(pattern: #"\bWebSocket\s*\("#, reason: "WebSocket is not allowed"),
        BlockedRule(pattern: #"\bEventSource\s*\("#, reason: "EventSource is not allowed"),
        BlockedRule(pattern: #"\bnavigator\.serviceWorker\b"#, reason: "service workers are not allowed"),
        BlockedRule(pattern: #"\bnavigator\.geolocation\b"#, reason: "geolocation is not allowed"),
        BlockedRule(pattern: #"\bgetUserMedia\s*\("#, reason: "camera/microphone access is not allowed"),
        BlockedRule(pattern: #"\bNotification\.requestPermission\b"#, reason: "notifications are not allowed"),
        BlockedRule(pattern: #"\bmodulepreload\b"#, reason: "module preloads are not allowed"),
    ]

    private static func hasSlideLikeContent(_ html: String) -> Bool {
        html.containsMatch(pattern: #"<(?:section|article|div)\b(?=[^>]*(?:\bclass\s*=\s*(?:"[^"]*\bslide\b[^"]*"|'[^']*\bslide\b[^']*'|[^\s>]*\bslide\b[^\s>]*)|\bdata-slide(?:\s|=|>)))[^>]*>"#)
    }

    private static func rewriteRemoteResourceAttributes(
        in tag: String,
        allowRemoteImages: Bool,
        allowRemoteFonts: Bool
    ) -> String {
        guard let tagName = openTagName(tag) else { return tag }
        return tag.replacingMatches(pattern: #"\s+(src|href|xlink:href)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'=<>`]+))"#) { match, source in
            guard let wholeRange = Range(match.range, in: source),
                  let attrRange = Range(match.range(at: 1), in: source) else {
                return ""
            }
            let original = String(source[wholeRange])
            let attribute = String(source[attrRange]).lowercased()
            let rawValue = (3...5).compactMap { index -> String? in
                guard let valueRange = Range(match.range(at: index), in: source), !valueRange.isEmpty else {
                    return nil
                }
                return String(source[valueRange])
            }.first ?? ""
            guard rawValue.lowercased().hasPrefix("https://") else {
                return original
            }
            guard let kind = remoteResourceKind(
                tagName: tagName,
                attribute: attribute,
                url: rawValue,
                allowRemoteImages: allowRemoteImages,
                allowRemoteFonts: allowRemoteFonts
            ),
                  let rewrittenURL = remoteResourceURL(originalURL: rawValue, kind: kind) else {
                return " data-amber-blocked-\(attribute.replacingOccurrences(of: ":", with: "-"))=\"\""
            }
            return " \(attribute)=\"\(escapeXML(rewrittenURL))\""
        }
    }

    private static func rewriteCSSRemoteResourceUrls(
        _ html: String,
        allowRemoteImages: Bool,
        allowRemoteFonts: Bool
    ) -> String {
        html.replacingMatches(pattern: #"url\(\s*(['"]?)(https://[^'"()\s]+)\1\s*\)"#) { match, source in
            guard let urlRange = Range(match.range(at: 2), in: source) else {
                return #"url("")"#
            }
            let url = String(source[urlRange])
            let kind: RemoteResourceKind?
            if allowRemoteImages, isAllowedRemoteImage(url) {
                kind = .image
            } else if allowRemoteFonts, isAllowedRemoteFontOrStylesheet(url) {
                kind = .font
            } else {
                kind = nil
            }
            guard let kind, let rewrittenURL = remoteResourceURL(originalURL: url, kind: kind) else {
                return #"url("")"#
            }
            return #"url("\#(rewrittenURL)")"#
        }
    }

    private static func remoteResourceKind(
        tagName: String,
        attribute: String,
        url: String,
        allowRemoteImages: Bool,
        allowRemoteFonts: Bool
    ) -> RemoteResourceKind? {
        if allowRemoteImages,
           ["img", "image", "source"].contains(tagName),
           ["src", "href", "xlink:href"].contains(attribute),
           isAllowedRemoteImage(url) {
            return .image
        }
        if allowRemoteFonts,
           tagName == "link",
           attribute == "href",
           isAllowedRemoteFontOrStylesheet(url) {
            return .stylesheet
        }
        return nil
    }

    private static func openTagName(_ tag: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: #"^<\s*([a-zA-Z][a-zA-Z0-9:-]*)"#, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)),
              let range = Range(match.range(at: 1), in: tag) else {
            return nil
        }
        return String(tag[range]).lowercased()
    }

    private static func injectResourceContentSecurityPolicy(_ html: String) -> String {
        let contentSecurityPolicy = """
        default-src 'none'; script-src 'unsafe-inline' amber-full-html:; style-src 'unsafe-inline' \(remoteResourceScheme):; img-src data: blob: \(remoteResourceScheme):; font-src data: \(remoteResourceScheme):; connect-src 'none'; media-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'
        """
        let meta = #"<meta http-equiv="Content-Security-Policy" content="\#(escapeXML(contentSecurityPolicy.compactSpaces()))">"#
        let stripped = html.replacing(
            pattern: #"<meta\b[^>]*http-equiv\s*=\s*(?:"Content-Security-Policy"|'Content-Security-Policy'|Content-Security-Policy)[^>]*>"#,
            with: ""
        )
        guard let regex = try? NSRegularExpression(pattern: #"<head\b[^>]*>"#, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: stripped, range: NSRange(stripped.startIndex..., in: stripped)),
              let range = Range(match.range, in: stripped) else {
            return "<head>\(meta)</head>\n" + stripped
        }
        return stripped.replacingCharacters(in: range.upperBound..<range.upperBound, with: "\n\(meta)")
    }

    private static func normalizeDeckStructure(_ html: String) -> String {
        var output = html
        output = output.replacingMatches(pattern: #"<(div|main)\b(?=[^>]*(?:\bid\s*=\s*(?:"deck"|'deck')|\bclass\s*=\s*(?:"[^"]*\b(?:deck|slides)\b[^"]*"|'[^']*\b(?:deck|slides)\b[^']*'|[^\s>]*\b(?:deck|slides)\b[^\s>]*)))[^>]*>"#) { match, source in
            guard let range = Range(match.range, in: source) else { return "" }
            var tag = String(source[range])
            if !tag.containsMatch(pattern: #"\bid\s*="#) {
                tag = appendAttribute("id=\"deck\"", to: tag)
            }
            return tag
        }
        output = output.replacingMatches(pattern: #"<(div|main)\b(?=[^>]*(?:\bid\s*=\s*(?:"card-set"|'card-set')|\bclass\s*=\s*(?:"[^"]*\b(?:card-set|poster-set|social-card-set)\b[^"]*"|'[^']*\b(?:card-set|poster-set|social-card-set)\b[^']*'|[^\s>]*\b(?:card-set|poster-set|social-card-set)\b[^\s>]*)))[^>]*>"#) { match, source in
            guard let range = Range(match.range, in: source) else { return "" }
            var tag = String(source[range])
            if !tag.containsMatch(pattern: #"\bdata-guizang-deck(?:\s|=|>)"#) {
                tag = appendAttribute("data-guizang-deck", to: tag)
            }
            return tag
        }
        output = output.replacingMatches(pattern: #"<(section|article)\b(?=[^>]*\bclass\s*=\s*(?:"[^"]*\b(?:poster|xhs|wide|square|social-card)\b[^"]*"|'[^']*\b(?:poster|xhs|wide|square|social-card)\b[^']*'|[^\s>]*\b(?:poster|xhs|wide|square|social-card)\b[^\s>]*))[^>]*>"#) { match, source in
            guard let range = Range(match.range, in: source) else { return "" }
            return addClassTokens(["slide", "social-card"], to: String(source[range]))
        }
        if output.containsMatch(pattern: #"<(?:div|main)\b(?=[^>]*(?:\bid\s*=\s*(?:"deck"|'deck')|\bclass\s*=\s*(?:"[^"]*\b(?:deck|slides)\b[^"]*"|'[^']*\b(?:deck|slides)\b[^']*'|[^\s>]*\b(?:deck|slides)\b[^\s>]*)))[^>]*>"#) {
            output = output.replacingMatches(pattern: #"<(section|article)\b[^>]*>"#) { match, source in
                guard let range = Range(match.range, in: source) else { return "" }
                return addClassTokens(["slide"], to: String(source[range]))
            }
        }
        return output
    }

    private static func validateExternalScripts(_ html: String) -> ValidationResult {
        let scriptPattern = #"<script\b[^>]*\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))"#
        if let regex = try? NSRegularExpression(pattern: scriptPattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) {
            for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
                let url = (1...3).compactMap { idx -> String? in
                    guard let range = Range(match.range(at: idx), in: html), !range.isEmpty else { return nil }
                    return String(html[range])
                }.first ?? ""
                if runtimeAssetForURL(url) == nil {
                    return ValidationResult(valid: false, reason: "external script is not an allowed full_html runtime asset: \(String(url.prefix(80)))")
                }
            }
        }
        for pattern in [#"\bimport\s*\(\s*(['"])(.*?)\1\s*\)"#, #"\bfrom\s*(['"])(.*?)\1"#] {
            if let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) {
                for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
                    guard let range = Range(match.range(at: 2), in: html) else { continue }
                    let url = String(html[range])
                    if looksLikeExternalScriptReference(url), runtimeAssetForURL(url) == nil {
                        return ValidationResult(valid: false, reason: "module import is not an allowed full_html runtime asset: \(String(url.prefix(80)))")
                    }
                }
            }
        }
        return ValidationResult(valid: true)
    }

    private static func looksLikeExternalScriptReference(_ value: String) -> Bool {
        let lower = value.lowercased()
        return lower.hasPrefix("http://") ||
            lower.hasPrefix("https://") ||
            lower.hasSuffix(".js") ||
            lower.hasSuffix(".mjs") ||
            lower.contains("/+esm")
    }

    private static func appendAttribute(_ attribute: String, to openTag: String) -> String {
        guard let end = openTag.lastIndex(of: ">") else { return openTag }
        let insertAt = openTag.index(before: end) >= openTag.startIndex && openTag[openTag.index(before: end)] == "/" ? openTag.index(before: end) : end
        return String(openTag[..<insertAt]) + " " + attribute + String(openTag[insertAt...])
    }

    private static func addClassTokens(_ tokens: [String], to openTag: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: #"\bclass\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))"#, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: openTag, range: NSRange(openTag.startIndex..., in: openTag)) else {
            return appendAttribute("class=\"\(tokens.joined(separator: " "))\"", to: openTag)
        }
        let existing = (1...3).compactMap { idx -> String? in
            guard let range = Range(match.range(at: idx), in: openTag), !range.isEmpty else { return nil }
            return String(openTag[range])
        }.first ?? ""
        var parts = existing.split(whereSeparator: \.isWhitespace).map(String.init)
        for token in tokens where !parts.contains(where: { $0.caseInsensitiveCompare(token) == .orderedSame }) {
            parts.append(token)
        }
        guard let classRange = Range(match.range, in: openTag) else { return openTag }
        return openTag.replacingCharacters(in: classRange, with: "class=\"\(parts.joined(separator: " "))\"")
    }
}

func jsonValue(_ json: String) -> Any? {
    guard let data = json.data(using: .utf8) else { return nil }
    return try? JSONSerialization.jsonObject(with: data)
}

func jsonObject(_ json: String) -> [String: Any]? {
    jsonValue(json) as? [String: Any]
}

func jsonString(_ value: Any) -> String? {
    guard JSONSerialization.isValidJSONObject(value),
          let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else {
        return nil
    }
    return String(data: data, encoding: .utf8)
}

func stableID(_ value: String) -> String {
    var hash: UInt64 = 0xcbf29ce484222325
    for byte in value.utf8 {
        hash ^= UInt64(byte)
        hash &*= 0x100000001b3
    }
    return String(hash, radix: 16)
}

func escapeXML(_ value: String) -> String {
    value
        .replacingOccurrences(of: "&", with: "&amp;")
        .replacingOccurrences(of: "<", with: "&lt;")
        .replacingOccurrences(of: ">", with: "&gt;")
        .replacingOccurrences(of: "\"", with: "&quot;")
        .replacingOccurrences(of: "'", with: "&#39;")
}

extension String {
    func compactSpaces() -> String {
        split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    func singleFenceBodyOrSelf() -> String {
        guard hasPrefix("```") else { return self }
        guard let firstLineEnd = firstIndex(of: "\n") else {
            return String(dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let body = self[index(after: firstLineEnd)...]
        if let endFence = body.range(of: "```", options: .backwards) {
            return String(body[..<endFence.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return body.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? {
        if let value = self[key] as? String {
            return value.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let value = self[key], !(value is NSNull) {
            return "\(value)".trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return nil
    }

    func bool(_ key: String) -> Bool? {
        if let value = self[key] as? Bool { return value }
        if let number = self[key] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() {
            return number.boolValue
        }
        return nil
    }

    func int(_ key: String) -> Int? {
        if let value = self[key] as? Int { return value }
        if let number = self[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() {
            return number.intValue
        }
        return nil
    }
}
