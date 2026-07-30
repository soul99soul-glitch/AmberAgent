import Foundation
import Observation
@preconcurrency import Shared

enum IOSCapabilityGate: String, CaseIterable, Identifiable, Codable {
    case skills
    case mcp
    case webMount
    case miniApps
    case subAgents
    case modelCouncil
    case remoteRuntime
    case remoteSync
    case standaloneSearch
    case workspace
    case todayBoard

    var id: String { rawValue }

    var isAlwaysAvailable: Bool {
        true
    }

    var title: String {
        switch self {
        case .skills: "技能"
        case .mcp: "MCP"
        case .webMount: "WebMount"
        case .miniApps: "小应用"
        case .subAgents: "子代理"
        case .modelCouncil: "模型议会"
        case .remoteRuntime: "远程执行"
        case .remoteSync: "远端同步"
        case .standaloneSearch: "全局搜索"
        case .workspace: "工作区"
        case .todayBoard: "深度阅读"
        }
    }

    var disabledReason: String {
        switch self {
        case .skills:
            "需要开启技能系统后才能管理本机技能。"
        case .mcp:
            "需要开启 MCP 后才会连接服务器或向聊天声明 MCP 工具。"
        case .webMount:
            "WebMount 是正式高级能力，入口默认可用。"
        case .miniApps:
            "小应用是正式高级能力，入口默认可用。"
        case .subAgents:
            "子代理是正式高级能力，入口默认可用。"
        case .modelCouncil:
            "模型议会是正式高级能力，入口默认可用。"
        case .remoteRuntime:
            "需要开启远程执行后才能配置运行环境或创建远程命令任务。"
        case .remoteSync:
            "需要开启远端同步后才会显示 WebDAV 和远端快照操作。"
        case .standaloneSearch:
            "需要开启全局搜索后才会显示独立搜索页。"
        case .workspace:
            "需要开启工作区后才会显示工作区和助手入口。"
        case .todayBoard:
            "深度阅读是正式高级能力，入口默认可用。"
        }
    }
}

struct IOSCapabilityGateSettings: Codable, Equatable {
    var skills = false
    var mcp = false
    var webMount = false
    var miniApps = false
    var subAgents = false
    var modelCouncil = false
    var remoteRuntime = false
    var remoteSync = false
    var standaloneSearch = false
    var workspace = false
    var todayBoard = false

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.skills = try container.decodeIfPresent(Bool.self, forKey: .skills) ?? false
        self.mcp = try container.decodeIfPresent(Bool.self, forKey: .mcp) ?? false
        self.webMount = try container.decodeIfPresent(Bool.self, forKey: .webMount) ?? false
        self.miniApps = try container.decodeIfPresent(Bool.self, forKey: .miniApps) ?? false
        self.subAgents = try container.decodeIfPresent(Bool.self, forKey: .subAgents) ?? false
        self.modelCouncil = try container.decodeIfPresent(Bool.self, forKey: .modelCouncil) ?? false
        self.remoteRuntime = try container.decodeIfPresent(Bool.self, forKey: .remoteRuntime) ?? false
        self.remoteSync = try container.decodeIfPresent(Bool.self, forKey: .remoteSync) ?? false
        self.standaloneSearch = try container.decodeIfPresent(Bool.self, forKey: .standaloneSearch) ?? false
        self.workspace = try container.decodeIfPresent(Bool.self, forKey: .workspace) ?? false
        self.todayBoard = try container.decodeIfPresent(Bool.self, forKey: .todayBoard) ?? false
    }

    func isEnabled(_ gate: IOSCapabilityGate) -> Bool {
        true
    }

    mutating func set(_ gate: IOSCapabilityGate, enabled: Bool) {
        // Capability gates are kept for persisted-data compatibility only.
        // Product-level visibility is always on; each feature owns its real configuration.
    }
}

/// Read-write access to a real, seeded KMP `Settings` snapshot on iOS.
///
/// On init it calls the KMP `IosSettingsDefaults.shared.defaultSeededSettings()`.
/// User edits are persisted to UserDefaults (JSON) and merged on read.
///
/// WRITE-BACK: council seats / model defaults are persisted to UserDefaults
/// as JSON and survive app restart. This is a real iOS-local persistence
/// layer (not Android DataStore, not orphan save — it's the canonical
/// settings store for iOS, same role as SettingsStore for baseUrl/apiKey).
@Observable
final class IOSSharedSettingsStore {

    @ObservationIgnored private(set) var snapshot: Settings
    private(set) var revision: Int = 0

    private let defaults: UserDefaults
    private let fullSettingsJsonKey = "app.amber.ios.sharedSettingsJson"
    private let seatsKey = "app.amber.ios.councilSeats"
    private let remoteSyncStatusKey = "app.amber.ios.remoteSyncStatus"
    private let capabilityGatesKey = "app.amber.ios.capabilityGates.v1"

    private(set) var capabilityGates: IOSCapabilityGateSettings

