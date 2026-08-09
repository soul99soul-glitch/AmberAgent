import Foundation
import Darwin
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
    /// Candidate image URLs from the search provider (e.g. Brave thumbnails), used
    /// to source the Deep Read editorial hero. Defaults empty for providers/parsers
    /// that don't surface images.
    var images: [String] = []
}

enum IOSSearchRoute: String, Equatable {
    case duckDuckGoLite = "duckduckgo_lite"
    case bingHTML = "bing_html"
    case tavilyAPI = "tavily_api"
    case exaAPI = "exa_api"
    case zhipuAPI = "zhipu_api"
    case braveAPI = "brave_api"
    case serperAPI = "serper_api"
    case serpAPI = "serpapi_api"
    case jinaAPI = "jina_api"
    case unsupported
}

struct IOSSearchProviderSelection: Equatable {
    let route: IOSSearchRoute
    let providerName: String
    let providerType: String
    let serviceId: String?
    let fallbackReason: String?
}

struct IOSSearchExecution: Equatable {
    let request: IOSSearchRequest
    let selection: IOSSearchProviderSelection
    let results: [IOSSearchResult]
}

@MainActor
protocol IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data)
    func sendPublic(_ request: URLRequest, maximumResponseBytes: Int) async throws -> (HTTPURLResponse, Data)
}

extension IOSSearchHTTPTransport {
    func sendPublic(_ request: URLRequest, maximumResponseBytes: Int) async throws -> (HTTPURLResponse, Data) {
        let (response, data) = try await send(request)
        guard data.count <= maximumResponseBytes else {
            throw IOSSearchExecutorError.responseTooLarge(maximumResponseBytes)
        }
        return (response, data)
    }
}

struct IOSURLSessionSearchHTTPTransport: IOSSearchHTTPTransport {
    var session: URLSession
    var resolveHost: @Sendable (String) throws -> [String]

    init(
        session: URLSession = .shared,
        resolveHost: @escaping @Sendable (String) throws -> [String] = IOSSearchExecutor.resolveIPAddresses
    ) {
        self.session = session
        self.resolveHost = resolveHost
    }

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw IOSSearchExecutorError.invalidHTTPResponse
        }
        return (httpResponse, data)
    }

    func sendPublic(_ request: URLRequest, maximumResponseBytes: Int) async throws -> (HTTPURLResponse, Data) {
        guard let url = request.url else { throw IOSSearchExecutorError.invalidURL }
        let validated = try IOSSearchExecutor.allowedPublicHTTPURL(from: url.absoluteString)
        guard let host = validated.host else { throw IOSSearchExecutorError.invalidURL }
        let resolver = resolveHost
        let addresses = try await Task.detached(priority: .userInitiated) {
            try resolver(host)
        }.value
        guard !addresses.isEmpty, addresses.allSatisfy(IOSSearchExecutor.publicHostAllowed) else {
            throw IOSSearchExecutorError.disallowedURL("host resolves to a non-public address")
        }
        let loader = IOSBoundedPublicURLSessionLoader(
            configuration: session.configuration,
            maximumResponseBytes: maximumResponseBytes,
            requiresHTTPS: validated.scheme?.lowercased() == "https",
            resolveHost: resolveHost
        )
        return try await loader.load(request)
    }
}

