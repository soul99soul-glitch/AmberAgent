import Foundation
import Shared

struct IOSMcpTool: Codable, Equatable, Identifiable {
    let name: String
    let description: String?
    let enabled: Bool
    /// Persisted raw JSON text of the tool's `inputSchema` from the MCP
    /// `tools/list` result, or nil for legacy persisted data / servers that
    /// omit a schema. The text is always a complete JSON value.
    let inputSchema: String?

    var id: String { name }

    init(name: String, description: String?, enabled: Bool = true, inputSchema: String? = nil) {
        self.name = name
        self.description = description
        self.enabled = enabled
        self.inputSchema = inputSchema
    }

    func withEnabled(_ enabled: Bool) -> IOSMcpTool {
        IOSMcpTool(name: name, description: description, enabled: enabled, inputSchema: inputSchema)
    }
}

enum IOSMcpServerConfig: Equatable, Identifiable {
    case streamableHTTP(name: String, url: String, headers: [String: String] = [:], enabled: Bool = true, tools: [IOSMcpTool] = [])
    case sse(name: String, url: String, headers: [String: String] = [:], enabled: Bool = true, tools: [IOSMcpTool] = [])

    var id: String { name }

    var name: String {
        switch self {
        case .streamableHTTP(let name, _, _, _, _), .sse(let name, _, _, _, _): name
        }
    }

    var url: String {
        switch self {
        case .streamableHTTP(_, let url, _, _, _), .sse(_, let url, _, _, _): url
        }
    }

    var headers: [String: String] {
        switch self {
        case .streamableHTTP(_, _, let headers, _, _), .sse(_, _, let headers, _, _): headers
        }
    }

    var enabled: Bool {
        switch self {
        case .streamableHTTP(_, _, _, let enabled, _), .sse(_, _, _, let enabled, _): enabled
        }
    }

    var tools: [IOSMcpTool] {
        switch self {
        case .streamableHTTP(_, _, _, _, let tools), .sse(_, _, _, _, let tools): tools
        }
    }

    var transportTitle: String {
        switch self {
        case .streamableHTTP: "Streamable HTTP"
        case .sse: "SSE"
        }
    }

    var transportKey: String {
        switch self {
        case .streamableHTTP: "streamable_http"
        case .sse: "sse"
        }
    }

    func withEnabled(_ enabled: Bool) -> IOSMcpServerConfig {
        switch self {
        case .streamableHTTP(let name, let url, let headers, _, let tools):
            return .streamableHTTP(name: name, url: url, headers: headers, enabled: enabled, tools: tools)
        case .sse(let name, let url, let headers, _, let tools):
            return .sse(name: name, url: url, headers: headers, enabled: enabled, tools: tools)
        }
    }

    func withTools(_ tools: [IOSMcpTool]) -> IOSMcpServerConfig {
        switch self {
        case .streamableHTTP(let name, let url, let headers, let enabled, _):
            return .streamableHTTP(name: name, url: url, headers: headers, enabled: enabled, tools: tools)
        case .sse(let name, let url, let headers, let enabled, _):
            return .sse(name: name, url: url, headers: headers, enabled: enabled, tools: tools)
        }
    }
}

struct IOSMcpStoredServer: Codable, Equatable {
    let name: String
    let url: String
    let transport: String
    var headers: [String: String]
    let enabled: Bool
    let tools: [IOSMcpTool]

    init(_ config: IOSMcpServerConfig) {
        self.name = config.name
        self.url = config.url
        self.transport = config.transportKey
        self.headers = config.headers
        self.enabled = config.enabled
        self.tools = config.tools
    }

    var config: IOSMcpServerConfig {
        if transport == "sse" {
            return .sse(name: name, url: url, headers: headers, enabled: enabled, tools: tools)
        }
        return .streamableHTTP(name: name, url: url, headers: headers, enabled: enabled, tools: tools)
    }

