import Foundation
#if canImport(UIKit)
import UIKit
#endif

struct IOSMiniAppBridgePolicy: Equatable {
    var miniAppEnabled: Bool = true
    var storageEnabled: Bool = true
    var toastEnabled: Bool = true
    var themeEnabled: Bool = true
    var networkEnabled: Bool = false
    var searchEnabled: Bool = false
    var clipboardCopyEnabled: Bool = true
    var boardSummaryUpdateEnabled: Bool = true
    var aiEnabled: Bool = false
    var sharedStoreEnabled: Bool = true
    var eventBusEnabled: Bool = true
    var hostContextEnabled: Bool = false
    var hostWriteEnabled: Bool = false
}

enum IOSMiniAppBridgeDispatchResult: Equatable {
    case success(IOSMiniAppJSONValue)
    case failure(String)

    var anyValue: Any? {
        if case .success(let value) = self { return value.anyValue }
        return nil
    }

    var errorMessage: String? {
        if case .failure(let message) = self { return message }
        return nil
    }
}

struct IOSMiniAppThemePayload: Equatable {
    var dark: Bool
    var background: String
    var foreground: String
    var primary: String

    var json: IOSMiniAppJSONValue {
        .object([
            "dark": .bool(dark),
            "background": .string(background),
            "foreground": .string(foreground),
            "primary": .string(primary),
        ])
    }
}

struct IOSMiniAppAIGenerateRequest: Equatable {
    var prompt: String
    var system: String
    var maxOutputChars: Int
    var temperature: Double?
}

struct IOSMiniAppHostContextRequest: Equatable {
    var maxChars: Int
}

struct IOSMiniAppHostSendRequest: Equatable {
    var text: String
    var mode: String
}

struct IOSMiniAppHostArtifactRequest: Equatable {
    var title: String
    var content: String
    var type: String
}

enum IOSMiniAppHostRequest: Equatable {
    case getConversationContext(IOSMiniAppHostContextRequest)
    case sendToConversation(IOSMiniAppHostSendRequest)
    case createArtifact(IOSMiniAppHostArtifactRequest)
}

@MainActor
final class IOSMiniAppBridgeRuntime {
    typealias EventEmitter = (_ type: String, _ subscriptionId: String?, _ payload: IOSMiniAppJSONValue) -> Void
    typealias AIGenerateHandler = (_ request: IOSMiniAppAIGenerateRequest) async throws -> IOSMiniAppJSONValue
    typealias HostHandler = (_ request: IOSMiniAppHostRequest) async throws -> IOSMiniAppJSONValue
    /// First-use grant prompt. Return true to allow and persist, false to deny and persist.
    typealias GrantHandler = (_ permission: IOSMiniAppPermission) async throws -> Bool

    let appId: String

    private let repository: IOSMiniAppRepository
    private let policy: IOSMiniAppBridgePolicy
    private let fetchTransport: any IOSSearchHTTPTransport
    private let aiGenerateHandler: AIGenerateHandler?
    private let hostHandler: HostHandler?
    private let grantHandler: GrantHandler?
    private let toastHandler: (String) -> Void
    private let clipboardCopyHandler: (String) -> Void
    private let themeProvider: () -> IOSMiniAppThemePayload
    private var eventEmitter: EventEmitter?
    private var eventSubscriptionIds = Set<String>()
    private var inFlightTasks: [UUID: Task<IOSMiniAppBridgeDispatchResult, Never>] = [:]
    private var isClosed = false