private final class IOSBoundedPublicURLSessionLoader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let configuration: URLSessionConfiguration
    private let maximumResponseBytes: Int
    private let requiresHTTPS: Bool
    private let resolveHost: @Sendable (String) throws -> [String]
    private let lock = NSLock()

    private var continuation: CheckedContinuation<(HTTPURLResponse, Data), Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var response: HTTPURLResponse?
    private var data = Data()
    private var cancellationRequested = false

    init(
        configuration: URLSessionConfiguration,
        maximumResponseBytes: Int,
        requiresHTTPS: Bool,
        resolveHost: @escaping @Sendable (String) throws -> [String]
    ) {
        self.configuration = configuration
        self.maximumResponseBytes = maximumResponseBytes
        self.requiresHTTPS = requiresHTTPS
        self.resolveHost = resolveHost
    }

    func load(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if cancellationRequested || Task.isCancelled {
                    cancellationRequested = true
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.continuation = continuation
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                let task = session.dataTask(with: request)
                self.session = session
                self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: {
            cancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            finish(.failure(IOSSearchExecutorError.invalidHTTPResponse))
            return
        }
        if response.expectedContentLength > Int64(maximumResponseBytes) {
            completionHandler(.cancel)
            finish(.failure(IOSSearchExecutorError.responseTooLarge(maximumResponseBytes)))
            return
        }
        self.response = http
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard data.count <= maximumResponseBytes - self.data.count else {
            dataTask.cancel()
            finish(.failure(IOSSearchExecutorError.responseTooLarge(maximumResponseBytes)))
            return
        }
        self.data.append(data)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        do {
            guard let url = request.url else { throw IOSSearchExecutorError.invalidURL }
            let validated = try IOSSearchExecutor.allowedPublicHTTPURL(from: url.absoluteString)
            if requiresHTTPS, validated.scheme?.lowercased() != "https" {
                throw IOSSearchExecutorError.disallowedURL("HTTPS redirects may not downgrade to HTTP")
            }
            guard let host = validated.host else { throw IOSSearchExecutorError.invalidURL }
            let addresses = try resolveHost(host)
            guard !addresses.isEmpty, addresses.allSatisfy(IOSSearchExecutor.publicHostAllowed) else {
                throw IOSSearchExecutorError.disallowedURL("host resolves to a non-public address")
            }
            completionHandler(request)
        } catch {
            completionHandler(nil)
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            finish(.failure(error))
            return
        }
        guard let response else {
            finish(.failure(IOSSearchExecutorError.invalidHTTPResponse))
            return
        }
        finish(.success((response, data)))
    }

    private func finish(_ result: Result<(HTTPURLResponse, Data), Error>) {
        lock.lock()
        guard let continuation else {
            lock.unlock()
            return
        }
        self.continuation = nil
        let session = self.session
        self.session = nil
        self.task = nil
        lock.unlock()

        switch result {
        case .success:
            session?.finishTasksAndInvalidate()
        case .failure:
            session?.invalidateAndCancel()
        }
        continuation.resume(with: result)
    }

    private func cancel() {
        lock.lock()
        cancellationRequested = true
        let task = self.task
        let hasContinuation = continuation != nil
        lock.unlock()
        task?.cancel()
        if hasContinuation {
            finish(.failure(CancellationError()))
        }
    }
}

enum IOSSearchExecutorError: LocalizedError, Equatable {
    case missingQuery
    case missingURL
    case invalidURL
    case disallowedURL(String)
    case unsupportedTool(String)
    case unsupportedProvider(String)
    case missingAPIKey(String)
    case unsupportedContentType(String)
    case invalidHTTPResponse
    case httpStatus(String, Int)
    case emptyResponse(String)
    case responseTooLarge(Int)

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
        case .missingAPIKey(let provider):
            return "\(provider) requires an API key before it can search."
        case .unsupportedContentType(let contentType):
            return "Unsupported content type for scraping: \(contentType)."
        case .invalidHTTPResponse:
            return "Search transport returned a non-HTTP response."
        case .httpStatus(let provider, let status):
            return "\(provider) failed with HTTP status \(status)."
        case .emptyResponse(let provider):
            return "\(provider) returned no parseable content."
        case .responseTooLarge(let limit):
            return "Response exceeds the \(limit)-byte limit."
        }
    }
}

struct IOSSearchExecutor {
    static let supportedToolNames: Set<String> = ["search_web", "scrape_web"]

    @MainActor
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

