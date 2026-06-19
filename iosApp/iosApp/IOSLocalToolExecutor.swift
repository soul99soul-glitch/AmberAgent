import Foundation
import Combine
import Observation
import WebKit

struct IOSLocalToolExecutionRequest: Equatable {
    let toolName: String
    let operation: String
    let scopeDigest: String
    let payloadDigest: String
    let isUserInitiated: Bool
}

enum IOSLocalToolExecutionOutput: Equatable {
    case selectedFilePreview(SelectedDocumentReadResult)
    case permissionsStatus(IOSPermissionsStatusSnapshot)
    case webMountResult(String)
    case needsUserAction(String)
    case denied(String)
    case failed(String)
}

struct IOSWebMountToolApprovalPreview: Equatable {
    let toolName: String
    let siteId: String
    let siteName: String
    let host: String
}

struct IOSPermissionsStatusSnapshot: Equatable {
    let generatedAt: Date
    let platform: String
    let capabilities: [IOSCapabilityStatusItem]
}

struct IOSCapabilityStatusItem: Equatable, Identifiable {
    let id: String
    let title: String
    let summary: String
    let domain: String
    let status: String
    let systemStatus: String
    let systemStatusMessage: String
    let risk: String
    let policy: String
    let requestKind: String
    let requestEntryPoint: String
    let canRequestInApp: Bool
    let canOpenSettings: Bool
    let uiActionNames: [String]
    let modelToolNames: [String]
    let blockedToolNames: [String]
    let defaultEnabled: Bool
    let requiresFreshUserPresence: Bool
    let allowRunScopedReuse: Bool
    let allowGlobalAutoApproval: Bool
    let requiredInfoPlistKeys: [String]
    let requiredEntitlements: [String]
    let requiredBackgroundModes: [String]
    let requiredExtensionTargets: [String]
    let reason: String?
    let executable: Bool
}

@MainActor
final class IOSLocalToolExecutor {
    private let permissionStore: IOSPermissionStore
    private let documentStore: DocumentAccessStore
    private let systemPermissionCoordinator: IOSSystemPermissionCoordinator
    private let runtime: IOSToolRuntime
    private let webMountController: IOSWebMountController

    init(
        permissionStore: IOSPermissionStore,
        documentStore: DocumentAccessStore,
        systemPermissionCoordinator: IOSSystemPermissionCoordinator? = nil,
        webMountController: IOSWebMountController? = nil
    ) {
        self.permissionStore = permissionStore
        self.documentStore = documentStore
        self.systemPermissionCoordinator = systemPermissionCoordinator ?? IOSSystemPermissionCoordinator()
        self.runtime = IOSToolRuntime(permissionStore: permissionStore, documentStore: documentStore)
        self.webMountController = webMountController ?? IOSWebMountController.shared
    }

    func execute(
        _ request: IOSLocalToolExecutionRequest,
        now: Date = Date()
    ) async -> IOSLocalToolExecutionOutput {
        if request.toolName == "permissions_status" {
            return .permissionsStatus(permissionsStatus(now: now))
        }
        if IOSWebMountToolCatalog.unsupportedToolNames.contains(request.toolName) {
            return .webMountResult(IOSWebMountController.unsupportedToolResult(toolName: request.toolName))
        }
        if IOSWebMountToolCatalog.supportedToolNames.contains(request.toolName) {
            guard let capability = IOSCapabilityRegistry.capability(forToolName: request.toolName) else {
                return .denied("Unknown iOS WebMount tool: \(request.toolName)")
            }
            switch resolveWebMount(request: request, capability: capability) {
            case .allow:
                let output = await webMountController.execute(
                    toolName: request.toolName,
                    input: request.operation,
                    isUserInitiated: request.isUserInitiated
                )
                return .webMountResult(output)
            case .needsUserAction(let reason):
                return .needsUserAction(reason)
            case .deny(let reason):
                return .denied(reason)
            }
        }
        if IOSCapabilityRegistry.capability(forUIActionName: request.toolName) != nil {
            return .denied("\(request.toolName) is a foreground UI action")
        }

        guard IOSCapabilityRegistry.capability(forToolName: request.toolName) != nil else {
            return .denied("Unknown iOS tool: \(request.toolName)")
        }

        let invocation = IOSToolInvocationRequest(
            toolName: request.toolName,
            operation: request.operation,
            scopeDigest: request.scopeDigest,
            payloadDigest: request.payloadDigest,
            isUserInitiated: request.isUserInitiated
        )

        guard request.toolName == "file_read_selected" else {
            switch runtime.resolve(request: invocation, now: now) {
            case .allow:
                return .denied("No iOS executor implementation for \(request.toolName)")
            case .needsUserAction(let reason):
                return .needsUserAction(reason)
            case .deny(let reason):
                return .denied(reason)
            }
        }

        let result = await runtime.executeFileReadSelected(request: invocation, now: now)
        switch result {
        case .success(let readResult):
            return .selectedFilePreview(readResult)
        case .needsUserAction(let reason):
            return .needsUserAction(reason)
        case .denied(let reason):
            return .denied(reason)
        case .failed(let message):
            return .failed(message)
        }
    }

    private func resolveWebMount(
        request: IOSLocalToolExecutionRequest,
        capability: IOSPlatformCapability
    ) -> IOSPlatformGateDecision {
        let policy = permissionStore.policy(for: capability)
        if policy == .disabled {
            return .deny(reason: "Disabled by AmberAgent policy")
        }
        if request.toolName == "wm_clear_session", !request.isUserInitiated {
            return .needsUserAction(reason: "Clearing WebMount cookies requires an explicit foreground user action")
        }
        if !request.isUserInitiated, !webMountController.settings.globalEnabled {
            return .needsUserAction(reason: "Enable WebMount in the foreground before using WebMount tools")
        }
        return .allow(capabilityId: capability.id)
    }

