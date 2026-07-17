import Foundation
import Combine
import Observation
import WebKit
#if canImport(UIKit)
import UIKit
#endif

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
    case ishExecuteResult(String)
    case ishHandoffResult(String)
    case webMountResult(String)
    case workspaceResult(String)
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

struct IOSWorkspaceToolApprovalPreview: Equatable {
    let toolName: String
    let action: String
    let target: String
    let isWrite: Bool
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
    let lastApprovalAction: String?
    let lastApprovalReason: String?
    let lastApprovalAt: Date?
}

@MainActor
final class IOSLocalToolExecutor {
    private let permissionStore: IOSPermissionStore
    private let documentStore: DocumentAccessStore
    private let systemPermissionCoordinator: IOSSystemPermissionCoordinator
    private let runtime: IOSToolRuntime
    private let webMountController: IOSWebMountController
    private let workspaceStore: IOSWorkspaceStore

    /// 全局自动批准：开启后普通工具自动放行。
    static var isGlobalAutoApproveEnabled: Bool {
        UserDefaults.standard.bool(forKey: "app.amber.ios.globalAutoApprove")
    }

    /// 高风险自动批准：开启后高风险工具也自动放行。
    static var isHighRiskAutoApproveEnabled: Bool {
        UserDefaults.standard.bool(forKey: "app.amber.ios.highRiskAutoApprove")
    }

    init(
        permissionStore: IOSPermissionStore,
        documentStore: DocumentAccessStore,
        workspaceStore: IOSWorkspaceStore = .shared,
        systemPermissionCoordinator: IOSSystemPermissionCoordinator? = nil,
        webMountController: IOSWebMountController? = nil
    ) {
        self.permissionStore = permissionStore
        self.documentStore = documentStore
        self.workspaceStore = workspaceStore
        self.systemPermissionCoordinator = systemPermissionCoordinator ?? IOSSystemPermissionCoordinator()
        self.runtime = IOSToolRuntime(permissionStore: permissionStore, documentStore: documentStore)
        self.webMountController = webMountController ?? IOSWebMountController.shared
    }

    var webMountGlobalEnabled: Bool {
        true
    }

    func execute(
        _ request: IOSLocalToolExecutionRequest,
        now: Date = Date()
    ) async -> IOSLocalToolExecutionOutput {
        if request.toolName == "permissions_status" {
            return .permissionsStatus(permissionsStatus(now: now))
        }
        if IOSEmbeddedIshToolCatalog.supportedToolNames.contains(request.toolName) {
            guard let capability = IOSCapabilityRegistry.capability(forToolName: request.toolName) else {
                return .denied("Unknown embedded iSH tool: \(request.toolName)")
            }
            switch resolveEmbeddedIshExecute(request: request, capability: capability) {
            case .allow:
                return .ishExecuteResult(await IOSEmbeddedIshExecuteExecutor.execute(input: request.operation))
            case .needsUserAction(let reason):
                return .needsUserAction(reason)
            case .deny(let reason):
                return .denied(reason)
            }
        }
        if IOSIshToolCatalog.supportedToolNames.contains(request.toolName) {
            guard let capability = IOSCapabilityRegistry.capability(forToolName: request.toolName) else {
                return .denied("Unknown iSH handoff tool: \(request.toolName)")
            }
            switch resolveIshHandoff(request: request, capability: capability) {
            case .allow:
                return .ishHandoffResult(IOSIshHandoffExecutor.execute(input: request.operation, now: now))
            case .needsUserAction(let reason):
                return .needsUserAction(reason)
            case .deny(let reason):
                return .denied(reason)
            }
        }
        if IOSWorkspaceToolCatalog.supportedToolNames.contains(request.toolName) {
            guard let capability = IOSCapabilityRegistry.capability(forToolName: request.toolName) else {
                return .denied("Unknown iOS Workspace tool: \(request.toolName)")
            }
            switch resolveWorkspace(request: request, capability: capability) {
            case .allow:
                let output = await workspaceStore.executeTool(
                    toolName: request.toolName,
                    input: request.operation
                )
                return .workspaceResult(output)
            case .needsUserAction(let reason):
                return .needsUserAction(reason)
            case .deny(let reason):
                return .denied(reason)
            }
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
        // 自动批准策略直接放行。
        if policy == .autoApprove || policy == .autoApproveHighRisk {
            return .allow(capabilityId: capability.id)
        }
        if !request.isUserInitiated {
            if request.toolName == "wm_clear_session" {
                return .needsUserAction(reason: "Clearing WebMount cookies requires an explicit foreground user action")
            }
            if IOSWebMountToolCatalog.descriptors.first(where: { $0.name == request.toolName })?.requiresUserAction == true {
                return .needsUserAction(reason: "This WebMount action requires explicit foreground user approval: \(request.toolName)")
            }
            if policy == .askEveryTime || capability.gate.requiresFreshUserPresence {
                if Self.isGlobalAutoApproveEnabled && (capability.risk != .high || Self.isHighRiskAutoApproveEnabled) {
                    return .allow(capabilityId: capability.id)
                }
                return .needsUserAction(reason: "WebMount browser tools require explicit foreground approval before the model can use the page session.")
            }
        }
        return .allow(capabilityId: capability.id)
    }

    private func resolveEmbeddedIshExecute(
        request: IOSLocalToolExecutionRequest,
        capability: IOSPlatformCapability
    ) -> IOSPlatformGateDecision {
        let policy = permissionStore.policy(for: capability)
        if policy == .disabled {
            return .deny(reason: "Disabled by AmberAgent embedded iSH policy")
        }
        guard request.isUserInitiated else {
            return .needsUserAction(reason: "Embedded iSH executes local Linux commands and returns stdout/stderr/exit code. It requires explicit foreground approval.")
        }
        return .allow(capabilityId: capability.id)
    }

    private func resolveIshHandoff(
        request: IOSLocalToolExecutionRequest,
        capability: IOSPlatformCapability
    ) -> IOSPlatformGateDecision {
        let policy = permissionStore.policy(for: capability)
        if policy == .disabled {
            return .deny(reason: "Disabled by AmberAgent iSH handoff policy")
        }
        guard request.isUserInitiated else {
            return .needsUserAction(reason: "iSH handoff prepares a paste-ready command for another app. It requires explicit foreground approval.")
        }
        return .allow(capabilityId: capability.id)
    }

    private func resolveWorkspace(
        request: IOSLocalToolExecutionRequest,
        capability: IOSPlatformCapability
    ) -> IOSPlatformGateDecision {
        let policy = permissionStore.policy(for: capability)
        if policy == .disabled {
            return .deny(reason: "Disabled by AmberAgent Workspace tool policy")
        }
        // 自动批准策略直接放行。
        if policy == .autoApprove || policy == .autoApproveHighRisk {
            return .allow(capabilityId: capability.id)
        }
        if !request.isUserInitiated {
            if IOSWorkspaceToolCatalog.writeToolNames.contains(request.toolName) {
                if !(policy == .autoApprove || policy == .autoApproveHighRisk) {
                    // Honor the global / high-risk auto-approve switches (writes are high-risk).
                    if Self.isGlobalAutoApproveEnabled && (capability.risk != .high || Self.isHighRiskAutoApproveEnabled) {
                        return .allow(capabilityId: capability.id)
                    }
                    return .needsUserAction(reason: "Workspace writes and deletes require explicit foreground approval.")
                }
            }
            if policy == .askEveryTime || capability.gate.requiresFreshUserPresence {
                if Self.isGlobalAutoApproveEnabled && (capability.risk != .high || Self.isHighRiskAutoApproveEnabled) {
                    return .allow(capabilityId: capability.id)
                }
                return .needsUserAction(reason: "Workspace reads require explicit foreground approval before the model can use saved files or artifacts.")
            }
        }
        return .allow(capabilityId: capability.id)
    }

    func permissionPolicy(capabilityId: String) -> IOSAgentPermissionPolicy? {
        guard let capability = IOSCapabilityRegistry.capabilities.first(where: { $0.id == capabilityId }) else {
            return nil
        }
        return permissionStore.policy(for: capability)
    }

    func permissionsStatus(now: Date = Date()) -> IOSPermissionsStatusSnapshot {
        IOSPermissionsStatusSnapshot(
            generatedAt: now,
            platform: "iOS",
            capabilities: IOSCapabilityRegistry.capabilities.map { capability in
                let policy = permissionStore.policy(for: capability)
                let systemStatus = systemPermissionCoordinator.cachedStatus(for: capability, now: now)
                let latestApproval = permissionStore.latestApproval(for: capability)
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
                    executable: capability.status != .unsupported &&
                        policy != .disabled &&
                        (!capability.modelToolNames.isEmpty || !capability.uiActionNames.isEmpty),
                    lastApprovalAction: latestApproval?.action.title,
                    lastApprovalReason: latestApproval?.reason,
                    lastApprovalAt: latestApproval?.createdAt
                )
            }
        )
    }