    /// Scheme B: mask values of credential-bearing header names before this
    /// stored server is persisted to UserDefaults. (parity with
    /// IOSCredentialRedactor.)
    mutating func redactSensitiveHeaders() {
        var redacted: [String: String] = [:]
        for (name, value) in headers {
            redacted[name] = IOSCredentialRedactor.redactHeaderValue(headerName: name, value: value)
        }
        headers = redacted
    }

    enum CodingKeys: String, CodingKey {
        case name
        case url
        case transport
        case headers
        case enabled
        case tools
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        url = try container.decode(String.self, forKey: .url)
        transport = try container.decodeIfPresent(String.self, forKey: .transport) ?? "streamable_http"
        headers = try container.decodeIfPresent([String: String].self, forKey: .headers) ?? [:]
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        tools = try container.decodeIfPresent([IOSMcpTool].self, forKey: .tools) ?? []
    }
}

enum IOSMcpConnectionStatus: Equatable {
    case idle
    case connecting
    case connected
    case reconnecting
    case error(String)

    var title: String {
        switch self {
        case .idle: "未连接"
        case .connecting: "连接中"
        case .connected: "已连接"
        case .reconnecting: "重连中"
        case .error: "连接失败"
        }
    }
}

enum IOSMcpClientError: LocalizedError, Equatable {
    case invalidURL(String)
    case unsafeEndpoint
    case httpStatus(Int)
    case invalidResponse
    case rpcError(String)
    case requestTimedOut(String)
    case unsupportedContent
    case serverNotFound(String)
    case serverDisabled(String)
    case toolNotFound(server: String, tool: String)
    case toolDisabled(server: String, tool: String)
    case notConnected(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL: "Invalid MCP server URL."
        case .unsafeEndpoint: "MCP server advertised an unsafe endpoint."
        case .httpStatus(let status): "MCP server returned HTTP status \(status)."
        case .invalidResponse: "MCP server returned an invalid JSON-RPC response."
        case .rpcError(let message): message
        case .requestTimedOut(let message): message
        case .unsupportedContent: "MCP tool result did not contain supported text content."
        case .serverNotFound(let server): "MCP server is not configured: \(server)"
        case .serverDisabled(let server): "MCP server is disabled: \(server)"
        case .toolNotFound(let server, let tool): "MCP tool not found: \(server)/\(tool)"
        case .toolDisabled(let server, let tool): "MCP tool is disabled: \(server)/\(tool)"
        case .notConnected(let server): "MCP server is not connected: \(server)"
        }
    }
}