    @MainActor
    static func execute(
        toolName: String,
        toolInput: String,
        maxResults defaultMaxResults: Int = 5,
        settings: Settings? = nil,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> String {
        switch toolName {
        case "search_web":
            let execution = try await searchResults(
                toolInput: toolInput,
                maxResults: defaultMaxResults,
                settings: settings,
                transport: transport
            )
            return format(query: execution.request.query, results: execution.results, selection: execution.selection)
        case "scrape_web":
            let request = try scrapeRequest(from: toolInput)
            return try await scrapeWeb(request: request, transport: transport)
        default:
            throw IOSSearchExecutorError.unsupportedTool(toolName)
        }
    }

    @MainActor
    static func searchResults(
        toolInput: String,
        maxResults defaultMaxResults: Int = 5,
        settings: Settings? = nil,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> IOSSearchExecution {
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
        case .tavilyAPI:
            guard let service = searchService(settings: settings, selection: selection) as? SearchServiceOptions.TavilyOptions else {
                throw IOSSearchExecutorError.unsupportedProvider("Selected Tavily service is missing from settings.")
            }
            results = try await searchTavily(request: request, service: service, transport: transport)
        case .exaAPI:
            guard let service = searchService(settings: settings, selection: selection) as? SearchServiceOptions.ExaOptions else {
                throw IOSSearchExecutorError.unsupportedProvider("Selected Exa service is missing from settings.")
            }
            results = try await searchExa(request: request, service: service, transport: transport)
        case .zhipuAPI:
            guard let service = searchService(settings: settings, selection: selection) as? SearchServiceOptions.ZhipuOptions else {
                throw IOSSearchExecutorError.unsupportedProvider("Selected Zhipu service is missing from settings.")
            }
            results = try await searchZhipu(request: request, service: service, transport: transport)
        case .braveAPI:
            guard let service = searchService(settings: settings, selection: selection) as? SearchServiceOptions.BraveOptions else {
                throw IOSSearchExecutorError.unsupportedProvider("Selected Brave service is missing from settings.")
            }
            results = try await searchBrave(request: request, service: service, transport: transport)
        case .serperAPI:
            guard let service = searchService(settings: settings, selection: selection) as? SearchServiceOptions.SerperOptions else {
                throw IOSSearchExecutorError.unsupportedProvider("Selected Serper service is missing from settings.")
            }
            results = try await searchSerper(request: request, service: service, transport: transport)
        case .serpAPI:
            guard let service = searchService(settings: settings, selection: selection) as? SearchServiceOptions.SerpApiOptions else {
                throw IOSSearchExecutorError.unsupportedProvider("Selected SerpAPI service is missing from settings.")
            }
            results = try await searchSerpAPI(request: request, service: service, transport: transport)
        case .jinaAPI:
            guard let service = searchService(settings: settings, selection: selection) as? SearchServiceOptions.JinaOptions else {
                throw IOSSearchExecutorError.unsupportedProvider("Selected Jina service is missing from settings.")
            }
            results = try await searchJina(request: request, service: service, transport: transport)
        case .unsupported:
            throw IOSSearchExecutorError.unsupportedProvider(
                selection.fallbackReason ?? "\(selection.providerName) is not implemented on iOS search."
            )
        }
        return IOSSearchExecution(request: request, selection: selection, results: results)
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
                if descriptor.route != .unsupported, hasRequiredCredential(selectedService) {
                    return IOSSearchProviderSelection(
                        route: descriptor.route,
                        providerName: descriptor.name,
                        providerType: descriptor.type,
                        serviceId: selectedId,
                        fallbackReason: nil
                    )
                }
                if descriptor.route != .unsupported, !hasRequiredCredential(selectedService) {
                    if let fallback = executableConfiguredFallback(
                        services: services,
                        enabledIds: enabledIds,
                        excluding: selectedId,
                        reason: "Selected provider \(descriptor.name) is enabled but has no API key."
                    ) {
                        return fallback
                    }
                    return builtInFallback(
                        settings: settings,
                        reason: "Selected provider \(descriptor.name) is enabled but has no API key."
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

    @MainActor
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
        request.timeoutInterval = 8

        let (response, data) = try await transport.send(request)
        guard (200...299).contains(response.statusCode) else {
            throw IOSSearchExecutorError.httpStatus("DuckDuckGo Lite", response.statusCode)
        }

        let html = String(decoding: data, as: UTF8.self)
        let results = parseDuckDuckGoLite(html: html, maxResults: maxResults)
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("DuckDuckGo Lite") }
        return results
    }

    @MainActor
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
        request.timeoutInterval = 8

        let (response, data) = try await transport.send(request)
        guard (200...299).contains(response.statusCode) else {
            throw IOSSearchExecutorError.httpStatus("Bing HTML", response.statusCode)
        }

        let html = String(decoding: data, as: UTF8.self)
        let results = parseBingHTML(html: html, maxResults: maxResults)
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Bing HTML") }
        return results
    }

    @MainActor
    static func searchTavily(
        request: IOSSearchRequest,
        service: SearchServiceOptions.TavilyOptions,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let apiKey = try requiredAPIKey(service.apiKey, provider: "Tavily")
        let topic = searchTopic(from: request.query)
        let httpRequest = try jsonPOST(
            url: URL(string: "https://api.tavily.com/search")!,
            body: [
                "query": request.query,
                "max_results": request.maxResults,
                "search_depth": service.depth.isEmpty ? "advanced" : service.depth,
                "topic": topic,
                "include_answer": "advanced"
            ],
            headers: ["Authorization": "Bearer \(apiKey)"]
        )
        let object = try await jsonResponse(httpRequest, provider: "Tavily", transport: transport)
        let results = array(object["results"]).compactMap {
            IOSSearchResult(
                title: string($0["title"]),
                url: string($0["url"]),
                snippet: string($0["content"])
            )
        }
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Tavily") }
        return Array(results.prefix(request.maxResults))
    }

    @MainActor
    static func searchExa(
        request: IOSSearchRequest,
        service: SearchServiceOptions.ExaOptions,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let apiKey = try requiredAPIKey(service.apiKey, provider: "Exa")
        let httpRequest = try jsonPOST(
            url: URL(string: "https://api.exa.ai/search")!,
            body: [
                "query": request.query,
                "numResults": request.maxResults,
                "type": "auto",
                // highlights（相关摘录）而非 text（整页全文）——text:true 会把整个网页
                // 正文塞进每条结果，单页可达 1MB+，直接撞爆上下文预算（真机事故）。
                "contents": ["highlights": true]
            ],
            headers: ["Authorization": "Bearer \(apiKey)"]
        )
        let object = try await jsonResponse(httpRequest, provider: "Exa", transport: transport)
        let results = array(object["results"]).compactMap { item -> IOSSearchResult? in
            // 优先 highlights 摘录数组；旧形态/异常响应回退 text 字段（漏斗上限兜底）。
            let highlights = (item["highlights"] as? [Any] ?? [])
                .map { string($0) }
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            let snippet = highlights.isEmpty ? string(item["text"]) : highlights
            return IOSSearchResult(
                title: string(item["title"]),
                url: string(item["url"]),
                snippet: snippet
            )
        }
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Exa") }
        return Array(results.prefix(request.maxResults))
    }

    @MainActor
    static func searchZhipu(
        request: IOSSearchRequest,
        service: SearchServiceOptions.ZhipuOptions,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let apiKey = try requiredAPIKey(service.apiKey, provider: "Zhipu")
        let httpRequest = try jsonPOST(
            url: URL(string: "https://open.bigmodel.cn/api/paas/v4/web_search")!,
            body: [
                "search_query": request.query,
                "search_engine": "search_std",
                "count": request.maxResults
            ],
            headers: ["Authorization": "Bearer \(apiKey)"]
        )
        let object = try await jsonResponse(httpRequest, provider: "Zhipu", transport: transport)
        let results = array(object["search_result"]).compactMap {
            IOSSearchResult(
                title: string($0["title"]),
                url: string($0["link"]),
                snippet: string($0["content"])
            )
        }
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Zhipu") }
        return Array(results.prefix(request.maxResults))
    }

    @MainActor
    static func searchBrave(
        request: IOSSearchRequest,
        service: SearchServiceOptions.BraveOptions,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let apiKey = try requiredAPIKey(service.apiKey, provider: "Brave")
        var components = URLComponents(string: "https://api.search.brave.com/res/v1/web/search")
        components?.queryItems = [
            URLQueryItem(name: "q", value: request.query),
            URLQueryItem(name: "count", value: "\(request.maxResults)")
        ]
        guard let url = components?.url else { throw IOSSearchExecutorError.invalidURL }
        var httpRequest = URLRequest(url: url)
        httpRequest.httpMethod = "GET"
        httpRequest.timeoutInterval = 8
        httpRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        httpRequest.setValue(apiKey, forHTTPHeaderField: "X-Subscription-Token")

        let object = try await jsonResponse(httpRequest, provider: "Brave", transport: transport)
        let web = object["web"] as? [String: Any]
        let results = array(web?["results"]).compactMap {
            IOSSearchResult(
                title: string($0["title"]),
                url: string($0["url"]),
                snippet: string($0["description"]),
                images: braveImageURLs($0)
            )
        }
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Brave") }
        return Array(results.prefix(request.maxResults))
    }

    /// Brave web results carry a `thumbnail` object: `original` is the true source
    /// image URL; `src` is Brave's proxied thumbnail (imgs.search.brave.com/<sig>/…),
    /// which is HOT-LINK PROTECTED — a fetch without a brave.com `Referer` gets a 403
    /// and the image renders blank. So prefer `original`, fall back to `src` (parity
    /// with Android's BraveSearchService). Only keep http(s) URLs.
    private static func braveImageURLs(_ item: [String: Any]) -> [String] {
        guard let thumbnail = item["thumbnail"] as? [String: Any] else { return [] }
        var urls: [String] = []
        let original = string(thumbnail["original"])
        let src = string(thumbnail["src"])
        if !original.isEmpty { urls.append(original) }
        if !src.isEmpty, src != original { urls.append(src) }
        return urls.filter { $0.hasPrefix("http") }
    }

    @MainActor
    static func searchSerper(
        request: IOSSearchRequest,
        service: SearchServiceOptions.SerperOptions,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let apiKey = try requiredAPIKey(service.apiKey, provider: "Serper")
        let endpoint = searchTopic(from: request.query) == "news" ? "news" : "search"
        let httpRequest = try jsonPOST(
            url: URL(string: "https://google.serper.dev/\(endpoint)")!,
            body: ["q": request.query, "num": min(request.maxResults, 20)],
            headers: ["X-API-KEY": apiKey]
        )
        let object = try await jsonResponse(httpRequest, provider: "Serper", transport: transport)
        let rawItems = array(object["news"]).isEmpty ? array(object["organic"]) : array(object["news"])
        let results = rawItems.compactMap {
            IOSSearchResult(
                title: string($0["title"]),
                url: string($0["link"]),
                snippet: string($0["snippet"])
            )
        }
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Serper") }
        return Array(results.prefix(request.maxResults))
    }

    @MainActor
    static func searchSerpAPI(
        request: IOSSearchRequest,
        service: SearchServiceOptions.SerpApiOptions,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let apiKey = try requiredAPIKey(service.apiKey, provider: "SerpAPI")
        var items = [
            URLQueryItem(name: "engine", value: "google"),
            URLQueryItem(name: "q", value: request.query),
            URLQueryItem(name: "api_key", value: apiKey),
            URLQueryItem(name: "num", value: "\(min(request.maxResults, 20))")
        ]
        if searchTopic(from: request.query) == "news" {
            items.append(URLQueryItem(name: "tbm", value: "nws"))
        }
        var components = URLComponents(string: "https://serpapi.com/search.json")
        components?.queryItems = items
        guard let url = components?.url else { throw IOSSearchExecutorError.invalidURL }
        var httpRequest = URLRequest(url: url)
        httpRequest.httpMethod = "GET"
        httpRequest.timeoutInterval = 8
        httpRequest.setValue("application/json", forHTTPHeaderField: "Accept")

        let object = try await jsonResponse(httpRequest, provider: "SerpAPI", transport: transport)
        let rawItems = array(object["news_results"]).isEmpty ? array(object["organic_results"]) : array(object["news_results"])
        let results = rawItems.compactMap {
            IOSSearchResult(
                title: string($0["title"]),
                url: string($0["link"]),
                snippet: string($0["snippet"])
            )
        }
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("SerpAPI") }
        return Array(results.prefix(request.maxResults))
    }

