import Foundation
#if canImport(UIKit)
import UIKit
#endif

struct IOSMiniAppBridgePolicy: Equatable, Hashable {
    var miniAppEnabled: Bool = true
    var storageEnabled: Bool = true
    var toastEnabled: Bool = true
    var themeEnabled: Bool = true
    var networkEnabled: Bool = false
    var externalImagesEnabled: Bool = true
    var searchEnabled: Bool = false
    var clipboardCopyEnabled: Bool = true
    var boardSummaryUpdateEnabled: Bool = true
    var aiEnabled: Bool = false
    var sharedStoreEnabled: Bool = true
    var eventBusEnabled: Bool = true
    var hostContextEnabled: Bool = false
    var hostWriteEnabled: Bool = false
    var launchEnabled: Bool = true
    var sensorEnabled: Bool = true
    var locationEnabled: Bool = false
    var clipboardReadEnabled: Bool = false
    var webViewDebugEnabled: Bool = false
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

struct IOSMiniAppThemePayload: Equatable, Hashable {
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
    typealias LaunchHandler = (_ appId: String) async throws -> Void
    typealias ClipboardReadHandler = () async throws -> String
    typealias LocationHandler = (_ accuracy: String) async throws -> IOSMiniAppJSONValue
    typealias SensorSubscribeHandler = (
        _ subscriptionId: String,
        _ type: String,
        _ intervalMs: Int,
        _ onEvent: @escaping (IOSMiniAppJSONValue) -> Void
    ) throws -> Void
    typealias SensorUnsubscribeHandler = (_ subscriptionId: String) -> Void
    typealias SensitiveConfirmationHandler = (_ title: String, _ message: String) async throws -> Bool

    let appId: String