@MainActor
protocol IOSMcpHTTPTransport {
    func sendJSONRPC(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> [String: Any]
    func sendJSONRPCNotification(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws
    func disconnect(config: IOSMcpServerConfig)
}

extension IOSMcpHTTPTransport {
    func sendJSONRPCNotification(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws {
        _ = try? await sendJSONRPC(payload, to: config)
    }

    func disconnect(config: IOSMcpServerConfig) {}
}

@MainActor
final class URLSessionMcpHTTPTransport: IOSMcpHTTPTransport {
    var session: URLSession = .shared
    private var sseSessions: [String: LegacySSEMcpSession] = [:]

    init(session: URLSession = .shared) {
        self.session = session
    }

    func sendJSONRPC(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> [String: Any] {
        if case .sse = config {
            return try await sseSession(for: config).sendJSONRPC(payload)
        }

        let data = try await send(payload, to: config)
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return object
        }

        if let eventObject = Self.firstServerSentEventJSON(from: data) {
            return eventObject
        }

        throw IOSMcpClientError.invalidResponse
    }

    func sendJSONRPCNotification(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws {
        if case .sse = config {
            try await sseSession(for: config).sendNotification(payload)
            return
        }

        let data = try await send(payload, to: config)
        guard !data.isEmpty else { return }
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let error = object["error"] as? [String: Any] {
            throw IOSMcpClientError.rpcError(error["message"] as? String ?? "MCP JSON-RPC error")
        }
    }

    func disconnect(config: IOSMcpServerConfig) {
        sseSessions[Self.sseSessionKey(config: config)]?.close()
        sseSessions.removeValue(forKey: Self.sseSessionKey(config: config))
    }

    private func send(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> Data {
        guard let url = URL(string: config.url) else {
            throw IOSMcpClientError.invalidURL(config.url)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        for (name, value) in config.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await session.data(for: request)
        if let httpResponse = response as? HTTPURLResponse,
           !(200...299).contains(httpResponse.statusCode) {
            throw IOSMcpClientError.httpStatus(httpResponse.statusCode)
        }

        return data
    }

    private func sseSession(for config: IOSMcpServerConfig) async throws -> LegacySSEMcpSession {
        let key = Self.sseSessionKey(config: config)
        if let existing = sseSessions[key] {
            return existing
        }
        let sseSession = LegacySSEMcpSession(config: config, session: session)
        try await sseSession.connect()
        sseSessions[key] = sseSession
        return sseSession
    }

    private static func sseSessionKey(config: IOSMcpServerConfig) -> String {
        let headerKey = config.headers
            .sorted { $0.key < $1.key }
            .map { "\($0.key)=\($0.value)" }
            .joined(separator: "&")
        return "\(config.transportKey)|\(config.name)|\(config.url)|\(headerKey)"
    }

    static func firstServerSentEventJSON(from data: Data) -> [String: Any]? {
        let text = String(decoding: data, as: UTF8.self)
        let blocks = text.components(separatedBy: "\n\n")
        for block in blocks {
            let dataLines = block
                .split(separator: "\n")
                .compactMap { line -> String? in
                    let trimmed = line.trimmingCharacters(in: .whitespaces)
                    guard trimmed.hasPrefix("data:") else { return nil }
                    return String(trimmed.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                }
            guard !dataLines.isEmpty else { continue }
            let jsonText = dataLines.joined(separator: "\n")
            guard let jsonData = jsonText.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
                continue
            }
            return object
        }
        return nil
    }
}

@MainActor
enum IOSMcpLegacyEndpointPolicy {
    static func validatedEndpoint(_ endpoint: String, relativeTo serverURLString: String) throws -> URL {
        guard let serverURL = URL(string: serverURLString),
              isAllowedTransportURL(serverURL),
              let endpointURL = URL(string: endpoint, relativeTo: serverURL)?.absoluteURL,
              isAllowedTransportURL(endpointURL),
              endpointURL.user == nil,
              endpointURL.password == nil,
              sameOrigin(serverURL, endpointURL) else {
            throw IOSMcpClientError.unsafeEndpoint
        }
        return endpointURL
    }

    private static func isAllowedTransportURL(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), url.host != nil else { return false }
        return scheme == "https" || scheme == "http"
    }

    private static func sameOrigin(_ lhs: URL, _ rhs: URL) -> Bool {
        lhs.scheme?.lowercased() == rhs.scheme?.lowercased()
            && lhs.host?.lowercased() == rhs.host?.lowercased()
            && effectivePort(lhs) == effectivePort(rhs)
    }

    private static func effectivePort(_ url: URL) -> Int? {
        if let port = url.port { return port }
        switch url.scheme?.lowercased() {
        case "https": return 443
        case "http": return 80
        default: return nil
        }
    }
}

@MainActor
private final class LegacySSEMcpSession {
    private let config: IOSMcpServerConfig
    private let session: URLSession
    private var lineIterator: AsyncLineSequence<URLSession.AsyncBytes>.AsyncIterator?
    private var endpointURL: URL?
    private var eventName: String?
    private var dataLines: [String] = []

    init(config: IOSMcpServerConfig, session: URLSession) {
        self.config = config
        self.session = session
    }

    func connect() async throws {
        guard endpointURL == nil else { return }
        guard let url = URL(string: config.url) else {
            throw IOSMcpClientError.invalidURL(config.url)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 30
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        for (name, value) in config.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let (bytes, response) = try await session.bytes(for: request)
        if let httpResponse = response as? HTTPURLResponse,
           !(200...299).contains(httpResponse.statusCode) {
            throw IOSMcpClientError.httpStatus(httpResponse.statusCode)
        }

        lineIterator = bytes.lines.makeAsyncIterator()
        endpointURL = try await readEndpoint()
    }

    func sendJSONRPC(_ payload: [String: Any]) async throws -> [String: Any] {
        try await connect()
        let requestId = Self.requestIdString(from: payload["id"])
        let immediateResponse = try await post(payload)
        if let object = Self.jsonObject(from: immediateResponse), Self.requestIdString(from: object["id"]) == requestId {
            return object
        }
        return try await readResponse(matching: requestId)
    }

    func sendNotification(_ payload: [String: Any]) async throws {
        try await connect()
        let data = try await post(payload)
        if let object = Self.jsonObject(from: data),
           let error = object["error"] as? [String: Any] {
            throw IOSMcpClientError.rpcError(error["message"] as? String ?? "MCP JSON-RPC error")
        }
    }

    func close() {
        lineIterator = nil
        endpointURL = nil
        eventName = nil
        dataLines = []
    }

    private func post(_ payload: [String: Any]) async throws -> Data {
        guard let endpointURL else {
            throw IOSMcpClientError.invalidResponse
        }

        var request = URLRequest(url: endpointURL)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        for (name, value) in config.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await session.data(for: request)
        if let httpResponse = response as? HTTPURLResponse,
           !(200...299).contains(httpResponse.statusCode) {
            throw IOSMcpClientError.httpStatus(httpResponse.statusCode)
        }
        return data
    }

    private func readEndpoint() async throws -> URL {
        while true {
            let event = try await readNextEvent()
            guard event.name == "endpoint" else { continue }
            let endpoint = event.data.trimmingCharacters(in: .whitespacesAndNewlines)
            return try IOSMcpLegacyEndpointPolicy.validatedEndpoint(endpoint, relativeTo: config.url)
        }
    }

    private func readResponse(matching requestId: String?) async throws -> [String: Any] {
        while true {
            let event = try await readNextEvent()
            guard event.name == nil || event.name == "message" else { continue }
            guard let object = Self.jsonObject(from: event.data) else { continue }
            guard Self.requestIdString(from: object["id"]) == requestId else { continue }
            return object
        }
    }

    private func readNextEvent() async throws -> SseEvent {
        guard var iterator = lineIterator else {
            throw IOSMcpClientError.invalidResponse
        }

        while let line = try await iterator.next() {
            lineIterator = iterator
            if line.isEmpty {
                if let event = flushEventIfReady() {
                    return event
                }
                continue
            }

            if line.hasPrefix(":") {
                continue
            } else if line.hasPrefix("event:") {
                eventName = Self.eventValue(from: line, prefix: "event:")
            } else if line.hasPrefix("data:") {
                dataLines.append(Self.eventValue(from: line, prefix: "data:"))
            }
        }

        lineIterator = iterator
        if let event = flushEventIfReady() {
            return event
        }
        throw IOSMcpClientError.invalidResponse
    }

    private func flushEventIfReady() -> SseEvent? {
        guard !dataLines.isEmpty else {
            eventName = nil
            return nil
        }
        let event = SseEvent(name: eventName, data: dataLines.joined(separator: "\n"))
        eventName = nil
        dataLines = []
        return event
    }

    private static func eventValue(from line: String, prefix: String) -> String {
        var value = String(line.dropFirst(prefix.count))
        if value.first == " " {
            value.removeFirst()
        }
        return value
    }

    private static func jsonObject(from data: Data) -> [String: Any]? {
        guard !data.isEmpty else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func jsonObject(from text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return jsonObject(from: data)
    }

    private static func requestIdString(from value: Any?) -> String? {
        if let string = value as? String { return string }
        if let int = value as? Int { return String(int) }
        if let number = value as? NSNumber { return number.stringValue }
        return nil
    }

    private struct SseEvent {
        let name: String?
        let data: String
    }
}

@MainActor
protocol IOSMcpClienting {
    func connect(config: IOSMcpServerConfig) async throws -> Bool
    func listTools() async throws -> [IOSMcpTool]
    func callTool(name: String, arguments: [String: Any]) async throws -> String
    func disconnect()
}

final class IOSMcpClient: IOSMcpClienting {
    private let transport: IOSMcpHTTPTransport
    private let requestTimeoutSeconds: TimeInterval
    private var config: IOSMcpServerConfig?
    private var nextId = 1
    private var requestInProgress = false
    private var requestWaiters: [IOSMcpRequestWaiter] = []

    private(set) var status: IOSMcpConnectionStatus = .idle

    init(transport: IOSMcpHTTPTransport = URLSessionMcpHTTPTransport(), requestTimeoutSeconds: TimeInterval = 120) {
        self.transport = transport
        self.requestTimeoutSeconds = requestTimeoutSeconds
    }

    func connect(config: IOSMcpServerConfig) async throws -> Bool {
        try await withRequestSlot {
            if self.status == .connected, self.config == config {
                return true
            }
            if let previousConfig = self.config {
                self.transport.disconnect(config: previousConfig)
            }
            self.status = .connecting
            self.config = config
            _ = try await self.sendWithoutRequestSlot(method: "initialize", params: [
                "protocolVersion": "2024-11-05",
                "capabilities": [:],
                "clientInfo": [
                    "name": "AmberAgent iOS",
                    "version": "1.0"
                ]
            ])
            try await self.sendNotificationWithoutRequestSlot(method: "notifications/initialized", params: [:])
            self.status = .connected
            return true
        }
    }

    func listTools() async throws -> [IOSMcpTool] {
        let result = try await send(method: "tools/list", params: [:])
        guard let tools = result["tools"] as? [[String: Any]] else { return [] }
        return tools.compactMap { item in
            guard let name = item["name"] as? String, !name.isEmpty else { return nil }
            return IOSMcpTool(
                name: name,
                description: item["description"] as? String,
                inputSchema: Self.persistedSchemaText(item["inputSchema"])
            )
        }
    }

    /// Serializes the raw `inputSchema` object into complete compact JSON text.
    /// Returns nil when the server omitted a schema or the value is not
    /// serializable JSON.
    private static func persistedSchemaText(_ value: Any?) -> String? {
        guard let value else { return nil }
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        return text
    }

    func callTool(name: String, arguments: [String: Any]) async throws -> String {
        let result = try await send(method: "tools/call", params: [
            "name": name,
            "arguments": arguments
        ])
        guard let content = result["content"] as? [[String: Any]] else {
            throw IOSMcpClientError.unsupportedContent
        }
        let text = content.compactMap { item -> String? in
            guard item["type"] as? String == "text" else { return nil }
            return item["text"] as? String
        }.joined(separator: "\n")
        if !text.isEmpty { return text }
        if let data = try? JSONSerialization.data(withJSONObject: content, options: [.sortedKeys]),
           let serialized = String(data: data, encoding: .utf8) {
            return serialized
        }
        throw IOSMcpClientError.unsupportedContent
    }

    func disconnect() {
        if let config {
            transport.disconnect(config: config)
        }
        config = nil
        status = .idle
    }

    private func send(method: String, params: [String: Any]) async throws -> [String: Any] {
        try await withRequestSlot {
            try await self.sendWithoutRequestSlot(method: method, params: params)
        }
    }

    private func sendWithoutRequestSlot(method: String, params: [String: Any]) async throws -> [String: Any] {
        guard let config else { throw IOSMcpClientError.invalidResponse }
        let id = nextId
        nextId += 1
        let payload: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params
        ]
        let response = try await sendJSONRPCWithTimeout(payload, to: config, method: method)
        if let error = response["error"] as? [String: Any] {
            throw IOSMcpClientError.rpcError(error["message"] as? String ?? "MCP JSON-RPC error")
        }
        guard let result = response["result"] as? [String: Any] else {
            throw IOSMcpClientError.invalidResponse
        }
        return result
    }

    private func sendNotification(method: String, params: [String: Any]) async throws {
        try await withRequestSlot {
            try await self.sendNotificationWithoutRequestSlot(method: method, params: params)
        }
    }

    private func sendNotificationWithoutRequestSlot(method: String, params: [String: Any]) async throws {
        guard let config else { throw IOSMcpClientError.invalidResponse }
        let payload: [String: Any] = [
            "jsonrpc": "2.0",
            "method": method,
            "params": params
        ]
        try await sendJSONRPCNotificationWithTimeout(payload, to: config, method: method)
    }

    private func withRequestSlot<T>(_ operation: () async throws -> T) async throws -> T {
        try await acquireRequestSlot()
        defer { releaseRequestSlot() }
        try Task.checkCancellation()
        return try await operation()
    }

    private func acquireRequestSlot() async throws {
        try Task.checkCancellation()
        guard requestInProgress else {
            requestInProgress = true
            return
        }

        let waiterID = UUID()
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                } else {
                    requestWaiters.append(.init(id: waiterID, continuation: continuation))
                }
            }
        }, onCancel: {
            Task { @MainActor [weak self] in
                self?.cancelRequestWaiter(id: waiterID)
            }
        })
    }

    private func releaseRequestSlot() {
        guard !requestWaiters.isEmpty else {
            requestInProgress = false
            return
        }
        requestWaiters.removeFirst().continuation.resume()
    }

    private func cancelRequestWaiter(id: UUID) {
        guard let index = requestWaiters.firstIndex(where: { $0.id == id }) else { return }
        requestWaiters.remove(at: index).continuation.resume(throwing: CancellationError())
    }

    private func sendJSONRPCWithTimeout(
        _ payload: [String: Any],
        to config: IOSMcpServerConfig,
        method: String
    ) async throws -> [String: Any] {
        let timeout = max(0.001, requestTimeoutSeconds)
        let timeoutText = Self.formatTimeout(timeout)
        let payloadBox = IOSMcpJSONRPCPayloadBox(payload: payload)
        return try await raceAgainstTimeout(method: method, timeout: timeout, timeoutText: timeoutText) {
            IOSMcpJSONRPCResponseBox(response: try await self.transport.sendJSONRPC(payloadBox.payload, to: config))
        }.response
    }

    private func raceAgainstTimeout<T: Sendable>(
        method: String,
        timeout: TimeInterval,
        timeoutText: String,
        operation: @escaping @MainActor () async throws -> T
    ) async throws -> T {
        let gate = IOSMcpContinuationGate<T>()
        let operationTaskBox = IOSMcpOperationTaskBox()
        let timerTaskBox = IOSMcpOperationTaskBox()
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                guard !gate.isCompleted else { return }

                let timerTask = Task {
                    do {
                        try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                        if gate.resume(throwing: IOSMcpClientError.requestTimedOut(
                            "MCP request \(method) timed out after \(timeoutText)s"
                        )) {
                            operationTaskBox.cancel()
                        }
                    } catch {
                        // Timer task was cancelled after the operation completed.
                    }
                }
                timerTaskBox.task = timerTask

                let operationTask = Task { @MainActor in
                    do {
                        let value = try await operation()
                        if gate.resume(returning: value) {
                            timerTaskBox.cancel()
                        }
                    } catch {
                        if gate.resume(throwing: error) {
                            timerTaskBox.cancel()
                        }
                    }
                }
                operationTaskBox.task = operationTask
            }
        }, onCancel: {
            if gate.resume(throwing: CancellationError()) {
                operationTaskBox.cancel()
                timerTaskBox.cancel()
            }
        })
    }

    private func sendJSONRPCNotificationWithTimeout(
        _ payload: [String: Any],
        to config: IOSMcpServerConfig,
        method: String
    ) async throws {
        let timeout = max(0.001, requestTimeoutSeconds)
        let timeoutText = Self.formatTimeout(timeout)
        let payloadBox = IOSMcpJSONRPCPayloadBox(payload: payload)
        try await raceAgainstTimeout(method: method, timeout: timeout, timeoutText: timeoutText) {
            try await self.transport.sendJSONRPCNotification(payloadBox.payload, to: config)
        }
    }

    nonisolated private static func formatTimeout(_ value: TimeInterval) -> String {
        let rounded = (value * 100).rounded() / 100
        if rounded == rounded.rounded() {
            return String(Int(rounded))
        }
        return String(format: "%.2f", rounded)
    }
}