    init(
        appId: String,
        repository: IOSMiniAppRepository? = nil,
        policy: IOSMiniAppBridgePolicy = IOSMiniAppBridgePolicy(),
        fetchTransport: any IOSSearchHTTPTransport = IOSURLSessionSearchHTTPTransport(),
        aiGenerateHandler: AIGenerateHandler? = nil,
        hostHandler: HostHandler? = nil,
        grantHandler: GrantHandler? = nil,
        toastHandler: @escaping (String) -> Void = { _ in },
        clipboardCopyHandler: @escaping (String) -> Void = {
            #if canImport(UIKit)
            UIPasteboard.general.string = $0
            #else
            _ = $0
            #endif
        },
        themeProvider: @escaping () -> IOSMiniAppThemePayload = {
            IOSMiniAppThemePayload(dark: false, background: "#FFFFFF", foreground: "#111827", primary: "#2563EB")
        },
        eventEmitter: EventEmitter? = nil
    ) {
        self.appId = appId
        self.repository = repository ?? IOSMiniAppRepository.shared
        self.policy = policy
        self.fetchTransport = fetchTransport
        self.aiGenerateHandler = aiGenerateHandler
        self.hostHandler = hostHandler
        self.grantHandler = grantHandler
        self.toastHandler = toastHandler
        self.clipboardCopyHandler = clipboardCopyHandler
        self.themeProvider = themeProvider
        self.eventEmitter = eventEmitter
    }

    func setEventEmitter(_ emitter: EventEmitter?) {
        eventEmitter = emitter
    }

    func dispatch(method: String, params: [String: Any]) async -> IOSMiniAppBridgeDispatchResult {
        guard !isClosed else { return .failure("MiniApp bridge is closed.") }
        let id = UUID()
        let task = Task { @MainActor [weak self] in
            guard let self else {
                return IOSMiniAppBridgeDispatchResult.failure("MiniApp bridge is closed.")
            }
            return await self.dispatchOpen(method: method, params: params)
        }
        inFlightTasks[id] = task
        let result = await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
        inFlightTasks.removeValue(forKey: id)
        return result
    }

