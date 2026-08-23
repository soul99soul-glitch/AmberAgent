import Foundation
@preconcurrency import Shared

enum IOSGenerativeWidgetSanitizeStatus: Equatable {
    case ready
    case empty
    case tooLarge
    case unsafe
}

struct IOSSanitizedGenerativeWidget: Equatable {
    let status: IOSGenerativeWidgetSanitizeStatus
    var html: String = ""
    var reason: String?
}

struct IOSGenerativeWidgetSettings: Equatable {
    var enabled = true
    var maxWidgetCodeChars = 12_000
    var maxWidgetHeight = 720
    var enableActions = true
    var enableStructuredRenderers = true
    var enableInteractiveCharts = true

    init() {}

    init(_ shared: GenerativeUiSetting?) {
        guard let shared else { return }
        enabled = shared.enabled
        maxWidgetCodeChars = Int(shared.maxWidgetCodeChars)
        maxWidgetHeight = Int(shared.maxWidgetHeightDp)
        enableActions = shared.enableActions
        enableStructuredRenderers = shared.enableStructuredRenderers
        enableInteractiveCharts = shared.enableInteractiveCharts
    }
}

enum IOSGenerativeWidgetSanitizer {
    private static let maxTagCount = 420
    private static let maxTextChars = 10_000

    static func sanitize(
        _ code: String,
        setting: IOSGenerativeWidgetSettings = IOSGenerativeWidgetSettings()
    ) -> IOSSanitizedGenerativeWidget {
        if code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return IOSSanitizedGenerativeWidget(status: .empty)
        }
        if code.count > max(setting.maxWidgetCodeChars, 1_000) {
            return IOSSanitizedGenerativeWidget(status: .tooLarge, reason: "code length")
        }