private struct IOSMcpRequestWaiter {
    let id: UUID
    let continuation: CheckedContinuation<Void, Error>
}

private struct IOSMcpJSONRPCPayloadBox: @unchecked Sendable {
    let payload: [String: Any]
}

private struct IOSMcpJSONRPCResponseBox: @unchecked Sendable {
    let response: [String: Any]
}

private final class IOSMcpContinuationGate<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?
    private var pendingResult: Result<T, Error>?
    private var completed = false

    var isCompleted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return completed
    }

    func install(_ continuation: CheckedContinuation<T, Error>) {
        lock.lock()
        let result = pendingResult
        if result == nil {
            self.continuation = continuation
        } else {
            pendingResult = nil
        }
        lock.unlock()
        if let result {
            continuation.resume(with: result)
        }
    }

    @discardableResult
    func resume(returning value: T) -> Bool {
        finish(with: .success(value))
    }

    @discardableResult
    func resume(throwing error: Error) -> Bool {
        finish(with: .failure(error))
    }

    private func finish(with result: Result<T, Error>) -> Bool {
        lock.lock()
        guard !completed else {
            lock.unlock()
            return false
        }
        completed = true
        let continuation = self.continuation
        self.continuation = nil
        if continuation == nil {
            pendingResult = result
        }
        lock.unlock()
        continuation?.resume(with: result)
        return true
    }
}

