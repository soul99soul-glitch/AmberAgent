import Foundation

/// [MiniApp MVP] iOS port of Android's `MiniAppHtmlValidator`
/// (app/src/main/java/app/amber/feature/miniapp/MiniAppHtmlValidator.kt).
///
/// Validates generated MiniApp HTML before it is loaded into the WKWebView
/// runner. Same rule set as Android so the security posture is equivalent:
/// reject external scripts, non-https images, dangerous browser APIs (eval,
/// dynamic import, XHR, WebSocket, storage, geo/media/clipboard), and
/// iframe/object/embed/form elements. Allow inline `<script>` (the MiniApp's
/// own logic) calling the Amber bridge API.
///
/// This is the load-bearing security gate for the MiniApp runner — without it,
/// generated HTML could exfiltrate data or reach the network directly.
enum MiniAppHtmlValidator {

    /// Max HTML size, matching Android's MINI_APP_MAX_HTML_BYTES = 768 * 1024
    /// (app/src/main/java/app/amber/feature/miniapp/MiniAppModels.kt:6).
    static let maxHtmlBytes = 768 * 1024

    struct ValidationError: Error, LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // All patterns are case-insensitive + dotall (match newlines), matching the
    // Kotlin `(?is)` flag. NSRegularExpression options: [.caseInsensitive, .dotMatchesLineSeparators].
    private struct BlockedRule {
        let pattern: String
        let reason: String
    }

    private static let requiredHtmlPattern = #"<\s*(html\b|!doctype\s+html)"#
    private static let blockedRules: [BlockedRule] = [
        .init(pattern: ##"<\s*script\b[^>]*\bsrc\s*="##, reason: "External scripts are not allowed"),
        .init(pattern: #"<\s*(iframe|object|embed|form)\b"#, reason: "Embedded/submit-capable elements are not allowed"),
        .init(pattern: ##"<\s*(img|source)\b[^>]*\bsrcset\s*="##, reason: "Image srcset is not supported"),
        .init(pattern: #"<\s*link\b[^>]*\bhref\s*=\s*['"]?\s*https?:"#, reason: "External stylesheets are not allowed"),
        .init(pattern: #"@import\s+(['"]?\s*)?(https?:|//|file:|content:)"#, reason: "CSS imports are not allowed"),
        .init(pattern: #"url\s*\(\s*(['"]?)\s*(https?:|//|file:|content:)"#, reason: "External CSS URLs are not allowed"),
        .init(pattern: #"\beval\s*\("#, reason: "eval() is not allowed"),
        .init(pattern: #"\bnew\s+Function\b"#, reason: "new Function is not allowed"),
        .init(pattern: #"\bimport\s*\("#, reason: "dynamic import() is not allowed"),
        .init(pattern: #"\bimport\s+['"]"#, reason: "static import is not allowed"),
        .init(pattern: #"\bWebSocket\b"#, reason: "WebSocket is not allowed"),
        .init(pattern: #"\bEventSource\b"#, reason: "EventSource is not allowed"),
        .init(pattern: #"\bXMLHttpRequest\b"#, reason: "XMLHttpRequest is not allowed"),
        .init(pattern: #"\blocalStorage\b"#, reason: "localStorage is not allowed"),
        .init(pattern: #"\bsessionStorage\b"#, reason: "sessionStorage is not allowed"),
        .init(pattern: #"\bindexedDB\b"#, reason: "indexedDB is not allowed"),
        .init(pattern: #"\bnavigator\s*\.\s*geolocation\b"#, reason: "geolocation is not allowed"),
        .init(pattern: #"\bnavigator\s*\.\s*mediaDevices\b"#, reason: "mediaDevices is not allowed"),
        .init(pattern: #"\bnavigator\s*\.\s*clipboard\b"#, reason: "native clipboard is not allowed"),
        .init(pattern: #"\bnavigator\s*\[\s*['"]\s*(geolocation|mediaDevices|clipboard)\s*['"]\s*\]"#, reason: "computed access to blocked navigator APIs is not allowed"),
        .init(pattern: #"\bwindow\s*\[\s*['"]\s*(XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB)\s*['"]\s*\]"#, reason: "computed access to blocked browser APIs is not allowed"),
        .init(pattern: #"\bglobalThis\s*\[\s*['"]\s*(XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB)\s*['"]\s*\]"#, reason: "computed access to blocked browser APIs is not allowed"),
    ]

    // Image resource extraction (src/srcset), quoted + unquoted.
    private static let quotedImageResourcePattern = ##"<\s*(img|source)\b[^>]*\b(src|srcset)\s*=\s*(['"])(.*?)\3"##
    private static let unquotedImageResourcePattern = ##"<\s*(img|source)\b[^>]*\b(src|srcset)\s*=\s*([^\s"'=<>`]+)"##

    /// Validate `html`. Throws `ValidationError` if any rule is violated.
    /// Mirrors Android's `MiniAppHtmlValidator.validate` exactly.
    static func validate(_ html: String) throws {
        let sizeBytes = html.utf8.count
        if sizeBytes > maxHtmlBytes {
            throw ValidationError(message: "HTML is too large: \(sizeBytes) bytes")
        }
        let opts: NSRegularExpression.Options = [.caseInsensitive, .dotMatchesLineSeparators]
        let fullRange = NSRange(html.startIndex..., in: html)

        if try regex(requiredHtmlPattern, opts).firstMatch(in: html, range: fullRange) == nil {
            throw ValidationError(message: "HTML must include <html> or <!DOCTYPE html>")
        }
        for rule in blockedRules {
            if try regex(rule.pattern, opts).firstMatch(in: html, range: fullRange) != nil {
                throw ValidationError(message: rule.reason)
            }
        }
        if try hasInvalidImageResource(html, opts: opts) {
            throw ValidationError(message: "MiniApp images must use data:image or https URLs")
        }
    }

    private static func regex(_ pattern: String, _ opts: NSRegularExpression.Options) throws -> NSRegularExpression {
        try NSRegularExpression(pattern: pattern, options: opts)
    }

    private static func hasInvalidImageResource(_ html: String, opts: NSRegularExpression.Options) throws -> Bool {
        let fullRange = NSRange(html.startIndex..., in: html)
        // Quoted: capture group 4 is the URL.
        let quoted = try regex(quotedImageResourcePattern, opts)
        for m in quoted.matches(in: html, range: fullRange) {
            if let r = Range(m.range(at: 4), in: html),
               !html[r].trimmingCharacters(in: .whitespaces).isAllowedImageUrl {
                return true
            }
        }
        // Unquoted: capture group 3 is the URL.
        let unquoted = try regex(unquotedImageResourcePattern, opts)
        for m in unquoted.matches(in: html, range: fullRange) {
            if let r = Range(m.range(at: 3), in: html),
               !html[r].trimmingCharacters(in: .whitespaces).isAllowedImageUrl {
                return true
            }
        }
        return false
    }
}

private extension String {
    var isAllowedImageUrl: Bool {
        lowercased().hasPrefix("data:image/") || lowercased().hasPrefix("https://")
    }
}