    @MainActor
    static func searchJina(
        request: IOSSearchRequest,
        service: SearchServiceOptions.JinaOptions,
        transport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport()
    ) async throws -> [IOSSearchResult] {
        let urlString = service.searchUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "https://s.jina.ai/"
            : service.searchUrl
        guard let url = URL(string: urlString) else { throw IOSSearchExecutorError.invalidURL }
        var headers = ["Accept": "application/json"]
        let apiKey = service.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !apiKey.isEmpty {
            headers["Authorization"] = "Bearer \(apiKey)"
        }
        let httpRequest = try jsonPOST(
            url: url,
            body: ["q": request.query],
            headers: headers
        )
        let object = try await jsonResponse(httpRequest, provider: "Jina", transport: transport)
        let results = array(object["data"]).compactMap {
            IOSSearchResult(
                title: string($0["title"]),
                url: string($0["url"]),
                snippet: string($0["description"]).isEmpty ? string($0["content"]) : string($0["description"])
            )
        }
        guard !results.isEmpty else { throw IOSSearchExecutorError.emptyResponse("Jina") }
        return Array(results.prefix(request.maxResults))
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

    @MainActor
    private static func scrapeWeb(
        request: IOSScrapeRequest,
        transport: any IOSSearchHTTPTransport
    ) async throws -> String {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = "GET"
        urlRequest.setValue("Mozilla/5.0 AmberAgent-iOS Scrape", forHTTPHeaderField: "User-Agent")
        urlRequest.setValue("text/html,text/plain,application/xhtml+xml,application/json;q=0.8,*/*;q=0.3", forHTTPHeaderField: "Accept")
        urlRequest.timeoutInterval = 10

        let (response, data) = try await transport.sendPublic(
            urlRequest,
            maximumResponseBytes: 512 * 1_024
        )
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

        var output: [String: Any] = [
            "status": "ok",
            "url": request.url.absoluteString,
            "title": title,
            "content": content,
            "chars": content.count
        ]
        // og:image / twitter:image candidates — used to source the Deep Read editorial
        // hero for hot-topic articles (parity with Android's fetchPageImages).
        let heroImages = extractHeroImageURLs(from: raw)
        if !heroImages.isEmpty { output["images"] = heroImages }
        return cappedScrapeOutput(output)
    }

    /// scrape_web 的 JSON 输出保形截断：总长超上限时收缩 content 字段（含截断
    /// 标记），保持合法 JSON 可解析——status/ok 等判定键不被破坏。content 压到
    /// 地板仍超限（巨型 title 等）时再收缩 title；仍超限则原样返回（保 JSON
    /// 可解析优先，宁可略微超窗）。
    private static func cappedScrapeOutput(_ output: [String: Any]) -> String {
        var candidate = output
        var string = jsonString(candidate)
        guard string.count > IOSToolOutputLimits.maxOutputChars,
              let content = candidate["content"] as? String, !content.isEmpty else {
            return string
        }
        let originalChars = content.count
        var contentLength = content.count
        var iterations = 0
        while string.count > IOSToolOutputLimits.maxOutputChars, contentLength > 256, iterations < 24 {
            iterations += 1
            contentLength = max(contentLength / 2, 256)
            let truncated = String(content.prefix(contentLength))
            candidate["content"] = truncated
                + IOSToolOutputLimits.truncationMarker(droppedChars: max(originalChars - truncated.count, 0))
            candidate["chars"] = (candidate["content"] as? String)?.count ?? truncated.count
            candidate["truncated"] = true
            string = jsonString(candidate)
        }
        if string.count > IOSToolOutputLimits.maxOutputChars,
           let title = candidate["title"] as? String, title.count > 200 {
            candidate["title"] = String(title.prefix(200))
            candidate["truncated"] = true
            string = jsonString(candidate)
        }
        return string
    }

    /// Pull og:image / twitter:image URLs out of page HTML (both meta-attribute
    /// orders), http(s) only, deduped, capped — the hero-image candidates.
    static func extractHeroImageURLs(from raw: String) -> [String] {
        let patterns = [
            #"<meta[^>]+?(?:property|name)\s*=\s*["'](?:og:image(?::secure_url|:url)?|twitter:image(?::src)?)["'][^>]+?content\s*=\s*["']([^"'>]+)["']"#,
            #"<meta[^>]+?content\s*=\s*["']([^"'>]+)["'][^>]+?(?:property|name)\s*=\s*["'](?:og:image(?::secure_url|:url)?|twitter:image(?::src)?)["']"#,
        ]
        var urls: [String] = []
        var seen = Set<String>()
        let ns = raw as NSString
        let range = NSRange(location: 0, length: ns.length)
        for pattern in patterns {
            guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { continue }
            for match in re.matches(in: raw, options: [], range: range) where match.numberOfRanges > 1 {
                let r = match.range(at: 1)
                guard r.location != NSNotFound else { continue }
                let url = ns.substring(with: r)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .replacingOccurrences(of: "&amp;", with: "&")
                guard url.hasPrefix("http"), !seen.contains(url) else { continue }
                seen.insert(url)
                urls.append(url)
            }
        }
        return Array(urls.prefix(5))
    }

    static func format(
        query: String,
        results: [IOSSearchResult],
        selection: IOSSearchProviderSelection
    ) -> String {
        var lines = [
            "搜索结果：\(query)",
            "来源：\(selection.providerName)",
            "服务类型：\(selection.providerType)"
        ]
        if let fallbackReason = selection.fallbackReason {
            lines.append("已自动改用：\(fallbackReason)")
        }
        for (index, result) in results.enumerated() {
            lines.append("\n[\(index + 1)] \(result.title)")
            lines.append(result.url)
            if !result.snippet.isEmpty {
                // 单条 snippet 上限：Exa 等提供商会把全文灌进 text/content 字段。
                lines.append(String(result.snippet.prefix(IOSToolOutputLimits.maxSnippetChars)))
            }
        }
        var output = lines.joined(separator: "\n")
        if output.count > IOSToolOutputLimits.maxOutputChars {
            let dropped = output.count - IOSToolOutputLimits.maxOutputChars
            output = String(output.prefix(IOSToolOutputLimits.maxOutputChars))
                + IOSToolOutputLimits.truncationMarker(droppedChars: dropped)
        }
        return output
    }

    private static func searchService(settings: Settings?, selection: IOSSearchProviderSelection) -> SearchServiceOptions? {
        guard let serviceId = selection.serviceId else { return nil }
        return settings?.searchServices.first { uuidString($0.id) == serviceId }
    }

    private static func hasRequiredCredential(_ service: SearchServiceOptions) -> Bool {
        let descriptor = providerDescriptor(for: service)
        guard descriptor.requiresAPIKey else { return true }
        return !apiKey(for: service).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private static func apiKey(for service: SearchServiceOptions) -> String {
        switch service {
        case let service as SearchServiceOptions.TavilyOptions:
            service.apiKey
        case let service as SearchServiceOptions.ExaOptions:
            service.apiKey
        case let service as SearchServiceOptions.ZhipuOptions:
            service.apiKey
        case let service as SearchServiceOptions.BraveOptions:
            service.apiKey
        case let service as SearchServiceOptions.SerperOptions:
            service.apiKey
        case let service as SearchServiceOptions.SerpApiOptions:
            service.apiKey
        case let service as SearchServiceOptions.JinaOptions:
            service.apiKey
        default:
            ""
        }
    }

    private static func requiredAPIKey(_ value: String, provider: String) throws -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        // 旧版脱敏器曾把搜索服务的 apiKey 误写成掩码(见 IOSCredentialRedactor 修复)。
        // 若读到残留掩码,视为未配置 —— 抛明确的"缺 key"错误,而不是把掩码当 token
        // 发出去被服务端判 SUBSCRIPTION_TOKEN_INVALID(那会表现为搜索静默失败)。
        guard !trimmed.isEmpty, trimmed != IOSCredentialRedactor.mask else {
            throw IOSSearchExecutorError.missingAPIKey(provider)
        }
        return trimmed
    }

    private static func searchTopic(from query: String) -> String {
        let lower = query.lowercased()
        if lower.contains("news") || lower.contains("新闻") || lower.contains("latest") || lower.contains("最近") {
            return "news"
        }
        return "general"
    }

    private static func jsonPOST(url: URL, body: [String: Any], headers: [String: String] = [:]) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 8
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        return request
    }