private final class IOSMcpOperationTaskBox: @unchecked Sendable {
    private let lock = NSLock()
    private var storedTask: Task<Void, Never>?
    private var cancellationRequested = false

    var task: Task<Void, Never>? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return storedTask
        }
        set {
            lock.lock()
            storedTask = newValue
            let shouldCancel = cancellationRequested
            lock.unlock()
            if shouldCancel {
                newValue?.cancel()
            }
        }
    }

    func cancel() {
        lock.lock()
        cancellationRequested = true
        let task = storedTask
        lock.unlock()
        task?.cancel()
    }
}

extension IOSMcpServerConfig {
    init?(_ sharedConfig: McpServerConfig) {
        let options = sharedConfig.commonOptions
        let headers = Dictionary(uniqueKeysWithValues: options.headers.compactMap { pair -> (String, String)? in
            guard let first = pair.first as? String, let second = pair.second as? String else { return nil }
            return (first, second)
        })

        let tools = options.tools.map {
            IOSMcpTool(name: $0.name, description: $0.description_, enabled: $0.enable)
        }

        if let http = sharedConfig as? McpServerConfig.StreamableHTTPServer {
            self = .streamableHTTP(name: options.name, url: http.url, headers: headers, enabled: options.enable, tools: tools)
        } else if let sse = sharedConfig as? McpServerConfig.SseTransportServer {
            self = .sse(name: options.name, url: sse.url, headers: headers, enabled: options.enable, tools: tools)
        } else {
            return nil
        }
    }
}
