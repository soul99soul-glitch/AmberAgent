import Foundation
import Shared

struct IOSMcpTool: Equatable, Identifiable {
    let name: String
    let description: String?

    var id: String { name }
}

enum IOSMcpServerConfig: Equatable, Identifiable {
    case streamableHTTP(name: String, url: String, headers: [String: String] = [:], enabled: Bool = true)
    case sse(name: String, url: String, headers: [String: String] = [:], enabled: Bool = true)

    var id: String { name }

    var name: String {
        switch self {
        case .streamableHTTP(let name, _, _, _), .sse(let name, _, _, _): name
        }
    }

    var url: String {
        switch self {
        case .streamableHTTP(_, let url, _, _), .sse(_, let url, _, _): url
        }
    }

    var headers: [String: String] {
        switch self {
        case .streamableHTTP(_, _, let headers, _), .sse(_, _, let headers, _): headers
        }
    }

    var enabled: Bool {
        switch self {
        case .streamableHTTP(_, _, _, let enabled), .sse(_, _, _, let enabled): enabled
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
}

struct IOSMcpStoredServer: Codable, Equatable {
    let name: String
    let url: String
    let transport: String
    let headers: [String: String]
    let enabled: Bool

    init(_ config: IOSMcpServerConfig) {
        self.name = config.name
        self.url = config.url
        self.transport = config.transportKey
        self.headers = config.headers
        self.enabled = config.enabled
    }

    var config: IOSMcpServerConfig {
        if transport == "sse" {
            return .sse(name: name, url: url, headers: headers, enabled: enabled)
        }
        return .streamableHTTP(name: name, url: url, headers: headers, enabled: enabled)
    }
}

enum IOSMcpConnectionStatus: Equatable {
    case idle
    case connecting
    case connected
    case error(String)

    var title: String {
        switch self {
        case .idle: "未连接"
        case .connecting: "连接中"
        case .connected: "已连接"
        case .error: "连接失败"
        }
    }
}

enum IOSMcpClientError: LocalizedError {
    case invalidURL(String)
    case httpStatus(Int)
    case invalidResponse
    case rpcError(String)
    case unsupportedContent

    var errorDescription: String? {
        switch self {
        case .invalidURL(let url): "Invalid MCP server URL: \(url)"
        case .httpStatus(let status): "MCP server returned HTTP status \(status)."
        case .invalidResponse: "MCP server returned an invalid JSON-RPC response."
        case .rpcError(let message): message
        case .unsupportedContent: "MCP tool result did not contain supported text content."
        }
    }
}

@MainActor
protocol IOSMcpHTTPTransport {
    func sendJSONRPC(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> [String: Any]
}

struct URLSessionMcpHTTPTransport: IOSMcpHTTPTransport {
    var session: URLSession = .shared

    func sendJSONRPC(_ payload: [String: Any], to config: IOSMcpServerConfig) async throws -> [String: Any] {
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

        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return object
        }

        if let eventObject = Self.firstServerSentEventJSON(from: data) {
            return eventObject
        }

        throw IOSMcpClientError.invalidResponse
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
protocol IOSMcpClienting {
    func connect(config: IOSMcpServerConfig) async throws -> Bool
    func listTools() async throws -> [IOSMcpTool]
    func callTool(name: String, arguments: [String: Any]) async throws -> String
    func disconnect()
}

final class IOSMcpClient: IOSMcpClienting {
    private let transport: IOSMcpHTTPTransport
    private var config: IOSMcpServerConfig?
    private var nextId = 1

    private(set) var status: IOSMcpConnectionStatus = .idle

    init(transport: IOSMcpHTTPTransport = URLSessionMcpHTTPTransport()) {
        self.transport = transport
    }

    func connect(config: IOSMcpServerConfig) async throws -> Bool {
        status = .connecting
        self.config = config
        _ = try await send(method: "initialize", params: [
            "protocolVersion": "2024-11-05",
            "capabilities": [:],
            "clientInfo": [
                "name": "AmberAgent iOS",
                "version": "1.0"
            ]
        ])
        status = .connected
        return true
    }

    func listTools() async throws -> [IOSMcpTool] {
        let result = try await send(method: "tools/list", params: [:])
        guard let tools = result["tools"] as? [[String: Any]] else { return [] }
        return tools.compactMap { item in
            guard let name = item["name"] as? String, !name.isEmpty else { return nil }
            return IOSMcpTool(name: name, description: item["description"] as? String)
        }
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
        guard !text.isEmpty else { throw IOSMcpClientError.unsupportedContent }
        return text
    }

    func disconnect() {
        config = nil
        status = .idle
    }

    private func send(method: String, params: [String: Any]) async throws -> [String: Any] {
        guard let config else { throw IOSMcpClientError.invalidResponse }
        let id = nextId
        nextId += 1
        let payload: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params
        ]
        let response = try await transport.sendJSONRPC(payload, to: config)
        if let error = response["error"] as? [String: Any] {
            throw IOSMcpClientError.rpcError(error["message"] as? String ?? "MCP JSON-RPC error")
        }
        guard let result = response["result"] as? [String: Any] else {
            throw IOSMcpClientError.invalidResponse
        }
        return result
    }
}

extension IOSMcpServerConfig {
    init?(_ sharedConfig: McpServerConfig) {
        let options = sharedConfig.commonOptions
        let headers = Dictionary(uniqueKeysWithValues: options.headers.compactMap { pair -> (String, String)? in
            guard let first = pair.first as? String, let second = pair.second as? String else { return nil }
            return (first, second)
        })

        if let http = sharedConfig as? McpServerConfig.StreamableHTTPServer {
            self = .streamableHTTP(name: options.name, url: http.url, headers: headers, enabled: options.enable)
        } else if let sse = sharedConfig as? McpServerConfig.SseTransportServer {
            self = .sse(name: options.name, url: sse.url, headers: headers, enabled: options.enable)
        } else {
            return nil
        }
    }
}
