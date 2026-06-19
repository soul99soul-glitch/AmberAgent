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

@MainActor
final class IOSMiniAppBridgeRuntime {
    typealias EventEmitter = (_ type: String, _ subscriptionId: String?, _ payload: IOSMiniAppJSONValue) -> Void

    let appId: String

    private let repository: IOSMiniAppRepository
    private let policy: IOSMiniAppBridgePolicy
    private let apiKeyProvider: () -> String
    private let toastHandler: (String) -> Void
    private let clipboardCopyHandler: (String) -> Void
    private let themeProvider: () -> IOSMiniAppThemePayload
    private var eventEmitter: EventEmitter?
    private var eventSubscriptionIds = Set<String>()

    init(
        appId: String,
        repository: IOSMiniAppRepository? = nil,
        policy: IOSMiniAppBridgePolicy = IOSMiniAppBridgePolicy(),
        apiKeyProvider: @escaping () -> String = { "" },
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
        self.apiKeyProvider = apiKeyProvider
        self.toastHandler = toastHandler
        self.clipboardCopyHandler = clipboardCopyHandler
        self.themeProvider = themeProvider
        self.eventEmitter = eventEmitter
    }

    func setEventEmitter(_ emitter: EventEmitter?) {
        eventEmitter = emitter
    }