    @discardableResult
    func recordApproval(
        capabilityId: String,
        toolName: String,
        action: IOSToolApprovalAction,
        reason: String,
        runId: String = "",
        scopeDigest: String = "",
        payloadDigest: String = "",
        now: Date = Date()
    ) -> IOSToolApprovalRecord {
        permissionStore.recordApproval(
            capabilityId: capabilityId,
            toolName: toolName,
            action: action,
            reason: reason,
            runId: runId,
            scopeDigest: scopeDigest,
            payloadDigest: payloadDigest,
            now: now
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
            // Honor the global / high-risk auto-approve switches (writes are high-risk).
            if Self.isGlobalAutoApproveEnabled && (capability.risk != .high || Self.isHighRiskAutoApproveEnabled) {
                return .allow
            }
            return .needsUserAction("Memory writes require explicit foreground approval before the model can change saved memories.")
        case .autoApprove, .autoApproveHighRisk:
            return .allow
        }
    }

    func webMountApprovalPreview(toolName: String, input: String) -> IOSWebMountToolApprovalPreview? {
        guard IOSWebMountToolCatalog.supportedToolNames.contains(toolName) else {
            return nil
        }
        let object = Self.webMountInputObject(input)
        if let siteId = (object["site_id"] as? String)?.nilIfBlank,
           let site = webMountController.registry.site(id: siteId) {
            return IOSWebMountToolApprovalPreview(
                toolName: toolName,
                siteId: site.id,
                siteName: site.displayName,
                host: site.homepageHost
            )
        }
        if let rawURL = object["url"] as? String,
           let url = URL(string: rawURL),
           let site = webMountController.registry.site(for: url) {
            return IOSWebMountToolApprovalPreview(
                toolName: toolName,
                siteId: site.id,
                siteName: site.displayName,
                host: site.homepageHost
            )
        }
        let host = Self.redactedHost(from: object["url"] as? String)
            ?? Self.redactedHost(from: webMountController.runtime.snapshot.currentURL)
            ?? "WebMount"
        return IOSWebMountToolApprovalPreview(
            toolName: toolName,
            siteId: "current",
            siteName: "当前 WebMount 会话",
            host: host
        )
    }

    func workspaceApprovalPreview(toolName: String, input: String) -> IOSWorkspaceToolApprovalPreview? {
        guard IOSWorkspaceToolCatalog.supportedToolNames.contains(toolName) else {
            return nil
        }
        let object = Self.toolInputObject(input)
        let target = ((object["file_id"] as? String)?.nilIfBlank
            ?? (object["artifact_id"] as? String)?.nilIfBlank
            ?? (object["id"] as? String)?.nilIfBlank
            ?? (object["path"] as? String)?.nilIfBlank
            ?? "Workspace").trimmingCharacters(in: .whitespacesAndNewlines)
        let action: String
        switch toolName {
        case "workspace_file_read":
            action = "读取文件"
        case "workspace_file_list":
            action = "列出文件"
        case "workspace_file_search":
            action = "搜索文件"
        case "workspace_file_write":
            action = "写入文件"
        case "workspace_file_edit":
            action = "编辑文件"
        case "workspace_file_move":
            action = "移动文件"
        case "workspace_artifact_read":
            action = "读取 Artifact"
        case "workspace_artifact_delete":
            action = "删除 Artifact"
        default:
            action = toolName
        }
        return IOSWorkspaceToolApprovalPreview(
            toolName: toolName,
            action: action,
            target: target.isEmpty ? "Workspace" : target,
            isWrite: IOSWorkspaceToolCatalog.writeToolNames.contains(toolName)
        )
    }

