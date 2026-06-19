import Foundation
@preconcurrency import Shared

struct IOSSearchRequest: Equatable {
    let query: String
    let maxResults: Int
}

struct IOSScrapeRequest: Equatable {
    let url: URL
    let maxChars: Int
}

struct IOSSearchResult: Equatable {
    let title: String
    let url: String
    let snippet: String
}

enum IOSSearchRoute: String, Equatable {
    case duckDuckGoLite = "duckduckgo_lite"
    case bingHTML = "bing_html"
    case unsupported
}

struct IOSSearchProviderSelection: Equatable {
    let route: IOSSearchRoute
    let providerName: String
    let providerType: String
    let serviceId: String?
    let fallbackReason: String?
}

protocol IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data)
}

struct IOSURLSessionSearchHTTPTransport: IOSSearchHTTPTransport {
    var session: URLSession = .shared

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw IOSSearchExecutorError.invalidHTTPResponse
        }
        return (httpResponse, data)
    }
}

enum IOSSearchExecutorError: LocalizedError, Equatable {
    case missingQuery
    case missingURL
    case invalidURL
    case disallowedURL(String)
    case unsupportedTool(String)
    case unsupportedProvider(String)
    case unsupportedContentType(String)
    case invalidHTTPResponse
    case httpStatus(String, Int)
    case emptyResponse(String)

    var errorDescription: String? {
        switch self {
        case .missingQuery:
            return "Search query is empty."
        case .missingURL:
            return "scrape_web requires a url."
        case .invalidURL:
            return "URL is invalid or uses an unsupported scheme."
        case .disallowedURL(let reason):
            return "URL is not allowed: \(reason)"
        case .unsupportedTool(let name):
            return "Unsupported iOS search tool: \(name)."
        case .unsupportedProvider(let reason):
            return reason
        case .unsupportedContentType(let contentType):
            return "Unsupported content type for scraping: \(contentType)."
        case .invalidHTTPResponse:
            return "Search transport returned a non-HTTP response."
        case .httpStatus(let provider, let status):
            return "\(provider) failed with HTTP status \(status)."
        case .emptyResponse(let provider):
            return "\(provider) returned no parseable content."
        }
    }
}

struct IOSSearchExecutor {
    static let supportedToolNames: Set<String> = ["search_web", "scrape_web"]