    private func dispatchOpen(method: String, params: [String: Any]) async -> IOSMiniAppBridgeDispatchResult {
        do {
            try Task.checkCancellation()
            guard policy.miniAppEnabled else {
                throw BridgeError.denied("MiniApp runtime is disabled in settings.")
            }
            switch method {
            case "log":
                return .success(.object(["ok": .bool(true)]))
            case "echo":
                return .success(try IOSMiniAppJSONValue(any: params))
            case "app.info":
                return .success(try appInfo())
            case "storage.get":
                try await require(.storage, method: method)
                let key = try stringParam("key", params)
                let value = try repository.storageGet(appId: appId, key: key) ?? .null
                try audit(method: method, permission: .storage, summary: "storage.get", payload: params)
                return .success(value)
            case "storage.set":
                try await require(.storage, method: method)
                let key = try stringParam("key", params)
                let value = try IOSMiniAppJSONValue(any: params["value"])
                try repository.storageSet(appId: appId, key: key, value: value)
                try audit(method: method, permission: .storage, summary: "storage.set", payload: params)
                return .success(.bool(true))
            case "storage.remove":
                try await require(.storage, method: method)
                try repository.storageRemove(appId: appId, key: try stringParam("key", params))
                try audit(method: method, permission: .storage, summary: "storage.remove", payload: params)
                return .success(.bool(true))
            case "toast":
                try await require(.toast, method: method)
                let message = try stringParam("message", params).truncated(to: 120)
                toastHandler(message)
                return .success(.bool(true))
            case "host.getTheme", "theme":
                try await require(.theme, method: method)
                return .success(themeProvider().json)
            case "clipboard.copy":
                try await require(.clipboardCopy, method: method)
                let text = try stringParam("text", params).truncated(to: 20_000)
                clipboardCopyHandler(text)
                try audit(method: method, permission: .clipboardCopy, summary: "clipboard.copy", payload: ["bytes": text.utf8.count])
                return .success(.bool(true))
            case "host.updateBoardSummary":
                try await require(.hostUpdateBoardSummary, method: method)
                let summary = try stringParam("summary", params).truncated(to: 500)
                try repository.updateBoardSummary(id: appId, summary: summary)
                try audit(method: method, permission: .hostUpdateBoardSummary, summary: "Update board summary", payload: ["summary": summary])
                return .success(.bool(true))
            case "sharedStore.get":
                try await require(.sharedStore, method: method)
                let value = try repository.sharedGet(
                    appId: appId,
                    namespace: stringParamOrNil("namespace", params),
                    key: try stringParam("key", params)
                ) ?? .null
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.get", payload: params)
                return .success(value)
            case "sharedStore.set":
                try await require(.sharedStore, method: method)
                try repository.sharedSet(
                    appId: appId,
                    namespace: stringParamOrNil("namespace", params),
                    key: try stringParam("key", params),
                    value: try IOSMiniAppJSONValue(any: params["value"])
                )
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.set", payload: ["ok": true])
                return .success(.bool(true))
            case "sharedStore.remove":
                try await require(.sharedStore, method: method)
                try repository.sharedRemove(
                    appId: appId,
                    namespace: stringParamOrNil("namespace", params),
                    key: try stringParam("key", params)
                )
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.remove", payload: params)
                return .success(.bool(true))
            case "eventBus.subscribe":
                try await require(.eventBus, method: method)
                let namespace = try ownNamespace(stringParamOrNil("namespace", params) ?? appId)
                let topic = try safeTopic(try stringParam("topic", params))
                let subscriptionId = IOSMiniAppEventBus.subscribe(appId: appId, namespace: namespace, topic: topic) { [weak self] id, payload in
                    self?.eventEmitter?("eventBus", id, payload)
                }
                eventSubscriptionIds.insert(subscriptionId)
                try audit(method: method, permission: .eventBus, summary: "eventBus.subscribe", payload: params)
                return .success(.object(["subscriptionId": .string(subscriptionId)]))
            case "eventBus.unsubscribe":
                try await require(.eventBus, method: method)
                let subscriptionId = try stringParam("subscriptionId", params)
                eventSubscriptionIds.remove(subscriptionId)
                IOSMiniAppEventBus.unsubscribe(subscriptionId)
                return .success(.bool(true))
            case "eventBus.publish":
                try await require(.eventBus, method: method)
                let namespace = try ownNamespace(stringParamOrNil("namespace", params) ?? appId)
                let topic = try safeTopic(try stringParam("topic", params))
                let payload = try IOSMiniAppJSONValue(any: params["payload"])
                guard payload.byteCount <= 16 * 1024 else { throw BridgeError.denied("EventBus payload is too large.") }
                IOSMiniAppEventBus.publish(namespace: namespace, topic: topic, payload: payload)
                try audit(method: method, permission: .eventBus, summary: "eventBus.publish", payload: ["ok": true])
                return .success(.bool(true))
            case "search":
                try await require(.search, method: method)
                let query = try stringParam("query", params)
                let limit = (params["limit"] as? Int) ?? (params["max_results"] as? Int) ?? 5
                let results = try await IOSSearchExecutor.searchDuckDuckGoLite(query: query, maxResults: limit)
                try audit(method: method, permission: .search, summary: "MiniApp search request", payload: ["query": query])
                let payload: [IOSMiniAppJSONValue] = results.map { result in
                    .object([
                        "title": .string(result.title),
                        "url": .string(result.url),
                        "snippet": .string(result.snippet),
                        "source": .string("DuckDuckGo Lite"),
                    ])
                }
                return .success(.array(payload))
            case "fetch":
                try await require(.network, method: method)
                let value = try await fetch(params: params)
                try audit(method: method, permission: .network, summary: "MiniApp network request", payload: ["url": stringParamOrNil("url", params) ?? ""])
                return .success(value)
            case "ai.generate":
                try await require(.aiGenerate, method: method)
                let request = try aiGenerateRequest(params)
                guard let aiGenerateHandler else {
                    throw BridgeError.denied("Amber.ai is not available in this MiniApp runner.")
                }
                try audit(method: method, permission: .aiGenerate, summary: "ai.generate", payload: ["prompt": request.prompt])
                return .success(try await aiGenerateHandler(request))
            case "host.getConversationContext":
                try await require(.hostContext, method: method)
                let request = hostContextRequest(params)
                guard let hostHandler else {
                    throw BridgeError.denied("MiniApp host confirmation is not available in this runner.")
                }
                let value = try await hostHandler(.getConversationContext(request))
                try audit(method: method, permission: .hostContext, summary: "host.context", payload: ["maxChars": request.maxChars])
                return .success(value)
            case "host.sendToConversation":
                try await require(.hostSendToConversation, method: method)
                let request = try hostSendRequest(params)
                guard let hostHandler else {
                    throw BridgeError.denied("MiniApp host confirmation is not available in this runner.")
                }
                let value = try await hostHandler(.sendToConversation(request))
                try audit(method: method, permission: .hostSendToConversation, summary: "host.sendToConversation", payload: ["text": request.text])
                return .success(value)
            case "host.createArtifact":
                try await require(.hostCreateArtifact, method: method)
                let request = try hostArtifactRequest(params)
                guard let hostHandler else {
                    throw BridgeError.denied("MiniApp host confirmation is not available in this runner.")
                }
                let value = try await hostHandler(.createArtifact(request))
                try audit(method: method, permission: .hostCreateArtifact, summary: "host.createArtifact", payload: ["title": request.title, "content": request.content])
                return .success(value)
            case "launch", "clipboard.read", "location.getCurrent", "sensor.subscribe", "sensor.unsubscribe":
                throw BridgeError.denied("Method '\(method)' requires sensitive host/system permission that is not implemented on iOS yet.")
            default:
                throw BridgeError.denied("Unknown MiniApp bridge method: \(method)")
            }
        } catch is CancellationError {
            return .failure("MiniApp bridge request was cancelled.")
        } catch {
            return .failure((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        let tasks = Array(inFlightTasks.values)
        inFlightTasks.removeAll()
        tasks.forEach { $0.cancel() }
        for id in eventSubscriptionIds {
            IOSMiniAppEventBus.unsubscribe(id)
        }
        eventSubscriptionIds.removeAll()
    }

    private func appInfo() throws -> IOSMiniAppJSONValue {
        let app = repository.get(appId)
        let grantObjects = repository.grants(appId: appId).map { grant in
            IOSMiniAppJSONValue.object([
                "permission": .string(grant.permission),
                "decision": .string(grant.decision.rawValue),
                "updatedAt": .number(Double(grant.updatedAt)),
            ])
        }
        return .object([
            "platform": .string("ios"),
            "bridgeVersion": .string("0.2-local-runner"),
            "appId": .string(appId),
            "title": .string(app?.title ?? "Unknown MiniApp"),
            "version": .number(Double(app?.version ?? 0)),
            "runCount": .number(Double(app?.runCount ?? 0)),
            "permissions": .array((app?.permissions ?? []).map { .string($0) }),
            "grants": .array(grantObjects),
        ])
    }

    private func require(_ permission: IOSMiniAppPermission, method: String) async throws {
        guard let app = repository.get(appId) else { throw BridgeError.denied("MiniApp not found: \(appId)") }
        guard app.permissions.contains(permission.rawValue) else {
            throw BridgeError.denied("Permission '\(permission.rawValue)' is not declared by this MiniApp.")
        }
        guard settingAllows(permission) else {
            throw BridgeError.denied("Permission '\(permission.rawValue)' is disabled in MiniApp settings.")
        }
        switch repository.grantDecision(appId: appId, permission: permission.rawValue) {
        case .allow:
            return
        case .deny:
            throw BridgeError.denied("Permission '\(permission.rawValue)' was denied.")
        case nil:
            guard let grantHandler else {
                throw BridgeError.denied("Permission '\(permission.rawValue)' has no grant decision.")
            }
            let allowed = try await grantHandler(permission)
            try repository.setGrant(
                appId: appId,
                permission: permission.rawValue,
                decision: allowed ? .allow : .deny
            )
            guard allowed else {
                throw BridgeError.denied("Permission '\(permission.rawValue)' was denied.")
            }
        }
    }

    private func settingAllows(_ permission: IOSMiniAppPermission) -> Bool {
        switch permission {
        case .storage:
            return policy.storageEnabled
        case .toast:
            return policy.toastEnabled
        case .theme:
            return policy.themeEnabled
        case .network:
            return policy.networkEnabled
        case .search:
            return policy.searchEnabled
        case .clipboardCopy:
            return policy.clipboardCopyEnabled
        case .hostUpdateBoardSummary:
            return policy.boardSummaryUpdateEnabled
        case .aiGenerate:
            return policy.aiEnabled
        case .sharedStore:
            return policy.sharedStoreEnabled
        case .eventBus:
            return policy.eventBusEnabled
        case .hostContext:
            return policy.hostContextEnabled
        case .hostSendToConversation, .hostCreateArtifact:
            return policy.hostWriteEnabled
        case .externalImages, .launch, .sensor, .location, .clipboardRead:
            return false
        }
    }

    private func fetch(params: [String: Any]) async throws -> IOSMiniAppJSONValue {
        let urlString = try stringParam("url", params)
        let url: URL
        do {
            url = try IOSSearchExecutor.allowedPublicHTTPURL(from: urlString)
        } catch {
            throw BridgeError.denied(error.localizedDescription)
        }
        guard url.scheme?.lowercased() == "https" else {
            throw BridgeError.denied("Amber.fetch only allows https URLs.")
        }
        let method = (stringParamOrNil("method", params) ?? "GET").uppercased()
        guard ["GET", "POST"].contains(method) else {
            throw BridgeError.denied("Amber.fetch only allows GET and POST on iOS.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 20
        if let headers = params["headers"] as? [String: Any] {
            for (key, value) in headers {
                if let value = value as? String, isSafeHeaderName(key) {
                    request.setValue(value.truncated(to: 1_000), forHTTPHeaderField: key)
                }
            }
        }
        if let body = params["body"] as? String {
            let data = Data(body.utf8)
            guard data.count <= 64 * 1_024 else {
                throw BridgeError.denied("Amber.fetch request body is too large.")
            }
            request.httpBody = data
        }
        let response: HTTPURLResponse
        let data: Data
        do {
            (response, data) = try await fetchTransport.sendPublic(
                request,
                maximumResponseBytes: 256 * 1_024
            )
        } catch IOSSearchExecutorError.responseTooLarge {
            throw BridgeError.denied("Amber.fetch response is too large.")
        } catch {
            throw BridgeError.denied(error.localizedDescription)
        }
        let status = response.statusCode
        let text = String(decoding: data, as: UTF8.self)
        return .object([
            "status": .number(Double(status)),
            "ok": .bool((200...299).contains(status)),
            "url": .string(response.url?.absoluteString ?? url.absoluteString),
            "text": .string(text),
        ])
    }

    private func audit(method: String, permission: IOSMiniAppPermission, summary: String, payload: Any) throws {
        let payloadData = try JSONSerialization.data(withJSONObject: IOSMiniAppJSONValue(any: payload).anyValue)
        let payloadString = String(decoding: payloadData, as: UTF8.self)
        try repository.audit(appId: appId, method: method, permission: permission.rawValue, summary: summary, payload: payloadString)
    }

    private func stringParam(_ key: String, _ params: [String: Any]) throws -> String {
        guard let value = params[key] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw BridgeError.denied("Missing parameter: \(key)")
        }
        return value
    }

    private func stringParamOrNil(_ key: String, _ params: [String: Any]) -> String? {
        (params[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
    }

    private func aiGenerateRequest(_ params: [String: Any]) throws -> IOSMiniAppAIGenerateRequest {
        IOSMiniAppAIGenerateRequest(
            prompt: try stringParam("prompt", params).truncated(to: 16_000),
            system: (stringParamOrNil("system", params) ?? "").truncated(to: 2_000),
            maxOutputChars: intParam("maxOutputChars", params, defaultValue: 6_000, range: 1...16_000),
            temperature: doubleParam("temperature", params)?.clamped(to: 0...2)
        )
    }

    private func hostContextRequest(_ params: [String: Any]) -> IOSMiniAppHostContextRequest {
        IOSMiniAppHostContextRequest(
            maxChars: intParam("maxChars", params, defaultValue: 6_000, range: 200...8_000)
        )
    }

    private func hostSendRequest(_ params: [String: Any]) throws -> IOSMiniAppHostSendRequest {
        let rawMode = stringParamOrNil("mode", params)?.lowercased() ?? "draft"
        let mode = rawMode == "insert" ? "insert" : "draft"
        return IOSMiniAppHostSendRequest(
            text: try stringParam("text", params).truncated(to: 8_000),
            mode: mode
        )
    }

    private func hostArtifactRequest(_ params: [String: Any]) throws -> IOSMiniAppHostArtifactRequest {
        IOSMiniAppHostArtifactRequest(
            title: try stringParam("title", params).truncated(to: 80),
            content: try stringParam("content", params).truncated(to: 12_000),
            type: (stringParamOrNil("type", params) ?? "note").truncated(to: 40)
        )
    }

    private func intParam(
        _ key: String,
        _ params: [String: Any],
        defaultValue: Int,
        range: ClosedRange<Int>
    ) -> Int {
        let raw: Int?
        if let value = params[key] as? Int {
            raw = value
        } else if let value = params[key] as? Double {
            raw = Int(value)
        } else if let value = params[key] as? NSNumber {
            raw = value.intValue
        } else {
            raw = nil
        }
        return (raw ?? defaultValue).clamped(to: range)
    }

    private func doubleParam(_ key: String, _ params: [String: Any]) -> Double? {
        if let value = params[key] as? Double {
            return value
        }
        if let value = params[key] as? Int {
            return Double(value)
        }
        if let value = params[key] as? NSNumber {
            return value.doubleValue
        }
        return nil
    }

    private func ownNamespace(_ namespace: String) throws -> String {
        guard namespace == appId else { throw BridgeError.denied("Cross-app namespace is not granted.") }
        return namespace
    }

    private func safeTopic(_ topic: String) throws -> String {
        let normalized = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...64).contains(normalized.count),
              normalized.range(of: #"^[a-zA-Z0-9._:-]+$"#, options: .regularExpression) != nil else {
            throw BridgeError.denied("Invalid topic.")
        }
        return normalized
    }

    private func isSafeHeaderName(_ name: String) -> Bool {
        name.range(of: #"^[A-Za-z0-9-]{1,80}$"#, options: .regularExpression) != nil
    }
}

@MainActor
private enum IOSMiniAppEventBus {
    typealias Handler = (_ subscriptionId: String, _ payload: IOSMiniAppJSONValue) -> Void

    private struct Subscriber {
        var namespace: String
        var topic: String
        var handler: Handler
    }

    private static var subscribers: [String: Subscriber] = [:]

    static func subscribe(appId: String, namespace: String, topic: String, handler: @escaping Handler) -> String {
        let id = "\(appId):\(UUID().uuidString)"
        subscribers[id] = Subscriber(namespace: namespace, topic: topic, handler: handler)
        return id
    }

    static func unsubscribe(_ id: String) {
        subscribers.removeValue(forKey: id)
    }

    static func publish(namespace: String, topic: String, payload: IOSMiniAppJSONValue) {
        for (id, subscriber) in subscribers where subscriber.namespace == namespace && subscriber.topic == topic {
            subscriber.handler(id, payload)
        }
    }
}

private enum BridgeError: LocalizedError {
    case denied(String)

    var errorDescription: String? {
        if case .denied(let message) = self { return message }
        return nil
    }
}