    func dispatch(method: String, params: [String: Any]) async -> IOSMiniAppBridgeDispatchResult {
        do {
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
                try require(.storage, method: method)
                let key = try stringParam("key", params)
                let value = try repository.storageGet(appId: appId, key: key) ?? .null
                try audit(method: method, permission: .storage, summary: "storage.get", payload: params)
                return .success(value)
            case "storage.set":
                try require(.storage, method: method)
                let key = try stringParam("key", params)
                let value = try IOSMiniAppJSONValue(any: params["value"])
                try repository.storageSet(appId: appId, key: key, value: value)
                try audit(method: method, permission: .storage, summary: "storage.set", payload: params)
                return .success(.bool(true))
            case "storage.remove":
                try require(.storage, method: method)
                try repository.storageRemove(appId: appId, key: try stringParam("key", params))
                try audit(method: method, permission: .storage, summary: "storage.remove", payload: params)
                return .success(.bool(true))
            case "toast":
                try require(.toast, method: method)
                let message = try stringParam("message", params).truncated(to: 120)
                toastHandler(message)
                return .success(.bool(true))
            case "host.getTheme", "theme":
                try require(.theme, method: method)
                return .success(themeProvider().json)
            case "clipboard.copy":
                try require(.clipboardCopy, method: method)
                let text = try stringParam("text", params).truncated(to: 20_000)
                clipboardCopyHandler(text)
                try audit(method: method, permission: .clipboardCopy, summary: "clipboard.copy", payload: ["bytes": text.utf8.count])
                return .success(.bool(true))
            case "host.updateBoardSummary":
                try require(.hostUpdateBoardSummary, method: method)
                let summary = try stringParam("summary", params).truncated(to: 500)
                try repository.updateBoardSummary(id: appId, summary: summary)
                try audit(method: method, permission: .hostUpdateBoardSummary, summary: "Update board summary", payload: ["summary": summary])
                return .success(.bool(true))
            case "sharedStore.get":
                try require(.sharedStore, method: method)
                let value = try repository.sharedGet(
                    appId: appId,
                    namespace: stringParamOrNil("namespace", params),
                    key: try stringParam("key", params)
                ) ?? .null
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.get", payload: params)
                return .success(value)
            case "sharedStore.set":
                try require(.sharedStore, method: method)
                try repository.sharedSet(
                    appId: appId,
                    namespace: stringParamOrNil("namespace", params),
                    key: try stringParam("key", params),
                    value: try IOSMiniAppJSONValue(any: params["value"])
                )
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.set", payload: ["ok": true])
                return .success(.bool(true))
            case "sharedStore.remove":
                try require(.sharedStore, method: method)
                try repository.sharedRemove(
                    appId: appId,
                    namespace: stringParamOrNil("namespace", params),
                    key: try stringParam("key", params)
                )
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.remove", payload: params)
                return .success(.bool(true))
            case "eventBus.subscribe":
                try require(.eventBus, method: method)
                let namespace = try ownNamespace(stringParamOrNil("namespace", params) ?? appId)
                let topic = try safeTopic(try stringParam("topic", params))
                let subscriptionId = IOSMiniAppEventBus.subscribe(appId: appId, namespace: namespace, topic: topic) { [weak self] id, payload in
                    self?.eventEmitter?("eventBus", id, payload)
                }
                eventSubscriptionIds.insert(subscriptionId)
                try audit(method: method, permission: .eventBus, summary: "eventBus.subscribe", payload: params)
                return .success(.object(["subscriptionId": .string(subscriptionId)]))
            case "eventBus.unsubscribe":
                try require(.eventBus, method: method)
                let subscriptionId = try stringParam("subscriptionId", params)
                eventSubscriptionIds.remove(subscriptionId)
                IOSMiniAppEventBus.unsubscribe(subscriptionId)
                return .success(.bool(true))
            case "eventBus.publish":
                try require(.eventBus, method: method)
                let namespace = try ownNamespace(stringParamOrNil("namespace", params) ?? appId)
                let topic = try safeTopic(try stringParam("topic", params))
                let payload = try IOSMiniAppJSONValue(any: params["payload"])
                guard payload.byteCount <= 16 * 1024 else { throw BridgeError.denied("EventBus payload is too large.") }
                IOSMiniAppEventBus.publish(namespace: namespace, topic: topic, payload: payload)
                try audit(method: method, permission: .eventBus, summary: "eventBus.publish", payload: ["ok": true])
                return .success(.bool(true))
            case "search":
                try require(.search, method: method)
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
                try require(.network, method: method)
                let value = try await fetch(params: params)
                try audit(method: method, permission: .network, summary: "MiniApp network request", payload: ["url": stringParamOrNil("url", params) ?? ""])
                return .success(value)
            case "ai.generate":
                try require(.aiGenerate, method: method)
                let key = apiKeyProvider().trimmingCharacters(in: .whitespacesAndNewlines)
                guard !key.isEmpty else {
                    throw BridgeError.denied("Amber.ai is not available because no API key is configured.")
                }
                throw BridgeError.denied("Amber.ai grant is allowed, but the iOS MiniApp-specific AI bridge is not implemented yet.")
            case "host.getConversationContext", "host.sendToConversation", "host.createArtifact",
                 "launch", "clipboard.read", "location.getCurrent", "sensor.subscribe", "sensor.unsubscribe":
                throw BridgeError.denied("Method '\(method)' requires sensitive host/system permission that is not implemented on iOS yet.")
            default:
                throw BridgeError.denied("Unknown MiniApp bridge method: \(method)")
            }
        } catch {
            return .failure((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    func close() {
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

    private func require(_ permission: IOSMiniAppPermission, method: String) throws {
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
            throw BridgeError.denied("Permission '\(permission.rawValue)' has no grant decision.")
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
        case .externalImages, .hostContext, .hostSendToConversation, .hostCreateArtifact,
             .launch, .sensor, .location, .clipboardRead:
            return false
        }
    }

    private func fetch(params: [String: Any]) async throws -> IOSMiniAppJSONValue {
        let urlString = try stringParam("url", params)
        guard let url = URL(string: urlString), url.scheme?.lowercased() == "https" else {
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
            request.httpBody = Data(body.utf8)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard data.count <= 256 * 1024 else { throw BridgeError.denied("Amber.fetch response is too large.") }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(decoding: data, as: UTF8.self)
        return .object([
            "status": .number(Double(status)),
            "ok": .bool((200...299).contains(status)),
            "url": .string(url.absoluteString),
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

private extension String {
    var nilIfBlank: String? {
        isEmpty ? nil : self
    }

    func truncated(to limit: Int) -> String {
        String(prefix(limit))
    }
}
