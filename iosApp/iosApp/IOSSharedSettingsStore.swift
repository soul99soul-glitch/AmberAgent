import Foundation
import Observation
@preconcurrency import Shared

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

    private let defaults: UserDefaults
    private let fullSettingsJsonKey = "app.amber.ios.sharedSettingsJson"
    private let seatsKey = "app.amber.ios.councilSeats"

    init(userDefaults: UserDefaults = .standard) {
        self.defaults = userDefaults
        if let json = defaults.string(forKey: fullSettingsJsonKey),
           let decoded = try? Self.decodeSettings(json) {
            self.snapshot = decoded
        } else {
            self.snapshot = IosSettingsDefaults.shared.defaultSeededSettings()
        }
    }

    func restoreSnapshot(_ settings: Settings) {
        snapshot = settings
        defaults.set(IosSettingsJsonBridge.shared.encode(settings: settings), forKey: fullSettingsJsonKey)
    }

    private static func decodeSettings(_ json: String) throws -> Settings {
        IosSettingsJsonBridge.shared.decode(json: json)
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
    func addCustomModel(name: String, modelId: String, providerName: String = "") {
        var models = savedCustomModels
        models.append([
            "id": UUID().uuidString,
            "name": name,
            "modelId": modelId,
            "providerName": providerName,
        ])
        savedCustomModels = models
    }

    /// Remove a custom model by index.
    func removeCustomModel(at index: Int) {
        var models = savedCustomModels
        guard index >= 0 && index < models.count else { return }
        models.remove(at: index)
        savedCustomModels = models
    }

    // MARK: - Custom search providers write-back

    private let searchProvidersKey = "app.amber.ios.customSearchProviders"

    /// User-customized search provider entries (persisted to UserDefaults).
    var savedSearchProviders: [[String: String]] {
        get { (defaults.array(forKey: searchProvidersKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: searchProvidersKey) }
    }

    /// Add a custom search provider to persistent storage.
    func addSearchProvider(name: String, apiKey: String = "", serviceType: String = "") {
        var providers = savedSearchProviders
        providers.append([
            "id": UUID().uuidString,
            "name": name,
            "apiKey": apiKey,
            "serviceType": serviceType,
        ])
        savedSearchProviders = providers
    }

    /// Remove a custom search provider by index.
    func removeSearchProvider(at index: Int) {
        var providers = savedSearchProviders
        guard index >= 0 && index < providers.count else { return }
        providers.remove(at: index)
        savedSearchProviders = providers
    }

    // MARK: - Custom TTS engines write-back

    private let ttsEnginesKey = "app.amber.ios.customTtsEngines"

    var savedTtsEngines: [[String: String]] {
        get { (defaults.array(forKey: ttsEnginesKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: ttsEnginesKey) }
    }

    func addTtsEngine(name: String, engineType: String, apiKey: String = "", model: String = "") {
        var engines = savedTtsEngines
        engines.append([
            "id": UUID().uuidString,
            "name": name,
            "engineType": engineType,
            "apiKey": apiKey,
            "model": model,
        ])
        savedTtsEngines = engines
    }

    func removeTtsEngine(at index: Int) {
        var engines = savedTtsEngines
        guard index >= 0 && index < engines.count else { return }
        engines.remove(at: index)
        savedTtsEngines = engines
    }

    // MARK: - SubAgent role overrides write-back

    private let subAgentOverridesKey = "app.amber.ios.subAgentOverrides"

    var savedSubAgentOverrides: [[String: String]] {
        get { (defaults.array(forKey: subAgentOverridesKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: subAgentOverridesKey) }
    }

    func addSubAgentOverride(roleId: String, systemPrompt: String) {
        var overrides = savedSubAgentOverrides
        overrides.append([
            "id": UUID().uuidString,
            "roleId": roleId,
            "systemPrompt": systemPrompt,
        ])
        savedSubAgentOverrides = overrides
    }

    func removeSubAgentOverride(at index: Int) {
        var overrides = savedSubAgentOverrides
        guard index >= 0 && index < overrides.count else { return }
        overrides.remove(at: index)
        savedSubAgentOverrides = overrides
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