    func permissionsStatus(now: Date = Date()) -> IOSPermissionsStatusSnapshot {
        IOSPermissionsStatusSnapshot(
            generatedAt: now,
            platform: "iOS",
            capabilities: IOSCapabilityRegistry.capabilities.map { capability in
                let policy = permissionStore.policy(for: capability)
                let systemStatus = systemPermissionCoordinator.cachedStatus(for: capability, now: now)
                let canRequestInApp = IOSSystemPermissionCoordinator.canRequestInApp(
                    for: capability,
                    systemStatus: systemStatus.status
                )
                return IOSCapabilityStatusItem(
                    id: Self.snapshotId(for: capability),
                    title: capability.title,
                    summary: capability.summary,
                    domain: Self.snapshotDomain(for: capability),
                    status: capability.status.title,
                    systemStatus: systemStatus.status.title,
                    systemStatusMessage: systemStatus.message,
                    risk: capability.risk.title,
                    policy: policy.title,
                    requestKind: capability.requestKind.title,
                    requestEntryPoint: capability.requestEntryPoint,
                    canRequestInApp: canRequestInApp,
                    canOpenSettings: capability.canOpenSettings,
                    uiActionNames: capability.uiActionNames,
                    modelToolNames: capability.modelToolNames,
                    blockedToolNames: capability.blockedToolNames,
                    defaultEnabled: capability.defaultEnabled,
                    requiresFreshUserPresence: capability.gate.requiresFreshUserPresence,
                    allowRunScopedReuse: capability.gate.allowRunScopedReuse,
                    allowGlobalAutoApproval: capability.gate.allowGlobalAutoApproval,
                    requiredInfoPlistKeys: capability.requiredInfoPlistKeys,
                    requiredEntitlements: capability.requiredEntitlements,
                    requiredBackgroundModes: capability.requiredBackgroundModes,
                    requiredExtensionTargets: capability.requiredExtensionTargets,
                    reason: capability.unavailableReason,
                    executable: capability.status == .supported &&
                        policy != .disabled &&
                        !capability.modelToolNames.isEmpty
                )
            }
        )
    }

    func memoryToolWritePolicy(
        input: String,
        isUserInitiated: Bool
    ) -> IOSMemoryToolWritePolicy {
        guard IOSMemoryToolExecutor.requiresWriteApproval(input: input) else {
            return .allow
        }
        guard let capability = IOSCapabilityRegistry.capabilities.first(where: { $0.id == "ios.agent.memory_write" }) else {
            return .needsUserAction("Memory writes require foreground approval.")
        }

        switch permissionStore.policy(for: capability) {
        case .disabled:
            return .denied("Memory writes are disabled in AmberAgent tool policy.")
        case .askEveryTime, .allowOncePerRun:
            if isUserInitiated {
                return .allow
            }
            return .needsUserAction("Memory writes require explicit foreground approval before the model can change saved memories.")
        }
    }

    func webMountApprovalPreview(toolName: String, input: String) -> IOSWebMountToolApprovalPreview? {
        guard toolName == "wm_clear_session",
              let siteId = Self.siteId(fromWebMountInput: input),
              let site = webMountController.registry.site(id: siteId) else {
            return nil
        }
        return IOSWebMountToolApprovalPreview(
            toolName: toolName,
            siteId: site.id,
            siteName: site.displayName,
            host: site.homepageHost
        )
    }

    private static func siteId(fromWebMountInput input: String) -> String? {
        guard let data = input.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let siteId = object["site_id"] as? String else {
            return nil
        }
        let trimmed = siteId.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func snapshotId(for capability: IOSPlatformCapability) -> String {
        guard capability.id.hasPrefix("android.") else {
            return capability.id
        }
        return "ios.unavailable." + capability.id.dropFirst("android.".count)
    }

    private static func snapshotDomain(for capability: IOSPlatformCapability) -> String {
        if capability.id.hasPrefix("android."), capability.status == .unsupported {
            return "Unavailable on iOS"
        }
        return capability.domain.title
    }

    func requestForCurrentSelectedFile(isUserInitiated: Bool) -> IOSLocalToolExecutionRequest {
        let request = documentStore.requestForCurrentGrant(isUserInitiated: isUserInitiated)
        return IOSLocalToolExecutionRequest(
            toolName: request.toolName,
            operation: request.operation,
            scopeDigest: request.scopeDigest,
            payloadDigest: request.payloadDigest,
            isUserInitiated: request.isUserInitiated
        )
    }
}

// MARK: - iOS WebMount Core

enum IOSWebMountAuthKind: String, Codable, CaseIterable, Identifiable {
    case anonymous
    case cookie
    case oauth

    var id: String { rawValue }

    var title: String {
        switch self {
        case .anonymous: "Anonymous"
        case .cookie: "Cookie"
        case .oauth: "OAuth"
        }
    }
}

enum IOSWebMountRuntimeStatus: String, Codable, Equatable, Sendable {
    case idle
    case loading
    case ready
    case failed
}

struct IOSWebMountSite: Codable, Equatable, Hashable, Identifiable {
    let id: String
    var displayName: String
    var homepageURL: String
    var authKind: IOSWebMountAuthKind
    var loginCookieName: String?
    var nativeAdapterId: String?
    var iconKey: String?
    var oauthProviderId: String?
    var allowedHosts: [String]
    var enabled: Bool
    var addedAtMillis: Int64

    var homepageHost: String {
        URL(string: homepageURL)?.host?.lowercased() ?? homepageURL
    }