    private static func siteId(fromWebMountInput input: String) -> String? {
        guard let siteId = webMountInputObject(input)["site_id"] as? String else {
            return nil
        }
        let trimmed = siteId.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func webMountInputObject(_ input: String) -> [String: Any] {
        toolInputObject(input)
    }

    private static func toolInputObject(_ input: String) -> [String: Any] {
        guard let data = input.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    private static func redactedHost(from rawURL: String?) -> String? {
        guard let rawURL,
              let components = URLComponents(string: rawURL),
              let host = components.host?.nilIfBlank else {
            return nil
        }
        return host
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
        self.globalEnabled = userDefaults.object(forKey: globalKey) as? Bool ?? true
        self.evalEnabled = userDefaults.object(forKey: evalKey) as? Bool ?? false
        let seedHosts = IOSWebMountSite.seeds().flatMap(\.allowedHosts)
        self.allowedHosts = Set((userDefaults.array(forKey: hostsKey) as? [String]) ?? seedHosts)
        self.allowedSchemes = Set((userDefaults.array(forKey: schemesKey) as? [String]) ?? ["http", "https"])
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

    func syncAllowedHosts(_ hosts: [String]) {
        let normalized = hosts.compactMap { IOSWebMountURLPolicy.normalizedHost($0) }
        allowedHosts = Set(normalized)
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
    var canGoBack: Bool
    var canGoForward: Bool
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
            canGoBack: false,
            canGoForward: false,
            error: nil,
            updatedAtMillis: 0
        )
    }
}

struct IOSWebMountScreenshotCapture: Equatable {
    let data: Data
    let width: Int
    let height: Int
    let format: String
}

@MainActor
protocol IOSWebMountRuntimeServicing: AnyObject {
    var snapshot: IOSWebMountRuntimeSnapshot { get }
    var webView: WKWebView? { get }
    func open(_ url: URL, timeoutMillis: UInt64) async -> IOSWebMountRuntimeSnapshot
    func state() async throws -> [String: Any]
    func extract(mode: String, maxChars: Int, maxLinks: Int) async throws -> [String: Any]
    func get(selector: String?, target: String?, kind: String, attrName: String?, maxChars: Int) async throws -> [String: Any]
    func interact(method: String, selector: String?, text: String?, options: [String: Any]) async throws -> [String: Any]
    func screenshot() async throws -> IOSWebMountScreenshotCapture
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
            canGoBack: webView.canGoBack,
            canGoForward: webView.canGoForward,
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

    /// Drives a page interaction (click/type/scroll/keys/select/find/wait) via a
    /// restricted JS bridge. Android WebMountInteractionTools parity. Only the
    /// listed methods are permitted; arbitrary JS eval stays disabled.
    func interact(method: String, selector: String?, text: String?, options: [String: Any]) async throws -> [String: Any] {
        try await evaluateJSON(
            IOSWebMountBridgeScripts.interact(
                method: method,
                selector: selector,
                text: text,
                options: options
            )
        )
    }

    func screenshot() async throws -> IOSWebMountScreenshotCapture {
        guard let webView else { throw IOSWebMountRuntimeError.webViewUnavailable }
#if canImport(UIKit)
        let image: UIImage = try await withCheckedThrowingContinuation { continuation in
            webView.takeSnapshot(with: nil) { image, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let image {
                    continuation.resume(returning: image)
                } else {
                    continuation.resume(throwing: IOSWebMountRuntimeError.invalidBridgePayload)
                }
            }
        }
        guard let data = image.pngData() else {
            throw IOSWebMountRuntimeError.invalidBridgePayload
        }
        return IOSWebMountScreenshotCapture(
            data: data,
            width: Int(image.size.width * image.scale),
            height: Int(image.size.height * image.scale),
            format: "png"
        )
#else
        throw IOSWebMountRuntimeError.webViewUnavailable
#endif
    }

    func back() async -> IOSWebMountRuntimeSnapshot {
        guard let webView, webView.canGoBack else {
            snapshot.canGoBack = webView?.canGoBack ?? false
            snapshot.canGoForward = webView?.canGoForward ?? false
            snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
            return snapshot
        }
        webView.goBack()
        snapshot.status = .loading
        snapshot.canGoBack = webView.canGoBack
        snapshot.canGoForward = webView.canGoForward
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
        return snapshot
    }

    func forward() async -> IOSWebMountRuntimeSnapshot {
        guard let webView, webView.canGoForward else {
            snapshot.canGoBack = webView?.canGoBack ?? false
            snapshot.canGoForward = webView?.canGoForward ?? false
            snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
            return snapshot
        }
        webView.goForward()
        snapshot.status = .loading
        snapshot.canGoBack = webView.canGoBack
        snapshot.canGoForward = webView.canGoForward
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
        snapshot.canGoBack = webView.canGoBack
        snapshot.canGoForward = webView.canGoForward
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
        snapshot.canGoBack = webView.canGoBack
        snapshot.canGoForward = webView.canGoForward
        snapshot.error = nil
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        snapshot.currentURL = IOSWebMountRedactor.redactedURL(webView.url?.absoluteString)
        snapshot.estimatedProgress = webView.estimatedProgress
        snapshot.canGoBack = webView.canGoBack
        snapshot.canGoForward = webView.canGoForward
        snapshot.updatedAtMillis = IOSWebMountClock.nowMillis()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        snapshot.status = .ready
        snapshot.currentURL = IOSWebMountRedactor.redactedURL(webView.url?.absoluteString)
        snapshot.title = webView.title
        snapshot.estimatedProgress = 1
        snapshot.canGoBack = webView.canGoBack
        snapshot.canGoForward = webView.canGoForward
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
        snapshot.canGoBack = webView?.canGoBack ?? false
        snapshot.canGoForward = webView?.canGoForward ?? false
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
              return {
                ref:"css:"+cssPath(el),
                tag:(el.tagName||"").toLowerCase(),
                text:(el.innerText||el.value||el.getAttribute("aria-label")||"").slice(0,240),
                href: el.href ? cleanUrl(el.href) : "",
                visible: !!(rect.width && rect.height),
                rect: {
                  x: Math.round(rect.x || 0),
                  y: Math.round(rect.y || 0),
                  width: Math.round(rect.width || 0),
                  height: Math.round(rect.height || 0)
                }
              };
            });
            if(mode==="interactive"){
              return JSON.stringify({ mode: mode, url: cleanUrl(location.href), nodes: nodes });
            }
            function nearbyText(el){
              var text="";
              if(el.getAttribute) text = el.getAttribute("alt") || el.getAttribute("title") || el.getAttribute("aria-label") || "";
              if(!text && el.parentElement) text = el.parentElement.innerText || "";
              return String(text || "").replace(/\\s+/g," ").trim().slice(0,240);
            }
            var candidates=Array.prototype.slice.call(document.querySelectorAll("img,iframe,canvas,video,svg,picture,h1,h2,h3,p,blockquote,article,section"),0,120).map(function(el){
              var rect=el.getBoundingClientRect();
              return {
                ref:"css:"+cssPath(el),
                tag:(el.tagName||"").toLowerCase(),
                src: el.currentSrc ? cleanUrl(el.currentSrc) : (el.src ? cleanUrl(el.src) : ""),
                href: el.href ? cleanUrl(el.href) : "",
                alt: el.getAttribute ? (el.getAttribute("alt") || "") : "",
                title: el.getAttribute ? (el.getAttribute("title") || "") : "",
                nearby_text: nearbyText(el),
                visible: !!(rect.width && rect.height && rect.bottom >= 0 && rect.right >= 0 && rect.top <= (window.innerHeight || 0) && rect.left <= (window.innerWidth || 0)),
                rect: {
                  x: Math.round(rect.x || 0),
                  y: Math.round(rect.y || 0),
                  width: Math.round(rect.width || 0),
                  height: Math.round(rect.height || 0)
                }
              };
            }).filter(function(item){ return item.visible && (item.rect.width || item.rect.height); });
            var visibleText=(body && body.innerText ? body.innerText : "").replace(/\\s+/g," ").trim().slice(0,1200);
            return JSON.stringify({
              mode: mode,
              url: cleanUrl(location.href),
              viewport: { width: window.innerWidth || 0, height: window.innerHeight || 0 },
              interactive_nodes: nodes,
              visual_candidates: candidates,
              visible_text: visibleText
            });
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

    /// Builds a restricted interaction script (click/type/scroll/select/find/wait).
    /// Only the listed methods run; arbitrary eval stays disabled. Returns JSON
    /// {ok, method, found, message}. Android WebMountInteractionTools parity.
    static func interact(method: String, selector: String?, text: String?, options: [String: Any]) -> String {
        let method = method.lowercased()
        let sel = jsString(selector ?? "")
        let txt = jsString(text ?? "")
        let amount = options["amount"] as? Int ?? 0
        let waitMs = max(0, min(options["wait_ms"] as? Int ?? 500, 5_000))

        // The script switches on method so only known interactions are reachable.
        return """
        (function(){
          function pick(sel){
            try { return document.querySelector(sel); } catch(e){ return null; }
          }
          var method = \(jsString(method));
          var sel = \(sel);
          var text = \(txt);
          var el = sel ? pick(sel) : null;

          if(method === "click" || method === "tap"){
            if(!el) return JSON.stringify({ok:false, method:method, found:false, message:"element not found"});
            el.scrollIntoView({block:"center"}); el.click();
            return JSON.stringify({ok:true, method:method, found:true, message:"clicked"});
          }
          if(method === "type" || method === "keys"){
            if(!el){ return JSON.stringify({ok:false, method:method, found:false, message:"element not found"}); }
            if("value" in el || el.tagName === "INPUT" || el.tagName === "TEXTAREA"){
              el.focus(); el.value = text; el.dispatchEvent(new Event("input",{bubbles:true}));
              return JSON.stringify({ok:true, method:method, found:true, message:"typed"});
            }
            return JSON.stringify({ok:false, method:method, found:true, message:"element not typeable"});
          }
          if(method === "scroll"){
            var dx = \(amount) || 0;
            var dy = \(options["dy"] as? Int ?? 0);
            if(el){ el.scrollIntoView({block:"center"}); }
            else { window.scrollBy(dx, dy || 400); }
            return JSON.stringify({ok:true, method:method, found:el!=null, message:"scrolled"});
          }
          if(method === "select"){
            if(el && "value" in el){ el.value = text; el.dispatchEvent(new Event("change",{bubbles:true})); return JSON.stringify({ok:true, method:method, found:true, message:"selected"}); }
            return JSON.stringify({ok:false, method:method, found:el!=null, message:"element not selectable"});
          }
          if(method === "find"){
            // Read-only: report whether the selector matches + a text snippet.
            if(!el) return JSON.stringify({ok:false, method:"find", found:false, message:"not found"});
            var snippet = (el.innerText || el.textContent || "").substring(0, 160);
            return JSON.stringify({ok:true, method:"find", found:true, snippet:snippet});
          }
          if(method === "wait"){
            return JSON.stringify({ok:true, method:"wait", found:true, message:"waited " + \(waitMs) + "ms"});
          }
          return JSON.stringify({ok:false, method:method, found:false, message:"unknown interaction method"});
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

struct IOSWebMountSessionRecord: Equatable, Identifiable {
    let id: String
    let siteId: String?
    let siteName: String?
    let title: String
    let redactedURL: String
    let status: String
    let canGoBack: Bool
    let canGoForward: Bool
    let lastActivityMillis: Int64
    let isCurrent: Bool

    func dictionary() -> [String: Any] {
        [
            "session_id": id,
            "site_id": siteId ?? "",
            "site_name": siteName ?? "",
            "title": title,
            "url": redactedURL,
            "status": status,
            "can_go_back": canGoBack,
            "can_go_forward": canGoForward,
            "last_activity_ms": lastActivityMillis,
            "is_current": isCurrent
        ]
    }
}

enum IOSWebMountSessionError: Error, LocalizedError {
    case sessionNotFound(String)
    case cannotCloseOnlySession

    var errorDescription: String? {
        switch self {
        case .sessionNotFound(let sessionId):
            "WebMount session was not found: \(sessionId)"
        case .cannotCloseOnlySession:
            "Cannot close the only WebMount session."
        }
    }
}

@MainActor
@Observable
final class IOSWebMountSessionStore {
    let maxSessions: Int

    private(set) var currentSessionId: String
    @ObservationIgnored private var runtimes: [String: IOSWebMountRuntimeServicing] = [:]
    @ObservationIgnored private var siteIds: [String: String] = [:]
    @ObservationIgnored private var siteNames: [String: String] = [:]
    @ObservationIgnored private var lastActivityMillis: [String: Int64] = [:]
    @ObservationIgnored private let runtimeFactory: () -> IOSWebMountRuntimeServicing

    init(
        initialRuntime: IOSWebMountRuntimeServicing? = nil,
        maxSessions: Int = 3,
        runtimeFactory: @escaping () -> IOSWebMountRuntimeServicing = { IOSWebMountWKRuntime() }
    ) {
        self.maxSessions = max(1, maxSessions)
        self.runtimeFactory = runtimeFactory
        let runtime = initialRuntime ?? runtimeFactory()
        let sessionId = runtime.snapshot.sessionId
        self.currentSessionId = sessionId
        self.runtimes[sessionId] = runtime
        self.lastActivityMillis[sessionId] = IOSWebMountClock.nowMillis()
    }

    var currentRuntime: IOSWebMountRuntimeServicing {
        if let runtime = runtimes[currentSessionId] {
            return runtime
        }
        let runtime = runtimeFactory()
        let sessionId = runtime.snapshot.sessionId
        runtimes[sessionId] = runtime
        currentSessionId = sessionId
        lastActivityMillis[sessionId] = IOSWebMountClock.nowMillis()
        return runtime
    }

    var records: [IOSWebMountSessionRecord] {
        runtimes.keys
            .sorted { lhs, rhs in
                if lhs == currentSessionId { return true }
                if rhs == currentSessionId { return false }
                return (lastActivityMillis[lhs] ?? 0) > (lastActivityMillis[rhs] ?? 0)
            }
            .compactMap { sessionId in
                guard let runtime = runtimes[sessionId] else { return nil }
                let snapshot = runtime.snapshot
                return IOSWebMountSessionRecord(
                    id: sessionId,
                    siteId: siteIds[sessionId],
                    siteName: siteNames[sessionId],
                    title: snapshot.title?.nilIfBlank ?? "未命名页面",
                    redactedURL: snapshot.currentURL ?? snapshot.requestedURL ?? "",
                    status: snapshot.status.rawValue,
                    canGoBack: snapshot.canGoBack,
                    canGoForward: snapshot.canGoForward,
                    lastActivityMillis: lastActivityMillis[sessionId] ?? snapshot.updatedAtMillis,
                    isCurrent: sessionId == currentSessionId
                )
            }
    }

    func runtime(sessionId requestedSessionId: String?, makeCurrent: Bool = true) throws -> IOSWebMountRuntimeServicing {
        let sessionId = requestedSessionId?.nilIfBlank ?? currentSessionId
        guard let runtime = runtimes[sessionId] else {
            throw IOSWebMountSessionError.sessionNotFound(sessionId)
        }
        touch(sessionId: sessionId, makeCurrent: makeCurrent)
        return runtime
    }

    @discardableResult
    func newSession(site: IOSWebMountSite? = nil) -> IOSWebMountSessionRecord {
        if runtimes.count >= maxSessions {
            evictLeastRecentlyUsedSession()
        }
        let runtime = runtimeFactory()
        let sessionId = runtime.snapshot.sessionId
        runtimes[sessionId] = runtime
        currentSessionId = sessionId
        lastActivityMillis[sessionId] = IOSWebMountClock.nowMillis()
        tag(sessionId: sessionId, site: site)
        return records.first { $0.id == sessionId } ?? fallbackRecord(for: runtime, sessionId: sessionId)
    }

    @discardableResult
    func close(sessionId: String) throws -> IOSWebMountSessionRecord? {
        guard runtimes[sessionId] != nil else {
            throw IOSWebMountSessionError.sessionNotFound(sessionId)
        }
        if runtimes.count == 1 {
            runtimes.removeValue(forKey: sessionId)
            siteIds.removeValue(forKey: sessionId)
            siteNames.removeValue(forKey: sessionId)
            lastActivityMillis.removeValue(forKey: sessionId)
            let record = newSession()
            return record
        }
        runtimes.removeValue(forKey: sessionId)
        siteIds.removeValue(forKey: sessionId)
        siteNames.removeValue(forKey: sessionId)
        lastActivityMillis.removeValue(forKey: sessionId)
        if currentSessionId == sessionId {
            currentSessionId = runtimes.keys.max {
                (lastActivityMillis[$0] ?? 0) < (lastActivityMillis[$1] ?? 0)
            } ?? runtimes.keys.first ?? currentSessionId
        }
        return records.first { $0.id == currentSessionId }
    }

    func tag(sessionId: String, site: IOSWebMountSite?) {
        guard runtimes[sessionId] != nil else { return }
        if let site {
            siteIds[sessionId] = site.id
            siteNames[sessionId] = site.displayName
        }
    }

    func touch(sessionId: String, makeCurrent: Bool = true) {
        guard runtimes[sessionId] != nil else { return }
        if makeCurrent {
            currentSessionId = sessionId
        }
        lastActivityMillis[sessionId] = IOSWebMountClock.nowMillis()
    }

    private func evictLeastRecentlyUsedSession() {
        let candidate = runtimes.keys
            .filter { $0 != currentSessionId }
            .min { (lastActivityMillis[$0] ?? 0) < (lastActivityMillis[$1] ?? 0) }
            ?? currentSessionId
        runtimes.removeValue(forKey: candidate)
        siteIds.removeValue(forKey: candidate)
        siteNames.removeValue(forKey: candidate)
        lastActivityMillis.removeValue(forKey: candidate)
        if currentSessionId == candidate, let next = runtimes.keys.first {
            currentSessionId = next
        }
    }

    private func fallbackRecord(for runtime: IOSWebMountRuntimeServicing, sessionId: String) -> IOSWebMountSessionRecord {
        let snapshot = runtime.snapshot
        return IOSWebMountSessionRecord(
            id: sessionId,
            siteId: siteIds[sessionId],
            siteName: siteNames[sessionId],
            title: snapshot.title?.nilIfBlank ?? "Untitled",
            redactedURL: snapshot.currentURL ?? snapshot.requestedURL ?? "",
            status: snapshot.status.rawValue,
            canGoBack: snapshot.canGoBack,
            canGoForward: snapshot.canGoForward,
            lastActivityMillis: lastActivityMillis[sessionId] ?? snapshot.updatedAtMillis,
            isCurrent: sessionId == currentSessionId
        )
    }
}

enum IOSWebMountScreenshotArtifactStore {
    static func save(_ capture: IOSWebMountScreenshotCapture, sessionId: String) throws -> [String: Any] {
        let fileManager = FileManager.default
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = root
            .appendingPathComponent("AmberWorkspace", isDirectory: true)
            .appendingPathComponent("WebMount", isDirectory: true)
            .appendingPathComponent("screenshots", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let artifactId = "wmshot-\(IOSWebMountClock.nowMillis())-\(sessionId)"
        let fileName = "\(artifactId).\(capture.format)"
        let destination = directory.appendingPathComponent(fileName, isDirectory: false)
        try capture.data.write(to: destination, options: [.atomic])
        return [
            "artifact_id": artifactId,
            "local_ref": "Workspace/WebMount/screenshots/\(fileName)",
            "format": capture.format,
            "width": capture.width,
            "height": capture.height,
            "size_bytes": capture.data.count
        ]
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
        .init(name: "wm_tab_list", description: "List up to three foreground iOS WebMount sessions.", requiresUserAction: false),
        .init(name: "wm_tab_new", description: "Create a new foreground iOS WebMount session, bounded to three sessions.", requiresUserAction: false),
        .init(name: "wm_tab_close", description: "Close one foreground iOS WebMount session by session_id.", requiresUserAction: false),
        .init(name: "wm_open", description: "Open an allowlisted URL in a local WKWebView session.", requiresUserAction: false),
        .init(name: "wm_state", description: "Read current WKWebView status, title, redacted URL, and page state.", requiresUserAction: false),
        .init(name: "wm_observe", description: "Read state, visible text, links, interactive elements, and visual candidates without cookies or headers.", requiresUserAction: false),
        .init(name: "wm_extract", description: "Extract readable or interactive page content through a read-only bridge.", requiresUserAction: false),
        .init(name: "wm_get", description: "Read a single element text/value/attribute through a restricted bridge. Raw HTML reads are disabled on iOS.", requiresUserAction: false),
        .init(name: "wm_visual_snapshot", description: "Return viewport visual candidates from DOM rectangles without calling an external vision model.", requiresUserAction: false),
        .init(name: "wm_screenshot", description: "Capture the current viewport to a local WebMount artifact after foreground approval.", requiresUserAction: true),
        .init(name: "wm_back", description: "Navigate the current WebMount session backward.", requiresUserAction: false),
        .init(name: "wm_forward", description: "Navigate the current WebMount session forward.", requiresUserAction: false),
        .init(name: "wm_clear_session", description: "Clear cookies and website data for one station after explicit user action.", requiresUserAction: true),
        .init(name: "wm_site_add", description: "Add an iOS WebMount station and sync the URL allowlist after foreground approval.", requiresUserAction: true),
        .init(name: "wm_site_remove", description: "Remove an iOS WebMount station and sync the URL allowlist after foreground approval. Cookies are not cleared.", requiresUserAction: true),
        .init(name: "wm_click", description: "Click an element by CSS selector on the current WebMount page.", requiresUserAction: false),
        .init(name: "wm_tap", description: "Tap a coordinate or target on the current WebMount page.", requiresUserAction: false),
        .init(name: "wm_type", description: "Type text into an input element by CSS selector.", requiresUserAction: false),
        .init(name: "wm_keys", description: "Send a short key sequence to the current WebMount page or focused field.", requiresUserAction: false),
        .init(name: "wm_scroll", description: "Scroll the page or an element into view.", requiresUserAction: false),
        .init(name: "wm_select", description: "Select an option value in a <select> element by CSS selector.", requiresUserAction: false),
        .init(name: "wm_find", description: "Read-only check whether a CSS selector matches on the current page, with a text snippet.", requiresUserAction: false),
        .init(name: "wm_wait", description: "Pause for a short bounded wait (max 5000ms) before the next action.", requiresUserAction: false)
    ]

    static let supportedToolNames = Set(descriptors.map(\.name))

    static let unsupportedToolNames: Set<String> = [
        "wm_eval",
        "wm_signed_fetch",
        "wm_network_inspect",
        "wm_fetch_replay",
        "wm_recipe_candidates",
        "wm_oauth_connect",
        "wm_oauth_refresh",
        "wm_profile_synthesize",
        "wm_site_adapter",
        "wm_visual_read"
    ]
}

@MainActor
final class IOSWebMountController {
    static let shared = IOSWebMountController()

    let registry: IOSWebMountRegistry
    let settings: IOSWebMountSettings
    let cookieStore: IOSWebMountCookieStoreProtocol
    let sessionStore: IOSWebMountSessionStore

    var runtime: IOSWebMountRuntimeServicing {
        sessionStore.currentRuntime
    }

    var visibleRuntime: IOSWebMountWKRuntime? {
        runtime as? IOSWebMountWKRuntime
    }

    init(
        registry: IOSWebMountRegistry? = nil,
        settings: IOSWebMountSettings? = nil,
        cookieStore: IOSWebMountCookieStoreProtocol? = nil,
        runtime: IOSWebMountRuntimeServicing? = nil,
        runtimeFactory: (() -> IOSWebMountRuntimeServicing)? = nil
    ) {
        self.registry = registry ?? IOSWebMountRegistry()
        self.settings = settings ?? IOSWebMountSettings()
        self.cookieStore = cookieStore ?? IOSWebMountCookieStore()
        let factory = runtimeFactory ?? { IOSWebMountWKRuntime() }
        self.sessionStore = IOSWebMountSessionStore(initialRuntime: runtime ?? factory(), runtimeFactory: factory)
        self.settings.syncAllowedHosts(self.registry.sites.flatMap(\.allowedHosts))
    }

    func openForUser(site: IOSWebMountSite) async -> IOSWebMountRuntimeSnapshot {
        let runtime = sessionStore.currentRuntime
        let policy = IOSWebMountURLPolicy(settings: settings, extraAllowedHosts: registry.sites.flatMap(\.allowedHosts))
        switch policy.validate(site.homepageURL, site: site) {
        case .success(let url):
            (runtime as? IOSWebMountWKRuntime)?.setNavigationPolicy(policy, site: site)
            sessionStore.tag(sessionId: runtime.snapshot.sessionId, site: site)
            let snapshot = await runtime.open(url, timeoutMillis: 30_000)
            sessionStore.touch(sessionId: snapshot.sessionId)
            return snapshot
        case .failure(let error):
            return IOSWebMountRuntimeSnapshot(
                sessionId: runtime.snapshot.sessionId,
                status: .failed,
                requestedURL: IOSWebMountRedactor.redactedURL(site.homepageURL),
                currentURL: runtime.snapshot.currentURL,
                title: runtime.snapshot.title,
                estimatedProgress: runtime.snapshot.estimatedProgress,
                canGoBack: runtime.snapshot.canGoBack,
                canGoForward: runtime.snapshot.canGoForward,
                error: error.localizedDescription,
                updatedAtMillis: IOSWebMountClock.nowMillis()
            )
        }
    }

    func execute(toolName: String, input: String, isUserInitiated: Bool) async -> String {
        guard IOSWebMountToolCatalog.supportedToolNames.contains(toolName) else {
            return Self.unsupportedToolResult(toolName: toolName)
        }
        let args = Self.parseObject(input)
        do {
            switch toolName {
            case "wm_stations":
                return await stationsResult(args: args)
            case "wm_tab_list":
                return tabListResult()
            case "wm_tab_new":
                return tabNewResult(args: args)
            case "wm_tab_close":
                return try tabCloseResult(args: args)
            case "wm_open":
                return try await openResult(args: args)
            case "wm_state":
                return try await stateResult(args: args)
            case "wm_observe":
                return try await observeResult(args: args)
            case "wm_extract":
                return try await extractResult(args: args)
            case "wm_get":
                return try await getResult(args: args)
            case "wm_visual_snapshot":
                return try await visualSnapshotResult(args: args)
            case "wm_screenshot":
                guard isUserInitiated else {
                    return Self.json([
                        "ok": false,
                        "tool": toolName,
                        "needs_user_action": true,
                        "reason": "Viewport screenshots require an explicit foreground user action"
                    ])
                }
                return try await screenshotResult(args: args)
            case "wm_back":
                let runtime = try sessionRuntime(from: args)
                let snapshot = await runtime.back()
                sessionStore.touch(sessionId: snapshot.sessionId)
                return Self.json([
                    "ok": true,
                    "session_id": snapshot.sessionId,
                    "state": snapshot.dictionary(redactURLs: true)
                ])
            case "wm_forward":
                let runtime = try sessionRuntime(from: args)
                let snapshot = await runtime.forward()
                sessionStore.touch(sessionId: snapshot.sessionId)
                return Self.json([
                    "ok": true,
                    "session_id": snapshot.sessionId,
                    "state": snapshot.dictionary(redactURLs: true)
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
            case "wm_site_add":
                guard isUserInitiated else {
                    return Self.json([
                        "ok": false,
                        "tool": toolName,
                        "needs_user_action": true,
                        "reason": "Adding a WebMount station requires an explicit foreground user action"
                    ])
                }
                return try siteAddResult(args: args)
            case "wm_site_remove":
                guard isUserInitiated else {
                    return Self.json([
                        "ok": false,
                        "tool": toolName,
                        "needs_user_action": true,
                        "reason": "Removing a WebMount station requires an explicit foreground user action"
                    ])
                }
                return try siteRemoveResult(args: args)
            case "wm_click", "wm_type", "wm_scroll", "wm_select", "wm_find", "wm_wait", "wm_keys", "wm_tap":
                // Page-interaction tools (Android WebMountInteractionTools parity).
                // Map the tool name to an interaction method and run the restricted
                // JS bridge. Arbitrary eval stays disabled.
                let runtime = try sessionRuntime(from: args)
                let method: String
                switch toolName {
                case "wm_click", "wm_tap": method = "click"
                case "wm_type", "wm_keys": method = "type"
                case "wm_scroll": method = "scroll"
                case "wm_select": method = "select"
                case "wm_find": method = "find"
                case "wm_wait": method = "wait"
                default: method = "click"
                }
                let selector = args["selector"] as? String ?? args["target"] as? String
                let text = args["text"] as? String ?? args["value"] as? String
                var options = args
                if options["dy"] == nil, let byY = args["by_y"] {
                    options["dy"] = byY
                }
                if options["wait_ms"] == nil, let timeout = args["timeout_ms"] {
                    options["wait_ms"] = timeout
                }
                let result = try await runtime.interact(method: method, selector: selector, text: text, options: options)
                sessionStore.touch(sessionId: runtime.snapshot.sessionId)
                var payload = result
                payload["ok"] = (result["ok"] as? Bool ?? false)
                payload["tool"] = toolName
                payload["session_id"] = runtime.snapshot.sessionId
                return Self.json(payload)
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
        let reason: String
        switch toolName {
        case "wm_visual_read":
            reason = "wm_visual_read is unsupported on iOS because it requires an external vision provider and a separate privacy approval path."
        case "wm_signed_fetch", "wm_network_inspect", "wm_fetch_replay", "wm_recipe_candidates":
            reason = "This network replay capability is unsupported on iOS until WebMount has isolated signed-fetch and network-log handling."
        case "wm_eval":
            reason = "Arbitrary JavaScript evaluation is disabled on iOS WebMount by default."
        default:
            reason = "This WebMount capability is not implemented on iOS yet"
        }
        return json([
            "ok": false,
            "tool": toolName,
            "unsupported": true,
            "reason": reason
        ])
    }

    private func sessionRuntime(from args: [String: Any]) throws -> IOSWebMountRuntimeServicing {
        try sessionStore.runtime(sessionId: args["session_id"] as? String, makeCurrent: true)
    }

    private func tabListResult() -> String {
        Self.json([
            "ok": true,
            "tool": "wm_tab_list",
            "current_session_id": sessionStore.currentSessionId,
            "max_sessions": sessionStore.maxSessions,
            "count": sessionStore.records.count,
            "sessions": sessionStore.records.map { $0.dictionary() }
        ])
    }

    private func tabNewResult(args: [String: Any]) -> String {
        let site = siteFromArgs(args)
        let record = sessionStore.newSession(site: site)
        return Self.json([
            "ok": true,
            "tool": "wm_tab_new",
            "session_id": record.id,
            "current_session_id": sessionStore.currentSessionId,
            "max_sessions": sessionStore.maxSessions,
            "session": record.dictionary(),
            "sessions": sessionStore.records.map { $0.dictionary() }
        ])
    }

    private func tabCloseResult(args: [String: Any]) throws -> String {
        let sessionId = (args["session_id"] as? String)?.nilIfBlank ?? sessionStore.currentSessionId
        let next = try sessionStore.close(sessionId: sessionId)
        return Self.json([
            "ok": true,
            "tool": "wm_tab_close",
            "closed_session_id": sessionId,
            "current_session_id": sessionStore.currentSessionId,
            "current_session": next?.dictionary() ?? [:],
            "sessions": sessionStore.records.map { $0.dictionary() }
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
                loginStatus = "unknown"
            }
            var payload: [String: Any] = [
                "id": site.id,
                "display_name": site.displayName,
                "url": IOSWebMountRedactor.redactedURL(site.homepageURL) ?? "",
                "auth_kind": site.authKind.rawValue,
                "enabled": site.enabled,
                "user_added": site.nativeAdapterId == nil,
                "login_status": loginStatus,
                "cookie_count": summary.cookieCount,
                "cookie_names": summary.cookieNames,
                "native_adapter_id": site.nativeAdapterId ?? "",
                "adapter_status": site.nativeAdapterId == nil ? "not_configured" : "unsupported_on_ios",
                "oauth_token_present": false,
                "redacted": true
            ]
            if site.authKind == .oauth {
                payload["oauth_status"] = "unsupported_on_ios"
            }
            stations.append(payload)
        }
        return Self.json([
            "ok": true,
            "global_enabled": true,
            "eval_enabled": settings.evalEnabled,
            "count": stations.count,
            "stations": stations
        ])
    }

    private func openResult(args: [String: Any]) async throws -> String {
        let site = siteFromArgs(args)
        guard let site else {
            return Self.json([
                "ok": false,
                "denied": true,
                "reason": "wm_open requires a registered WebMount station. Add or restore the site before opening this URL."
            ])
        }
        guard site.enabled else {
            return Self.json([
                "ok": false,
                "denied": true,
                "reason": "WebMount station is disabled",
                "site_id": site.id
            ])
        }
        let rawURL = (args["url"] as? String)?.nilIfBlank ?? site.homepageURL
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
            let runtime = try sessionRuntime(from: args)
            (runtime as? IOSWebMountWKRuntime)?.setNavigationPolicy(policy, site: site)
            sessionStore.tag(sessionId: runtime.snapshot.sessionId, site: site)
            let snapshot = await runtime.open(url, timeoutMillis: timeout)
            sessionStore.touch(sessionId: snapshot.sessionId)
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

    private func stateResult(args: [String: Any]) async throws -> String {
        let runtime = try sessionRuntime(from: args)
        let page = try await runtime.state()
        sessionStore.touch(sessionId: runtime.snapshot.sessionId)
        return Self.json([
            "ok": true,
            "session_id": runtime.snapshot.sessionId,
            "state": runtime.snapshot.dictionary(redactURLs: true),
            "page": IOSWebMountRedactor.redactedJSONObject(page)
        ])
    }

    private func observeResult(args: [String: Any]) async throws -> String {
        let runtime = try sessionRuntime(from: args)
        let page = try await runtime.state()
        let readable = try await runtime.extract(mode: "readable", maxChars: 2_000, maxLinks: 20)
        let interactive = try await runtime.extract(mode: "interactive", maxChars: 0, maxLinks: 80)
        let visual = try await runtime.extract(mode: "snapshot", maxChars: 0, maxLinks: 80)
        sessionStore.touch(sessionId: runtime.snapshot.sessionId)
        return Self.json([
            "ok": true,
            "tool": "wm_observe",
            "session_id": runtime.snapshot.sessionId,
            "state": runtime.snapshot.dictionary(redactURLs: true),
            "page": IOSWebMountRedactor.redactedJSONObject(page),
            "visible_text": IOSWebMountRedactor.redactedJSONObject(readable["text"] ?? ""),
            "links": IOSWebMountRedactor.redactedJSONObject(readable["links"] ?? []),
            "interactive_elements": IOSWebMountRedactor.redactedJSONObject(interactive["nodes"] ?? []),
            "visual_candidates": IOSWebMountRedactor.redactedJSONObject(visual["visual_candidates"] ?? []),
            "redacted": true
        ])
    }

    private func extractResult(args: [String: Any]) async throws -> String {
        let mode = (args["mode"] as? String)?.nilIfBlank ?? "readable"
        let maxChars = ((args["max_chars"] as? Int) ?? 20_000).clamped(to: 0...80_000)
        let maxLinks = ((args["max_links"] as? Int) ?? 20).clamped(to: 0...100)
        let runtime = try sessionRuntime(from: args)
        let result = try await runtime.extract(mode: mode, maxChars: maxChars, maxLinks: maxLinks)
        sessionStore.touch(sessionId: runtime.snapshot.sessionId)
        return Self.json([
            "ok": true,
            "session_id": runtime.snapshot.sessionId,
            "result": IOSWebMountRedactor.redactedJSONObject(result)
        ])
    }

    private func getResult(args: [String: Any]) async throws -> String {
        let kind = ((args["kind"] as? String)?.nilIfBlank ?? "text").lowercased()
        if kind == "html" {
            return Self.json([
                "ok": false,
                "denied": true,
                "reason": "wm_get kind=html is disabled on iOS because raw DOM can contain hidden tokens. Use text, value, or attr."
            ])
        }
        if kind == "value",
           Self.looksSensitiveSelector(args["selector"] as? String) ||
            Self.looksSensitiveSelector(args["target"] as? String) {
            return Self.json([
                "ok": false,
                "denied": true,
                "reason": "wm_get refused a value read from a selector that looks like a token, password, cookie, or secret field."
            ])
        }
        let maxChars = ((args["max_chars"] as? Int) ?? 20_000).clamped(to: 0...100_000)
        let runtime = try sessionRuntime(from: args)
        let result = try await runtime.get(
            selector: args["selector"] as? String,
            target: args["target"] as? String,
            kind: kind,
            attrName: args["attr_name"] as? String,
            maxChars: maxChars
        )
        sessionStore.touch(sessionId: runtime.snapshot.sessionId)
        return Self.json([
            "ok": true,
            "session_id": runtime.snapshot.sessionId,
            "result": IOSWebMountRedactor.redactedJSONObject(result)
        ])
    }

    private func visualSnapshotResult(args: [String: Any]) async throws -> String {
        let runtime = try sessionRuntime(from: args)
        let result = try await runtime.extract(mode: "snapshot", maxChars: 0, maxLinks: 80)
        sessionStore.touch(sessionId: runtime.snapshot.sessionId)
        return Self.json([
            "ok": true,
            "tool": "wm_visual_snapshot",
            "session_id": runtime.snapshot.sessionId,
            "state": runtime.snapshot.dictionary(redactURLs: true),
            "result": IOSWebMountRedactor.redactedJSONObject(result),
            "redacted": true
        ])
    }

    private func screenshotResult(args: [String: Any]) async throws -> String {
        let runtime = try sessionRuntime(from: args)
        let capture = try await runtime.screenshot()
        let artifact = try IOSWebMountScreenshotArtifactStore.save(capture, sessionId: runtime.snapshot.sessionId)
        sessionStore.touch(sessionId: runtime.snapshot.sessionId)
        return Self.json([
            "ok": true,
            "tool": "wm_screenshot",
            "session_id": runtime.snapshot.sessionId,
            "state": runtime.snapshot.dictionary(redactURLs: true),
            "artifact": artifact,
            "redacted": true
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

    private func siteAddResult(args: [String: Any]) throws -> String {
        let displayName = ((args["display_name"] as? String)?.nilIfBlank
            ?? (args["name"] as? String)?.nilIfBlank
            ?? "WebMount Site")
        guard let homepageURL = (args["homepage_url"] as? String)?.nilIfBlank
            ?? (args["url"] as? String)?.nilIfBlank else {
            return Self.json([
                "ok": false,
                "tool": "wm_site_add",
                "error": "wm_site_add requires homepage_url or url"
            ])
        }
        let needsLogin = args["needs_login"] as? Bool ?? true
        let cookieName = (args["login_cookie_name"] as? String)?.nilIfBlank
            ?? (args["cookie_name"] as? String)?.nilIfBlank
        let site = try registry.addCustomSite(
            displayName: displayName,
            homepageURL: homepageURL,
            needsLogin: needsLogin,
            loginCookieName: cookieName
        )
        let enabled = args["enabled"] as? Bool ?? true
        registry.setEnabled(id: site.id, enabled: enabled)
        let savedSite = registry.site(id: site.id) ?? site
        settings.syncAllowedHosts(registry.sites.flatMap(\.allowedHosts))
        return Self.json([
            "ok": true,
            "tool": "wm_site_add",
            "site_id": savedSite.id,
            "display_name": savedSite.displayName,
            "url": IOSWebMountRedactor.redactedURL(savedSite.homepageURL) ?? "",
            "enabled": savedSite.enabled,
            "allowed_hosts": savedSite.allowedHosts,
            "allowlist_count": settings.allowedHosts.count,
            "redacted": true
        ])
    }

    private func siteRemoveResult(args: [String: Any]) throws -> String {
        guard let siteId = (args["site_id"] as? String)?.nilIfBlank else {
            return Self.json([
                "ok": false,
                "tool": "wm_site_remove",
                "error": "wm_site_remove requires site_id"
            ])
        }
        guard let site = registry.site(id: siteId) else {
            return Self.json([
                "ok": false,
                "tool": "wm_site_remove",
                "error": "WebMount station was not found",
                "site_id": siteId
            ])
        }
        let removed = registry.remove(id: siteId)
        settings.syncAllowedHosts(registry.sites.flatMap(\.allowedHosts))
        return Self.json([
            "ok": removed,
            "tool": "wm_site_remove",
            "site_id": siteId,
            "display_name": site.displayName,
            "removed": removed,
            "cookies_cleared": false,
            "allowlist_count": settings.allowedHosts.count,
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

    private static func looksSensitiveSelector(_ value: String?) -> Bool {
        guard let value else { return false }
        let lowercased = value.lowercased()
        return ["password", "passwd", "token", "csrf", "xsrf", "secret", "cookie", "authorization", "auth"].contains { marker in
            lowercased.contains(marker)
        }
    }

    private static func parseObject(_ input: String) -> [String: Any] {
        guard let data = input.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    nonisolated static func json(_ object: Any) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]),
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
                let loweredKey = key.lowercased()
                if isSensitiveKey(loweredKey) {
                    redacted[key] = "***redacted***"
                } else if loweredKey.contains("url") || loweredKey == "href" || loweredKey == "src",
                          let string = item as? String {
                    redacted[key] = redactedURL(string) ?? string
                } else {
                    redacted[key] = redactedJSONObject(item)
                }
            }
            return redacted
        }
        if let array = value as? [Any] {
            return array.map(redactedJSONObject)
        }
        if let string = value as? String {
            return redactedText(string)
        }
        return value
    }

    static func redactedText(_ value: String) -> String {
        var output = value
        output = replacingMatches(
            in: output,
            pattern: #"https?://[^\s"'<>)]+"#,
            options: [.caseInsensitive]
        ) { match in
            redactedURL(match) ?? "[redacted-url]"
        }
        output = replacingMatches(
            in: output,
            pattern: #"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{6,}"#
        ) { _ in
            "Bearer ***redacted***"
        }
        output = replacingMatches(
            in: output,
            pattern: #"(?i)\b(authorization|token|access_token|refresh_token|auth_token|csrf|xsrf|session|secret|password)=([^&\s"'<>)]*)"#,
            options: [.caseInsensitive]
        ) { match in
            let key = match.split(separator: "=", maxSplits: 1).first.map(String.init) ?? "secret"
            return "\(key)=***redacted***"
        }
        return output
    }

    private static func isSensitiveKey(_ key: String) -> Bool {
        if ["authorization", "auth", "token", "access_token", "refresh_token", "auth_token", "cookie", "secret", "password"].contains(key) {
            return true
        }
        return key.hasSuffix("_token") ||
            key.hasSuffix("token") ||
            key.hasSuffix("_secret") ||
            key.hasSuffix("_password")
    }

    private static func replacingMatches(
        in value: String,
        pattern: String,
        options: NSRegularExpression.Options = [],
        transform: (String) -> String
    ) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else {
            return value
        }
        let nsValue = value as NSString
        let matches = regex.matches(in: value, range: NSRange(location: 0, length: nsValue.length))
        guard !matches.isEmpty else { return value }
        var output = value
        for match in matches.reversed() {
            let original = nsValue.substring(with: match.range)
            if let range = Range(match.range, in: output) {
                output.replaceSubrange(range, with: transform(original))
            }
        }
        return output
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
            "can_go_back": canGoBack,
            "can_go_forward": canGoForward,
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
    func removingPrefix(_ prefix: String) -> String {
        hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
    }
}