    init(userDefaults: UserDefaults = .standard) {
        self.defaults = userDefaults
        if let data = defaults.data(forKey: capabilityGatesKey),
           let decoded = try? JSONDecoder().decode(IOSCapabilityGateSettings.self, from: data) {
            self.capabilityGates = decoded
        } else {
            self.capabilityGates = IOSCapabilityGateSettings()
        }
        if let json = defaults.string(forKey: fullSettingsJsonKey),
           let decoded = try? Self.decodeSettings(json) {
            self.snapshot = decoded
        } else {
            self.snapshot = IosSettingsDefaults.shared.defaultSeededSettings()
        }
        // Scheme B rehydration: the persisted JSON is redacted (provider apiKeys
        // are the mask sentinel). Rehydrate the real apiKeys from the Keychain
        // side-table so the in-memory snapshot carries usable credentials. Also
        // migrates any pre-redactor plaintext values still in the JSON: if a
        // provider's apiKey is non-empty AND non-mask, store it to the side-table
        // now (so the next persist redacts it), then keep it in memory.
        let migratedProviderPlaintext = Self.rehydrateProviderApiKeys(into: &self.snapshot)
        let migratedSearchPlaintext = Self.rehydrateSearchServiceApiKeys(into: &self.snapshot)
        let hydratedJson = IOSCredentialRedactor.rehydrate(
            IosSettingsJsonBridge.shared.encode(settings: self.snapshot)
        ) { path in
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath(path))
        }
        if let hydrated = try? Self.decodeSettings(hydratedJson) {
            self.snapshot = hydrated
        }
        // iOS identity: introduce as "Amber" and don't claim to run on Android.
        // Applied on every load (idempotent) so persisted snapshots are rebranded too.
        self.snapshot = IosSettingsMutations.shared.rebrandAmberIdentity(settings: self.snapshot)
        if migratedProviderPlaintext || migratedSearchPlaintext {
            restoreSnapshot(self.snapshot)
        }
    }

    /// Rehydrates provider apiKeys in a snapshot from the Keychain side-table
    /// (scheme B). Masked values are replaced with the real key; non-empty non-
    /// mask values are migrated to the side-table (first-time migration from a
    /// pre-redactor plaintext store).
    private static func rehydrateProviderApiKeys(into snapshot: inout Settings) -> Bool {
        var migratedPlaintext = false
        for provider in snapshot.providers {
            let providerId = provider.id.description() as String
            let current = apiKey(of: provider)
            if current == IOSCredentialRedactor.mask {
                // Redacted on disk → rehydrate real key from Keychain.
                if let real = IOSCredentialSideTable.load(key: IOSCredentialSideTable.providerApiKey(providerId: providerId)) {
                    snapshot = IosSettingsMutations.shared.updateProviderApiKey(settings: snapshot, providerId: providerId, apiKey: real)
                }
            } else if !current.isEmpty {
                // Pre-redactor plaintext still in the JSON → migrate to side-table
                // and immediately rewrite persisted JSON through restoreSnapshot.
                if IOSCredentialSideTable.store(
                    key: IOSCredentialSideTable.providerApiKey(providerId: providerId),
                    value: current
                ) {
                    migratedPlaintext = true
                }
            }
        }
        return migratedPlaintext
    }

    /// Reads the apiKey from a provider by sealed type.
    private static func apiKey(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.apiKey }
        if let claude = provider as? ProviderSetting.Claude { return claude.apiKey }
        if let google = provider as? ProviderSetting.Google { return google.apiKey }
        return ""
    }

    private static func rehydrateSearchServiceApiKeys(into snapshot: inout Settings) -> Bool {
        var migratedPlaintext = false
        for service in snapshot.searchServices {
            let serviceId = service.id.description()
            let current = searchApiKey(of: service)
            if current == IOSCredentialRedactor.mask {
                // Search-service credentials follow the same scheme-B invariant
                // as chat providers: UserDefaults may contain only the mask,
                // while the real key is restored from the side-table before the
                // snapshot is used or persisted again.
                if let real = IOSCredentialSideTable.load(key: IOSCredentialSideTable.searchServiceApiKey(serviceId: serviceId)) {
                    snapshot = IosSettingsMutations.shared.updateSearchServiceApiKey(
                        settings: snapshot,
                        id: serviceId,
                        apiKey: real
                    )
                }
            } else if !current.isEmpty {
                if IOSCredentialSideTable.store(
                    key: IOSCredentialSideTable.searchServiceApiKey(serviceId: serviceId),
                    value: current
                ) {
                    migratedPlaintext = true
                }
            }
        }
        return migratedPlaintext
    }

    private static func searchApiKey(of service: SearchServiceOptions) -> String {
        if let service = service as? SearchServiceOptions.ZhipuOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.TavilyOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.ExaOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.LinkUpOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.BraveOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.SerperOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.SerpApiOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.MetasoOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.OllamaOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.PerplexityOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.FirecrawlOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.JinaOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.BochaOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.AmberAgentSearchOptions { return service.apiKey }
        if let service = service as? SearchServiceOptions.GrokOptions { return service.apiKey }
        return ""
    }

    func restoreSnapshot(_ settings: Settings) {
        var runtimeSettings = settings
        _ = Self.rehydrateProviderApiKeys(into: &runtimeSettings)
        _ = Self.rehydrateSearchServiceApiKeys(into: &runtimeSettings)
        let runtimeJson = IOSCredentialRedactor.rehydrate(
            IosSettingsJsonBridge.shared.encode(settings: runtimeSettings)
        ) { path in
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath(path))
        }
        if let decoded = try? Self.decodeSettings(runtimeJson) {
            runtimeSettings = decoded
        }
        snapshot = runtimeSettings
        // Scheme B: store real credentials in the Keychain side-table BEFORE
        // redacting, so they survive restart (load rehydrates from here). Covers
        // provider apiKey + model customHeaders (Authorization-like). iOS-only;
        // the shared ProviderSetting is not modified.
        for provider in runtimeSettings.providers {
            let providerId = provider.id.description() as String
            let apiKey = Self.apiKey(of: provider)
            // Guard against the redaction mask (parity with the search loop below):
            // if the in-memory key is the placeholder — e.g. rehydration didn't find
            // a side-table entry — never write it back, or it would overwrite the
            // real key in the Keychain and break the provider permanently.
            if !apiKey.isEmpty, apiKey != IOSCredentialRedactor.mask {
                IOSCredentialSideTable.store(key: IOSCredentialSideTable.providerApiKey(providerId: providerId), value: apiKey)
            }
        }
        for service in runtimeSettings.searchServices {
            let serviceId = service.id.description()
            let apiKey = Self.searchApiKey(of: service)
            if !apiKey.isEmpty, apiKey != IOSCredentialRedactor.mask {
                // Persist the real search key before JSON redaction. Reloading a
                // masked Settings blob must round-trip back to this value.
                IOSCredentialSideTable.store(
                    key: IOSCredentialSideTable.searchServiceApiKey(serviceId: serviceId),
                    value: apiKey
                )
            }
        }
        // Redact credentials BEFORE persisting (in-memory snapshot keeps real
        // values; the UserDefaults form is credential-free). (parity with
        // Android BackupSettingsRedactor.) See IOSCredentialRedactor.
        let redactedJson = IOSCredentialRedactor.redact(
            IosSettingsJsonBridge.shared.encode(settings: runtimeSettings)
        ) { path, value in
            IOSCredentialSideTable.store(
                key: IOSCredentialSideTable.settingsPath(path),
                value: value
            )
        }
        defaults.set(redactedJson, forKey: fullSettingsJsonKey)
        revision &+= 1
    }

    func isCapabilityGateEnabled(_ gate: IOSCapabilityGate) -> Bool {
        capabilityGates.isEnabled(gate)
    }

    func setCapabilityGate(_ gate: IOSCapabilityGate, enabled: Bool) {
        capabilityGates.set(gate, enabled: enabled)
        persistCapabilityGates()
    }

    private func persistCapabilityGates() {
        if let data = try? JSONEncoder().encode(capabilityGates) {
            defaults.set(data, forKey: capabilityGatesKey)
        }
    }

    var remoteSyncStatus: IOSRemoteSyncStatus {
        if let data = defaults.data(forKey: remoteSyncStatusKey),
           let decoded = try? JSONDecoder().decode(IOSRemoteSyncStatus.self, from: data) {
            return decoded
        }
        return IOSRemoteSyncStatus()
    }

    func updateRemoteSyncStatus(_ transform: (inout IOSRemoteSyncStatus) -> Void) {
        var status = remoteSyncStatus
        transform(&status)
        if let data = try? JSONEncoder().encode(status) {
            defaults.set(data, forKey: remoteSyncStatusKey)
        }
    }

    func recordRemoteUpload(snapshot: IOSRemoteSnapshot, preview: IOSSyncPreview) {
        updateRemoteSyncStatus { status in
            status.lastUploadAt = Self.currentEpochMillis()
            status.setRemoteRevision(snapshot.remoteRevision, for: snapshot.provider)
            status.deviceLabel = preview.manifest.deviceLabel
            status.lastBackupVersionName = preview.manifest.appVersionName
            status.lastBackupVersionCode = preview.manifest.appVersionCode
            status.lastError = ""
        }
    }

    func recordRemoteDownload(snapshot: IOSRemoteSnapshot, preview: IOSSyncPreview) {
        updateRemoteSyncStatus { status in
            status.lastDownloadAt = Self.currentEpochMillis()
            status.setRemoteRevision(snapshot.remoteRevision, for: snapshot.provider)
            status.deviceLabel = preview.manifest.deviceLabel
            status.lastBackupVersionName = preview.manifest.appVersionName
            status.lastBackupVersionCode = preview.manifest.appVersionCode
            status.lastError = ""
        }
    }

    func recordLocalRestore(preview: IOSSyncPreview) {
        updateRemoteSyncStatus { status in
            status.lastDownloadAt = Self.currentEpochMillis()
            status.deviceLabel = preview.manifest.deviceLabel
            status.lastBackupVersionName = preview.manifest.appVersionName
            status.lastBackupVersionCode = preview.manifest.appVersionCode
            status.lastError = ""
        }
    }

    func recordRemoteSyncError(_ error: Error) {
        updateRemoteSyncStatus { status in
            status.lastError = error.localizedDescription
        }
    }

    func setMemoryRuntimeEnabled(core: Bool? = nil, shortTerm: Bool? = nil, longTerm: Bool? = nil) {
        let runtime = snapshot.agentRuntime
        restoreSnapshot(
            IosSettingsMutations.shared.setMemoryRuntimeEnabled(
                settings: snapshot,
                enableCoreMemory: core ?? runtime.enableCoreMemory,
                enableShortTermMemory: shortTerm ?? runtime.enableShortTermMemory,
                enableLongTermMemory: longTerm ?? runtime.enableLongTermMemory
            )
        )
    }

    private static func currentEpochMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static func decodeSettings(_ json: String) throws -> Settings {
        // decode 现已声明 @Throws：损坏的 settings JSON 会作为 NSError 抛出，
        // 交给上层 try? 回退默认配置，而不是在 Kotlin/Native 边界终止进程。
        try IosSettingsJsonBridge.shared.decode(json: json)
    }

    // MARK: - Council seats write-back

    /// User-customized council seats (persisted to UserDefaults as JSON).
    /// Each seat: [seatId, name, role, modelId, runnerType].
    var savedCouncilSeats: [[String: String]] {
        get { (defaults.array(forKey: seatsKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: seatsKey) }
    }

    /// Add a council seat to persistent storage.
    ///
    /// [Slice 4] Now merges into the live snapshot via
    /// `IosSettingsMutations.addCouncilSeat` (shared/IosSettingsMutations.kt:42)
    /// and persists the WHOLE snapshot via `restoreSnapshot` — so the seat
    /// survives restart as a real `agentRuntime.modelCouncil.defaultSeats`
    /// entry, not just a side-channel UserDefaults row. The legacy
    /// `savedCouncilSeats` mirror is kept in sync for any reader still on it.
    func addCouncilSeat(name: String, role: String, modelId: String, runnerType: String = "provider") {
        let seatId = UUID().uuidString
        // [Slice 4] The KMP side (IosSettingsMutations.addCouncilSeat) calls
        // kotlin.uuid.Uuid.parse(modelId), which on an invalid Uuid throws —
        // but the ObjC bridge marks the call non-throwing, so it would CRASH
        // rather than throw. Guard BEFORE calling: only run the real snapshot
        // merge when modelId is a valid Uuid. Otherwise fall back to the
        // legacy mirror only (honest: no fake seat in the snapshot).
        var mergedIntoSnapshot = false
        if UUID(uuidString: modelId) != nil {
            let merged = IosSettingsMutations.shared.addCouncilSeat(
                settings: snapshot,
                seatId: seatId,
                name: name,
                role: role,
                modelId: modelId,
                runnerType: runnerType,
                systemPrompt: "",
                outputBudgetChars: 4096
            )
            restoreSnapshot(merged)
            mergedIntoSnapshot = true
        }
        var seats = savedCouncilSeats
        seats.append([
            "seatId": seatId,
            "name": name,
            "role": role,
            "modelId": modelId,
            "runnerType": runnerType,
            "mergedIntoSnapshot": mergedIntoSnapshot ? "true" : "false",
        ])
        savedCouncilSeats = seats
    }

    /// Remove a council seat by index.
    ///
    /// [Slice 4] Removes from BOTH the snapshot (via
    /// `IosSettingsMutations.removeCouncilSeat`, matching by seatId) and the
    /// legacy mirror, so a delete survives restart. Seats added before Slice 4
    /// (mirror-only) are also cleared from the mirror.
    func removeCouncilSeat(at index: Int) {
        var seats = savedCouncilSeats
        guard index >= 0 && index < seats.count else { return }
        let removed = seats.remove(at: index)
        savedCouncilSeats = seats
        if let seatId = removed["seatId"] {
            let merged = IosSettingsMutations.shared.removeCouncilSeat(
                settings: snapshot,
                seatId: seatId
            )
            restoreSnapshot(merged)
        }
    }

    // MARK: - Custom models write-back

    private let modelsKey = "app.amber.ios.customModels"

    /// User-customized model entries (persisted to UserDefaults).
    var savedCustomModels: [[String: String]] {
        get { (defaults.array(forKey: modelsKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: modelsKey) }
    }

    /// Add a custom model to persistent storage.
    ///
    /// [Slice 4] Now merges a real ProviderSetting.OpenAI (containing the model)
    /// into `snapshot.providers` via `IosSettingsMutations.buildOpenAIProvider`
    /// + `addProvider`, then `restoreSnapshot` (full JSON persist). The model
    /// survives restart as a real provider entry. The legacy `savedCustomModels`
    /// mirror is kept in sync (records the KMP providerId for removal) for
    /// backward-compatible readers.
    func addCustomModel(name: String, modelId: String, providerName: String = "") {
        let provider = IosSettingsMutations.shared.buildOpenAIProvider(
            name: providerName.isEmpty ? name : providerName,
            apiKey: "",
            baseUrl: "https://api.openai.com/v1",
            modelName: name,
            modelId: modelId
        )
        let merged = IosSettingsMutations.shared.addProvider(settings: snapshot, provider: provider)
        restoreSnapshot(merged)
        var models = savedCustomModels
        models.append([
            "id": UUID().uuidString,
            "name": name,
            "modelId": modelId,
            "providerName": providerName,
            // KMP-side provider Uuid (string) so removeCustomModel can filter
            // it out of snapshot.providers by id.
            "providerId": providerIdString(from: provider),
        ])
        savedCustomModels = models
    }

    /// Remove a custom model by index.
    ///
    /// [Slice 4] Removes from BOTH the snapshot (via
    /// `IosSettingsMutations.removeProvider`, by the providerId recorded at add
    /// time) and the legacy mirror, so the delete survives restart.
    func removeCustomModel(at index: Int) {
        var models = savedCustomModels
        guard index >= 0 && index < models.count else { return }
        let removed = models[index]
        if let providerId = removed["providerId"], removeProvider(providerId: providerId) {
            return
        }
        models.remove(at: index)
        savedCustomModels = models
    }

    // MARK: - Provider (Settings.providers) write-back

    /// Write an API key onto the provider identified by [providerId] inside the
    /// real `Settings.providers` snapshot, then persist. Works for OpenAI,
    /// Claude, and Google subtypes. The key now lives inside the ProviderSetting
    /// itself (Android model) rather than a separate iOS per-provider Keychain
    /// slot — so the chat chain can read it directly when resolving the current
    /// model's provider. Returns the provider that was updated, if found.
    @discardableResult
    func updateProviderApiKey(providerId: String, apiKey: String) -> ProviderSetting? {
        let before = snapshot
        let merged = IosSettingsMutations.shared.updateProviderApiKey(
            settings: before, providerId: providerId, apiKey: apiKey
        )
        restoreSnapshot(merged)
        // Identify the updated provider by id description equality (KMP parses
        // the string internally; we match the same provider here without needing
        // a Swift-side Uuid parser).
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    @discardableResult
    func updateProviderBasics(providerId: String, name: String, enabled: Bool) -> ProviderSetting? {
        let merged = IosSettingsMutations.shared.updateProviderBasics(
            settings: snapshot,
            providerId: providerId,
            name: name,
            enabled: enabled
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    @discardableResult
    func updateProviderEndpoint(
        providerId: String,
        baseUrl: String,
        chatCompletionsPath: String,
        useResponseApi: Bool,
        promptCaching: Bool
    ) -> ProviderSetting? {
        let merged = IosSettingsMutations.shared.updateProviderEndpoint(
            settings: snapshot,
            providerId: providerId,
            baseUrl: baseUrl,
            chatCompletionsPath: chatCompletionsPath,
            useResponseApi: useResponseApi,
            promptCaching: promptCaching
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    /// Sets the OpenAI auth mode (API key ↔ Codex OAuth) for a provider. Codex
    /// pins the codex backend baseUrl + Responses API. Persists to snapshot.
    @discardableResult
    func setOpenAIAuthMode(providerId: String, authMode: OpenAIAuthMode) -> ProviderSetting? {
        let merged = IosSettingsMutations.shared.setOpenAIAuthMode(
            settings: snapshot,
            providerId: providerId,
            authMode: authMode
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    /// Replace the chat-typed models on provider [providerId]. Each entry is
    /// (modelId, displayName). Non-chat models are preserved. Persists to snapshot.
    @discardableResult
    func updateProviderChatModels(providerId: String, models: [(modelId: String, displayName: String)]) -> ProviderSetting? {
        let before = snapshot
        let pairs = models.map {
            KotlinPair(first: $0.modelId as NSString, second: $0.displayName as NSString)
        }
        let merged = IosSettingsMutations.shared.updateProviderChatModels(
            settings: before, providerId: providerId, modelIds: pairs
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    /// Merge discovered chat models without deleting private/manual models or
    /// replacing existing model UUIDs and metadata.
    @discardableResult
    func mergeProviderChatModels(providerId: String, models: [(modelId: String, displayName: String)]) -> ProviderSetting? {
        let pairs = models.map {
            KotlinPair(first: $0.modelId as NSString, second: $0.displayName as NSString)
        }
        let merged = IosSettingsMutations.shared.mergeProviderChatModels(
            settings: snapshot,
            providerId: providerId,
            modelIds: pairs
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    /// Upserts the synthetic codex IMAGE model on a provider (replaces the entry
    /// with the same modelId, preserves the rest). Persists to snapshot.
    @discardableResult
    func upsertProviderImageModel(providerId: String, modelId: String, displayName: String) -> ProviderSetting? {
        let merged = IosSettingsMutations.shared.upsertProviderImageModel(
            settings: snapshot,
            providerId: providerId,
            modelId: modelId,
            displayName: displayName
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    /// Switch an existing provider between OpenAI-compatible and Anthropic-compatible
    /// subtypes, preserving id/name/base URL/key/models. Persists to snapshot.
    @discardableResult
    func switchProviderProtocol(providerId: String, protocolOption: ProviderProtocolOption) -> ProviderSetting? {
        let before = snapshot
        let merged = IosSettingsMutations.shared.convertProviderProtocol(
            settings: before,
            providerId: providerId,
            target: protocolOption.rawValue
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    @discardableResult
    func upsertProviderChatModel(
        providerId: String,
        modelUuid: String?,
        modelId: String,
        displayName: String,
        contextWindowTokens: Int?,
        modelType: ModelType,
        headers: [(name: String, value: String)]
    ) -> ProviderSetting? {
        let pairs = headers.map {
            KotlinPair(first: $0.name as NSString, second: $0.value as NSString)
        }
        let merged = IosSettingsMutations.shared.upsertProviderChatModel(
            settings: snapshot,
            providerId: providerId,
            modelUuid: modelUuid,
            modelId: modelId,
            displayName: displayName,
            contextWindowTokens: contextWindowTokens.map { KotlinInt(value: Int32($0)) },
            modelType: modelType,
            headerPairs: pairs
        )
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    @discardableResult
    func removeProviderChatModel(providerId: String, modelUuid: String) -> ProviderSetting? {
        let modelExisted = snapshot.providers
            .first { ($0.id.description() as String) == providerId }?
            .models
            .contains { $0.id.description() == modelUuid } == true
        let genericCredentialRefs = genericCredentialRefs(stableId: modelUuid)
        let merged = IosSettingsMutations.shared.removeProviderChatModel(
            settings: snapshot,
            providerId: providerId,
            modelUuid: modelUuid
        )
        let modelWasRemoved = merged.providers
            .first { ($0.id.description() as String) == providerId }?
            .models
            .contains { $0.id.description() == modelUuid } == false
        if modelExisted, modelWasRemoved {
            genericCredentialRefs.forEach { IOSCredentialSideTable.delete(key: $0) }
        }
        restoreSnapshot(merged)
        return merged.providers.first { ($0.id.description() as String) == providerId }
    }

    /// Set the global current chat model by model UUID string. Persists to snapshot.
    func setCurrentChatModelId(_ modelId: String) {
        let merged = IosSettingsMutations.shared.setChatModelId(settings: snapshot, modelId: modelId)
        restoreSnapshot(merged)
    }

    /// Set the current assistant's chat model and carry per-model reasoning
    /// memory across the switch, matching Android's chat-surface behavior.
    func setCurrentAssistantChatModelId(_ modelId: String) {
        let merged = IosSettingsMutations.shared.setCurrentAssistantChatModelId(settings: snapshot, modelId: modelId)
        restoreSnapshot(merged)
    }

    func currentAssistantReasoningLevels() -> [ReasoningLevel] {
        IosSettingsMutations.shared.currentAssistantReasoningLevels(settings: snapshot)
    }

    func currentAssistantReasoningLevel() -> ReasoningLevel {
        IosSettingsMutations.shared.currentAssistantReasoningLevel(settings: snapshot)
    }

    func updateCurrentAssistantReasoningLevel(_ level: ReasoningLevel) {
        let merged = IosSettingsMutations.shared.updateCurrentAssistantReasoningLevel(
            settings: snapshot,
            reasoningLevel: level
        )
        restoreSnapshot(merged)
    }

    /// Auxiliary-task model slots. Pass a model UUID string to set, or "" to clear
    /// (clearing falls back to the current chat model at run time).
    func setOcrModelId(_ modelId: String) {
        restoreSnapshot(IosSettingsMutations.shared.setOcrModelId(settings: snapshot, modelId: modelId))
    }

    func setTitleModelId(_ modelId: String) {
        restoreSnapshot(IosSettingsMutations.shared.setTitleModelId(settings: snapshot, modelId: modelId))
    }

    func setSuggestionModelId(_ modelId: String) {
        restoreSnapshot(IosSettingsMutations.shared.setSuggestionModelId(settings: snapshot, modelId: modelId))
    }

    func setCompressModelId(_ modelId: String) {
        restoreSnapshot(IosSettingsMutations.shared.setCompressModelId(settings: snapshot, modelId: modelId))
    }

    func setImageGenerationModelId(_ modelId: String) {
        restoreSnapshot(IosSettingsMutations.shared.setImageGenerationModelId(settings: snapshot, modelId: modelId))
    }

    /// Add a new provider (already-constructed ProviderSetting) and persist.
    /// Returns the added provider so callers can read its generated id.
    @discardableResult
    func addProvider(_ provider: ProviderSetting) -> ProviderSetting {
        let merged = IosSettingsMutations.shared.addProvider(settings: snapshot, provider: provider)
        restoreSnapshot(merged)
        return provider
    }

    func canRemoveProvider(providerId: String) -> Bool {
        snapshot.providers.contains { $0.id.description() == providerId }
            && !IosSettingsMutations.shared.isDefaultProvider(id: providerId)
    }

    /// Delete a user-created provider and its credential. Bundled providers are immutable.
    @discardableResult
    func removeProvider(providerId: String) -> Bool {
        guard canRemoveProvider(providerId: providerId) else { return false }

        let genericCredentialRefs = genericCredentialRefs(stableId: providerId)
        let merged = IosSettingsMutations.shared.removeProvider(settings: snapshot, id: providerId)
        guard !merged.providers.contains(where: { $0.id.description() == providerId }) else {
            return false
        }

        IOSCredentialSideTable.delete(key: IOSCredentialSideTable.providerApiKey(providerId: providerId))
        genericCredentialRefs.forEach { IOSCredentialSideTable.delete(key: $0) }
        savedCustomModels.removeAll { $0["providerId"] == providerId }
        restoreSnapshot(merged)
        return true
    }

    /// Execution-path provider resolution that mirrors chat (the canonical store
    /// the formal Provider UI writes and chat reads): resolves the current chat
    /// model's provider — a full `ProviderSetting` with the real apiKey
    /// (rehydrated from the Keychain side-table) and the user's selected sealed
    /// type. Agent surfaces (SubAgent / Council / Board) should call this instead
    /// of the legacy `ProviderRegistryStore`, which returns a key-LESS provider
    /// and ignores the selected model (the dual-source bug). Returns nil when no
    /// usable current model/provider is configured.
    func resolveCurrentProviderSetting() -> ProviderSetting? {
        guard let model = snapshot.getCurrentChatModel() else { return nil }
        return model.findProvider(providers: snapshot.providers, checkOverwrite: true)
    }

    /// The wire model id of the current chat model (e.g. "gpt-4o"), or "" when
    /// none is selected. Pairs with `resolveCurrentProviderSetting()`.
    func resolveCurrentModelId() -> String {
        snapshot.getCurrentChatModel()?.modelId ?? ""
    }

    /// Resolve the (wire modelId, provider) pair for a board Deep Read run.
    /// `boardModelId` is the per-board model override (a hex-dash UUID); when it
    /// is empty or not found, falls back to the current chat model. Resolves from
    /// the canonical shared snapshot (mirrors chat), so a provider+model the user
    /// configured in Settings is honored — not the legacy key-LESS
    /// ProviderRegistryStore. Returns nil when no usable model/provider/key is
    /// configured, so the caller can degrade to the deterministic offline draft
    /// (honest degradation, never a silent /chat/completions).
    func resolveBoardDeepReadModel(boardModelId: String?) -> (modelId: String, provider: ProviderSetting)? {
        let model: Model?
        if let id = boardModelId?.trimmingCharacters(in: .whitespacesAndNewlines), !id.isEmpty {
            model = snapshot.providers
                .flatMap { $0.models }
                .first { $0.type == ModelType.chat && $0.id.toHexDashString().caseInsensitiveCompare(id) == .orderedSame }
                ?? snapshot.getCurrentChatModel()
        } else {
            model = snapshot.getCurrentChatModel()
        }
        guard let model,
              let provider = model.findProvider(providers: snapshot.providers, checkOverwrite: true),
              !Self.apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !model.modelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return (model.modelId, provider)
    }

    /// A chat model offered for selection in pickers, resolved from the shared
    /// snapshot across ALL configured providers — the canonical source the formal
    /// Provider UI writes. Replaces ProviderRegistryStore.selectedProvider.models
    /// (which only saw a single provider's models).
    struct ChatModelOption: Identifiable, Equatable {
        let id: String          // hex-dash UUID of the model
        let modelId: String     // wire id (e.g. "gpt-4o")
        let displayName: String
        let providerName: String
    }

    /// All chat models across every configured provider in the shared snapshot,
    /// de-duplicated by model UUID. Pickers (Board / Council seat / etc.) should
    /// use this instead of the legacy registry's single-provider model list.
    func availableChatModels() -> [ChatModelOption] {
        var seen = Set<String>()
        var options: [ChatModelOption] = []
        for provider in snapshot.providers {
            for model in provider.models where model.type == ModelType.chat {
                let wire = model.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !wire.isEmpty else { continue }
                let hexId = model.id.toHexDashString()
                guard seen.insert(hexId).inserted else { continue }
                let display = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                options.append(ChatModelOption(
                    id: hexId,
                    modelId: wire,
                    displayName: display.isEmpty ? wire : display,
                    providerName: provider.name
                ))
            }
        }
        return options
    }

    func syncLegacySettingsStoreForCurrentChat(_ settingsStore: SettingsStore) {
        guard let model = snapshot.getCurrentChatModel(),
              let provider = model.findProvider(providers: snapshot.providers, checkOverwrite: true) else {
            return
        }
        settingsStore.modelId = model.modelId
        if let openAI = provider as? ProviderSetting.OpenAI {
            settingsStore.baseUrl = openAI.baseUrl
            settingsStore.apiKey = openAI.apiKey
        } else if let claude = provider as? ProviderSetting.Claude {
            settingsStore.baseUrl = claude.baseUrl
            settingsStore.apiKey = claude.apiKey
        }
    }

    // MARK: - Custom search providers write-back

    private let searchProvidersKey = "app.amber.ios.customSearchProviders"

    /// User-customized search provider entries (persisted to UserDefaults).
    var savedSearchProviders: [[String: String]] {
        get { (defaults.array(forKey: searchProvidersKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: searchProvidersKey) }
    }

    /// Add a custom search provider to persistent storage.
    ///
    /// [Slice 4] Now merges a real SearchServiceOptions subtype (built by
    /// `IosSettingsMutations.buildSearchService`) into `snapshot.searchServices`
    /// via `addSearchServiceAndSelect`, then `restoreSnapshot`. Survives
    /// restart and immediately becomes the selected/enabled iOS search route.
    func addSearchProvider(name: String, apiKey: String = "", serviceType: String = "") {
        let service = IosSettingsMutations.shared.buildSearchService(
            serviceType: serviceType.isEmpty ? "tavily" : serviceType,
            apiKey: apiKey
        )
        let merged = IosSettingsMutations.shared.addSearchServiceAndSelect(settings: snapshot, service: service)
        restoreSnapshot(merged)
        var providers = savedSearchProviders
        providers.append([
            "id": UUID().uuidString,
            "name": name,
            // Scheme B: never persist the real apiKey in the legacy mirror
            // (full Settings JSON is already redacted by restoreSnapshot). The
            // real key lives in the in-memory snapshot; the mirror is a
            // structural record only. (parity with IOSCredentialRedactor.)
            "apiKey": IOSCredentialRedactor.mask,
            "serviceType": serviceType,
            // KMP-side search service Uuid (string) for removal.
            "serviceId": searchServiceIdString(from: service),
        ])
        savedSearchProviders = providers
    }

    /// Remove a custom search provider by index.
    ///
    /// [Slice 4] Removes from BOTH snapshot (via removeSearchService by id) and
    /// the legacy mirror.
    func removeSearchProvider(at index: Int) {
        var providers = savedSearchProviders
        guard index >= 0 && index < providers.count else { return }
        let removed = providers.remove(at: index)
        savedSearchProviders = providers
        if let serviceId = removed["serviceId"] {
            IOSCredentialSideTable.delete(key: IOSCredentialSideTable.searchServiceApiKey(serviceId: serviceId))
            let merged = IosSettingsMutations.shared.removeSearchService(settings: snapshot, id: serviceId)
            restoreSnapshot(merged)
        }
    }

    func setEnableWebSearch(_ enabled: Bool) {
        let merged = IosSettingsMutations.shared.setEnableWebSearch(settings: snapshot, enabled: enabled)
        restoreSnapshot(merged)
    }

    func setSearchBuiltinDuckDuckGoEnabled(_ enabled: Bool) {
        let merged = IosSettingsMutations.shared.setSearchBuiltinDuckDuckGoEnabled(settings: snapshot, enabled: enabled)
        restoreSnapshot(merged)
    }

    func setSearchBuiltinBingEnabled(_ enabled: Bool) {
        let merged = IosSettingsMutations.shared.setSearchBuiltinBingEnabled(settings: snapshot, enabled: enabled)
        restoreSnapshot(merged)
    }

    func setSearchBuiltinJinaEnabled(_ enabled: Bool) {
        let merged = IosSettingsMutations.shared.setSearchBuiltinJinaEnabled(settings: snapshot, enabled: enabled)
        restoreSnapshot(merged)
    }

    func setSearchBuiltinWikipediaEnabled(_ enabled: Bool) {
        let merged = IosSettingsMutations.shared.setSearchBuiltinWikipediaEnabled(settings: snapshot, enabled: enabled)
        restoreSnapshot(merged)
    }

    func setSearchBuiltinHackerNewsEnabled(_ enabled: Bool) {
        let merged = IosSettingsMutations.shared.setSearchBuiltinHackerNewsEnabled(settings: snapshot, enabled: enabled)
        restoreSnapshot(merged)
    }

    func setSearchGoogleWebViewFallbackEnabled(_ enabled: Bool) {
        let merged = IosSettingsMutations.shared.setSearchGoogleWebViewFallbackEnabled(settings: snapshot, enabled: enabled)
        restoreSnapshot(merged)
    }

    func setSearchResultSize(_ size: Int) {
        let merged = IosSettingsMutations.shared.setSearchResultSize(settings: snapshot, size: Int32(size))
        restoreSnapshot(merged)
    }

    func moveSearchProvider(fromIndex: Int, toIndex: Int) {
        let merged = IosSettingsMutations.shared.moveSearchService(
            settings: snapshot,
            fromIndex: Int32(fromIndex),
            toIndex: Int32(toIndex)
        )
        restoreSnapshot(merged)
    }

    func setSearchProviderEnabled(serviceId: String, enabled: Bool) {
        let merged = IosSettingsMutations.shared.setSearchServiceEnabled(
            settings: snapshot,
            id: serviceId,
            enabled: enabled
        )
        restoreSnapshot(merged)
    }

    func selectSearchProvider(serviceId: String) {
        guard let index = snapshot.searchServices.firstIndex(where: { $0.id.description() == serviceId }) else {
            return
        }
        let merged = IosSettingsMutations.shared.selectSearchService(settings: snapshot, index: Int32(index))
        restoreSnapshot(merged)
    }

    // MARK: - Custom TTS engines write-back

    private let ttsEnginesKey = "app.amber.ios.customTtsEngines"

    var savedTtsEngines: [[String: String]] {
        get { (defaults.array(forKey: ttsEnginesKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: ttsEnginesKey) }
    }

    /// [Slice 4] Now merges a real TTSProviderSetting.OpenAI into
    /// `snapshot.ttsProviders` via `buildOpenAITtsProvider` + `addTtsProvider`,
    /// then `restoreSnapshot`. Survives restart. Only engineType == "openai"
    /// builds a real provider; other types fall back to legacy mirror only
    /// with honest UI labels.
    func addTtsEngine(name: String, engineType: String, apiKey: String = "", model: String = "") {
        if engineType.lowercased() == "openai" {
            let provider = IosSettingsMutations.shared.buildOpenAITtsProvider(
                name: name,
                apiKey: apiKey,
                model: model.isEmpty ? "gpt-4o-mini-tts" : model
            )
            let merged = IosSettingsMutations.shared.addTtsProvider(settings: snapshot, provider: provider)
            restoreSnapshot(merged)
            var engines = savedTtsEngines
            engines.append([
                "id": UUID().uuidString,
                "name": name,
                "engineType": engineType,
                // Scheme B: never persist the real apiKey in the legacy mirror.
                "apiKey": IOSCredentialRedactor.mask,
                "model": model,
                // KMP-side TTS provider Uuid (string) for removal.
                "ttsId": ttsIdString(from: provider),
            ])
            savedTtsEngines = engines
        } else {
            // Non-OpenAI engine types aren't built from the iOS form in this
            // slice — legacy mirror only (honest fallback, no fake snapshot entry).
            var engines = savedTtsEngines
            engines.append([
                "id": UUID().uuidString,
                "name": name,
                "engineType": engineType,
                // Scheme B: never persist the real apiKey in the legacy mirror.
                "apiKey": IOSCredentialRedactor.mask,
                "model": model,
            ])
            savedTtsEngines = engines
        }
    }

    /// [Slice 4] Removes from BOTH snapshot (via removeTtsProvider by id) and
    /// the legacy mirror.
    func removeTtsEngine(at index: Int) {
        var engines = savedTtsEngines
        guard index >= 0 && index < engines.count else { return }
        let removed = engines.remove(at: index)
        savedTtsEngines = engines
        if let ttsId = removed["ttsId"] {
            let genericCredentialRefs = genericCredentialRefs(stableId: ttsId)
            let merged = IosSettingsMutations.shared.removeTtsProvider(settings: snapshot, id: ttsId)
            if !merged.ttsProviders.contains(where: { $0.id.description() == ttsId }) {
                genericCredentialRefs.forEach { IOSCredentialSideTable.delete(key: $0) }
            }
            restoreSnapshot(merged)
        }
    }

    // MARK: - SubAgent role overrides write-back

    private let subAgentOverridesKey = "app.amber.ios.subAgentOverrides"

    // MARK: - KMP id extraction (Slice 4)

    /// Extract the canonical Uuid string from a freshly-built KMP provider/
    /// service, so it can be recorded in the legacy mirror and used by the
    /// removeXxx path to filter it out of the snapshot on restart.
    private func providerIdString(from provider: ProviderSetting) -> String {
        provider.id.description()
    }

    private func searchServiceIdString(from service: SearchServiceOptions) -> String {
        service.id.description()
    }

    private func ttsIdString(from provider: TTSProviderSetting) -> String {
        provider.id.description()
    }

    private func genericCredentialRefs(stableId: String) -> [String] {
        // `IOSCredentialRedactor.arrayItemPath` 把数组项标成 `[<identity>#<index>]`
        // ——索引后缀是为了让两个同名项(例如两个都叫 X-Api-Key 的 header)不塌到
        // 同一条 side-table 路径。因此按稳定 ID 反查必须匹配到 `#` 为止:用
        // `"[\(stableId)]"` 对真实路径恒为 false,generic 条目会在删除后全部残留。
        let itemMarker = "[\(stableId)#"
        let json = IosSettingsJsonBridge.shared.encode(settings: snapshot)
        return IOSCredentialRedactor.activeCredentialPaths(in: json)
            .filter { $0.contains(itemMarker) }
            .map(IOSCredentialSideTable.settingsPath)
    }

    var savedSubAgentOverrides: [[String: String]] {
        get { (defaults.array(forKey: subAgentOverridesKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: subAgentOverridesKey) }
    }

    /// [Slice 4] Now merges a real SubAgentOverride into
    /// `snapshot.agentRuntime.subAgent.overrides` via
    /// `IosSettingsMutations.putSubAgentOverride`, then `restoreSnapshot`.
    /// Survives restart. The legacy `savedSubAgentOverrides` mirror is kept in
    /// sync (records roleId for removal) for backward-compatible readers.
    func addSubAgentOverride(roleId: String, systemPrompt: String) {
        let merged = IosSettingsMutations.shared.putSubAgentOverride(
            settings: snapshot,
            roleId: roleId,
            systemPrompt: systemPrompt
        )
        restoreSnapshot(merged)
        var overrides = savedSubAgentOverrides
        overrides.append([
            "id": UUID().uuidString,
            "roleId": roleId,
            "systemPrompt": systemPrompt,
        ])
        savedSubAgentOverrides = overrides
    }

    /// [Slice 4] Removes from BOTH the snapshot (via removeSubAgentOverride by
    /// roleId) and the legacy mirror.
    func removeSubAgentOverride(at index: Int) {
        var overrides = savedSubAgentOverrides
        guard index >= 0 && index < overrides.count else { return }
        let removed = overrides.remove(at: index)
        savedSubAgentOverrides = overrides
        if let roleId = removed["roleId"] {
            let merged = IosSettingsMutations.shared.removeSubAgentOverride(
                settings: snapshot,
                roleId: roleId
            )
            restoreSnapshot(merged)
        }
    }

    // MARK: - Skill enablement write-back

    var currentAssistantEnabledSkillNames: Set<String> {
        Set(IosSettingsMutations.shared.currentAssistantEnabledSkillNames(settings: snapshot))
    }

    func isSkillEnabled(_ name: String) -> Bool {
        currentAssistantEnabledSkillNames.contains(name)
    }

    func setSkillEnabled(name: String, enabled: Bool) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let merged = IosSettingsMutations.shared.setSkillEnabledForCurrentAssistant(
            settings: snapshot,
            skillName: trimmed,
            enabled: enabled
        )
        restoreSnapshot(merged)
    }

    /// Update the current Amber Assistant's generation-prompt fields (Android
    /// AssistantBasicPage/AssistantPromptPage parity). Any nil argument is left
    /// unchanged; the `clear*` flags explicitly null out temperature/topP/maxTokens.
    func updateCurrentAssistantParams(
        systemPrompt: String? = nil,
        messageTemplate: String? = nil,
        temperature: Float? = nil,
        topP: Float? = nil,
        maxTokens: Int? = nil,
        contextMessageSize: Int? = nil,
        clearTemperature: Bool = false,
        clearTopP: Bool = false,
        clearMaxTokens: Bool = false
    ) {
        // Kotlin Float?/Int? bridge to KotlinFloat?/KotlinInt?; wrap the Swift
        // optionals so the KMP helper receives the right boxed types.
        let merged = IosSettingsMutations.shared.updateCurrentAssistantParams(
            settings: snapshot,
            systemPrompt: systemPrompt,
            messageTemplate: messageTemplate,
            temperature: temperature.map { KotlinFloat(value: $0) },
            topP: topP.map { KotlinFloat(value: $0) },
            maxTokens: maxTokens.map { KotlinInt(value: Int32($0)) },
            contextMessageSize: contextMessageSize.map { KotlinInt(value: Int32($0)) },
            clearTemperature: clearTemperature,
            clearTopP: clearTopP,
            clearMaxTokens: clearMaxTokens
        )
        restoreSnapshot(merged)
    }

    /// Update the user nickname (displaySetting.userNickname). Android
    /// ChatDrawer parity — iOS AccountView was preview-only before this.
    func updateUserNickname(_ nickname: String) {
        let merged = IosSettingsMutations.shared.updateUserNickname(settings: snapshot, nickname: nickname)
        restoreSnapshot(merged)
    }

    // MARK: - Mode injections (PromptInjection.ModeInjection) — Android PromptPage parity

    var modeInjections: [PromptInjection.ModeInjection] {
        snapshot.modeInjections
    }

    func upsertModeInjection(id: String, name: String, content: String, enabled: Bool, priority: Int, position: String, role: String) {
        let merged = IosSettingsMutations.shared.upsertModeInjection(
            settings: snapshot, id: id, name: name, content: content,
            enabled: enabled, priority: Int32(priority), position: position, role: role
        )
        restoreSnapshot(merged)
    }

    func deleteModeInjection(id: String) {
        restoreSnapshot(IosSettingsMutations.shared.deleteModeInjection(settings: snapshot, id: id))
    }

    // MARK: - Lorebooks — Android PromptPage parity

    var lorebooks: [Lorebook] {
        snapshot.lorebooks
    }

    func upsertLorebook(id: String, name: String, description: String, enabled: Bool) {
        let merged = IosSettingsMutations.shared.upsertLorebook(
            settings: snapshot, id: id, name: name, description: description, enabled: enabled
        )
        restoreSnapshot(merged)
    }

    func deleteLorebook(id: String) {
        restoreSnapshot(IosSettingsMutations.shared.deleteLorebook(settings: snapshot, id: id))
    }

    func removeSkillFromAllAssistants(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let merged = IosSettingsMutations.shared.removeSkillFromAllAssistants(
            settings: snapshot,
            skillName: trimmed
        )
        restoreSnapshot(merged)
    }

    // MARK: - MiniApp host access write-back

    func setMiniAppHostContextEnabled(_ enabled: Bool) {
        let miniApp = snapshot.agentRuntime.miniApp
        let merged = IosSettingsMutations.shared.setMiniAppHostAccess(
            settings: snapshot,
            hostContextEnabled: enabled,
            hostWriteEnabled: miniApp.hostWriteEnabled
        )
        restoreSnapshot(merged)
    }

    func setMiniAppHostWriteEnabled(_ enabled: Bool) {
        let miniApp = snapshot.agentRuntime.miniApp
        let merged = IosSettingsMutations.shared.setMiniAppHostAccess(
            settings: snapshot,
            hostContextEnabled: miniApp.hostContextEnabled,
            hostWriteEnabled: enabled
        )
        restoreSnapshot(merged)
    }

    func updateMiniAppRuntime(_ transform: (MiniAppSetting) -> MiniAppSettingPatch) {
        let miniApp = snapshot.agentRuntime.miniApp
        let patch = transform(miniApp)
        let merged = IosSettingsMutations.shared.setMiniAppRuntimeOptions(
            settings: snapshot,
            enabled: patch.enabled ?? miniApp.enabled,
            networkEnabled: patch.networkEnabled ?? miniApp.networkEnabled,
            externalImagesEnabled: patch.externalImagesEnabled ?? miniApp.externalImagesEnabled,
            searchEnabled: patch.searchEnabled ?? miniApp.searchEnabled,
            clipboardCopyEnabled: patch.clipboardCopyEnabled ?? miniApp.clipboardCopyEnabled,
            boardSummaryUpdateEnabled: patch.boardSummaryUpdateEnabled ?? miniApp.boardSummaryUpdateEnabled,
            hostContextEnabled: patch.hostContextEnabled ?? miniApp.hostContextEnabled,
            hostWriteEnabled: patch.hostWriteEnabled ?? miniApp.hostWriteEnabled,
            aiEnabled: patch.aiEnabled ?? miniApp.aiEnabled,
            sharedStoreEnabled: patch.sharedStoreEnabled ?? miniApp.sharedStoreEnabled,
            eventBusEnabled: patch.eventBusEnabled ?? miniApp.eventBusEnabled,
            launchEnabled: patch.launchEnabled ?? miniApp.launchEnabled,
            sensorEnabled: patch.sensorEnabled ?? miniApp.sensorEnabled,
            locationEnabled: patch.locationEnabled ?? miniApp.locationEnabled,
            clipboardReadEnabled: patch.clipboardReadEnabled ?? miniApp.clipboardReadEnabled,
            webViewDebugEnabled: patch.webViewDebugEnabled ?? miniApp.webViewDebugEnabled,
            showSourceButton: patch.showSourceButton ?? miniApp.showSourceButton
        )
        restoreSnapshot(merged)
    }

    // MARK: - Today Board / Deep Read write-back

    var todayBoard: TodayBoardSetting {
        snapshot.agentRuntime.todayBoard
    }

    func updateTodayBoard(_ transform: (TodayBoardSetting) -> TodayBoardSettingPatch) {
        let board = snapshot.agentRuntime.todayBoard
        let patch = transform(board)
        let merged = IosSettingsMutations.shared.setTodayBoardOptions(
            settings: snapshot,
            boardModelId: patch.boardModelId ?? board.boardModelId,
            clearBoardModelId: patch.clearBoardModelId,
            hotListRefreshIntervalMinutes: Int32(
                patch.hotListRefreshIntervalMinutes ?? Int(board.hotListRefreshIntervalMinutes)
            ),
            hotListWifiOnly: patch.hotListWifiOnly ?? board.hotListWifiOnly,
            hotListEnabledSources: patch.hotListEnabledSources ?? Array(board.hotListEnabledSources),
            hotListFocusKeywords: patch.hotListFocusKeywords ?? board.hotListFocusKeywords,
            hotListFilterModeWireName: patch.hotListFilterModeWireName ?? board.hotListFilterMode.wireName,
            boardReadingFontModeWireName: patch.boardReadingFontModeWireName ?? board.boardReadingFontMode.wireName,
            boardReadingFontPackId: patch.boardReadingFontPackId ?? board.boardReadingFontPackId,
            clearBoardReadingFontPackId: patch.clearBoardReadingFontPackId,
            deepReadFontScale: patch.deepReadFontScale ?? board.deepReadFontScale,
            deepReadTemplateId: patch.deepReadTemplateId ?? board.deepReadTemplateId,
            hotListTranslateToChinese: patch.hotListTranslateToChinese ?? board.hotListTranslateToChinese
        )
        restoreSnapshot(merged)
    }

    // MARK: - Real seeded collections (for UI display)

    /// Real KMP default TTS providers (DEFAULT_TTS_PROVIDERS), seeded + de-duplicated.
    /// E.g. the SystemTTS default plus cloud-provider templates. Read-only.
    var ttsProviders: [TTSProviderSetting] {
        snapshot.ttsProviders
    }

    /// The id of the seeded default TTS provider (DEFAULT_SYSTEM_TTS_ID).
    var selectedTTSProviderId: KotlinUuid {
        snapshot.selectedTTSProviderId
    }

    /// Real KMP default providers (DEFAULT_PROVIDERS), seeded + de-duplicated.
    /// Read-only preset templates (the Provider registry already consumes these).
    var providers: [ProviderSetting] {
        snapshot.providers
    }

    /// Chat-capable model ids from the real KMP providers snapshot. This is the
    /// list the iOS default-model menu can safely offer because ChatViewModel
    /// already consumes `SettingsStore.modelId` as the request model id.
    var chatModelIds: [String] {
        var seen = Set<String>()
        var ids: [String] = []
        for provider in snapshot.providers {
            for model in provider.models where model.type == ModelType.chat {
                let modelId = model.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !modelId.isEmpty else { continue }
                guard seen.insert(modelId).inserted else { continue }
                ids.append(modelId)
            }
        }
        return ids
    }

    /// Real KMP default assistants (DEFAULT_ASSISTANTS), with branding applied.
    var assistants: [Assistant] {
        snapshot.assistants
    }

    /// Real KMP default search services (a default SearchServiceOptions entry).
    var searchServices: [SearchServiceOptions] {
        snapshot.searchServices
    }

    /// Index of the real seeded default selected search service.
    var searchServiceSelected: Int32 { snapshot.searchServiceSelected }

    /// Real seeded built-in search source toggles + web-search master switch (read-only).
    var enableWebSearch: Bool { snapshot.enableWebSearch }
    var searchBuiltinJinaEnabled: Bool { snapshot.searchBuiltinJinaEnabled }
    var searchBuiltinDuckDuckGoEnabled: Bool { snapshot.searchBuiltinDuckDuckGoEnabled }
    var searchBuiltinBingEnabled: Bool { snapshot.searchBuiltinBingEnabled }
    var searchBuiltinWikipediaEnabled: Bool { snapshot.searchBuiltinWikipediaEnabled }
    var searchBuiltinHackerNewsEnabled: Bool { snapshot.searchBuiltinHackerNewsEnabled }
    var searchGoogleWebViewFallbackEnabled: Bool { snapshot.searchGoogleWebViewFallbackEnabled }

    /// Per-search max result count (SearchCommonOptions.resultSize).
    var searchResultSize: Int { Int(snapshot.searchCommonOptions.resultSize) }

    /// Real default display setting.
    var displaySetting: DisplaySetting {
        snapshot.displaySetting
    }

    /// Real default agent-runtime setting (council/subagent/memory/miniApp/etc.
    /// default config). Read-only display; iOS cannot execute any of these yet.
    var agentRuntime: AgentRuntimeSetting {
        snapshot.agentRuntime
    }

    // MARK: - Scalar model-id pointers (Uuids into providers' models)

    /// Seeded image-generation model pointer (Uuid). NOTE: in the seed snapshot
    /// these modelIds are freshly random Uuids that may not resolve to a real
    /// model in any provider — display as a pointer only, do not assume a label.
    var imageGenerationModelId: KotlinUuid { snapshot.imageGenerationModelId }
    var titleModelId: KotlinUuid { snapshot.titleModelId }
    var suggestionModelId: KotlinUuid { snapshot.suggestionModelId }
    var ocrModelId: KotlinUuid { snapshot.ocrModelId }
    var compressModelId: KotlinUuid { snapshot.compressModelId }
}

struct MiniAppSettingPatch {
    var enabled: Bool?
    var networkEnabled: Bool?
    var externalImagesEnabled: Bool?
    var searchEnabled: Bool?
    var clipboardCopyEnabled: Bool?
    var boardSummaryUpdateEnabled: Bool?
    var hostContextEnabled: Bool?
    var hostWriteEnabled: Bool?
    var aiEnabled: Bool?
    var sharedStoreEnabled: Bool?
    var eventBusEnabled: Bool?
    var launchEnabled: Bool?
    var sensorEnabled: Bool?
    var locationEnabled: Bool?
    var clipboardReadEnabled: Bool?
    var webViewDebugEnabled: Bool?
    var showSourceButton: Bool?

    init(
        enabled: Bool? = nil,
        networkEnabled: Bool? = nil,
        externalImagesEnabled: Bool? = nil,
        searchEnabled: Bool? = nil,
        clipboardCopyEnabled: Bool? = nil,
        boardSummaryUpdateEnabled: Bool? = nil,
        hostContextEnabled: Bool? = nil,
        hostWriteEnabled: Bool? = nil,
        aiEnabled: Bool? = nil,
        sharedStoreEnabled: Bool? = nil,
        eventBusEnabled: Bool? = nil,
        launchEnabled: Bool? = nil,
        sensorEnabled: Bool? = nil,
        locationEnabled: Bool? = nil,
        clipboardReadEnabled: Bool? = nil,
        webViewDebugEnabled: Bool? = nil,
        showSourceButton: Bool? = nil
    ) {
        self.enabled = enabled
        self.networkEnabled = networkEnabled
        self.externalImagesEnabled = externalImagesEnabled
        self.searchEnabled = searchEnabled
        self.clipboardCopyEnabled = clipboardCopyEnabled
        self.boardSummaryUpdateEnabled = boardSummaryUpdateEnabled
        self.hostContextEnabled = hostContextEnabled
        self.hostWriteEnabled = hostWriteEnabled
        self.aiEnabled = aiEnabled
        self.sharedStoreEnabled = sharedStoreEnabled
        self.eventBusEnabled = eventBusEnabled
        self.launchEnabled = launchEnabled
        self.sensorEnabled = sensorEnabled
        self.locationEnabled = locationEnabled
        self.clipboardReadEnabled = clipboardReadEnabled
        self.webViewDebugEnabled = webViewDebugEnabled
        self.showSourceButton = showSourceButton
    }
}

struct TodayBoardSettingPatch {
    var boardModelId: String?
    var clearBoardModelId: Bool
    var hotListRefreshIntervalMinutes: Int?
    var hotListWifiOnly: Bool?
    var hotListEnabledSources: [String]?
    var hotListFocusKeywords: [String]?
    var hotListFilterModeWireName: String?
    var boardReadingFontModeWireName: String?
    var boardReadingFontPackId: String?
    var clearBoardReadingFontPackId: Bool
    var deepReadFontScale: Float?
    var deepReadTemplateId: String?
    var hotListTranslateToChinese: Bool?

    init(
        boardModelId: String? = nil,
        clearBoardModelId: Bool = false,
        hotListRefreshIntervalMinutes: Int? = nil,
        hotListWifiOnly: Bool? = nil,
        hotListEnabledSources: [String]? = nil,
        hotListFocusKeywords: [String]? = nil,
        hotListFilterModeWireName: String? = nil,
        boardReadingFontModeWireName: String? = nil,
        boardReadingFontPackId: String? = nil,
        clearBoardReadingFontPackId: Bool = false,
        deepReadFontScale: Float? = nil,
        deepReadTemplateId: String? = nil,
        hotListTranslateToChinese: Bool? = nil
    ) {
        self.boardModelId = boardModelId
        self.clearBoardModelId = clearBoardModelId
        self.hotListRefreshIntervalMinutes = hotListRefreshIntervalMinutes
        self.hotListWifiOnly = hotListWifiOnly
        self.hotListEnabledSources = hotListEnabledSources
        self.hotListFocusKeywords = hotListFocusKeywords
        self.hotListFilterModeWireName = hotListFilterModeWireName
        self.boardReadingFontModeWireName = boardReadingFontModeWireName
        self.boardReadingFontPackId = boardReadingFontPackId
        self.clearBoardReadingFontPackId = clearBoardReadingFontPackId
        self.deepReadFontScale = deepReadFontScale
        self.deepReadTemplateId = deepReadTemplateId
        self.hotListTranslateToChinese = hotListTranslateToChinese
    }
}

struct IOSRemoteSyncStatus: Codable, Equatable {
    var lastUploadAt: Int64 = 0
    var lastDownloadAt: Int64 = 0
    var remoteRevision: String = ""
    var provider: IOSRemoteProviderKind = .localFolder
    var providerRevisions: [String: String] = [:]
    var deviceLabel: String = ""
    var lastBackupVersionName: String = ""
    var lastBackupVersionCode: Int64 = 0
    var lastError: String = ""

    init() {}

    func remoteRevision(for provider: IOSRemoteProviderKind) -> String {
        providerRevisions[provider.rawValue] ?? (self.provider == provider ? remoteRevision : "")
    }

    func scoped(to provider: IOSRemoteProviderKind) -> IOSRemoteSyncStatus {
        var scoped = self
        scoped.provider = provider
        scoped.remoteRevision = remoteRevision(for: provider)
        return scoped
    }

    mutating func setRemoteRevision(_ revision: String, for provider: IOSRemoteProviderKind) {
        self.provider = provider
        self.remoteRevision = revision
        providerRevisions[provider.rawValue] = revision
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.lastUploadAt = try container.decodeIfPresent(Int64.self, forKey: .lastUploadAt) ?? 0
        self.lastDownloadAt = try container.decodeIfPresent(Int64.self, forKey: .lastDownloadAt) ?? 0
        self.remoteRevision = try container.decodeIfPresent(String.self, forKey: .remoteRevision) ?? ""
        self.provider = try container.decodeIfPresent(IOSRemoteProviderKind.self, forKey: .provider) ?? .localFolder
        self.providerRevisions = try container.decodeIfPresent([String: String].self, forKey: .providerRevisions) ?? [:]
        if !remoteRevision.isEmpty, providerRevisions[provider.rawValue] == nil {
            providerRevisions[provider.rawValue] = remoteRevision
        }
        self.deviceLabel = try container.decodeIfPresent(String.self, forKey: .deviceLabel) ?? ""
        self.lastBackupVersionName = try container.decodeIfPresent(String.self, forKey: .lastBackupVersionName) ?? ""
        self.lastBackupVersionCode = try container.decodeIfPresent(Int64.self, forKey: .lastBackupVersionCode) ?? 0
        self.lastError = try container.decodeIfPresent(String.self, forKey: .lastError) ?? ""
    }
}