    private let repository: IOSMiniAppRepository
    private let policy: IOSMiniAppBridgePolicy
    private let fetchTransport: any IOSSearchHTTPTransport
    private let aiGenerateHandler: AIGenerateHandler?
    private let hostHandler: HostHandler?
    private let grantHandler: GrantHandler?
    private let launchHandler: LaunchHandler?
    private let clipboardReadHandler: ClipboardReadHandler?
    private let locationHandler: LocationHandler?
    private let sensorSubscribeHandler: SensorSubscribeHandler?
    private let sensorUnsubscribeHandler: SensorUnsubscribeHandler?
    private let sensitiveConfirmationHandler: SensitiveConfirmationHandler?
    private let toastHandler: (String) -> Void
    private let clipboardCopyHandler: (String) -> Void
    private let themeProvider: () -> IOSMiniAppThemePayload
    private var eventEmitter: EventEmitter?
    private var eventSubscriptionIds = Set<String>()
    private var sensorSubscriptionIds = Set<String>()
    private var eventPublishTimes: [TimeInterval] = []
    private static var launchTimes: [TimeInterval] = []
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
        launchHandler: LaunchHandler? = nil,
        clipboardReadHandler: ClipboardReadHandler? = {
            #if canImport(UIKit)
            UIPasteboard.general.string ?? ""
            #else
            ""
            #endif
        },
        locationHandler: LocationHandler? = nil,
        sensorSubscribeHandler: SensorSubscribeHandler? = nil,
        sensorUnsubscribeHandler: SensorUnsubscribeHandler? = nil,
        sensitiveConfirmationHandler: SensitiveConfirmationHandler? = nil,
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
        self.launchHandler = launchHandler
        self.clipboardReadHandler = clipboardReadHandler
        self.locationHandler = locationHandler
        self.sensorSubscribeHandler = sensorSubscribeHandler
        self.sensorUnsubscribeHandler = sensorUnsubscribeHandler
        self.sensitiveConfirmationHandler = sensitiveConfirmationHandler
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
                try audit(method: method, permission: .storage, summary: "storage.set", payload: params)
                try repository.storageSet(appId: appId, key: key, value: value)
                return .success(.bool(true))
            case "storage.remove":
                try await require(.storage, method: method)
                let key = try stringParam("key", params)
                try audit(method: method, permission: .storage, summary: "storage.remove", payload: params)
                try repository.storageRemove(appId: appId, key: key)
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
                try audit(method: method, permission: .clipboardCopy, summary: "clipboard.copy", payload: ["bytes": text.utf8.count])
                clipboardCopyHandler(text)
                return .success(.bool(true))
            case "host.updateBoardSummary":
                try await require(.hostUpdateBoardSummary, method: method)
                let summary = try stringParam("summary", params).truncated(to: 500)
                try audit(method: method, permission: .hostUpdateBoardSummary, summary: "Update board summary", payload: ["summary": summary])
                try repository.updateBoardSummary(id: appId, summary: summary)
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
                let namespace = stringParamOrNil("namespace", params)
                let key = try stringParam("key", params)
                let value = try IOSMiniAppJSONValue(any: params["value"])
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.set", payload: ["ok": true])
                try repository.sharedSet(
                    appId: appId,
                    namespace: namespace,
                    key: key,
                    value: value
                )
                return .success(.bool(true))
            case "sharedStore.remove":
                try await require(.sharedStore, method: method)
                let namespace = stringParamOrNil("namespace", params)
                let key = try stringParam("key", params)
                try audit(method: method, permission: .sharedStore, summary: "sharedStore.remove", payload: params)
                try repository.sharedRemove(
                    appId: appId,
                    namespace: namespace,
                    key: key
                )
                return .success(.bool(true))
            case "eventBus.subscribe":
                try await require(.eventBus, method: method)
                guard eventSubscriptionIds.count < 20 else {
                    throw BridgeError.denied("EventBus subscription limit exceeded")
                }
                let namespace = try ownNamespace(stringParamOrNil("namespace", params) ?? appId)
                let topic = try safeTopic(try stringParam("topic", params))
                let subscriptionId = IOSMiniAppEventBus.subscribe(appId: appId, namespace: namespace, topic: topic) { [weak self] id, payload in
                    self?.eventEmitter?("eventBus", id, payload)
                }
                eventSubscriptionIds.insert(subscriptionId)
                do {
                    try audit(method: method, permission: .eventBus, summary: "eventBus.subscribe", payload: params)
                } catch {
                    eventSubscriptionIds.remove(subscriptionId)
                    IOSMiniAppEventBus.unsubscribe(subscriptionId)
                    throw error
                }
                return .success(.object(["subscriptionId": .string(subscriptionId)]))
            case "eventBus.unsubscribe":
                let subscriptionId = try stringParam("subscriptionId", params)
                guard eventSubscriptionIds.remove(subscriptionId) != nil else {
                    throw BridgeError.denied("Unknown EventBus subscription")
                }
                IOSMiniAppEventBus.unsubscribe(subscriptionId)
                return .success(.bool(true))
            case "eventBus.publish":
                try await require(.eventBus, method: method)
                try checkEventPublishRateLimit()
                let namespace = try ownNamespace(stringParamOrNil("namespace", params) ?? appId)
                let topic = try safeTopic(try stringParam("topic", params))
                let payload = try IOSMiniAppJSONValue(any: params["payload"])
                guard payload.byteCount <= 16 * 1024 else { throw BridgeError.denied("EventBus payload is too large.") }
                try audit(method: method, permission: .eventBus, summary: "eventBus.publish", payload: ["ok": true])
                IOSMiniAppEventBus.publish(namespace: namespace, topic: topic, payload: payload)
                return .success(.bool(true))
            case "search":
                try await require(.search, method: method)
                let query = try stringParam("query", params).truncated(to: 160)
                let requestedLimit = (params["limit"] as? Int) ?? (params["max_results"] as? Int) ?? 6
                let limit = min(8, max(1, requestedLimit))
                try audit(method: method, permission: .search, summary: "MiniApp search request", payload: ["query": query])
                let results = try await IOSSearchExecutor.searchDuckDuckGoLite(query: query, maxResults: limit)
                let payload: [IOSMiniAppJSONValue] = results.map { result in
                    .object([
                        "title": .string(result.title),
                        "url": .string(result.url),
                        "snippet": .string(result.snippet),
                        "source": .string("DuckDuckGo Lite"),
                    ])
                }
                return .success(.object(["items": .array(payload)]))
            case "fetch":
                try await require(.network, method: method)
                try audit(method: method, permission: .network, summary: "MiniApp network request", payload: ["url": stringParamOrNil("url", params) ?? ""])
                let value = try await fetch(params: params)
                return .success(value)
            case "ai.generate":
                try await require(.aiGenerate, method: method)
                let request = try aiGenerateRequest(params)
                guard let aiGenerateHandler else {
                    throw BridgeError.denied("Amber.ai is not available in this MiniApp runner.")
                }
                try await confirmSensitive(
                    title: "允许 AI 生成？",
                    message: "「\(currentAppTitle)」想调用当前聊天模型生成文本。",
                    permission: .aiGenerate
                )
                try consumeDailyAIBudget()
                try audit(method: method, permission: .aiGenerate, summary: "ai.generate", payload: ["prompt": request.prompt])
                return .success(try await aiGenerateHandler(request))
            case "host.getConversationContext":
                try await require(.hostContext, method: method)
                let request = hostContextRequest(params)
                guard let hostHandler else {
                    throw BridgeError.denied("MiniApp host confirmation is not available in this runner.")
                }
                try audit(method: method, permission: .hostContext, summary: "host.context", payload: ["maxChars": request.maxChars])
                let value = try await hostHandler(.getConversationContext(request))
                return .success(value)
            case "host.sendToConversation":
                try await require(.hostSendToConversation, method: method)
                let request = try hostSendRequest(params)
                guard let hostHandler else {
                    throw BridgeError.denied("MiniApp host confirmation is not available in this runner.")
                }
                try audit(method: method, permission: .hostSendToConversation, summary: "host.sendToConversation", payload: ["text": request.text])
                let value = try await hostHandler(.sendToConversation(request))
                return .success(value)
            case "host.createArtifact":
                try await require(.hostCreateArtifact, method: method)
                let request = try hostArtifactRequest(params)
                guard let hostHandler else {
                    throw BridgeError.denied("MiniApp host confirmation is not available in this runner.")
                }
                try audit(method: method, permission: .hostCreateArtifact, summary: "host.createArtifact", payload: ["title": request.title, "content": request.content])
                let value = try await hostHandler(.createArtifact(request))
                return .success(value)
            case "launch":
                try await require(.launch, method: method)
                let targetAppId = try stringParam("appId", params)
                guard repository.get(targetAppId) != nil else {
                    throw BridgeError.denied("Target MiniApp does not exist")
                }
                guard let launchHandler else {
                    throw BridgeError.denied("MiniApp launch is not available in this runner.")
                }
                try await confirmSensitive(
                    title: "打开另一个小应用？",
                    message: "「\(currentAppTitle)」想打开小应用 \(targetAppId)。",
                    permission: .launch
                )
                guard repository.get(targetAppId) != nil else {
                    throw BridgeError.denied("Target MiniApp no longer exists")
                }
                try checkLaunchRateLimit()
                try audit(method: method, permission: .launch, summary: "launch", payload: ["appId": targetAppId])
                try await launchHandler(targetAppId)
                return .success(.bool(true))
            case "clipboard.read":
                try await require(.clipboardRead, method: method)
                guard let clipboardReadHandler else {
                    throw BridgeError.denied("Clipboard reading is not available in this runner.")
                }
                try await confirmSensitive(
                    title: "允许读取剪贴板？",
                    message: "「\(currentAppTitle)」想读取当前剪贴板文本。",
                    permission: .clipboardRead
                )
                try audit(method: method, permission: .clipboardRead, summary: "clipboard.read", payload: ["requested": true])
                let text = try await clipboardReadHandler().truncated(to: 20_000)
                return .success(.string(text))
            case "location.getCurrent":
                try await require(.location, method: method)
                guard let locationHandler else {
                    throw BridgeError.denied("Location is not available in this runner.")
                }
                let accuracy = stringParamOrNil("accuracy", params) == "fine" ? "fine" : "coarse"
                try await confirmSensitive(
                    title: "允许读取位置？",
                    message: "「\(currentAppTitle)」想读取 \(accuracy) 位置。",
                    permission: .location
                )
                try audit(method: method, permission: .location, summary: "location.getCurrent", payload: ["accuracy": accuracy])
                let value = try await locationHandler(accuracy)
                return .success(value)
            case "sensor.subscribe":
                try await require(.sensor, method: method)
                guard let sensorSubscribeHandler else {
                    throw BridgeError.denied("Sensors are not available in this runner.")
                }
                let type = try stringParam("type", params)
                let intervalMs = max(250, intParam("intervalMs", params, defaultValue: 500, range: 250...60_000))
                let subscriptionId = UUID().uuidString
                try await confirmSensitive(
                    title: "允许读取传感器？",
                    message: "「\(currentAppTitle)」想订阅 \(type) 传感器。",
                    permission: .sensor
                )
                try sensorSubscribeHandler(subscriptionId, type, intervalMs) { [weak self] payload in
                    self?.eventEmitter?("sensor", subscriptionId, payload)
                }
                sensorSubscriptionIds.insert(subscriptionId)
                do {
                    try audit(method: method, permission: .sensor, summary: "sensor.subscribe", payload: ["type": type])
                } catch {
                    sensorSubscriptionIds.remove(subscriptionId)
                    sensorUnsubscribeHandler?(subscriptionId)
                    throw error
                }
                return .success(.object(["subscriptionId": .string(subscriptionId)]))
            case "sensor.unsubscribe":
                let subscriptionId = try stringParam("subscriptionId", params)
                guard sensorSubscriptionIds.remove(subscriptionId) != nil else {
                    throw BridgeError.denied("Unknown sensor subscription")
                }
                sensorUnsubscribeHandler?(subscriptionId)
                return .success(.bool(true))
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
        for id in sensorSubscriptionIds {
            sensorUnsubscribeHandler?(id)
        }
        sensorSubscriptionIds.removeAll()
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
            try Task.checkCancellation()
            guard !isClosed else {
                throw CancellationError()
            }
            guard let currentApp = repository.get(appId),
                  currentApp.version == app.version,
                  currentApp.htmlHash == app.htmlHash,
                  currentApp.permissions.contains(permission.rawValue),
                  settingAllows(permission) else {
                throw BridgeError.denied("MiniApp changed while permission confirmation was open.")
            }
            if repository.grantDecision(appId: appId, permission: permission.rawValue) == .deny {
                throw BridgeError.denied("Permission '\(permission.rawValue)' was denied while confirmation was open.")
            }
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
        case .externalImages:
            return policy.externalImagesEnabled
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
        case .launch:
            return policy.launchEnabled
        case .sensor:
            return policy.sensorEnabled
        case .location:
            return policy.locationEnabled
        case .clipboardRead:
            return policy.clipboardReadEnabled
        }
    }

    private func checkLaunchRateLimit() throws {
        let now = Date().timeIntervalSince1970
        Self.launchTimes.removeAll { now - $0 > 30 }
        guard Self.launchTimes.count < 3 else {
            throw BridgeError.denied("Launch rate limit exceeded")
        }
        Self.launchTimes.append(now)
    }

    private func checkEventPublishRateLimit() throws {
        let now = Date().timeIntervalSince1970
        eventPublishTimes.removeAll { now - $0 > 10 }
        guard eventPublishTimes.count < 30 else {
            throw BridgeError.denied("EventBus publish rate limit exceeded")
        }
        eventPublishTimes.append(now)
    }

    private var currentAppTitle: String {
        repository.get(appId)?.title ?? "MiniApp"
    }

    private func confirmSensitive(
        title: String,
        message: String,
        permission: IOSMiniAppPermission
    ) async throws {
        guard let sensitiveConfirmationHandler else {
            throw BridgeError.denied("Sensitive MiniApp confirmation is unavailable in this runner.")
        }
        guard try await sensitiveConfirmationHandler(title, message) else {
            throw BridgeError.denied("User denied MiniApp request")
        }
        try Task.checkCancellation()
        guard !isClosed else { throw CancellationError() }
        guard let app = repository.get(appId),
              app.permissions.contains(permission.rawValue),
              settingAllows(permission),
              repository.grantDecision(appId: appId, permission: permission.rawValue) != .deny else {
            throw BridgeError.denied("MiniApp permission changed while confirmation was open.")
        }
    }

    private func consumeDailyAIBudget() throws {
        let formatter = DateFormatter()
        formatter.calendar = .autoupdatingCurrent
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .autoupdatingCurrent
        formatter.dateFormat = "yyyy-MM-dd"
        let today = formatter.string(from: Date())
        let key = "app.amber.ios.miniApp.aiBudget.\(appId)"
        let defaults = UserDefaults.standard
        let stored = defaults.dictionary(forKey: key)
        let count = stored?["day"] as? String == today ? stored?["count"] as? Int ?? 0 : 0
        guard count < 50 else {
            throw BridgeError.denied("Daily MiniApp AI budget exceeded")
        }
        defaults.set(["day": today, "count": count + 1], forKey: key)
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
            guard data.count <= 128 * 1_024 else {
                throw BridgeError.denied("Amber.fetch request body is too large.")
            }
            request.httpBody = data
        }
        let response: HTTPURLResponse
        let data: Data
        do {
            (response, data) = try await fetchTransport.sendPublic(
                request,
                maximumResponseBytes: 1_024 * 1_024
            )
        } catch IOSSearchExecutorError.responseTooLarge {
            throw BridgeError.denied("Amber.fetch response is too large.")
        } catch {
            throw BridgeError.denied(error.localizedDescription)
        }
        let status = response.statusCode
        let text = String(decoding: data, as: UTF8.self)
        let contentType = response.value(forHTTPHeaderField: "content-type")?.truncated(to: 120) ?? ""
        let responseType = (stringParamOrNil("responseType", params) ?? "text").lowercased()
        let body: IOSMiniAppJSONValue
        switch responseType {
        case "json":
            body = (try? IOSMiniAppJSONValue(any: JSONSerialization.jsonObject(with: data))) ?? .null
        case "dataurl":
            let mime = contentType.split(separator: ";", maxSplits: 1).first.map(String.init)?.nilIfBlank
                ?? "application/octet-stream"
            body = .string("data:\(mime);base64,\(data.base64EncodedString())")
        default:
            body = .string(text.truncated(to: 512 * 1_024))
        }
        return .object([
            "status": .number(Double(status)),
            "ok": .bool((200...299).contains(status)),
            "url": .string(response.url?.absoluteString ?? url.absoluteString),
            "contentType": .string(contentType),
            "body": body,
            "text": .string(text.truncated(to: 512 * 1_024)),
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