    @MainActor
    private static func jsonResponse(
        _ request: URLRequest,
        provider: String,
        transport: any IOSSearchHTTPTransport
    ) async throws -> [String: Any] {
        let (response, data) = try await transport.send(request)
        guard (200...299).contains(response.statusCode) else {
#if DEBUG
            NSLog("[AmberSearch] \(provider) HTTP \(response.statusCode) body=\(String(data: data, encoding: .utf8)?.prefix(400) ?? "")")
#endif
            throw IOSSearchExecutorError.httpStatus(provider, response.statusCode)
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw IOSSearchExecutorError.emptyResponse(provider)
        }
        return object
    }

    private static func array(_ value: Any?) -> [[String: Any]] {
        value as? [[String: Any]] ?? []
    }

    private static func string(_ value: Any?) -> String {
        if let value = value as? String { return value.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
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
            providerName: "没有可用搜索服务",
            providerType: "none",
            serviceId: nil,
            fallbackReason: "\(reason) 当前没有可用的内置搜索服务。"
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
            guard descriptor.route != .unsupported, hasRequiredCredential(service) else { continue }
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
            return IOSSearchProviderDescriptor(name: "Bing HTML", type: "bing_local", route: .bingHTML, requiresAPIKey: false)
        case is SearchServiceOptions.TavilyOptions:
            return IOSSearchProviderDescriptor(name: "Tavily", type: "tavily", route: .tavilyAPI, requiresAPIKey: true)
        case is SearchServiceOptions.ExaOptions:
            return IOSSearchProviderDescriptor(name: "Exa", type: "exa", route: .exaAPI, requiresAPIKey: true)
        case is SearchServiceOptions.ZhipuOptions:
            return IOSSearchProviderDescriptor(name: "Zhipu", type: "zhipu", route: .zhipuAPI, requiresAPIKey: true)
        case is SearchServiceOptions.SearXNGOptions:
            return IOSSearchProviderDescriptor(name: "SearXNG", type: "searxng", route: .unsupported, requiresAPIKey: false)
        case is SearchServiceOptions.LinkUpOptions:
            return IOSSearchProviderDescriptor(name: "LinkUp", type: "linkup", route: .unsupported, requiresAPIKey: true)
        case is SearchServiceOptions.BraveOptions:
            return IOSSearchProviderDescriptor(name: "Brave", type: "brave", route: .braveAPI, requiresAPIKey: true)
        case is SearchServiceOptions.SerperOptions:
            return IOSSearchProviderDescriptor(name: "Serper", type: "serper", route: .serperAPI, requiresAPIKey: true)
        case is SearchServiceOptions.SerpApiOptions:
            return IOSSearchProviderDescriptor(name: "SerpAPI", type: "serpapi", route: .serpAPI, requiresAPIKey: true)
        case is SearchServiceOptions.MetasoOptions:
            return IOSSearchProviderDescriptor(name: "Metaso", type: "metaso", route: .unsupported, requiresAPIKey: true)
        case is SearchServiceOptions.OllamaOptions:
            return IOSSearchProviderDescriptor(name: "Ollama", type: "ollama", route: .unsupported, requiresAPIKey: true)
        case is SearchServiceOptions.PerplexityOptions:
            return IOSSearchProviderDescriptor(name: "Perplexity", type: "perplexity", route: .unsupported, requiresAPIKey: true)
        case is SearchServiceOptions.FirecrawlOptions:
            return IOSSearchProviderDescriptor(name: "Firecrawl", type: "firecrawl", route: .unsupported, requiresAPIKey: true)
        case is SearchServiceOptions.JinaOptions:
            return IOSSearchProviderDescriptor(name: "Jina", type: "jina", route: .jinaAPI, requiresAPIKey: false)
        case is SearchServiceOptions.BochaOptions:
            return IOSSearchProviderDescriptor(name: "Bocha", type: "bocha", route: .unsupported, requiresAPIKey: true)
        case is SearchServiceOptions.AmberAgentSearchOptions:
            return IOSSearchProviderDescriptor(name: "AmberAgent", type: "amber_agent", route: .unsupported, requiresAPIKey: true)
        case is SearchServiceOptions.GrokOptions:
            return IOSSearchProviderDescriptor(name: "Grok", type: "grok", route: .unsupported, requiresAPIKey: true)
        default:
            return IOSSearchProviderDescriptor(
                name: "Search provider",
                type: String(describing: type(of: service)),
                route: .unsupported,
                requiresAPIKey: false
            )
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

    static func allowedPublicHTTPURL(from rawURL: String) throws -> URL {
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

    static func publicHostAllowed(_ host: String) -> Bool {
        let lower = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".[]"))
        if lower == "localhost" || lower == "0.0.0.0" || lower.hasSuffix(".local") {
            return false
        }
        var ipv4 = in_addr()
        if inet_aton(lower, &ipv4) == 1 {
            return publicIPv4Allowed(UInt32(bigEndian: ipv4.s_addr))
        }
        var ipv6 = in6_addr()
        if inet_pton(AF_INET6, lower, &ipv6) == 1 {
            let bytes = withUnsafeBytes(of: &ipv6) { Array($0) }
            if bytes.prefix(10).allSatisfy({ $0 == 0 }), bytes[10] == 0xff, bytes[11] == 0xff {
                let mapped = bytes[12...15].reduce(UInt32.zero) { ($0 << 8) | UInt32($1) }
                return publicIPv4Allowed(mapped)
            }
            // Only globally routable IPv6 unicast addresses are allowed. This
            // rejects unspecified/loopback, link-local, unique-local, multicast,
            // documentation, and transition-space addresses.
            return (bytes[0] & 0xe0) == 0x20 && !(bytes[0...3] == [0x20, 0x01, 0x0d, 0xb8])
        }
        return true
    }

    private static func publicIPv4Allowed(_ address: UInt32) -> Bool {
        let first = Int((address >> 24) & 0xff)
        let second = Int((address >> 16) & 0xff)
        let third = Int((address >> 8) & 0xff)
        if first == 0 || first == 10 || first == 127 { return false }
        if first == 100 && (64...127).contains(second) { return false }
        if first == 169 && second == 254 { return false }
        if first == 172 && (16...31).contains(second) { return false }
        if first == 192 && second == 168 { return false }
        if first == 192 && second == 0 && (third == 0 || third == 2) { return false }
        if first == 198 && (second == 18 || second == 19) { return false }
        if first == 198 && second == 51 && third == 100 { return false }
        if first == 203 && second == 0 && third == 113 { return false }
        return first < 224
    }

    static func resolveIPAddresses(_ host: String) throws -> [String] {
        var result: UnsafeMutablePointer<addrinfo>?
        let status = getaddrinfo(host, nil, nil, &result)
        guard status == 0, let first = result else {
            throw IOSSearchExecutorError.disallowedURL("host could not be resolved")
        }
        defer { freeaddrinfo(first) }

        var addresses: [String] = []
        var cursor: UnsafeMutablePointer<addrinfo>? = first
        while let info = cursor?.pointee {
            if info.ai_family == AF_INET || info.ai_family == AF_INET6,
               let address = info.ai_addr {
                var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(
                    address,
                    info.ai_addrlen,
                    &buffer,
                    socklen_t(buffer.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                ) == 0 {
                    addresses.append(String(cString: buffer))
                }
            }
            cursor = info.ai_next
        }
        return Array(Set(addresses))
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
    let requiresAPIKey: Bool
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