        var sanitized = code
        sanitized = sanitized.replacing(pattern: #"<script\b[\s\S]*?</script\s*>"#, with: "")
        sanitized = sanitized.replacing(pattern: #"<script\b[^>]*>"#, with: "")
        sanitized = sanitized.replacing(pattern: #"<\s*(iframe|object|embed|form|foreignObject)\b[\s\S]*?</\s*\1\s*>"#, with: "")
        sanitized = sanitized.replacing(pattern: #"<\s*(iframe|object|embed|meta|link|base|foreignObject)\b[^>]*\/?>"#, with: "")
        sanitized = sanitized.replacing(pattern: #"\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>"']*)"#, with: "")
        sanitized = sanitized.replacing(pattern: #"@font-face\s*\{[\s\S]*?\}"#, with: "")
        sanitized = sanitized.replacing(pattern: #"@import\s+[^;]+;?"#, with: "")
        sanitized = sanitized.replacing(pattern: #"url\(\s*(['"]?)\s*javascript:[^)]+\)"#, with: "none")
        sanitized = sanitized.replacing(pattern: #"url\(\s*(['"]?)\s*(https?:|//)[^)]+\)"#, with: "none")
        sanitized = sanitized.replacing(pattern: #"url\(\s*(['"]?)(?!data:image/|#)[^)]+\)"#, with: "none")
        sanitized = sanitized.replacing(pattern: #"position\s*:\s*(fixed|-webkit-sticky|sticky)\s*;?"#, with: "position:relative;")
        sanitized = stripUnsafeURLAttributes(sanitized)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if sanitized.matchCount(pattern: #"<\s*[a-zA-Z][^>]*>"#) > maxTagCount {
            return IOSSanitizedGenerativeWidget(status: .tooLarge, reason: "tag count")
        }
        if sanitized.replacing(pattern: #"<[^>]+>"#, with: "").count > maxTextChars {
            return IOSSanitizedGenerativeWidget(status: .tooLarge, reason: "text length")
        }
        if let reason = safetyViolation(sanitized) {
            return IOSSanitizedGenerativeWidget(status: .unsafe, reason: reason)
        }
        if sanitized.isEmpty {
            return IOSSanitizedGenerativeWidget(status: .empty)
        }
        return IOSSanitizedGenerativeWidget(status: .ready, html: sanitized)
    }

    private static func stripUnsafeURLAttributes(_ html: String) -> String {
        html.replacingMatches(pattern: #"\s+(href|xlink:href|src|srcset|poster|background|action|srcdoc)\s*=\s*("[^"]*"|'[^']*'|[^\s>"']*)"#) { match, source in
            guard match.numberOfRanges >= 3,
                  let attrRange = Range(match.range(at: 1), in: source),
                  let valueRange = Range(match.range(at: 2), in: source) else {
                return ""
            }
            let attr = source[attrRange].lowercased()
            let rawValue = source[valueRange].trimmingCharacters(in: .whitespacesAndNewlines)
            let value = rawValue.trimmingCharacters(in: CharacterSet(charactersIn: "\"'")).trimmingCharacters(in: .whitespacesAndNewlines)
            let safe: Bool
            switch attr {
            case "href":
                safe = value.hasPrefix("#") ||
                    value.lowercased().hasPrefix("https://") ||
                    value.lowercased().hasPrefix("http://")
            case "xlink:href":
                safe = value.hasPrefix("#")
            case "src":
                safe = isSafeDataImage(value)
            default:
                safe = false
            }
            return safe ? String(source[Range(match.range, in: source)!]) : ""
        }
    }

    private static func safetyViolation(_ html: String) -> String? {
        let lower = html.lowercased()
        let compact = String(lower.unicodeScalars.filter { scalar in
            let value = scalar.value
            if value <= 0x1f || value == 0x00a0 || value == 0xfeff { return false }
            if (0x2000...0x200f).contains(value) { return false }
            return !CharacterSet.whitespacesAndNewlines.contains(scalar)
        })
        if compact.contains("<script") { return "script" }
        if html.containsMatch(pattern: #"<\s*(iframe|object|embed|form|meta|link|base|foreignobject)\b"#) {
            return "dangerous tag"
        }
        if html.containsMatch(pattern: #"\son[a-z]+\s*="#) { return "event handler" }
        if compact.contains("javascript:") { return "javascript url" }
        if compact.contains("data:text/html") { return "html data url" }
        if html.containsMatch(pattern: #"\s(srcdoc|action|srcset|poster|background)\s*="#) {
            return "external resource"
        }
        if html.containsMatch(pattern: #"\ssrc\s*=\s*(?!["']?data:image/(png|jpeg|jpg|gif|webp);base64,)"#) {
            return "external resource"
        }
        if compact.contains("data:image/svg") { return "svg data image" }
        if html.containsMatch(pattern: #"url\("#),
           !html.containsMatch(pattern: #"url\(\s*(['"]?)data:image/(png|jpeg|jpg|gif|webp);base64,"#),
           !html.containsMatch(pattern: #"url\(\s*(['"]?)#"#) {
            return "css url"
        }
        return nil
    }

    private static func isSafeDataImage(_ value: String) -> Bool {
        value.containsMatch(pattern: #"^data:image/(png|jpeg|jpg|gif|webp);base64,[a-z0-9+/=\s]+$"#)
    }
}

extension String {
    func containsMatch(pattern: String, options: NSRegularExpression.Options = [.caseInsensitive, .dotMatchesLineSeparators]) -> Bool {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return false }
        return regex.firstMatch(in: self, range: NSRange(startIndex..., in: self)) != nil
    }

    func matchCount(pattern: String, options: NSRegularExpression.Options = [.caseInsensitive, .dotMatchesLineSeparators]) -> Int {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return 0 }
        return regex.numberOfMatches(in: self, range: NSRange(startIndex..., in: self))
    }

    func replacing(
        pattern: String,
        with replacement: String,
        options: NSRegularExpression.Options = [.caseInsensitive, .dotMatchesLineSeparators]
    ) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return self }
        return regex.stringByReplacingMatches(in: self, range: NSRange(startIndex..., in: self), withTemplate: replacement)
    }

    func replacingMatches(
        pattern: String,
        options: NSRegularExpression.Options = [.caseInsensitive, .dotMatchesLineSeparators],
        transform: (NSTextCheckingResult, String) -> String
    ) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return self }
        let matches = regex.matches(in: self, range: NSRange(startIndex..., in: self))
        guard !matches.isEmpty else { return self }
        var output = ""
        var cursor = startIndex
        for match in matches {
            guard let range = Range(match.range, in: self) else { continue }
            output += self[cursor..<range.lowerBound]
            output += transform(match, self)
            cursor = range.upperBound
        }
        output += self[cursor..<endIndex]
        return output
    }
}
