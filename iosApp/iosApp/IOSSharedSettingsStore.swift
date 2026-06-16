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

    private let defaults = UserDefaults.standard
    private let seatsKey = "app.amber.ios.councilSeats"

    init() {
        self.snapshot = IosSettingsDefaults.shared.defaultSeededSettings()
    }

    // MARK: - Council seats write-back

    /// User-customized council seats (persisted to UserDefaults as JSON).
    /// Each seat: [seatId, name, role, modelId, runnerType].
    var savedCouncilSeats: [[String: String]] {
        get { (defaults.array(forKey: seatsKey) as? [[String: String]]) ?? [] }
        set { defaults.set(newValue, forKey: seatsKey) }
    }

    /// Add a council seat to persistent storage.
    func addCouncilSeat(name: String, role: String, modelId: String, runnerType: String = "provider") {
        var seats = savedCouncilSeats
        seats.append([
            "seatId": UUID().uuidString,
            "name": name,
            "role": role,
            "modelId": modelId,
            "runnerType": runnerType,
        ])
        savedCouncilSeats = seats
    }

    /// Remove a council seat by index.
    func removeCouncilSeat(at index: Int) {
        var seats = savedCouncilSeats
        guard index >= 0 && index < seats.count else { return }
        seats.remove(at: index)
        savedCouncilSeats = seats
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
