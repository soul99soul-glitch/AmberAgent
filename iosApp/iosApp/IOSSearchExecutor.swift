import Foundation

struct IOSSearchRequest: Equatable {
    let query: String
    let maxResults: Int
}

struct IOSSearchResult: Equatable {
    let title: String
    let url: String
    let snippet: String
}

enum IOSSearchExecutorError: LocalizedError {
    case missingQuery
    case invalidURL
    case httpStatus(Int)
    case emptyResponse

    var errorDescription: String? {
        switch self {
        case .missingQuery:
            return "Search query is empty."
        case .invalidURL:
            return "Unable to build DuckDuckGo Lite search URL."
        case .httpStatus(let status):
            return "DuckDuckGo Lite search failed with HTTP status \(status)."
        case .emptyResponse:
            return "DuckDuckGo Lite returned no parseable search results."
        }
    }
}

struct IOSSearchExecutor {
    static func execute(toolInput: String, maxResults defaultMaxResults: Int = 5) async throws -> String {
        let request = try searchRequest(from: toolInput, defaultMaxResults: defaultMaxResults)
        let results = try await searchDuckDuckGoLite(query: request.query, maxResults: request.maxResults)
        return format(query: request.query, results: results)
    }

    static func searchDuckDuckGoLite(query: String, maxResults: Int = 5) async throws -> [IOSSearchResult] {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else { throw IOSSearchExecutorError.missingQuery }

        var components = URLComponents(string: "https://lite.duckduckgo.com/lite/")
        components?.queryItems = [URLQueryItem(name: "q", value: trimmedQuery)]
        guard let url = components?.url else { throw IOSSearchExecutorError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Mozilla/5.0 AmberAgent-iOS Search", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20

        let (data, response) = try await URLSession.shared.data(for: request)
        if let httpResponse = response as? HTTPURLResponse,
           !(200...299).contains(httpResponse.statusCode) {
            throw IOSSearchExecutorError.httpStatus(httpResponse.statusCode)
        }

        let html = String(decoding: data, as: UTF8.self)
        let results = parseDuckDuckGoLite(html: html, maxResults: maxResults)
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse }
        return results
    }

    static func searchRequest(from toolInput: String, defaultMaxResults: Int = 5) throws -> IOSSearchRequest {
        let trimmedInput = toolInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { throw IOSSearchExecutorError.missingQuery }

        if let data = trimmedInput.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let query = (object["query"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !query.isEmpty else { throw IOSSearchExecutorError.missingQuery }
            let maxResults = sanitizedMaxResults(object["max_results"] as? Int ?? defaultMaxResults)
            return IOSSearchRequest(query: query, maxResults: maxResults)
        }

        return IOSSearchRequest(query: trimmedInput, maxResults: sanitizedMaxResults(defaultMaxResults))
    }

    static func parseDuckDuckGoLite(html: String, maxResults: Int) -> [IOSSearchResult] {
        let cappedMaxResults = sanitizedMaxResults(maxResults)
        // Primary: DuckDuckGo Lite CSS class-based parsing.
        let pattern = #"<a[^>]*class=["']?result-link["']?[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>"#
        var matches = regexMatches(pattern: pattern, in: html)

        // Fallback: if the primary pattern fails (DuckDuckGo changed HTML),
        // try a generic <a> with href containing "duckduckgo.com/lite?uddg=".
        if matches.isEmpty {
            let fallbackPattern = #"<a[^>]*href=["']([^"']*uddg=([^"']+))["'][^>]*>(.*?)</a>"#
            matches = regexMatches(pattern: fallbackPattern, in: html)
        }

        // Fallback 2: any <a> with class containing "result" and an href.
        if matches.isEmpty {
            let fallbackPattern2 = #"<a[^>]*class=["'][^"']*result[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>"#
            matches = regexMatches(pattern: fallbackPattern2, in: html)
        }

        return matches.prefix(cappedMaxResults).compactMap { match in
            guard match.numberOfRanges >= 3,
                  let hrefRange = Range(match.range(at: 1), in: html),
                  let titleRange = Range(match.range(at: 2), in: html) else {
                return nil
            }

            let rawURL = String(html[hrefRange])
            let rawTitle = String(html[titleRange])
            let snippet = snippet(after: match.range, in: html)
            let title = cleanHTML(rawTitle)
            let url = cleanResultURL(rawURL)
            guard !title.isEmpty, !url.isEmpty else { return nil }
            return IOSSearchResult(title: title, url: url, snippet: snippet)
        }
    }

    private static func format(query: String, results: [IOSSearchResult]) -> String {
        var lines = ["Search results for: \(query)", "Source: DuckDuckGo Lite"]
        for (index, result) in results.enumerated() {
            lines.append("\n[\(index + 1)] \(result.title)")
            lines.append(result.url)
            if !result.snippet.isEmpty {
                lines.append(result.snippet)
            }
        }
        return lines.joined(separator: "\n")
    }

    private static func snippet(after anchorRange: NSRange, in html: String) -> String {
        let start = anchorRange.location + anchorRange.length
        let end = min(html.utf16.count, start + 3_000)
        guard start < end,
              let searchRange = Range(NSRange(location: start, length: end - start), in: html) else {
            return ""
        }

        let fragment = String(html[searchRange])
        let pattern = #"<td[^>]*class=["']?result-snippet["']?[^>]*>(.*?)</td>"#
        guard let match = regexMatches(pattern: pattern, in: fragment).first,
              match.numberOfRanges >= 2,
              let range = Range(match.range(at: 1), in: fragment) else {
            return ""
        }
        return cleanHTML(String(fragment[range]))
    }

    private static func cleanResultURL(_ rawURL: String) -> String {
        let decoded = decodeHTMLEntities(rawURL)
        guard let components = URLComponents(string: decoded),
              let uddg = components.queryItems?.first(where: { $0.name == "uddg" })?.value,
              !uddg.isEmpty else {
            if decoded.hasPrefix("//") { return "https:" + decoded }
            return decoded
        }
        return uddg
    }

    private static func cleanHTML(_ html: String) -> String {
        let withoutTags = html.replacingOccurrences(
            of: #"<[^>]+>"#,
            with: " ",
            options: .regularExpression
        )
        let decoded = decodeHTMLEntities(withoutTags)
        return decoded
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func decodeHTMLEntities(_ text: String) -> String {
        var decoded = text
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ")

        let numericPattern = #"&#(x?[0-9A-Fa-f]+);"#
        let matches = regexMatches(pattern: numericPattern, in: decoded).reversed()
        for match in matches where match.numberOfRanges >= 2 {
            guard let fullRange = Range(match.range(at: 0), in: decoded),
                  let valueRange = Range(match.range(at: 1), in: decoded) else { continue }
            let value = String(decoded[valueRange])
            let radix = value.lowercased().hasPrefix("x") ? 16 : 10
            let digits = radix == 16 ? String(value.dropFirst()) : value
            guard let scalarValue = UInt32(digits, radix: radix),
                  let scalar = UnicodeScalar(scalarValue) else { continue }
            decoded.replaceSubrange(fullRange, with: String(Character(scalar)))
        }
        return decoded
    }

    private static func regexMatches(pattern: String, in text: String) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return []
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, range: range)
    }

    private static func sanitizedMaxResults(_ value: Int) -> Int {
        min(max(value, 1), 10)
    }
}