    static func seeds(nowMillis: Int64 = IOSWebMountClock.nowMillis()) -> [IOSWebMountSite] {
        [
            IOSWebMountSite(
                id: "hackernews",
                displayName: "Hacker News",
                homepageURL: "https://news.ycombinator.com",
                authKind: .anonymous,
                loginCookieName: nil,
                nativeAdapterId: "hackernews",
                iconKey: "hackernews",
                oauthProviderId: nil,
                allowedHosts: ["news.ycombinator.com"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "reddit",
                displayName: "Reddit",
                homepageURL: "https://www.reddit.com",
                authKind: .anonymous,
                loginCookieName: nil,
                nativeAdapterId: "reddit",
                iconKey: "reddit",
                oauthProviderId: nil,
                allowedHosts: ["www.reddit.com", "reddit.com"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "github",
                displayName: "GitHub",
                homepageURL: "https://github.com/login",
                authKind: .cookie,
                loginCookieName: "user_session",
                nativeAdapterId: "github",
                iconKey: "github",
                oauthProviderId: nil,
                allowedHosts: ["github.com"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "bilibili",
                displayName: "Bilibili",
                homepageURL: "https://passport.bilibili.com/login",
                authKind: .cookie,
                loginCookieName: "SESSDATA",
                nativeAdapterId: "bilibili",
                iconKey: "bilibili",
                oauthProviderId: nil,
                allowedHosts: ["passport.bilibili.com", "www.bilibili.com", "bilibili.com"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "x_com",
                displayName: "X.com",
                homepageURL: "https://x.com/i/flow/login",
                authKind: .cookie,
                loginCookieName: "auth_token",
                nativeAdapterId: nil,
                iconKey: "x_com",
                oauthProviderId: nil,
                allowedHosts: ["x.com", "twitter.com", "www.x.com"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "weibo",
                displayName: "微博",
                homepageURL: "https://m.weibo.cn",
                authKind: .cookie,
                loginCookieName: "SUB",
                nativeAdapterId: nil,
                iconKey: "weibo",
                oauthProviderId: nil,
                allowedHosts: ["m.weibo.cn", "weibo.cn", "weibo.com", "www.weibo.com"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "juejin",
                displayName: "掘金",
                homepageURL: "https://juejin.cn/login",
                authKind: .cookie,
                loginCookieName: "sessionid",
                nativeAdapterId: "juejin",
                iconKey: "juejin",
                oauthProviderId: nil,
                allowedHosts: ["juejin.cn", "www.juejin.cn"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "zhihu",
                displayName: "知乎",
                homepageURL: "https://www.zhihu.com/signin",
                authKind: .cookie,
                loginCookieName: "z_c0",
                nativeAdapterId: "zhihu",
                iconKey: "zhihu",
                oauthProviderId: nil,
                allowedHosts: ["www.zhihu.com", "zhihu.com"],
                enabled: false,
                addedAtMillis: nowMillis
            ),
            IOSWebMountSite(
                id: "feishu_docs",
                displayName: "飞书云文档",
                homepageURL: "https://www.feishu.cn/wiki",
                authKind: .oauth,
                loginCookieName: nil,
                nativeAdapterId: "feishu_docs",
                iconKey: "feishu_docs",
                oauthProviderId: "feishu",
                allowedHosts: ["www.feishu.cn", "feishu.cn"],
                enabled: false,
                addedAtMillis: nowMillis
            )
        ]
    }
}

@MainActor
@Observable
final class IOSWebMountRegistry {
    private let defaults: UserDefaults
    private let storageKey: String
    private let seededKey: String
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    var sites: [IOSWebMountSite]

    init(
        userDefaults: UserDefaults = .standard,
        storageKey: String = "app.amber.ios.webmount.sites.v1",
        seededKey: String = "app.amber.ios.webmount.seeded.v1"
    ) {
        self.defaults = userDefaults
        self.storageKey = storageKey
        self.seededKey = seededKey

        if let data = userDefaults.data(forKey: storageKey),
           let decoded = try? decoder.decode([IOSWebMountSite].self, from: data) {
            self.sites = decoded
        } else {
            self.sites = IOSWebMountSite.seeds()
            if let data = try? encoder.encode(self.sites) {
                userDefaults.set(data, forKey: storageKey)
                userDefaults.set(true, forKey: seededKey)
            }
        }
    }

    func site(id: String) -> IOSWebMountSite? {
        sites.first { $0.id == id }
    }

    func site(for url: URL) -> IOSWebMountSite? {
        sites.first { site in
            IOSWebMountURLPolicy.host(url.host, matchesAnyOf: site.allowedHosts)
        }
    }

    @discardableResult
    func add(_ site: IOSWebMountSite) -> Bool {
        guard !sites.contains(where: { $0.id == site.id }) else { return false }
        sites.insert(site, at: 0)
        persist()
        return true
    }

    @discardableResult
    func addCustomSite(
        displayName: String,
        homepageURL: String,
        needsLogin: Bool = true,
        loginCookieName: String? = nil
    ) throws -> IOSWebMountSite {
        guard let url = URL(string: homepageURL),
              let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              let host = url.host?.lowercased(),
              !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw IOSWebMountRegistryError.invalidSite
        }
        let baseId = "user_" + IOSWebMountRegistry.slug(displayName)
        var candidate = baseId
        var suffix = 2
        while sites.contains(where: { $0.id == candidate }) {
            candidate = "\(baseId)_\(suffix)"
            suffix += 1
        }
        let site = IOSWebMountSite(
            id: candidate,
            displayName: displayName.trimmingCharacters(in: .whitespacesAndNewlines),
            homepageURL: url.absoluteString,
            authKind: needsLogin ? .cookie : .anonymous,
            loginCookieName: needsLogin ? loginCookieName?.nilIfBlank : nil,
            nativeAdapterId: nil,
            iconKey: nil,
            oauthProviderId: nil,
            allowedHosts: [host],
            enabled: false,
            addedAtMillis: IOSWebMountClock.nowMillis()
        )
        add(site)
        return site
    }

    @discardableResult
    func remove(id: String) -> Bool {
        guard sites.contains(where: { $0.id == id }) else { return false }
        sites.removeAll { $0.id == id }
        persist()
        return true
    }

    @discardableResult
    func restoreMissingSeeds() -> Int {
        let existing = Set(sites.map(\.id))
        let missing = IOSWebMountSite.seeds().filter { !existing.contains($0.id) }
        guard !missing.isEmpty else { return 0 }
        sites.append(contentsOf: missing)
        persist()
        return missing.count
    }

    func setEnabled(id: String, enabled: Bool) {
        guard let index = sites.firstIndex(where: { $0.id == id }) else { return }
        sites[index].enabled = enabled
        persist()
    }

    private func persist() {
        guard let data = try? encoder.encode(sites) else { return }
        defaults.set(data, forKey: storageKey)
        defaults.set(true, forKey: seededKey)
    }

    private static func slug(_ value: String) -> String {
        let lowered = value.lowercased()
        let scalars = lowered.unicodeScalars.map { scalar -> Character in
            CharacterSet.alphanumerics.contains(scalar) ? Character(String(scalar)) : "_"
        }
        let collapsed = String(scalars)
            .split(separator: "_")
            .joined(separator: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        return collapsed.isEmpty ? "site" : String(collapsed.prefix(40))
    }
}

enum IOSWebMountRegistryError: Error {
    case invalidSite
}

@MainActor
@Observable
final class IOSWebMountSettings {
    var globalEnabled: Bool {
        didSet {
            if !globalEnabled, evalEnabled {
                evalEnabled = false
            }
            persist()
        }
    }
    var evalEnabled: Bool {
        didSet {
            if evalEnabled, !globalEnabled {
                evalEnabled = false
            }
            persist()
        }
    }
    var allowedHosts: Set<String> {
        didSet { persist() }
    }
    var allowedSchemes: Set<String> {
        didSet { persist() }
    }

    private let defaults: UserDefaults
    private let globalKey: String
    private let evalKey: String
    private let hostsKey: String
    private let schemesKey: String
    private var isLoading = true

    init(
        userDefaults: UserDefaults = .standard,
        globalKey: String = "app.amber.ios.webmount.globalEnabled.v1",
        evalKey: String = "app.amber.ios.webmount.evalEnabled.v1",
        hostsKey: String = "app.amber.ios.webmount.allowedHosts.v1",
        schemesKey: String = "app.amber.ios.webmount.allowedSchemes.v1"
    ) {
        self.defaults = userDefaults
        self.globalKey = globalKey
        self.evalKey = evalKey
        self.hostsKey = hostsKey
        self.schemesKey = schemesKey
        self.globalEnabled = userDefaults.object(forKey: globalKey) as? Bool ?? false
        self.evalEnabled = userDefaults.object(forKey: evalKey) as? Bool ?? false
        let seedHosts = IOSWebMountSite.seeds().flatMap(\.allowedHosts)
        self.allowedHosts = Set((userDefaults.array(forKey: hostsKey) as? [String]) ?? seedHosts)
        self.allowedSchemes = Set((userDefaults.array(forKey: schemesKey) as? [String]) ?? ["https"])
        self.isLoading = false
        if !globalEnabled, evalEnabled {
            self.evalEnabled = false
        }
        persist()
    }

    func addAllowedHosts(_ hosts: [String]) {
        let normalized = hosts.compactMap { IOSWebMountURLPolicy.normalizedHost($0) }
        guard !normalized.isEmpty else { return }
        allowedHosts.formUnion(normalized)
    }

    private func persist() {
        guard !isLoading else { return }
        defaults.set(globalEnabled, forKey: globalKey)
        defaults.set(evalEnabled && globalEnabled, forKey: evalKey)
        defaults.set(Array(allowedHosts).sorted(), forKey: hostsKey)
        defaults.set(Array(allowedSchemes).sorted(), forKey: schemesKey)
    }
}

enum IOSWebMountURLPolicyError: Error, Equatable, LocalizedError {
    case invalidURL
    case unsupportedScheme(String)
    case missingHost
    case hostNotAllowed(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            "Invalid URL"
        case .unsupportedScheme(let scheme):
            "Unsupported URL scheme: \(scheme)"
        case .missingHost:
            "URL host is missing"
        case .hostNotAllowed(let host):
            "Host is not in the WebMount allowlist: \(host)"
        }
    }
}

struct IOSWebMountURLPolicy {
    let allowedSchemes: Set<String>
    let allowedHosts: Set<String>

    @MainActor
    init(settings: IOSWebMountSettings, extraAllowedHosts: [String] = []) {
        self.allowedSchemes = Set(settings.allowedSchemes.map { $0.lowercased() })
        self.allowedHosts = Set(settings.allowedHosts.map { $0.lowercased() })
            .union(extraAllowedHosts.compactMap(Self.normalizedHost))
    }

    func validate(_ rawURL: String, site: IOSWebMountSite? = nil) -> Result<URL, IOSWebMountURLPolicyError> {
        guard let url = URL(string: rawURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = url.scheme?.lowercased() else {
            return .failure(.invalidURL)
        }
        guard allowedSchemes.contains(scheme) else {
            return .failure(.unsupportedScheme(scheme))
        }
        guard let host = Self.normalizedHost(url.host) else {
            return .failure(.missingHost)
        }
        let hosts = allowedHosts.union(site?.allowedHosts.compactMap(Self.normalizedHost) ?? [])
        guard Self.host(host, matchesAnyOf: Array(hosts)) else {
            return .failure(.hostNotAllowed(host))
        }
        return .success(url)
    }

    static func normalizedHost(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return trimmed.isEmpty ? nil : trimmed
    }

    static func host(_ rawHost: String?, matchesAnyOf allowedHosts: [String]) -> Bool {
        guard let host = normalizedHost(rawHost) else { return false }
        return allowedHosts.compactMap(normalizedHost).contains { allowed in
            host == allowed ||
                (host.hasPrefix("www.") && String(host.dropFirst(4)) == allowed) ||
                (allowed.hasPrefix("www.") && String(allowed.dropFirst(4)) == host)
        }
    }
}

struct IOSWebMountCookieSummary: Codable, Equatable {
    let siteId: String
    let cookieCount: Int
    let cookieNames: [String]
    let domains: [String]
    let hasLoginCookie: Bool?
    let redacted: Bool
}

struct IOSWebMountCookieClearResult: Codable, Equatable {
    let siteId: String
    let deletedCookieCount: Int
    let clearedWebsiteDataRecords: Int
}

@MainActor
protocol IOSWebMountCookieStoreProtocol: AnyObject {
    func summary(for site: IOSWebMountSite) async -> IOSWebMountCookieSummary
    func clearSession(for site: IOSWebMountSite) async -> IOSWebMountCookieClearResult
}

@MainActor
final class IOSWebMountCookieStore: IOSWebMountCookieStoreProtocol {
    private let dataStore: WKWebsiteDataStore

    init(dataStore: WKWebsiteDataStore? = nil) {
        self.dataStore = dataStore ?? WKWebsiteDataStore.default()
    }

    func summary(for site: IOSWebMountSite) async -> IOSWebMountCookieSummary {
        let allCookies = await allCookies()
        let cookies = allCookies.filter { cookie in
            IOSWebMountURLPolicy.host(cookie.domain, matchesAnyOf: site.allowedHosts)
        }
        let names = cookies.map(\.name).uniqued().sorted()
        let domains = cookies.map(\.domain).uniqued().sorted()
        let hasLoginCookie = site.loginCookieName.map { names.contains($0) }
        return IOSWebMountCookieSummary(
            siteId: site.id,
            cookieCount: cookies.count,
            cookieNames: names,
            domains: domains,
            hasLoginCookie: hasLoginCookie,
            redacted: true
        )
    }

    func clearSession(for site: IOSWebMountSite) async -> IOSWebMountCookieClearResult {
        let allCookies = await allCookies()
        let cookies = allCookies.filter { cookie in
            IOSWebMountURLPolicy.host(cookie.domain, matchesAnyOf: site.allowedHosts)
        }
        for cookie in cookies {
            await delete(cookie)
        }
        let dataRecords = await dataRecords()
        let records = dataRecords.filter { record in
            IOSWebMountURLPolicy.host(record.displayName, matchesAnyOf: site.allowedHosts)
        }
        if !records.isEmpty {
            await remove(records: records)
        }
        return IOSWebMountCookieClearResult(
            siteId: site.id,
            deletedCookieCount: cookies.count,
            clearedWebsiteDataRecords: records.count
        )
    }

    private func allCookies() async -> [HTTPCookie] {
        await withCheckedContinuation { continuation in
            dataStore.httpCookieStore.getAllCookies { cookies in
                continuation.resume(returning: cookies)
            }
        }
    }

    private func delete(_ cookie: HTTPCookie) async {
        await withCheckedContinuation { continuation in
            dataStore.httpCookieStore.delete(cookie) {
                continuation.resume()
            }
        }
    }

    private func dataRecords() async -> [WKWebsiteDataRecord] {
        await withCheckedContinuation { continuation in
            dataStore.fetchDataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes()) { records in
                continuation.resume(returning: records)
            }
        }
    }

    private func remove(records: [WKWebsiteDataRecord]) async {
        await withCheckedContinuation { continuation in
            dataStore.removeData(
                ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
                for: records
            ) {
                continuation.resume()
            }
        }
    }
}

struct IOSWebMountRuntimeSnapshot: Codable, Equatable, Sendable {
    let sessionId: String
    var status: IOSWebMountRuntimeStatus
    var requestedURL: String?
    var currentURL: String?
    var title: String?
    var estimatedProgress: Double
    var error: String?
    var updatedAtMillis: Int64

    static func idle(sessionId: String) -> IOSWebMountRuntimeSnapshot {
        IOSWebMountRuntimeSnapshot(
            sessionId: sessionId,
            status: .idle,
            requestedURL: nil,
            currentURL: nil,
            title: nil,
            estimatedProgress: 0,
            error: nil,
            updatedAtMillis: 0
        )
    }
}

@MainActor
protocol IOSWebMountRuntimeServicing: AnyObject {
    var snapshot: IOSWebMountRuntimeSnapshot { get }
    var webView: WKWebView? { get }
    func open(_ url: URL, timeoutMillis: UInt64) async -> IOSWebMountRuntimeSnapshot
    func state() async throws -> [String: Any]
    func extract(mode: String, maxChars: Int, maxLinks: Int) async throws -> [String: Any]
    func get(selector: String?, target: String?, kind: String, attrName: String?, maxChars: Int) async throws -> [String: Any]
    func back() async -> IOSWebMountRuntimeSnapshot
    func forward() async -> IOSWebMountRuntimeSnapshot
}

@MainActor
final class IOSWebMountWKRuntime: NSObject, ObservableObject, IOSWebMountRuntimeServicing, WKNavigationDelegate {
    let webView: WKWebView?
    @Published private(set) var snapshot: IOSWebMountRuntimeSnapshot

    private var loadSequence = 0
    private var pendingLoad: (id: Int, continuation: CheckedContinuation<IOSWebMountRuntimeSnapshot, Never>)?
    private var navigationPolicy: IOSWebMountURLPolicy?
    private var navigationSite: IOSWebMountSite?

    override init() {
        let sessionId = "ios_wm_" + String(UUID().uuidString.prefix(8))
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        self.webView = webView
        self.snapshot = .idle(sessionId: String(sessionId))
        super.init()
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = true
    }

    func setNavigationPolicy(_ policy: IOSWebMountURLPolicy, site: IOSWebMountSite?) {
        navigationPolicy = policy
        navigationSite = site
    }

    func open(_ url: URL, timeoutMillis: UInt64 = 30_000) async -> IOSWebMountRuntimeSnapshot {
        guard let webView else {
            snapshot.status = .failed
            snapshot.error = "WKWebView is unavailable"
            return snapshot
        }
        loadSequence += 1
        let loadId = loadSequence
        pendingLoad?.continuation.resume(returning: snapshot)
        pendingLoad = nil
        snapshot = IOSWebMountRuntimeSnapshot(
            sessionId: snapshot.sessionId,
            status: .loading,
            requestedURL: IOSWebMountRedactor.redactedURL(url.absoluteString),
            currentURL: IOSWebMountRedactor.redactedURL(webView.url?.absoluteString),
            title: webView.title,
            estimatedProgress: webView.estimatedProgress,
            error: nil,
            updatedAtMillis: IOSWebMountClock.nowMillis()
        )
        webView.load(URLRequest(url: url))
        return await withCheckedContinuation { continuation in
            pendingLoad = (loadId, continuation)
            Task { @MainActor [weak self] in
                let nanos = timeoutMillis * 1_000_000
                try? await Task.sleep(nanoseconds: nanos)
                guard let self,
                      let pendingLoad = self.pendingLoad,
                      pendingLoad.id == loadId else { return }
                self.snapshot.status = .failed
                self.snapshot.error = "load timed out after \(timeoutMillis)ms"
                self.snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
                self.pendingLoad = nil
                pendingLoad.continuation.resume(returning: self.snapshot)
            }
        }
    }

    func state() async throws -> [String: Any] {
        let bridgeState = try await evaluateJSON(IOSWebMountBridgeScripts.state)
        return bridgeState.merging(snapshot.dictionary(redactURLs: true)) { page, _ in page }
    }

    func extract(mode: String, maxChars: Int, maxLinks: Int) async throws -> [String: Any] {
        try await evaluateJSON(
            IOSWebMountBridgeScripts.extract(
                mode: mode,
                maxChars: maxChars,
                maxLinks: maxLinks
            )
        )
    }

    func get(
        selector: String?,
        target: String?,
        kind: String,
        attrName: String?,
        maxChars: Int
    ) async throws -> [String: Any] {
        try await evaluateJSON(
            IOSWebMountBridgeScripts.get(
                selector: selector,
                target: target,
                kind: kind,
                attrName: attrName,
                maxChars: maxChars
            )
        )
    }

    func back() async -> IOSWebMountRuntimeSnapshot {
        webView?.goBack()
        snapshot.status = .loading
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
        return snapshot
    }

    func forward() async -> IOSWebMountRuntimeSnapshot {
        webView?.goForward()
        snapshot.status = .loading
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
        return snapshot
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
    ) {
        guard let policy = navigationPolicy,
              let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        switch policy.validate(url.absoluteString, site: navigationSite) {
        case .success:
            decisionHandler(.allow)
        case .failure(let error):
            snapshot.status = .failed
            snapshot.requestedURL = IOSWebMountRedactor.redactedURL(url.absoluteString)
            snapshot.currentURL = IOSWebMountRedactor.redactedURL(webView.url?.absoluteString)
            snapshot.title = webView.title
            snapshot.error = error.localizedDescription
            snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
            completePendingLoad()
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        snapshot.status = .loading
        snapshot.currentURL = IOSWebMountRedactor.redactedURL(webView.url?.absoluteString)
        snapshot.estimatedProgress = webView.estimatedProgress
        snapshot.error = nil
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        snapshot.currentURL = IOSWebMountRedactor.redactedURL(webView.url?.absoluteString)
        snapshot.estimatedProgress = webView.estimatedProgress
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        snapshot.status = .ready
        snapshot.currentURL = IOSWebMountRedactor.redactedURL(webView.url?.absoluteString)
        snapshot.title = webView.title
        snapshot.estimatedProgress = 1
        snapshot.error = nil
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
        completePendingLoad()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        fail(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        fail(error)
    }

    private func fail(_ error: Error) {
        snapshot.status = .failed
        snapshot.currentURL = IOSWebMountRedactor.redactedURL(webView?.url?.absoluteString)
        snapshot.title = webView?.title
        snapshot.estimatedProgress = webView?.estimatedProgress ?? 0
        snapshot.error = error.localizedDescription
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
        completePendingLoad()
    }

    private func completePendingLoad() {
        guard let pendingLoad else { return }
        self.pendingLoad = nil
        pendingLoad.continuation.resume(returning: snapshot)
    }

    private func evaluateJSON(_ script: String) async throws -> [String: Any] {
        guard let webView else { throw IOSWebMountRuntimeError.webViewUnavailable }
        let jsonString: String = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            webView.evaluateJavaScript(script) { value, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let string = value as? String {
                    continuation.resume(returning: string)
                } else if let value {
                    continuation.resume(returning: "\(value)")
                } else {
                    continuation.resume(returning: "{}")
                }
            }
        }
        guard let data = jsonString.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw IOSWebMountRuntimeError.invalidBridgePayload
        }
        return object
    }
}

enum IOSWebMountRuntimeError: Error {
    case webViewUnavailable
    case invalidBridgePayload
}

enum IOSWebMountBridgeScripts {
    static let state = """
    (function(){
      function cleanUrl(raw){try{var u=new URL(raw);return u.origin+u.pathname;}catch(e){return "";}}
      var body=document.body;
      return JSON.stringify({
        url: cleanUrl(location.href),
        title: document.title || "",
        ready_state: document.readyState || "unknown",
        text_length: body && body.innerText ? body.innerText.length : 0,
        links_count: document.links ? document.links.length : 0,
        viewport: { width: window.innerWidth || 0, height: window.innerHeight || 0 },
        scroll: { x: window.scrollX || 0, y: window.scrollY || 0 }
      });
    })();
    """

    static func extract(mode: String, maxChars: Int, maxLinks: Int) -> String {
        let mode = jsString(mode)
        let maxChars = max(0, min(maxChars, 80_000))
        let maxLinks = max(0, min(maxLinks, 100))
        return """
        (function(){
          function cleanUrl(raw){try{var u=new URL(raw, location.href);return u.origin+u.pathname;}catch(e){return "";}}
          function cssPath(el){
            if(!el || !el.tagName) return "";
            var path=[];
            while(el && el.nodeType===1 && path.length<5){
              var part=el.tagName.toLowerCase();
              if(el.id){part += "#" + CSS.escape(el.id); path.unshift(part); break;}
              var parent=el.parentElement;
              if(parent){
                var peers=Array.prototype.filter.call(parent.children,function(x){return x.tagName===el.tagName;});
                if(peers.length>1) part += ":nth-of-type(" + (peers.indexOf(el)+1) + ")";
              }
              path.unshift(part); el=parent;
            }
            return path.join(" > ");
          }
          var mode=\(mode);
          var body=document.body;
          if(mode==="interactive" || mode==="snapshot"){
            var nodes=Array.prototype.slice.call(document.querySelectorAll("a,button,input,textarea,select,[role='button'],[role='link'],[role='tab'],[role='menuitem']"),0,80).map(function(el,idx){
              var rect=el.getBoundingClientRect();
              return { ref:"css:"+cssPath(el), tag:(el.tagName||"").toLowerCase(), text:(el.innerText||el.value||el.getAttribute("aria-label")||"").slice(0,240), href: el.href ? cleanUrl(el.href) : "", visible: !!(rect.width && rect.height) };
            });
            return JSON.stringify({ mode: mode, url: cleanUrl(location.href), nodes: nodes });
          }
          var text=(body && body.innerText ? body.innerText : "").slice(0,\(maxChars));
          var links=Array.prototype.slice.call(document.querySelectorAll("a[href]"),0,\(maxLinks)).map(function(a){
            return { text:(a.innerText||a.getAttribute("aria-label")||"").trim().slice(0,200), href: cleanUrl(a.href) };
          });
          return JSON.stringify({ mode:"readable", url: cleanUrl(location.href), title: document.title || "", text: text, links: links });
        })();
        """
    }

    static func get(selector: String?, target: String?, kind: String, attrName: String?, maxChars: Int) -> String {
        let selectorLiteral = jsString(selector ?? target?.removingPrefix("css:") ?? "body")
        let kindLiteral = jsString(kind)
        let attrLiteral = jsString(attrName ?? "")
        let maxChars = max(0, min(maxChars, 100_000))
        return """
        (function(){
          function cleanUrl(raw){try{var u=new URL(raw, location.href);return u.origin+u.pathname;}catch(e){return "";}}
          var selector=\(selectorLiteral), kind=\(kindLiteral), attr=\(attrLiteral);
          var el=null;
          try{ el=document.querySelector(selector); }catch(e){ return JSON.stringify({ ok:false, error:"invalid selector" }); }
          if(!el){ return JSON.stringify({ ok:false, error:"target not found", selector: selector }); }
          var value="";
          if(kind==="value"){ value=el.value || ""; }
          else if(kind==="attr"){ value=attr ? (el.getAttribute(attr) || "") : ""; if(attr==="href" || attr==="src") value=cleanUrl(value); }
          else if(kind==="html"){ value=el.outerHTML || ""; }
          else { value=el.innerText || el.textContent || ""; }
          return JSON.stringify({ ok:true, selector:selector, kind:kind, value:String(value).slice(0,\(maxChars)) });
        })();
        """
    }

    private static func jsString(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [value]),
              let array = String(data: data, encoding: .utf8),
              array.count >= 2 else {
            return "\"\""
        }
        return String(array.dropFirst().dropLast())
    }
}

struct IOSWebMountToolDescriptor: Equatable, Identifiable {
    let name: String
    let description: String
    let requiresUserAction: Bool

    var id: String { name }
}

enum IOSWebMountToolCatalog {
    static let descriptors: [IOSWebMountToolDescriptor] = [
        .init(name: "wm_stations", description: "List configured WebMount stations without exposing cookie values.", requiresUserAction: false),
        .init(name: "wm_open", description: "Open an allowlisted URL in a local WKWebView session.", requiresUserAction: false),
        .init(name: "wm_state", description: "Read current WKWebView status, title, redacted URL, and page state.", requiresUserAction: false),
        .init(name: "wm_extract", description: "Extract readable or interactive page content through a read-only bridge.", requiresUserAction: false),
        .init(name: "wm_get", description: "Read a single element text/value/attribute/html through a restricted bridge.", requiresUserAction: false),
        .init(name: "wm_back", description: "Navigate the current WebMount session backward.", requiresUserAction: false),
        .init(name: "wm_forward", description: "Navigate the current WebMount session forward.", requiresUserAction: false),
        .init(name: "wm_clear_session", description: "Clear cookies and website data for one station after explicit user action.", requiresUserAction: true)
    ]

    static let supportedToolNames = Set(descriptors.map(\.name))

    static let unsupportedToolNames: Set<String> = [
        "wm_eval",
        "wm_signed_fetch",
        "wm_oauth_connect",
        "wm_oauth_refresh",
        "wm_profile_synthesize",
        "wm_site_adapter"
    ]
}

@MainActor
final class IOSWebMountController {
    static let shared = IOSWebMountController()

    let registry: IOSWebMountRegistry
    let settings: IOSWebMountSettings
    let cookieStore: IOSWebMountCookieStoreProtocol
    let runtime: IOSWebMountRuntimeServicing

    var visibleRuntime: IOSWebMountWKRuntime? {
        runtime as? IOSWebMountWKRuntime
    }

    init(
        registry: IOSWebMountRegistry? = nil,
        settings: IOSWebMountSettings? = nil,
        cookieStore: IOSWebMountCookieStoreProtocol? = nil,
        runtime: IOSWebMountRuntimeServicing? = nil
    ) {
        self.registry = registry ?? IOSWebMountRegistry()
        self.settings = settings ?? IOSWebMountSettings()
        self.cookieStore = cookieStore ?? IOSWebMountCookieStore()
        self.runtime = runtime ?? IOSWebMountWKRuntime()
        self.settings.addAllowedHosts(self.registry.sites.flatMap(\.allowedHosts))
    }

    func openForUser(site: IOSWebMountSite) async -> IOSWebMountRuntimeSnapshot {
        let policy = IOSWebMountURLPolicy(settings: settings, extraAllowedHosts: registry.sites.flatMap(\.allowedHosts))
        switch policy.validate(site.homepageURL, site: site) {
        case .success(let url):
            (runtime as? IOSWebMountWKRuntime)?.setNavigationPolicy(policy, site: site)
            return await runtime.open(url, timeoutMillis: 30_000)
        case .failure(let error):
            return IOSWebMountRuntimeSnapshot(
                sessionId: runtime.snapshot.sessionId,
                status: .failed,
                requestedURL: IOSWebMountRedactor.redactedURL(site.homepageURL),
                currentURL: runtime.snapshot.currentURL,
                title: runtime.snapshot.title,
                estimatedProgress: runtime.snapshot.estimatedProgress,
                error: error.localizedDescription,
                updatedAtMillis: IOSWebMountClock.nowMillis()
            )
        }
    }

    func execute(toolName: String, input: String, isUserInitiated: Bool) async -> String {
        guard IOSWebMountToolCatalog.supportedToolNames.contains(toolName) else {
            return Self.unsupportedToolResult(toolName: toolName)
        }
        guard settings.globalEnabled else {
            return Self.json([
                "ok": false,
                "tool": toolName,
                "denied": true,
                "reason": "WebMount globalEnabled is off"
            ])
        }
        let args = Self.parseObject(input)
        do {
            switch toolName {
            case "wm_stations":
                return await stationsResult(args: args)
            case "wm_open":
                return try await openResult(args: args)
            case "wm_state":
                return try await stateResult()
            case "wm_extract":
                return try await extractResult(args: args)
            case "wm_get":
                return try await getResult(args: args)
            case "wm_back":
                return Self.json([
                    "ok": true,
                    "session_id": runtime.snapshot.sessionId,
                    "state": await runtime.back().dictionary(redactURLs: true)
                ])
            case "wm_forward":
                return Self.json([
                    "ok": true,
                    "session_id": runtime.snapshot.sessionId,
                    "state": await runtime.forward().dictionary(redactURLs: true)
                ])
            case "wm_clear_session":
                guard isUserInitiated else {
                    return Self.json([
                        "ok": false,
                        "tool": toolName,
                        "needs_user_action": true,
                        "reason": "Clearing WebMount cookies requires an explicit foreground user action"
                    ])
                }
                return try await clearSessionResult(args: args)
            default:
                return Self.unsupportedToolResult(toolName: toolName)
            }
        } catch {
            return Self.json([
                "ok": false,
                "tool": toolName,
                "error": error.localizedDescription
            ])
        }
    }

    static func unsupportedToolResult(toolName: String) -> String {
        json([
            "ok": false,
            "tool": toolName,
            "unsupported": true,
            "reason": "This WebMount capability is not implemented on iOS yet"
        ])
    }

    private func stationsResult(args: [String: Any]) async -> String {
        let authFilter = (args["auth_kind_filter"] as? String)?.lowercased().nilIfBlank
        var stations: [[String: Any]] = []
        for site in registry.sites {
            if let authFilter, site.authKind.rawValue != authFilter { continue }
            let summary = await cookieStore.summary(for: site)
            let loginStatus: String
            switch site.authKind {
            case .anonymous:
                loginStatus = "logged_in"
            case .cookie:
                if summary.hasLoginCookie == true {
                    loginStatus = "logged_in"
                } else if summary.hasLoginCookie == false {
                    loginStatus = "logged_out"
                } else {
                    loginStatus = "unknown"
                }
            case .oauth:
                loginStatus = "unsupported"
            }
            var payload: [String: Any] = [
                "id": site.id,
                "display_name": site.displayName,
                "url": IOSWebMountRedactor.redactedURL(site.homepageURL) ?? "",
                "auth_kind": site.authKind.rawValue,
                "enabled": site.enabled,
                "user_added": site.nativeAdapterId == nil,
                "login_status": loginStatus,
                "cookie_count": summary.cookieCount
            ]
            if let nativeAdapterId = site.nativeAdapterId {
                payload["native_adapter_id"] = nativeAdapterId
                payload["adapter_status"] = "unsupported_on_ios"
            }
            if site.authKind == .oauth {
                payload["oauth_status"] = "unsupported_on_ios"
            }
            stations.append(payload)
        }
        return Self.json([
            "ok": true,
            "global_enabled": settings.globalEnabled,
            "eval_enabled": settings.evalEnabled,
            "count": stations.count,
            "stations": stations
        ])
    }

    private func openResult(args: [String: Any]) async throws -> String {
        let site = siteFromArgs(args)
        guard site?.enabled ?? true else {
            return Self.json([
                "ok": false,
                "denied": true,
                "reason": "WebMount station is disabled",
                "site_id": site?.id ?? ""
            ])
        }
        let rawURL = (args["url"] as? String)?.nilIfBlank ?? site?.homepageURL
        guard let rawURL else {
            return Self.json(["ok": false, "error": "wm_open requires url or site_id"])
        }
        let policy = IOSWebMountURLPolicy(settings: settings, extraAllowedHosts: registry.sites.flatMap(\.allowedHosts))
        switch policy.validate(rawURL, site: site) {
        case .failure(let error):
            return Self.json([
                "ok": false,
                "denied": true,
                "reason": error.localizedDescription,
                "url": IOSWebMountRedactor.redactedURL(rawURL) ?? ""
            ])
        case .success(let url):
            let timeout = UInt64((args["timeout_ms"] as? Int) ?? 30_000).clamped(to: 1_000...60_000)
            (runtime as? IOSWebMountWKRuntime)?.setNavigationPolicy(policy, site: site)
            let snapshot = await runtime.open(url, timeoutMillis: timeout)
            return Self.json([
                "ok": snapshot.status != .failed,
                "session_id": snapshot.sessionId,
                "status": snapshot.status.rawValue,
                "url": snapshot.currentURL ?? snapshot.requestedURL ?? "",
                "title": snapshot.title ?? "",
                "error": snapshot.error ?? "",
                "waited": true
            ])
        }
    }

    private func stateResult() async throws -> String {
        let page = try await runtime.state()
        return Self.json([
            "ok": true,
            "session_id": runtime.snapshot.sessionId,
            "state": runtime.snapshot.dictionary(redactURLs: true),
            "page": IOSWebMountRedactor.redactedJSONObject(page)
        ])
    }

    private func extractResult(args: [String: Any]) async throws -> String {
        let mode = (args["mode"] as? String)?.nilIfBlank ?? "readable"
        let maxChars = ((args["max_chars"] as? Int) ?? 20_000).clamped(to: 0...80_000)
        let maxLinks = ((args["max_links"] as? Int) ?? 20).clamped(to: 0...100)
        let result = try await runtime.extract(mode: mode, maxChars: maxChars, maxLinks: maxLinks)
        return Self.json([
            "ok": true,
            "session_id": runtime.snapshot.sessionId,
            "result": IOSWebMountRedactor.redactedJSONObject(result)
        ])
    }

    private func getResult(args: [String: Any]) async throws -> String {
        let kind = (args["kind"] as? String)?.nilIfBlank ?? "text"
        let maxChars = ((args["max_chars"] as? Int) ?? 20_000).clamped(to: 0...100_000)
        let result = try await runtime.get(
            selector: args["selector"] as? String,
            target: args["target"] as? String,
            kind: kind,
            attrName: args["attr_name"] as? String,
            maxChars: maxChars
        )
        return Self.json([
            "ok": true,
            "session_id": runtime.snapshot.sessionId,
            "result": IOSWebMountRedactor.redactedJSONObject(result)
        ])
    }

    private func clearSessionResult(args: [String: Any]) async throws -> String {
        guard let siteId = (args["site_id"] as? String)?.nilIfBlank,
              let site = registry.site(id: siteId) else {
            return Self.json(["ok": false, "error": "wm_clear_session requires a valid site_id"])
        }
        let result = await cookieStore.clearSession(for: site)
        return Self.json([
            "ok": true,
            "site_id": result.siteId,
            "deleted_cookie_count": result.deletedCookieCount,
            "cleared_website_data_records": result.clearedWebsiteDataRecords,
            "redacted": true
        ])
    }

    private func siteFromArgs(_ args: [String: Any]) -> IOSWebMountSite? {
        if let siteId = (args["site_id"] as? String)?.nilIfBlank {
            return registry.site(id: siteId)
        }
        if let rawURL = args["url"] as? String,
           let url = URL(string: rawURL) {
            return registry.site(for: url)
        }
        return nil
    }

    private static func parseObject(_ input: String) -> [String: Any] {
        guard let data = input.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    static func json(_ object: Any) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return #"{"ok":false,"error":"Failed to encode WebMount result"}"#
        }
        return string
    }
}

enum IOSWebMountRedactor {
    static func redactedURL(_ raw: String?) -> String? {
        guard let raw, let components = URLComponents(string: raw) else { return raw }
        var safe = URLComponents()
        safe.scheme = components.scheme
        safe.host = components.host
        safe.port = components.port
        safe.path = components.path.isEmpty ? "/" : components.path
        return safe.string
    }

    static func redactedJSONObject(_ value: Any) -> Any {
        if let dict = value as? [String: Any] {
            var redacted: [String: Any] = [:]
            for (key, item) in dict {
                if key.lowercased().contains("url") || key.lowercased() == "href" || key.lowercased() == "src",
                   let string = item as? String {
                    redacted[key] = redactedURL(string) ?? string
                } else if key.lowercased().contains("authorization") ||
                            key.lowercased().contains("token") ||
                            key.lowercased().contains("cookie") {
                    redacted[key] = "***redacted***"
                } else {
                    redacted[key] = redactedJSONObject(item)
                }
            }
            return redacted
        }
        if let array = value as? [Any] {
            return array.map(redactedJSONObject)
        }
        return value
    }
}

enum IOSWebMountClock {
    static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

private extension IOSWebMountRuntimeSnapshot {
    func dictionary(redactURLs: Bool) -> [String: Any] {
        [
            "session_id": sessionId,
            "status": status.rawValue,
            "requested_url": redactURLs ? (requestedURL ?? "") : (requestedURL ?? ""),
            "current_url": redactURLs ? (currentURL ?? "") : (currentURL ?? ""),
            "title": title ?? "",
            "estimated_progress": estimatedProgress,
            "error": error ?? "",
            "updated_at_ms": updatedAtMillis
        ]
    }
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    func removingPrefix(_ prefix: String) -> String {
        hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