    static func execute(
        toolInput: String,
        maxResults defaultMaxResults: Int = 5,
        settings: Settings? = nil,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> String {
        try await execute(
            toolName: "search_web",
            toolInput: toolInput,
            maxResults: defaultMaxResults,
            settings: settings,
            transport: transport
        )
    }

    static func execute(
        toolName: String,
        toolInput: String,
        maxResults defaultMaxResults: Int = 5,
        settings: Settings? = nil,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> String {
        switch toolName {
        case "search_web":
            let settingsDefaultMaxResults = settings.map { Int($0.searchCommonOptions.resultSize) } ?? defaultMaxResults
            let request = try searchRequest(from: toolInput, defaultMaxResults: settingsDefaultMaxResults)
            let selection = searchProviderSelection(settings: settings)
            let results: [IOSSearchResult]
            switch selection.route {
            case .duckDuckGoLite:
                results = try await searchDuckDuckGoLite(
                    query: request.query,
                    maxResults: request.maxResults,
                    transport: transport
                )
            case .bingHTML:
                results = try await searchBingHTML(
                    query: request.query,
                    maxResults: request.maxResults,
                    transport: transport
                )
            case .unsupported:
                throw IOSSearchExecutorError.unsupportedProvider(
                    selection.fallbackReason ?? "\(selection.providerName) is not implemented on iOS search."
                )
            }
            return format(query: request.query, results: results, selection: selection)
        case "scrape_web":
            let request = try scrapeRequest(from: toolInput)
            return try await scrapeWeb(request: request, transport: transport)
        default:
            throw IOSSearchExecutorError.unsupportedTool(toolName)
        }
    }

    static func searchProviderSelection(settings: Settings?) -> IOSSearchProviderSelection {
        guard let settings else {
            return IOSSearchProviderSelection(
                route: .duckDuckGoLite,
                providerName: "DuckDuckGo Lite",
                providerType: "duckduckgo_builtin",
                serviceId: nil,
                fallbackReason: nil
            )
        }

        let services = settings.searchServices
        let enabledIds = Set(settings.searchEnabledServiceIds.map { uuidString($0) })
        let selectedIndex = Int(settings.searchServiceSelected)
        let selectedService = services.indices.contains(selectedIndex) ? services[selectedIndex] : nil

        if let selectedService {
            let descriptor = providerDescriptor(for: selectedService)
            let selectedId = uuidString(selectedService.id)
            if enabledIds.contains(selectedId) {
                if descriptor.route != .unsupported {
                    return IOSSearchProviderSelection(
                        route: descriptor.route,
                        providerName: descriptor.name,
                        providerType: descriptor.type,
                        serviceId: selectedId,
                        fallbackReason: nil
                    )
                }
                if let fallback = executableConfiguredFallback(
                    services: services,
                    enabledIds: enabledIds,
                    excluding: selectedId,
                    reason: "Selected provider \(descriptor.name) is saved and enabled, but iOS has no native executor for \(descriptor.type) yet."
                ) {
                    return fallback
                }
                return builtInFallback(
                    settings: settings,
                    reason: "Selected provider \(descriptor.name) is saved and enabled, but iOS has no native executor for \(descriptor.type) yet."
                )
            } else {
                if let fallback = executableConfiguredFallback(
                    services: services,
                    enabledIds: enabledIds,
                    excluding: selectedId,
                    reason: "Selected provider \(descriptor.name) is disabled in searchEnabledServiceIds."
                ) {
                    return fallback
                }
                return builtInFallback(
                    settings: settings,
                    reason: "Selected provider \(descriptor.name) is disabled in searchEnabledServiceIds."
                )
            }
        }

        if let fallback = executableConfiguredFallback(
            services: services,
            enabledIds: enabledIds,
            excluding: nil,
            reason: "searchServiceSelected does not point at a saved provider."
        ) {
            return fallback
        }
        return builtInFallback(
            settings: settings,
            reason: "searchServiceSelected does not point at a saved provider."
        )
    }

    static func searchDuckDuckGoLite(
        query: String,
        maxResults: Int = 5,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else { throw IOSSearchExecutorError.missingQuery }

        var components = URLComponents(string: "https://lite.duckduckgo.com/lite/")
        components?.queryItems = [URLQueryItem(name: "q", value: trimmedQuery)]
        guard let url = components?.url else { throw IOSSearchExecutorError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Mozilla/5.0 AmberAgent-iOS Search", forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 20

        let (response, data) = try await transport.send(request)
        guard (200...299).contains(response.statusCode) else {
            throw IOSSearchExecutorError.httpStatus("DuckDuckGo Lite", response.statusCode)
        }

        let html = String(decoding: data, as: UTF8.self)
        let results = parseDuckDuckGoLite(html: html, maxResults: maxResults)
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("DuckDuckGo Lite") }
        return results
    }

    static func searchBingHTML(
        query: String,
        maxResults: Int = 5,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else { throw IOSSearchExecutorError.missingQuery }

        var components = URLComponents(string: "https://www.bing.com/search")
        components?.queryItems = [URLQueryItem(name: "q", value: trimmedQuery)]
        guard let url = components?.url else { throw IOSSearchExecutorError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Mozilla/5.0 AmberAgent-iOS Search", forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 20

        let (response, data) = try await transport.send(request)
        guard (200...299).contains(response.statusCode) else {
            throw IOSSearchExecutorError.httpStatus("Bing HTML", response.statusCode)
        }

        let html = String(decoding: data, as: UTF8.self)
        let results = parseBingHTML(html: html, maxResults: maxResults)
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Bing HTML") }
        return results
    }

    static func searchRequest(from toolInput: String, defaultMaxResults: Int = 5) throws -> IOSSearchRequest {
        let trimmedInput = toolInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { throw IOSSearchExecutorError.missingQuery }

        if let object = jsonObject(trimmedInput) {
            let query = (object["query"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !query.isEmpty else { throw IOSSearchExecutorError.missingQuery }
            let maxResults = sanitizedMaxResults(intValue(object["max_results"]) ?? defaultMaxResults)
            return IOSSearchRequest(query: query, maxResults: maxResults)
        }

        return IOSSearchRequest(query: trimmedInput, maxResults: sanitizedMaxResults(defaultMaxResults))
    }

    static func scrapeRequest(from toolInput: String, defaultMaxChars: Int = 12_000) throws -> IOSScrapeRequest {
        let trimmedInput = toolInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedInput.isEmpty else { throw IOSSearchExecutorError.missingURL }

        let rawURL: String
        let maxChars: Int
        if let object = jsonObject(trimmedInput) {
            rawURL = (object["url"] as? String) ??
                (object["link"] as? String) ??
                (object["uri"] as? String) ??
                ""
            maxChars = sanitizedMaxChars(intValue(object["max_chars"]) ?? defaultMaxChars)
        } else {
            rawURL = trimmedInput
            maxChars = sanitizedMaxChars(defaultMaxChars)
        }

        let url = try allowedPublicHTTPURL(from: rawURL)
        return IOSScrapeRequest(url: url, maxChars: maxChars)
    }

    static func parseDuckDuckGoLite(html: String, maxResults: Int) -> [IOSSearchResult] {
        let cappedMaxResults = sanitizedMaxResults(maxResults)
        let pattern = #"<a[^>]*class=["']?result-link["']?[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>"#
        var matches = regexMatches(pattern: pattern, in: html)

        if matches.isEmpty {
            let fallbackPattern = #"<a[^>]*href=["']([^"']*uddg=(?:[^"']+))["'][^>]*>(.*?)</a>"#
            matches = regexMatches(pattern: fallbackPattern, in: html)
        }

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

    static func parseBingHTML(html: String, maxResults: Int) -> [IOSSearchResult] {
        let cappedMaxResults = sanitizedMaxResults(maxResults)
        let primaryPattern = #"<li[^>]*class=["'][^"']*\bb_algo\b[^"']*["'][^>]*>(.*?)</li>"#
        let resultBlocks = regexMatches(pattern: primaryPattern, in: html)
        let primary = resultBlocks.compactMap { match -> IOSSearchResult? in
            guard let blockRange = Range(match.range(at: 1), in: html) else { return nil }
            let block = String(html[blockRange])
            let anchorPattern = #"<h2[^>]*>.*?<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>.*?</h2>"#
            guard let anchor = regexMatches(pattern: anchorPattern, in: block).first,
                  anchor.numberOfRanges >= 3,
                  let hrefRange = Range(anchor.range(at: 1), in: block),
                  let titleRange = Range(anchor.range(at: 2), in: block) else {
                return nil
            }
            let snippet = firstCapture(pattern: #"<p[^>]*>(.*?)</p>"#, text: block).map(cleanHTML) ?? ""
            let title = cleanHTML(String(block[titleRange]))
            let url = cleanBingURL(String(block[hrefRange]))
            guard !title.isEmpty, url.hasPrefix("http") else { return nil }
            return IOSSearchResult(title: title, url: url, snippet: snippet)
        }

        if !primary.isEmpty {
            return Array(primary.distinctByURL().prefix(cappedMaxResults))
        }

        let fallbackPattern = #"<h2[^>]*>.*?<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>.*?</h2>"#
        return regexMatches(pattern: fallbackPattern, in: html)
            .compactMap { match -> IOSSearchResult? in
                guard match.numberOfRanges >= 3,
                      let hrefRange = Range(match.range(at: 1), in: html),
                      let titleRange = Range(match.range(at: 2), in: html) else {
                    return nil
                }
                let title = cleanHTML(String(html[titleRange]))
                let url = cleanBingURL(String(html[hrefRange]))
                guard !title.isEmpty, url.hasPrefix("http") else { return nil }
                return IOSSearchResult(title: title, url: url, snippet: "")
            }
            .distinctByURL()
            .prefix(cappedMaxResults)
            .map { $0 }
    }

    private static func scrapeWeb(
        request: IOSScrapeRequest,
        transport: any IOSSearchHTTPTransport
    ) async throws -> String {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = "GET"
        urlRequest.setValue("Mozilla/5.0 AmberAgent-iOS Scrape", forHTTPHeaderField: "User-Agent")
        urlRequest.setValue("text/html,text/plain,application/xhtml+xml,application/json;q=0.8,*/*;q=0.3", forHTTPHeaderField: "Accept")
        urlRequest.timeoutInterval = 20

        let (response, data) = try await transport.send(urlRequest)
        guard (200...299).contains(response.statusCode) else {
            throw IOSSearchExecutorError.httpStatus("scrape_web", response.statusCode)
        }

        let contentType = response.value(forHTTPHeaderField: "Content-Type") ?? ""
        guard contentTypeAllowedForScrape(contentType) else {
            throw IOSSearchExecutorError.unsupportedContentType(contentType)
        }

        let raw = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        let title = extractTitle(from: raw)
        let content = extractReadableText(from: raw, maxChars: request.maxChars)
        guard !content.isEmpty else { throw IOSSearchExecutorError.emptyResponse("scrape_web") }

        return jsonString([
            "status": "ok",
            "url": request.url.absoluteString,
            "title": title,
            "content": content,
            "chars": content.count
        ])
    }

    private static func format(
        query: String,
        results: [IOSSearchResult],
        selection: IOSSearchProviderSelection
    ) -> String {
        var lines = [
            "Search results for: \(query)",
            "Source: \(selection.providerName)",
            "Provider route: \(selection.providerType)"
        ]
        if let fallbackReason = selection.fallbackReason {
            lines.append("Fallback: \(fallbackReason)")
        }
        for (index, result) in results.enumerated() {
            lines.append("\n[\(index + 1)] \(result.title)")
            lines.append(result.url)
            if !result.snippet.isEmpty {
                lines.append(result.snippet)
            }
        }
        return lines.joined(separator: "\n")
    }

    private static func builtInFallback(settings: Settings, reason: String) -> IOSSearchProviderSelection {
        if settings.searchBuiltinDuckDuckGoEnabled {
            return IOSSearchProviderSelection(
                route: .duckDuckGoLite,
                providerName: "DuckDuckGo Lite",
                providerType: "duckduckgo_builtin",
                serviceId: nil,
                fallbackReason: reason
            )
        }
        if settings.searchBuiltinBingEnabled {
            return IOSSearchProviderSelection(
                route: .bingHTML,
                providerName: "Bing HTML",
                providerType: "bing_builtin",
                serviceId: nil,
                fallbackReason: reason
            )
        }
        return IOSSearchProviderSelection(
            route: .unsupported,
            providerName: "No iOS search provider",
            providerType: "none",
            serviceId: nil,
            fallbackReason: "\(reason) No executable built-in iOS fallback is enabled."
        )
    }

    private static func executableConfiguredFallback(
        services: [SearchServiceOptions],
        enabledIds: Set<String>,
        excluding excludedId: String?,
        reason: String
    ) -> IOSSearchProviderSelection? {
        for service in services {
            let id = uuidString(service.id)
            guard id != excludedId, enabledIds.contains(id) else { continue }
            let descriptor = providerDescriptor(for: service)
            guard descriptor.route != .unsupported else { continue }
            return IOSSearchProviderSelection(
                route: descriptor.route,
                providerName: descriptor.name,
                providerType: descriptor.type,
                serviceId: id,
                fallbackReason: reason
            )
        }
        return nil
    }

    private static func providerDescriptor(for service: SearchServiceOptions) -> IOSSearchProviderDescriptor {
        switch service {
        case is SearchServiceOptions.BingLocalOptions:
            return IOSSearchProviderDescriptor(name: "Bing HTML", type: "bing_local", route: .bingHTML)
        case is SearchServiceOptions.TavilyOptions:
            return IOSSearchProviderDescriptor(name: "Tavily", type: "tavily", route: .unsupported)
        case is SearchServiceOptions.ExaOptions:
            return IOSSearchProviderDescriptor(name: "Exa", type: "exa", route: .unsupported)
        case is SearchServiceOptions.ZhipuOptions:
            return IOSSearchProviderDescriptor(name: "Zhipu", type: "zhipu", route: .unsupported)
        case is SearchServiceOptions.SearXNGOptions:
            return IOSSearchProviderDescriptor(name: "SearXNG", type: "searxng", route: .unsupported)
        case is SearchServiceOptions.LinkUpOptions:
            return IOSSearchProviderDescriptor(name: "LinkUp", type: "linkup", route: .unsupported)
        case is SearchServiceOptions.BraveOptions:
            return IOSSearchProviderDescriptor(name: "Brave", type: "brave", route: .unsupported)
        case is SearchServiceOptions.SerperOptions:
            return IOSSearchProviderDescriptor(name: "Serper", type: "serper", route: .unsupported)
        case is SearchServiceOptions.SerpApiOptions:
            return IOSSearchProviderDescriptor(name: "SerpAPI", type: "serpapi", route: .unsupported)
        case is SearchServiceOptions.MetasoOptions:
            return IOSSearchProviderDescriptor(name: "Metaso", type: "metaso", route: .unsupported)
        case is SearchServiceOptions.OllamaOptions:
            return IOSSearchProviderDescriptor(name: "Ollama", type: "ollama", route: .unsupported)
        case is SearchServiceOptions.PerplexityOptions:
            return IOSSearchProviderDescriptor(name: "Perplexity", type: "perplexity", route: .unsupported)
        case is SearchServiceOptions.FirecrawlOptions:
            return IOSSearchProviderDescriptor(name: "Firecrawl", type: "firecrawl", route: .unsupported)
        case is SearchServiceOptions.JinaOptions:
            return IOSSearchProviderDescriptor(name: "Jina", type: "jina", route: .unsupported)
        case is SearchServiceOptions.BochaOptions:
            return IOSSearchProviderDescriptor(name: "Bocha", type: "bocha", route: .unsupported)
        case is SearchServiceOptions.AmberAgentSearchOptions:
            return IOSSearchProviderDescriptor(name: "AmberAgent", type: "amber_agent", route: .unsupported)
        case is SearchServiceOptions.GrokOptions:
            return IOSSearchProviderDescriptor(name: "Grok", type: "grok", route: .unsupported)
        default:
            return IOSSearchProviderDescriptor(name: "Search provider", type: String(describing: type(of: service)), route: .unsupported)
        }
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

    private static func cleanBingURL(_ rawURL: String) -> String {
        let decoded = decodeHTMLEntities(rawURL)
        guard let components = URLComponents(string: decoded),
              let queryItems = components.queryItems else {
            return decoded
        }
        if let target = queryItems.first(where: { $0.name == "u" || $0.name == "url" })?.value,
           !target.isEmpty {
            return target
        }
        return decoded
    }

    private static func allowedPublicHTTPURL(from rawURL: String) throws -> URL {
        let trimmed = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw IOSSearchExecutorError.missingURL }
        guard var components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host?.trimmingCharacters(in: .whitespacesAndNewlines),
              !host.isEmpty else {
            throw IOSSearchExecutorError.invalidURL
        }
        if components.user != nil || components.password != nil {
            throw IOSSearchExecutorError.disallowedURL("embedded credentials are blocked")
        }
        guard publicHostAllowed(host) else {
            throw IOSSearchExecutorError.disallowedURL("local, loopback, link-local, and private hosts are blocked")
        }
        components.fragment = nil
        guard let url = components.url else { throw IOSSearchExecutorError.invalidURL }
        return url
    }

    private static func publicHostAllowed(_ host: String) -> Bool {
        let lower = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".[]"))
        if lower == "localhost" || lower == "0.0.0.0" || lower.hasSuffix(".local") {
            return false
        }
        if lower == "::1" || lower.hasPrefix("fe80:") || lower.hasPrefix("fc") || lower.hasPrefix("fd") {
            return false
        }
        let parts = lower.split(separator: ".").compactMap { Int($0) }
        if parts.count == 4 {
            if parts[0] == 10 || parts[0] == 127 || parts[0] == 0 { return false }
            if parts[0] == 169 && parts[1] == 254 { return false }
            if parts[0] == 172 && (16...31).contains(parts[1]) { return false }
            if parts[0] == 192 && parts[1] == 168 { return false }
        }
        return true
    }

    private static func contentTypeAllowedForScrape(_ contentType: String) -> Bool {
        let lower = contentType.lowercased()
        if lower.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        return lower.contains("text/") ||
            lower.contains("html") ||
            lower.contains("xml") ||
            lower.contains("json")
    }

    private static func extractTitle(from html: String) -> String {
        firstCapture(pattern: #"<title[^>]*>(.*?)</title>"#, text: html)
            .map(cleanHTML) ?? ""
    }

    private static func extractReadableText(from raw: String, maxChars: Int) -> String {
        var text = raw
        text = regexReplace(pattern: #"<script\b[^>]*>.*?</script>"#, in: text, with: " ")
        text = regexReplace(pattern: #"<style\b[^>]*>.*?</style>"#, in: text, with: " ")
        text = regexReplace(pattern: #"<noscript\b[^>]*>.*?</noscript>"#, in: text, with: " ")
        text = regexReplace(pattern: #"<svg\b[^>]*>.*?</svg>"#, in: text, with: " ")
        text = regexReplace(pattern: #"</?(p|div|section|article|header|footer|main|li|h[1-6]|br)\b[^>]*>"#, in: text, with: "\n")
        text = cleanHTML(text)
        if text.count > maxChars {
            return String(text.prefix(maxChars)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text
    }

    private static func cleanHTML(_ html: String) -> String {
        let withoutTags = regexReplace(pattern: #"<[^>]+>"#, in: html, with: " ")
        let decoded = decodeHTMLEntities(withoutTags)
        let collapsed = decoded
            .replacingOccurrences(of: #"[ \t\r\f\v]+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\n\s*\n+"#, with: "\n", options: .regularExpression)
        return collapsed
            .replacingOccurrences(of: #"\s+([.,!?;:])"#, with: "$1", options: .regularExpression)
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

    private static func regexReplace(pattern: String, in text: String, with replacement: String) -> String {
        text.replacingOccurrences(
            of: pattern,
            with: replacement,
            options: [.regularExpression, .caseInsensitive]
        )
    }

    private static func firstCapture(pattern: String, text: String) -> String? {
        guard let match = regexMatches(pattern: pattern, in: text).first,
              match.numberOfRanges >= 2,
              let range = Range(match.range(at: 1), in: text) else {
            return nil
        }
        return String(text[range])
    }

    private static func jsonObject(_ string: String) -> [String: Any]? {
        guard let data = string.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func jsonString(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }

    private static func intValue(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber { return value.intValue }
        if let value = value as? String { return Int(value) }
        return nil
    }

    private static func sanitizedMaxResults(_ value: Int) -> Int {
        min(max(value, 1), 10)
    }

    private static func sanitizedMaxChars(_ value: Int) -> Int {
        min(max(value, 1_000), 40_000)
    }

    private static func uuidString(_ uuid: KotlinUuid) -> String {
        uuid.description()
    }
}

private struct IOSSearchProviderDescriptor {
    let name: String
    let type: String
    let route: IOSSearchRoute
}

private extension Array where Element == IOSSearchResult {
    func distinctByURL() -> [IOSSearchResult] {
        var seen = Set<String>()
        var output: [IOSSearchResult] = []
        for result in self where !seen.contains(result.url) {
            seen.insert(result.url)
            output.append(result)
        }
        return output
    }
}
